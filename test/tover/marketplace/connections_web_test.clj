(ns tover.marketplace.connections-web-test
  "Board chapter 40 forms: Connect and Replace verify and seal the key before the descriptor, rejected
  credentials store nothing, and no page or command input ever carries the API key."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.handler :as handler]
    [bel.journal :as journal]
    [bel.realtime]
    [bel.runtime :as runtime]
    [bel.store :as store]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [dev.onionpancakes.chassis.core :as chassis]
    [tover.catalogues.products.model]
    [tover.catalogues.stores.model :as stores]
    [tover.inventory.acceptance.model]
    [tover.inventory.entries.model]
    [tover.inventory.stock-items.model]
    [tover.marketplace.connections-test :as scenarios]
    [tover.marketplace.connections.model :as model]
    [tover.marketplace.connections.web :as web]
    [tover.marketplace.connector :as connector]
    [tover.marketplace.products.model]
    [tover.marketplace.provenance]
    [tover.marketplace.warehouses.model]
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
                        'tover.catalogues.products.model 'tover.inventory.stock-items.model 'tover.marketplace.products.model 'tover.marketplace.warehouses.model
                        'tover.marketplace.connections.model 'tover.marketplace.sync.model 'tover.marketplace.provenance 'tover.catalogues.suppliers.model 'tover.catalogues.categories.model 'tover.inventory.entries.model 'tover.inventory.acceptance.model 'tover.shared.web.core
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


(defn section
  "Rendered HTML of the marketplace section of the settings page."
  {:malli/schema [:=> [:cat :map] :string]}
  [ctx]
  (chassis/html (web/connections-section {:request (:request ctx) :model (web/query-connections ctx)})))


(def connect-form
  {:connection-id "c-1" :provider "ozon" :client-id " 123 " :api-key "good"})


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
          (let [list    (section ctx)
                details (page ctx ::web/connection {:connection-id "c-1"})
                secret  (:secret-ref (runtime/read-model ctx ::model/connections {:connection-id "c-1"}))]
            (is (str/includes? list "/settings/marketplace/c-1"))
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


(defn restarted
  "The same context after a process restart: nothing is in flight."
  {:malli/schema [:=> [:cat :map] :map]}
  [ctx]
  (assoc-in ctx [:bel/system :resources :marketplace :in-flight] (atom {})))


(deftest abandoned-secret-is-destroyed-on-the-next-attempt
  (with-context
    (fn [ctx marketplace]
      (let [key-of    #(connector/api-key marketplace scenarios/workspace (get-in % [:input :secret-ref]))
            abandoned (act ctx ::web/connect connect-form)]
        (is (= "good" (key-of abandoned)) "sealed, and its command never ran")
        (let [connected (act (restarted ctx) ::web/connect (assoc connect-form :connection-id "c-2"))]
          (is (nil? (key-of abandoned)))
          (is (= :conflict (scenarios/rejection #(accept! ctx abandoned))) "a command whose secret is gone is refused")
          (accept! ctx connected)
          (act (restarted ctx) ::web/connect (assoc connect-form :connection-id "c-3" :client-id "456" :api-key "wrong"))
          (is (= "good" (key-of connected)) "a connection's key is never destroyed by another attempt"))))))


(deftest in-flight-key-survives-another-attempt
  (with-context
    (fn [ctx _]
      (let [first-admin (act ctx ::web/connect connect-form)]
        (act ctx ::web/connect (assoc connect-form :connection-id "c-2" :client-id "456"))
        (is (some? (accept! ctx first-admin)))))))


(defn verifying
  "`ctx` whose provider answers `(verify credentials)`; `calls` counts the questions."
  {:malli/schema [:=> [:cat :map :any [:fn fn?]] :map]}
  [ctx calls verify]
  (assoc-in ctx [:bel/system :resources :marketplace :verify]
            (fn [input] (swap! calls inc) (verify input))))


(deftest a-retry-recovers-the-original-outcome
  (doseq [verify [(constantly {:verification-outcome "unreachable"})
                  (constantly {:verification-outcome "rejected"})
                  #(assoc (fake-verify %) :account-label "Renamed")
                  #(dissoc (fake-verify %) :key-expires-at)
                  #(assoc (fake-verify %) :readable-domains ["products"])]]
    (with-context
      (fn [ctx marketplace]
        (let [calls  (atom 0)
              later  (verifying ctx calls verify)
              form   (assoc connect-form :command-id "attempt-1")
              before (act ctx ::web/connect form)
              result (accept! ctx before)
              change {:command-id "attempt-2" :connection-id "c-1" :client-id " 123 " :api-key "good-2"}]
          (is (= before (act later ::web/connect form)))
          (is (= result (accept! ctx (act later ::web/connect form))))
          (let [replaced (act ctx ::web/replace-credentials change)
                result   (accept! ctx replaced)]
            (is (= replaced (act later ::web/replace-credentials change)))
            (is (= result (accept! ctx (act later ::web/replace-credentials change))))
            (is (= "good-2" (connector/api-key marketplace scenarios/workspace (get-in replaced [:input :secret-ref])))))
          (is (zero? @calls) "Ozon is not asked again"))))))


(defn submit
  "Post `form` as the browser does: text values, with the fields every Tover form carries."
  {:malli/schema [:=> [:cat :map :qualified-keyword :map] :any]}
  [ctx id form]
  (handler/execute-action ctx id (merge {:__anti-forgery-token "token"                   :_tover-context "version"
                                         :workspace-id         (str scenarios/workspace)}
                                        form)))


(deftest retries-sent-by-the-real-form
  (doseq [verify [fake-verify
                  (constantly {:verification-outcome "unreachable"})
                  #(assoc (fake-verify %) :account-label "Renamed" :readable-domains ["products"])]]
    (with-context
      (fn [ctx marketplace]
        (let [calls    (atom 0)
              later    (verifying ctx calls verify)
              form     (assoc connect-form :command-id "attempt-1")
              change   {:command-id "attempt-2" :connection-id "c-1" :client-id " 123 " :api-key "good-2"}
              conflict #(scenarios/rejection (fn [] (submit later %1 %2)))
              row      #(runtime/read-model ctx ::model/connections {:connection-id "c-1"})]
          (let [shown (submit ctx ::web/connect form)]
            (is (= {:type :page :id ::web/connection :path-params {:connection-id "c-1"}} shown))
            (is (= shown (submit later ::web/connect form)) "double-click on Connect"))
          (doseq [other [{:client-id "456"} {:api-key "good-2"} {:connection-id "c-2"}]]
            (is (= :idempotency-conflict (conflict ::web/connect (merge form other)))))
          (let [shown    (submit ctx ::web/replace-credentials change)
                replaced (row)]
            (is (= shown (submit later ::web/replace-credentials change)) "double-click on Replace credentials")
            (is (= replaced (row)))
            (is (= "good-2" (connector/api-key marketplace scenarios/workspace (:secret-ref replaced)))))
          (doseq [other [{:client-id "456"} {:api-key "good"}]]
            (is (= :idempotency-conflict (conflict ::web/replace-credentials (merge change other)))))
          (is (= 1 (count (:connections (runtime/read-model ctx ::model/marketplace-connections-and-requirements {})))))
          (is (zero? @calls) "Ozon is not asked again"))))))


(deftest a-retry-recovers-an-attempt-that-left-the-outbox
  (with-context
    (fn [ctx _]
      (let [calls    (atom 0)
            form     (assoc connect-form :command-id "attempt-1")
            before   (act ctx ::web/connect form)
            _        (accept! ctx before)
            outbox   (get-in ctx [:bel/system :stores :catalogue])
            delivery (some #(when (= "attempt-1" (get-in % [:delivery :command-id])) (:delivery %)) (store/-pending outbox))
            later    (verifying ctx calls (constantly {:verification-outcome "unreachable"}))]
        (store/-ack! outbox delivery)
        (with-redefs [journal/receipt (fn [_ id] (when (= "attempt-1" id) (select-keys delivery [:request :result])))]
          (is (= before (act later ::web/connect form))))
        (with-redefs [journal/receipt (constantly nil)]
          (is (= :journal-unavailable (scenarios/rejection #(act later ::web/connect form)))))
        (is (= 503 (first (web/problems :journal-unavailable))) "a problem on the form that can be retried")
        (is (zero? @calls))))))


(deftest an-attempt-accepted-while-verifying-is-recovered
  (with-context
    (fn [ctx _]
      (let [form   (assoc connect-form :command-id "attempt-1")
            before (act ctx ::web/connect form)
            slow   (verifying ctx (atom 0) (fn [_] (accept! ctx before) {:verification-outcome "unreachable"}))]
        (is (= before (act slow ::web/connect form)))))))


(deftest replaying-a-superseded-replacement-seals-nothing
  (with-context
    (fn [ctx marketplace]
      (accept! ctx (act ctx ::web/connect connect-form))
      (let [calls  (atom 0)
            key-of #(connector/api-key marketplace scenarios/workspace (get-in % [:input :secret-ref]))
            form   {:command-id "replace-1" :connection-id "c-1" :client-id "123" :api-key "good"}
            r1     (act ctx ::web/replace-credentials form)
            _      (accept! ctx r1)
            r2     (act ctx ::web/replace-credentials (assoc form :command-id "replace-2" :api-key "good-2"))]
        (accept! ctx r2)
        (loop [tries 100]
          (when (and (key-of r1) (pos? tries)) (Thread/sleep 20) (recur (dec tries))))
        (is (nil? (key-of r1)) "the second replacement destroyed the first one's key")
        (let [replay (act (verifying ctx calls fake-verify) ::web/replace-credentials form)]
          (is (= r1 replay))
          (accept! ctx replay)
          (is (nil? (key-of r1)))
          (is (= "good-2" (key-of r2)))
          (is (zero? @calls) "Ozon is not asked again"))))))


(deftest only-the-same-attempt-recovers
  (with-context
    (fn [ctx marketplace]
      (let [calls (atom 0)
            later (verifying ctx calls fake-verify)
            form  (assoc connect-form :command-id "attempt-1")
            other (random-uuid)]
        (accept! ctx (act ctx ::web/connect form))
        (doseq [[ctx form] [[later (assoc form :api-key "good-2")]
                            [later (assoc form :client-id "456")]
                            [later (assoc form :connection-id "c-2")]
                            [(runtime/prepare-context (assoc later :user-id scenarios/member)) form]]]
          (is (= :idempotency-conflict (scenarios/rejection #(act ctx ::web/connect form)))))
        (is (= 409 (first (web/problems :idempotency-conflict))))
        (is (= :idempotency-conflict
               (scenarios/rejection #(act later ::web/replace-credentials
                                          {:command-id "attempt-1" :connection-id "c-1" :client-id "123" :api-key "good"})))
            "another command")
        (is (= :forbidden (scenarios/rejection #(act (assoc later :memberships [{:workspace-id scenarios/workspace :role :member}])
                                                     ::web/connect form)))
            "not a manager any more")
        (is (zero? @calls))
        (let [elsewhere (-> (runtime/prepare-context (assoc later :workspace-id other :memberships [{:workspace-id other :role :admin}]))
                            (assoc-in [:request :tover/identity :workspace-id] other))
              fresh     (act elsewhere ::web/connect (assoc form :workspace-id other))]
          (is (= 1 @calls) "another workspace verifies its own attempt")
          (is (contains? (:input fresh) :secret-ref) "its own attempt seals its own key")
          (is (= ["good" "good"] (keep #(connector/api-key marketplace % (:secret-ref (runtime/read-model ctx ::model/connections {:connection-id "c-1"})))
                                       [scenarios/workspace other]))
              "each workspace seals its own copy under the attempt's reference"))))))


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


(deftest coverage-counts-are-text-until-their-pages-exist
  (with-context
    (fn [ctx _]
      (accept! ctx (act ctx ::web/connect connect-form))
      ;; Chapter 42's own method is put back afterwards, so later tests count real listings.
      (let [unmatched (get-method model/count-part :unmatched-listings)]
        (defmethod model/count-part :unmatched-listings [_ _ _] 3)
        (defmethod model/count-part :next-steps [_ _ _] {"Confirm <b>" 5})
        (try
          (let [html (page ctx ::web/connection {:connection-id "c-1"})]
            (is (str/includes? html "Unmatched listings"))
            (is (str/includes? html "<dt>Baselines</dt><dd>0</dd>"))
            (is (str/includes? html "Imported operations by next step"))
            (is (str/includes? html "Confirm &lt;b&gt;") "text from outside is escaped")
            (is (not (str/includes? html "Confirm <b>")))
            (is (str/includes? html "href=\"/operations/marketplace/c-1/products\">3</a>") "the unmatched count opens Product Matching")
            (is (str/includes? html "href=\"/operations/marketplace/c-1/warehouses\">0</a>") "the unmatched count opens Warehouse Matching")
            (is (not (re-find #"href=\"[^\"]*/cutover" html)) "no links to pages that do not exist")
            (is (every? #(str/includes? html (str "<td>" (web/domain-labels %) "</td>")) connector/domains) "one row per domain"))
          (finally (.addMethod ^clojure.lang.MultiFn model/count-part :unmatched-listings unmatched)
                   (defmethod model/count-part :next-steps [_ _ _] {})))))))


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


(def unsafe-ids
  ["x');alert(1);('" "a:b" "a/b" "a b" "a\n" "" (apply str (repeat 101 "a"))])


(deftest a-connection-id-is-letters-digits-and-hyphens
  (with-context
    (fn [ctx _]
      (let [calls (atom 0)
            ctx   (verifying ctx calls fake-verify)
            good  (:input (act ctx ::web/connect connect-form))]
        (reset! calls 0)
        (doseq [id unsafe-ids]
          (doseq [[action form] {::web/connect             connect-form
                                 ::web/replace-credentials {:client-id "123" :api-key "good"}
                                 ::web/pause               {}
                                 ::web/resume              {}
                                 ::web/sync-now            {}}]
            (is (= [:validation true]
                   ((juxt :type :bel/input-error?)
                    (try (submit ctx action (assoc form :command-id "attempt-1" :connection-id id)) nil
                         (catch clojure.lang.ExceptionInfo error (ex-data error)))))
                (str action " " id)))
          (is (= :validation
                 (scenarios/rejection #(runtime/execute! ctx ::model/connect-marketplace-account
                                                         {:command-id (str (random-uuid)) :input (assoc good :connection-id id)})))
              (str "the command refuses " id)))
        (is (zero? @calls) "Ozon is not asked")
        (is (= [] (:connections (runtime/read-model ctx ::model/marketplace-connections-and-requirements {}))))))))


(deftest a-connection-id-is-encoded-in-expressions
  (is (= "/settings/marketplace/x%27%29%3Balert%281%29%3B%28%27" (#'web/connection-link "x');alert(1);('")))
  (is (= "/settings/marketplace/00000000-0000-0000-0000-00000000c001" (#'web/connection-link scenarios/c))))


(deftest problems-are-translated
  (doseq [[type status] {:journal-unavailable 503 :validation 422}
          locale        ["en" "ru"]
          :let          [[code message] (web/problems type)]]
    (is (= status code))
    (is (some? (get-in i18n/messages [locale message])) (str type))))


(deftest domains-are-named
  (with-context
    (fn [ctx _]
      (accept! ctx (act ctx ::web/connect connect-form))
      (doseq [[locale names] {"en" ["Warehouses" "FBO postings"] "ru" ["Склады" "Отправления FBO"]}]
        (binding [i18n/*locale* locale]
          (let [html   (page ctx ::web/connection {:connection-id "c-1"})
                failed (chassis/html (#'web/sync-panel {:last-outcome "failed" :found-counts {}
                                                        :last-failure {:failed-domain "fbo-postings" :reason "retries exhausted"
                                                                       :failed-at     scenarios/now}} "c-1"))]
            (is (every? #(str/includes? html (str "<td>" % "</td>")) names))
            (is (not-any? #(str/includes? html (str "<td>" % "</td>")) connector/domains))
            (is (str/includes? failed (second names)))
            (is (not (str/includes? failed "fbo-postings"))))))
      (is (= (set connector/domains) (set (keys web/domain-labels))))
      (is (every? #(get-in i18n/messages ["ru" %]) (vals web/domain-labels))))))


(deftest rejected-form-stays-open-without-credentials
  (with-context
    (fn [ctx _]
      (accept! ctx (act ctx ::web/connect connect-form))
      (let [html (section (assoc-in ctx [:request :params] {:provider "ozon"}))]
        (is (re-find #"<details[^>]* open" html))
        (is (not (re-find #"name=\"(?:client-id|api-key)\"[^>]*value=\"[^\"]" html)))))))


(deftest times-are-elements
  (with-context
    (fn [ctx _]
      (accept! ctx (act ctx ::web/connect connect-form))
      (accept! ctx (act ctx ::web/sync-now {:connection-id "c-1"}))
      (doseq [[locale text] {"en" "Sync running since <time datetime=\"" "ru" "Синхронизация идёт с <time datetime=\""}]
        (binding [i18n/*locale* locale]
          (let [html (page ctx ::web/connection {:connection-id "c-1"})]
            (is (str/includes? html text))
            (is (not (str/includes? html "[:time")))))))))


(deftest the-refresh-keeps-the-credentials-form-as-the-person-left-it
  (with-context
    (fn [ctx _]
      (accept! ctx (act ctx ::web/connect connect-form))
      (let [details (re-find #"<details[^>]*>" (page ctx ::web/connection {:connection-id "c-1"}))]
        (is (str/includes? details "data-preserve-attr=\"open\""))
        (testing "a morph from the list or from another connection must not match it"
          (is (str/includes? details "id=\"replace-credentials-c-1\""))
          (is (re-find #"<details[^>]* id=\"connect-account\"" (section ctx))))))))
