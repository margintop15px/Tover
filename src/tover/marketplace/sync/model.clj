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
    [clojure.edn :as edn]
    [clojure.string :as str]
    [com.brunobonacci.mulog :as mu]
    [tover.catalogues.records :as records]
    [tover.inventory.actors :as actors]
    [tover.marketplace.connections.model :as connections]
    [tover.marketplace.connector :as connector]
    [tover.marketplace.sync.observations :as observations]
    [tover.marketplace.sync.ozon :as ozon]
    [tover.shared.rdf :as rdf])
  (:import
    [java.nio.charset StandardCharsets]
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
  {:credentials-rejected "credentials rejected"         :retries-exhausted "retries exhausted"
   :repeated-cursor      "repeated cursor"              :incomplete-page   "incomplete page"
   :unexpected-response  "unexpected provider response"})


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


(defmethod actors/principal-label "connector"
  [context _ connection-id]
  (some-> (runtime/read-model context ::connections/connections {:connection-id connection-id}) connector-label))


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
   [:covered-until {:optional true} At] [:first-covered-at {:optional true} At]])


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
                 [:provider-updated-at {:optional true} At] [:facts Text]
                 [:cost {:optional true} :string] [:cost-currency {:optional true} :string]
                 [:aliases-observed-at {:optional true} At]])))


(def ExternalWarehouse
  (into [:map [:external-warehouse-id [:string {:min 1 :max 600}]]]
        (concat Observed [[:name {:optional true} [:string {:max 2000}]] [:warehouse-type observations/WarehouseType]
                          [:active {:optional true} :boolean]])))


(def SourceOperation
  "`handed-over-at` is the handover time last observed on a posting, which its cancelled revisions keep as
  `occurred-at`. `refetch-key` is what the provider looks the operation up by when that is not its source id."
  (into [:map [:source-operation-id [:string {:min 1 :max 700}]]]
        (concat Observed
                [[:kind observations/OperationKind] [:status :string] [:terminal :boolean]
                 [:occurred-at {:optional true} At] [:planned-at {:optional true} At] [:provider-updated-at {:optional true} At]
                 [:related-operation {:optional true} :string] [:lines Text]
                 [:from-warehouse {:optional true} :string] [:to-warehouse {:optional true} :string]
                 [:handed-over-at {:optional true} At] [:refetch-key {:optional true} :string] [:return-schema {:optional true} [:enum "fbo" "fbs"]]
                 [:compensated {:optional true} :boolean] [:disposed {:optional true} :boolean]
                 [:complete :boolean] [:missing {:optional true} Text]])))


(def FieldDefinition
  (into [:map [:field-definition-id [:string {:min 1 :max 700}]]]
        (concat Observed
                [[:category-id :string] [:type-id :string] [:attribute-id :string] [:name :string] [:value-type observations/ValueType]
                 [:multiple :boolean] [:complex :boolean] [:separates-variants :boolean] [:unit {:optional true} :string] [:dictionary-id {:optional true} :string]])))


(def SourceStock
  "`parts` are the stated rows the figure adds up, as JSON [part present reserved]: one per SKU of the listing."
  [:map
   [:stock-id [:string {:min 1 :max 2000}]] [:connection-id Ref] [:run-id Ref] [:external-product-id :string]
   [:external-warehouse-id {:optional true} :string] [:source [:enum "fulfilment-totals" "warehouse-stock" "warehouse-analytics"]]
   [:fulfilment-type [:enum "fbo" "fbs" "rfbs" "fbp"]] [:present :int] [:reserved {:optional true} :int] [:parts Text]
   [:provider-as-of {:optional true} At] [:observed-at At]])


(def StockPass
  "A finished traversal of one domain by one run. `asked`, JSON source ids of the listings a stock-by-warehouse
  pass speaks for: one of them without a row has 0."
  [:map
   [:pass-id Ref] [:run-id Ref] [:connection-id Ref] [:domain Domain] [:traversed-at At] [:asked {:optional true} Text]])


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
            [:position-id Ref] [:finished-domains :string] [:first-covered-at At] [:pass-id Ref] [:asked {:optional true} Text]]})


(defevent source-domain-unavailable
  {:schema [:map [:run-id Ref] [:connection-id Ref] [:domain Domain] [:unavailable-domains :string] [:readable-domains connections/Domains]]})


(defevent provider-throttled
  {:schema [:map [:connection-id Ref] [:resume-not-before At]]})


(defevent external-product-observed {:schema (into ExternalProduct [[:entity-id :string]])})
(defevent external-warehouse-observed {:schema (into ExternalWarehouse [[:entity-id :string] [:facts Text]])})
(defevent source-operation-observed {:schema (into SourceOperation [[:entity-id :string] [:facts Text]])})
(defevent attribute-definition-observed {:schema (into FieldDefinition [[:entity-id :string] [:facts Text]])})
(defevent source-stock-observed {:schema SourceStock})


(defevent external-product-seen {:schema [:map [:external-product-id :string] [:last-observed-at At]]})
(defevent external-warehouse-seen {:schema [:map [:external-warehouse-id :string] [:last-observed-at At] [:active [:= true]]]})
(defevent source-operation-seen {:schema [:map [:source-operation-id :string] [:last-observed-at At]]})
(defevent attribute-definition-seen {:schema [:map [:field-definition-id :string] [:last-observed-at At]]})
(defevent external-product-aliases-seen {:schema [:map [:external-product-id :string] [:aliases-observed-at At]]})


;; After a full pass that finished without errors, bindings the pass did not see become inactive.
(defevent external-product-bindings-expired {:schema [:map [:external-product-id :string] [:identifiers Text]]})
(defevent external-warehouse-expired {:schema [:map [:external-warehouse-id :string] [:active [:= false]]]})


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
            ::source-domain-traversed {:set [:covered-until :first-covered-at]}}})


(defmodel external-products
  {:id     :external-product-id :store :catalogue :schema ExternalProduct :policy ::records/workspace-member
   :events {::external-product-observed         {:set (into observed-fields [:name :identifiers :category :attributes :unit-and-pack
                                                                             :archived :provider-updated-at :facts :cost
                                                                             :cost-currency])}
            ::external-product-seen             {:set [:last-observed-at]}
            ::external-product-aliases-seen     {:set [:aliases-observed-at]}
            ::external-product-bindings-expired {:set [:identifiers]}}})


(defmodel external-warehouses
  {:id     :external-warehouse-id :store :catalogue :schema ExternalWarehouse :policy ::records/workspace-member
   :events {::external-warehouse-observed {:set (into observed-fields [:name :warehouse-type :active])}
            ::external-warehouse-seen     {:set [:last-observed-at :active]}
            ::external-warehouse-expired  {:set [:active]}}})


(defmodel source-operations
  {:id     :source-operation-id :store :catalogue :schema SourceOperation :policy ::records/workspace-member
   :events {::source-operation-observed {:set (into observed-fields [:kind :status :terminal :occurred-at :planned-at
                                                                     :provider-updated-at :related-operation :lines
                                                                     :from-warehouse :to-warehouse :complete :missing
                                                                     :handed-over-at :refetch-key :return-schema :compensated :disposed])}
            ::source-operation-seen     {:set [:last-observed-at]}}})


(defmodel field-definitions
  {:id     :field-definition-id :store :catalogue :schema FieldDefinition :policy ::records/workspace-member
   :events {::attribute-definition-observed {:set (into observed-fields [:category-id :type-id :attribute-id :name :value-type
                                                                         :multiple :complex :separates-variants :unit
                                                                         :dictionary-id])}
            ::attribute-definition-seen     {:set [:last-observed-at]}}})


(defmodel source-stock
  {:id     :stock-id :store :catalogue :schema SourceStock :policy ::records/workspace-member
   :events {::source-stock-observed {:set [:connection-id :run-id :external-product-id :external-warehouse-id :source
                                           :fulfilment-type :present :reserved :parts :provider-as-of :observed-at]}}})


(defmodel stock-passes
  {:id     :pass-id :store :catalogue :schema StockPass :policy ::records/workspace-member
   :events {::source-domain-traversed {:create [:run-id :connection-id :domain :traversed-at :asked]}}})


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


(defn load-rows
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


(defn- own-connector!
  "The connector principal of one connection never acts for another one."
  {:malli/schema [:=> [:cat :any :string] :nil]}
  [actor connection-id]
  (when (not= actor (connector-actor connection-id)) (fail! :not-permitted "Not permitted")))


(defn- readable-without
  "The connection's readable domains without `unavailable`; one that can read nothing more keeps them."
  {:malli/schema [:=> [:cat :map [:sequential :string]] connections/Domains]}
  [connection unavailable]
  (or (not-empty (encode (remove (set unavailable) (decode (:readable-domains connection)))))
      (:readable-domains connection)))


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


(defn- window-start
  "Where the run's window of a dated domain starts; nil for a full domain."
  {:malli/schema [:=> [:cat :map :string] [:maybe :string]]}
  [run domain]
  (when (contains? ozon/dated-domains domain)
    (get (observations/read-json (:starting-positions run)) (keyword domain))))


(defcommand start-marketplace-sync
  {:input StartMarketplaceSync :policy ::sync-actor
   :cofx  [[:connection ::connection (fn [_ input] (select-keys input [:connection-id]))]
           [:positions ::connection-positions (fn [_ input] (select-keys input [:connection-id]))]]
   :emits #{::marketplace-sync-started}}
  [{:keys [actor now connection positions]} {:keys [run-id connection-id trigger]}]
  (let [resume (instant (:resume-not-before connection))]
    (cond
      (nil? connection) (fail! :unknown-connection "Unknown connection")
      (and (= "schedule" trigger) (not= actor (connector-actor connection-id))) (fail! :not-permitted "Not permitted")
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
                          :starting-positions (observations/write-json (starting-positions domains positions now))
                          :started-at         (str now) :run-state  "open"}}]}))))


(def RecordSourceObservations
  "`resume-not-before` (the provider asks for a long wait) and `domain-unavailable` (it answered 403) come with no
  observations and leave the position where it is."
  [:map
   [:run-id Ref]
   [:domain Domain]
   [:batch-key Text]
   [:next-position [:map [:cursor Text] [:window-from {:optional true} At] [:window-to {:optional true} At]
                    ;; Refetch ids of records listed and not read yet.
                    [:pending-ids {:optional true} [:vector :string]]]]
   [:domain-finished :boolean]
   [:covered-until {:optional true} At]
   [:observations [:vector observations/Observation]]
   [:adapter-version [:string {:min 1 :max 50}]]
   [:resume-not-before {:optional true} At]
   [:domain-unavailable {:optional true} :boolean]])


(def ^:private personal-keys
  #{:buyer :customer :addressee :address})


(defn- personal?
  {:malli/schema [:=> [:cat :any] :boolean]}
  [observations]
  (boolean (some #(and (map-entry? %) (contains? personal-keys (key %)) (some? (val %)))
                 (tree-seq coll? seq observations))))


(def ^:private entity-models
  {"product"              [::external-products :external-product-id]
   "product-aliases"      [::external-products :external-product-id]
   "product-attributes"   [::external-products :external-product-id]
   "product-cost"         [::external-products :external-product-id]
   "warehouse"            [::external-warehouses :external-warehouse-id]
   "operation"            [::source-operations :source-operation-id]
   "attribute-definition" [::field-definitions :field-definition-id]
   "stock"                [::external-products :external-product-id]})


(declare group-by-model)


(defn- stock-ids
  "Ids of the figures so far that these observations add to: the rows of one listing in one place add up."
  {:malli/schema [:=> [:cat :string [:maybe :string] [:sequential :map]] [:vector :string]]}
  [connection-id run-id observations]
  (into [] (comp (filter #(= "stock" (:kind %))) (mapcat #(observations/candidate-stock-ids connection-id run-id %))) observations))


(defmethod runtime/coeffect ::existing
  [_ context {:keys [connection-id run-id observations]}]
  (if connection-id
    (reduce (fn [acc [model ids]] (merge acc (load-rows context model ids)))
            {}
            (group-by-model connection-id run-id observations))
    {}))


(defn- group-by-model
  {:malli/schema [:=> [:cat :string [:maybe :string] [:sequential :map]] [:map-of :qualified-keyword [:vector :string]]]}
  [connection-id run-id observations]
  (reduce (fn [acc o]
            (cond-> (-> acc
                        (update (first (entity-models (:kind o))) (fnil conj []) (observations/entity-id connection-id o))
                        ;; The warehouses it names, to tell FBS from rFBS.
                        (update ::external-warehouses (fnil into [])
                                (mapcat #(observations/candidate-warehouse-ids connection-id %)) (observations/warehouses o)))
              (= "stock" (:kind o)) (update ::source-stock (fnil into []) (stock-ids connection-id run-id [o]))))
          {} observations))


(defn- refetch
  "Known non-terminal operations of a dated domain stay on the refetch list until terminal. An operation is
  refetched by its `refetch-key` when the provider looks it up by another id (returns by posting number). So do the
  `pending` ids, until they are read."
  {:malli/schema [:=> [:cat [:maybe :string] [:sequential :map] [:maybe [:sequential :string]]] :string]}
  [previous observations pending]
  (let [ops      (filter #(= "operation" (:kind %)) observations)
        key      #(or (:refetch-key %) (:source-id %))
        open     (map key (remove :terminal ops))
        terminal (apply disj (set (map key (filter :terminal ops))) open)]
    (observations/write-json (vec (distinct (concat (remove terminal (concat (observations/read-json previous) open)) pending))))))


(defn current-key
  "The cursor the next batch of `domain` must be fetched from: the committed one within the run, else the start."
  {:malli/schema [:=> [:cat :string [:maybe :map]] :string]}
  [run-id position]
  (cond (not= run-id (:run-id position)) ""
        (:finished position) "finished"
        :else (:cursor position)))


(def ^:private pass-models
  "Domains whose finished pass reads every row of a model: to expire the bindings it did not see, or, for
  stock-by-warehouse, to know the listings it asked for."
  {"warehouses"         [::external-warehouses :external-warehouse-id]
   "products"           [::external-products :external-product-id]
   "sku-aliases"        [::external-products :external-product-id]
   "stock-by-warehouse" [::external-products :external-product-id]})


(defn- values-where
  "The distinct values of `field` in the rows of `model-id` whose fields hold these texts, found by query rather
  than by scanning the model."
  {:malli/schema [:=> [:cat :map :qualified-keyword :keyword [:map-of :keyword :string]] [:vector :string]]}
  [context model-id field wanted]
  (let [fields  (:fields (rdf-adapter/vocabulary (bel/get-model model-id)))
        clauses (for [[i [field value]] (map-indexed vector wanted)]
                  {:where    ['?row (symbol (str "?field" i)) (symbol (str "?value" i))]
                   :bindings {(keyword (str "field" i)) (fields field) (keyword (str "value" i)) value}})
        query   {:select-distinct '[?found] :where (into (mapv :where clauses) '[[?row ?field ?found]])}]
    (mapv :found (runtime/coeffect :rdf/query context {:query    query
                                                       :bindings (into {:field (fields field)} (map :bindings) clauses)}))))


(defn- run-figures
  "What holds for the figures of one run."
  {:malli/schema [:=> [:cat :string :string] [:map-of :keyword :string]]}
  [connection-id run-id]
  {:connection-id connection-id :run-id run-id :source "warehouse-stock"})


(defmethod runtime/coeffect ::named
  [_ context {:keys [connection-id run-id domain domain-finished]}]
  ;; The listings earlier rows of a finishing stock-by-warehouse pass named.
  (if (and connection-id domain-finished (= "stock-by-warehouse" domain))
    (set (values-where context ::source-stock :external-product-id (run-figures connection-id run-id)))
    #{}))


(defmethod runtime/coeffect ::pass-rows
  [_ context {:keys [connection-id domain domain-finished]}]
  (if-let [[model id-key] (when (and connection-id domain-finished) (pass-models domain))]
    (into {} (for [row   (records/all-records context model id-key)
                   :when (= connection-id (:connection-id row))]
               [(get row id-key) row]))
    {}))


(defn- skus
  "Active SKUs of a listing: what stock is asked by."
  {:malli/schema [:=> [:cat :map] [:sequential :string]]}
  [listing]
  (for [b     (observations/read-json (:identifiers listing))
        :when (and (= "sku" (:type b)) (:active b))]
    (:value b)))


(defn- sku->products
  "Every listing a SKU is active on, as source ids."
  {:malli/schema [:=> [:cat [:sequential :map]] [:map-of :string [:set :string]]]}
  [listings]
  (reduce (fn [acc [sku listing]] (update acc sku (fnil conj #{}) (:source-id listing)))
          {}
          (for [listing listings, sku (skus listing)] [sku listing])))


(defn- spoken-for
  "Source ids of the listings a stock-by-warehouse pass speaks for: the only listing of an asked SKU, or named by
  a row. A SKU active on several listings says nothing of those its rows do not name."
  {:malli/schema [:=> [:cat [:map-of :string :map] [:set :string]] [:vector :string]]}
  [listings named]
  (vec (sort (into (set (keep #(:source-id (listings %)) named))
                   (mapcat #(when (= 1 (count %)) %))
                   (vals (sku->products (or (vals listings) [])))))))


(defcommand record-source-observations
  {:input RecordSourceObservations :policy ::connector-principal
   :cofx  [run-cofx run-connection
           [:position ::position (fn [{:keys [run]} input] {:connection-id (:connection-id run) :domain (:domain input)})]
           [:existing ::existing (fn [{:keys [run]} input] {:connection-id (:connection-id run)  :run-id (:run-id run)
                                                            :observations  (:observations input)})]
           [:pass-rows ::pass-rows (fn [{:keys [run]} input] (assoc (select-keys input [:domain :domain-finished])
                                                                    :connection-id (:connection-id run)))]
           [:named ::named (fn [{:keys [run]} input] (assoc (select-keys input [:domain :domain-finished])
                                                            :connection-id (:connection-id run) :run-id (:run-id run)))]]
   :emits #{::source-batch-recorded ::source-domain-traversed ::external-product-observed ::external-product-seen
            ::external-product-aliases-seen ::external-product-bindings-expired ::external-warehouse-expired
            ::external-warehouse-observed ::external-warehouse-seen ::source-operation-observed ::source-operation-seen
            ::attribute-definition-observed ::attribute-definition-seen ::source-stock-observed
            ::source-domain-unavailable ::provider-throttled}}
  [{:keys [actor now run connection position existing pass-rows named]}
   {:keys [run-id domain batch-key next-position domain-finished covered-until observations adapter-version
           resume-not-before domain-unavailable]}]
  (when-not run (fail! :unknown-run "Unknown run"))
  (own-connector! actor (:connection-id run))
  (cond
    (not= "open" (:run-state run)) (fail! :run-not-open "Run not open")
    (not= "active" (:sync-state connection)) (fail! :connection-paused "Connection paused")
    ;; (run, domain, batch key) was recorded already: a repeat records nothing new.
    (and (= run-id (:run-id position)) (= batch-key (:batch-key position))) {}
    (not= batch-key (current-key run-id position)) (fail! :stale-position "Stale position")
    (and (or resume-not-before domain-unavailable) (or (seq observations) domain-finished))
    (fail! :validation "A setback carries no observations")
    domain-unavailable
    (let [unavailable (decode (:unavailable-domains run))]
      (if (some #{domain} unavailable)
        {}
        {:events [{:type ::source-domain-unavailable
                   :data {:run-id              run-id :connection-id (:connection-id run) :domain domain
                          :unavailable-domains (encode (conj unavailable domain))
                          :readable-domains    (readable-without connection [domain])}}]}))
    resume-not-before
    {:events [{:type ::provider-throttled :data {:connection-id (:connection-id run) :resume-not-before resume-not-before}}]}
    (personal? observations) (fail! :personal-data-present "Personal data present")
    ;; The fetcher sees this first and fails the run; nothing overwrites a figure silently.
    (observations/contradicts? existing (:connection-id run) run-id observations)
    (fail! :conflicting-figures "Conflicting figures")
    :else
    (let [connection-id     (:connection-id run)
          at                (str now)
          [events rows new] (reduce (fn [[events rows new] o]
                                      (let [[e rows kind] (observations/observe rows o connection-id run-id at adapter-version)]
                                        [(into events e) rows (cond-> new kind (conj kind))]))
                                    [[] existing []] observations)
          found             (frequencies new)
          finished          (decode (:finished-domains run))
          covered           (later (:covered-until position) (or covered-until (:started-at run)))]
      {:events (cond-> (conj events
                             {:type ::source-batch-recorded
                              :data (merge {:position-id      (position-id connection-id domain) :connection-id connection-id
                                            :domain           domain :run-id run-id :cursor (:cursor next-position) :batch-key batch-key
                                            :refetch-ids      (refetch (:refetch-ids position) observations (:pending-ids next-position))
                                            :finished         domain-finished
                                            :found-listings   (+ (:found-listings run 0) (found "product" 0))
                                            :found-warehouses (+ (:found-warehouses run 0) (found "warehouse" 0))
                                            :found-operations (+ (:found-operations run 0) (found "operation" 0))
                                            :recorded-at      at}
                                           (select-keys next-position [:window-from :window-to]))})
                 domain-finished
                 (into (observations/expired-bindings domain (merge pass-rows (select-keys rows (keys pass-rows))) (:started-at run)))
                 domain-finished
                 (conj {:type ::source-domain-traversed
                        :data (cond-> {:run-id           run-id :connection-id connection-id :domain domain :covered-until covered
                                       :traversed-at     at :position-id       (position-id connection-id domain)
                                       :finished-domains (encode (conj finished domain))
                                       :first-covered-at (or (:first-covered-at position) (window-start run domain) (:started-at run))
                                       :pass-id          (str run-id "/" domain)}
                                (= "stock-by-warehouse" domain)
                                (assoc :asked (observations/write-json
                                                (spoken-for (or pass-rows {}) (into (set named) (keep (comp :external-product-id :data)) events)))))}))
       :fx     [[::mark-dirty {:connection-id connection-id}]]})))


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
   [:unavailable-domains {:optional true} [:vector Domain]]])


(def sync-interval
  connections/sync-interval)


(defcommand complete-marketplace-sync
  {:input CompleteMarketplaceSync :policy ::connector-principal :cofx [run-cofx run-connection run-positions]
   :emits #{::marketplace-sync-completed ::marketplace-sync-failed ::connections/connection-credentials-rejected}}
  [{:keys [actor now run connection positions]} {:keys [run-id outcome failed-domain reason unavailable-domains]}]
  (when-not run (fail! :unknown-run "Unknown run"))
  (own-connector! actor (:connection-id run))
  (cond
    ;; A repeat for a closed run returns the recorded outcome.
    (not= "open" (:run-state run)) {}
    :else
    (let [{:keys [unfinished-domains] :as cov} (coverage (update run :unavailable-domains
                                                                 #(encode (concat (decode %) unavailable-domains))))
          unavailable (:unavailable-domains cov)
          connection-id (:connection-id run)
          at (str now)
          readable (readable-without connection unavailable)
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
              saved    (observations/write-json (into (sorted-map) (keep (fn [[d row]] (when (:covered-until row) [d (:covered-until row)])))
                                                      positions))]
          {:events (cond-> [{:type ::marketplace-sync-failed
                             :data (cond-> {:run-id    run-id :connection-id   connection-id :failed-domain        failed-domain
                                            :reason    reason :saved-positions saved         :consecutive-failures failures
                                            :failed-at at     :run-state       "failed"      :readable-domains     readable}
                                     wait (assoc :next-attempt-at (str (.plus ^Instant now ^Duration wait))))}]
                     (= "credentials rejected" reason)
                     (conj {:type ::connections/connection-credentials-rejected
                            :data {:connection-id connection-id :run-id run-id :credentials-state "rejected" :rejected-at at}}))})))))


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


;; Stock passes and their figures


(defn- rows-where
  "Rows of `model-id` whose fields hold these texts."
  {:malli/schema [:=> [:cat :map :qualified-keyword :keyword [:map-of :keyword :string]] [:vector :map]]}
  [context model-id id-key wanted]
  (vec (vals (load-rows context model-id (values-where context model-id id-key wanted)))))


(defn finished-passes
  "Finished passes of a connection's domain, newest first; a stock-by-warehouse pass names the external products
  it speaks for."
  {:malli/schema [:=> [:cat :map :string :string] [:vector [:map [:run-id Ref] [:observed-at At] [:asked {:optional true} [:set :string]]]]]}
  [context connection-id domain]
  (->> (rows-where context ::stock-passes :pass-id {:connection-id connection-id :domain domain})
       (sort-by (comp instant :traversed-at) #(compare %2 %1))
       (mapv (fn [{:keys [run-id traversed-at asked]}]
               (cond-> {:run-id run-id :observed-at traversed-at}
                 asked (assoc :asked (into #{} (map #(observations/entity-id connection-id {:kind "product" :source-id %}))
                                           (observations/read-json asked))))))))


(defn stock-figures
  "The warehouse stock figures of one run, the only view that keeps a figure per run. The run's pass is finished
  when `finished-passes` names it."
  {:malli/schema [:=> [:cat :map :string :string] [:vector SourceStock]]}
  [context connection-id run-id]
  (rows-where context ::source-stock :stock-id (run-figures connection-id run-id)))


(defn stated-present
  "What a finished pass states of a listing in a warehouse: its figure, 0 without one, nil when the pass does not
  speak for the listing or is not finished."
  {:malli/schema [:=> [:cat [:maybe :map] [:sequential :map] :string :string] [:maybe :int]]}
  [{:keys [run-id asked]} figures external-product-id external-warehouse-id]
  (when (contains? asked external-product-id)
    (or (some #(when (= [run-id external-product-id external-warehouse-id]
                        ((juxt :run-id :external-product-id :external-warehouse-id) %))
                 (:present %))
              figures)
        0)))


;; Connections due for sync (spans workspaces; read by the scheduler and by the Mapping Guard's sweep)


(defn workspace-ids
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


(defn workspace-connections
  {:malli/schema [:=> [:cat :map :uuid] [:vector :map]]}
  [system workspace-id]
  (records/all-records {:bel/system system :scope {:workspace-id workspace-id}} ::connections/connections :connection-id))


(defn due-rows
  "Connections Due for Sync, oldest due first."
  {:malli/schema [:=> [:cat [:sequential :map] validation/Instant] [:vector [:map [:workspace-id :string] [:connection-id :string]
                                                                             [:open-run {:optional true} :string]
                                                                             [:after-run {:optional true} :string] [:due-since :string]]]]}
  [connections now]
  (->> connections
       (keep (fn [c]
               (when (connections/due? c now)
                 (cond-> {:workspace-id (:workspace-id c)                  :connection-id (:connection-id c)
                          :due-since    (str (connections/next-sync-at c))}
                   (= "open" (:run-state c)) (assoc :open-run (:run-id c))
                   (and (:run-id c) (not= "open" (:run-state c))) (assoc :after-run (:run-id c))))))
       (sort-by (juxt #(instant (:due-since %)) :connection-id))
       vec))


(def consumers
  "Who re-evaluates marked connections; each drains its own marks, so none takes another's."
  [:mapping-guard :marketplace-operation-intake :automatic-acceptance :source-correction-intake])


(defmethod runtime/effect! ::mark-dirty
  [_ {:keys [resources]} {:keys [connection-id]}]
  (some-> (:marketplace resources) :dirty
          (swap! (fn [marks] (reduce #(update %1 %2 (fnil conj #{}) connection-id) (if (map? marks) marks {}) consumers)))))


(defn take-dirty!
  "The connections `consumer` re-evaluates: those marked since its last call, and all of `all-ids` at its first
  call and once a minute after its last full sweep. Its marks are cleared atomically; other consumers keep theirs."
  {:malli/schema [:=> [:cat [:maybe :map] (into [:enum] consumers) [:set :string] validation/Instant] [:set :string]]}
  [{:keys [dirty swept]} consumer all-ids now]
  (let [marks          (first (swap-vals! dirty #(assoc (if (map? %) % {}) consumer #{})))
        marked         (get (if (map? marks) marks {}) consumer #{})
        due?           #(or (nil? %) (not (.isBefore now (.plus ^Instant % (Duration/ofMinutes 1)))))
        [before after] (swap-vals! swept #(let [sweeps (if (map? %) % {})]
                                            (cond-> sweeps (due? (get sweeps consumer)) (assoc consumer now))))]
    (cond-> marked (not= (get before consumer) (get after consumer)) (into all-ids))))


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
  "A stable run id for one due period after the connection's last closed run, so repeated ticks and retries
  propose the same run. Replacing credentials can make a connection due at the same time as before its failed
  run; the last run tells the two apart."
  {:malli/schema [:=> [:cat :string :string [:maybe :string]] :string]}
  [connection-id due-since after-run]
  (str (UUID/nameUUIDFromBytes (.getBytes (str connection-id "/" due-since "/" after-run) StandardCharsets/UTF_8))))


(defprocessor ^{:malli/schema [:=> [:cat [:map [:workspace-id :string] [:connection-id :string] [:due-since :string]
                                          [:after-run {:optional true} :string]]]
                               bel/ProcessorRequest]}
  marketplace-sync-scheduler
  {:todo ::due-connections}
  [{:keys [workspace-id connection-id due-since after-run]}]
  {:type  :command :id ::start-marketplace-sync :scope {:workspace-id (parse-uuid workspace-id)}
   :actor (connector-actor connection-id)
   :input {:attempt-id    (str "schedule/" due-since) :run-id  (run-id-for connection-id due-since after-run)
           :connection-id connection-id               :trigger "schedule"}})


;; Fetcher


(defn- lookup
  "`f`, whose failure is the store's and never the provider's."
  {:malli/schema [:=> [:cat [:fn fn?]] [:fn fn?]]}
  [f]
  (fn [& args]
    (try (apply f args)
         (catch Exception error
           (throw (ex-info "Stored records cannot be read" {:type ::lookup-failed} error))))))


(def FetchItem
  [:map [:workspace-id :string] [:run :map] [:positions [:map-of :string :map]] [:env [:maybe ozon/Env]]
   [:products [:fn fn?]]
   [:known-orders {:optional true} [:fn fn?]] [:stored-supplies {:optional true} [:fn fn?]]
   ;; Stored figures, by id, that observations of the run may add to.
   [:figures {:optional true} [:fn fn?]]
   ;; Failed tries to record a batch, by connection: [[run domain batch key] count].
   [:attempts {:optional true} :any]])


(defn- context-for
  "What a domain step may know: the run's window, refetch ids and lookups over the connection's records."
  {:malli/schema [:=> [:cat :map [:maybe :map] FetchItem] ozon/Context]}
  [run {:keys [domain covered-until first-covered-at refetch-ids]}
   {:keys [products known-orders stored-supplies] :or {known-orders (constantly #{}) stored-supplies (constantly [])}}]
  (let [from     (window-start run domain)
        products (lookup products)
        listings (delay (vals (products)))
        refetch  (vec (observations/read-json refetch-ids))]
    (cond-> {:refetch-ids     refetch
             :skus            (fn [] (mapcat skus @listings))
             :categories      (fn [] (distinct (for [p     @listings
                                                     :let  [pair (some-> (:category p) (str/split #"/"))]
                                                     :when (= 2 (count pair))]
                                                 pair)))
             :sku->product    (fn [] (into {} (for [p @listings, sku (skus p)] [sku (:source-id p)])))
             :sku->products   (fn [] (sku->products @listings))
             :known-orders    (lookup known-orders)
             :stored-supplies (lookup stored-supplies)}
      from (assoc :window {:from from :to (:started-at run)})
      covered-until (assoc :settled-before (str (.minus ^Instant (instant covered-until) ^Duration ozon/overlap)))
      (and first-covered-at (seq refetch)) (assoc :reread-from first-covered-at))))


(def ^:private record-tries
  "A batch is tried as often as one provider request."
  4)


(def ^:private no-fault
  "Rejections that say nothing about the batch."
  #{:run-not-open :connection-paused :stale-position :unknown-run :not-permitted})


(defn- recording-failure
  "Why the run fails when its batch cannot be recorded, or the stored records it needs cannot be read; nil to try
  again. A batch the command refuses is refused every time; any other failure may pass, and ends the run after
  `record-tries` at one position."
  {:malli/schema [:=> [:cat [:fn #(instance? Throwable %)] [:maybe :any] :string [:vector :any]] [:maybe :keyword]]}
  [error attempts connection-id position]
  (let [type (:type (ex-data error))]
    (cond
      (contains? no-fault type) nil
      (contains? #{:validation :personal-data-present} type) :unexpected-response
      (= :conflicting-figures type) :incomplete-page
      attempts (let [[_ n] (get (swap! attempts update connection-id
                                       (fn [[at n]] [position (if (= at position) (inc n) 1)]))
                                connection-id)]
                 (when (<= record-tries n) :retries-exhausted)))))


(defn- read-step
  "One step of a domain, or the failure it met as `:setback` with no observations. A page that cannot be translated
  is an unexpected provider response; a failure of the store or a shutdown is thrown."
  {:malli/schema [:=> [:cat :string [:maybe ozon/Env] :map :map [:fn fn?]] :map]}
  [domain env context state contradicts?]
  (try
    (when-not env (ozon/fail! :credentials-rejected))
    (let [{after :state :keys [observations finished? setback] :as step} (ozon/step domain env context state)]
      (when (and (not finished?) (not setback) (= state after)) (ozon/fail! :repeated-cursor))
      (when (contradicts? observations) (ozon/fail! :incomplete-page))
      step)
    (catch InterruptedException error (throw error))
    (catch Exception error
      (let [{:keys [type] :as data} (ex-data error)]
        (cond
          (= ::lookup-failed type) (throw error)
          (contains? ozon/failures type) {:setback data}
          :else (do (mu/log ::page-unreadable :domain domain :error-type (.getName (class error)))
                    {:setback {:type :unexpected-response}}))))))


(defn fetch-request
  "Check the run's remaining work and read its next batch: the command that records it, or the setback it met, or
  closes the run; both when the setback came after something was read. Reads the provider outside any store
  transaction."
  {:malli/schema [:=> [:cat FetchItem] [:or bel/ProcessorRequest [:vector bel/ProcessorRequest]]]}
  [{:keys [workspace-id run positions env figures attempts] :or {figures (constantly {})} :as item}]
  (let [connection-id (:connection-id run)
        run-id (:run-id run)
        request (fn [id input] {:type  :command :id id :scope {:workspace-id (parse-uuid workspace-id)}
                                :actor (connector-actor connection-id) :input input})
        {:keys [unfinished-domains unavailable-domains]} (coverage run)]
    (if-let [domain (first unfinished-domains)]
      (let [position  (assoc (get positions domain) :domain domain)
            batch-key (current-key run-id position)
            figures   (lookup figures)
            fail      (fn [type]
                        (request ::complete-marketplace-sync {:run-id run-id         :outcome             "failed"            :failed-domain domain
                                                              :reason (reasons type) :unavailable-domains unavailable-domains}))
            from      (window-start run domain)
            record    (fn [key cursor input]
                        (assoc (request ::record-source-observations
                                        (merge {:run-id          run-id :domain      domain :batch-key key
                                                :next-position   (cond-> {:cursor cursor} from (assoc :window-from from :window-to (:started-at run)))
                                                :domain-finished false :observations []
                                                :adapter-version ozon/adapter-version}
                                               input))
                               :catch #(some-> (recording-failure % attempts connection-id [run-id domain key]) fail)))
            hold      (fn [key {:keys [type resume-not-before]}]
                        (case type
                          :unavailable (record key key {:domain-unavailable true})
                          :throttled (record key key {:resume-not-before resume-not-before})
                          (fail type)))
            {:keys [observations state finished? setback pending-ids] failed :run-failure}
            ;; A stored position that cannot be read is no page of the provider's either.
            (try (read-step domain env (context-for run position item) (if (= "" batch-key) {} (edn/read-string batch-key))
                            #(and (= "stock-by-warehouse" domain)
                                  (observations/contradicts? (figures %) connection-id run-id %)))
                 (catch InterruptedException error (throw error))
                 (catch Exception error
                   {:run-failure (or (recording-failure error attempts connection-id [run-id domain batch-key]) (throw error))}))]
        (cond
          failed (fail failed)
          (nil? state) (hold batch-key setback)
          :else
          (let [cursor (pr-str state)
                batch  (cond-> (record batch-key cursor {:domain-finished finished? :observations observations})
                         pending-ids (assoc-in [:input :next-position :pending-ids] pending-ids))]
            (if setback [batch (hold cursor setback)] batch))))
      (request ::complete-marketplace-sync {:run-id run-id :outcome "completed" :unavailable-domains unavailable-domains}))))


(defn- known-orders
  "Order ids of a connection's stored supplies."
  {:malli/schema [:=> [:cat :map :string] [:set :string]]}
  [context connection-id]
  (into #{} (map #(first (str/split % #"/")))
        (values-where context ::source-operations :refetch-key {:connection-id connection-id :kind "supply"})))


(defn- stored-supplies
  "The last stated facts of a connection's supplies by refetch key."
  {:malli/schema [:=> [:cat :map :string [:sequential :string]] [:sequential :map]]}
  [context connection-id refetch-keys]
  (->> refetch-keys
       (mapcat #(values-where context ::source-operations :source-revision-id {:connection-id connection-id :kind "supply" :refetch-key %}))
       (load-rows context ::source-revisions)
       vals
       (mapv (comp observations/read-json :facts))))


(defn- fetchable?
  {:malli/schema [:=> [:cat :map validation/Instant] :boolean]}
  [{:keys [run-state sync-state credentials-state resume-not-before]} now]
  (and (= "open" run-state) (= "active" sync-state) (= "valid" credentials-state)
       (not (some-> (instant resume-not-before) (.isAfter now)))))


(defn- or-skip
  "The value of `f`. What cannot be read now says nothing about the credentials: it is nil, and tried again later."
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (try (f)
       (catch InterruptedException error (throw error))
       (catch Exception error
         (mu/log ::fetch-skipped :error-type (or (:type (ex-data error)) (.getName (class error))))
         nil)))


(defn- fetch-item
  "The open run of a connection with what the fetcher needs; nil without one. `:env` is nil only when the sealed key
  is absent or cannot be decrypted."
  {:malli/schema [:=> [:cat :map :map] [:maybe FetchItem]]}
  [{:keys [bel/system scope] :as context} connection]
  (let [marketplace  (get-in system [:resources :marketplace])
        workspace-id (:workspace-id scope)
        run          (get (load-rows context ::sync-runs [(:run-id connection)]) (:run-id connection))]
    (when (= "open" (:run-state run))
      {:workspace-id    (str workspace-id)
       :run             run
       :positions       (runtime/coeffect ::connection-positions context {:connection-id (:connection-id connection)})
       :env             (some->> (connector/api-key marketplace workspace-id (:secret-ref connection))
                                 (ozon/env marketplace (:provider-account-id connection)))
       :attempts        (:attempts marketplace)
       :known-orders    #(known-orders context (:connection-id connection))
       :stored-supplies #(stored-supplies context (:connection-id connection) %)
       :figures         #(load-rows context ::source-stock (stock-ids (:connection-id connection) (:run-id run) %))
       :products        #(into {} (filter (fn [[_ p]] (= (:connection-id connection) (:connection-id p))))
                               (map (juxt :external-product-id identity)
                                    (records/all-records context ::external-products :external-product-id)))})))


(defmethod runtime/coeffect ::runs-to-fetch
  [_ {:keys [bel/system]} _]
  (let [now (Instant/now)]
    (vec (for [workspace-id (workspace-ids system)
               :let         [context {:bel/system system :scope {:workspace-id workspace-id}}]
               connection   (or-skip #(workspace-connections system workspace-id))
               :when        (fetchable? connection now)
               :let         [item (or-skip #(fetch-item context connection))]
               :when        item]
           item))))


;; ponytail: the fetcher shares the journal-delivery worker thread, one step of at most `ozon/max-calls` calls per
;; open run per second; move it to its own executor if provider latency delays journal delivery.
(defprocessor ^{:malli/schema [:=> [:cat FetchItem] [:or bel/ProcessorRequest [:vector bel/ProcessorRequest]]]} marketplace-fetcher
  {:todo ::runs-to-fetch}
  [item]
  (fetch-request item))
