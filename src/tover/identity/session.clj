(ns tover.identity.session
  "Tover sign-ins: the opaque Ring session store on app_private.sessions (row lock, AES-GCM tokens, 30-day
  absolute expiry), the session models and the CurrentSession Bel authority.

  A session row is {:id key :session-id uuid :data map :tokens map :user-id :workspace-id :version
  :created-at :expires-at :ended-at}. `:id` is the secret cookie key and never leaves this layer; `:session-id`
  is the non-secret Bel identity (`:bel/session-id`). `version` changes with every access change (selection,
  password state) and is echoed by unsafe requests. A row is live while unexpired and not ended, and valid
  while live and not covered by the user's reset boundary."
  (:require
    [bel-postgres.jdbc :as db]
    [bel.core :refer [defmodel]]
    [bel.runtime :as runtime]
    [bel.validation :as validation]
    [buddy.core.codecs :as codecs]
    [buddy.core.crypto :as crypto]
    [buddy.core.nonce :as nonce]
    [charred.api :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [com.brunobonacci.mulog :as mu]
    [next.jdbc :as jdbc]
    [next.jdbc.date-time :as date-time]
    [next.jdbc.result-set :as result-set]
    [ring.middleware.session.store :as ring]
    [tover.shared.jdbc :as database])
  (:import
    [java.time Instant]
    [java.util UUID]))


(date-time/read-as-instant)


(def ttl-seconds
  (* 30 24 60 60))


(def Membership
  [:map [:workspace-id :uuid] [:name :string] [:role :keyword] [:created-at {:optional true} :any]])


(def Directory
  "What the access check reads with the session row: profile, active memberships and the reset boundary."
  [:map
   [:profile [:maybe [:map [:display-name {:optional true} [:maybe :string]] [:language {:optional true} [:maybe :string]]]]]
   [:memberships [:vector Membership]]
   [:boundary [:maybe [:map [:valid-after inst?] [:kept-session-id {:optional true} [:maybe :uuid]]]]]])


;; Models. Slices that change a session declare their own defmodel over `session-table` with the fields
;; they write; these two are the shared read side.


(defmethod runtime/policy ::current-session
  [_ {:keys [principal]} {:keys [session-id]}]
  (boolean (and session-id (= session-id (:session-id principal)))))


(defmethod runtime/policy ::own-boundary
  [_ {:keys [principal]} {:keys [user-id]}]
  (boolean (and user-id (= user-id (:user-id principal)))))


(def Session
  [:map
   [:session-id :uuid]
   [:user-id {:optional true} :uuid]
   [:workspace-id {:optional true} :uuid]
   [:version :uuid]
   [:created-at validation/Instant]
   [:expires-at validation/Instant]
   [:ended-at {:optional true} validation/Instant]])


(defmodel sessions
  {:id     :session-id
   :store  :directory
   :schema Session
   :policy ::current-session})


(defmodel session-boundaries
  {:id     :user-id
   :store  :directory
   :schema [:map [:user-id :uuid] [:valid-after validation/Instant] [:kept-session-id {:optional true} :uuid]]
   :policy ::own-boundary})


(def columns
  {:session-id :session_id :user-id    :user_id    :workspace-id :workspace_id :version :version
   :created-at :created_at :expires-at :expires_at :ended-at     :ended_at})


(defn session-table
  "A native table binding on app_private.sessions for `fields` (default: every field of Session)."
  {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat [:sequential :keyword]] :map]]}
  ([] (session-table (keys columns)))
  ([fields] {:table :app_private.sessions :columns (select-keys columns (cons :session-id fields))}))


(def tables
  {::sessions           (session-table)
   ::session-boundaries {:table   :app_private.session_boundaries
                         :columns {:user-id :user_id :valid-after :valid_after :kept-session-id :kept_session_id}}})


;; Rows


(defn random-key
  {:malli/schema [:=> [:cat] :string]} []
  (codecs/bytes->b64-str (nonce/random-bytes (long 32)) true))


(defn new-row
  {:malli/schema [:=> [:cat :string] :map]} [id]
  (let [now (Instant/now)]
    {:id         id  :session-id (UUID/randomUUID)              :data {} :version (UUID/randomUUID)
     :created-at now :expires-at (.plusSeconds now ttl-seconds)}))


(defn live?
  "Unexpired and not ended."
  {:malli/schema [:=> [:cat [:maybe :map]] :boolean]}
  [row]
  (boolean (and row (nil? (:ended-at row)) (.isAfter ^Instant (:expires-at row) (Instant/now)))))


(defn valid?
  "Live and not covered by the user's reset boundary; the sign-in that performed the reset is kept."
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :map]] :boolean]}
  [row boundary]
  (boolean (and (live? row)
                (or (nil? boundary)
                    (.isAfter ^Instant (:created-at row) (:valid-after boundary))
                    (= (:session-id row) (:kept-session-id boundary))))))


(defn profile-complete?
  {:malli/schema [:=> [:cat [:maybe :map]] :boolean]}
  [profile]
  (let [name (some-> (:display-name profile) str/trim)]
    (boolean (and (seq name) (<= (count (:display-name profile)) 100)))))


;; Ring store. `transaction` is (fn [key f]); f receives the locked row (or nil) and `directory`, a
;; (fn [user-id] Directory) reading in the same transaction, and returns [updated-row value]. A nil updated
;; row deletes it. `find-key` maps a Bel session id to its key.


(defn- write-row
  {:malli/schema [:=> [:cat [:maybe :map] :string :map] :map]}
  [row id data]
  (let [tokens     (:identity/tokens data)
        session-id (:bel/session-id data)]
    (cond-> (assoc (or row (new-row id))
                   :data (cond-> (dissoc data :identity/session-id :identity/version :identity/tokens)
                           (and row (not tokens))
                           (assoc :identity/password-required? (get-in row [:data :identity/password-required?]))))
      (uuid? session-id) (assoc :session-id session-id)
      tokens (assoc :tokens tokens :user-id (get-in tokens [:user :user-id])))))


(defrecord Store [transaction find-key]
  ring/SessionStore
  (read-session [_ key]
    (if key
      (transaction key
                   (fn [row _]
                     (if (live? row)
                       [row (assoc (:data row) :identity/session-id key :identity/version (:version row)
                                   :bel/session-id (:session-id row))]
                       [row {}])))
      {}))
  (write-session [_ key data]
    ;; An ended or expired key is never revived: the browser gets a new one.
    (let [id (or (when key (transaction key (fn [row _] [row (when (live? row) key)]))) (random-key))]
      (transaction id (fn [row _] [(write-row row id data) id]))))
  (delete-session [_ key]
    (when key (transaction key (fn [_ _] [nil nil])))
    nil))


;; Record constructors participate in the same development instrumentation as functions.
(alter-meta! #'->Store assoc :malli/schema [:=> [:cat [:fn ifn?] [:fn ifn?]] :map])
(alter-meta! #'map->Store assoc :malli/schema [:=> [:cat :map] :map])


(defn transact!
  "Lock the row for `key` and call (f row directory) -> [updated value]. Missing rows pass nil."
  {:malli/schema [:=> [:cat :map [:maybe :string] [:fn ifn?]] :any]}
  [store key f]
  (if key ((:transaction store) key f) (second (f nil (constantly nil)))))


(defn session
  "The live row for `key`, or nil."
  {:malli/schema [:=> [:cat :map [:maybe :string]] [:maybe :map]]}
  [store key]
  (transact! store key (fn [row _] [row (when (live? row) row)])))


(defn update-session!
  "Change a live row under its lock: f receives the row and returns [updated value]."
  {:malli/schema [:=> [:cat :map [:maybe :string] [:fn ifn?]] :any]}
  [store key f]
  (transact! store key (fn [row _] (if (live? row) (f row) [row nil]))))


(defn session-key
  "The secret key of the session with this Bel session id, for server-side use only."
  {:malli/schema [:=> [:cat :map [:maybe :uuid]] [:maybe :string]]}
  [store session-id]
  (when session-id ((:find-key store) session-id)))


;; PostgreSQL


(defn- encrypt
  {:malli/schema [:=> [:cat :any :string] :string]}
  [key input]
  (let [iv (nonce/random-bytes (long 12))]
    (str (codecs/bytes->b64-str iv) "."
         (codecs/bytes->b64-str (crypto/encrypt (codecs/str->bytes input) key iv {:alg :aes256-gcm})))))


(defn- decrypt
  {:malli/schema [:=> [:cat :any :string] :string]}
  [key input]
  (let [[iv encrypted] (str/split input #"\." 2)]
    (codecs/bytes->str
      (crypto/decrypt (codecs/b64->bytes encrypted) key (codecs/b64->bytes iv) {:alg :aes256-gcm}))))


(def ^:private directory-query
  "select p.display_name, p.language, b.valid_after, b.kept_session_id,
     coalesce((select jsonb_agg(jsonb_build_object('workspace-id', o.id, 'name', o.name, 'role', m.role_id,
                                                   'created-at', m.created_at) order by m.created_at, o.id)
               from public.organization_memberships m join public.organizations o on o.id = m.organization_id
               where m.user_id = u.id and m.status = 'active'), '[]'::jsonb)::text as memberships
   from (select ?::uuid as id) u
   left join public.profiles p on p.user_id = u.id
   left join app_private.session_boundaries b on b.user_id = u.id")


(defn- read-directory
  "Profile, active memberships and boundary in one query, under the user's row policies."
  {:malli/schema [:=> [:cat :any :uuid] Directory]}
  [tx user-id]
  (jdbc/execute-one! tx ["select set_config('request.jwt.claim.sub', ?, true)" (str user-id)])
  (let [row (jdbc/execute-one! tx [directory-query user-id] {:builder-fn result-set/as-unqualified-lower-maps})]
    {:profile     (when row {:display-name (:display_name row) :language (:language row)})
     :boundary    (when (:valid_after row) {:valid-after (:valid_after row) :kept-session-id (:kept_session_id row)})
     :memberships (mapv (fn [{:keys [workspace-id name role created-at]}]
                          {:workspace-id (parse-uuid workspace-id) :name name :role (keyword role) :created-at created-at})
                        (json/read-json (:memberships row) :key-fn keyword))}))


(defn jdbc-store
  {:malli/schema [:=> [:cat [:map [:datasource :any] [:encryption-key :string]]] :map]}
  [{:keys [datasource encryption-key]}]
  (let [key (try (codecs/b64->bytes encryption-key)
                 (catch Exception _ (throw (ex-info "Invalid session encryption key" {:type :configuration}))))]
    (when-not (= 32 (alength key))
      (throw (ex-info "Session encryption key must contain 32 bytes" {:type :configuration})))
    (->Store
      (fn [id f]
        (mu/trace ::transaction {:pairs []}
          (with-open [connection (database/connection datasource)]
            (jdbc/with-transaction [tx connection]
              (let [stored          (jdbc/execute-one!
                                      tx ["select session_id,data,tokens,user_id,workspace_id,version,created_at,expires_at,ended_at
                                           from app_private.sessions where id=? for update" id]
                                      {:builder-fn result-set/as-unqualified-lower-maps})
                    row             (when stored
                                      {:id         id :session-id                  (:session_id stored)
                                       :data       (edn/read-string (:data stored))
                                       :tokens     (some->> (:tokens stored) (decrypt key) edn/read-string)
                                       :user-id    (:user_id stored) :workspace-id (:workspace_id stored)
                                       :version    (:version stored) :created-at   (:created_at stored)
                                       :expires-at (:expires_at stored) :ended-at  (:ended_at stored)})
                    [updated value] (f row #(read-directory tx %))]
                (cond
                  (and updated (not= row updated))
                  (mu/trace ::persist {:pairs [:operation :write]}
                    (jdbc/execute-one!
                      tx ["insert into app_private.sessions(id,session_id,data,tokens,user_id,workspace_id,version,created_at,expires_at,ended_at)
                           values(?,?,?,?,?,?,?,?,?,?)
                           on conflict(id) do update set session_id=excluded.session_id,data=excluded.data,tokens=excluded.tokens,
                             user_id=excluded.user_id,workspace_id=excluded.workspace_id,version=excluded.version,
                             expires_at=excluded.expires_at,ended_at=excluded.ended_at,updated_at=now()"
                          id (:session-id updated) (pr-str (:data updated))
                          (when (:tokens updated) (encrypt key (pr-str (:tokens updated))))
                          (:user-id updated) (:workspace-id updated) (:version updated)
                          (:created-at updated) (:expires-at updated) (:ended-at updated)]))

                  (and row (nil? updated))
                  (mu/trace ::persist {:pairs [:operation :delete]}
                    (jdbc/execute-one! tx ["delete from app_private.sessions where id=?" id])))
                value)))))
      (fn [session-id]
        (with-open [connection (database/connection datasource)]
          (:id (jdbc/execute-one! connection ["select id from app_private.sessions where session_id=?" session-id]
                                  {:builder-fn result-set/as-unqualified-lower-maps})))))))


;; Bel authority


(def Principal
  [:map [:user-id :uuid] [:session-id :uuid] [:confirmed? :boolean] [:password-required? :boolean]
   [:memberships [:vector [:map [:workspace-id :uuid] [:role :keyword]]]]])


(defn check-context!
  "The trusted request context must name the signed-in user, this session and an allowed scope."
  {:malli/schema [:=> [:cat :map] :nil]}
  [{:keys [actor user-id scope auth-session-id session-id]}]
  (when-not (and (uuid? user-id) (= actor user-id) (string? auth-session-id) (uuid? session-id)
                 (or (= scope {:user-id user-id}) (= scope {:session-id session-id})
                     (and (= #{:workspace-id} (set (keys scope))) (uuid? (:workspace-id scope)))))
    (throw (ex-info "A current user session is required" {:type :forbidden :status 403})))
  nil)


(defn ended!
  {:malli/schema [:=> [:cat] :nil]}
  []
  (throw (ex-info "This session has ended or changed" {:type :forbidden :status 403})))


(defn current
  "Lock the current session row for share and verify it is this user's valid sign-in; return the principal."
  {:malli/schema [:=> [:cat :any :map] Principal]}
  [tx context]
  (check-context! context)
  (let [{:keys [actor user-id scope auth-session-id session-id]} context
        row (db/execute-one! tx {:select [:user_id :session_id :data :created_at :expires_at :ended_at]
                                 :from   [:app_private.sessions] :where [:= :id auth-session-id] :for :share})
        row (when row {:user-id    (:user_id row) :session-id    (:session_id row)
                       :data       (edn/read-string {:readers {}} (:data row))
                       :created-at (:created_at row) :expires-at (:expires_at row) :ended-at (:ended_at row)})]
    (when-not (and (= user-id (:user-id row)) (= session-id (:session-id row)) (live? row))
      (ended!))
    (db/execute-one! tx {:select [[[:set_config "request.jwt.claim.sub" [:cast actor :text] true]]
                                  [[:set_config "tover.session_key" auth-session-id true]]]})
    (let [boundary (db/execute-one! tx {:select [:valid_after :kept_session_id] :from [:app_private.session_boundaries]
                                        :where  [:= :user_id user-id]           :for  :share})]
      (when-not (valid? row (when boundary {:valid-after (:valid_after boundary) :kept-session-id (:kept_session_id boundary)}))
        (ended!)))
    (let [principal {:user-id            user-id
                     :session-id         session-id
                     :confirmed?         (boolean (:confirmed (db/execute-one! tx {:select [[[:app_private.bel_confirmed_user] :confirmed]]})))
                     :password-required? (boolean (get-in row [:data :identity/password-required?]))
                     :memberships        []}]
      (if-let [workspace-id (:workspace-id scope)]
        (let [membership (db/execute-one! tx {:select [:role_id :status]
                                              :from   [[[:app_private.bel_workspace_membership workspace-id] :membership]]})]
          (when-not (and (:confirmed? principal) (not (:password-required? principal)) (= "active" (:status membership)))
            (throw (ex-info "Current workspace access is required" {:type :forbidden :status 403})))
          (assoc principal :memberships [{:workspace-id workspace-id :role (keyword (:role_id membership))}]))
        principal))))


(defrecord CurrentSession []
  runtime/Authority
  (-principal [_ tx context] (current tx context)))


(alter-meta! #'->CurrentSession assoc :malli/schema [:=> [:cat] :map])
(alter-meta! #'map->CurrentSession assoc :malli/schema [:=> [:cat :map] :map])
