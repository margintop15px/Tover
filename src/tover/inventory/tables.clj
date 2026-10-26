(ns tover.inventory.tables
  (:require
    [clojure.string :as str]
    [tover.inventory.grid :as grid]
    [tover.operations.ui :as operations-ui]
    [tover.shared.i18n :as i18n]))


(def stock-columns
  [{:key "name" :label "Goods" :width 310 :sortKey "name" :filterKey "name"}
   {:key "quantity" :label "Quantity" :width 110 :sortKey "quantity" :filterKey "quantity"}
   {:key "unit" :label "Unit" :width 90 :sortKey "unit" :filterKey "unit"}
   {:key "location" :label "Location" :width 190 :sortKey "location" :filterKey "location"}
   {:key "article" :label "Article" :width 150 :sortKey "article" :filterKey "article"}
   {:key "barcode" :label "Barcode" :width 150 :sortKey "barcode" :filterKey "barcode"}])


(defn- value
  {:malli/schema [:=> [:cat :map :string] :any]}
  [row key]
  (or (get row (keyword key)) ""))


(defn stock
  "Accepted stock with durable draft overlays and stable server edit addresses."
  {:malli/schema [:=> [:cat [:sequential :map]] :map]}
  [rows]
  {:id   "stock" :columns (i18n/translate-labels stock-columns) :merges [] :editable true :rowHeaders true :showPrevious false
   :rows (mapv (fn [row]
                 {:id    (:id row) :groupId (:id row) :className (:_changeClass row)
                  :cells (mapv (fn [{:keys [key]}]
                                 (cond-> {:value (value row key)}
                                   true
                                   (assoc :editor {:type (if (= "quantity" key) "quantity" "text")}
                                          :target (cond-> {:type     "stock-edit" :id (:id row) :path           key
                                                           :revision (or (:draft-revision row) (:ledger-revision row))
                                                           :rowId    (:id row) :field key       :ledgerRevision (:ledger-revision row)}
                                                    (:draft-id row) (assoc :draftId (:draft-id row))
                                                    (:draft-revision row) (assoc :draftRevision (:draft-revision row))
                                                    (:draft-kind row) (assoc :draftKind (:draft-kind row))))
                                   (and (= key "unit") (not= (:unit row) (i18n/unit-label (:unit row))))
                                   (assoc :display (i18n/unit-label (:unit row)))
                                   (some #{key} (:_changedFields row))
                                   (assoc :before (or (get-in row [:_accepted (keyword key)]) ""))
                                   (and (= key "name") (not= "opening" (:draft-kind row)))
                                   (assoc :action {:type  "item-history" :itemId (:item-id row) :rowId (:id row)
                                                   :label (i18n/t "History for {name}" {:name (:name row)}) :icon "›"}))) stock-columns)}) rows)})


(def operation-column-queries
  {"type"          {:sortKey "type" :filterKey "type"}
   "occurred-at"   {:sortKey "occurred-at" :filterKey "occurred-at"}
   "party"         {:sortKey "party" :filterKey "context"}
   "name"          {:filterKey "name"}
   "location"      {:filterKey "context"}
   "from-location" {:filterKey "context"}
   "to-location"   {:filterKey "context"}
   "reason"        {:sortKey "reason" :filterKey "reason"}})


(defn- readonly-cell
  {:malli/schema [:=> [:cat :map] :map]}
  [cell]
  (cond-> (dissoc cell :target :editor :selection :action :actions :itemTarget :itemValue)
    (= "date" (get-in cell [:editor :type])) (assoc :date true)))


(defn operations
  "Accepted operations retain their own merged line groups, without draft controls."
  {:malli/schema [:=> [:cat [:sequential :map] [:? :map]] :map]}
  [operation-rows & [{:keys [column-keys operation-catalog]}]]
  (let [operation-rows (mapv (fn [entry]
                               (if (and (= :purchase (:type entry)) (contains? entry :receipts))
                                 (assoc (dissoc entry :receipts) :lines (vec (mapcat :lines (:receipts entry))))
                                 entry)) operation-rows)
        by-id          (into {} (map (juxt :id identity)) operation-rows)
        model          (grid/drafts {:drafts     (mapv (fn [entry] {:id (:id entry) :revision (or (:revision entry) 1) :entry entry}) operation-rows)
                                     :operations (vec operation-rows) :operation-catalog operation-catalog :items {} :accepted? true
                                     :columns    (when column-keys
                                                   (filterv #(contains? (set column-keys) (:key %)) grid/columns))})
        keep-columns   (keep-indexed (fn [index column] (when-not (#{"select" "actions" "line-actions"} (:key column)) index)) (:columns model))
        coordinates    (zipmap keep-columns (range))
        columns        (mapv (fn [index] (let [column (nth (:columns model) index)] (merge column (get operation-column-queries (:key column))))) keep-columns)]
    {:id      "operations" :columns columns :editable false :rowHeaders true
     :details (into {} (map (juxt :id :detail)) operation-rows)
     :rows    (mapv (fn [row]
                      (let [entry (get by-id (:groupId row))
                            cells (mapv (fn [index]
                                          (let [cell (readonly-cell (nth (:cells row) index))
                                                key  (:key (nth (:columns model) index))]
                                            (cond-> cell
                                              (and (= "type" key) (seq cell))
                                              (assoc :value (operations-ui/operation-title entry) :display nil
                                                     :action {:type  "operation-details"               :id   (:id entry)
                                                              :label (i18n/t "Show operation details") :icon "›"})))) keep-columns)]
                        (assoc row :className (when (:cancelled? entry) "inventory-cancelled-row") :cells cells))) (:rows model))
     :merges  (vec (keep (fn [{:keys [col colspan] :as merge}]
                           (when-let [new-col (get coordinates col)]
                             (let [remaining (count (filter #(contains? coordinates %) (range col (+ col colspan))))]
                               (when (pos? remaining) (assoc merge :col new-col :colspan remaining))))) (:merges model)))}))


(def history-columns
  [{:key "occurred-at" :label "Occurred at" :width 175}
   {:key "recorded-at" :label "Recorded at" :width 175}
   {:key "record" :label "Operation type" :width 210}
   {:key "status" :label "Status" :width 145}
   {:key "actor" :label "Actor" :width 190}
   {:key "reason" :label "Reason" :width 230}
   {:key "quantity" :label "Quantity / location" :width 230}])


(defn history
  "One row per immutable revision; only effective own-item records contribute effects."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [records audit-records]}]
  (let [effects (group-by (juxt :operation-id :operation-revision) records)]
    {:id   "item-history" :columns (i18n/translate-labels history-columns) :merges [] :editable false :rowHeaders false
     :rows (mapv (fn [{:keys [entity-id revision status entry occurred-at recorded-at reason actor-label touches-item?]}]
                   (let [own-effects (if (= :effective status) (get effects [entity-id revision] []) [])
                         type        (if (= :operation (:kind entry)) (:type entry) (:kind entry))]
                     {:id    (str entity-id "/" revision) :groupId entity-id
                      :cells [{:value (or occurred-at (:occurred-at entry) "") :date true}
                              {:value (or recorded-at "") :date true}
                              {:value (grid/label type)}
                              {:value (str (grid/label status) (when (false? touches-item?) (i18n/t " · Revised to other items")))}
                              {:value (if (str/blank? actor-label) (i18n/t "Unavailable user") actor-label)}
                              {:value (or reason "")}
                              (cond-> {:value (if (seq own-effects)
                                                (str/join "; " (map #(str (:before %) " → " (:after %) " · " (:location %)) own-effects))
                                                "—")}
                                (seq own-effects)
                                (assoc :lines (vec (mapcat (fn [effect]
                                                             (cond-> [{:value (str (:before effect) " → " (:after effect) " · " (:location effect))}]
                                                               (and (:occurred-at effect)
                                                                    (not= (:occurred-at effect) (or occurred-at (:occurred-at entry))))
                                                               (conj {:value (:occurred-at effect) :date true}))) own-effects))))]})) audit-records)}))


(defn resulting-stock
  "Project changed stock fields only; missing balances remain visible as zero."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [rows before-rows]}]
  (let [cell-value (fn [row key] (if (= "quantity" key) (or (get row :quantity) 0) (value row key)))
        before     (into {} (map (juxt :id identity)) before-rows)
        after-ids  (set (map :id rows))
        after      (into (vec rows) (for [row before-rows :when (not (after-ids (:id row)))] (assoc row :quantity 0)))
        changed?   (fn [row] (some #(not= (cell-value row (:key %)) (cell-value (get before (:id row) {}) (:key %))) stock-columns))]
    {:id     "resulting-stock" :columns (mapv #(dissoc % :sortKey :filterKey) (i18n/translate-labels stock-columns))
     :merges [] :editable               false :rowHeaders false
     :rows   (mapv (fn [row]
                     (let [original (get before (:id row) {})]
                       {:id    (:id row) :groupId (:id row) :className nil
                        :cells (mapv (fn [{:keys [key]}]
                                       (let [old (cell-value original key) new (cell-value row key)]
                                         (cond-> {:value new}
                                           (and (= key "unit") (not= (:unit row) (i18n/unit-label (:unit row))))
                                           (assoc :display (i18n/unit-label (:unit row)))
                                           (not= old new) (assoc :before old)))) stock-columns)}))
                   (filter changed? after))}))
