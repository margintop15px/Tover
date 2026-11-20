(ns tover.catalogues.grid-test
  "Catalogue grids (board chapters 08–12): cells per column type, one command descriptor per edited cell."
  (:require
    [bel.runtime :as runtime]
    [clojure.test :refer [deftest is testing]]
    [tover.catalogues.categories.model :as categories]
    [tover.catalogues.grid :as grid]
    [tover.catalogues.products.model :as products]
    [tover.catalogues.stores.model :as stores]
    [tover.catalogues.suppliers.model :as suppliers]
    [tover.catalogues.warehouses.model :as warehouses]))


(defn- edit
  {:malli/schema [:=> [:cat :string :string :string :int] :map]}
  [kind path value revision]
  (grid/save {} {:kind kind :id "r1" :revision revision :path path :value value :commandId "c1"}))


(deftest cell-edits-return-command-descriptors
  (with-redefs [runtime/execute! (fn [& _] (throw (Exception. "Action must be pure")))]
    (testing "the new row registers by its name"
      (is (= {:type  :command :id ::products/register-product :command-id "c1" :input {:product-id "r1" :name "Mug"}
              :then  {:type :view :id ::grid/saved :input {:kind "products" :id "r1" :commandId "c1"}}
              :catch {:type :view :id ::grid/failed :input {:kind "products" :commandId "c1"}}}
             (edit "products" "name" "Mug" 0)))
      (is (= [::warehouses/register-warehouse ::suppliers/register-supplier ::categories/register-category ::stores/register-store]
             (map #(:id (edit % "name" "X" 0)) ["warehouses" "suppliers" "categories" "stores"]))))
    (testing "one field updates; blank clears it"
      (is (= {:product-id "r1" :revision 3 :default-warehouse-id nil} (:input (edit "products" "default-warehouse-id" "" 3))))
      (is (= {:warehouse-id "r1" :revision 3 :purpose "sales"} (:input (edit "warehouses" "purpose" "sales" 3))))
      (is (= {:supplier-id "r1" :revision 3 :contact "+1"} (:input (edit "suppliers" "contact" "+1" 3))))
      (is (= {:store-id "r1" :revision 3 :default-warehouse-id "main"} (:input (edit "stores" "default-warehouse-id" "main" 3)))))
    (testing "status archives or restores; only products delete"
      (is (= [::products/archive-product ::products/restore-product ::products/delete-product]
             (map #(:id (edit "products" "status" % 3)) ["archived" "active" "deleted"])))
      (is (= [::categories/archive-category ::categories/restore-category]
             (map #(:id (edit "categories" "status" % 3)) ["archived" "active"])))
      (is (= :signals (:type (edit "categories" "status" "deleted" 3)))))
    (testing "a field the kind lacks and a typed reference name are answered, never run"
      (is (= :signals (:type (edit "categories" "purpose" "sales" 3))))
      (is (= :signals (:type (grid/save {} {:kind      "stores" :id "r1" :revision 3 :path "default-warehouse-id" :value "" :referenceName "New"
                                            :commandId "c1"})))))))


(deftest grid-shows-products-and-marks-archived-references
  (let [row   {:id                   "mug" :name "Mug" :status "active" :revision 1 :can-delete? false :category-id nil
               :default-warehouse-id {:id "old" :name "Old" :archived? true}}
        model (grid/grid "products" [row (assoc row :id "cup" :name "Cup")] {:query "mu"})
        cells (:cells (first (:rows model)))]
    (is (= ["mug"] (map :id (:rows model))) "search filters by name")
    (is (= "Old (archived)" (:display (nth cells 2))) "a retained archived warehouse stays visible and marked")
    (is (= ["active" "archived"] (map :value (get-in (nth cells 3) [:editor :options]))) "Delete only while eligible")
    (is (= {:type "catalogue-edit" :id "mug" :rowId "mug" :revision 1 :path "category-id" :kind "products" :lookupKind "categories"}
           (:target (nth cells 1))))))


(deftest every-kind-builds-its-columns
  (let [row   {:id "w" :name "Main" :status "active" :revision 1 :purpose "storage" :can-delete? true}
        cells (:cells (grid/grid-row "warehouses" row))]
    (is (= ["Main" "" "storage" "active"] (map :value cells)))
    (is (= "select" (get-in (nth cells 2) [:editor :type])) "purpose is a select")
    (is (= ["active" "archived"] (map :value (get-in (nth cells 3) [:editor :options]))) "warehouses never delete"))
  (is (= ["name" "address" "contact" "status"] (map :key (:columns (grid/grid "suppliers" [] {})))))
  (is (= ["name" "status"] (map :key (:columns (grid/grid "categories" [] {})))))
  (is (= "warehouses" (get-in (grid/grid-row "stores" {:id "s" :name "Shop" :status "active" :revision 1})
                              [:cells 1 :target :lookupKind]))))


(deftest unavailable-catalogue-is-not-an-empty-grid
  (is (re-find #"Retry" (str (:html (grid/render {:kind "stores" :archived? false :rows nil})))))
  (let [html (str (:html (grid/render {:kind "stores" :archived? false :rows []})))]
    (is (re-find #"catalogue-workbench" html))
    (is (re-find #"/catalogues/warehouses" html) "kind links")
    (is (re-find #"/catalogues/stores\?archived=true" html))))
