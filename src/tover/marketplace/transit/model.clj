(ns tover.marketplace.transit.model
  "Board chapter 46 slices 1 and 2 · Read shipments in transit, and one shipment's status history. A view built
  from source operations, their revisions and their acceptances: it never holds stock, stores nothing and emits no
  event. Dispatched units left the warehouse with the accepted Sale; delivery closes transit without another
  deduction; a physical return restores stock once, through its own accepted Customer return.

  One row per line of each posting and return after its cutover; an operation set aside, before its cutover or of
  no effect is left out, and so is a line without a confirmed mapping and baseline. A return's quantities leave its
  posting's lines of the same stock item, so no unit is shown twice. A posting whose Sale is not accepted (or was
  cancelled in Tover) is not `counted`: its units are still in warehouse stock. Without an accepted Sale it is
  `awaiting facts` while facts are missing, else in the state its status names, with its next step. A return counts
  once its posting's Sale is accepted, or when its posting is history or was never read; it is `returned` only with
  an accepted Customer return. A travelling or returning shipment whose domain has not been read for 30 days is
  `unknown`, as is one with a status Tover does not recognise."
  (:require
    [bel.core :refer [defreadmodel]]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [tover.catalogues.records :as records]
    [tover.marketplace.connections.model :as connections]
    [tover.marketplace.operations.model :as operations]
    [tover.marketplace.operations.proposals :as proposals]
    [tover.marketplace.operations.steps :as steps]
    [tover.marketplace.sync.model :as sync]
    [tover.marketplace.sync.observations :as observations])
  (:import
    [java.time Duration Instant]))


(def Ref connections/Ref)
(def At connections/At)


(def states
  ["travelling" "delivered" "returning" "returned" "unknown" "awaiting facts"])


(def State
  (into [:enum] states))


(def Shipment
  "Board `Shipments in Transit`: one line of a posting or return. `counted` when its quantity is part of the totals;
  `actual-time` the operation's actual time (dispatch, or a return's receipt), apart from `observed-at`, when Tover
  observed its current status; `missing` the facts an `awaiting facts` Sale lacks; `next-step` that of an operation
  not accepted yet."
  [:map
   [:source-operation-id :string] [:connection-id Ref] [:kind :string] [:line-key :string] [:stock-item-id :string]
   [:quantity {:optional true} :int] [:state State] [:counted :boolean]
   [:actual-time {:optional true} :string] [:observed-at :string]
   [:missing {:optional true} [:vector :string]] [:next-step {:optional true} steps/NextStep]])


(def Facts
  "`steps/Facts` of one connection (`operations/load-facts`), with `connection-id` and `covered-until`, the instant
  each domain was last read up to (`::sync/covered-until`)."
  [:map [:connection-id Ref] [:covered-until {:optional true} [:map-of :string :any]]])


;; Statuses


(def ^:private travelling-statuses
  "Posting statuses from processing to the buyer: the units are on their way once the Sale is accepted."
  #{"awaiting_registration" "acknowledged" "awaiting_approve" "awaiting_packaging" "awaiting_deliver" "delivering"
    "driver_pickup" "sent_by_seller" "arbitration" "client_arbitration"})


(def ^:private returning-postings
  "Posting statuses after which the units come back: not accepted by the buyer, or cancelled after dispatch."
  #{"not_accepted" "cancelled"})


(def ^:private return-statuses
  "Return statuses Tover recognises, from initiation to receipt (Ozon's visual statuses and rFBS states)."
  #{"disputeopened" "onsellerapproval" "arrivedatreturnplace" "onsellerclarification"
    "onsellerclarificationafterpartialcompensation" "offeredpartialcompensation" "returnmoneyapproved"
    "partialcompensationreturned" "movingtoseller" "returnedtoozon" "returned" "receivedbyseller" "moneyreturned"
    "approved" "received"})


(def ^:private domains
  {"fbs-posting" "fbs-postings" "fbo-posting" "fbo-postings" "return" "returns" "rfbs-return" "rfbs-returns"})


(def stale-after
  "A travelling or returning shipment whose domain was not read for this long is `unknown`."
  (Duration/ofDays 30))


(defn- return?
  {:malli/schema [:=> [:cat [:maybe :string]] :boolean]}
  [kind]
  (contains? #{"return" "rfbs-return"} kind))


(defn status-state
  "The state an operation's status names: a posting `travelling`, `delivered` or `returning`, a return
  `returning`; `unknown` when Tover does not recognise the status."
  {:malli/schema [:=> [:cat :string [:maybe :string]] State]}
  [kind status]
  (let [s (str/lower-case (or status ""))]
    (cond
      (return? kind) (if (contains? return-statuses s) "returning" "unknown")
      (contains? travelling-statuses s) "travelling"
      (= "delivered" s) "delivered"
      (contains? returning-postings s) "returning"
      :else "unknown")))


;; Instants


(defn- instant
  {:malli/schema [:=> [:cat :any] [:maybe inst?]]}
  [value]
  (cond (inst? value) value
        (string? value) (try (Instant/parse value) (catch Exception _ nil))
        :else nil))


(defn- latest
  {:malli/schema [:=> [:cat [:sequential :any]] [:maybe inst?]]}
  [values]
  (reduce (fn [a b] (if (or (nil? a) (and b (.isAfter ^Instant b ^Instant a))) b a)) nil (keep instant values)))


(defn- stale?
  "Whether the operation's domain was last read, up to its position or by the operation's own observation, at
  least `stale-after` before `now`."
  {:malli/schema [:=> [:cat :map :map inst?] :boolean]}
  [{:keys [covered-until]} operation now]
  (let [read (latest [(get covered-until (domains (:kind operation))) (:last-observed-at operation)])]
    (boolean (and read (not (.isBefore ^Instant now ^Instant (.plus ^Instant read ^Duration stale-after)))))))


;; Records


(defn- record-index
  "Accepted history by record id: whether its latest revision is cancelled, its last facts' time and lines by
  position."
  {:malli/schema [:=> [:cat [:maybe :map]] [:map-of :string :map]]}
  [{:keys [records lines]}]
  (let [by-revision (group-by :record-revision-id lines)]
    (into {} (for [[record-id revisions] (group-by :record-id records)
                   :let                  [ordered (sort-by :revision revisions)
                                          facts (or (last (remove :cancelled ordered)) (last ordered))]]
               [record-id {:cancelled   (boolean (:cancelled (last ordered)))
                           :occurred-at (:occurred-at facts)
                           :lines       (into {} (map (juxt :position identity)) (by-revision (:record-revision-id facts)))}]))))


(defn- live-record
  "The operation's accepted record unless it was cancelled in Tover."
  {:malli/schema [:=> [:cat :map :map :string] [:maybe :map]]}
  [{:keys [acceptances]} records id]
  (when-let [record (some->> (get-in acceptances [id :record-id]) records)]
    (when-not (:cancelled record) record)))


(defn- sale-decided?
  "Whether a return's units left the warehouse with a Sale Tover holds: its posting's Sale is accepted and not
  cancelled, its posting is history, or Tover never read its posting."
  {:malli/schema [:=> [:cat :map :map [:maybe :string]] :boolean]}
  [facts records related]
  (let [posting (get-in facts [:operations related])]
    (boolean
      (or (nil? posting)
          (live-record facts records related)
          (= "history" (steps/finished facts posting (proposals/resolve-lines facts posting)))))))


;; Shipments


(defn- operation-rows
  "The rows of one posting or return, before its returns are taken from it; nil when it is left out."
  {:malli/schema [:=> [:cat :map :map inst? :map] [:maybe [:vector :map]]]}
  [facts records now {:keys [source-operation-id kind related-operation] :as operation}]
  (let [accepted (contains? (:acceptances facts) source-operation-id)
        lines    (proposals/resolve-lines facts operation)]
    (when (or accepted (nil? (steps/finished facts operation lines)))
      (let [record (live-record facts records source-operation-id)
            {:keys [missing next-step]} (when-not accepted (steps/classify facts operation))
            by-status                   (let [s (status-state kind (:status operation))]
                                          (if (and (#{"travelling" "returning"} s) (stale? facts operation now)) "unknown" s))
            state (cond (return? kind) (if record "returned" by-status)
                        (and (not accepted) (seq missing)) "awaiting facts"
                        :else by-status)
            counted (if (return? kind) (sale-decided? facts records related-operation) (some? record))
            ;; Even when cancelled since: what a member entered at acceptance fills what the source does not state.
            accepted-r                  (some->> (get-in facts [:acceptances source-operation-id :record-id]) records)
            actual (or (:occurred-at operation) (:occurred-at accepted-r))]
        (vec (for [[position {:keys [line-key stock-item-id after-baseline] :as line}] (map-indexed vector lines)
                   :when (and stock-item-id after-baseline)
                   :let [quantity (or (:quantity line) (some-> (get-in accepted-r [:lines position :quantity-change]) abs))]]
               (cond-> {:source-operation-id source-operation-id            :connection-id (:connection-id operation) :kind kind
                        :line-key            line-key :stock-item-id stock-item-id :state state :counted counted
                        :observed-at         (str (:observed-at operation))}
                 quantity (assoc :quantity quantity)
                 actual (assoc :actual-time (str actual))
                 (seq missing) (assoc :missing (vec missing))
                 next-step (assoc :next-step next-step)
                 (return? kind) (assoc ::related related-operation))))))))


(defn- returned-quantities
  "What returns take from their postings: {[posting stock-item] quantity}."
  {:malli/schema [:=> [:cat [:sequential :map]] [:map-of [:tuple :string :string] :int]]}
  [rows]
  (reduce (fn [acc {::keys [related] :keys [stock-item-id quantity]}]
            (cond-> acc (and related quantity) (update [related stock-item-id] (fnil + 0) quantity)))
          {} rows))


(defn- without-returned
  "The posting rows with their returns' quantities taken from their lines of the same stock item, in line order; a
  line left with nothing is dropped."
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector :map]]}
  [rows]
  (first
    (reduce (fn [[kept left] {::keys [related] :keys [source-operation-id stock-item-id quantity] :as row}]
              (let [k     [source-operation-id stock-item-id]
                    taken (if (or related (nil? quantity)) 0 (min quantity (get left k 0)))
                    row   (dissoc (cond-> row (pos? taken) (update :quantity - taken)) ::related)]
                [(cond-> kept (or (nil? quantity) (pos? (or (:quantity row) 0))) (conj row))
                 (cond-> left (pos? taken) (update k - taken))]))
            [[] (returned-quantities rows)]
            rows)))


(defn shipments
  "Board `Shipments in Transit` of one connection at `now`: every line of its postings and returns after their
  cutover, ordered by operation and line."
  {:malli/schema [:=> [:cat Facts inst?] [:vector Shipment]]}
  [facts now]
  (let [records (record-index (:history facts))]
    (without-returned
      (into [] (comp (filter (comp domains :kind)) (mapcat #(operation-rows facts records now %)))
            (map val (sort-by key (:operations facts)))))))


(defn blocking-items
  "Stock items whose counted units are travelling or returning, with those operations: they block archiving.
  `unknown` blocks nothing."
  {:malli/schema [:=> [:cat [:sequential :map]] [:map-of :string [:vector :string]]]}
  [shipments]
  (update-vals (group-by :stock-item-id (filter #(and (:counted %) (#{"travelling" "returning"} (:state %))) shipments))
               #(vec (sort (distinct (map :source-operation-id %))))))


(defn totals
  "Counted quantities by state."
  {:malli/schema [:=> [:cat [:sequential :map]] [:map-of State :int]]}
  [shipments]
  (reduce (fn [acc {:keys [state quantity counted]}] (cond-> acc (and counted quantity) (update state (fnil + 0) quantity)))
          {} shipments))


;; Facts


(defn load-facts
  "`Facts` of one connection: plan 4's operation facts and how far each domain was read."
  {:malli/schema [:=> [:cat :map :string] Facts]}
  [context connection-id]
  (assoc (operations/load-facts context connection-id)
         :connection-id connection-id
         :covered-until (runtime/coeffect ::sync/covered-until context {:connection-id connection-id})))


(defmethod runtime/coeffect ::facts
  ;; The facts of each named connection, else of every connection of the workspace.
  [_ context {:keys [connection-id]}]
  (mapv #(load-facts context %)
        (if connection-id
          [connection-id]
          (sort (map :connection-id (records/all-records context ::connections/connections :connection-id))))))


(defn- within?
  "Whether the shipment's actual time, else its observation time, is within `from` and `to` (both included)."
  {:malli/schema [:=> [:cat :map [:maybe :string] [:maybe :string]] :boolean]}
  [{:keys [actual-time observed-at]} from to]
  (let [at (instant (or actual-time observed-at))]
    (boolean (and at
                  (or (nil? from) (not (.isBefore ^Instant at ^Instant (instant from))))
                  (or (nil? to) (not (.isAfter ^Instant at ^Instant (instant to))))))))


(def ShipmentsInTransit
  [:map [:shipments [:vector Shipment]] [:totals [:map-of State :int]]])


(defreadmodel shipments-in-transit
  {:input  [:map [:connection-id {:optional true} Ref] [:from {:optional true} At] [:to {:optional true} At]]
   :output ShipmentsInTransit
   :policy ::records/workspace-member
   :cofx   [[:facts ::facts (fn [_ input] (select-keys input [:connection-id]))]
            [:clock :tover.inventory.acceptance.model/now (constantly {})]]}
  [{:keys [facts clock]} {:keys [from to]}]
  (let [found (into [] (comp (mapcat #(shipments % clock)) (filter #(within? % from to))) facts)]
    {:shipments found :totals (totals found)}))


;; Board 46 slice 2 · Read shipment status history


(defn- related-ids
  "The operations of one shipment: a posting and its returns, or a return and its posting."
  {:malli/schema [:=> [:cat :map :map] [:vector :string]]}
  [{:keys [operations]} {:keys [source-operation-id related-operation]}]
  (vec (distinct (concat [source-operation-id]
                         (when related-operation [related-operation])
                         (for [[id o] (sort-by key operations) :when (= source-operation-id (:related-operation o))] id)))))


(def ShipmentStatusHistory
  "Board `Shipment Status History`: the shipment's lines, every status with its actual time and Tover's observation
  time, and the accepted Sale and Customer return records it produced. Never buyer data."
  [:map
   [:source-operation-id :string] [:connection-id Ref] [:kind :string]
   [:lines [:vector [:map [:line-key :string] [:stock-item-id :string] [:quantity {:optional true} :int] [:state State]
                     [:counted :boolean]]]]
   [:statuses [:vector [:map [:revision :int] [:status {:optional true} :string] [:actual-time {:optional true} :string]
                        [:observed-at :string]]]]
   [:records [:vector [:map [:record-id :string] [:kind [:enum "sale" "customer-return"]] [:source-operation-id :string]
                       [:cancelled :boolean]]]]])


(defn status-history
  "`Shipment Status History` of `operation` from its connection's facts and its source revisions by id."
  {:malli/schema [:=> [:cat Facts :map [:map-of :string :map] inst?] ShipmentStatusHistory]}
  [{:keys [acceptances operations history] :as facts} {:keys [source-operation-id kind revision] :as operation} revisions now]
  (let [records (record-index history)]
    {:source-operation-id source-operation-id
     :connection-id       (:connection-id operation)
     :kind                kind
     :lines               (into [] (comp (filter #(= source-operation-id (:source-operation-id %)))
                                         (map #(select-keys % [:line-key :stock-item-id :quantity :state :counted])))
                                (shipments facts now))
     :statuses            (vec (for [r     (range 1 (inc (or revision 0)))
                                     :let  [row (get revisions (str source-operation-id "#" r))]
                                     :when row
                                     :let  [{:keys [status occurred-at]} (observations/read-json (:facts row))]]
                                 (cond-> {:revision r :observed-at (str (:observed-at row))}
                                   (string? status) (assoc :status status)
                                   (string? occurred-at) (assoc :actual-time occurred-at))))
     :records             (vec (for [id    (related-ids facts operation)
                                     :let  [{:keys [record-id]} (get acceptances id)]
                                     :when record-id]
                                 {:record-id           record-id
                                  :kind                (if (return? (get-in operations [id :kind])) "customer-return" "sale")
                                  :source-operation-id id
                                  :cancelled           (boolean (get-in records [record-id :cancelled]))}))}))


(defmethod runtime/coeffect ::history-facts
  ;; The operation, its connection's facts and its source revisions, each loaded by id; nil for an unknown one.
  [_ context {:keys [source-operation-id]}]
  (when-let [operation (get (sync/load-rows context ::sync/source-operations [source-operation-id]) source-operation-id)]
    (when (domains (:kind operation))
      {:operation operation
       :facts     (load-facts context (:connection-id operation))
       :revisions (sync/load-rows context ::sync/source-revisions
                                  (mapv #(str source-operation-id "#" %) (range 1 (inc (:revision operation)))))})))


(defreadmodel shipment-status-history
  {:input  [:map [:source-operation-id :string]]
   :output [:maybe ShipmentStatusHistory]
   :policy ::records/workspace-member
   :cofx   [[:found ::history-facts (fn [_ input] (select-keys input [:source-operation-id]))]
            [:clock :tover.inventory.acceptance.model/now (constantly {})]]}
  [{:keys [found clock]} _]
  (when-let [{:keys [operation facts revisions]} found]
    (status-history facts operation revisions clock)))
