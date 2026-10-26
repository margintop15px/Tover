(ns tover.inventory.store
  (:require
    [bel.validation :as validation]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [tover.inventory.affected-query :as affected-query]
    [tover.inventory.completion :as completion]
    [tover.inventory.derivation :as derivation]
    [tover.inventory.history-query :as history-query]
    [tover.inventory.ledger :as ledger]
    [tover.inventory.model :as model]
    [tover.inventory.read-model :as read-model]
    [tover.inventory.schema :as schema]
    [tover.inventory.semantics :as semantics]
    [tover.shared.rdf :as rdf]
    [tover.shared.schema :as shared])
  (:import
    [java.net URLEncoder]
    [java.nio.charset StandardCharsets]
    [java.time Instant]
    [java.util UUID]
    [org.eclipse.rdf4j.common.transaction IsolationLevels]
    [org.eclipse.rdf4j.repository Repository]))


(def ^:private base
  "urn:tover:inventory-v1:")


(defn- graph
  {:malli/schema [:=> [:cat shared/Scope :string] rdf/IRI]}
  [scope suffix]
  (validation/check! shared/Scope scope)
  (rdf/iri (str "urn:tover:workspace:" (:workspace-id scope) ":inventory-v1:" suffix)))


(defn- entity
  {:malli/schema [:=> [:cat shared/Scope :string :string] rdf/IRI]}
  [scope kind id]
  (graph scope (str kind ":" (URLEncoder/encode ^String id StandardCharsets/UTF_8))))


(defn- predicate
  {:malli/schema [:=> [:cat :keyword] rdf/IRI]}
  [field]
  (rdf/iri (str base (name field))))


(defn- now
  {:malli/schema [:=> [:cat] :string]}
  []
  (str (Instant/now)))


(defn- read-payload
  {:malli/schema [:=> [:cat [:maybe :string]] [:maybe :map]]}
  [value]
  (when value
    (edn/read-string {:readers {} :default (fn [tag _]
                                             (schema/fail! :invalid-storage "Unsupported stored data tag." {:tag tag}))}
                     value)))


(defn- payload
  {:malli/schema [:=> [:cat rdf/Connection rdf/Resource rdf/Resource] [:maybe :map]]}
  [conn graph subject]
  (read-payload (rdf/value-at conn graph subject (predicate :payload))))


(defn- put-payload!
  {:malli/schema [:=> [:cat rdf/Connection rdf/Resource rdf/Resource :map] :nil]}
  [conn graph subject data]
  (rdf/remove! conn graph subject (predicate :payload) nil)
  (rdf/add! conn graph subject (predicate :payload) (pr-str data)))


(declare semantic-projection!)


(defn- state
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope] :map]}
  [conn scope]
  (let [stored (or (payload conn (graph scope "state") (graph scope "workspace"))
                   {:ledger-revision 0 :drafts {} :projection (ledger/replay [])})]
    (if (= :read-model (:projection-storage stored))
      (assoc stored :projection
             (if (read-model/ready? conn scope)
               (read-model/read-projection conn scope)
               (semantic-projection! conn scope
                                     (ledger/effective-entries (model/read-history conn (graph scope "history"))))))
      stored)))


(defn- save-light-state!
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :map] :nil]}
  [conn scope data]
  (put-payload! conn (graph scope "state") (graph scope "workspace")
                (-> data (dissoc :projection :generation :epoch) (assoc :projection-storage :read-model))))


(defn- save-state!
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :map] :nil]}
  [conn scope data]
  (when-not (read-model/ready? conn scope)
    (read-model/ensure! conn scope (state conn scope)))
  (read-model/stage-state! conn scope data)
  (save-light-state! conn scope data))


(defn read-snapshot
  "Read the disposable inventory projection and lightweight state in one consistent transaction."
  {:malli/schema [:=> [:cat rdf/Repository shared/Scope [:=> [:cat rdf/Connection :map] :any]] :any]}
  [repo scope read-fn]
  (locking repo
    (rdf/with-transaction [conn {:repo repo :level :snapshot}]
      (let [_ (when-not (read-model/ready? conn scope)
                (read-model/ensure! conn scope (state conn scope)))]
        (read-fn conn (read-model/read-state conn scope))))))


(defn- history
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope] [:vector :map]]}
  [conn scope]
  (model/read-history conn (graph scope "history")))


(defn write-record!
  "Stage an immutable semantic RDF record in the workspace history graph."
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :map] :nil]}
  [conn scope record]
  (model/write-record! conn (graph scope "history")
                       (rdf/iri (str "urn:tover:workspace:" (:workspace-id scope))) record))

(defn- public-snapshot
  {:malli/schema [:=> [:cat :map [:vector :map] shared/Scope] schema/Snapshot]}
  [current records scope]
  (let [drafts   (sort-by (juxt :saved-at :id) (vals (:drafts current)))
        owner-id (some-> (:user-id scope) str)]
    (merge (select-keys current [:ledger-revision])
           (select-keys (:projection current) [:items :rows])
           {:drafts       (vec (remove #(= :composing (:phase %)) drafts))
            :compositions (if owner-id
                            (filterv #(and (= :composing (:phase %)) (= owner-id (:owner-id %))) drafts)
                            [])
            :operations   (ledger/effective-entries records)
            :history      records})))


(defn snapshot
  "Read accepted inventory and the shared workspace draft in one consistent snapshot."
  {:malli/schema [:=> [:cat rdf/Repository shared/Scope] schema/Snapshot]}
  [repo scope]
  (rdf/with-transaction [conn {:repo repo :level :snapshot}]
    (public-snapshot (state conn scope) (history conn scope) scope)))


(defn operation-records
  "Read one operation's revisions for an authorized on-demand detail request."
  {:malli/schema [:=> [:cat rdf/Repository shared/Scope :string] [:vector :map]]}
  [repo scope entity-id]
  (rdf/with-transaction [conn {:repo repo :level :snapshot}]
    (model/read-entity-history conn (graph scope "history") entity-id)))


(defn operation-record
  "Read only the requested historical operation revision."
  {:malli/schema [:=> [:cat rdf/Repository shared/Scope :string :int] [:maybe :map]]}
  [repo scope entity-id revision]
  (rdf/with-transaction [conn {:repo repo :level :snapshot}]
    (model/read-entity-record conn (graph scope "history") entity-id revision)))


(defn- plain-data?
  {:malli/schema [:=> [:cat :any] :boolean]}
  [data]
  (cond
    (map? data) (and (every? keyword? (keys data)) (every? plain-data? (vals data)))
    (vector? data) (every? plain-data? data)
    (or (nil? data) (boolean? data) (string? data) (keyword? data) (integer? data) (decimal? data)) true
    :else false))


(defn- check-draft!
  {:malli/schema [:=> [:cat :map :string [:maybe :int]] schema/Draft]}
  [current id expected-revision]
  (let [draft (get-in current [:drafts id])
        draft (when-not (= :composing (:phase draft)) draft)]
    (when-not (and draft (= expected-revision (:revision draft)))
      (schema/fail! :stale-draft "This draft changed. Review the saved value before applying your edit."
                    {:id id :expected-revision expected-revision :current draft}))
    draft))


(defn- owner-id!
  {:malli/schema [:=> [:cat shared/Scope] schema/Id]}
  [scope]
  (when-not (:user-id scope)
    (schema/fail! :forbidden "An authenticated account is required for private composition." {}))
  (schema/text! (str (:user-id scope)) :user-id))


(defn- check-composition!
  {:malli/schema [:=> [:cat :map schema/Id schema/Id [:maybe :int]] schema/Draft]}
  [current owner-id id expected-revision]
  (let [stored (get-in current [:drafts id])
        draft  (when (and (= :composing (:phase stored)) (= owner-id (:owner-id stored))) stored)]
    (when-not (and draft (= expected-revision (:revision draft)))
      (schema/fail! :stale-draft "This draft changed. Review the saved value before applying your edit."
                    {:id id :expected-revision expected-revision :current draft}))
    draft))


(defn- check-content!
  {:malli/schema [:=> [:cat :any] :nil]}
  [entry]
  (when-not (and (map? entry) (plain-data? entry) (<= (count (pr-str entry)) 1048576))
    (schema/fail! :validation "Draft content must be plain data, at most 1 MiB." {})))


(defn- selected-drafts-by-revision!
  {:malli/schema [:=> [:cat :map schema/Selection] [:vector schema/Draft]]}
  [current selected]
  (when-not (and (vector? selected) (seq selected) (<= (count selected) 500)
                 (= (count selected) (count (set (map :id selected)))))
    (schema/fail! :validation "Select one or more complete draft entries, without duplicates." {:field :selected}))
  (mapv (fn [{:keys [id revision]}]
          (schema/text! id :id)
          (check-draft! current id revision))
        selected))


(defn save-entry!
  "Autosave incomplete draft content. Acknowledgement follows the durable RDF commit."
  {:malli/schema [:=> [:cat rdf/Repository shared/Scope schema/SaveRequest] schema/Draft]}
  [repo scope {:keys [id expected-revision entry actor]}]
  (schema/text! actor :actor)
  (check-content! entry)
  (locking repo
    (rdf/with-transaction [conn {:repo repo :level :serializable}]
      (let [current  (state conn scope)
            id       (if id (schema/text! id :id) (str (UUID/randomUUID)))
            existing (get-in current [:drafts id])]
        (when (or existing expected-revision)
          (check-draft! current id expected-revision))
        (let [saved (merge (select-keys existing [:phase :owner-id])
                           {:id    id    :revision (inc (or (:revision existing) 0))
                            :entry entry :actor    actor :created-by (or (:created-by existing) (:actor existing) actor) :saved-at (now)})]
          (save-state! conn scope (assoc-in current [:drafts id] saved))
          saved)))))


(defn save-composition!
  "Persist an owner-private unfinished operation without exposing a shared draft."
  {:malli/schema [:=> [:cat rdf/Repository shared/Scope schema/SaveRequest] schema/Draft]}
  [repo scope {:keys [id expected-revision entry actor]}]
  (let [owner-id (owner-id! scope)]
    (schema/text! actor :actor)
    (check-content! entry)
    (locking repo
      (rdf/with-transaction [conn {:repo repo :level :serializable}]
        (let [current  (state conn scope)
              id       (if id (schema/text! id :id) (str (UUID/randomUUID)))
              existing (get-in current [:drafts id])]
          (when (or existing expected-revision)
            (check-composition! current owner-id id expected-revision))
          (let [saved {:id         id :revision (inc (or (:revision existing) 0))
                       :phase      :composing :owner-id owner-id :entry entry :actor actor
                       :created-by (or (:created-by existing) (:actor existing) actor) :saved-at (now)}]
            (save-state! conn scope (assoc-in current [:drafts id] saved))
            saved))))))


(defn discard-composition!
  {:malli/schema [:=> [:cat rdf/Repository shared/Scope schema/DiscardRequest] :map]}
  [repo scope {:keys [id expected-revision]}]
  (let [owner-id (owner-id! scope)]
    (schema/text! id :id)
    (locking repo
      (rdf/with-transaction [conn {:repo repo :level :serializable}]
        (let [current (state conn scope)]
          (check-composition! current owner-id id expected-revision)
          (save-state! conn scope (update current :drafts dissoc id))
          {:id id :discarded? true})))))


(defn- known-item-ids
  {:malli/schema [:=> [:cat :map] [:set :string]]}
  [current]
  (into (set (keys (get-in current [:projection :items])))
        (keep (fn [node]
                (when (and (map? node) (string? (:item-id node))
                           (string? (:name node)) (not (str/blank? (:name node))))
                  (:item-id node))))
        (tree-seq coll? seq (map :entry (remove #(= :composing (:phase %)) (vals (:drafts current)))))))


(defn complete-composition!
  "Atomically finish a private composition, with a durable retry receipt, without accepting stock."
  {:malli/schema [:=> [:cat rdf/Repository shared/Scope schema/CompleteRequest] schema/Draft]}
  [repo scope {:keys [id expected-revision actor command-id entry] :as request}]
  (let [owner-id (owner-id! scope)]
    (schema/text! actor :actor)
    (schema/text! command-id :command-id)
    (schema/text! id :id)
    (when (contains? request :entry) (check-content! entry))
    (locking repo
      (rdf/with-transaction [conn {:repo repo :level :serializable}]
        (let [receipt-subject (entity scope "composition-receipt" (str owner-id "/" command-id))
              fingerprint     (select-keys request [:id :expected-revision :actor])]
          (if-let [receipt (payload conn (graph scope "composition-receipts") receipt-subject)]
            (if (and (= fingerprint (:request receipt))
                     (or (not (contains? request :entry)) (= entry (:entry receipt))))
              (:draft receipt)
              (schema/fail! :idempotency-conflict "This Done identifier already belongs to another operation." {}))
            (let [current (state conn scope)
                  draft   (check-composition! current owner-id id expected-revision)
                  entry   (if (contains? request :entry) entry (:entry draft))
                  checked (completion/check entry (known-item-ids current))]
              (when-not (:valid? checked)
                (schema/fail! :validation "Complete the required fields before choosing Done."
                              {:errors (:errors checked)}))
              (let [saved (assoc draft :phase :pending :revision (inc (:revision draft))
                                 :entry entry :actor actor :saved-at (now))]
                (save-state! conn scope (assoc-in current [:drafts id] saved))
                (put-payload! conn (graph scope "composition-receipts") receipt-subject
                              {:owner-id owner-id :request fingerprint :entry entry :draft saved})
                saved))))))))


(defn discard-entry!
  {:malli/schema [:=> [:cat rdf/Repository shared/Scope schema/DiscardRequest] :map]}
  [repo scope {:keys [id expected-revision]}]
  (schema/text! id :id)
  (locking repo
    (rdf/with-transaction [conn {:repo repo :level :serializable}]
      (let [current (state conn scope)]
        (check-draft! current id expected-revision)
        (save-state! conn scope (update current :drafts dissoc id))
        {:id id :discarded? true}))))


(defn discard-selected!
  "Discard exactly the reviewed draft revisions together, leaving accepted history untouched."
  {:malli/schema [:=> [:cat rdf/Repository shared/Scope schema/Selection]
                  [:map [:discarded-ids [:vector schema/Id]]]]}
  [repo scope selected]
  (locking repo
    (rdf/with-transaction [conn {:repo repo :level :serializable}]
      (let [current (state conn scope)
            ids     (mapv :id (selected-drafts-by-revision! current selected))]
        (save-state! conn scope (update current :drafts #(apply dissoc % ids)))
        {:discarded-ids ids}))))


(defn- selected-drafts!
  {:malli/schema [:=> [:cat :map schema/ReviewRequest] [:vector schema/Draft]]}
  [current {:keys [selected expected-ledger-revision]}]
  (when-not (= expected-ledger-revision (:ledger-revision current))
    (schema/fail! :stale-ledger "Accepted inventory changed. Preview this selection again."
                  {:expected-ledger-revision expected-ledger-revision
                   :ledger-revision          (:ledger-revision current)}))
  (selected-drafts-by-revision! current selected))


(defn- purchase-entry
  "Current purchase drafts use item quantities; historical receipt records remain unchanged."
  {:malli/schema [:=> [:cat :map] :map]}
  [entry]
  (let [direct (fn [value] (if (= :purchase (:type value)) (dissoc value :receipts) value))]
    (if (= :revision (:kind entry)) (update entry :replacement direct) (direct entry))))


(defn- record-for
  {:malli/schema [:=> [:cat schema/Draft [:vector :map] :string :string :int] :map]}
  [draft records actor recorded-at sequence]
  (let [entry     (ledger/normalize-entry (purchase-entry (:entry draft)))
        revision? (#{:revision :cancellation} (:kind entry))
        target-id (if revision? (:target-id entry) (:id draft))
        target    (last (filter #(= target-id (:entity-id %)) records))]
    (when (and revision? (or (nil? target) (:cancelled? target)))
      (schema/fail! :validation "The operation to revise or cancel is unavailable."
                    {:target-id target-id}))
    (when (and (not revision?) target)
      (schema/fail! :validation "This operation already exists." {:target-id target-id}))
    (let [replacement (if (= :revision (:kind entry)) (:replacement entry) entry)]
      {:id                (str (UUID/randomUUID))                 :entity-id target-id :revision (inc (or (:revision target) 0))
       :semantic-version  semantics/version :sequence sequence :business-order                   (or (:business-order target) sequence)
       :recorded-at       recorded-at :actor actor :proposed-by (:actor draft)
       :created-by        (or (:created-by draft) (:actor draft)) :reason    (:reason entry)
       :previous-revision (:revision target) :cancelled? (= :cancellation (:kind entry))
       :change-kind       (:kind entry) :entry (if (= :cancellation (:kind entry)) (:entry target) replacement)})))


(defn- prepare
  {:malli/schema [:=> [:cat :map [:vector :map] schema/ReviewRequest :string :string :int] :map]}
  [current records request actor recorded-at sequence]
  (let [drafts     (selected-drafts! current request)
        target-ids (map #(or (get-in % [:entry :target-id]) (:id %)) drafts)]
    (when-not (= (count target-ids) (count (set target-ids)))
      (schema/fail! :validation "Select only one revision of the same operation at a time." {}))
    (let [new-records (reduce (fn [prepared draft]
                                (conj prepared (record-for draft (into records prepared) actor recorded-at
                                                           (+ sequence (count prepared) 1))))
                              [] drafts)
          effective   (ledger/effective-entries (into records new-records))]
      (doseq [entry effective
              :let  [reference (:original-operation-id entry)]
              :when reference]
        (when-not (some #(and (= reference (:id %))
                              (#{:purchase :sale} (:type %))) effective)
          (schema/fail! :validation "The referenced purchase or sale is unavailable."
                        {:operation-id (:id entry) :original-operation-id reference})))
      {:drafts drafts :records new-records :operations effective})))


(defn- lineage-signature
  {:malli/schema [:=> [:cat [:vector :map]] [:vector :map]]}
  [events]
  (mapv (fn [event]
          (assoc (select-keys event [:operation-id :business-order :occurred-at])
                 :inputs (mapv #(select-keys % [:line-id :item-id :location :quantity]) (:inputs event))
                 :outputs (mapv #(select-keys % [:line-id :item-id :location :quantity]) (:outputs event))))
        events))


(defn- derive-projection!
  "SPARQL is the projection authority; independent replay must agree before persistence."
  {:malli/schema [:=> [:cat rdf/Connection rdf/Resource [:vector :map]] :map]}
  [conn history-graph effective]
  (let [derived   (derivation/validate! conn history-graph)
        reference (ledger/replay effective)]
    (when-not (and (= (:balances derived) (:balances reference))
                   (= (lineage-signature (:lineage derived)) (lineage-signature (:lineage reference))))
      (schema/fail! :projection-drift "Semantic projection and independent replay disagree; no changes were accepted."
                    {:derived-balances (:balances derived) :reference-balances (:balances reference)}))
    (assoc reference :balances (:balances derived)
           :rows (mapv (fn [row] (assoc row :quantity (get (:balances derived) [(:item-id row) (:location row)])))
                       (:rows reference))
           :lineage (:lineage derived) :lineage-relationships (:lineage-relationships derived))))


(defn- semantic-projection!
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope [:vector :map]] :map]}
  [conn scope effective]
  (derive-projection! conn (graph scope "history") effective))


(defn- prepare-scoped
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :map schema/ReviewRequest :string :string] :map]}
  [conn scope current request actor recorded-at]
  (let [drafts   (selected-drafts! current request)
        entries  (mapv #(ledger/normalize-entry (purchase-entry (:entry %))) drafts)
        targets  (set (map #(or (get-in % [:entry :target-id]) (:id %)) drafts))
        affected (affected-query/closure conn scope entries targets)]
    (merge affected (prepare current (:records affected) request actor recorded-at (:sequence affected))
           {:history (:records affected)})))


(defn- scoped-projection!
  "Validate complete affected revision chains with the installed semantic package."
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :map] :map]}
  [conn scope prepared]
  (let [candidate (graph scope (str "review:" (random-uuid)))
        workspace (rdf/iri (str "urn:tover:workspace:" (:workspace-id scope)))]
    (try
      (doseq [record (concat (:history prepared) (:records prepared))]
        (model/write-record! conn candidate workspace record))
      (semantics/validate! conn candidate)
      (derive-projection! conn candidate (:operations prepared))
      (finally (rdf/remove! conn candidate nil nil nil)))))


(defn preview
  "Validate exactly the reviewer-selected revisions, staging semantic records before a guaranteed rollback."
  {:malli/schema [:=> [:cat rdf/Repository shared/Scope schema/ReviewRequest] schema/Preview]}
  [repo scope request]
  (locking repo
    (with-open [conn (.getConnection ^Repository repo)]
      (.begin conn IsolationLevels/SERIALIZABLE)
      (try
        (let [_          (when-not (read-model/ready? conn scope)
                           (read-model/ensure! conn scope (state conn scope)))
              current    (read-model/read-state conn scope)
              prepared   (prepare-scoped conn scope current request "preview" (now))
              projection (scoped-projection! conn scope prepared)
              before     (read-model/read-projection conn scope (:item-ids prepared))]
          (merge (select-keys request [:selected :expected-ledger-revision])
                 {:valid?      true           :errors []                  :operations (:operations prepared) :rows (:rows projection)
                  :before-rows (:rows before) :items  (:items projection)}))
        (catch clojure.lang.ExceptionInfo error
          (if (#{:stale-draft :stale-ledger} (:type (ex-data error)))
            (throw error)
            (merge (select-keys request [:selected :expected-ledger-revision])
                   {:valid? false :errors (or (:errors (ex-data error))
                                              [(assoc (ex-data error) :message (.getMessage error))])})))
        (finally
          (when (.isActive conn) (.rollback conn)))))))

(defn submit!
  "Commit one reviewed selection, its projection, receipt and draft removal in one transaction."
  {:malli/schema [:=> [:cat rdf/Repository shared/Scope schema/SubmitRequest] schema/Receipt]}
  [repo scope {:keys [actor command-id] :as request}]
  (schema/text! actor :actor)
  (schema/text! command-id :command-id)
  (locking repo
    (rdf/with-transaction [conn {:repo repo :level :serializable}]
      (let [receipt-subject (entity scope "receipt" command-id)
            fingerprint     (select-keys request [:selected :expected-ledger-revision :actor])]
        (if-let [receipt (payload conn (graph scope "receipts") receipt-subject)]
          (if (= fingerprint (:request receipt))
            (dissoc receipt :request)
            (schema/fail! :idempotency-conflict "This Submit identifier already belongs to another selection." {}))
          (let [_           (when-not (read-model/ready? conn scope)
                              (read-model/ensure! conn scope (state conn scope)))
                current     (read-model/read-state conn scope)
                recorded-at (now)
                prepared    (prepare-scoped conn scope current request actor recorded-at)
                revision    (inc (:ledger-revision current))
                receipt     {:command-id    command-id :semantic-version               semantics/version :ledger-revision revision
                             :accepted      (mapv #(select-keys % [:entity-id :revision]) (:records prepared))
                             :submitted-ids (mapv :id (:drafts prepared)) :recorded-at recorded-at       :actor           actor}]
            (let [projection (scoped-projection! conn scope prepared)
                  updated    (-> current
                                 (assoc :ledger-revision revision)
                                 (update :drafts #(apply dissoc % (map :id (:drafts prepared)))))]
              (doseq [record (:records prepared)] (write-record! conn scope record))
              (save-light-state! conn scope updated)
              (read-model/stage-accepted! conn scope updated projection (:item-ids prepared) (:entity-ids prepared))
              (read-model/stage-records! conn scope (:records prepared) (:items projection)))
            (rdf/add! conn (graph scope "receipts") receipt-subject (predicate :payload)
                      (pr-str (assoc receipt :request fingerprint)))
            receipt))))))

(defn item-history
  {:malli/schema [:=> [:cat rdf/Repository shared/Scope :string :map] :map]}
  [repo scope item-id {:keys [include-ancestors? row-id]}]
  (schema/text! item-id :item-id)
  (read-snapshot repo scope
                 (fn [conn current]
                   (let [result    (history-query/history conn scope item-id include-ancestors?)
                         locations (concat (:records result) (mapcat :item-lines (:audit-records result)))]
                     (when (and row-id (not-any? #(and (string? (:location %))
                                                       (= row-id (ledger/row-id item-id (:location %)))) locations))
                       (schema/fail! :validation "This stock history is unavailable." {}))
                     (assoc result :ledger-revision (:ledger-revision current) :snapshot-at (now))))))

(defn rebuild!
  "Rebuild query-derived stock and lineage, checking independent replay, from immutable semantic RDF."
  {:malli/schema [:=> [:cat rdf/Repository shared/Scope] schema/Snapshot]}
  [repo scope]
  (locking repo
    (rdf/with-transaction [conn {:repo repo :level :serializable}]
      (let [current (state conn scope)
            records (history conn scope)]
        (semantics/validate! conn (graph scope "history"))
        (let [projection (semantic-projection! conn scope (ledger/effective-entries records))
              updated    (assoc current :projection projection)]
          (save-state! conn scope updated)
          (read-model/rebuild! conn scope updated)
          (public-snapshot updated records scope))))))
