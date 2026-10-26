(ns tover.inventory.workbench
  (:require
    [bel.expressions :refer [->expr]]
    [bel.handler :as html]
    [bel.validation :as validation]
    [charred.api :as charred]
    [clojure.string :as str]
    [tover.inventory.filters :as filtering]
    [tover.inventory.grid :as grid]
    [tover.inventory.layouts :as layouts]
    [tover.inventory.read-model :as read-model]
    [tover.inventory.read-query :as read-query]
    [tover.inventory.smart :as smart]
    [tover.inventory.store :as store]
    [tover.inventory.tables :as tables]
    [tover.inventory.timeline :as timeline]
    [tover.inventory.windows :as windows]
    [tover.operations.ui :as operations-ui]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as shared]))


(def operation-types
  [[:purchase "Purchase"] [:return "Return"] [:transfer "Transfer"] [:production "Production"]
   [:defect "Defect"] [:sale "Sale"] [:write-off "Write-off"] [:payment "Payment"]])


(defn label
  {:malli/schema [:=> [:cat :any] :string]}
  [value]
  (i18n/t (or (some #(when (= (first %) value) (second %)) operation-types)
              (some-> value name (str/replace "-" " ") str/capitalize) (i18n/t "Operation"))))


(defn item-catalog
  {:malli/schema [:=> [:cat :map] [:map-of :string :map]]}
  [{:keys [items drafts]}]
  (merge
    (into {}
          (for [draft drafts
                line  (tree-seq coll? seq (:entry draft))
                :when (and (map? line) (string? (:item-id line)) (not (str/blank? (:item-id line)))
                           (string? (:name line)) (not (str/blank? (:name line))))]
            [(:item-id line) (assoc (select-keys line [:name :article :barcode :unit]) :id (:item-id line))]))
    items))


(defn stock-rows
  "Show durable pending edits over accepted rows; acceptance still uses the ledger snapshot."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [snapshot]
  (read-model/stock-rows snapshot))


(defn layout-settings
  {:malli/schema [:=> [:cat :map] :map]}
  [context]
  {:column-layouts (layouts/load-all context)
   :layout-scope   (some-> (:user-id context) str)})


(defn configured-grid
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [column-layouts layout-scope]} model]
  (cond-> (layouts/apply-layout model (get column-layouts (:id model) {}))
    layout-scope (assoc :layoutScope layout-scope)))


(declare accepted-grid)


(defn- visible-drafts
  {:malli/schema [:=> [:cat :map :map] :map]}
  [context state]
  (let [drafts (sort-by (juxt :saved-at :id) (vals (:drafts state)))
        owner  (str (:user-id context))]
    {:ledger-revision (:ledger-revision state)
     :generation      (:generation state)
     :epoch           (:epoch state)
     :drafts          (vec (remove #(= :composing (:phase %)) drafts))
     :compositions    (filterv #(and (= :composing (:phase %)) (= owner (:owner-id %))) drafts)}))


(defn- referenced-ids
  {:malli/schema [:=> [:cat :map :keyword] [:vector :string]]}
  [snapshot field]
  (into [] (comp (filter map?) (keep field) (filter string?) (distinct))
        (tree-seq coll? seq (select-keys snapshot [:drafts :compositions]))))


(defn draft-snapshot
  "Load shared drafts and only their referenced catalog entries."
  {:malli/schema [:=> [:cat :map] :map]}
  [context]
  (store/read-snapshot (:repo context) context
                       (fn [conn state]
                         (let [snapshot      (visible-drafts context state)
                               items         (into {} (mapcat #(read-query/items conn context %) (partition-all 500 (referenced-ids snapshot :item-id))))
                               operation-ids (vec (distinct (concat (referenced-ids snapshot :target-id) (referenced-ids snapshot :original-operation-id))))
                               operations    (vec (mapcat #(read-query/operations conn context %) (partition-all 500 operation-ids)))]
                           (assoc snapshot :items (item-catalog (assoc snapshot :items items))
                                  :rows [] :history [] :operations operations :operation-catalog operations)))))


(defn- query-window
  {:malli/schema [:=> [:cat :any :map :map :map] [:maybe :map]]}
  [conn context query input]
  (when-let [page (read-query/page conn context (merge query input))]
    (let [operation? (= :operations (:view query))
          rows       (if operation?
                       (mapv #(assoc % :operation (operations-ui/operation-title %)) (:rows page))
                       (:rows page))
          references (when operation?
                       (read-query/operations conn context (vec (distinct (keep :original-operation-id rows)))))
          sheet      (if operation?
                       (tables/operations rows {:column-keys (:columns page) :operation-catalog references})
                       (tables/stock rows))
          grouped    (partition-by :groupId (:rows sheet))
          indexed    (mapcat (fn [group-index group]
                               (map #(assoc % :groupIndex group-index) group))
                             (range (:groupStart page) (+ (:groupStart page) (count grouped))) grouped)
          states     (mapcat :cells (:rows (tables/stock (:pendingRows page))))]
      (-> sheet
          (dissoc :details)
          (assoc :rows (mapv #(assoc %1 :rowIndex %2) indexed (range (:start page) (:end page)))
                 :editStates (mapv #(select-keys % [:target :value]) (filter :target states))
                 :missingRows (:missingRows page)
                 :viewport (select-keys page [:totalRows :totalGroups :start :end :groupStart :anchorFound :detailFound]))
          (with-meta {:source-rows rows})))))


(defn query-stock
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [repo] :as context} {:keys [view filters filterRequest query sort direction operationType]
                               :or   {view :stock query "" sort "name" operationType ""}             :as input}]
  ;; Keep token registration inside the read/write monitor, so an old read cannot
  ;; register a live token after the write that invalidated its generation.
  (locking repo
    (let [view         (keyword view)
          direction    (or direction (if (= :operations view) "desc" "asc"))
          sort         (if (contains? (if (= :operations view)
                                        #{"occurred-at" "type" "party" "summary" "context" "items" "reason"}
                                        #{"name" "article" "barcode" "unit" "location" "quantity"}) sort)
                         sort (if (= :operations view) "occurred-at" "name"))
          filters      (filtering/check! view (or filters {}))
          query-spec   {:view view :filters filters :sort sort :direction direction :operationType operationType}
          snapshot     (draft-snapshot context)
          query-spec   (assoc query-spec :generation (:generation snapshot) :epoch (:epoch snapshot))
          read-window  (fn [range-input]
                         (store/read-snapshot repo context
                                              (fn [conn _] (query-window conn context query-spec range-input))))
          first-model  (read-window {:start       0 :count windows/window-size
                                     :pendingRows (if-let [id (:ackRowId input)] [id] [])})
          grid         (windows/initial! context
                                         {:queryKey   (charred/write-json-str [(name view) filters sort direction operationType])
                                          :generation [(:epoch snapshot) (:generation snapshot)] :first-model first-model :read read-window
                                          :contains   (fn [id]
                                                        (store/read-snapshot repo context
                                                                             (fn [conn _] (read-query/contains-group? conn context query-spec id))))})
          visible-rows (:source-rows (meta first-model))
          model        (merge snapshot (layout-settings context)
                              {:view           view :rows (if (= :stock view) visible-rows [])
                               :operations     (if (= :operations view) visible-rows [])
                               :operation-rows (if (= :operations view) visible-rows [])})]
      {:model         model :context context :input input :view view :filters filters :filterRequest (or filterRequest 0)
       :initial?      (not (contains? input :filterRequest)) :query query :sort sort :direction direction :operationType operationType
       :accepted-grid (configured-grid model grid)})))


(defn refresh-input
  {:malli/schema [:=> [:cat :keyword] :map]}
  [view]
  {:view (name view) :query "$query"})


(defn human-time
  {:malli/schema [:=> [:cat :map] shared/Hiccup]}
  [{:keys [value]}]
  [:time {:datetime (str value) :data-human-date ""} "—"])


(defn draft-panel
  {:malli/schema [:=> [:cat :map :keyword] :any]}
  [_model _view]
  [:section#shared-draft {:data-show "($_draftPreview && $_draftIds.length > 0) || $_unresolvedDraftEdits > 0" :style "display:none"}
   [:h2.inventory-grid-heading (i18n/t "Changes to submit")]
   [:inventory-grid#pending-draft-grid
    {:aria-label               (i18n/t "Changes to submit") :data-ignore-morph ""
     :data-effect              "el.model = JSON.parse($_draftGridJson || 'null'); el.selectedEntries = $_selectedEntries; el.saveStatus = $saveStatus; el.busy = $_reviewPending"
     :data-on:review-selection "$_selectedEntries = evt.detail.selected; $_review = null; $_reviewDirty = true"}]])


(defn header
  {:malli/schema [:=> [:cat :map] :any]}
  [{:keys [view]}]
  (smart/entry
    {:view        view
     :init        "el.model = JSON.parse($_smartModelJson || 'null'); el.saveStatus = $saveStatus"
     :on-suggest  (->expr (@action :tover.inventory.smart-web/suggest
                                   {:state evt.detail.state :text evt.detail.text :filters   $_filters  :filterRequest $_filterRequest
                                    :view  $_inventoryView  :sort $sort           :direction $direction :request       evt.detail.request}
                                   {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}))
     :on-complete (->expr (set! $saveStatus "Saving") (set! $_filterRequest (+ $_filterRequest 1))
                    (@action :tover.inventory.smart-web/complete
                             {:state evt.detail.state :text evt.detail.text :option    evt.detail.option :filters $_filters          :filterRequest $_filterRequest
                              :view  $_inventoryView  :sort $sort           :direction $direction        :request evt.detail.request}
                             {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}))}))


(defn filter-strip
  {:malli/schema [:=> [:cat :map] shared/Hiccup]}
  [{:keys [view filters on-remove on-reset on-edit]}]
  (let [labels (into {} (map (juxt :key :label) (filtering/columns view)))
        names  {"empty"  (i18n/t "is blank")    "notEmpty" (i18n/t "is not blank") "eq"          (i18n/t "equals")           "notEq" (i18n/t "does not equal")
                "begins" (i18n/t "begins with") "contains" (i18n/t "contains")     "notContains" (i18n/t "does not contain")}]
    [:div.inventory-filter-strip {:aria-label (i18n/t "Active filters") :hidden (empty? filters)}
     (for [[column conditions] (sort-by key filters)]
       [:div.inventory-filter-group {:data-column column :role "group" :aria-label (str (get labels column column) (i18n/t " filters"))}
        [:strong (get labels column column)]
        (map-indexed
          (fn [index condition]
            [:span.inventory-filter-condition
             (when (pos? index) [:span.inventory-filter-connector (i18n/t (str/upper-case (or (:relation (nth conditions (dec index))) "and")))])
             [:button {:type       "button" :data-column column :data-simple (str (not (filtering/complex? filters column))) :data-attr:disabled "$saveStatus === 'Saving'"
                       :aria-label (i18n/t "Edit {field} filter" {:field (get labels column column)}) :data-on:click on-edit}
              (get names (:type condition) (:type condition))
              (when-not (#{"empty" "notEmpty"} (:type condition))
                (if (and (= column "occurred-at") (re-matches #".+T.+(?:Z|[+-]\d\d:\d\d)" (str (:value condition))))
                  [:span " “" (human-time {:value (:value condition)}) "”"]
                  (str " “" (:value condition) "”")))]
             [:button {:type       "button" :data-column column :data-condition-id (:id condition) :data-attr:disabled "$saveStatus === 'Saving'"
                       :aria-label (i18n/t "Remove {field} filter {index}" {:field (get labels column column) :index (inc index)}) :data-on:click on-remove} "×"]]) conditions)])
     [:button.inventory-filter-reset {:type "button" :data-attr:disabled "$saveStatus === 'Saving'" :data-on:click on-reset} (i18n/t "Reset all")]]))


(defn filter-strip-html
  {:malli/schema [:=> [:cat :keyword :map] :string]}
  [view filters]
  (html/render-html
    (filter-strip
      {:view      view :filters filters
       :on-remove (->expr (set! $saveStatus "Saving") (set! $_filterRequest (+ $_filterRequest 1))
                    (@action :tover.stock.web/update-filters
                             {:command "remove"  :column        el.dataset.column :conditionId el.dataset.conditionId
                              :filters $_filters :filterRequest $_filterRequest   :view        $_inventoryView        :sort $sort :direction $direction}
                             {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}))
       :on-reset  (->expr (set! $saveStatus "Saving") (set! $_filterRequest (+ $_filterRequest 1))
                    (@action :tover.stock.web/update-filters
                             {:command "reset" :filters $_filters :filterRequest $_filterRequest :view $_inventoryView :sort $sort :direction $direction}
                             {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}))
       :on-edit   "const root=el.closest('#inventory-evidence-workbench'); if(el.dataset.simple==='true'){root.querySelector('stock-entry').editFilter(el.dataset.column)}else{root.querySelector('#operations-history,#stock-grid').openColumnFilter(el.dataset.column)}"})))


(defn stock-grid-model
  "Keep saved edit states available when the server filter hides their rows."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [rows stock-catalog]}]
  (let [visible (set (map :id rows))
        hidden  (remove #(visible (:id %)) stock-catalog)]
    (assoc (tables/stock (or rows []))
           :editStates (mapv #(select-keys % [:target :value])
                             (filter :target (mapcat :cells (:rows (tables/stock hidden))))))))


(defn accepted-grid
  "Open an immutable read window; pure component fixtures can still supply a complete model."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [context model view filters sort direction operationType input] :as props}]
  (or (:accepted-grid props)
      (let [build (fn []
                    (if (= :operations view)
                      (tables/operations (mapv #(dissoc % :detail) (:operation-rows model)))
                      (stock-grid-model model)))
            grid  (if (and (:repo context) (uuid? (:user-id context)) (uuid? (:workspace-id context)))
                    (windows/initial! context
                                      {:queryKey    (charred/write-json-str [(name view) filters sort direction operationType])
                                       :generation  [(:ledger-revision model)
                                                     (when (= :stock view) (mapv (juxt :id :revision) (:drafts model)))]
                                       :pendingRows (if-let [row-id (:ackRowId input)] [row-id] [])
                                       :build       build})
                    (build))]
        (configured-grid model grid))))


(defn view-snapshot
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [model view filters filterRequest] :as props}]
  (let [grid   (accepted-grid props)
        ids    (set (map :id (:rows grid)))
        groups (set (map :groupId (:rows grid)))]
    ;; Scalars replace whole query results; recursive signal patches retain omitted map keys.
    {:id                 (str (random-uuid)) :view                  (name view) :request (or filterRequest 0) :filtersJson (charred/write-json-str (or filters {}))
     :rows               (if (= :stock view) (filterv #(ids (:id %)) (:rows model)) [])
     :operationRows      (if (= :operations view)
                           (mapv #(select-keys % [:id :operation :type]) (filter #(groups (:id %)) (:operation-rows model))) [])
     :draftIds           (mapv :id (:drafts model)) :draftSelection (mapv #(select-keys % [:id :revision]) (:drafts model))
     :ledgerRevision     (:ledger-revision model 0)
     :columnLayoutsJson  (charred/write-json-str (:column-layouts model {}))
     :layoutScope        (:layout-scope model)
     :filterHtml         (filter-strip-html view (or filters {}))
     :draftGridJson      (charred/write-json-str (configured-grid model (grid/drafts model)))
     :stockGridJson      (charred/write-json-str (when (= :stock view) grid))
     :operationsGridJson (charred/write-json-str (when (= :operations view) grid))}))


(defn sheet-icon
  {:malli/schema [:=> [:cat :keyword] :any]}
  [name]
  [:svg {:viewBox        "0 0 24 24" :fill            "none"  :stroke      "currentColor" :stroke-width 1.6
         :stroke-linecap "round"     :stroke-linejoin "round" :aria-hidden true}
   (case name
     :modified [:path {:d "m16 3 5 5-12 12-6 1 1-6L16 3Z m-2 2 5 5"}]
     :deleted [:path {:d "M5 12h14"}]
     :added [:path {:d "M5 12h14 M12 5v14"}]
     :revert [:path {:d "m9 4-5 5 5 5 M4 9h10a5.5 5.5 0 0 1 0 11h-3"}]
     :preview [:g [:path {:d "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z"}]
               [:circle {:cx 12 :cy 12 :r 3}]]
     :submit [:path {:d "M12 21V3 m-7 7 7-7 7 7"}]
     :refresh [:path {:d "M20 10a8 8 0 1 0-2 8 M20 4v6h-6"}]
     :close [:path {:d "m6 6 12 12 M6 18 18 6"}])])


(defn change-toolbar
  {:malli/schema [:=> [:cat :map :keyword] :any]}
  [{:keys [drafts]} view]
  (let [counts  (frequencies (map #(case (get-in % [:entry :kind])
                                     (:correction :revision) :modified
                                     :cancellation :deleted
                                     :added) drafts))
        preview (->expr (set! $_reviewPending true)
                  (@action :tover.stock.web/preview
                           {:selected (if (.-length $_selectedEntries) $_selectedEntries $_draftIds)
                            :view     ~(name view) :query $query :filters $_filters :filterRequest $_filterRequest :sort $sort :direction $direction :operationType $operationType}
                           {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}))
        toggle  (str "if ($_draftPreview) { "
                     (->expr (@action :tover.stock.web/close-preview {} {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}))
                     " } else { " preview " }")]
    [:div#inventory-toolbar.sheet-toolbar
     [:div.sheet-change-status {:aria-live "polite"}
      (if (seq drafts)
        (for [[kind title] [[:modified (i18n/t "modified")] [:deleted (i18n/t "cancelled")] [:added (i18n/t "added")]]]
          [:span.sheet-count {:class      (str "is-" (name kind)) :role "img"
                              :title      (i18n/t "{count} entries {status}" {:count (get counts kind 0) :status title})
                              :aria-label (i18n/t "{count} entries {status}" {:count (get counts kind 0) :status title})}
           (sheet-icon kind) [:span {:aria-hidden true} (get counts kind 0)]])
        [:span.quiet (i18n/t "No changes")])
      [:span#inventory-save-status.quiet {:role "status" :data-show "$saveStatus !== 'Saved'" :data-text "window.toverTranslate($saveStatus)"}]]
     [:div.sheet-actions
      [:button.sheet-add-row {:type "button" :data-attr:disabled "$saveStatus === 'Saving'" :data-on:click
                              (if (= view :operations)
                                "el.closest('#inventory-evidence-workbench').querySelector('stock-entry').beginAdd()"
                                (->expr (@action :tover.stock.web/add-entry
                                                 {:kind "opening" :view "stock" :query $query :filters $_filters :filterRequest $_filterRequest :sort $sort :direction $direction :operationType $operationType}
                                                 {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0})))}
       (sheet-icon :added) (i18n/t "Add row")]
      [:button.sheet-icon-button {:type               "button" :title (i18n/t "Revert all changes") :aria-label (i18n/t "Revert all changes") :disabled true
                                  :data-attr:disabled "!$_draftPreview || $_reviewPending || $_draftIds.length === 0 || $saveStatus === 'Saving'"
                                  :data-on:click      (->expr (@action :tover.stock.web/revert-drafts
                                                                       {:selected $_draftSelection :view ~(name view) :query $query :filters $_filters :filterRequest $_filterRequest :sort $sort :direction $direction :operationType $operationType}
                                                                       {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}))}
       (sheet-icon :revert)]
      [:button.sheet-icon-button {:type                 "button" :title (i18n/t "Preview changes") :aria-label (i18n/t "Preview changes")                  :disabled (empty? drafts)
                                  :data-attr:disabled   "$_reviewPending || $_draftIds.length === 0 || $saveStatus === 'Saving'"
                                  :data-class:is-active "$_draftPreview" :data-attr:aria-pressed "$_draftPreview ? 'true' : 'false'" :data-on:click toggle}
       (sheet-icon :preview)]
      [:button.sheet-icon-button.is-submit
       {:type               "button" :title (i18n/t "Submit reviewed changes") :aria-label (i18n/t "Submit changes") :disabled true
        :data-attr:disabled "$_reviewPending || !$_review || $_draftIds.length === 0 || $saveStatus !== 'Saved'"
        :data-on:click
        (->expr (when (and $_review (not $_reviewPending) (= $saveStatus "Saved"))
                  (set! $saveStatus "Saving")
                  (@action :tover.stock.web/submit
                           {:review $_review :view ~(name view) :query $query :filters $_filters :filterRequest $_filterRequest :sort $sort :direction $direction :operationType $operationType}
                           {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0})))}
       (sheet-icon :submit)]]]))


(defn grid-events
  {:malli/schema [:=> [:cat :keyword] :map]}
  [view]
  {:on-sort    (->expr (set! $sort evt.detail.column) (set! $direction evt.detail.direction) (set! $_filterRequest (+ $_filterRequest 1))
                 (@view :tover.stock.web/stock {:view ~(name view) :filters $_filters :filterRequest $_filterRequest :sort $sort :direction $direction}
                        {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}))
   :on-filter  (->expr (set! $_filters evt.detail.filters) (set! $_filterRequest (+ $_filterRequest 1))
                 (@view :tover.stock.web/stock {:view ~(name view) :filters $_filters :filterRequest $_filterRequest :sort $sort :direction $direction}
                        {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}))
   :on-edit    (->expr (set! $saveStatus "Saving")
                 (@action :tover.stock.web/edit-stock
                          {:rowId          evt.detail.rowId          :field         evt.detail.field         :value     evt.detail.value
                           :draftId        evt.detail.draftId        :draftRevision evt.detail.draftRevision :draftKind evt.detail.draftKind
                           :ledgerRevision evt.detail.ledgerRevision :view          "stock"                  :filters   $_filters            :filterRequest $_filterRequest :sort $sort :direction $direction}
                          {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}))
   :on-history (str "const h=document.getElementById('history-panel'), requestId=crypto.randomUUID(); h.dataset.requestId=requestId; "
                    (->expr (if evt.detail.expanded
                              (@action :tover.stock.web/close-history
                                       {:rowId evt.detail.rowId :requestId (js* "requestId")}
                                       {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0})
                              (@action :tover.stock.web/open-history
                                       {:itemId evt.detail.itemId :rowId evt.detail.rowId :requestId (js* "requestId") :mode "history"}
                                       {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}))))
   :on-revise  (->expr (set! $_draftPreview true) (set! $saveStatus "Saving")
                 (@action :tover.stock.web/revise-entry
                          {:targetId evt.detail.targetId :kind evt.detail.kind :view ~(name view) :filters $_filters :filterRequest $_filterRequest :sort $sort :direction $direction}
                          {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}))})


(defn stock-sheet
  {:malli/schema [:=> [:cat :map] shared/Hiccup]}
  [{:keys [init on-sort on-filter on-edit on-history]}]
  [:section.stock-sheet {:aria-label (i18n/t "Stock spreadsheet")}
   [:div.sheet-grid-host
    [:inventory-grid#stock-grid {:aria-label         (i18n/t "Stock spreadsheet") :data-ignore-morph ""         :data-inventory-grid      ""
                                 :data-effect        init :data-on:inventory-sort                    on-sort    :data-on:inventory-filter on-filter
                                 :data-on:stock-edit on-edit :data-on:item-history                   on-history}]]
   [:div#history-parking {:hidden true :data-ignore-morph ""}
    [:section#history-panel {:data-ignore-morph ""}]]])


(defn stock-page
  {:malli/schema [:=> [:cat :map] :any]}
  [{:keys [model view query sort direction operationType initial?] :as props}]
  [:div#inventory-evidence-workbench.inventory-workbench
   {:data-view (name view) :data-attr:data-filter-request "$_filterRequest"
    :aria-label (i18n/t "Tover inventory workbench")
    :data-signals__ifmissing             (validation/signal-json :map {:query              (or query "")         :sort             (or sort "name") :direction            (or direction "asc")
                                                                       :operationType      (or operationType "") :_inventoryView   (name view)      :_selectedEntries     []                   :_review            nil
                                                                       :_draftPreview      false                 :_reviewPending   false            :_reviewDirty         false                :_draftGridJson     "null" :_stockGridJson  "null" :_operationsGridJson   "null" :_draftIds [] :_draftSelection [] :_smartModelJson "null" :_operationRows []
                                                                       :saveStatus         "Saved"               :inventoryChanged ""               :_stockRows           []
                                                                       :_itemOptionsJson   "null"                :_gridWindowJson  "null"           :_operationDetailJson "null"
                                                                       :_columnLayoutsJson "{}"                  :_layoutStatus    "Saved"          :_layoutScope         nil
                                                                       :_filters           {}                    :_acceptedFilters {}               :_filterRequest       0                    :_inventorySnapshot nil    :_ledgerRevision 0      :_unresolvedDraftEdits 0})
    :data-init (str "$_inventoryView = " (charred/write-json-str (name view)) "; $sort = " (charred/write-json-str (or sort "name"))
                    "; $direction = " (charred/write-json-str (or direction "asc"))
                    (when initial? "; $_filterRequest = 0; $_filters = {}; $_acceptedFilters = {}")
                    "; $_inventorySnapshot = " (charred/write-json-str (view-snapshot props))
                    (when initial? (str "; $_smartModelJson = " (charred/write-json-str (charred/write-json-str (smart/initial-model model (or (:filters props) {}))))))
                    "; $_draftIds = " (charred/write-json-str (mapv :id (:drafts model)))
                    "; $_draftSelection = " (charred/write-json-str (mapv #(select-keys % [:id :revision]) (:drafts model))))
    :data-effect (str "const s=$_inventorySnapshot; if(s && s.view===$_inventoryView && s.request===$_filterRequest && el.dataset.snapshotId!==s.id){el.dataset.snapshotId=s.id; $_ledgerRevision=s.ledgerRevision; $_stockRows=s.rows; $_operationRows=s.operationRows; $_draftIds=s.draftIds; $_draftSelection=s.draftSelection; $_selectedEntries=$_selectedEntries.filter(id=>s.draftIds.includes(id)); $_draftGridJson=s.draftGridJson; $_stockGridJson=s.stockGridJson; $_operationsGridJson=s.operationsGridJson; $_columnLayoutsJson=s.columnLayoutsJson; if($_layoutScope!==s.layoutScope){$_layoutScope=s.layoutScope; $_layoutStatus='Saved'}; $_acceptedFilters=JSON.parse(s.filtersJson); $_filters=JSON.parse(s.filtersJson); if($_draftPreview){$_review=null; $_reviewDirty=true}; const strip=el.querySelector('#inventory-filters'); if(strip && strip.innerHTML!==s.filterHtml) strip.innerHTML=s.filterHtml}; const layouts=JSON.parse($_columnLayoutsJson || '{}'); for(const g of el.querySelectorAll('inventory-grid')){if(g.model?.id){g.columnLayout=layouts[g.model.id] || {widths:{},order:[],version:0}; g.layoutStatus=$_layoutStatus}}; for(const g of el.querySelectorAll('inventory-grid')){g.itemOptionsResponse=JSON.parse($_itemOptionsJson || 'null');g.referenceOptionsResponse=JSON.parse($_referenceOptionsJson || 'null')}; const host=el.querySelector('#operations-history,#stock-grid'); if(host){host.windowResponse=JSON.parse($_gridWindowJson || 'null'); host.operationDetailResponse=JSON.parse($_operationDetailJson || 'null')}; if($_review && ($_review['expected-ledger-revision']!==$_ledgerRevision || $_review.selected.some(r=>!$_draftSelection.some(d=>d.id===r.id && d.revision===r.revision)))){$_review=null; $_reviewDirty=true}; "
                      (->expr (when (and $_draftPreview $_reviewDirty (not $_reviewPending) (= $saveStatus "Saved"))
                                (set! $_reviewDirty false) (set! $_reviewPending true)
                                (@action :tover.stock.web/preview
                                         {:selected $_selectedEntries :view $_inventoryView :filters $_filters :filterRequest $_filterRequest :sort $sort :direction $direction :operationType $operationType}
                                         {:requestCancellation "auto" :retry "never" :retryMaxCount 0})))
                      "; "
                      (->expr (when (and $inventoryChanged (not= $saveStatus "Saving"))
                                (set! $inventoryChanged "") (set! $_filterRequest (+ $_filterRequest 1))
                                (.catch (@view :tover.stock.web/stock {:view ~(name view) :query $query :filters $_filters :filterRequest $_filterRequest :sort $sort :direction $direction :operationType $operationType}
                                               {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0})
                                        (fn [_error] nil)))))
    :data-on:inventory-resnapshot        (->expr (set! $inventoryChanged (js* "String(Date.now())")))
    :data-on:inventory-reference-options
    (->expr (.catch (@view :tover.stock.web/reference-options
                           {:requestId evt.detail.requestId :text evt.detail.text}
                           {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0})
                    (fn [_error]
                      (set! $_referenceOptionsJson (js* "JSON.stringify({requestId:evt.detail.requestId,status:'failed',options:[]})")))))
    :data-on:inventory-item-options
    (->expr (.catch (@view :tover.stock.web/item-options
                           {:requestId evt.detail.requestId :text evt.detail.text}
                           {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0})
                    (fn [_error]
                      (set! $_itemOptionsJson (js* "JSON.stringify({requestId:evt.detail.requestId,status:'failed',options:[]})")))))
    :data-on:inventory-window
    (->expr (.catch (@view :tover.stock.web/grid-window
                           {:queryId     evt.detail.queryId     :start       evt.detail.start       :count     evt.detail.count     :anchorId evt.detail.anchorId
                            :pendingRows evt.detail.pendingRows :detailRowId evt.detail.detailRowId :requestId evt.detail.requestId :view     $_inventoryView}
                           {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0})
                    (fn [_error]
                      (set! $_gridWindowJson (js* "JSON.stringify({status:'failed',queryId:evt.detail.queryId,requestId:evt.detail.requestId,view:evt.detail.view})")))))
    :data-on:operation-detail-request
    (->expr (.catch (@view :tover.stock.web/operation-details
                           {:queryId evt.detail.queryId :operationId evt.detail.operationId :requestId evt.detail.requestId}
                           {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0})
                    (fn [_error]
                      (set! $_operationDetailJson (js* "JSON.stringify({status:'failed',queryId:evt.detail.queryId,requestId:evt.detail.requestId,operationId:evt.detail.operationId})")))))
    :data-on:inventory-layout
    (->expr (set! $_layoutStatus "Saving")
      (.catch (@action :tover.stock.web/save-layout
                       {:gridId evt.detail.gridId :widths evt.detail.widths :order evt.detail.order}
                       {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0})
              (fn [_error] (set! $_layoutStatus "Failed"))))
    :data-on:inventory-conflict-resolved "if(!evt.detail.remaining){$saveStatus='Saved'; $_review=null; $_reviewDirty=true; const n=el.querySelector('#inventory-notice'); n.replaceChildren(); n.classList.remove('error'); n.setAttribute('role','status')}"
    :data-on:inventory-unresolved        "if(evt.target.id==='pending-draft-grid') $_unresolvedDraftEdits=evt.detail.count"
    :data-on:inventory-row-detail-close  "const h=document.getElementById('history-panel'); if(h){h.removeAttribute('data-init'); delete h.dataset.rowId; h.dataset.requestId=crypto.randomUUID(); h.replaceChildren()}"
    :data-on:draft-edit
    (->expr (set! $saveStatus "Saving") (@action :tover.stock.web/save-field
                                                 {:id   evt.detail.id :revision evt.detail.revision :path    evt.detail.path :value         evt.detail.value
                                                  :view ~(name view)  :query    $query              :filters $_filters       :filterRequest $_filterRequest  :sort $sort :direction $direction :operationType $operationType}
                                                 {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}))
    :data-on:draft-command
    (->expr (set! $saveStatus "Saving") (@action :tover.stock.web/draft-command
                                                 {:id   evt.detail.id :revision evt.detail.revision :command evt.detail.command :path          evt.detail.path
                                                  :view ~(name view)  :query    $query              :filters $_filters          :filterRequest $_filterRequest :sort $sort :direction $direction :operationType $operationType}
                                                 {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}))}
   (header {:view view})
   [:div#inventory-filters {:data-ignore-morph ""}]
   [:div#inventory-notice {:role "status" :data-ignore-morph ""}]
   (change-toolbar model view)
   (draft-panel model view)
   [:section#inventory-review {:data-ignore-morph ""}]
   (if (= :operations view)
     (operations-ui/sheet (assoc (grid-events view) :init "el.model = JSON.parse($_operationsGridJson || 'null'); el.entries = JSON.parse(JSON.stringify($_operationRows)); el.acceptedSort = {column:$sort,direction:$direction}; el.acceptedFilters = JSON.parse(JSON.stringify($_acceptedFilters)); el.saveStatus = $saveStatus"))
     (stock-sheet (assoc (grid-events view) :init "el.model = JSON.parse($_stockGridJson || 'null'); el.acceptedSort = {column:$sort,direction:$direction}; el.acceptedFilters = JSON.parse(JSON.stringify($_acceptedFilters)); el.saveStatus = $saveStatus")))])


(defn preview-panel
  {:malli/schema [:=> [:cat :map :keyword :string] :any]}
  [preview _view _query]
  [:section#inventory-review.sheet-preview {:aria-label (i18n/t "Selected changes review") :data-ignore-morph ""}
   (if (:valid? preview)
     [:div
      [:h2.inventory-grid-heading (i18n/t "Resulting stock")]
      (let [model (tables/resulting-stock preview)]
        (if (seq (:rows model))
          [:inventory-grid#resulting-stock-grid
           {:aria-label (i18n/t "Resulting stock") :data-init (str "el.model = " (charred/write-json-str (configured-grid preview model)))}]
          [:p.quiet.inventory-grid-empty (i18n/t "No stock changes.")]))
      [:div.sheet-preview-actions
       [:button.sheet-submit-preview {:data-attr:disabled "$_reviewPending || !$_review || $saveStatus !== 'Saved'" :type "button" :data-on:click
                                      (->expr (when (and $_review (not $_reviewPending) (= $saveStatus "Saved"))
                                                (set! $saveStatus "Saving")
                                                (@action :tover.stock.web/submit {:review $_review :view $_inventoryView :query $query :filters $_filters :filterRequest $_filterRequest :sort $sort :direction $direction :operationType $operationType}
                                                         {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0})))} (i18n/t "Submit selected")]]]
     [:div {:role "alert"} [:p (i18n/t "Resolve these conflicts before submitting.")]
      [:ul (for [error (:errors preview)]
             [:li (i18n/error-message error)
              (when (:entry-id error)
                [:button.inventory-error-edit {:type "button" :data-on:click
                                               (str "el.closest('#inventory-evidence-workbench').querySelector('#pending-draft-grid').focusField("
                                                    (charred/write-json-str (:entry-id error)) ","
                                                    (charred/write-json-str (str/join "/" (map #(if (keyword? %) (name %) (str %)) (:path error)))) ")")} (i18n/t "Edit field")])
              [:span.subline
               (when (:occurred-at error) (human-time {:value (:occurred-at error)}))
               (str (when (:location error) (str " · " (:location error)))
                    (when (contains? error :before) (str (i18n/t " · available ") (:before error)))
                    (when (:quantity error) (str (i18n/t " · requested ") (:quantity error))))]])]])])


(defn timeline-chart
  {:malli/schema [:=> [:cat :map] :any]}
  [history]
  (timeline/chart history))


(defn history-request
  {:malli/schema [:=> [:cat :string :string :string] :string]}
  [item-id row-id mode]
  (str "const h=el.closest('#history-panel'), requestId=crypto.randomUUID(); h.dataset.requestId=requestId; "
       (->expr (@action :tover.stock.web/open-history
                        {:itemId      ~item-id :rowId ~row-id :requestId (js* "requestId") :mode ~mode
                         :operationId (js* "h.dataset.operationId || ''") :revision (js* "Number(h.dataset.operationRevision || 0)")}
                        {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}))))


(defn history-operation
  {:malli/schema [:=> [:cat :map] shared/Hiccup]}
  [{:keys [operation-id revision] :as detail}]
  [:section#history-operation-details.inventory-history-operation
   {:data-ignore-morph "" :data-operation-id operation-id :data-operation-revision revision
    :data-init         "const p=el.closest('#history-panel'); p.dataset.operationId=el.dataset.operationId; p.dataset.operationRevision=el.dataset.operationRevision; const trigger=document.activeElement?.closest('.inventory-timeline button'); if(trigger && p.contains(trigger)) p._historyTrigger=trigger; el.querySelector('h3').focus({preventScroll:true})"}
   (timeline/operation-details
     (assoc detail :on-close
            (str "const h=el.closest('#history-panel'), requestId=crypto.randomUUID(); h.dataset.requestId=requestId; "
                 (->expr (@action :tover.stock.web/close-history-operation {:requestId (js* "requestId")}
                                  {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0})))))])


(defn history-panel
  {:malli/schema [:=> [:cat :map] shared/Hiccup]}
  [{:keys [item row-id request-id mode detail operation-id revision] :as history}]
  [:section#history-panel.inventory-history
   {:aria-label      (i18n/t "Item history") :data-ignore-morph ""   :data-row-id       row-id       :data-item-id            (:id item)
    :data-request-id request-id :data-history-mode              mode :data-operation-id operation-id :data-operation-revision revision
    :data-init       "const g=document.getElementById('stock-grid'); if(g) g.rowDetail={rowId:el.dataset.rowId,node:el}"}
   [:header
    [:h2 (str (:name item) (i18n/t " · history"))]
    [:div.inventory-history-tabs {:role "tablist" :aria-label (i18n/t "Item history view")}
     (for [[value title] [["history" (i18n/t "History")] ["lineage" (i18n/t "Lineage")]]]
       [:button {:type          "button" :role "tab" :aria-selected (str (= mode value))
                 :data-on:click (history-request (:id item) row-id value)} title])]
    [:button.sheet-icon-button {:type          "button" :title (i18n/t "Refresh history") :aria-label (i18n/t "Refresh history")
                                :data-on:click (history-request (:id item) row-id mode)} (sheet-icon :refresh)]
    [:button.sheet-icon-button
     {:type "button" :title (i18n/t "Close history") :aria-label (i18n/t "Close history")
      :data-on:click
      (str "const h=el.closest('#history-panel'), requestId=crypto.randomUUID(); h.dataset.requestId=requestId; "
           (->expr (@action :tover.stock.web/close-history {:rowId ~row-id :requestId (js* "requestId")}
                            {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0})))}
     (sheet-icon :close)]]
   (if (= "lineage" mode)
     [:div.inventory-history-lineage
      (timeline-chart history)
      (if detail (history-operation detail) [:section#history-operation-details {:data-ignore-morph ""}])]
     [:inventory-grid#item-history-grid
      {:aria-label (i18n/t "Item history records") :data-init (str "el.model = " (charred/write-json-str (configured-grid history (tables/history history))))}])])
