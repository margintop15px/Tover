(ns tover.marketplace.steps-test
  "Board chapter 45 slices 3 and 5 · Check automatic-acceptance eligibility and Read imported operations needing
  review: every downloaded operation has exactly one next step, the first that applies, with `history`, `set aside`
  and `no effect` decided before the rest."
  (:require
    [clojure.test :refer [are deftest is testing]]
    [tover.inventory.acceptance.model :as acceptance]
    [tover.inventory.valuation :as valuation]
    [tover.marketplace.operations.steps :as steps]
    [tover.marketplace.products.candidates :as candidates]
    [tover.marketplace.sync.model :as sync]
    [tover.marketplace.sync.observations :as observations])
  (:import
    [java.time Instant]))


(def connection "c1")
(def connector (sync/connector-actor connection))
(def member #uuid "00000000-0000-0000-0000-000000000011")


(defn- at
  {:malli/schema [:=> [:cat :string] :any]}
  [text]
  (Instant/parse text))


(def listings
  [{:external-product-id "c1:mug-red" :connection-id connection :name "Mug red"
    :identifiers         [{:type "sku" :value "100" :active true}]}
   {:external-product-id "c1:mug-blue" :connection-id connection :name "Mug blue"
    :identifiers         (observations/write-json [{:type "sku" :value "200" :active true}])}])


(def stock-items
  [{:stock-item-id "red" :product-id "mug" :variant-label "red" :unit "pcs" :condition "regular" :status "active" :revision 1}
   {:stock-item-id "blue" :product-id "mug" :variant-label "blue" :unit "pcs" :condition "regular" :status "active" :revision 1}])


(def catalogue
  {:products    [{:product-id "mug" :name "Mug" :status "active" :revision 1}]
   :stock-items stock-items
   :barcodes    []
   :warehouses  [{:warehouse-id "home" :name "Home" :status "active" :revision 1}
                 {:warehouse-id "ozon-7" :name "Ozon 7" :status "active" :revision 1}]
   :suppliers   []
   :stores      []
   :categories  []})


(defn- basis
  "The identity basis a confirmed mapping of `listing` to `item` stands on, as stored JSON."
  {:malli/schema [:=> [:cat :map :map] :string]}
  [listing item]
  (observations/write-json (candidates/identity-basis listing [] item)))


(def mappings
  {"c1:mug-red"  {:external-product-id "c1:mug-red" :connection-id  connection :stock-item-id "red" :state "confirmed"
                  :mapping-revision    1            :identity-basis (basis (first listings) (first stock-items))}
   "c1:mug-blue" {:external-product-id "c1:mug-blue" :connection-id  connection :stock-item-id "blue" :state "confirmed"
                  :mapping-revision    1             :identity-basis (basis (second listings) (second stock-items))}})


(def warehouse-mappings
  {"c1:fbs:3"           {:external-warehouse-id "c1:fbs:3" :connection-id connection :warehouse-type "fbs" :warehouse-id "home" :state "confirmed"}
   "c1:fbo:7"           {:external-warehouse-id "c1:fbo:7" :connection-id connection :warehouse-type "fbo" :warehouse-id "ozon-7" :state "confirmed"}
   "c1:supply-origin:9" {:external-warehouse-id "c1:supply-origin:9" :connection-id connection  :warehouse-type "supply-origin"
                         :warehouse-id          "home"               :state         "confirmed"}})


(defn- baseline
  {:malli/schema [:=> [:cat :string :string :string] :map]}
  [item warehouse instant]
  {:baseline-id (str connection "|" item "|" warehouse) :connection-id connection :stock-item-id  item    :warehouse-id warehouse
   :instant     instant :record-id (str "opening-" item "-" warehouse)            :established-at instant})


(def baselines
  (into {} (map (juxt :baseline-id identity))
        [(baseline "red" "home" "2026-11-01T00:00:00Z") (baseline "red" "ozon-7" "2026-11-01T00:00:00Z")
         ;; Blue's cutover is later: an operation of both items on the 10th has lines before and after.
         (baseline "blue" "home" "2026-11-20T00:00:00Z")]))


(defn- opening
  "The accepted opening a baseline names: `quantity` of `item` in `warehouse` at the baseline's instant."
  {:malli/schema [:=> [:cat :string :string :string :int] :map]}
  [item warehouse instant quantity]
  (let [rid (acceptance/record-revision-id (str "opening-" item "-" warehouse) 1)]
    {:record {:record-revision-id rid       :record-id   (str "opening-" item "-" warehouse) :revision 1 :acceptance-id (str "a-" rid)
              :kind               "opening" :occurred-at (at instant) :sequence 0 :cancelled false :recorded-at (at instant) :actor member}
     :line   {:record-line-id (acceptance/record-line-id rid 0) :record-revision-id rid :position 0 :stock-item-id item
              :warehouse-id   warehouse :quantity-change quantity :unit-cost "350.00"}}))


(defn- history
  {:malli/schema [:=> [:cat [:* :map]] :map]}
  [& openings]
  {:records (mapv :record openings) :lines (mapv :line openings)})


(def stocked
  (history (opening "red" "home" "2026-11-01T00:00:00Z" 10) (opening "red" "ozon-7" "2026-11-01T00:00:00Z" 10)
           (opening "blue" "home" "2026-11-20T00:00:00Z" 10)))


(defn- lines
  {:malli/schema [:=> [:cat [:* :any]] :string]}
  [& sku-quantities]
  (observations/write-json (vec (map-indexed (fn [i [sku quantity]]
                                               {:line-key (str (inc i)) :identifiers    [{:type "sku" :value sku}] :quantity quantity
                                                :price    "99.90"       :price-currency "RUB"})
                                             (partition 2 sku-quantities)))))


(def posting
  {:source-operation-id "c1:fbs-posting:P1" :connection-id connection             :kind           "fbs-posting" :status "delivered"
   :terminal            true                :occurred-at   "2026-11-10T09:00:00Z" :from-warehouse "c1:fbs:3"    :lines  (lines "100" "3")})


(def posting-of-3 posting)


;; Original postings of the returns, already accepted.
(def fbs-sale
  {:source-operation-id "c1:fbs-posting:P0" :connection-id connection             :kind           "fbs-posting" :status "delivered"
   :terminal            true                :occurred-at   "2026-11-05T09:00:00Z" :from-warehouse "c1:fbs:3"    :lines  (lines "100" "1")})


(def fbo-sale
  {:source-operation-id "c1:fbo-posting:F0" :connection-id connection             :kind           "fbo-posting" :status "delivered"
   :terminal            true                :occurred-at   "2026-11-05T09:00:00Z" :from-warehouse "c1:fbo:7"    :lines  (lines "100" "1")})


(def fbs-return
  {:source-operation-id "c1:return:R1"         :connection-id     connection          :kind  "return"          :return-schema "fbs" :status "returned" :terminal true
   :occurred-at         "2026-11-12T09:00:00Z" :related-operation "c1:fbs-posting:P0" :lines (lines "100" "1")})


(def fbo-return-operation
  (assoc fbs-return :source-operation-id "c1:return:R2" :return-schema "fbo" :status "ReturnedToOzon" :related-operation "c1:fbo-posting:F0"))


(def mixed
  (assoc posting :source-operation-id "c1:fbs-posting:M1" :lines (lines "100" "1" "200" "1")))


(def no-time
  (-> posting (assoc :source-operation-id "c1:fbs-posting:T1" :status "delivering" :terminal false
                     :missing (observations/write-json ["actual time"]))
      (dissoc :occurred-at)))


(def no-time-terminal
  (assoc no-time :source-operation-id "c1:fbs-posting:T2" :status "delivered" :terminal true))


(def supply-operation
  {:source-operation-id "c1:supply:S1"         :connection-id  connection           :kind         "supply"   :status "completed"       :terminal true
   :occurred-at         "2026-11-10T09:00:00Z" :from-warehouse "c1:supply-origin:9" :to-warehouse "c1:fbo:7" :lines  (lines "100" "3")})


(def before-cutover
  (assoc posting :source-operation-id "c1:fbs-posting:B1" :occurred-at "2026-10-20T09:00:00Z"))


(def cancelled-before-handover
  (-> posting (assoc :source-operation-id "c1:fbs-posting:C1" :status "cancelled" :terminal true) (dissoc :occurred-at)))


(def operations
  (into {} (map (juxt :source-operation-id identity))
        [posting fbs-sale fbo-sale fbs-return fbo-return-operation mixed no-time no-time-terminal supply-operation before-cutover cancelled-before-handover]))


(defn- baselined
  "Every listing and warehouse matched, every scope with a cutover, the connection active, the returns' sales accepted."
  {:malli/schema [:=> [:cat] :map]}
  []
  {:connection         {:connection-id connection :sync-state "active" :credentials-state "valid"}
   :products           listings
   :mappings           mappings
   :warehouse-mappings warehouse-mappings
   :baselines          baselines
   :imports            {}
   :acceptances        {"c1:fbs-posting:P0" {:source-operation-id "c1:fbs-posting:P0" :revision 1}
                        "c1:fbo-posting:F0" {:source-operation-id "c1:fbo-posting:F0" :revision 1}}
   :aside              {}
   :operations         operations
   :catalogue          catalogue
   :guard-facts        catalogue
   :history            stocked
   :entries            []
   :currency           "RUB"
   :now                (at "2026-11-25T00:00:00Z")})


(defn- edited-entry
  "The posting's pending entry as a member saved it."
  {:malli/schema [:=> [:cat :map] :map]}
  [operation]
  (cond-> {:entry-id (str "import:" (:source-operation-id operation)) :kind "sale" :status "pending" :revision 2 :saved-by member
           :lines    [{:line-id (str (:source-operation-id operation) "/1") :stock-item-id "red" :warehouse-id "home" :quantity 3}]}
    (:occurred-at operation) (assoc :occurred-at (at (:occurred-at operation)))))


(defn- edited-conflict
  {:malli/schema [:=> [:cat] :map]}
  []
  (assoc (baselined)
         :entries [(edited-entry posting)]
         :imports {(:source-operation-id posting) {:source-operation-id (:source-operation-id posting) :revision        2
                                                   :entry-id            "import:c1:fbs-posting:P1"     :source-conflict true}}))


(defn- unmatched
  {:malli/schema [:=> [:cat] :map]}
  []
  (update (baselined) :mappings dissoc "c1:mug-red"))


(defn- suspended
  {:malli/schema [:=> [:cat :map] :map]}
  [facts]
  (assoc-in facts [:mappings "c1:mug-red" :state] "suspended"))


(defn- unmatched-wh
  {:malli/schema [:=> [:cat] :map]}
  []
  (update (baselined) :warehouse-mappings dissoc "c1:fbs:3"))


(defn- unmatched-warehouse
  {:malli/schema [:=> [:cat] :map]}
  []
  (unmatched-wh))


(defn- no-baseline
  {:malli/schema [:=> [:cat] :map]}
  []
  (assoc (baselined) :baselines {}))


(defn- posting-undecided
  {:malli/schema [:=> [:cat] :map]}
  []
  (update (baselined) :acceptances dissoc "c1:fbo-posting:F0"))


(defn- balance
  {:malli/schema [:=> [:cat :int] :map]}
  [n]
  (assoc (baselined) :history (history (opening "red" "home" "2026-11-01T00:00:00Z" n) (opening "red" "ozon-7" "2026-11-01T00:00:00Z" 10))))


(defn- paused
  {:malli/schema [:=> [:cat] :map]}
  []
  (assoc-in (baselined) [:connection :sync-state] "paused"))


(defn- aside
  {:malli/schema [:=> [:cat] :map]}
  []
  (assoc (baselined) :aside {(:source-operation-id posting) {:source-operation-id (:source-operation-id posting)}}))


(defn- step
  {:malli/schema [:=> [:cat :map :map] [:maybe :string]]}
  [facts operation]
  (:next-step (steps/classify facts operation)))


(deftest the-seventeen-next-steps
  (is (= ["resolve conflict" "match product" "confirm match again" "match warehouse" "set cutover" "remove old lines"
          "waiting for Ozon" "enter missing facts" "confirm return warehouse" "accept the sale first" "fix shortage"
          "connection stopped" "accept" "automatic" "history" "set aside" "no effect"]
         steps/next-steps)))


(deftest first-that-applies
  (are [operation facts expected] (= expected (step facts operation))
    posting (edited-conflict) "resolve conflict"
    posting (unmatched) "match product"
    posting (suspended (unmatched-wh)) "confirm match again"
    posting (unmatched-warehouse) "match warehouse"
    posting (no-baseline) "set cutover"
    mixed (baselined) "remove old lines"
    no-time (baselined) "waiting for Ozon"
    no-time-terminal (baselined) "enter missing facts"
    fbs-return (baselined) "confirm return warehouse"
    fbo-return-operation (baselined) "confirm return warehouse"
    fbo-return-operation (posting-undecided) "accept the sale first"
    posting (balance 2) "fix shortage"
    posting (paused) "connection stopped"
    supply-operation (baselined) "accept"
    posting (baselined) "automatic"
    before-cutover (baselined) "history"
    posting (aside) "set aside"
    cancelled-before-handover (baselined) "no effect"))


(deftest finished-states-come-first
  (testing "set aside over every other step"
    (is (= "set aside" (step (assoc (unmatched) :aside {(:source-operation-id posting) {}}) posting)))
    (is (= "set aside" (step (assoc (balance 2) :aside {(:source-operation-id posting) {}}) posting))))
  (testing "history over a missing cutover of another scope or a stopped connection"
    (is (= "history" (step (paused) before-cutover))))
  (testing "no effect over an unmatched listing or a set-aside operation"
    (is (= "no effect" (step (unmatched) cancelled-before-handover)))
    (is (= "no effect" (step (assoc (baselined) :aside {(:source-operation-id cancelled-before-handover) {}}) cancelled-before-handover)))))


(deftest accepted-operation-has-no-next-step
  (is (nil? (steps/classify (baselined) fbs-sale)))
  (is (false? (steps/eligible? (baselined) fbs-sale))))


(defn- suggested
  {:malli/schema [:=> [:cat :map :map] [:maybe :map]]}
  [operation facts]
  (:suggested-correction (steps/classify facts operation)))


(def just-before "2026-11-10T08:59:59Z")


(deftest shortage-suggestion
  (is (= {:count 3 :instant just-before} (select-keys (suggested posting-of-3 (balance 2)) [:count :instant])))
  (is (= {:stock-item-id "red" :warehouse-id "home"} (select-keys (suggested posting-of-3 (balance 2)) [:stock-item-id :warehouse-id])))
  (testing "every later balance stays at 0 or above"
    (let [later (assoc posting :source-operation-id "c1:fbs-posting:P2" :occurred-at "2026-11-11T09:00:00Z" :lines (lines "100" "4"))
          facts (-> (balance 2)
                    (update-in [:history :records] conj
                               {:record-revision-id "s#1" :record-id "s" :revision 1 :acceptance-id "a-s" :kind "sale"
                                :occurred-at        (at "2026-11-12T00:00:00Z") :sequence 0      :cancelled false
                                :recorded-at        (at "2026-11-12T00:00:00Z") :actor    member})
                    (update-in [:history :lines] conj
                               {:record-line-id  "s#1/0" :record-revision-id "s#1" :position 0 :stock-item-id "red" :warehouse-id "home"
                                :quantity-change -1}))]
      (is (= {:count 5 :instant "2026-11-11T08:59:59Z"} (select-keys (suggested later facts) [:count :instant])))))
  (is (nil? (suggested posting (baselined))) "only for fix shortage"))


(deftest short-stock
  (let [result (steps/classify (balance 2) posting-of-3)]
    (is (= "fix shortage" (:next-step result)))
    (is (false? (steps/eligible? (balance 2) posting-of-3)))
    (is (= ["validation"] (:failing-conditions result)))))


(deftest ozon-counted-differently
  (let [{:keys [stock-item-id warehouse-id instant count]} (suggested posting-of-3 (balance 2))
        correction {:entry-id "correction-1" :kind "correction" :occurred-at (at instant) :status "pending" :revision 1 :saved-by member
                    :lines    [{:line-id "c-1" :stock-item-id stock-item-id :warehouse-id warehouse-id :quantity count}]}
        sale (assoc (edited-entry posting) :revision 1)
        facts (balance 2)
        review (acceptance/review (merge catalogue {:entries    [correction sale] :history (:history facts) :currency nil
                                                    :provenance {:cutover-entries {} :baselines baselines}
                                                    :now        (:now facts)})
                                  {:selection [{:entry-id "correction-1" :revision 1} {:entry-id (:entry-id sale) :revision 1}]
                                   :order     ["correction-1" (:entry-id sale)]})]
    (is (= [] (:problems review)) "one acceptance takes both")
    (is (= [[2 0]] (map (juxt :quantity-before :quantity-after) (:effects review))))
    (is (= [2 3 0] (keep #(when (= "home" (:warehouse-id %)) (:balance-after %)) (:steps (valuation/replay (into (:records (:history facts)) (get-in review [:records :records]))
                                                                                                           (into (:lines (:history facts)) (get-in review [:records :lines]))))))
        "the count becomes 3 just before the sale and 0 after it")))


(deftest at-the-instant-is-history
  (is (= "history" (step (baselined) (assoc posting :source-operation-id "c1:fbs-posting:I1" :occurred-at "2026-11-01T00:00:00Z"))))
  (is (= "automatic" (step (baselined) (assoc posting :source-operation-id "c1:fbs-posting:I2" :occurred-at "2026-11-01T00:00:01Z")))))


(deftest return-of-a-pre-cutover-sale-counts
  (let [old-sale (assoc fbs-sale :occurred-at "2026-10-20T09:00:00Z")
        facts    (-> (baselined)
                     (update :acceptances dissoc "c1:fbs-posting:P0")
                     (assoc-in [:operations "c1:fbs-posting:P0"] old-sale))]
    (is (= "history" (step facts old-sale)) "the sale itself is history")
    (is (= "confirm return warehouse" (step facts fbs-return)) "its return after the cutover counts")))


(deftest cutover-accepted
  (let [ops    (for [i (range 340)]
                 (assoc posting :source-operation-id (str "c1:fbs-posting:H" i) :lines (lines "100" "1")
                        :occurred-at (if (< i 300) "2026-10-20T09:00:00Z" "2026-11-10T09:00:00Z")))
        counts (frequencies (map #(step (balance 100) %) ops))]
    (is (= 300 (get counts "history")))
    (is (= 40 (reduce + (vals (dissoc counts "history")))))))


(deftest first-sync
  (let [listings (vec (for [i (range 12)]
                        {:external-product-id (str "c1:listing-" i) :connection-id connection :name (str "Listing " i)
                         :identifiers         [{:type "sku" :value (str "L" i) :active true}]}))
        ops      (vec (for [i (range 340)]
                        (assoc posting :source-operation-id (str "c1:fbs-posting:N" i) :lines (lines (str "L" (mod i 12)) "1"))))
        facts    (assoc (baselined) :products listings :mappings {} :history (history (opening "red" "home" "2026-11-01T00:00:00Z" 1000)))
        results  (map #(steps/classify facts %) ops)]
    (is (= #{"match product"} (set (map :next-step results))))
    (is (= 12 (count (distinct (map :group results)))))
    (is (= {:decision "match product" :id "c1:listing-0" :label "Match Listing 0"} (:group (first results))))
    (is (not-any? :entry-id results))
    (testing "matching one listing moves its operations to their next step"
      (let [matched (assoc-in facts [:mappings "c1:listing-0"]
                              {:external-product-id "c1:listing-0" :connection-id connection :stock-item-id "red" :state "confirmed" :mapping-revision 1
                               :identity-basis      (basis (first listings) (first stock-items))})
            steps   (frequencies (map #(step matched %) ops))]
        (is (= 29 (get steps "automatic")))
        (is (= 311 (get steps "match product")))))))


(deftest edited-entry-is-not-automatic
  (let [facts  (assoc (baselined) :entries [(edited-entry posting)])
        result (steps/classify facts posting)]
    (is (= "accept" (:next-step result)))
    (is (= "import:c1:fbs-posting:P1" (:entry-id result)))
    (is (= ["member edit"] (:failing-conditions result)))
    (is (false? (steps/eligible? facts posting))))
  (testing "the connector's own entry is"
    (let [facts (assoc (baselined) :entries [(assoc (edited-entry posting) :saved-by connector)])]
      (is (true? (steps/eligible? facts posting))))))


(deftest suspension-found-now-is-not-automatic
  (let [changed (assoc (baselined) :guard-facts (update catalogue :stock-items (partial mapv #(cond-> % (= "red" (:stock-item-id %)) (assoc :unit "kg")))))
        result  (steps/classify changed posting)]
    (is (= "confirm match again" (:next-step result)))
    (is (some #{"mapping"} (:failing-conditions result)))
    (is (false? (steps/eligible? changed posting))))
  (is (true? (steps/eligible? (assoc (baselined) :guard-facts catalogue) posting)) "an unchanged identity basis is fine"))


(deftest supply
  (is (= "accept" (step (baselined) supply-operation)) "a supply with every fact entered still waits for a member")
  (is (= ["kind"] (:failing-conditions (steps/classify (baselined) supply-operation)))))


(deftest fbo-return
  (let [result (steps/classify (baselined) fbo-return-operation)]
    (is (= "confirm return warehouse" (:next-step result)) "an FBO return received by Ozon whose sale is accepted waits for a member")
    (is (= [] (:missing result)))
    (is (some #{"kind"} (:failing-conditions result)))))


(deftest compensated-return-carries-its-finding
  (let [compensated (assoc fbs-return :source-operation-id "c1:return:R3" :status "MoneyReturned" :compensated true)
        result      (steps/classify (baselined) compensated)]
    (is (= "accept" (:next-step result)))
    (is (= ["Compensated by Ozon"] (:findings result)))))


(deftest eligibility-names-the-failing-conditions
  (are [facts operation conditions] (= conditions (:failing-conditions (steps/classify facts operation)))
    (baselined) posting []
    (paused) posting ["connection state"]
    (assoc-in (baselined) [:connection :credentials-state] "rejected") posting ["connection state"]
    (unmatched) posting ["mapping" "validation"]
    (no-baseline) posting ["baseline" "validation"]
    (baselined) before-cutover ["baseline" "validation"]
    (baselined) no-time-terminal ["completeness" "validation"]
    (aside) posting ["set aside"]
    (edited-conflict) posting ["member edit" "source conflict"]
    (balance 2) posting ["validation"])
  (testing "an archived item or warehouse"
    (is (= ["mapping" "validation"] (:failing-conditions (steps/classify (assoc-in (baselined) [:catalogue :warehouses 0 :status] "archived") posting))))))


(deftest missing-facts-never-make-an-operation-automatic
  (testing "without the Mapping Guard's facts the mapping is not known to hold"
    (let [facts (dissoc (baselined) :guard-facts)]
      (is (= ["mapping"] (:failing-conditions (steps/classify facts posting))))
      (is (false? (steps/eligible? facts posting)))))
  (testing "without the review instant a time in the future is not ruled out"
    (let [facts    (dissoc (baselined) :now)
          tomorrow (assoc posting :source-operation-id "c1:fbs-posting:F9" :occurred-at "2026-11-26T09:00:00Z")]
      (is (= ["validation"] (:failing-conditions (steps/classify facts posting))))
      (is (false? (steps/eligible? facts posting)))
      (is (= ["validation"] (:failing-conditions (steps/classify (baselined) tomorrow))) "with it, the future time fails")))
  (testing "no review ran: validation does not pass by default"
    (is (some #{"validation"} (:failing-conditions (steps/classify (unmatched) posting))))))


(deftest member-fills-the-gap
  (let [entry (-> (edited-entry no-time-terminal) (assoc :occurred-at (at "2026-11-10T09:00:00Z")))
        facts (assoc (baselined) :entries [entry])]
    (is (= [] (:missing (steps/classify facts no-time-terminal))))
    (is (= "accept" (step facts no-time-terminal)))))
