(ns tover.marketplace.cutover-test
  "Board chapter 44 · Read cutover readiness and observed balances (slice 1) and Read baseline coverage (slice 5):
  pure reads over given facts, then the coeffect, read models and connection counts through a store."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.runtime :as runtime]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [tover.catalogues.categories.model]
    [tover.catalogues.products.model]
    [tover.catalogues.stores.model]
    [tover.catalogues.suppliers.model]
    [tover.catalogues.warehouses.model]
    [tover.inventory.acceptance.model :as acceptance]
    [tover.inventory.entries.model]
    [tover.inventory.stock-items.model]
    [tover.marketplace.connections.model :as connections]
    [tover.marketplace.cutover.model :as cutover]
    [tover.marketplace.products-test :as listings]
    [tover.marketplace.provenance :as provenance]
    [tover.marketplace.sync.model :as sync]
    [tover.support :as support]
    [tover.workspaces.currency.model])
  (:import
    [java.time Instant]))


(defn- register
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (bel/build-registry! ['tover.catalogues.products.model 'tover.catalogues.stores.model 'tover.catalogues.warehouses.model
                        'tover.catalogues.suppliers.model 'tover.catalogues.categories.model
                        'tover.inventory.stock-items.model 'tover.inventory.entries.model 'tover.inventory.acceptance.model
                        'tover.marketplace.connections.model 'tover.marketplace.sync.model 'tover.marketplace.products.model
                        'tover.marketplace.warehouses.model 'tover.marketplace.provenance 'tover.marketplace.cutover.model
                        'tover.workspaces.currency.model])
  (f))


(use-fixtures :each bel-test/isolated-registry register listings/unit-authority)


(def c "c1")
(def snapshot "2026-11-20T10:00:00Z")
(def hub "c1:fbo:1")
(def seller-a "c1:fbs:2")
(def seller-b "c1:fbs:3")


(defn- day
  {:malli/schema [:=> [:cat :int] :any]}
  [n]
  (Instant/parse (format "2026-11-%02dT00:00:00Z" n)))


(defn- figure
  {:malli/schema [:=> [:cat :string :string :string :int [:* :any]] :map]}
  [run-id listing warehouse present & {:keys [type] :or {type "fbo"}}]
  {:run-id run-id :external-product-id listing :external-warehouse-id warehouse :fulfilment-type type :present present})


(defn- product-mapping
  {:malli/schema [:=> [:cat :string [:* :any]] :map]}
  [item & {:keys [state revision] :or {state "confirmed" revision 1}}]
  (cond-> {:connection-id c :state state :mapping-revision revision} item (assoc :stock-item-id item)))


(defn- warehouse-mapping
  {:malli/schema [:=> [:cat :string :string] :map]}
  [warehouse type]
  {:connection-id c :warehouse-id warehouse :warehouse-type type :state "confirmed" :mapping-revision 1})


(defn- record
  "One accepted revision on day `d` with `[item warehouse change unit-cost?]` lines."
  {:malli/schema [:=> [:cat :string :string :int [:* :any]] :map]}
  [id kind d & lines]
  (let [rid (acceptance/record-revision-id id 1)]
    {:record {:record-revision-id rid     :record-id id :revision 1 :kind kind :occurred-at (day d) :sequence 0 :cancelled false
              :recorded-at        (day d)}
     :lines  (vec (map-indexed (fn [i [item warehouse n cost]]
                                 (cond-> {:record-revision-id rid :position i :stock-item-id item :warehouse-id warehouse :quantity-change n}
                                   cost (assoc :unit-cost cost)))
                               lines))}))


(defn- history
  {:malli/schema [:=> [:cat [:* :map]] :map]}
  [& records]
  {:records (mapv :record records) :lines (vec (mapcat :lines records))})


(def base
  "Listing p1 (red) is in the hub; the pass of run r1 finished at the snapshot."
  {:currency           "RUB"
   :passes             [{:run-id "r1" :observed-at snapshot :asked #{"c1:p1" "c1:p2"}}]
   :figures            [(figure "r1" "c1:p1" hub 120)]
   :listings           [{:external-product-id "c1:p1"} {:external-product-id "c1:p2"}]
   :product-mappings   {"c1:p1" (product-mapping "red")}
   :warehouse-mappings {hub (warehouse-mapping "main" "fbo")}
   :history            (history)
   :provenance         {:cutover-entries {} :baselines {}}})


(defn- scopes
  {:malli/schema [:=> [:cat :map [:* :any]] [:vector :map]]}
  [facts & {:keys [instant] :or {instant snapshot}}]
  (cutover/scopes facts {:connection-id c :instant instant}))


(defn- scope
  {:malli/schema [:=> [:cat :map :string :string [:* :any]] [:maybe :map]]}
  [facts item warehouse & options]
  (some #(when (= [item warehouse] ((juxt :stock-item-id :warehouse-id) %)) %) (apply scopes facts options)))


;; Cost


(deftest cost-from-ozon
  (testing "Given an item without cost in Tover and Ozon cost 350 RUB in a RUB workspace, the column shows 350, source Ozon"
    (is (= ["350" "Ozon"] (cutover/cost {:average nil :listings [{:cost "350" :cost-currency "RUB"}] :currency "RUB"})))
    (is (= ["350" "Ozon"] ((juxt :suggested-cost :cost-source)
                           (scope (assoc base :listings [{:external-product-id "c1:p1" :cost "350" :cost-currency "RUB"}]) "red" "main"))))))


(deftest tover-average-wins
  (is (= ["120.5" "Tover average"] (cutover/cost {:average 120.5000M :listings [{:cost "350" :cost-currency "RUB"}] :currency "RUB"})))
  (testing "the item's average in another warehouse, weighted by quantity"
    (let [facts (assoc base
                       :listings [{:external-product-id "c1:p1" :cost "350" :cost-currency "RUB"}]
                       :history (history (record "o1" "opening" 1 ["red" "back" 2 "100.00"])
                                         (record "o2" "opening" 1 ["red" "store" 6 "200.00"])))]
      (is (= ["175" "Tover average" "opening"] ((juxt :suggested-cost :cost-source :result) (scope facts "red" "main")))))))


(deftest listings-disagree-on-cost
  (is (= [nil nil] (cutover/cost {:listings [{:cost "350" :cost-currency "RUB"} {:cost "360" :cost-currency "RUB"}] :currency "RUB"})))
  (testing "equal values written differently agree; listings without a cost do not vote"
    (is (= ["350" "Ozon"] (cutover/cost {:listings [{:cost "350" :cost-currency "RUB"} {:cost "350.00" :cost-currency "RUB"}
                                                    {:cost "0" :cost-currency "RUB"} {}]
                                         :currency "RUB"}))))
  (testing "0 or blank means not entered; another currency stays blank"
    (is (= [nil nil] (cutover/cost {:listings [{:cost "0" :cost-currency "RUB"} {:cost "" :cost-currency "RUB"}] :currency "RUB"})))
    (is (= [nil nil] (cutover/cost {:listings [{:cost "350" :cost-currency "CNY"}] :currency "RUB"})))
    (is (= [nil nil] (cutover/cost {:listings [{:cost "350" :cost-currency "RUB"} {:cost "40" :cost-currency "USD"}] :currency "RUB"}))))
  (testing "two listings of one item in the scope"
    (let [facts (assoc base
                       :listings [{:external-product-id "c1:p1" :cost "350" :cost-currency "RUB"}
                                  {:external-product-id "c1:p2" :cost "360" :cost-currency "RUB"}]
                       :product-mappings {"c1:p1" (product-mapping "red") "c1:p2" (product-mapping "red")})]
      (is (= [nil nil] ((juxt :suggested-cost :cost-source) (scope facts "red" "main")))))))


;; Quantities


(deftest moved-instant
  (testing "Given the instant is moved away from the snapshot, quantities are blank and the observation stays visible"
    (let [row (scope base "red" "main" :instant "2026-11-19T10:00:00Z")]
      (is (= "ready" (:readiness row)))
      (is (= [120 snapshot] ((juxt :observed-quantity :observed-at) row)))
      (is (not (contains? row :suggested-quantity))))
    (is (= 120 (:suggested-quantity (scope base "red" "main"))))
    (testing "the same instant in another offset is the snapshot"
      (is (= 120 (:suggested-quantity (scope base "red" "main" :instant "2026-11-20T13:00:00+03:00")))))))


(deftest listings-of-one-item-are-summed
  (let [facts (assoc base
                     :figures [(figure "r1" "c1:p1" hub 120) (figure "r1" "c1:p2" hub 30)]
                     :product-mappings {"c1:p1" (product-mapping "red") "c1:p2" (product-mapping "red" :revision 3)})
        row   (scope facts "red" "main")]
    (is (= [150 150] ((juxt :observed-quantity :suggested-quantity) row)))
    (is (= {:products {"c1:p1" 1 "c1:p2" 3} :warehouses {hub 1}} (:mapping-revisions row)))
    (is (= 1 (count (scopes facts))))))


(deftest seller-warehouse-quantity-is-evidence-only
  (testing "two seller warehouses sharing one Tover warehouse form one scope, observed but never prefilled"
    (let [facts (assoc base
                       :figures [(figure "r1" "c1:p1" seller-a 4 :type "fbs") (figure "r1" "c1:p1" seller-b 3 :type "rfbs")]
                       :warehouse-mappings {seller-a (warehouse-mapping "shop" "fbs") seller-b (warehouse-mapping "shop" "rfbs")})
          rows  (scopes facts)]
      (is (= 1 (count rows)))
      (is (= ["red" "shop" "ready" 7 snapshot]
             ((juxt :stock-item-id :warehouse-id :readiness :observed-quantity :observed-at) (first rows))))
      (is (not (contains? (first rows) :suggested-quantity))))))


(deftest unfinished-pass-is-not-used
  (testing "figures of a run whose pass did not finish are neither evidence nor prefill"
    (let [facts (assoc base :figures [(figure "r2" "c1:p1" hub 99) (figure "r1" "c1:p1" hub 120)])]
      (is (= [120 120] ((juxt :observed-quantity :suggested-quantity) (scope facts "red" "main" :instant snapshot))))))
  (testing "without a finished pass there is no instant and no observation"
    (is (nil? (cutover/default-instant [])))
    (is (= [] (scopes (assoc base :passes [] :figures [(figure "r2" "c1:p1" hub 99)]) :instant nil)))))


(deftest a-listing-the-pass-speaks-for-without-a-figure-has-zero
  (let [facts (assoc base
                     :figures [(figure "r1" "c1:p1" hub 120)]
                     :product-mappings {"c1:p1" (product-mapping "red") "c1:p2" (product-mapping "red")})]
    (is (= 120 (:observed-quantity (scope facts "red" "main"))))))


;; Readiness


(deftest not-ready-names-the-missing-match
  (let [facts (assoc base
                     :figures [(figure "r1" "c1:p1" hub 120) (figure "r1" "c1:p2" hub 5) (figure "r1" "c1:p1" seller-a 2 :type "fbs")]
                     :product-mappings {"c1:p1" (product-mapping "red") "c1:p2" (product-mapping "blue" :state "suspended")})
        rows  (scopes facts)]
    (is (= [{:stock-item-id "red" :warehouse-id "main" :readiness "ready"}
            {:external-product-id "c1:p2" :readiness "needs product match"}
            {:external-warehouse-id seller-a :readiness "needs warehouse match"}]
           (mapv #(select-keys % [:stock-item-id :warehouse-id :external-product-id :external-warehouse-id :readiness]) rows)))
    (testing "suggested fields appear only on ready rows"
      (is (every? #(not-any? % [:suggested-quantity :suggested-cost :cost-source :result]) (rest rows))))))


(deftest sold-out-at-ozon-with-tover-stock
  (testing "a mapped pair Tover holds stock for, without an Ozon row, is listed: observed 0 at an FBO warehouse"
    (let [facts (assoc base
                       :figures [(figure "r1" "c1:p1" hub 120)]
                       :product-mappings {"c1:p1" (product-mapping "red") "c1:p2" (product-mapping "blue")}
                       :history (history (record "o1" "opening" 1 ["blue" "main" 5 "100.00"])))
          row   (scope facts "blue" "main")]
      (is (= ["ready" 0 snapshot 0 5 "correction"]
             ((juxt :readiness :observed-quantity :observed-at :suggested-quantity :balance :result) row)))))
  (testing "at a seller warehouse there is no observation"
    (let [facts (assoc base
                       :figures []
                       :warehouse-mappings {seller-a (warehouse-mapping "shop" "fbs")}
                       :history (history (record "o1" "opening" 1 ["red" "shop" 5 "100.00"])))
          row   (scope facts "red" "shop")]
      (is (= ["ready" "correction"] ((juxt :readiness :result) row)))
      (is (not-any? row [:observed-quantity :observed-at :suggested-quantity]))))
  (testing "a listing the pass did not ask for has no observation"
    (let [facts (assoc base
                       :passes [{:run-id "r1" :observed-at snapshot :asked #{"c1:p1"}}]
                       :product-mappings {"c1:p1" (product-mapping "red") "c1:p2" (product-mapping "blue")}
                       :history (history (record "o1" "opening" 1 ["blue" "main" 5 "100.00"])))]
      (is (not (contains? (scope facts "blue" "main") :observed-quantity)))))
  (testing "history alone lists no pair without both confirmed mappings"
    (let [facts (assoc base :history (history (record "o1" "opening" 1 ["green" "main" 5] ["red" "back" 2])))]
      (is (= [["red" "main"]] (mapv (juxt :stock-item-id :warehouse-id) (scopes facts)))))))


(deftest pending-and-baselined-scopes-are-marked
  (let [entry {:entry-id    "cutover:c1|red|main#1" :connection-id c :stock-item-id "red" :warehouse-id "main" :instant snapshot
               :proposed-by "m" :proposed-at snapshot}
        blue  {:baseline-id (provenance/baseline-id c "blue" "main") :connection-id c :stock-item-id "blue" :warehouse-id "main"
               :instant     snapshot :record-id "o9" :established-at snapshot}
        other (assoc blue :baseline-id (provenance/baseline-id "c2" "green" "main") :connection-id "c2" :stock-item-id "green")
        facts (assoc base :provenance {:cutover-entries {(:entry-id entry) entry}
                                       :baselines       {(:baseline-id blue) blue (:baseline-id other) other}})
        rows  (scopes facts)]
    (is (= [["blue" "main" "has baseline"] ["red" "main" "pending"]]
           (mapv (juxt :stock-item-id :warehouse-id :readiness) rows)))
    (is (= 120 (:observed-quantity (second rows))))
    (is (every? #(not-any? % [:suggested-quantity :suggested-cost :result]) rows))
    (testing "an accepted entry's scope has its baseline"
      (let [red (assoc blue :baseline-id (provenance/baseline-id c "red" "main") :stock-item-id "red")]
        (is (= "has baseline" (:readiness (scope (assoc-in facts [:provenance :baselines (:baseline-id red)] red) "red" "main"))))))))


(deftest balance-is-at-the-instant
  (let [facts (assoc base :history (history (record "o1" "opening" 1 ["red" "main" 10 "100.00"])
                                            (record "s1" "sale" 25 ["red" "main" -4])))]
    (testing "later records are excluded"
      (is (= [10 "correction"] ((juxt :balance :result) (scope facts "red" "main" :instant "2026-11-20T10:00:00Z")))))
    (testing "a correction needs no cost"
      (is (not-any? (scope facts "red" "main") [:suggested-cost :cost-source])))
    (testing "accepted history starting after the instant makes the scope not ready: no opening or correction fits"
      (let [row (scope facts "red" "main" :instant "2026-10-01T00:00:00Z")]
        (is (= "history after instant" (:readiness row)))
        (is (not-any? #(contains? row %) [:result :balance :suggested-quantity :mapping-revisions])))))
  (testing "without history: no balance, an opening"
    (let [row (scope base "red" "main")]
      (is (not (contains? row :balance)))
      (is (= "opening" (:result row)))))
  (testing "a cancelled record is no history"
    (let [o1        (record "o1" "opening" 1 ["red" "main" 10 "100.00"])
          cancelled (assoc (:record o1) :record-revision-id "o1#2" :revision 2 :cancelled true)]
      (is (= "opening" (:result (scope (assoc base :history {:records [(:record o1) cancelled] :lines (:lines o1)}) "red" "main")))))))


;; Instants


(deftest default-instant-is-the-last-finished-snapshot
  (is (= snapshot (cutover/default-instant [{:run-id "r2" :observed-at snapshot} {:run-id "r1" :observed-at "2026-11-19T10:00:00Z"}]))))


(deftest earliest-is-the-latest-first-coverage
  (let [positions (into {} (map (fn [[d at]] [d {:domain d :first-covered-at at}]))
                        {"fbs-postings" "2026-08-01T00:00:00Z" "fbo-postings" "2026-08-03T00:00:00Z" "returns"  "2026-08-02T00:00:00Z"
                         "rfbs-returns" "2026-08-01T00:00:00Z" "supplies"     "2026-07-01T00:00:00Z" "removals" "2026-08-02T00:00:00Z"
                         "products"     "2026-11-01T00:00:00Z"})]
    (is (= "2026-08-03T00:00:00Z" (cutover/earliest positions)))
    (testing "nil while any operation domain lacks it, so nothing can be proposed"
      (is (nil? (cutover/earliest (dissoc positions "removals"))))
      (is (nil? (cutover/earliest {}))))
    (testing "only readable operation domains count"
      (is (= "2026-08-02T00:00:00Z"
             (cutover/earliest (dissoc positions "fbo-postings") #{"fbs-postings" "returns" "rfbs-returns" "supplies" "removals"}))))))


;; Coverage


(deftest coverage-by-stock-item-and-warehouse
  (let [blue  {:baseline-id (provenance/baseline-id c "blue" "main") :connection-id c :stock-item-id "blue" :warehouse-id "main"
               :instant     snapshot :record-id "o9" :established-at snapshot}
        facts (assoc base
                     :figures [(figure "r1" "c1:p1" hub 120) (figure "r1" "c1:p2" hub 5) (figure "r1" "c1:p3" seller-a 1 :type "fbs")]
                     :product-mappings {"c1:p1" (product-mapping "red") "c1:p2" (product-mapping "blue")}
                     :provenance {:cutover-entries {} :baselines {(:baseline-id blue) blue}})]
    (is (= {:connection-id    c
            :baselines        [{:stock-item-id "blue" :warehouse-id "main" :instant snapshot :record-id "o9" :waiting-operations 0}]
            :without-baseline [{:stock-item-id "red" :warehouse-id "main" :readiness "ready" :waiting-operations 0}]}
           (cutover/coverage facts c {})))
    (is (= {:without-baseline [{:stock-item-id "red" :warehouse-id "main" :readiness "ready" :waiting-operations 2}]}
           (select-keys (cutover/coverage facts c {["red" "main"] 2}) [:without-baseline])))
    (testing "counts that could not be read are unknown, never zero"
      (is (= [nil nil] (map :waiting-operations (mapcat (cutover/coverage facts c nil) [:baselines :without-baseline])))))))


;; Store


(defn- seed!
  {:malli/schema [:=> [:cat :map :keyword :map] :nil]}
  [ctx type data]
  (listings/seed! ctx type data))


(deftest readiness-and-coverage-through-the-store
  (support/with-store
    (fn [repo _]
      (let [ctx     (listings/context repo)
            listing (str listings/c ":p1")
            wh      (str listings/c ":fbo:1")]
        (seed! ctx ::connections/marketplace-account-connected
               {:connection-id listings/c :workspace-id      (str listings/workspace) :provider         "ozon" :provider-account-id "123"
                :secret-ref    "secret"   :account-currency  "RUB"                    :readable-domains "products stock-by-warehouse"
                :sync-state    "active"   :credentials-state "valid"                  :connected-by     "m" :connected-at           snapshot})
        ;; The run stays open: a finished stock-by-warehouse pass is used even while other domains are still syncing.
        (seed! ctx ::sync/marketplace-sync-started
               {:run-id             "r1" :connection-id listings/c :trigger   "member" :triggered-by "m" :domains "stock-by-warehouse"
                :starting-positions "{}" :started-at    snapshot   :run-state "open"})
        (seed! ctx ::sync/source-batch-recorded
               {:position-id      (str listings/c "/stock-by-warehouse") :connection-id listings/c :domain "stock-by-warehouse"
                :run-id           "r1" :cursor "" :batch-key "" :refetch-ids "[]" :finished true :found-listings 1
                :found-warehouses 0 :found-operations 0 :recorded-at snapshot})
        (seed! ctx ::sync/source-domain-traversed
               {:run-id           "r1" :connection-id listings/c :domain           "stock-by-warehouse" :covered-until snapshot :traversed-at snapshot
                :position-id      (str listings/c "/stock-by-warehouse") :finished-domains "stock-by-warehouse"
                :first-covered-at snapshot :pass-id "r1/stock-by-warehouse" :asked "[\"p1\"]"})
        (seed! ctx ::sync/source-stock-observed
               {:stock-id            (str listing "|warehouse-stock|" wh "|r1") :connection-id listings/c :run-id "r1"
                :external-product-id listing :external-warehouse-id wh :source "warehouse-stock" :fulfilment-type "fbo"
                :present             120 :parts "[]" :observed-at snapshot})
        (seed! ctx :tover.marketplace.products.model/external-product-mapped
               {:external-product-id listing :mapping-revision-id (str listing "#1") :connection-id listings/c  :mapping-revision 1
                :changed-by          "m"     :changed-at          snapshot           :change        "mapped"    :stock-item-id    "red" :listing-revision 1
                :item-revision       1       :identity-basis      "{}"               :state         "confirmed"})
        (seed! ctx :tover.marketplace.warehouses.model/external-warehouse-mapped
               {:external-warehouse-id wh :mapping-revision-id (str wh "#1") :connection-id listings/c :warehouse-type "fbo"
                :mapping-revision      1  :changed-by          "m"           :changed-at    snapshot   :change         "mapped" :warehouse-id "main" :state "confirmed"})
        (let [read (runtime/read-model ctx ::cutover/cutover-readiness-and-observed-balances {:connection-id listings/c})]
          (is (= snapshot (:instant read)))
          (is (= [["red" "main" "ready" 120 120 "opening"]]
                 (mapv (juxt :stock-item-id :warehouse-id :readiness :observed-quantity :suggested-quantity :result) (:scopes read))))
          (is (= [nil] (mapv :balance (:scopes read)))))
        (is (= 1 (connections/count-part :scopes-without-baseline ctx listings/c)))
        (is (= 0 (connections/count-part :baselines ctx listings/c)))
        (seed! ctx ::provenance/cutover-baseline-established
               {:baseline-id  (provenance/baseline-id listings/c "red" "main") :connection-id listings/c :stock-item-id "red"
                :warehouse-id "main" :instant snapshot :record-id "o1" :established-at snapshot})
        (is (= 0 (connections/count-part :scopes-without-baseline ctx listings/c)))
        (is (= 1 (connections/count-part :baselines ctx listings/c)))
        (is (= [{:stock-item-id "red" :warehouse-id "main" :instant snapshot :record-id "o1" :waiting-operations 0}]
               (:baselines (runtime/read-model ctx ::cutover/baseline-coverage-by-stock-item-and-warehouse {:connection-id listings/c}))))))))
