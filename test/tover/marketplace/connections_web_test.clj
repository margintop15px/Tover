(ns tover.marketplace.connections-web-test
  "Board chapter 40 forms: Connect and Replace verify and seal the key before the descriptor, rejected
  credentials store nothing, and no page or command input ever carries the API key."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.realtime]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [dev.onionpancakes.chassis.core :as chassis]
    [tover.catalogues.stores.model :as stores]
    [tover.marketplace.connections-test :as scenarios]
    [tover.marketplace.connections.model :as model]
    [tover.marketplace.connections.web :as web]
    [tover.marketplace.connector :as connector]
    [tover.shared.i18n :as i18n]
    [tover.shared.web.core]
    [tover.support :as support]
    [tover.workspaces.currency.model :as currency])
  (:import
    [java.util Base64]))


(defn register
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (bel/build-registry! ['tover.catalogues.warehouses.model 'tover.catalogues.stores.model
                        'tover.marketplace.connections.model 'tover.marketplace.sync.model 'tover.shared.web.core
                        'tover.marketplace.connections.web 'tover.workspaces.currency.model])
  (f))


(use-fixtures :each bel-test/isolated-registry register scenarios/unit-authority)


(def key-text
  (.encodeToString (Base64/getEncoder) (byte-array (range 32))))


(defn fake-verify
  "Client-Id 123 with key `good` reads everything; key `blind` cannot read products; others are refused."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [client-id api-key]}]
  (case api-key
    ("good" "good-2") {:verification-outcome "verified"             :account-currency "KZT"             :account-label (str "Shop " client-id)
                       :key-expires-at       "2027-01-01T00:00:00Z" :readable-domains connector/domains}
    "blind" {:verification-outcome "verified" :account-currency "RUB" :readable-domains ["stock-analytics"]}
    "slow" {:verification-outcome "unreachable"}
    {:verification-outcome "rejected"}))


(defn context
  {:malli/schema [:=> [:cat :any] :map]}
  [repo]
  (let [marketplace (assoc (connector/connector {:repo repo :connector-key key-text}) :verify fake-verify)]
    (assoc (runtime/prepare-context {:bel/system  (support/bel-system {:repo repo :marketplace marketplace})
                                     :repo        repo :workspace-id scenarios/workspace :user-id scenarios/admin
                                     :memberships [{:workspace-id scenarios/workspace :role :admin}]})
           :request {:tover/identity {:workspace-id scenarios/workspace} :params {}})))


(defn act
  {:malli/schema [:=> [:cat :map :qualified-keyword :map] :map]}
  [ctx id input]
  ((:bel/fn (bel/get-action id)) ctx (merge {:command-id (str (random-uuid)) :workspace-id scenarios/workspace} input)))


(defn page
  "Rendered HTML of page `id` for `input`."
  {:malli/schema [:=> [:cat :map :qualified-keyword :map] :string]}
  [ctx id input]
  (let [{:bel/keys [fn query]} (bel/get-page id)]
    (chassis/html (:html (fn (query ctx input))))))


(def connect-form
  {:connection-id "c-1" :store-id "s" :provider "ozon" :client-id " 123 " :api-key "good"})


(deftest connect-and-replace-through-the-forms
  (support/with-store
    (fn [repo _]
      (let [ctx         (context repo)
            marketplace (get-in ctx [:bel/system :resources :marketplace])]
        (runtime/execute! ctx ::stores/register-store {:command-id "st1" :input {:store-id "s" :name "Ozon shop"}})

        (testing "rejected credentials seal nothing and reach the command as a provider outcome"
          (let [descriptor (act ctx ::web/connect (assoc connect-form :api-key "wrong"))]
            (is (= "rejected" (get-in descriptor [:input :verification-outcome])))
            (is (not (contains? (:input descriptor) :secret-ref)))
            (is (= :credentials-rejected (scenarios/rejection #(runtime/execute! ctx (:id descriptor) (select-keys descriptor [:command-id :input])))))))

        (testing "a key that cannot read products is not stored"
          (is (not (contains? (:input (act ctx ::web/connect (assoc connect-form :api-key "blind"))) :secret-ref))))

        (testing "verified credentials: the descriptor carries a secret reference, never the key"
          (let [descriptor (act ctx ::web/connect connect-form)
                input      (:input descriptor)]
            (is (= {:type :page :id ::web/connection :path-params {:connection-id "c-1"}} (:then descriptor)))
            (is (not (str/includes? (pr-str descriptor) "good")))
            (is (= "123" (:client-id input)))
            (is (= "good" (connector/api-key marketplace scenarios/workspace (:secret-ref input))))
            (runtime/execute! ctx (:id descriptor) (select-keys descriptor [:command-id :input]))))

        (testing "an account already connected seals nothing"
          (is (not (contains? (:input (act ctx ::web/connect (assoc connect-form :connection-id "c-2"))) :secret-ref))))

        (testing "the pages show the connection and never the key or its reference"
          (let [list    (page ctx ::web/connections {})
                details (page ctx ::web/connection {:connection-id "c-1"})
                secret  (:secret-ref (runtime/read-model ctx ::model/connections {:connection-id "c-1"}))]
            (is (str/includes? list "/marketplace/c-1"))
            (is (str/includes? details "Pause"))
            (doseq [html [list details]]
              (is (not (str/includes? html "good")))
              (is (not (str/includes? html secret))))))

        (testing "replacing destroys the old secret after commit"
          (let [old        (:secret-ref (runtime/read-model ctx ::model/connections {:connection-id "c-1"}))
                descriptor (act ctx ::web/replace-credentials {:connection-id "c-1" :client-id "123" :api-key "good"})]
            (runtime/execute! ctx (:id descriptor) (select-keys descriptor [:command-id :input]))
            (is (= (get-in descriptor [:input :secret-ref])
                   (:secret-ref (runtime/read-model ctx ::model/connections {:connection-id "c-1"}))))
            (is (loop [tries 50]
                  (cond (nil? (connector/api-key marketplace scenarios/workspace old)) true
                        (zero? tries) false
                        :else (do (Thread/sleep 20) (recur (dec tries))))))))

        (testing "credentials of another seller account seal nothing"
          (is (not (contains? (:input (act ctx ::web/replace-credentials {:connection-id "c-1" :client-id "456" :api-key "good"}))
                              :secret-ref))))))))


(defn accept!
  {:malli/schema [:=> [:cat :map :map] :map]}
  [ctx descriptor]
  (runtime/execute! ctx (:id descriptor) (select-keys descriptor [:command-id :input])))


(defn with-context
  "Calls `(f ctx marketplace)` over an empty store with one active store."
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (support/with-store
    (fn [repo _]
      (let [ctx (context repo)]
        (runtime/execute! ctx ::stores/register-store {:command-id "st1" :input {:store-id "s" :name "Ozon shop"}})
        (f ctx (get-in ctx [:bel/system :resources :marketplace]))))))


(defn set-currency!
  {:malli/schema [:=> [:cat :map :string] :map]}
  [ctx code]
  (runtime/execute! (assoc ctx :memberships [{:workspace-id scenarios/workspace :role :owner}]) ::currency/change-workspace-currency
                    {:command-id (str "currency-" code) :input {:workspace-id scenarios/workspace :currency code}}))


(deftest double-click
  (with-context
    (fn [ctx marketplace]
      (let [form   (assoc connect-form :command-id "attempt-1")
            before (act ctx ::web/connect form)
            again  (act ctx ::web/connect form)]
        (is (= before again) "both clicks before the commit send the same command")
        (let [accepted (accept! ctx before)]
          (is (= accepted (accept! ctx again)))
          (is (= accepted (accept! ctx (act ctx ::web/connect form))) "a click after the commit recovers the outcome"))
        (is (= {:connection-id "c-1"} (get-in again [:then :path-params])) "both responses show it")
        (is (= 1 (count (:connections (runtime/read-model ctx ::model/marketplace-connections-and-requirements {})))))
        (is (= "good" (connector/api-key marketplace scenarios/workspace (get-in before [:input :secret-ref]))))))))


(deftest abandoned-secret-is-destroyed-on-the-next-attempt
  (with-context
    (fn [ctx marketplace]
      (let [key-of    #(connector/api-key marketplace scenarios/workspace (get-in % [:input :secret-ref]))
            abandoned (act ctx ::web/connect connect-form)]
        (is (= "good" (key-of abandoned)) "sealed, and its command never ran")
        (let [connected (act ctx ::web/connect (assoc connect-form :connection-id "c-2"))]
          (is (nil? (key-of abandoned)))
          (is (= :conflict (scenarios/rejection #(accept! ctx abandoned))) "a command whose secret is gone is refused")
          (accept! ctx connected)
          (act ctx ::web/replace-credentials {:connection-id "c-2" :client-id "123" :api-key "good-2"})
          (act ctx ::web/connect (assoc connect-form :connection-id "c-3" :client-id "456" :api-key "wrong"))
          (is (= "good" (key-of connected)) "a connection's key is never destroyed by another attempt"))))))


(deftest other-currency
  (with-context
    (fn [ctx _]
      (set-currency! ctx "RUB")
      (accept! ctx (act ctx ::web/connect connect-form))
      (let [notice "Imported prices and costs are left blank"]
        (is (str/includes? (page ctx ::web/connection {:connection-id "c-1"}) notice))
        (set-currency! ctx "KZT")
        (let [html (page ctx ::web/connection {:connection-id "c-1"})]
          (is (not (str/includes? html notice)))
          (is (str/includes? html "KZT"))
          (is (str/includes? html "API key expires") "the key's expiry is shown"))))))


(deftest missing-connector-key-is-refused-before-contacting-ozon
  (with-context
    (fn [ctx _]
      (accept! ctx (act ctx ::web/connect connect-form))
      (let [calls  (atom 0)
            no-key (update-in ctx [:bel/system :resources :marketplace]
                              #(-> % (dissoc :key) (assoc :verify (fn [_] (swap! calls inc) {:verification-outcome "rejected"}))))]
        (doseq [ctx        [no-key (update-in ctx [:bel/system :resources] dissoc :marketplace)]
                [id input] {::web/connect             (assoc connect-form :connection-id "c-2" :client-id "456")
                            ::web/replace-credentials {:connection-id "c-1" :client-id "123" :api-key "good"}}]
          (let [error (try (act ctx id input) nil (catch clojure.lang.ExceptionInfo e e))]
            (is (= 503 (web/problems (:type (ex-data error)))) "a problem on the form, not a server error")
            (is (= "Marketplace connections are not configured" (i18n/error-message error)))))
        (is (zero? @calls))))))


(deftest a-changed-workspace-touches-no-secret
  (with-context
    (fn [ctx marketplace]
      (let [other  (random-uuid)
            secret (connector/store-secret! marketplace other "key")]
        (is (= :identity (scenarios/rejection #(act ctx ::web/connect (assoc connect-form :workspace-id other)))))
        (is (= "key" (connector/api-key marketplace other secret)))))))


(deftest rejected-form-stays-open-without-credentials
  (with-context
    (fn [ctx _]
      (accept! ctx (act ctx ::web/connect connect-form))
      (let [html (page (assoc-in ctx [:request :params] {:store-id "s"}) ::web/connections {})]
        (is (re-find #"<details[^>]* open" html))
        (is (not (re-find #"name=\"(?:client-id|api-key)\"[^>]*value=\"[^\"]" html)))))))
