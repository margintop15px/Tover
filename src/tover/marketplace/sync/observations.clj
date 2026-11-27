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


(def WarehouseType
  [:enum "fbo" "fbs" "rfbs" "supply-origin"])


(def Warehouse
  "The warehouse a fact names. The type comes from which fact names it, never from a name."
  [:map [:type WarehouseType] [:id :string] [:name {:optional true} :string]])


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
   [:price {:optional true} :string]
   [:price-currency {:optional true} :string]
   [:warehouse {:optional true} Warehouse]
   [:duplicate {:optional true} :boolean]
   ;; Declared so that a batch still carrying buyer data is refused instead of silently stripped.
   [:buyer {:optional true} :any] [:customer {:optional true} :any] [:addressee {:optional true} :any]
   [:address {:optional true} :any]])


(def Observation
  "A sanitized source record as the fetcher translated it (see `tover.marketplace.sync.ozon`)."
  [:map
   [:kind [:enum "product" "product-aliases" "product-attributes" "product-cost" "warehouse" "stock" "operation"
           "attribute-definition"]]
   [:source-id {:optional true} :string]
   [:name {:optional true} [:maybe :string]]
   [:identifiers {:optional true} [:vector Identifier]]
   [:aliases {:optional true} [:vector Identifier]]
   [:attributes {:optional true} [:vector Attribute]]
   [:category {:optional true} [:maybe :string]]
   [:archived {:optional true} :boolean]
   [:provider-updated-at {:optional true} [:maybe :string]]
   [:cost {:optional true} :string]
   [:cost-currency {:optional true} :string]
   [:warehouse-type {:optional true} WarehouseType]
   [:fulfilment-type {:optional true} [:maybe :string]]
   [:source {:optional true} :string]
   [:product-source-id {:optional true} :string]
   [:warehouse {:optional true} Warehouse]
   [:part {:optional true} :string]
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
   [:from-warehouse {:optional true} Warehouse]
   [:to-warehouse {:optional true} Warehouse]
   [:return-schema {:optional true} [:enum "fbo" "fbs"]]
   [:compensated {:optional true} :boolean]
   [:disposed {:optional true} :boolean]
   [:complete {:optional true} :boolean]
   [:missing {:optional true} [:vector :string]]
   [:category-id {:optional true} :string]
   [:type-id {:optional true} :string]
   [:attribute-id {:optional true} :string]
   [:value-type {:optional true} ValueType]
   [:multiple {:optional true} :boolean]
   [:complex {:optional true} :boolean]
   [:separates-variants {:optional true} :boolean]
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


(defn warehouse-id
  "External warehouse identity: the same provider id under two types is two warehouses."
  {:malli/schema [:=> [:cat :string :string :string] :string]}
  [connection-id type source-id]
  (str connection-id ":" type ":" source-id))


(defn warehouses
  "Every warehouse an observation names, its lines included."
  {:malli/schema [:=> [:cat :map] [:sequential Warehouse]]}
  [{:keys [from-warehouse to-warehouse warehouse lines]}]
  (filter some? (concat [from-warehouse to-warehouse warehouse] (map :warehouse lines))))


(defn candidate-warehouse-ids
  "Ids a named warehouse may be stored under. An FBS posting also names rFBS warehouses; only the seller's
  warehouse list tells them apart."
  {:malli/schema [:=> [:cat :string Warehouse] [:vector :string]]}
  [connection-id {:keys [type id]}]
  (cond-> [(warehouse-id connection-id type id)]
    (= "fbs" type) (conj (warehouse-id connection-id "rfbs" id))))


(defn- warehouse-ref
  "The stored id of a named warehouse. An FBS-named id is the listed FBS or rFBS warehouse, an active one first;
  nil when neither is listed."
  {:malli/schema [:=> [:cat :map :string Warehouse] [:maybe :string]]}
  [rows connection-id {:keys [type] :as warehouse}]
  (let [ids (candidate-warehouse-ids connection-id warehouse)]
    (if (= "fbs" type)
      (first (sort-by #(false? (:active (rows %))) (filter #(contains? rows %) ids)))
      (first ids))))


(defn- map-warehouses
  "`f` of every warehouse the observation names, its lines included; a nil result leaves it out."
  {:malli/schema [:=> [:cat [:fn fn?] :map] :map]}
  [f observation]
  (letfn [(named [m ks]
            (reduce (fn [m k]
                      (if-let [warehouse (get m k)]
                        (if-let [value (f warehouse)] (assoc m k value) (dissoc m k))
                        m))
                    m ks))]
    (cond-> (named observation [:from-warehouse :to-warehouse :warehouse])
      (:lines observation) (update :lines (partial mapv #(named % [:warehouse]))))))


(defn- resolved
  "The observation with warehouse ids in place of the warehouses it names. An operation naming one that is not
  listed lacks a warehouse; a stock figure keeps it under the type that names it."
  {:malli/schema [:=> [:cat :map :string :map] :map]}
  [rows connection-id observation]
  (let [ref  #(or (warehouse-ref rows connection-id %)
                  (when (= "stock" (:kind observation)) (warehouse-id connection-id (:type %) (:id %))))
        lack (and (= "operation" (:kind observation)) (some (comp nil? ref) (warehouses observation)))]
    (cond-> (map-warehouses ref observation)
      lack (-> (assoc :complete false)
               (update :missing #(vec (distinct (conj (vec %) "warehouse"))))))))


(defn- warehouse-gained?
  "Whether a warehouse the stored revision of the same facts lacks now resolves."
  {:malli/schema [:=> [:cat :map :map] :boolean]}
  [row observation]
  (letfn [(slots [m lines]
            (into [(:from-warehouse m) (:to-warehouse m)] (map :warehouse) lines))]
    (boolean (some true? (map #(and (some? %1) (nil? %2))
                              (slots observation (:lines observation))
                              (slots row (read-json (:lines row))))))))


(defn cancelled?
  {:malli/schema [:=> [:cat [:maybe :string]] :boolean]}
  [status]
  (= "cancelled" (some-> status str/lower-case)))


(defn- with-handover
  "The posting with the handover time last observed. A cancelled revision that states none takes it as its own:
  it comes from the stored row, so it is no part of the payload hash."
  {:malli/schema [:=> [:cat [:maybe :map] :map] :map]}
  [row {:keys [operation-kind status occurred-at missing] :as observation}]
  (let [at (or occurred-at (:handed-over-at row))]
    (if-not (and at (#{"fbs-posting" "fbo-posting"} operation-kind))
      observation
      (cond-> (assoc observation :handed-over-at at)
        (and (nil? occurred-at) (cancelled? status))
        (as-> o (let [missing (vec (remove #{"actual time"} missing))]
                  (assoc o :occurred-at at :missing missing :complete (empty? missing))))))))


(defn entity-id
  "Source identity within the connection; names and articles are never identity."
  {:malli/schema [:=> [:cat :string :map] :string]}
  [connection-id {:keys [kind source-id warehouse-type operation-kind category-id type-id attribute-id product-source-id]}]
  (case kind
    ("product" "product-aliases" "product-attributes" "product-cost") (str connection-id ":" source-id)
    "warehouse" (warehouse-id connection-id warehouse-type source-id)
    "operation" (str connection-id ":" operation-kind ":" source-id)
    "attribute-definition" (str connection-id ":" category-id ":" type-id ":" attribute-id)
    "stock" (str connection-id ":" product-source-id)))


(defn stock-id
  "A figure's identity. `place` is the external warehouse id or the fulfilment type. Warehouse stock keeps one
  figure per run; the other views keep the latest only."
  {:malli/schema [:=> [:cat :string :string :string :string] :string]}
  [external-product-id source place run-id]
  (str/join "|" (cond-> [external-product-id source place] (= "warehouse-stock" source) (conj run-id))))


(defn candidate-stock-ids
  "Ids the figure may be stored under."
  {:malli/schema [:=> [:cat :string :string :map] [:vector :string]]}
  [connection-id run-id {:keys [source warehouse fulfilment-type] :as observation}]
  (mapv #(stock-id (entity-id connection-id observation) source % run-id)
        (if warehouse (candidate-warehouse-ids connection-id warehouse) [fulfilment-type])))


(defn- stock-figure
  "A listing's figure in one place. Its rows there (one per SKU) add up over the pages of one run; a row stated
  twice counts once. `present` includes `reserved`."
  {:malli/schema [:=> [:cat :map :map :map :map] :map]}
  [rows stated {:keys [source warehouse] :as observation} {:keys [connection-id run-id now listing]}]
  (let [type     (cond (nil? warehouse) (:fulfilment-type observation)
                       ;; The list, not the fact, tells rFBS from FBS.
                       (= warehouse (warehouse-id connection-id "rfbs" (get-in stated [:warehouse :id]))) "rfbs"
                       :else (get-in stated [:warehouse :type]))
        id       (stock-id listing source (or warehouse type) run-id)
        row      (get rows id)
        before   (when (= run-id (:run-id row)) (read-json (:parts row)))
        parts    (into (sorted-map) (map (juxt first identity))
                       (conj (vec before) [(:part observation "") (:present observation) (:reserved observation)]))
        reserved (seq (keep peek (vals parts)))]
    (cond-> {:stock-id        id   :connection-id connection-id :run-id run-id :external-product-id listing :source source
             :fulfilment-type type :present       (transduce (map second) + (vals parts)) :parts (write-json (vec (vals parts)))
             :observed-at     now}
      warehouse (assoc :external-warehouse-id warehouse)
      reserved (assoc :reserved (reduce + reserved))
      (:provider-as-of observation) (assoc :provider-as-of (:provider-as-of observation)))))


(defn contradicts?
  "Whether a warehouse figure states a part again, with other quantities than `figures` (by id) hold for this run."
  {:malli/schema [:=> [:cat [:map-of :string :map] :string :string [:sequential :map]] :boolean]}
  [figures connection-id run-id observations]
  (boolean
    (some (fn [{:keys [part present reserved] :as observation}]
            (some (fn [[p & quantities]] (and (= p (or part "")) (not= quantities [present reserved])))
                  (for [id    (candidate-stock-ids connection-id run-id observation)
                        :let  [row (get figures id)]
                        :when (= run-id (:run-id row))
                        row   (read-json (:parts row))]
                    row)))
          (filter #(= "warehouse-stock" (:source %)) observations))))


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
  "A listing's facts merge four provider views: product details, SKU aliases, attributes and the entered cost."
  {:malli/schema [:=> [:cat [:maybe :map] :map] :map]}
  [row observation]
  (let [facts (or (read-json (:facts row)) {})]
    (case (:kind observation)
      "product" (assoc facts :base (dissoc observation :kind :source-id))
      "product-aliases" (assoc facts :aliases (:aliases observation))
      "product-attributes" (assoc facts :attributes (:attributes observation))
      "product-cost" (if (:cost observation)
                       (assoc facts :cost (select-keys observation [:cost :cost-currency]))
                       (dissoc facts :cost)))))


(defn- observed-common
  {:malli/schema [:=> [:cat :map [:maybe :map] :map :string :string :string] :map]}
  [{:keys [id-key]} row facts connection-id source-id now]
  (let [revision (inc (or (:revision row) 0))]
    {id-key              (:id facts)                    :connection-id   connection-id    :source-id source-id :revision revision
     :payload-hash       (:hash facts)                  :adapter-version (:adapter facts)
     :observed-at        now :last-observed-at now
     :source-revision-id (str (:id facts) "#" revision) :entity-id       (:id facts)}))


(defn- operation-fields
  "What a source operation row stores of a resolved operation observation, beside its identity and revision."
  {:malli/schema [:=> [:cat :string :map] :map]}
  [connection-id observation]
  (cond-> (select-keys observation [:status :terminal :complete :occurred-at :planned-at
                                    :provider-updated-at :from-warehouse :to-warehouse
                                    :handed-over-at :refetch-key :return-schema :compensated :disposed])
    true (assoc :kind (:operation-kind observation)
                :lines (write-json (vec (:lines observation))))
    (:related-operation observation) (assoc :related-operation (str connection-id ":" (:related-operation observation)))
    (seq (:missing observation)) (assoc :missing (write-json (:missing observation)))))


(defn revision-operation
  "The source operation `row` as its earlier `revision` (a `::sync/source-revisions` row of it) stated it: the
  stored facts read back as `observe` stores them, warehouses resolved against `rows` and with the handover time
  `row` keeps. Board 45 slice 7 compares an accepted revision with a newer one."
  {:malli/schema [:=> [:cat [:map-of :string :map] :string :map :map] :map]}
  [rows connection-id row revision]
  (let [stated      (assoc (read-json (:facts revision)) :kind "operation")
        observation (with-handover row (resolved rows connection-id stated))]
    (merge (select-keys row [:source-operation-id :entity-id :connection-id :source-id])
           (select-keys revision [:revision :payload-hash :adapter-version :facts :observed-at :source-revision-id])
           (operation-fields connection-id observation))))


(declare observe-product)


(defn observe
  "One observation of a run against the current rows: [events rows new?]. An unchanged hash only moves last
  observed, except that an operation stored by another adapter version is observed again: the new revision holds
  this version, so that happens once."
  {:malli/schema [:=> [:cat :map :map :string :string :string :string] [:tuple [:vector :map] :map [:maybe :string]]]}
  [rows observation connection-id run-id now adapter]
  (let [kind        (:kind observation)
        id          (entity-id connection-id observation)
        row         (get rows id)
        ;; The facts as the provider stated them, hashed before any warehouse is resolved. A warehouse's name is
        ;; no fact of what names it; an FBO fact that names no name keeps the one another fact stated.
        stated      (cond-> (map-warehouses #(select-keys % [:type :id]) observation)
                      (and (= "fbo" (:warehouse-type observation)) (nil? (:name observation)) (:name row))
                      (assoc :name (:name row)))
        observation (cond->> (resolved rows connection-id stated) (= "operation" kind) (with-handover row))]
    (case kind
      "stock"
      (if-not row
        ;; ponytail: a figure for a listing not observed yet is skipped; products are fetched before stock.
        [[] rows nil]
        (let [figure (stock-figure rows stated observation
                                   {:connection-id connection-id :run-id run-id :now now :listing id})]
          [[{:type ::sync/source-stock-observed :data figure}] (assoc rows (:stock-id figure) figure) nil]))

      ("product-aliases" "product-attributes" "product-cost")
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
            facts (dissoc stated :kind)
            hash (payload-hash facts)]
        (if (and (= hash (:payload-hash row)) (not (and (= "operation" kind)
                                                        (or (not= adapter (:adapter-version row)) (warehouse-gained? row observation)))))
          [[{:type seen :data (cond-> {id-key id :last-observed-at now} (= "warehouse" kind) (assoc :active true))}]
           (update rows id assoc :last-observed-at now) nil]
          (let [common (observed-common {:id-key id-key} row {:id id :hash hash :adapter adapter} connection-id
                                        (or (:source-id observation) (:attribute-id observation)) now)
                data   (merge common
                              {:facts (write-json (canonical facts))}
                              (case kind
                                "warehouse" (cond-> {:warehouse-type (:warehouse-type observation) :active true}
                                              (:name observation) (assoc :name (:name observation)))
                                "operation" (operation-fields connection-id observation)
                                "attribute-definition" (select-keys observation [:category-id :type-id :attribute-id :name :value-type
                                                                                 :multiple :complex :separates-variants :unit
                                                                                 :dictionary-id])))]
            [[{:type event :data data}] (assoc rows id data) (when-not row kind)]))))))


(defn- observe-product
  {:malli/schema [:=> [:cat :map :map :string :string :string [:maybe :map]] [:tuple [:vector :map] :map [:maybe :string]]]}
  [rows observation connection-id now adapter row]
  (let [id      (entity-id connection-id observation)
        facts   (product-facts row observation)
        hash    (payload-hash facts)
        k       (juxt :type :value :role)
        ;; A cost states no identifiers: it leaves the bindings as they are.
        cost?   (= "product-cost" (:kind observation))
        active  (set (map k (filter :active (read-json (:identifiers row)))))
        aliases (when (= "product-aliases" (:kind observation))
                  {:type ::sync/external-product-aliases-seen :data {:external-product-id id :aliases-observed-at now}})
        rows    (cond-> rows aliases (assoc-in [id :aliases-observed-at] now))]
    ;; An unchanged payload whose bindings an earlier pass expired is observed again, reactivating them.
    (if (and (= hash (:payload-hash row))
             (or cost? (every? #(contains? active (k %)) (concat (get-in facts [:base :identifiers]) (:aliases facts)))))
      [(cond-> [{:type ::sync/external-product-seen :data {:external-product-id id :last-observed-at now}}] aliases (conj aliases))
       (assoc-in rows [id :last-observed-at] now) nil]
      (let [base     (:base facts)
            supplied (concat (:identifiers base) (:aliases facts))
            data     (cond-> (merge (observed-common {:id-key :external-product-id} row {:id id :hash hash :adapter adapter}
                                                     connection-id (:source-id observation) now)
                                    {:identifiers (if cost?
                                                    (:identifiers row)
                                                    (write-json (bindings (read-json (:identifiers row)) supplied now)))
                                     :archived    (boolean (:archived base))
                                     :facts       (write-json (canonical facts))}
                                    (:cost facts))
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
  does not finish in the run, and positions restart with each run.
  No list states FBO warehouses, so they never expire."
  {:malli/schema [:=> [:cat :string [:map-of :string :map] :string] [:vector :map]]}
  [domain rows started-at]
  (let [start   (connections/at started-at)
        unseen? #(or (nil? %) (.isBefore ^Instant (connections/at %) start))
        k       (juxt :type :value :role)]
    (vec
      (case domain
        "warehouses"
        (for [[id row] (sort rows)
              :when    (and (not= "fbo" (:warehouse-type row)) (not (false? (:active row))) (unseen? (:last-observed-at row)))]
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
