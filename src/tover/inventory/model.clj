(ns tover.inventory.model
  (:require
    [clojure.string :as str]
    [tover.inventory.schema :as schema]
    [tover.shared.rdf :as rdf])
  (:import
    [java.net URLEncoder]
    [java.nio.charset StandardCharsets]
    [org.eclipse.rdf4j.model IRI Literal Resource]
    [org.eclipse.rdf4j.model.impl SimpleValueFactory]
    [org.eclipse.rdf4j.model.vocabulary RDF XSD]
    [org.eclipse.rdf4j.repository RepositoryConnection]))


(def base
  "urn:tover:inventory-v1:")


(def ^:private fields
  {:entity-id             :entityId            :business-order :businessOrder :recorded-at   :recordedAt
   :semantic-version      :semanticVersion     :proposed-by    :proposedBy    :created-by    :createdBy     :previous-revision :previousRevision :cancelled? :cancelled :change-kind :changeKind
   :entry                 :operation           :kind           :kind          :type          :operationType :occurred-at       :occurredAt
   :lines                 :line                :inputs         :input         :outputs       :output        :receipts          :receipt
   :item-id               :item                :location       :location      :from-location :fromLocation  :to-location       :toLocation
   :original-operation-id :originalOperationId :id             :id})


(def ^:private reverse-fields
  (into {} (map (fn [[key value]] [(name value) key])) fields))


(def ^:private array-fields
  #{:lines :inputs :outputs :receipts})


(def ^:private value-factory
  (SimpleValueFactory/getInstance))


(defn term
  {:malli/schema [:=> [:cat :keyword] rdf/IRI]}
  [name]
  (rdf/iri (str base (clojure.core/name name))))


(defn- child
  {:malli/schema [:=> [:cat rdf/Resource :string] rdf/IRI]}
  [parent suffix]
  (rdf/iri (str parent ":" (URLEncoder/encode ^String suffix StandardCharsets/UTF_8))))


(defn- field-term
  {:malli/schema [:=> [:cat :keyword] rdf/IRI]}
  [field]
  (term (get fields field field)))


(defn- type!
  {:malli/schema [:=> [:cat rdf/Connection rdf/Resource rdf/Resource :keyword] :nil]}
  [conn graph subject type]
  (rdf/add! conn graph subject RDF/TYPE (term type)))


(defn- scoped!
  {:malli/schema [:=> [:cat rdf/Connection rdf/Resource rdf/Resource rdf/IRI :keyword] :nil]}
  [conn graph subject workspace type]
  (type! conn graph subject type)
  (rdf/add! conn graph subject (term :workspace) workspace))


(defn- location!
  {:malli/schema [:=> [:cat rdf/Connection rdf/Resource rdf/IRI :string] rdf/IRI]}
  [conn graph workspace name]
  (let [subject (child workspace (str "inventory-location:" name))]
    (scoped! conn graph subject workspace :Location)
    (rdf/add! conn graph subject (term :locationName) name)
    subject))


(defn- item!
  {:malli/schema [:=> [:cat rdf/Connection rdf/Resource rdf/IRI :string] rdf/IRI]}
  [conn graph workspace id]
  (let [subject (child workspace (str "inventory-item:" id))]
    (scoped! conn graph subject workspace :Item)
    (rdf/add! conn graph subject (term :itemId) id)
    subject))


(defn- scalar!
  {:malli/schema [:=> [:cat rdf/Connection rdf/Resource rdf/Resource rdf/IRI :keyword :any] :nil]}
  [conn graph subject workspace field value]
  (when (some? value)
    (let [value (cond
                  (= field :item-id) (item! conn graph workspace value)
                  (#{:location :from-location :to-location} field) (location! conn graph workspace value)
                  (keyword? value) (term value)
                  (#{:occurred-at :recorded-at} field) (.createLiteral value-factory ^String value XSD/DATETIME)
                  :else value)]
      (rdf/add! conn graph subject (field-term field) value))))


(defn- scalar-fields!
  {:malli/schema [:=> [:cat rdf/Connection rdf/Resource rdf/Resource rdf/IRI :map] :nil]}
  [conn graph subject workspace data]
  (doseq [[field value] data]
    (when (or (map? value) (vector? value))
      (schema/fail! :validation "An unsupported nested inventory field was supplied." {:field field}))
    (scalar! conn graph subject workspace field value)))


(defn- write-line!
  {:malli/schema [:=> [:cat rdf/Connection rdf/Resource rdf/Resource rdf/IRI :keyword :int :map] :nil]}
  [conn graph parent workspace field index line]
  (let [subject (child parent (str (name field) ":" index))]
    (scoped! conn graph subject workspace :Line)
    (when (= :inputs field) (type! conn graph subject :InputLine))
    (when (= :outputs field) (type! conn graph subject :OutputLine))
    (rdf/add! conn graph subject (term :position) index)
    (rdf/add! conn graph parent (field-term field) subject)
    (scalar-fields! conn graph subject workspace (dissoc line :id))
    (rdf/add! conn graph subject (term :lineId) (:id line))))


(defn- write-receipt!
  {:malli/schema [:=> [:cat rdf/Connection rdf/Resource rdf/Resource rdf/IRI :int :map] :nil]}
  [conn graph parent workspace index receipt]
  (let [subject (child parent (str "receipt:" index))]
    (scoped! conn graph subject workspace :Receipt)
    (rdf/add! conn graph subject (term :position) index)
    (rdf/add! conn graph parent (term :receipt) subject)
    (scalar-fields! conn graph subject workspace (dissoc receipt :lines))
    (doseq [[index line] (map-indexed vector (:lines receipt))]
      (write-line! conn graph subject workspace :lines index line))))


(defn- write-entry!
  {:malli/schema [:=> [:cat rdf/Connection rdf/Resource rdf/Resource rdf/IRI :map] :nil]}
  [conn graph subject workspace entry]
  (scoped! conn graph subject workspace :InventoryEntry)
  (case (:kind entry)
    :opening (type! conn graph subject :OpeningBalance)
    :correction (type! conn graph subject :ManualCorrection)
    :operation (do
                 (type! conn graph subject :Operation)
                 (type! conn graph subject (keyword (str/join (map str/capitalize (str/split (name (:type entry)) #"-")))))))
  (scalar-fields! conn graph subject workspace (apply dissoc entry array-fields))
  (doseq [field        [:lines :inputs :outputs]
          [index line] (map-indexed vector (get entry field))]
    (write-line! conn graph subject workspace field index line))
  (when (contains? entry :receipts)
    (rdf/add! conn graph subject (term :hasReceipts) true)
    (doseq [[index receipt] (map-indexed vector (:receipts entry))]
      (write-receipt! conn graph subject workspace index receipt))))


(defn write-record!
  "Write one immutable semantic record with ordered operation lines and stable item/location references."
  {:malli/schema [:=> [:cat rdf/Connection rdf/Resource rdf/IRI :map] :nil]}
  [conn graph workspace record]
  (let [subject   (child workspace (str "inventory-record:" (:id record)))
        operation (child subject "operation")]
    (scoped! conn graph subject workspace :Record)
    (scalar-fields! conn graph subject workspace (dissoc record :entry))
    (rdf/add! conn graph subject (term :operation) operation)
    (write-entry! conn graph operation workspace (:entry record))))


(defn- statements
  {:malli/schema [:=> [:cat rdf/Connection rdf/Resource rdf/Resource] [:vector :any]]}
  [conn graph subject]
  (with-open [result (.getStatements ^RepositoryConnection conn subject nil nil false (into-array Resource [graph]))]
    (vec (iterator-seq result))))


(defn- decode-value
  {:malli/schema [:=> [:cat rdf/Connection rdf/Resource :keyword rdf/Value] :any]}
  [conn graph field value]
  (cond
    (= field :item-id) (rdf/value-at conn graph value (term :itemId))
    (#{:location :from-location :to-location} field) (rdf/value-at conn graph value (term :locationName))
    (instance? Literal value) (if (= XSD/DATETIME (.getDatatype ^Literal value))
                                (.getLabel ^Literal value)
                                (rdf/value value))
    (and (instance? IRI value) (str/starts-with? (str value) base)) (keyword (subs (str value) (count base)))
    :else (schema/fail! :invalid-storage "Unknown inventory resource reference." {:field field})))


(declare read-node)


(defn- read-array
  {:malli/schema [:=> [:cat rdf/Connection rdf/Resource [:sequential :any]] [:vector :map]]}
  [conn graph statements]
  (->> statements
       (map #(read-node conn graph (.getObject ^org.eclipse.rdf4j.model.Statement %)))
       (sort-by :position)
       (mapv #(dissoc % :position))))


(defn- read-node
  {:malli/schema [:=> [:cat rdf/Connection rdf/Resource rdf/Resource] :map]}
  [conn graph subject]
  (let [properties (->> (statements conn graph subject)
                        (filter #(str/starts-with? (str (.getPredicate ^org.eclipse.rdf4j.model.Statement %)) base))
                        (group-by #(subs (str (.getPredicate ^org.eclipse.rdf4j.model.Statement %)) (count base))))
        data       (reduce-kv
                     (fn [result property statements]
                       (let [field  (get reverse-fields property (keyword property))
                             object (.getObject ^org.eclipse.rdf4j.model.Statement (first statements))]
                         (cond
                           (#{"workspace" "hasReceipts"} property) result
                           (= "operation" property) (assoc result :entry (read-node conn graph object))
                           (= "lineId" property) (assoc result :id (rdf/value object))
                           (array-fields field) (assoc result field (read-array conn graph statements))
                           :else (assoc result field (decode-value conn graph field object)))))
                     {} properties)]
    (cond-> data
      (contains? properties "hasReceipts") (update :receipts #(or % [])))))


(defn read-record
  "Read one immutable revision, without realizing the workspace history."
  {:malli/schema [:=> [:cat rdf/Connection rdf/Resource rdf/Resource] :map]}
  [conn graph subject]
  (read-node conn graph subject))


(defn read-entity-history
  "Read only the revisions of one authorized workspace operation."
  {:malli/schema [:=> [:cat rdf/Connection rdf/Resource :string] [:vector :map]]}
  [conn graph entity-id]
  (->> (rdf/select conn {:prefixes {:t (str "<" base ">") :rdf "<http://www.w3.org/1999/02/22-rdf-syntax-ns#>"}
                         :select   '[?record]
                         :where    '[[:graph ?history [[?record :rdf/type :t/Record]
                                                       [?record :t/entityId ?entity]]]]}
                   {'?history graph '?entity entity-id})
       (map #(read-record conn graph (:record %)))
       (sort-by :revision)
       vec))


(defn read-entity-record
  "Read one immutable revision of an authorized workspace operation."
  {:malli/schema [:=> [:cat rdf/Connection rdf/Resource :string :int] [:maybe :map]]}
  [conn graph entity-id revision]
  (when-let [record (:record (first (rdf/select conn
                                                {:prefixes {:t (str "<" base ">") :rdf "<http://www.w3.org/1999/02/22-rdf-syntax-ns#>"}
                                                 :select   '[?record]
                                                 :where    '[[:graph ?history [[?record :rdf/type :t/Record]
                                                                               [?record :t/entityId ?entity]
                                                                               [?record :t/revision ?revision]]]]
                                                 :limit    1}
                                                {'?history graph '?entity entity-id '?revision revision})))]
    (read-record conn graph record)))


(defn read-history
  "Reconstruct canonical accepted revisions exclusively from semantic RDF statements."
  {:malli/schema [:=> [:cat rdf/Connection rdf/Resource] [:vector :map]]}
  [conn graph]
  (->> (rdf/select conn {:prefixes {:t (str "<" base ">") :rdf "<http://www.w3.org/1999/02/22-rdf-syntax-ns#>"}
                         :select   '[?record]
                         :where    '[[:graph ?history [[?record :rdf/type :t/Record]]]]}
                   {'?history graph})
       (map #(read-node conn graph (:record %)))
       (sort-by :sequence)
       vec))
