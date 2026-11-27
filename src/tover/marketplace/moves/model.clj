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
    [bel.core :as bel :refer [defcommand defevent defmodel defprocessor defreadmodel]]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [com.brunobonacci.mulog :as mu]
    [tover.catalogues.records :as records]
    [tover.inventory.acceptance.model :as acceptance]
    [tover.inventory.entries.model :as entries]
    [tover.inventory.valuation :as valuation]
    [tover.marketplace.connections.model :as connections]
    [tover.marketplace.operations.corrections :as corrections]
    [tover.marketplace.operations.model :as operations]
    [tover.marketplace.operations.proposals :as proposals]
    [tover.marketplace.operations.steps :as steps]
    [tover.marketplace.provenance :as provenance]
    [tover.marketplace.sync.model :as sync]
    [tover.marketplace.sync.observations :as observations]
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
                         :connection         (:connection operation-facts)
                         :pending            (:entries operation-facts)
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


;; Board 45 slice c · Propose the detected move
;;
;; `Move Detection` evaluates each compared item of a marked, active connection and acts as the connector. A move
;; becomes one pending Transfer per from-to pair, dated one second after the last agreeing snapshot, keyed by the
;; connection, the item and that snapshot (`::detected-moves`). The same moves emit nothing; different moves replace
;; the unedited Transfers and withdraw the pairs no longer moved; a Transfer a member edited is kept, an accepted one
;; is history. A discard is remembered by the row: its moves are unchanged, so nothing is proposed again until they
;; change. Nothing here is ever accepted automatically.


(def DetectedMove
  "Board `Detected Move Proposed`, one row per key `<connection>|<item>|<last agreeing run>`: the snapshot it was
  last evaluated against, the moves (JSON list of {from to quantity}) and the pending Transfers' entry ids (JSON
  list, empty when withdrawn)."
  [:map
   [:detected-move-id records/Id]
   [:connection-id Ref]
   [:stock-item-id records/Id]
   [:last-agreeing-run Ref]
   [:current-run Ref]
   [:moves :string]
   [:entries :string]
   [:proposed-at At]])


(defevent detected-move-proposed {:schema DetectedMove})


(defmodel detected-moves
  {:id     :detected-move-id :store :catalogue :schema DetectedMove :policy ::records/workspace-member
   :events {::detected-move-proposed {:set [:connection-id :stock-item-id :last-agreeing-run :current-run :moves :entries :proposed-at]}}})


(defn move-key
  {:malli/schema [:=> [:cat :string :string :string] :string]}
  [connection-id stock-item-id last-agreeing-run]
  (str connection-id "|" stock-item-id "|" last-agreeing-run))


(defn move-entry-id
  "The pending Transfer of one pair: `move:<key>|<from>|<to>`."
  {:malli/schema [:=> [:cat :string :string :string] :string]}
  [key from to]
  (str "move:" key "|" from "|" to))


(def Evaluation
  [:map [:stock-item-id :string] [:current-run :string] [:last-agreeing Snapshot] [:moves [:vector Move]]])


(defn evaluate
  "What the newest snapshot says of `item`: the moves from its last agreeing snapshot (empty unless `moved`), or
  `{:refusal :stale}` when it is not compared, waits for operations or has no agreeing snapshot."
  {:malli/schema [:=> [:cat Facts :string] [:or Evaluation [:map [:refusal :keyword]]]]}
  [{:keys [passes] :as facts} item]
  (let [pass     (first passes)
        rows     (when pass (item-rows facts pass item))
        agreeing (when rows (last-agreeing facts item))
        st       (when rows (state facts item rows agreeing))]
    (if (or (nil? agreeing) (= "waiting for operations" st))
      {:refusal :stale}
      {:stock-item-id item :current-run (:run-id pass) :last-agreeing agreeing
       :moves         (if (= "moved" st) (pairs rows) [])})))


(defn- read-list
  {:malli/schema [:=> [:cat [:maybe :string]] [:vector :any]]}
  [text]
  (vec (or (observations/read-json text) [])))


(defn- connector?
  {:malli/schema [:=> [:cat [:maybe :map] :uuid] :boolean]}
  [stored actor]
  (= (str (get-in stored [:entry :saved-by])) (str actor)))


(defn- pending?
  {:malli/schema [:=> [:cat [:maybe :map]] :boolean]}
  [stored]
  (= "pending" (get-in stored [:entry :status])))


(defn- comparable
  {:malli/schema [:=> [:cat [:maybe :map] [:sequential :map]] :any]}
  [entry lines]
  [(:kind entry) (instant (:occurred-at entry)) (:from-warehouse-id entry) (:to-warehouse-id entry) (boolean (:amends entry))
   (boolean (:cancel entry)) (mapv (juxt :line-id :stock-item-id :quantity :warehouse-id) lines)])


(defn- withdrawal
  "Discarding an unedited pending Transfer, with its lines and any stock item changes."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [{:keys [entry lines changes]}]
  (let [entry-id (:entry-id entry)]
    (-> [{:type ::entries/inventory-draft-discarded :data {:entry-id entry-id :status "discarded" :revision (inc (:revision entry))}}]
        (into (entries/line-events entry-id (or lines []) []))
        (into (entries/change-events entry-id (or changes []) [])))))


(defn- transfer
  "The pending Transfer of one move: one line, dated one second after the last agreeing snapshot."
  {:malli/schema [:=> [:cat :string :string Snapshot Move [:maybe :map] :uuid] [:tuple :map [:vector :map]]]}
  [entry-id item {:keys [observed-at]} {:keys [from to quantity]} stored actor]
  [{:entry-id          entry-id :kind        "transfer" :status "pending" :revision (inc (or (get-in stored [:entry :revision]) 0)) :saved-by actor
    :occurred-at       (.plusSeconds ^Instant (instant observed-at) 1)
    :from-warehouse-id from :to-warehouse-id to}
   [{:line-id "1" :stock-item-id item :quantity quantity}]])


(defn proposal-events
  "Board `Propose Detected Move`: what one item's `evaluation` changes, given the item's `::detected-moves` rows by
  id and the `stored` entries ({entry-id {:entry :lines :changes}}) they and the moves name. Rows of the item under
  another key are superseded: their unedited pending Transfers are withdrawn. Empty when nothing changes."
  {:malli/schema [:=> [:cat :string Evaluation [:map-of :string :map] [:map-of :string :map] :any] [:vector :map]]}
  [connection-id {:keys [stock-item-id current-run last-agreeing moves]} rows stored now]
  (let [actor      (sync/connector-actor connection-id)
        id-now     (move-key connection-id stock-item-id (:run-id last-agreeing))
        withdrawn? #(and (pending? %) (connector? % actor))
        proposed   (fn [row-key last-run moves ids]
                     {:type ::detected-move-proposed
                      :data {:detected-move-id  row-key :connection-id connection-id                 :stock-item-id stock-item-id
                             :last-agreeing-run last-run :current-run current-run                    :moves         (observations/write-json moves)
                             :entries           (observations/write-json ids) :proposed-at (str now)}})
        superseded (vec (for [[row-key row] (sort-by first rows)
                              :when         (not= id-now row-key)
                              :let          [gone (filter withdrawn? (keep stored (read-list (:entries row))))]
                              :when         (seq gone)
                              event         (conj (into [] (mapcat withdrawal) gone) (proposed row-key (:last-agreeing-run row) [] []))]
                          event))
        row        (get rows id-now)
        current    (if (and row (= moves (read-list (:moves row))))
                     []
                     (let [desired (for [{:keys [from to] :as move} moves
                                         :let [id (move-entry-id id-now from to) s (get stored id)]
                                         :when (not= "accepted" (get-in s [:entry :status]))]
                                     [id move s])
                           ids     (mapv first desired)
                           saves   (into [] (mapcat (fn [[id move s]]
                                                      (when-not (and (pending? s) (not (connector? s actor)))
                                                        (let [[entry lines] (transfer id stock-item-id last-agreeing move s actor)]
                                                          (when-not (and (pending? s) (= (comparable (:entry s) (:lines s)) (comparable entry lines)))
                                                            (into [{:type ::entries/shared-inventory-entry-saved :data entry}]
                                                                  (entries/line-events id (if (pending? s) (:lines s) []) lines)))))))
                                         desired)
                           removed (into [] (mapcat withdrawal)
                                         (filter withdrawn? (keep stored (remove (set ids) (read-list (:entries row))))))
                           changed (into saves removed)]
                       (if (empty? changed) [] (conj changed (proposed id-now (:run-id last-agreeing) moves ids)))))]
    (into superseded current)))


(defn entry-ids
  "The entries one item's proposal reads: those its rows list and those its moves would be."
  {:malli/schema [:=> [:cat :string Evaluation [:maybe [:sequential :map]]] [:set :string]]}
  [connection-id {:keys [stock-item-id last-agreeing moves]} rows]
  (let [id-now (move-key connection-id stock-item-id (:run-id last-agreeing))]
    (into (set (mapcat #(read-list (:entries %)) rows)) (map #(move-entry-id id-now (:from %) (:to %))) moves)))


(defn stored-entries
  "The shared entries of `ids` in any status, loaded by id, each with its lines and changes while pending (from
  `pending`, the pending entries already read)."
  {:malli/schema [:=> [:cat :map [:sequential :map] [:set :string]] [:map-of :string :map]]}
  [context pending ids]
  (let [open (into {} (map (juxt :entry-id identity)) pending)]
    (into {} (map (fn [[id entry]]
                    (let [p (get open id)]
                      [id {:entry entry :lines (or (:lines p) []) :changes (or (:changes p) [])}])))
          (when (seq ids) (sync/load-rows context ::entries/entries (vec (sort ids)))))))


(defn- rows-by-item
  "The connection's `::detected-moves` rows by stock item, each by id."
  {:malli/schema [:=> [:cat :map :string] [:map-of :string [:map-of :string :map]]]}
  [context connection-id]
  (->> (records/all-records context ::detected-moves :detected-move-id)
       (filter #(= connection-id (:connection-id %)))
       (group-by :stock-item-id)
       (into {} (map (fn [[item rows]] [item (into {} (map (juxt :detected-move-id identity)) rows)])))))


(def ProposeDetectedMove
  "Board `Propose Detected Move`: the item and the evidence intake read. The moves are computed again on the facts
  the command commits on; a newer snapshot, an undecided operation or a changed balance makes the input stale."
  [:map [:connection-id Ref] [:stock-item-id records/Id] [:last-agreeing-run Ref] [:current-run Ref]])


(def ^:private messages
  {:not-permitted     "Not permitted"
   :connection-paused "Connection paused"
   :stale             "A newer snapshot, an undecided operation or a changed balance exists."})


(defn- fail!
  {:malli/schema [:=> [:cat :keyword] :nil]}
  [code]
  (records/fail! code (messages code) {}))


(defmethod runtime/coeffect ::item-rows
  [_ context {:keys [connection-id stock-item-id]}]
  (get (rows-by-item context connection-id) stock-item-id {}))


(defmethod runtime/coeffect ::stored
  [_ context {:keys [pending ids]}]
  (stored-entries context pending ids))


(defn- stored-input
  "The entries the command reads: those the item's rows list and, when it evaluates, those its moves would be."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [facts rows]} {:keys [connection-id stock-item-id]}]
  (let [evaluation (evaluate facts stock-item-id)]
    {:pending (:pending facts)
     :ids     (if (:refusal evaluation) #{} (entry-ids connection-id evaluation (vals rows)))}))


(defcommand propose-detected-move
  ;; The connector acts only for its own active connection, and never accepts: the Transfers wait for a member.
  {:input  ProposeDetectedMove
   :policy ::sync/connector-principal
   :cofx   [[:facts ::facts (fn [_ input] (select-keys input [:connection-id]))]
            [:rows ::item-rows (fn [_ input] (select-keys input [:connection-id :stock-item-id]))]
            [:stored ::stored stored-input]]
   :emits  #{::detected-move-proposed ::entries/shared-inventory-entry-saved ::entries/inventory-draft-discarded
             ::entries/inventory-entry-line-saved ::entries/inventory-entry-line-removed
             ::entries/inventory-entry-line-barcode-added ::entries/inventory-entry-line-barcode-removed
             ::entries/inventory-entry-change-removed ::entries/inventory-entry-change-barcode-removed}}
  [{:keys [scope actor now facts rows stored]} {:keys [connection-id stock-item-id last-agreeing-run current-run]}]
  (when (not= actor (sync/connector-actor connection-id)) (fail! :not-permitted))
  (when (not= "active" (get-in facts [:connection :sync-state])) (fail! :connection-paused))
  (let [evaluation (evaluate facts stock-item-id)]
    (when (or (:refusal evaluation)
              (not= current-run (:current-run evaluation))
              (not= last-agreeing-run (get-in evaluation [:last-agreeing :run-id])))
      (fail! :stale))
    (let [events (proposal-events connection-id evaluation rows stored now)]
      (if (empty? events)
        {}
        {:events events :fx (records/publish scope)}))))


;; Move Detection


(def DetectionItem
  [:map [:workspace-id :string] [:connection-id :string] [:input ProposeDetectedMove]])


(defn- connection-requests
  "One request per item whose proposal changes something; the facts, rows and entries are read once."
  {:malli/schema [:=> [:cat :map :uuid :string inst?] [:vector DetectionItem]]}
  [system workspace-id connection-id now]
  (let [context {:bel/system system :scope {:workspace-id workspace-id}}
        facts   (load-facts context connection-id)]
    (if (not= "active" (get-in facts [:connection :sync-state]))
      []
      (let [by-item     (rows-by-item context connection-id)
            evaluations (vec (remove :refusal (map #(evaluate facts %) (items facts))))
            stored      (stored-entries context (:pending facts)
                                        (into #{} (mapcat #(entry-ids connection-id % (vals (get by-item (:stock-item-id %) {})))) evaluations))]
        (vec (for [{:keys [stock-item-id current-run last-agreeing] :as evaluation} evaluations
                   :when (seq (proposal-events connection-id evaluation (get by-item stock-item-id {}) stored now))]
               {:workspace-id (str workspace-id) :connection-id connection-id
                :input        {:connection-id     connection-id           :stock-item-id stock-item-id
                               :last-agreeing-run (:run-id last-agreeing) :current-run   current-run}}))))))


(defmethod runtime/coeffect ::moves-to-propose
  [_ {:keys [bel/system]} _]
  (if-let [marketplace (get-in system [:resources :marketplace])]
    (let [owned (for [workspace-id (sync/workspace-ids system)
                      connection   (sync/workspace-connections system workspace-id)]
                  [workspace-id (:connection-id connection)])
          now   (if-let [clock (:clock marketplace)] (Instant/ofEpochMilli (clock)) (Instant/now))
          dirty (sync/take-dirty! marketplace :move-detection (set (map second owned)) now)]
      (vec (for [[workspace-id connection-id] owned
                 :when (contains? dirty connection-id)
                 item (try (connection-requests system workspace-id connection-id now)
                           (catch InterruptedException error (throw error))
                           (catch Exception error
                             ;; The minute sweep tries the connection again.
                             (mu/log ::detection-skipped :connection-id connection-id
                                     :error-type (or (:type (ex-data error)) (.getName (class error))))
                             []))]
             item)))
    []))


(defprocessor ^{:malli/schema [:=> [:cat DetectionItem] bel/ProcessorRequest]} move-detection
  ;; Board `Move Detection`: wakes on the connection's marks (finished passes, acceptances, saves) and the minute sweep.
  {:todo ::moves-to-propose}
  [{:keys [workspace-id connection-id input]}]
  {:type  :command :id ::propose-detected-move :scope {:workspace-id (parse-uuid workspace-id)}
   :actor (sync/connector-actor connection-id)
   :input input})


;; What the Stock differences view shows beside each moved item


(def ProposedTransfer
  [:map [:entry-id :string] [:from Ref] [:to Ref] [:quantity :int] [:status [:enum "pending" "accepted" "discarded"]] [:edited :boolean]])


(def Proposal
  [:map [:stock-item-id Ref] [:last-agreeing-run Ref] [:current-run Ref] [:transfers [:vector ProposedTransfer]]])


(defn proposals
  "The connection's detected moves with the Transfers they produced: each entry's status, and whether a member edited
  it. Quantities are the detected ones."
  {:malli/schema [:=> [:cat :string [:sequential :map] [:map-of :string :map]] [:vector Proposal]]}
  [connection-id rows stored]
  (let [actor (sync/connector-actor connection-id)]
    (vec (for [{:keys [detected-move-id stock-item-id last-agreeing-run current-run moves entries]} (sort-by :detected-move-id rows)
               :let [listed (set (read-list entries))]]
           {:stock-item-id stock-item-id :last-agreeing-run last-agreeing-run :current-run current-run
            :transfers     (vec (for [{:keys [from to quantity]} (read-list moves)
                                      :let [id (move-entry-id detected-move-id from to) s (get stored id)]
                                      :when (and (listed id) s)]
                                  {:entry-id id :from from :to to :quantity quantity :status (get-in s [:entry :status])
                                   :edited   (not (connector? s actor))}))}))))


(defmethod runtime/coeffect ::connection-rows
  [_ context {:keys [connection-id]}]
  (vec (mapcat vals (vals (rows-by-item context connection-id)))))


(defreadmodel detected-move-proposals
  {:input  [:map [:connection-id Ref]]
   :output [:map [:proposals [:vector Proposal]]]
   :policy ::records/workspace-member
   :cofx   [[:rows ::connection-rows (fn [_ input] (select-keys input [:connection-id]))]
            [:stored ::stored (fn [{:keys [rows]} _] {:pending [] :ids (set (mapcat #(read-list (:entries %)) rows))})]]}
  [{:keys [rows stored]} {:keys [connection-id]}]
  {:proposals (proposals connection-id rows stored)})
