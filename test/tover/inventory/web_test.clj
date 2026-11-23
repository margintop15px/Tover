(ns tover.inventory.web-test
  "Board chapters 13, 14, 16, 17 and 32a on the stock page: grid models and one command descriptor per action."
  (:require
    [bel.runtime :as runtime]
    [clojure.test :refer [deftest is testing]]
    [tover.inventory.acceptance.model :as acceptance]
    [tover.inventory.entries.model :as entries]
    [tover.inventory.stock.model :as stock]
    [tover.inventory.web :as web]))


(def workspace #uuid "00000000-0000-0000-0000-000000000001")


(def table
  {:currency   "RUB"
   :warehouses [{:id "main" :name "Main"} {:id "shop" :name "Shop"}]
   :products   [{:product-id "tee" :name "T-shirt" :archived? false
                 :items      [{:stock-item-id "tee-m" :variant-label "M" :article "T-100" :barcodes [] :unit-locked? true :archived? false
                               :cells         {"main" {:quantity 5 :value 500.0000M :valued? true :pending? false}
                                               "shop" {:quantity 3 :value nil :valued? false :pending? true}}
                               :total         {:quantity 8 :value 500.0000M :valued "partial"}}]
                 :total      {:quantity 8 :value 500.0000M :valued "partial"}}]})


(defn- cells-of
  {:malli/schema [:=> [:cat :map :string] [:vector :map]]}
  [model id]
  (:cells (some #(when (= id (:id %)) %) (:rows model))))


(defn- cells
  {:malli/schema [:=> [:cat :map :string] [:vector :any]]}
  [model id]
  (mapv #(or (:display %) (:value %)) (:cells (some #(when (= id (:id %)) %) (:rows model)))))


(def ^:private opening
  [{:entry-id "e" :revision 1 :kind "opening" :lines [{:product-id "tee" :variant-label "L" :warehouse-id "shop" :quantity 2 :unit-cost "10"}]}])


(deftest stock-grid-is-one-table-with-pending-openings
  (let [model (web/stock-grid table {:mode "units" :entries opening})]
    (is (= ["product" "item" "article" "barcodes" "unit" "unit-cost" "main" "shop" "total"] (map :key (:columns model))))
    (is (= ["tee-m" "new:tee:L"] (map :id (:rows model))) "a row per stock item; a new item has its own row")
    (is (= [{:row 0 :col 0 :rowspan 2}] (:merges model)) "the product spans its items")
    (is (= ["T-shirt" "M" "T-100" "" "" "100.00" "5" "3" "8"] (cells model "tee-m")))
    (is (= ["" "L" "" "" "" "10" "" "2" "2"] (cells model "new:tee:L")) "the pending opening and its total")
    (is (= [{:entry-id "e" :revision 1 :rowId "new:tee:L"}] (:pending model)))
    (is (= "quantity" (get-in (cells-of model "tee-m") [6 :editor :type])) "warehouse cells take whole quantities")))


(deftest pending-covers-every-opening
  (let [entry  (fn [id & lines] {:entry-id id :revision 1 :lines (vec (map-indexed #(assoc %2 :line-id (str id "-" %1)) lines))})
        line   (fn [quantity warehouse] (cond-> {:stock-item-id "tee-m" :quantity quantity :unit-cost "2"} warehouse (assoc :warehouse-id warehouse)))
        grid   (fn [mode & entries] (web/stock-grid table {:mode mode :scope {:pending true} :entries entries}))
        column (fn [model k] (nth (cells-of model "tee-m") (.indexOf ^java.util.List (mapv :key (:columns model)) k)))]
    (testing "Money previews the same openings as Units"
      (is (= [{:entry-id "a" :revision 1 :rowId "tee-m"}] (:pending (grid "money" (entry "a" (line 5 "main")))))))
    (testing "two openings of one pair show their sum, read-only, and are both pending"
      (let [model (grid "units" (entry "a" (line 5 "main")) (entry "b" (line 3 "main")))]
        (is (= ["a" "b"] (map :entry-id (:pending model))))
        (is (= "8" (:value (column model "main"))))
        (is (:readOnly (column model "main")))))
    (testing "a line without a warehouse has its own column and cannot be submitted"
      (let [model (grid "units" (entry "a" (line 5 nil)))]
        (is (= "5" (:value (column model "no-warehouse"))))
        (is (= [{:entry-id "a" :revision 1 :rowId "tee-m" :blocked true}] (:pending model))))
      (is (not-any? #{"no-warehouse"} (map :key (:columns (grid "units" (entry "a" (line 5 "main"))))))))
    (testing "discarding a row removes its lines only"
      (let [discards (fn [& lines] (->> (cells-of (grid "units" (apply entry "a" lines)) "tee-m") (mapcat :actions)
                                        (some #(when (= "stock-discard-row" (:type %)) (:entries %))) (map (juxt :line :shared))))]
        (is (= [["a-0" false]] (discards (line 5 "main") (line 2 "shop"))) "the whole entry is this row")
        (is (= [["a-0" true]] (discards (line 5 "main") {:product-id "tee" :variant-label "L" :warehouse-id "shop" :quantity 1})))))
    (testing "filters hide rows, not pending openings"
      (is (= [{:entry-id "a" :revision 1 :rowId "tee-m"}]
             (:pending (web/stock-grid table {:mode "units" :scope {:pending true :productId "other"} :entries [(entry "a" (line 5 "main"))]})))))))


(deftest editing-one-line-keeps-the-others
  (let [saved [{:line-id "e-0" :stock-item-id "tee-m" :warehouse-id "main" :quantity 5}
               {:line-id "e-1" :stock-item-id "tee-m" :warehouse-id "back" :quantity 2 :barcodes []}]
        input (fn [values] (:lines (web/entry-input {:stock-items [{:stock-item-id "tee-m" :status "active"}]
                                                     :warehouses  [{:warehouse-id "main" :status "active"}]}
                                                    workspace "e" 1 values saved)))]
    (is (= [{:line-id "e-0" :stock-item-id "tee-m" :warehouse-id "main" :quantity "7"} (second saved)]
           (input {"line-id" "e-0" "stock-item-id" "tee-m" "warehouse-id" "main" "quantity" "7"})))
    (is (= [(second saved)] (input {"line-id" "e-0" "remove" "true"})) "one line can be discarded")))


(deftest money-mode-shows-amounts
  (let [model (web/stock-grid table {:mode "money" :entries opening})]
    (is (= ["500.00" "—" "500.00"] (subvec (cells model "tee-m") 6)))
    (is (= ["" "20.00" "20.00"] (subvec (cells model "new:tee:L") 6)))
    (is (:partial (peek (cells-of model "tee-m"))) "the total includes unvalued stock")
    (is (not (:partial (peek (cells-of model "new:tee:L")))))
    (is (= "—" (peek (cells (web/stock-grid table {:mode "money" :entries (update-in opening [0 :lines 0] dissoc :unit-cost)}) "new:tee:L")))
        "a wholly unvalued total is not zero")))


(deftest submit-sets-time-and-reason
  (let [at    (java.time.Instant/parse "2026-11-19T10:00:00Z")
        lines [{:line-id "e-0" :stock-item-id "tee-m" :quantity 5} {:line-id "e-1" :stock-item-id "tee-m" :quantity 2}]
        input (fn [values]
                (with-redefs [runtime/read-model (fn [& _] {:entries [{:entry-id "e" :revision 3 :occurred-at at :reason "Count" :lines lines}]})]
                  (:input (web/describe-entry {} (merge {:workspaceId workspace :entryId "e" :revision 3 :commandId "c"} values)))))]
    (is (= {:workspace-id workspace              :entry-id "e"         :kind "opening" :expected-revision 3 :lines lines
            :occurred-at  "2026-11-01T00:00:00Z" :reason   "Stocktake"}
           (input {:occurredAt "2026-11-01T00:00:00Z" :reason "Stocktake"})))
    (testing "an unset value keeps the opening's own"
      (is (= ["2026-11-19T10:00:00Z" "Stocktake"] ((juxt :occurred-at :reason) (input {:occurredAt "" :reason "Stocktake"}))))
      (is (= "Count" (:reason (input {:occurredAt "2026-11-01T00:00:00Z" :reason " "})))))))


(deftest stock-page-hides-products-without-items-by-default
  (let [inputs (atom [])]
    (with-redefs [runtime/read-model (fn [_ id input] (swap! inputs conj [id input]) (if (= id ::stock/stock-table) table {:entries [] :findings []}))]
      (web/query-page {} {})
      (is (some #{[::stock/stock-table {:hide-empty? true}]} @inputs)))))


(deftest typed-names-resolve-to-existing-records
  (let [catalogue {:products    [{:product-id "tee" :name "T-shirt" :status "active"}]
                   :stock-items [{:stock-item-id "tee-m" :product-id "tee" :variant-label "M" :status "active"}]
                   :warehouses  [{:warehouse-id "main" :name "Main" :status "active"}]}]
    (is (= {:workspace-id workspace :entry-id "e" :kind "opening" :occurred-at "2026-11-19T00:00:00Z"
            :lines        [{:line-id "e-0" :stock-item-id "tee-m" :warehouse-id "main" :quantity "5" :unit-cost "100"}]}
           (web/entry-input catalogue workspace "e" 0 {"product"     " t-shirt"             "variant" "m" "warehouse" "MAIN" "quantity" "5" "unit-cost" "100"
                                                       "occurred-at" "2026-11-19T00:00:00Z"})))
    (testing "an unknown variant is a new item of the product; an unknown warehouse a new one"
      (is (= {:workspace-id workspace :entry-id "e" :kind "opening" :expected-revision 2
              :lines        [{:line-id "e-0" :product-id "tee" :variant-label "L" :barcodes ["4600000000031"] :warehouse-name "Back"}]}
             (web/entry-input catalogue workspace "e" 2 {"product" "T-shirt" "variant" "L" "barcodes" "4600000000031, 0" "warehouse" "Back"}))))))


(deftest actions-return-command-descriptors
  (with-redefs [runtime/execute!   (fn [& _] (throw (Exception. "Action must be pure")))
                runtime/read-model (fn [& _] {:products [] :stock-items [] :warehouses []})]
    (is (= {:type  :command :id ::entries/save-shared-inventory-entry :command-id "c1"
            :input {:workspace-id workspace :entry-id "e" :kind "opening" :lines [{:line-id "e-0" :product-name "Cup"}]}
            :then  {:type :view :id ::web/saved :input {:commandId "c1"}}
            :catch {:type :view :id ::web/failed :input {:commandId "c1"}}}
           (web/save-entry {} {:workspaceId workspace :entryId "e" :revision 0 :values {"product" "Cup"} :commandId "c1"})))
    (is (= {:type  :command :id ::entries/discard-inventory-draft :command-id "c2"
            :input {:workspace-id workspace :entry-id "e" :revision 3}
            :then  {:type :view :id ::web/saved :input {:commandId "c2"}}
            :catch {:type :view :id ::web/failed :input {:commandId "c2"}}}
           (web/discard-entry {} {:workspaceId workspace :entryId "e" :revision 3 :commandId "c2"})))
    (let [selection [{:entry-id "a" :revision 1}]]
      (is (= {:type  :command :id ::acceptance/accept-reviewed-inventory-changes :command-id "c3"
              :input {:workspace-id workspace :acceptance-id "c3" :selection selection :order ["a"] :fingerprint "f"}
              :then  {:type :view :id ::web/saved :input {:commandId "c3"}}
              :catch {:type :view :id ::web/failed :input {:commandId "c3"}}}
             (web/accept {} {:workspaceId workspace :selection selection :order ["a"] :fingerprint "f" :commandId "c3"}))))))


;; 14 · Read opening-stock facts and catalogue choices


(deftest opening-is-offered-once-per-cell
  (let [table  (-> table
                   (assoc-in [:products 0 :items 0 :cells "main" :opened?] true)
                   (update :warehouses conj {:id "old" :name "Old" :archived? true})
                   (assoc-in [:products 0 :items 0 :cells "old"] {:quantity 2 :value nil :valued? false :pending? false}))
        model  (web/stock-grid table {:mode "units" :entries []})
        column (fn [k] (nth (cells-of model "tee-m") (.indexOf ^java.util.List (mapv :key (:columns model)) k)))]
    (testing "item without opening offers one"
      (is (= "quantity" (get-in (column "shop") [:editor :type])))
      (is (not (:readOnly (column "shop")))))
    (testing "existing opening blocks another; correction is offered instead"
      (is (:readOnly (column "main")))
      (is (re-find #"correction" (:title (column "main")))))
    (testing "archived warehouse not offered"
      (is (:readOnly (column "old"))))))


;; 32a · Read inventory drafts and active catalogue suggestions


(def ^:private catalogue
  {:products    [{:product-id "tee" :name "T-shirt" :status "active"} {:product-id "mug" :name "Mug" :status "active"}]
   :stock-items [{:stock-item-id "tee-m" :product-id "tee" :variant-label "M" :article "T-100" :status "active"}
                 {:stock-item-id "tee-l" :product-id "tee" :variant-label "L" :status "active"}]
   :barcodes    []
   :warehouses  []})


(deftest suggestions-show-two-kinds
  (is (= [["item" "T-shirt / M · T-100"] ["item" "T-shirt / L"] ["product" "T-shirt"]]
         (map (juxt :kind :label) (:options (web/suggestions catalogue "T-sh")))))
  (is (= [["product" "Mug"]] (map (juxt :kind :label) (:options (web/suggestions catalogue "Mug")))) "a product without items")
  (is (true? (:exact (web/suggestions catalogue "mug")))))


(deftest lookup-failure-is-not-no-match
  (is (= {:text "T-sh" :failed true :options []} (web/suggestions nil "T-sh"))))
