(ns tover.inventory.acceptance-test
  "Board chapters 14, 17 and 32a · Accept the exact reviewed selection: records, registrations and draft removal
  in one commit, rejected as a whole when anything the review saw changed."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.runtime :as runtime]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [tover.catalogues.products.model :as products]
    [tover.catalogues.records :as records]
    [tover.catalogues.stores.model :as stores]
    [tover.catalogues.suppliers.model :as suppliers]
    [tover.catalogues.warehouses.model :as warehouses]
    [tover.inventory.acceptance.model :as acceptance]
    [tover.inventory.entries.model :as entries]
    [tover.inventory.stock-items.model :as stock-items]
    [tover.support :as support])
  (:import
    [java.time Instant]))


(use-fixtures :each bel-test/isolated-registry
  (fn [f]
    (bel/build-registry! ['tover.shared.web.core 'tover.catalogues.warehouses.model 'tover.catalogues.suppliers.model
                          'tover.catalogues.stores.model 'tover.catalogues.categories.model
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
   :stock-items [{:stock-item-id "tee-m" :product-id "tee" :variant-label "M" :unit "pcs" :condition "regular" :status "active" :revision 1}
                 {:stock-item-id "mug-1" :product-id "mug" :condition "regular" :status "active" :revision 1}]
   :barcodes    []
   :warehouses  [{:warehouse-id "main" :name "Main" :status "active" :revision 1}]})


(defn- priced
  "Units enter stock only at a cost: opening and purchase lines default to one. An explicit nil unit cost leaves it out."
  {:malli/schema [:=> [:cat :string :map] :map]}
  [kind line]
  (cond-> line
    (and (#{"opening" "purchase"} kind) (not (contains? line :unit-cost))) (assoc :unit-cost "10.00")
    (contains? line :unit-cost) (->> (into {} (remove (comp nil? val))))))


(defn- entry
  {:malli/schema [:=> [:cat :string [:* :map]] :map]}
  [id & lines]
  {:entry-id id :kind "opening" :occurred-at (day 19) :status "pending" :revision 1
   :lines    (vec (map-indexed #(priced "opening" (merge {:line-id (str id "-" %1) :warehouse-id "main" :quantity 5 :unit "pcs"} %2)) lines))})


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
    (is (= [{:stock-item-id "item:e-0" :product-id "tee" :variant-label "L" :unit "pcs" :condition "regular" :status "active" :revision 1}]
           (of-type plan ::stock-items/stock-item-registered)))
    (is (= ["item:e-0"] (map :stock-item-id (of-type plan ::acceptance/inventory-record-line-accepted))))))


(deftest a-new-stock-item-registers-its-store
  (let [f    (-> (facts [(entry "e" {:product-id "tee" :variant-label "L" :store-id "web"})])
                 (assoc-in [:catalogue :stores] [{:store-id "web" :name "Web shop" :status "active" :revision 1}]))
        plan (accept f (input f "e"))]
    (is (= ["web"] (map :store-id (of-type plan ::stock-items/stock-item-registered))))))


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
        (save "s1" {:entry-id "a" :lines [{:line-id   "a-0"    :product-id "tee" :variant-label "M" :unit "pcs" :warehouse-id "main" :quantity "5"
                                           :unit-cost "100.00"}]})
        (save "s2" {:entry-id "b" :lines [{:line-id "b-0" :product-id "tee" :variant-label "L" :unit "pcs" :warehouse-id "main" :quantity "2" :unit-cost "40.00"}]})
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
              (is (= #{["item:a-0" "main" 5 500.0000M] ["item:b-0" "main" 2 80.0000M]}
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


;; 15 · Accept the reviewed correction


(def opened-5
  {:records [{:record-revision-id "o#1" :record-id   "o"     :revision 1 :acceptance-id "a0" :kind "opening" :occurred-at (day 1) :sequence 0
              :cancelled          false :recorded-at (day 1)}]
   :lines   [{:record-line-id "o#1/0" :record-revision-id "o#1" :position 0 :stock-item-id "tee-m" :warehouse-id "main" :quantity-change 5}]})


(defn- correction
  {:malli/schema [:=> [:cat :string [:sequential :map] [:sequential :map]] :map]}
  [id lines changes]
  (cond-> {:entry-id id :kind "correction" :occurred-at (day 19) :status "pending" :revision 1
           :lines    (vec (map-indexed #(merge {:line-id (str id "-" %1) :stock-item-id "tee-m" :warehouse-id "main"} %2) lines))}
    (seq changes) (assoc :changes (vec changes))))


(deftest accept-a-count
  (let [f    (assoc (facts [(correction "c" [{:quantity 7}] [])]) :history opened-5)
        plan (accept f (input f "c"))]
    (is (= ["correction"] (map :kind (of-type plan ::acceptance/inventory-record-accepted))))
    (is (= [{:record-line-id "c#1/0" :record-revision-id "c#1" :position 0 :stock-item-id "tee-m" :warehouse-id "main" :counted-quantity 7}]
           (of-type plan ::acceptance/inventory-record-line-accepted)))
    (is (= [{:entry-id "c" :status "accepted" :revision 2}] (of-type plan ::entries/shared-inventory-entry-accepted)) "the draft leaves the pending work")))


(deftest barcode-owned-by-another-product-names-the-owner
  (let [f (-> (facts [(correction "c" [] [{:stock-item-id "mug-1" :barcodes-added ["4600000000017"]}])])
              (assoc-in [:catalogue :barcodes] [{:barcode-id "tee-m|4600000000017" :stock-item-id "tee-m" :barcode "4600000000017"}]))
        r (rejection #(accept f (input f "c")))]
    (is (= :uniqueness-conflict (:type r)))
    (is (= "T-shirt" (get-in r [:problem :owner-product])))))


(deftest field-change-only
  (let [f    (facts [(dissoc (correction "c" [] [{:stock-item-id "tee-m" :article "T-101"}]) :occurred-at)])
        plan (accept f (input f "c"))]
    (is (= [{:stock-item-id "tee-m" :variant-label "M" :article "T-101" :unit "pcs" :revision 2}] (of-type plan ::stock-items/stock-item-updated)))
    (is (= [] (of-type plan ::acceptance/inventory-record-accepted)))
    (is (= [{:entry-id "c" :status "accepted" :revision 2}] (of-type plan ::entries/shared-inventory-entry-accepted)))))


(deftest a-correction-changes-the-store
  (let [f    (facts [(dissoc (correction "c" [] [{:stock-item-id "tee-m" :store-id "web"}]) :occurred-at)])
        plan (accept f (input f "c"))]
    (is (= [{:stock-item-id "tee-m" :variant-label "M" :unit "pcs" :store-id "web" :revision 2}]
           (of-type plan ::stock-items/stock-item-updated)))))


(deftest barcodes-added-and-removed
  (let [f    (-> (facts [(correction "c" [] [{:stock-item-id "tee-m" :barcodes-added ["4600000000024"] :barcodes-removed ["4600000000017"]}])])
                 (assoc-in [:catalogue :barcodes] [{:barcode-id "tee-m|4600000000017" :stock-item-id "tee-m" :barcode "4600000000017"}]))
        plan (accept f (input f "c"))]
    (is (= [{:barcode-id "tee-m|4600000000017"}] (of-type plan ::stock-items/stock-item-barcode-removed)))
    (is (= [{:barcode-id "tee-m|4600000000024" :stock-item-id "tee-m" :barcode "4600000000024"}]
           (of-type plan ::stock-items/stock-item-barcode-assigned)))))


(deftest archive-after-counting-to-zero
  (let [f    (assoc (facts [(correction "c" [{:quantity 0}] [{:stock-item-id "tee-m" :archive true}])]) :history opened-5)
        plan (accept f (input f "c"))]
    (is (= 1 (count (of-type plan ::acceptance/inventory-record-accepted))))
    (is (= [{:stock-item-id "tee-m" :status "archived" :revision 2}] (of-type plan ::stock-items/stock-item-archived)))))


(deftest move-and-update-together
  (let [f    (facts [(correction "c" [] [{:stock-item-id "tee-m" :product-id "mug" :variant-label "red"}])])
        plan (accept f (input f "c"))]
    (is (= [{:stock-item-id "tee-m" :variant-label "red" :unit "pcs" :revision 2}] (of-type plan ::stock-items/stock-item-updated)))
    (is (= [{:stock-item-id "tee-m" :previous-product-id "tee" :product-id "mug" :variant-label "red" :revision 3}]
           (of-type plan ::stock-items/stock-item-moved)))))


(deftest unit-locked-by-a-race
  (let [f     (facts [(correction "c" [] [{:stock-item-id "tee-m" :unit "kg"}])])
        input (input f "c")]
    (is (= :unit-locked (:type (rejection #(accept (assoc f :history opened-5) input)))))))


(deftest correction-through-the-store
  (support/with-store
    (fn [repo _]
      (let [ctx    (context repo [{:workspace-id workspace :role :member}])
            save   #(execute! ctx ::entries/save-shared-inventory-entry %1 (merge {:workspace-id workspace :occurred-at "2026-11-19T00:00:00Z"} %2))
            submit (fn [id ids]
                     (let [selection {:selection (mapv #(hash-map :entry-id % :revision 1) ids) :order ids}
                           review    (runtime/read-model ctx ::acceptance/inventory-review selection)]
                       (is (:valid? review) (pr-str (:problems review)))
                       (execute! ctx ::acceptance/accept-reviewed-inventory-changes id
                                 (merge selection {:workspace-id workspace :acceptance-id id :fingerprint (:fingerprint review)}))))
            item   #(some (fn [i] (when (= "item:a-0" (:stock-item-id i)) i))
                          (records/all-records ctx ::stock-items/stock-items :stock-item-id))]
        (execute! ctx ::warehouses/register-warehouse "w1" {:warehouse-id "main" :name "Main"})
        (execute! ctx ::products/register-product "p1" {:product-id "tee" :name "T-shirt"})
        (save "s1" {:entry-id "a" :kind "opening"
                    :lines    [{:line-id  "a-0" :product-id "tee"    :variant-label "M" :article "T-1" :unit "pcs" :warehouse-id "main"
                                :quantity "5"   :unit-cost  "100.00"}]})
        (submit "acc-1" ["a"])
        (save "s2" {:entry-id "c" :kind "correction"
                    :lines    [{:line-id "c-0" :stock-item-id "item:a-0" :warehouse-id "main" :quantity "0"}]
                    :changes  [{:stock-item-id "item:a-0" :article "" :archive true :barcodes-added ["4600000000017" "0"]}]})
        (testing "the pending change is read back as saved"
          (is (= [{:stock-item-id "item:a-0" :article "" :archive true :barcodes-added ["4600000000017"]}]
                 (:changes (first (:entries (runtime/read-model ctx ::entries/pending-entries {}))))))
          (is (= [] (:blockers (first (:entries (runtime/read-model ctx ::entries/pending-entries {})))))))
        (submit "acc-2" ["c"])
        (is (= {:variant-label "M" :unit "pcs" :status "archived" :revision 3}
               (select-keys (item) [:variant-label :article :unit :status :revision]))
            "the article is cleared, the item archived")
        (is (= [["item:a-0" "main" 0 0M]]
               (map (juxt :stock-item-id :warehouse-id :quantity :value)
                    (:balances (runtime/read-model ctx ::acceptance/acceptance-result {:acceptance-id "acc-2"})))))))))


;; 18 · Accept the reviewed purchase · 19 · Accept the reviewed sale


(def trading
  {:suppliers [{:supplier-id "tex" :name "Textiles LLC" :status "active" :revision 1}]
   :stores    [{:store-id "web" :name "Web shop" :status "active" :revision 1}]})


(defn- operation
  {:malli/schema [:=> [:cat :string :string :map [:* :map]] :map]}
  [kind id fields & lines]
  (-> (apply entry id lines)
      (assoc :kind kind)
      (update :lines #(mapv (fn [line given] (priced kind (merge (dissoc line :unit-cost) (select-keys given [:unit-cost])))) % lines))
      (merge fields)))


(defn- trade-facts
  {:malli/schema [:=> [:cat [:sequential :map]] :map]}
  [entries]
  (-> (facts entries) (update :catalogue merge trading) (assoc :history (assoc-in opened-5 [:lines 0 :unit-cost] "100.00"))))


(deftest accept-a-priced-purchase
  (let [f    (trade-facts [(operation "purchase" "p" {:supplier-id "tex" :reason "restock"} {:stock-item-id "tee-m" :unit-cost "200.00"})])
        plan (accept f (input f "p"))]
    (is (= [{:record-revision-id "p#1" :record-id "p"   :revision    1     :acceptance-id "acc-1"   :kind "purchase" :occurred-at (day 19)
             :sequence           0     :cancelled false :supplier-id "tex" :reason        "restock"}]
           (of-type plan ::acceptance/inventory-record-accepted)))
    (is (= [[5 "200.00"]] (map (juxt :quantity-change :unit-cost) (of-type plan ::acceptance/inventory-record-line-accepted))))
    (is (= [{:entry-id "p" :status "accepted" :revision 2}] (of-type plan ::entries/shared-inventory-entry-accepted)))))


(deftest new-supplier-registered-with-the-purchase
  (let [f    (trade-facts [(operation "purchase" "p" {:supplier-name " Cotton Co "} {:stock-item-id "tee-m"})])
        plan (accept f (input f "p"))]
    (is (= [{:supplier-id "supplier:p" :name "Cotton Co" :status "active" :revision 1}] (of-type plan ::suppliers/supplier-registered)))
    (is (= ["supplier:p"] (map :supplier-id (of-type plan ::acceptance/inventory-record-accepted))))))


(deftest supplier-name-taken-meanwhile
  (let [f     (trade-facts [(operation "purchase" "p" {:supplier-name "Cotton Co"} {:stock-item-id "tee-m"})])
        seen  (input f "p")
        later (update-in f [:catalogue :suppliers] conj {:supplier-id "cc" :name "cotton co" :status "active" :revision 1})]
    (is (= :review-outdated (:type (rejection #(accept later seen)))) "the review saw no such supplier")
    (let [r (rejection #(accept later (input later "p")))]
      (is (= :supplier-name-taken (:type r)))
      (is (= "cotton co" (get-in r [:problem :owner-supplier]))))))


(deftest archived-supplier-or-store-rejects
  (let [f (-> (trade-facts [(operation "purchase" "p" {:supplier-id "tex"} {:stock-item-id "tee-m"})])
              (assoc-in [:catalogue :suppliers 0 :status] "archived"))]
    (is (= :archived-reference (:type (rejection #(accept f (input f "p")))))))
  (let [f (-> (trade-facts [(operation "sale" "s" {:store-id "web"} {:stock-item-id "tee-m" :quantity 1})])
              (assoc-in [:catalogue :stores 0 :status] "archived"))]
    (is (= :archived-reference (:type (rejection #(accept f (input f "s"))))))))


(deftest new-stock-item-on-a-purchase-line
  (let [f    (trade-facts [(operation "purchase" "p" {:supplier-id "tex"} {:product-id "tee" :variant-label "XL" :unit "pcs"})])
        plan (accept f (input f "p"))]
    (is (= [["item:p-0" "pcs"]] (map (juxt :stock-item-id :unit) (of-type plan ::stock-items/stock-item-registered))))
    (is (= ["item:p-0"] (map :stock-item-id (of-type plan ::acceptance/inventory-record-line-accepted))))))


(deftest accept-a-sale
  (let [f    (trade-facts [(operation "sale" "s" {:store-id "web"} {:stock-item-id "tee-m" :quantity 3 :unit-price "150.00"})])
        plan (accept f (input f "s"))]
    (is (= [["sale" "web"]] (map (juxt :kind :store-id) (of-type plan ::acceptance/inventory-record-accepted))))
    (is (= [[-3 "150.00" nil]] (map (juxt :quantity-change :unit-price :unit-cost) (of-type plan ::acceptance/inventory-record-line-accepted))))))


(deftest stock-taken-meanwhile
  (let [f     (trade-facts [(operation "sale" "s" {} {:stock-item-id "tee-m" :quantity 3})])
        seen  (input f "s")
        later (-> f
                  (update-in [:history :records] conj {:record-revision-id "x#1"   :record-id "x" :revision  1     :acceptance-id "a1"    :kind "sale"
                                                       :occurred-at        (day 2) :sequence  0   :cancelled false :recorded-at   (day 2)})
                  (update-in [:history :lines] conj {:record-line-id "x#1/0" :record-revision-id "x#1" :position 0 :stock-item-id "tee-m"
                                                     :warehouse-id   "main"  :quantity-change    -4}))]
    (is (= :review-outdated (:type (rejection #(accept later seen)))))
    (is (= :negative-balance (:type (rejection #(accept later (input later "s"))))) "a new review shows the shortage")))


(deftest operations-through-the-store
  (support/with-store
    (fn [repo _]
      (let [ctx    (context repo [{:workspace-id workspace :role :member}])
            save   #(execute! ctx ::entries/save-shared-inventory-entry %1 (merge {:workspace-id workspace :occurred-at "2026-11-19T00:00:00Z"} %2))
            submit (fn [id ids]
                     (let [selection {:selection (mapv #(hash-map :entry-id % :revision 1) ids) :order ids}
                           review    (runtime/read-model ctx ::acceptance/inventory-review selection)]
                       (is (:valid? review) (pr-str (:problems review)))
                       (merge selection {:workspace-id workspace :acceptance-id id :fingerprint (:fingerprint review)})))]
        (execute! ctx ::warehouses/register-warehouse "w1" {:warehouse-id "main" :name "Main"})
        (execute! ctx ::products/register-product "p1" {:product-id "tee" :name "T-shirt"})
        (execute! ctx ::stores/register-store "st1" {:store-id "web" :name "Web shop"})
        (save "s1" {:entry-id "p" :kind "purchase" :supplier-name "Cotton Co"
                    :lines    [{:line-id "p-0" :product-id "tee" :variant-label "M" :unit "pcs" :warehouse-id "main" :quantity "5" :unit-cost "200.00"}]})
        (let [accept (submit "acc-1" ["p"])]
          (is (= :accepted (:status (execute! ctx ::acceptance/accept-reviewed-inventory-changes "acc-1" accept))))
          (testing "a retry returns the original result and stock rises once"
            (is (= :accepted (:status (execute! ctx ::acceptance/accept-reviewed-inventory-changes "acc-1" accept))))
            (is (= [["item:p-0" "main" 5]]
                   (map (juxt :stock-item-id :warehouse-id :quantity)
                        (:balances (runtime/read-model ctx ::acceptance/acceptance-result {:acceptance-id "acc-1"})))))))
        (is (= [["supplier:p" "Cotton Co"]] (map (juxt :supplier-id :name) (records/all-records ctx ::suppliers/suppliers :supplier-id))))
        (save "s2" {:entry-id "s" :kind "sale" :store-id "web"
                    :lines    [{:line-id "s-0" :stock-item-id "item:p-0" :warehouse-id "main" :quantity "3" :unit-price "250.00"}]})
        (execute! ctx ::acceptance/accept-reviewed-inventory-changes "acc-2" (submit "acc-2" ["s"]))
        (is (= [["item:p-0" "main" 2 400.0000M]]
               (map (juxt :stock-item-id :warehouse-id :quantity :value)
                    (:balances (runtime/read-model ctx ::acceptance/acceptance-result {:acceptance-id "acc-2"})))))))))


;; 20–25 · Accept returns, transfers, production, defects, write-offs and payments


(deftest defect-registers-the-counterpart-once
  (let [defect #(operation "defect" % {:reason "torn"} {:stock-item-id "tee-m" :quantity 1})
        f      (trade-facts [(defect "d") (assoc (defect "e") :occurred-at (day 18))])
        plan   (accept f (input f "e" "d"))]
    (is (= [{:stock-item-id   "tee-m~defect" :product-id "tee"    :variant-label "M · defect" :unit "pcs" :condition "defective"
             :regular-item-id "tee-m"        :status     "active" :revision      1}]
           (map #(dissoc % :barcodes) (of-type plan ::stock-items/stock-item-registered))))
    (is (= [[-1 nil] [1 0] [-1 nil] [1 0]] (map (juxt :quantity-change :carry-from) (of-type plan ::acceptance/inventory-record-line-accepted))))))


(deftest production-registers-a-new-output
  (let [f    (trade-facts [(operation "production" "m" {} {:stock-item-id "tee-m" :quantity 2 :side "input"}
                                      {:product-id "tee" :variant-label "Bundle" :unit "pcs" :quantity 1 :side "output"})])
        plan (accept f (input f "m"))]
    (is (= ["item:m-1"] (map :stock-item-id (of-type plan ::stock-items/stock-item-registered))))
    (is (= [["tee-m" -2] ["item:m-1" 1]] (map (juxt :stock-item-id :quantity-change) (of-type plan ::acceptance/inventory-record-line-accepted))))))


(deftest payment-keeps-the-exact-amount
  (let [f    (trade-facts [(assoc (operation "payment" "pay" {:direction "in" :store-id "web" :amount "1234.56"}) :lines [])])
        plan (accept f (input f "pay"))]
    (is (= [["payment" "in" "web" "1234.56"]] (map (juxt :kind :direction :store-id :amount) (of-type plan ::acceptance/inventory-record-accepted))))
    (is (= [] (of-type plan ::acceptance/inventory-record-line-accepted)))))


(deftest other-operations-through-the-store
  (support/with-store
    (fn [repo _]
      (let [ctx     (context repo [{:workspace-id workspace :role :member}])
            save    #(execute! ctx ::entries/save-shared-inventory-entry %1 (merge {:workspace-id workspace :occurred-at "2026-11-19T00:00:00Z"} %2))
            submit  (fn [id ids]
                      (let [selection {:selection (mapv #(hash-map :entry-id % :revision 1) ids) :order ids}
                            review    (runtime/read-model ctx ::acceptance/inventory-review selection)]
                        (is (:valid? review) (pr-str (:problems review)))
                        (execute! ctx ::acceptance/accept-reviewed-inventory-changes id
                                  (merge selection {:workspace-id workspace :acceptance-id id :fingerprint (:fingerprint review)}))))
            balance #(set (map (juxt :stock-item-id :warehouse-id :quantity :value) (:balances (runtime/read-model ctx ::acceptance/acceptance-result {:acceptance-id %}))))]
        (execute! ctx ::warehouses/register-warehouse "w1" {:warehouse-id "main" :name "Main"})
        (execute! ctx ::warehouses/register-warehouse "w2" {:warehouse-id "back" :name "Back"})
        (execute! ctx ::products/register-product "p1" {:product-id "tee" :name "T-shirt"})
        (execute! ctx ::suppliers/register-supplier "s1" {:supplier-id "tex" :name "Textiles LLC"})
        (save "e1" {:entry-id "p" :kind "purchase" :supplier-id "tex"
                    :lines    [{:line-id "p-0" :product-id "tee" :variant-label "M" :unit "pcs" :warehouse-id "main" :quantity "10" :unit-cost "100.00"}]})
        (submit "acc-1" ["p"])
        (save "e2" {:entry-id "t" :kind "transfer" :from-warehouse-id "main" :to-warehouse-id "back"
                    :lines    [{:line-id "t-0" :stock-item-id "item:p-0" :quantity "4"}]})
        (submit "acc-2" ["t"])
        (is (= #{["item:p-0" "main" 6 600.0000M] ["item:p-0" "back" 4 400.0000M]} (balance "acc-2")))
        (save "e3" {:entry-id "d" :kind "defect" :reason "torn" :lines [{:line-id "d-0" :stock-item-id "item:p-0" :warehouse-id "back" :quantity "1"}]})
        (submit "acc-3" ["d"])
        (is (contains? (balance "acc-3") ["item:p-0~defect" "back" 1 100.0000M]))
        (save "e4" {:entry-id "pay" :kind "payment" :direction "out" :supplier-id "tex" :amount "1000.00"})
        (submit "acc-4" ["pay"])
        (is (= [["payment" 1000M] ["defect" nil] ["transfer" nil] ["purchase" 1000M]]
               (map (juxt :kind #(some-> ^BigDecimal (:total %) .stripTrailingZeros)) (runtime/read-model ctx ::acceptance/accepted-operations {}))))))))
