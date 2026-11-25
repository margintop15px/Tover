(ns tover.marketplace.connector-test
  "Credential verification against a fake Ozon, sealing and the secret vault. No live provider calls."
  (:require
    [bel-rdf.adapter :as adapter]
    [charred.api :as json]
    [clojure.string :as str]
    [clojure.test :refer [are deftest is testing]]
    [tover.marketplace.connector :as connector]
    [tover.shared.rdf :as rdf]
    [tover.support :as support])
  (:import
    [java.io IOException]
    [java.net.http HttpTimeoutException]
    [java.util Base64]))


(def key-text
  (.encodeToString (Base64/getEncoder) (byte-array (range 32))))


(def credentials
  {:client-id "0123" :api-key "key"})


(defn fake
  "A fake `post`. `answers` maps a path to a 200 body, a status or an exception to throw; `:calls` holds the paths."
  {:malli/schema [:=> [:cat :map] [:map [:post [:fn fn?]] [:calls :any]]]}
  [answers]
  (let [calls (atom [])]
    {:calls calls
     :post  (fn [path _body headers]
              (swap! calls conj path)
              (assert (= {"Client-Id" "0123" "Api-Key" "key"} headers))
              (let [answer (get answers path)]
                (cond
                  (instance? Exception answer) (throw answer)
                  (int? answer) {:status answer :body "{}"}
                  (string? answer) {:status 200 :body answer}
                  :else {:status 200 :body (json/write-json-str answer)})))}))


(def seller-info
  {:company {:name "Shop LLC" :currency "RUB"}})


(defn roles
  {:malli/schema [:=> [:cat [:* [:sequential :string]]] :map]}
  [& methods]
  {:expires_at "2027-01-01T00:00:00Z" :roles (mapv (fn [m] {:name "x" :methods m}) methods)})


(defn verify
  {:malli/schema [:=> [:cat :map] :map]}
  [answers]
  (connector/ozon-verify (:post (fake (merge {"/v1/seller/info" seller-info "/v1/roles" (roles)} answers))) credentials))


(deftest readable-domains-come-from-roles
  (let [{:keys [post calls]} (fake {"/v1/seller/info" seller-info
                                    "/v1/roles"       (roles (vec (conj (connector/domain-calls "products") "/v1/analytics/stocks")))})]
    (is (= {:verification-outcome "verified"             :account-label    "Shop LLC" :account-currency "RUB"
            :key-expires-at       "2027-01-01T00:00:00Z" :readable-domains ["products" "stock-analytics"]}
           (connector/ozon-verify post credentials)))
    (is (= ["/v1/seller/info" "/v1/roles"] @calls) "no probes")))


(deftest domain-calls-cover-every-domain
  (is (= #{"warehouses" "products" "sku-aliases" "attributes" "attribute-definitions" "prices" "stock-totals"
           "stock-by-warehouse" "stock-analytics" "fbs-postings" "fbo-postings" "returns" "rfbs-returns" "supplies" "removals"}
         (set (keys connector/domain-calls))))
  (is (= connector/domains
         (:readable-domains (verify {"/v1/roles" (apply roles (map vec (vals connector/domain-calls)))})))
      "methods of several roles add up"))


(deftest one-missing-call-makes-the-domain-unreadable
  (let [methods (disj (into (connector/domain-calls "supplies") (connector/domain-calls "products")) "/v1/supply-order/bundle")]
    (is (= ["products"] (:readable-domains (verify {"/v1/roles" (roles (vec methods))}))))))


(deftest empty-role-list-makes-every-domain-unreadable
  (doseq [answer [(roles) {:roles []} {} {:roles nil} {:roles "x"} {:roles [1 {:methods "x"} {:methods [1 nil]}]}]]
    (is (= ["verified" []] ((juxt :verification-outcome :readable-domains) (verify {"/v1/roles" answer}))))))


(deftest outcome-by-status
  (doseq [path ["/v1/seller/info" "/v1/roles"]]
    (are [status outcome] (= {:verification-outcome outcome} (verify {path status}))
      400 "rejected" 401 "rejected" 403 "rejected" 404 "rejected"
      302 "unreachable" 429 "unreachable" 500 "unreachable" 503 "unreachable"
      (HttpTimeoutException. "timeout") "unreachable"
      (IOException. "reset") "unreachable"
      "not json" "unreachable"
      "[]" "unreachable"))
  (testing "a seller account without a currency cannot be verified"
    (is (= {:verification-outcome "unreachable"} (verify {"/v1/seller/info" {:company {:name "Shop LLC"}}})))))


(deftest optional-facts-are-left-out
  (is (= {:verification-outcome "verified" :account-currency "RUB" :readable-domains []}
         (verify {"/v1/seller/info" {:company {:name " " :currency "RUB"}} "/v1/roles" {:expires_at "" :roles []}}))))


(deftest other-response-fields-are-discarded
  (let [result (verify {"/v1/seller/info" {:company    {:name       "Shop LLC"         :currency "RUB" :inn "SENTINEL" :legal_name "SENTINEL"
                                                        :legal_info {:name "SENTINEL"}}
                                           :legal_info {:ogrn "SENTINEL"} :ratings [{:name "SENTINEL"}]}
                        "/v1/roles"       (assoc (roles ["/v1/analytics/stocks" "/SENTINEL"]) :owner "SENTINEL")})]
    (is (= #{:verification-outcome :account-label :account-currency :readable-domains :key-expires-at} (set (keys result))))
    (is (not (str/includes? (pr-str result) "SENTINEL")))))


(deftest credentials-illegal-in-headers-are-rejected
  (doseq [bad ["\u043a\u043b\u044e\u0447" "a\nb" "a\r\nX: y" "a\u0000" ""]
          k   [:client-id :api-key]]
    (let [calls (atom 0)]
      (is (= {:verification-outcome "rejected"}
             (connector/ozon-verify (fn [& _] (swap! calls inc) {:status 200 :body "{}"}) (assoc credentials k bad))))
      (is (zero? @calls) "nothing is sent"))))


(deftest one-http-client-with-a-connect-timeout
  (let [client @@#'connector/http-client]
    (is (identical? client @@#'connector/http-client))
    (is (.isPresent (.connectTimeout ^java.net.http.HttpClient client)))))


(deftest sealing-round-trips-and-hides-the-key
  (let [key    (connector/connector-key key-text)
        sealed (connector/seal key "api-key-value")]
    (is (not (str/includes? sealed "api-key-value")))
    (is (not= sealed (connector/seal key "api-key-value")) "a fresh IV per seal")
    (is (= "api-key-value" (connector/unseal key sealed))))
  (is (thrown? clojure.lang.ExceptionInfo (connector/connector-key "c2hvcnQ="))))


(deftest vault-stores-and-destroys
  (support/with-store
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


(defn restarted
  "The same vault after a process restart: nothing is in flight."
  {:malli/schema [:=> [:cat :map] :map]}
  [vault]
  (assoc vault :in-flight (atom {})))


(deftest unreferenced-secrets-are-destroyed
  (support/with-store
    (fn [repo _]
      (let [vault     (connector/connector {:repo repo :connector-key key-text})
            workspace (random-uuid)
            elsewhere (random-uuid)
            kept      (connector/store-secret! vault workspace "kept")
            orphan    (connector/store-secret! vault workspace "orphan")
            other     (connector/store-secret! vault elsewhere "other workspace")]
        (connector/destroy-unreferenced! (restarted vault) workspace #{kept})
        (is (= "kept" (connector/api-key vault workspace kept)))
        (is (nil? (connector/api-key vault workspace orphan)))
        (is (= "other workspace" (connector/api-key vault elsewhere other)))))))


(deftest secret-references-identify-one-attempt-and-key
  (let [vault {:key (connector/connector-key key-text)}
        ref   #(connector/secret-ref vault %1 "0123" %2)]
    (is (= (ref "attempt-1" "key") (ref "attempt-1" "key")) "a double-click proposes the same secret")
    (is (= 3 (count (distinct [(ref "attempt-1" "key") (ref "attempt-2" "key") (ref "attempt-1" "other")]))))))


(deftest destroying-conflicts-with-a-command-that-names-the-secret
  (support/with-store
    (fn [repo _]
      (let [vault     (connector/connector {:repo repo :connector-key key-text})
            workspace (random-uuid)
            secret    (connector/store-secret! vault workspace "key")
            accept!   (fn [during]
                        (adapter/transact repo :serializable
                                          (fn [conn]
                                            (is (connector/sealed? conn workspace secret))
                                            (during)
                                            (rdf/add! conn nil (rdf/iri "urn:test:connection") (rdf/iri "urn:test:secret-ref") secret))))]
        (testing "destroyed between the command's check and its commit: the command is refused"
          (is (= :conflict (:type (support/error-data (fn [] (accept! #(connector/destroy-unreferenced! (restarted vault) workspace #{})))))))
          (is (nil? (connector/api-key vault workspace secret))))
        (testing "named by a stored record: kept"
          (connector/store-secret! vault workspace secret "key")
          (accept! (constantly nil))
          (connector/destroy-unreferenced! (restarted vault) workspace #{})
          (is (= "key" (connector/api-key vault workspace secret))))))))


(deftest in-flight-secrets-survive-a-sweep
  (support/with-store
    (fn [repo _]
      (let [now       (atom 1000000)
            vault     (assoc (connector/connector {:repo repo :connector-key key-text}) :clock #(deref now))
            workspace (random-uuid)
            secret    (connector/store-secret! vault workspace "key")
            sweep!    #(do (connector/destroy-unreferenced! % workspace #{}) (connector/api-key vault workspace secret))]
        (is (= "key" (sweep! vault)))
        (swap! now + 120000)
        (is (= "key" (sweep! vault)) "two minutes, inclusive")
        (swap! now inc)
        (is (nil? (sweep! vault)) "an attempt abandoned without a crash waits two minutes")
        (connector/store-secret! vault workspace secret "key")
        (is (= "key" (sweep! vault)) "sealing again starts again")
        (is (nil? (sweep! (restarted vault))) "a restart forgets it")))))


(deftest a-secret-is-in-flight-before-a-sweep-can-see-it
  (support/with-store
    (fn [repo _]
      (let [vault (connector/connector {:repo repo :connector-key key-text})
            seen  (atom nil)
            add   rdf/add!]
        (with-redefs [rdf/add! (fn [& args]
                                 (reset! seen (vals @(:in-flight vault)))
                                 (apply add args))]
          (connector/store-secret! vault (random-uuid) "key"))
        (is (= 1 (count @seen)))))))
