(ns tover.auth.sessions-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [ring.middleware.session.store :as ring]
    [tover.auth.sessions :as sessions]
    [tover.session-store :as fixtures])
  (:import
    [java.sql DriverManager]
    [java.time Instant]
    [java.util Base64 Properties]))


(def ^:dynamic *jdbc-options*
  nil)


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
  (let [key       (.decode (Base64/getDecoder) "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        encrypted (#'sessions/crypt javax.crypto.Cipher/ENCRYPT_MODE key "private credentials")]
    (is (not= encrypted "private credentials"))
    (is (= "private credentials" (#'sessions/crypt javax.crypto.Cipher/DECRYPT_MODE key encrypted)))
    (is (thrown? Exception (#'sessions/crypt javax.crypto.Cipher/DECRYPT_MODE key (str encrypted "x"))))))


(defn check-jdbc!
  {:malli/schema [:=> [:cat :map] :any]}
  [options]
  (binding [*jdbc-options* options]
    (let [store (sessions/jdbc-store *jdbc-options*)
          id (sessions/establish! store nil tokens)
          {:keys [jdbc-url user password]} *jdbc-options*
          props (doto (Properties.) (.setProperty "user" user) (.setProperty "password" password))]
      (try
        (check-store! store)
        (with-open [connection (DriverManager/getConnection jdbc-url props)
                    statement  (.prepareStatement connection "select tokens,data from app_private.sessions where id=?")]
          (.setString statement 1 id)
          (with-open [result (.executeQuery statement)]
            (is (.next result))
            (is (not (.contains (.getString result "tokens") "synthetic")))
            (is (not (.contains (.getString result "data") "synthetic")))))
        (finally (ring/delete-session store id))))))
