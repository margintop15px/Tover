(ns tover.marketplace.moves.model
  "Board chapter 45 slice b · Read stock differences. Ozon reports no moves between its own warehouses, so they are
  found by comparing a finished `stock-by-warehouse` snapshot with Tover: plain arithmetic, no inference.

  Per stock item and FBO warehouse with a baseline, difference = Ozon's `present` − Tover's balance at the
  snapshot's observation time. Seller warehouses are not compared. An item is compared only when every FBO
  warehouse holding it (by Ozon's figures or Tover's balance) is matched and has a baseline, and the snapshot is
  not before those baselines. An item with an undecided operation after its cutover is `waiting for operations`;
  `history`, `set aside` and `no effect` count as decided. So does an accepted operation whose source changed
  until its correction is decided (pending, unproposable, or still to be proposed, it waits). The last agreeing snapshot is the latest earlier finished
  one without any difference for the item. A move needs one, and differences summing to exactly 0; anything else
  is only shown. Nothing here proposes or accepts.

  Accepted history is replayed once per read, over the records of the connection's mapped items only, and gives
  the balances at every retained snapshot (`balances-at`)."
  (:require
    [bel.core :refer [defreadmodel]]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [tover.catalogues.records :as records]
    [tover.inventory.acceptance.model :as acceptance]
    [tover.inventory.valuation :as valuation]
    [tover.marketplace.connections.model :as connections]
    [tover.marketplace.operations.corrections :as corrections]
    [tover.marketplace.operations.model :as operations]
    [tover.marketplace.operations.proposals :as proposals]
    [tover.marketplace.operations.steps :as steps]
    [tover.marketplace.provenance :as provenance]
    [tover.marketplace.sync.model :as sync]
    [tover.marketplace.warehouses.model :as warehouses])
  (:import
    [java.time Instant OffsetDateTime]
    [java.time.temporal ChronoUnit]))


(def Ref connections/Ref)
(def At connections/At)


(def State
  [:enum "equal" "moved" "unexplained" "waiting for operations"])


(def Difference
  [:map [:stock-item-id Ref] [:warehouse-id Ref] [:ozon :int] [:tover :int] [:difference :int]])


(def Move
  [:map [:from Ref] [:to Ref] [:quantity [:int {:min 1}]]])


(def Snapshot
  [:map [:run-id Ref] [:observed-at At]])


(def Facts
  "What the comparison reads of one connection. `passes` are the finished stock-by-warehouse passes, newest first
  (`sync/finished-passes`); `figures` the warehouse stock figures they kept; `mappings` and `warehouse-mappings`
  the connection's product and warehouse mappings; `baselines` by id; `history` the accepted records and lines;
  `undecided` the stock items with an undecided operation after their cutover; `balances`, optional, Tover's
  balances by run id ({run-id {[item warehouse] quantity}}), else read from `history` at each snapshot;
  `figure-index`, optional, the figures by [run-id listing] (`indexed`)."
  [:map
   [:connection-id Ref]
   [:passes [:sequential :map]]
   [:figures [:sequential :map]]
   [:mappings [:map-of :string :map]]
   [:warehouse-mappings [:map-of :string :map]]
   [:baselines [:maybe [:map-of :string :map]]]
   [:history {:optional true} [:maybe [:map [:records [:sequential :map]] [:lines [:sequential :map]]]]]
   [:undecided {:optional true} [:set :string]]
   [:balances {:optional true} [:map-of :string :map]]
   [:figure-index {:optional true} :map]])


;; Instants


(defn- instant
  "Instant or ISO text as a whole-second instant, as the cutover reads it; nil when blank or unreadable."
  {:malli/schema [:=> [:cat :any] [:maybe inst?]]}
  [value]
  (cond (inst? value) (.truncatedTo ^Instant value ChronoUnit/SECONDS)
        (and (string? value) (not (str/blank? value)))
        (try (.truncatedTo (.toInstant (OffsetDateTime/parse (str/trim value))) ChronoUnit/SECONDS)
             (catch Exception _ nil))))


;; Tover's balance at an instant


(defn balances-at
  "Tover's balances {[item warehouse] quantity} at each of `instants` (records at the instant count): one replay of
  the effective accepted history in business order, read at each instant. Keys are whole-second instants."
  {:malli/schema [:=> [:cat [:maybe :map] [:sequential :any]] [:map-of inst? [:map-of [:tuple :string :string] :int]]]}
  [{:keys [records lines] :or {records [] lines []}} instants]
  (let [occurred (into {} (map (juxt :record-revision-id (comp instant :occurred-at))) records)
        steps    (:steps (valuation/replay records lines))]
    (loop [wanted (sort (distinct (keep instant instants))) steps steps balances {} found {}]
      (cond
        (empty? wanted) found
        (and (seq steps) (not (.isAfter ^Instant (occurred (:record-revision-id (first steps))) ^Instant (first wanted))))
        (let [{:keys [stock-item-id warehouse-id balance-after]} (first steps)]
          (recur wanted (rest steps) (assoc balances [stock-item-id warehouse-id] balance-after) found))
        :else (recur (rest wanted) steps balances (assoc found (first wanted) balances))))))


(defn balance-at
  "Tover's balances {[item warehouse] quantity} at `at`, replaying the accepted history up to it."
  {:malli/schema [:=> [:cat [:maybe :map] :any] [:map-of [:tuple :string :string] :int]]}
  [history at]
  (get (balances-at history [at]) (instant at) {}))


(defn narrowed
  "The accepted history of `items` only: every revision of each record with a line on one of them, and the lines of
  those revisions. Their balances replay exactly as in the whole history."
  {:malli/schema [:=> [:cat [:maybe :map] [:set :string]] :map]}
  [{:keys [records lines] :or {records [] lines []}} items]
  (let [record-of (into {} (map (juxt :record-revision-id :record-id)) records)
        touched   (into #{} (keep #(when (items (:stock-item-id %)) (record-of (:record-revision-id %)))) lines)
        kept      (filterv (comp touched :record-id) records)
        revisions (set (map :record-revision-id kept))]
    {:records kept :lines (filterv (comp revisions :record-revision-id) lines)}))


;; Comparison


(defn- confirmed
  {:malli/schema [:=> [:cat [:maybe :map] :string] :map]}
  [mappings connection-id]
  (into {} (filter (fn [[_ m]] (and (= "confirmed" (:state m)) (= connection-id (:connection-id m))))) mappings))


(defn- items
  "The stock items the connection's confirmed product mappings reach, sorted."
  {:malli/schema [:=> [:cat :map] [:vector :string]]}
  [{:keys [mappings connection-id]}]
  (vec (sort (distinct (keep :stock-item-id (vals (confirmed mappings connection-id)))))))


(defn- pass-of
  {:malli/schema [:=> [:cat :map :any] [:maybe :map]]}
  [{:keys [passes]} run]
  (let [run-id (if (map? run) (:run-id run) run)]
    (some #(when (= run-id (:run-id %)) %) passes)))


(defn- tover-at
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [balances history]} {:keys [run-id observed-at]}]
  (or (get balances run-id) (balance-at history observed-at)))


(defn- item-rows
  "The item's differences in one finished pass, by warehouse; nil when the item is not compared there."
  {:malli/schema [:=> [:cat :map :map :string] [:maybe [:vector Difference]]]}
  [{:keys [connection-id mappings warehouse-mappings baselines figures] :as facts} {:keys [run-id observed-at asked] :as pass} item]
  (let [listings (set (keep (fn [[id m]] (when (= item (:stock-item-id m)) id)) (confirmed mappings connection-id)))
        places   (into {} (keep (fn [[id m]] (when (warehouses/fbo? (:warehouse-type m)) [id (:warehouse-id m)])))
                       (confirmed warehouse-mappings connection-id))
        stated   (filter #(= "fbo" (:fulfilment-type %))
                         (if-let [index (:figure-index facts)]
                           (mapcat #(get index [run-id %]) listings)
                           (filter #(and (= run-id (:run-id %)) (listings (:external-product-id %))) figures)))
        tover    (tover-at facts pass)
        baseline #(get baselines (str connection-id "|" item "|" %))
        held     (set (keep #(when (pos? (:present %)) (:external-warehouse-id %)) stated))
        homes    (vec (sort (distinct (vals places))))
        compared (filterv #(or (baseline %) (not (zero? (get tover [item %] 0)))
                               (some (fn [[ext w]] (and (= w %) (held ext))) places))
                          homes)
        at       (instant observed-at)]
    (when (and (some asked listings)
               (every? places held)
               (seq compared)
               (every? #(when-let [b (baseline %)] (not (.isBefore ^Instant at ^Instant (instant (:instant b))))) compared))
      (mapv (fn [warehouse]
              (let [externals (keep (fn [[ext w]] (when (= w warehouse) ext)) places)
                    ozon      (reduce + 0 (for [l listings e externals] (or (sync/stated-present pass stated l e) 0)))
                    balance   (get tover [item warehouse] 0)]
                {:stock-item-id item :warehouse-id warehouse :ozon ozon :tover balance :difference (- ozon balance)}))
            compared))))


(defn differences
  "Board `Stock Differences`, the rows of one finished pass (`run`, its id or the pass): per compared item and FBO
  warehouse, Ozon's quantity, Tover's balance at the observation time and their difference."
  {:malli/schema [:=> [:cat Facts :any] [:vector Difference]]}
  [facts run]
  (if-let [pass (pass-of facts run)]
    (into [] (mapcat #(item-rows facts pass %)) (items facts))
    []))


(defn- agrees?
  {:malli/schema [:=> [:cat [:maybe [:sequential Difference]]] :boolean]}
  [rows]
  (boolean (and (seq rows) (every? (comp zero? :difference) rows))))


(defn last-agreeing
  "The latest finished snapshot before the newest one in which the item is compared without any difference; nil
  when none agreed yet."
  {:malli/schema [:=> [:cat Facts :string] [:maybe Snapshot]]}
  [{:keys [passes] :as facts} item]
  (some #(when (agrees? (item-rows facts % item)) (select-keys % [:run-id :observed-at])) (rest passes)))


(defn pairs
  "Moves pairing the largest shortage with the largest surplus until none is left. `differences` are rows or
  {warehouse difference}; a shortage (Ozon below Tover) is where stock moved from."
  {:malli/schema [:=> [:cat [:or [:map-of :string :int] [:sequential :map]]] [:vector Move]]}
  [differences]
  (let [by-place (if (map? differences) differences (into {} (map (juxt :warehouse-id :difference)) differences))
        ordered  (fn [pred] (vec (sort-by (juxt (comp - abs val) key) (filter (comp pred val) by-place))))]
    (loop [from (ordered neg?) to (ordered pos?) moves []]
      (if (or (empty? from) (empty? to))
        moves
        (let [[f short] (first from)            [t surplus] (first to)
              n         (min (- short) surplus)
              left-from (+ short n)             left-to     (- surplus n)]
          (recur (if (zero? left-from) (subvec from 1) (assoc from 0 [f left-from]))
                 (if (zero? left-to) (subvec to 1) (assoc to 0 [t left-to]))
                 (conj moves {:from f :to t :quantity n})))))))


(defn- state
  {:malli/schema [:=> [:cat Facts :string [:sequential Difference] [:maybe Snapshot]] State]}
  [{:keys [undecided]} item rows agreeing]
  (cond
    (contains? undecided item) "waiting for operations"
    (agrees? rows) "equal"
    (and agreeing (zero? (reduce + 0 (map :difference rows)))) "moved"
    :else "unexplained"))


(defn item-state
  "The item's state in the newest pass: nil when it is not compared there."
  {:malli/schema [:=> [:cat Facts :string] [:maybe State]]}
  [{:keys [passes] :as facts} item]
  (when-let [rows (some-> (first passes) (#(item-rows facts % item)))]
    (state facts item rows (last-agreeing facts item))))


(defn indexed
  "The facts with their figures indexed by run and listing, so each comparison reads only its own."
  {:malli/schema [:=> [:cat Facts] Facts]}
  [facts]
  (assoc facts :figure-index (group-by (juxt :run-id :external-product-id) (:figures facts))))


;; Facts


(defn- cutovers
  "The earliest baseline instant of each stock item on the connection."
  {:malli/schema [:=> [:cat [:maybe :map] :string] [:map-of :string inst?]]}
  [baselines connection-id]
  (reduce (fn [acc {:keys [stock-item-id] :as b}]
            (let [at (instant (:instant b))]
              (cond-> acc (and at (or (nil? (acc stock-item-id)) (.isBefore ^Instant at ^Instant (acc stock-item-id)))) (assoc stock-item-id at))))
          {}
          (filter #(= connection-id (:connection-id %)) (vals baselines))))


(defn undecided
  "Stock items with an operation of the connection that is not accepted and not decided (`history`, `set aside`,
  `no effect`), with a line on the item after its cutover: dated after it, or not dated yet."
  {:malli/schema [:=> [:cat steps/Facts :string] [:set :string]]}
  [facts connection-id]
  (let [from (cutovers (:baselines facts) connection-id)]
    (set (for [[id operation] (:operations facts)
               :when          (not (contains? (:acceptances facts) id))
               :let           [lines (proposals/resolve-lines facts operation)]
               :when          (nil? (steps/finished facts operation lines))
               :let           [at (instant (:occurred-at operation))]
               item           (distinct (keep :stock-item-id lines))
               :when          (when-let [cutover (from item)] (or (nil? at) (.isAfter ^Instant at ^Instant cutover)))]
           item))))


(defn- correcting?
  "Whether an accepted operation's source change is still undecided (plan 5 task 1): its correction is pending,
  cannot be proposed (the accepted revision is missing or the kind changed), or intake would propose one now."
  {:malli/schema [:=> [:cat :map inst?] :boolean]}
  [facts now]
  (boolean (or (corrections/target facts) (corrections/unproposable facts) (seq (:events (corrections/assess facts now))))))


(defn changed
  "Stock items on the lines of the connection's accepted operations whose source changed since and whose correction
  is not decided: the source states a revision other than the one the record reflects, or a correction is
  proposed. Only those operations are evaluated, each by id."
  {:malli/schema [:=> [:cat :map steps/Facts :string] [:set :string]]}
  [context {:keys [acceptances operations]} connection-id]
  (let [rows       (into {} (map (juxt :record-id identity)) (records/all-records context ::provenance/source-corrections :record-id))
        candidates (for [[id acceptance] (sort-by key acceptances)
                         :let            [operation (get operations id)
                                          row (get rows (:record-id acceptance))
                                          reflected (max (provenance/reflected-revision acceptance) (or (:accepted-revision row) 0))]
                         :when           (and operation (or (:kind row) (not= (:revision operation) reflected)))]
                     operation)]
    (if (empty? candidates)
      #{}
      (let [shared (corrections/connection-facts context connection-id)
            now    (runtime/coeffect ::acceptance/now context {})]
        (set (for [operation candidates
                   :let      [facts (corrections/operation-facts context shared operation)]
                   :when     (correcting? facts now)
                   line      (concat (get-in facts [:new-proposal :entry :lines]) (get-in facts [:accepted-proposal :entry :lines])
                                     (get-in facts [:latest :lines]))
                   :let      [item (:stock-item-id line)]
                   :when     item]
               item))))))


(defn load-facts
  "`Facts` of one connection: plan 4's operation facts once (mappings, baselines, operations and history), the
  retained passes and figures, and Tover's balances at every pass from one replay of the mapped items' history."
  {:malli/schema [:=> [:cat :map :string] Facts]}
  [context connection-id]
  (let [operation-facts (operations/load-facts context connection-id)
        passes          (vec (take sync/retained-passes (sync/finished-passes context connection-id "stock-by-warehouse")))
        facts           {:connection-id      connection-id
                         :passes             passes
                         :figures            (into [] (mapcat #(sync/stock-figures context connection-id (:run-id %))) passes)
                         :mappings           (:mappings operation-facts)
                         :warehouse-mappings (:warehouse-mappings operation-facts)
                         :baselines          (:baselines operation-facts)
                         :undecided          (into (undecided operation-facts connection-id)
                                                   (changed context operation-facts connection-id))}
        history         (narrowed (:history operation-facts) (set (items facts)))
        at              (balances-at history (map :observed-at passes))]
    (assoc (indexed facts)
           :history history
           :balances (into {} (map (fn [{:keys [run-id observed-at]}] [run-id (get at (instant observed-at) {})])) passes))))


(defmethod runtime/coeffect ::facts
  [_ context {:keys [connection-id]}]
  (load-facts context connection-id))


(def Item
  [:map
   [:stock-item-id Ref] [:state State]
   [:rows [:vector [:map [:warehouse-id Ref] [:ozon :int] [:tover :int] [:difference :int]]]]
   [:last-agreeing {:optional true} Snapshot]
   [:moves {:optional true} [:vector Move]]])


(def StockDifferences
  "Board `Stock Differences`: the newest finished snapshot compared with Tover, per compared item."
  [:map [:connection-id Ref] [:run-id {:optional true} Ref] [:observed-at {:optional true} At] [:items [:vector Item]]])


(defn report
  "`Stock Differences` from the facts of one connection."
  {:malli/schema [:=> [:cat Facts] StockDifferences]}
  [{:keys [connection-id passes] :as facts}]
  (let [pass (first passes)
        rows (group-by :stock-item-id (when pass (differences facts pass)))]
    (cond-> {:connection-id connection-id
             :items         (vec (for [item  (items facts)
                                       :let  [found (rows item)]
                                       :when found
                                       :let  [agreeing (last-agreeing facts item)
                                              state (state facts item found agreeing)]]
                                   (cond-> {:stock-item-id item :state state
                                            :rows          (mapv #(dissoc % :stock-item-id) found)}
                                     agreeing (assoc :last-agreeing agreeing)
                                     (= "moved" state) (assoc :moves (pairs found)))))}
      pass (assoc :run-id (:run-id pass) :observed-at (:observed-at pass)))))


(defreadmodel stock-differences
  {:input  [:map [:connection-id Ref]]
   :output StockDifferences
   :policy ::records/workspace-member
   :cofx   [[:facts ::facts (fn [_ input] (select-keys input [:connection-id]))]]}
  [{:keys [facts]} _]
  (report facts))
