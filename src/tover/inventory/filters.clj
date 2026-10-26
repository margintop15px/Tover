(ns tover.inventory.filters
  (:require
    [bel.validation :as validation]
    [clojure.string :as str]
    [tover.inventory.schema :as schema]
    [tover.shared.i18n :as i18n])
  (:import
    [java.util UUID]))


(def stock-columns
  [{:key "name" :label "Goods"}
   {:key "quantity" :label "Quantity"}
   {:key "unit" :label "Unit"}
   {:key "location" :label "Location"}
   {:key "article" :label "Article"}
   {:key "barcode" :label "Barcode"}])


(def operation-columns
  [{:key "name" :label "Goods"}
   {:key "occurred-at" :label "Date"}
   {:key "type" :label "Operation"}
   {:key "summary" :label "Summary"}
   {:key "context" :label "Locations / party"}
   {:key "items" :label "Items"}
   {:key "reason" :label "Reason / evidence"}])


(def Condition
  [:map
   [:id [:and :string [:fn #(<= 1 (count %) 128)]]]
   [:type [:enum "empty" "notEmpty" "eq" "notEq" "begins" "contains" "notContains"]]
   [:value {:optional true} [:and :string [:fn #(<= (count %) 2000)]]]
   [:relation {:optional true} [:enum "and" "or"]]])


(def Filters
  [:map-of :string [:vector {:max 32} Condition]])


(defn columns
  {:malli/schema [:=> [:cat [:or :keyword :string]] [:vector :map]]}
  [view]
  (case (keyword view)
    :stock (i18n/translate-labels stock-columns)
    :operations (i18n/translate-labels operation-columns)
    (schema/fail! :validation "Choose Stock or Operations filters." {:field :view})))


(defn check!
  "Validate a filter request and normalize native popup IDs and JSON map keys."
  {:malli/schema [:=> [:cat [:or :keyword :string] :any] Filters]}
  [view filters]
  (when-not (or (nil? filters) (map? filters))
    (schema/fail! :validation "Filters must be grouped by column." {:field :filters}))
  (let [allowed (set (map :key (columns view)))
        result  (into (sorted-map)
                      (for [[column conditions] (or filters {})
                            :let                [column (if (keyword? column) (name column) column)]]
                        (do
                          (when-not (contains? allowed column)
                            (schema/fail! :validation "This column cannot be filtered in this view." {:field :filters :column column}))
                          (when-not (vector? conditions)
                            (schema/fail! :validation "Column filters must be an ordered list." {:field :filters :column column}))
                          [column (mapv (fn [condition]
                                          (when-not (map? condition)
                                            (schema/fail! :validation "Each filter needs an operator and a value." {:field :filters :column column}))
                                          (let [condition (into {} (map (fn [[key value]] [(if (string? key) (keyword key) key) value]) condition))]
                                            (cond-> (select-keys condition [:id :type :value :relation])
                                              (number? (:id condition)) (update :id str)))) conditions)])))
        result  (into (sorted-map) (remove (comp empty? val)) result)]
    (validation/check! Filters result)
    (doseq [[column conditions] result]
      (when-not (= (count conditions) (count (distinct (map :id conditions))))
        (schema/fail! :validation "Filter condition IDs must be distinct within a column." {:field :filters :column column})))
    result))


(defn complex?
  {:malli/schema [:=> [:cat :map [:maybe :string]] :boolean]}
  [filters column]
  (let [conditions (get filters (or (not-empty column) "name"))]
    (boolean (and (seq conditions)
                  (not (and (= 1 (count conditions)) (= "begins" (:type (first conditions)))))))))


(defn smart-add
  {:malli/schema [:=> [:cat [:or :keyword :string] :map [:maybe :string] :string :boolean] Filters]}
  [view filters column value replace?]
  (let [filters (check! view filters)
        column  (or (not-empty column) "name")]
    (when (and (complex? filters column) (not replace?))
      (schema/fail! :validation "Replace this column's existing filter before using begins with."
                    {:kind :filter-replacement :column column :current (get filters column)}))
    (check! view (assoc filters column [{:id       (or (when-not (complex? filters column) (get-in filters [column 0 :id])) (str (UUID/randomUUID)))
                                         :type     "begins"
                                         :value    value
                                         :relation "and"}]))))


(defn- text-value
  {:malli/schema [:=> [:cat :any] :string]}
  [value]
  (str/lower-case (if (keyword? value) (name value) (str value))))


(defn- values-at
  {:malli/schema [:=> [:cat [:or :keyword :string] :map :string :map] [:vector :any]]}
  [view row column items]
  (cond
    (and (= :operations (keyword view)) (= "name" column))
    (let [lines (mapcat #(get row % []) [:lines :inputs :outputs])]
      (mapv #(or (:name %) (get-in items [(:item-id %) :name]) "") lines))

    (and (= :operations (keyword view)) (= "type" column)) [(or (:operation row) (:type row))]

    :else [(get row (keyword column))]))


(defn- condition-matches?
  {:malli/schema [:=> [:cat [:vector :any] Condition] :boolean]}
  [values {:keys [type value]}]
  (let [values (mapv text-value values)
        needle (text-value value)
        empty? (every? #(= "" %) values)]
    (case type
      "empty" empty?
      "notEmpty" (not empty?)
      "eq" (boolean (some #(= % needle) values))
      "notEq" (not-any? #(= % needle) values)
      "begins" (boolean (some #(str/starts-with? % needle) values))
      "contains" (boolean (some #(str/includes? % needle) values))
      "notContains" (not-any? #(str/includes? % needle) values))))


(defn- column-matches?
  {:malli/schema [:=> [:cat [:vector :any] [:vector Condition]] :boolean]}
  [values conditions]
  (loop [[condition & remaining] conditions
         group                   true]
    (if-not condition
      (boolean group)
      (let [group (and group (condition-matches? values condition))]
        (if (and (seq remaining) (= "or" (:relation condition)))
          (if group true (recur remaining true))
          (if (seq remaining) (recur remaining group) (boolean group)))))))


(defn matches?
  "Evaluate AND groups joined by OR within each column; all columns must match."
  {:malli/schema [:=> [:cat [:or :keyword :string] :map Filters :map] :boolean]}
  [view row filters items]
  (every? (fn [[column conditions]] (column-matches? (values-at view row column items) conditions)) filters))
