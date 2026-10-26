(ns tover.inventory.history-ui-test
  (:require
    [bel.handler :as handler]
    [clojure.test :refer [deftest is]]
    [tover.inventory.tables :as tables]
    [tover.inventory.workbench :as workbench]))


(defn audit
  {:malli/schema [:=> [:cat :string :int :keyword] :map]}
  [entity revision status]
  {:entity-id   entity :revision                    revision               :status status :actor "immutable-actor-id" :actor-label "Owner"
   :reason      "Verified count" :touches-item?     true
   :occurred-at "2026-10-20T12:00:00Z" :recorded-at "2026-10-21T08:00:00Z"
   :entry       {:kind :opening :occurred-at "2026-10-20T12:00:00Z"}})


(defn effect
  {:malli/schema [:=> [:cat :string :int :string :int :int] :map]}
  [entity revision location before after]
  {:operation-id entity                 :operation-revision revision :item-id "goods"
   :occurred-at  "2026-10-20T12:00:00Z" :location           location :before  before  :after after})


(deftest effective-effects-join-by-entity-and-revision
  (let [history {:audit-records [(audit "count" 1 :superseded)
                                 (audit "count" 2 :effective)
                                 (audit "cancelled" 1 :superseded)
                                 (audit "cancelled" 2 :cancelled)]
                 :records       [(effect "count" 1 "Main" 0 3) (effect "count" 2 "Main" 0 7)
                                 (effect "cancelled" 2 "Main" 0 99)]}
        rows    (:rows (tables/history history))]
    (is (= ["count/1" "count/2" "cancelled/1" "cancelled/2"] (mapv :id rows)))
    (is (= ["—" "0 → 7 · Main" "—" "—"] (mapv #(get-in % [:cells 6 :value]) rows)))
    (is (= ["Superseded" "Effective" "Superseded" "Cancelled"] (mapv #(get-in % [:cells 3 :value]) rows)))))


(deftest multiple-effects-stay-in-one-revision-without-parent-timeline-records
  (let [effects [(effect "purchase" 1 "Main" 0 3)
                 (effect "purchase" 1 "Main" 3 7)
                 (assoc (effect "purchase" 1 "Shelf" 0 2) :occurred-at "2026-10-22T08:00:00Z")]
        history {:audit-records    [(audit "purchase" 1 :effective)]              :records effects
                 :timeline-records [(effect "parent" 1 "Parent warehouse" 0 100)]}
        rows    (:rows (tables/history history))]
    (is (= 1 (count rows)))
    (is (= "0 → 3 · Main; 3 → 7 · Main; 0 → 2 · Shelf" (get-in rows [0 :cells 6 :value])))
    (is (not (.contains (str rows) "Parent warehouse")))
    (is (= [] (:rows (tables/history {:records effects}))))))


(deftest history-renders-one-audited-spreadsheet-with-automatic-timeline-and-icon-actions
  (let [history {:item          {:id "goods" :name "Goods"} :ledger-revision 9
                 :audit-records [(audit "opening" 1 :effective)
                                 (assoc (audit "count" 1 :cancelled) :actor-label "Reviewer" :reason "Wrong count")]
                 :records       [(effect "opening" 1 "Main" 0 3)]}
        html    (handler/render-html (workbench/history-panel (assoc history :row-id "goods@Main" :request-id "history-test" :mode "history")))
        model   (tables/history history)]
    (is (= 1 (count (re-seq #"<inventory-grid\b" html))))
    (is (re-find #"aria-label=\"Item history records\"" html))
    (is (= ["Occurred at" "Recorded at" "Operation type" "Status" "Actor" "Reason" "Quantity / location"]
           (mapv :label (:columns model))))
    (is (every? true? (for [row (:rows model) column [0 1]] (get-in row [:cells column :date]))))
    (is (= ["Owner" "Reviewer"] (mapv #(get-in % [:cells 4 :value]) (:rows model))))
    (is (not (.contains html "immutable-actor-id")))
    (is (re-find #"aria-label=\"Refresh history\"" html))
    (is (re-find #"aria-label=\"Close history\"" html))
    (is (re-find #"role=\"tablist\"" html))
    (is (re-find #"data-history-mode=\"history\"" html))
    (is (not (re-find #"inventory-timeline|lineage-lane" html)))
    (let [lineage (handler/render-html (workbench/history-panel (assoc history :row-id "goods@Main" :request-id "history-lineage" :mode "lineage")))]
      (is (re-find #"inventory-timeline" lineage))
      (is (not (re-find #"item-history-grid" lineage))))
    (is (not (re-find #"Snapshot · accepted ledger revision|All item revisions and cancellations|Pooled quantities|Include parent items|Show this item only" html)))))


(deftest revised-to-other-items-still-shows-its-audit-row-without-quantity
  (let [history {:item          {:id "goods" :name "Goods"}
                 :audit-records [(assoc (audit "opening" 2 :effective) :touches-item? false)] :records []}
        row     (first (:rows (tables/history history)))]
    (is (= "—" (get-in row [:cells 6 :value])))
    (is (= "Effective · Revised to other items" (get-in row [:cells 3 :value])))
    (is (= "Verified count" (get-in row [:cells 5 :value])))))
