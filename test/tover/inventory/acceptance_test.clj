(ns tover.inventory.acceptance-test
  "Board chapters 14, 17 and 32a · Accept the exact reviewed selection: records, registrations and draft removal
  in one commit, rejected as a whole when anything the review saw changed."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.runtime :as runtime]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [tover.catalogues.products.model :as products]
    [tover.catalogues.warehouses.model :as warehouses]
    [tover.inventory.acceptance.model :as acceptance]
    [tover.inventory.entries.model :as entries]
    [tover.inventory.stock-items.model :as stock-items]
    [tover.support :as support])
  (:import
    [java.time Instant]))


(use-fixtures :each bel-test/isolated-registry
  (fn [f]
    (bel/build-registry! ['tover.shared.web.core 'tover.catalogues.warehouses.model 'tover.catalogues.categories.model
                          'tover.catalogues.products.model 'tover.inventory.stock-items.model
                          'tover.inventory.entries.model 'tover.inventory.acceptance.model
                          'tover.workspaces.currency.model])
    (f))
  (fn [f]
    ;; Membership guards run in the store integration suite; here the principal comes from the context.
    (with-redefs [runtime/with-authority (fn [context _touched _isolation read]
                                           (read (assoc context :principal (select-keys context [:user-id :memberships]))))]
      (f))))


(def workspace (:workspace-id support/scope))
(def member #uuid "00000000-0000-0000-0000-000000000011")


(defn- day
  {:malli/schema [:=> [:cat :int] :any]}
  [n]
  (Instant/parse (format "2026-11-%02dT00:00:00Z" n)))


(def catalogue
  {:products    [{:product-id "tee" :name "T-shirt" :status "active" :revision 1}
                 {:product-id "mug" :name "Mug" :status "active" :revision 1}]
   :stock-items [{:stock-item-id "tee-m" :product-id "tee" :variant-label "M" :condition "regular" :status "active" :revision 1}
                 {:stock-item-id "mug-1" :product-id "mug" :condition "regular" :status "active" :revision 1}]
   :barcodes    []
   :warehouses  [{:warehouse-id "main" :name "Main" :status "active" :revision 1}]})


(defn- entry
  {:malli/schema [:=> [:cat :string [:* :map]] :map]}
  [id & lines]
  {:entry-id id :kind "opening" :occurred-at (day 19) :status "pending" :revision 1
   :lines    (vec (map-indexed #(merge {:line-id (str id "-" %1) :warehouse-id "main" :quantity 5} %2) lines))})


(defn- facts
  {:malli/schema [:=> [:cat [:sequential :map]] :map]}
  [entries]
  {:scope   {:workspace-id workspace} :actor     member    :now     (day 20)                :command-id   "accept-1"
   :entries entries                   :catalogue catalogue :history {:records [] :lines []} :currency-row nil})


(defn- input
  "Accept input carrying the fingerprint of a review of `reviewed` facts."
  {:malli/schema [:=> [:cat :map [:* :string]] :map]}
  [reviewed & ids]
  (let [selection {:selection (mapv #(hash-map :entry-id % :revision 1) ids) :order (vec ids)}]
    (merge selection {:workspace-id workspace :acceptance-id "acc-1"
                      :fingerprint  (:fingerprint (acceptance/review (assoc (acceptance/review-input reviewed) :now (:now reviewed))
                                                                     selection))})))


(defn- accept
  {:malli/schema [:=> [:cat :map :map] :map]}
  [facts input]
  (runtime/decide ::acceptance/accept-reviewed-inventory-changes facts input))


(defn- rejection
  {:malli/schema [:=> [:cat [:fn fn?]] [:maybe :map]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (ex-data error))))


(defn- of-type
  {:malli/schema [:=> [:cat :map :keyword] [:vector :map]]}
  [plan type]
  (into [] (comp (filter #(= type (:type %))) (map :data)) (:events plan)))


;; 14 · Accept reviewed opening stock


(deftest accept-opening
  (let [f    (facts [(entry "e" {:stock-item-id "tee-m" :unit-cost "100.00"})])
        plan (accept f (input f "e"))]
    (is (= [{:record-revision-id "e#1" :record-id "e"   :revision 1 :acceptance-id "acc-1" :kind "opening" :occurred-at (day 19)
             :sequence           0     :cancelled false}]
           (map #(dissoc % :recorded-at) (of-type plan ::acceptance/inventory-record-accepted))))
    (is (= [{:record-line-id  "e#1/0" :record-revision-id "e#1"    :position 0 :stock-item-id "tee-m" :warehouse-id "main"
             :quantity-change 5       :unit-cost          "100.00"}]
           (of-type plan ::acceptance/inventory-record-line-accepted)))
    (is (= [{:entry-id "e" :status "accepted" :revision 2}] (of-type plan ::entries/shared-inventory-entry-accepted))
        "the draft is removed from pending work")))


(deftest opening-with-a-new-stock-item
  (let [f    (facts [(entry "e" {:product-id "tee" :variant-label " L "})])
        plan (accept f (input f "e"))]
    (is (= [{:stock-item-id "item:e-0" :product-id "tee" :variant-label "L" :condition "regular" :status "active" :revision 1}]
           (of-type plan ::stock-items/stock-item-registered)))
    (is (= ["item:e-0"] (map :stock-item-id (of-type plan ::acceptance/inventory-record-line-accepted))))))


(deftest future-time-rejected
  (let [f (facts [(assoc (entry "e" {:stock-item-id "tee-m"}) :occurred-at (day 21))])]
    (is (= :future-occurrence (:type (rejection #(accept f (input f "e"))))))))


;; 32a · Accept inventory and required registrations atomically


(deftest registration-and-opening-together
  (let [f    (facts [(entry "e" {:product-id "tee" :variant-label "XL" :barcodes ["4600000000031"]})])
        plan (accept f (input f "e"))]
    (is (= ["item:e-0"] (map :stock-item-id (of-type plan ::stock-items/stock-item-registered))))
    (is (= [{:barcode-id "item:e-0|4600000000031" :stock-item-id "item:e-0" :barcode "4600000000031"}]
           (of-type plan ::stock-items/stock-item-barcode-assigned)))
    (is (= 1 (count (of-type plan ::acceptance/inventory-record-accepted))))))


(deftest one-new-item-in-two-warehouses-is-registered-once
  (let [f    (-> (facts [(entry "a" {:product-id "tee" :variant-label "XL" :barcodes ["4600000000031"]})
                         (entry "b" {:product-id "tee" :variant-label "XL" :barcodes ["4600000000031"] :warehouse-id "back"})])
                 (update-in [:catalogue :warehouses] conj {:warehouse-id "back" :name "Back" :status "active" :revision 1}))
        plan (accept f (input f "a" "b"))]
    (is (= ["item:a-0"] (map :stock-item-id (of-type plan ::stock-items/stock-item-registered))))
    (is (= ["item:a-0|4600000000031"] (map :barcode-id (of-type plan ::stock-items/stock-item-barcode-assigned))))
    (is (= [["item:a-0" "main"] ["item:a-0" "back"]]
           (map (juxt :stock-item-id :warehouse-id) (of-type plan ::acceptance/inventory-record-line-accepted))))))


(deftest new-product-and-warehouse-are-registered
  (let [f    (facts [(entry "e" {:product-name " Cup " :variant-label "Big" :warehouse-id nil :warehouse-name "Back"})])
        plan (accept f (input f "e"))]
    (is (= [{:product-id "product:e-0" :name "Cup" :status "active" :revision 1}] (of-type plan ::products/product-registered)))
    (is (= [{:warehouse-id "warehouse:e-0" :name "Back" :status "active" :revision 1}] (of-type plan ::warehouses/warehouse-registered)))
    (is (= [["item:e-0" "warehouse:e-0"]] (map (juxt :stock-item-id :warehouse-id) (of-type plan ::acceptance/inventory-record-line-accepted))))))


(deftest barcode-assigned-elsewhere-after-review-is-outdated
  (let [reviewed (facts [(entry "e" {:product-id "tee" :variant-label "XL" :barcodes ["4600000000031"]})])
        now      (assoc-in reviewed [:catalogue :barcodes] [{:barcode-id "mug-1|4600000000031" :stock-item-id "mug-1" :barcode "4600000000031"}])]
    (is (= :review-outdated (:type (rejection #(accept now (input reviewed "e"))))))))


(deftest rename-after-review-is-outdated
  (let [reviewed (facts [(entry "e" {:product-id "tee" :variant-label "XL"})])
        now      (assoc-in reviewed [:catalogue :products 0] {:product-id "tee" :name "Tee" :status "active" :revision 2})]
    (is (= :review-outdated (:type (rejection #(accept now (input reviewed "e"))))))))


(deftest unknown-unit-registers-nothing
  (let [f (facts [(entry "e" {:product-id "tee" :variant-label "XL" :unit "crate"})])]
    (is (= :validation (:type (rejection #(accept f (input f "e"))))))))


;; 17 · Accept the exact reviewed selection


(deftest atomic-acceptance
  (let [f    (facts [(entry "a" {:stock-item-id "tee-m"}) (entry "b" {:product-id "tee" :variant-label "S"})])
        plan (accept f (input f "a" "b"))]
    (is (= [{:acceptance-id "acc-1" :actor member :recorded-at (day 20) :record-count 2}]
           (of-type plan ::acceptance/inventory-changes-accepted)))
    (is (= [["a" 0] ["b" 1]] (map (juxt :record-id :sequence) (of-type plan ::acceptance/inventory-record-accepted))))
    (is (= ["a" "b"] (map :entry-id (of-type plan ::entries/shared-inventory-entry-accepted))))
    (is (= 1 (count (of-type plan ::stock-items/stock-item-registered))))))


(deftest one-failure-accepts-nothing
  (let [reviewed (facts [(entry "a" {:stock-item-id "tee-m"}) (entry "b" {:stock-item-id "mug-1"})])
        now      (assoc-in reviewed [:entries 1 :revision] 2)]
    (is (= :review-outdated (:type (rejection #(accept now (input reviewed "a" "b"))))))))


(deftest changed-order-is-outdated
  (let [f (facts [(entry "a" {:stock-item-id "tee-m"}) (entry "b" {:stock-item-id "mug-1"})])]
    (is (= :review-outdated (:type (rejection #(accept f (assoc (input f "a" "b") :order ["b" "a"]))))))))


(deftest second-opening-rejects
  (let [f (assoc (facts [(entry "e" {:stock-item-id "tee-m"})])
                 :history {:records [{:record-revision-id "r#1"   :record-id "r" :revision  1     :acceptance-id "x"     :kind "opening"
                                      :occurred-at        (day 1) :sequence  0   :cancelled false :recorded-at   (day 1)}]
                           :lines   [{:record-line-id "r#1/0" :record-revision-id "r#1" :position 0 :stock-item-id "tee-m"
                                      :warehouse-id   "main"  :quantity-change    3}]})]
    (is (= :second-opening (:type (rejection #(accept f (input f "e"))))))))


(deftest accept-for-other-workspace-is-not-permitted
  (let [f (facts [(entry "e" {:stock-item-id "tee-m"})])]
    (is (= :not-permitted
           (:type (rejection #(accept f (assoc (input f "e") :workspace-id #uuid "00000000-0000-0000-0000-000000000099"))))))))


;; Through the store: review, accept, retry, result


(defn- context
  {:malli/schema [:=> [:cat :any [:vector :map]] :map]}
  [repo memberships]
  (runtime/prepare-context {:bel/system  (support/bel-system {:repo repo}) :repo repo :workspace-id workspace :user-id member
                            :memberships memberships}))


(defn- execute!
  {:malli/schema [:=> [:cat :map :keyword :string :map] :map]}
  [ctx command command-id input]
  (runtime/execute! ctx command {:command-id command-id :input input}))


(deftest acceptance-retry-returns-the-same-result
  (support/with-store
    (fn [repo _]
      (let [ctx  (context repo [{:workspace-id workspace :role :member}])
            save #(execute! ctx ::entries/save-shared-inventory-entry %1 (merge {:workspace-id workspace              :kind "opening"
                                                                                 :occurred-at  "2026-11-19T00:00:00Z"}
                                                                                %2))]
        (execute! ctx ::warehouses/register-warehouse "w1" {:warehouse-id "main" :name "Main"})
        (execute! ctx ::products/register-product "p1" {:product-id "tee" :name "T-shirt"})
        (save "s1" {:entry-id "a" :lines [{:line-id   "a-0"    :product-id "tee" :variant-label "M" :warehouse-id "main" :quantity "5"
                                           :unit-cost "100.00"}]})
        (save "s2" {:entry-id "b" :lines [{:line-id "b-0" :product-id "tee" :variant-label "L" :warehouse-id "main" :quantity "2"}]})
        (save "s3" {:entry-id "c" :lines [{:line-id "c-0" :product-id "tee" :variant-label "S" :warehouse-id "main"}]})
        (let [selection {:selection [{:entry-id "a" :revision 1} {:entry-id "b" :revision 1}] :order ["a" "b"]}
              review    (runtime/read-model ctx ::acceptance/inventory-review selection)
              accept    (merge selection {:workspace-id workspace :acceptance-id "acc-1" :fingerprint (:fingerprint review)})]
          (is (:valid? review))
          (is (= :accepted (:status (execute! ctx ::acceptance/accept-reviewed-inventory-changes "acc-1" accept))))
          (testing "a retry with the same attempt returns the original outcome and stores nothing twice"
            (is (= :accepted (:status (execute! ctx ::acceptance/accept-reviewed-inventory-changes "acc-1" accept))))
            (let [{:keys [records balances pending-count]} (runtime/read-model ctx ::acceptance/acceptance-result {:acceptance-id "acc-1"})]
              (is (= [["a" 0] ["b" 1]] (map (juxt :record-id :sequence) records)))
              (is (= #{["item:a-0" "main" 5 500.0000M] ["item:b-0" "main" 2 nil]}
                     (set (map (juxt :stock-item-id :warehouse-id :quantity :value) balances))))
              (is (= 1 pending-count) "the unselected entry stays pending")))
          (testing "access lost before commit rejects and changes nothing"
            (execute! ctx ::entries/save-shared-inventory-entry "s4" {:workspace-id workspace :entry-id    "c"                    :expected-revision 1
                                                                      :kind         "opening" :occurred-at "2026-11-19T00:00:00Z"
                                                                      :lines        [{:line-id      "c-0"  :product-id "tee" :variant-label "S"
                                                                                      :warehouse-id "main" :quantity   "1"}]})
            (let [selection {:selection [{:entry-id "c" :revision 2}] :order ["c"]}
                  review    (runtime/read-model ctx ::acceptance/inventory-review selection)]
              (is (= :forbidden
                     (:type (rejection #(execute! (context repo []) ::acceptance/accept-reviewed-inventory-changes "acc-2"
                                                  (merge selection {:workspace-id workspace             :acceptance-id "acc-2"
                                                                    :fingerprint  (:fingerprint review)}))))))
              (is (= 1 (:pending-count (runtime/read-model ctx ::acceptance/acceptance-result {:acceptance-id "acc-1"})))))))))))
