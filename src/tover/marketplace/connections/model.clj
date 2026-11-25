(ns tover.marketplace.connections.model
  "Board chapter 40 · Connect a marketplace account: connect, pause, resume and replace credentials, and the
  connection reads. Two independent states: sync `active | paused` (the manager's choice) and credentials
  `valid | rejected` (the provider's outcome).

  Store: every Marketplace model lives in `:catalogue`, the workspace RDF repository. Stores (chapter 12) are
  there, so connect and resume check the store in the same snapshot, and chapter 41 persists source records in
  the same repository, so a sync batch and its checkpoint can commit atomically with the connection. Identity
  (`:directory`) holds only accounts, memberships and sessions.

  Board `attempt id` is the Bel command id. The board's `api key` input never reaches a command: the action
  verifies and seals it first (`tover.marketplace.connector`) and the command receives `secret ref`, because
  command input is recorded with its receipt. `readable domains` is a list on the board and space-separated
  text here: Bel model fields are scalar. The RDF adapter stores strings, numbers and booleans only, so ids
  are text and instants are ISO-8601 text in models and events; reads return instants."
  (:require
    [bel.core :refer [defcommand defevent defmodel defreadmodel]]
    [bel.runtime :as runtime]
    [bel.store :as store]
    [bel.validation :as validation]
    [clojure.string :as str]
    [tover.catalogues.records :as records]
    [tover.catalogues.stores.model :as stores]
    [tover.inventory.actors :as actors]
    [tover.marketplace.connector :as connector]
    [tover.workspaces.currency.model :as currency])
  (:import
    [java.time Duration Instant]))


;; Data model (element details on the board)


(def Domain
  (into [:enum] connector/domains))


(def Domains
  "Readable domains as space-separated text in board order."
  [:re (re-pattern (str "^(?:" (str/join "|" connector/domains) ")(?: (?:" (str/join "|" connector/domains) "))*$"))])


(defn encode-domains
  {:malli/schema [:=> [:cat [:sequential :string]] :string]}
  [domains]
  (str/join " " (filter (set domains) connector/domains)))


(defn decode-domains
  {:malli/schema [:=> [:cat [:maybe :string]] [:set :string]]}
  [text]
  (set (remove str/blank? (str/split (or text "") #" "))))


(def Provider
  [:enum "ozon"])


(def ClientId
  "Provider seller id (Ozon Client-Id): trimmed text, leading zeros kept."
  [:and [:string {:max 100 :decode/string #(if (string? %) (str/trim %) %)}] [:re #"\S+"]])


(def SyncState
  [:enum "active" "paused"])


(def CredentialsState
  [:enum "valid" "rejected"])


(def PauseReason
  [:enum "manager" "store archived"])


(def Ref
  "An id as text: connection, secret, account or sync run."
  [:string {:min 1 :max 100}])


(def ConnectionId
  "A connection id a browser proposes: plain text in any URL or expression."
  [:re #"\A[A-Za-z0-9-]{1,100}\z"])


(def At
  "An instant as ISO-8601 text."
  [:string {:min 20 :max 40}])


(defn at
  {:malli/schema [:=> [:cat [:maybe :string]] [:maybe validation/Instant]]}
  [text]
  (some-> text Instant/parse))


(def Outcome
  [:enum "verified" "rejected" "unreachable"])


(def Connection
  "One connection; optional fields are absent until their event happens. Chapter 41 keeps the latest run here
  (`run-id`, `run-state`, `started-at`), with the failure count, its wait and provider throttling, so the
  scheduler reads one row per connection."
  [:map
   [:connection-id Ref]
   [:workspace-id Ref]
   [:store-id {:bel/ref ::stores/stores} records/Id]
   [:provider Provider]
   [:provider-account-id :string]
   [:account-label {:optional true} :string]
   [:account-currency :string]
   [:key-expires-at {:optional true} At]
   [:secret-ref Ref]
   [:readable-domains :string]
   [:sync-state SyncState]
   [:credentials-state CredentialsState]
   [:connected-by Ref]
   [:connected-at At]
   [:reason {:optional true} PauseReason]
   [:paused-by {:optional true} Ref]
   [:paused-at {:optional true} At]
   [:resumed-by {:optional true} Ref]
   [:resumed-at {:optional true} At]
   [:run-id {:optional true} Ref]
   [:rejected-at {:optional true} At]
   [:replaced-by {:optional true} Ref]
   [:replaced-at {:optional true} At]
   [:last-completed-sync-at {:optional true} At]
   [:run-state {:optional true} [:enum "open" "completed" "failed"]]
   [:started-at {:optional true} At]
   [:consecutive-failures {:optional true} [:int {:min 0 :max 5}]]
   [:next-attempt-at {:optional true} At]
   [:resume-not-before {:optional true} At]])


(def MarketplaceAccountConnected
  [:map
   [:connection-id Ref] [:workspace-id Ref] [:store-id records/Id] [:provider Provider]
   [:provider-account-id :string] [:account-label {:optional true} :string] [:secret-ref Ref]
   [:account-currency :string] [:key-expires-at {:optional true} At]
   [:readable-domains Domains] [:sync-state [:= "active"]] [:credentials-state [:= "valid"]]
   [:connected-by Ref] [:connected-at At]])


(def MarketplaceConnectionPaused
  [:map [:connection-id Ref] [:sync-state [:= "paused"]] [:reason PauseReason]
   [:paused-by Ref] [:paused-at At]])


(def MarketplaceConnectionResumed
  [:map [:connection-id Ref] [:sync-state [:= "active"]] [:resumed-by Ref] [:resumed-at At]])


(def ConnectionCredentialsRejected
  "Emitted by chapter 41's sync run; declared here because the connection maps it."
  [:map [:connection-id Ref] [:run-id Ref] [:credentials-state [:= "rejected"]] [:rejected-at At]])


(def ConnectionCredentialsReplaced
  "Replacing credentials also resets the consecutive-failure count (chapter 41)."
  [:map [:connection-id Ref] [:secret-ref Ref] [:account-currency :string] [:key-expires-at {:optional true} At]
   [:readable-domains Domains] [:credentials-state [:= "valid"]]
   [:replaced-by Ref] [:replaced-at At] [:consecutive-failures {:optional true} [:= 0]]
   [:next-attempt-at {:optional true} At]])


(defevent marketplace-account-connected {:schema MarketplaceAccountConnected})
(defevent marketplace-connection-paused {:schema MarketplaceConnectionPaused})
(defevent marketplace-connection-resumed {:schema MarketplaceConnectionResumed})
(defevent connection-credentials-rejected {:schema ConnectionCredentialsRejected})
(defevent connection-credentials-replaced {:schema ConnectionCredentialsReplaced})


(defmodel connections
  {:id     :connection-id
   :store  :catalogue
   :schema Connection
   :policy ::records/workspace-member
   :events {::marketplace-account-connected {:create [:workspace-id :store-id :provider :provider-account-id :account-label
                                                      :account-currency :key-expires-at :secret-ref :readable-domains
                                                      :sync-state :credentials-state
                                                      :connected-by :connected-at]}
            ::marketplace-connection-paused {:set [:sync-state :reason :paused-by :paused-at]}
            ::marketplace-connection-resumed {:set [:sync-state :resumed-by :resumed-at]}
            ::connection-credentials-rejected {:set [:credentials-state :run-id :rejected-at]}
            ::connection-credentials-replaced {:set [:secret-ref :account-currency :key-expires-at :readable-domains
                                                     :credentials-state :replaced-by :replaced-at :consecutive-failures :next-attempt-at]}
            ;; Chapter 41 sync events (declared in tover.marketplace.sync.model).
            :tover.marketplace.sync.model/marketplace-sync-started   {:set [:run-id :run-state :started-at]}
            :tover.marketplace.sync.model/marketplace-sync-completed {:set [:run-state :last-completed-sync-at :readable-domains
                                                                            :consecutive-failures :next-attempt-at]}
            :tover.marketplace.sync.model/marketplace-sync-failed    {:set [:run-state :readable-domains :consecutive-failures
                                                                            :next-attempt-at]}
            :tover.marketplace.sync.model/source-domain-unavailable  {:set [:readable-domains]}
            :tover.marketplace.sync.model/provider-throttled         {:set [:resume-not-before]}}})


;; Access and facts


(defn manager?
  "An active Owner or Admin of the scope's workspace. The authority only lists active memberships."
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :map]] :boolean]}
  [principal scope]
  (boolean (some (fn [{:keys [workspace-id role]}]
                   (and (= workspace-id (:workspace-id scope)) (contains? #{:owner :admin} role)))
                 (:memberships principal))))


(defmethod runtime/policy ::manager
  [_ {:keys [principal scope]} _]
  (manager? principal scope))


(defmethod runtime/coeffect ::viewer
  [_ {:keys [principal scope]} _]
  {:manager? (manager? principal scope)})


(defmethod runtime/coeffect ::all
  [_ context _]
  (records/all-records context ::connections :connection-id))


(defmethod runtime/coeffect ::sealed?
  [_ {:keys [scope] :as context} {:keys [secret-ref]}]
  (boolean (when secret-ref
             (runtime/with-store context :catalogue :serializable
               (fn [{:keys [tx]}] (connector/sealed? tx (:workspace-id scope) secret-ref))))))


(defmethod runtime/coeffect ::account-label
  [_ {:keys [actor scope] :as context} {:keys [account-id]}]
  (when account-id
    (get (actors/labels (assoc context :user-id actor :workspace-id (:workspace-id scope)) [{:actor account-id}])
         (str account-id))))


(defmethod runtime/effect! ::destroy-secret
  [_ {:keys [resources scope]} {:keys [secret-ref]}]
  (connector/destroy-secret! (:marketplace resources) (:workspace-id scope) secret-ref))


(defn- by-connection
  {:malli/schema [:=> [:cat :map :map] :map]}
  [_ input]
  (select-keys input [:connection-id]))


(defn- connection-store
  {:malli/schema [:=> [:cat :map :map] :map]}
  [facts _]
  {:store-id (get-in facts [:connection :store-id])})


(def ^:private connection
  [:connection ::connections by-connection])


(def ^:private viewer
  [:viewer ::viewer (constantly {})])


(def ^:private sealed
  [:sealed? ::sealed? (fn [_ input] (select-keys input [:secret-ref]))])


(def ^:private covered
  [:covered ::covered-until by-connection])


;; Decisions


(defn- fail!
  {:malli/schema [:function [:=> [:cat :keyword :string] :nil] [:=> [:cat :keyword :string :map] :nil]]}
  ([type message] (fail! type message {}))
  ([type message data] (throw (ex-info message (assoc data :type type)))))


(defn- known!
  {:malli/schema [:=> [:cat [:maybe :map]] :map]}
  [connection]
  (or connection (fail! :unknown-connection "Unknown connection")))


(defn- sealed!
  "Checked last: the action seals a key only when every earlier rule holds. A concurrent attempt may have
  destroyed it as unreferenced since."
  {:malli/schema [:=> [:cat [:maybe :string] :boolean] :nil]}
  [secret-ref sealed?]
  (when-not secret-ref
    (fail! :validation "Verified credentials need a secret reference"))
  (when-not sealed?
    (fail! :conflict "Concurrent change; submit the credentials again")))


(defn- check-verification!
  "Board rejections shared by connect and replace, in the order the provider answers them."
  {:malli/schema [:=> [:cat :map] :nil]}
  [{:keys [verification-outcome readable-domains]}]
  (case verification-outcome
    "rejected" (fail! :credentials-rejected "Credentials rejected")
    "unreachable" (fail! :could-not-verify "Could not verify")
    nil)
  (when-not (some #{"products"} readable-domains)
    (fail! :products-not-readable "Products not readable"))
  nil)


(def ConnectMarketplaceAccount
  [:map
   [:connection-id ConnectionId]
   [:store-id records/Id]
   [:provider Provider]
   [:client-id ClientId]
   [:secret-ref {:optional true} Ref]
   [:verification-outcome Outcome]
   [:account-label {:optional true} [:string {:max 500}]]
   [:account-currency {:optional true} :string]
   [:readable-domains {:optional true} [:vector Domain]]
   [:key-expires-at {:optional true} At]])


(defcommand connect-marketplace-account
  {:input ConnectMarketplaceAccount :policy ::manager
   :cofx  [[:store ::stores/stores (fn [_ input] (select-keys input [:store-id]))]
           [:connections ::all (constantly {})]
           sealed]
   :emits #{::marketplace-account-connected}}
  [{:keys [actor now scope store connections sealed?]}
   {:keys [connection-id store-id provider client-id secret-ref account-label account-currency readable-domains key-expires-at]
    :as   input}]
  (when-not (= "active" (:status store))
    (fail! :store-not-available "Store not available"))
  (check-verification! input)
  (when-let [existing (first (filter #(and (= provider (:provider %)) (= client-id (:provider-account-id %)))
                                     connections))]
    (fail! :already-connected "Already connected" {:connection-id (:connection-id existing)}))
  (sealed! secret-ref sealed?)
  {:events [{:type ::marketplace-account-connected
             :data (cond-> {:connection-id       connection-id
                            :workspace-id        (str (:workspace-id scope))
                            :store-id            store-id
                            :provider            provider
                            :provider-account-id client-id
                            :account-currency    account-currency
                            :secret-ref          secret-ref
                            :readable-domains    (encode-domains readable-domains)
                            :sync-state          "active"
                            :credentials-state   "valid"
                            :connected-by        (str actor)
                            :connected-at        (str now)}
                     (not (str/blank? account-label)) (assoc :account-label (str/trim account-label))
                     key-expires-at (assoc :key-expires-at key-expires-at))}]})


(def ConnectionAttempt
  [:map [:connection-id Ref]])


(defcommand pause-marketplace-connection
  {:input ConnectionAttempt :policy ::manager :cofx [connection] :emits #{::marketplace-connection-paused}}
  [{:keys [actor now connection]} {:keys [connection-id]}]
  (if (= "paused" (:sync-state (known! connection)))
    {}
    {:events [{:type ::marketplace-connection-paused
               :data {:connection-id connection-id :sync-state "paused" :reason "manager" :paused-by (str actor) :paused-at (str now)}}]}))


(defcommand resume-marketplace-connection
  {:input ConnectionAttempt :policy ::manager
   :cofx  [connection [:store ::stores/stores connection-store]]
   :emits #{::marketplace-connection-resumed}}
  [{:keys [actor now connection store]} {:keys [connection-id]}]
  (cond
    (= "active" (:sync-state (known! connection))) {}
    (= "rejected" (:credentials-state connection)) (fail! :needs-credentials "Needs credentials")
    (not= "active" (:status store)) (fail! :store-archived "Store archived")
    :else {:events [{:type ::marketplace-connection-resumed
                     :data {:connection-id connection-id :sync-state "active" :resumed-by (str actor) :resumed-at (str now)}}]}))


(def ReplaceConnectionCredentials
  [:map
   [:connection-id Ref]
   [:client-id ClientId]
   [:secret-ref {:optional true} Ref]
   [:verification-outcome Outcome]
   [:account-currency {:optional true} :string]
   [:readable-domains {:optional true} [:vector Domain]]
   [:key-expires-at {:optional true} At]])


(defcommand replace-connection-credentials
  {:input ReplaceConnectionCredentials :policy ::manager :cofx [connection sealed] :emits #{::connection-credentials-replaced}}
  [{:keys [actor now connection sealed?]}
   {:keys [connection-id client-id secret-ref account-currency readable-domains key-expires-at] :as input}]
  (let [{account :provider-account-id previous :secret-ref} (known! connection)]
    (check-verification! input)
    (when-not (= account client-id)
      (fail! :different-seller-account "Different seller account"))
    (sealed! secret-ref sealed?)
    {:events [{:type ::connection-credentials-replaced
               :data (cond-> {:connection-id        connection-id :secret-ref secret-ref
                              :account-currency     account-currency
                              :readable-domains     (encode-domains readable-domains)
                              :credentials-state    "valid" :replaced-by      (str actor) :replaced-at (str now)
                              :consecutive-failures 0}
                       key-expires-at (assoc :key-expires-at key-expires-at))}]
     ;; The superseded key is destroyed right after commit; Bel writes only mapped event fields in the commit.
     :fx     [[::destroy-secret {:secret-ref previous}]]}))


;; Attempts


(defmethod runtime/coeffect ::accepted-request
  [_ context {:keys [command-id]}]
  ;; As Bel's own recovery: the outbox while the delivery is pending, then the journal's receipt.
  (when-let [accepted (runtime/with-store context :catalogue :repeatable-read
                        (fn [{:keys [tx scope] :as context}]
                          (store/-accepted (store/store context :catalogue) tx (store/store-scope scope) command-id)))]
    (:request (or (:pending accepted)
                  (runtime/coeffect :bel/receipt context {:command-id command-id})
                  (throw (ex-info "Accepted command is not available in the journal; retry without changing its identity"
                                  {:type :journal-unavailable :retryable? true :command-id command-id}))))))


;; The recorded input of an accepted connect or replace attempt, for the same manager, workspace and entered values
;; (the API key through its secret reference); nil before acceptance. Server-side only: it names the secret.
(defreadmodel accepted-attempt
  {:input  [:map [:command-id :string] [:command :qualified-keyword] [:entered [:map-of :keyword :any]]]
   :output [:maybe :map]
   :policy ::manager
   :cofx   [[:request ::accepted-request (fn [_ input] (select-keys input [:command-id]))]]}
  [{:keys [request scope actor]} {:keys [command entered]}]
  (when request
    (when-not (and (= {:command command :scope scope :actor actor} (dissoc request :input))
                   (= entered (select-keys (:input request) (keys entered))))
      (fail! :idempotency-conflict "This command identifier belongs to another request"))
    (:input request)))


;; Reads


(def sync-interval
  (Duration/ofHours 24))


(def max-failures
  "Consecutive failed runs after which the schedule stops until Sync now or a credential replacement."
  5)


(def failure-waits
  "Wait after the 1st to 4th consecutive failed run."
  [(Duration/ofMinutes 1) (Duration/ofMinutes 5) (Duration/ofMinutes 15) (Duration/ofHours 1)])


(defn next-sync-at
  "When the connection is due on schedule, or nil: never while paused, rejected or stopped after 5 failures.
  An open run is due from its start; after a failure the wait applies; otherwise 24 hours after the last
  completed run, or at once (its connection time) before the first. Provider throttling defers all of these."
  {:malli/schema [:=> [:cat :map] [:maybe validation/Instant]]}
  [{:keys [sync-state credentials-state last-completed-sync-at connected-at run-state started-at
           consecutive-failures next-attempt-at resume-not-before]}]
  (when (and (= "active" sync-state) (= "valid" credentials-state))
    (let [due (cond
                (= "open" run-state) (at started-at)
                (<= max-failures (or consecutive-failures 0)) nil
                (pos? (or consecutive-failures 0)) (at next-attempt-at)
                last-completed-sync-at (.plus ^Instant (at last-completed-sync-at) sync-interval)
                :else (at connected-at))]
      (when due
        (let [resume (at resume-not-before)]
          (if (and resume (.isAfter ^Instant resume due)) resume due))))))


(defn due?
  {:malli/schema [:=> [:cat :map validation/Instant] :boolean]}
  [connection now]
  (boolean (some-> (next-sync-at connection) (#(not (.isAfter ^Instant % now))))))


(defn scheduling-stopped?
  {:malli/schema [:=> [:cat :map] :boolean]}
  [{:keys [consecutive-failures run-state]}]
  (and (not= "open" run-state) (<= max-failures (or consecutive-failures 0))))


(defn- store-ref
  {:malli/schema [:=> [:cat :map [:maybe :map]] :map]}
  [connection store]
  {:store-id (:store-id connection) :name (or (:name store) (:store-id connection)) :archived? (not= "active" (:status store))})


(def StoreRef
  [:map [:store-id records/Id] [:name :string] [:archived? :boolean]])


(def ConnectionRow
  [:map
   [:connection-id Ref] [:provider Provider] [:provider-account-id :string] [:account-label :string] [:store StoreRef]
   [:sync-state SyncState] [:credentials-state CredentialsState]
   [:last-completed-sync-at {:optional true} validation/Instant]])


(def MarketplaceConnectionsAndRequirements
  [:map
   [:connections [:vector ConnectionRow]]
   [:selectable-stores [:vector [:map [:store-id records/Id] [:name :string]]]]
   [:can-manage? :boolean]])


(defn- row
  "Display fields only: never the secret reference."
  {:malli/schema [:=> [:cat :map [:maybe :map]] ConnectionRow]}
  [connection store]
  (cond-> (assoc (select-keys connection [:connection-id :provider :provider-account-id :sync-state :credentials-state])
                 :account-label (or (:account-label connection) (:provider-account-id connection))
                 :store (store-ref connection store))
    (:last-completed-sync-at connection) (assoc :last-completed-sync-at (at (:last-completed-sync-at connection)))))


(defreadmodel marketplace-connections-and-requirements
  {:input [:map] :output MarketplaceConnectionsAndRequirements :policy ::records/workspace-member
   :cofx  [[:connections ::all (constantly {})] [:stores ::stores/all (constantly {})] viewer]}
  [{:keys [connections stores viewer]} _]
  (let [by-id (into {} (map (juxt :store-id identity)) stores)]
    {:connections       (->> connections (sort-by (juxt :connected-at :connection-id))
                             (mapv #(row % (by-id (:store-id %)))))
     :selectable-stores (->> stores (filter #(= "active" (:status %))) (sort-by :name)
                             (mapv #(select-keys % [:store-id :name])))
     :can-manage?       (:manager? viewer)}))


(def BlockingReason
  "Unmatched listings and warehouses hold only the operations that name them, never the connection."
  [:enum "paused" "needs credentials" "no baseline"])


(def ConnectionStatusAndSyncCoverage
  [:map
   [:connection-id Ref] [:provider Provider] [:provider-account-id :string] [:account-label :string] [:store StoreRef]
   [:account-currency :string] [:key-expires-at {:optional true} validation/Instant]
   [:sync-state SyncState] [:credentials-state CredentialsState]
   [:current-run {:optional true} Ref]
   [:last-completed-sync-at {:optional true} validation/Instant]
   [:next-sync-at {:optional true} validation/Instant]
   [:domains [:vector [:map [:domain Domain] [:readable? :boolean] [:covered-until {:optional true} validation/Instant]]]]
   [:unmatched-listings :int] [:unmatched-warehouses :int] [:scopes-without-baseline :int]
   [:imported-operations-by-next-step [:vector [:map [:next-step :string] [:count :int]]]]
   [:currency-notice? :boolean] [:workspace-currency {:optional true} :string]
   [:automatic-acceptance-possible? :boolean]
   [:blocking-reasons [:vector BlockingReason]]
   [:can-manage? :boolean]])


(defn domain-rows
  "One row per domain; covered-until (domain -> ISO text, from chapter 41's sync positions) for readable ones."
  {:malli/schema [:function [:=> [:cat :map] [:vector :map]] [:=> [:cat :map [:maybe [:map-of :string :string]]] [:vector :map]]]}
  ([connection] (domain-rows connection {}))
  ([connection covered]
   (let [readable (decode-domains (:readable-domains connection))]
     (mapv (fn [domain]
             (let [until (when (contains? readable domain) (at (get covered domain)))]
               (cond-> {:domain domain :readable? (contains? readable domain)} until (assoc :covered-until until))))
           connector/domains))))


(defmethod runtime/coeffect ::covered-until
  [_ context input]
  ;; Sync positions are chapter 41's model; its namespace answers through this multimethod.
  (if (contains? (methods runtime/coeffect) :tover.marketplace.sync.model/covered-until)
    (runtime/coeffect :tover.marketplace.sync.model/covered-until context input)
    {}))


(defmulti count-part
  "One part of the coverage counts for a connection: `:unmatched-listings`, `:unmatched-warehouses`,
  `:scopes-without-baseline`, `:baselines` (whole numbers) and `:next-steps` ({next step count}). The chapters
  that own the data add a method each (42, 43, 44, 45); until then a part is zero."
  (fn [part _context _connection-id] part))


(defmethod count-part :default [_ _ _] 0)
(defmethod count-part :next-steps [_ _ _] {})


(def ^:private count-parts
  [:unmatched-listings :unmatched-warehouses :scopes-without-baseline :baselines :next-steps])


(defmethod runtime/coeffect ::counts
  [_ context {:keys [connection-id]}]
  (into {} (map (fn [part] [part (count-part part context connection-id)])) count-parts))


(defn status
  "Connection Status and Sync Coverage. `counts` (see `count-part`) and `currency` (the workspace's) come from
  the caller; absent counts are zero. Only three things block automatic acceptance of a connection."
  {:malli/schema [:=> [:cat :map [:maybe :map] [:maybe :map]] ConnectionStatusAndSyncCoverage]}
  [connection store {:keys [manager? covered counts currency]}]
  (let [{:keys [unmatched-listings unmatched-warehouses scopes-without-baseline baselines next-steps]
         :or   {unmatched-listings 0 unmatched-warehouses 0 scopes-without-baseline 0 baselines 0 next-steps {}}} counts
        reasons (cond-> []
                  (= "paused" (:sync-state connection)) (conj "paused")
                  (= "rejected" (:credentials-state connection)) (conj "needs credentials")
                  (not (pos? baselines)) (conj "no baseline"))
        next-at (next-sync-at connection)]
    (cond-> (assoc (row connection store)
                   :account-currency (:account-currency connection)
                   :domains (domain-rows connection covered)
                   :unmatched-listings unmatched-listings :unmatched-warehouses unmatched-warehouses
                   :scopes-without-baseline scopes-without-baseline
                   :imported-operations-by-next-step (->> next-steps (filter (comp pos? val)) (sort-by key)
                                                          (mapv (fn [[step n]] {:next-step step :count n})))
                   :currency-notice? (boolean (and currency (not= currency (:account-currency connection))))
                   :automatic-acceptance-possible? (empty? reasons)
                   :blocking-reasons reasons
                   :can-manage? (boolean manager?))
      currency (assoc :workspace-currency currency)
      (:key-expires-at connection) (assoc :key-expires-at (at (:key-expires-at connection)))
      (= "open" (:run-state connection)) (assoc :current-run (:run-id connection))
      next-at (assoc :next-sync-at next-at))))


(defreadmodel connection-status-and-sync-coverage
  {:input ConnectionAttempt :output ConnectionStatusAndSyncCoverage :policy ::records/workspace-member
   :cofx  [connection [:store ::stores/stores connection-store] viewer covered currency/row-fact
           [:counts ::counts by-connection]]}
  [{:keys [connection store viewer covered counts currency-row]} _]
  (status (known! connection) store {:manager? (:manager? viewer)              :covered covered :counts counts
                                     :currency (currency/current currency-row)}))


(def PausedConnection
  [:map
   [:connection-id Ref]
   [:paused-by [:map [:account-id Ref] [:display-name {:optional true} :string]]]
   [:paused-at validation/Instant]
   [:reason PauseReason]
   [:resume-positions [:vector [:map [:domain Domain] [:covered-until {:optional true} validation/Instant]]]]
   [:pending-imported-proposals :int]
   [:can-resume? :boolean]])


(defreadmodel paused-connection
  {:input ConnectionAttempt :output PausedConnection :policy ::records/workspace-member
   :cofx  [connection [:store ::stores/stores connection-store] viewer covered
           [:label ::account-label (fn [facts _] {:account-id (get-in facts [:connection :paused-by])})]]}
  [{:keys [connection store viewer label covered]} _]
  (let [{:keys [paused-by] :as connection} (known! connection)]
    (when-not (= "paused" (:sync-state connection))
      (fail! :not-paused "This connection is not paused"))
    ;; ponytail: imported proposals arrive with chapter 45; until then none wait in Review.
    {:connection-id              (:connection-id connection)
     :paused-by                  (cond-> {:account-id paused-by} label (assoc :display-name label))
     :paused-at                  (at (:paused-at connection))
     :reason (:reason connection)
     :resume-positions           (mapv #(select-keys % [:domain :covered-until]) (filter :readable? (domain-rows connection covered)))
     :pending-imported-proposals 0
     :can-resume?                (and (:manager? viewer) (= "valid" (:credentials-state connection)) (= "active" (:status store)))}))


(def ConnectionNeedingAttention
  [:map [:connection-id Ref] [:rejected-at validation/Instant] [:run-id Ref] [:sync-state SyncState]
   [:can-replace-credentials? :boolean]])


(defreadmodel connection-needing-attention
  {:input ConnectionAttempt   :output ConnectionNeedingAttention :policy ::records/workspace-member
   :cofx  [connection viewer]}
  [{:keys [connection viewer]} _]
  (let [connection (known! connection)]
    (when-not (= "rejected" (:credentials-state connection))
      (fail! :credentials-valid "This connection's credentials are valid"))
    (assoc (select-keys connection [:connection-id :run-id :sync-state])
           :rejected-at (at (:rejected-at connection))
           :can-replace-credentials? (:manager? viewer))))
