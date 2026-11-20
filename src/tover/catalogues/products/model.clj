(ns tover.catalogues.products.model
  "Board chapter 08 · Manage products. Field rules live in the board's shared element details.
  Inventory still resolves legacy goods until it is rebuilt on stock items; categories and warehouses are
  the Bel records of chapters 11 and 09."
  (:require
    [bel-rdf.adapter :as source]
    [bel.core :as bel :refer [defcommand defevent defmodel defreadmodel]]
    [bel.runtime :as runtime]
    [bel.store :as store]
    [clojure.string :as str]
    [tover.catalogues.categories.model :as categories]
    [tover.catalogues.records :as records]
    [tover.catalogues.warehouses.model :as warehouses]))


(def Id
  [:string {:min 1 :max 500}])


(def Name
  "`Product Registered`: trimmed, 1–500 characters."
  [:string {:min 1 :max 500}])


(def Status
  [:enum "active" "archived" "deleted"])


(def Product
  [:map
   [:product-id Id]
   [:name :string]
   [:category-id {:optional true} :string]
   [:default-warehouse-id {:optional true} :string]
   [:status Status]
   [:revision [:int {:min 1}]]])


(defmethod runtime/policy ::workspace-member
  [_ {{:keys [memberships]} :principal scope :scope} _]
  (boolean (some #(= (:workspace-id scope) (:workspace-id %)) memberships)))


(def ^:private fields
  [:name :category-id :default-warehouse-id :status :revision])


(defevent product-registered {:schema Product})
(defevent product-updated {:schema Product})
(defevent product-archived {:schema [:map [:product-id Id] [:status Status] [:revision [:int {:min 1}]]]})
(defevent product-restored {:schema [:map [:product-id Id] [:status Status] [:revision [:int {:min 1}]]]})
(defevent product-deleted {:schema [:map [:product-id Id] [:status Status] [:revision [:int {:min 1}]]]})


(defmodel products
  {:id     :product-id
   :store  :catalogue
   :schema Product
   :policy ::workspace-member
   ;; `:set` replaces every listed field, so `Product Updated` carries the resulting values, not a diff.
   :events {::product-registered {:create fields}
            ::product-updated    {:set fields}
            ::product-archived   {:set [:status :revision]}
            ::product-restored   {:set [:status :revision]}
            ::product-deleted    {:set [:status :revision]}}})


(defmethod runtime/coeffect ::all
  [_ context _]
  ;; ponytail: full scan of the workspace's products per decision; add a normalized-name index if catalogues grow large.
  (let [definition (bel/get-model ::products)
        field      (get-in (source/vocabulary definition) [:fields :product-id])]
    (runtime/with-store context :catalogue :repeatable-read
      (fn [{:keys [tx scope] :as context}]
        (let [adapter (store/store context :catalogue)
              ids     (runtime/coeffect :rdf/query context {:query    '{:select [?id] :where [[?row ?field ?id]]}
                                                            :bindings {:field field}})]
          (into [] (keep #(store/-load adapter tx (store/store-scope scope) definition (:id %))) ids))))))


(defmethod runtime/coeffect ::references
  [_ context _]
  {:categories (records/choices (records/all-records context ::categories/categories :category-id) :category-id)
   :warehouses (records/choices (records/all-records context ::warehouses/warehouses :warehouse-id) :warehouse-id)})


(def Usage
  [:map [:active-stock-items :int] [:stock-items-ever :int] [:pending-entry [:maybe :string]]])


(def unused
  {:active-stock-items 0 :stock-items-ever 0 :pending-entry nil})


(defmethod runtime/coeffect ::usage
  [_ context {:keys [product-ids]}]
  ;; Usage by product id: active stock items, stock items ever registered, and a shared pending entry naming the
  ;; product directly or through one of its stock items. A missing id is unused.
  (let [items   (records/all-records context :tover.inventory.stock-items.model/stock-items :stock-item-id)
        owner   (into {} (map (juxt :stock-item-id :product-id)) items)
        pending (runtime/coeffect :tover.inventory.entries.model/pending context {})
        named   (fn [pid] (some (fn [entry] (when (some #(or (= pid (:product-id %)) (= pid (owner (:stock-item-id %)))) (:lines entry))
                                              (:entry-id entry)))
                                pending))
        by-prod (group-by :product-id items)]
    (into {} (for [pid product-ids]
               [pid {:active-stock-items (count (filter #(= "active" (:status %)) (by-prod pid)))
                     :stock-items-ever   (count (by-prod pid)) :pending-entry (named pid)}]))))


(defn- fail!
  {:malli/schema [:=> [:cat :keyword :string :map] :nil]}
  [type message data]
  (throw (ex-info message (assoc data :type type))))


(defn name-key
  {:malli/schema [:=> [:cat :string] :string]}
  [name]
  (str/lower-case (str/trim name)))


(defn- check-name!
  {:malli/schema [:=> [:cat [:sequential :map] :string :string] :string]}
  [products product-id name]
  (let [name (str/trim name)]
    (when-not (<= 1 (count name) 500)
      (fail! :validation "Enter a product name of 1 to 500 characters." {:field :name}))
    (when-let [owner (some #(when (and (= (name-key name) (name-key (:name %)))
                                       (not= product-id (:product-id %))
                                       (not= "deleted" (:status %)))
                              %)
                           products)]
      (fail! :name-taken "Another product already uses this name." {:product (select-keys owner [:product-id :name :status]) :attempted name}))
    name))


(defn- check-related!
  {:malli/schema [:=> [:cat [:maybe :string] [:sequential :map] :keyword] :nil]}
  [id records type]
  (when (and id (not-any? #(and (= id (:id %)) (not= "archived" (:status %))) records))
    (fail! type "Choose an active record of this workspace." {:id id})))


(defn- current!
  "The reviewed product, or a rejection. Stale is checked before any status rule."
  {:malli/schema [:=> [:cat [:maybe :map] :int] :map]}
  [product revision]
  (when (or (nil? product) (= "deleted" (:status product)))
    (fail! :not-found "Choose an existing product in this workspace." {}))
  (when-not (= revision (:revision product))
    (fail! :stale-catalogue "This product changed. Review it again." {:current product}))
  product)


(defn- by-product
  {:malli/schema [:=> [:cat :map :map] :map]}
  [_ input]
  (select-keys input [:product-id]))


(defn- usage-of
  {:malli/schema [:=> [:cat :map :string] Usage]}
  [usage product-id]
  (get usage product-id unused))


(def ^:private all-products
  [:products ::all (constantly {})])


(def ^:private references
  [:references ::references (constantly {})])


(def ^:private usage
  [:usage ::usage (fn [_ input] {:product-ids [(:product-id input)]})])


(defn- publish
  {:malli/schema [:=> [:cat :map] [:vector :any]]}
  [scope]
  [[:bel/publish {:topic :tover.stock.web/changed :scope-id (:workspace-id scope) :data {}}]])


(def RegisterProduct
  [:map
   [:product-id Id]
   [:name [:string {:max 1000}]]
   [:category-id {:optional true} [:maybe Id]]
   [:default-warehouse-id {:optional true} [:maybe Id]]])


(defcommand register-product
  {:input RegisterProduct           :policy ::workspace-member
   :cofx  [all-products references]
   :emits #{::product-registered}}
  [{:keys [products references scope]} {:keys [product-id category-id default-warehouse-id] :as input}]
  (let [name (check-name! products product-id (:name input))]
    (check-related! category-id (:categories references) :category-unavailable)
    (check-related! default-warehouse-id (:warehouses references) :warehouse-unavailable)
    {:events [{:type ::product-registered
               :data (cond-> {:product-id product-id :name name :status "active" :revision 1}
                       category-id (assoc :category-id category-id)
                       default-warehouse-id (assoc :default-warehouse-id default-warehouse-id))}]
     :fx     (publish scope)}))


(def UpdateProduct
  "An absent key keeps the field; a present nil clears category or default warehouse."
  [:map
   [:product-id Id]
   [:revision [:int {:min 1}]]
   [:name {:optional true} [:string {:max 1000}]]
   [:category-id {:optional true} [:maybe Id]]
   [:default-warehouse-id {:optional true} [:maybe Id]]])


(defcommand update-product
  {:input UpdateProduct :policy ::workspace-member
   :cofx  [[:product ::products by-product] all-products references]
   :emits #{::product-updated}}
  [{:keys [product products references scope]} {:keys [revision] :as input}]
  (let [product (current! product revision)
        name    (if (contains? input :name) (check-name! products (:product-id product) (:name input)) (:name product))
        changed (cond-> (assoc product :name name)
                  (contains? input :category-id) (assoc :category-id (:category-id input))
                  (contains? input :default-warehouse-id) (assoc :default-warehouse-id (:default-warehouse-id input)))
        changed (into {} (remove (comp nil? val)) changed)]
    (when (not= (:category-id changed) (:category-id product))
      (check-related! (:category-id changed) (:categories references) :category-unavailable))
    (when (not= (:default-warehouse-id changed) (:default-warehouse-id product))
      (check-related! (:default-warehouse-id changed) (:warehouses references) :warehouse-unavailable))
    (if (= changed product)
      {}
      {:events [{:type ::product-updated :data (update changed :revision inc)}]
       :fx     (publish scope)})))


(def Reviewed
  [:map [:product-id Id] [:revision [:int {:min 1}]]])


(defn- transition
  {:malli/schema [:=> [:cat :keyword :map :string] :map]}
  [type product status]
  {:events [{:type type :data {:product-id (:product-id product) :status status :revision (inc (:revision product))}}]})


(defcommand archive-product
  {:input Reviewed :policy ::workspace-member
   :cofx  [[:product ::products by-product] usage]
   :emits #{::product-archived}}
  [{:keys [product usage scope]} {:keys [product-id revision]}]
  (let [product (current! product revision)
        usage   (usage-of usage product-id)]
    (when-not (= "active" (:status product))
      (fail! :already-archived "This product is already archived." {}))
    (when (pos? (:active-stock-items usage))
      (fail! :active-stock-items "Archive this product's stock items first." {:count (:active-stock-items usage)}))
    (assoc (transition ::product-archived product "archived") :fx (publish scope))))


(defcommand restore-product
  {:input Reviewed :policy ::workspace-member
   :cofx  [[:product ::products by-product]]
   :emits #{::product-restored}}
  [{:keys [product scope]} {:keys [revision]}]
  (let [product (current! product revision)]
    (when-not (= "archived" (:status product))
      (fail! :not-archived "This product is not archived." {}))
    (assoc (transition ::product-restored product "active") :fx (publish scope))))


(defcommand delete-product
  {:input Reviewed :policy ::workspace-member
   :cofx  [[:product ::products by-product] usage]
   :emits #{::product-deleted}}
  [{:keys [product usage scope]} {:keys [product-id revision]}]
  (let [product (current! product revision)
        usage   (usage-of usage product-id)]
    (when (pos? (:stock-items-ever usage))
      (fail! :had-stock-item "This product has had stock items. Archive it instead." {}))
    (when-let [entry (:pending-entry usage)]
      (fail! :pending-reference "A pending inventory entry names this product." {:entry entry}))
    (assoc (transition ::product-deleted product "deleted") :fx (publish scope))))


(defn- label
  {:malli/schema [:=> [:cat :map [:maybe :string]] [:maybe :map]]}
  [records id]
  (when id
    (let [record (get records id)]
      {:id id :name (or (:name record) id) :archived? (or (nil? record) (= "archived" (:status record)))})))


(defn- index
  {:malli/schema [:=> [:cat [:sequential :map]] :map]}
  [records]
  (into {} (map (juxt :id identity)) records))


(def CatalogueRow
  [:map
   [:product-id :string] [:name :string] [:status :string] [:revision :int]
   [:category {:optional true} [:maybe :map]] [:default-warehouse {:optional true} [:maybe :map]]
   [:active-stock-items :int] [:can-archive? :boolean] [:can-delete? :boolean]])


(defreadmodel product-catalogue
  {:input  [:map [:archived? {:optional true} :boolean] [:product-id {:optional true} :string]]
   :output [:map [:products [:vector CatalogueRow]]
            [:choices [:map [:categories [:vector :map]] [:warehouses [:vector :map]]]]]
   :policy ::workspace-member
   :cofx   [all-products references
            [:usage ::usage (fn [{:keys [products]} _] {:product-ids (mapv :product-id products)})]]}
  [{:keys [products references usage]} {:keys [archived? product-id]}]
  (let [categories (index (:categories references))
        warehouses (index (:warehouses references))]
    {:products (->> products
                    ;; One product, whatever its status, answers a saved grid cell.
                    (filter (if product-id
                              #(and (= product-id (:product-id %)) (not= "deleted" (:status %)))
                              #(= (if archived? "archived" "active") (:status %))))
                    (sort-by (comp name-key :name))
                    (mapv (fn [product]
                            (let [{:keys [active-stock-items stock-items-ever pending-entry]} (usage-of usage (:product-id product))]
                              (-> (select-keys product [:product-id :name :status :revision])
                                  (assoc :category (label categories (:category-id product))
                                         :default-warehouse (label warehouses (:default-warehouse-id product))
                                         :active-stock-items active-stock-items
                                         :can-archive? (and (= "active" (:status product)) (zero? active-stock-items))
                                         :can-delete? (and (zero? stock-items-ever) (nil? pending-entry))))))))
     :choices  {:categories (:categories references) :warehouses (:warehouses references)}}))


(defn prefill-warehouse
  "The warehouse a new draft line for this product's stock item starts with; an archived default prefills nothing."
  {:malli/schema [:=> [:cat :map] [:maybe :string]]}
  [product-row]
  (let [warehouse (:default-warehouse product-row)]
    (when (and warehouse (not (:archived? warehouse))) (:id warehouse))))
