(ns tover.catalogues.records
  "What every catalogue kind shares (board chapters 08–12): workspace membership, the unique-name rule
  (trimmed, case-insensitive, archived included), reviewed-revision checks, active relationships and the
  register / update / archive / restore decisions. A kind is described by a spec map; its namespace keeps
  only its declarations."
  (:require
    [bel-rdf.adapter :as source]
    [bel.core :as bel]
    [bel.runtime :as runtime]
    [bel.store :as store]
    [clojure.string :as str]))


(def Id
  [:string {:min 1 :max 500}])


(def Status
  [:enum "active" "archived" "deleted"])


(def Text
  "An optional free-text field; blank clears it."
  [:maybe [:string {:max 1000}]])


(def Spec
  [:map
   [:id-key :keyword]
   [:events [:map [:registered :qualified-keyword] [:updated :qualified-keyword]
             [:archived :qualified-keyword] [:restored :qualified-keyword]]]
   [:messages [:map-of :keyword :string]]
   [:fields [:vector :keyword]]
   [:references {:optional true} [:map-of :keyword [:tuple :keyword :keyword]]]])


(defmethod runtime/policy ::workspace-member
  [_ {{:keys [memberships]} :principal scope :scope} _]
  (boolean (some #(= (:workspace-id scope) (:workspace-id %)) memberships)))


(defn fail!
  {:malli/schema [:=> [:cat :keyword :string :map] :nil]}
  [type message data]
  (throw (ex-info message (assoc data :type type))))


(defn name-key
  {:malli/schema [:=> [:cat :string] :string]}
  [name]
  (str/lower-case (str/trim name)))


(defn check-name!
  "The trimmed name, unless blank, too long or held by another live record of the kind."
  {:malli/schema [:=> [:cat Spec [:sequential :map] :string :string] :string]}
  [{:keys [id-key messages]} records id name]
  (let [name (str/trim name)]
    (when-not (<= 1 (count name) 500)
      (fail! :validation (:invalid-name messages) {:field :name}))
    (when-let [owner (some #(when (and (= (name-key name) (name-key (:name %)))
                                       (not= id (get % id-key))
                                       (not= "deleted" (:status %)))
                              %)
                           records)]
      (fail! :name-taken (:name-taken messages) {:record (select-keys owner [id-key :name :status]) :attempted name}))
    name))


(defn check-related!
  "An id chosen for a relationship must name an active record of `records` (`{:id :status}` maps)."
  {:malli/schema [:=> [:cat [:maybe :string] [:sequential :map] :keyword] :nil]}
  [id records type]
  (when (and id (not-any? #(and (= id (:id %)) (not (#{"archived" "deleted"} (:status %)))) records))
    (fail! type "Choose an active record of this workspace." {:id id})))


(defn current!
  "The reviewed record, or a rejection. Stale is checked before any status rule."
  {:malli/schema [:=> [:cat Spec [:maybe :map] :int] :map]}
  [{:keys [messages]} record revision]
  (when (or (nil? record) (= "deleted" (:status record)))
    (fail! :not-found (:not-found messages) {}))
  (when-not (= revision (:revision record))
    (fail! :stale-catalogue (:stale messages) {:current record}))
  record)


(defn publish
  {:malli/schema [:=> [:cat :map] [:vector :any]]}
  [scope]
  [[:bel/publish {:topic :tover.inventory.web/changed :scope-id (:workspace-id scope) :data {}}]])


(defn all-records
  "Every record of model `model-id` in the scope's workspace, deleted ones included."
  {:malli/schema [:=> [:cat :map :qualified-keyword :keyword] [:vector :map]]}
  [context model-id id-key]
  ;; ponytail: full scan of the workspace's records per decision; add a normalized-name index if catalogues grow large.
  (let [definition (bel/get-model model-id)
        field      (get-in (source/vocabulary definition) [:fields id-key])]
    (runtime/with-store context :catalogue :repeatable-read
      (fn [{:keys [tx scope] :as context}]
        (let [adapter (store/store context :catalogue)
              ids     (runtime/coeffect :rdf/query context {:query    '{:select [?id] :where [[?row ?field ?id]]}
                                                            :bindings {:field field}})]
          (into [] (keep #(store/-load adapter tx (store/store-scope scope) definition (:id %))) ids))))))


(defn choices
  "Records as relationship choices: `{:id :name :status}`, sorted by name, deleted ones left out."
  {:malli/schema [:=> [:cat [:sequential :map] :keyword] [:vector :map]]}
  [records id-key]
  (->> records
       (remove #(= "deleted" (:status %)))
       (map #(-> (select-keys % [:name :status]) (assoc :id (get % id-key))))
       (sort-by (comp name-key :name))
       vec))


(defn label
  "How a saved relationship reads: unknown or archived records are marked archived."
  {:malli/schema [:=> [:cat :map [:maybe :string]] [:maybe :map]]}
  [records id]
  (when id
    (let [record (get records id)]
      {:id id :name (or (:name record) id) :archived? (or (nil? record) (contains? #{"archived" "deleted"} (:status record)))})))


(defn index
  {:malli/schema [:=> [:cat [:sequential :map]] :map]}
  [records]
  (into {} (map (juxt :id identity)) records))


(defn- check-references!
  "Each changed relationship field must name an active record of its kind in `references`."
  {:malli/schema [:=> [:cat Spec [:maybe :map] :map [:maybe :map]] :nil]}
  [spec references changed before]
  (doseq [[field [kind rejection]] (:references spec)
          :when                    (not= (get changed field) (get before field))]
    (check-related! (get changed field) (get references kind []) rejection)))


(defn- blank->nil
  {:malli/schema [:=> [:cat :any] :any]}
  [value]
  (if (and (string? value) (str/blank? value)) nil (cond-> value (string? value) str/trim)))


(defn register
  "Decide a registration: a fresh live name and active relationships."
  {:malli/schema [:=> [:cat Spec :map :map] :map]}
  [{:keys [id-key events fields] :as spec} {:keys [records references scope]} input]
  (let [id      (get input id-key)
        name    (check-name! spec records id (:name input))
        values  (into {} (comp (map (juxt identity #(blank->nil (get input %)))) (remove (comp nil? second))) fields)
        created (merge values {id-key id :name name :status "active" :revision 1})]
    (check-references! spec references created nil)
    {:events [{:type (:registered events) :data created}] :fx (publish scope)}))


(defn update-record
  "Decide an edit of the reviewed revision. An absent key keeps the field, a present blank clears it, and an
  unchanged save records nothing. The event carries the resulting values."
  {:malli/schema [:=> [:cat Spec :map :map] :map]}
  [{:keys [id-key events fields] :as spec} {:keys [record records references scope]} {:keys [revision] :as input}]
  (let [record  (current! spec record revision)
        name    (if (contains? input :name) (check-name! spec records (get record id-key) (:name input)) (:name record))
        changed (reduce (fn [changed field]
                          (if (contains? input field) (assoc changed field (blank->nil (get input field))) changed))
                        (assoc record :name name) fields)
        changed (into {} (remove (comp nil? val)) changed)]
    (check-references! spec references changed record)
    (if (= changed record)
      {}
      {:events [{:type (:updated events) :data (update changed :revision inc)}] :fx (publish scope)})))


(defn transition
  {:malli/schema [:=> [:cat Spec :qualified-keyword :map :string :map] :map]}
  [{:keys [id-key]} type record status scope]
  {:events [{:type type :data {id-key (get record id-key) :status status :revision (inc (:revision record))}}]
   :fx     (publish scope)})


(defn archive
  {:malli/schema [:=> [:cat Spec :map :map] :map]}
  [{:keys [events messages] :as spec} {:keys [record scope]} {:keys [revision]}]
  (let [record (current! spec record revision)]
    (when-not (= "active" (:status record))
      (fail! :already-archived (:already-archived messages) {}))
    (transition spec (:archived events) record "archived" scope)))


(defn restore
  {:malli/schema [:=> [:cat Spec :map :map] :map]}
  [{:keys [events messages] :as spec} {:keys [record scope]} {:keys [revision]}]
  (let [record (current! spec record revision)]
    (when-not (= "archived" (:status record))
      (fail! :not-archived (:not-archived messages) {}))
    (transition spec (:restored events) record "active" scope)))


(defn catalogue
  "Read-model rows of `records`: one record by `:id` whatever its status, else the active or archived ones,
  sorted by name, each with `:id` and its relationships as labels."
  {:malli/schema [:=> [:cat Spec [:sequential :map] :map :map] [:vector :map]]}
  [{:keys [id-key] :as spec} records references {:keys [archived? id]}]
  (let [labels (update-vals (:references spec) (fn [[kind _]] (index (get references kind []))))]
    (->> records
         (filter (if id
                   #(and (= id (get % id-key)) (not= "deleted" (:status %)))
                   #(= (if archived? "archived" "active") (:status %))))
         (sort-by (comp name-key :name))
         (mapv (fn [record]
                 (reduce-kv (fn [row field records] (assoc row field (label records (get record field))))
                            (assoc record :id (get record id-key))
                            labels))))))
