(ns tover.inventory.affected-query
  "Query the complete affected item/reference component without decoding unrelated history."
  (:require
    [bel.validation :as validation]
    [clojure.set :as set]
    [clojure.string :as str]
    [tover.inventory.model :as model]
    [tover.shared.rdf :as rdf]
    [tover.shared.schema :as shared])
  (:import
    [org.eclipse.rdf4j.model Value]
    [org.eclipse.rdf4j.query BindingSet]
    [org.eclipse.rdf4j.repository RepositoryConnection]))


(def ^:private prefix
  "PREFIX t: <urn:tover:inventory-v1:> ")


(def ^:private latest
  (str "?record a t:Record; t:entityId ?entityId; t:revision ?revision; t:operation ?entry. "
       "FILTER NOT EXISTS { ?later t:entityId ?entityId; t:revision ?laterRevision. "
       "FILTER (?laterRevision > ?revision) } "))


(def ^:private active
  "FILTER NOT EXISTS { ?record t:cancelled true } ")


(def ^:private item-path
  "?entry (t:line|t:input|t:output|t:receipt/t:line) ?line. ?line t:item ?item. ?item t:itemId ?itemId. ")


(defn- graph
  {:malli/schema [:=> [:cat shared/Scope] rdf/IRI]}
  [scope]
  (rdf/iri (str "urn:tover:workspace:" (:workspace-id scope) ":inventory-v1:history")))


(defn- select
  {:malli/schema [:=> [:cat rdf/Connection :string :map] [:vector :map]]}
  [conn query bindings]
  (let [prepared (.prepareTupleQuery ^RepositoryConnection conn (str prefix query))]
    (.setMaxExecutionTime prepared 15)
    (doseq [[key value] bindings]
      (.setBinding prepared (name key) (if (instance? Value value) value (rdf/literal value))))
    (with-open [result (.evaluate prepared)]
      (mapv (fn [^BindingSet row]
              (into {} (map (fn [key] [(keyword key) (.getValue row key)])) (.getBindingNames row)))
            (iterator-seq result)))))


(defn- matching
  "Use only bound values in each bounded identifier batch."
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :string :string :string [:set :string]] [:vector :map]]}
  [conn scope columns patterns field identifiers]
  (into []
        (mapcat (fn [batch]
                  (let [bindings   (into {:graph (graph scope)}
                                         (map-indexed #(vector (keyword (str "value" %1)) %2) batch))
                        candidates (str/join " UNION "
                                             (map #(str "{ BIND (?value" % " AS ?" field ") }") (range (count batch))))]
                    ;; Starting with bound candidates lets RDF4J use item/entity indexes;
                    ;; filtering an unbound identifier after the pattern scans all records.
                    (select conn (str "SELECT DISTINCT " columns " WHERE { " candidates
                                      " GRAPH ?graph { " patterns " }}") bindings))))
        (partition-all 250 (sort identifiers))))


(defn- latest-metadata
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope [:set :string]] [:vector :map]]}
  [conn scope entity-ids]
  (matching conn scope "?entityId ?itemId ?reference"
            (str latest "OPTIONAL { " item-path "} OPTIONAL { ?entry t:originalOperationId ?reference. } ")
            "entityId" entity-ids))


(defn- touching-entities
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope [:set :string]] [:set :string]]}
  [conn scope item-ids]
  (into #{} (map #(rdf/value (:entityId %)))
        (matching conn scope "?entityId" (str item-path latest active)
                  "itemId" item-ids)))


(defn- referencing-entities
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope [:set :string]] [:set :string]]}
  [conn scope entity-ids]
  (into #{} (map #(rdf/value (:entityId %)))
        (matching conn scope "?entityId" (str "?entry t:originalOperationId ?reference. " latest active)
                  "reference" entity-ids)))


(defn- entry-item-ids
  {:malli/schema [:=> [:cat :map] [:set :string]]}
  [entry]
  (into (if-let [replacement (:replacement entry)] (entry-item-ids replacement) #{})
        (keep :item-id)
        (concat (:lines entry) (:inputs entry) (:outputs entry) (mapcat :lines (:receipts entry)))))


(defn- entry-references
  {:malli/schema [:=> [:cat :map] [:set :string]]}
  [entry]
  (into (if-let [replacement (:replacement entry)] (entry-references replacement) #{})
        (keep entry [:target-id :original-operation-id])))


(defn- component
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope [:vector :map] [:set :string]] :map]}
  [conn scope entries target-ids]
  (loop [item-ids      (into #{} (mapcat entry-item-ids) entries)
         entity-ids    (into target-ids (mapcat entry-references) entries)
         read-items    #{}
         read-entities #{}]
    (let [new-items    (set/difference item-ids read-items)
          new-entities (set/difference entity-ids read-entities)]
      (if (and (empty? new-items) (empty? new-entities))
        {:item-ids item-ids :entity-ids entity-ids}
        (let [metadata     (latest-metadata conn scope new-entities)
              linked-items (into #{} (keep #(some-> (:itemId %) rdf/value)) metadata)
              references   (into #{} (keep #(some-> (:reference %) rdf/value)) metadata)]
          (recur (into item-ids linked-items)
                 (into (into (into entity-ids references)
                             (touching-entities conn scope new-items))
                       (referencing-entities conn scope new-entities))
                 (into read-items new-items)
                 (into read-entities new-entities)))))))


(defn- records
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope [:set :string]] [:vector :map]]}
  [conn scope entity-ids]
  (->> (matching conn scope "?record ?sequence"
                 "?record a t:Record; t:entityId ?entityId; t:sequence ?sequence. "
                 "entityId" entity-ids)
       (sort-by #(rdf/value (:sequence %)))
       (mapv #(model/read-record conn (graph scope) (:record %)))))


(defn- sequence-number
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope] :int]}
  [conn scope]
  (or (some-> (first (select conn
                             "SELECT (MAX(?number) AS ?sequence) WHERE { GRAPH ?graph { ?record a t:Record; t:sequence ?number. }}"
                             {:graph (graph scope)}))
              :sequence rdf/value)
      0))


(defn closure
  "Read all revisions in the item/reference component of complete selected entries.
   Latest operations determine membership; replaced and cancelled revisions remain available
   inside selected entities. Sequence is the workspace-wide high-water mark."
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope [:vector :map] [:set :string]] :map]}
  [conn scope entries target-ids]
  (validation/check! shared/Scope scope)
  (let [affected (component conn scope entries target-ids)]
    (assoc affected :records (records conn scope (:entity-ids affected))
           :sequence (sequence-number conn scope))))
