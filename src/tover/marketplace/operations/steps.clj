(ns tover.marketplace.operations.steps
  "Board chapter 45 slices 3 and 5 · Check automatic-acceptance eligibility, and the one next step of every
  downloaded operation (`Imported Operations Needing Review`).

  Pure. `classify` decides from the facts at hand: `no effect`, `set aside` and `history` first (they are finished
  states, never shown as another step), then the board's table, the first row that applies. `automatic` is the
  only eligible step; every other operation waits for a member or for Ozon, and `failing-conditions` names why it
  is not accepted without a person. An accepted operation has no next step.

  The shortage check is the ordinary `acceptance/review` over the operation's own entry: its pending entry when
  intake saved one, else the entry `proposals/proposal` builds."
  (:require
    [clojure.string :as str]
    [tover.inventory.acceptance.model :as acceptance]
    [tover.inventory.valuation :as valuation]
    [tover.marketplace.operations.proposals :as proposals]
    [tover.marketplace.products.guard :as guard]
    [tover.marketplace.sync.model :as sync]
    [tover.marketplace.sync.observations :as observations])
  (:import
    [java.time Instant]))


(def next-steps
  "The board's next steps, in table order: `first that applies`, except the last three, which are decided first."
  ["resolve conflict" "match product" "confirm match again" "match warehouse" "set cutover" "remove old lines"
   "waiting for Ozon" "enter missing facts" "confirm return warehouse" "accept the sale first" "fix shortage"
   "connection stopped" "accept" "automatic" "history" "set aside" "no effect"])


(def NextStep
  (into [:enum] next-steps))


(def conditions
  "What automatic acceptance requires, in the order failing conditions are named."
  ["connection state" "kind" "mapping" "baseline" "completeness" "set aside" "member edit" "source conflict" "validation"])


(def Facts
  "`proposals/Facts`, and: `connection` the connection row, `guard-facts` the Mapping Guard's catalogue (products,
  stock items, barcodes, definitions; a mapping it finds reasons for counts as suspended), `imports`,
  `acceptances` and `aside` by source operation id, `catalogue`, `history` and `provenance` (`::provenance/facts`,
  else the baselines alone) as acceptance reviews them, `history-index` (`index-history`) so each review replays only
  the pairs it touches, `entries` the pending entries, `now` the review instant. Without `guard-facts` \"mapping\"
  fails, without `now` \"validation\" fails: an operation is never automatic on facts nobody supplied."
  [:map
   [:connection {:optional true} [:maybe :map]]
   [:products {:optional true} [:sequential :map]]
   [:mappings {:optional true} [:map-of :string :map]]
   [:guard-facts {:optional true} [:maybe :map]]
   [:warehouse-mappings {:optional true} [:map-of :string :map]]
   [:baselines {:optional true} [:map-of :string :map]]
   [:imports {:optional true} [:map-of :string :map]]
   [:acceptances {:optional true} [:map-of :string :map]]
   [:aside {:optional true} [:map-of :string :map]]
   [:operations {:optional true} [:map-of :string :map]]
   [:catalogue {:optional true} [:maybe :map]]
   [:history {:optional true} [:maybe [:map [:records [:sequential :map]] [:lines [:sequential :map]]]]]
   [:entries {:optional true} [:sequential :map]]
   [:currency {:optional true} [:maybe :string]]
   [:provenance {:optional true} [:maybe :map]]
   [:history-index {:optional true} [:maybe :map]]
   [:now {:optional true} [:maybe inst?]]])


(defn index-history
  "`history` by stock item and warehouse: the record ids with a line on each pair, every revision of each record,
  and the lines of each revision. Built once per set of facts, so classifying many operations replays only what
  each one touches."
  {:malli/schema [:=> [:cat [:maybe :map]] :map]}
  [{:keys [records lines]}]
  (let [record-of (into {} (map (juxt :record-revision-id :record-id)) records)]
    {:by-pair   (reduce (fn [index {:keys [stock-item-id warehouse-id record-revision-id]}]
                          (update index [stock-item-id warehouse-id] (fnil conj #{}) (record-of record-revision-id)))
                        {} lines)
     :revisions (group-by :record-id records)
     :lines     (group-by :record-revision-id lines)}))


(defn- narrowed
  "The history a review of `entry` needs: every revision, with all its lines, of each record with a line on a pair
  the entry touches. Quantities on those pairs replay exactly as in the whole history. Without an index, the whole
  history."
  {:malli/schema [:=> [:cat :map [:maybe :map]] :map]}
  [{:keys [history history-index]} entry]
  (if-not (and history-index entry)
    (or history {:records [] :lines []})
    (let [warehouses (distinct (filter some? (concat [(:from-warehouse-id entry) (:to-warehouse-id entry)] (map :warehouse-id (:lines entry)))))
          pairs      (for [item (distinct (keep :stock-item-id (:lines entry))) warehouse warehouses] [item warehouse])
          revisions  (vec (mapcat (:revisions history-index) (distinct (mapcat (:by-pair history-index) pairs))))]
      {:records revisions
       :lines   (vec (mapcat (comp (:lines history-index) :record-revision-id) revisions))})))


(def Group
  "The decision that unblocks the operation: one decision unblocks every operation of its group. `:label` reads it
  in English; `:label-key` with `:label-args` is the message the page translates."
  [:map [:decision NextStep] [:id :string] [:label :string] [:label-key :string] [:label-args [:map-of :keyword :string]]])


(def Correction
  "`Fix shortage`: a count of `count` at `instant` (text) raises the scope exactly to what its timeline needs."
  [:map [:stock-item-id :string] [:warehouse-id :string] [:instant :string] [:count :int]])


(def Classification
  [:map
   [:next-step NextStep]
   [:group Group]
   [:entry-id {:optional true} :string]
   [:missing [:vector :string]]
   [:findings [:vector :string]]
   [:suggested-correction {:optional true} Correction]
   [:failing-conditions [:vector (into [:enum] conditions)]]])


(defn- json
  {:malli/schema [:=> [:cat :any] :any]}
  [value]
  (if (string? value) (observations/read-json value) value))


(defn- instant
  {:malli/schema [:=> [:cat :any] [:maybe inst?]]}
  [value]
  (cond (instance? Instant value) value
        (string? value) (try (Instant/parse value) (catch Exception _ nil))
        :else nil))


(defn- status
  {:malli/schema [:=> [:cat :map] :string]}
  [operation]
  (str/lower-case (or (:status operation) "")))


(defn- set-aside?
  {:malli/schema [:=> [:cat :map :string] :boolean]}
  [facts id]
  (let [row (get-in facts [:aside id])]
    (boolean (and row (not (false? (:aside row)))))))


(defn- finished
  "`no effect`, `set aside` or `history`, decided before the table (roadmap decision 5), else nil. `lines` are the
  operation's resolved lines."
  {:malli/schema [:=> [:cat :map :map [:sequential :map]] [:maybe NextStep]]}
  [facts operation lines]
  (cond
    (and (nil? (proposals/entry-kind operation)) (:terminal operation)) "no effect"
    (set-aside? facts (:source-operation-id operation)) "set aside"
    (and (seq lines) (every? #(and (:stock-item-id %) (:baseline %) (not (:after-baseline %))) lines)) "history"
    :else nil))


(defn- decided?
  "Whether a return's original posting is decided: accepted, history or of no effect."
  {:malli/schema [:=> [:cat :map :string] :boolean]}
  [facts id]
  (boolean
    (or (contains? (:acceptances facts) id)
        (when-let [posting (get-in facts [:operations id])]
          (#{"history" "no effect"} (finished facts posting (proposals/resolve-lines facts posting)))))))


(defn- entry-id
  {:malli/schema [:=> [:cat :map :string] :string]}
  [facts id]
  (or (get-in facts [:imports id :entry-id]) (str "import:" id)))


(defn- pending-entry
  {:malli/schema [:=> [:cat :map :string] [:maybe :map]]}
  [facts id]
  (let [eid (entry-id facts id)]
    (some #(when (= eid (:entry-id %)) %) (:entries facts))))


(defn- filled
  "The missing facts the operation's pending entry does not hold yet: a member may have entered them."
  {:malli/schema [:=> [:cat [:sequential :string] [:maybe :map]] [:vector :string]]}
  [missing entry]
  (if-not entry
    (vec missing)
    (let [lines  (:lines entry)
          placed (if (= "transfer" (:kind entry))
                   (boolean (and (:from-warehouse-id entry) (:to-warehouse-id entry)))
                   (boolean (and (seq lines) (every? :warehouse-id lines))))
          has    {"actual time"         (some? (:occurred-at entry))
                  "line quantity"       (boolean (and (seq lines) (every? :quantity lines)))
                  "warehouse"           placed
                  "receiving warehouse" placed}]
      (vec (remove #(get has % false) missing)))))


(defn- listing-of
  "The connection's listing with its identifiers and attributes read, as the Mapping Guard takes it."
  {:malli/schema [:=> [:cat :map :string] [:maybe :map]]}
  [facts external-product-id]
  (some-> (some #(when (= external-product-id (:external-product-id %)) %) (:products facts))
          (update :identifiers #(vec (json %)))
          (update :attributes #(vec (json %)))))


(defn- guard-found?
  "Whether the Mapping Guard finds a reason to suspend the confirmed mapping now, before its suspension is stored."
  {:malli/schema [:=> [:cat :map :map] :boolean]}
  [{:keys [guard-facts] :as facts} {:keys [external-product-id mapping]}]
  (boolean
    (when (and guard-facts (= "confirmed" (:state mapping)))
      (when-let [listing (listing-of facts external-product-id)]
        (seq (guard/reasons {:listing listing :catalogue guard-facts} mapping))))))


(defn- stated-warehouses
  {:malli/schema [:=> [:cat :map] [:vector :string]]}
  [operation]
  (vec (distinct (filter some? (concat [(:from-warehouse operation) (:to-warehouse operation)]
                                       (map :warehouse (json (:lines operation))))))))


(defn- by-id
  {:malli/schema [:=> [:cat :keyword [:maybe [:sequential :map]]] :map]}
  [k rows]
  (into {} (map (juxt k identity)) rows))


(defn- usable-references?
  "Every item is active with a unit and every warehouse is active, in the catalogue as acceptance reads it."
  {:malli/schema [:=> [:cat :map [:sequential :map] [:maybe :map]] :boolean]}
  [{:keys [catalogue]} lines entry]
  (let [items      (by-id :stock-item-id (:stock-items catalogue))
        warehouses (by-id :warehouse-id (:warehouses catalogue))
        named      (distinct (filter some? (concat [(:from-warehouse-id entry) (:to-warehouse-id entry)] (map :warehouse-id lines))))]
    (boolean
      (and catalogue
           (every? #(let [item (items (:stock-item-id %))]
                      (and (= "active" (:status item)) (not (str/blank? (:unit item)))))
                   lines)
           (every? #(= "active" (:status (warehouses %))) named)))))


(defn- review-entry
  "The operation's own entry as acceptance reviews it."
  {:malli/schema [:=> [:cat :map :map :map [:maybe :map]] :map]}
  [facts operation proposal entry]
  (or entry
      (let [{:keys [occurred-at] :as proposed} (:entry proposal)]
        (cond-> (assoc proposed
                       :entry-id (entry-id facts (:source-operation-id operation))
                       :status "pending" :revision 1 :saved-by (sync/connector-actor (:connection-id operation)))
          occurred-at (assoc :occurred-at (instant occurred-at))))))


(defn- review
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [catalogue history currency baselines provenance now]} {:keys [entry-id revision] :as entry}]
  (acceptance/review (merge catalogue
                            {:entries      [entry]
                             :history      (or history {:records [] :lines []})
                             :currency     currency
                             :provenance   (or provenance {:cutover-entries {} :baselines (or baselines {})})
                             :now          (or now Instant/MAX)
                             ;; Classifying never compares a review fingerprint, its costliest part.
                             :fingerprint? false})
                     {:selection [{:entry-id entry-id :revision revision}] :order [entry-id]}))


(defn- correction
  "The count just before the operation that keeps every later balance of the short scope at 0 or above, up to the
  scope's next count."
  {:malli/schema [:=> [:cat :map :map :map] [:maybe Correction]]}
  [{:keys [history]} entry result]
  (when-let [{:keys [stock-item-id warehouse-id]} (some #(when (= :negative-balance (:code %)) %) (:problems result))]
    (when-let [at (some-> (instant (:occurred-at entry)) (.minusSeconds 1))]
      (let [records        (into (vec (:records history)) (get-in result [:records :records]))
            lines          (into (vec (:lines history)) (get-in result [:records :lines]))
            when-of        (into {} (map (juxt :record-revision-id :occurred-at)) records)
            steps          (filter #(and (= stock-item-id (:stock-item-id %)) (= warehouse-id (:warehouse-id %)))
                                   (:steps (valuation/replay records lines)))
            [before after] (split-with #(not (pos? (compare (instant (when-of (:record-revision-id %))) at))) steps)
            start          (or (:balance-after (last before)) 0)
            lowest         (reduce min start (map :balance-after (take-while #(nil? (:counted-quantity %)) after)))]
        {:stock-item-id stock-item-id :warehouse-id warehouse-id :instant (str at) :count (- start lowest)}))))


(defn- capitalized
  {:malli/schema [:=> [:cat :string] :string]}
  [text]
  (str (str/upper-case (subs text 0 1)) (subs text 1)))


(defn- line-label
  "A line's listing name, else what identifies it."
  {:malli/schema [:=> [:cat :map :map :map] :string]}
  [facts operation {:keys [external-product-id line-key]}]
  (or (some-> external-product-id (#(listing-of facts %)) :name str/trim not-empty)
      (some->> (json (:lines operation)) (some #(when (= line-key (:line-key %)) %)) :identifiers first :value)
      external-product-id
      line-key))


(defn- posting-number
  "How Ozon numbers a source operation: its source id, else the last part of its id."
  {:malli/schema [:=> [:cat :map :string] :string]}
  [facts source-operation-id]
  (or (not-empty (str (get-in facts [:operations source-operation-id :source-id])))
      (last (str/split source-operation-id #":"))))


(defn- item-label
  {:malli/schema [:=> [:cat :map :string] :string]}
  [{:keys [catalogue]} stock-item-id]
  (let [item    (some #(when (= stock-item-id (:stock-item-id %)) %) (:stock-items catalogue))
        product (some #(when (= (:product-id item) (:product-id %)) %) (:products catalogue))]
    (or (not-empty (str/join " " (remove str/blank? [(:name product) (:variant-label item)]))) stock-item-id)))


(defn- group
  "The group of `step`: `template` names it with `name` (\"Match {name}\"), else the step does."
  {:malli/schema [:=> [:cat NextStep [:maybe :string] [:* :string]] Group]}
  [step id & [template name]]
  (let [key (or template (capitalized step)) args (if name {:name name} {})]
    {:decision step :id (or id step) :label (str/replace key "{name}" (or name "")) :label-key key :label-args args}))


(defn- evaluate
  "Everything the next step and eligibility read, computed once."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [facts {:keys [source-operation-id] :as operation}]
  (let [lines      (proposals/resolve-lines facts operation)
        kind       (proposals/entry-kind operation)
        proposal   (proposals/proposal facts operation)
        entry      (pending-entry facts source-operation-id)
        missing    (filled (if proposal (:missing proposal) (vec (json (:missing operation)))) entry)
        unmatched  (filter #(not (#{"confirmed" "suspended"} (get-in % [:mapping :state]))) lines)
        suspended  (filter #(or (= "suspended" (get-in % [:mapping :state])) (guard-found? facts %)) lines)
        warehouses (remove #(= "confirmed" (get-in facts [:warehouse-mappings % :state])) (stated-warehouses operation))
        itemized   (filter :stock-item-id lines)
        own        (when proposal (review-entry facts operation proposal entry))
        mapped     (and (empty? unmatched) (empty? suspended) (empty? warehouses)
                        (usable-references? facts (or (:lines own) itemized) own))
        facts      (assoc facts :history (narrowed facts own))
        result     (when (and own (empty? missing) mapped) (review facts own))
        connection (:connection facts)]
    {:lines     lines :kind          kind      :proposal   proposal   :entry    entry    :missing missing
     :unmatched unmatched :suspended suspended :warehouses warehouses :itemized itemized :own     own     :mapped mapped :result result
     :active    (and (= "active" (:sync-state connection)) (= "valid" (:credentials-state connection)))
     :edited    (boolean (and entry (not= (str (:saved-by entry)) (str (sync/connector-actor (:connection-id operation))))))
     :conflict  (boolean (get-in facts [:imports source-operation-id :source-conflict]))
     ;; What the shortage correction replays.
     :history   (:history facts)}))


(defn- failing
  {:malli/schema [:=> [:cat :map :map :map] [:vector :string]]}
  [facts operation {:keys [lines kind missing itemized mapped result active edited conflict]}]
  (let [fails {"connection state" (not active)
               "kind"             (not (and (= "sale" kind) (#{"fbs-posting" "fbo-posting"} (:kind operation))))
               ;; Missing facts never pass a condition: without the guard's facts a suspension may be due, without
               ;; `now` a future time is not ruled out, and without a review nothing was validated.
               "mapping"          (or (not mapped) (nil? (:guard-facts facts)))
               "baseline"         (not-every? :after-baseline itemized)
               "completeness"     (or (nil? kind) (empty? lines) (boolean (seq missing)))
               "set aside"        (set-aside? facts (:source-operation-id operation))
               "member edit"      edited
               "source conflict"  conflict
               "validation"       (or (nil? result) (not (:valid? result)) (nil? (:now facts)))}]
    (filterv fails conditions)))


(defn- table
  "The first row of the board's table that applies, with its group."
  {:malli/schema [:=> [:cat :map :map :map [:vector :string]] [:tuple NextStep Group]]}
  [facts operation {:keys [kind lines missing unmatched suspended warehouses itemized own result active conflict] :as evaluated} fails]
  (let [related    (:related-operation operation)
        returned?  (= "customer-return" kind)
        undecided? (and returned? related (contains? (:operations facts) related) (not (decided? facts related)))
        short      (when (and result own) (correction (assoc facts :history (:history evaluated)) own result))
        incomplete (or (nil? kind) (empty? lines) (seq missing))]
    (cond
      conflict ["resolve conflict" (group "resolve conflict" nil)]
      (seq unmatched) (let [line (first unmatched)]
                        ["match product" (group "match product" (or (:external-product-id line) (line-label facts operation line))
                                                "Match {name}" (line-label facts operation line))])
      (seq suspended) (let [line (first suspended)]
                        ["confirm match again" (group "confirm match again" (:external-product-id line)
                                                      "Confirm {name} again" (line-label facts operation line))])
      (seq warehouses) (let [id (first warehouses)]
                         ["match warehouse" (group "match warehouse" id
                                                   "Match warehouse {name}" (or (get-in facts [:warehouse-mappings id :name]) id))])
      (some (comp nil? :baseline) itemized) ["set cutover" (group "set cutover" (:connection-id operation))]
      (and (some :after-baseline itemized) (some (complement :after-baseline) itemized))
      ["remove old lines" (group "remove old lines" nil)]
      (and incomplete (not (:terminal operation))) ["waiting for Ozon" (group "waiting for Ozon" nil)]
      incomplete ["enter missing facts" (group "enter missing facts" nil)]
      (and returned? (= "return" (:kind operation)) (not undecided?)
           (#{"returned" "returnedtoozon" "receivedbyseller"} (status operation)))
      ["confirm return warehouse" (group "confirm return warehouse" nil)]
      undecided? ["accept the sale first" (group "accept the sale first" related "Accept sale {name}" (posting-number facts related))]
      short ["fix shortage" (group "fix shortage" (str (:stock-item-id short) "|" (:warehouse-id short))
                                   "Fix shortage of {name}" (item-label facts (:stock-item-id short)))]
      (not active) ["connection stopped" (group "connection stopped" (:connection-id operation))]
      (seq fails) ["accept" (group "accept" nil)]
      :else ["automatic" (group "automatic" nil)])))


(defn classify
  "Board `Imported Operations Needing Review` row of one downloaded operation: its next step, the group of the
  decision that unblocks it, its entry once proposed, missing facts, findings, the suggested correction for
  `fix shortage`, and the failing conditions of automatic acceptance. nil once the operation is accepted."
  {:malli/schema [:=> [:cat Facts proposals/Operation] [:maybe Classification]]}
  [facts {:keys [source-operation-id] :as operation}]
  (when-not (contains? (:acceptances facts) source-operation-id)
    (let [{:keys [lines entry proposal own result missing] :as evaluated} (evaluate facts operation)
          fails (failing facts operation evaluated)
          done (finished facts operation lines)
          [step grp] (if done [done (group done nil)] (table facts operation evaluated fails))
          shortage (when (= "fix shortage" step) (correction (assoc facts :history (:history evaluated)) own result))]
      (cond-> {:next-step          step
               :group              grp
               :missing            missing
               :findings           (or (:findings proposal) [])
               :failing-conditions fails}
        entry (assoc :entry-id (:entry-id entry))
        shortage (assoc :suggested-correction shortage)))))


(defn eligible?
  "Board 45 slice 3: whether intake accepts the operation without a person (its next step is `automatic`)."
  {:malli/schema [:=> [:cat Facts proposals/Operation] :boolean]}
  [facts operation]
  (= "automatic" (:next-step (classify facts operation))))
