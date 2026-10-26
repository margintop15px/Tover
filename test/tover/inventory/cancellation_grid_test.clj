(ns tover.inventory.cancellation-grid-test
  (:require
    [clojure.test :refer [deftest is]]
    [tover.inventory.grid :as grid]
    [tover.inventory.store :as store]
    [tover.inventory.workbench :as workbench]
    [tover.support :as support]))


(defn- cell
  {:malli/schema [:=> [:cat :map :string :string] [:maybe :map]]}
  [model id key]
  (let [column (first (keep-indexed #(when (= key (:key %2)) %1) (:columns model)))
        row    (some #(when (= id (:groupId %)) %) (:rows model))]
    (when (some? column) (get-in row [:cells column]))))


(defn- cancellation
  {:malli/schema [:=> [:cat :string :string] :map]}
  [id target]
  {:id id :revision 1 :entry {:kind :cancellation :target-id target :reason "Duplicate"}})


(deftest cancellation-payment-and-return-target-fields-are-readonly
  (let [purchase {:id "purchase" :kind :operation :type :purchase :party "Supplier"}
        payment  {:id          "payment"              :kind   :operation :type     :payment :party                 "Supplier" :direction :outgoing
                  :occurred-at "2026-01-02T00:00:00Z" :amount "12.34"    :currency "EUR"    :original-operation-id "purchase"}
        returned {:id          "return"               :kind                  :operation :type :return :party "Supplier" :direction :outgoing
                  :occurred-at "2026-01-03T00:00:00Z" :original-operation-id "purchase"}
        model    (grid/drafts {:drafts     [(cancellation "cancel-payment" "payment") (cancellation "cancel-return" "return")]
                               :operations [purchase payment returned] :operation-catalog [purchase payment returned] :items {}})]
    (doseq [[id type] [["cancel-payment" "Cancellation · Payment"] ["cancel-return" "Cancellation · Return"]]]
      (is (= type (:value (cell model id "type"))))
      (doseq [key ["direction" "original-operation-id" "party" "occurred-at" "type"]]
        (is (nil? (:target (cell model id key))))
        (is (nil? (:editor (cell model id key)))))
      (is (= "outgoing" (:value (cell model id "direction"))))
      (is (= "purchase" (:value (cell model id "original-operation-id"))))
      (is (= "reason" (get-in (cell model id "reason") [:target :path])))
      (is (= id (get-in (cell model id "select") [:selection :id])))
      (is (= ["discard"] (mapv :command (:actions (cell model id "actions"))))))))


(deftest filters-do-not-hide-cancellation-targets-or-reference-options
  (support/with-application
    (fn [{:keys [repo users] :as context}]
      (let [context  (assoc context :user-id (:user-id (first users)))
            actor    (str (:user-id (first users)))
            save     (fn [entry] (store/save-entry! repo context {:actor actor :entry entry}))
            purchase (save {:kind  :operation :type :purchase :party "Supplier" :occurred-at "2026-01-01"
                            :lines [{:item-id "goods" :name "Goods" :unit "pcs" :location "Shelf" :quantity 5}]})
            payment  (save {:kind        :operation   :type                  :payment       :party "Supplier" :direction :outgoing :amount "12.34" :currency "EUR"
                            :occurred-at "2026-01-02" :original-operation-id (:id purchase)})
            returned (save {:kind  :operation :type :return :party "Customer" :direction :incoming :occurred-at "2026-01-03"
                            :lines [{:item-id "goods" :name "Goods" :unit "pcs" :location "Shelf" :quantity 1}]})]
        (store/submit! repo context {:actor    actor :command-id "accepted-operations" :expected-ledger-revision 0
                                     :selected (mapv #(select-keys % [:id :revision]) [purchase payment returned])})
        (let [cancel-payment (save (:entry (cancellation "unused" (:id payment))))
              cancel-return  (save (:entry (cancellation "unused" (:id returned))))
              new-payment    (save {:kind        :operation   :type                  :payment       :party "Supplier" :direction :outgoing :amount "1.00" :currency "EUR"
                                    :occurred-at "2026-01-04" :original-operation-id (:id purchase)})]
          (doseq [input [{:view "operations" :operationType "purchase"}
                         {:view "stock" :filters {"name" [{:id "goods" :type "begins" :value "Goods"}]}}]]
            (let [model (:model (workbench/query-stock context input))
                  draft (grid/drafts model)]
              (is (= #{(:id purchase) (:id payment) (:id returned)} (set (map :id (:operation-catalog model)))))
              (is (= "Cancellation · Payment" (:value (cell draft (:id cancel-payment) "type"))))
              (is (= "Cancellation · Return" (:value (cell draft (:id cancel-return) "type"))))
              (is (= "12.34" (:value (cell draft (:id cancel-payment) "amount"))))
              (is (= "Supplier" (:value (cell draft (:id cancel-payment) "party"))))
              (is (= "Customer" (:value (cell draft (:id cancel-return) "party"))))))
          (let [model     (:model (workbench/query-stock context {:view "operations" :operationType "payment"}))
                draft     (grid/drafts model)
                reference (cell draft (:id new-payment) "original-operation-id")]
            (is (not-any? #(= (:id purchase) (:id %)) (:operations model)))
            (is (= (:id purchase) (:value reference)))
            (is (= "Purchase · Supplier" (:display reference)))
            (is (some #(= (:id purchase) (:value %)) (get-in reference [:editor :options])))))))))
