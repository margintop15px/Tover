(ns tover.marketplace.provenance
  "Board chapter 44 · What inventory must know of the marketplace: pending cutover entries and the baselines their
  acceptance establishes, with the pure rules saving and accepting inventory apply to them.

  Chapter 45 adds the imported operations: the pending entry a source operation became (`::imports`), its acceptance
  (`::source-acceptances`) and operations set aside (`::asides`), each keyed by source operation id.

  Depends on no other marketplace namespace, so inventory may require it. Store: both models live in `:catalogue`;
  ids and instants are text, an observation is JSON text. A cutover entry is an ordinary pending entry whose id
  has a `::cutover-entries` row; discarding the entry deletes the row and frees its scope. A baseline's id is
  `<connection>|<item>|<warehouse>`: at most one per scope."
  (:require
    [bel.core :as bel :refer [defevent defmodel defreadmodel]]
    [bel.runtime :as runtime]
    [bel.store :as store]
    [charred.api :as charred]
    [clojure.string :as str]
    [tover.catalogues.records :as records :refer [Id]]
    [tover.shared.i18n :as i18n])
  (:import
    [java.time Instant OffsetDateTime]
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


;; Board chapter 45 · Imported operations: which pending entry a source operation became, which accepted record it
;; became, and which operations a member set aside. Each row's id is the source operation's.


(def SourceOperationId
  [:string {:min 1 :max 700}])


(def Import
  "Board `Imported Operation Proposed`: the source operation's revision and the shared entry it became; `entry-revision`
  the entry revision the connector last saved, `missing` the missing facts as JSON text, `source-conflict` a newer
  revision that differs from a member's edit, `mappings-used` the line keys and mapping revisions of the proposal
  (JSON text), which its acceptance records. `reflected-revision` is the source revision the connector's entry was
  saved from: `revision` itself, except after a source conflict (absent on rows written before plan 5)."
  [:map
   [:source-operation-id SourceOperationId]
   [:connection-id Ref]
   [:revision [:int {:min 1}]]
   [:reflected-revision {:optional true} [:int {:min 1}]]
   [:entry-id Id]
   [:entry-revision [:int {:min 1}]]
   [:missing :string]
   [:source-conflict :boolean]
   [:mappings-used {:optional true} :string]
   [:proposed-at At]])


(def SourceAcceptance
  "A source operation is accepted at most once: the revision accepted, its record, who accepted it, whether a member
  edited the entry, and the mappings its lines used (JSON text). `revision` is the import row's latest revision;
  `reflected-revision` the source revision the accepted entry reflects (see `reflected-revision`)."
  [:map
   [:source-operation-id SourceOperationId]
   [:connection-id {:optional true} Ref]
   [:revision [:int {:min 1}]]
   [:reflected-revision {:optional true} [:int {:min 1}]]
   [:record-id Id]
   [:accepted-by Ref]
   [:edited :boolean]
   [:mappings-used :string]
   [:accepted-at At]])


(def Aside
  "Board 45 slice a `Source Operation Set Aside`: an operation a member set aside (`aside` false once brought back),
  with the pending entry setting aside removed."
  [:map
   [:source-operation-id SourceOperationId]
   [:connection-id {:optional true} Ref]
   [:revision {:optional true} [:int {:min 1}]]
   [:aside {:optional true} :boolean]
   [:reason {:optional true} :string]
   [:removed-entry-id {:optional true} Id]
   [:set-by {:optional true} Ref]
   [:set-at {:optional true} At]])


(def SourceCorrection
  "Board 45 slice 8 `Source Correction Proposed`, one row per accepted record (`record-id`): the source revision last
  evaluated, the pending proposal `entry-id` of kind `amendment` or `cancellation`, the connector's last saved entry
  revision, `source-conflict` when that revision differs from a member's edit, and `reflected-revision`, the source
  revision the connector's entry was saved from. `accepted-revision` is the source revision of the last correction a
  member accepted: what the record reflects from then on."
  [:map
   [:record-id Id]
   [:source-operation-id SourceOperationId]
   [:connection-id Ref]
   [:revision [:int {:min 1}]]
   [:reflected-revision {:optional true} [:int {:min 1}]]
   [:entry-id Id]
   [:entry-revision {:optional true} [:int {:min 1}]]
   [:kind {:optional true} [:enum "amendment" "cancellation"]]
   [:source-conflict {:optional true} :boolean]
   [:proposed-at {:optional true} At]
   [:accepted-revision {:optional true} [:int {:min 1}]]])


(defevent imported-operation-proposed {:schema Import})
;; Intake withdraws an unedited entry whose operation is no longer resolvable: the entry is discarded beside it.
(defevent imported-operation-withdrawn {:schema [:map [:source-operation-id SourceOperationId] [:entry-id Id]]})
(defevent source-operation-accepted {:schema SourceAcceptance})
(defevent source-operation-set-aside {:schema Aside})
(defevent source-correction-proposed {:schema SourceCorrection})
;; The source states again what was accepted: an unedited proposal is withdrawn at that revision, its entry
;; discarded beside it.
(defevent source-correction-withdrawn {:schema [:map [:record-id Id] [:entry-id Id] [:revision [:int {:min 1}]]
                                                [:kind {:optional true} :string] [:source-conflict {:optional true} :boolean]]})
;; A member accepted the proposal: the record reflects its source revision from then on.
(defevent source-correction-accepted {:schema [:map [:record-id Id] [:connection-id Ref] [:accepted-revision [:int {:min 1}]]]})


(defmodel imports
  {:id     :source-operation-id :store :catalogue :schema Import :policy ::records/workspace-member
   :events {::imported-operation-proposed  {:set [:connection-id :revision :reflected-revision :entry-id :entry-revision :missing
                                                  :source-conflict :mappings-used :proposed-at]}
            ::imported-operation-withdrawn {:delete true}}})


(defmodel source-acceptances
  {:id     :source-operation-id :store :catalogue :schema SourceAcceptance :policy ::records/workspace-member
   :events {::source-operation-accepted {:create [:connection-id :revision :reflected-revision :record-id :accepted-by :edited
                                                  :mappings-used :accepted-at]}}})


(defmodel asides
  {:id     :source-operation-id :store :catalogue :schema Aside :policy ::records/workspace-member
   :events {::source-operation-set-aside {:set [:connection-id :revision :aside :reason :removed-entry-id :set-by :set-at]}}})


(defmodel source-corrections
  {:id     :record-id :store :catalogue :schema SourceCorrection :policy ::records/workspace-member
   :events {::source-correction-proposed  {:set [:source-operation-id :connection-id :revision :reflected-revision :entry-id
                                                 :entry-revision :kind :source-conflict :proposed-at]}
            ;; Kept, with no kind: a discarded proposal is not proposed again for the same revision.
            ::source-correction-withdrawn {:set [:revision :kind :source-conflict]}
            ::source-correction-accepted  {:set [:connection-id :accepted-revision]}}})


(defn reflected-revision
  "The source revision an import row or a source acceptance reflects; a row written before plan 5 has no
  `reflected-revision`, and then its `revision` is it."
  {:malli/schema [:=> [:cat :map] [:int {:min 1}]]}
  [row]
  (or (:reflected-revision row) (:revision row)))


(defn import-entry-id
  "The shared entry a source operation becomes: `import:<source-operation-id>`."
  {:malli/schema [:=> [:cat :string] :string]}
  [source-operation-id]
  (str "import:" source-operation-id))


(defn shipment-label
  "Board 46 · \"Posting P1\" or \"Return R2\": a posting or return by its number at the marketplace, read from its
  source operation id (`<connection>:<kind>:<number>`); never its buyer. Any other id is shown as it is."
  {:malli/schema [:=> [:cat :string] :string]}
  [source-operation-id]
  (or (some (fn [[kind label]]
              (let [marker (str ":" kind ":")]
                (when-let [at (str/index-of source-operation-id marker)]
                  (str (i18n/t label) " " (subs source-operation-id (+ at (count marker)))))))
            [["fbs-posting" "Posting"] ["fbo-posting" "Posting"] ["return" "Return"] ["rfbs-return" "Return"]])
      source-operation-id))


(defn import-entry-id?
  "Whether a shared entry id is an imported operation's."
  {:malli/schema [:=> [:cat [:maybe :string]] :boolean]}
  [entry-id]
  (boolean (some-> entry-id (str/starts-with? "import:"))))


(defn import-source
  "The source operation an import entry id names."
  {:malli/schema [:=> [:cat :string] :string]}
  [entry-id]
  (subs entry-id (count "import:")))


(defn correction-entry-id
  "Board 45 slice 8 · the pending proposal against an accepted record: `correction:<source-operation-id>@<record
  revision>`, never `import:`, so it may amend and cancel."
  {:malli/schema [:=> [:cat :string [:int {:min 1}]] :string]}
  [source-operation-id record-revision]
  (str "correction:" source-operation-id "@" record-revision))


(defn correction-entry-id?
  "Whether a shared entry id is a source correction's."
  {:malli/schema [:=> [:cat [:maybe :string]] :boolean]}
  [entry-id]
  (boolean (some-> entry-id (str/starts-with? "correction:"))))


(defn correction-source
  "The source operation a correction entry id names."
  {:malli/schema [:=> [:cat :string] :string]}
  [entry-id]
  (subs entry-id (count "correction:") (or (str/last-index-of entry-id "@") (count entry-id))))


(defn move-entry-id?
  "Board 45 slice c · whether a shared entry id is a detected move's pending Transfer, `move:<connection>|<item>|<last
  agreeing run>|<from>|<to>`: an ordinary transfer, never `import:` or `correction:`."
  {:malli/schema [:=> [:cat [:maybe :string]] :boolean]}
  [entry-id]
  (boolean (some-> entry-id (str/starts-with? "move:"))))


(defn move-connection
  "The connection a detected move's entry id names."
  {:malli/schema [:=> [:cat :string] :string]}
  [entry-id]
  (let [key (subs entry-id (count "move:"))]
    (subs key 0 (or (str/index-of key "|") (count key)))))


(def Facts
  "`imports` and `source-acceptances` (by source operation id) are those of pending import entries; `corrections`
  (by record id) those of pending correction entries."
  [:map [:cutover-entries [:map-of :string CutoverEntry]] [:baselines [:map-of :string Baseline]]
   [:imports {:optional true} [:map-of :string Import]] [:source-acceptances {:optional true} [:map-of :string SourceAcceptance]]
   [:corrections {:optional true} [:map-of :string SourceCorrection]]])


(defn- rows-by-id
  "Rows of `model-id` by id in the scope's workspace; missing ids are left out."
  {:malli/schema [:=> [:cat :map :qualified-keyword [:sequential :string]] [:map-of :string :map]]}
  [context model-id ids]
  (if (empty? ids)
    {}
    (let [definition (bel/get-model model-id)]
      (runtime/with-store context :catalogue :repeatable-read
        (fn [{:keys [tx scope] :as context}]
          (let [adapter (store/store context :catalogue)]
            (into {} (keep (fn [id] (some->> (store/-load adapter tx (store/store-scope scope) definition id) (vector id))))
                  (distinct ids))))))))


(defmethod runtime/coeffect ::facts
  ;; `:cutover-entries` holds the rows whose shared entry is still pending: an accepted entry keeps its row, and its
  ;; scope is then the baseline's (or, for another connection, history), never pending again. With `entry-ids`, only
  ;; those entries are considered, each row loaded by id: what a review of exactly that selection reads.
  [_ context {:keys [entry-ids]}]
  (let [entries (if entry-ids
                  (vals (rows-by-id context :tover.inventory.entries.model/entries entry-ids))
                  (records/all-records context :tover.inventory.entries.model/entries :entry-id))
        pending (into #{} (keep #(when (= "pending" (:status %)) (:entry-id %))) entries)
        sources (into #{} (comp (filter import-entry-id?) (map import-source)) pending)
        fixed   (into #{} (comp (filter correction-entry-id?) (map (comp import-entry-id correction-source))) pending)
        of      (fn [model id-key ids]
                  (if entry-ids
                    (rows-by-id context model (vec ids))
                    (into {} (comp (filter (comp ids id-key)) (map (juxt id-key identity))) (records/all-records context model id-key))))]
    (cond-> {:cutover-entries (of ::cutover-entries :entry-id pending)
             :baselines       (into {} (map (juxt :baseline-id identity)) (records/all-records context ::baselines :baseline-id))}
      ;; Only while import entries are pending.
      (seq sources) (assoc :imports (of ::imports :source-operation-id sources)
                           :source-acceptances (of ::source-acceptances :source-operation-id sources))
      ;; Only while correction entries are pending.
      (seq fixed) (assoc :corrections (of ::source-corrections :record-id fixed)))))


(defn import-row
  "The import row of a pending import entry, or nil."
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :string]] [:maybe Import]]}
  [facts entry-id]
  (when (import-entry-id? entry-id)
    (let [row (get-in facts [:imports (import-source entry-id)])]
      (when (= entry-id (:entry-id row)) row))))


(defn correction-row
  "The source correction row of a pending correction entry, or nil."
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :string]] [:maybe SourceCorrection]]}
  [facts entry-id]
  (when (correction-entry-id? entry-id)
    (let [row (get-in facts [:corrections (import-entry-id (correction-source entry-id))])]
      (when (= entry-id (:entry-id row)) row))))


(defn entry-prefix
  "What every cutover entry id of a connection starts with: `cutover:<connection>|`."
  {:malli/schema [:=> [:cat :string] :string]}
  [connection-id]
  (str "cutover:" connection-id "|"))


(defn cutover-entry?
  "Whether a shared entry id is a cutover entry's, of any connection."
  {:malli/schema [:=> [:cat [:maybe :string]] :boolean]}
  [entry-id]
  (boolean (some-> entry-id (str/starts-with? "cutover:"))))


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
  "Effects marking each connection's operations for evaluation again; the effect is named, not required.
  Acceptance fails at planning (\"Undeclared effect\") unless `tover.marketplace.sync.model` is loaded."
  {:malli/schema [:=> [:cat [:sequential [:maybe :string]]] [:vector [:tuple :qualified-keyword :map]]]}
  [connection-ids]
  (mapv #(vector :tover.marketplace.sync.model/mark-dirty {:connection-id %}) (sort (set (remove nil? connection-ids)))))


(defn record-id
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
  (cond (inst? value) (.truncatedTo ^Instant value ChronoUnit/SECONDS)
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
  cancelled. An import entry never amends or cancels: its acceptance must record the source operation."
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :map] :map] [:maybe [:map [:field :keyword] [:message :string]]]]}
  [facts entry-before {:keys [entry-id amends cancel occurred-at lines]}]
  (let [target (when amends (of-record facts (record-id amends)))
        row    (or (get-in facts [:cutover-entries (:entry-id entry-before)]) target)]
    (cond
      (and (import-entry-id? entry-id) (or amends cancel))
      {:field (if cancel :cancel :amends) :message "An imported operation cannot amend or cancel an accepted record."}
      (and target cancel) {:field :cancel :message "A cutover baseline cannot be cancelled."}
      (and row (not cancel)) (when-let [field (moved-field row occurred-at (or lines []))]
                               {:field field :message fixed}))))


(defn- import-problems
  "Board `Accept Reviewed Inventory Changes` § Imported entries: a line of an imported operation is strictly after
  the baseline its connection holds for the line's stock item and warehouse (a pair the connection holds no baseline
  for has no cutover to be before), and a source operation is accepted at most once."
  {:malli/schema [:=> [:cat [:maybe :map] [:sequential :map] [:sequential :map]] [:sequential :map]]}
  [facts lines picked]
  (concat
    (for [{:keys [entry-id]} picked
          :let               [row (import-row facts entry-id)]
          :when              (and row (contains? (:source-acceptances facts) (import-source entry-id)))]
      {:entry-id entry-id :line-id nil :code :already-accepted})
    (for [{:keys [entry-id entry line-id stock-item-id warehouse-id]} lines
          :let [{:keys [connection-id]} (import-row facts entry-id)
                from (some->> (when (and connection-id stock-item-id warehouse-id)
                                (baseline facts connection-id stock-item-id warehouse-id))
                              :instant instant)
                at (instant (:occurred-at entry))]
          :when (and from at (not (.isAfter ^Instant at ^Instant from)))]
      {:entry-id entry-id :line-id line-id :code :before-cutover})))


(defn acceptance-problems
  "Board `Accept Reviewed Inventory Changes`: reviewed `lines` (each with its `:entry`) of a cutover entry or of an
  amendment of a baseline's record keep its instant, item and warehouse; a scope gets one baseline, so it may not
  have one already nor two cutover entries in one selection. A `picked` entry cancelling a baseline's record is
  refused. Imported entries: see `import-problems`."
  {:malli/schema [:=> [:cat [:maybe :map] [:sequential :map] [:sequential :map]] [:vector :map]]}
  [facts lines picked]
  (let [scope-of #(when-let [row (get-in facts [:cutover-entries %])] ((juxt :connection-id :stock-item-id :warehouse-id) row))
        shared   (->> (distinct (keep :entry-id lines))
                      (group-by scope-of)
                      (keep (fn [[scope ids]] (when (and scope (< 1 (count ids))) ids)))
                      (apply concat)
                      set)]
    (vec (distinct
           (concat
             (for [{:keys [entry-id target cancel]} picked
                   :when (and cancel (of-record facts (:record-id target)))]
               {:entry-id entry-id :line-id nil :code :baseline-cancelled})
             (for [{:keys [entry-id entry] :as line} lines
                   :let [row (get-in facts [:cutover-entries entry-id])
                         target (of-record facts (get-in entry [:target :record-id]))
                         fixed-row (or row target)
                         code (cond (and fixed-row (moved-field fixed-row (:occurred-at entry) [line])) :cutover-date-fixed
                                    (and row (or (shared entry-id)
                                                 (baseline facts (:connection-id row) (:stock-item-id row) (:warehouse-id row))))
                                    :baseline-exists)]
                   :when code]
               {:entry-id entry-id :line-id nil :code code})
             (import-problems facts lines picked))))))


(defn acceptance-events
  "One `Cutover Baseline Established` per accepted cutover entry of `picked`, naming the record its acceptance
  `records` create, established `now`; one `Source Operation Accepted` per accepted import entry, by `actor`,
  edited when the entry's revision is not the one the connector last saved, reflecting the source revision its entry
  was saved from; one `Source Correction Accepted` per accepted correction entry."
  {:malli/schema [:=> [:cat [:maybe :map] [:sequential :map] [:sequential :map] :any inst?] [:vector :map]]}
  [facts picked records actor now]
  (let [record-of (fn [entry-id] (some #(when (and (= entry-id (:record-id %)) (not (:cancelled %))) %) records))]
    (vec (concat
           (for [{:keys [entry-id]} (sort-by :entry-id picked)
                 :let               [{:keys [connection-id stock-item-id warehouse-id] :as row} (get-in facts [:cutover-entries entry-id])
                                     record (record-of entry-id)]
                 :when              (and row record)]
             {:type ::cutover-baseline-established
              :data {:baseline-id    (baseline-id connection-id stock-item-id warehouse-id) :connection-id connection-id
                     :stock-item-id  stock-item-id :warehouse-id warehouse-id
                     :instant        (:instant row) :record-id (:record-id record)
                     :established-at (str now)}})
           (for [{:keys [entry-id revision]} (sort-by :entry-id picked)
                 :let [row (import-row facts entry-id) record (record-of entry-id)]
                 :when (and row record)]
             {:type ::source-operation-accepted
              :data {:source-operation-id (:source-operation-id row)            :connection-id (:connection-id row) :revision (:revision row)
                     :reflected-revision  (reflected-revision row)
                     :record-id           (:record-id record)                   :accepted-by   (str actor)
                     :edited              (not= revision (:entry-revision row))
                     :mappings-used       (or (:mappings-used row) "[]")        :accepted-at   (str now)}})
           (for [{:keys [entry-id amends]} (sort-by :entry-id picked)
                 :let [row (correction-row facts entry-id)]
                 :when (and row amends (some #(= amends (:amends %)) records))]
             {:type ::source-correction-accepted
              :data {:record-id (:record-id row) :connection-id (:connection-id row) :accepted-revision (reflected-revision row)}})))))


(defn discard-events
  "Board 45 slice a, Discarded imported entry (roadmap decision 2): a member's discard of a pending import entry sets
  its operation aside in the same commit and drops its import row; nothing for any other entry."
  {:malli/schema [:=> [:cat [:maybe :map] :string :any :any] [:vector :map]]}
  [facts entry-id actor now]
  (if-let [{:keys [source-operation-id connection-id revision]} (import-row facts entry-id)]
    [{:type ::source-operation-set-aside
      :data {:source-operation-id source-operation-id :connection-id connection-id :revision revision  :aside true
             :removed-entry-id    entry-id            :set-by        (str actor)   :set-at   (str now)}}
     {:type ::imported-operation-withdrawn :data {:source-operation-id source-operation-id :entry-id entry-id}}]
    []))


(defn connection-of
  "The connection a pending import, correction or detected move entry came from, or nil."
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :string]] [:maybe :string]]}
  [facts entry-id]
  (if (move-entry-id? entry-id)
    (move-connection entry-id)
    (:connection-id (or (import-row facts entry-id) (correction-row facts entry-id)))))


(defn review-facts
  "What a review of `picked` with reviewed `lines` saw of imports: the selected entries' import rows and source
  acceptances, and the baselines of the lines' stock items and warehouses."
  {:malli/schema [:=> [:cat [:maybe :map] [:sequential :map] [:sequential :map]] :any]}
  [facts lines picked]
  (let [sources (keep #(some-> (import-row facts (:entry-id %)) :source-operation-id) picked)
        pairs   (set (map (juxt :stock-item-id :warehouse-id) lines))]
    [(sort-by :source-operation-id (keep #(get-in facts [:imports %]) sources))
     (sort-by :source-operation-id (keep #(get-in facts [:source-acceptances %]) sources))
     (sort-by :baseline-id (filter #(pairs [(:stock-item-id %) (:warehouse-id %)]) (vals (:baselines facts))))]))


;; Board `Cutover Review and Resulting Corrections` (slice 3): part of the ordinary Review of chapter 17.


(def ReviewRow
  "One pending cutover line: the frozen observation, the proposed absolute count and its difference against Tover's
  balance at the instant as frozen at proposal (absent without history, then the difference is the whole count). The
  notes say \"at proposal\": history accepted later is not in it."
  [:map
   [:entry-id Id] [:line-id Id] [:stock-item-id Id] [:warehouse-id Id] [:connection-id Ref]
   [:observed-quantity {:optional true} :int] [:proposed-quantity :int] [:balance {:optional true} :int] [:difference :int]])


(defn observation
  "A frozen observation as a map; nil when absent or unreadable."
  {:malli/schema [:=> [:cat [:maybe :string]] [:maybe :map]]}
  [text]
  (when-not (str/blank? text)
    (try (charred/read-json text :key-fn keyword) (catch Exception _ nil))))


(defn review-rows
  "The pending entries of `pending` that have a cutover row in `rows`, one row per counted line."
  {:malli/schema [:=> [:cat [:map-of :string :map] [:sequential :map]] [:vector ReviewRow]]}
  [rows pending]
  (vec (for [{:keys [entry-id lines]} pending
             :let [{:keys [connection-id] :as row} (get rows entry-id)]
             :when row
             {:keys [line-id stock-item-id warehouse-id quantity]} lines
             :when (int? quantity)
             :let [{:keys [observed-quantity balance]} (observation (:observation row))]]
         (cond-> {:entry-id      entry-id      :line-id           line-id  :stock-item-id stock-item-id               :warehouse-id warehouse-id
                  :connection-id connection-id :proposed-quantity quantity :difference    (- quantity (or balance 0))}
           (int? observed-quantity) (assoc :observed-quantity observed-quantity)
           (int? balance) (assoc :balance balance)))))


(defreadmodel cutover-review
  {:input  [:map]
   :output [:map [:rows [:vector ReviewRow]] [:baseline-records {:optional true} [:vector :string]]]
   :policy ::records/workspace-member
   :cofx   [[:provenance ::facts (constantly {})] [:pending :tover.inventory.entries.model/pending (constantly {})]]}
  [{:keys [provenance pending]} _]
  ;; `baseline-records`: the accepted records baselines name, whose date and warehouse stay fixed.
  {:rows             (review-rows (:cutover-entries provenance) pending)
   :baseline-records (vec (sort (distinct (keep :record-id (vals (:baselines provenance))))))})
