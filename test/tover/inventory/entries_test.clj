(ns tover.inventory.entries-test
  "Board chapters 16, 14 and 32a · Save, discard and read shared pending inventory entries."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.runtime :as runtime]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [tover.inventory.entries.model :as entries]
    [tover.support :as support])
  (:import
    [java.time Instant]))


(use-fixtures :each bel-test/isolated-registry
  (fn [f]
    (bel/build-registry! ['tover.shared.web.core 'tover.catalogues.warehouses.model 'tover.catalogues.suppliers.model
                          'tover.catalogues.stores.model 'tover.catalogues.categories.model
                          'tover.catalogues.products.model 'tover.inventory.stock-items.model
                          'tover.inventory.entries.model 'tover.inventory.acceptance.model])
    (f))
  (fn [f]
    ;; Membership guards run in the store integration suite; here the principal comes from the context.
    (with-redefs [runtime/with-authority (fn [context _touched _isolation read]
                                           (read (assoc context :principal (select-keys context [:user-id :memberships]))))]
      (f))))


(def workspace (:workspace-id support/scope))
(def member #uuid "00000000-0000-0000-0000-000000000011")
(def at (Instant/parse "2026-11-20T10:00:00Z"))


(def references
  {:warehouses  [{:id "main" :status "active"} {:id "old" :status "archived"}]
   :products    [{:id "tee" :status "active"}]
   :stock-items [{:id "tee-m" :status "active"}]
   :suppliers   [{:id "tex" :status "active"} {:id "gone" :status "archived"}]
   :stores      [{:id "web" :status "active"} {:id "closed" :status "archived"}]})


(defn decide
  {:malli/schema [:=> [:cat :keyword :map :map] :map]}
  [command facts input]
  (runtime/decide command (merge {:scope {:workspace-id workspace} :actor member :now at :entry nil :lines [] :references references}
                                 facts)
                  (merge {:workspace-id workspace} input)))


(defn rejection
  {:malli/schema [:=> [:cat [:fn fn?]] [:maybe :map]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (ex-data error))))


(def opening
  {:entry-id "e1" :kind "opening" :occurred-at "2026-11-19T00:00:00Z"
   :lines    [{:line-id "l1" :stock-item-id "tee-m" :warehouse-id "main" :quantity "5" :unit-cost "100.00"}]})


(def saved
  {:entry-id "e1" :kind "opening" :occurred-at (Instant/parse "2026-11-19T00:00:00Z") :status "pending" :revision 3 :saved-by member})


(def saved-lines
  [{:line-id "l1" :stock-item-id "tee-m" :warehouse-id "main" :quantity 5 :unit-cost "100.00"}])


(defn types
  {:malli/schema [:=> [:cat :map] [:vector :keyword]]}
  [plan]
  (mapv :type (:events plan)))


;; 14 · Propose opening stock


(deftest save-an-opening-with-cost
  (let [plan (decide ::entries/save-shared-inventory-entry {} opening)]
    (is (= [::entries/shared-inventory-entry-saved ::entries/inventory-entry-line-saved] (types plan)))
    (is (= {:entry-id "e1"   :kind "opening" :occurred-at (Instant/parse "2026-11-19T00:00:00Z") :status "pending" :revision 1
            :saved-by member}
           (get-in plan [:events 0 :data])))
    (is (= {:entry-line-id "e1/l1" :entry-id  "e1"     :line-id "l1" :position 0 :stock-item-id "tee-m" :warehouse-id "main"
            :quantity      5       :unit-cost "100.00"}
           (get-in plan [:events 1 :data])))))


(deftest cost-is-optional
  (is (not (contains? (get-in (decide ::entries/save-shared-inventory-entry {} (assoc-in opening [:lines 0 :unit-cost] " ")) [:events 1 :data])
                      :unit-cost))))


(deftest invalid-count-is-rejected-naming-the-field
  (doseq [bad ["-2" "1.5" "abc"]]
    (is (= {:type :validation :field :quantity :line-id "l1" :attempted bad}
           (select-keys (rejection #(decide ::entries/save-shared-inventory-entry {} (assoc-in opening [:lines 0 :quantity] bad)))
                        [:type :field :line-id :attempted])))))


(deftest negative-cost-is-rejected
  (is (= [:validation :unit-cost]
         ((juxt :type :field) (rejection #(decide ::entries/save-shared-inventory-entry {} (assoc-in opening [:lines 0 :unit-cost] "-1")))))))


;; 16 · Save a shared pending entry


(deftest unit-comes-from-the-fixed-list
  (let [new-item (fn [unit] (assoc opening :lines [{:line-id "l1" :product-id "tee" :variant-label "L" :unit unit}]))
        save     (fn [facts input] (decide ::entries/save-shared-inventory-entry facts input))]
    (is (= "kg" (get-in (save {} (new-item "kg")) [:events 1 :data :unit])))
    (is (= [:validation :unit] ((juxt :type :field) (rejection #(save {} (new-item "crate"))))))
    (is (some? (save {:entry (assoc saved :revision 1) :lines [{:line-id "l1" :product-id "tee" :variant-label "L" :unit "crate"}]}
                     (assoc (new-item "crate") :expected-revision 1)))
        "a unit saved before the list stays while other cells are edited")))


(deftest cell-edit-saves-at-the-next-revision
  (let [plan (decide ::entries/save-shared-inventory-entry {:entry saved :lines saved-lines}
                     (-> opening (assoc :expected-revision 3) (assoc-in [:lines 0 :quantity] "6")))]
    (is (= 4 (get-in plan [:events 0 :data :revision])))
    (is (= 6 (get-in plan [:events 1 :data :quantity])))))


(deftest second-save-against-old-revision-is-stale
  (let [error (rejection #(decide ::entries/save-shared-inventory-entry {:entry (assoc saved :revision 4) :lines saved-lines}
                                  (assoc opening :expected-revision 3)))]
    (is (= :stale-draft (:type error)))
    (is (= 4 (get-in error [:current :revision])) "the other member's value is returned with the rejection")
    (is (= saved-lines (get-in error [:current :lines]))))
  (is (= :stale-draft (:type (rejection #(decide ::entries/save-shared-inventory-entry {:entry saved} opening))))
      "an id already in use is not a new entry"))


(deftest an-accepted-or-discarded-entry-is-not-pending
  (is (= :entry-not-pending
         (:type (rejection #(decide ::entries/save-shared-inventory-entry {:entry (assoc saved :status "accepted")}
                                    (assoc opening :expected-revision 3)))))))


(deftest removed-lines-are-deleted
  (is (= [::entries/shared-inventory-entry-saved ::entries/inventory-entry-line-removed]
         (types (decide ::entries/save-shared-inventory-entry {:entry saved :lines saved-lines}
                        (assoc opening :expected-revision 3 :lines []))))))


(deftest comma-decimal-and-bounds
  (is (= 100.50M (entries/parse-amount "100,50")))
  (is (= "100.50" (get-in (decide ::entries/save-shared-inventory-entry {} (assoc-in opening [:lines 0 :unit-cost] "100,50"))
                          [:events 1 :data :unit-cost])))
  (doseq [bad ["1.23456" "-0.5" "ten"]]
    (is (= :validation (:type (rejection #(entries/parse-amount bad)))) bad)))


(deftest offset-instant-stored-as-utc-seconds
  (is (= (Instant/parse "2026-11-19T21:00:00Z") (entries/parse-instant "2026-11-20T00:00:00.700+03:00")))
  (is (= [:validation :occurred-at]
         ((juxt :type :field) (rejection #(decide ::entries/save-shared-inventory-entry {} (assoc opening :occurred-at "yesterday")))))))


(deftest other-workspace-id-is-not-permitted
  (is (= :not-permitted
         (:type (rejection #(decide ::entries/save-shared-inventory-entry {}
                                    (assoc opening :workspace-id #uuid "00000000-0000-0000-0000-000000000099")))))))


(deftest new-selections-use-active-records-existing-archived-stays
  (is (= [:validation :warehouse-id]
         ((juxt :type :field) (rejection #(decide ::entries/save-shared-inventory-entry {} (assoc-in opening [:lines 0 :warehouse-id] "old"))))))
  (is (seq (:events (decide ::entries/save-shared-inventory-entry {:entry saved :lines [(assoc (first saved-lines) :warehouse-id "old")]}
                            (-> opening (assoc :expected-revision 3) (assoc-in [:lines 0 :warehouse-id] "old")))))
      "a reference archived after it was saved is kept"))


;; 16 · Discard unaccepted inventory work


(deftest discard-a-pending-entry
  (is (= [{:type ::entries/inventory-draft-discarded :data {:entry-id "e1" :status "discarded" :revision 3}}
          {:type ::entries/inventory-entry-line-removed :data {:entry-line-id "e1/l1"}}]
         (mapv #(select-keys % [:type :data])
               (:events (decide ::entries/discard-inventory-draft {:entry (assoc saved :revision 2) :lines saved-lines}
                                {:entry-id "e1" :revision 2}))))))


(deftest discard-changed-since-confirmation-is-rejected
  (is (= :stale-draft (:type (rejection #(decide ::entries/discard-inventory-draft {:entry saved} {:entry-id "e1" :revision 2}))))))


(deftest discard-of-an-accepted-entry-is-rejected
  (is (= :entry-not-pending
         (:type (rejection #(decide ::entries/discard-inventory-draft {:entry (assoc saved :status "accepted")} {:entry-id "e1" :revision 3}))))))


;; 15 · Propose a stock correction


(def references-with-mug
  (-> references
      (update :products conj {:id "cup" :status "active"} {:id "mug" :status "active"})
      (update :stock-items conj {:id "mug-blue" :status "active"} {:id "old-item" :status "archived"})))


(defn correct
  {:malli/schema [:=> [:cat :map] :map]}
  [input]
  (decide ::entries/save-shared-inventory-entry {:references references-with-mug}
          (merge {:entry-id "c1" :kind "correction" :lines []} input)))


(def count-line {:line-id "l1" :stock-item-id "tee-m" :warehouse-id "main" :quantity "7"})


(deftest count-saved-as-pending
  (let [plan (correct {:lines [count-line]})]
    (is (= "correction" (get-in plan [:events 0 :data :kind])))
    (is (= 7 (get-in plan [:events 1 :data :quantity])))))


(deftest zero-is-a-count-blank-is-not
  (is (= 0 (get-in (correct {:lines [(assoc count-line :quantity "0")]}) [:events 1 :data :quantity])))
  (is (not (contains? (get-in (correct {:lines [(assoc count-line :quantity " ")]}) [:events 1 :data]) :quantity))))


(deftest invalid-count-kept
  (doseq [bad ["-1" "2.5"]]
    (is (= {:type :validation :field :quantity :line-id "l1" :attempted bad}
           (select-keys (rejection #(correct {:lines [(assoc count-line :quantity bad)]})) [:type :field :line-id :attempted])))))


(deftest one-row-one-entry
  (let [plan (correct {:lines [count-line] :changes [{:stock-item-id "tee-m" :article "TS-M"}]})]
    (is (= [::entries/shared-inventory-entry-saved ::entries/inventory-entry-line-saved ::entries/inventory-entry-change-saved]
           (types plan)))
    (is (= {:entry-change-id "c1/tee-m" :entry-id "c1" :stock-item-id "tee-m" :article "TS-M"} (get-in plan [:events 2 :data])))))


(deftest empty-text-clears-a-field
  (is (= "" (get-in (correct {:changes [{:stock-item-id "tee-m" :article " " :variant-label nil}]}) [:events 1 :data :article])))
  (is (not (contains? (get-in (correct {:changes [{:stock-item-id "tee-m" :variant-label nil}]}) [:events 1 :data]) :variant-label))))


(deftest barcode-list-cleaned
  (let [plan (correct {:changes [{:stock-item-id "mug-blue" :barcodes-added ["4600000000024" "0" " " "4600000000024"]}]})]
    (is (= [{:entry-change-barcode-id "c1/mug-blue|add|4600000000024" :entry-change-id "c1/mug-blue" :op "add"
             :barcode                 "4600000000024"}]
           (mapv :data (filter (comp #{::entries/inventory-entry-change-barcode-added} :type) (:events plan)))))))


(deftest move-proposed
  (is (= "mug" (get-in (correct {:changes [{:stock-item-id "mug-blue" :product-id "mug"}]}) [:events 1 :data :product-id]))))


(deftest restore-names-an-archived-item
  (is (true? (get-in (correct {:changes [{:stock-item-id "old-item" :restore true}]}) [:events 1 :data :restore]))))


(deftest invalid-corrections-name-the-field
  (doseq [[input field] [[{:lines [(assoc count-line :unit-cost "1.00")]} :unit-cost]
                         [{:lines [{:line-id "l1" :product-name "Mug" :warehouse-id "main" :quantity "1"}]} :stock-item-id]
                         [{:changes [{:stock-item-id "unknown"}]} :stock-item-id]
                         [{:changes [{:stock-item-id "mug-blue" :unit "crate"}]} :unit]
                         [{:changes [{:stock-item-id "mug-blue" :archive true :restore true}]} :archive]
                         [{:changes [{:stock-item-id "mug-blue" :product-id "nope"}]} :product-id]
                         [{:kind "opening" :changes [{:stock-item-id "mug-blue" :archive true}]} :changes]]]
    (is (= [:validation field] ((juxt :type :field) (rejection #(correct input)))) (pr-str input))))


(deftest removed-change-is-deleted
  (let [stored [{:stock-item-id "mug-blue" :archive true :barcodes-added ["4600000000024"]}]
        plan   (decide ::entries/save-shared-inventory-entry
                       {:references references-with-mug :entry (assoc saved :entry-id "c1" :kind "correction") :changes stored}
                       {:entry-id "c1" :kind "correction" :expected-revision 3 :changes []})]
    (is (= [::entries/shared-inventory-entry-saved ::entries/inventory-entry-change-barcode-removed ::entries/inventory-entry-change-removed]
           (types plan)))))


(deftest change-only-entry-needs-no-time
  (is (= [] (entries/blockers {:kind "correction" :changes [{:stock-item-id "mug-blue" :archive true}]} [] {})))
  (is (= [{:line-id nil :field :occurred-at :code :occurred-at-missing}]
         (entries/blockers {:kind "correction"} [] {}))))


;; 16 · Read shared pending inventory work, through the store


(defn context
  {:malli/schema [:=> [:cat :any :uuid] :map]}
  [repo workspace-id]
  (runtime/prepare-context {:bel/system  (support/bel-system {:repo repo})            :repo repo :workspace-id workspace-id :user-id member
                            :memberships [{:workspace-id workspace-id :role :member}]}))


(defn execute!
  {:malli/schema [:=> [:cat :map :keyword :string :map] :map]}
  [ctx command command-id input]
  (runtime/execute! ctx command {:command-id command-id :input input}))


(deftest pending-work-through-the-store
  (support/with-store
    (fn [repo _]
      (let [ctx  (context repo workspace)
            save #(execute! ctx ::entries/save-shared-inventory-entry %1 (merge {:workspace-id workspace} %2))]
        (execute! ctx :tover.catalogues.warehouses.model/register-warehouse "w1" {:warehouse-id "main" :name "Main"})
        (execute! ctx :tover.catalogues.warehouses.model/register-warehouse "w2" {:warehouse-id "old" :name "Old"})
        (execute! ctx :tover.catalogues.warehouses.model/register-warehouse "w3" {:warehouse-id "spare" :name "Spare"})
        (let [first-save (-> opening (assoc-in [:lines 0 :stock-item-id] nil) (assoc-in [:lines 0 :product-name] "T-shirt")
                             (assoc-in [:lines 0 :barcodes] ["4600000000017"]))]
          (save "s1" first-save)
          (testing "a retried attempt returns the original outcome; the entry exists once"
            (save "s1" first-save)))
        (testing "one entry"
          (is (= 1 (count (:entries (runtime/read-model ctx ::entries/pending-entries {}))))))
        (execute! ctx :tover.catalogues.warehouses.model/archive-warehouse "a1" {:warehouse-id "old" :revision 1})
        (save "s2" {:entry-id "e2" :kind "opening" :lines [{:line-id "a" :product-name "Mug"}]})
        (testing "a shared pending entry blocks archiving the warehouse it names"
          (save "s2b" {:entry-id "e3" :kind "opening" :lines [{:line-id "z" :warehouse-id "spare"}]})
          (is (= :pending-reference
                 (:type (rejection #(execute! ctx :tover.catalogues.warehouses.model/archive-warehouse "a2" {:warehouse-id "spare" :revision 1})))))
          (execute! ctx ::entries/discard-inventory-draft "d1" {:workspace-id workspace :entry-id "e3" :revision 1}))
        (testing "a discard releases the archive block"
          (is (nil? (rejection #(execute! ctx :tover.catalogues.warehouses.model/archive-warehouse "a3" {:warehouse-id "spare" :revision 1})))))
        (testing "the remaining entries after a discard, unchanged"
          (is (= ["e1" "e2"] (map :entry-id (:entries (runtime/read-model ctx ::entries/pending-entries {}))))))
        (testing "another member of the workspace sees the same entries"
          (is (= ["e1" "e2"] (map :entry-id (:entries (runtime/read-model (assoc ctx :user-id #uuid "00000000-0000-0000-0000-000000000012")
                                                                          ::entries/pending-entries {}))))))
        (testing "saving works when the rules cannot be evaluated"
          (with-redefs [tover.inventory.findings/derive (fn [_] (throw (ex-info "rules down" {})))]
            (save "s4" {:entry-id "e4" :kind "opening" :lines [{:line-id "q" :warehouse-id "main" :quantity "1"}]})
            (is (empty? (:findings (runtime/read-model ctx ::entries/pending-entries {}))))
            (is (some #{"e4"} (map :entry-id (:entries (runtime/read-model ctx ::entries/pending-entries {})))))))
        (execute! ctx ::entries/discard-inventory-draft "d4" {:workspace-id workspace :entry-id "e4" :revision 1})
        (let [{[e1 e2] :entries} (runtime/read-model ctx ::entries/pending-entries {})]
          (testing "everyone sees shared work with its saved values"
            (is (= [{:line-id   "l1"     :product-name "T-shirt" :barcodes ["4600000000017"] :warehouse-id "main" :quantity 5
                     :unit-cost "100.00"}]
                   (:lines e1)))
            (is (= [] (:blockers e1))))
          (testing "findings cover every pending entry"
            (is (contains? (set (map (juxt :line-id :code) (:findings (runtime/read-model ctx ::entries/pending-entries {}))))
                           ["a" :new-reference])))
          (testing "incomplete entry shows blockers"
            (is (= #{[nil :occurred-at :occurred-at-missing] ["a" :quantity :quantity-missing]
                     ["a" :warehouse-id :warehouse-missing] ["a" :unit-cost :unit-cost-missing]}
                   (set (map (juxt :line-id :field :code) (:blockers e2)))))))
        (testing "stale save through the store keeps the attempt out"
          (is (= :stale-draft (:type (rejection #(save "s3" (assoc opening :expected-revision 7)))))))
        (testing "another workspace never sees these entries"
          (is (= {:entries [] :findings []}
                 (runtime/read-model (context repo #uuid "00000000-0000-0000-0000-000000000002") ::entries/pending-entries {}))))))))


;; 18 · Propose the purchase · 19 · Propose the sale


(def purchase
  {:entry-id "p1" :kind "purchase" :occurred-at "2026-11-19T00:00:00Z" :supplier-id "tex"
   :lines    [{:line-id "l1" :stock-item-id "tee-m" :warehouse-id "main" :quantity "5" :unit-cost "200.00"}]})


(def sale
  {:entry-id "s1" :kind "sale" :occurred-at "2026-11-19T00:00:00Z" :store-id "web"
   :lines    [{:line-id "l1" :stock-item-id "tee-m" :warehouse-id "main" :quantity "3" :unit-price "150.00"}]})


(defn- save-rejection
  {:malli/schema [:=> [:cat :map] [:maybe :map]]}
  [input]
  (select-keys (rejection #(decide ::entries/save-shared-inventory-entry {} input)) [:type :field]))


(deftest save-a-purchase-and-a-sale
  (let [plan (decide ::entries/save-shared-inventory-entry {} purchase)]
    (is (= ["purchase" "tex"] ((juxt :kind :supplier-id) (get-in plan [:events 0 :data]))))
    (is (= [5 "200.00"] ((juxt :quantity :unit-cost) (get-in plan [:events 1 :data])))))
  (let [plan (decide ::entries/save-shared-inventory-entry {} sale)]
    (is (= ["sale" "web"] ((juxt :kind :store-id) (get-in plan [:events 0 :data]))))
    (is (= [3 "150.00"] ((juxt :quantity :unit-price) (get-in plan [:events 1 :data])))))
  (testing "a price is optional"
    (is (not (contains? (get-in (decide ::entries/save-shared-inventory-entry {} (update-in sale [:lines 0] dissoc :unit-price)) [:events 1 :data])
                        :unit-price))))
  (testing "a new supplier is saved by name"
    (is (= [nil "Cotton Co"] ((juxt :supplier-id :supplier-name)
                              (get-in (decide ::entries/save-shared-inventory-entry {} (-> purchase (dissoc :supplier-id) (assoc :supplier-name " Cotton Co ")))
                                      [:events 0 :data]))))))


(deftest operation-quantities-are-whole-and-positive
  (doseq [bad ["0" "-3" "2.5"] op [purchase sale]]
    (is (= {:type :validation :field :quantity} (save-rejection (assoc-in op [:lines 0 :quantity] bad))) (str (:kind op) " " bad))))


(deftest a-new-stock-item-takes-a-store
  (is (= "web" (:store-id (get-in (decide ::entries/save-shared-inventory-entry {}
                                          (update-in purchase [:lines 0] #(-> % (dissoc :stock-item-id) (assoc :product-id "tee" :variant-label "XL" :unit "pcs" :store-id "web"))))
                                  [:events 1 :data]))))
  (is (= {:type :validation :field :store-id} (save-rejection (assoc-in purchase [:lines 0 :store-id] "web")))
      "an existing item changes its store by a correction"))


(deftest negative-prices-are-rejected
  (is (= {:type :validation :field :unit-cost} (save-rejection (assoc-in purchase [:lines 0 :unit-cost] "-1"))))
  (is (= {:type :validation :field :unit-price} (save-rejection (assoc-in sale [:lines 0 :unit-price] "-1")))))


(deftest a-sale-takes-existing-stock
  (is (= {:type :validation :field :stock-item-id}
         (save-rejection (update-in sale [:lines 0] #(-> % (dissoc :stock-item-id) (assoc :product-name "Cup"))))))
  (is (= :validation (:type (save-rejection (update-in sale [:lines 0] #(-> % (dissoc :warehouse-id) (assoc :warehouse-name "Back"))))))))


(deftest references-fit-the-kind
  (is (= :validation (:type (save-rejection (assoc purchase :store-id "web")))))
  (is (= :validation (:type (save-rejection (assoc sale :supplier-id "tex")))))
  (is (= :validation (:type (save-rejection (assoc-in purchase [:lines 0 :unit-price] "1")))))
  (is (= {:type :validation :field :supplier-id} (save-rejection (assoc purchase :supplier-id "gone"))) "a new selection must be active")
  (is (= {:type :validation :field :store-id} (save-rejection (assoc sale :store-id "closed")))))


(deftest operation-blockers
  (let [status {:warehouses {"main" "active"} :stock-items {"tee-m" "active"} :suppliers {"gone" "archived"} :stores {"closed" "archived"}}
        line   {:line-id "l1" :stock-item-id "tee-m" :warehouse-id "main" :quantity 1}
        codes  #(set (map (juxt :field :code) (entries/blockers %1 %2 status)))]
    (is (= #{[:supplier-id :supplier-missing]} (codes {:kind "purchase" :occurred-at at} [(assoc line :unit-cost "5")])))
    (is (= #{[:unit-cost :unit-cost-missing]} (codes {:kind "purchase" :occurred-at at :supplier-name "Textiles"} [line]))
        "units enter stock only at a cost")
    (is (= #{[:unit-cost :unit-cost-missing]} (codes {:kind "opening" :occurred-at at} [line])))
    (is (= #{[:lines :lines-missing]} (codes {:kind "sale" :occurred-at at} [])))
    (is (= #{[:supplier-id :archived-reference]} (codes {:kind "purchase" :occurred-at at :supplier-id "gone"} [(assoc line :unit-cost "5")])))
    (is (= #{[:store-id :archived-reference]} (codes {:kind "sale" :occurred-at at :store-id "closed"} [line])))))


;; 20–25 · Propose returns, transfers, production, defects, write-offs and payments


(def transfer
  {:entry-id "t1" :kind "transfer" :occurred-at "2026-11-19T00:00:00Z" :from-warehouse-id "main" :to-warehouse-id "back"
   :lines    [{:line-id "l1" :stock-item-id "tee-m" :quantity "3"}]})


(def with-back
  (-> references
      (update :warehouses conj {:id "back" :status "active"})
      (update :stock-items conj {:id "tee-m~defect" :status "active" :condition "defective"})))


(defn- other-rejection
  {:malli/schema [:=> [:cat :map] [:maybe :map]]}
  [input]
  (select-keys (rejection #(decide ::entries/save-shared-inventory-entry {:references with-back} input)) [:type :field]))


(deftest save-each-other-kind
  (let [saved #(get-in (decide ::entries/save-shared-inventory-entry {:references with-back} %) [:events 0 :data])]
    (is (= "web" (:store-id (saved (assoc sale :kind "customer-return")))) "a customer return may name a store and price")
    (is (= "tex" (:supplier-id (saved (-> sale (assoc :kind "supplier-return" :supplier-id "tex") (dissoc :store-id))))))
    (is (= ["main" "back"] ((juxt :from-warehouse-id :to-warehouse-id) (saved transfer))))
    (is (= ["input" "output"]
           (map :side (rest (map :data (:events (decide ::entries/save-shared-inventory-entry {:references with-back} {:entry-id "m1" :kind "production" :occurred-at "2026-11-19T00:00:00Z"
                                                                                                                       :lines    [{:line-id "a" :stock-item-id "tee-m" :warehouse-id "main" :quantity "2" :side "input"}
                                                                                                                                  {:line-id "b" :product-id "tee" :variant-label "Pack" :unit "pcs" :warehouse-id "main" :quantity "1" :side "output"}]})))))))
    (is (= ["out" "tex" "10.5"] ((juxt :direction :supplier-id :amount)
                                 (saved {:entry-id "pay" :kind "payment" :occurred-at "2026-11-19T00:00:00Z" :direction "out" :supplier-id "tex" :amount "10,5"}))))))


(deftest other-kinds-reject-what-they-do-not-take
  (is (= {:type :validation :field :quantity} (other-rejection (assoc-in transfer [:lines 0 :quantity] "0"))))
  (is (= {:type :validation :field :to-warehouse-id} (other-rejection (assoc transfer :to-warehouse-id "main"))) "same warehouse")
  (is (= :validation (:type (other-rejection (assoc-in transfer [:lines 0 :warehouse-id] "main")))) "a transfer line has no warehouse")
  (is (= :validation (:type (other-rejection (-> sale (assoc :kind "customer-return") (update-in [:lines 0] #(-> % (assoc :product-name "Cup") (dissoc :stock-item-id)))))))
      "no new item on a return")
  (is (= :validation (:type (other-rejection (assoc sale :kind "supplier-return" :store-id "web")))))
  (is (= {:type :validation :field :stock-item-id}
         (other-rejection {:entry-id "d1" :kind "defect" :reason "torn" :lines [{:line-id "l1" :stock-item-id "tee-m~defect" :warehouse-id "main" :quantity "1"}]}))
      "a defect takes a Regular item")
  (is (= :validation (:type (other-rejection {:entry-id "pay" :kind "payment" :supplier-id "tex" :store-id "web" :amount "1"}))) "one counterparty")
  (is (= {:type :validation :field :amount} (other-rejection {:entry-id "pay" :kind "payment" :supplier-id "tex" :amount "0"})))
  (is (= :validation (:type (other-rejection {:entry-id "pay" :kind "payment" :supplier-id "tex" :amount "1" :lines [{:line-id "l1" :stock-item-id "tee-m"}]})))
      "a payment has no lines"))


(deftest other-kind-blockers
  (let [status {:warehouses {"main" "active" "old" "archived"} :stock-items {"tee-m" "active"} :suppliers {"tex" "active"} :stores {}}
        codes  #(set (map (juxt :field :code) (entries/blockers %1 %2 status)))]
    (is (= #{[:reason :reason-missing]} (codes {:kind "write-off" :occurred-at at} [{:line-id "l1" :stock-item-id "tee-m" :warehouse-id "main" :quantity 1}])))
    (is (= #{[:to-warehouse-id :warehouse-missing]} (codes {:kind "transfer" :occurred-at at :from-warehouse-id "main"} [{:line-id "l1" :stock-item-id "tee-m" :quantity 1}])))
    (is (= #{[:from-warehouse-id :archived-reference]}
           (codes {:kind "transfer" :occurred-at at :from-warehouse-id "old" :to-warehouse-id "main"} [{:line-id "l1" :stock-item-id "tee-m" :quantity 1}])))
    (is (= #{[:lines :outputs-missing]} (codes {:kind "production" :occurred-at at} [{:line-id "l1" :stock-item-id "tee-m" :warehouse-id "main" :quantity 1 :side "input"}])))
    (is (= #{[:direction :direction-missing] [:supplier-id :counterparty-missing] [:amount :amount-missing]} (codes {:kind "payment" :occurred-at at} [])))))
