(ns tover.inventory.ledger-test
  (:require
    [clojure.test :refer [deftest is]]
    [tover.inventory.ledger :as ledger]))


(defn line
  {:malli/schema [:function [:=> [:cat :string :any] :map] [:=> [:cat :string :any :string] :map]]}
  ([item quantity] (line item quantity "Main"))
  ([item quantity location]
   {:item-id item :name item :quantity quantity :location location}))


(defn entry
  {:malli/schema [:=> [:cat :string :string :map] :map]}
  [id date fields]
  (assoc (ledger/normalize-entry (merge {:kind :operation :occurred-at date} fields))
         :id id :revision 1 :business-order (Integer/parseInt id)
         :actor "tester" :recorded-at "2026-10-21T00:00:00Z"))


(defn opening
  {:malli/schema [:=> [:cat :string :string [:* :map]] :map]}
  [id date & lines]
  (entry id date {:kind :opening :lines (vec lines)}))


(deftest unused-blank-line-locations-do-not-create-invalid-location-resources
  (let [transfer (ledger/normalize-entry {:kind          :operation :type    :transfer   :occurred-at "2026-01-01"
                                          :from-location "Main" :to-location "Secondary"
                                          :lines         [{:item-id "material" :quantity 1 :location ""}]})
        purchase (ledger/normalize-entry {:kind     :operation :type  :purchase :occurred-at "2026-01-01"
                                          :party    "Supplier" :lines [{:item-id "material" :quantity 1 :location ""}]
                                          :receipts [{:occurred-at "2026-01-02" :lines [{:item-id "material" :quantity 1 :location "Main"}]}]})]
    (is (not (contains? (first (:lines transfer)) :location)))
    (is (not (contains? (first (:lines purchase)) :location)))
    (is (= "Main" (get-in purchase [:receipts 0 :lines 0 :location])))))


(defn quantity
  {:malli/schema [:=> [:cat :map :string :string] :int]}
  [projection item location]
  (get-in projection [:balances [item location]] 0))


(defn failure
  {:malli/schema [:=> [:cat [:=> [:cat] :any]] [:maybe :map]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (ex-data error))))


(deftest all-eight-operation-effects-and-money-remain-commercial-facts
  (let [entries    [(opening "1" "2026-01-01" (line "a" 50))
                    (entry "2" "2026-01-02" {:type :purchase :party "Supplier 1" :amount "100.25" :currency "EUR" :lines [(assoc (line "a" 10) :amount "100.25")]})
                    (entry "3" "2026-01-03" {:type :return :direction :incoming :lines [(line "a" 2)]})
                    (entry "4" "2026-01-04" {:type :return :direction :outgoing :lines [(line "a" 3)]})
                    (entry "5" "2026-01-05" {:type :transfer :from-location "Main" :to-location "Shop" :lines [(line "a" 20)]})
                    (entry "6" "2026-01-06" {:type :production :inputs [(line "a" 4)] :outputs [(line "b" 2)]})
                    (entry "7" "2026-01-07" {:type :defect :reason "Damaged" :lines [(line "a" 1)]})
                    (entry "8" "2026-01-08" {:type :sale :party "Customer" :lines [(line "a" 5)]})
                    (entry "9" "2026-01-09" {:type :write-off :reason "Lost" :lines [(line "a" 2)]})
                    (entry "10" "2026-01-10" {:type :payment :party "Supplier 1" :amount "0.10" :currency "EUR" :direction :outgoing})]
        projection (ledger/replay entries)]
    (is (= 27 (quantity projection "a" "Main")))
    (is (= 20 (quantity projection "a" "Shop")))
    (is (= 2 (quantity projection "b" "Main")))
    (is (= 100.25M (:amount (second entries))))
    (is (= 0.10M (:amount (last entries))))
    (is (empty? (filter #(= :payment (:type %)) (:records projection))))))


(deftest partial-purchase-arrivals-replay-at-their-own-dates
  (let [purchase   (entry "1" "2026-01-01" {:type     :purchase :party "Supplier" :currency "EUR"
                                            :lines    [{:item-id "a" :name "Item" :quantity 10 :amount "25.10"}]
                                            :receipts [{:occurred-at "2026-01-03" :lines [(line "a" 3 "Dock")]}
                                                       {:occurred-at "2026-01-06" :lines [(line "a" 2 "Shop")]}]})
        projection (ledger/replay [purchase])]
    (is (= 3 (quantity projection "a" "Dock")))
    (is (= 2 (quantity projection "a" "Shop")))
    (is (= ["2026-01-03T00:00:00Z" "2026-01-06T00:00:00Z"] (mapv :occurred-at (:records projection))))
    (is (= 25.10M (get-in purchase [:lines 0 :amount])))
    (is (empty? (:rows (ledger/replay [(assoc purchase :receipts [])]))))
    (is (= :validation (:type (failure #(ledger/normalize-entry
                                          (assoc-in purchase [:receipts 1 :lines 0 :quantity] 8))))))))


(deftest purchase-cannot-fund-a-sale-before-arrival
  (let [purchase (entry "1" "2026-01-01" {:type     :purchase :party "Supplier"
                                          :lines    [(line "a" 10)]
                                          :receipts [{:occurred-at "2026-01-05" :lines [(line "a" 10)]}]})
        sale     (entry "2" "2026-01-03" {:type :sale :party "Customer" :lines [(line "a" 1)]})
        error    (failure #(ledger/replay [purchase sale]))]
    (is (= "2" (get-in error [:errors 0 :operation-id])))
    (is (= -1 (get-in error [:errors 0 :after])))))


(deftest transfers-preserve-quantity-and-do-not-create-source-stock
  (let [a        (opening "1" "2026-01-01" (line "a" 4))
        transfer (entry "2" "2026-01-02" {:type :transfer :from-location "Main" :to-location "Shop" :lines [(line "a" 4)]})
        result   (ledger/replay [a transfer])]
    (is (= 4 (reduce + (vals (:balances result)))))
    (is (= 0 (quantity result "a" "Main")))
    (is (= :validation (:type (failure #(ledger/replay [transfer])))))))


(deftest production-supports-multiple-inputs-outputs-partial-use-and-repacking
  (let [initial (opening "1" "2026-01-01" (line "a" 20) (line "b" 10))
        pack    (entry "2" "2026-01-02" {:type    :production
                                         :inputs  [(line "a" 6) (line "b" 2)]
                                         :outputs [(line "kit" 2) (line "offcut" 1)]})
        unpack  (entry "3" "2026-01-03" {:type    :production
                                         :inputs  [(line "kit" 1)]
                                         :outputs [(line "a" 3) (line "b" 1)]})
        repack  (entry "4" "2026-01-04" {:type    :production
                                         :inputs  [(line "kit" 1) (line "offcut" 1)]
                                         :outputs [(line "bundle" 1)]})
        result  (ledger/replay [initial pack unpack repack])]
    (is (= {"a" 17 "b" 9 "kit" 0 "offcut" 0 "bundle" 1}
           (into {} (map (juxt :item-id :quantity)) (:rows result))))
    (is (= 3 (count (:lineage result))))
    (is (= 2 (count (:inputs (first (:lineage result))))))
    (is (= 2 (count (:outputs (first (:lineage result))))))))


(deftest repeated-transformations-have-dated-lineage-without-unit-attribution
  (let [result  (ledger/replay
                  [(opening "1" "2026-01-01" (line "a" 10))
                   (entry "2" "2026-01-02" {:type :production :inputs [(line "a" 4)] :outputs [(line "b" 2)]})
                   (entry "3" "2026-01-03" {:type :production :inputs [(line "b" 1)] :outputs [(line "a" 2)]})
                   (entry "4" "2026-01-04" {:type :production :inputs [(line "a" 1)] :outputs [(line "c" 1)]})])
        history (ledger/item-history result "c" true)]
    (is (= ["2" "3" "4"] (mapv :operation-id (:lineage history))))
    (is (= ["c"] (mapv :item-id (:records history))))
    (is (= 7 (quantity result "a" "Main")))
    (is (not-any? #(some (fn [line] (or (:origin-id line) (:unit-id line) (:source-receipt line)))
                         (concat (:inputs %) (:outputs %))) (:lineage history)))))


(deftest lineage-does-not-pull-future-transformations-into-ancestors
  (let [result (ledger/replay
                 [(opening "1" "2026-01-01" (line "a" 10))
                  (entry "2" "2026-01-02" {:type :production :inputs [(line "a" 1)] :outputs [(line "b" 1)]})
                  (entry "3" "2026-01-03" {:type :production :inputs [(line "b" 1)] :outputs [(line "c" 1)]})
                  (entry "4" "2026-01-04" {:type :production :inputs [(line "a" 1)] :outputs [(line "b" 1)]})])]
    (is (= ["2" "3"] (mapv :operation-id (:lineage (ledger/item-history result "c" true)))))))


(deftest production-inputs-are-checked-before-same-operation-outputs
  (let [production (entry "1" "2026-01-01" {:type :production :inputs [(line "a" 2)] :outputs [(line "a" 3)]})]
    (is (= :validation (:type (failure #(ledger/replay [production])))))))


(deftest chronological-order-is-independent-of-vector-order
  (let [opening (opening "2" "2026-01-01" (line "a" 5))
        sale    (entry "1" "2026-01-02" {:type :sale :party "Customer" :lines [(line "a" 3)]})]
    (is (= (ledger/replay [opening sale]) (ledger/replay [sale opening])))))


(deftest same-time-business-order-is-deterministic
  (let [opening (opening "1" "2026-01-01" (line "a" 5))
        sale    (entry "2" "2026-01-01" {:type :sale :party "Customer" :lines [(line "a" 3)]})]
    (is (= 2 (quantity (ledger/replay [sale opening]) "a" "Main")))
    (is (= :validation (:type (failure #(ledger/replay [(assoc opening :business-order 3) sale])))))))


(deftest manual-correction-counts-and-article-change-preserve-item-history
  (let [result (ledger/replay
                 [(opening "1" "2026-01-01" (assoc (line "a" 10) :article "OLD"))
                  (entry "2" "2026-01-02" {:kind :correction :reason "Counted again" :lines [(assoc (line "a" 7) :article "NEW")]})])]
    (is (= 7 (quantity result "a" "Main")))
    (is (= "NEW" (get-in result [:items "a" :article])))
    (is (= [10 -3] (mapv :delta (:records (ledger/item-history result "a" false)))))))


(deftest validation-rejects-fractional-negative-overflow-and-floating-money
  (doseq [quantity [-1 1.5 1/2 (inc (bigint Long/MAX_VALUE)) "1.5" "-1"]]
    (is (= :validation (:type (failure #(opening "1" "2026-01-01" (line "a" quantity)))))))
  (doseq [amount [0.1 ##NaN "NaN" "-1.00" "1e3"]]
    (is (= :validation (:type (failure #(entry "1" "2026-01-01" {:type     :payment :party  "P"    :direction :incoming
                                                                 :currency "EUR"    :amount amount})))))))


(deftest suppliers-share-one-item-and-do-not-create-receipt-allocation
  (let [result (ledger/replay
                 [(entry "1" "2026-01-01" {:type :purchase :party "S1" :lines [(line "a" 3)]})
                  (entry "2" "2026-01-02" {:type :purchase :party "S2" :lines [(line "a" 4)]})
                  (entry "3" "2026-01-03" {:type :sale :party "C" :lines [(line "a" 5)]})])]
    (is (= 1 (count (:rows result))))
    (is (= 2 (quantity result "a" "Main")))
    (is (= ["S1" "S2" "C"] (mapv :party (:records result))))))


(deftest explicit-metadata-corrections-outrank-passive-operation-snapshots
  (let [initial         (opening "1" "2026-01-01" (assoc (line "a" 10) :article "OLD"))
        corrected       (assoc (entry "2" "2026-01-02" {:kind :correction :reason "Correct article" :lines [(assoc (line "a" 8) :article "NEW")]}) :accepted-order 3)
        historical-sale (assoc (entry "3" "2026-01-03" {:type :sale :party "C" :lines [(assoc (line "a" 1) :article "OLD")]}) :accepted-order 2)
        result          (ledger/replay [initial corrected historical-sale])]
    (is (= "NEW" (get-in result [:items "a" :article])))
    (is (= 7 (quantity result "a" "Main")))))


(deftest metadata-uses-accepted-correction-order-while-quantities-use-business-time
  (let [initial          (opening "1" "2026-01-01" (assoc (line "a" 10) :article "OLD"))
        first-correction (assoc (entry "2" "2026-01-03" {:kind :correction :reason "First count" :lines [(assoc (line "a" 10) :article "FIRST")]}) :accepted-order 2)
        later-backdate   (assoc (entry "3" "2026-01-02" {:kind :correction :reason "Backdated detail" :lines [(assoc (line "a" 8) :article "LATEST")]}) :accepted-order 3)
        later-snapshot   (assoc (entry "4" "2026-01-04" {:type :purchase :party "S" :lines [(assoc (line "a" 1) :article "OLD")]}) :accepted-order 4)
        result           (ledger/replay [initial later-backdate first-correction later-snapshot])]
    (is (= "LATEST" (get-in result [:items "a" :article])))
    (is (= 11 (quantity result "a" "Main")))))
