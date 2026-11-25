(ns tover.marketplace.sync.observations
  "Board chapter 41 · Record source observations: sanitized observation schemas, source identity and the
  revision rules. Pure; the events and models they name live in `tover.marketplace.sync.model`."
  (:require
    [charred.api :as json]
    [clojure.string :as str]
    [tover.marketplace.connections.model :as connections]
    [tover.marketplace.sync.model :as-alias sync])
  (:import
    [java.nio.charset StandardCharsets]
    [java.security MessageDigest]
    [java.time Instant]))


(defn write-json
  {:malli/schema [:=> [:cat :any] :string]}
  [value]
  (json/write-json-str value))


(defn read-json
  {:malli/schema [:=> [:cat [:maybe :string]] :any]}
  [text]
  (when-not (str/blank? text) (json/read-json text :key-fn keyword)))


(def OperationKind
  [:enum "fbs-posting" "fbo-posting" "return" "rfbs-return" "supply" "removal"])


(def ValueType
  [:enum "string" "integer" "decimal" "boolean" "date" "dictionary" "unknown"])


(def Identifier
  [:map [:type [:enum "product-id" "article" "sku" "barcode"]] [:value :string]
   [:role {:optional true} [:enum "primary" "source" "historical"]]])


(def Attribute
  [:map [:attribute-id :string] [:group-id {:optional true} :string] [:group-index {:optional true} :int]
   [:values [:vector [:map [:value :string] [:dictionary-id {:optional true} :string]]]]])


(def Line
  [:map
   [:line-key :string]
   [:identifiers [:vector Identifier]]
   [:quantity {:optional true} :string]
   [:declared-quantity {:optional true} :string]
   [:fact-quantity {:optional true} :string]
   [:approved-quantity {:optional true} :string]
   [:act-id {:optional true} :string]
   [:unit {:optional true} :string]
   [:warehouse {:optional true} :string]
   [:duplicate {:optional true} :boolean]
   ;; Declared so that a batch still carrying buyer data is refused instead of silently stripped.
   [:buyer {:optional true} :any] [:customer {:optional true} :any] [:addressee {:optional true} :any]
   [:address {:optional true} :any]])


(def Observation
  "A sanitized source record as the fetcher translated it (see `tover.marketplace.sync.ozon`)."
  [:map
   [:kind [:enum "product" "product-aliases" "product-attributes" "warehouse" "stock" "operation" "attribute-definition"]]
   [:source-id {:optional true} :string]
   [:name {:optional true} [:maybe :string]]
   [:identifiers {:optional true} [:vector Identifier]]
   [:aliases {:optional true} [:vector Identifier]]
   [:attributes {:optional true} [:vector Attribute]]
   [:category {:optional true} [:maybe :string]]
   [:archived {:optional true} :boolean]
   [:provider-updated-at {:optional true} [:maybe :string]]
   [:fulfilment-type {:optional true} [:maybe :string]]
   [:source {:optional true} :string]
   [:product-source-id {:optional true} :string]
   [:warehouse-source-id {:optional true} :string]
   [:present {:optional true} :int]
   [:reserved {:optional true} :int]
   [:provider-as-of {:optional true} :string]
   [:operation-kind {:optional true} OperationKind]
   [:status {:optional true} :string]
   [:terminal {:optional true} :boolean]
   [:occurred-at {:optional true} :string]
   [:planned-at {:optional true} :string]
   [:related-operation {:optional true} :string]
   [:refetch-key {:optional true} :string]
   [:lines {:optional true} [:vector Line]]
   [:from-warehouse {:optional true} :string]
   [:to-warehouse {:optional true} :string]
   [:complete {:optional true} :boolean]
   [:missing {:optional true} [:vector :string]]
   [:category-id {:optional true} :string]
   [:type-id {:optional true} :string]
   [:attribute-id {:optional true} :string]
   [:value-type {:optional true} ValueType]
   [:multiple {:optional true} :boolean]
   [:complex {:optional true} :boolean]
   [:unit {:optional true} :string]
   [:dictionary-id {:optional true} :string]
   [:buyer {:optional true} :any] [:customer {:optional true} :any] [:addressee {:optional true} :any]
   [:address {:optional true} :any]])


(defn- canonical
  {:malli/schema [:=> [:cat :any] :any]}
  [value]
  (cond (map? value) (into (sorted-map) (map (fn [[k v]] [k (canonical v)])) (remove (comp nil? val) value))
        (sequential? value) (mapv canonical value)
        :else value))


(defn payload-hash
  "SHA-256 of the sanitized facts."
  {:malli/schema [:=> [:cat :any] :string]}
  [facts]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes (pr-str (canonical facts)) StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))


(defn entity-id
  "Source identity within the connection; names and articles are never identity."
  {:malli/schema [:=> [:cat :string :map] :string]}
  [connection-id {:keys [kind source-id operation-kind category-id type-id attribute-id product-source-id]}]
  (case kind
    ("product" "product-aliases" "product-attributes") (str connection-id ":" source-id)
    "warehouse" (str connection-id ":" source-id)
    "operation" (str connection-id ":" operation-kind ":" source-id)
    "attribute-definition" (str connection-id ":" category-id ":" type-id ":" attribute-id)
    "stock" (str connection-id ":" product-source-id)))


(defn bindings
  "Identifier bindings after an observation: supplied ones active (first observed kept), others inactive."
  {:malli/schema [:=> [:cat [:maybe [:sequential :map]] [:sequential :map] :string] [:vector :map]]}
  [previous supplied now]
  (let [k       (juxt :type :value :role)
        before  (into {} (map (juxt k identity)) previous)
        current (distinct (map #(select-keys % [:type :value :role]) supplied))
        keys    (set (map k current))]
    (vec (concat (for [b current]
                   (assoc b :active true :first-observed (get-in before [(k b) :first-observed] now) :last-observed now))
                 (for [b previous :when (not (contains? keys (k b)))]
                   (assoc b :active false))))))


(defn product-facts
  "A listing's facts merge three provider views: product details, SKU aliases and attributes."
  {:malli/schema [:=> [:cat [:maybe :map] :map] :map]}
  [row observation]
  (let [facts (or (read-json (:facts row)) {})]
    (case (:kind observation)
      "product" (assoc facts :base (dissoc observation :kind :source-id))
      "product-aliases" (assoc facts :aliases (:aliases observation))
      "product-attributes" (assoc facts :attributes (:attributes observation)))))


(defn- observed-common
  {:malli/schema [:=> [:cat :map [:maybe :map] :map :string :string :string] :map]}
  [{:keys [id-key]} row facts connection-id source-id now]
  (let [revision (inc (or (:revision row) 0))]
    {id-key              (:id facts)                    :connection-id   connection-id    :source-id source-id :revision revision
     :payload-hash       (:hash facts)                  :adapter-version (:adapter facts)
     :observed-at        now :last-observed-at now
     :source-revision-id (str (:id facts) "#" revision) :entity-id       (:id facts)}))


(declare observe-product)


(defn observe
  "One observation against the current rows: [events rows new?]. An unchanged hash only moves last observed."
  {:malli/schema [:=> [:cat :map :map :string :string :string] [:tuple [:vector :map] :map [:maybe :string]]]}
  [rows observation connection-id now adapter]
  (let [kind (:kind observation)
        id   (entity-id connection-id observation)
        row  (get rows id)]
    (case kind
      "stock"
      (if-not row
        ;; ponytail: a figure for a listing not observed yet is skipped; products are fetched before stock.
        [[] rows nil]
        (let [{:keys [source fulfilment-type warehouse-source-id]} observation
              stock-id (str id ":" source ":" (or (some->> warehouse-source-id (str "w")) fulfilment-type))]
          [[{:type ::sync/source-stock-observed
             :data (cond-> {:stock-id stock-id               :connection-id connection-id :external-product-id id :source source
                            :present  (:present observation) :observed-at   now}
                     fulfilment-type (assoc :fulfilment-type fulfilment-type)
                     warehouse-source-id (assoc :external-warehouse-id (str connection-id ":" warehouse-source-id))
                     (:reserved observation) (assoc :reserved (:reserved observation))
                     (:provider-as-of observation) (assoc :provider-as-of (:provider-as-of observation)))}]
           rows nil]))

      ("product-aliases" "product-attributes")
      (if row (observe-product rows observation connection-id now adapter row) [[] rows nil])

      "product"
      (observe-product rows observation connection-id now adapter row)

      (let [[model id-key seen event] (case kind
                                        "warehouse" [::sync/external-warehouses :external-warehouse-id ::sync/external-warehouse-seen
                                                     ::sync/external-warehouse-observed]
                                        "operation" [::sync/source-operations :source-operation-id ::sync/source-operation-seen
                                                     ::sync/source-operation-observed]
                                        "attribute-definition" [::sync/field-definitions :field-definition-id
                                                                ::sync/attribute-definition-seen ::sync/attribute-definition-observed])
            _ model
            facts (dissoc observation :kind)
            hash (payload-hash facts)]
        (if (= hash (:payload-hash row))
          [[{:type seen :data (cond-> {id-key id :last-observed-at now} (= "warehouse" kind) (assoc :active true))}]
           (update rows id assoc :last-observed-at now) nil]
          (let [common (observed-common {:id-key id-key} row {:id id :hash hash :adapter adapter} connection-id
                                        (or (:source-id observation) (:attribute-id observation)) now)
                data   (merge common
                              {:facts (write-json (canonical facts))}
                              (case kind
                                "warehouse" (cond-> {:fulfilment-type (:fulfilment-type observation) :active true}
                                              (:name observation) (assoc :name (:name observation)))
                                "operation" (cond-> (select-keys observation [:status :terminal :complete :occurred-at :planned-at
                                                                              :provider-updated-at :from-warehouse :to-warehouse])
                                              true (assoc :kind (:operation-kind observation)
                                                          :lines (write-json (mapv #(cond-> % (:warehouse %) (update :warehouse (fn [w] (str connection-id ":" w))))
                                                                                   (:lines observation))))
                                              (:related-operation observation) (assoc :related-operation (str connection-id ":" (:related-operation observation)))
                                              (seq (:missing observation)) (assoc :missing (write-json (:missing observation)))
                                              (:from-warehouse observation) (update :from-warehouse #(str connection-id ":" %))
                                              (:to-warehouse observation) (update :to-warehouse #(str connection-id ":" %)))
                                "attribute-definition" (select-keys observation [:category-id :type-id :attribute-id :name :value-type
                                                                                 :multiple :complex :unit :dictionary-id])))]
            [[{:type event :data data}] (assoc rows id data) (when-not row kind)]))))))


(defn- observe-product
  {:malli/schema [:=> [:cat :map :map :string :string :string [:maybe :map]] [:tuple [:vector :map] :map [:maybe :string]]]}
  [rows observation connection-id now adapter row]
  (let [id      (entity-id connection-id observation)
        facts   (product-facts row observation)
        hash    (payload-hash facts)
        k       (juxt :type :value :role)
        active  (set (map k (filter :active (read-json (:identifiers row)))))
        aliases (when (= "product-aliases" (:kind observation))
                  {:type ::sync/external-product-aliases-seen :data {:external-product-id id :aliases-observed-at now}})
        rows    (cond-> rows aliases (assoc-in [id :aliases-observed-at] now))]
    ;; An unchanged payload whose bindings an earlier pass expired is observed again, reactivating them.
    (if (and (= hash (:payload-hash row))
             (every? #(contains? active (k %)) (concat (get-in facts [:base :identifiers]) (:aliases facts))))
      [(cond-> [{:type ::sync/external-product-seen :data {:external-product-id id :last-observed-at now}}] aliases (conj aliases))
       (assoc-in rows [id :last-observed-at] now) nil]
      (let [base     (:base facts)
            supplied (concat (:identifiers base) (:aliases facts))
            data     (cond-> (merge (observed-common {:id-key :external-product-id} row {:id id :hash hash :adapter adapter}
                                                     connection-id (:source-id observation) now)
                                    {:identifiers (write-json (bindings (read-json (:identifiers row)) supplied now))
                                     :archived    (boolean (:archived base))
                                     :facts       (write-json (canonical facts))})
                       (:name base) (assoc :name (:name base))
                       (:category base) (assoc :category (:category base))
                       (:provider-updated-at base) (assoc :provider-updated-at (:provider-updated-at base))
                       (seq (:attributes facts)) (assoc :attributes (write-json (:attributes facts))))]
        [(cond-> [{:type ::sync/external-product-observed :data data}] aliases (conj aliases))
         (update rows id merge data) (when-not row "product")]))))


(defn expired-bindings
  "Board decision: after a full pass of products, SKU aliases or warehouses that finished without errors, the
  bindings that pass did not see become inactive. `rows` are the connection's rows after the pass's last batch;
  seen means observed at or after the run started. A partial or failed pass never reaches here: its domain
  does not finish in the run, and positions restart with each run."
  {:malli/schema [:=> [:cat :string [:map-of :string :map] :string] [:vector :map]]}
  [domain rows started-at]
  (let [start   (connections/at started-at)
        unseen? #(or (nil? %) (.isBefore ^Instant (connections/at %) start))
        k       (juxt :type :value :role)]
    (vec
      (case domain
        "warehouses"
        (for [[id row] (sort rows)
              :when    (and (not (false? (:active row))) (unseen? (:last-observed-at row)))]
          {:type ::sync/external-warehouse-expired :data {:external-warehouse-id id :active false}})

        ("products" "sku-aliases")
        (for [[id row] (sort rows)
              :let     [bindings (read-json (:identifiers row))
                        facts (read-json (:facts row))
                        expire (if (= "products" domain)
                                 (when (unseen? (:last-observed-at row)) (set (map k bindings)))
                                   ;; Only bindings the aliases view alone supplied.
                                 (when (unseen? (:aliases-observed-at row))
                                   (apply disj (set (map k (:aliases facts))) (map k (get-in facts [:base :identifiers])))))
                        after (mapv #(cond-> % (contains? expire (k %)) (assoc :active false)) bindings)]
              :when    (not= after bindings)]
          {:type ::sync/external-product-bindings-expired :data {:external-product-id id :identifiers (write-json after)}})

        []))))
