(ns tover.marketplace.connector-test
  "Credential verification against a fake Ozon, sealing and the secret vault. No live provider calls."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [tover.inventory.store-test :as fixture]
    [tover.marketplace.connector :as connector])
  (:import
    [java.io IOException]
    [java.net.http HttpTimeoutException]
    [java.util Base64]))


(def key-text
  (.encodeToString (Base64/getEncoder) (byte-array (range 32))))


(defn fake-ozon
  "`statuses` maps a path to a status or a thrown exception; unlisted paths answer 200."
  {:malli/schema [:=> [:cat :map] [:fn fn?]]}
  [statuses]
  (fn [path _body headers]
    (assert (= {"Client-Id" "0123" "Api-Key" "key"} headers))
    (let [status (get statuses path 200)]
      (if (instance? Exception status)
        (throw status)
        {:status status :body (if (= "/v1/seller/info" path) "{\"company\":{\"name\":\"Shop LLC\"}}" "{}")}))))


(defn verify
  {:malli/schema [:=> [:cat :map] :map]}
  [statuses]
  (connector/ozon-verify (fake-ozon statuses) {:client-id "0123" :api-key "key"}))


(deftest verification-outcomes
  (testing "seller info and every probe answer"
    (is (= {:verification-outcome "verified"        :provider-account-id "0123" :account-label "Shop LLC"
            :readable-domains     connector/domains}
           (verify {}))))
  (testing "a refused probe makes only that domain unreadable"
    (is (= (remove #{"stock-analytics"} connector/domains)
           (:readable-domains (verify {"/v1/analytics/stocks" 403})))))
  (testing "refused credentials"
    (is (= {:verification-outcome "rejected"} (verify {"/v1/seller/info" 401}))))
  (testing "timeouts and provider errors are not reported as invalid credentials"
    (is (= {:verification-outcome "unreachable"} (verify {"/v1/seller/info" (HttpTimeoutException. "timeout")})))
    (is (= {:verification-outcome "unreachable"} (verify {"/v1/seller/info" 503})))
    (is (= {:verification-outcome "unreachable"} (verify {"/v3/product/list" (IOException. "reset")})))))


(deftest sealing-round-trips-and-hides-the-key
  (let [key    (connector/connector-key key-text)
        sealed (connector/seal key "api-key-value")]
    (is (not (str/includes? sealed "api-key-value")))
    (is (not= sealed (connector/seal key "api-key-value")) "a fresh IV per seal")
    (is (= "api-key-value" (connector/unseal key sealed))))
  (is (thrown? clojure.lang.ExceptionInfo (connector/connector-key "c2hvcnQ="))))


(deftest vault-stores-and-destroys
  (fixture/with-store
    (fn [repo _]
      (let [vault     (connector/connector {:repo repo :connector-key key-text})
            workspace (random-uuid)
            ref       (connector/store-secret! vault workspace "api-key-value")]
        (is (= "api-key-value" (connector/api-key vault workspace ref)))
        (is (nil? (connector/api-key vault (random-uuid) ref)) "secrets are workspace-scoped")
        (connector/destroy-secret! vault workspace ref)
        (is (nil? (connector/api-key vault workspace ref)))
        (is (thrown? clojure.lang.ExceptionInfo
                     (connector/store-secret! (connector/connector {:repo repo}) workspace "k"))
            "no connector key, no stored credentials")))))
