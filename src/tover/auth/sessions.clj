(ns tover.auth.sessions
  "Opaque Ring sessions. Credential changes run under the same database row lock."
  (:require
    [buddy.core.codecs :as codecs]
    [buddy.core.crypto :as crypto]
    [buddy.core.nonce :as nonce]
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


(defn random-key
  {:malli/schema [:=> [:cat] :string]} []
  (codecs/bytes->b64-str (nonce/random-bytes (long 32)) true))


(defn- expired?
  {:malli/schema [:=> [:cat [:maybe :map]] [:maybe :boolean]]} [row]
  (and row (not (.isAfter ^Instant (:expires-at row) (Instant/now)))))


(defn new-row
  {:malli/schema [:=> [:cat :string] :map]} [id]
  {:id         id :data {} :version (UUID/randomUUID)
   :expires-at (.plusSeconds (Instant/now) (* 30 24 60 60))})


(defrecord Store [transaction]
  ring/SessionStore
  (read-session [_ key]
    (if key
      (transaction key
                   (fn [row]
                     (if (or (nil? row) (expired? row))
                       [nil {}]
                       [row (assoc (:data row) :identity/session-id key :identity/version (:version row))])))
      {}))
  (write-session [_ key data]
    (let [id (or key (random-key))]
      (transaction id
                   (fn [row]
                     [(cond-> (assoc (or row (new-row id)) :data
                                     (cond-> (dissoc data :identity/session-id :identity/version :identity/tokens)
                                       (and row (not (:identity/tokens data)))
                                       (assoc :identity/password-required? (get-in row [:data :identity/password-required?]))))
                        (:identity/tokens data) (assoc :tokens (:identity/tokens data)
                                                       :user-id (get-in data [:identity/tokens :user :user-id]))) id]))))
  (delete-session [_ key]
    (when key (transaction key (fn [_] [nil nil])))
    nil))


;; Record constructors participate in the same development instrumentation as functions.
(alter-meta! #'->Store assoc :malli/schema [:=> [:cat [:fn ifn?]] :map])
(alter-meta! #'map->Store assoc :malli/schema [:=> [:cat :map] :map])


(defn session
  {:malli/schema [:=> [:cat :map [:maybe :string]] [:maybe :map]]} [store key]
  (when key
    ((:transaction store) key
                          (fn [row] (if (expired? row) [nil nil] [row row])))))


(defn update-session!
  {:malli/schema [:=> [:cat :map [:maybe :string] [:fn ifn?]] :any]} [store key f]
  ((:transaction store) key
                        (fn [row]
                          (if (or (nil? row) (expired? row)) [nil nil] (f row)))))


(defn establish!
  {:malli/schema [:=> [:cat :map [:maybe :string] :map] :string]} [store old-key tokens]
  (let [id  (random-key)
        row (assoc (new-row id) :tokens tokens :user-id (get-in tokens [:user :user-id])
                   :data {:bel/session-id (UUID/randomUUID)})]
    ((:transaction store) id (fn [_] [row id]))
    (when old-key (ring/delete-session store old-key))
    id))


(defn select-workspace!
  {:malli/schema [:=> [:cat :map :string :uuid] [:maybe :map]]} [store key workspace-id]
  (update-session! store key
                   (fn [row]
                     (let [row (assoc row :workspace-id workspace-id :version (UUID/randomUUID))]
                       [row row]))))


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


(defn jdbc-store
  {:malli/schema [:=> [:cat :map] :map]} [{:keys [datasource encryption-key]}]
  (let [key (try (codecs/b64->bytes encryption-key)
                 (catch Exception _ (throw (ex-info "Invalid session encryption key" {:type :configuration}))))]
    (when-not (= 32 (alength key))
      (throw (ex-info "Session encryption key must contain 32 bytes" {:type :configuration})))
    (->Store
      (fn [id f]
        (mu/trace ::transaction {:pairs []}
          (with-open [connection (database/connection datasource)]
            (jdbc/with-transaction [tx connection]
              (let [stored          (jdbc/execute-one! tx
                                                       ["select data,tokens,user_id,workspace_id,version,expires_at from app_private.sessions where id=? for update" id]
                                                       {:builder-fn result-set/as-unqualified-lower-maps})
                    row             (when stored
                                      {:id      id :data (edn/read-string (:data stored))
                                       :tokens  (some->> (:tokens stored) (decrypt key) edn/read-string)
                                       :user-id (:user_id stored) :workspace-id (:workspace_id stored)
                                       :version (:version stored) :expires-at   (:expires_at stored)})
                    [updated value] (f row)]
                (cond
                  (and updated (not= row updated))
                  (mu/trace ::persist {:pairs [:operation :write]}
                    (jdbc/execute-one! tx
                                       ["insert into app_private.sessions(id,data,tokens,user_id,workspace_id,version,expires_at) values(?,?,?,?,?,?,?) on conflict(id) do update set data=excluded.data,tokens=excluded.tokens,user_id=excluded.user_id,workspace_id=excluded.workspace_id,version=excluded.version,expires_at=excluded.expires_at,updated_at=now()"
                                        id (pr-str (:data updated)) (when (:tokens updated) (encrypt key (pr-str (:tokens updated))))
                                        (:user-id updated) (:workspace-id updated) (:version updated) (:expires-at updated)]))

                  (and row (nil? updated))
                  (mu/trace ::persist {:pairs [:operation :delete]}
                    (jdbc/execute-one! tx ["delete from app_private.sessions where id=?" id])))
                value))))))))
