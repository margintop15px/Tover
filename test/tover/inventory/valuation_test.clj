(ns tover.inventory.valuation-test
  "Board chapter 13 · Accepted Stock by Product and Warehouse, Valuation: moving average per stock item and warehouse."
  (:require
    [clojure.test :refer [deftest is]]
    [tover.inventory.valuation :as valuation])
  (:import
    [java.time Instant]))


(defn- day
  {:malli/schema [:=> [:cat :int] :any]}
  [n]
  (Instant/parse (format "2026-11-%02dT00:00:00Z" n)))


(defn- record
  "One record revision with its lines `[quantity-change unit-cost]` on item `i` in warehouse `w`."
  {:malli/schema [:=> [:cat :string :map [:* :any]] :map]}
  [id {:keys [revision kind on sequence recorded cancelled] :or {revision 1 kind "move" on 1 sequence 0 recorded 1 cancelled false}}
   & lines]
  (let [rid (str id "#" revision)]
    {:record {:record-revision-id rid            :record-id id        :revision revision :kind kind :occurred-at (day on) :sequence sequence
              :recorded-at        (day recorded) :cancelled cancelled}
     :lines  (map-indexed (fn [position [quantity cost]]
                            (cond-> {:record-revision-id rid      :position position :stock-item-id "i" :warehouse-id "w"
                                     :quantity-change    quantity}
                              cost (assoc :unit-cost cost)))
                          lines)}))


(defn- replay
  {:malli/schema [:=> [:cat [:* :map]] :map]}
  [& records]
  (valuation/replay (map :record records) (mapcat :lines records)))


(defn- balance
  {:malli/schema [:=> [:cat [:* :map]] :any]}
  [& records]
  (get-in (apply replay records) [:balances ["i" "w"]]))


(deftest opening-cost-values-the-cell
  (is (== 500.00M (valuation/value (balance (record "o" {:kind "opening"} [5 "100.00"]))))))


(deftest costed-incoming-moves-the-average
  (let [b (balance (record "o" {:kind "opening"} [5 "100.00"]) (record "p" {:on 2} [5 "120.00"]))]
    (is (= 110.0000M (:average b)))
    (is (== 1100.00M (valuation/value b)))))


(deftest outgoing-keeps-the-average
  (let [b (balance (record "o" {:kind "opening"} [10 "110"]) (record "s" {:on 2} [-4]))]
    (is (= 6 (:quantity b)))
    (is (== 660.00M (valuation/value b)))))


(deftest uncosted-opening-is-unvalued
  (let [b (balance (record "o" {:kind "opening"} [5]))]
    (is (false? (:valued? b)))
    (is (nil? (valuation/value b)))))


(deftest unvalued-until-zero-then-restarts
  (let [b (balance (record "o" {:kind "opening"} [3]) (record "s" {:on 2} [-3]) (record "p" {:on 3} [2 "50"]))]
    (is (= 50.0000M (:average b)))
    (is (:valued? b))))


(deftest costed-incoming-into-unvalued-stock-stays-unvalued
  (is (false? (:valued? (balance (record "o" {:kind "opening"} [3]) (record "p" {:on 2} [2 "50"]))))))


(deftest negative-intermediate-balance-is-an-error
  (is (= {:type :negative-balance :stock-item-id "i" :warehouse-id "w" :occurred-at (day 10)}
         (:error (replay (record "o" {:kind "opening"} [5]) (record "s" {:on 10} [-5]) (record "t" {:on 5} [-2]))))))


(deftest superseded-revision-does-not-count
  (is (= 6 (:quantity (balance (record "o" {:kind "opening"} [5]) (record "o" {:kind "opening" :revision 2} [6])))))
  (is (nil? (balance (record "o" {:kind "opening"} [5]) (record "o" {:kind "opening" :revision 2 :cancelled true} [5])))
      "a cancelled latest revision removes the record"))


(deftest opening-sorts-first-at-equal-instants
  (is (= ["o" "s"]
         (map :record-id (valuation/business-order [(:record (record "s" {:recorded 1}))
                                                    (:record (record "o" {:kind "opening" :recorded 2}))]))))
  (is (nil? (:error (replay (record "s" {:recorded 1} [-2]) (record "o" {:kind "opening" :recorded 2} [5]))))))


(deftest display-amount-uses-currency-minor-units
  (is (= "1100.00" (valuation/display-amount 1100.0000M "EUR")))
  (is (= "1100" (valuation/display-amount 1100.0000M "JPY")))
  (is (= "0.12" (valuation/display-amount 0.1250M "EUR")) "half-even"))


;; 15 · Correct stock: a count sets the balance; its change is derived on every replay.


(defn- count-of
  {:malli/schema [:=> [:cat :string :int :int] :map]}
  [id on counted]
  (let [rid (str id "#1")]
    {:record {:record-revision-id rid      :record-id id    :revision 1 :kind "correction" :occurred-at (day on) :sequence 0
              :recorded-at        (day on) :cancelled false}
     :lines  [{:record-revision-id rid :position 0 :stock-item-id "i" :warehouse-id "w" :counted-quantity counted}]}))


(deftest count-down-leaves-at-the-average
  (let [b (balance (record "o" {:kind "opening"} [5 "100.00"]) (count-of "c" 2 3))]
    (is (= 3 (:quantity b)))
    (is (== 300.00M (valuation/value b)))))


(deftest count-up-enters-at-the-average
  (is (== 700.00M (valuation/value (balance (record "o" {:kind "opening"} [5 "100.00"]) (count-of "c" 2 7))))))


(deftest count-up-on-unvalued-stock-stays-unvalued
  (is (false? (:valued? (balance (record "o" {:kind "opening"} [5]) (count-of "c" 2 7))))))


(deftest count-effect-is-re-derived
  (let [steps (:steps (replay (record "o" {:kind "opening"} [5]) (count-of "late" 10 7) (count-of "early" 5 9)))]
    (is (= [["o#1" 5 5] ["early#1" 4 9] ["late#1" -2 7]] (map (juxt :record-revision-id :quantity-change :balance-after) steps)))))


(deftest count-never-excuses-an-earlier-shortage
  (is (= :negative-balance
         (:type (:error (replay (record "o" {:kind "opening"} [5]) (record "s" {:on 3} [-6]) (count-of "c" 10 4)))))))
