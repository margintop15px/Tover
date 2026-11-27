(ns tover.marketplace.operations.corrections
  "Board chapter 45 slices 7 and 8 · Notice a changed or cancelled source operation, and Propose the source correction.

  `Source Correction Intake` evaluates the accepted source operations of a marked connection and acts as the
  connector. A new revision never changes stock by itself: it is compared with the source revision the accepted
  record reflects, both resolved with the mappings that acceptance used, so a member's own edit and a mapping changed
  since are never proposed back. A difference in lines, quantities, warehouses or actual time becomes a pending
  amendment, a withdrawn effect a pending cancellation, and a status, price or descriptive change nothing.

  The proposal is the shared entry `correction:<source-operation-id>@<record revision>` amending the record's latest
  revision; `::provenance/source-corrections` (one row per record) links it to the source revision. An unedited
  proposal follows newer revisions and is withdrawn when the source states again what was accepted; a member's edit is
  kept and a newer revision records a source conflict. A discarded proposal is not proposed again until the next
  source revision. A record cancelled by hand gets no proposal, and the connector's unedited one is withdrawn. Nothing here is ever accepted automatically: a member accepts the entry like any other, and
  `provenance/acceptance-events` then records the source revision the record reflects."
  (:require
    [bel.core :as bel :refer [defcommand defprocessor]]
    [bel.runtime :as runtime]
    [com.brunobonacci.mulog :as mu]
    [tover.catalogues.records :as records]
    [tover.inventory.acceptance.model :as acceptance]
    [tover.inventory.entries.model :as entries]
    [tover.marketplace.operations.proposals :as proposals]
    [tover.marketplace.products.model :as products]
    [tover.marketplace.provenance :as provenance]
    [tover.marketplace.sync.model :as sync]
    [tover.marketplace.sync.observations :as observations]
    [tover.marketplace.warehouses.model :as warehouses]
    [tover.workspaces.currency.model :as currency])
  (:import
    [java.time Instant]))


(def messages
  {:not-found         "Unknown accepted source operation."
   :not-permitted     "Not permitted"
   :connection-paused "Connection paused"
   :stale             "The operation, its record or the proposal changed since it was read."
   :already-cancelled "This operation is already cancelled."})


(defn- fail!
  {:malli/schema [:=> [:cat :keyword] :nil]}
  [code]
  (records/fail! code (messages code) {}))


(defn- instant
  "Stored or typed instant as an instant; nil when absent or unreadable."
  {:malli/schema [:=> [:cat :any] [:maybe inst?]]}
  [value]
  (cond (instance? Instant value) value
        (inst? value) (.toInstant ^java.util.Date value)
        (string? value) (try (Instant/parse value) (catch Exception _ nil))))


;; Difference


(defn- compared
  "What a correction compares of a proposed entry: lines, quantities, warehouses and actual time."
  {:malli/schema [:=> [:cat :map] :any]}
  [{:keys [occurred-at from-warehouse-id to-warehouse-id lines]}]
  [(instant occurred-at) from-warehouse-id to-warehouse-id
   (mapv #(select-keys % [:line-id :stock-item-id :quantity :warehouse-id]) lines)])


(defn difference
  "Board 45 slice 8: what the source states now (`new-proposal`, nil when its effect is withdrawn) against the
  proposal of the revision that was accepted, both resolved with the accepted mappings: nil when lines, quantities,
  warehouses and actual time agree (status, prices, missing facts and findings are not compared), an amendment with
  the new entry, or a cancellation. A changed kind proposes nothing: an amendment keeps its record's kind."
  {:malli/schema [:=> [:cat [:maybe proposals/Proposal] [:maybe proposals/Proposal]]
                  [:maybe [:map [:kind [:enum "amendment" "cancellation"]] [:entry {:optional true} :map]]]]}
  [accepted-proposal new-proposal]
  (cond (nil? accepted-proposal) nil
        (nil? new-proposal) {:kind "cancellation"}
        (not= (get-in accepted-proposal [:entry :kind]) (get-in new-proposal [:entry :kind])) nil
        (= (compared (:entry accepted-proposal)) (compared (:entry new-proposal))) nil
        :else {:kind "amendment" :entry (:entry new-proposal)}))


;; Facts


(defn- by-id
  {:malli/schema [:=> [:cat [:sequential :map] :keyword] [:map-of :string :map]]}
  [rows k]
  (into {} (map (juxt k identity)) rows))


(defn connection-facts
  "What every accepted operation of one connection is compared with: listings, current mappings, warehouse mapping
  revisions, external warehouses (to read an earlier revision back), baselines, history and currency."
  {:malli/schema [:=> [:cat :map :string] :map]}
  [context connection-id]
  (let [input {:connection-id connection-id}
        own   #(filterv (fn [row] (= connection-id (:connection-id row))) %)]
    {:connection          (runtime/coeffect ::sync/connection context input)
     :products            (runtime/coeffect ::products/listings context input)
     :mappings            (runtime/coeffect ::products/mappings context input)
     :warehouse-mappings  (runtime/coeffect ::warehouses/mappings context input)
     :warehouse-revisions (own (records/all-records context ::warehouses/warehouse-mapping-revisions :mapping-revision-id))
     :external-warehouses (by-id (own (records/all-records context ::sync/external-warehouses :external-warehouse-id))
                                 :external-warehouse-id)
     :baselines           (by-id (records/all-records context ::provenance/baselines :baseline-id) :baseline-id)
     :history             (runtime/coeffect ::acceptance/history context {})
     :currency            (currency/current (first (records/all-records context ::currency/workspace-currencies :currency-key)))}))


(defn- warehouses-at
  "The connection's warehouse mappings as they stood at `at`: each one's latest revision changed by then, or the
  current mapping when no revision is stored for it."
  {:malli/schema [:=> [:cat :map [:maybe inst?]] [:map-of :string :map]]}
  [{:keys [warehouse-mappings warehouse-revisions]} at]
  (let [by-warehouse (group-by :external-warehouse-id warehouse-revisions)]
    (into {} (keep (fn [[id mapping]]
                     (if-let [revisions (seq (get by-warehouse id))]
                       (when-let [then (last (sort-by :mapping-revision
                                                      (filter #(let [changed (instant (:changed-at %))]
                                                                 (or (nil? at) (nil? changed) (not (.isAfter ^Instant changed ^Instant at))))
                                                              revisions)))]
                         [id then])
                       [id mapping])))
          warehouse-mappings)))


(defn- accepted-mappings
  "The product mapping revisions the acceptance used, by listing."
  {:malli/schema [:=> [:cat :map :map] [:map-of :string :map]]}
  [context acceptance]
  (let [used (observations/read-json (:mappings-used acceptance))
        ids  (vec (distinct (keep (fn [{:keys [external-product-id mapping-revision]}]
                                    (when (and external-product-id mapping-revision) (products/revision-id external-product-id mapping-revision)))
                                  used)))]
    (by-id (vals (sync/load-rows context ::products/product-mapping-revisions ids)) :external-product-id)))


(defn- stored
  "A shared entry in any status, with its lines and stock item changes while pending."
  {:malli/schema [:=> [:cat :map [:maybe :string]] [:maybe :map]]}
  [context entry-id]
  (when entry-id
    (when-let [entry (get (sync/load-rows context ::entries/entries [entry-id]) entry-id)]
      (let [pending? (= "pending" (:status entry))]
        {:entry   entry
         :lines   (if pending? (runtime/coeffect ::entries/lines context {:entry-id entry-id}) [])
         :changes (if pending? (runtime/coeffect ::entries/changes context {:entry-id entry-id}) [])}))))


(defn operation-facts
  "Everything `assess` reads for one source operation, loaded by id beside `shared` (`connection-facts`): its
  acceptance and correction row, the record's latest revision, the operation at the revision the record reflects,
  the proposals of both resolved with the accepted mappings, and the stored proposals. When the latest proposal was
  discarded, `discarded` is what the source stated at the revision it was saved from."
  {:malli/schema [:=> [:cat :map :map [:maybe :map]] :map]}
  [context shared {:keys [source-operation-id connection-id] :as operation}]
  (let [acceptance (when operation (get (sync/load-rows context ::provenance/source-acceptances [source-operation-id]) source-operation-id))
        record-id  (:record-id acceptance)
        row        (when record-id (get (sync/load-rows context ::provenance/source-corrections [record-id]) record-id))
        latest     (when record-id (entries/latest-revision (:history shared) (acceptance/record-revision-id record-id 1)))
        reflected  (when acceptance (or (:accepted-revision row) (provenance/reflected-revision acceptance)))
        at         (fn [revision]
                     (if (= revision (:revision operation))
                       operation
                       (let [id (str source-operation-id "#" revision)]
                         (some->> (get (sync/load-rows context ::sync/source-revisions [id]) id)
                                  (observations/revision-operation (:external-warehouses shared) connection-id operation)))))
        accepted   (when reflected (at reflected))
        desired-id (when latest (provenance/correction-entry-id source-operation-id (:revision latest)))
        desired    (stored context desired-id)
        ;; A member's discard (a withdrawal clears the row's kind) of the proposal at the record's latest revision.
        discarded  (when (and (:kind row) (= desired-id (:entry-id row)) (= "discarded" (get-in desired [:entry :status])))
                     (at (provenance/reflected-revision row)))
        related    (sync/load-rows context ::sync/source-operations
                                   (vec (distinct (keep :related-operation [operation accepted discarded]))))
        resolving  (-> (select-keys shared [:products :baselines :currency])
                       (assoc :mappings (merge (:mappings shared) (when acceptance (accepted-mappings context acceptance)))
                              :warehouse-mappings (warehouses-at shared (instant (:accepted-at acceptance)))
                              :operations related))]
    {:operation         operation
     :connection        (:connection shared)
     :acceptance        acceptance
     :row               row
     :latest            latest
     :accepted-proposal (when accepted (proposals/proposal resolving accepted))
     :new-proposal      (when operation (proposals/proposal resolving operation))
     :withdrawn?        (boolean (and operation (nil? (proposals/entry-kind operation))))
     :accepted-missing? (boolean (and reflected (nil? accepted)))
     :discarded         (when discarded
                          {:proposal (proposals/proposal resolving discarded) :withdrawn? (nil? (proposals/entry-kind discarded))})
     :desired-id        desired-id
     :desired           desired
     :previous          (when (and (:entry-id row) (not= desired-id (:entry-id row))) (stored context (:entry-id row)))}))


;; Decision


(defn unproposable
  "Why a change of the source cannot be proposed, for the log: `:accepted-revision-missing` when the revision the
  record reflects is not stored, `:kind-changed` when the source now states another kind (an amendment keeps its
  record's kind); else nil."
  {:malli/schema [:=> [:cat :map] [:maybe :keyword]]}
  [{:keys [accepted-missing? accepted-proposal new-proposal]}]
  (cond accepted-missing? :accepted-revision-missing
        (and accepted-proposal new-proposal
             (not= (get-in accepted-proposal [:entry :kind]) (get-in new-proposal [:entry :kind]))) :kind-changed))


(defn- outcome
  "A difference as what it proposes: its kind and the compared content of an amendment."
  {:malli/schema [:=> [:cat [:maybe :map]] :any]}
  [diff]
  (when diff [(:kind diff) (some-> (:entry diff) compared)]))


(defn- pending?
  {:malli/schema [:=> [:cat [:maybe :map]] :boolean]}
  [stored]
  (= "pending" (get-in stored [:entry :status])))


(defn target
  "The pending proposal against the record: at its latest revision or, after a member amended the record, an older one."
  {:malli/schema [:=> [:cat :map] [:maybe :map]]}
  [{:keys [desired previous]}]
  (cond (pending? desired) desired
        (pending? previous) previous))


(defn- present
  {:malli/schema [:=> [:cat [:maybe :map]] :map]}
  [m]
  (into {} (remove (comp nil? val)) m))


(defn- comparable
  "What a saved proposal says, to tell a repeat from a change."
  {:malli/schema [:=> [:cat [:maybe :map] [:sequential :map]] :any]}
  [entry lines]
  [(present (-> (select-keys entry [:kind :from-warehouse-id :to-warehouse-id :reason :store-id :supplier-id :amends :cancel])
                (assoc :occurred-at (instant (:occurred-at entry)))))
   (mapv #(present (select-keys % [:line-id :stock-item-id :quantity :warehouse-id :unit-price])) lines)])


(defn- correction-entry
  "The pending entry a difference becomes against `latest`: a cancellation carries nothing else; an amendment the
  new facts over the record's own (store, supplier, reason, and each line's accepted price). The record's lines are
  in the order of `accepted-proposal`'s, so a line keeps the price accepted under its line id; a line the source
  added takes its proposed price, blank outside the Workspace Currency."
  {:malli/schema [:=> [:cat :string :map :map [:maybe :map] :map :uuid] [:tuple :map [:vector :map]]]}
  [entry-id {:keys [kind entry]} latest stored accepted-proposal actor]
  (let [base {:entry-id entry-id :kind   (:kind latest)               :status "pending" :revision (inc (or (get-in stored [:entry :revision]) 0))
              :saved-by actor    :amends (:record-revision-id latest)}]
    (if (= "cancellation" kind)
      [(assoc base :cancel true) []]
      (let [facts  (:facts latest)
            prices (zipmap (map :line-id (get-in accepted-proposal [:entry :lines])) (map :unit-price (:lines facts)))
            at     (or (instant (:occurred-at entry)) (instant (:occurred-at facts)))]
        [(merge base
                (present (select-keys facts [:store-id :supplier-id :reason]))
                (present {:occurred-at       at
                          :from-warehouse-id (:from-warehouse-id entry)           :to-warehouse-id (:to-warehouse-id entry)
                          :reason            (or (:reason facts) (:reason entry))}))
         (mapv (fn [{:keys [line-id] :as line}]
                 (present (assoc (select-keys line [:line-id :stock-item-id :quantity :warehouse-id])
                                 :unit-price (if (contains? prices line-id) (get prices line-id) (:unit-price line)))))
               (:lines entry))]))))


(defn- withdrawal-events
  {:malli/schema [:=> [:cat :string :map :int] [:vector :map]]}
  [record-id {:keys [entry lines changes]} revision]
  (let [entry-id (:entry-id entry)]
    (-> [{:type ::entries/inventory-draft-discarded :data {:entry-id entry-id :status "discarded" :revision (inc (:revision entry))}}]
        (into (entries/line-events entry-id lines []))
        (into (entries/change-events entry-id (or changes []) []))
        (conj {:type ::provenance/source-correction-withdrawn :data {:record-id record-id :entry-id entry-id :revision revision}}))))


(defn assess
  "What intake does with one accepted operation now (`operation-facts`): `{:events events}`, empty when nothing
  changes, or `{:refusal code}`."
  {:malli/schema [:=> [:cat :map :any] [:map [:events {:optional true} [:vector :map]] [:refusal {:optional true} :keyword]]]}
  [{:keys [operation acceptance row latest accepted-proposal new-proposal withdrawn? discarded desired-id desired] :as facts} now]
  (cond
    (or (nil? operation) (nil? acceptance) (nil? latest)) {:refusal :not-found}
    ;; A record cancelled by hand: the connector's unedited proposal could never be accepted, so it is withdrawn.
    (:cancelled latest) (let [current (target facts)]
                          (if (and current (= (str (get-in current [:entry :saved-by])) (str (sync/connector-actor (:connection-id operation)))))
                            {:events (withdrawal-events (:record-id acceptance) current (:revision operation))}
                            {:refusal :already-cancelled}))
    (nil? accepted-proposal) {:events []}
    :else
    (let [{:keys [source-operation-id connection-id revision]} operation
          record-id (:record-id acceptance)
          actor (sync/connector-actor connection-id)
          diff (when (or new-proposal withdrawn?) (difference accepted-proposal (when-not withdrawn? new-proposal)))
          current (target facts)
          edited? (and current (not= (str (get-in current [:entry :saved-by])) (str actor)))
          seen? (= revision (:revision row))
          proposed (fn [entry-id entry-revision kind conflict? reflected]
                     {:type ::provenance/source-correction-proposed
                      :data (present {:record-id      record-id      :source-operation-id source-operation-id :connection-id   connection-id
                                      :revision       revision       :reflected-revision  reflected           :entry-id        entry-id
                                      :entry-revision entry-revision :kind                kind                :source-conflict conflict?
                                      :proposed-at    (str now)})})
          conflict #(if seen?
                      []
                      [(proposed (get-in current [:entry :entry-id]) (or (:entry-revision row) (get-in current [:entry :revision]))
                                 (or (:kind row) (:kind diff)) true (or (:reflected-revision row) revision))])]
      {:events
       (cond
         edited? (conflict)
         (nil? diff) (if current (withdrawal-events record-id current revision) [])
         (and (nil? current) seen? (= desired-id (:entry-id row))) []
         ;; A discard holds while the source states what was discarded: a status-only revision brings nothing back.
         (and (nil? current) discarded
              (= (outcome diff) (outcome (difference accepted-proposal (when-not (:withdrawn? discarded) (:proposal discarded))))))
         []
         :else
         (let [[entry lines] (correction-entry desired-id diff latest desired accepted-proposal actor)
               same?         (and current (= desired-id (get-in current [:entry :entry-id]))
                                  (= (comparable (:entry current) (:lines current)) (comparable entry lines)))]
           (cond
             (and same? seen?) []
             same? [(proposed desired-id (get-in current [:entry :revision]) (:kind diff) false revision)]
             :else (-> (if (and current (not= desired-id (get-in current [:entry :entry-id])))
                         (vec (butlast (withdrawal-events record-id current revision)))
                         [])
                       (conj {:type ::entries/shared-inventory-entry-saved :data entry})
                       (into (entries/line-events desired-id (if (pending? desired) (:lines desired) []) lines))
                       (conj (proposed desired-id (:revision entry) (:kind diff) false revision))))))})))


;; Command


(def ProposeSourceCorrection
  "Board `Propose Source Correction`: what intake read, the source revision and, optionally, the record revision and
  the pending proposal's entry revision (0 without one). Anything changed since is stale."
  [:map
   [:source-operation-id provenance/SourceOperationId]
   [:revision [:int {:min 1}]]
   [:record-revision {:optional true} [:int {:min 1}]]
   [:entry-revision {:optional true} [:int {:min 0}]]])


(defmethod runtime/coeffect ::operation
  [_ context {:keys [source-operation-id]}]
  (when source-operation-id
    (get (sync/load-rows context ::sync/source-operations [source-operation-id]) source-operation-id)))


(defmethod runtime/coeffect ::facts
  [_ context {:keys [operation]}]
  (when operation
    (operation-facts context (connection-facts context (:connection-id operation)) operation)))


(defn- entry-revision
  {:malli/schema [:=> [:cat :map] :int]}
  [facts]
  (or (get-in (target facts) [:entry :revision]) 0))


(defcommand propose-source-correction
  ;; The connector acts only for its own connection, and never accepts: the proposal waits for a member.
  {:input  ProposeSourceCorrection
   :policy ::sync/connector-principal
   :cofx   [[:operation ::operation (fn [_ input] (select-keys input [:source-operation-id]))]
            [:facts ::facts (fn [{:keys [operation]} _] {:operation operation})]]
   :emits  #{::provenance/source-correction-proposed ::provenance/source-correction-withdrawn
             ::entries/shared-inventory-entry-saved ::entries/inventory-draft-discarded
             ::entries/inventory-entry-line-saved ::entries/inventory-entry-line-removed
             ::entries/inventory-entry-line-barcode-added ::entries/inventory-entry-line-barcode-removed
             ::entries/inventory-entry-change-removed ::entries/inventory-entry-change-barcode-removed}}
  [{:keys [scope actor now operation facts]} {:keys [revision record-revision] :as input}]
  (when-not operation (fail! :not-found))
  (when (not= actor (sync/connector-actor (:connection-id operation))) (fail! :not-permitted))
  (when (not= "active" (get-in facts [:connection :sync-state])) (fail! :connection-paused))
  (when (or (not= revision (:revision operation))
            (and record-revision (not= record-revision (get-in facts [:latest :revision])))
            (and (:entry-revision input) (not= (:entry-revision input) (entry-revision facts))))
    (fail! :stale))
  (let [{:keys [events refusal]} (assess facts now)]
    (cond
      refusal (fail! refusal)
      (empty? events) {}
      :else {:events events
             :fx     (into (records/publish scope) (provenance/dirty-fx [(:connection-id operation)]))})))


;; Slice 7 · Notice a changed or cancelled source operation


(def IntakeItem
  [:map [:workspace-id :string] [:connection-id :string] [:input ProposeSourceCorrection]])


(defn- candidates
  "The connection's accepted operations a correction may concern: a revision other than the one its record
  reflects, or a correction row."
  {:malli/schema [:=> [:cat :map :string] [:vector :map]]}
  [context connection-id]
  (let [operations  (by-id (filter #(= connection-id (:connection-id %))
                                   (records/all-records context ::sync/source-operations :source-operation-id))
                           :source-operation-id)
        corrections (by-id (records/all-records context ::provenance/source-corrections :record-id) :record-id)]
    (vec (for [{:keys [source-operation-id record-id] :as acceptance} (sort-by :source-operation-id
                                                                               (records/all-records context ::provenance/source-acceptances :source-operation-id))
               :let [operation (get operations source-operation-id)
                     row (get corrections record-id)]
               :when (and operation
                          (or row (not= (:revision operation) (provenance/reflected-revision acceptance))))]
           operation))))


(defn- connection-requests
  {:malli/schema [:=> [:cat :map :uuid :string inst?] [:vector IntakeItem]]}
  [system workspace-id connection-id now]
  (let [context {:bel/system system :scope {:workspace-id workspace-id}}
        shared  (connection-facts context connection-id)]
    (if (not= "active" (get-in shared [:connection :sync-state]))
      []
      (vec (for [operation (candidates context connection-id)
                 :let      [facts (try (operation-facts context shared operation)
                                       (catch InterruptedException error (throw error))
                                       (catch Exception error
                                         ;; One operation that cannot be evaluated never stops the others.
                                         (mu/log ::operation-skipped :connection-id connection-id
                                                 :source-operation-id (:source-operation-id operation) :error-type (.getName (class error)))
                                         nil))]
                 :let      [_ (when-let [reason (some-> facts unproposable)]
                                ;; Visible, never proposed; ids only, no buyer data.
                                (mu/log ::correction-not-proposed :reason reason :connection-id connection-id
                                        :source-operation-id (:source-operation-id operation)))]
                 :when     (and facts (seq (:events (assess facts now))))]
             {:workspace-id (str workspace-id) :connection-id connection-id
              :input        {:source-operation-id (:source-operation-id operation)   :revision       (:revision operation)
                             :record-revision     (get-in facts [:latest :revision]) :entry-revision (entry-revision facts)}})))))


(defmethod runtime/coeffect ::corrections-to-propose
  [_ {:keys [bel/system]} _]
  (if-let [marketplace (get-in system [:resources :marketplace])]
    (let [owned (for [workspace-id (sync/workspace-ids system)
                      connection   (sync/workspace-connections system workspace-id)]
                  [workspace-id (:connection-id connection)])
          now   (if-let [clock (:clock marketplace)] (Instant/ofEpochMilli (clock)) (Instant/now))
          dirty (sync/take-dirty! marketplace :source-correction-intake (set (map second owned)) now)]
      (vec (for [[workspace-id connection-id] owned
                 :when (contains? dirty connection-id)
                 item (try (connection-requests system workspace-id connection-id now)
                           (catch InterruptedException error (throw error))
                           (catch Exception error
                             ;; The minute sweep tries the connection again.
                             (mu/log ::intake-skipped :connection-id connection-id
                                     :error-type (or (:type (ex-data error)) (.getName (class error))))
                             []))]
             item)))
    []))


(defprocessor ^{:malli/schema [:=> [:cat IntakeItem] bel/ProcessorRequest]} source-correction-intake
  ;; Board `Source Correction Intake`: wakes on the connection's marks (sync batches, acceptances, saves of
  ;; correction entries) and the minute sweep.
  {:todo ::corrections-to-propose}
  [{:keys [workspace-id connection-id input]}]
  {:type  :command :id ::propose-source-correction :scope {:workspace-id (parse-uuid workspace-id)}
   :actor (sync/connector-actor connection-id)
   :input input})
