(ns tover.inventory.completion-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [tover.inventory.completion :as completion]))


(def item-line
  {:item-id "a" :name "Goods A" :quantity 2 :location "Main"})


(def complete-entries
  (let [base {:kind :operation :occurred-at "2026-01-01" :lines [item-line]}]
    [(assoc base :type :purchase :party "Supplier")
     (assoc base :type :return :direction :incoming)
     (assoc base :type :transfer :from-location "Main" :to-location "Other")
     (-> base (dissoc :lines) (assoc :type :production :inputs [item-line] :outputs [(assoc item-line :item-id "b" :name "Goods B")]))
     (assoc base :type :defect :reason "Damaged")
     (assoc base :type :sale :party "Customer")
     (assoc base :type :write-off :reason "Lost")
     {:kind  :operation :type   :payment :occurred-at "2026-01-01" :direction :outgoing
      :party "Supplier" :amount "12.34"  :currency    "EUR"}]))


(deftest all-eight-complete-operation-types-are-ready-without-stock-replay
  (doseq [entry complete-entries]
    (testing (str (:type entry))
      (is (= {:valid? true :errors []} (completion/check entry))))))


(deftest completion-reports-actionable-paths-for-all-required-payment-fields
  (let [checked (completion/check {:kind :operation :type :payment})]
    (is (false? (:valid? checked)))
    (is (= #{[:occurred-at] [:party] [:direction] [:amount] [:currency]}
           (set (map :path (:errors checked)))))
    (is (every? #(seq (:message %)) (:errors checked)))))


(deftest new-items-need-a-name-but-existing-items-can-use-their-stable-id
  (let [entry {:kind :opening :occurred-at "2026-01-01" :lines [(dissoc item-line :name)]}]
    (is (= [[:lines 0 :name]] (mapv :path (:errors (completion/check entry)))))
    (is (:valid? (completion/check entry #{"a"})))
    (is (false? (:valid? (completion/check (assoc-in entry [:lines 0 :name] "") #{}))))))


(deftest multi-line-production-and-replacement-errors-retain-their-exact-field-paths
  (let [production (-> (nth complete-entries 3)
                       (assoc :inputs [(dissoc item-line :quantity) (dissoc item-line :location)])
                       (assoc-in [:outputs 0 :quantity] "1.5"))
        checked    (completion/check {:kind :revision :target-id "op" :reason "Correct" :replacement production})]
    (is (= #{[:replacement :inputs 0 :quantity] [:replacement :inputs 1 :location] [:replacement :outputs 0 :quantity]}
           (set (map :path (:errors checked)))))))


(deftest final-normalization-keeps-optional-financial-and-operation-rules
  (is (= [:to-location]
         (-> (completion/check (assoc (nth complete-entries 2) :to-location "Main")) :errors first :path)))
  (is (= [:currency]
         (-> (completion/check (assoc (first complete-entries) :currency "eu")) :errors first :path)))
  (is (:valid? (completion/check (assoc (first complete-entries) :receipts
                                        [{:occurred-at "2026-01-01" :lines [(assoc item-line :quantity 3)]}]))))
  (is (= [:lines 0 :location]
         (-> (completion/check (-> (first complete-entries)
                                   (assoc :receipts [])
                                   (update-in [:lines 0] dissoc :location)))
             :errors first :path))))
