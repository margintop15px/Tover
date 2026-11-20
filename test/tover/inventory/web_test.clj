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


(defn- cells
  {:malli/schema [:=> [:cat :map :string] [:vector :any]]}
  [model id]
  (mapv #(or (:display %) (:value %)) (:cells (some #(when (= id (:id %)) %) (:rows model)))))


(deftest stock-grid-groups-items-under-products
  (let [model (web/stock-grid table {:mode "units"})]
    (is (= ["item" "main" "shop" "total"] (map :key (:columns model))))
    (is (= ["product:tee" "tee-m"] (map :id (:rows model))) "a product group row, then its items")
    (is (= ["T-shirt" "" "" "8"] (cells model "product:tee")) "the group row shows the total")
    (is (= ["M · T-100" "5" "3" "8"] (cells model "tee-m")))
    (is (= "item-history" (get-in (first (:cells (second (:rows model)))) [:actions 0 :type])))
    (is (re-find #"stock-cell-pending" (:className (get-in (second (:rows model)) [:cells 2])))
        "a cell touched by pending work is marked")))


(deftest money-mode-dashes-unvalued-and-marks-partial-totals
  (let [model (web/stock-grid table {:mode "money"})
        total (last (:cells (second (:rows model))))]
    (is (= ["M · T-100" "500.00" "—" "500.00"] (cells model "tee-m")))
    (is (:partial total))
    (is (:partial (last (:cells (first (:rows model))))))))


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
