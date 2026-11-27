(ns tover.inventory.operations-web-test
  "Board chapters 18 and 19 on the operations page: the grid of operations, one saved field per edit, the warehouse
  prefill and command descriptors."
  (:require
    [bel.runtime :as runtime]
    [charred.api :as charred]
    [clojure.test :refer [deftest is testing]]
    [tover.inventory.entries.model :as entries]
    [tover.inventory.operations.web :as operations]
    [tover.inventory.web :as web]
    [tover.shared.i18n :as i18n])
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
    (is (= [["T-shirt / M" "Main" "5" "200.00"] ["T-shirt / M" "Back" "2" ""]]
           (for [row ["p|p-0" "p|p-1"]] (map #(column model row %) ["item" "warehouse" "quantity" "price"]))))
    (is (= ["Textiles LLC" ""] (map #(column model "p|p-0" %) ["supplier" "store"])))
    (is (= #{0} (set (map :row (:merges model)))) "the purchase's shared cells span its two lines")
    (testing "normal mode is read-only; every operation offers Edit and Copy, an accepted one Cancel and its revisions"
      (is (not-any? #(or (:editor %) (:target %)) (mapcat :cells (:rows model))))
      (is (= [["stock-edit" "operation-copy"] nil ["item-history" "stock-edit" "operation-copy" "operation-cancel"]] (map #(some->> % :actions seq (map :type)) (map (comp last :cells) (:rows model)))))
      (is (= ["Sale" "Web shop" "3"] (map #(column model "s|0" %) ["kind" "store" "quantity"]))))))


(defn- shown
  {:malli/schema [:=> [:cat :map] :map]}
  [view]
  (operations/operations-grid (merge {:catalogue catalogue :pending [purchase] :findings [] :accepted accepted :currency "RUB" :preview? false} view)))


(deftest money-mode-shows-amounts-in-place-of-quantities
  (let [model (shown {:mode "money"})]
    (is (= ["1000.00" "" "450.00"] (map #(column model % "quantity") ["p|p-0" "p|p-1" "s|0"])))
    (is (some #(= ["quantity" "Amount"] ((juxt :key :label) %)) (:columns model)))
    (is (= "5" (column (shown {:mode "money" :preview? true}) "p|p-0" "quantity")) "Preview is in units")))


(deftest sort-and-filter-keep-operations-whole
  (is (= ["s|0" "p|p-0" "p|p-1"] (map :id (:rows (shown {:sort "quantity" :direction "asc"})))))
  (is (= ["s|0" "p|p-0" "p|p-1"] (map :id (:rows (shown {:sort "kind" :direction "desc"})))))
  (is (= ["p|p-0" "p|p-1"] (map :id (:rows (shown {:filters {:warehouse [{:type "eq" :value "back"}]}})))) "one matching line keeps its operation")
  (is (= ["s|0"] (map :id (:rows (shown {:filters {"store" [{:type "begins" :value "web"}]}}))))))


(def stocked {"tee-m" ["main"]})


(defn- input
  {:malli/schema [:=> [:cat :map [:maybe :map]] :map]}
  [values saved]
  (operations/operation-input catalogue stocked workspace (:entry-id saved "n") (:revision saved 0) values saved))


(deftest a-blank-row-adds-a-line-in-preview
  (let [row  (last (:rows (shown {:preview? true})))
        cell (some #(when (= "item" (get-in % [:target :field])) %) (:cells row))]
    (is (= ["p|p-r2" "+ Add line"] [(:id row) (:placeholder cell)]))
    (is (= ["p-0" "p-1" "p-r2"] (map :line-id (:lines (input {"field" "item" "line-id" (get-in cell [:target :line]) "value" "tee-m"} purchase)))))
    (is (= "output" (:side (last (:lines (input {"field" "item" "line-id" "m-r1-output" "side" "output" "value" "tee-m"}
                                                {:entry-id "m" :revision 1 :kind "production" :lines []}))))))))


(deftest smart-addition-builds-the-operation
  (is (= {:kind  "production" :occurred-at "2026-11-20T10:00:00Z"
          :lines [{:line-id "n-0" :side "input" :stock-item-id "tee-m" :warehouse-id "main" :quantity "12"}
                  {:line-id "n-1" :side "output" :product-name "Pack" :quantity "1"}]}
         (select-keys (operations/addition-input catalogue stocked workspace "n" {"kind" "production" "occurred-at" "2026-11-20T10:00:00Z"}
                                                 [{"side" "input" "item" "tee-m" "quantity" "12"} {"side" "input" "item" ""} {"side" "output" "item" "Pack" "quantity" "1"}])
                      [:kind :occurred-at :lines])))
  (is (= "tex" (:supplier-id (operations/addition-input catalogue stocked workspace "n" {"kind" "purchase" "supplier" "textiles llc"} [])))))


(deftest preview-shows-pending-only-with-include
  (let [model (grid true)]
    (is (= ["p|p-0" "p|p-1" "p|p-r2"] (map :id (:rows model))))
    (is (= {:id "p" :label "Include in submit"} (:selection (first (:cells (first (:rows model)))))))
    (is (= [{:entry-id "p" :revision 2 :rowId "p"}] (:pending model)))))


(deftest partial-total-is-marked
  (let [model (shown {:pending  [] :mode "money"
                      :accepted [(assoc (first accepted) :total 300M :partial? true
                                        :lines [{:position 0 :stock-item-id "tee-m" :warehouse-id "main" :quantity-change -2 :unit-price "150.00"}
                                                {:position 1 :stock-item-id "tee-m" :warehouse-id "main" :quantity-change -1}])]})]
    (is (some #(= "Total: 300.00. Some lines have no price." (:title %)) (:cells (first (:rows model)))))))


(deftest findings-and-blockers-mark-cells
  (let [model (operations/operations-grid {:catalogue catalogue :currency "RUB" :preview? false :accepted []
                                           :pending   [(assoc purchase :supplier-id nil :blockers [{:line-id nil :field :supplier-id :code :supplier-missing}])]
                                           :findings  [{:entry-id "p" :line-id "p-1" :field :quantity :code :insufficient-stock :level :warning}]})
        cell  (fn [row key] (get (:cells (some #(when (= row (:id %)) %) (:rows model))) (.indexOf ^java.util.List (mapv :key (:columns model)) key)))]
    (is (re-find #"stock-finding-error" (:className (cell "p|p-0" "supplier"))))
    (is (= "Choose a supplier." (:title (cell "p|p-0" "supplier"))))
    (is (re-find #"stock-finding-warning" (:className (cell "p|p-1" "quantity"))))))


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


;; 20–25 · Other operation kinds on the page


(defn- pending-grid
  {:malli/schema [:=> [:cat :map] :map]}
  [entry]
  (operations/operations-grid {:catalogue catalogue :pending [entry] :findings [] :accepted [] :currency "RUB" :preview? true}))


(deftest transfer-and-defect-show-leaving-and-entering-rows
  (let [model (pending-grid {:entry-id "t" :revision 1 :kind "transfer" :occurred-at at :from-warehouse-id "main" :to-warehouse-id "back"
                             :lines    [{:line-id "t-0" :stock-item-id "tee-m" :quantity 4}]})]
    (is (= [["t|t-0" "operations-pending operations-out" "Main" "4"] ["t|t-0|in" "operations-pending operations-in" "Back" "4"]]
           (map (juxt :id :className #(column model (:id %) "warehouse") #(column model (:id %) "quantity")) (take 2 (:rows model)))))
    (is (= "to-warehouse" (get-in (second (:rows model)) [:cells (.indexOf ^java.util.List (mapv :key (:columns model)) "warehouse") :target :field]))
        "the green warehouse edits the destination"))
  (let [model (pending-grid {:entry-id "d" :revision 1 :kind "defect" :occurred-at at :reason "torn" :lines [{:line-id "d-0" :stock-item-id "tee-m" :warehouse-id "main" :quantity 1}]})]
    (is (= "T-shirt / M · defect (will be created)" (column model "d|d-0|in" "item")))))


(deftest production-inputs-red-outputs-green
  (let [model (pending-grid {:entry-id "m" :revision 1 :kind "production" :occurred-at at
                             :lines    [{:line-id "m-0" :stock-item-id "tee-m" :warehouse-id "main" :quantity 2 :side "input"}
                                        {:line-id "m-1" :product-name "Pack" :warehouse-id "main" :quantity 1 :side "output"}]})]
    (is (= [["m|m-0" "−"] ["m|m-r1-input" nil] ["m|m-1" "+"] ["m|m-r1-output" nil]]
           (map (juxt :id #(some :prefix (:cells %))) (:rows model))))
    (is (= ["+ Add input" "+ Add output"] (filter #(re-find #"Add" %) (keep :placeholder (mapcat :cells (:rows model)))))))
  (is (= ["n-0" "input"] ((juxt :line-id :side) (first (:lines (input {"kind" "production" "field" "create" "value" "2026-11-20T10:00:00Z"} nil))))))
  (is (= "output" (:side (last (:lines (input {"field" "add-line" "value" "output"} {:entry-id "m" :revision 1 :kind "production" :lines []})))))))


(deftest payment-is-one-row-with-amount
  (let [model (pending-grid {:entry-id "pay" :revision 1 :kind "payment" :occurred-at at :direction "in" :store-id "web" :amount "500" :lines []})]
    (is (= [["Payment received" "Web shop" "500.00 RUB"]] (map (fn [r] (map #(column model (:id r) %) ["kind" "store" "quantity"])) (:rows model)))))
  (is (= ["store-id" "web"] (let [saved (input {"field" "counterparty" "value" "store:web"} {:entry-id "pay" :revision 1 :kind "payment" :supplier-id "tex" :lines []})]
                              [(some #(when (% saved) (name %)) [:supplier-id :store-id]) (:store-id saved)])))
  (is (= [] (:lines (input {"kind" "payment" "field" "create" "value" "2026-11-20T10:00:00Z"} nil)))))


(deftest return-and-output-prefill
  (is (= "main" (:warehouse-id (first (:lines (operations/operation-input catalogue {"tee-m" ["main" "back"]} workspace "r" 1 {"field" "item" "line-id" "r-0" "value" "tee-m"}
                                                                          {:entry-id "r" :revision 1 :kind "customer-return" :store-id "web" :lines [{:line-id "r-0"}]})))))
      "a customer return takes the store's default like a sale")
  (is (= "back" (:warehouse-id (second (:lines (operations/operation-input catalogue {} workspace "m" 1 {"field" "item" "line-id" "m-1" "value" "tee-m"}
                                                                           {:entry-id "m" :revision 1 :kind "production"
                                                                            :lines    [{:line-id "m-0" :side "input" :warehouse-id "back"} {:line-id "m-1" :side "output"}]})))))
      "an output takes the first input's warehouse")
  (is (= "150" (:unit-price (first (:lines (input {"field" "price" "line-id" "p-0" "value" "150"} (assoc purchase :kind "supplier-return"))))))))


(deftest accepted-payment-has-a-row
  (let [model (operations/operations-grid {:catalogue catalogue :pending [] :findings [] :currency "RUB" :preview? false
                                           :accepted  [{:record-id "pay" :kind "payment" :direction "out" :occurred-at at :supplier-id "tex" :total 1000M :partial? false :lines []}]})]
    (is (= ["Payment made" "Textiles LLC" "1000.00 RUB"] (map #(column model "pay|" %) ["kind" "supplier" "quantity"])))))


;; 26–28 · Accepted operations change through pending revisions, cancellations and copies


(defn- amended
  {:malli/schema [:=> [:cat [:maybe :map] :map [:* :any]] :map]}
  [entry op & {:as more}]
  (shown (merge {:pending (if entry [entry] []) :accepted [op]} more)))


(defn- cell
  {:malli/schema [:=> [:cat :map :string :string] :map]}
  [model row key]
  (get (:cells (some #(when (= row (:id %)) %) (:rows model))) (.indexOf ^java.util.List (mapv :key (:columns model)) key)))


(defn- labels
  {:malli/schema [:=> [:cat :map] [:sequential :any]]}
  [model]
  (map :label (:actions (last (:cells (first (:rows model)))))))


(def sale (first accepted))


(def edited
  {:entry-id "e" :revision 1 :kind "sale" :amends "s#1" :occurred-at at :store-id "web" :status "pending"
   :lines    [{:line-id "s#1-0" :stock-item-id "tee-m" :warehouse-id "main" :quantity 2 :unit-price "150"}
              {:line-id "e-1" :stock-item-id "tee-m" :warehouse-id "back" :quantity 1 :unit-price "150.00"}]})


(deftest accepted-cells-save-a-revision-of-it
  (is (= ["Revisions" "Edit" "Copy" "Cancel operation"] (labels (amended nil sale))))
  (let [model (amended nil sale :preview? true :editing ["s"])]
    (is (= {:entry nil :revision 0 :amends "s#1" :line "s#1-0" :field "quantity" :kind "sale"}
           (select-keys (:target (cell model "s|s#1-0" "quantity")) [:entry :revision :amends :line :field :kind])))
    (is (= ["s|s#1-0" "s|s-r0"] (map :id (:rows model))) "with a blank row for another line")
    (is (= ["Revisions"] (labels model)))
    (is (nil? (:selection (cell model "s|s#1-0" "select"))) "nothing to submit yet")))


(deftest a-pending-revision-shows-what-changes
  (let [model (amended edited sale :preview? true
                       :findings [{:entry-id "e" :line-id "s#1-0" :field :quantity :code :insufficient-stock :level :warning}])]
    (is (= [["s|s#1-0" "e"] ["s|e-1" "e"] ["s|s-r1" "e"]] (map (juxt :id :entry) (:rows model))))
    (is (= ["3" "2"] ((juxt :before :value) (cell model "s|s#1-0" "quantity"))))
    (is (nil? (:before (cell model "s|s#1-0" "price"))) "150 is 150.00")
    (is (re-find #"stock-cell-pending" (:className (cell model "s|e-1" "item"))) "an added line")
    (is (re-find #"stock-finding" (:className (cell model "s|s#1-0" "quantity"))))
    (is (= ["Remove line" "Revisions" "Discard pending changes"] (labels model)))
    (is (= "e" (get-in model [:rows 0 :cells 0 :selection :id]))))
  (testing "outside Preview it is in the operation's place, read-only"
    (let [model (amended edited sale)]
      (is (= ["3" "2"] ((juxt :before :value) (cell model "s|s#1-0" "quantity"))))
      (is (not-any? #(or (:editor %) (:target %)) (mapcat :cells (:rows model))))
      (is (= ["Revisions" "Edit" "Copy" "Cancel operation"] (labels model)))
      (is (= ["450.00" "300.00"] ((juxt :before :value) (cell (amended edited sale :mode "money") "s|s#1-0" "quantity"))))))
  (testing "a changed transfer keeps its signs"
    (let [op    {:record-id "t" :record-revision-id "t#1" :kind "transfer" :occurred-at at
                 :lines     [{:position 0 :stock-item-id "tee-m" :warehouse-id "main" :quantity-change -3}
                             {:position 1 :stock-item-id "tee-m" :warehouse-id "back" :quantity-change 3}]}
          model (amended {:entry-id "e" :revision 1 :kind "transfer" :amends "t#1" :occurred-at at :from-warehouse-id "main" :to-warehouse-id "back"
                          :lines    [{:line-id "t#1-0" :stock-item-id "tee-m" :quantity 2}]} op)]
      (is (= [["−3" "−" "2"] ["+3" "+" "2"]]
             (map #((juxt :before :prefix :value) (cell model % "quantity")) ["t|t#1-0" "t|t#1-0|in"])))))
  (testing "a removed line stays struck through"
    (let [model (amended (update edited :lines subvec 1) sale :preview? true)]
      (is (= [["s|e-1" "operations-pending"] ["s|s-r1" "operations-pending operations-new-line"] ["s|s#1-0|removed" "operations-pending operations-cancelled"]]
             (map (juxt :id :className) (:rows model))))
      (is (nil? (:editor (cell model "s|s#1-0|removed" "quantity"))))))
  (testing "Preview shows only pending ones"
    (is (= [] (:rows (amended nil sale :preview? true))))))


(deftest a-stale-revision-is-marked-in-place
  (let [op    (assoc sale :record-revision-id "s#2" :revisions [sale (assoc sale :record-revision-id "s#2")])
        model (amended (assoc edited :blockers [{:line-id nil :field :amends :code :target-not-current}]) op)]
    (is (= "This operation changed since it was loaded. Review the latest values." (:title (cell model "s|s#1-0" "kind"))))))


(def cancelling {:entry-id "e" :revision 1 :kind "sale" :amends "s#1" :cancel true :reason "entered twice" :status "pending" :lines []})


(deftest a-pending-cancellation-is-struck-through
  (let [model (amended cancelling sale :preview? true)]
    (is (= [["s|s#1-0" "operations-pending operations-cancelled"]] (map (juxt :id :className) (:rows model))))
    (is (= ["Sale (will be cancelled)" "3" "entered twice"] (map #(column model "s|s#1-0" %) ["kind" "quantity" "reason"])))
    (is (= [["reason" "e"]] (keep #(when-let [t (:target %)] [(:field t) (:entry t)]) (:cells (first (:rows model))))) "only the reason is editable")
    (is (= "e" (get-in model [:rows 0 :cells 0 :selection :id])))
    (is (= ["Revisions" "Discard pending changes"] (labels model))))
  (let [model (amended cancelling sale)]
    (is (= ["Revisions" "Edit" "Copy"] (labels model)))
    (is (not-any? :target (mapcat :cells (:rows model))))
    (is (= "s#1" (:source (last (:actions (last (:cells (first (:rows model)))))))) "Copy takes the accepted facts")))


(deftest a-cancelled-operation-is-read-only
  (let [op    (assoc sale :cancelled true :record-revision-id "s#2")
        model (amended nil op)]
    (is (not-any? :target (mapcat :cells (:rows model))))
    (is (= ["Sale (cancelled)" "3"] (map #(column model "s|0" %) ["kind" "quantity"])))
    (is (= ["Revisions" "Copy"] (labels model)))
    (is (= [] (:rows (amended nil op :preview? true :editing ["s"]))))))


(deftest revisions-tell-who-and-why
  (let [revision {:revision 1 :record-revision-id "s#1" :recorded-at at :occurred-at at :actor workspace :store-id "web" :cancelled false :lines (:lines sale)}
        model    (amended nil (assoc sale :revisions [revision (assoc revision :revision 2 :record-revision-id "s#2" :reason "miscounted")]) :actors {(str workspace) "Ann"})]
    (is (= [[1 "Ann" "" ["Web shop" "T-shirt / M · Main · -3 × 150.00"]] [2 "Ann" "miscounted" ["Web shop" "T-shirt / M · Main · -3 × 150.00"]]]
           (map (juxt :revision :actor :reason :facts) (get-in model [:revisions "s"]))))))


(deftest a-copy-is-below-its-source
  (let [copy {:entry-id "c" :revision 1 :kind "sale" :occurred-at at :lines [{:line-id "c-0"}]}]
    (is (= ["c|c-0" "s|0"] (map :id (:rows (amended copy sale)))))
    (is (= ["s|0" "c|c-0"] (map :id (:rows (amended copy sale :below [{:entry "c" :group "s"}])))))
    (is (= ["c|c-0" "s|0"] (map :id (:rows (amended copy sale :below [{:entry "c" :group "s"}] :sort "quantity" :direction "asc")))) "a chosen order wins")
    (is (= ["c" "p"] (distinct (map :groupId (:rows (shown {:pending [(assoc purchase :occurred-at (.minusSeconds at 60)) copy] :preview? true :below [{:entry "c" :group "s"}]})))))
        "first in Preview, where its source is not shown")))


(defn- saved-input
  {:malli/schema [:=> [:cat [:vector :map] [:vector :map] :int :map] :map]}
  [pending accepted revision values]
  (with-redefs [runtime/execute!   (fn [& _] (throw (Exception. "Action must be pure")))
                runtime/read-model (fn [_ id _] (case (name id) "pending-entries" {:entries pending} "accepted-operations" accepted catalogue))]
    (:input (operations/save-operation {} {:workspaceId workspace :entryId "e" :revision revision :values values :commandId "c1"}))))


(deftest first-edit-starts-from-the-accepted-operation
  (is (= {:workspace-id workspace :entry-id "e" :kind "sale" :amends "s#1" :occurred-at "2026-11-19T10:00:00Z" :store-id "web"
          :lines        [{:line-id "s#1-0" :stock-item-id "tee-m" :warehouse-id "main" :quantity "2" :unit-price "150.00"}]}
         (saved-input [] accepted 0 {"kind" "sale" "field" "quantity" "line-id" "s#1-0" "value" "2" "amends" "s#1"})))
  (is (= ["s#1" 1] ((juxt :amends :expected-revision) (saved-input [edited] accepted 1 {"field" "reason" "value" "x" "amends" "s#1"}))) "later edits keep it"))


(deftest cancel-carries-only-its-target
  (is (= {:workspace-id workspace :entry-id "e" :kind "sale" :amends "s#1" :cancel true}
         (saved-input [] accepted 0 {"kind" "sale" "field" "cancel" "value" "true" "amends" "s#1"})))
  (is (= {:workspace-id workspace :entry-id "e" :kind "sale" :amends "s#1" :cancel true :expected-revision 1}
         (saved-input [edited] accepted 1 {"kind" "sale" "field" "cancel" "value" "true" "amends" "s#1"}))
      "pending edits become the cancellation")
  (is (= ["s#1" true "twice" []] ((juxt :amends :cancel :reason :lines) (saved-input [cancelling] accepted 1 {"field" "reason" "value" "twice" "amends" "s#1"})))))


(deftest copy-is-a-new-operation-with-the-sources-values
  (let [values {"kind" "sale" "field" "copy" "value" "2026-11-24T10:00:00Z"}]
    (is (= {:workspace-id workspace :entry-id "e" :kind "sale" :occurred-at "2026-11-24T10:00:00Z" :store-id "web" :copies "s#1"
            :lines        [{:line-id "e-0" :stock-item-id "tee-m" :warehouse-id "main" :quantity "3" :unit-price "150.00"}]}
           (saved-input [] accepted 0 (assoc values "copies" "s#1"))))
    (let [copy (saved-input [(assoc edited :entry-id "x")] accepted 0 (assoc values "copies" "x"))]
      (is (= [["e-0" "2"] ["e-1" "1"]] (map (juxt :line-id :quantity) (:lines copy))) "a pending revision is copied as edited")
      (is (nil? (:amends copy))))))


;; 45 · Imported operations on the Operations page


(def import-sale
  {:entry-id "import:c:fbs-posting:P9" :revision 1 :kind "sale" :occurred-at (.plusSeconds at 60) :status "pending"
   :lines    [{:line-id "c:fbs-posting:P9/1" :stock-item-id "tee-m" :warehouse-id "main" :quantity 1}]})


(defn- imported-row
  {:malli/schema [:=> [:cat :string :string [:* :any]] :map]}
  [id step & {:as more}]
  (merge {:source-operation-id (str "c:fbs-posting:" id) :connection-id "c"         :connection-label "Ozon · Shop"          :kind     "fbs-posting"
          :entry-kind          "sale"                    :status        "delivered" :occurred-at      "2026-11-18T10:00:00Z" :revision 1             :next-step step
          :missing             [] :findings [] :lines [{:label "Mug red" :quantity 1}]}
         more))


(def review-groups
  {:groups [{:decision   "match product" :id "c:100" :label "Match Mug red" :count 2 :connection-id "c" :listing-state "unmatched"
             :operations [(imported-row "P1" "match product") (imported-row "P2" "match product" :lines [{:label "Mug red" :quantity 2}])]}
            {:decision   "accept" :id "accept" :label "Accept" :count 1 :connection-id "c"
             :operations [(imported-row "P9" "accept" :entry-id (:entry-id import-sale) :entry-revision 1)]}
            {:decision   "history" :id "history" :label "History" :count 1 :connection-id "c"
             :operations [(imported-row "P0" "history")]}]})


(defn- imported
  {:malli/schema [:=> [:cat :map] :map]}
  [view]
  (shown (merge {:pending [purchase import-sale] :imported review-groups :sources [{:connection-id "c" :account-label "Shop"}]} view)))


(deftest the-imported-filter-groups-most-operations-first
  (let [model (imported {:source "imported"})]
    (is (= ["Match Mug red – 2 operations" "Accept – 1 operation"] (keep #(when (:header %) (get-in % [:cells 0 :value])) (:rows model))))
    (is (= ["header" "c:fbs-posting:P1" "c:fbs-posting:P2" "header" (:entry-id import-sale)]
           (map #(if (:header %) "header" (:groupId %)) (:rows model)))
        "only imported rows; an operation with an entry is the entry's own row")
    (is (= ["Ozon · Shop" "Match product" "Mug red" "2"]
           (map #(column model "c:fbs-posting:P2|0" %) ["source" "next-step" "item" "quantity"])))
    (is (not-any? #(or (:editor %) (:target %)) (:cells (some #(when (= "c:fbs-posting:P1|0" (:id %)) %) (:rows model))))
        "a row without an entry is read-only")
    (is (some #(= {:colspan (count (:columns model)) :row 0 :col 0} (select-keys % [:colspan :row :col])) (:merges model))
        "the decision spans the table")))


(deftest hidden-steps-show-only-when-filtered
  (is (not-any? #(= "c:fbs-posting:P0" (:groupId %)) (:rows (imported {:source "imported"}))))
  (is (= ["c:fbs-posting:P0"] (keep #(when-not (:header %) (:groupId %)) (:rows (imported {:source "imported" :filters {"next-step" [{:type "eq" :value "History"}]}}))))))


(deftest imported-entries-carry-their-connection-and-next-step
  (let [model (imported {})]
    (is (= ["p" (:entry-id import-sale) "s"] (distinct (map :groupId (:rows model)))))
    (is (= ["Ozon · Shop" "Accept"] (map #(column model (str (:entry-id import-sale) "|c:fbs-posting:P9/1") %) ["source" "next-step"])))
    (is (= ["" ""] (map #(column model "p|p-0" %) ["source" "next-step"])))
    (is (nil? (some :selection (mapcat :cells (:rows model)))) "outside the filter nothing is selected outside Preview")))


(def ^:private move-transfer
  {:entry-id          "move:c|tee-m|D1|main|back" :revision 1      :kind "transfer" :occurred-at (.plusSeconds at 120) :status "pending"
   :from-warehouse-id "main" :to-warehouse-id               "back"
   :lines             [{:line-id "1" :stock-item-id "tee-m" :quantity 5}]})


(deftest a-detected-move-carries-its-connection-and-evidence
  (let [model (imported {:pending [purchase move-transfer]
                         :moves   [{:entry-id         (:entry-id move-transfer) :connection-id "c"                    :last-agreeing-run "D1" :current-run "D2"
                                    :last-agreeing-at "2026-11-21T06:00:00Z"    :current-at    "2026-11-22T06:00:00Z"}]})
        row   (str (:entry-id move-transfer) "|1")
        cell  (fn [key] (get-in (some #(when (= row (:id %)) %) (:rows model))
                                [:cells (.indexOf ^java.util.List (mapv :key (:columns model)) key)]))]
    (testing "A Transfer the connector proposed shows its connection, not as one a member entered"
      (is (= ["Ozon · Shop" "Detected move"] (map #(column model row %) ["source" "next-step"]))))
    (testing "and the snapshots it was found between, with a link to the Stock differences view"
      (is (= "Last agreeing snapshot 2026-11-21T06:00:00Z · current snapshot 2026-11-22T06:00:00Z" (:title (cell "next-step"))))
      (is (= [{:type "operations-open" :href "/operations/marketplace/c/differences"}]
             (map #(select-keys % [:type :href]) (:actions (cell "next-step"))))))
    (testing "in Russian"
      (let [model (binding [i18n/*locale* "ru"]
                    (imported {:pending [move-transfer]
                               :moves   [{:entry-id (:entry-id move-transfer) :connection-id "c" :last-agreeing-run "D1" :current-run "D2"}]}))]
        (is (= "Найденное перемещение" (column model row "next-step")))))
    (testing "without its evidence, it still shows its connection"
      (is (= ["Ozon · Shop" "Detected move"] (map #(column (imported {:pending [move-transfer]}) row %) ["source" "next-step"]))))))


(deftest the-table-is-unchanged-without-connections
  (is (= ["kind" "occurred-at" "supplier" "store" "reason" "item" "article" "unit" "warehouse" "quantity" "price" "actions"]
         (map :key (:columns (grid false)))))
  (is (nil? (:imported (grid false)))))


(deftest the-proposed-correction-fits-its-columns-in-preview
  (let [short  (imported-row "P9" "fix shortage" :entry-id (:entry-id import-sale) :entry-revision 1
                             :suggested-correction {:stock-item-id "tee-m" :warehouse-id "main" :instant "2026-11-19T09:59:59Z" :count 3})
        review {:groups [{:decision "fix shortage" :id "tee-m|main" :label "Fix shortage of T-shirt M" :count 1 :connection-id "c" :operations [short]}]}
        model  (imported {:source "imported" :imported review :preview? true})
        fix    (some #(when (re-find #"operations-suggested" (or (:className %) "")) %) (:rows model))]
    (is (= "select" (:key (first (:columns model)))))
    (is (= (count (:columns model)) (count (:cells fix))) "one cell under each column, the include column first")
    (is (= ["Correction" "Ozon · Shop" "T-shirt / M" "Main" "3"] (map #(column model (:id fix) %) ["kind" "source" "item" "warehouse" "quantity"])))
    (is (= "operation-correction" (get-in (last (:cells fix)) [:actions 0 :type])) "the action stays in the last column")))


(deftest decision-labels-translate-from-their-key
  (testing "the page translates the decision from its message key, not from the English label"
    (is (= "Сопоставить Mug red – 48 операций"
           (binding [i18n/*locale* "ru"]
             (operations/decision-label {:decision "match product" :label "Pair Mug red" :label-key "Match {name}" :label-args {:name "Mug red"}} 48))))
    (is (= "Задать дату перехода – 1 операция"
           (binding [i18n/*locale* "ru"]
             (operations/decision-label {:decision "set cutover" :label "Set cutover" :label-key "Set cutover" :label-args {}} 1))))))


(deftest accept-the-sale-first-points-at-the-sale
  (let [return (imported-row "R1" "accept the sale first" :kind "return")
        review {:groups [{:decision   "accept the sale first" :id    "c:fbs-posting:P0" :label         "Accept sale P0" :label-key  "Accept sale {name}"
                          :label-args {:name "P0"}            :count 1                  :connection-id "c"              :operations [return]}]}
        model  (imported {:source "imported" :imported review})
        header (first (filter :header (:rows model)))]
    (is (re-find #"^Accept sale P0 – 1 operation$" (get-in header [:cells 0 :value])))
    (is (= {:type "operations-focus" :groups ["import:c:fbs-posting:P0" "c:fbs-posting:P0"]}
           (select-keys (get-in header [:cells 0 :actions 0]) [:type :groups]))
        "Open moves to the sale's own rows on the page")))


(deftest accept-the-sale-first-says-when-the-sale-is-set-aside
  (let [sale   (imported-row "P0" "set aside")
        return (imported-row "R1" "accept the sale first" :kind "return")
        review {:groups [{:decision "set aside" :id "aside" :label "Set aside" :count 1 :connection-id "c" :operations [sale]}
                         {:decision   "accept the sale first" :id    "c:fbs-posting:P0" :label         "Accept sale P0" :label-key  "Accept sale {name}"
                          :label-args {:name "P0"}            :count 1                  :connection-id "c"              :operations [return]}]}
        model  (imported {:source "imported" :imported review})
        header (first (filter #(and (:header %) (re-find #"P0" (get-in % [:cells 0 :value]))) (:rows model)))]
    (is (re-find #"^Bring back sale P0 first – 1 operation$" (get-in header [:cells 0 :value])))
    (is (nil? (get-in header [:cells 0 :actions])) "there is nothing on the page to open")))


(deftest the-source-select-and-the-aside-actions-are-one-node-each
  (let [select (operations/source-select {:connections [{:connection-id "c-1" :account-label "Shop"}] :source "c-1"})
        aside  (operations/aside-actions)]
    (is (= :select.operations-source (first select)))
    (is (= [["" false] ["imported" false] ["c-1" true]] (map (fn [[_ {:keys [value selected]}]] [value selected]) (drop 2 select))))
    (is (= "Ozon · Shop" (last (last select))) "the connection reads as its rows do")
    (is (= :span.operations-aside (first aside)))
    (is (nil? (operations/source-select {:connections []})))))


(deftest selected-rows-name-what-was-seen
  (let [model (imported {:source "imported"})]
    (is (= {"import:c:fbs-posting:P1" {:sourceOperationId "c:fbs-posting:P1" :revision 1 :aside false}
            "import:c:fbs-posting:P2" {:sourceOperationId "c:fbs-posting:P2" :revision 1 :aside false}
            (:entry-id import-sale)   {:sourceOperationId "c:fbs-posting:P9" :revision 1 :entryRevision 1 :aside false}}
           (:imported model)))
    (is (= ["import:c:fbs-posting:P1" "import:c:fbs-posting:P2" (:entry-id import-sale)]
           (keep #(get-in % [:cells 0 :selection :id]) (:rows model)))))
  (is (= {:type  :command :id :tover.marketplace.operations.model/set-source-operations-aside :command-id "c1"
          :input {:aside      true :reason "Not tracked"
                  :operations [{:source-operation-id "c:fbs-posting:P1" :revision 1 :entry-revision 0}
                               {:source-operation-id "c:fbs-posting:P9" :revision 1 :entry-revision 2}]}
          :then  {:type :view :id ::web/saved :input {:commandId "c1"}}
          :catch {:type :view :id ::web/failed :input {:commandId "c1"}}}
         (operations/set-aside-operations {} {:workspaceId workspace :aside true :reason "Not tracked" :commandId "c1"
                                              :operations  [{:sourceOperationId "c:fbs-posting:P1" :revision 1}
                                                            {:sourceOperationId "c:fbs-posting:P9" :revision 1 :entryRevision 2}]}))
      "a row without an entry revision is sent as one that saw no entry"))


(deftest the-proposed-correction-is-highlighted
  (let [short  (imported-row "P9" "fix shortage" :entry-id (:entry-id import-sale) :entry-revision 1
                             :suggested-correction {:stock-item-id "tee-m" :warehouse-id "main" :instant "2026-11-19T09:59:59Z" :count 3})
        review {:groups [{:decision "fix shortage" :id "tee-m|main" :label "Fix shortage of T-shirt M" :count 1 :connection-id "c" :operations [short]}]}
        model  (imported {:source "imported" :imported review})
        fix    (some #(when (re-find #"operations-suggested" (or (:className %) "")) %) (:rows model))]
    (is (= ["header" (:id fix) (str (:entry-id import-sale) "|c:fbs-posting:P9/1")] (map #(if (:header %) "header" (:id %)) (:rows model))))
    (is (= ["Correction" "T-shirt / M" "Main" "3"] (map #(column model (:id fix) %) ["kind" "item" "warehouse" "quantity"])))
    (is (= {:type "operation-correction" :entry (:entry-id import-sale) :stockItemId "tee-m" :warehouseId "main" :instant "2026-11-19T09:59:59Z" :count 3}
           (select-keys (first (:actions (last (:cells fix)))) [:type :entry :stockItemId :warehouseId :instant :count])))
    (testing "once taken it is a pending correction before its sale, and both are submitted together"
      (let [taken {:entry-id    (operations/correction-entry-id (:entry-id import-sale)) :revision 1 :kind "correction"
                   :occurred-at (Instant/parse "2026-11-19T09:59:59Z") :status "pending"
                   :lines       [{:line-id "fix-0" :stock-item-id "tee-m" :warehouse-id "main" :quantity 3}]}
            model (imported {:source "imported" :imported review :pending [purchase import-sale taken] :preview? true})]
        (is (not-any? #(re-find #"operations-suggested" (or (:className %) "")) (:rows model)))
        (is (= [(:entry-id taken) (:entry-id import-sale)] (map :entry-id (:pending model))))
        (is (= ["header" (:entry-id taken) (:entry-id import-sale)]
               (distinct (map #(if (:header %) "header" (:groupId %)) (:rows model)))))))))
