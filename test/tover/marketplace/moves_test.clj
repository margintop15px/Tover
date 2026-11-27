(ns tover.marketplace.moves-test
  "Board chapter 45 slice b · Read stock differences: Ozon's finished stock-by-warehouse snapshots compared with
  Tover's balance at each snapshot's observation time, per item and FBO warehouse with a baseline; the item's state,
  its last agreeing snapshot and, when moved, its moves. Roadmap decision 4: only the last 30 finished passes keep
  their figures."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.runtime :as runtime]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [tover.catalogues.records :as records]
    [tover.inventory.acceptance.model :as acceptance]
    [tover.inventory.entries.model :as entries]
    [tover.marketplace.connections.model :as connections]
    [tover.marketplace.corrections-test :as corrections-test]
    [tover.marketplace.guard-test :as guard-test]
    [tover.marketplace.moves.model :as moves]
    [tover.marketplace.operations-test :as operations-test]
    [tover.marketplace.provenance :as provenance]
    [tover.marketplace.sync.model :as sync]
    [tover.marketplace.sync.observations :as observations]
    [tover.marketplace.warehouses.model :as warehouses]
    [tover.support :as support])
  (:import
    [java.time Instant]))


(use-fixtures :each bel-test/isolated-registry
  (fn [f]
    (bel/build-registry! ['tover.catalogues.products.model 'tover.catalogues.stores.model 'tover.catalogues.warehouses.model
                          'tover.catalogues.suppliers.model 'tover.catalogues.categories.model
                          'tover.inventory.stock-items.model 'tover.inventory.entries.model 'tover.inventory.acceptance.model
                          'tover.marketplace.connections.model 'tover.marketplace.sync.model 'tover.marketplace.products.model
                          'tover.marketplace.products.guard 'tover.marketplace.warehouses.model 'tover.marketplace.provenance
                          'tover.marketplace.operations.model 'tover.marketplace.operations.corrections
                          'tover.marketplace.moves.model 'tover.workspaces.currency.model])
    (f))
  guard-test/unit-authority)


(def workspace (:workspace-id support/scope))
(def member #uuid "00000000-0000-0000-0000-0000000000b1")
(def c operations-test/c)
(def connector (sync/connector-actor c))
(def hub-7 (str c ":fbo:7"))
(def hub-8 (str c ":fbo:8"))
(def hub-9 (str c ":fbo:9"))
(def seller (str c ":fbs:3"))

(def seed-all! #'operations-test/seed-all!)
(def operation #'operations-test/operation)
(def opening #'operations-test/opening)
(def warehouse-mapped #'operations-test/warehouse-mapped)


(defn tuples
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector :any]]}
  [moves]
  (mapv (juxt :from :to :quantity) moves))


(defn- day
  "06:00 on the given day of November 2026."
  {:malli/schema [:=> [:cat :int] :string]}
  [n]
  (format "2026-11-%02dT06:00:00Z" n))


(defn- pass-events
  "A finished stock-by-warehouse pass of `run-id` traversed at `at` that asked for listings 100 and 200, with the
  figures {[sku external-warehouse] present}."
  {:malli/schema [:=> [:cat :string :string :string :map] [:vector :any]]}
  [connection-id run-id at stated]
  (into [[::sync/marketplace-sync-started {:run-id             run-id :connection-id connection-id :trigger "schedule"
                                           :triggered-by       (str "connector:" connection-id) :domains "stock-by-warehouse"
                                           :starting-positions "{}" :started-at at :run-state               "open"}]
         [::sync/source-batch-recorded {:position-id (sync/position-id connection-id "stock-by-warehouse") :connection-id connection-id
                                        :domain      "stock-by-warehouse" :run-id run-id :cursor "{}" :batch-key        "" :refetch-ids "[]"
                                        :finished    true :found-listings 0 :found-warehouses    0    :found-operations 0
                                        :recorded-at at}]
         [::sync/source-domain-traversed {:run-id           run-id :connection-id                  connection-id :domain "stock-by-warehouse"
                                          :covered-until    at :traversed-at at
                                          :position-id      (sync/position-id connection-id "stock-by-warehouse")
                                          :finished-domains "stock-by-warehouse" :first-covered-at at
                                          :pass-id          (str run-id "/stock-by-warehouse")
                                          :asked            (observations/write-json ["100" "200"])}]]
        (for [[[sku place] present] stated
              :let                  [product (str connection-id ":" sku)
                                     type (second (re-find #":(fbo|fbs):" place))]]
          [::sync/source-stock-observed {:stock-id              (observations/stock-id product "warehouse-stock" place run-id)
                                         :connection-id         connection-id :run-id run-id            :external-product-id product
                                         :external-warehouse-id place :source         "warehouse-stock" :fulfilment-type     type
                                         :present               present :parts        "[]"              :observed-at         at}])))


(defn- accepted
  "An accepted record of `kind` at `occurred-at` with lines [[item warehouse change]]."
  {:malli/schema [:=> [:cat :string :string :string [:sequential :any]] [:vector :any]]}
  [record kind occurred-at changes]
  (let [rid (acceptance/record-revision-id record 1)
        at  (Instant/parse occurred-at)]
    (into [[::acceptance/inventory-changes-accepted {:acceptance-id (str "a-" rid) :actor member :recorded-at (Instant/parse "2026-11-26T00:00:00Z")
                                                     :record-count  1}]
           [::acceptance/inventory-record-accepted {:record-revision-id rid  :record-id   record :revision 1 :acceptance-id (str "a-" rid)
                                                    :kind               kind :occurred-at at     :sequence 0 :cancelled     false}]]
          (map-indexed (fn [i [item warehouse change]]
                         [::acceptance/inventory-record-line-accepted {:record-line-id  (acceptance/record-line-id rid i) :record-revision-id rid
                                                                       :position        i :stock-item-id item :warehouse-id warehouse
                                                                       :quantity-change change}])
                       changes))))


(defn- setup!
  "Plan 4's connected workspace: red and blue mugs (listings 100 and 200), the seller warehouse as Home, the FBO
  hub 7 as Ozon 7, and here also hub 8 as Ozon 8 and hub 9 as Ozon 9. Red has 10 in Home, Ozon 7 and Ozon 8 from
  the 1st, each its cutover; Ozon 9 has no baseline."
  {:malli/schema [:function [:=> [:cat :any] :map] [:=> [:cat :any ifn?] :map]]}
  ([repo] (setup! repo operations-test/setup!))
  ([repo base]
   (let [{:keys [ctx] :as setup} (base repo)]
     (seed-all! ctx (concat [[:tover.catalogues.warehouses.model/warehouse-registered {:warehouse-id "ozon-8" :name "Ozon 8" :status "active" :revision 1}]
                             [:tover.catalogues.warehouses.model/warehouse-registered {:warehouse-id "ozon-9" :name "Ozon 9" :status "active" :revision 1}]
                             [::warehouses/external-warehouse-mapped (warehouse-mapped hub-8 "fbo" "ozon-8")]
                             [::warehouses/external-warehouse-mapped (warehouse-mapped hub-9 "fbo" "ozon-9")]]
                            (opening "red" "ozon-8")))
     (assoc setup
            :pass! (fn [run-id at stated] (seed-all! ctx (pass-events c run-id at stated)))
            :accept! (fn [& args] (seed-all! ctx (apply accepted args)))
            :differences (fn [] (runtime/read-model ctx ::moves/stock-differences {:connection-id c}))))))


(defn- item
  {:malli/schema [:=> [:cat :map :string] [:maybe :map]]}
  [read stock-item-id]
  (some #(when (= stock-item-id (:stock-item-id %)) %) (:items read)))


(defn- rows
  "The item's rows as [warehouse ozon tover difference]."
  {:malli/schema [:=> [:cat [:maybe :map]] [:vector :any]]}
  [item]
  (mapv (juxt :warehouse-id :ozon :tover :difference) (:rows item)))


(def equal {["100" hub-7] 10 ["100" hub-8] 10})
(def moved-5 {["100" hub-7] 5 ["100" hub-8] 15})


;; Pairing


(deftest moved
  (is (= [{:from "W1" :to "W2" :quantity 5}] (moves/pairs {"W1" -5 "W2" 5})))
  (support/with-store
    (fn [repo _]
      (let [{:keys [pass! differences]} (setup! repo)]
        (testing "Given no difference yesterday"
          (pass! "D1" (day 21) equal)
          (is (= "equal" (:state (item (differences) "red"))))
          (testing "and today −5 in W1 and +5 in W2"
            (pass! "D2" (day 22) moved-5)
            (let [read (differences)
                  red  (item read "red")]
              (is (= ["D2" (day 22)] ((juxt :run-id :observed-at) read)) "the latest finished snapshot")
              (is (= [["ozon-7" 5 10 -5] ["ozon-8" 15 10 5]] (rows red)))
              (testing "then a move of 5 from W1 to W2 is detected"
                (is (= "moved" (:state red)))
                (is (= [["ozon-7" "ozon-8" 5]] (tuples (:moves red))))
                (is (= {:run-id "D1" :observed-at (day 21)} (:last-agreeing red)))))))))))


(deftest largest-first
  (is (= [["W1" "W3" 6] ["W2" "W3" 1] ["W2" "W4" 3]] (tuples (moves/pairs {"W1" -6 "W2" -4 "W3" 7 "W4" 3}))))
  (is (= [["W1" "W2" 5]] (tuples (moves/pairs [{:warehouse-id "W1" :difference -5} {:warehouse-id "W2" :difference 5}
                                               {:warehouse-id "W3" :difference 0}])))
      "from difference rows too"))


(deftest lost
  (support/with-store
    (fn [repo _]
      (let [{:keys [pass! differences]} (setup! repo)]
        (pass! "D1" (day 21) equal)
        (testing "Given today −5 in W1 and +3 in W2"
          (pass! "D2" (day 22) {["100" hub-7] 5 ["100" hub-8] 13})
          (let [red (item (differences) "red")]
            (testing "then both differences are shown and nothing is proposed"
              (is (= [["ozon-7" 5 10 -5] ["ozon-8" 13 10 3]] (rows red)))
              (is (= "unexplained" (:state red)))
              (is (nil? (:moves red)))
              (is (= "D1" (get-in red [:last-agreeing :run-id])) "the last agreeing snapshot is still shown"))))))))


(deftest no-agreeing-snapshot
  (support/with-store
    (fn [repo _]
      (let [{:keys [pass! differences]} (setup! repo)]
        (testing "Given −5 and +5 in the first snapshot"
          (pass! "D2" (day 22) moved-5)
          (let [red (item (differences) "red")]
            (testing "then they are shown, and no move without an agreeing snapshot"
              (is (= [["ozon-7" 5 10 -5] ["ozon-8" 15 10 5]] (rows red)))
              (is (= "unexplained" (:state red)))
              (is (nil? (:last-agreeing red)))
              (is (nil? (:moves red))))))))))


(deftest still-not-accepted-next-day
  (support/with-store
    (fn [repo _]
      (let [{:keys [pass! differences]} (setup! repo)]
        (pass! "D1" (day 21) equal)
        (pass! "D2" (day 22) moved-5)
        (is (= "D1" (get-in (item (differences) "red") [:last-agreeing :run-id])))
        (testing "Given the same differences the day after"
          (pass! "D3" (day 23) moved-5)
          (let [read (differences)
                red  (item read "red")]
            (is (= "D3" (:run-id read)))
            (testing "then the same move is detected, from the same last agreeing snapshot"
              (is (= "moved" (:state red)))
              (is (= [["ozon-7" "ozon-8" 5]] (tuples (:moves red))))
              (is (= {:run-id "D1" :observed-at (day 21)} (:last-agreeing red))))))))))


(deftest undecided-operation
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! pass! differences source-id]} (setup! repo)]
        (pass! "D1" (day 21) equal)
        (pass! "D2" (day 22) moved-5)
        (testing "History before the cutover counts as decided"
          (observe! (operation "P0" 1 :occurred-at "2026-10-20T09:00:00Z"))
          (is (= "moved" (:state (item (differences) "red")))))
        (testing "An undecided operation of the item after its cutover: waiting, never a move"
          (observe! (operation "P1" 1))
          (let [red (item (differences) "red")]
            (is (= "waiting for operations" (:state red)))
            (is (nil? (:moves red)))
            (is (= [["ozon-7" 5 10 -5] ["ozon-8" 15 10 5]] (rows red)) "the differences are still shown")))
        (testing "Set aside, it counts as decided"
          (seed-all! ctx [[::provenance/source-operation-set-aside
                           {:source-operation-id (source-id "P1")       :connection-id c :revision 1 :aside true :set-by (str member)
                            :set-at              "2026-11-22T10:00:00Z"}]])
          (is (= "moved" (:state (item (differences) "red")))))))))


(deftest warehouse-without-baseline
  (support/with-store
    (fn [repo _]
      (let [{:keys [pass! differences]} (setup! repo)]
        (testing "Given Ozon holds red in Ozon 9, which has no baseline"
          (pass! "D1" (day 21) (assoc equal ["100" hub-9] 2))
          (testing "then the item is not compared"
            (is (nil? (item (differences) "red")))))
        (testing "nor when the warehouse holding it is not matched"
          (pass! "D2" (day 22) (assoc equal ["100" (str c ":fbo:404")] 1))
          (is (nil? (item (differences) "red"))))
        (testing "Blue has no FBO baseline at all"
          (is (nil? (item (differences) "blue"))))))))


(deftest seller-warehouses-not-compared
  (support/with-store
    (fn [repo _]
      (let [{:keys [pass! differences]} (setup! repo)]
        (testing "Given Ozon states 4 red in the seller warehouse, where Tover has 10"
          (pass! "D1" (day 21) (assoc equal ["100" seller] 4))
          (let [red (item (differences) "red")]
            (testing "then only the FBO warehouses are compared"
              (is (= [["ozon-7" 10 10 0] ["ozon-8" 10 10 0]] (rows red)))
              (is (= "equal" (:state red))))))))))


(deftest balance-at-the-snapshot
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx pass! accept! differences]} (setup! repo)]
        (pass! "D1" (day 21) equal)
        (testing "Given a sale of 3 from Ozon 7 accepted after the snapshot"
          (accept! "sale-1" "sale" "2026-11-21T12:00:00Z" [["red" "ozon-7" -3]])
          (let [history (runtime/coeffect ::acceptance/history ctx {})]
            (is (= 10 (get (moves/balance-at history (day 21)) ["red" "ozon-7"])))
            (is (= 7 (get (moves/balance-at history "2026-11-21T12:00:00Z") ["red" "ozon-7"])) "the instant itself counts")
            (is (= 7 (get (moves/balance-at history (day 23)) ["red" "ozon-7"]))))
          (testing "then the snapshot is compared with the balance at the snapshot"
            (is (= [["ozon-7" 10 10 0] ["ozon-8" 10 10 0]] (rows (item (differences) "red"))))
            (is (= "equal" (:state (item (differences) "red"))))))))))


(deftest backdated-acceptance-moves-the-last-agreeing-snapshot-back
  (support/with-store
    (fn [repo _]
      (let [{:keys [pass! accept! differences]} (setup! repo)]
        (pass! "D1" (day 21) equal)
        (pass! "D2" (day 22) equal)
        (pass! "D3" (day 23) {["100" hub-7] 5 ["100" hub-8] 15})
        (is (= ["moved" "D2"] ((juxt :state (comp :run-id :last-agreeing)) (item (differences) "red"))))
        (testing "Given a transfer of 1 from Ozon 7 to Ozon 8 accepted later, dated between D1 and D2"
          (accept! "transfer-1" "transfer" "2026-11-21T12:00:00Z" [["red" "ozon-7" -1] ["red" "ozon-8" 1]])
          (let [red (item (differences) "red")]
            (testing "then yesterday no longer agrees and the last agreeing snapshot moves back"
              (is (= {:run-id "D1" :observed-at (day 21)} (:last-agreeing red)))
              (is (= [["ozon-7" 5 9 -4] ["ozon-8" 15 11 4]] (rows red)))
              (is (= [["ozon-7" "ozon-8" 4]] (tuples (:moves red)))))))))))


;; Roadmap decision 4 · Snapshot retention


(deftest thirty-first-pass-expires-the-oldest
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx system]} (setup! repo)
            run-ids              (mapv #(format "R%02d" %) (range 1 32))
            figures              #(set (map (juxt :run-id :source :external-warehouse-id) (records/all-records ctx ::sync/source-stock :stock-id)))
            analytic             {:stock-id (observations/stock-id (str c ":100") "warehouse-analytics" hub-7 "R01") :connection-id c
                                  :run-id   "R01" :external-product-id (str c ":100") :external-warehouse-id hub-7
                                  :source   "warehouse-analytics" :fulfilment-type "fbo" :present            9     :parts "[]" :observed-at (day 1)}
            finish!              (fn [run-id]
                                   (runtime/execute! {:workspace-id workspace :bel/system system :bel/service ::sync/marketplace-fetcher :user-id connector}
                                                     ::sync/record-source-observations
                                                     {:command-id (str "finish-" run-id)
                                                      :input      {:run-id          run-id :domain                  "stock-by-warehouse" :batch-key ""
                                                                   :next-position   {:cursor "{}"} :domain-finished true
                                                                   :observations    [{:kind      "stock"               :source  "warehouse-stock" :product-source-id "100"
                                                                                      :warehouse {:type "fbo" :id "7"} :present 10}]
                                                                   :adapter-version "test"}}))]
        (testing "Given 30 finished passes, another connection's older pass and the latest figure of another view"
          (seed-all! ctx (concat (mapcat #(pass-events c %1 (format "2026-10-%02dT06:00:00Z" %2) {["100" hub-7] 10}) (take 30 run-ids) (range 1 31))
                                 ;; Only its figures: the other connection is not set up here.
                                 (filter (comp #{::sync/source-stock-observed} first)
                                         (pass-events "other" "O1" "2026-09-01T06:00:00Z" {["100" "other:fbo:7"] 3}))
                                 [[::sync/source-stock-observed analytic]
                                  [::sync/marketplace-sync-started {:run-id             "R31"                :connection-id c                    :trigger   "schedule"
                                                                    :triggered-by       (str "connector:" c) :domains       "stock-by-warehouse"
                                                                    :starting-positions "{}"                 :started-at    (day 1)              :run-state "open"}]]))
          (is (= 32 (count (figures))))
          (testing "when the batch that finishes the 31st pass is recorded"
            (finish! "R31")
            (testing "then only the oldest pass's figures are deleted"
              (is (= (set (concat (map #(vector % "warehouse-stock" hub-7) (rest run-ids))
                                  [["O1" "warehouse-stock" "other:fbo:7"] ["R01" "warehouse-analytics" hub-7]]))
                     (figures))))
            (testing "and nothing else"
              (is (= 31 (count (sync/finished-passes ctx c "stock-by-warehouse"))) "the passes stay")
              (is (some? (runtime/read-model ctx ::sync/sync-runs {:run-id "R01"})) "the run stays")
              (is (= 1 (count (sync/stock-figures ctx "other" "O1"))) "another connection keeps its figures"))))))))


(deftest closed-runs-older-than-the-retained-passes-expire
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx system]} (setup! repo)
            oct                  #(format "2026-10-%02dT06:00:00Z" %)
            figures              #(set (map (juxt :connection-id :run-id :source) (records/all-records ctx ::sync/source-stock :stock-id)))
            started              (fn [run-id at]
                                   [::sync/marketplace-sync-started {:run-id             run-id               :connection-id c                    :trigger   "schedule"
                                                                     :triggered-by       (str "connector:" c) :domains       "stock-by-warehouse"
                                                                     :starting-positions "{}"                 :started-at    at                   :run-state "open"}])
            failed               (fn [run-id at]
                                   [::sync/marketplace-sync-failed {:run-id               run-id            :connection-id   c    :failed-domain "stock-by-warehouse"
                                                                    :reason               "incomplete page" :saved-positions "{}"
                                                                    :consecutive-failures 1                 :failed-at       at   :run-state     "failed"             :readable-domains "products"}])
            figure               (fn [connection-id run-id source at]
                                   (let [product (str connection-id ":100") place (str connection-id ":fbo:7")]
                                     [::sync/source-stock-observed {:stock-id              (observations/stock-id product source place run-id)
                                                                    :connection-id         connection-id :run-id run-id :external-product-id product
                                                                    :external-warehouse-id place :source         source :fulfilment-type     "fbo"   :present 1
                                                                    :parts                 "[]" :observed-at     at}]))
            passes               (mapv #(format "P%02d" %) (range 2 31))]
        (testing "Given 29 finished passes from 2 October, closed runs with partial figures before and after the oldest
                  of them, an open run's figures, another view's figures and another connection's"
          (seed-all! ctx (concat [(started "F0" (oct 1)) (figure c "F0" "warehouse-stock" (oct 1)) (failed "F0" (oct 1))
                                  (figure c "F0" "warehouse-analytics" (oct 1))
                                  (started "Open0" (oct 1)) (figure c "Open0" "warehouse-stock" (oct 1))
                                  (figure "other" "O1" "warehouse-stock" (oct 1))]
                                 (mapcat #(pass-events c %1 (oct %2) {["100" hub-7] 10}) passes (range 2 31))
                                 [(started "F15" "2026-10-15T07:00:00Z") (figure c "F15" "warehouse-stock" "2026-10-15T07:00:00Z")
                                  (failed "F15" "2026-10-15T08:00:00Z")
                                  (started "N" (oct 31))]))
          (testing "when the batch that finishes the 30th pass is recorded"
            (runtime/execute! {:workspace-id workspace :bel/system system :bel/service ::sync/marketplace-fetcher :user-id connector}
                              ::sync/record-source-observations
                              {:command-id "finish-N"
                               :input      {:run-id          "N" :domain           "stock-by-warehouse" :batch-key "" :next-position {:cursor "{}"}
                                            :domain-finished true :adapter-version "test"
                                            :observations    [{:kind      "stock"               :source  "warehouse-stock" :product-source-id "100"
                                                               :warehouse {:type "fbo" :id "7"} :present 10}]}})
            (let [kept (figures)]
              (testing "then the closed run older than the oldest retained pass loses its warehouse stock figures"
                (is (not (contains? kept [c "F0" "warehouse-stock"]))))
              (testing "and nothing else is deleted"
                (is (contains? kept [c "F15" "warehouse-stock"]) "a closed run after the oldest retained pass")
                (is (contains? kept [c "Open0" "warehouse-stock"]) "an open run")
                (is (contains? kept [c "F0" "warehouse-analytics"]) "another view")
                (is (contains? kept ["other" "O1" "warehouse-stock"]) "another connection")
                (is (every? #(contains? kept [c % "warehouse-stock"]) (conj passes "N")) "the retained passes")))
            (testing "and the comparison reads only the retained passes' figures"
              (is (= (set (conj passes "N")) (set (map :run-id (:figures (moves/load-facts ctx c)))))))))))))


;; Accepted operations whose source changed (plan 5 task 1)


(def ^:private corrections-setup #'corrections-test/setup!)
(def ^:private posting #'corrections-test/posting)


(defn- fbo-sale
  "The FBO posting P1: 3 red mugs shipped from hub `hub` on the 10th."
  {:malli/schema [:=> [:cat :string [:* :any]] :map]}
  [hub & {:as fields}]
  (apply posting (mapcat identity (merge {:operation-kind "fbo-posting" :from-warehouse {:type "fbo" :id hub}} fields))))


(defn- accepted-fbo-sale!
  "Given P1 accepted automatically as a sale of 3 from Ozon 7, and a snapshot D1 that agrees with it."
  {:malli/schema [:=> [:cat :map] :nil]}
  [{:keys [observe! intake! automatic! accepted source-id pass! differences balance]}]
  (observe! (fbo-sale "7"))
  (intake!)
  (is (= 1 (automatic!)))
  (is (some? (accepted (source-id "P1"))))
  (is (= 7 (balance "red" "ozon-7")))
  (pass! "D1" (day 21) {["100" hub-7] 7 ["100" hub-8] 10})
  (is (= "equal" (:state (item (differences) "red"))))
  nil)


(deftest restated-warehouse-waits-for-the-correction
  (support/with-store
    (fn [repo _]
      (let [{:keys [observe! corrections! pass! differences pending] :as s} (setup! repo corrections-setup)
            s (assoc s :source-id #(str c ":fbo-posting:" %))]
        (accepted-fbo-sale! s)
        (testing "Given Ozon restates the accepted sale as shipped from Ozon 8, and its snapshot shows that"
          (observe! (fbo-sale "8"))
          (pass! "D2" (day 22) {["100" hub-7] 10 ["100" hub-8] 7})
          (testing "then, before the correction is proposed, the item waits and no move is found"
            (let [red (item (differences) "red")]
              (is (= [["ozon-7" 10 7 3] ["ozon-8" 7 10 -3]] (rows red)))
              (is (= "waiting for operations" (:state red)))
              (is (nil? (:moves red)))))
          (testing "and while the correction is pending it still waits"
            (is (= 1 (corrections!)))
            (is (some? (pending (provenance/correction-entry-id (str c ":fbo-posting:P1") 1))))
            (is (= "waiting for operations" (:state (item (differences) "red"))))))))))


(deftest restated-status-only-is-decided
  (support/with-store
    (fn [repo _]
      (let [{:keys [observe! corrections! differences] :as s} (setup! repo corrections-setup)
            s (assoc s :source-id #(str c ":fbo-posting:" %))]
        (accepted-fbo-sale! s)
        (testing "Given Ozon restates only the status of the accepted sale"
          (observe! (fbo-sale "7" :status "customer_received" :price "450"))
          (is (= 0 (corrections!)) "nothing to propose")
          (testing "then the item is compared as before"
            (is (= "equal" (:state (item (differences) "red"))))))))))


;; Board 45 slice c · Propose the detected move


(def ^:private failure #'operations-test/failure)
(def ^:private accept-all! #'operations-test/accept-all!)


(defn- move-id
  "The pending Transfer of red from `from` to `to`, keyed by the last agreeing run."
  {:malli/schema [:=> [:cat :string :string :string] :string]}
  [last-agreeing from to]
  (str "move:" c "|red|" last-agreeing "|" from "|" to))


(defn- detection
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [ctx system marketplace] :as setup}]
  (assoc setup
         :detect! (fn []
                    ;; As `runtime/run-processors!` runs Move Detection, with a full sweep each time.
                    (reset! (:swept marketplace) nil)
                    (let [found (runtime/coeffect ::moves/moves-to-propose {:bel/system system} {})]
                      (doseq [request (map moves/move-detection found)]
                        (runtime/execute! (merge (:scope request) {:bel/system system :bel/service ::moves/move-detection :user-id (:actor request)})
                                          (:id request)
                                          {:command-id (runtime/processor-command-id ::moves/move-detection request) :input (:input request)}))
                      (count found)))
         :propose! (fn [command-id input]
                     (runtime/execute! {:workspace-id workspace :bel/system system :bel/service ::moves/move-detection :user-id connector}
                                       ::moves/propose-detected-move {:command-id command-id :input input}))
         :detected (fn [last-agreeing]
                     (some #(when (= (str c "|red|" last-agreeing) (:detected-move-id %)) %)
                           (records/all-records ctx ::moves/detected-moves :detected-move-id)))
         :moved (fn []
                  (vec (sort (keep #(when (provenance/move-entry-id? (:entry-id %)) (:entry-id %))
                                   (runtime/coeffect ::entries/pending ctx {})))))))


(defn- transfer
  "A pending Transfer as [kind from to occurred-at saved-by [[item quantity]]]."
  {:malli/schema [:=> [:cat [:maybe :map]] [:maybe [:vector :any]]]}
  [entry]
  (when entry
    [(:kind entry) (:from-warehouse-id entry) (:to-warehouse-id entry) (some-> (:occurred-at entry) str) (:saved-by entry)
     (mapv (juxt :stock-item-id :quantity) (:lines entry))]))


(defn- status-of
  {:malli/schema [:=> [:cat :map :string] [:maybe :string]]}
  [ctx entry-id]
  (some #(when (= entry-id (:entry-id %)) (:status %)) (records/all-records ctx ::entries/entries :entry-id)))


(deftest member-accepts
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx pass! detect! pending balance detected differences]} (detection (setup! repo))
            id (move-id "D1" "ozon-7" "ozon-8")]
        (pass! "D1" (day 21) equal)
        (pass! "D2" (day 22) moved-5)
        (testing "Given a detected move of 5 from W1 to W2, when it is proposed"
          (is (= 1 (detect!)))
          (testing "then one pending Transfer exists, dated right after the last agreeing snapshot"
            (is (= ["transfer" "ozon-7" "ozon-8" "2026-11-21T06:00:01Z" connector [["red" 5]]] (transfer (pending id))))
            (let [row (detected "D1")]
              (is (= [c "red" "D1" "D2" [{:from "ozon-7" :to "ozon-8" :quantity 5}] [id]]
                     ((juxt :connection-id :stock-item-id :last-agreeing-run :current-run
                            (comp observations/read-json :moves) (comp observations/read-json :entries))
                      row)))
              (is (some? (:proposed-at row)))))
          (testing "and stock is unchanged"
            (is (= [10 10] [(balance "red" "ozon-7") (balance "red" "ozon-8")]))
            (is (= "moved" (:state (item (differences) "red")))))
          (testing "and stock changes only when a member accepts it"
            (accept-all! ctx "acc-move" [[id 1]])
            (is (nil? (pending id)))
            (is (= [5 15] [(balance "red" "ozon-7") (balance "red" "ozon-8")]))
            (is (= "equal" (:state (item (differences) "red"))))
            (is (= 0 (detect!)) "nothing more to propose")))))))


(deftest still-there-next-day
  (support/with-store
    (fn [repo _]
      (let [{:keys [pass! detect! pending moved]} (detection (setup! repo))
            id (move-id "D1" "ozon-7" "ozon-8")]
        (pass! "D1" (day 21) equal)
        (pass! "D2" (day 22) moved-5)
        (is (= 1 (detect!)))
        (testing "Given that pending Transfer and the same differences the next day"
          (pass! "D3" (day 23) moved-5)
          (testing "then nothing new is proposed"
            (is (= 0 (detect!)))
            (is (= [id] (moved)))
            (is (= 1 (:revision (pending id))))))))))


(deftest explained-later
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx pass! detect! pending detected moved]} (detection (setup! repo))
            id (move-id "D1" "ozon-7" "ozon-8")]
        (pass! "D1" (day 21) equal)
        (pass! "D2" (day 22) moved-5)
        (is (= 1 (detect!)))
        (testing "Given that Transfer, unedited, when the next snapshot no longer shows the difference"
          (pass! "D3" (day 23) equal)
          (is (= 1 (detect!)))
          (testing "then the pending Transfer is withdrawn"
            (is (nil? (pending id)))
            (is (= [] (moved)))
            (is (= "discarded" (status-of ctx id)))
            (is (= [[] [] "D3"] ((juxt (comp observations/read-json :moves) (comp observations/read-json :entries) :current-run) (detected "D1"))))
            (is (= 0 (detect!)) "once")))))))


(deftest different-moves-replace-unedited
  (support/with-store
    (fn [repo _]
      (let [{:keys [pass! detect! pending detected moved]} (detection (setup! repo))
            id (move-id "D1" "ozon-7" "ozon-8")]
        (pass! "D1" (day 21) equal)
        (pass! "D2" (day 22) moved-5)
        (is (= 1 (detect!)))
        (testing "Given the unedited Transfer of 5, when the next snapshot shows 7 moved"
          (pass! "D3" (day 23) {["100" hub-7] 3 ["100" hub-8] 17})
          (is (= 1 (detect!)))
          (testing "then the same key's Transfer is replaced"
            (is (= [id] (moved)))
            (is (= ["transfer" "ozon-7" "ozon-8" "2026-11-21T06:00:01Z" connector [["red" 7]]] (transfer (pending id))))
            (is (= 2 (:revision (pending id))))
            (is (= [{:from "ozon-7" :to "ozon-8" :quantity 7}] (observations/read-json (:moves (detected "D1")))))))))))


(deftest edited-transfer-is-kept
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx pass! detect! pending]} (detection (setup! repo))
            id (move-id "D1" "ozon-7" "ozon-8")]
        (pass! "D1" (day 21) equal)
        (pass! "D2" (day 22) moved-5)
        (is (= 1 (detect!)))
        (testing "Given a member edited the Transfer to 4"
          (runtime/execute! ctx ::entries/save-shared-inventory-entry
                            {:command-id "edit-move"
                             :input      {:workspace-id workspace :entry-id id :expected-revision 1 :kind "transfer"
                                          :occurred-at  "2026-11-21T06:00:01Z" :from-warehouse-id "ozon-7" :to-warehouse-id "ozon-8"
                                          :lines        [{:line-id "1" :stock-item-id "red" :quantity 4}]}})
          (is (= member (:saved-by (pending id))))
          (testing "when different moves are detected"
            (pass! "D3" (day 23) {["100" hub-7] 3 ["100" hub-8] 17})
            (is (= 0 (detect!)))
            (testing "then the member's Transfer is kept"
              (is (= ["transfer" "ozon-7" "ozon-8" "2026-11-21T06:00:01Z" member [["red" 4]]] (transfer (pending id))))))
          (testing "nor is it withdrawn when the difference goes away"
            (pass! "D4" (day 24) equal)
            (is (= 0 (detect!)))
            (is (= 2 (:revision (pending id))))))))))


(deftest discarded-is-not-repeated
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx pass! detect! pending moved]} (detection (setup! repo))
            id (move-id "D1" "ozon-7" "ozon-8")]
        (pass! "D1" (day 21) equal)
        (pass! "D2" (day 22) moved-5)
        (is (= 1 (detect!)))
        (testing "Given a member discarded the Transfer"
          (runtime/execute! ctx ::entries/discard-inventory-draft {:command-id "discard-move" :input {:workspace-id workspace :entry-id id :revision 1}})
          (is (nil? (pending id)))
          (testing "when the same moves are seen again"
            (pass! "D3" (day 23) moved-5)
            (testing "then it is not proposed again for the same key and moves"
              (is (= 0 (detect!)))
              (is (= [] (moved)))))
          (testing "but different moves are proposed"
            (pass! "D4" (day 24) {["100" hub-7] 3 ["100" hub-8] 17})
            (is (= 1 (detect!)))
            (is (= [["red" 7]] (last (transfer (pending id)))))))))))


(deftest discarded-moves-are-remembered-per-key
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx pass! detect! pending moved]} (detection (setup! repo))
            id (move-id "D1" "ozon-7" "ozon-8")
            discard (fn [command-id revision]
                      (runtime/execute! ctx ::entries/discard-inventory-draft
                                        {:command-id command-id :input {:workspace-id workspace :entry-id id :revision revision}}))]
        (pass! "D1" (day 21) equal)
        (testing "Given a member discarded the proposed 5, then the proposed 7"
          (pass! "D2" (day 22) moved-5)
          (is (= 1 (detect!)))
          (discard "discard-5" 1)
          (pass! "D3" (day 23) {["100" hub-7] 3 ["100" hub-8] 17})
          (is (= 1 (detect!)))
          (is (= [["red" 7]] (last (transfer (pending id)))))
          (discard "discard-7" (:revision (pending id)))
          (testing "when 5 is seen again under the same key"
            (pass! "D4" (day 24) moved-5)
            (testing "then it is not proposed again"
              (is (= 0 (detect!)))
              (is (= [] (moved))))))))))


(deftest balance-changed-within-the-pass
  (support/with-store
    (fn [repo _]
      (let [{:keys [pass! accept! detect! pending moved]} (detection (setup! repo))
            id (move-id "D1" "ozon-7" "ozon-8")]
        (pass! "D1" (day 21) equal)
        (pass! "D2" (day 22) moved-5)
        (is (= 1 (detect!)))
        (testing "Given a transfer of 2 accepted later, dated between the snapshots, and no newer pass"
          (accept! "transfer-2" "transfer" "2026-11-21T12:00:00Z" [["red" "ozon-7" -2] ["red" "ozon-8" 2]])
          (testing "then the pending Transfer is replaced by the 3 still moved"
            (is (= 1 (detect!)))
            (is (= [["red" 3]] (last (transfer (pending id)))))))
        (testing "and once the rest is accepted the same way, it is withdrawn"
          (accept! "transfer-3" "transfer" "2026-11-21T13:00:00Z" [["red" "ozon-7" -3] ["red" "ozon-8" 3]])
          (is (= 1 (detect!)))
          (is (= [] (moved))))))))


(deftest paused-connection
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx pass! detect! propose! moved]} (detection (setup! repo))]
        (pass! "D1" (day 21) equal)
        (pass! "D2" (day 22) moved-5)
        (testing "Given the connection is paused"
          (seed-all! ctx [[::connections/marketplace-connection-paused
                           {:connection-id c :sync-state "paused" :reason "manager" :paused-by (str member) :paused-at (day 22)}]])
          (testing "then no move is proposed"
            (is (= 0 (detect!)))
            (is (= :connection-paused (failure #(propose! "p" {:connection-id c :stock-item-id "red" :last-agreeing-run "D1" :current-run "D2" :moves [{:from "ozon-7" :to "ozon-8" :quantity 5}]}))))
            (is (= [] (moved)))))))))


(deftest stale-when-a-newer-pass-exists
  (support/with-store
    (fn [repo _]
      (let [{:keys [system observe! pass! propose! moved]} (detection (setup! repo))
            input {:connection-id c :stock-item-id "red" :last-agreeing-run "D1" :current-run "D2" :moves [{:from "ozon-7" :to "ozon-8" :quantity 5}]}]
        (pass! "D1" (day 21) equal)
        (pass! "D2" (day 22) moved-5)
        (testing "Given a newer snapshot than the one read"
          (pass! "D3" (day 23) moved-5)
          (testing "then the proposal is stale"
            (is (= :stale (failure #(propose! "p1" input))))
            (is (= [] (moved)))))
        (testing "as it is with another last agreeing snapshot"
          (is (= :stale (failure #(propose! "p2" (assoc input :current-run "D3" :last-agreeing-run "D2"))))))
        (testing "or with other moves than those computed on commit"
          (is (= :stale (failure #(propose! "p5" (assoc input :current-run "D3" :moves [{:from "ozon-7" :to "ozon-8" :quantity 4}])))))
          (is (= [] (moved))))
        (testing "or with an undecided operation of the item"
          (observe! (operation "P1" 1))
          (is (= :stale (failure #(propose! "p3" (assoc input :current-run "D3"))))))
        (testing "and only the connector of the connection proposes"
          (is (= :not-permitted (failure #(runtime/execute! {:workspace-id workspace :bel/system system :bel/service ::moves/move-detection
                                                             :user-id      (sync/connector-actor "other")}
                                                            ::moves/propose-detected-move
                                                            {:command-id "p4" :input (assoc input :current-run "D3")})))))))))


;; Moves wait for corrections (final review I1, I2)


(defn- waiting-setup!
  "Given P1 accepted from Ozon 7 and D1 agreeing, D2 shows the sale shipped from Ozon 8 before its revision arrives:
  a move of 3 from Ozon 8 to Ozon 7 is proposed. `detection` keys plus the correction setup's."
  {:malli/schema [:=> [:cat :any] :map]}
  [repo]
  (let [s (assoc (detection (setup! repo corrections-setup)) :source-id #(str c ":fbo-posting:" %))]
    (accepted-fbo-sale! s)
    ((:pass! s) "D2" (day 22) {["100" hub-7] 10 ["100" hub-8] 7})
    (is (= 1 ((:detect! s))))
    (is (some? ((:pending s) (move-id "D1" "ozon-8" "ozon-7"))))
    s))


(deftest correction-withdraws-the-unedited-transfer
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! corrections! detect! pending moved detected differences] :as s} (waiting-setup! repo)
            id (move-id "D1" "ozon-8" "ozon-7")
            correction (provenance/correction-entry-id (str c ":fbo-posting:P1") 1)]
        (testing "Given the pending Transfer, when the revision restating the warehouse proposes a correction"
          (observe! (fbo-sale "8"))
          (is (= 1 (corrections!)))
          (is (some? (pending correction)))
          (is (= "waiting for operations" (:state (item (differences) "red"))))
          (testing "then the connector's unedited Transfer is withdrawn, so the two can never both be accepted"
            (is (= 1 (detect!)))
            (is (= [] (moved)))
            (is (= "discarded" (status-of ctx id)))
            (is (= [[] []] ((juxt (comp observations/read-json :moves) (comp observations/read-json :entries)) (detected "D1"))))
            (is (= 0 (detect!)) "once"))
          (testing "and once the member accepts the correction, nothing is proposed again"
            (accept-all! ctx "acc-correction" [[correction 1]])
            (is (= "equal" (:state (item (differences) "red"))))
            (is (= 0 (detect!)))
            (is (= [] (moved)))))))))


(deftest edited-transfer-stays-while-waiting
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! corrections! detect! pending]} (waiting-setup! repo)
            id (move-id "D1" "ozon-8" "ozon-7")]
        (testing "Given a member edited the Transfer to 2"
          (runtime/execute! ctx ::entries/save-shared-inventory-entry
                            {:command-id "edit-move"
                             :input      {:workspace-id workspace :entry-id id :expected-revision 1 :kind "transfer"
                                          :occurred-at  "2026-11-21T06:00:01Z" :from-warehouse-id "ozon-8" :to-warehouse-id "ozon-7"
                                          :lines        [{:line-id "1" :stock-item-id "red" :quantity 2}]}})
          (testing "when a correction makes the item wait, the member's Transfer is kept"
            (observe! (fbo-sale "8"))
            (is (= 1 (corrections!)))
            (is (= 0 (detect!)))
            (is (= [["red" 2]] (last (transfer (pending id)))))))))))


(deftest settled-item-is-proposed-again
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! corrections! detect! pending moved]} (waiting-setup! repo)
            id (move-id "D1" "ozon-8" "ozon-7")
            correction (provenance/correction-entry-id (str c ":fbo-posting:P1") 1)]
        (observe! (fbo-sale "8"))
        (is (= 1 (corrections!)))
        (is (= 1 (detect!)))
        (is (= [] (moved)))
        (testing "Given the member discards the correction, the item settles with the move still shown"
          (runtime/execute! ctx ::entries/discard-inventory-draft {:command-id "discard-correction" :input {:workspace-id workspace :entry-id correction :revision 1}})
          (testing "then detection proposes the Transfer again"
            (is (= 1 (detect!)))
            (is (= [id] (moved)))
            (is (= ["transfer" "ozon-8" "ozon-7" "2026-11-21T06:00:01Z" connector [["red" 3]]] (transfer (pending id))))))))))


(deftest hand-cancelled-record-stops-the-wait
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! corrections! detect! pending moved differences]} (waiting-setup! repo)
            correction (provenance/correction-entry-id (str c ":fbo-posting:P1") 1)
            record (provenance/import-entry-id (str c ":fbo-posting:P1"))]
        (testing "Given a member's cancellation of the accepted sale is pending when its correction is proposed"
          (runtime/execute! ctx ::entries/save-shared-inventory-entry
                            {:command-id "m-cancel"
                             :input      {:workspace-id workspace :entry-id "m-cancel" :kind "sale" :amends (acceptance/record-revision-id record 1)
                                          :cancel       true      :reason   "Wrong"}})
          (observe! (fbo-sale "8"))
          (is (= 1 (corrections!)))
          (is (= 1 (detect!)) "the Transfer is withdrawn while the item waits")
          (is (= [] (moved)))
          (testing "when the member accepts the cancellation"
            (accept-all! ctx "acc-cancel" [["m-cancel" 1]])
            (testing "then intake withdraws the correction, which can no longer be accepted"
              (is (= 1 (corrections!)))
              (is (nil? (pending correction))))
            (testing "and the item no longer waits, so it is compared again"
              (is (not= "waiting for operations" (:state (item (differences) "red")))))))))))


(deftest refusals-do-not-keep-the-item-waiting
  (let [correcting? #'moves/correcting?
        now         (Instant/parse "2026-11-26T00:00:00Z")
        sale        {:entry {:kind "sale" :lines [{:line-id "1" :stock-item-id "red" :quantity 3 :warehouse-id "home"}]} :missing [] :findings []}
        base        {:operation  {:source-operation-id "s" :connection-id c :revision 2} :acceptance        {:record-id "r"}
                     :latest     {:revision 1 :record-revision-id "r#1"}                 :accepted-proposal sale             :new-proposal sale
                     :desired-id "correction:s@1"}]
    (testing "A changed kind proposes nothing and does not make the item wait"
      (is (false? (correcting? (assoc-in base [:new-proposal :entry :kind] "transfer") now))))
    (testing "Nor does a record already cancelled"
      (is (false? (correcting? (assoc-in (assoc-in base [:new-proposal :entry :lines 0 :quantity] 2) [:latest :cancelled] true) now))))
    (testing "A change intake would propose does"
      (is (true? (correcting? (assoc-in base [:new-proposal :entry :lines 0 :quantity] 2) now))))))
