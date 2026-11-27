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
                          'tover.marketplace.operations.model 'tover.marketplace.moves.model 'tover.workspaces.currency.model])
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
  {:malli/schema [:=> [:cat :any] :map]}
  [repo]
  (let [{:keys [ctx] :as setup} (operations-test/setup! repo)]
    (seed-all! ctx (concat [[:tover.catalogues.warehouses.model/warehouse-registered {:warehouse-id "ozon-8" :name "Ozon 8" :status "active" :revision 1}]
                            [:tover.catalogues.warehouses.model/warehouse-registered {:warehouse-id "ozon-9" :name "Ozon 9" :status "active" :revision 1}]
                            [::warehouses/external-warehouse-mapped (warehouse-mapped hub-8 "fbo" "ozon-8")]
                            [::warehouses/external-warehouse-mapped (warehouse-mapped hub-9 "fbo" "ozon-9")]]
                           (opening "red" "ozon-8")))
    (assoc setup
           :pass! (fn [run-id at stated] (seed-all! ctx (pass-events c run-id at stated)))
           :accept! (fn [& args] (seed-all! ctx (apply accepted args)))
           :differences (fn [] (runtime/read-model ctx ::moves/stock-differences {:connection-id c})))))


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
