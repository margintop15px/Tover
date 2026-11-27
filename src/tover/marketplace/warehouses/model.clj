(ns tover.marketplace.warehouses.model
  "Board chapter 43 · Match marketplace warehouses: warehouse mappings, the matching reads (slices 1, 3, 5) and
  the member's confirm and change commands (slices 2, 4).

  An external warehouse is an `External Warehouse Observed` row of one connection; its id carries its type, so
  the type never changes. A mapping links it to one Tover warehouse. States: `confirmed` (permits automatic
  acceptance) and `removed` (changed to no warehouse: unmatched again). A Tover warehouse mapped from an FBO
  warehouse is mapped from no other external warehouse of any connection; seller warehouses (`fbs`, `rfbs`,
  `supply-origin`) may share one.

  Store: every model lives in `:catalogue`; instants are ISO text. The board's `confirmed by/at` are
  `changed-by`/`changed-at` in both events. `External Warehouse Mapped` writes with `:set`: a removed mapping is
  mapped again in place. Each event also creates a revision `<external-warehouse-id>#<mapping-revision>`. A new
  warehouse is chapter 09's `Warehouse Registered` under chapter 09's name rule (`records/check-name!`)."
  (:require
    [bel.core :refer [defcommand defevent defmodel defreadmodel]]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [tover.catalogues.records :as records]
    [tover.catalogues.warehouses.model :as warehouses]
    [tover.marketplace.connections.model :as connections]
    [tover.marketplace.sync.model :as sync]
    [tover.marketplace.sync.observations :as observations])
  (:import
    [clojure.lang ExceptionInfo]))


;; Data model (element details on the board)


(def Ref connections/Ref)
(def At connections/At)


(def ExternalWarehouseId
  [:string {:min 1 :max 600}])


(def WarehouseId
  records/Id)


(def Revision
  [:int {:min 1}])


(def MappingState
  [:enum "confirmed" "removed"])


(def WarehouseMapping
  [:map
   [:external-warehouse-id ExternalWarehouseId]
   [:connection-id Ref]
   [:warehouse-type observations/WarehouseType]
   [:warehouse-id {:optional true} WarehouseId]
   [:mapping-revision Revision]
   [:state MappingState]
   [:changed-by Ref]
   [:changed-at At]])


(def WarehouseMappingRevision
  "One mapping revision as its event left it; never updated."
  (into WarehouseMapping
        [[:mapping-revision-id [:string {:min 1 :max 700}]]
         [:change [:enum "mapped" "changed"]]
         [:previous-warehouse-id {:optional true} WarehouseId]]))


(defn revision-id
  {:malli/schema [:=> [:cat :string :int] :string]}
  [external-warehouse-id mapping-revision]
  (str external-warehouse-id "#" mapping-revision))


(defn new-warehouse-id
  "The Tover warehouse an external warehouse registers, derived from it: `listing-warehouse:<id>`, or
  `listing-warehouse:<id>:<n>` with the least n from 2 when the earlier ones are archived or deleted. An active
  one is the answer itself, so one external warehouse never registers two active warehouses. `warehouses` by id."
  {:malli/schema [:=> [:cat :map :string] :string]}
  [warehouses external-warehouse-id]
  (let [base (str "listing-warehouse:" external-warehouse-id)]
    (some #(let [id (if (= 1 %) base (str base ":" %))
                 w (get warehouses id)]
             (when (or (nil? w) (= "active" (:status w))) id))
          (iterate inc 1))))


(def ^:private Common
  [[:external-warehouse-id ExternalWarehouseId] [:mapping-revision-id :string] [:connection-id Ref]
   [:warehouse-type observations/WarehouseType] [:mapping-revision Revision] [:changed-by Ref] [:changed-at At]
   [:previous-warehouse-id {:optional true} WarehouseId]])


(def ExternalWarehouseMapped
  (into [:map] (concat Common [[:change [:= "mapped"]] [:warehouse-id WarehouseId] [:state [:= "confirmed"]]])))


(def WarehouseMappingChanged
  "A blank new warehouse means unmatched: state `removed`."
  (into [:map] (concat Common [[:change [:= "changed"]] [:warehouse-id {:optional true} WarehouseId]
                               [:state [:enum "confirmed" "removed"]]])))


(defevent external-warehouse-mapped {:schema ExternalWarehouseMapped})
(defevent warehouse-mapping-changed {:schema WarehouseMappingChanged})


(def ^:private mapping-fields
  [:connection-id :warehouse-type :warehouse-id :mapping-revision :state :changed-by :changed-at])


(defmodel warehouse-mappings
  {:id     :external-warehouse-id :store :catalogue :schema WarehouseMapping :policy ::records/workspace-member
   :events {::external-warehouse-mapped {:set mapping-fields}
            ::warehouse-mapping-changed {:set [:warehouse-id :mapping-revision :state :changed-by :changed-at]}}})


(defmodel warehouse-mapping-revisions
  {:id     :mapping-revision-id :store :catalogue :schema WarehouseMappingRevision :policy ::records/workspace-member
   :events (into {} (for [event [::external-warehouse-mapped ::warehouse-mapping-changed]]
                      [event {:create (into [:external-warehouse-id :change :previous-warehouse-id] mapping-fields)}]))})


;; Facts


(defn- by-id
  {:malli/schema [:=> [:cat [:sequential :map] :keyword] :map]}
  [rows k]
  (into {} (map (juxt k identity)) rows))


(defmethod runtime/coeffect ::mappings
  [_ context {:keys [connection-id]}]
  ;; Every connection's by default: an FBO warehouse's Tover warehouse is checked across connections.
  (by-id (cond->> (records/all-records context ::warehouse-mappings :external-warehouse-id)
           connection-id (filter #(= connection-id (:connection-id %))))
         :external-warehouse-id))


(defmethod runtime/coeffect ::external-warehouses
  [_ context {:keys [connection-id]}]
  ;; ponytail: scans the workspace's external warehouses; Ozon accounts have tens, not thousands.
  (if connection-id
    (filterv #(= connection-id (:connection-id %))
             (records/all-records context ::sync/external-warehouses :external-warehouse-id))
    []))


(defmulti waiting-operations
  "Imported operations naming each external warehouse of a connection: {external-warehouse-id n}. Chapter 45
  adds the `:imported-operations` method; until then nothing waits."
  (fn [_context _connection-id] :imported-operations))


(defmethod waiting-operations :default [_ _] {})


(defmethod runtime/coeffect ::waiting
  [_ context {:keys [connection-id]}]
  (waiting-operations context connection-id))


(defmethod connections/count-part :unmatched-warehouses
  ;; Board chapter 40: external warehouses of the connection without a confirmed mapping.
  [_ context connection-id]
  (let [mappings (runtime/coeffect ::mappings context {:connection-id connection-id})]
    (count (remove #(= "confirmed" (:state (get mappings (:external-warehouse-id %))))
                   (runtime/coeffect ::external-warehouses context {:connection-id connection-id})))))


;; Rules shared by the reads and the commands


(defn fbo?
  {:malli/schema [:=> [:cat [:maybe :string]] :boolean]}
  [warehouse-type]
  (= "fbo" warehouse-type))


(defn- confirmed?
  {:malli/schema [:=> [:cat [:maybe :map]] :boolean]}
  [mapping]
  (= "confirmed" (:state mapping)))


(defn- fold
  {:malli/schema [:=> [:cat [:maybe :string]] :string]}
  [text]
  (str/lower-case (str/trim (or text ""))))


(defn- live?
  {:malli/schema [:=> [:cat [:maybe :map]] :boolean]}
  [warehouse]
  (boolean (and warehouse (not= "deleted" (:status warehouse)))))


(defn- users
  "External warehouses with a confirmed mapping to each Tover warehouse: {warehouse-id [mapping]}."
  {:malli/schema [:=> [:cat [:map-of :string :map]] [:map-of :string [:vector :map]]]}
  [mappings]
  (update-vals (group-by :warehouse-id (filter confirmed? (vals mappings))) vec))


(defn- shared-with-fbo?
  "Whether mapping `external` (of `type`) to a warehouse that `others` already use breaks the FBO rule."
  {:malli/schema [:=> [:cat :string :string [:sequential :map]] :boolean]}
  [external type others]
  (let [others (remove #(= external (:external-warehouse-id %)) others)]
    (boolean (and (seq others) (or (fbo? type) (some (comp fbo? :warehouse-type) others))))))


(defn provider-name
  "The provider's name; a blank one suggests the provider warehouse id."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:keys [name source-id]}]
  (or (some-> name str/trim not-empty) source-id))


(defn suggestion
  "The prefilled choice: the active warehouse it registered before, else for a seller warehouse an active one of the
  same name that the FBO rule leaves free, else a new one under the provider's name. A name another warehouse
  holds takes the account label: a second account's FBO warehouse of the same name."
  {:malli/schema [:=> [:cat :map [:maybe :string] :map] [:or [:map [:warehouse-id :string]] [:map [:new-name :string]]]]}
  [{:keys [warehouses used]} label {:keys [external-warehouse-id warehouse-type] :as external}]
  (let [name     (provider-name external)
        own      (get warehouses (new-warehouse-id warehouses external-warehouse-id))
        taken    (some #(when (and (live? %) (= (fold name) (fold (:name %)))) %) (vals warehouses))
        sharable (when (and taken (= "active" (:status taken)) (not (fbo? warehouse-type))
                            (not (shared-with-fbo? external-warehouse-id warehouse-type (get used (:warehouse-id taken) []))))
                   taken)]
    (cond own {:warehouse-id (:warehouse-id own)}
          sharable {:warehouse-id (:warehouse-id sharable)}
          (and taken label) {:new-name (str name " · " label)}
          :else {:new-name name})))


(defn- account-label
  {:malli/schema [:=> [:cat [:sequential :map] :string] [:maybe :string]]}
  [connections connection-id]
  (some #(when (= connection-id (:connection-id %))
           (or (some-> (:account-label %) str/trim not-empty) (:provider-account-id %)))
        connections))


;; Reads


(def Suggestion
  [:or [:map [:warehouse-id :string]] [:map [:new-name :string]]])


(def UnmatchedRow
  [:map
   [:external-warehouse-id :string] [:revision :int] [:name {:optional true} :string] [:source-id :string]
   [:warehouse-type observations/WarehouseType] [:active :boolean]
   [:suggestion Suggestion] [:waiting-operations [:maybe :int]]])


(defn- by-waiting
  "Most waiting operations first, then by name and id."
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector :map]]}
  [rows]
  (vec (sort-by (juxt (comp - #(or (:waiting-operations %) 0)) (comp fold :name) :external-warehouse-id) rows)))


(defn- evidence
  {:malli/schema [:=> [:cat :map [:maybe :map]] :map]}
  [external waiting]
  (cond-> {:external-warehouse-id (:external-warehouse-id external) :revision (:revision external)
           :source-id             (:source-id external) :warehouse-type       (:warehouse-type external)
           :active                (not (false? (:active external)))
           :waiting-operations    (when waiting (get waiting (:external-warehouse-id external) 0))}
    (some-> (:name external) str/trim not-empty) (assoc :name (str/trim (:name external)))))


(defn unmatched-rows
  "External warehouses of the connection without a confirmed mapping, each with its suggestion. Inactive ones
  are listed: their operations may still wait."
  {:malli/schema [:=> [:cat :map :string] [:vector UnmatchedRow]]}
  [{:keys [externals mappings warehouses connections waiting]} connection-id]
  (let [index {:warehouses (by-id warehouses :warehouse-id) :used (users mappings)}
        label (account-label connections connection-id)]
    (by-waiting (for [external externals
                      :when    (not (confirmed? (get mappings (:external-warehouse-id external))))]
                  (assoc (evidence external waiting) :suggestion (suggestion index label external))))))


(defn- by-connection
  {:malli/schema [:=> [:cat :map :map] :map]}
  [_ input]
  (select-keys input [:connection-id]))


(def ^:private read-cofx
  [[:externals ::external-warehouses by-connection]
   [:mappings ::mappings (constantly {})]
   [:warehouses :tover.catalogues.warehouses.model/all (constantly {})]
   [:connections ::connections/all (constantly {})]
   [:waiting ::waiting by-connection]])


(def Choice
  [:map [:warehouse-id :string] [:name :string]])


(defn- choices
  "Active Tover warehouses by name: what Confirm and Change accept, the FBO rule aside."
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector Choice]]}
  [warehouses]
  (->> warehouses (filter #(= "active" (:status %))) (map #(select-keys % [:warehouse-id :name]))
       (sort-by (comp fold :name)) vec))


(defreadmodel unmatched-external-warehouses
  {:input  [:map [:connection-id Ref]]
   :output [:map [:rows [:vector UnmatchedRow]] [:warehouses [:vector Choice]]]
   :policy ::records/workspace-member
   :cofx   read-cofx}
  [{:keys [warehouses] :as facts} {:keys [connection-id]}]
  {:rows (unmatched-rows facts connection-id) :warehouses (choices warehouses)})


(def MappingRow
  [:map
   [:external-warehouse-id :string] [:revision :int] [:name {:optional true} :string] [:source-id :string]
   [:warehouse-type observations/WarehouseType] [:active :boolean] [:waiting-operations [:maybe :int]]
   [:state [:enum "unmatched" "confirmed"]]
   [:mapping-revision {:optional true} :int]
   [:warehouse {:optional true} [:map [:warehouse-id :string] [:name :string] [:archived? :boolean]]]
   [:confirmed-by {:optional true} :string]
   [:confirmed-at {:optional true} :any]])


(defn mapping-rows
  "Every external warehouse of the connection with its Tover warehouse and who confirmed it; unmatched first."
  {:malli/schema [:=> [:cat :map] [:vector MappingRow]]}
  [{:keys [externals mappings warehouses waiting]}]
  (let [warehouses (by-id warehouses :warehouse-id)]
    (->> (for [external externals
               :let     [{:keys [warehouse-id] :as mapping} (get mappings (:external-warehouse-id external))
                         row (evidence external waiting)]]
           (cond-> (assoc row :state (if (confirmed? mapping) "confirmed" "unmatched"))
             mapping (assoc :mapping-revision (:mapping-revision mapping))
             (confirmed? mapping)
             (assoc :warehouse (let [w (get warehouses warehouse-id)]
                                 {:warehouse-id warehouse-id                :name (or (:name w) warehouse-id)
                                  :archived?    (not= "active" (:status w))})
                    :confirmed-by (:changed-by mapping)
                    :confirmed-at (connections/at (:changed-at mapping)))))
         (sort-by (juxt #(if (= "unmatched" (:state %)) 0 1) (comp - #(or (:waiting-operations %) 0)) (comp fold :name)
                        :external-warehouse-id))
         vec)))


(defreadmodel current-warehouse-mappings
  ;; Board `Warehouse Mappings` (slices 3 and 5); `warehouse-mappings` names the model. Baseline coverage arrives with chapter 44's cutover.
  {:input  [:map [:connection-id Ref]]
   :output [:map [:rows [:vector MappingRow]] [:warehouses [:vector Choice]]]
   :policy ::records/workspace-member
   :cofx   read-cofx}
  [{:keys [warehouses] :as facts} _]
  {:rows (mapping-rows facts) :warehouses (choices warehouses)})


;; Commands (slices 2 and 4). Every row is checked and every problem returned; one bad row accepts nothing.


(def ^:private messages
  {:stale                   "The Ozon warehouse or its mapping changed since review. Review it again."
   :already-mapped          "The Ozon warehouse is already mapped. Change its mapping instead."
   :listed-twice            "The Ozon warehouse is in the batch twice."
   :warehouse-exists        "The Ozon warehouse already registered a warehouse. Choose it instead."
   :warehouse-not-available "Choose an active warehouse of this workspace."
   :already-used            "Already used for another Ozon warehouse."
   :invalid-name            (get-in warehouses/spec [:messages :invalid-name])
   :name-taken              (get-in warehouses/spec [:messages :name-taken])})


(def ^:private rejection-type
  "Row codes that reject with another type than their own."
  {:listed-twice :already-mapped :warehouse-exists :already-mapped :invalid-name :validation})


(defn- problem
  {:malli/schema [:=> [:cat :string :keyword [:* :any]] :map]}
  [external-warehouse-id code & {:as extra}]
  (merge {:external-warehouse-id external-warehouse-id :code code :message (messages code)} extra))


(defn- reject!
  "Rejects with the first problem's type, naming every problem row."
  {:malli/schema [:=> [:cat [:sequential :map]] :nil]}
  [problems]
  (when-let [{:keys [code message]} (first problems)]
    (records/fail! (rejection-type code code) message {:rows (vec problems)})))


(defn- name-code
  "Chapter 09's name rule for a new warehouse among `records`: nil when the trimmed name is free, else the code."
  {:malli/schema [:=> [:cat [:sequential :map] :string [:maybe :string]] [:maybe :keyword]]}
  [records warehouse-id name]
  (try (records/check-name! warehouses/spec records warehouse-id (or name "")) nil
       (catch ExceptionInfo e
         (if (= :name-taken (:type (ex-data e))) :name-taken :invalid-name))))


(defn- row-target
  "The row with what it maps to: an existing `:warehouse-id`, or a new one under `:warehouse-name`."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [warehouses {:keys [external-warehouse-id warehouse-id warehouse-name] :as row}]
  (if (some-> warehouse-id str/trim not-empty)
    (assoc row :target (str/trim warehouse-id))
    (assoc row :target (new-warehouse-id warehouses external-warehouse-id) :new-name (some-> warehouse-name str/trim))))


(defn- target-code
  "Nil when an existing target is active in this workspace (one of another workspace is not found here)."
  {:malli/schema [:=> [:cat :map :string] [:maybe :keyword]]}
  [warehouses warehouse-id]
  (when-not (= "active" (:status (get warehouses warehouse-id))) :warehouse-not-available))


(defn- used-problems
  "The FBO rule over the confirmed mappings and the batch: one problem per row that breaks it, naming the other
  Ozon warehouse."
  {:malli/schema [:=> [:cat :map [:sequential :map]] [:vector :map]]}
  [{:keys [mappings named]} accepted]
  (let [used (merge-with into (users mappings)
                         (update-vals (group-by :warehouse-id (for [{:keys [external target]} accepted]
                                                                {:external-warehouse-id (:external-warehouse-id external)
                                                                 :warehouse-id          target
                                                                 :warehouse-type        (:warehouse-type external)}))
                                      vec))]
    (vec (for [{:keys [external target]} accepted
               :let [id (:external-warehouse-id external)
                     others (remove #(= id (:external-warehouse-id %)) (get used target []))]
               :when (shared-with-fbo? id (:warehouse-type external) others)
               :let [other (:external-warehouse-id (first (sort-by :external-warehouse-id others)))]]
           (problem id :already-used :other-warehouse (or (some-> (get named other) provider-name) other))))))


(defn- decided
  "What every member decision writes: the external warehouse, the new mapping revision, who and when."
  {:malli/schema [:=> [:cat :map :map :string :int] :map]}
  [{:keys [actor now]} external change mapping-revision]
  {:external-warehouse-id (:external-warehouse-id external)
   :mapping-revision-id   (revision-id (:external-warehouse-id external) mapping-revision)
   :connection-id         (:connection-id external)
   :warehouse-type        (:warehouse-type external)
   :change                change
   :mapping-revision      mapping-revision
   :changed-by            (str actor)
   :changed-at            (str now)})


(defn- accepted-fx
  "Publish, and evaluate again the waiting operations of each connection touched."
  {:malli/schema [:=> [:cat :map [:sequential [:maybe :string]]] [:vector :any]]}
  [scope connection-ids]
  (into (records/publish scope) (for [c (sort (set (remove nil? connection-ids)))] [::sync/mark-dirty {:connection-id c}])))


(defmethod runtime/coeffect ::reviewed
  [_ context {:keys [ids]}]
  (sync/load-rows context ::sync/external-warehouses (vec ids)))


(defn- row-ids
  {:malli/schema [:=> [:cat :map :map] :map]}
  [_ {:keys [rows external-warehouse-id]}]
  {:ids (vec (or (some->> rows (map :external-warehouse-id)) [external-warehouse-id]))})


(defn- named-ids
  "External warehouses already mapped to a warehouse the input names: the FBO rejection names them."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [mappings]} {:keys [rows warehouse-id]}]
  (let [targets (set (keep #(some-> % str/trim not-empty) (cons warehouse-id (map :warehouse-id rows))))]
    {:ids (vec (for [m (vals mappings) :when (and (confirmed? m) (targets (:warehouse-id m)))] (:external-warehouse-id m)))}))


(def ^:private command-cofx
  [[:externals ::reviewed row-ids]
   [:mappings ::mappings (constantly {})]
   [:named ::reviewed named-ids]
   [:warehouses :tover.catalogues.warehouses.model/all (constantly {})]])


(def ConfirmRow
  "`:warehouse-id` maps to an existing warehouse; without it `:warehouse-name` registers a new one."
  [:map
   [:external-warehouse-id ExternalWarehouseId] [:revision Revision]
   [:warehouse-id {:optional true} WarehouseId]
   [:warehouse-name {:optional true} [:string {:max 1000}]]])


(def ConfirmWarehouseMapping
  [:map [:rows [:vector {:min 1 :max 5000} ConfirmRow]]])


(defn- confirm-row
  "Adds the row's problems, or the accepted row, to `acc`. One already confirmed to the same warehouse is skipped."
  {:malli/schema [:=> [:cat :map :map :map] :map]}
  [{:keys [externals mappings warehouses records twice]} acc {:keys [external-warehouse-id revision target new-name] :as row}]
  (let [external (get externals external-warehouse-id)
        mapping  (get mappings external-warehouse-id)
        new?     (contains? row :new-name)
        code     (cond (twice external-warehouse-id) :listed-twice
                       (or (nil? external) (not= revision (:revision external))) :stale
                       (and (confirmed? mapping) (= target (:warehouse-id mapping))) :same
                       (confirmed? mapping) :already-mapped
                       (and new? (get warehouses target)) :warehouse-exists
                       new? (name-code records target new-name)
                       :else (target-code warehouses target))]
    (cond (= :same code) acc
          code (update acc :problems conj (problem external-warehouse-id code))
          :else (update acc :accepted conj {:external external :mapping mapping :target target
                                            :new-name (when new? (str/trim new-name))}))))


(defcommand confirm-warehouse-mapping
  {:input ConfirmWarehouseMapping :policy ::records/workspace-member
   :cofx  command-cofx
   :emits #{::external-warehouse-mapped :tover.catalogues.warehouses.model/warehouse-registered}}
  [{:keys [scope externals mappings named warehouses] :as facts} {:keys [rows]}]
  (let [rows (mapv (partial row-target (by-id warehouses :warehouse-id)) (distinct rows))
        twice (set (keep (fn [[id n]] (when (> n 1) id)) (frequencies (map :external-warehouse-id rows))))
        ;; Each new name is checked against the warehouses and every other new name of the batch.
        records (into (vec warehouses) (for [{:keys [target new-name] :as row} rows :when (contains? row :new-name)]
                                         {:warehouse-id target :name (or new-name "") :status "active"}))
        checked {:externals externals :mappings mappings :warehouses (by-id warehouses :warehouse-id)
                 :records   records   :twice    twice}
        {:keys [problems accepted]} (reduce (partial confirm-row checked) {:problems [] :accepted []} rows)]
    (reject! problems)
    (reject! (used-problems {:mappings mappings :named (merge named externals)} accepted))
    (if (empty? accepted)
      {}
      {:events (vec (concat
                      (for [{:keys [target new-name]} accepted :when new-name]
                        {:type :tover.catalogues.warehouses.model/warehouse-registered
                         :data {:warehouse-id target :name new-name :status "active" :revision 1}})
                      (for [{:keys [external mapping target]} accepted]
                        {:type ::external-warehouse-mapped
                         :data (assoc (decided facts external "mapped" (inc (:mapping-revision mapping 0)))
                                      :warehouse-id target :state "confirmed")})))
       :fx     (accepted-fx scope (map (comp :connection-id :external) accepted))})))


(def ChangeWarehouseMapping
  "A blank warehouse removes the mapping."
  [:map [:external-warehouse-id ExternalWarehouseId] [:mapping-revision Revision]
   [:warehouse-id {:optional true} WarehouseId]])


(defcommand change-warehouse-mapping
  {:input ChangeWarehouseMapping :policy ::records/workspace-member :cofx command-cofx :emits #{::warehouse-mapping-changed}}
  [{:keys [scope externals mappings named warehouses] :as facts} {:keys [external-warehouse-id mapping-revision warehouse-id]}]
  (let [external (get externals external-warehouse-id)
        mapping  (get mappings external-warehouse-id)
        target   (some-> warehouse-id str/trim not-empty)
        same?    (if target (and (confirmed? mapping) (= target (:warehouse-id mapping))) (not (confirmed? mapping)))]
    (reject! (cond (or (nil? external) (nil? mapping) (not= mapping-revision (:mapping-revision mapping)))
                   [(problem external-warehouse-id :stale)]
                   same? []
                   target (if-let [code (target-code (by-id warehouses :warehouse-id) target)]
                            [(problem external-warehouse-id code)]
                            (used-problems {:mappings mappings :named (merge named externals)}
                                           [{:external external :target target}]))
                   :else []))
    (if same?
      {}
      {:events [{:type ::warehouse-mapping-changed
                 :data (cond-> (decided facts external "changed" (inc mapping-revision))
                         (and (confirmed? mapping) (:warehouse-id mapping)) (assoc :previous-warehouse-id (:warehouse-id mapping))
                         target (assoc :warehouse-id target :state "confirmed")
                         (nil? target) (assoc :state "removed"))}]
       :fx     (accepted-fx scope [(:connection-id external)])})))
