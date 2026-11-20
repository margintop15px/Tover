(ns tover.inventory.entries-test
  "Board chapters 16, 14 and 32a · Save, discard and read shared pending inventory entries."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.runtime :as runtime]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [tover.inventory.entries.model :as entries]
    [tover.inventory.store-test :as fixture]
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


(def workspace (:workspace-id fixture/scope))
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
  (fixture/with-store
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
