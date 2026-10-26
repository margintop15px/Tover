(ns tover.inventory.derivation
  "Executable SPARQL projections and ordered timeline invariants over accepted semantic RDF."
  (:refer-clojure :exclude [derive])
  (:require
    [clojure.java.io :as io]
    [clojure.set :as set]
    [tover.inventory.model :as model]
    [tover.inventory.schema :as schema]
    [tover.shared.rdf :as rdf])
  (:import
    [java.time Instant]
    [org.eclipse.rdf4j.model Literal]
    [org.eclipse.rdf4j.model.vocabulary RDF XSD]
    [org.eclipse.rdf4j.repository RepositoryConnection]
    [org.eclipse.rdf4j.repository.sail SailRepository]
    [org.eclipse.rdf4j.sail.memory MemoryStore]))


(def ^:private queries
  (delay (into {} (map (fn [name] [name (slurp (io/resource (str "inventory/queries/" name ".rq")))])
                       ["effects" "balances" "timeline" "lineage"]))))


(def ^:private ontology-graph
  (rdf/iri "urn:tover:inventory-v1:ontology"))


(def ^:private effects-graph
  (rdf/iri "urn:tover:inventory-v1:derived-effects"))


(def ^:private effect-fields
  {:entityId   :operation-id :revision      :operation-revision :businessOrder :business-order
   :recordedAt :recorded-at  :operationType :type               :occurredAt    :occurred-at
   :eventId    :event-id     :receiptId     :receipt-id         :eventIndex    :event-index
   :lineIndex  :line-index   :lineId        :line-id            :itemId        :item-id})


(defn- decoded-value
  {:malli/schema [:=> [:cat rdf/Value] :any]}
  [value]
  (if (and (instance? Literal value) (= XSD/DATETIME (.getDatatype ^Literal value)))
    (.getLabel ^Literal value)
    (rdf/value value)))


(defn- query
  {:malli/schema [:=> [:cat rdf/Connection :string [:map-of :string rdf/Value]] [:vector :map]]}
  [conn name bindings]
  (let [prepared (.prepareTupleQuery ^RepositoryConnection conn ^String (get @queries name))]
    (.setMaxExecutionTime prepared 15)
    (doseq [[name value] bindings]
      (.setBinding prepared name value))
    (with-open [result (.evaluate prepared)]
      (mapv (fn [row]
              (into {} (map (fn [binding] [(keyword (.getName ^org.eclipse.rdf4j.query.Binding binding))
                                           (decoded-value (.getValue ^org.eclipse.rdf4j.query.Binding binding))]) row)))
            (iterator-seq result)))))


(defn- policy-key
  {:malli/schema [:=> [:cat rdf/Value] :keyword]}
  [value]
  (keyword (subs (str value) (count model/base))))


(defn- effect
  {:malli/schema [:=> [:cat :int :map] :map]}
  [ordinal row]
  (-> (set/rename-keys (dissoc row :record) effect-fields)
      (update :mode policy-key)
      (update :kind policy-key)
      (cond-> (:operationType row) (update :type policy-key))
      (assoc :ordinal ordinal)))


(defn- write-effect!
  {:malli/schema [:=> [:cat rdf/Connection :map] :nil]}
  [conn effect]
  (let [subject (rdf/iri (str effects-graph ":" (:ordinal effect)))]
    (rdf/add! conn effects-graph subject RDF/TYPE (model/term :Effect))
    (doseq [[property value] {:itemId  (:item-id effect)           :locationName (:location effect)
                              :ordinal (:ordinal effect)           :quantity     (:quantity effect)
                              :mode    (model/term (:mode effect)) :delta        (:delta effect)}]
      (rdf/add! conn effects-graph subject (model/term property) value))))


(defn- lineage-line
  {:malli/schema [:=> [:cat :map :string] :map]}
  [row prefix]
  (let [field #(get row (keyword (str prefix %)))]
    {:id       (field "Id")       :line-id  (field "Id")       :item-id  (field "ItemId")
     :location (field "Location") :quantity (field "Quantity") :position (field "Position")}))


(defn- lineage
  {:malli/schema [:=> [:cat [:vector :map]] :map]}
  [rows]
  (let [relationships (mapv (fn [row]
                              {:operation-id   (:entityId row) :operation-revision (:revision row)
                               :business-order (:businessOrder row) :occurred-at   (:occurredAt row)
                               :allocation     :unknown :input (dissoc (lineage-line row "input") :position)
                               :output         (dissoc (lineage-line row "output") :position)}) rows)
        events        (->> rows
                           (group-by :entityId)
                           vals
                           (map (fn [rows]
                                  (let [row   (first rows)
                                        lines (fn [prefix]
                                                (->> rows (map #(lineage-line % prefix)) distinct
                                                     (sort-by :position) (mapv #(dissoc % :position))))]
                                    {:operation-id (:entityId row)   :business-order (:businessOrder row)
                                     :occurred-at  (:occurredAt row) :allocation     :unknown             :inputs (lines "input") :outputs (lines "output")})))
                           (sort-by (juxt #(Instant/parse (:occurred-at %)) :business-order :operation-id))
                           vec)]
    {:lineage events :lineage-relationships relationships}))


(defn derive
  "Derive effective effects, reset-aware balances, dated production relationships and timeline violations."
  {:malli/schema [:=> [:cat rdf/Connection rdf/Resource] :map]}
  [conn graph]
  (let [bindings      {"history" graph "ontology" ontology-graph}
        effects       (mapv effect (range) (query conn "effects" bindings))
        relationships (lineage (query conn "lineage" bindings))
        repo          (SailRepository. (MemoryStore.))]
    (.init repo)
    (try
      (with-open [effect-conn (.getConnection repo)]
        (.begin effect-conn)
        (doseq [effect effects] (write-effect! effect-conn effect))
        (.commit effect-conn)
        (merge relationships
               {:effects    effects
                :balances   (into {} (map (fn [{:keys [itemId location quantity]}] [[itemId location] quantity]))
                                  (query effect-conn "balances" {"effects" effects-graph}))
                :violations (mapv (fn [{:keys [ordinal after]}]
                                    (assoc (get effects ordinal)
                                           :after after
                                           :message "This timeline leaves unavailable stock or an unsupported balance."))
                                  (query effect-conn "timeline" {"effects" effects-graph}))}))
      (finally (.shutDown repo)))))


(defn validate!
  "Execute the SPARQL timeline invariants in an acceptance transaction; return its derived projection."
  {:malli/schema [:=> [:cat rdf/Connection rdf/Resource] :map]}
  [conn graph]
  (let [projection (derive conn graph)]
    (when (seq (:violations projection))
      (schema/fail! :validation "The proposed history violates its ordered inventory timeline."
                    {:errors (:violations projection)}))
    projection))
