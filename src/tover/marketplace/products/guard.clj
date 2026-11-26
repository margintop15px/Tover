(ns tover.marketplace.products.guard
  "Board chapter 42 · the Mapping Guard (slices 9, a, b): notice an identity-relevant change, suspend the mapping,
  read suspended mappings.

  `reasons` compares a confirmed mapping's identity basis with the listing and stock item as they are now, by the
  board's named rules. Renames, articles, prices, cost and descriptive attributes match none. The `mapping-guard`
  processor evaluates the mappings of each connection marked dirty, and of every connection once a minute, and sends
  `Suspend Product Mapping` as that connection's connector principal when a mapping matches a rule it does not yet
  name. The command id derives from the mapping revision and the reasons, and a suspended mapping only gains
  reasons it lacks, so running twice emits once.

  Every before and after value is text (or nil), so stored reasons compare equal to fresh ones."
  (:require
    [bel.core :as bel :refer [defcommand defprocessor defreadmodel]]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [com.brunobonacci.mulog :as mu]
    [tover.catalogues.records :as records]
    [tover.marketplace.connections.model :as connections]
    [tover.marketplace.products.candidates :as candidates]
    [tover.marketplace.products.model :as products]
    [tover.marketplace.sync.model :as sync]
    [tover.marketplace.sync.observations :as observations])
  (:import
    [java.time Instant]))


(def rules
  "The board's rules, in the order reasons are listed."
  ["listing-identity-changed" "barcode-moved" "item-identity-changed" "item-archived"])


(def Rule
  (into [:enum] rules))


(def Reason
  [:map [:rule Rule] [:before [:maybe [:string {:max 2000}]]] [:after [:maybe [:string {:max 2000}]]]])


(defn- text
  {:malli/schema [:=> [:cat :any] [:maybe :string]]}
  [value]
  (some-> value str str/trim not-empty))


(defn catalogue-index
  "The catalogue as the guard reads it, built once for many mappings."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [products stock-items barcodes definitions]}]
  {::items       (into {} (map (juxt :stock-item-id identity)) stock-items)
   ::products    (into {} (map (juxt :product-id identity)) products)
   ::owners      (candidates/barcode-products barcodes stock-items)
   ::definitions (vec definitions)})


(defn- attribute-names
  {:malli/schema [:=> [:cat [:sequential :map]] [:map-of :string :string]]}
  [definitions]
  (reduce (fn [names {:keys [attribute-id name]}]
            (let [id (str attribute-id)]
              (if (or (contains? names id) (str/blank? name)) names (assoc names id (str/trim name)))))
          {} definitions))


(defn- reason
  {:malli/schema [:=> [:cat :string :any :any] Reason]}
  [rule before after]
  {:rule rule :before (text before) :after (text after)})


(defn- values-text
  {:malli/schema [:=> [:cat [:maybe [:sequential :any]] [:maybe :string]] [:maybe :string]]}
  [values label]
  (when (seq values) (str label ": " (str/join ", " values))))


(defn- listing-reasons
  "Variant attributes (by id, as `candidates/separating-attributes` decides now) and the pack."
  {:malli/schema [:=> [:cat :map :map [:sequential :map]] [:vector Reason]]}
  [basis current definitions]
  (let [separating? (candidates/separating-attributes definitions)
        names       (attribute-names definitions)
        before      (update-keys (:attributes basis {}) name)
        after       (:attributes current)
        ids         (sort (filter separating? (into (set (keys before)) (keys after))))]
    (into (vec (for [id    ids
                     :let  [b (get before id) a (get after id) label (get names id id)]
                     :when (not= (sort (map str b)) (sort a))]
                 (reason "listing-identity-changed" (values-text b label) (values-text a label))))
          (when (not= (text (:pack basis)) (text (:pack current)))
            [(reason "listing-identity-changed" (:pack basis) (:pack current))]))))


(defn- archived?
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :map]] :boolean]}
  [item product]
  (boolean (or (nil? item) (= "archived" (:status item)) (contains? #{"archived" "deleted"} (:status product)))))


(defn- product-label
  {:malli/schema [:=> [:cat :map [:maybe :string]] [:maybe :string]]}
  [products product-id]
  (or (text (:name (get products product-id))) product-id))


(defn reasons
  "The rules a mapping matches now: `[{:rule :before :after}]`, in rule order. `facts` holds the `:listing` (identifiers
  and attributes parsed) and the `:catalogue` (products, stock items, barcodes and the connection's attribute
  definitions), or its `catalogue-index` as `::index`. A missing item counts as archived."
  {:malli/schema [:=> [:cat :map :map] [:vector Reason]]}
  [{:keys [listing catalogue] ::keys [index]} mapping]
  (let [{::keys [items owners definitions] :as index} (or index (catalogue-index catalogue))
        products (::products index)
        item (get items (:stock-item-id mapping))
        product-id (:product-id item)
        definitions (filterv #(= (:connection-id listing) (:connection-id %)) definitions)
        basis (or (observations/read-json (:identity-basis mapping)) {})
        current (candidates/identity-basis listing definitions item)
        barcodes (sort (distinct (keep #(when (and (= "barcode" (:type %)) (not (false? (:active %))))
                                          (candidates/usable-barcode (:value %)))
                                       (:identifiers listing))))]
    (vec (concat
           (listing-reasons basis current definitions)
           (when item
             (for [barcode barcodes
                   owner   (sort (disj (get owners barcode #{}) product-id))]
               (reason "barcode-moved" (str barcode " · " (product-label products product-id))
                       (str barcode " · " (product-label products owner)))))
           (when item
             (for [k     [:unit :variant-label]
                   :when (not= (text (get basis k)) (text (get item k)))]
               (reason "item-identity-changed" (get basis k) (get item k))))
           (when (archived? item (get products product-id))
             [(reason "item-archived" "active" (if item "archived" "missing"))])))))


(defn- key-of
  {:malli/schema [:=> [:cat :map] [:vector :any]]}
  [{:keys [rule before after]}]
  [rule before after])


(defn- stored-reasons
  {:malli/schema [:=> [:cat [:maybe :map]] [:vector Reason]]}
  [mapping]
  (mapv #(reason (:rule %) (:before %) (:after %)) (observations/read-json (:reasons mapping))))


(defn new-reasons
  "Reasons the mapping does not name yet."
  {:malli/schema [:=> [:cat [:maybe :map] [:sequential Reason]] [:vector Reason]]}
  [mapping found]
  (let [known (set (map key-of (stored-reasons mapping)))]
    (vec (distinct (remove (comp known key-of) found)))))


;; Slice a · Suspend the mapping


(def SuspendProductMapping
  [:map [:external-product-id products/ListingId] [:mapping-revision products/Revision]
   [:reasons [:vector {:min 1 :max 200} Reason]]])


(defcommand suspend-product-mapping
  {:input SuspendProductMapping :policy ::sync/connector-principal
   :cofx  [[:mappings ::products/mappings-by-id (fn [_ {:keys [external-product-id]}] {:ids [external-product-id]})]]
   :emits #{::products/product-mapping-suspended}}
  [{:keys [actor now scope mappings]} {:keys [external-product-id mapping-revision reasons]}]
  (let [mapping (get mappings external-product-id)
        added   (new-reasons mapping reasons)]
    (cond (nil? mapping) (records/fail! :stale "The mapping changed since it was compared." {})
          (not= actor (sync/connector-actor (:connection-id mapping))) (records/fail! :not-permitted "Not permitted" {})
          (or (not= mapping-revision (:mapping-revision mapping)) (not (#{"confirmed" "suspended"} (:state mapping))))
          (records/fail! :stale "The mapping changed since it was compared." {}))
    (if (and (= "suspended" (:state mapping)) (empty? added))
      {}
      (let [revision (inc mapping-revision)]
        {:events [{:type ::products/product-mapping-suspended
                   :data {:external-product-id external-product-id
                          :mapping-revision-id (products/revision-id external-product-id revision)
                          :connection-id       (:connection-id mapping)
                          :change              "suspended"
                          :stock-item-id       (:stock-item-id mapping)
                          :listing-revision    (:listing-revision mapping)
                          :item-revision       (:item-revision mapping)
                          :identity-basis      (:identity-basis mapping)
                          :reasons             (observations/write-json (into (stored-reasons mapping) added))
                          :state               "suspended"
                          :mapping-revision    revision
                          :changed-by          (str "connector:" (:connection-id mapping))
                          :changed-at          (str now)}}]
         :fx     (into (records/publish scope) [[::sync/mark-dirty {:connection-id (:connection-id mapping)}]])}))))


;; Slice 9 · Notice an identity-relevant change


(def GuardItem
  [:map [:workspace-id :string] [:connection-id :string] [:external-product-id :string] [:mapping-revision :int]
   [:reasons [:vector Reason]]])


(defn pending
  "Mappings of one connection that match a rule they do not name yet, with every reason they match now."
  {:malli/schema [:=> [:cat :map :string :string] [:vector GuardItem]]}
  [{:keys [listings mappings catalogue]} workspace-id connection-id]
  (let [index (catalogue-index catalogue)
        by-id (into {} (map (juxt :external-product-id identity)) listings)]
    (vec (for [[id mapping] (sort-by key mappings)
               :let         [listing (get by-id id)]
               :when        (and listing (#{"confirmed" "suspended"} (:state mapping)))
               :let         [found (reasons {:listing listing ::index index} mapping)]
               :when        (seq (new-reasons mapping found))]
           {:workspace-id     workspace-id                :connection-id connection-id :external-product-id id
            :mapping-revision (:mapping-revision mapping) :reasons       found}))))


(defn- connection-pending
  {:malli/schema [:=> [:cat :map :uuid :string] [:vector GuardItem]]}
  [system workspace-id connection-id]
  (let [context {:bel/system system :scope {:workspace-id workspace-id}}
        input   {:connection-id connection-id}]
    (pending {:listings  (runtime/coeffect ::products/listings context input)
              :mappings  (runtime/coeffect ::products/mappings context input)
              :catalogue (runtime/coeffect ::products/catalogue context input)}
             (str workspace-id) connection-id)))


(defmethod runtime/coeffect ::mappings-to-suspend
  [_ {:keys [bel/system]} _]
  (if-let [marketplace (get-in system [:resources :marketplace])]
    (let [owned (for [workspace-id (sync/workspace-ids system)
                      connection   (sync/workspace-connections system workspace-id)]
                  [workspace-id (:connection-id connection)])
          dirty (sync/take-dirty! marketplace (set (map second owned)) (Instant/now))]
      (vec (for [[workspace-id connection-id] owned
                 :when (contains? dirty connection-id)
                 item (try (connection-pending system workspace-id connection-id)
                           (catch InterruptedException error (throw error))
                           (catch Exception error
                              ;; The minute sweep tries the connection again.
                             (mu/log ::guard-skipped :error-type (or (:type (ex-data error)) (.getName (class error))))
                             []))]
             item)))
    []))


(defprocessor ^{:malli/schema [:=> [:cat GuardItem] bel/ProcessorRequest]} mapping-guard
  {:todo ::mappings-to-suspend}
  [{:keys [workspace-id connection-id external-product-id mapping-revision reasons]}]
  {:type  :command :id ::suspend-product-mapping :scope {:workspace-id (parse-uuid workspace-id)}
   :actor (sync/connector-actor connection-id)
   :input {:external-product-id external-product-id :mapping-revision mapping-revision :reasons reasons}})


;; Slice b · Read suspended mappings


(def SuspendedRow
  [:map
   [:external-product-id :string] [:name {:optional true} :string] [:listing-revision :int] [:mapping-revision :int]
   [:stock-item [:map [:stock-item-id :string] [:product-name {:optional true} :string]
                 [:variant-label {:optional true} :string]]]
   [:reasons [:vector Reason]] [:suspended-at :any] [:waiting-operations :int]])


(defn suspended-rows
  "Suspended mappings of the connection, most waiting operations first."
  {:malli/schema [:=> [:cat :map] [:vector SuspendedRow]]}
  [{:keys [listings mappings waiting catalogue]}]
  (let [items    (into {} (map (juxt :stock-item-id identity)) (:stock-items catalogue))
        products (into {} (map (juxt :product-id identity)) (:products catalogue))]
    (->> (for [{:keys [external-product-id] :as listing} listings
               :let [mapping (get mappings external-product-id)]
               :when (= "suspended" (:state mapping))
               :let [item (get items (:stock-item-id mapping))
                     product-name (:name (get products (:product-id item)))]]
           (cond-> {:external-product-id external-product-id
                    :listing-revision    (:revision listing)
                    :mapping-revision    (:mapping-revision mapping)
                    :stock-item          (cond-> {:stock-item-id (:stock-item-id mapping)}
                                           product-name (assoc :product-name product-name)
                                           (:variant-label item) (assoc :variant-label (:variant-label item)))
                    :reasons             (stored-reasons mapping)
                    :suspended-at        (connections/at (:changed-at mapping))
                    :waiting-operations  (get waiting external-product-id 0)}
             (:name listing) (assoc :name (:name listing))))
         (sort-by (juxt (comp - :waiting-operations) (comp str/lower-case #(or (:name %) "")) :external-product-id))
         vec)))


(defn- by-connection
  {:malli/schema [:=> [:cat :map :map] :map]}
  [_ input]
  (select-keys input [:connection-id]))


(defreadmodel suspended-mappings-awaiting-review
  {:input  [:map [:connection-id products/Ref]]
   :output [:map [:rows [:vector SuspendedRow]]]
   :policy ::records/workspace-member
   :cofx   [[:listings ::products/listings by-connection]
            [:mappings ::products/mappings by-connection]
            [:waiting ::products/waiting by-connection]
            [:catalogue ::products/catalogue by-connection]]}
  [facts _]
  {:rows (suspended-rows facts)})
