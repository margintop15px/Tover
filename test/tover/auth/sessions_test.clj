(ns tover.auth.sessions-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as result-set]
    [ring.middleware.session.store :as ring]
    [tover.auth.sessions :as sessions]
    [tover.session-store :as fixtures]
    [tover.shared.jdbc :as database])
  (:import
    [java.time Instant]
    [java.util Base64]
    [javax.crypto Cipher]
    [javax.crypto.spec GCMParameterSpec SecretKeySpec]))


(def tokens
  {:access-token "synthetic-access" :refresh-token "synthetic-refresh" :expires-at 4102444800
   :user         {:user-id #uuid "00000000-0000-0000-0000-000000000001" :email "test@example.test" :email-confirmed? true}})


(defn- check-store!
  {:malli/schema [:=> [:cat :map] :any]} [store]
  (let [anonymous     (ring/write-session store nil {:draft "retained"})
        authenticated (sessions/establish! store anonymous tokens)]
    (try
      (is (not= anonymous authenticated))
      (is (= {} (ring/read-session store anonymous)))
      (is (re-matches #"[A-Za-z0-9_-]{43}" authenticated))
      (is (nil? (:tokens (ring/read-session store authenticated))))
      (let [stale-data (ring/read-session store authenticated)
            rotated    (assoc tokens :refresh-token "rotated-refresh")]
        (sessions/update-session! store authenticated #(vector (assoc % :tokens rotated) nil))
        (ring/write-session store authenticated (assoc stale-data :draft "preserved"))
        (is (= rotated (:tokens (sessions/session store authenticated)))))
      (let [workspace (random-uuid)
            before    (:version (sessions/session store authenticated))]
        (sessions/select-workspace! store authenticated workspace)
        (is (= workspace (:workspace-id (sessions/session store authenticated))))
        (is (not= before (:version (sessions/session store authenticated)))))
      (testing "an old Ring response cannot restore completed password setup"
        (sessions/update-session! store authenticated #(vector (assoc-in % [:data :identity/password-required?] true) nil))
        (let [stale (ring/read-session store authenticated)]
          (sessions/update-session! store authenticated #(vector (assoc-in % [:data :identity/password-required?] false) nil))
          (ring/write-session store authenticated stale)
          (is (false? (get-in (sessions/session store authenticated) [:data :identity/password-required?])))))
      (testing "parallel operations serialize and retain every update"
        (let [jobs (mapv (fn [_]
                           (future
                             (sessions/update-session! store authenticated
                                                       #(vector (update-in % [:data :counter] (fnil inc 0)) nil))))
                         (range 8))]
          (doseq [job jobs] (is (nil? (deref job 10000 :timeout))))
          (is (= 8 (get-in (sessions/session store authenticated) [:data :counter])))))
      (sessions/update-session! store authenticated #(vector (assoc % :expires-at (.minusSeconds (Instant/now) 1)) nil))
      (is (= {} (ring/read-session store authenticated)))
      (is (nil? (sessions/session store authenticated)))
      (finally
        (ring/delete-session store anonymous)
        (ring/delete-session store authenticated)))))


(deftest ring-and-credential-store-contract
  (check-store! (fixtures/memory-store)))


(deftest authenticated-encryption-rejects-tampering
  (let [key       (byte-array 32)
        encrypted (#'sessions/encrypt key "private credentials ✓")]
    (is (not= encrypted "private credentials"))
    (is (= "private credentials ✓" (#'sessions/decrypt key encrypted)))
    (is (not= encrypted (#'sessions/encrypt key "private credentials ✓")))
    (is (thrown? Exception (#'sessions/decrypt key (str encrypted "x"))))
    (is (thrown? Exception (#'sessions/decrypt (byte-array (repeat 32 1)) encrypted)))))


(deftest encrypted-sessions-remain-compatible-with-v010
  (let [key             (byte-array 32)
        legacy          "vWLK9QpBKuZAJGIP.asPllE+ILQGFjB/2NRN4cL+fwOjEM5lsuqUIr4gL6uuNYSAlGIU="
        encrypted       (#'sessions/encrypt key "new credentials ✓")
        [iv ciphertext] (str/split encrypted #"\." 2)
        cipher          (Cipher/getInstance "AES/GCM/NoPadding")
        decoder         (Base64/getDecoder)]
    (is (= "legacy credentials ✓" (#'sessions/decrypt key legacy)))
    (.init cipher Cipher/DECRYPT_MODE (SecretKeySpec. key "AES")
           (GCMParameterSpec. 128 (.decode decoder ^String iv)))
    (is (= "new credentials ✓" (String. (.doFinal cipher (.decode decoder ^String ciphertext)) "UTF-8")))))


(deftest invalid-encryption-configuration-fails-before-use
  (doseq [key ["not base64!" "AAAA"]]
    (is (thrown? clojure.lang.ExceptionInfo (sessions/jdbc-store {:encryption-key key})))))


(defn check-jdbc!
  {:malli/schema [:=> [:cat :map] :any]}
  [options]
  (with-open [datasource (database/datasource options)]
    (let [store    (sessions/jdbc-store (assoc options :datasource datasource))
          id       (sessions/establish! store nil tokens)
          snapshot #(jdbc/execute-one! datasource
                                       ["select data,tokens,updated_at from app_private.sessions where id=?" id]
                                       {:builder-fn result-set/as-unqualified-lower-maps})]
      (try
        (check-store! store)
        (ring/write-session store id (ring/read-session store id))
        (let [before (snapshot)]
          (is (not (str/includes? (:tokens before) "synthetic")))
          (is (not (str/includes? (:data before) "synthetic")))
          (dotimes [_ 3]
            (sessions/session store id)
            (sessions/update-session! store id #(vector % nil))
            (ring/write-session store id (ring/read-session store id)))
          (is (= before (snapshot)) "Unchanged reads/writes preserve ciphertext and updated_at")
          (is (thrown? IllegalStateException
                       (sessions/update-session! store id
                                                 (fn [_] (throw (IllegalStateException. "rollback"))))))
          (is (= before (snapshot)))
          (ring/write-session store id (assoc (ring/read-session store id) :setting true))
          (is (not= (:data before) (:data (snapshot)))))
        (let [backend-ids (repeatedly 16 #(-> (jdbc/execute-one! datasource ["select pg_backend_pid() as pid"]
                                                                 {:builder-fn result-set/as-unqualified-lower-maps}) :pid))]
          (is (< (count (set backend-ids)) 16) "Physical connections are reused"))
        (let [borrowed (mapv (fn [_] (database/connection datasource)) (range 8))
              started  (System/nanoTime)]
          (try
            (is (thrown? java.sql.SQLException (database/connection datasource)))
            (is (< 1500 (/ (- (System/nanoTime) started) 1e6) 5000))
            (finally (run! #(.close ^java.sql.Connection %) borrowed))))
        (is (= 1 (:one (jdbc/execute-one! datasource ["select 1 as one"]
                                          {:builder-fn result-set/as-unqualified-lower-maps}))) "Pool recovers after exhaustion")
        (finally (ring/delete-session store id))))))
