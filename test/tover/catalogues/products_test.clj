(ns tover.catalogues.products-test
  "Board chapter 08 · Manage products: one test per slice example, decided without a store,
  plus store tests for name uniqueness at commit and the catalogue reads."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.realtime]
    [bel.runtime :as runtime]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [tover.catalogues.products.model :as model]
    [tover.support :as support]))


(defn register
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (bel/build-registry! ['tover.shared.web.core 'tover.catalogues.warehouses.model 'tover.catalogues.suppliers.model
                        'tover.catalogues.stores.model 'tover.catalogues.categories.model
                        'tover.catalogues.products.model 'tover.inventory.stock-items.model 'tover.inventory.entries.model 'tover.marketplace.provenance
                        'tover.inventory.acceptance.model 'tover.workspaces.currency.model])
  (f))


(defn unit-authority
  "Membership guards run in the store integration suite; here the principal comes from the context."
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (with-redefs [runtime/with-authority (fn [context _touched _isolation read]
                                         (read (assoc context :principal {:user-id (:user-id context) :memberships (:memberships context)})))]
    (f)))


(use-fixtures :each bel-test/isolated-registry register unit-authority)


(def scope {:workspace-id (:workspace-id support/scope)})


(defn product
  {:malli/schema [:=> [:cat :string :string :map] :map]}
  [id name more]
  (merge {:product-id id :name name :status "active" :revision 1} more))


(def mug (product "mug" "Mug" {}))


(defn decide
  {:malli/schema [:=> [:cat :keyword :map :map] :map]}
  [command facts input]
  (runtime/decide command (merge {:scope scope :products [] :usage {} :references {:categories [] :warehouses []}}
                                 facts)
                  input))


(defn rejection
  {:malli/schema [:=> [:cat [:fn fn?]] [:maybe :map]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (ex-data error))))


(defn data
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [plan]
  (mapv #(select-keys % [:type :data]) (:events plan)))


;; Register a product


(deftest product-without-stock
  (is (= [{:type ::model/product-registered
           :data {:product-id "mug" :name "Mug" :category-id "kitchen" :status "active" :revision 1}}]
         (data (decide ::model/register-product {:references {:categories [{:id "kitchen" :name "Kitchen"}] :warehouses []}}
                       {:product-id "mug" :name "  Mug " :category-id "kitchen"})))))


(deftest default-warehouse-prefills-draft-lines
  (let [row (fn [archived?] {:default-warehouse {:id "main" :name "Main" :archived? archived?}})]
    (is (= "main" (model/prefill-warehouse (row false))))
    (testing "an archived default warehouse prefills nothing"
      (is (nil? (model/prefill-warehouse (row true)))))))


(deftest name-taken-ignores-letter-case-and-archived-status
  (let [kettle (product "kettle" "Kettle" {:status "archived"})]
    (is (= {:type :name-taken :product {:product-id "kettle" :name "Kettle" :status "archived"} :attempted "kettle"}
           (select-keys (rejection #(decide ::model/register-product {:products [kettle]} {:product-id "new" :name " kettle "}))
                        [:type :product :attempted])))))


(deftest a-deleted-product-frees-its-name
  (is (seq (:events (decide ::model/register-product {:products [(product "old" "Mug" {:status "deleted"})]}
                            {:product-id "new" :name "Mug"})))))


(deftest inactive-relationships-reject
  (is (= :category-unavailable
         (:type (rejection #(decide ::model/register-product {:references {:categories [{:id "c" :name "C" :status "archived"}] :warehouses []}}
                                    {:product-id "new" :name "Mug" :category-id "c"})))))
  (is (= :warehouse-unavailable
         (:type (rejection #(decide ::model/register-product {} {:product-id "new" :name "Mug" :default-warehouse-id "missing"})))))
  (is (= :validation (:type (rejection #(decide ::model/register-product {} {:product-id "new" :name "   "}))))))


;; Update the product


(deftest rename-into-a-case-insensitive-collision
  (let [blue (product "blue" "mug blue" {})]
    (is (= [:name-taken "Mug Blue"]
           ((juxt :type :attempted)
            (rejection #(decide ::model/update-product {:product mug :products [mug blue]}
                                {:product-id "mug" :revision 1 :name "Mug Blue"})))))))


(deftest rename-to-the-current-name-records-nothing
  (let [current (assoc mug :revision 3)]
    (is (= [] (:events (decide ::model/update-product {:product current :products [current]}
                               {:product-id "mug" :revision 3 :name "Mug"}))))))


(deftest stale-edit-keeps-the-attempted-value
  (let [error (rejection #(decide ::model/update-product {:product (assoc mug :revision 4)}
                                  {:product-id "mug" :revision 3 :category-id "kitchen"}))]
    (is (= :stale-catalogue (:type error)))
    (is (= 4 (get-in error [:current :revision])))))


(deftest update-carries-the-resulting-values
  (let [current (assoc mug :category-id "kitchen" :default-warehouse-id "main")]
    (is (= [{:type ::model/product-updated
             :data {:product-id "mug" :name "Mug cup" :default-warehouse-id "main" :status "active" :revision 2}}]
           (data (decide ::model/update-product {:product current :products [current]}
                         {:product-id "mug" :revision 1 :name "Mug cup" :category-id nil}))))
    (testing "a retained archived relationship does not block other edits"
      (is (seq (:events (decide ::model/update-product {:product    current :products [current]
                                                        :references {:categories [] :warehouses [{:id "main" :name "Main" :status "archived"}]}}
                                {:product-id "mug" :revision 1 :name "Mug cup"})))))))


;; Archive, restore, delete


(deftest active-stock-items-block-archiving
  (is (= {:type :active-stock-items :count 1}
         (select-keys (rejection #(decide ::model/archive-product {:product mug :usage {"mug" {:active-stock-items 1 :stock-items-ever 2 :pending-entry nil}}}
                                          {:product-id "mug" :revision 1}))
                      [:type :count]))))


(deftest archive-a-product-whose-items-are-all-archived
  (is (= [{:type ::model/product-archived :data {:product-id "mug" :status "archived" :revision 2}}]
         (data (decide ::model/archive-product {:product mug :usage {"mug" {:active-stock-items 0 :stock-items-ever 1 :pending-entry nil}}}
                       {:product-id "mug" :revision 1})))))


(deftest a-stock-item-restored-first-makes-archiving-stale
  (is (= :stale-catalogue
         (:type (rejection #(decide ::model/archive-product {:product (assoc mug :revision 2)} {:product-id "mug" :revision 1}))))))


(deftest restore-keeps-identity
  (is (= [{:type ::model/product-restored :data {:product-id "mug" :status "active" :revision 3}}]
         (data (decide ::model/restore-product {:product (assoc mug :status "archived" :revision 2)}
                       {:product-id "mug" :revision 2})))))


(deftest restoring-a-product-restored-by-someone-else-is-stale
  (is (= :stale-catalogue
         (:type (rejection #(decide ::model/restore-product {:product (assoc mug :revision 3)} {:product-id "mug" :revision 2}))))))


(deftest unused-product-is-deleted
  (is (= [{:type ::model/product-deleted :data {:product-id "mug" :status "deleted" :revision 2}}]
         (data (decide ::model/delete-product {:product mug} {:product-id "mug" :revision 1})))))


(deftest product-that-had-a-stock-item-cannot-be-deleted
  (is (= :had-stock-item
         (:type (rejection #(decide ::model/delete-product {:product mug :usage {"mug" {:active-stock-items 0 :stock-items-ever 1 :pending-entry nil}}}
                                    {:product-id "mug" :revision 1}))))))


(deftest pending-reference-blocks-deletion
  (is (= {:type :pending-reference :entry "entry-7"}
         (select-keys (rejection #(decide ::model/delete-product
                                          {:product mug :usage {"mug" {:active-stock-items 0 :stock-items-ever 0 :pending-entry "entry-7"}}}
                                          {:product-id "mug" :revision 1}))
                      [:type :entry]))))


(deftest deleted-products-are-not-found
  (is (= :not-found
         (:type (rejection #(decide ::model/update-product {:product (assoc mug :status "deleted")} {:product-id "mug" :revision 1 :name "X"}))))))


;; Store: uniqueness at commit, reads and the page


(defn context
  {:malli/schema [:=> [:cat :any] :map]}
  [repo]
  (runtime/prepare-context {:bel/system  (support/bel-system {:repo repo}) :repo repo :workspace-id (:workspace-id support/scope)
                            :user-id     #uuid "00000000-0000-0000-0000-000000000011"
                            :memberships [{:workspace-id (:workspace-id support/scope) :role :member}]}))


(defn execute!
  {:malli/schema [:=> [:cat :map :keyword :string :map] :map]}
  [ctx command command-id input]
  (runtime/execute! ctx command {:command-id command-id :input input}))


(defn catalogue
  {:malli/schema [:=> [:cat :map :boolean] :map]}
  [ctx archived?]
  (runtime/read-model ctx ::model/product-catalogue {:archived? archived?}))


(deftest catalogue-lifecycle-through-the-store
  (support/with-store
    (fn [repo _]
      (let [ctx (context repo)]
        (execute! ctx :tover.catalogues.warehouses.model/register-warehouse "w1" {:warehouse-id "main" :name "Main"})
        (execute! ctx :tover.catalogues.categories.model/register-category "c1" {:category-id "kitchen" :name "Kitchen"})
        (execute! ctx ::model/register-product "r1" {:product-id "mug" :name "Mug" :category-id "kitchen" :default-warehouse-id "main"})
        (execute! ctx ::model/register-product "r2" {:product-id "kettle" :name "Kettle"})
        (testing "uniqueness is checked against stored products"
          (is (= :name-taken (:type (rejection #(execute! ctx ::model/register-product "r3" {:product-id "x" :name "MUG"}))))))
        (testing "an unknown warehouse rejects"
          (is (= :warehouse-unavailable
                 (:type (rejection #(execute! ctx ::model/register-product "r5" {:product-id "y" :name "Cup" :default-warehouse-id "gone"}))))))
        (testing "catalogue lists active products with labels, choices and eligibility"
          (let [{:keys [products choices]} (catalogue ctx false)]
            (is (= [["Kettle" nil] ["Mug" "Kitchen"]] (map (juxt :name (comp :name :category)) products)))
            (is (every? (every-pred :can-archive? :can-delete?) products))
            (is (= [["kitchen" "Kitchen"]] (map (juxt :id :name) (:categories choices))))))
        (execute! ctx ::model/archive-product "a1" {:product-id "kettle" :revision 1})
        (testing "archived filter"
          (is (= ["Kettle"] (map :name (:products (catalogue ctx true))))))
        (testing "update clears a relationship and keeps the rest"
          (execute! ctx ::model/update-product "u1" {:product-id "mug" :revision 1 :name "Mug" :category-id nil :default-warehouse-id "main"})
          (is (= {:product-id        "mug" :name "Mug" :status "active" :revision 2 :category nil
                  :default-warehouse {:id "main" :name "Main" :archived? false}}
                 (select-keys (first (filter #(= "mug" (:product-id %)) (:products (catalogue ctx false))))
                              [:product-id :name :status :revision :category :default-warehouse]))))
        (testing "deletion frees the name"
          (execute! ctx ::model/delete-product "d1" {:product-id "mug" :revision 2})
          (is (not-any? #(= "mug" (:product-id %)) (:products (catalogue ctx false))))
          (is (= :accepted (:status (execute! ctx ::model/register-product "r4" {:product-id "mug-2" :name "mug"})))))))))


(deftest one-row-by-product-id
  (support/with-store
    (fn [repo _]
      (let [ctx (context repo)]
        (execute! ctx ::model/register-product "r1" {:product-id "mug" :name "Mug"})
        (execute! ctx ::model/archive-product "a1" {:product-id "mug" :revision 1})
        (is (= ["Mug"] (map :name (:products (runtime/read-model ctx ::model/product-catalogue {:product-id "mug"}))))
            "a saved cell reads its product whatever its status")))))
