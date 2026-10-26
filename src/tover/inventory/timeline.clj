(ns tover.inventory.timeline
  (:require
    [bel.expressions :refer [->expr]]
    [clojure.string :as str]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as shared])
  (:import
    [java.time Instant]))


(defn- epoch
  {:malli/schema [:=> [:cat :string] :int]}
  [value]
  (.toEpochMilli (Instant/parse value)))


(defn- human-time
  {:malli/schema [:=> [:cat :string] shared/Hiccup]}
  [value]
  [:time {:datetime value :data-human-date ""} "—"])


(defn- operation-label
  {:malli/schema [:=> [:cat :map] :string]}
  [{:keys [kind type]}]
  (-> (or (when (= :operation kind) type) kind :operation) name (str/replace "-" " ") str/capitalize i18n/t))


(defn- event-order
  {:malli/schema [:=> [:cat :map] [:vector :any]]}
  [event]
  [(epoch (:occurred-at event)) (:business-order event 0) (:event-index event 0) (:phase event 0)])


(defn- event-action
  {:malli/schema [:=> [:cat :string :map] :string]}
  [item-id event]
  (str "const h=el.closest('#history-panel'), requestId=crypto.randomUUID(); h.dataset.requestId=requestId; "
       (->expr (@action :tover.stock.web/open-history-operation
                        {:itemId ~item-id                :operationId ~(:operation-id event) :revision ~(:operation-revision event)
                         :rowId  (js* "h.dataset.rowId") :requestId   (js* "requestId")}
                        {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}))))


(defn- event-attributes
  {:malli/schema [:=> [:cat :string :map :string] :map]}
  [item-id event description]
  {:type                  "button" :data-operation-id                    (:operation-id event)   :data-operation-revision (:operation-revision event)
   :data-on:click         (event-action item-id event) :aria-label       description
   :data-human-date-title (:occurred-at event)         :data-date-prefix (str description " · ")})


(defn- parent-inputs
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [{:keys [item lineage records timeline-records items]}]
  (let [selected-id (:id item)
        produces?   (fn [ids event] (some #(contains? ids (:item-id %)) (:outputs event)))
        origins     (filterv #(produces? #{selected-id} %) lineage)
        ancestors   (loop [pending origins found {}]
                      (if-let [event (peek pending)]
                        (if (contains? found (:operation-id event))
                          (recur (pop pending) found)
                          (let [inputs  (set (map :item-id (:inputs event)))
                                earlier (filter #(and (neg? (compare (event-order %) (event-order event)))
                                                      (produces? inputs %)) lineage)]
                            (recur (into (pop pending) earlier) (assoc found (:operation-id event) event))))
                        (vals found)))
        revisions   (into {} (map (juxt :operation-id :operation-revision)) (concat records timeline-records))]
    (vec (for [event (sort-by (juxt event-order :operation-id) ancestors)
               line  (:inputs event)
               :when (not= selected-id (:item-id line))]
           (assoc line
                  :name (or (get-in items [(:item-id line) :name]) (:name line) (:item-id line))
                  :unit (or (:unit line) (get-in items [(:item-id line) :unit]))
                  :operation-id (:operation-id event) :operation-revision (get revisions (:operation-id event))
                  :occurred-at (:occurred-at event))))))


(defn chart
  "Show the item's own timeline and compact consumed-input links for dated production ancestry."
  {:malli/schema [:=> [:cat :map] shared/Hiccup]}
  [{:keys [item records timeline-records items snapshot-at] :as history}]
  (let [records (filterv #(= (:id item) (:item-id %)) (or records timeline-records))
        parents (parent-inputs history)
        start   (when (seq records) (apply min (map #(epoch (:occurred-at %)) (concat records parents))))
        end     (when start (max (inc (apply max (map #(epoch (:occurred-at %)) records)))
                                 (or (some-> snapshot-at epoch) (inc start))))
        percent (fn [at] (double (* 100 (/ (- at start) (- end start)))))]
    [:div.inventory-timeline {:role "group" :aria-label (i18n/t "Item operation timeline by location")}
     (if (seq records)
       [:div.lineage-scroll
        [:div.lineage-chart {:class (when (seq parents) "has-parents")}
         [:div.lineage-chart-heading
          [:span.lineage-axis-caption (i18n/t "Goods / location")]
          [:div.lineage-axis
           (for [fraction [0 1/4 1/2 3/4 1]
                 :let     [at (+ start (long (* fraction (- end start))))]]
             [:span.lineage-tick {:class (when (= 1 fraction) "lineage-snapshot") :style (str "left:" (* 100 fraction) "%;")}
              (human-time (str (Instant/ofEpochMilli at)))])]]
         [:div.lineage-lanes
          (for [{:keys [name quantity unit location item-id occurred-at] :as input} parents
                :let [quantity-label (str quantity (when (seq unit) (str " " (i18n/unit-label unit))))
                      description (str (i18n/t "Production") " · " name " · " quantity-label (i18n/t " consumed") (when (seq location) (str " · " location)))
                      at (percent (epoch occurred-at))]]
            [:div.lineage-parent-lane {:data-item-id item-id :data-location location}
             [:div.lineage-parent-label location]
             [:div.lineage-parent-plot
              (for [fraction [0 1/4 1/2 3/4 1]]
                [:span.lineage-grid-line {:style (str "left:" (* 100 fraction) "%;")}])
              [:span.lineage-parent-anchor {:aria-hidden true :style (str "left:" at "%;")}]
              [:button.lineage-parent-strip
               (merge (event-attributes (:id item) input description)
                      {:data-item-id item-id :data-location location
                       :style        (str "right:" (- 100 at) "%;")})
               [:span.lineage-parent-name name] [:span.lineage-parent-quantity (str " × " quantity-label)]]]])
          (for [[[item-id location] events] (sort-by (fn [[[id location] _]] [(get-in items [id :name] id) location])
                                                     (group-by (juxt :item-id :location) records))
                :let [events (vec (sort-by event-order events))
                      goods (get items item-id)
                      name (or (:name goods) (:name item) item-id)
                      unit (:unit goods)]]
            [:div.lineage-lane {:data-item-id item-id :data-location location}
             [:div.lineage-lane-label
              [:strong name] [:span location]
              [:small (str (:after (last events)) (when (seq unit) (str " " (i18n/unit-label unit))))]]
             [:div.lineage-plot
              (for [fraction [0 1/4 1/2 3/4 1]]
                [:span.lineage-grid-line {:style (str "left:" (* 100 fraction) "%;")}])
              (map-indexed
                (fn [index event]
                  (let [from        (epoch (:occurred-at event))
                        until       (or (some-> (get events (inc index)) :occurred-at epoch) end)
                        quantity    (:after event)
                        description (str (operation-label event) " · " name " · " location " · " (:before event) " → " quantity
                                         (when (seq unit) (str " " (i18n/unit-label unit))))]
                    (when (> until from)
                      [:button.lineage-bar
                       (merge (event-attributes (:id item) event description)
                              {:class (str (when (= index (dec (count events))) "is-current ") (when (zero? quantity) "is-empty"))
                               :style (str "left:" (percent from) "%;width:" (- (percent until) (percent from)) "%;")})
                       (operation-label event)]))) events)
              (for [event events
                    :let  [description (str (operation-label event) " · " name " · " location " · " (:before event) " → " (:after event)
                                            (when (seq unit) (str " " (i18n/unit-label unit))))]]
                [:button.lineage-marker
                 (merge (event-attributes (:id item) event description)
                        {:class (cond (neg? (:delta event 0)) "is-consumed" (pos? (:delta event 0)) "is-produced" :else "is-corrected")
                         :style (str "left:" (percent (epoch (:occurred-at event))) "%;")})])]])]]]
       [:p.lineage-empty (i18n/t "No inventory history recorded.")])]))


(defn operation-details
  "Render the selected accepted operation without repeating the history audit columns."
  {:malli/schema [:=> [:cat :map] shared/Hiccup]}
  [{:keys [heading metadata sections on-close]}]
  (let [facts        (into {} (map (juxt :key identity)) metadata)
        actor        (or (get facts "Submitted by") (get facts "Edited by") (get facts "Created by"))
        header-facts (keep facts ["Occurred at" "Party" "Direction" "From" "To" "Amount"])]
    [:div.lineage-operation-details
     [:header.lineage-operation-header
      [:h3 {:tabindex -1} heading]
      [:div.lineage-operation-facts
       (for [{:keys [label value date?]} (cond-> (vec header-facts) actor (conj actor))]
         [:span {:title label} (if date? (human-time value) value)])]
      (when on-close
        [:button.sheet-icon-button
         {:type "button" :aria-label (i18n/t "Close operation details") :title (i18n/t "Close operation details") :data-on:click on-close}
         [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width 1.6 :aria-hidden true}
          [:path {:d "M6 6l12 12M18 6L6 18"}]]])]
     (when-let [reason (:value (get facts "Reason"))]
       [:p.lineage-operation-reason reason])
     [:div.lineage-operation-sections
      (for [{:keys [heading date columns rows]} sections
            :let [indices (keep-indexed (fn [index column]
                                          (when (or (not= (i18n/t "Amount") column)
                                                    (some #(not (contains? #{nil "" "—"} (get % index))) rows)) index)) columns)]]
        [:section.lineage-operation-section
         (when (or date (not= (i18n/t "Items") heading))
           [:h4 heading (when date (human-time date))])
         [:div.lineage-operation-table-scroll
          [:table.lineage-operation-table
           [:thead [:tr (for [index indices] [:th (get columns index)])]]
           [:tbody (for [row rows] [:tr (for [index indices] [:td (get row index)])])]]]])]]))
