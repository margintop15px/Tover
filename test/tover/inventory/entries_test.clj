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
    (bel/build-registry! ['tover.shared.web.core 'tover.catalogues.warehouses.model 'tover.catalogues.categories.model
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
   :stock-items [{:id "tee-m" :status "active"}]})


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
          (save "s2b" {:entry-id "e3" :kind "opening" :lines [{:line-id "z" :warehouse-id "main"}]})
          (is (= :pending-reference
                 (:type (rejection #(execute! ctx :tover.catalogues.warehouses.model/archive-warehouse "a2" {:warehouse-id "main" :revision 1})))))
          (execute! ctx ::entries/discard-inventory-draft "d1" {:workspace-id workspace :entry-id "e3" :revision 1}))
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
                     ["a" :warehouse-id :warehouse-missing]}
                   (set (map (juxt :line-id :field :code) (:blockers e2)))))))
        (testing "stale save through the store keeps the attempt out"
          (is (= :stale-draft (:type (rejection #(save "s3" (assoc opening :expected-revision 7)))))))
        (testing "another workspace never sees these entries"
          (is (= {:entries [] :findings []}
                 (runtime/read-model (context repo #uuid "00000000-0000-0000-0000-000000000002") ::entries/pending-entries {}))))))))
