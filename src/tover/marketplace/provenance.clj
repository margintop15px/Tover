(ns tover.marketplace.provenance
  "Board chapter 44 · What inventory must know of the marketplace: pending cutover entries and the baselines their
  acceptance establishes, with the pure rules saving and accepting inventory apply to them.

  Depends on no other marketplace namespace, so inventory may require it. Store: both models live in `:catalogue`;
  ids and instants are text, an observation is JSON text. A cutover entry is an ordinary pending entry whose id
  has a `::cutover-entries` row; discarding the entry deletes the row and frees its scope. A baseline's id is
  `<connection>|<item>|<warehouse>`: at most one per scope."
  (:require
    [bel.core :refer [defevent defmodel]]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [tover.catalogues.records :as records :refer [Id]])
  (:import
    [java.time OffsetDateTime]
    [java.time.format DateTimeParseException]
    [java.time.temporal ChronoUnit]))


(def At
  "An instant as ISO-8601 text."
  [:string {:min 20 :max 40}])


(def Ref
  "A connection or account id as text."
  [:string {:min 1 :max 100}])


(def CutoverEntry
  "One proposed scope and the pending entry it produced; `observation` is the frozen evidence as JSON text."
  [:map
   [:entry-id Id]
   [:connection-id Ref]
   [:stock-item-id Id]
   [:warehouse-id Id]
   [:instant At]
   [:observation {:optional true} :string]
   [:cost-source {:optional true} :string]
   [:proposed-by Ref]
   [:proposed-at At]])


(def Baseline
  "Board `Cutover Baseline Established`: operations of the scope after `instant` count; `record-id` is the accepted
  opening or correction."
  [:map
   [:baseline-id Id]
   [:connection-id Ref]
   [:stock-item-id Id]
   [:warehouse-id Id]
   [:instant At]
   [:record-id Id]
   [:established-at At]])


(defevent marketplace-cutover-proposed {:schema CutoverEntry})
(defevent cutover-baseline-established {:schema Baseline})


(defmodel cutover-entries
  {:id     :entry-id :store :catalogue :schema CutoverEntry :policy ::records/workspace-member
   :events {::marketplace-cutover-proposed {:create [:connection-id :stock-item-id :warehouse-id :instant :observation
                                                     :cost-source :proposed-by :proposed-at]}
            :tover.inventory.entries.model/inventory-draft-discarded {:delete true}}})


(defmodel baselines
  {:id     :baseline-id :store :catalogue :schema Baseline :policy ::records/workspace-member
   :events {::cutover-baseline-established {:create [:connection-id :stock-item-id :warehouse-id :instant :record-id :established-at]}}})


(def Facts
  [:map [:cutover-entries [:map-of :string CutoverEntry]] [:baselines [:map-of :string Baseline]]])


(defmethod runtime/coeffect ::facts
  [_ context _]
  {:cutover-entries (into {} (map (juxt :entry-id identity)) (records/all-records context ::cutover-entries :entry-id))
   :baselines       (into {} (map (juxt :baseline-id identity)) (records/all-records context ::baselines :baseline-id))})


(defn baseline-id
  {:malli/schema [:=> [:cat :string :string :string] :string]}
  [connection-id stock-item-id warehouse-id]
  (str connection-id "|" stock-item-id "|" warehouse-id))


(defn baseline
  "The baseline of one connection's scope, or nil."
  {:malli/schema [:=> [:cat [:maybe :map] :string :string :string] [:maybe Baseline]]}
  [facts connection-id stock-item-id warehouse-id]
  (get-in facts [:baselines (baseline-id connection-id stock-item-id warehouse-id)]))


(defn dirty-fx
  "Effects marking each connection's operations for evaluation again; the effect is named, not required."
  {:malli/schema [:=> [:cat [:sequential [:maybe :string]]] [:vector [:tuple :qualified-keyword :map]]]}
  [connection-ids]
  (mapv #(vector :tover.marketplace.sync.model/mark-dirty {:connection-id %}) (sort (set (remove nil? connection-ids)))))


(defn- record-id
  "The record a revision id `record-id#revision` belongs to; a record id may hold `#` itself."
  {:malli/schema [:=> [:cat :string] :string]}
  [revision-id]
  (subs revision-id 0 (or (str/last-index-of revision-id "#") (count revision-id))))


(defn- of-record
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :string]] [:maybe Baseline]]}
  [facts record-id]
  (when record-id (some #(when (= record-id (:record-id %)) %) (vals (:baselines facts)))))


(defn- instant
  "Typed or stored instant text as a whole-second instant; nil when blank or unreadable (saving reports it)."
  {:malli/schema [:=> [:cat :any] [:maybe inst?]]}
  [value]
  (cond (inst? value) value
        (and (string? value) (not (str/blank? value)))
        (try (.truncatedTo (.toInstant (OffsetDateTime/parse (str/trim value))) ChronoUnit/SECONDS)
             (catch DateTimeParseException _ nil))))


(def ^:private fixed "Cutover date is fixed")


(defn- moved-field
  "The first fact of `entry` and its `lines` that differs from the scope and instant of `fixed-row`, or nil."
  {:malli/schema [:=> [:cat :map :any [:sequential :map]] [:maybe :keyword]]}
  [{:keys [stock-item-id warehouse-id] :as fixed-row} occurred-at lines]
  (let [value #(let [v (get %1 %2)] (when-not (and (string? v) (str/blank? v)) v))]
    (cond (not= (instant (:instant fixed-row)) (instant occurred-at)) :occurred-at
          (or (empty? lines) (some #(not= stock-item-id (value % :stock-item-id)) lines)) :stock-item-id
          (some #(not= warehouse-id (value % :warehouse-id)) lines) :warehouse-id)))


(defn save-problem
  "Board `Save Shared Inventory Entry`: a pending cutover entry (`entry-before` has a cutover row) and an amendment of
  a baseline's record keep the scope's instant, item and warehouse; only count and cost change. A baseline is never
  cancelled."
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :map] :map] [:maybe [:map [:field :keyword] [:message :string]]]]}
  [facts entry-before {:keys [amends cancel occurred-at lines]}]
  (let [target (when amends (of-record facts (record-id amends)))
        row    (or (get-in facts [:cutover-entries (:entry-id entry-before)]) target)]
    (cond
      (and target cancel) {:field :cancel :message "A cutover baseline cannot be cancelled."}
      (and row (not cancel)) (when-let [field (moved-field row occurred-at (or lines []))]
                               {:field field :message fixed}))))


(defn acceptance-problems
  "Board `Accept Reviewed Inventory Changes`: reviewed `lines` (each with its `:entry`) of a cutover entry or of an
  amendment of a baseline's record keep its instant, item and warehouse; a scope gets one baseline."
  {:malli/schema [:=> [:cat [:maybe :map] [:sequential :map]] [:vector :map]]}
  [facts lines]
  (vec (distinct
         (for [{:keys [entry-id entry] :as line} lines
               :let [row (get-in facts [:cutover-entries entry-id])
                     target (of-record facts (get-in entry [:target :record-id]))
                     fixed-row (or row target)
                     code (cond (and fixed-row (moved-field fixed-row (:occurred-at entry) [line])) :cutover-date-fixed
                                (and row (baseline facts (:connection-id row) (:stock-item-id row) (:warehouse-id row)))
                                :baseline-exists)]
               :when code]
           {:entry-id entry-id :line-id nil :code code}))))


(defn acceptance-events
  "One `Cutover Baseline Established` per accepted cutover entry of `picked`, naming the record its acceptance
  `records` create, established `now`."
  {:malli/schema [:=> [:cat [:maybe :map] [:sequential :map] [:sequential :map] :any inst?] [:vector :map]]}
  [facts picked records _actor now]
  (vec (for [{:keys [entry-id]} (sort-by :entry-id picked)
             :let               [{:keys [connection-id stock-item-id warehouse-id] :as row} (get-in facts [:cutover-entries entry-id])
                                 record (some #(when (and (= entry-id (:record-id %)) (not (:cancelled %))) %) records)]
             :when              (and row record)]
         {:type ::cutover-baseline-established
          :data {:baseline-id    (baseline-id connection-id stock-item-id warehouse-id) :connection-id connection-id
                 :stock-item-id  stock-item-id :warehouse-id warehouse-id
                 :instant        (:instant row) :record-id (:record-id record)
                 :established-at (str now)}})))
