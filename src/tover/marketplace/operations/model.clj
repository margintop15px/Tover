(ns tover.marketplace.operations.model
  "Board chapter 45 slices 1 and 2 · Notice a new source operation and Propose the imported operation.

  `Marketplace Operation Intake` evaluates every source operation of a marked connection (sync batches, mappings,
  baselines, resumes and its own saves mark it, and inventory edits reach it through the sweep; every connection is swept once a minute) and acts as the
  connector. An operation becomes a pending shared entry `import:<source-operation-id>`, saved by the connector,
  only while it is resolvable: a proposal exists and its next step is none of `unresolvable`. An unedited entry
  follows its operation: a newer revision or a changed mapping replaces it, and an operation no longer resolvable
  withdraws it. A member's edit is never overwritten: a newer revision then records a source conflict and the entry
  is kept. Edited means the stored entry's `:saved-by` is not the connector.

  The command decides from its own facts at commit; its input names only what intake read, so a change since then
  is refused as stale and evaluated again in the next round.

  Slices 3 and 4 · `Automatic Acceptance` accepts, as the connector, each pending proposal whose next step is
  `automatic`, in occurrence order per connection. `Accept Imported Operation Automatically` rechecks eligibility on
  the facts of its own commit and plans exactly what `Accept Reviewed Inventory Changes` would, without review
  fingerprint; its acceptance id is fixed by the source operation and revision, so a source revision is accepted at
  most once."
  (:require
    [bel.core :as bel :refer [defcommand defprocessor defreadmodel]]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [com.brunobonacci.mulog :as mu]
    [tover.catalogues.records :as records]
    [tover.inventory.acceptance.model :as acceptance]
    [tover.inventory.entries.model :as entries]
    [tover.marketplace.operations.proposals :as proposals]
    [tover.marketplace.operations.steps :as steps]
    [tover.marketplace.products.model :as products]
    [tover.marketplace.provenance :as provenance]
    [tover.marketplace.sync.model :as sync]
    [tover.marketplace.sync.observations :as observations]
    [tover.marketplace.warehouses.model :as warehouses]
    [tover.workspaces.currency.model :as currency])
  (:import
    [java.time Instant]))


(def unresolvable
  "Next steps of an operation that holds no entry: nothing to propose, or a match, baseline or person is missing."
  #{"no effect" "history" "set cutover" "match product" "confirm match again" "match warehouse" "set aside"})


(def messages
  {:not-found         "Unknown source operation."
   :reason-too-long   "Enter at most 500 characters."
   :not-permitted     "Not permitted"
   :connection-paused "Connection paused"
   :stale             "The operation, a mapping or its entry changed since it was read."
   :already-accepted  "This operation is already accepted."
   :set-aside         "This operation is set aside."
   :not-resolvable    "A match or baseline is missing."
   :still-resolvable  "This operation is still resolvable."
   :not-eligible      "This operation waits for a member."
   :registers         "Automatic acceptance never registers or changes catalogue records."})


(defn- fail!
  {:malli/schema [:=> [:cat :keyword] :nil]}
  [code]
  (records/fail! code (messages code) {}))


;; Facts


(defn- by-source
  {:malli/schema [:=> [:cat [:sequential :map]] [:map-of :string :map]]}
  [rows]
  (into {} (map (juxt :source-operation-id identity)) rows))


(defn load-facts
  "Everything `steps/classify` and the decision read for one connection."
  {:malli/schema [:=> [:cat :map :string] steps/Facts]}
  [context connection-id]
  (let [input      {:connection-id connection-id}
        provenance (runtime/coeffect ::provenance/facts context {})
        history    (runtime/coeffect ::acceptance/history context {})]
    {:connection         (runtime/coeffect ::sync/connection context input)
     :products           (runtime/coeffect ::products/listings context input)
     :mappings           (runtime/coeffect ::products/mappings context input)
     :guard-facts        (runtime/coeffect ::products/catalogue context input)
     :warehouse-mappings (runtime/coeffect ::warehouses/mappings context input)
     ;; As acceptance reads it, so eligibility applies the import rules acceptance applies.
     :provenance         provenance
     :baselines          (:baselines provenance)
     :imports            (by-source (records/all-records context ::provenance/imports :source-operation-id))
     :acceptances        (by-source (records/all-records context ::provenance/source-acceptances :source-operation-id))
     :aside              (by-source (records/all-records context ::provenance/asides :source-operation-id))
     ;; ponytail: scans the workspace's operations; a per-connection query when several large connections exist.
     :operations         (by-source (filter #(= connection-id (:connection-id %))
                                            (records/all-records context ::sync/source-operations :source-operation-id)))
     :catalogue          (runtime/coeffect ::entries/catalogue context {})
     :history            history
     ;; Every operation of the connection is classified: each review replays only the pairs it touches.
     :history-index      (steps/index-history history)
     :entries            (runtime/coeffect ::entries/pending context {})
     :currency           (currency/current (first (records/all-records context ::currency/workspace-currencies :currency-key)))}))


(defmethod runtime/coeffect ::operation
  [_ context {:keys [source-operation-id]}]
  (when source-operation-id
    (get (sync/load-rows context ::sync/source-operations [source-operation-id]) source-operation-id)))


(defmethod runtime/coeffect ::facts
  [_ context {:keys [connection-id]}]
  (when connection-id (load-facts context connection-id)))


(defn command-facts
  "`load-facts` for one operation's command: the same connection-wide facts (listings, mappings, guard catalogue,
  warehouse mappings, catalogue, history, currency), but only the operation and its related one, their import,
  acceptance and aside rows, its own pending entry, and `::provenance/facts` of that entry, each loaded by id. What
  `steps/classify`, `assess` and acceptance of this operation read is the same; the cost no longer grows with the
  connection's other operations and entries."
  {:malli/schema [:=> [:cat :map [:maybe :map]] [:maybe steps/Facts]]}
  [context {:keys [source-operation-id connection-id related-operation]}]
  (when connection-id
    (let [input    {:connection-id connection-id}
          ids      (vec (distinct (remove nil? [source-operation-id related-operation])))
          entry-id (provenance/import-entry-id source-operation-id)
          stored   (get (sync/load-rows context ::entries/entries [entry-id]) entry-id)
          changes  (when (= "pending" (:status stored)) (runtime/coeffect ::entries/changes context {:entry-id entry-id}))
          own      (fn [rows] (into {} (filter #(= connection-id (:connection-id (val %)))) rows))
          prov     (runtime/coeffect ::provenance/facts context {:entry-ids [entry-id]})]
      {:connection         (runtime/coeffect ::sync/connection context input)
       :products           (runtime/coeffect ::products/listings context input)
       :mappings           (runtime/coeffect ::products/mappings context input)
       :guard-facts        (runtime/coeffect ::products/catalogue context input)
       :warehouse-mappings (runtime/coeffect ::warehouses/mappings context input)
       :provenance         prov
       :baselines          (:baselines prov)
       :imports            (sync/load-rows context ::provenance/imports ids)
       :acceptances        (sync/load-rows context ::provenance/source-acceptances ids)
       :aside              (sync/load-rows context ::provenance/asides ids)
       :operations         (own (sync/load-rows context ::sync/source-operations ids))
       :catalogue          (runtime/coeffect ::entries/catalogue context {})
       :history            (runtime/coeffect ::acceptance/history context {})
       :entries            (if (= "pending" (:status stored))
                             [(cond-> (assoc stored :lines (runtime/coeffect ::entries/lines context {:entry-id entry-id}))
                                (seq changes) (assoc :changes changes))]
                             [])
       :currency           (currency/current (first (records/all-records context ::currency/workspace-currencies :currency-key)))})))


(defmethod runtime/coeffect ::command-facts
  [_ context {:keys [operation]}]
  (command-facts context operation))


(defmethod runtime/coeffect ::stored
  ;; The operation's shared entry in any status, with its lines.
  [_ context {:keys [source-operation-id]}]
  (let [entry-id (provenance/import-entry-id source-operation-id)]
    {:entry (get (sync/load-rows context ::entries/entries [entry-id]) entry-id)
     :lines (runtime/coeffect ::entries/lines context {:entry-id entry-id})}))


;; Decision


(defn mapping-revisions
  "The mapping revisions the operation's proposal reads: its lines' product mappings and the connection's
  warehouse mappings (a return's warehouse is prefilled from them)."
  {:malli/schema [:=> [:cat :map :map] [:map [:products [:map-of :string :int]] [:warehouses [:map-of :string :int]]]]}
  [facts operation]
  {:products   (into {} (keep #(when-let [id (:external-product-id %)]
                                 (when-let [n (get-in facts [:mappings id :mapping-revision])] [id n])))
                     (proposals/resolve-lines facts operation))
   :warehouses (into {} (keep (fn [[id m]] (when (:mapping-revision m) [id (:mapping-revision m)]))) (:warehouse-mappings facts))})


(defn mappings-used
  "Board `Source Operation Accepted` § mappings used: each line's key with the mapping revision that resolved it,
  as JSON text."
  {:malli/schema [:=> [:cat :map :map] :string]}
  [facts operation]
  (observations/write-json
    (vec (for [{:keys [line-key external-product-id mapping]} (proposals/resolve-lines facts operation)
               :when (:mapping-revision mapping)]
           {:line-key line-key :external-product-id external-product-id :mapping-revision (:mapping-revision mapping)}))))


(defn- aside?
  {:malli/schema [:=> [:cat :map :string] :boolean]}
  [facts id]
  (let [row (get-in facts [:aside id])]
    (boolean (and row (not (false? (:aside row)))))))


(defn- present
  {:malli/schema [:=> [:cat [:maybe :map]] :map]}
  [m]
  (into {} (remove (comp nil? val)) m))


(defn- comparable
  "What an unedited entry says, to tell a repeat from a change."
  {:malli/schema [:=> [:cat [:maybe :map] [:sequential :map]] :any]}
  [entry lines]
  [(present (select-keys entry [:kind :occurred-at :from-warehouse-id :to-warehouse-id :reason]))
   (mapv #(present (select-keys % [:line-id :stock-item-id :quantity :warehouse-id :unit-price])) lines)])


(defn- entry-data
  {:malli/schema [:=> [:cat :string :map [:maybe :map] :uuid] :map]}
  [entry-id {:keys [kind occurred-at from-warehouse-id to-warehouse-id reason]} stored actor]
  (cond-> {:entry-id entry-id :kind kind :status "pending" :revision (inc (or (:revision stored) 0)) :saved-by actor}
    occurred-at (assoc :occurred-at (Instant/parse occurred-at))
    from-warehouse-id (assoc :from-warehouse-id from-warehouse-id)
    to-warehouse-id (assoc :to-warehouse-id to-warehouse-id)
    reason (assoc :reason reason)))


(defn- proposal-events
  "Saving or replacing an unedited entry, a source conflict over an edited one, or nothing for a repeat."
  {:malli/schema [:=> [:cat :map :map :map :map :any] [:vector :map]]}
  [facts {:keys [source-operation-id connection-id revision] :as operation} {:keys [entry missing]} {stored :entry lines :lines} now]
  (let [actor    (sync/connector-actor connection-id)
        entry-id (provenance/import-entry-id source-operation-id)
        row      (get-in facts [:imports source-operation-id])
        pending? (= "pending" (:status stored))
        edited?  (and pending? (not= (str (:saved-by stored)) (str actor)))
        missing  (observations/write-json missing)
        used     (mappings-used facts operation)
        import   (fn [entry-revision conflict?]
                   {:type ::provenance/imported-operation-proposed
                    :data {:source-operation-id source-operation-id :connection-id  connection-id  :revision revision
                           :entry-id            entry-id            :entry-revision entry-revision :missing  missing  :source-conflict conflict?
                           :mappings-used       used                :proposed-at    (str now)}})]
    (if edited?
      (if (= revision (:revision row))
        []
        [(import (or (:entry-revision row) (:revision stored)) true)])
      (let [saved     (entry-data entry-id entry stored actor)
            new-lines (:lines entry)]
        (if (and pending? row (= revision (:revision row)) (not (:source-conflict row)) (= missing (:missing row))
                 (= used (:mappings-used row)) (= (comparable stored lines) (comparable saved new-lines)))
          []
          (-> [{:type ::entries/shared-inventory-entry-saved :data saved}]
              (into (entries/line-events entry-id (if pending? lines []) new-lines))
              (conj (import (:revision saved) false))))))))


(defn- withdrawal-events
  {:malli/schema [:=> [:cat :string :map] [:vector :map]]}
  [source-operation-id {stored :entry lines :lines changes :changes}]
  (let [entry-id (provenance/import-entry-id source-operation-id)]
    (-> [{:type ::entries/inventory-draft-discarded :data {:entry-id entry-id :status "discarded" :revision (inc (:revision stored))}}]
        (into (entries/line-events entry-id lines []))
        ;; A member may have made the entry a correction with stock item changes; they go with it.
        (into (entries/change-events entry-id (or changes []) []))
        (conj {:type ::provenance/imported-operation-withdrawn :data {:source-operation-id source-operation-id :entry-id entry-id}}))))


(defn assess
  "What intake does with one operation now: `{:propose events}` (empty for a repeat), `{:withdraw events}` for
  an unedited entry whose operation is no longer resolvable, or `{:refusal code}`."
  {:malli/schema [:=> [:cat steps/Facts :map [:map [:entry [:maybe :map]] [:lines [:sequential :map]]] :any]
                  [:map [:propose {:optional true} [:vector :map]] [:withdraw {:optional true} [:vector :map]]
                   [:refusal {:optional true} :keyword]]]}
  [facts {:keys [source-operation-id connection-id] :as operation} {stored :entry :as entry} now]
  (let [accepted? (or (contains? (:acceptances facts) source-operation-id) (= "accepted" (:status stored)))
        step      (when-not accepted? (:next-step (steps/classify facts operation)))
        proposal  (when-not accepted? (proposals/proposal facts operation))
        unedited? (and (= "pending" (:status stored)) (= (str (:saved-by stored)) (str (sync/connector-actor connection-id))))]
    (cond
      accepted? {:refusal :already-accepted}
      (aside? facts source-operation-id) {:refusal :set-aside}
      (or (nil? proposal) (contains? unresolvable step)) (if unedited?
                                                           {:withdraw (withdrawal-events source-operation-id entry)}
                                                           {:refusal :not-resolvable})
      :else {:propose (proposal-events facts operation proposal entry now)})))


;; Commands


(def ProposeImportedOperation
  "What intake read: the operation's revision and, optionally, the mapping revisions its proposal used and its
  entry's revision (0 without one). Anything changed since is stale."
  [:map
   [:source-operation-id provenance/SourceOperationId]
   [:revision [:int {:min 1}]]
   [:product-mapping-revisions {:optional true} [:map-of :string :int]]
   [:warehouse-mapping-revisions {:optional true} [:map-of :string :int]]
   [:entry-revision {:optional true} [:int {:min 0}]]])


(defn- checked!
  "The operation, unless unknown, not the actor's connection, paused or changed since intake read it."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [actor operation facts stored]} {:keys [revision product-mapping-revisions warehouse-mapping-revisions entry-revision]}]
  (when-not operation (fail! :not-found))
  (when (not= actor (sync/connector-actor (:connection-id operation))) (fail! :not-permitted))
  (when (not= "active" (get-in facts [:connection :sync-state])) (fail! :connection-paused))
  (let [used (mapping-revisions facts operation)]
    (when (or (not= revision (:revision operation))
              (and product-mapping-revisions (not= product-mapping-revisions (:products used)))
              (and warehouse-mapping-revisions (not= warehouse-mapping-revisions (:warehouses used)))
              (and entry-revision (not= entry-revision (or (get-in stored [:entry :revision]) 0))))
      (fail! :stale)))
  operation)


(def ^:private command-cofx
  [[:operation ::operation (fn [_ input] (select-keys input [:source-operation-id]))]
   [:facts ::command-facts (fn [{:keys [operation]} _] {:operation operation})]
   [:stored ::stored (fn [_ input] (select-keys input [:source-operation-id]))]])


(defn- instant
  {:malli/schema [:=> [:cat :any] [:maybe inst?]]}
  [value]
  (cond (instance? Instant value) value
        (inst? value) (.toInstant ^java.util.Date value)
        (string? value) (Instant/parse value)))


(defcommand propose-imported-operation
  ;; Board `Propose Imported Operation`; the connector acts only for its own connection.
  {:input  ProposeImportedOperation
   :policy ::sync/connector-principal
   :cofx   command-cofx
   :emits  #{::provenance/imported-operation-proposed ::entries/shared-inventory-entry-saved
             ::entries/inventory-entry-line-saved ::entries/inventory-entry-line-removed
             ::entries/inventory-entry-line-barcode-removed ::entries/inventory-entry-line-barcode-added}}
  [{:keys [scope now facts stored] :as context} input]
  (let [operation                 (checked! context input)
        {:keys [propose refusal]} (assess (assoc facts :now (instant now)) operation stored now)]
    (cond
      refusal (fail! refusal)
      (empty? propose) {}
      :else {:events propose
             :fx     (into (records/publish scope) (provenance/dirty-fx [(:connection-id operation)]))})))


(defcommand withdraw-imported-operation
  ;; Intake's withdrawal of an unedited entry whose operation is no longer resolvable (controller ruling).
  {:input  [:map [:source-operation-id provenance/SourceOperationId] [:revision [:int {:min 1}]] [:entry-revision [:int {:min 1}]]]
   :policy ::sync/connector-principal
   :cofx   command-cofx
   :emits  #{::provenance/imported-operation-withdrawn ::entries/inventory-draft-discarded
             ::entries/inventory-entry-line-removed ::entries/inventory-entry-line-barcode-removed}}
  [{:keys [scope now facts stored] :as context} input]
  (let [operation                  (checked! context input)
        {:keys [withdraw refusal]} (assess (assoc facts :now (instant now)) operation stored now)]
    (cond
      withdraw {:events withdraw
                :fx     (into (records/publish scope) (provenance/dirty-fx [(:connection-id operation)]))}
      (= :not-resolvable refusal) {}
      refusal (fail! refusal)
      :else (fail! :still-resolvable))))


;; Slice a · Set an operation aside


(def SetSourceOperationsAside
  "Board `Set Source Operations Aside`: the reviewed rows, each at the revision (and, optionally, the entry revision,
  0 without one) the member saw; `aside` false brings them back."
  [:map
   [:operations [:vector {:min 1} [:map [:source-operation-id provenance/SourceOperationId] [:revision [:int {:min 1}]]
                                   [:entry-revision {:optional true} [:int {:min 0}]]]]]
   [:aside :boolean]
   [:reason {:optional true} [:maybe :string]]])


(defmethod runtime/coeffect ::selected
  ;; Each named operation with its import entry (any status) and lines, and whether it is accepted.
  [_ context {:keys [operations]}]
  (let [ids       (vec (distinct (map :source-operation-id operations)))
        found     (sync/load-rows context ::sync/source-operations ids)
        accepted  (sync/load-rows context ::provenance/source-acceptances ids)
        entry-ids (mapv provenance/import-entry-id ids)
        entries   (sync/load-rows context ::entries/entries entry-ids)]
    (into {} (for [id ids :let [entry-id (provenance/import-entry-id id) entry (get entries entry-id)]]
               [id {:operation (get found id)
                    :accepted? (or (contains? accepted id) (= "accepted" (:status entry)))
                    :entry     entry
                    :lines     (if (= "pending" (:status entry)) (runtime/coeffect ::entries/lines context {:entry-id entry-id}) [])
                    :changes   (if (= "pending" (:status entry)) (runtime/coeffect ::entries/changes context {:entry-id entry-id}) [])}]))))


(defn set-aside-events
  "Setting aside removes the pending entry, edited or not, in the same commit; bringing back only records it, and
  intake proposes the operation again. Refused when unknown, accepted, or changed since review."
  {:malli/schema [:=> [:cat [:map-of :string :map] :map :any :any] [:vector :map]]}
  [selected {:keys [operations aside reason]} actor now]
  (let [reason (some-> reason str/trim not-empty)]
    (when (and reason (< 500 (count reason))) (fail! :reason-too-long))
    (vec (mapcat
           (fn [{:keys [source-operation-id revision entry-revision]}]
             (let [{:keys [operation accepted? entry lines changes]} (get selected source-operation-id)
                   pending? (= "pending" (:status entry))]
               (when-not operation (fail! :not-found))
               (when accepted? (fail! :already-accepted))
               (when (or (not= revision (:revision operation))
                         (and entry-revision (not= entry-revision (if pending? (:revision entry) 0))))
                 (fail! :stale))
               (cons {:type ::provenance/source-operation-set-aside
                      :data (cond-> {:source-operation-id source-operation-id :connection-id (:connection-id operation)
                                     :revision            revision            :aside         aside
                                     :set-by              (str actor)         :set-at        (str now)}
                              reason (assoc :reason reason)
                              (and aside pending?) (assoc :removed-entry-id (:entry-id entry)))}
                     (when (and aside pending?) (withdrawal-events source-operation-id {:entry entry :lines lines :changes changes})))))
           (vals (into (sorted-map) (map (juxt :source-operation-id identity)) operations))))))


(defcommand set-source-operations-aside
  ;; Board 45 slice a; any active member, whatever the connection's state. No stock effect.
  {:input  SetSourceOperationsAside
   :policy ::records/workspace-member
   :cofx   [[:selected ::selected (fn [_ input] (select-keys input [:operations]))]]
   :emits  #{::provenance/source-operation-set-aside ::provenance/imported-operation-withdrawn ::entries/inventory-draft-discarded
             ::entries/inventory-entry-line-removed ::entries/inventory-entry-line-barcode-removed
             ::entries/inventory-entry-change-removed ::entries/inventory-entry-change-barcode-removed}}
  [{:keys [scope actor now selected]} input]
  (let [events (set-aside-events selected input actor now)]
    {:events events
     :fx     (into (records/publish scope)
                   (provenance/dirty-fx (keep (comp :connection-id :operation) (vals selected))))}))


;; Slice 1 · Notice a new source operation


(def IntakeItem
  [:map [:workspace-id :string] [:connection-id :string] [:command :qualified-keyword] [:input :map]])


(defn requests
  "What intake does over one connection's facts: one command per operation that changes something, in source
  operation order. A paused connection proposes and withdraws nothing."
  {:malli/schema [:=> [:cat steps/Facts [:map-of :string :map] :string :string inst?] [:vector IntakeItem]]}
  [facts stored workspace-id connection-id now]
  (if (not= "active" (get-in facts [:connection :sync-state]))
    []
    (let [facts (assoc facts :now now)]
      (vec (for [[id operation] (sort-by key (:operations facts))
                 :let           [entry (get stored id {:entry nil :lines []})
                                 {:keys [propose withdraw]} (try (assess facts operation entry now)
                                                                 (catch InterruptedException error (throw error))
                                                                 (catch Exception error
                                                                  ;; One operation that cannot be evaluated never stops the others.
                                                                   (mu/log ::operation-skipped :error-type (.getName (class error)))
                                                                   {}))
                                 entry-revision (or (get-in entry [:entry :revision]) 0)
                                 item (fn [command input]
                                        {:workspace-id workspace-id :connection-id connection-id :command command
                                         :input        (merge {:source-operation-id id :revision (:revision operation)} input)})]
                 :when          (or (seq propose) withdraw)]
             (if withdraw
               (item ::withdraw-imported-operation {:entry-revision entry-revision})
               (let [{:keys [products warehouses]} (mapping-revisions facts operation)]
                 (item ::propose-imported-operation {:product-mapping-revisions products       :warehouse-mapping-revisions warehouses
                                                     :entry-revision            entry-revision}))))))))


(defn- stored-entries
  "Every operation's import entry with its lines (pending ones only need them), by source operation id."
  {:malli/schema [:=> [:cat :map [:sequential :map]] [:map-of :string :map]]}
  [context pending]
  (let [lines (into {} (map (juxt :entry-id :lines)) pending)]
    (into {} (keep (fn [{:keys [entry-id] :as entry}]
                     (when (provenance/import-entry-id? entry-id)
                       [(provenance/import-source entry-id) {:entry entry :lines (get lines entry-id [])}])))
          (records/all-records context ::entries/entries :entry-id))))


(defn- connection-requests
  {:malli/schema [:=> [:cat :map :uuid :string inst?] [:vector IntakeItem]]}
  [system workspace-id connection-id now]
  (let [context {:bel/system system :scope {:workspace-id workspace-id}}
        facts   (load-facts context connection-id)]
    (requests facts (stored-entries context (:entries facts)) (str workspace-id) connection-id now)))


(defmethod runtime/coeffect ::operations-to-propose
  [_ {:keys [bel/system]} _]
  (if-let [marketplace (get-in system [:resources :marketplace])]
    (let [owned (for [workspace-id (sync/workspace-ids system)
                      connection   (sync/workspace-connections system workspace-id)]
                  [workspace-id (:connection-id connection)])
          now   (if-let [clock (:clock marketplace)] (Instant/ofEpochMilli (clock)) (Instant/now))
          dirty (sync/take-dirty! marketplace :marketplace-operation-intake (set (map second owned)) now)]
      (vec (for [[workspace-id connection-id] owned
                 :when (contains? dirty connection-id)
                 item (try (connection-requests system workspace-id connection-id now)
                           (catch InterruptedException error (throw error))
                           (catch Exception error
                             ;; The minute sweep tries the connection again.
                             (mu/log ::intake-skipped :error-type (or (:type (ex-data error)) (.getName (class error))))
                             []))]
             item)))
    []))


(defprocessor ^{:malli/schema [:=> [:cat IntakeItem] bel/ProcessorRequest]} marketplace-operation-intake
  {:todo ::operations-to-propose}
  [{:keys [workspace-id connection-id command input]}]
  {:type  :command :id command :scope {:workspace-id (parse-uuid workspace-id)}
   :actor (sync/connector-actor connection-id)
   :input input})


;; Slices 3 and 4 · Check automatic-acceptance eligibility, and Accept the imported operation automatically


(def registering
  "What automatic acceptance never emits: catalogue registrations and stock item changes. It confirms no mapping
  either; no mapping event is declared."
  #{:tover.catalogues.products.model/product-registered :tover.catalogues.warehouses.model/warehouse-registered
    :tover.catalogues.suppliers.model/supplier-registered :tover.inventory.stock-items.model/stock-item-registered
    :tover.inventory.stock-items.model/stock-item-barcode-assigned :tover.inventory.stock-items.model/stock-item-updated
    :tover.inventory.stock-items.model/stock-item-moved :tover.inventory.stock-items.model/stock-item-archived
    :tover.inventory.stock-items.model/stock-item-restored :tover.inventory.stock-items.model/stock-item-barcode-removed})


(defn acceptance-id
  "The acceptance an automatic attempt creates, fixed by the source operation and revision: a second attempt at
  the same revision can never accept it again."
  {:malli/schema [:=> [:cat :string :int] :string]}
  [source-operation-id revision]
  (str "auto/" source-operation-id "#" revision))


(def EligibilityRow
  [:map
   [:entry-id :string]
   [:source-operation-id :string]
   [:occurred-at {:optional true} inst?]
   [:eligible :boolean]
   [:failing-conditions [:vector :string]]
   [:revision [:int {:min 1}]]
   [:entry-revision [:int {:min 1}]]])


(defn eligibility
  "Board `Imported Proposals and Acceptance Eligibility`: the connection's pending imported proposals in occurrence
  order (time, then source operation id; one without a time last), each eligible or not with its failing conditions,
  and the revisions an automatic attempt names."
  {:malli/schema [:=> [:cat steps/Facts] [:vector EligibilityRow]]}
  [{:keys [operations imports entries] :as facts}]
  (let [pending (into {} (map (juxt :entry-id identity)) entries)]
    (->> (for [[id {:keys [entry-id]}] imports
               :let                    [operation (get operations id) entry (get pending entry-id)]
               :when                   (and operation entry (= entry-id (provenance/import-entry-id id)))
               :let                    [{:keys [next-step failing-conditions]} (steps/classify facts operation)]
               :when                   next-step]
           (cond-> {:entry-id entry-id :source-operation-id id
                    :eligible (= "automatic" next-step)       :failing-conditions failing-conditions
                    :revision (get-in imports [id :revision]) :entry-revision     (:revision entry)}
             (:occurred-at entry) (assoc :occurred-at (instant (:occurred-at entry)))))
         (sort-by (juxt (comp nil? :occurred-at) :occurred-at :source-operation-id))
         vec)))


(defreadmodel imported-proposals-and-acceptance-eligibility
  {:input  [:map [:connection-id :string]]
   :output [:vector :map]
   :policy ::records/workspace-member
   :cofx   [[:facts ::facts (fn [_ input] (select-keys input [:connection-id]))]
            [:clock ::acceptance/now (constantly {})]]}
  [{:keys [facts clock]} _]
  (mapv #(select-keys % [:entry-id :source-operation-id :occurred-at :eligible :failing-conditions])
        (eligibility (assoc facts :now clock))))


(def AcceptImportedOperationAutomatically
  "Board `Accept Imported Operation Automatically`: the attempt is named by the source operation and revision, and
  exactly one entry at the revision eligibility read."
  [:map
   [:source-operation-id provenance/SourceOperationId]
   [:revision [:int {:min 1}]]
   [:entry-id records/Id]
   [:entry-revision [:int {:min 1}]]])


(defn automatic-acceptance-plan
  "The events and effects of accepting one eligible proposal as the connector, decided on `facts` loaded inside
  the commit (`load-facts`, with its `::provenance/facts`) at `now`. Refused when unknown, not the actor's
  connection, accepted already, stale (the operation or its entry changed, or the entry is gone), not eligible
  (naming the failing conditions), when it would register or change a catalogue record, or as `Accept Reviewed
  Inventory Changes` refuses it."
  {:malli/schema [:=> [:cat :map [:maybe :map] steps/Facts AcceptImportedOperationAutomatically :uuid inst?]
                  [:map [:events [:vector :map]] [:fx [:vector :any]]]]}
  [scope operation facts {:keys [source-operation-id revision entry-id entry-revision]} actor now]
  (when-not operation (fail! :not-found))
  (when (not= actor (sync/connector-actor (:connection-id operation))) (fail! :not-permitted))
  (when (contains? (:acceptances facts) source-operation-id) (fail! :already-accepted))
  (let [facts (assoc facts :now now)
        entry (some #(when (= entry-id (:entry-id %)) %) (:entries facts))]
    (when (or (not= revision (:revision operation)) (not= revision (get-in facts [:imports source-operation-id :revision]))
              (not= entry-id (provenance/import-entry-id source-operation-id)) (not= entry-revision (:revision entry)))
      (fail! :stale))
    (let [{:keys [next-step failing-conditions]} (steps/classify facts operation)]
      (when-not (= "automatic" next-step)
        (records/fail! :not-eligible (messages :not-eligible) {:conditions failing-conditions :next-step next-step})))
    (let [result (acceptance/plan {:scope        scope :now now
                                   :entries      (:entries facts)              :catalogue  (:catalogue facts)
                                   :history      (:history facts)              :provenance (:provenance facts)
                                   :currency-row {:currency (:currency facts)}}
                                  {:acceptance-id (acceptance-id source-operation-id revision)
                                   :selection     [{:entry-id entry-id :revision entry-revision}] :order [entry-id]}
                                  {:actor actor :fingerprint? false})]
      (when (some (comp registering :type) (:events result))
        (records/fail! :not-eligible (messages :registers) {:conditions ["validation"] :next-step "accept"}))
      result)))


(defcommand accept-imported-operation-automatically
  ;; Board 45 slice 4: the validation and events of `Accept Reviewed Inventory Changes`, as the connector, with every
  ;; eligibility condition rechecked on the facts of this commit. Retries of one attempt return its first result.
  {:input  AcceptImportedOperationAutomatically
   :policy ::sync/connector-principal
   :cofx   [[:operation ::operation (fn [_ input] (select-keys input [:source-operation-id]))]
            [:facts ::command-facts (fn [{:keys [operation]} _] {:operation operation})]]
   :emits  #{::acceptance/inventory-changes-accepted ::acceptance/inventory-record-accepted
             ::acceptance/inventory-record-line-accepted ::entries/shared-inventory-entry-accepted
             ::provenance/source-operation-accepted}}
  [{:keys [scope actor now operation facts]} input]
  (automatic-acceptance-plan scope operation facts input actor (instant now)))


(def AcceptanceItem
  [:map [:workspace-id :string] [:connection-id :string] [:input AcceptImportedOperationAutomatically]])


(defn acceptances-to-send
  "Automatic Acceptance over one connection's facts: an attempt for each eligible proposal, in occurrence order.
  Each is rechecked at its commit, after those before it, so a later one validates against what they accepted and a
  refused one blocks none after it. A connection that is not active sends none."
  {:malli/schema [:=> [:cat steps/Facts :string :string] [:vector AcceptanceItem]]}
  [facts workspace-id connection-id]
  (if (not= "active" (get-in facts [:connection :sync-state]))
    []
    (vec (for [row (eligibility facts) :when (:eligible row)]
           {:workspace-id workspace-id :connection-id connection-id
            :input        (select-keys row [:source-operation-id :revision :entry-id :entry-revision])}))))


(defmethod runtime/coeffect ::operations-to-accept
  [_ {:keys [bel/system]} _]
  (if-let [marketplace (get-in system [:resources :marketplace])]
    (let [owned (for [workspace-id (sync/workspace-ids system)
                      connection   (sync/workspace-connections system workspace-id)]
                  [workspace-id (:connection-id connection)])
          now   (if-let [clock (:clock marketplace)] (Instant/ofEpochMilli (clock)) (Instant/now))
          dirty (sync/take-dirty! marketplace :automatic-acceptance (set (map second owned)) now)]
      (vec (for [[workspace-id connection-id] owned
                 :when (contains? dirty connection-id)
                 item (try (let [context {:bel/system system :scope {:workspace-id workspace-id}}]
                             (acceptances-to-send (assoc (load-facts context connection-id) :now now) (str workspace-id) connection-id))
                           (catch InterruptedException error (throw error))
                           (catch Exception error
                             ;; The minute sweep tries the connection again.
                             (mu/log ::acceptance-skipped :connection-id connection-id
                                     :error-type (or (:type (ex-data error)) (.getName (class error))))
                             []))]
             item)))
    []))


(defprocessor ^{:malli/schema [:=> [:cat AcceptanceItem] bel/ProcessorRequest]} automatic-acceptance
  ;; Board `Automatic Acceptance`: wakes on the connection's marks (new proposals, confirmed mappings, new baselines,
  ;; resumed connections, acceptances) and the minute sweep.
  {:todo ::operations-to-accept}
  [{:keys [workspace-id connection-id input]}]
  {:type  :command :id ::accept-imported-operation-automatically :scope {:workspace-id (parse-uuid workspace-id)}
   :actor (sync/connector-actor connection-id)
   :input input})
