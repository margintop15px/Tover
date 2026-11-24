(ns tover.fixtures
  "Offline Bel fixtures for onboarding tests. Absent from the production classpath.

  One `state` atom holds an in-memory directory in PostgreSQL column terms, keyed by table:
    {:db      {:auth.users {id row} :profiles {user-id row} :workspaces {id row}
               :memberships {id row} :invitations {id row}
               :invitation_deliveries {id row} :sessions {secret-key row}
               :session_boundaries {user-id row}}
     :journal [delivery …]      accepted command batches
     :emails  [{:type :signup|:recovery :email :token-hash :redirect-to} …]   provider outbox
     :tokens  {access-token {:user-id :refresh-token}}}
  The Bel directory store, the CurrentSession-like authority, the Ring session store and the fake GoTrue
  provider all read and write it, so a command's write is what the next request's access check sees.
  Model-backed reads and writes work; SQL coeffects (`db/execute!` on the tx) do not and need integration tests.

  Seeded users (password `tover-development`, all confirmed):
    owner@example.test   Olga Owner   owner of `workspace-id`, member of `other-workspace-id`
    member@example.test  Max Member   member of `workspace-id`
    other@example.test   Otto Other   owner of `other-workspace-id`
    unnamed@example.test (no name)    no memberships; the profile gate applies

  API:
    (system)                                   services for tover.app/create-handler (add :repo and :realtime)
    (user email)                               {:user-id :email :password} of a seeded or signed-up user
    (sign-in! services email opts)             a live session; opts :workspace-id :password-required? :data;
                                               returns {:key :session-id :version :csrf}
    (request services session req)             a Ring request for that session; req :method :uri :params
                                               :headers; POSTs carry the form, CSRF token and x-tover-context
    (context services session)                 the trusted Bel context (`:bel/context` + :bel/system) of that
                                               session, for runtime/execute! and runtime/read-model in tests"
  (:require
    [bel-postgres.adapter :as postgres]
    [bel.runtime :as runtime]
    [bel.store :as store]
    [bel.validation :as validation]
    [clojure.string :as str]
    [ring.util.codec :as codec]
    [tover.identity.access :as access]
    [tover.identity.provider :as provider]
    [tover.identity.session :as session]
    [tover.workspaces.persistence :as workspaces])
  (:import
    [java.io ByteArrayInputStream]
    [java.time Instant]
    [java.util UUID]))


(def password
  "tover-development")


(def owner-id #uuid "00000000-0000-4000-8000-000000000001")
(def member-id #uuid "00000000-0000-4000-8000-000000000002")
(def other-id #uuid "00000000-0000-4000-8000-000000000003")
(def unnamed-id #uuid "00000000-0000-4000-8000-000000000004")
(def workspace-id #uuid "00000000-0000-4000-8000-000000000101")
(def other-workspace-id #uuid "00000000-0000-4000-8000-000000000102")


(def ^:private seeded-at
  (Instant/parse "2026-01-01T00:00:00Z"))


(defn seed
  "The initial in-memory database."
  {:malli/schema [:=> [:cat] :map]}
  []
  (let [users       [[owner-id "owner@example.test" "Olga Owner"] [member-id "member@example.test" "Max Member"]
                     [other-id "other@example.test" "Otto Other"] [unnamed-id "unnamed@example.test" nil]]
        memberships [[workspace-id owner-id "owner"] [workspace-id member-id "member"]
                     [other-workspace-id owner-id "member"] [other-workspace-id other-id "owner"]]]
    {:auth.users         (into {} (for [[id email] users]
                                    [id {:id id :email email :email_confirmed_at seeded-at :password password}]))
     :profiles           (into {} (for [[id _ name] users]
                                    [id (cond-> {:user_id id :name_revision (if name 1 0) :language_revision 0}
                                          name (assoc :display_name name))]))
     :workspaces         {workspace-id       {:workspace_id workspace-id :name "Development workspace" :created_by owner-id :created_at seeded-at}
                          other-workspace-id {:workspace_id other-workspace-id :name "Other workspace" :created_by other-id :created_at seeded-at}}
     :memberships        (into {} (for [[workspace user role] memberships
                                        :let                  [id (UUID/nameUUIDFromBytes (.getBytes (str workspace user)))]]
                                    [id {:membership_id id :workspace_id workspace :user_id user :role role :status "active"
                                         :revision      1  :created_at   seeded-at}]))
     :sessions           {}
     :session_boundaries {}}))


;; Directory store


(defn- entry
  "The [key row] of `table` whose `column` equals `id`."
  {:malli/schema [:=> [:cat :map :keyword :keyword :any] [:maybe [:tuple :any :map]]]}
  [db table column id]
  (some (fn [[key row]] (when (= id (get row column)) [key row])) (get db table)))


(defrecord MemoryDirectory [state]
  store/Transactional
  (-transact [_ _ f] (locking state (f state)))

  store/Store
  (-load [_ _ _ model id]
    (let [{:keys [table columns]} (postgres/table-binding model)]
      (when-let [[_ row] (entry (:db @state) table (get columns (:id model)) id)]
        (into {} (keep (fn [[field column]] (when (some? (get row column)) [field (get row column)]))) columns))))
  (-create! [_ _ _ model row]
    (validation/check! (:schema model) row)
    (let [{:keys [table columns]} (postgres/table-binding model)
          id (get row (:id model))]
      (when (entry (:db @state) table (get columns (:id model)) id)
        (throw (ex-info "This identity is already in use." {:type :identity-conflict})))
      (swap! state assoc-in [:db table id] (into {} (map (fn [[field column]] [column (get row field)])) columns))))
  (-write! [_ _ _ model fields row]
    (let [{:keys [table columns]} (postgres/table-binding model)
          id (get row (:id model))
          values                  (into {} (map (fn [field] [(get columns field) (get row field)])) (cons (:id model) fields))]
      (if-let [[key _] (entry (:db @state) table (get columns (:id model)) id)]
        (swap! state update-in [:db table key] merge values)
        (do (validation/check! (:schema model) row)
            (swap! state assoc-in [:db table id] values)))))
  (-delete! [_ _ _ model _]
    (throw (ex-info "Delete mappings are not supported in Postgres" {:type :unsupported-mapping :model (:model-id model)})))

  store/Journal
  (-receipt [_ _ scope command-id]
    (some #(when (and (= scope (:scope %)) (= command-id (:command-id %))) %) (:journal @state)))
  (-append! [_ _ scope delivery]
    (swap! state update :journal conj (assoc delivery :scope scope)))
  (-history [_ _ scope]
    (filterv #(= scope (:scope %)) (:journal @state))))


(defn- session-row
  {:malli/schema [:=> [:cat :string [:maybe :map]] [:maybe :map]]}
  [key columns]
  (when columns
    {:id         key                   :session-id   (:session_id columns)
     :data       (:data columns {})    :tokens       (:tokens columns)
     :user-id    (:user_id columns)    :workspace-id (:workspace_id columns)
     :version    (:version columns)    :created-at   (:created_at columns)
     :expires-at (:expires_at columns) :ended-at     (:ended_at columns)}))


(defn- session-columns
  {:malli/schema [:=> [:cat :map] :map]}
  [row]
  {:session_id (:session-id row) :data         (:data row)         :tokens   (:tokens row)
   :user_id    (:user-id row)    :workspace_id (:workspace-id row) :version  (:version row)
   :created_at (:created-at row) :expires_at   (:expires-at row)   :ended_at (:ended-at row)})


(defn directory
  "The access check's directory facts for `user-id`, as the SQL query returns them."
  {:malli/schema [:=> [:cat :map :uuid] session/Directory]}
  [db user-id]
  (let [profile  (get-in db [:profiles user-id])
        boundary (get-in db [:session_boundaries user-id])]
    {:profile     (when profile {:display-name (:display_name profile) :language (:language profile)})
     :boundary    (session/effective-boundary
                    (when boundary {:valid-after (:valid_after boundary) :kept-session-id (:kept_session_id boundary)})
                    (some->> (vals (:password_resets db))
                             (filter #(and (= user-id (:user_id %)) (= "requested" (:status %))))
                             seq (apply max-key #(.toEpochMilli ^java.time.Instant (:requested_at %)))
                             ((fn [r] {:requested-at (:requested_at r) :session-id (:session_id r)}))))
     :memberships (->> (vals (:memberships db))
                       (filter #(and (= user-id (:user_id %)) (= "active" (:status %))))
                       (sort-by (juxt :created_at :workspace_id))
                       (mapv (fn [{:keys [workspace_id role created_at]}]
                               {:workspace-id workspace_id
                                :name         (get-in db [:workspaces workspace_id :name])
                                :role         (keyword role)
                                :created-at   created_at})))}))


(defn session-store
  "The Ring session store over `state`; rows are plaintext here, encrypted only in PostgreSQL."
  {:malli/schema [:=> [:cat :any] :map]}
  [state]
  (session/->Store
    (fn [key f]
      (locking state
        (let [row             (session-row key (get-in @state [:db :sessions key]))
              [updated value] (f row #(directory (:db @state) %))]
          (cond
            updated (swap! state assoc-in [:db :sessions key] (session-columns updated))
            row (swap! state update-in [:db :sessions] dissoc key))
          value)))
    (fn [session-id]
      (first (entry (:db @state) :sessions :session_id session-id)))))


(defrecord FixtureSession [state]
  runtime/Authority
  (-principal [_ _ {:keys [user-id scope auth-session-id session-id] :as context}]
    (session/check-context! context)
    (let [db        (:db @state)
          row       (session-row auth-session-id (get-in db [:sessions auth-session-id]))
          _         (when-not (and (= user-id (:user-id row)) (= session-id (:session-id row))
                                   (session/valid? row (:boundary (directory db user-id))))
                      (session/ended!))
          principal {:user-id            user-id :session-id session-id
                     :confirmed?         (some? (get-in db [:auth.users user-id :email_confirmed_at]))
                     :password-required? (boolean (get-in row [:data :identity/password-required?]))
                     :memberships        []}]
      (if-let [workspace (:workspace-id scope)]
        (let [membership (second (some #(when (and (= workspace (:workspace_id (val %))) (= user-id (:user_id (val %)))) %)
                                       (:memberships db)))]
          (when-not (and (:confirmed? principal) (not (:password-required? principal)) (= "active" (:status membership)))
            (throw (ex-info "Current workspace access is required" {:type :forbidden :status 403})))
          (assoc principal :memberships [{:workspace-id workspace :role (keyword (:role membership))}]))
        principal))))


;; The directory transaction here is the state atom; membership lookups read it as SQL would.
(extend-protocol workspaces/Memberships
  clojure.lang.Atom
  (-membership [state user-id workspace-id]
    (some (fn [{:keys [membership_id workspace_id user_id role status]}]
            (when (and (= workspace-id workspace_id) (= user-id user_id))
              {:membership-id membership_id :workspace-id workspace_id :user-id user_id :role role :status status}))
          (vals (get-in @state [:db :memberships])))))


(alter-meta! #'->MemoryDirectory assoc :malli/schema [:=> [:cat :any] :map])
(alter-meta! #'map->MemoryDirectory assoc :malli/schema [:=> [:cat :map] :map])
(alter-meta! #'->FixtureSession assoc :malli/schema [:=> [:cat :any] :map])
(alter-meta! #'map->FixtureSession assoc :malli/schema [:=> [:cat :map] :map])


;; Fake GoTrue


(defn- find-user
  {:malli/schema [:=> [:cat :map [:maybe :string]] [:maybe :map]]}
  [db email]
  (let [email (some-> email str/trim str/lower-case)]
    (some #(when (= email (:email %)) %) (vals (:auth.users db)))))


(defn- user-body
  {:malli/schema [:=> [:cat :map] :map]}
  [user]
  {:id                 (str (:id user)) :email (:email user)
   :email_confirmed_at (some-> (:email_confirmed_at user) str)
   :user_metadata      (select-keys user [:locale])})


(defn- issue!
  {:malli/schema [:=> [:cat :any :uuid] :map]}
  [state user-id]
  (let [access (str (UUID/randomUUID)) refresh (str (UUID/randomUUID))]
    (swap! state assoc-in [:tokens access] {:user-id user-id :refresh-token refresh})
    {:access_token access :refresh_token refresh :expires_in 3600
     :user         (user-body (get-in @state [:db :auth.users user-id]))}))


(defn- queue!
  {:malli/schema [:=> [:cat :any :keyword :string [:maybe :map]] :nil]}
  [state type email query]
  (swap! state update :emails (fnil conj [])
         {:type type :email email :token-hash (str (UUID/randomUUID)) :redirect-to (:redirect_to query)})
  nil)


(defn- token-user!
  {:malli/schema [:=> [:cat :any [:maybe :string]] :uuid]}
  [state token]
  (or (get-in @state [:tokens token :user-id]) (provider/fail! :unauthenticated 401 false)))


(defn fake-provider
  "A provider whose `:request!` answers like GoTrue over `state`; failures use the provider's error codes."
  {:malli/schema [:=> [:cat :any] provider/Provider]}
  [state]
  {:request!
   (fn [{:keys [method path query body token] :or {method :post}}]
     (locking state
       (let [db (:db @state)]
         (case [method path]
           [:post "/signup"]
           (do (when (find-user db (:email body)) (provider/fail! :email-exists 422 false))
               (let [id    (UUID/randomUUID)
                     email (str/lower-case (str/trim (:email body)))
                     user  (cond-> {:id id :email email :password (:password body)}
                             (get-in body [:data :locale]) (assoc :locale (get-in body [:data :locale])))]
                 ;; The database triggers create the profile, starting from the signup language.
                 (swap! state #(-> %
                                   (assoc-in [:db :auth.users id] user)
                                   (assoc-in [:db :profiles id]
                                             (cond-> {:user_id id :name_revision 0 :language_revision 0}
                                               (:locale user) (assoc :language (:locale user))))))
                 (queue! state :signup email query)
                 (user-body user)))

           [:post "/token"]
           (if (= "refresh_token" (:grant_type query))
             (let [[access {:keys [user-id]}] (some #(when (= (:refresh_token body) (:refresh-token (val %))) %) (:tokens @state))]
               (when-not user-id (provider/fail! :invalid-refresh-token 400 false))
               (swap! state update :tokens dissoc access)
               (issue! state user-id))
             (let [user (find-user db (:email body))]
               (when-not (and user (= (:password body) (:password user))) (provider/fail! :invalid-credentials 400 false))
               (when-not (:email_confirmed_at user) (provider/fail! :email-not-confirmed 400 false))
               (issue! state (:id user))))

           [:post "/verify"]
           (let [email (some #(when (and (= (:token_hash body) (:token-hash %)) (= (:type body) (name (:type %)))) %) (:emails @state))
                 user  (when email (find-user db (:email email)))]
             (when-not user (provider/fail! :verification-expired 403 false))
             (swap! state #(-> %
                               (update :emails (fn [emails] (filterv (fn [e] (not= (:token-hash e) (:token_hash body))) emails)))
                               (update-in [:db :auth.users (:id user) :email_confirmed_at] (fnil identity (Instant/now)))))
             (issue! state (:id user)))

           [:post "/resend"]
           (let [user (find-user db (:email body))]
             (when (and user (not (:email_confirmed_at user))) (queue! state :signup (:email user) query))
             {})

           [:post "/recover"]
           (do (when-let [user (find-user db (:email body))] (queue! state :recovery (:email user) query))
               {})

           [:put "/user"]
           (let [user-id (token-user! state token)]
             (when (:password body) (swap! state assoc-in [:db :auth.users user-id :password] (:password body)))
             (user-body (get-in @state [:db :auth.users user-id])))

           [:post "/logout"]
           (let [user-id (token-user! state token)
                 ended?  (case (:scope query)
                           "local" #(= token (key %))
                           "others" #(and (= user-id (:user-id (val %))) (not= token (key %)))
                           #(= user-id (:user-id (val %))))]
             (swap! state update :tokens #(into {} (remove ended?) %))
             nil)

           (provider/fail! :not-found 404 false)))))})


;; Services and requests


(defn bel-system
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [state provider session-store origin realtime]}]
  {:stores    {:directory (->MemoryDirectory state)}
   :journal   :directory
   :scope     :workspace
   :authority {:store :directory :impl (->FixtureSession state)}
   :resources {:provider provider :session-store session-store :origin origin :realtime realtime}})


(defn system
  "Fresh offline services. Pass the result (plus :repo and :realtime) to tover.app/create-handler."
  {:malli/schema [:=> [:cat] :map]}
  []
  (let [state    (atom {:db (seed) :journal [] :emails [] :tokens {}})
        services {:state         state
                  :provider      (fake-provider state)
                  :session-store (session-store state)
                  :origin        "http://localhost:3030"
                  :development?  true}]
    (assoc services :bel/system (bel-system services))))


(defn user
  {:malli/schema [:=> [:cat :map :string] :map]}
  [{:keys [state]} email]
  (let [user (or (find-user (:db @state) email) (throw (ex-info "Unknown fixture user" {:type :not-found})))]
    {:user-id (:id user) :email (:email user) :password (:password user)}))


(defn sign-in!
  "Establish a live session for `email` as the sign-in action would, with provider tokens."
  {:malli/schema [:=> [:cat :map :string [:? [:map [:workspace-id {:optional true} :uuid]
                                              [:password-required? {:optional true} :boolean]
                                              [:data {:optional true} :map]]]]
                  [:map [:key :string] [:session-id :uuid] [:version :uuid] [:csrf :string]]]}
  ([services email] (sign-in! services email {}))
  ([{:keys [provider session-store] :as services} email {:keys [workspace-id password-required? data]}]
   (let [{:keys [password]} (user services email)
         tokens             (provider/sign-in! provider {:email email :password password})
         key                (session/random-key)
         csrf               (session/random-key)
         row                (cond-> (assoc (session/new-row key) :tokens tokens :user-id (get-in tokens [:user :user-id])
                                           :data (merge {:ring.middleware.anti-forgery/anti-forgery-token csrf
                                                         :identity/password-required? (boolean password-required?)} data))
                              workspace-id (assoc :workspace-id workspace-id))]
     (session/transact! session-store key (fn [_ _] [row nil]))
     {:key key :session-id (:session-id row) :version (:version row) :csrf csrf})))


(defn request
  "A Ring request from the browser holding `session`."
  {:malli/schema [:=> [:cat :map [:maybe :map] :map] :map]}
  [{:keys [session-store]} session {:keys [method uri params headers] :or {method :get params {}}}]
  (let [post?   (= :post method)
        version (some-> (session/session session-store (:key session)) :version str)
        params  (cond-> params
                  (and post? session) (assoc "__anti-forgery-token" (:csrf session) "_tover-context" version))
        encoded (codec/form-encode params)]
    (cond-> {:uri     uri :request-method method :scheme :http :server-name "localhost" :server-port 3030
             :headers (merge (cond-> {"host" "localhost:3030" "origin" "http://localhost:3030"}
                               session (assoc "cookie" (str "tover-session=" (:key session)))
                               post? (assoc "content-type" "application/x-www-form-urlencoded"))
                             headers)}
      post? (assoc :body (ByteArrayInputStream. (.getBytes ^String encoded "UTF-8")))
      (and (not post?) (seq params)) (assoc :query-string encoded))))


(defn context
  "The trusted Bel context a request of this session receives."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [services {:keys [key]}]
  (merge (select-keys services [:session-store :provider :origin :bel/system])
         (assoc (access/current-identity services key) :locale "en")))


(defn actor-labels
  "tover.inventory.actors/labels over the fixture `state`: display names of the workspace's active members. The
  real function reads them with SQL, which the in-memory directory cannot run; browser test servers swap it in."
  {:malli/schema [:=> [:cat :any] [:fn ifn?]]}
  [state]
  (fn [{:keys [workspace-id]} _records]
    (let [db @state]
      (into {} (for [{:keys [workspace_id user_id status]} (vals (get-in db [:db :memberships]))
                     :when (and (= workspace-id workspace_id) (= "active" status))
                     :let [name (get-in db [:db :profiles user_id :display_name])]
                     :when name]
                 [(str user_id) name])))))
