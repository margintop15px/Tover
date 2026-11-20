(ns tover.marketplace.sync.model
  "Board chapter 41 · Synchronize marketplace data: the scheduled read-only sync of one connection.

  The scheduler (a processor) starts a run for each due connection; the fetcher (a processor) reads one page
  or detail batch of the run's next unfinished domain outside any store transaction and sends it to
  `Record Source Observations`, which commits the observations with the connection's new sync position.
  When nothing is unfinished, or a domain is blocked, the fetcher closes the run with
  `Complete Marketplace Sync`. Source records are evidence only: nothing here touches stock, catalogue
  records or mappings.

  Store: every model lives in `:catalogue` next to the connection, so a batch, its position and a domain's
  coverage commit together. The RDF adapter stores scalars, so lists are JSON or space-separated text and
  instants are ISO text. A source revision keeps the normalized facts, payload hash and adapter version;
  provider payloads are never stored.

  The connector principal is one per connection: processors act as a Bel service with the connection id as
  actor, and runs record `connector:<connection id>`, shown as \"Ozon · <account label>\".

  Internal events the board does not name (`Source Batch Recorded`, `Source Domain Unavailable`,
  `Provider Throttled` and the `… Seen` last-observed updates) persist what the board's information
  elements read: sync positions, unavailable domains, \"resume not before\" and last observed times."
  (:require
    [bel-rdf.adapter :as rdf-adapter]
    [bel.core :as bel :refer [defcommand defevent defmodel defprocessor defreadmodel]]
    [bel.runtime :as runtime]
    [bel.store :as store]
    [bel.validation :as validation]
    [charred.api :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [com.brunobonacci.mulog :as mu]
    [tover.catalogues.records :as records]
    [tover.marketplace.connections.model :as connections]
    [tover.marketplace.connector :as connector]
    [tover.marketplace.sync.ozon :as ozon]
    [tover.shared.rdf :as rdf])
  (:import
    [java.nio.charset StandardCharsets]
    [java.security MessageDigest]
    [java.time Duration Instant]
    [java.time.temporal ChronoUnit]
    [java.util UUID]))


;; Data model (element details on the board)


(def Ref connections/Ref)
(def At connections/At)
(def Domain connections/Domain)


(def Text
  "Space-separated domains or a JSON document."
  [:string {:max 2000000}])


(def RunState
  [:enum "open" "completed" "failed"])


(def Trigger
  [:enum "schedule" "member"])


(def reasons
  {:credentials-rejected "credentials rejected" :retries-exhausted "retries exhausted"
   :repeated-cursor      "repeated cursor"      :incomplete-page   "incomplete page"})


(def Reason
  (into [:enum] (vals reasons)))


(defn- encode
  {:malli/schema [:=> [:cat [:sequential :string]] :string]}
  [domains]
  (str/join " " (filter (set domains) connector/domains)))


(defn- decode
  {:malli/schema [:=> [:cat [:maybe :string]] [:vector :string]]}
  [text]
  (let [present (connections/decode-domains text)] (filterv present connector/domains)))


(defn- write-json
  {:malli/schema [:=> [:cat :any] :string]}
  [value]
  (json/write-json-str value))


(defn- read-json
  {:malli/schema [:=> [:cat [:maybe :string]] :any]}
  [text]
  (when-not (str/blank? text) (json/read-json text :key-fn keyword)))


(defn position-id
  {:malli/schema [:=> [:cat :string :string] :string]}
  [connection-id domain]
  (str connection-id "/" domain))


(defn connector-actor
  "The connector principal's actor id: the connection's own UUID."
  {:malli/schema [:=> [:cat :string] :uuid]}
  [connection-id]
  (or (parse-uuid connection-id) (UUID/nameUUIDFromBytes (.getBytes ^String connection-id StandardCharsets/UTF_8))))


(defn connector-label
  {:malli/schema [:=> [:cat :map] :string]}
  [connection]
  (str "Ozon · " (or (:account-label connection) (:provider-account-id connection))))


(def SyncRun
  [:map
   [:run-id Ref] [:connection-id Ref] [:trigger Trigger] [:triggered-by [:string {:min 1 :max 200}]]
   [:domains :string] [:starting-positions Text] [:started-at At] [:run-state RunState]
   [:finished-domains {:optional true} :string] [:unavailable-domains {:optional true} :string]
   [:found-listings {:optional true} :int] [:found-warehouses {:optional true} :int] [:found-operations {:optional true} :int]
   [:failed-domain {:optional true} Domain] [:reason {:optional true} Reason] [:saved-positions {:optional true} Text]
   [:consecutive-failures {:optional true} :int] [:next-attempt-at {:optional true} At]
   [:completed-at {:optional true} At] [:next-sync-at {:optional true} At] [:failed-at {:optional true} At]])


(def SyncPosition
  "A connection's position in one domain. `cursor` is the fetcher's traversal state (EDN); `batch-key` the
  cursor the last recorded batch was fetched from; `finished` holds for `run-id`."
  [:map
   [:position-id Ref] [:connection-id Ref] [:domain Domain] [:run-id Ref] [:cursor Text] [:batch-key Text]
   [:window-from {:optional true} At] [:window-to {:optional true} At] [:refetch-ids Text] [:finished :boolean]
   [:covered-until {:optional true} At]])


(def Observed
  "Fields every source record shares."
  [[:connection-id Ref] [:source-id [:string {:min 1 :max 500}]] [:revision [:int {:min 1}]]
   [:payload-hash [:string {:min 64 :max 64}]] [:adapter-version [:string {:min 1 :max 50}]]
   [:observed-at At] [:last-observed-at At] [:source-revision-id [:string {:min 1 :max 700}]]])


(def ExternalProduct
  (into [:map [:external-product-id [:string {:min 1 :max 600}]]]
        (concat Observed
                [[:name {:optional true} [:string {:max 2000}]] [:identifiers Text] [:category {:optional true} :string]
                 [:attributes {:optional true} Text] [:unit-and-pack {:optional true} :string] [:archived :boolean]
                 [:provider-updated-at {:optional true} At] [:facts Text]])))


(def ExternalWarehouse
  (into [:map [:external-warehouse-id [:string {:min 1 :max 600}]]]
        (concat Observed [[:name {:optional true} [:string {:max 2000}]] [:fulfilment-type [:enum "fbo" "fbs" "rfbs"]]])))


(def OperationKind
  [:enum "fbs-posting" "fbo-posting" "return" "rfbs-return" "supply" "removal"])


(def SourceOperation
  (into [:map [:source-operation-id [:string {:min 1 :max 700}]]]
        (concat Observed
                [[:kind OperationKind] [:status :string] [:terminal :boolean]
                 [:occurred-at {:optional true} At] [:planned-at {:optional true} At] [:provider-updated-at {:optional true} At]
                 [:related-operation {:optional true} :string] [:lines Text]
                 [:from-warehouse {:optional true} :string] [:to-warehouse {:optional true} :string]
                 [:complete :boolean] [:missing {:optional true} Text]])))


(def ValueType
  [:enum "string" "integer" "decimal" "boolean" "date" "dictionary" "unknown"])


(def FieldDefinition
  (into [:map [:field-definition-id [:string {:min 1 :max 700}]]]
        (concat Observed
                [[:category-id :string] [:type-id :string] [:attribute-id :string] [:name :string] [:value-type ValueType]
                 [:multiple :boolean] [:complex :boolean] [:unit {:optional true} :string] [:dictionary-id {:optional true} :string]])))


(def SourceStock
  [:map
   [:stock-id [:string {:min 1 :max 800}]] [:connection-id Ref] [:external-product-id :string]
   [:external-warehouse-id {:optional true} :string] [:source [:enum "fulfilment-totals" "warehouse-analytics"]]
   [:fulfilment-type {:optional true} [:enum "fbo" "fbs" "rfbs"]] [:present :int] [:reserved {:optional true} :int]
   [:provider-as-of {:optional true} At] [:observed-at At]])


(def SourceRevision
  [:map
   [:source-revision-id [:string {:min 1 :max 700}]] [:entity-id [:string {:min 1 :max 700}]] [:revision [:int {:min 1}]]
   [:payload-hash :string] [:adapter-version :string] [:facts Text] [:observed-at At]])


;; Events


(defevent marketplace-sync-started
  {:schema [:map [:run-id Ref] [:connection-id Ref] [:trigger Trigger] [:triggered-by :string] [:domains :string]
            [:starting-positions Text] [:started-at At] [:run-state [:= "open"]]]})


(defevent source-batch-recorded
  {:schema [:map [:position-id Ref] [:connection-id Ref] [:domain Domain] [:run-id Ref] [:cursor Text] [:batch-key Text]
            [:window-from {:optional true} At] [:window-to {:optional true} At] [:refetch-ids Text] [:finished :boolean]
            [:found-listings :int] [:found-warehouses :int] [:found-operations :int] [:recorded-at At]]})


(defevent source-domain-traversed
  {:schema [:map [:run-id Ref] [:connection-id Ref] [:domain Domain] [:covered-until At] [:traversed-at At]
            [:position-id Ref] [:finished-domains :string]]})


(defevent source-domain-unavailable
  {:schema [:map [:run-id Ref] [:domain Domain] [:unavailable-domains :string]]})


(defevent provider-throttled
  {:schema [:map [:connection-id Ref] [:resume-not-before At]]})


(defevent external-product-observed {:schema (into ExternalProduct [[:entity-id :string]])})
(defevent external-warehouse-observed {:schema (into ExternalWarehouse [[:entity-id :string] [:facts Text]])})
(defevent source-operation-observed {:schema (into SourceOperation [[:entity-id :string] [:facts Text]])})
(defevent attribute-definition-observed {:schema (into FieldDefinition [[:entity-id :string] [:facts Text]])})
(defevent source-stock-observed {:schema SourceStock})


(defevent external-product-seen {:schema [:map [:external-product-id :string] [:last-observed-at At]]})
(defevent external-warehouse-seen {:schema [:map [:external-warehouse-id :string] [:last-observed-at At]]})
(defevent source-operation-seen {:schema [:map [:source-operation-id :string] [:last-observed-at At]]})
(defevent attribute-definition-seen {:schema [:map [:field-definition-id :string] [:last-observed-at At]]})


(defevent marketplace-sync-completed
  {:schema [:map [:run-id Ref] [:connection-id Ref] [:unavailable-domains :string]
            [:found-listings :int] [:found-warehouses :int] [:found-operations :int]
            [:completed-at At] [:next-sync-at At] [:run-state [:= "completed"]] [:last-completed-sync-at At]
            [:readable-domains connections/Domains] [:consecutive-failures [:= 0]]
            ;; Absent: completion clears any failure wait.
            [:next-attempt-at {:optional true} At]]})


(defevent marketplace-sync-failed
  {:schema [:map [:run-id Ref] [:connection-id Ref] [:failed-domain Domain] [:reason Reason] [:saved-positions Text]
            [:consecutive-failures [:int {:min 1 :max 5}]] [:next-attempt-at {:optional true} At] [:failed-at At]
            [:run-state [:= "failed"]] [:readable-domains connections/Domains]]})


;; Models


(def ^:private observed-fields
  [:connection-id :source-id :revision :payload-hash :adapter-version :observed-at :last-observed-at :source-revision-id])


(defmodel sync-runs
  {:id     :run-id :store :catalogue :schema SyncRun :policy ::records/workspace-member
   :events {::marketplace-sync-started   {:create [:connection-id :trigger :triggered-by :domains :starting-positions
                                                   :started-at :run-state]}
            ::source-batch-recorded      {:set [:found-listings :found-warehouses :found-operations]}
            ::source-domain-traversed    {:set [:finished-domains]}
            ::source-domain-unavailable  {:set [:unavailable-domains]}
            ::marketplace-sync-completed {:set [:run-state :unavailable-domains :found-listings :found-warehouses
                                                :found-operations :completed-at :next-sync-at]}
            ::marketplace-sync-failed    {:set [:run-state :failed-domain :reason :saved-positions :failed-at
                                                :consecutive-failures :next-attempt-at]}}})


(defmodel sync-positions
  {:id     :position-id :store :catalogue :schema SyncPosition :policy ::records/workspace-member
   :events {::source-batch-recorded   {:set [:connection-id :domain :run-id :cursor :batch-key :window-from :window-to
                                             :refetch-ids :finished]}
            ::source-domain-traversed {:set [:covered-until]}}})


(defmodel external-products
  {:id     :external-product-id :store :catalogue :schema ExternalProduct :policy ::records/workspace-member
   :events {::external-product-observed {:set (into observed-fields [:name :identifiers :category :attributes :unit-and-pack
                                                                     :archived :provider-updated-at :facts])}
            ::external-product-seen     {:set [:last-observed-at]}}})


(defmodel external-warehouses
  {:id     :external-warehouse-id :store :catalogue :schema ExternalWarehouse :policy ::records/workspace-member
   :events {::external-warehouse-observed {:set (into observed-fields [:name :fulfilment-type])}
            ::external-warehouse-seen     {:set [:last-observed-at]}}})


(defmodel source-operations
  {:id     :source-operation-id :store :catalogue :schema SourceOperation :policy ::records/workspace-member
   :events {::source-operation-observed {:set (into observed-fields [:kind :status :terminal :occurred-at :planned-at
                                                                     :provider-updated-at :related-operation :lines
                                                                     :from-warehouse :to-warehouse :complete :missing])}
            ::source-operation-seen     {:set [:last-observed-at]}}})


(defmodel field-definitions
  {:id     :field-definition-id :store :catalogue :schema FieldDefinition :policy ::records/workspace-member
   :events {::attribute-definition-observed {:set (into observed-fields [:category-id :type-id :attribute-id :name :value-type
                                                                         :multiple :complex :unit :dictionary-id])}
            ::attribute-definition-seen     {:set [:last-observed-at]}}})


(defmodel source-stock
  {:id     :stock-id :store :catalogue :schema SourceStock :policy ::records/workspace-member
   :events {::source-stock-observed {:set [:connection-id :external-product-id :external-warehouse-id :source :fulfilment-type
                                           :present :reserved :provider-as-of :observed-at]}}})


(defmodel source-revisions
  {:id     :source-revision-id :store :catalogue :schema SourceRevision :policy ::records/workspace-member
   :events (into {} (for [event [::external-product-observed ::external-warehouse-observed ::source-operation-observed
                                 ::attribute-definition-observed]]
                      [event {:create [:entity-id :revision :payload-hash :adapter-version :facts :observed-at]}]))})


;; Access and facts


(defmethod runtime/policy ::connector-principal
  [_ {:keys [principal]} _]
  (some? (:service principal)))


(defmethod runtime/policy ::sync-actor
  [_ {:keys [principal scope]} {:keys [trigger]}]
  ;; The scheduler acts as the connector (a service); a member's Sync now needs an active membership.
  (if (:service principal)
    (= "schedule" trigger)
    (and (= "member" trigger)
         (boolean (some #(= (:workspace-id scope) (:workspace-id %)) (:memberships principal))))))


(defn- load-rows
  "Rows of `model-id` by id in the scope's workspace; missing ids are left out."
  {:malli/schema [:=> [:cat :map :qualified-keyword [:sequential :string]] [:map-of :string :map]]}
  [context model-id ids]
  (if (empty? ids)
    {}
    (let [definition (bel/get-model model-id)]
      (runtime/with-store context :catalogue :repeatable-read
        (fn [{:keys [tx scope] :as context}]
          (let [adapter (store/store context :catalogue)]
            (into {} (keep (fn [id] (some->> (store/-load adapter tx (store/store-scope scope) definition id) (vector id))))
                  (distinct ids))))))))


(defmethod runtime/coeffect ::connection-positions
  [_ context {:keys [connection-id]}]
  (if connection-id
    (into {} (map (fn [[_ row]] [(:domain row) row]))
          (load-rows context ::sync-positions (map #(position-id connection-id %) connector/domains)))
    {}))


(defmethod runtime/coeffect ::covered-until
  [_ context input]
  (into {} (keep (fn [[domain row]] (when (:covered-until row) [domain (:covered-until row)])))
        (runtime/coeffect ::connection-positions context input)))


(defmethod runtime/coeffect ::position
  [_ context {:keys [connection-id domain]}]
  (when connection-id (get (load-rows context ::sync-positions [(position-id connection-id domain)]) (position-id connection-id domain))))


;; Commands read through these coeffects, not the models: the connector principal is a service, and the
;; models' policy admits workspace members only.
(defmethod runtime/coeffect ::run
  [_ context {:keys [run-id]}]
  (when run-id (get (load-rows context ::sync-runs [run-id]) run-id)))


(defmethod runtime/coeffect ::connection
  [_ context {:keys [connection-id]}]
  (when connection-id (get (load-rows context ::connections/connections [connection-id]) connection-id)))


(def ^:private run-cofx
  [:run ::run (fn [_ input] (select-keys input [:run-id]))])


(def ^:private run-connection
  [:connection ::connection (fn [{:keys [run]} _] {:connection-id (:connection-id run)})])


(def ^:private run-positions
  [:positions ::connection-positions (fn [{:keys [run]} _] {:connection-id (:connection-id run)})])


;; Decisions


(defn- fail!
  {:malli/schema [:function [:=> [:cat :keyword :string] :nil] [:=> [:cat :keyword :string :map] :nil]]}
  ([type message] (fail! type message {}))
  ([type message data] (throw (ex-info message (assoc data :type type)))))


(defn- instant
  {:malli/schema [:=> [:cat [:maybe :string]] [:maybe validation/Instant]]}
  [text]
  (connections/at text))


(defn- later
  "The later of two ISO instants; covered-until never moves backwards."
  {:malli/schema [:=> [:cat [:maybe :string] :string] :string]}
  [a b]
  (if (and a (.isAfter ^Instant (instant a) (instant b))) a b))


(defn starting-positions
  "Window start per included dated domain: 30 days before the first run, else covered-until minus 1 day."
  {:malli/schema [:=> [:cat [:sequential :string] [:map-of :string :map] validation/Instant] [:map-of :string :string]]}
  [domains positions now]
  (into (sorted-map)
        (for [domain domains
              :when  (contains? ozon/dated-domains domain)
              :let   [covered (instant (get-in positions [domain :covered-until]))]]
          [domain (str (if covered (.minus covered ozon/overlap) (.minus now ozon/first-run-days)))])))


(def StartMarketplaceSync
  [:map
   [:attempt-id [:string {:min 1 :max 200}]]
   [:run-id Ref]
   [:connection-id Ref]
   [:trigger Trigger]])


(defcommand start-marketplace-sync
  {:input StartMarketplaceSync :policy ::sync-actor
   :cofx  [[:connection ::connection (fn [_ input] (select-keys input [:connection-id]))]
           [:positions ::connection-positions (fn [_ input] (select-keys input [:connection-id]))]]
   :emits #{::marketplace-sync-started}}
  [{:keys [actor now connection positions]} {:keys [run-id connection-id trigger]}]
  (let [resume (instant (:resume-not-before connection))]
    (cond
      (nil? connection) (fail! :unknown-connection "Unknown connection")
      ;; One open run per connection: Sync now joins it, the scheduler resumes it.
      (= "open" (:run-state connection)) {}
      (= "paused" (:sync-state connection)) (fail! :connection-paused "Connection paused")
      (= "rejected" (:credentials-state connection)) (fail! :needs-credentials "Needs credentials")
      (and resume (.isAfter ^Instant resume now)) (fail! :throttled "Throttled" {:resume-not-before (str resume)})
      (and (= "schedule" trigger) (not (connections/due? connection now))) {}
      :else
      (let [domains (decode (:readable-domains connection))]
        {:events [{:type ::marketplace-sync-started
                   :data {:run-id             run-id :connection-id connection-id :trigger trigger
                          :triggered-by       (if (= "schedule" trigger) (str "connector:" connection-id) (str actor))
                          :domains            (encode domains)
                          :starting-positions (write-json (starting-positions domains positions now))
                          :started-at         (str now) :run-state  "open"}}]}))))


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


(def RecordSourceObservations
  [:map
   [:run-id Ref]
   [:domain Domain]
   [:batch-key Text]
   [:next-position [:map [:cursor Text] [:window-from {:optional true} At] [:window-to {:optional true} At]]]
   [:domain-finished :boolean]
   [:covered-until {:optional true} At]
   [:observations [:vector Observation]]
   [:adapter-version [:string {:min 1 :max 50}]]])


(def ^:private personal-keys
  #{:buyer :customer :addressee :address})


(defn- personal?
  {:malli/schema [:=> [:cat :any] :boolean]}
  [observations]
  (boolean (some #(and (map-entry? %) (contains? personal-keys (key %)) (some? (val %)))
                 (tree-seq coll? seq observations))))


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


(def ^:private entity-models
  {"product"              [::external-products :external-product-id]
   "product-aliases"      [::external-products :external-product-id]
   "product-attributes"   [::external-products :external-product-id]
   "warehouse"            [::external-warehouses :external-warehouse-id]
   "operation"            [::source-operations :source-operation-id]
   "attribute-definition" [::field-definitions :field-definition-id]
   "stock"                [::external-products :external-product-id]})


(declare group-by-model observe-product)


(defmethod runtime/coeffect ::existing
  [_ context {:keys [connection-id observations]}]
  (if connection-id
    (reduce (fn [acc [model ids]] (merge acc (load-rows context model ids)))
            {}
            (group-by-model connection-id observations))
    {}))


(defn- group-by-model
  {:malli/schema [:=> [:cat :string [:sequential :map]] [:map-of :qualified-keyword [:vector :string]]]}
  [connection-id observations]
  (reduce (fn [acc o] (update acc (first (entity-models (:kind o))) (fnil conj []) (entity-id connection-id o)))
          {} observations))


(defn- bindings
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


(defn- product-facts
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


(defn- observe
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
          [[{:type ::source-stock-observed
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
                                        "warehouse" [::external-warehouses :external-warehouse-id ::external-warehouse-seen
                                                     ::external-warehouse-observed]
                                        "operation" [::source-operations :source-operation-id ::source-operation-seen
                                                     ::source-operation-observed]
                                        "attribute-definition" [::field-definitions :field-definition-id
                                                                ::attribute-definition-seen ::attribute-definition-observed])
            _ model
            facts (dissoc observation :kind)
            hash (payload-hash facts)]
        (if (= hash (:payload-hash row))
          [[{:type seen :data {id-key id :last-observed-at now}}] (assoc-in rows [id :last-observed-at] now) nil]
          (let [common (observed-common {:id-key id-key} row {:id id :hash hash :adapter adapter} connection-id
                                        (or (:source-id observation) (:attribute-id observation)) now)
                data   (merge common
                              {:facts (write-json (canonical facts))}
                              (case kind
                                "warehouse" (cond-> {:fulfilment-type (:fulfilment-type observation)}
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
  (let [id    (entity-id connection-id observation)
        facts (product-facts row observation)
        hash  (payload-hash facts)]
    (if (= hash (:payload-hash row))
      [[{:type ::external-product-seen :data {:external-product-id id :last-observed-at now}}]
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
        [[{:type ::external-product-observed :data data}] (assoc rows id data) (when-not row "product")]))))


(defn- refetch
  "Known non-terminal operations of a dated domain stay on the refetch list until terminal."
  {:malli/schema [:=> [:cat [:maybe :string] [:sequential :map]] :string]}
  [previous observations]
  (let [ops      (filter #(= "operation" (:kind %)) observations)
        terminal (set (map :source-id (filter :terminal ops)))
        open     (map :source-id (remove :terminal ops))]
    (write-json (vec (distinct (remove terminal (concat (read-json previous) open)))))))


(defn current-key
  "The cursor the next batch of `domain` must be fetched from: the committed one within the run, else the start."
  {:malli/schema [:=> [:cat :string [:maybe :map]] :string]}
  [run-id position]
  (cond (not= run-id (:run-id position)) ""
        (:finished position) "finished"
        :else (:cursor position)))


(defcommand record-source-observations
  {:input RecordSourceObservations :policy ::connector-principal
   :cofx  [run-cofx run-connection
           [:position ::position (fn [{:keys [run]} input] {:connection-id (:connection-id run) :domain (:domain input)})]
           [:existing ::existing (fn [{:keys [run]} input] {:connection-id (:connection-id run) :observations (:observations input)})]]
   :emits #{::source-batch-recorded ::source-domain-traversed ::external-product-observed ::external-product-seen
            ::external-warehouse-observed ::external-warehouse-seen ::source-operation-observed ::source-operation-seen
            ::attribute-definition-observed ::attribute-definition-seen ::source-stock-observed}}
  [{:keys [now run connection position existing]}
   {:keys [run-id domain batch-key next-position domain-finished covered-until observations adapter-version]}]
  (cond
    (nil? run) (fail! :unknown-run "Unknown run")
    (not= "open" (:run-state run)) (fail! :run-not-open "Run not open")
    (not= "active" (:sync-state connection)) (fail! :connection-paused "Connection paused")
    ;; (run, domain, batch key) was recorded already: a repeat records nothing new.
    (and (= run-id (:run-id position)) (= batch-key (:batch-key position))) {}
    (not= batch-key (current-key run-id position)) (fail! :stale-position "Stale position")
    (personal? observations) (fail! :personal-data-present "Personal data present")
    :else
    (let [connection-id  (:connection-id run)
          at             (str now)
          [events _ new] (reduce (fn [[events rows new] o]
                                   (let [[e rows kind] (observe rows o connection-id at adapter-version)]
                                     [(into events e) rows (cond-> new kind (conj kind))]))
                                 [[] existing []] observations)
          found          (frequencies new)
          finished       (decode (:finished-domains run))
          covered        (later (:covered-until position) (or covered-until (:started-at run)))]
      {:events (cond-> (conj events
                             {:type ::source-batch-recorded
                              :data (merge {:position-id      (position-id connection-id domain)                  :connection-id connection-id
                                            :domain           domain :run-id run-id :cursor (:cursor next-position) :batch-key batch-key
                                            :refetch-ids      (refetch (:refetch-ids position) observations)
                                            :finished         domain-finished
                                            :found-listings   (+ (:found-listings run 0) (found "product" 0))
                                            :found-warehouses (+ (:found-warehouses run 0) (found "warehouse" 0))
                                            :found-operations (+ (:found-operations run 0) (found "operation" 0))
                                            :recorded-at      at}
                                           (select-keys next-position [:window-from :window-to]))})
                 domain-finished
                 (conj {:type ::source-domain-traversed
                        :data {:run-id           run-id :connection-id connection-id :domain domain :covered-until covered
                               :traversed-at     at :position-id       (position-id connection-id domain)
                               :finished-domains (encode (conj finished domain))}}))})))


(def RecordFetchSetback
  [:map [:run-id Ref] [:domain Domain] [:setback [:enum "unavailable" "throttled"]] [:resume-not-before {:optional true} At]])


(defcommand record-fetch-setback
  {:input RecordFetchSetback :policy ::connector-principal :cofx [run-cofx]
   :emits #{::source-domain-unavailable ::provider-throttled}}
  [{:keys [run]} {:keys [run-id domain setback resume-not-before]}]
  (cond
    (nil? run) (fail! :unknown-run "Unknown run")
    (not= "open" (:run-state run)) {}
    (= "unavailable" setback)
    {:events [{:type ::source-domain-unavailable
               :data {:run-id run-id :domain domain :unavailable-domains (encode (conj (decode (:unavailable-domains run)) domain))}}]}
    :else
    {:events [{:type ::provider-throttled :data {:connection-id (:connection-id run) :resume-not-before resume-not-before}}]}))


(defn coverage
  "Sync Run Coverage: finished, unavailable and unfinished domains of a run, in domain order."
  {:malli/schema [:=> [:cat :map] [:map [:run-id :string] [:finished-domains [:vector :string]]
                                   [:unavailable-domains [:vector :string]] [:unfinished-domains [:vector :string]]]]}
  [run]
  (let [finished    (decode (:finished-domains run))
        unavailable (decode (:unavailable-domains run))
        done        (set (concat finished unavailable))]
    {:run-id              (:run-id run)
     :finished-domains    finished
     :unavailable-domains unavailable
     :unfinished-domains  (filterv (complement done) (decode (:domains run)))}))


(def CompleteMarketplaceSync
  [:map
   [:run-id Ref]
   [:outcome [:enum "completed" "failed"]]
   [:failed-domain {:optional true} Domain]
   [:reason {:optional true} Reason]
   [:unavailable-domains {:optional true} [:vector Domain]]
   [:resume-not-before {:optional true} At]])


(def sync-interval
  connections/sync-interval)


(defcommand complete-marketplace-sync
  {:input CompleteMarketplaceSync :policy ::connector-principal :cofx [run-cofx run-connection run-positions]
   :emits #{::marketplace-sync-completed ::marketplace-sync-failed ::connections/connection-credentials-rejected
            ::provider-throttled}}
  [{:keys [now run connection positions]} {:keys [run-id outcome failed-domain reason unavailable-domains resume-not-before]}]
  (cond
    (nil? run) (fail! :unknown-run "Unknown run")
    ;; A repeat for a closed run returns the recorded outcome.
    (not= "open" (:run-state run)) {}
    :else
    (let [{:keys [unfinished-domains] :as cov} (coverage (update run :unavailable-domains
                                                                 #(encode (concat (decode %) unavailable-domains))))
          unavailable (:unavailable-domains cov)
          connection-id (:connection-id run)
          at (str now)
          readable (or (not-empty (encode (remove (set unavailable) (decode (:readable-domains connection)))))
                       (:readable-domains connection))
          counts {:found-listings   (:found-listings run 0)   :found-warehouses (:found-warehouses run 0)
                  :found-operations (:found-operations run 0)}]
      (if (= "completed" outcome)
        (do (when (seq unfinished-domains)
              (fail! :domains-unfinished "Domains unfinished" {:domains unfinished-domains}))
            {:events [{:type ::marketplace-sync-completed
                       :data (merge counts
                                    {:run-id               run-id      :connection-id          connection-id :unavailable-domains (encode unavailable)
                                     :completed-at         at          :next-sync-at           (str (.plus ^Instant now ^Duration sync-interval))
                                     :run-state            "completed" :last-completed-sync-at at :readable-domains               readable
                                     :consecutive-failures 0})}]})
        (let [_        (when-not (and failed-domain reason)
                         (fail! :validation "A failed run names its domain and reason"))
              failures (min connections/max-failures (inc (or (:consecutive-failures connection) 0)))
              wait     (get connections/failure-waits (dec failures))
              saved    (write-json (into (sorted-map) (keep (fn [[d row]] (when (:covered-until row) [d (:covered-until row)])))
                                         positions))]
          {:events (cond-> [{:type ::marketplace-sync-failed
                             :data (cond-> {:run-id    run-id :connection-id   connection-id :failed-domain        failed-domain
                                            :reason    reason :saved-positions saved         :consecutive-failures failures
                                            :failed-at at     :run-state       "failed"      :readable-domains     readable}
                                     wait (assoc :next-attempt-at (str (.plus ^Instant now ^Duration wait))))}]
                     (= "credentials rejected" reason)
                     (conj {:type ::connections/connection-credentials-rejected
                            :data {:connection-id connection-id :run-id run-id :credentials-state "rejected" :rejected-at at}})
                     resume-not-before
                     (conj {:type ::provider-throttled :data {:connection-id connection-id :resume-not-before resume-not-before}}))})))))


;; Reads


(defreadmodel sync-run-coverage
  {:input [:map [:run-id Ref]] :output [:maybe :map] :policy ::records/workspace-member :cofx [run-cofx]}
  [{:keys [run]} _]
  (some-> run coverage))


(def SyncStatusAndCoverage
  [:map
   [:connection-id Ref]
   [:current-run {:optional true} [:map [:run-id Ref] [:trigger Trigger] [:triggered-by :string] [:started-at validation/Instant]
                                   [:finished-domains [:vector Domain]] [:unfinished-domains [:vector Domain]]]]
   [:last-outcome {:optional true} [:enum "completed" "failed"]]
   [:last-failure {:optional true} [:map [:failed-domain Domain] [:reason Reason] [:failed-at validation/Instant]]]
   [:covered-until [:vector [:map [:domain Domain] [:readable? :boolean] [:covered-until {:optional true} validation/Instant]]]]
   [:next-sync-at {:optional true} validation/Instant]
   [:found-counts [:map [:listings :int] [:warehouses :int] [:operations :int]]]
   [:scheduling-stopped? :boolean]
   [:can-sync-now :boolean]])


(defn sync-status
  "Sync Status and Coverage: plain categories only, never payloads, cursors or secrets."
  {:malli/schema [:=> [:cat :map [:maybe :map] [:map-of :string :map] :boolean] SyncStatusAndCoverage]}
  [connection run positions member?]
  (let [state  (:run-state run)
        next   (connections/next-sync-at connection)
        closed (when (#{"completed" "failed"} state) run)]
    (cond-> {:connection-id       (:connection-id connection)
             :covered-until       (connections/domain-rows connection (into {} (keep (fn [[d row]] (some->> (:covered-until row) (vector d))))
                                                                            positions))
             :found-counts        {:listings   (:found-listings closed 0)   :warehouses (:found-warehouses closed 0)
                                   :operations (:found-operations closed 0)}
             :scheduling-stopped? (connections/scheduling-stopped? connection)
             :can-sync-now        (and member? (= "active" (:sync-state connection)) (= "valid" (:credentials-state connection)))}
      (= "open" state)
      (assoc :current-run (-> (select-keys run [:run-id :trigger :triggered-by])
                              (assoc :started-at (instant (:started-at run)))
                              (merge (select-keys (coverage run) [:finished-domains :unfinished-domains]))))
      closed (assoc :last-outcome state)
      (and (= "failed" state) (:failed-domain run) (:reason run) (:failed-at run))
      (assoc :last-failure {:failed-domain (:failed-domain run)       :reason (:reason run)
                            :failed-at     (instant (:failed-at run))})
      next (assoc :next-sync-at next))))


(defmethod runtime/coeffect ::member
  [_ {:keys [principal scope]} _]
  (boolean (some #(= (:workspace-id scope) (:workspace-id %)) (:memberships principal))))


(defreadmodel sync-status-and-coverage
  {:input [:map [:connection-id Ref]] :output SyncStatusAndCoverage :policy ::records/workspace-member
   :cofx  [[:connection ::connection (fn [_ input] (select-keys input [:connection-id]))]
           [:run ::run (fn [{:keys [connection]} _] {:run-id (:run-id connection)})]
           [:positions ::connection-positions (fn [_ input] (select-keys input [:connection-id]))]
           [:member? ::member (constantly {})]]}
  [{:keys [connection run positions member?]} _]
  (when-not connection (fail! :unknown-connection "Unknown connection"))
  ;; The connection's `run-id` may name the run that met a credential rejection; only sync runs count.
  (sync-status connection run positions member?))


;; Connections due for sync (spans workspaces; read only by the scheduler)


(defn- workspace-ids
  "Every workspace holding a marketplace connection, from the shared RDF repository."
  {:malli/schema [:=> [:cat :map] [:vector :uuid]]}
  [system]
  (let [repo      (:repo (get-in system [:stores :catalogue]))
        predicate (get-in (rdf-adapter/vocabulary (bel/get-model ::connections/connections)) [:fields :workspace-id])]
    (if-not repo
      []
      (rdf/with-transaction [conn {:repo repo :level :snapshot}]
        (vec (sort (distinct (keep #(some-> (:ws %) str parse-uuid)
                                   (rdf/select conn '{:select [?ws] :where [[?s ?p ?ws]]} {:p predicate})))))))))


(defn- workspace-connections
  {:malli/schema [:=> [:cat :map :uuid] [:vector :map]]}
  [system workspace-id]
  (records/all-records {:bel/system system :scope {:workspace-id workspace-id}} ::connections/connections :connection-id))


(defn due-rows
  "Connections Due for Sync, oldest due first."
  {:malli/schema [:=> [:cat [:sequential :map] validation/Instant] [:vector [:map [:workspace-id :string] [:connection-id :string]
                                                                             [:open-run {:optional true} :string] [:due-since :string]]]]}
  [connections now]
  (->> connections
       (keep (fn [c]
               (when (connections/due? c now)
                 (cond-> {:workspace-id (:workspace-id c)                  :connection-id (:connection-id c)
                          :due-since    (str (connections/next-sync-at c))}
                   (= "open" (:run-state c)) (assoc :open-run (:run-id c))))))
       (sort-by (juxt #(instant (:due-since %)) :connection-id))
       vec))


(defn- look?
  "The scheduler looks once a minute: true at most once per minute per `:marketplace` resource."
  {:malli/schema [:=> [:cat [:maybe :map] validation/Instant] :boolean]}
  [marketplace now]
  (if-let [last-look (:last-look marketplace)]
    (let [minute (.truncatedTo now ChronoUnit/MINUTES)]
      (not= minute (first (swap-vals! last-look (constantly minute)))))
    true))


(defmethod runtime/coeffect ::due-connections
  [_ {:keys [bel/system]} _]
  (let [now (Instant/now)]
    (if (look? (get-in system [:resources :marketplace]) now)
      (due-rows (mapcat #(workspace-connections system %) (workspace-ids system)) now)
      [])))


(defn run-id-for
  "A stable run id for one due period, so repeated ticks and retries propose the same run."
  {:malli/schema [:=> [:cat :string :string] :string]}
  [connection-id due-since]
  (str (UUID/nameUUIDFromBytes (.getBytes (str connection-id "/" due-since) StandardCharsets/UTF_8))))


(defprocessor ^{:malli/schema [:=> [:cat [:map [:workspace-id :string] [:connection-id :string] [:due-since :string]]]
                               bel/ProcessorRequest]}
  marketplace-sync-scheduler
  {:todo ::due-connections}
  [{:keys [workspace-id connection-id due-since]}]
  {:type  :command :id ::start-marketplace-sync :scope {:workspace-id (parse-uuid workspace-id)}
   :actor (connector-actor connection-id)
   :input {:attempt-id    (str "schedule/" due-since) :run-id  (run-id-for connection-id due-since)
           :connection-id connection-id               :trigger "schedule"}})


;; Fetcher


(defn- context-for
  "What a domain step may know: the run's window, refetch ids and lookups over the connection's listings."
  {:malli/schema [:=> [:cat :map [:maybe :map] [:fn fn?]] ozon/Context]}
  [run position products]
  (let [from     (get (read-json (:starting-positions run)) (keyword (:domain position)))
        listings (delay (vals (products)))
        skus     (fn [] (for [p     @listings
                              b     (read-json (:identifiers p))
                              :when (and (= "sku" (:type b)) (:active b))]
                          (:value b)))]
    (cond-> {:refetch-ids  (vec (read-json (:refetch-ids position)))
             :skus         skus
             :categories   (fn [] (distinct (for [p     @listings
                                                  :let  [pair (some-> (:category p) (str/split #"/"))]
                                                  :when (= 2 (count pair))]
                                              pair)))
             :sku->product (fn [] (into {} (for [p     @listings
                                                 b     (read-json (:identifiers p))
                                                 :when (and (= "sku" (:type b)) (:active b))]
                                             [(:value b) (:source-id p)])))}
      from (assoc :window {:from from :to (:started-at run)}))))


(def FetchItem
  [:map [:workspace-id :string] [:run :map] [:positions [:map-of :string :map]] [:env [:maybe ozon/Env]]
   [:products [:fn fn?]]])


(defn fetch-request
  "Check the run's remaining work and read its next batch: the command that records it, marks a setback or
  closes the run. Reads the provider outside any store transaction."
  {:malli/schema [:=> [:cat FetchItem] bel/ProcessorRequest]}
  [{:keys [workspace-id run positions env products]}]
  (let [connection-id (:connection-id run)
        run-id (:run-id run)
        request (fn [id input] {:type  :command :id id :scope {:workspace-id (parse-uuid workspace-id)}
                                :actor (connector-actor connection-id) :input input})
        {:keys [unfinished-domains unavailable-domains]} (coverage run)]
    (if-let [domain (first unfinished-domains)]
      (let [position  (assoc (get positions domain) :domain domain)
            batch-key (current-key run-id position)
            state     (if (= "" batch-key) {} (edn/read-string batch-key))
            context   (context-for run position products)]
        (try
          (when-not env (ozon/fail! :credentials-rejected))
          (let [{:keys [observations state finished?]} (ozon/step domain env context state)]
            (request ::record-source-observations
                     {:run-id          run-id :domain          domain       :batch-key       batch-key
                      :next-position   (merge {:cursor (pr-str state)}
                                              (when-let [{:keys [from to]} (:window context)] {:window-from from :window-to to}))
                      :domain-finished finished? :observations observations :adapter-version ozon/adapter-version}))
          (catch clojure.lang.ExceptionInfo error
            (let [{:keys [type resume-not-before]} (ex-data error)]
              (case type
                :unavailable (request ::record-fetch-setback {:run-id run-id :domain domain :setback "unavailable"})
                :throttled (request ::record-fetch-setback {:run-id            run-id            :domain domain :setback "throttled"
                                                            :resume-not-before resume-not-before})
                (:credentials-rejected :retries-exhausted :repeated-cursor :incomplete-page)
                (request ::complete-marketplace-sync {:run-id run-id         :outcome             "failed"            :failed-domain domain
                                                      :reason (reasons type) :unavailable-domains unavailable-domains})
                (throw error))))))
      (request ::complete-marketplace-sync {:run-id run-id :outcome "completed" :unavailable-domains unavailable-domains}))))


(defn- fetchable?
  {:malli/schema [:=> [:cat :map validation/Instant] :boolean]}
  [{:keys [run-state sync-state credentials-state resume-not-before]} now]
  (and (= "open" run-state) (= "active" sync-state) (= "valid" credentials-state)
       (not (some-> (instant resume-not-before) (.isAfter now)))))


(defmethod runtime/coeffect ::runs-to-fetch
  [_ {:keys [bel/system]} _]
  (let [now         (Instant/now)
        marketplace (get-in system [:resources :marketplace])]
    (vec (for [workspace-id (workspace-ids system)
               :let         [context {:bel/system system :scope {:workspace-id workspace-id}}]
               connection   (workspace-connections system workspace-id)
               :when        (fetchable? connection now)
               :let         [run (get (load-rows context ::sync-runs [(:run-id connection)]) (:run-id connection))]
               :when        (= "open" (:run-state run))]
           {:workspace-id (str workspace-id)
            :run          run
            :positions    (runtime/coeffect ::connection-positions context {:connection-id (:connection-id connection)})
            :env          (try (some->> (connector/api-key marketplace workspace-id (:secret-ref connection))
                                        (ozon/env marketplace (:provider-account-id connection)))
                               (catch Exception error
                                 (mu/log ::credentials-unavailable :error-type (.getName (class error)))
                                 nil))
            :products     #(into {} (filter (fn [[_ p]] (= (:connection-id connection) (:connection-id p))))
                                 (map (juxt :external-product-id identity)
                                      (records/all-records context ::external-products :external-product-id)))}))))


;; ponytail: the fetcher shares the journal-delivery worker thread, one batch per open run per second; move it
;; to its own executor if provider latency delays journal delivery.
(defprocessor ^{:malli/schema [:=> [:cat FetchItem] bel/ProcessorRequest]} marketplace-fetcher
  {:todo ::runs-to-fetch}
  [item]
  (fetch-request item))
