(ns tover.inventory.operations-web-test
  "Board chapters 18 and 19 on the operations page: the grid of operations, one saved field per edit, the warehouse
  prefill and command descriptors."
  (:require
    [bel.runtime :as runtime]
    [charred.api :as charred]
    [clojure.test :refer [deftest is testing]]
    [tover.inventory.entries.model :as entries]
    [tover.inventory.operations.web :as operations]
    [tover.inventory.web :as web])
  (:import
    [java.time Instant]))


(def workspace #uuid "00000000-0000-0000-0000-000000000001")
(def at (Instant/parse "2026-11-19T10:00:00Z"))


(def catalogue
  {:products    [{:product-id "tee" :name "T-shirt" :status "active" :default-warehouse-id "back"}]
   :stock-items [{:stock-item-id "tee-m" :product-id "tee" :variant-label "M" :article "T-100" :unit "pcs" :status "active"}]
   :barcodes    []
   :warehouses  [{:warehouse-id "main" :name "Main" :status "active"} {:warehouse-id "back" :name "Back" :status "active"}
                 {:warehouse-id "old" :name "Old" :status "archived"}]
   :suppliers   [{:supplier-id "tex" :name "Textiles LLC" :status "active"}]
   :stores      [{:store-id "web" :name "Web shop" :status "active" :default-warehouse-id "main"}]})


(def purchase
  {:entry-id "p" :revision 2 :kind "purchase" :occurred-at at :supplier-id "tex" :status "pending"
   :lines    [{:line-id "p-0" :stock-item-id "tee-m" :warehouse-id "main" :quantity 5 :unit-cost "200.00"}
              {:line-id "p-1" :stock-item-id "tee-m" :warehouse-id "back" :quantity 2}]})


(def accepted
  [{:record-id "s" :record-revision-id "s#1" :kind "sale" :occurred-at at :store-id "web" :total 450M :partial? false
    :lines     [{:position 0 :stock-item-id "tee-m" :warehouse-id "main" :quantity-change -3 :unit-price "150.00"}]}])


(defn- grid
  {:malli/schema [:=> [:cat :boolean] :map]}
  [preview?]
  (operations/operations-grid {:catalogue catalogue :pending [purchase] :findings [] :accepted accepted :currency "RUB" :preview? preview?}))


(defn- column
  {:malli/schema [:=> [:cat :map :string :string] :any]}
  [model row key]
  (let [x (.indexOf ^java.util.List (mapv :key (:columns model)) key)
        c (get (:cells (some #(when (= row (:id %)) %) (:rows model))) x)]
    (or (:display c) (:value c))))


(deftest operations-are-groups-of-lines
  (let [model (grid false)]
    (is (= ["p|p-0" "p|p-1" "s|0"] (map :id (:rows model))))
    (is (= [["T-shirt / M" "Main" "5" "200.00" "1000.00"] ["T-shirt / M" "Back" "2" "" ""]]
           (for [row ["p|p-0" "p|p-1"]] (map #(column model row %) ["item" "warehouse" "quantity" "price" "amount"]))))
    (is (= "Textiles LLC" (column model "p|p-0" "party")))
    (is (= #{0} (set (map :row (:merges model)))) "the purchase's shared cells span its two lines")
    (testing "accepted operations are read-only with their total"
      (is (every? :readOnly (:cells (last (:rows model)))))
      (is (= ["Sale" "Web shop" "3" "450.00" "450.00"] (map #(column model "s|0" %) ["kind" "party" "quantity" "amount" "total"]))))))


(deftest preview-shows-pending-only-with-include
  (let [model (grid true)]
    (is (= ["p|p-0" "p|p-1"] (map :id (:rows model))))
    (is (= {:id "p" :label "Include in submit"} (:selection (first (:cells (first (:rows model)))))))
    (is (= [{:entry-id "p" :revision 2 :rowId "p"}] (:pending model)))))


(deftest partial-total-is-marked
  (let [model (operations/operations-grid {:catalogue catalogue :pending [] :findings [] :currency "RUB" :preview? false
                                           :accepted  [(assoc (first accepted) :total 300M :partial? true
                                                              :lines [{:position 0 :stock-item-id "tee-m" :warehouse-id "main" :quantity-change 2 :unit-cost "150.00"}
                                                                      {:position 1 :stock-item-id "tee-m" :warehouse-id "main" :quantity-change 1}])]})]
    (is (= "≥ 300.00" (column model "s|0" "total")))))


(deftest findings-and-blockers-mark-cells
  (let [model (operations/operations-grid {:catalogue catalogue :currency "RUB" :preview? false :accepted []
                                           :pending   [(assoc purchase :supplier-id nil :blockers [{:line-id nil :field :supplier-id :code :supplier-missing}])]
                                           :findings  [{:entry-id "p" :line-id "p-1" :field :quantity :code :insufficient-stock :level :warning}]})
        cell  (fn [row key] (get (:cells (some #(when (= row (:id %)) %) (:rows model))) (.indexOf ^java.util.List (mapv :key (:columns model)) key)))]
    (is (re-find #"stock-finding-error" (:className (cell "p|p-0" "party"))))
    (is (= "Choose a supplier." (:title (cell "p|p-0" "party"))))
    (is (re-find #"stock-finding-warning" (:className (cell "p|p-1" "quantity"))))))


(def stocked {"tee-m" ["main"]})


(defn- input
  {:malli/schema [:=> [:cat :map [:maybe :map]] :map]}
  [values saved]
  (operations/operation-input catalogue stocked workspace (:entry-id saved "n") (:revision saved 0) values saved))


(deftest one-edit-saves-one-field
  (is (= {:workspace-id workspace :entry-id "n" :kind "sale" :occurred-at "2026-11-20T10:00:00Z" :lines [{:line-id "n-0"}]}
         (input {"kind" "sale" "field" "create" "value" "2026-11-20T10:00:00Z"} nil)))
  (let [saved (input {"field" "quantity" "line-id" "p-1" "value" "4"} purchase)]
    (is (= ["5" "4"] (map :quantity (:lines saved))))
    (is (= [2 "2026-11-19T10:00:00Z" "tex"] ((juxt :expected-revision :occurred-at :supplier-id) saved))))
  (is (= ["200.00" "90"] (map :unit-cost (:lines (input {"field" "price" "line-id" "p-1" "value" "90"} purchase)))))
  (is (= "150" (:unit-price (first (:lines (input {"field" "price" "line-id" "p-0" "value" "150"} (assoc purchase :kind "sale"))))))
      "a sale's price is a sale price")
  (is (= ["p-0"] (map :line-id (:lines (input {"field" "remove" "line-id" "p-1"} purchase)))))
  (is (= 3 (count (:lines (input {"field" "add-line"} purchase))))))


(deftest supplier-by-choice-or-by-name
  (is (= ["tex" nil] ((juxt :supplier-id :supplier-name) (input {"field" "supplier" "value" "tex"} (dissoc purchase :supplier-id)))))
  (is (= [nil "Cotton Co"] ((juxt :supplier-id :supplier-name) (input {"field" "supplier" "referenceName" "Cotton Co"} purchase))))
  (is (= ["tex" nil] ((juxt :supplier-id :supplier-name) (input {"field" "supplier" "referenceName" "textiles llc"} purchase)))
      "a typed name of an active supplier is that supplier")
  (is (= [nil nil] ((juxt :supplier-id :supplier-name) (input {"field" "supplier" "value" ""} purchase)))))


(deftest warehouse-prefill-order
  (let [sale   {:entry-id "s" :revision 1 :kind "sale" :store-id "web" :lines [{:line-id "s-0"}]}
        chosen (fn [entry stocked] (:warehouse-id (first (:lines (operations/operation-input catalogue stocked workspace "s" 1 {"field" "item" "line-id" "s-0" "value" "tee-m"} entry)))))]
    (is (= "main" (chosen sale {"tee-m" ["main"]})) "the only warehouse with stock")
    (is (= "main" (chosen sale {"tee-m" ["main" "back"]})) "then the store's default")
    (is (= "back" (chosen (assoc sale :kind "purchase" :store-id nil) {})) "then the product's default")
    (is (nil? (operations/warehouse-prefill (assoc-in catalogue [:warehouses 1 :status] "archived") {} "purchase" nil "tee-m"))
        "an archived default is left blank")
    (is (= "old" (chosen (assoc-in sale [:lines 0 :warehouse-id] "old") {"tee-m" ["main"]})) "a chosen warehouse stays")))


(deftest a-typed-item-resolves-or-proposes
  (is (= "tee-m" (:stock-item-id (first (:lines (input {"field" "item" "line-id" "p-0" "value" "T-shirt"} purchase))))))
  (is (= "Cup" (:product-name (first (:lines (input {"field" "item" "line-id" "p-0" "value" "Cup"} purchase)))))))


(deftest save-operation-returns-a-descriptor
  (with-redefs [runtime/execute!   (fn [& _] (throw (Exception. "Action must be pure")))
                runtime/read-model (fn [_ id _] (if (= id ::entries/pending-entries) {:entries [purchase]} catalogue))]
    (is (= {:type  :command :id ::entries/save-shared-inventory-entry :command-id "c1"
            :input (input {"field" "reason" "value" "Restock"} purchase)
            :then  {:type :view :id ::web/saved :input {:commandId "c1"}}
            :catch {:type :view :id ::web/failed :input {:commandId "c1"}}}
           (operations/save-operation {} {:workspaceId workspace :entryId "p" :revision 2 :values {"field" "reason" "value" "Restock"} :commandId "c1"})))))


(deftest describe-keeps-supplier-and-store
  (with-redefs [runtime/read-model (fn [& _] {:entries [purchase]})]
    (is (= "tex" (:supplier-id (:input (web/describe-entry {} {:workspaceId workspace :entryId "p" :revision 2 :occurredAt "" :reason "x" :commandId "c"})))))))


(deftest archived-choices-are-not-offered
  (let [archived (-> catalogue
                     (update :suppliers conj {:supplier-id "old" :name "Old Textiles" :status "archived"})
                     (update :stores conj {:store-id "gone" :name "Old Shop" :status "archived"}))
        model    (operations/operations-grid {:catalogue archived :pending [] :findings [] :accepted [] :currency "RUB" :preview? false})]
    (is (= ["Textiles LLC"] (map :name (get-in model [:choices :suppliers]))))
    (is (= ["Web shop"] (map :name (get-in model [:choices :stores]))))
    (with-redefs [runtime/read-model (fn [& _] archived)]
      (is (= [] (get-in (charred/read-json (get-in (operations/supplier-choices {:context {} :input {:requestId "r" :text "Old"}})
                                                   [:data :_operationsSuppliersJson]))
                        ["options"]))))))


(deftest barcode-picks-the-item-and-no-default-stays-blank
  (let [with-code (assoc catalogue :barcodes [{:stock-item-id "tee-m" :barcode "4600000000017"}])]
    (is (= "tee-m" (:stock-item-id (first (:lines (operations/operation-input with-code stocked workspace "p" 2 {"field" "item" "line-id" "p-0" "value" "4600000000017"} purchase)))))))
  (is (nil? (operations/warehouse-prefill (assoc-in catalogue [:products 0 :default-warehouse-id] nil) {"tee-m" ["main" "back"]} "sale" nil "tee-m"))
      "stock in two warehouses, no store, no product default"))
