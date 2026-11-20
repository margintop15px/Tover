(ns tover.inventory.web
  "Board chapters 13, 14, 16, 17 and 32a · The stock page: the stock table with Units / Money, shared opening drafts
  saved on each cell commit, review of the selected drafts and acceptance, and an item's history below its row.
  The browser side is `stock-workbench` in `stock.js`; every action answers with a command descriptor."
  (:require
    [bel.core :refer [defaction defpage defstream defview]]
    [bel.expressions :refer [->expr]]
    [bel.runtime :as runtime]
    [charred.api :as charred]
    [clojure.string :as str]
    [com.brunobonacci.mulog :as mu]
    [tover.catalogues.records :as records]
    [tover.inventory.acceptance.model :as acceptance]
    [tover.inventory.entries.model :as entries]
    [tover.inventory.references :as references]
    [tover.inventory.stock.model :as stock]
    [tover.inventory.valuation :as valuation]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]
    [tover.workspaces.currency.model :as currency])
  (:import
    [clojure.lang ExceptionInfo]
    [java.time Duration Instant]))


(def ^:private access
  {:tover/access :workspace})


;; Stock table


(defn- amount
  {:malli/schema [:=> [:cat [:maybe decimal?] :string] :string]}
  [value currency]
  (if value (valuation/display-amount value currency) "—"))


(defn- quantity-cell
  {:malli/schema [:=> [:cat :map :string :string] :map]}
  [{:keys [quantity value valued? pending?]} mode currency]
  (cond-> {:value     (if (= "money" mode) (if valued? (amount value currency) "—") (str quantity))
           :className (str "inventory-cell-number" (when pending? " stock-cell-pending"))}
    pending? (assoc :title (i18n/t "Pending changes"))))


(defn- total-cell
  {:malli/schema [:=> [:cat :map :string :string] :map]}
  [{:keys [quantity value valued]} mode currency]
  (if (= "money" mode)
    (cond-> {:value (if (= "none" valued) "—" (amount value currency)) :className "inventory-cell-number stock-total"}
      (= "partial" valued) (assoc :partial true :className "inventory-cell-number stock-total stock-total-partial"
                                  :title (i18n/t "Includes unvalued stock")))
    {:value (str quantity) :className "inventory-cell-number stock-total"}))


(defn- item-label
  {:malli/schema [:=> [:cat :map :string] :string]}
  [{:keys [variant-label article]} product-name]
  (str (or (not-empty variant-label) product-name) (when (not-empty article) (str " · " article))))


(defn stock-grid
  "The stock table as a grid model: a group row per product, a row per stock item, a column per warehouse and Total."
  {:malli/schema [:=> [:cat :map [:map [:mode [:enum "units" "money"]]]] :map]}
  [{:keys [warehouses products currency]} {:keys [mode]}]
  (let [blank (mapv (constantly {:value ""}) warehouses)
        rows  (for [{:keys [product-id name archived? items total]} products
                    row (cons {:id    (str "product:" product-id) :groupId product-id :label name :className "stock-group-row"
                               :cells (-> [{:value (if archived? (str name " " (i18n/t "(archived)")) name)}]
                                          (into blank) (conj (total-cell total mode currency)))}
                              (for [{:keys [stock-item-id cells] :as item} items]
                                {:id    stock-item-id :groupId product-id :label (item-label item name)
                                 :cells (-> [{:value   (item-label item name)
                                              :actions [{:type "item-history" :id stock-item-id :label (i18n/t "History")}]}]
                                            (into (map #(quantity-cell (get cells (:id %)) mode currency)) warehouses)
                                            (conj (total-cell (:total item) mode currency)))}))]
                row)]
    {:id      "stock" :editable false :rowHeaders false :merges [] :editStates []
     :columns (-> [{:key "item" :label (i18n/t "Stock item") :width 280}]
                  (into (map #(hash-map :key (:id %) :label (:name %) :width 120)) warehouses)
                  (conj {:key "total" :label (i18n/t "Total") :width 120}))
     :rows    (vec (map-indexed #(assoc %2 :rowIndex %1 :groupIndex %1) rows))}))


;; Opening drafts


(def ^:private draft-columns
  [["product" "Product" 200] ["variant" "Stock item" 140] ["article" "Article" 120] ["barcodes" "Barcodes" 160]
   ["unit" "Unit" 80] ["warehouse" "Warehouse" 160] ["quantity" "Quantity" 100] ["unit-cost" "Unit cost" 110]
   ["occurred-at" "Occurred at" 180] ["reason" "Reason" 200]])


(def ^:private field-column
  {:stock-item-id "product"   :product-id  "product"     :product-name "product"   :item           "product"   :variant-label "variant"
   :article       "article"   :barcodes    "barcodes"    :warehouse-id "warehouse" :warehouse-name "warehouse" :quantity      "quantity"
   :unit-cost     "unit-cost" :occurred-at "occurred-at"})


(def ^:private problem-text
  {:occurred-at-missing "Occurrence time is missing."              :quantity-missing  "Quantity is missing."
   :warehouse-missing   "Warehouse is missing."                    :item-missing      "Stock item is missing."
   :archived-reference  "Restore the archived record first."       :new-reference     "Will be created on acceptance."
   :similar-reference   "A record with a similar name exists."     :duplicate-variant "This product already has this variant."
   :insufficient-stock  "Not enough stock." :barcode-conflict "This barcode belongs to another product."
   :article-conflict    "This article belongs to another product."})


(defn- draft-values
  "Display text per draft column for the entry's first line."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [products stock-items warehouses]} {:keys [occurred-at reason lines]}]
  (let [line    (first lines)
        item    (some #(when (= (:stock-item-id line) (:stock-item-id %)) %) stock-items)
        product (some #(when (= (or (:product-id item) (:product-id line)) (:product-id %)) %) products)
        store   (some #(when (= (:warehouse-id line) (:warehouse-id %)) %) warehouses)]
    {"product"     (or (:name product) (:product-name line) "")
     "variant"     (or (:variant-label item) (:variant-label line) "")
     "article"     (or (:article item) (:article line) "")
     "barcodes"    (str/join ", " (:barcodes line))
     "unit"        (or (:unit item) (:unit line) "")
     "warehouse"   (or (:name store) (:warehouse-name line) "")
     "quantity"    (str (or (:quantity line) ""))
     "unit-cost"   (or (:unit-cost line) "")
     "occurred-at" (if occurred-at (str occurred-at) "")
     "reason"      (or reason "")}))


(defn- draft-cells
  {:malli/schema [:=> [:cat :string :int :map [:sequential :map]] [:vector :map]]}
  [entry-id revision values problems]
  (let [by-column (group-by #(field-column (:field %) "product") problems)]
    (vec (concat
           [(if (some #(= :blocker (:level %)) problems)
              {:value "" :title (i18n/t "Complete this entry to review it.")}
              {:selection {:id entry-id :label (i18n/t "Select entry")}})]
           (for [[path] draft-columns
                 :let   [found (by-column path)]]
             (cond-> {:value  (get values path "")
                      :editor {:type (if (= "occurred-at" path) "date" "text")}
                      :target {:type "stock-draft" :id entry-id :rowId entry-id :revision revision :path path}}
               (seq found) (assoc :className (if (some #(#{:blocker :error} (:level %)) found) "stock-cell-blocked" "stock-cell-finding")
                                  :title (str/join " " (distinct (map #(i18n/t (problem-text (:code %) "Check this value.")) found))))))
           [{:value   (i18n/t "Pending")
             :actions [{:type "stock-discard" :id entry-id :revision revision :label (i18n/t "Discard") :icon "×"}]}]))))


(defn draft-grid
  "Pending opening drafts as an editable grid; a new row starts from `:draftCells`."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [catalogue {:keys [entries findings]}]
  (let [found (group-by :entry-id findings)]
    {:id         "shared-draft" :editable true :rowHeaders false :merges [] :editStates []
     :columns    (-> [{:key "select" :label "" :width 44}]
                     (into (map (fn [[key label width]] {:key key :label (i18n/t label) :width width})) draft-columns)
                     (conj {:key "status" :label (i18n/t "Status") :width 120}))
     :draftCells (pop (draft-cells "" 0 {} []))
     :rows       (vec (map-indexed
                        (fn [index {:keys [entry-id revision blockers] :as entry}]
                          {:id    entry-id :groupId entry-id :rowIndex index :groupIndex index
                           :cells (draft-cells entry-id revision (draft-values catalogue entry)
                                               (concat (map #(assoc % :level :blocker) blockers) (found entry-id)))})
                        entries))}))


(defn- same-name?
  {:malli/schema [:=> [:cat [:maybe :string] [:maybe :string]] :boolean]}
  [a b]
  (let [k #(some-> % str/trim not-empty (str/replace #"\s+" " ") records/name-key)]
    (boolean (and (k a) (= (k a) (k b))))))


(defn entry-input
  "The `Save Shared Inventory Entry` input for one draft row. Typed names resolve to an active record of exactly that
  name; anything else is the intent to register it on acceptance."
  {:malli/schema [:=> [:cat :map :uuid :string :int :map] :map]}
  [{:keys [products stock-items warehouses]} workspace-id entry-id revision values]
  (let [v         #(some-> (get values %) str/trim not-empty)
        active    #(filter (comp #{"active"} :status) %)
        product   (some #(when (same-name? (v "product") (:name %)) %) (active products))
        item      (when product
                    (some #(when (and (= (:product-id product) (:product-id %))
                                      (= (some-> (v "variant") records/name-key) (some-> (:variant-label %) str/trim not-empty records/name-key)))
                             %)
                          (active stock-items)))
        warehouse (some #(when (same-name? (v "warehouse") (:name %)) %) (active warehouses))
        codes     (some->> (v "barcodes") (re-seq #"[^,\s]+") (remove #{"0"}) vec not-empty)
        line      (cond-> {:line-id (str entry-id "-0")}
                    item (assoc :stock-item-id (:stock-item-id item))
                    (and (not item) product) (assoc :product-id (:product-id product))
                    (and (not item) (not product) (v "product")) (assoc :product-name (v "product"))
                    (and (not item) (v "variant")) (assoc :variant-label (v "variant"))
                    (and (not item) (v "article")) (assoc :article (v "article"))
                    (and (not item) codes) (assoc :barcodes codes)
                    (and (not item) (v "unit")) (assoc :unit (v "unit"))
                    warehouse (assoc :warehouse-id (:warehouse-id warehouse))
                    (and (not warehouse) (v "warehouse")) (assoc :warehouse-name (v "warehouse"))
                    (v "quantity") (assoc :quantity (v "quantity"))
                    (v "unit-cost") (assoc :unit-cost (v "unit-cost")))]
    (cond-> {:workspace-id workspace-id :entry-id entry-id :kind "opening" :lines [line]}
      (pos? revision) (assoc :expected-revision revision)
      (v "occurred-at") (assoc :occurred-at (v "occurred-at"))
      (v "reason") (assoc :reason (v "reason")))))


;; Reads


(defn- read-or-nil
  "A read model's answer, or nil when it cannot be read: unavailable stock is never shown as empty."
  {:malli/schema [:=> [:cat :map :qualified-keyword :map] :any]}
  [context id input]
  (try (runtime/read-model context id input)
       (catch ExceptionInfo error
         (if (or (:status (ex-data error)) (= :forbidden (:type (ex-data error))))
           (throw error)
           (do (mu/log ::read-failed :read-model id :error-type (:type (ex-data error))) nil)))))


(defn- json
  {:malli/schema [:=> [:cat :any] :string]}
  [value]
  (charred/write-json-str value))


(defview table
  {:route-data access :schema [:map [:requestId :string] [:mode [:enum "units" "money"]] [:hideEmpty :boolean]]
   :query      (fn [context input] {:context context :input input})}
  [{:keys [context] {:keys [requestId mode hideEmpty]} :input}]
  {:type :signals
   :data {:_stockTableJson (json {:requestId requestId
                                  :model     (some-> (read-or-nil context ::stock/stock-table {:hide-empty? hideEmpty}) (stock-grid {:mode mode}))})}})


(defview pending
  {:route-data access :schema [:map [:requestId :string]] :query (fn [context input] {:context context :input input})}
  [{:keys [context] {:keys [requestId]} :input}]
  (let [catalogue (read-or-nil context ::references/draft-catalogue {})
        work      (read-or-nil context ::entries/pending-entries {})]
    {:type :signals
     :data {:_stockPendingJson (json {:requestId requestId :model (when (and catalogue work) (draft-grid catalogue work))})}}))


(defn- labels
  "Display names for the ids a review mentions: catalogue records and the registrations it proposes."
  {:malli/schema [:=> [:cat :map [:sequential :map]] :map]}
  [{:keys [products stock-items warehouses]} registrations]
  (let [names (into {} (map (juxt :product-id :name)) products)]
    (merge (into {} (map (juxt :warehouse-id :name)) warehouses)
           (into {} (map (fn [{:keys [stock-item-id product-id variant-label]}]
                           [stock-item-id (str (names product-id) (when (not-empty variant-label) (str " / " variant-label)))]))
                 stock-items)
           (into {} (map (juxt :id :label)) registrations))))


(defview review
  {:route-data access
   :schema     [:map [:requestId :string] [:selection [:vector [:map [:entry-id :string] [:revision :int]]]] [:order [:vector :string]]]
   :query      (fn [context input] {:context context :input input})}
  [{:keys [context] {:keys [requestId selection order]} :input}]
  (let [result    (read-or-nil context ::acceptance/inventory-review {:selection selection :order order})
        catalogue (read-or-nil context ::references/draft-catalogue {})
        label     (when (and result catalogue) (labels catalogue (:registrations result)))
        currency  (or (:currency (read-or-nil context ::currency/workspace-currency {})) "EUR")]
    {:type :signals
     :data {:_stockReviewJson
            (json {:requestId requestId :selection selection :order order
                   :review    (when label
                                (-> result
                                    (update :effects (partial mapv #(assoc % :item (label (:stock-item-id %)) :warehouse (label (:warehouse-id %))
                                                                           :valueBefore (some-> (:value-before %) (amount currency))
                                                                           :valueAfter (some-> (:value-after %) (amount currency)))))
                                    (update :registrations (partial mapv #(update % :type (fn [t] (i18n/t (str/capitalize (str/replace (name t) "-" " ")))))))
                                    (update :problems (partial mapv #(assoc % :message (i18n/t (or (problem-text (:code %)) (second (acceptance/rejection (:code %))) "Check this value.")))))))})}}))


(defview history
  {:route-data access :schema [:map [:requestId :string] [:stockItemId :string] [:days [:maybe :int]]]
   :query      (fn [context input] {:context context :input input})}
  [{:keys [context] {:keys [requestId stockItemId days]} :input}]
  (let [period    (cond-> {:stock-item-id stockItemId} days (assoc :from (.minus (Instant/now) (Duration/ofDays days))))
        rows      (read-or-nil context ::stock/item-history period)
        lineage   (read-or-nil context ::stock/item-lineage period)
        catalogue (read-or-nil context ::references/draft-catalogue {})
        name      (into {} (map (juxt :warehouse-id :name)) (:warehouses catalogue))]
    {:type :signals
     :data {:_stockHistoryJson (json {:requestId requestId :stockItemId stockItemId
                                      :status    (if (and rows lineage) "ok" "failed")
                                      :rows      (mapv #(-> % (update :occurred-at str) (update :recorded-at str)
                                                            (assoc :warehouse (name (:warehouse-id %) (:warehouse-id %))
                                                                   :kind-label (i18n/t (str/capitalize (:kind %)))))
                                                       (:rows rows))
                                      :lineage   (:status lineage)})}}))


(defview saved
  {:route-data access :schema [:map [:commandId [:string {:min 1 :max 100}]]] :signal :_stockSaveJson}
  [{:keys [commandId]}]
  {:commandId commandId :status "ok"})


(defview failed
  {:route-data access :schema [:map [:commandId :string] [:error :map]] :signal :_stockSaveJson}
  [{:keys [commandId error]}]
  {:commandId commandId :status "failed" :type (some-> (:type error) name) :message (i18n/error-message error)})


;; Actions


(defn- descriptor
  {:malli/schema [:=> [:cat :qualified-keyword :string :map] :map]}
  [command command-id input]
  {:type  :command :id command :command-id command-id :input input
   :then  {:type :view :id ::saved :input {:commandId command-id}}
   :catch {:type :view :id ::failed :input {:commandId command-id}}})


(def ^:private CommandId
  [:string {:min 1 :max 100}])


(defaction save-entry
  {:route-data access
   :schema     [:map [:workspaceId :uuid] [:entryId [:string {:min 1 :max 500}]] [:revision [:int {:min 0}]]
                [:values [:map-of [:or :string :keyword] [:string {:max 1000}]]] [:commandId CommandId]]}
  [context {:keys [workspaceId entryId revision values commandId]}]
  ;; Reads the catalogue only to resolve typed names; the command rechecks everything.
  (descriptor ::entries/save-shared-inventory-entry commandId
              (entry-input (runtime/read-model context ::references/draft-catalogue {}) workspaceId entryId revision (update-keys values name))))


(defaction discard-entry
  {:route-data access
   :schema     [:map [:workspaceId :uuid] [:entryId [:string {:min 1 :max 500}]] [:revision [:int {:min 1}]] [:commandId CommandId]]}
  [_ {:keys [workspaceId entryId revision commandId]}]
  (descriptor ::entries/discard-inventory-draft commandId {:workspace-id workspaceId :entry-id entryId :revision revision}))


(defaction accept
  {:route-data access
   :schema     [:map [:workspaceId :uuid] [:selection [:vector [:map [:entry-id :string] [:revision :int]]]] [:order [:vector :string]]
                [:fingerprint :string] [:commandId CommandId]]}
  [_ {:keys [workspaceId selection order fingerprint commandId]}]
  ;; The attempt id is the acceptance id, so a retried attempt recovers the same acceptance.
  (descriptor ::acceptance/accept-reviewed-inventory-changes commandId
              {:workspace-id workspaceId :acceptance-id commandId :selection selection :order order :fingerprint fingerprint}))


(defstream changed
  {:resnapshot-on-open? true}
  [_context _event]
  {:type :signals :data {:inventoryChanged (str (random-uuid))}})


;; Page


(defn query-page
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [request] :as context} _]
  (let [catalogue (read-or-nil context ::references/draft-catalogue {})
        work      (read-or-nil context ::entries/pending-entries {})]
    {:workspace-id (get-in request [:tover/identity :workspace-id])
     :table        (read-or-nil context ::stock/stock-table {:hide-empty? true})
     :pending      (when (and catalogue work) (draft-grid catalogue work))}))


(defn workbench
  {:malli/schema [:=> [:cat :map] schema/Hiccup]}
  [{:keys [workspace-id table pending]}]
  [:stock-workbench.stock-workbench
   {:data-workspace-id       (str workspace-id)
    :data-model              (json (stock-grid table {:mode "units"}))
    :data-pending            (json pending)
    :data-on:stock-query     (->expr (.catch (@view ::table evt.detail {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}) (fn [_] (.requestFailed el "table"))))
    :data-on:stock-pending   (->expr (.catch (@view ::pending evt.detail {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}) (fn [_] (.requestFailed el "pending"))))
    :data-on:stock-review    (->expr (.catch (@view ::review evt.detail {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}) (fn [_] (.requestFailed el "review"))))
    :data-on:stock-history   (->expr (.catch (@view ::history evt.detail {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}) (fn [_] (.requestFailed el "history"))))
    :data-on:stock-save      (->expr (.catch (@action ::save-entry evt.detail {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}) (fn [_] (.requestFailed el "save"))))
    :data-on:stock-discard   (->expr (.catch (@action ::discard-entry evt.detail {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}) (fn [_] (.requestFailed el "save"))))
    :data-on:stock-accept    (->expr (.catch (@action ::accept evt.detail {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}) (fn [_] (.requestFailed el "save"))))
    :data-signals__ifmissing (json {:_stockTableJson   "null" :_stockPendingJson "null" :_stockReviewJson "null"
                                    :_stockHistoryJson "null" :_stockSaveJson    "null" :inventoryChanged ""})
    :data-effect             "el.tableResponse=JSON.parse($_stockTableJson); el.pendingResponse=JSON.parse($_stockPendingJson); el.reviewResponse=JSON.parse($_stockReviewJson); el.historyResponse=JSON.parse($_stockHistoryJson); el.saveResponse=JSON.parse($_stockSaveJson); el.changed=$inventoryChanged"}
   [:div.sheet-toolbar
    [:div.sheet-change-status
     [:label.stock-hide-empty [:input {:type "checkbox" :checked true}] " " (i18n/t "Hide products without stock items")]
     [:div.stock-mode {:role "group" :aria-label (i18n/t "Show")}
      [:button {:type "button" :data-mode "units" :aria-pressed "true"} (i18n/t "Units")]
      [:button {:type "button" :data-mode "money" :aria-pressed "false"} (i18n/t "Money")]]]]
   [:div.stock-table-notice {:role "status"}]
   [:inventory-grid#stock-grid.stock-grid {:data-ignore-morph "" :aria-label (i18n/t "Stock")}]
   [:h2.stock-drafts-title (i18n/t "Pending openings")]
   [:div.sheet-toolbar
    [:div.sheet-change-status [:span.stock-save-status.quiet {:role "status"}]]
    [:div.sheet-actions
     [:button.stock-add {:type "button"} (i18n/t "Add opening")]
     [:button.stock-review-button {:type "button" :disabled true} (i18n/t "Review")]]]
   [:div.stock-notice {:role "status"}]
   [:inventory-grid.stock-drafts {:data-ignore-morph "" :aria-label (i18n/t "Pending openings")}]
   [:section.stock-review {:hidden true :aria-label (i18n/t "Review")}]
   [:div#history-parking {:hidden true}]])


(defpage page
  {:route "/" :title "Stock · Tover" :parent :tover.shared.web.core/shell :route-data access :query query-page}
  [{:keys [table] :as props}]
  {:type :elements
   :html (if table
           (workbench props)
           [:p.management-empty {:role "alert"} (i18n/t "Stock could not be loaded.") " " [:a {:href "/"} (i18n/t "Retry")]])})
