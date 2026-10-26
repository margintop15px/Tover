(ns tover.inventory.history-query
  "Read an item's accepted effects and dated ancestry without realizing workspace history."
  (:require
    [bel.validation :as validation]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [tover.inventory.ledger :as ledger]
    [tover.inventory.model :as model]
    [tover.inventory.read-model :as read-model]
    [tover.inventory.schema :as schema]
    [tover.shared.rdf :as rdf]
    [tover.shared.schema :as shared])
  (:import
    [java.time Instant]
    [org.eclipse.rdf4j.model Value]
    [org.eclipse.rdf4j.model.impl SimpleValueFactory]
    [org.eclipse.rdf4j.model.vocabulary XSD]
    [org.eclipse.rdf4j.query BindingSet]
    [org.eclipse.rdf4j.repository RepositoryConnection]))


(def ^:private prefixes
  "PREFIX r: <urn:tover:inventory-read-v1:> PREFIX t: <urn:tover:inventory-v1:> ")


(defn- select
  {:malli/schema [:=> [:cat rdf/Connection :string :map] [:vector :map]]}
  [conn query bindings]
  (let [prepared (.prepareTupleQuery ^RepositoryConnection conn (str prefixes query))]
    (.setMaxExecutionTime prepared 15)
    (doseq [[key value] bindings]
      (.setBinding prepared (name key) (if (instance? Value value) value (rdf/literal value))))
    (with-open [result (.evaluate prepared)]
      (mapv (fn [^BindingSet row]
              (into {} (map (fn [key] [(keyword key) (.getValue row key)])) (.getBindingNames row)))
            (iterator-seq result)))))


(defn- decode
  {:malli/schema [:=> [:cat rdf/Value] :map]}
  [value]
  (edn/read-string {:readers {} :default (fn [tag _]
                                           (schema/fail! :invalid-storage "Unsupported inventory read data." {:tag tag}))}
                   (rdf/value value)))


(defn- date
  {:malli/schema [:=> [:cat :string] rdf/Literal]}
  [value]
  (.createLiteral (SimpleValueFactory/getInstance) ^String value XSD/DATETIME))


(defn- order-key
  {:malli/schema [:=> [:cat :map] [:vector :any]]}
  [event]
  [(Instant/parse (:occurred-at event)) (:business-order event) (:event-index event 0) (:phase event 0)])


(defn- own-productions
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :string] [:vector :map]]}
  [conn scope item-id]
  (->> (select conn
               (str "SELECT DISTINCT ?node ?payload WHERE { GRAPH ?graph { "
                    "?node a r:Production; (r:inputItemId|r:outputItemId) ?itemId; r:payload ?payload. }}")
               {:graph (read-model/graph scope) :itemId item-id})
       (mapv #(decode (:payload %)))))


(defn- earlier-producers
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :string :map] [:vector :map]]}
  [conn scope item-id event]
  (->> (select conn
               (str "SELECT ?node ?payload WHERE { GRAPH ?graph { "
                    "?node a r:Production; r:outputItemId ?itemId; r:occurredAt ?occurredAt; "
                    "r:businessOrder ?businessOrder; r:payload ?payload. "
                    "FILTER (?occurredAt < ?cutoffTime || (?occurredAt = ?cutoffTime && ?businessOrder < ?cutoffOrder)) }}")
               {:graph       (read-model/graph scope) :itemId item-id :cutoffTime (date (:occurred-at event))
                :cutoffOrder (:business-order event)})
       (mapv #(decode (:payload %)))))


(defn- ancestry
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :string [:vector :map] [:maybe :boolean]] :map]}
  [conn scope item-id own-events include-ancestors?]
  (if-not include-ancestors?
    {}
    (loop [pending (filterv #(some (fn [line] (= item-id (:item-id line))) (:outputs %)) own-events)
           found   {}]
      (if-let [event (peek pending)]
        (if (contains? found (:operation-id event))
          (recur (pop pending) found)
          (let [parents (mapcat #(earlier-producers conn scope % event) (distinct (map :item-id (:inputs event))))]
            (recur (into (pop pending) parents) (assoc found (:operation-id event) event))))
        found))))


(defn- input-cutoffs
  {:malli/schema [:=> [:cat :map] :map]}
  [ancestors]
  (reduce (fn [cutoffs event]
            (reduce (fn [result {:keys [item-id]}]
                      (update result item-id #(if (or (nil? %) (pos? (compare (order-key event) (order-key %)))) event %)))
                    cutoffs (:inputs event)))
          {} (vals ancestors)))


(def ^:private cutoff-filter
  (str "FILTER (?occurredAt < ?cutoffTime || (?occurredAt = ?cutoffTime && "
       "(?businessOrder < ?cutoffOrder || (?businessOrder = ?cutoffOrder && "
       "(?eventIndex < ?cutoffIndex || (?eventIndex = ?cutoffIndex && ?phase <= ?cutoffPhase)))))) "))


(defn- effects
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :string [:maybe :map]] [:vector :map]]}
  [conn scope item-id cutoff]
  (mapv (fn [{:keys [occurredAt businessOrder eventIndex eventId phase lineIndex payload]}]
          {:order  [(Instant/parse (.stringValue ^Value occurredAt)) (rdf/value businessOrder)
                    (rdf/value eventIndex) (rdf/value eventId) (rdf/value phase) (rdf/value lineIndex)]
           :record (decode payload)})
        (select conn
                (str "SELECT ?occurredAt ?businessOrder ?eventIndex ?eventId ?phase ?lineIndex ?payload WHERE { GRAPH ?graph { "
                     "?node a r:Effect; r:itemId ?itemId; r:payload ?payload; r:occurredAt ?occurredAt; "
                     "r:businessOrder ?businessOrder; r:eventIndex ?eventIndex; r:eventId ?eventId; "
                     "r:phase ?phase; r:lineIndex ?lineIndex. "
                     (when cutoff
                       cutoff-filter)
                     "}} ORDER BY ?occurredAt ?businessOrder ?eventIndex ?eventId ?phase ?lineIndex")
                (cond-> {:graph (read-model/graph scope) :itemId item-id}
                  cutoff (assoc :cutoffTime (date (:occurred-at cutoff)) :cutoffOrder (:business-order cutoff)
                                :cutoffIndex (:event-index cutoff 0) :cutoffPhase (:phase cutoff 0))))))


(defn- audit
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :string] :map]}
  [conn scope item-id]
  (let [graph (rdf/iri (str "urn:tover:workspace:" (:workspace-id scope) ":inventory-v1:history"))
        rows  (select conn
                      (str "SELECT ?record ?sequence WHERE { "
                           "{ SELECT DISTINCT ?entityId WHERE { GRAPH ?graph { "
                           "?item t:itemId ?itemId. ?line t:item ?item. "
                           "?entry (t:line|t:input|t:output|t:receipt/t:line) ?line. "
                           "?touched t:operation ?entry; t:entityId ?entityId. }}} "
                           "GRAPH ?graph { ?record a t:Record; t:entityId ?entityId; t:sequence ?sequence. } "
                           "} ORDER BY ?sequence")
                      {:graph graph :itemId item-id})]
    (ledger/item-audit (mapv #(model/read-record conn graph (:record %)) rows) item-id)))


(defn- metadata
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope [:set :string]] :map]}
  [conn scope item-ids]
  (let [bindings  (into {:graph (read-model/graph scope)} (map-indexed #(vector (keyword (str "item" %1)) %2) (sort item-ids)))
        variables (map #(str "?" (name %)) (remove #{:graph} (keys bindings)))]
    (into {} (map (fn [{:keys [id payload]}] [(rdf/value id) (decode payload)]))
          (select conn
                  (str "SELECT ?id ?payload WHERE { GRAPH ?graph { "
                       "?node a r:Item; r:id ?id; r:acceptedFilterName ?accepted; r:payload ?payload. "
                       "FILTER (?id IN (" (str/join "," variables) ")) }}") bindings))))


(defn history
  "Query only the selected item's effects, audit entities and production parents before consumption."
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :string [:maybe :boolean]] :map]}
  [conn scope item-id include-ancestors?]
  (validation/check! shared/Scope scope)
  (schema/text! item-id :item-id)
  (let [own-events (own-productions conn scope item-id)
        ancestors  (ancestry conn scope item-id own-events include-ancestors?)
        cutoffs    (dissoc (input-cutoffs ancestors) item-id)
        records    (->> (concat (effects conn scope item-id nil)
                                (mapcat (fn [[parent cutoff]] (effects conn scope parent cutoff)) cutoffs))
                        (sort-by :order) (mapv :record))
        events     (vec (vals (merge (into {} (map (juxt :operation-id identity)) own-events) ancestors)))
        audit      (audit conn scope item-id)
        items      (metadata conn scope (conj (set (keys cutoffs)) item-id))
        item       (or (get items item-id) (:item audit))]
    (when-not item
      (schema/fail! :not-found "This item has no accepted inventory history." {:item-id item-id}))
    (let [items (assoc items item-id item)]
      (assoc (ledger/item-history {:items items :records records :lineage events} item-id include-ancestors?)
             :item item :items items :audit-records (:audit-records audit)))))
