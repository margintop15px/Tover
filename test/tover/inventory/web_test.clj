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
                   (assoc-in [:products 0 :items 0 :cells "main" :history?] true)
                   (update :warehouses conj {:id "old" :name "Old" :archived? true})
                   (assoc-in [:products 0 :items 0 :cells "old"] {:quantity 2 :value nil :valued? false :pending? false}))
        model  (web/stock-grid table {:mode "units" :entries []})
        column (fn [k] (nth (cells-of model "tee-m") (.indexOf ^java.util.List (mapv :key (:columns model)) k)))]
    (testing "item without opening offers one"
      (is (= "quantity" (get-in (column "shop") [:editor :type])))
      (is (not (:readOnly (column "shop")))))
    (testing "a cell with history takes a count, not a second opening"
      (is (= "correction" (get-in (column "main") [:target :values "kind"])))
      (is (not (:readOnly (column "main")))))
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


(deftest matched-barcode-is-shown
  (let [coded (assoc catalogue :barcodes [{:stock-item-id "tee-m" :barcode "4600000000017"} {:stock-item-id "tee-m" :barcode "4600000000024"}])]
    (is (= [["T-shirt / M · T-100" "4600000000017"]] (map (juxt :label :hint) (:options (web/suggestions coded "460000000001")))))
    (is (nil? (:hint (first (:options (web/suggestions coded "T-sh"))))) "no barcode matched, no hint")))


(deftest product-brings-its-default-warehouse
  (let [stocked (-> catalogue
                    (assoc :warehouses [{:warehouse-id "main" :name "Main" :status "active"} {:warehouse-id "old" :name "Old" :status "archived"}])
                    (assoc-in [:products 1 :default-warehouse-id] "main"))]
    (is (= "main" (:warehouseId (first (:options (web/suggestions stocked "Mug"))))))
    (is (nil? (:warehouseId (first (:options (web/suggestions (assoc-in stocked [:products 1 :default-warehouse-id] "old") "Mug")))))
        "an archived default is not filled"))
  (testing "the new row marks the default warehouse cell"
    (let [model  (web/stock-grid table {:mode "units" :entries [] :scope {:productId "tee" :newRow true :warehouseId "shop"}})
          column (fn [model row k] (nth (cells-of model row) (.indexOf ^java.util.List (mapv :key (:columns model)) k)))]
      (is (re-find #"stock-default-warehouse" (:className (column model "new-row:tee" "shop"))))
      (is (not (re-find #"stock-default-warehouse" (str (:className (column model "new-row:tee" "main")))))))))


(deftest lookup-failure-is-not-no-match
  (is (= {:text "T-sh" :failed true :options []} (web/suggestions nil "T-sh"))))


;; 15 · Read the stock requiring correction; propose a stock correction


(def ^:private counted
  (assoc-in table [:products 0 :items 0 :cells "main" :history?] true))


(defn- column
  {:malli/schema [:=> [:cat :map :string :string] :map]}
  [model row k]
  (nth (cells-of model row) (.indexOf ^java.util.List (mapv :key (:columns model)) k)))


(def ^:private correction
  {:entry-id "c" :revision 2 :kind "correction"
   :lines    [{:line-id "c/main" :stock-item-id "tee-m" :warehouse-id "main" :quantity 7}]})


(deftest count-offered-where-history-exists
  (let [model (web/stock-grid counted {:mode "units" :entries []})
        main  (column model "tee-m" "main")]
    (is (= {"kind" "correction" "stock-item-id" "tee-m" "warehouse-id" "main"} (select-keys (get-in main [:target :values]) ["kind" "stock-item-id" "warehouse-id"])))
    (is (= 0 (get-in main [:target :revision])))
    (is (= (get-in main [:target :entry]) (get-in (column model "tee-m" "article") [:target :entry])) "one row, one entry")
    (is (nil? (get-in (column model "tee-m" "shop") [:target :values "kind"])) "an empty cell still takes an opening")))


(deftest pending-count-shows-accepted-and-counted
  (let [model (web/stock-grid counted {:mode "units" :entries [correction]})
        main  (column model "tee-m" "main")]
    (is (= ["5 → " "7"] ((juxt :prefix :value) main)))
    (is (= ["c" 2 false] ((juxt :entry :revision :shared) (:target main))))
    (is (= "10" (:value (column model "tee-m" "total"))) "the total counts 7 instead of 5")
    (is (= [{:entry-id "c" :revision 2 :rowId "tee-m"}] (:pending model)))))


(deftest item-fields-propose-changes
  (let [model (web/stock-grid counted {:mode "units" :entries [(assoc correction :changes [{:stock-item-id "tee-m" :article "T-101"}])]})]
    (is (= ["T-100 → " "T-101"] ((juxt :prefix :value) (column model "tee-m" "article"))))
    (is (= "article" (get-in (column model "tee-m" "article") [:target :field])))
    (is (true? (get-in (column model "tee-m" "main") [:target :shared])) "the entry holds more than this count")
    (is (= "variant" (get-in (column model "tee-m" "item") [:target :field])))))


(deftest unit-cell-follows-the-lock
  (let [locked   (column (web/stock-grid counted {:mode "units" :entries []}) "tee-m" "unit")
        unlocked (column (web/stock-grid (assoc-in counted [:products 0 :items 0 :unit-locked?] false) {:mode "units" :entries []}) "tee-m" "unit")]
    (is (:readOnly locked))
    (is (re-find #"Production" (:title locked)))
    (is (= ["" "pcs" "kg" "g" "l" "ml" "m" "pack"] (map :value (get-in unlocked [:editor :options]))))))


(deftest a-move-stays-under-its-old-product-marked-pending
  (let [model (web/stock-grid counted {:mode "units" :entries [{:entry-id "c" :revision 1 :kind "correction" :lines [] :changes [{:stock-item-id "tee-m" :product-id "mug"}]}]})]
    (is (some #(= "tee-m" (:id %)) (:rows model)))
    (is (re-find #"move pending" (str (:value (column model "tee-m" "item")) (:display (column model "tee-m" "item")))))))


(deftest row-actions-move-archive-restore
  (let [labels (fn [model] (set (map :field (:actions (column model "tee-m" "item")))))]
    (is (= #{"move-to" "archive"} (disj (labels (web/stock-grid counted {:mode "units" :entries []})) nil)))
    (is (= "tee" (:current (some #(when (= "move-to" (:field %)) %) (:actions (column (web/stock-grid counted {:mode "units" :entries []}) "tee-m" "item")))))
        "Move knows the current product so the dialog leaves it out")
    (let [archived (web/stock-grid (assoc-in counted [:products 0 :items 0 :archived?] true) {:mode "units" :entries []})]
      (is (= #{"restore"} (disj (labels archived) nil)))
      (is (:readOnly (column archived "tee-m" "main")) "an archived item is read-only"))))


(def ^:private item-catalogue
  {:products    [{:product-id "tee" :name "T-shirt" :status "active"} {:product-id "mug" :name "Mug" :status "active"}]
   :stock-items [{:stock-item-id "tee-m" :product-id "tee" :variant-label "M" :article "T-100" :unit "pcs" :status "active"}]
   :barcodes    [{:stock-item-id "tee-m" :barcode "4600000000017"}]
   :warehouses  [{:warehouse-id "main" :name "Main" :status "active"}]})


(deftest correction-input-builds-one-entry-per-row
  (let [input (fn [values saved] (web/correction-input item-catalogue workspace "c" 2 (merge {"kind" "correction" "stock-item-id" "tee-m"} values) saved))]
    (is (= {:workspace-id workspace :entry-id "c" :kind "correction" :expected-revision 2 :occurred-at "2026-11-19T00:00:00Z"
            :lines        [{:line-id "c/main" :stock-item-id "tee-m" :warehouse-id "main" :quantity "7"}] :changes []}
           (input {"warehouse-id" "main" "line-id" "c/main" "quantity" "7" "occurred-at" "2026-11-19T00:00:00Z"} nil)))
    (testing "a field change joins the saved count"
      (is (= [{:stock-item-id "tee-m" :article "T-101"}]
             (:changes (input {"line-id" "c/change" "article" "T-101"} {:lines (:lines correction)}))))
      (is (= (:lines correction) (:lines (input {"line-id" "c/change" "article" "T-101"} {:lines (:lines correction)})))))
    (testing "typing the accepted value back drops the change"
      (is (nil? (input {"line-id" "c/change" "article" "T-100"} {:changes [{:stock-item-id "tee-m" :article "T-101"}]}))))
    (testing "barcodes become additions and removals; 0 is dropped"
      (is (= [{:stock-item-id "tee-m" :barcodes-added ["4600000000024"] :barcodes-removed ["4600000000017"]}]
             (:changes (input {"barcodes" "4600000000024, 0"} nil)))))
    (testing "move names an active product"
      (is (= [{:stock-item-id "tee-m" :product-id "mug"}] (:changes (input {"move-to" " mug"} nil))))
      (is (= [{:stock-item-id "tee-m" :product-id "mug" :variant-label "M2"}] (:changes (input {"move-to" "mug" "variant" "M2"} nil)))
          "the dialog sends the product id and a new label in one edit"))
    (testing "archive, and keeping it again"
      (is (= [{:stock-item-id "tee-m" :archive true}] (:changes (input {"archive" "true"} nil))))
      (is (nil? (input {"archive" ""} {:changes [{:stock-item-id "tee-m" :archive true}]}))))
    (testing "clearing the count removes the line"
      (is (nil? (input {"warehouse-id" "main" "line-id" "c/main" "quantity" ""} {:lines (:lines correction)}))))))


(deftest emptied-correction-is-discarded
  (with-redefs [runtime/read-model (fn [_ id _] (if (= id ::entries/pending-entries) {:entries [correction]} item-catalogue))]
    (is (= ::entries/discard-inventory-draft
           (:id (web/save-entry {} {:workspaceId workspace :entryId "c" :revision 2 :commandId "c1"
                                    :values      {"kind" "correction" "stock-item-id" "tee-m" "warehouse-id" "main" "line-id" "c/main" "quantity" ""}}))))))


(deftest submit-keeps-a-correction-whole
  (let [entry (assoc correction :changes [{:stock-item-id "tee-m" :archive true}])]
    (with-redefs [runtime/read-model (fn [& _] {:entries [entry]})]
      (is (= {:kind "correction" :lines (:lines entry) :changes (:changes entry) :reason "Count"}
             (select-keys (:input (web/describe-entry {} {:workspaceId workspace :entryId "c" :revision 2 :occurredAt "" :reason "Count" :commandId "d"}))
                          [:kind :lines :changes :reason]))))))


;; 16 · Read draft findings on the stock table


(defn- marked
  {:malli/schema [:=> [:cat :map] [:maybe :string]]}
  [cell]
  (some->> (:className cell) (re-find #"stock-finding-\w+")))


(deftest findings-mark-the-affected-cells
  (let [sale  [{:entry-id "o" :revision 1 :kind "opening" :lines [{:line-id "l" :stock-item-id "tee-m" :warehouse-id "shop" :quantity 2}]}]
        model (web/stock-grid counted {:mode     "units" :entries (conj sale (assoc correction :changes [{:stock-item-id "tee-m" :unit "kg" :archive true}]))
                                       :findings [{:entry-id "o" :line-id "l" :field :quantity :code :insufficient-stock :level :warning}
                                                  {:entry-id "c" :line-id nil :stock-item-id "tee-m" :field :unit :code :unit-locked :level :error}
                                                  {:entry-id "c"                    :line-id nil :stock-item-id "tee-m" :field :status :code :archive-blocked :level :error
                                                   :related  {:warehouse-id "main"}}]})]
    (is (= "stock-finding-warning" (marked (column model "tee-m" "shop"))))
    (is (re-find #"Exceeds available stock" (:title (column model "tee-m" "shop"))))
    (is (= "stock-finding-error" (marked (column model "tee-m" "unit"))))
    (is (re-find #"Cannot archive: stock in Main" (:title (column model "tee-m" "item"))))
    (is (re-find #"stock-row-error" (:className (column model "tee-m" "item"))) "the row shows its highest level")
    (is (nil? (marked (column model "tee-m" "article"))) "unaffected cells stay plain")))


(deftest findings-on-new-rows-name-the-related-record
  (let [entry [{:entry-id "e" :revision 1 :kind "opening" :lines [{:line-id "l" :product-id "tee" :variant-label "L" :warehouse-id "shop" :quantity 1}]}]
        model (web/stock-grid table {:mode     "units" :entries entry
                                     :findings [{:entry-id "e" :line-id "l" :field :variant-label :code :new-reference :level :info}
                                                {:entry-id "e" :line-id "l" :field :barcodes :code :barcode-conflict :level :error
                                                 :related  {:id "mug" :name "Mug"}}]})]
    (is (= "stock-finding-info" (marked (column model "new:tee:L" "item"))))
    (is (re-find #"belongs to Mug" (:title (column model "new:tee:L" "barcodes"))))))


(deftest messages-name-the-values-items-and-entries
  (let [names {:warehouse {"main" "Main"} :product {"mug" "Mug"} :item {"tee-l" "T-shirt / L"}
               :entry     {"o" "Opening · T-shirt / M · Main"}}]
    (is (= "The move conflicts with T-100, kept by T-shirt / L."
           (web/finding-text {:code :move-conflict :related {:value "T-100" :other-item "tee-l"}} names)))
    (is (= "Cannot archive: pending Opening · T-shirt / M · Main."
           (web/finding-text {:code :archive-blocked :related {:other-entry-id "o"}} names)))
    (testing "Submit uses the same words for a change problem"
      (is (= "The move conflicts with T-100, kept by T-shirt / L."
             (web/problem-message {:entry-id "c" :line-id nil :code :move-conflict :stock-item-id "tee-m" :field :article :value "T-100" :other-item "tee-l"} names)))
      (is (= "This barcode belongs to T-shirt."
             (web/problem-message {:entry-id "c" :line-id nil :code :uniqueness-conflict :stock-item-id "mug-1" :field :barcodes :value "4600000000017" :owner-product "T-shirt"} names)))
      (is (= "Set the stock item's unit first." (web/problem-message {:entry-id "c" :line-id "l" :code :unit-required} names))))))


(deftest entry-names-describe-pending-work
  (is (= {"o" "Opening · T-shirt / M · Main · 5" "c" "Correction · T-shirt / M"}
         (web/entry-names [{:entry-id "o" :kind "opening" :lines [{:stock-item-id "tee-m" :warehouse-id "main" :quantity 5}]}
                           {:entry-id "c" :kind "correction" :lines [] :changes [{:stock-item-id "tee-m" :archive true}]}]
                          {"tee-m" "T-shirt / M"} {"main" "Main"}))))
