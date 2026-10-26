(ns tover.inventory.timeline-ui-test
  (:require
    [bel.handler :as handler]
    [clojure.test :refer [deftest is]]
    [tover.inventory.timeline :as timeline]
    [tover.operations.ui :as operations-ui]))


(defn record
  {:malli/schema [:=> [:cat :string :string :string :string :int :int :int] :map]}
  [item-id location operation-id occurred-at before after revision]
  {:item-id            item-id  :location location :operation-id operation-id :occurred-at occurred-at
   :operation-revision revision :before   before   :after        after        :delta       (- after before) :kind :operation :type :production})


(def history
  {:item             {:id "child" :name "Finished goods"}
   :items            {"child" {:name "Finished goods" :unit "pcs"} "parent" {:name "Raw goods" :unit "pcs"}}
   :snapshot-at      "2026-10-22T12:00:00Z"
   :timeline-records [(record "child" "Main" "make" "2026-10-21T12:00:00Z" 0 5 2)
                      (record "child" "Shelf" "move" "2026-10-21T14:00:00Z" 0 2 1)
                      (assoc (record "parent" "Main" "receive" "2026-10-20T12:00:00Z" 0 10 1) :timeline-until "2026-10-21T12:00:00Z")
                      (assoc (record "parent" "Main" "make" "2026-10-21T12:00:00Z" 10 0 2) :timeline-until "2026-10-21T12:00:00Z")]
   :lineage          [{:operation-id "make" :occurred-at "2026-10-21T12:00:00Z"
                       :inputs       [{:item-id "parent" :quantity 10}] :outputs [{:item-id "child" :quantity 5}]}]})


(deftest separate-item-location-lanes-and-palette-roles
  (let [chart  (timeline/chart history)
        html   (handler/render-html chart)
        strips (filter #(and (vector? %) (= :button.lineage-parent-strip (first %))) (tree-seq coll? seq chart))
        plot   (first (filter #(and (vector? %) (= :div.lineage-chart (first %))) (tree-seq coll? seq chart)))]
    (is (= 2 (count (re-seq #"class=\"lineage-lane\"" html))))
    (is (= 2 (count (re-seq #"class=\"lineage-marker " html))))
    (is (= 1 (count strips)))
    (is (some #(= (first strips) %) (tree-seq coll? seq plot)))
    (is (= 1 (count (re-seq #"class=\"lineage-parent-lane\"" html))))
    (is (< (.indexOf html "class=\"lineage-parent-lane\"") (.indexOf html "class=\"lineage-lane\"")))
    (is (re-find #"class=\"lineage-chart has-parents\"" html))
    (is (= "parent" (get-in (first strips) [1 :data-item-id])))
    (is (= [:span.lineage-parent-name "Raw goods"] (nth (first strips) 2)))
    (is (= [:span.lineage-parent-quantity " × 10 pcs"] (nth (first strips) 3)))
    (is (re-find #"is-current" html))
    (is (re-find #"is-produced" html))
    (is (not (re-find #"lineage-legend|lineage-production|Recorded balance|Include parent items|<script" html)))))


(deftest bars-markers-and-production-links-request-authorized-details
  (let [chart (timeline/chart history)
        html  (handler/render-html chart)
        strip (first (filter #(and (vector? %) (= :button.lineage-parent-strip (first %))) (tree-seq coll? seq chart)))
        attrs (second strip)]
    (is (re-find #"open-history-operation" html))
    (is (= 5 (count (re-seq #"data-on:click=" html))))
    (is (= "make" (:data-operation-id attrs)))
    (is (= 2 (:data-operation-revision attrs)))
    (is (= "Production · Raw goods · 10 pcs consumed" (:aria-label attrs)))
    (is (re-find #"aria-label=\"Production · Finished goods · Main · 0 → 5 pcs\"" html))
    (is (re-find #"h.dataset.rowId" (:data-on:click attrs)))
    (is (re-find #"requestId=crypto.randomUUID\(\)" (:data-on:click attrs)))
    (is (.contains (:data-on:click attrs) "\"itemId\": \"child\""))
    (is (.contains (:data-on:click attrs) "\"operationId\": \"make\""))
    (is (not (.contains html "&quot;operationId&quot;: &quot;receive&quot;")))))


(deftest bars-show-operation-types-and-keep-balances-in-accessible-details
  (let [data  (update history :timeline-records
                      #(mapv (fn [event]
                               (assoc event :type (case (:operation-id event) "receive" :purchase "move" :transfer :production))) %))
        chart (timeline/chart data)
        bars  (filter #(and (vector? %) (= :button.lineage-bar (first %))) (tree-seq coll? seq chart))
        html  (handler/render-html chart)]
    (is (= #{"Production" "Transfer"} (set (map last bars))))
    (is (not-any? #(re-find #"pcs" (last %)) bars))
    (is (re-find #"Production · Finished goods · Main · 0 → 5 pcs" html))
    (is (not (re-find #"Purchase · Raw goods|2026-10-20T12:00:00Z" html)))))


(deftest ancestor-cutoffs-bound-bars-and-omit-future-events
  (let [past    (assoc (record "parent" "Main" "parent-sale" "2026-10-21T08:00:00Z" 100 10 3) :type :sale)
        future  (assoc (record "parent" "Main" "future" "2026-10-22T08:00:00Z" 0 99 1) :timeline-until "2026-10-21T12:00:00Z")
        chart   (timeline/chart (update history :timeline-records into [past future]))
        html    (handler/render-html chart)
        lanes   (filter #(and (vector? %) (= :div.lineage-lane (first %))) (tree-seq coll? seq chart))
        parents (filter #(and (vector? %) (= :button.lineage-parent-strip (first %))) (tree-seq coll? seq chart))]
    (is (= #{"child"} (set (map #(get-in % [1 :data-item-id]) lanes))))
    (is (= 1 (count parents)))
    (is (not (re-find #"future|99 pcs|parent-sale|100 → 10|2026-10-20" html)))
    (is (re-find #"datetime=\"2026-10-21T12:00:00Z\" data-human-date" html))
    (is (= "right:100.0%;" (get-in (first parents) [1 :style])))))


(deftest parent-strips-near-the-right-edge-keep-their-production-anchor
  (let [at    "2026-10-22T11:00:00Z"
        data  (-> history
                  (assoc-in [:lineage 0 :occurred-at] at)
                  (update :timeline-records
                          #(into [(assoc (record "child" "Main" "initial" "2026-10-20T12:00:00Z" 0 1 1) :type :purchase)]
                                 (map (fn [event] (if (= "make" (:operation-id event)) (assoc event :occurred-at at) event)) %))))
        chart (timeline/chart data)
        strip (first (filter #(and (vector? %) (= :button.lineage-parent-strip (first %))) (tree-seq coll? seq chart)))]
    (is (re-find #"^right:" (get-in strip [1 :style])))
    (is (= at (get-in strip [1 :data-human-date-title])))))


(deftest times-use-shared-local-date-hooks-and-empty-chart-is-valid
  (let [html (handler/render-html (timeline/chart history))]
    (is (re-find #"datetime=\"2026-10-21T12:00:00Z\" data-human-date=\"\"" html))
    (is (re-find #"data-human-date-title=\"2026-10-21T12:00:00Z\"" html))
    (is (not (re-find #">2026-10-" html)))
    (is (re-find #"No inventory history recorded" (handler/render-html (timeline/chart {:item {:id "empty"} :records []}))))))


(deftest parent-strips-preserve-whole-input-lines-across-locations-and-multiple-outputs
  (let [data   (-> history
                   (assoc-in [:lineage 0 :inputs]
                             [{:line-id "main" :item-id "parent" :name "Former raw name" :quantity 7 :location "Main"}
                              {:line-id "shelf" :item-id "parent" :quantity 3 :unit "boxes" :location "Shelf"}
                              {:line-id "other" :item-id "other" :name "Other input" :quantity 4 :unit "kg" :location "Remote"}])
                   (assoc-in [:lineage 0 :outputs]
                             [{:item-id "child" :quantity 5} {:item-id "sibling" :quantity 100}]))
        chart  (timeline/chart data)
        strips (filter #(and (vector? %) (= :button.lineage-parent-strip (first %))) (tree-seq coll? seq chart))
        html   (handler/render-html chart)]
    (is (= 3 (count strips)))
    (is (= ["parent" "parent" "other"] (mapv #(get-in % [1 :data-item-id]) strips)))
    (is (= ["Main" "Shelf" "Remote"] (mapv #(get-in % [1 :data-location]) strips)))
    (is (= [[:span.lineage-parent-quantity " × 7 pcs"]
            [:span.lineage-parent-quantity " × 3 boxes"]
            [:span.lineage-parent-quantity " × 4 kg"]]
           (mapv #(nth % 3) strips)))
    (is (= [[:span.lineage-parent-name "Raw goods"] [:span.lineage-parent-name "Raw goods"] [:span.lineage-parent-name "Other input"]]
           (mapv #(nth % 2) strips)))
    (is (= #{2} (set (map #(get-in % [1 :data-operation-revision]) strips))))
    (is (not (re-find #"Former raw name|sibling|100 pcs" html)))))


(deftest parent-ancestry-uses-business-order-and-excludes-downstream-coinputs-and-future-producers
  (let [at     "2026-10-21T12:00:00Z"
        before {:operation-id "earlier-producer" :occurred-at at :business-order 10
                :inputs       [{:item-id "ancestor" :name "Earlier input" :quantity 4 :unit "pcs" :location "Raw"}]
                :outputs      [{:item-id "parent" :quantity 10}]}
        future {:operation-id "later-producer" :occurred-at at :business-order 30
                :inputs       [{:item-id "future-input" :name "Future input" :quantity 99}]
                :outputs      [{:item-id "parent" :quantity 10}]}
        later  {:operation-id "downstream" :occurred-at at :business-order 40
                :inputs       [{:item-id "child" :quantity 1} {:item-id "coinput" :name "Downstream co-input" :quantity 50}]
                :outputs      [{:item-id "later-child" :quantity 1}]}
        data   (-> history
                   (assoc-in [:lineage 0 :business-order] 20)
                   (update :lineage into [later future before])
                   (update :timeline-records conj
                           (assoc (record "parent" "Main" "earlier-producer" at 0 10 4) :business-order 10)))
        chart  (timeline/chart data)
        strips (filter #(and (vector? %) (= :button.lineage-parent-strip (first %))) (tree-seq coll? seq chart))
        html   (handler/render-html chart)]
    (is (= ["ancestor" "parent"] (mapv #(get-in % [1 :data-item-id]) strips)))
    (is (= ["earlier-producer" "make"] (mapv #(get-in % [1 :data-operation-id]) strips)))
    (is (= [4 2] (mapv #(get-in % [1 :data-operation-revision]) strips)))
    (is (not (re-find #"Future input|Downstream co-input|later-producer|downstream|99|50 pcs" html)))))


(deftest repeated-transformations-retain-ancestors-without-duplicating-the-selected-item
  (let [first-at  "2026-10-20T12:00:00Z"
        middle-at "2026-10-21T12:00:00Z"
        last-at   "2026-10-22T10:00:00Z"
        data      {:item             {:id "a" :name "A"}
                   :items            {"a"    {:name "A" :unit "pcs"}    "b"    {:name "B" :unit "pcs"}
                                      "root" {:name "Root" :unit "pcs"} "side" {:name "Side" :unit "pcs"}}
                   :snapshot-at      "2026-10-22T12:00:00Z"
                   :timeline-records [(record "a" "Main" "make-a" first-at 0 4 1)
                                      (record "a" "Main" "a-b" middle-at 4 2 2)
                                      (record "a" "Main" "b-a" last-at 2 3 3)
                                      (record "b" "Main" "a-b" middle-at 0 1 2)
                                      (record "b" "Main" "b-a" last-at 1 0 3)
                                      (record "root" "Raw" "root-purchase" "2026-10-19T12:00:00Z" 0 8 1)]
                   :lineage          [{:operation-id "make-a" :occurred-at first-at :business-order 1
                                       :inputs       [{:item-id "root" :quantity 8 :location "Raw"}]
                                       :outputs      [{:item-id "a" :quantity 4 :location "Main"}]}
                                      {:operation-id "a-b" :occurred-at middle-at :business-order 2
                                       :inputs       [{:item-id "a" :quantity 2 :location "Main"} {:item-id "side" :quantity 3 :location "Side"}]
                                       :outputs      [{:item-id "b" :quantity 1 :location "Main"}]}
                                      {:operation-id "b-a" :occurred-at last-at :business-order 3
                                       :inputs       [{:item-id "b" :quantity 1 :location "Main"}]
                                       :outputs      [{:item-id "a" :quantity 1 :location "Main"}]}]}
        chart     (timeline/chart data)
        lanes     (filter #(and (vector? %) (= :div.lineage-lane (first %))) (tree-seq coll? seq chart))
        strips    (filter #(and (vector? %) (= :button.lineage-parent-strip (first %))) (tree-seq coll? seq chart))
        html      (handler/render-html chart)]
    (is (= ["a"] (mapv #(get-in % [1 :data-item-id]) lanes)))
    (is (= ["root" "side" "b"] (mapv #(get-in % [1 :data-item-id]) strips)))
    (is (= ["make-a" "a-b" "b-a"] (mapv #(get-in % [1 :data-operation-id]) strips)))
    (is (= [1 2 3] (mapv #(get-in % [1 :data-operation-revision]) strips)))
    (is (= [[:span.lineage-parent-quantity " × 8 pcs"] [:span.lineage-parent-quantity " × 3 pcs"] [:span.lineage-parent-quantity " × 1 pcs"]]
           (mapv #(nth % 3) strips)))
    (is (= 3 (count (re-seq #"class=\"lineage-marker " html))))
    (is (not (re-find #"root-purchase|2026-10-19" html)))))


(deftest compact-operation-details-retain-all-production-lines-and-relevant-facts
  (let [entry  {:id      "make" :kind      :operation :type     :production :occurred-at "2026-10-21T12:00:00Z"
                :party   "Factory" :amount "12.50"    :currency "EUR"       :reason      "Assembly"
                :inputs  [{:item-id "raw-a" :name "Raw A" :quantity 3 :unit "pcs" :location "Main"}
                          {:item-id "raw-b" :name "Raw B" :quantity 2 :unit "pcs" :location "Shelf"}]
                :outputs [{:item-id "made-a" :name "Made A" :quantity 1 :unit "pcs" :location "Finished"}
                          {:item-id "made-b" :name "Made B" :quantity 4 :unit "pcs" :location "Secondary"}]}
        record {:entity-id   "make"                 :entry entry :revision 2 :actor "uuid" :actor-label "Alex"
                :recorded-at "2026-10-21T13:00:00Z"}
        detail (:detail (first (operations-ui/rows [entry] [record])))
        html   (handler/render-html (timeline/operation-details (assoc detail :on-close "closeDetails()")))]
    (doseq [text ["Production" "Factory" "12.50 EUR" "Alex" "Assembly" "Inputs consumed" "Outputs created"
                  "Raw A" "Raw B" "Made A" "Made B" "Main" "Shelf" "Finished" "Secondary" "3 pcs" "4 pcs"]]
      (is (.contains html text)))
    (is (= 2 (count (re-seq #"class=\"lineage-operation-table\"" html))))
    (is (re-find #"datetime=\"2026-10-21T12:00:00Z\" data-human-date" html))
    (is (re-find #"aria-label=\"Close operation details\"" html))
    (is (not (re-find #"<dl|Recorded at|Revision|uuid|Created by|Edited by|>Amount</th>" html)))))


(deftest compact-details-show-historical-stock-lines-without-arrival-statuses
  (let [entry  {:id       "receive" :kind   :operation :type :purchase :occurred-at "2026-10-21T12:00:00Z"
                :party    "Supplier" :lines [{:item-id "raw" :name "Raw" :quantity 10 :unit "pcs" :amount "25.00"}]
                :receipts [{:occurred-at "2026-10-21T14:00:00Z" :lines [{:item-id "raw" :name "Raw" :quantity 4 :location "Main"}]}
                           {:occurred-at "2026-10-22T09:00:00Z" :lines [{:item-id "raw" :name "Raw" :quantity 6 :location "Shelf"}]}]}
        row    (first (operations-ui/rows [entry] []))
        detail (:detail row)
        html   (handler/render-html (timeline/operation-details detail))]
    (is (= 1 (count (re-seq #"class=\"lineage-operation-table\"" html))))
    (is (= ["Items"] (mapv :heading (:sections detail))))
    (is (= ["4 pcs" "6 pcs"] (mapv second (get-in detail [:sections 0 :rows]))))
    (is (not (re-find #"Arrival|Ordered|Received|>10 pcs</td>" html)))
    (is (= "25.00" (get-in row [:lines 0 :amount])))
    (is (re-find #"4 pcs" html))
    (is (re-find #"6 pcs" html))))


(deftest compact-details-preserve-direct-purchase-line-amounts
  (let [entry  {:id    "purchase" :kind  :operation :type :purchase :occurred-at "2026-10-21T12:00:00Z"
                :party "Supplier" :lines [{:item-id "raw" :name "Raw" :quantity 10 :unit "pcs" :location "Main" :amount "25.00"}]}
        detail (:detail (first (operations-ui/rows [entry] [])))
        html   (handler/render-html (timeline/operation-details detail))]
    (is (= 1 (count (re-seq #"class=\"lineage-operation-table\"" html))))
    (is (re-find #">Amount</th>" html))
    (is (re-find #">25.00</td>" html))
    (is (re-find #"10 pcs" html))))
