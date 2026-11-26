(ns tover.inventory.amendment-test
  "Board chapters 26–28 · Pending revisions, cancellations and copies of accepted operations, through review, acceptance
  and history."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.runtime :as runtime]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [tover.inventory.acceptance.model :as acceptance]
    [tover.inventory.entries.model :as entries]
    [tover.inventory.movements :as movements]
    [tover.inventory.stock.model :as stock]
    [tover.inventory.valuation :as valuation]
    [tover.support :as support])
  (:import
    [java.time Instant]))


(use-fixtures :each bel-test/isolated-registry
  (fn [f]
    (bel/build-registry! ['tover.shared.web.core 'tover.catalogues.warehouses.model 'tover.catalogues.suppliers.model
                          'tover.catalogues.stores.model 'tover.catalogues.categories.model
                          'tover.catalogues.products.model 'tover.inventory.stock-items.model
                          'tover.inventory.entries.model 'tover.inventory.acceptance.model
                          'tover.workspaces.currency.model])
    (f)))


(def workspace (:workspace-id support/scope))
(def member #uuid "00000000-0000-0000-0000-000000000011")


(defn- day
  {:malli/schema [:=> [:cat :int] :any]}
  [n]
  (Instant/parse (format "2026-11-%02dT00:00:00Z" n)))


(def catalogue
  {:products    [{:product-id "mug" :name "Mug" :status "active" :revision 1}]
   :stock-items [{:stock-item-id "red" :product-id "mug" :variant-label "red" :unit "pcs" :condition "regular" :status "active" :revision 1}
                 {:stock-item-id "red-d"  :product-id "mug" :variant-label "red · defect" :unit "pcs" :condition "defective" :regular-item-id "red"
                  :status        "active" :revision   1}]
   :barcodes    []
   :warehouses  [{:warehouse-id "main" :name "Main" :status "active" :revision 1} {:warehouse-id "back" :name "Back" :status "active" :revision 1}]
   :suppliers   [{:supplier-id "acme" :name "Acme" :status "active" :revision 1}]
   :stores      [{:store-id "web" :name "Web" :status "active" :revision 1}]
   :categories  []})


(defn- record
  "One accepted revision on day `d` with `[item warehouse change & {...}]` lines."
  {:malli/schema [:=> [:cat :string :int :string :int :map [:* :any]] :map]}
  [id revision kind d fields & lines]
  (let [rid (acceptance/record-revision-id id revision)]
    {:record (merge {:record-revision-id rid :record-id id    :revision    revision :acceptance-id                 (str "a-" rid) :kind kind :occurred-at (day d)
                     :sequence           0   :cancelled false :recorded-at (day (min 20 (+ d revision -1))) :actor member}
                    fields)
     :lines  (vec (map-indexed (fn [i [item warehouse change more]]
                                 (merge {:record-line-id (acceptance/record-line-id rid i) :record-revision-id rid :position i
                                         :stock-item-id  item :warehouse-id warehouse :quantity-change change}
                                        more))
                               lines))}))


(defn- history
  {:malli/schema [:=> [:cat [:* :map]] :map]}
  [& records]
  {:records (mapv :record records) :lines (vec (mapcat :lines records))})


(def purchase (record "p" 1 "purchase" 1 {:supplier-id "acme"} ["red" "main" 10 {:unit-cost "60.00"}]))


(defn- sale
  {:malli/schema [:=> [:cat :int :int] :map]}
  [d quantity]
  (record "s" 1 "sale" d {:store-id "web"} ["red" "main" (- quantity) {:unit-price "120.00"}]))


(defn- revision
  "The pending revision of `history`'s `amends`, with `change` applied to its facts."
  {:malli/schema [:=> [:cat :map :string :string [:=> [:cat :map] :map]] :map]}
  [history entry-id amends change]
  (change (merge (:facts (entries/latest-revision history amends))
                 {:entry-id entry-id :amends amends :status "pending" :revision 1})))


(defn- cancellation
  {:malli/schema [:=> [:cat :string :string :string] :map]}
  [entry-id kind amends]
  {:entry-id entry-id :kind kind :amends amends :cancel true :status "pending" :revision 1 :lines []})


(defn- quantity
  {:malli/schema [:=> [:cat :int] fn?]}
  [n]
  #(assoc-in % [:lines 0 :quantity] n))


(defn- facts
  {:malli/schema [:=> [:cat :map [:* :map]] :map]}
  [history & entries]
  {:scope   {:workspace-id workspace} :actor        member :now (day 20) :command-id "c" :entries (vec entries) :catalogue catalogue
   :history history                   :currency-row nil})


(defn- selected
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [entries]}]
  {:selection (mapv #(select-keys % [:entry-id :revision]) entries) :order (mapv :entry-id entries)})


(defn- review
  {:malli/schema [:=> [:cat :map] :map]}
  [facts]
  (acceptance/review (assoc (acceptance/review-input facts) :now (:now facts)) (selected facts)))


(defn- accept
  "Acceptance decided on `now` facts after a review of `reviewed` ones."
  {:malli/schema [:function [:=> [:cat :map] :map] [:=> [:cat :map :map] :map]]}
  ([facts] (accept facts facts))
  ([reviewed now]
   (runtime/decide ::acceptance/accept-reviewed-inventory-changes now
                   (merge (selected reviewed) {:workspace-id workspace :acceptance-id "acc" :fingerprint (:fingerprint (review reviewed))}))))


(defn- rejection
  {:malli/schema [:=> [:cat fn?] [:maybe :map]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (ex-data error))))


(defn- of-type
  {:malli/schema [:=> [:cat :map :keyword] [:vector :map]]}
  [plan type]
  (into [] (comp (filter #(= type (:type %))) (map :data)) (:events plan)))


(defn- shortage
  {:malli/schema [:=> [:cat :map] [:maybe :any]]}
  [facts]
  (some #(when (= :negative-balance (:code %)) ((juxt :stock-item-id :warehouse-id :occurred-at) %)) (:problems (review facts))))


;; Facts of an accepted revision


(deftest collapse-is-the-inverse-of-expand
  (doseq [entry [{:kind "purchase" :supplier-id "acme" :lines [{:stock-item-id "red" :warehouse-id "main" :quantity 3 :unit-cost "1.50"}]}
                 {:kind "sale" :store-id "web" :lines [{:stock-item-id "red" :warehouse-id "main" :quantity 3 :unit-price "2.00"}]}
                 {:kind "customer-return" :lines [{:stock-item-id "red" :warehouse-id "main" :quantity 1}]}
                 {:kind "supplier-return" :supplier-id "acme" :lines [{:stock-item-id "red" :warehouse-id "main" :quantity 1}]}
                 {:kind  "transfer" :from-warehouse-id "main" :to-warehouse-id "back"
                  :lines [{:stock-item-id "red" :quantity 2} {:stock-item-id "red-d" :quantity 1}]}
                 {:kind  "production"
                  :lines [{:stock-item-id "red" :warehouse-id "main" :quantity 2 :side "input"} {:stock-item-id "red-d" :warehouse-id "back" :quantity 1 :side "output"}]}
                 {:kind "defect" :reason "torn" :lines [{:stock-item-id "red" :warehouse-id "main" :quantity 2}]}
                 {:kind "write-off" :reason "lost" :lines [{:stock-item-id "red" :warehouse-id "main" :quantity 2}]}
                 {:kind "payment" :direction "in" :store-id "web" :amount "5.00" :lines []}
                 {:kind "opening" :reason "counted" :lines [{:stock-item-id "red" :warehouse-id "main" :quantity 5 :unit-cost "100.00"}]}
                 {:kind "opening" :lines [{:stock-item-id "red" :warehouse-id "back" :quantity 0 :unit-cost "0.00"}]}]]
    (let [lines (map-indexed #(-> %2 (assoc :position %1 :quantity-change (:change %2)) (dissoc :quantity :change))
                             (movements/expand entry (partial movements/counterpart (:stock-items catalogue))))]
      (is (= entry (update (movements/collapse (assoc entry :record-revision-id "r#1") lines) :lines (partial mapv #(dissoc % :line-id))))
          (:kind entry)))))


;; 26 and 27 · Propose


(def references
  {:warehouses  [{:id "main" :status "active"} {:id "back" :status "active"}] :products [] :categories []
   :stock-items [{:id "red" :status "active" :condition "regular"} {:id "blue" :status "active" :condition "regular"}]
   :suppliers   [{:id "acme" :status "archived"}]
   :stores      [{:id "web" :status "active"}]})


(defn- save
  {:malli/schema [:=> [:cat :map :map] :map]}
  [more input]
  (runtime/decide ::entries/save-shared-inventory-entry
                  (merge {:scope   {:workspace-id workspace} :actor member   :now (day 20) :entry nil :lines [] :changes [] :references references
                          :pending [] :history (history purchase (sale 5 4))}
                         more)
                  (merge {:workspace-id workspace} input)))


(def edit
  {:entry-id "x" :kind "purchase" :amends "p#1" :occurred-at "2026-11-01T00:00:00Z" :supplier-id "acme"
   :lines    [{:line-id "p#1-0" :stock-item-id "red" :warehouse-id "main" :quantity "8" :unit-cost "60.00"}]})


(defn- rejected
  {:malli/schema [:=> [:cat :map :map] :any]}
  [more input]
  ((juxt :type :field) (rejection #(save more input))))


(deftest edit-saves-a-pending-revision
  (let [plan (save {} edit)]
    (is (= ["p#1" "pending" 1] ((juxt :amends :status :revision) (get-in plan [:events 0 :data])))
        "the operation's archived supplier is kept")
    (is (= [8] (map :quantity (of-type plan ::entries/inventory-entry-line-saved))))))


(deftest second-edit-lands-in-the-same-entry
  (let [saved {:entry-id "x" :kind "purchase" :amends "p#1" :status "pending" :revision 1 :saved-by member}]
    (is (= 2 (get-in (save {:entry saved :pending [saved]} (assoc edit :expected-revision 1)) [:events 0 :data :revision])))
    (is (= [:stale-draft nil] (rejected {:pending [saved]} (assoc edit :entry-id "y"))) "another entry cannot amend the record too")
    (is (= [:validation :amends] (rejected {:entry saved :pending [saved]} (-> edit (assoc :expected-revision 1) (dissoc :amends))))
        "a save cannot turn it into a new operation")))


(deftest stale-target-is-rejected
  (is (= [:target-not-current nil] (rejected {:history (history purchase (record "p" 2 "purchase" 1 {:amends "p#1"} ["red" "main" 9]))} edit)))
  (is (= [:target-not-current nil] (rejected {:history (history purchase (record "p" 2 "purchase" 1 {:amends "p#1" :cancelled true}))} edit))
      "cancelled"))


(deftest only-operations-keep-their-kind
  (is (= [:validation :kind] (rejected {} (assoc edit :kind "sale" :supplier-id nil :lines []))))
  (is (= [:validation :kind] (rejected {:history (history (record "o" 1 "opening" 1 {} ["red" "main" 5]))} (assoc edit :amends "o#1")))
      "an opening stays an opening")
  (is (= [:validation :amends] (rejected {} (assoc edit :amends "nope#1")))))


(deftest cancel-saves-a-pending-cancellation
  (is (= {:entry-id "x" :kind "purchase" :amends "p#1" :cancel true :reason "entered twice" :status "pending" :revision 1 :saved-by member}
         (get-in (save {} {:entry-id "x" :kind "purchase" :amends "p#1" :cancel true :reason "entered twice"}) [:events 0 :data])))
  (is (= [:validation :cancel] (rejected {} (assoc edit :cancel true))) "it carries no other changes")
  (is (= [:validation :cancel] (rejected {} {:entry-id "x" :kind "purchase" :cancel true})) "only of an accepted operation"))


(deftest pending-edits-replaced-by-cancel
  (let [saved {:entry-id "x" :kind "purchase" :amends "p#1" :status "pending" :revision 1 :saved-by member}
        plan  (save {:entry saved :pending [saved] :lines [{:line-id "p#1-0" :stock-item-id "red" :quantity 8}]}
                    {:entry-id "x" :kind "purchase" :amends "p#1" :cancel true :expected-revision 1})]
    (is (= [{:entry-line-id "x/p#1-0"}] (of-type plan ::entries/inventory-entry-line-removed)))))


(deftest discard-leaves-the-accepted-operation
  (let [plan (runtime/decide ::entries/discard-inventory-draft
                             {:scope {:workspace-id workspace} :actor member :now (day 20)
                              :entry {:entry-id "x" :kind "purchase" :amends "p#1" :status "pending" :revision 1 :saved-by member}
                              :lines [] :changes []}
                             {:workspace-id workspace :entry-id "x" :revision 1})]
    (is (= [::entries/inventory-draft-discarded] (map :type (:events plan))))))


;; 28 · Copy


(deftest copying-a-cancelled-operation-keeps-its-references
  (is (= "acme" (get-in (save {:history (history purchase (record "p" 2 "purchase" 1 {:amends "p#1" :cancelled true}))}
                              (-> edit (dissoc :amends) (assoc :copies "p#2")))
                        [:events 0 :data :supplier-id]))))


(deftest copy-keeps-an-archived-reference
  (let [copy (-> edit (dissoc :amends) (assoc-in [:lines 0 :line-id] "x-0"))]
    (is (= [:validation :supplier-id] (rejected {} copy)) "an ordinary new purchase cannot choose it")
    (doseq [source ["p#1" "pending"]]
      (let [saved (get-in (save {:pending [{:entry-id "pending" :kind "purchase" :supplier-id "acme" :lines []}]} (assoc copy :copies source)) [:events 0 :data])]
        (is (= "acme" (:supplier-id saved)))
        (is (not-any? saved [:copies :amends]) "nothing links the copy to its source")))
    (is (= [{:line-id nil :field :supplier-id :code :archived-reference}]
           (entries/blockers {:kind "purchase" :occurred-at (day 9) :supplier-id "acme"}
                             [{:line-id "x-0" :stock-item-id "red" :warehouse-id "main" :quantity 10 :unit-cost "60.00"}]
                             {:suppliers {"acme" "archived"} :stock-items {"red" "active"} :warehouses {"main" "active"}})))))


;; 26 · Review


(deftest lower-quantity-breaks-a-later-sale
  (let [h (history purchase (sale 5 9))]
    (is (= ["red" "main" (day 5)] (shortage (facts h (revision h "x" "p#1" (quantity 8))))))))


(deftest moving-a-purchase-later-breaks-a-sale
  (let [h (history purchase (sale 5 9))]
    (is (= ["red" "main" (day 5)] (shortage (facts h (revision h "x" "p#1" #(assoc % :occurred-at (day 6)))))))))


(deftest price-change-moves-the-average
  (let [h (history (record "o" 1 "opening" 1 {} ["red" "main" 10 {:unit-cost "100.00"}])
                   (record "p" 1 "purchase" 2 {:supplier-id "acme"} ["red" "main" 10 {:unit-cost "60.00"}]))
        r (review (facts h (revision h "x" "p#1" #(assoc-in % [:lines 0 :unit-cost] "80.00"))))]
    (is (:valid? r))
    (is (= [[20 20 1600.0000M 1800.0000M]] (map (juxt :quantity-before :quantity-after :value-before :value-after) (:effects r)))
        "the average goes from 80.00 to 90.00")))


(deftest review-outdated-by-another-revision
  (let [h     (history purchase)
        entry (revision h "x" "p#1" (quantity 8))]
    (is (= :review-outdated (:type (rejection #(accept (facts h entry)
                                                       (facts (history purchase (record "p" 2 "purchase" 1 {:amends "p#1"} ["red" "main" 9])) entry))))))
    (is (= [:target-not-current]
           (map :code (:problems (review (facts (history purchase (record "p" 2 "purchase" 1 {:amends "p#1"} ["red" "main" 9])) entry)))))
        "a new review names it")))


(deftest a-cancellation-elsewhere-outdates-a-review
  (let [h    (history purchase (record "q" 1 "purchase" 2 {:supplier-id "acme"} ["red" "main" 5 {:unit-cost "10.00"}]))
        edit (revision h "x" "p#1" (quantity 12))]
    (is (= :review-outdated
           (:type (rejection #(accept (facts h edit)
                                      (facts (update h :records conj (:record (record "q" 2 "purchase" 2 {:amends "q#1" :cancelled true}))) edit))))))))


(deftest an-already-cancelled-target-is-named
  (let [h (history purchase (record "p" 2 "purchase" 1 {:amends "p#1" :cancelled true}))]
    (is (= [:already-cancelled] (map :code (:problems (review (facts h (cancellation "x" "purchase" "p#1")))))))))


(deftest a-revision-keeps-its-place-among-equal-instants
  (let [h (history purchase (assoc-in (sale 1 9) [:record :sequence] 1))]
    (is (:valid? (review (facts h (revision h "x" "p#1" (quantity 9))))) "recorded later, but still before the sale")))


(deftest an-amended-purchase-may-move-before-its-old-instant
  (let [h (history (record "o" 1 "opening" 2 {} ["red" "main" 1 {:unit-cost "1.00"}])
                   (record "p" 1 "purchase" 3 {:supplier-id "acme"} ["red" "main" 10 {:unit-cost "60.00"}]))]
    (is (= [:opening-not-first] (map :code (:problems (review (facts h (revision h "x" "p#1" #(assoc % :occurred-at (day 1)))))))))))


;; 26 · Accept


(deftest accept-a-revision
  (let [h    (history purchase)
        plan (accept (facts h (revision h "x" "p#1" (quantity 8))))]
    (is (= [{:record-revision-id "p#2" :record-id     "p"   :revision    2      :amends "p#1" :cancelled false :kind "purchase" :occurred-at (day 1)
             :sequence           0     :acceptance-id "acc" :supplier-id "acme"}]
           (of-type plan ::acceptance/operation-amended)))
    (is (= [["p#2/0" "p#2" 8]] (map (juxt :record-line-id :record-revision-id :quantity-change) (of-type plan ::acceptance/inventory-record-line-accepted))))
    (is (empty? (of-type plan ::acceptance/inventory-record-accepted)))
    (is (= [{:entry-id "x" :status "accepted" :revision 2}] (of-type plan ::entries/shared-inventory-entry-accepted)) "the entry leaves pending")))


(deftest the-submit-reason-is-the-revisions-reason
  (let [h (history purchase)]
    (is (= ["miscounted"] (map :reason (of-type (accept (facts h (revision h "x" "p#1" #(assoc % :reason "miscounted")))) ::acceptance/operation-amended))))))


(deftest revision-and-new-operation-together
  (let [h    (history purchase)
        sale {:entry-id "n" :kind "sale" :occurred-at (day 6) :status "pending" :revision 1
              :lines    [{:line-id "n-0" :stock-item-id "red" :warehouse-id "main" :quantity 12}]}
        plan (accept (facts h (revision h "x" "p#1" (quantity 12)) sale))]
    (is (= [1 1] (map #(count (of-type plan %)) [::acceptance/operation-amended ::acceptance/inventory-record-accepted])))))


;; 27 · Review and accept


(deftest sold-stock-blocks-cancelling-its-purchase
  (is (= ["red" "main" (day 5)] (shortage (facts (history purchase (sale 5 4)) (cancellation "x" "purchase" "p#1"))))))


(deftest defect-stock-already-written-off
  (let [h (history purchase
                   (record "d" 1 "defect" 2 {:reason "torn"} ["red" "main" -3] ["red-d" "main" 3 {:carry-from 0}])
                   (record "w" 1 "write-off" 4 {:reason "bin"} ["red-d" "main" -3]))]
    (is (= ["red-d" "main" (day 4)] (shortage (facts h (cancellation "x" "defect" "d#1")))))))


(deftest cancel-a-sale
  (let [r (review (facts (history purchase (sale 5 4)) (cancellation "x" "sale" "s#1")))]
    (is (:valid? r))
    (is (= [[6 10 360.0000M 600.0000M]] (map (juxt :quantity-before :quantity-after :value-before :value-after) (:effects r))))))


(deftest cancel-a-payment
  (let [plan (accept (facts (history (record "pay" 1 "payment" 3 {:direction "out" :supplier-id "acme" :amount "5.00"})) (cancellation "x" "payment" "pay#1")))]
    (is (= ["pay#2"] (map :record-revision-id (of-type plan ::acceptance/operation-cancelled))))
    (is (empty? (of-type plan ::acceptance/inventory-record-line-accepted)))))


(deftest accept-a-cancellation
  (let [plan (accept (facts (history purchase) (assoc (cancellation "x" "purchase" "p#1") :reason "entered twice")))]
    (is (= [{:record-revision-id "p#2" :record-id     "p"   :revision 2               :amends "p#1" :cancelled true :kind "purchase" :occurred-at (day 1)
             :sequence           0     :acceptance-id "acc" :reason   "entered twice"}]
           (of-type plan ::acceptance/operation-cancelled)))
    (is (= #{::acceptance/inventory-changes-accepted ::acceptance/operation-cancelled ::entries/shared-inventory-entry-accepted}
           (set (map :type (:events plan))))
        "nothing it registered is touched")))


;; 26 and 27 · Read the result


(def amended (record "p" 2 "purchase" 3 {:supplier-id "acme" :amends "p#1" :reason "miscounted"} ["red" "main" 8 {:unit-cost "60.00"}]))
(def cancelled (record "p" 2 "purchase" 1 {:amends "p#1" :cancelled true :reason "entered twice"}))


(deftest latest-values-and-revisions-are-read
  (let [[op :as ops] (acceptance/operations (history purchase amended))]
    (is (= 1 (count ops)))
    (is (= ["p#2" 8 480M] [(:record-revision-id op) (:quantity-change (first (:lines op))) (.stripTrailingZeros ^BigDecimal (:total op))]))
    (is (= [[1 10 nil] [2 8 "miscounted"]] (map (juxt :revision (comp :quantity-change first :lines) :reason) (:revisions op))))))


(deftest a-cancelled-operation-stays-listed
  (let [[op] (acceptance/operations (history purchase cancelled))]
    (is (= [true "p#2" 10] [(:cancelled op) (:record-revision-id op) (:quantity-change (first (:lines op)))]))
    (is (= [[1 false nil] [2 true "entered twice"]] (map (juxt :revision :cancelled :reason) (:revisions op))))))


(defn- rows
  {:malli/schema [:=> [:cat :map] [:sequential :any]]}
  [history]
  (map (juxt :kind :occurred-at :quantity-change :balance-after :cancelled?) (:rows (stock/history-rows {:history history} {:stock-item-id "red"}))))


(deftest history-counts-once
  (is (= [["purchase" (day 3) 8 8 nil]] (rows (history purchase amended)))))


(deftest history-marks-the-cancelled-row
  (is (= [["purchase" (day 1) 10 nil true] ["customer-return" (day 4) 1 1 nil]]
         (rows (history (record "r" 1 "customer-return" 4 {} ["red" "main" 1]) purchase cancelled)))))


;; Pending work around an amended operation


(deftest a-pending-revision-counts-the-difference
  (let [read (fn [h & pending]
               ((get-in (bel/operations) [:read-models ::entries/pending-entries :handler]) {:pending (vec pending) :catalogue catalogue :history h} {}))
        h    (history purchase (sale 5 4))]
    (testing "a sale raised to all the stock is not short"
      (let [{[e] :entries findings :findings} (read h (revision h "x" "s#1" (quantity 10)))]
        (is (empty? findings))
        (is (= [-4] (map :quantity-change (:replaces e))))))
    (is (= #{:insufficient-stock} (set (map :code (:findings (read h (revision h "x" "s#1" (quantity 11))))))))
    (testing "a superseded target blocks"
      (is (= [:target-not-current]
             (map :code (:blockers (first (:entries (read (history purchase amended) (revision (history purchase) "x" "p#1" (quantity 9))))))))))))


;; 14 · Opening amendment


(def opening (record "o" 1 "opening" 1 {} ["red" "main" 5 {:unit-cost "100.00"}]))


(defn- amend
  "Facts of a review of the pending amendment of `opening` in `history`: line fields of `change` apply to its line,
  `:occurred-at` to the entry."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [history change]
  (facts history (revision history "x" "o#1" #(-> %
                                                  (update-in [:lines 0] merge (dissoc change :occurred-at))
                                                  (merge (select-keys change [:occurred-at]))))))


(defn- accepted
  "`history` with the records and lines `facts`' acceptance adds."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [history facts]
  (let [plan (accept facts)]
    (-> history
        (update :records into (map #(assoc % :recorded-at (day 20) :actor member))
                (concat (of-type plan ::acceptance/inventory-record-accepted) (of-type plan ::acceptance/operation-amended)))
        (update :lines into (of-type plan ::acceptance/inventory-record-line-accepted)))))


(defn- balance
  {:malli/schema [:=> [:cat :map :string] [:maybe [:tuple :int decimal?]]]}
  [history warehouse]
  (when-let [b (get-in (valuation/replay (:records history) (:lines history)) [:balances ["red" warehouse]])]
    [(:quantity b) (valuation/value b)]))


(defn- codes
  {:malli/schema [:=> [:cat :map] [:set :keyword]]}
  [facts]
  (set (map :code (:problems (review facts)))))


(def opening-save
  {:entry-id "x" :kind "opening" :amends "o#1" :occurred-at "2026-11-01T00:00:00Z"
   :lines    [{:line-id "o#1-0" :stock-item-id "red" :warehouse-id "main" :quantity "5" :unit-cost "120.00"}]})


(deftest an-opening-amendment-saves
  (is (= ["opening" "o#1" "pending"] ((juxt :kind :amends :status) (get-in (save {:history (history opening)} opening-save) [:events 0 :data])))))


(deftest wrong-cost
  (let [h     (history opening)
        after (accepted h (amend h {:unit-cost "120.00"}))]
    (is (= [5 600.0000M] (balance after "main")))
    (is (= [["opening" 2 false] ["opening" 1 true]]
           (map (juxt :kind :revision :superseded?) (:rows (stock/history-rows {:history after} {:stock-item-id "red"}))))
        "history lists both revisions")
    (is (= [["o#2" "o#1" 2 "opening"]]
           (map (juxt :record-revision-id :amends :revision :kind) (of-type (accept (amend h {:unit-cost "120.00"})) ::acceptance/operation-amended))))))


(deftest amended-below-later-sales
  (let [f (amend (history opening (sale 5 4)) {:quantity 3})]
    (is (= ["red" "main" (day 5)] (shortage f)) "names the item, warehouse and sale date")
    (is (= :negative-balance (:type (rejection #(accept f)))) "nothing is accepted")))


(deftest openings-cannot-be-cancelled
  (is (= [:validation :cancel] (rejected {:history (history opening)} {:entry-id "x" :kind "opening" :amends "o#1" :cancel true}))))


(deftest the-stock-item-does-not-change
  (is (= [:validation :stock-item-id] (rejected {:history (history opening)} (assoc-in opening-save [:lines 0 :stock-item-id] "blue"))))
  (is (= [:validation :stock-item-id] (rejected {:history (history opening)} (update opening-save :lines conj {:line-id "n" :stock-item-id "blue" :warehouse-id "main" :quantity "1" :unit-cost "1.00"})))
      "nor does a line for another item join it")
  (is (= [:validation :stock-item-id] (rejected {:history (history opening)} (assoc opening-save :lines [])))
      "nor does its line go"))


(deftest corrections-stay-unamendable
  (is (= [:validation :amends]
         (rejected {:history (history opening (record "c" 1 "correction" 3 {} ["red" "main" nil {:counted-quantity 4}]))}
                   {:entry-id "x" :kind "correction" :amends "c#1" :occurred-at "2026-11-03T00:00:00Z"
                    :lines    [{:line-id "c#1-0" :stock-item-id "red" :warehouse-id "main" :quantity "3"}]}))))


(deftest moved-before-another-record
  (is (contains? (codes (amend (history opening (sale 5 4)) {:occurred-at (day 6)})) :opening-not-first)
      "the sale would come before the opening"))


(deftest amending-is-not-a-second-opening
  (let [r (review (amend (history opening (sale 5 4)) {:quantity 6 :occurred-at (day 2)}))]
    (is (:valid? r) (pr-str (:problems r)))
    (is (= [[1 2]] (map (juxt :quantity-before :quantity-after) (:effects r))) "after the sale of 4")))


(deftest changing-the-warehouse-moves-the-opening
  (let [h (history opening)]
    (let [after (accepted h (amend h {:warehouse-id "back"}))]
      (is (= [nil [5 500.0000M]] [(balance after "main") (balance after "back")]) "main loses the opening, back gains it"))
    (is (contains? (codes (amend (history opening (record "b" 1 "opening" 1 {} ["red" "back" 2 {:unit-cost "1.00"}])) {:warehouse-id "back"}))
                   :second-opening)
        "back already has an opening")
    (is (contains? (codes (amend (history opening (record "q" 1 "purchase" 1 {:supplier-id "acme"} ["red" "back" 2 {:unit-cost "1.00"}]))
                                 {:warehouse-id "back" :occurred-at (day 2)}))
                   :opening-not-first)
        "back has an earlier record")
    (is (= ["red" "main" (day 5)] (shortage (amend (history opening (sale 5 4)) {:warehouse-id "back"})))
        "the sale in main loses its stock")))


(deftest an-amended-opening-stays-first-among-equal-instants
  (let [h     (history opening (record "q" 1 "purchase" 3 {:supplier-id "acme"} ["red" "main" 2 {:unit-cost "10.00"}]))
        f     (amend h {:occurred-at (day 3)})
        after (accepted h f)]
    (is (empty? (:problems (review f))) "no order is needed and the opening is not after the purchase")
    (is (= ["opening" "purchase"] (map :kind (valuation/business-order (:records after)))))))


(deftest a-second-amendment-of-an-opening-is-stale
  (is (= [:stale-draft nil]
         (rejected {:history (history opening) :pending [{:entry-id "y" :kind "opening" :amends "o#1" :status "pending" :revision 1}]}
                   opening-save))))
