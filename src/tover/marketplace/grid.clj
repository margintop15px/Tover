(ns tover.marketplace.grid
  "One table workbench over `inventory-grid` for the marketplace pages (board chapters 42–46). The server sends
  authoritative rows; cell edits stay in the browser on their row until a selection action sends the selected
  rows as one command. An accepted command refreshes the table; a rejected one keeps every attempted value and
  shows each rejected row's reason on that row. The browser side is `marketplace-workbench` in `marketplace.js`;
  the look is the catalogue workbench's (`catalogues.grid`)."
  (:require
    [bel.core :refer [defview]]
    [bel.expressions :refer [->expr]]
    [bel.nav :as nav]
    [charred.api :as charred]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]))


(def Option
  [:map [:value :string] [:label :string] [:revision {:optional true} :int]])


(def Column
  "`:reference` is a picker searched through the page's choices view (`:lookup` names what it searches); with
  `:new-name?` a typed text that matches no choice is kept as a new name."
  [:map
   [:key :keyword] [:label :string] [:type [:enum :text :lines :select :reference]]
   [:editable? {:optional true} :boolean] [:options {:optional true} [:vector Option]]
   [:width {:optional true} :int] [:lookup {:optional true} :string] [:new-name? {:optional true} :boolean]])


(def Row
  "Cell values by column key: text, a vector of lines, an option value, or `{:value :label}` for a reference.
  Row `:options` override a column's options for that row; `:href` opens the row's details page; `:links` are
  further pages a column's cell opens; `:revisions` are further reviewed revisions the row sends with its command,
  and `:observation` the evidence it was read from."
  [:map
   [:id :string] [:revision :int] [:label {:optional true} :string]
   [:revisions {:optional true} [:map-of :keyword :int]]
   [:observation {:optional true} [:string {:max 200}]]
   [:cells [:map-of :keyword :any]]
   [:findings {:optional true} [:map-of :keyword :string]]
   [:options {:optional true} [:map-of :keyword [:vector Option]]]
   [:selectable? {:optional true} :boolean]
   [:href {:optional true} :string]
   [:links {:optional true} [:map-of :keyword [:vector [:map [:href :string] [:label :string]]]]]])


(def SelectionAction
  "`:each?` sends one command per selected row, one after another, for commands about one record."
  [:map [:command :string] [:label :string] [:primary? {:optional true} :boolean] [:reason? {:optional true} :boolean]
   [:each? {:optional true} :boolean]])


(def Filter
  [:map [:label :string] [:href :string] [:current? {:optional true} :boolean]])


(defn json
  {:malli/schema [:=> [:cat :any] :string]}
  [value]
  (charred/write-json-str value))


(defn- base-cell
  {:malli/schema [:=> [:cat Column :any [:maybe [:vector Option]]] :map]}
  [{:keys [type]} value options]
  (case type
    :lines {:value "" :lines (mapv (fn [line] {:value line}) value)}
    ;; The current value is always among the options, so its revision is sent with it.
    :reference {:value  (or (:value value) "") :display (or (:label value) "")
                :editor {:type    "catalogue-reference" :valueType "id"
                         :options (cond-> (vec (or options []))
                                    (and (seq (:value value)) (not-any? #(= (:value value) (:value %)) options))
                                    (conj (select-keys value [:value :label :revision])))}}
    :select {:value   (or value "")
             :display (or (some #(when (= (:value %) value) (:label %)) options) "")
             :editor  {:type "select" :options (vec (or options []))}}
    {:value (str (or value "")) :editor {:type "text"}}))


(defn cell
  "One grid cell. Only editable columns carry a target; a finding marks the cell and reads as its title."
  {:malli/schema [:=> [:cat Column Row] :map]}
  [{:keys [key type editable? options lookup new-name?] :as column} {:keys [id revision cells findings] :as row}]
  (let [options (get-in row [:options key] options)
        finding (get findings key)
        base    (base-cell column (get cells key) options)]
    (cond-> (if (and editable? (not= :lines type))
              (assoc base :target (cond-> {:type "marketplace-cell" :id id :rowId id :revision revision :path (name key)}
                                    lookup (assoc :lookupKind lookup)
                                    new-name? (assoc :newName true)))
              (-> base (dissoc :editor) (assoc :readOnly true)))
      finding (assoc :className "marketplace-finding" :title finding))))


(defn model
  "The inventory-grid model: a selection column first when the table has selection actions."
  {:malli/schema [:=> [:cat [:map [:id :string] [:columns [:vector Column]] [:rows [:vector Row]]
                             [:selection-actions {:optional true} [:vector SelectionAction]]]]
                  :map]}
  [{:keys [id columns rows selection-actions]}]
  (let [select? (boolean (seq selection-actions))]
    {:id      id :editable true :rowHeaders false :merges [] :editStates []
     :columns (cond->> (mapv (fn [{:keys [key label width]}] {:key (name key) :label (i18n/t label) :width (or width 160)}) columns)
                select? (into [{:key "select" :label "" :width 40}]))
     :rows    (vec (map-indexed
                     (fn [index {:keys [id revision revisions observation label href links selectable?] :or {selectable? true} :as row}]
                       (let [cells (mapv (fn [column]
                                           (cond-> (cell column row)
                                             (seq (get links (:key column)))
                                             (assoc :actions (mapv (fn [{:keys [href label]}] {:type "marketplace-open" :href href :icon "›" :label label})
                                                                   (get links (:key column))))))
                                         columns)
                             cells (cond-> cells
                                     href (update 0 assoc :actions [{:type  "marketplace-open"      :href href :icon "›"
                                                                     :label (i18n/t "Open details")}]))]
                         (cond-> {:id    id :groupId id :label (or label id) :revision revision :rowIndex index :groupIndex index
                                  :cells (cond->> cells
                                           select? (into [(if selectable?
                                                            {:selection {:id id :label (i18n/t "Select {name}" {:name (or label id)})}}
                                                            {:value "" :readOnly true})]))}
                           revisions (assoc :revisions revisions)
                           observation (assoc :observation observation))))
                     rows))}))


(defn table-signal
  "What a page's query view answers: the rows for `requestId`, or a failure."
  {:malli/schema [:=> [:cat :string [:maybe :map]] :map]}
  [request-id model]
  {:type :signals
   :data {:_marketplaceTableJson (json {:requestId request-id :status (if model "ok" "failed") :model model})}})


(defn choices-signal
  {:malli/schema [:=> [:cat :string [:maybe [:vector :map]]] :map]}
  [request-id options]
  {:type :signals
   :data {:_marketplaceChoicesJson (json {:requestId request-id :status (if options "ok" "failed") :options (or options [])})}})


(defn rejected
  "A command refused before it runs, answered like a rejected command."
  {:malli/schema [:=> [:cat :string :string [:* :any]] :map]}
  [command-id message & {:keys [rows]}]
  {:type :signals
   :data {:_marketplaceCommandJson (json {:commandId command-id :status "failed" :message message :rows (or rows [])})}})


(defview committed
  {:route-data {:tover/access :workspace} :schema [:map [:commandId [:string {:min 1 :max 100}]]] :signal :_marketplaceCommandJson}
  [{:keys [commandId]}]
  {:commandId commandId :status "ok"})


(def ^:private parameterized
  "Row codes whose message names the barcode, product or Ozon warehouse it is about."
  {"barcode-conflict"    "Barcode {barcode} belongs to {product}."
   "uniqueness-conflict" "A barcode or article belongs to {product}."
   "already-used"        "Already used for another Ozon warehouse: {warehouse}."})


(defn row-message
  {:malli/schema [:=> [:cat :map] :string]}
  [{:keys [code message owner-product other-warehouse barcode]}]
  (if-let [key (and (or owner-product other-warehouse) (get parameterized (some-> code name)))]
    (i18n/t key {:barcode (or barcode "") :product (or owner-product "") :warehouse (or other-warehouse "")})
    (i18n/t (or message "The request could not be completed."))))


(defview failed
  "A rejected command: every rejected row with its reason, placed on the column its code names."
  {:route-data {:tover/access :workspace}
   :schema     [:map [:commandId :string] [:idKey :string] [:path :string] [:paths {:optional true} [:map-of :any :string]]
                [:error :map]]
   :signal     :_marketplaceCommandJson}
  [{:keys [commandId idKey path paths error]}]
  (let [paths (update-keys (or paths {}) name)]
    {:commandId commandId :status "failed" :message (i18n/error-message error)
     :rows      (mapv (fn [row] {:id      (str (get row (keyword idKey)))
                                 :path    (get paths (some-> (:code row) name) path)
                                 :message (row-message row)})
                      (:rows error))}))


(defn workbench
  "The table with its filters and selection actions. `query` is what every refresh sends to `query-view`;
  `command-action` receives `{:command :commandId :rows [{:id :revision :values}] :reason?}` plus `query`."
  {:malli/schema [:=> [:cat [:map [:id :string] [:label :string] [:columns [:vector Column]] [:rows [:vector Row]]
                             [:query :map] [:query-view :qualified-keyword] [:command-action :qualified-keyword]
                             [:choices-view {:optional true} :qualified-keyword]
                             [:filters {:optional true} [:vector Filter]]
                             [:selection-actions {:optional true} [:vector SelectionAction]]]]
                  schema/Hiccup]}
  [{:keys [id label query query-view command-action choices-view filters selection-actions] :as props}]
  [:marketplace-workbench.catalogue-workbench.marketplace-workbench
   (cond-> {:id id
            :data-model                  (json (model props))
            :data-query                  (json query)
            :data-on:marketplace-query   (->expr (.catch (@view ~query-view evt.detail {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}) (fn [_] (.requestFailed el "read"))))
            :data-on:marketplace-command (->expr (.catch (@action ~command-action evt.detail {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}) (fn [_] (.requestFailed el "command"))))
            :data-signals__ifmissing     (json {:_marketplaceTableJson   "null" :_marketplaceChoicesJson "null"
                                                :_marketplaceCommandJson "null" :inventoryChanged        ""})
            :data-effect                 "el.tableResponse=JSON.parse($_marketplaceTableJson); el.choicesResponse=JSON.parse($_marketplaceChoicesJson); el.commandResponse=JSON.parse($_marketplaceCommandJson); el.changed=$inventoryChanged"}
     choices-view (assoc :data-on:marketplace-choices
                         (->expr (.catch (@view ~choices-view evt.detail {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}) (fn [_] (.requestFailed el "choices"))))))
   [:div.sheet-toolbar
    [:div.sheet-change-status
     (when (seq filters)
       [:nav.catalogue-kinds {:aria-label (i18n/t "Filter")}
        (for [{:keys [label href current?]} filters]
          [:a (nav/link href {:aria-current (when current? "page")}) (i18n/t label)])])
     [:span.marketplace-selected.quiet {:role "status"}]]
    (when (seq selection-actions)
      [:div.sheet-actions
       (for [{:keys [command label primary? reason? each?]} selection-actions]
         (list
           (when reason?
             [:input.marketplace-reason {:type        "text" :maxlength 500 :data-command-reason command
                                         :placeholder (i18n/t "Reason (optional)") :aria-label (i18n/t "Reason (optional)")}])
           [:button (cond-> {:type  "button" :data-command command :disabled true
                             :class (if primary? "catalogue-save-new" "catalogue-discard-new")}
                      each? (assoc :data-each "true"))
            (i18n/t label)]))])]
   [:div.catalogue-notice {:role "status"}]
   [:inventory-grid.catalogue-grid.marketplace-grid {:id (str id "-grid") :data-ignore-morph "" :aria-label (i18n/t label)}]])
