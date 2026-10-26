(ns tover.inventory.semantics
  "Versioned declarative inventory policy and SHACL validation of one workspace snapshot."
  (:require
    [clojure.java.io :as io]
    [tover.inventory.schema :as schema]
    [tover.shared.rdf :as rdf])
  (:import
    [java.io StringReader StringWriter]
    [org.eclipse.rdf4j.model Resource]
    [org.eclipse.rdf4j.model.vocabulary SHACL]
    [org.eclipse.rdf4j.repository RepositoryConnection]
    [org.eclipse.rdf4j.rio RDFFormat Rio]
    [org.eclipse.rdf4j.sail.shacl ShaclValidator]))


(def version
  "inventory-v1.0.0")


(def ^:private base
  "urn:tover:inventory-v1:")


(def ^:private ontology
  (delay (slurp (io/resource "inventory/ontology.ttl"))))


(def ^:private effect-policy
  (delay
    (let [model (Rio/parse (StringReader. @ontology) base RDFFormat/TURTLE (make-array Resource 0))]
      (into {} (for [statement model
                     :when     (= (str base "stockEffect") (str (.getPredicate ^org.eclipse.rdf4j.model.Statement statement)))]
                 [(keyword (subs (str (.getSubject ^org.eclipse.rdf4j.model.Statement statement)) (count base)))
                  (keyword (subs (str (.getObject ^org.eclipse.rdf4j.model.Statement statement)) (count base)))])))))


(def ^:private validator
  (delay
    (-> (ShaclValidator/builder)
        (.setValidationResultsLimitTotal 100)
        (.withShapes (io/resource "inventory/shapes.ttl") base RDFFormat/TURTLE)
        (.build))))


(defn stock-effect
  {:malli/schema [:=> [:cat :keyword [:maybe :keyword]] :keyword]}
  [kind type]
  (or (get @effect-policy (if (= kind :operation) type kind))
      (schema/fail! :validation "No stock effect is defined by the semantic package." {:kind kind :type type})))


(defn- violations
  {:malli/schema [:=> [:cat :any] [:vector :map]]}
  [report]
  (let [model (.asModel ^org.eclipse.rdf4j.sail.shacl.results.ValidationReport report)]
    (vec (for [statement (.filter model nil SHACL/RESULT nil (make-array Resource 0))
               :let      [node (.getObject ^org.eclipse.rdf4j.model.Statement statement)
                          value (fn [predicate]
                                  (some-> (.filter model node predicate nil (make-array Resource 0))
                                          .objects first .stringValue))]]
           {:message    (or (value SHACL/RESULT_MESSAGE) "The proposed inventory graph violates its shapes.")
            :focus-node (value SHACL/FOCUS_NODE) :path (value SHACL/RESULT_PATH)
            :shape      (value SHACL/SOURCE_SHAPE)}))))


(defn validate!
  "Validate the staged workspace graph, including uncommitted selected revisions."
  {:malli/schema [:=> [:cat rdf/Connection rdf/Resource] :nil]}
  [conn graph]
  (let [writer (StringWriter.)]
    (with-open [statements (.getStatements ^RepositoryConnection conn nil nil nil false (into-array Resource [graph]))]
      (Rio/write statements writer RDFFormat/TURTLE))
    (let [report (locking @validator (.validate @validator (str @ontology "\n" writer) base RDFFormat/TURTLE))]
      (when-not (.conforms ^org.eclipse.rdf4j.sail.shacl.results.ValidationReport report)
        (schema/fail! :validation "The proposed inventory history violates its semantic model."
                      {:semantic-version version :errors (violations report)}))))
  nil)
