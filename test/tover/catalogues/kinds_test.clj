(ns tover.catalogues.kinds-test
  "Board chapters 09–12: warehouses, suppliers, categories and stores share one lifecycle (register, update,
  archive, restore) and the unique-name rule; decided without a store, plus one store lifecycle."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.realtime]
    [bel.runtime :as runtime]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [tover.catalogues.categories.model :as categories]
    [tover.catalogues.products.model :as products]
    [tover.catalogues.stores.model :as stores]
    [tover.catalogues.suppliers.model :as suppliers]
    [tover.catalogues.warehouses.model :as warehouses]
    [tover.inventory.store-test :as fixture]
    [tover.support :as support]))


(defn register
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (bel/build-registry! ['tover.catalogues.warehouses.model 'tover.catalogues.suppliers.model 'tover.catalogues.categories.model
                        'tover.catalogues.stores.model 'tover.catalogues.products.model 'tover.inventory.stock-items.model 'tover.inventory.entries.model
                        'tover.inventory.acceptance.model 'tover.workspaces.currency.model])
  (f))


(defn unit-authority
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (with-redefs [runtime/with-authority (fn [context _touched _isolation read]
                                         (read (assoc context :principal {:user-id (:user-id context) :memberships (:memberships context)})))]
    (f)))


(use-fixtures :each bel-test/isolated-registry register unit-authority)


(def scope {:workspace-id (:workspace-id fixture/scope)})


(def kinds
  "kind → [id key, register, update, archive, restore, registered event, updated event]"
  {:warehouses [:warehouse-id ::warehouses/register-warehouse ::warehouses/update-warehouse ::warehouses/archive-warehouse
                ::warehouses/restore-warehouse ::warehouses/warehouse-registered ::warehouses/warehouse-updated]
   :suppliers  [:supplier-id ::suppliers/register-supplier ::suppliers/update-supplier ::suppliers/archive-supplier
                ::suppliers/restore-supplier ::suppliers/supplier-registered ::suppliers/supplier-updated]
   :categories [:category-id ::categories/register-category ::categories/update-category ::categories/archive-category
                ::categories/restore-category ::categories/category-registered ::categories/category-updated]
   :stores     [:store-id ::stores/register-store ::stores/update-store ::stores/archive-store
                ::stores/restore-store ::stores/store-registered ::stores/store-updated]})


(defn decide
  {:malli/schema [:=> [:cat :keyword :map :map] :map]}
  [command facts input]
  (runtime/decide command (merge {:scope scope :records [] :references {:warehouses []} :usage {}} facts) input))


(defn rejection
  {:malli/schema [:=> [:cat [:fn fn?]] [:maybe :map]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (ex-data error))))


(defn data
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [plan]
  (mapv #(select-keys % [:type :data]) (:events plan)))


(deftest every-kind-follows-the-catalogue-lifecycle
  (doseq [[kind [id-key register update archive restore registered updated]] kinds
          :let [record (fn [id name more] (merge {id-key id :name name :status "active" :revision 1} more))
                depot (record "d1" "Depot" {})]]
    (testing (name kind)
      (testing "registers with a trimmed name"
        (is (= [{:type registered :data {id-key "d1" :name "Depot" :status "active" :revision 1}}]
               (data (decide register {} {id-key "d1" :name "  Depot "})))))
      (testing "a name held by another record, archived included, rejects regardless of letter case"
        (is (= {:type :name-taken :record {id-key "k" :name "Kiosk" :status "archived"}}
               (select-keys (rejection #(decide register {:records [(record "k" "Kiosk" {:status "archived"})]} {id-key "n" :name "kiosk"}))
                            [:type :record]))))
      (testing "a blank name rejects"
        (is (= :validation (:type (rejection #(decide register {} {id-key "n" :name "  "}))))))
      (testing "renaming to the current name in another case is its own name, not a collision"
        (is (= [{:type updated :data {id-key "d1" :name "DEPOT" :status "active" :revision 2}}]
               (data (decide update {:record depot :records [depot]} {id-key "d1" :revision 1 :name "DEPOT"})))))
      (testing "an unchanged save records nothing"
        (is (= [] (data (decide update {:record depot :records [depot]} {id-key "d1" :revision 1 :name "Depot"})))))
      (testing "a stale edit rejects with the current record"
        (is (= 3 (get-in (rejection #(decide update {:record (assoc depot :revision 3)} {id-key "d1" :revision 1 :name "X"}))
                         [:current :revision]))))
      (testing "archive and restore keep the identity"
        (is (= "archived" (get-in (decide archive {:record depot} {id-key "d1" :revision 1}) [:events 0 :data :status])))
        (is (= :already-archived (:type (rejection #(decide archive {:record (assoc depot :status "archived")} {id-key "d1" :revision 1})))))
        (is (= "active" (get-in (decide restore {:record (assoc depot :status "archived")} {id-key "d1" :revision 1}) [:events 0 :data :status])))
        (is (= :not-archived (:type (rejection #(decide restore {:record depot} {id-key "d1" :revision 1})))))))))


(deftest optional-fields-set-and-clear
  (let [main {:warehouse-id "w" :name "Main" :description "Back room" :purpose "storage" :status "active" :revision 1}]
    (is (= {:warehouse-id "w" :name "Main" :purpose "storage" :status "active" :revision 2}
           (get-in (decide ::warehouses/update-warehouse {:record main :records [main]} {:warehouse-id "w" :revision 1 :description " "})
                   [:events 0 :data]))
        "a blank description clears it")
    (is (= :validation (:type (rejection #(decide ::warehouses/update-warehouse {:record main} {:warehouse-id "w" :revision 1 :purpose "garage"})))))))


(deftest a-store-default-warehouse-must-be-active
  (let [warehouses {:warehouses [{:id "main" :name "Main" :status "active"} {:id "old" :name "Old" :status "archived"}]}]
    (is (= "main" (get-in (decide ::stores/register-store {:references warehouses} {:store-id "s" :name "Shop" :default-warehouse-id "main"})
                          [:events 0 :data :default-warehouse-id])))
    (is (= :warehouse-unavailable
           (:type (rejection #(decide ::stores/register-store {:references warehouses} {:store-id "s" :name "Shop" :default-warehouse-id "old"})))))
    (let [shop {:store-id "s" :name "Shop" :default-warehouse-id "old" :status "active" :revision 1}]
      (is (= [] (data (decide ::stores/update-store {:record shop :records [shop] :references warehouses} {:store-id "s" :revision 1 :name "Shop"})))
          "a saved archived default is kept while it is not changed"))))


(defn context
  {:malli/schema [:=> [:cat :any] :map]}
  [repo]
  (runtime/prepare-context {:bel/system  (support/bel-system {:repo repo}) :repo repo :workspace-id (:workspace-id fixture/scope)
                            :user-id     #uuid "00000000-0000-0000-0000-000000000011"
                            :memberships [{:workspace-id (:workspace-id fixture/scope) :role :member}]}))


(defn execute!
  {:malli/schema [:=> [:cat :map :keyword :string :map] :map]}
  [ctx command command-id input]
  (runtime/execute! ctx command {:command-id command-id :input input}))


(deftest catalogues-through-the-store
  (fixture/with-store
    (fn [repo _]
      (let [ctx (context repo)]
        (execute! ctx ::warehouses/register-warehouse "w1" {:warehouse-id "main" :name "Main" :purpose "storage"})
        (execute! ctx ::warehouses/register-warehouse "w2" {:warehouse-id "old" :name "Old"})
        (execute! ctx ::categories/register-category "c1" {:category-id "kitchen" :name "Kitchen"})
        (testing "uniqueness is checked against stored records"
          (is (= :name-taken (:type (rejection #(execute! ctx ::warehouses/register-warehouse "w3" {:warehouse-id "x" :name " MAIN"}))))))
        (execute! ctx ::stores/register-store "s1" {:store-id "shop" :name "Shop" :default-warehouse-id "old"})
        (execute! ctx ::warehouses/archive-warehouse "a1" {:warehouse-id "old" :revision 1})
        (testing "archived filter and the archived default stays marked"
          (is (= ["Old"] (map :name (:records (runtime/read-model ctx ::warehouses/warehouse-catalogue {:archived? true})))))
          (is (= {:id "old" :name "Old" :archived? true}
                 (:default-warehouse-id (first (:records (runtime/read-model ctx ::stores/store-catalogue {})))))))
        (testing "a product chooses Bel categories and warehouses, never archived ones"
          (is (= :accepted (:status (execute! ctx ::products/register-product "p1" {:product-id "mug" :name "Mug" :category-id "kitchen" :default-warehouse-id "main"}))))
          (is (= :warehouse-unavailable
                 (:type (rejection #(execute! ctx ::products/register-product "p2" {:product-id "cup" :name "Cup" :default-warehouse-id "old"}))))))
        (testing "restore brings back the same warehouse"
          (execute! ctx ::warehouses/restore-warehouse "r1" {:warehouse-id "old" :revision 2})
          (is (= ["Main" "Old"] (map :name (:records (runtime/read-model ctx ::warehouses/warehouse-catalogue {}))))))))))
