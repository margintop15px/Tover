(ns tover.auth.sessions
  "Opaque Ring sessions. Credential changes run under the same database row lock."
  (:require
    [clojure.edn :as edn]
    [ring.middleware.session.store :as ring])
  (:import
    [java.security SecureRandom]
    [java.sql DriverManager Timestamp]
    [java.time Instant]
    [java.util Base64 Properties UUID]
    [javax.crypto Cipher]
    [javax.crypto.spec GCMParameterSpec SecretKeySpec]))


(defn random-key
  {:malli/schema [:=> [:cat] :string]} []
  (let [bytes (byte-array 32)]
    (.nextBytes (SecureRandom.) bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))


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


(defn- crypt
  {:malli/schema [:=> [:cat :int :any :string] :string]} [mode key input]
  (let [cipher  (Cipher/getInstance "AES/GCM/NoPadding")
        encoder (Base64/getEncoder)
        decoder (Base64/getDecoder)]
    (if (= mode Cipher/ENCRYPT_MODE)
      (let [nonce (byte-array 12)]
        (.nextBytes (SecureRandom.) nonce)
        (.init cipher mode (SecretKeySpec. key "AES") (GCMParameterSpec. 128 nonce))
        (str (.encodeToString encoder nonce) "."
             (.encodeToString encoder (.doFinal cipher (.getBytes ^String input "UTF-8")))))
      (let [[nonce encrypted] (.split ^String input "\\." 2)]
        (.init cipher mode (SecretKeySpec. key "AES") (GCMParameterSpec. 128 (.decode decoder ^String nonce)))
        (String. (.doFinal cipher (.decode decoder ^String encrypted)) "UTF-8")))))


(defn jdbc-store
  {:malli/schema [:=> [:cat :map] :map]} [{:keys [jdbc-url user password encryption-key]}]
  (let [key   (try (.decode (Base64/getDecoder) ^String encryption-key)
                   (catch Exception _ (throw (ex-info "Invalid session encryption key" {:type :configuration}))))
        props (doto (Properties.)
                (.setProperty "user" user)
                (.setProperty "password" password)
                (.setProperty "connectTimeout" "15")
                (.setProperty "socketTimeout" "15"))]
    (when-not (= 32 (alength key))
      (throw (ex-info "Session encryption key must contain 32 bytes" {:type :configuration})))
    (->Store
      (fn [id f]
        (with-open [connection (DriverManager/getConnection jdbc-url props)]
          (.setAutoCommit connection false)
          (try
            (let [row             (with-open [statement (.prepareStatement connection "select data,tokens,user_id,workspace_id,version,expires_at from app_private.sessions where id=? for update")]
                                    (.setString statement 1 id)
                                    (with-open [result (.executeQuery statement)]
                                      (when (.next result)
                                        {:id      id :data (edn/read-string (.getString result "data"))
                                         :tokens  (some->> (.getString result "tokens") (crypt Cipher/DECRYPT_MODE key) edn/read-string)
                                         :user-id (.getObject result "user_id") :workspace-id (.getObject result "workspace_id")
                                         :version (.getObject result "version") :expires-at   (.toInstant (.getTimestamp result "expires_at"))})))
                  [updated value] (f row)]
              (if updated
                (with-open [statement (.prepareStatement connection "insert into app_private.sessions(id,data,tokens,user_id,workspace_id,version,expires_at) values(?,?,?,?,?,?,?) on conflict(id) do update set data=excluded.data,tokens=excluded.tokens,user_id=excluded.user_id,workspace_id=excluded.workspace_id,version=excluded.version,expires_at=excluded.expires_at,updated_at=now()")]
                  (.setString statement 1 id)
                  (.setString statement 2 (pr-str (:data updated)))
                  (.setString statement 3 (when (:tokens updated) (crypt Cipher/ENCRYPT_MODE key (pr-str (:tokens updated)))))
                  (.setObject statement 4 (:user-id updated))
                  (.setObject statement 5 (:workspace-id updated))
                  (.setObject statement 6 (:version updated))
                  (.setTimestamp statement 7 (Timestamp/from (:expires-at updated)))
                  (.executeUpdate statement))
                (when row
                  (with-open [statement (.prepareStatement connection "delete from app_private.sessions where id=?")]
                    (.setString statement 1 id)
                    (.executeUpdate statement))))
              (.commit connection)
              value)
            (catch Throwable error
              (.rollback connection)
              (throw error))))))))
