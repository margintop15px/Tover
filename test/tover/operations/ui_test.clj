(ns tover.operations.ui-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [tover.operations.ui :as ui]
    [tover.shared.i18n :as i18n]))


(def ^:private production
  {:id      "production-1" :kind :operation :type :production :occurred-at "2026-10-20T09:30:00Z"
   :reason  "Repack"
   :inputs  [{:item-id "a" :name "Notebooks" :article "A" :quantity 6 :unit "pcs" :location "Main"}]
   :outputs [{:item-id "b" :name "Notebook packs" :article "B" :quantity 2 :unit "pack" :location "Main"}]})


(defn- record
  {:malli/schema [:=> [:cat :int :map] :map]}
  [revision entry]
  {:entity-id   (:id entry) :revision revision :recorded-at "2026-10-20T10:00:00Z"
   :actor       "00000000-0000-0000-0000-000000000001" :created-by       "00000000-0000-0000-0000-000000000002" :proposed-by       "00000000-0000-0000-0000-000000000003"
   :actor-label "reviewer@example.test"                :created-by-label "creator@example.test"                 :proposed-by-label "editor@example.test"
   :reason      (:reason entry) :entry entry})


(deftest server-formats-production-and-audit-details
  (let [row    (first (ui/rows [production] [(record 1 production)]))
        detail (:detail row)]
    (is (= :production (:type row)))
    (is (= "Production" (:operation row)))
    (is (= "2026-10-20T09:30:00Z" (:occurred-at row)))
    (is (= "2026-10-20T09:30:00Z" (:time row)))
    (is (every? :date? (filter #(#{"Occurred at" "Recorded at"} (:label %)) (:metadata detail))))
    (is (= "Notebooks × 6, Notebook packs × 2" (:summary row)))
    (is (= 2 (:items row)))
    (is (= "Main" (:context row)))
    (is (= ["Inputs consumed" "Outputs created"] (mapv :heading (:sections detail))))
    (is (= ["Notebooks · A" "6 pcs" "Main" "—"] (get-in detail [:sections 0 :rows 0])))
    (is (= #{"reviewer@example.test" "creator@example.test" "editor@example.test"}
           (set (map :value (filter #(#{"Submitted by" "Created by" "Edited by"} (:label %)) (:metadata detail))))))
    (is (= 1 (count (:revisions detail))))))


(deftest cancelled-entities-remain-inspectable
  (let [original     (record 1 production)
        correction   (assoc (record 2 (assoc production :reason "Corrected quantities")) :previous-revision 1)
        cancellation (assoc (record 3 production) :cancelled? true :reason "Cancelled mistake")
        rows         (ui/rows [] [original cancellation correction])
        row          (first rows)]
    (is (= 1 (count rows)))
    (is (= "production-1" (:id row)))
    (is (:cancelled? row))
    (is (= "Production · cancelled" (:operation row)))
    (is (= "Cancelled mistake" (:reason row)))
    (is (get-in row [:detail :cancelled]))
    (is (= ["Cancelled" "Correction" "Accepted"]
           (mapv :heading (get-in row [:detail :revisions]))))))


(deftest expanded-operation-only-adds-facts-not-already-in-its-row
  (let [detail          (:detail (first (ui/rows [production] [(record 2 production)])))
        same-person     (assoc (record 1 production) :created-by "00000000-0000-0000-0000-000000000001"
                               :proposed-by "00000000-0000-0000-0000-000000000001")
        simple          (:detail (first (ui/rows [production] [same-person])))
        same-editor     (assoc (record 1 production) :proposed-by "00000000-0000-0000-0000-000000000002")
        one-author      (:detail (first (ui/rows [production] [same-editor])))
        same-labels     (assoc (record 1 production) :created-by-label "reviewer@example.test"
                               :proposed-by-label "reviewer@example.test")
        repeated-labels (:detail (first (ui/rows [production] [same-labels])))]
    (is (= ["Reason" "Recorded at" "Submitted by" "Created by" "Edited by"]
           (mapv :label (:summary-metadata detail))))
    (is (= ["Reason" "Recorded at" "Submitted by"] (mapv :label (:summary-metadata simple))))
    (is (= ["Reason" "Recorded at" "Submitted by" "Created by"] (mapv :label (:summary-metadata one-author))))
    (is (= ["Reason" "Recorded at" "Submitted by" "Created by" "Edited by"]
           (mapv :label (:summary-metadata repeated-labels))))
    (is (not-any? #(= "Revision" (:label %)) (:metadata detail)))
    (is (not-any? #(re-find #"Revision|[0-9]" (:heading %)) (:revisions detail)))
    (is (= 2 (count (:sections detail))))
    (is (some #(= "Occurred at" (:label %)) (:metadata detail)))))


(deftest historical-purchases-display-stock-affecting-items-with-one-commercial-amount
  (testing "Accepted receipt facts remain readable as ordinary items"
    (let [entry  {:id       "purchase-1" :kind :operation :type     :purchase :occurred-at "2026-10-18T09:00:00Z"
                  :party    "Supplier" :amount 100.25M    :currency "EUR"
                  :lines    [{:item-id "a" :name "Notebook" :quantity 10 :location "Main"}]
                  :receipts [{:occurred-at "2026-10-20T09:00:00Z"
                              :lines       [{:item-id "a" :name "Notebook" :quantity 4 :location "Main"}]}]}
          detail (:detail (first (ui/rows [entry] [(record 1 entry)])))]
      (is (= ["Items"] (mapv :heading (:sections detail))))
      (is (not-any? :date (:sections detail)))
      (is (= "4 pcs" (get-in detail [:sections 0 :rows 0 1])))
      (is (= "Notebook × 4" (:summary (first (ui/rows [entry] [(record 1 entry)])))))
      (is (= [{:key "Amount" :label "Amount" :value "100.25 EUR"}]
             (filterv #(= "Amount" (:label %)) (:metadata detail))))))
  (testing "Payment has no invented stock lines"
    (let [entry {:id          "payment-1"            :kind  :operation :type   :payment :direction :outgoing
                 :occurred-at "2026-10-20T09:00:00Z" :party "Supplier" :amount 25.10M   :currency  "EUR"}
          row   (first (ui/rows [entry] [(record 1 entry)]))]
      (is (= "Outgoing 25.10 EUR" (:summary row)))
      (is (zero? (:items row)))
      (is (empty? (get-in row [:detail :sections]))))))


(deftest sheet-binds-server-sort-and-revision-actions
  (let [sheet      (ui/sheet {:init "el.entries = $_operationRows" :on-sort "sort" :on-filter "filter" :on-revise "revise"})
        element    (second (rest sheet))
        attributes (second element)]
    (is (= :section.inventory-operations (first sheet)))
    (is (= :stock-operations#operations-history (first element)))
    (is (contains? attributes :data-on:operations-sort))
    (is (= "filter" (:data-on:operations-filter attributes)))
    (is (contains? attributes :data-on:operation-revise))
    (is (re-find #"_operationRows" (:data-effect attributes)))
    (is (not (re-find #"lineage|examples" (pr-str sheet))))))


(deftest purchase-item-details-have-no-arrival-statuses-in-both-languages
  (doseq [locale ["en" "ru"]]
    (binding [i18n/*locale* locale]
      (let [entry {:id    "purchase-2" :kind :operation :type :purchase :occurred-at "2026-10-20T09:00:00Z"
                   :lines [{:item-id "a" :name "Notebook" :quantity 10 :location "Main"}]}
            row   (first (ui/rows [entry] [(record 1 entry)]))]
        (is (= [(i18n/t "Items")] (mapv :heading (get-in row [:detail :sections]))))
        (is (= "10 " (subs (get-in row [:detail :sections 0 :rows 0 1]) 0 3)))
        (is (= "Notebook × 10" (:summary row)))))))
