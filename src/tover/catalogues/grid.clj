(ns tover.catalogues.grid
  "Board chapters 08–12 on the Catalogues pages: each catalogue kind as an editable grid in the stock-page
  frame. A new row registers a record, a cell edit updates one field and the Status cell archives, restores
  or (products only) deletes. The browser side is the shared `catalogue-workbench` adapter; every edit
  answers with the authoritative row. Kinds differ only by the `kinds` config."
  (:require
    [bel.core :refer [defaction defpage defview]]
    [bel.expressions :refer [->expr]]
    [bel.runtime :as runtime]
    [charred.api :as charred]
    [clojure.string :as str]
    [com.brunobonacci.mulog :as mu]
    [tover.catalogues.categories.model :as categories]
    [tover.catalogues.products.model :as products]
    [tover.catalogues.records :as records]
    [tover.catalogues.stores.model :as stores]
    [tover.catalogues.suppliers.model :as suppliers]
    [tover.catalogues.warehouses.model :as warehouses]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]
    [tover.shared.ui.core :as ui])
  (:import
    [clojure.lang ExceptionInfo]))


(def ^:private access
  {:tover/access :workspace})


(defn- product-rows
  "Product rows carry their relationships under the field keys, like every other kind."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [{:keys [products]}]
  (mapv #(assoc % :id (:product-id %) :category-id (:category %) :default-warehouse-id (:default-warehouse %)) products))


(defn purchased
  "Board chapter 18 · Supplier history: per supplier, the stock items bought from it in accepted purchases, grouped
  by product, as `Product: variant, variant; Product: …`."
  {:malli/schema [:=> [:cat [:sequential :map] :map] :map]}
  [operations {:keys [products stock-items]}]
  (let [product (into {} (map (juxt :product-id :name)) products)
        item    (into {} (map (juxt :stock-item-id identity)) stock-items)]
    (into {} (for [[supplier ops] (group-by :supplier-id (filter #(= "purchase" (:kind %)) operations))
                   :let           [items (keep item (distinct (mapcat #(map :stock-item-id (:lines %)) ops)))]]
               [supplier (str/join "; " (for [[p is] (sort-by (comp records/name-key str product key) (group-by :product-id items))]
                                          (let [variants (sort (keep #(not-empty (:variant-label %)) is))]
                                            (str (product p) (when (seq variants) (str ": " (str/join ", " variants)))))))]))))


(defn- with-purchases
  "Supplier rows with what was bought from them; without it when the history cannot be read."
  {:malli/schema [:=> [:cat :map [:vector :map]] [:vector :map]]}
  [context rows]
  (let [bought (try (purchased (runtime/read-model context :tover.inventory.acceptance.model/accepted-operations {})
                               (runtime/read-model context :tover.inventory.references/draft-catalogue {}))
                    (catch ExceptionInfo error
                      (if (:status (ex-data error)) (throw error) (do (mu/log ::purchases-unavailable :message (ex-message error)) {}))))]
    (mapv #(assoc % :purchased (bought (:id %))) rows)))


(def ^:private purposes
  [["storage" "Storage"] ["sales" "Sales"] ["production" "Production"]])


(def kinds
  "kind → read model, how to read one record, commands `[register update archive restore delete]`, columns."
  (array-map
    "products" {:title    "Products" :read-model ::products/product-catalogue :rows product-rows :by-id :product-id :id-key :product-id
                :commands [::products/register-product ::products/update-product ::products/archive-product
                           ::products/restore-product ::products/delete-product]
                :columns  [{:key :name :label "Name" :width 280}
                           {:key :category-id :label "Category" :width 200 :lookup "categories"}
                           {:key :default-warehouse-id :label "Default warehouse" :width 200 :lookup "warehouses"}]}
    "warehouses" {:title    "Warehouses" :read-model ::warehouses/warehouse-catalogue :rows :records :by-id :id :id-key :warehouse-id
                  :commands [::warehouses/register-warehouse ::warehouses/update-warehouse ::warehouses/archive-warehouse
                             ::warehouses/restore-warehouse]
                  :columns  [{:key :name :label "Name" :width 280} {:key :description :label "Description" :width 280}
                             {:key :purpose :label "Purpose" :width 160 :options purposes}]}
    "suppliers" {:title    "Suppliers" :read-model ::suppliers/supplier-catalogue :rows :records :by-id :id :id-key :supplier-id
                 :extra    with-purchases
                 :commands [::suppliers/register-supplier ::suppliers/update-supplier ::suppliers/archive-supplier
                            ::suppliers/restore-supplier]
                 :columns  [{:key :name :label "Name" :width 280} {:key :address :label "Address" :width 280}
                            {:key :contact :label "Contact information" :width 240}
                            {:key :purchased :label "Purchased" :width 320 :read-only true}]}
    "categories" {:title    "Categories" :read-model ::categories/category-catalogue :rows :records :by-id :id :id-key :category-id
                  :commands [::categories/register-category ::categories/update-category ::categories/archive-category
                             ::categories/restore-category]
                  :columns  [{:key :name :label "Name" :width 280}]}
    "stores" {:title    "Stores" :read-model ::stores/store-catalogue :rows :records :by-id :id :id-key :store-id
              :commands [::stores/register-store ::stores/update-store ::stores/archive-store ::stores/restore-store]
              :columns  [{:key :name :label "Name" :width 280}
                         {:key :default-warehouse-id :label "Default warehouse" :width 200 :lookup "warehouses"}]}))


(def Kind
  (into [:enum] (keys kinds)))


(defn- href
  {:malli/schema [:=> [:cat :string :boolean] :string]}
  [kind archived?]
  (str "/catalogues" (when-not (= "products" kind) (str "/" kind)) (when archived? "?archived=true")))


(defn- reference-cell
  "An id cell showing the record's name; a saved record that was archived since stays and is marked."
  {:malli/schema [:=> [:cat [:maybe :map]] :map]}
  [{:keys [id name archived?]}]
  {:value   (or id "")
   :display (cond (nil? id) "" archived? (str name " " (i18n/t "(archived)")) :else name)
   :editor  {:type "catalogue-reference" :valueType "id" :options (if id [{:value id :label name}] [])}})


(defn- select-cell
  {:malli/schema [:=> [:cat [:maybe :string] [:vector [:tuple :string :string]]] :map]}
  [value options]
  {:value   (or value "")
   :display (some (fn [[option label]] (when (= option value) (i18n/t label))) options)
   :editor  {:type "select" :allowEmpty true :options (into [{:value "" :label (i18n/t "None")}]
                                                            (map (fn [[option label]] {:value option :label (i18n/t label)}))
                                                            options)}})


(defn- status-cell
  "Active and archived swap; Delete is offered only where the kind allows it and the record may go."
  {:malli/schema [:=> [:cat :map :boolean] :map]}
  [{:keys [status can-delete?]} delete?]
  {:value   status
   :display (i18n/t (if (= "archived" status) "Archived" "Active"))
   :editor  {:type    "select"
             :options (cond-> [{:value "active" :label (i18n/t "Active")} {:value "archived" :label (i18n/t "Archived")}]
                        (and delete? can-delete?) (conj {:value "deleted" :label (i18n/t "Delete")}))}})


(defn grid-row
  {:malli/schema [:=> [:cat Kind :map] :map]}
  [kind {:keys [id revision] :as row}]
  (let [{:keys [columns commands]} (get kinds kind)
        target (fn [path] {:type "catalogue-edit" :id id :rowId id :revision revision :path path :kind kind})]
    {:id    id :groupId id :label (:name row)
     :cells (conj (mapv (fn [{:keys [key lookup options read-only]}]
                          (let [value (get row key)]
                            (if read-only
                              {:value (or value "") :readOnly true}
                              (assoc (cond lookup (reference-cell value)
                                           options (select-cell value options)
                                           :else {:value (or value "") :editor {:type "text"}})
                                     :target (cond-> (target (name key)) lookup (assoc :lookupKind lookup))))))
                        columns)
                  (assoc (status-cell row (= 5 (count commands))) :target (target "status")))}))


(defn- sort-text
  {:malli/schema [:=> [:cat :any] :string]}
  [value]
  (records/name-key (str (if (map? value) (:name value) value))))


(defn grid
  "The grid model for `rows` of `kind`, filtered by the search text and sorted by a column."
  {:malli/schema [:=> [:cat Kind [:vector :map] :map] :map]}
  [kind rows {:keys [query sort direction]}]
  (let [{:keys [columns]} (get kinds kind)
        sort-key          (or (some #(when (= sort (name (:key %))) (:key %)) columns) :name)
        text              (some-> query str/trim str/lower-case not-empty)
        rows              (cond->> (sort-by (comp sort-text sort-key) rows)
                            text (filter #(str/includes? (records/name-key (:name %)) text))
                            (= "desc" direction) reverse)]
    {:id         (str "catalogue-" kind) :editable true :rowHeaders true :merges []
     :columns    (mapv (fn [{:keys [key label width]}] {:key (name key) :label (i18n/t label) :width width :sortKey (name key)})
                       (conj columns {:key :status :label "Status" :width 140}))
     ;; Empty cells a new row starts from; the browser keeps it as a draft until Save.
     :draftCells (pop (:cells (grid-row kind {:id "" :revision 0 :status "active"})))
     :rows       (vec (map-indexed (fn [index row] (assoc (grid-row kind row) :rowIndex index :groupIndex index)) rows))
     :editStates []}))


(defn- read-rows
  "Rows of `kind`, or nil when they cannot be read: unavailable information is never shown as an empty grid."
  {:malli/schema [:=> [:cat :map Kind :map] [:maybe [:vector :map]]]}
  [context kind input]
  (let [{:keys [read-model rows extra] :or {extra (fn [_ found] found)}} (get kinds kind)]
    (try (extra context (rows (runtime/read-model context read-model input)))
         (catch ExceptionInfo error
           (if (:status (ex-data error))
             (throw error)
             (do (mu/log ::read-failed :kind kind :error-type (:type (ex-data error)) :message (ex-message error)) nil))))))


(def Query
  [:map [:kind Kind] [:requestId :string] [:archived {:optional true} :boolean]
   [:query {:optional true} [:string {:max 500}]] [:sort {:optional true} :string] [:direction {:optional true} [:enum "asc" "desc"]]])


(defview table
  {:route-data access :schema Query :query (fn [context input] {:context context :input input})}
  [{:keys [context] {:keys [kind] :as input} :input}]
  {:type :signals
   :data {:_catalogueResponseJson
          (charred/write-json-str {:requestId (:requestId input) :kind kind
                                   :model     (some-> (read-rows context kind {:archived? (boolean (:archived input))}) (as-> rows (grid kind rows input)))})}})


(defview choices
  {:route-data access
   :schema     [:map [:kind [:enum "categories" "warehouses"]] [:text [:string {:max 500}]] [:requestId :string]]
   :query      (fn [context input] {:context context :input input})}
  [{:keys [context] {:keys [kind text requestId]} :input}]
  (let [text (records/name-key text)
        rows (read-rows context kind {:archived? false})]
    {:type :signals
     :data {:_catalogueChoicesJson
            (charred/write-json-str {:requestId requestId :status (if rows "ok" "failed")
                                     :options   (->> rows
                                                     (filter #(str/includes? (records/name-key (:name %)) text))
                                                     (mapv (fn [{:keys [id name]}] {:value id :label name})))})}}))


(defview saved
  {:route-data access :schema [:map [:kind Kind] [:id :string] [:commandId [:string {:min 1 :max 100}]]]
   :query      (fn [context input] {:context context :input input})}
  [{:keys [context] {:keys [kind id commandId]} :input}]
  (let [row (first (read-rows context kind {(get-in kinds [kind :by-id]) id}))]
    {:type :signals
     :data {:_catalogueSaveJson (charred/write-json-str {:commandId commandId :kind kind :status "ok"
                                                         :row       (when row {:id (:id row) :revision (:revision row)})
                                                         :gridRow   (some->> row (grid-row kind))})}}))


(defview failed
  {:route-data access :schema [:map [:kind Kind] [:commandId :string] [:error :map]] :signal :_catalogueSaveJson}
  [{:keys [kind commandId error]}]
  {:commandId commandId :kind kind :status "failed" :current (:current error) :message (i18n/error-message error)})


(def Save
  [:map
   [:kind Kind] [:id [:string {:min 1 :max 500}]] [:revision [:int {:min 0}]] [:path [:string {:max 100}]]
   [:value [:string {:max 1000}]] [:values {:optional true} [:map-of :any [:string {:max 1000}]]] [:referenceName {:optional true} [:string {:min 1 :max 500}]] [:commandId [:string {:min 1 :max 100}]]])


(defn command
  "The command for one edited cell, or nil when the kind has no such field or transition. Revision 0 is a
  new row saved explicitly: it registers with every filled field in `values`."
  {:malli/schema [:=> [:cat :map] [:maybe [:tuple :qualified-keyword :map]]]}
  [{:keys [kind id revision path value values]}]
  (let [{:keys [id-key columns] [register update archive restore delete] :commands} (get kinds kind)
        field (some #(when (= path (name (:key %))) (:key %)) columns)]
    (cond
      (zero? revision) [register (into {id-key id :name (get (update-keys values name) "name" "")}
                                       (keep (fn [{:keys [key]}]
                                               (when-let [value (some-> (get (update-keys values name) (name key)) str/trim not-empty)] [key value])))
                                       columns)]
      (= "status" path) (when-let [command (get {"archived" archive "active" restore "deleted" delete} value)]
                          [command {id-key id :revision revision}])
      field [update {id-key id :revision revision field (when-not (str/blank? value) value)}])))


(defn- rejected
  {:malli/schema [:=> [:cat :map :string] :map]}
  [{:keys [kind commandId]} message]
  {:type :signals
   :data {:_catalogueSaveJson (charred/write-json-str {:commandId commandId :kind kind :status "failed" :message (i18n/t message)})}})


(defaction save
  {:route-data access :schema Save}
  [_ {:keys [kind id commandId referenceName] :as input}]
  (let [[command-id command-input] (command input)]
    (cond
      ;; Related records are chosen here, never created: answered like a rejected command.
      referenceName (rejected input "Choose an active record of this workspace.")
      (nil? command-id) (rejected input "This cell cannot be changed.")
      :else {:type  :command :id command-id :command-id commandId :input command-input
             :then  {:type :view :id ::saved :input {:kind kind :id id :commandId commandId}}
             :catch {:type :view :id ::failed :input {:kind kind :commandId commandId}}})))


(defn workbench
  {:malli/schema [:=> [:cat Kind :boolean :map] schema/Hiccup]}
  [kind archived? model]
  [:catalogue-workbench.catalogue-workbench
   {:id (str "catalogue-workbench-" kind)
    :data-kind               kind
    :data-model              (charred/write-json-str model)
    :data-query              (charred/write-json-str {:kind kind :archived archived? :query "" :sort "name" :direction "asc" :filters {}})
    :data-on:catalogue-query (->expr (.catch (@view ::table evt.detail {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}) (fn [_] (.requestFailed el "read"))))
    :data-on:catalogue-choices
    (->expr (.catch (@view ::choices evt.detail {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}) (fn [_] (.requestFailed el "choices"))))
    :data-on:catalogue-save  (->expr (.catch (@action ::save evt.detail {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}) (fn [_] (.requestFailed el "save"))))
    :data-signals__ifmissing (charred/write-json-str {:_catalogueResponseJson "null" :_catalogueWindowJson "null" :_catalogueChoicesJson "null"
                                                      :_catalogueSaveJson     "null" :_columnLayoutsJson   "{}"   :_layoutStatus         "Saved"
                                                      :inventoryChanged       ""})
    :data-effect             "el.catalogueResponse=JSON.parse($_catalogueResponseJson); el.catalogueWindow=JSON.parse($_catalogueWindowJson); el.catalogueChoices=JSON.parse($_catalogueChoicesJson); el.catalogueSave=JSON.parse($_catalogueSaveJson); el.catalogueChanged=$inventoryChanged; el.catalogueLayouts=$_columnLayoutsJson; el.catalogueLayoutStatus=$_layoutStatus"}
   [:div.smart-entry
    [:div.smart-line
     [:input.catalogue-search {:type "search" :aria-label (i18n/t "Search by name") :placeholder (i18n/t "Search by name…")}]
     [:button.catalogue-reset {:type "button"} (i18n/t "Reset all")]]]
   [:div.sheet-toolbar
    [:div.sheet-change-status
     [:nav.catalogue-kinds {:aria-label (i18n/t "Catalogues")}
      (for [[other {:keys [title]}] kinds]
        [:a {:href (href other false) :aria-current (when (= kind other) "page")} (i18n/t title)])]
     [:span.catalogue-save-status.quiet {:role "status"}]]
    [:div.sheet-actions
     [:button.sheet-add-row.catalogue-add {:type "button" :hidden archived?} (ui/sheet-icon :added) (i18n/t "Add row")]
     [:button.catalogue-save-new {:type "button" :hidden true} (i18n/t "Save")]
     [:button.catalogue-discard-new {:type "button" :hidden true} (i18n/t "Discard")]
     [:nav.sheet-actions {:aria-label (i18n/t "Record status")}
      (for [[archived-tab? icon label] [[false :active "Active records"] [true :archived "Archived records"]]]
        [:a.sheet-icon-button {:href         (href kind archived-tab?)                 :title (i18n/t label) :aria-label (i18n/t label)
                               :aria-current (when (= archived? archived-tab?) "page")}
         (ui/sheet-icon icon)])]]]
   [:div.catalogue-notice {:role "status"}]
   [:inventory-grid.catalogue-grid {:id (str "catalogue-grid-" kind) :data-ignore-morph "" :aria-label (i18n/t (get-in kinds [kind :title]))}]])


(defn query-page
  {:malli/schema [:=> [:cat Kind] [:=> [:cat :map :map] :map]]}
  [kind]
  (fn [context {:keys [archived]}]
    (let [archived? (= "true" archived)]
      {:kind kind :archived? archived? :rows (read-rows context kind {:archived? archived?})})))


(defn render
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [kind archived? rows]}]
  {:type :elements
   :html (if rows
           (workbench kind archived? (grid kind rows {}))
           [:p.management-empty {:role "alert"} (i18n/t "Records could not be loaded.") " "
            [:a {:href (href kind archived?)} (i18n/t "Retry")]])})


(defpage catalogue
  {:route "/catalogues" :title "Products · Tover" :parent :tover.shared.web.core/shell :route-data access :query (query-page "products")}
  [props]
  (render props))


(defpage warehouse-catalogue
  {:route "/catalogues/warehouses" :title "Warehouses · Tover" :parent :tover.shared.web.core/shell :route-data access :query (query-page "warehouses")}
  [props]
  (render props))


(defpage supplier-catalogue
  {:route "/catalogues/suppliers" :title "Suppliers · Tover" :parent :tover.shared.web.core/shell :route-data access :query (query-page "suppliers")}
  [props]
  (render props))


(defpage category-catalogue
  {:route "/catalogues/categories" :title "Categories · Tover" :parent :tover.shared.web.core/shell :route-data access :query (query-page "categories")}
  [props]
  (render props))


(defpage store-catalogue
  {:route "/catalogues/stores" :title "Stores · Tover" :parent :tover.shared.web.core/shell :route-data access :query (query-page "stores")}
  [props]
  (render props))
