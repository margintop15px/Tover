(ns tover.inventory.stock-test
  "Board chapter 13 · Stock table, item history and lineage from accepted records; 32a · shared references block
  archiving."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.runtime :as runtime]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [tover.catalogues.products.model :as products]
    [tover.catalogues.warehouses.model :as warehouses]
    [tover.inventory.acceptance.model :as acceptance]
    [tover.inventory.entries.model :as entries]
    [tover.inventory.stock.model :as stock]
    [tover.inventory.store-test :as fixture]
    [tover.support :as support])
  (:import
    [java.time Instant]))


(use-fixtures :each bel-test/isolated-registry
  (fn [f]
    (bel/build-registry! ['tover.shared.web.core 'tover.catalogues.warehouses.model 'tover.catalogues.categories.model
                          'tover.catalogues.products.model 'tover.inventory.stock-items.model
                          'tover.inventory.entries.model 'tover.inventory.acceptance.model
                          'tover.workspaces.currency.model 'tover.inventory.stock.model])
    (f))
  (fn [f]
    (with-redefs [runtime/with-authority (fn [context _touched _isolation read]
                                           (read (assoc context :principal (select-keys context [:user-id :memberships]))))]
      (f))))


(defn- day
  {:malli/schema [:=> [:cat :int] :any]}
  [n]
  (Instant/parse (format "2026-11-%02dT00:00:00Z" n)))


(def catalogue
  {:products    [{:product-id "tee" :name "T-shirt" :status "active" :revision 1}
                 {:product-id "mug" :name "Mug" :status "active" :revision 1}]
   :stock-items [{:stock-item-id "tee-m"  :product-id "tee" :variant-label "M" :article "T-100" :unit "pcs" :condition "regular"
                  :status        "active" :revision   1}]
   :barcodes    [{:barcode-id "tee-m|4600000000017" :stock-item-id "tee-m" :barcode "4600000000017"}]
   :warehouses  [{:warehouse-id "main" :name "Main" :status "active" :revision 1}
                 {:warehouse-id "shop" :name "Shop" :status "active" :revision 1}]})


(defn- record
  "One accepted record revision with its lines: `[[item warehouse quantity cost] ...]`."
  {:malli/schema [:=> [:cat :string :int :string :int [:sequential :any]] :map]}
  [id revision kind at lines]
  (let [rrid (str id "#" revision)]
    {:record {:record-revision-id rrid :record-id id    :revision    revision :acceptance-id "acc" :kind kind :occurred-at (day at)
              :sequence           0    :cancelled false :recorded-at (day at) :actor         #uuid "00000000-0000-0000-0000-000000000011"}
     :lines  (vec (map-indexed (fn [i [item warehouse quantity cost]]
                                 (cond-> {:record-line-id (str rrid "/" i) :record-revision-id rrid     :position i :stock-item-id item
                                          :warehouse-id   warehouse        :quantity-change    quantity}
                                   cost (assoc :unit-cost cost)))
                               lines))}))


(defn- history
  {:malli/schema [:=> [:cat [:* :map]] :map]}
  [& records]
  {:records (mapv :record records) :lines (into [] (mapcat :lines) records)})


(defn- facts
  {:malli/schema [:=> [:cat :map] :map]}
  [more]
  (merge {:catalogue catalogue :history (history) :pending [] :currency "RUB" :now (day 20)} more))


(defn- item
  {:malli/schema [:=> [:cat :map :string] [:maybe :map]]}
  [table id]
  (some #(when (= id (:stock-item-id %)) %) (mapcat :items (:products table))))


;; 13 · Inspect accepted stock


(deftest product-without-stock-items-hidden-by-default
  (is (= ["T-shirt"] (map :name (:products (stock/table (facts {}) true)))))
  (is (= [["Mug" []] ["T-shirt" ["tee-m"]]]
         (map (juxt :name #(mapv :stock-item-id (:items %))) (:products (stock/table (facts {}) false))))))


(deftest balances-per-warehouse-and-total
  (let [table (stock/table (facts {:history (history (record "a" 1 "opening" 1 [["tee-m" "main" 5 nil]])
                                                     (record "b" 1 "opening" 1 [["tee-m" "shop" 3 nil]]))})
                           true)]
    (is (= ["main" "shop"] (map :id (:warehouses table))))
    (is (= [5 3 8] [(get-in (item table "tee-m") [:cells "main" :quantity]) (get-in (item table "tee-m") [:cells "shop" :quantity])
                    (get-in (item table "tee-m") [:total :quantity])]))
    (is (= 8 (get-in (first (:products table)) [:total :quantity])))))


(deftest money-view-from-opening-cost
  (let [row (item (stock/table (facts {:history (history (record "a" 1 "opening" 1 [["tee-m" "main" 5 "100.00"]]))}) true) "tee-m")]
    (is (= [500.0000M true] ((juxt :value :valued?) (get-in row [:cells "main"]))))
    (is (= [500.0000M "full"] ((juxt :value :valued) (:total row))))
    (is (:unit-locked? row) "a quantity-bearing record locks the unit")
    (is (= ["4600000000017"] (:barcodes row)))))


(deftest unvalued-opening-marks-totals-partial
  (let [table (stock/table (facts {:history (history (record "a" 1 "opening" 1 [["tee-m" "main" 3 nil]])
                                                     (record "b" 1 "opening" 1 [["tee-m" "shop" 2 "10"]]))})
                           true)
        row   (item table "tee-m")]
    (is (= [nil false] ((juxt :value :valued?) (get-in row [:cells "main"]))))
    (is (= [20M "partial"] ((juxt :value :valued) (:total row))))
    (is (= "partial" (get-in (first (:products table)) [:total :valued])))))


(deftest future-dated-work-stays-pending
  (let [pending [{:entry-id "e" :kind "opening" :occurred-at (day 21) :status "pending" :revision 1
                  :lines    [{:line-id "l" :stock-item-id "tee-m" :warehouse-id "main" :quantity 4}]}]
        row     (item (stock/table (facts {:pending pending}) true) "tee-m")]
    (is (= [0 true] ((juxt :quantity :pending?) (get-in row [:cells "main"]))))
    (is (false? (get-in row [:cells "shop" :pending?])))))


;; 13 · Inspect a product's accepted history


(deftest period-filters-rows-but-balance-is-true
  (let [h    (history (record "a" 1 "opening" 1 [["tee-m" "main" 5 nil]]) (record "b" 1 "correction" 15 [["tee-m" "main" 2 nil]]))
        rows (:rows (stock/history-rows (facts {:history h}) {:stock-item-id "tee-m" :from (day 10) :to (day 20)}))]
    (is (= [["b" 2 7]] (map (juxt :record-id :quantity-change :balance-after) rows)))
    (is (= #uuid "00000000-0000-0000-0000-000000000011" (:actor (first rows))))))


(deftest equal-instants-keep-accepted-order
  (let [one  (assoc-in (record "x" 1 "correction" 5 [["tee-m" "main" 1 nil]]) [:record :sequence] 1)
        two  (assoc-in (record "y" 1 "correction" 5 [["tee-m" "main" 2 nil]]) [:record :sequence] 0)
        rows (:rows (stock/history-rows (facts {:history (history (record "a" 1 "opening" 1 [["tee-m" "main" 5 nil]]) one two)})
                                        {:stock-item-id "tee-m"}))]
    (is (= ["a" "y" "x"] (map :record-id rows)))))


(deftest superseded-revision-is-inspectable-not-counted
  (let [h    (history (record "a" 1 "opening" 1 [["tee-m" "main" 5 nil]]) (record "a" 2 "opening" 1 [["tee-m" "main" 6 nil]]))
        rows (:rows (stock/history-rows (facts {:history h}) {:stock-item-id "tee-m"}))]
    (is (= [[2 6 6 false] [1 5 nil true]] (map (juxt :revision :quantity-change :balance-after :superseded?) rows)))
    (is (= 6 (get-in (item (stock/table (facts {:history h}) true) "tee-m") [:cells "main" :quantity])))))


;; 13 · Inspect production lineage


(deftest no-production-yet
  (is (= {:status "none"} (stock/lineage (facts {}) {:stock-item-id "tee-m"}))))


;; Through the store: read models and catalogue usage


(def workspace (:workspace-id fixture/scope))


(defn- context
  {:malli/schema [:=> [:cat :any] :map]}
  [repo]
  (runtime/prepare-context {:bel/system  (support/bel-system {:repo repo})            :repo repo :workspace-id workspace
                            :user-id     #uuid "00000000-0000-0000-0000-000000000011"
                            :memberships [{:workspace-id workspace :role :member}]}))


(defn- execute!
  {:malli/schema [:=> [:cat :map :keyword :string :map] :map]}
  [ctx command command-id input]
  (runtime/execute! ctx command {:command-id command-id :input input}))


(defn- rejection
  {:malli/schema [:=> [:cat [:fn fn?]] [:maybe :map]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (ex-data error))))


(deftest stock-reads-and-shared-references-block-archiving
  (fixture/with-store
    (fn [repo _]
      (let [ctx  (context repo)
            save #(execute! ctx ::entries/save-shared-inventory-entry %1 (merge {:workspace-id workspace              :kind "opening"
                                                                                 :occurred-at  "2026-11-19T00:00:00Z"}
                                                                                %2))]
        (execute! ctx ::warehouses/register-warehouse "w1" {:warehouse-id "main" :name "Main"})
        (execute! ctx ::warehouses/register-warehouse "w2" {:warehouse-id "back" :name "Back"})
        (execute! ctx ::products/register-product "p1" {:product-id "tee" :name "T-shirt"})
        (save "s1" {:entry-id "a" :lines [{:line-id   "a-0"    :product-id "tee" :variant-label "M" :warehouse-id "main" :quantity "5"
                                           :unit-cost "100.00"}]})
        (let [selection {:selection [{:entry-id "a" :revision 1}] :order ["a"]}
              review    (runtime/read-model ctx ::acceptance/inventory-review selection)]
          (execute! ctx ::acceptance/accept-reviewed-inventory-changes "acc-1"
                    (merge selection {:workspace-id workspace :acceptance-id "acc-1" :fingerprint (:fingerprint review)})))
        (testing "the stock table reads accepted records"
          (let [table (runtime/read-model ctx ::stock/stock-table {})]
            (is (= "EUR" (:currency table)))
            (is (= 5 (get-in (item table "item:a-0") [:cells "main" :quantity])))
            (is (= "M" (:variant-label (item table "item:a-0"))) "the registered item keeps its variant")))
        (testing "item history"
          (is (= [["a" 5 5]] (map (juxt :record-id :quantity-change :balance-after)
                                  (:rows (runtime/read-model ctx ::stock/item-history {:stock-item-id "item:a-0"}))))))
        (testing "lineage is reachable and empty"
          (is (= {:status "none"} (runtime/read-model ctx ::stock/item-lineage {:stock-item-id "item:a-0"}))))
        (testing "a warehouse holding stock cannot be archived"
          (is (= :warehouse-has-stock (:type (rejection #(execute! ctx ::warehouses/archive-warehouse "x1" {:warehouse-id "main" :revision 1}))))))
        (testing "a warehouse named by a shared pending entry cannot be archived"
          (save "s2" {:entry-id "b" :lines [{:line-id "b-0" :stock-item-id "item:a-0" :warehouse-id "back"}]})
          (is (= [:pending-reference "b"]
                 ((juxt :type :entry) (rejection #(execute! ctx ::warehouses/archive-warehouse "x2" {:warehouse-id "back" :revision 1}))))))
        (testing "a product with an active stock item cannot be archived; one that had items cannot be deleted"
          (is (= :active-stock-items (:type (rejection #(execute! ctx ::products/archive-product "x3" {:product-id "tee" :revision 1})))))
          (is (= :had-stock-item (:type (rejection #(execute! ctx ::products/delete-product "x4" {:product-id "tee" :revision 1}))))))))))
