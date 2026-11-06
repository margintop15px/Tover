(ns tover.integrations.semantics
  "Standalone validation; never installs shapes or writes to an inventory repository."
  (:require
    [bel.validation :as validation]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [tover.integrations.schema :as schema]
    [tover.shared.rdf :as rdf])
  (:import
    [org.eclipse.rdf4j.model Resource Statement]
    [org.eclipse.rdf4j.model.vocabulary SHACL]
    [org.eclipse.rdf4j.rio RDFFormat]
    [org.eclipse.rdf4j.sail.shacl ShaclValidator]
    [org.eclipse.rdf4j.sail.shacl.results ValidationReport]))


(def version
  "integrations-v1.0.0")

(def base
  "urn:tover:integrations:v1:")

(def ^:private ontology
  (delay (slurp (io/resource "integrations/v1/ontology.ttl"))))


(defn- validator
  {:malli/schema [:=> [:cat :boolean] :any]}
  [acceptance?]
  (let [shapes (str (slurp (io/resource "integrations/v1/source-shapes.ttl"))
                    (when acceptance? (str "\n" (slurp (io/resource "integrations/v1/acceptance-shapes.ttl")))))]
    (-> (ShaclValidator/builder)
        (.setValidationResultsLimitTotal 100)
        (.withShapes shapes base RDFFormat/TURTLE)
        (.build))))


(def ^:private validators
  {:source (delay (validator false)) :acceptance (delay (validator true))})


(defn- findings
  {:malli/schema [:=> [:cat :any] [:vector schema/Finding]]}
  [report]
  (let [model (.asModel ^ValidationReport report)]
    (->> (.filter model nil SHACL/RESULT nil (make-array Resource 0))
         (map (fn [^Statement statement]
                (let [node            (.getObject statement)
                      value           (fn [predicate]
                                        (some-> (.filter model node predicate nil (make-array Resource 0))
                                                .objects first .stringValue))
                      message         (or (value SHACL/RESULT_MESSAGE) "contradictory:structure")
                      [category code] (str/split message #":" 2)]
                  {:category   (if (#{"ambiguous" "incomplete" "contradictory"} category)
                                 (keyword category) :contradictory)
                   :code       (or code "structure")
                   :focus-node (value SHACL/FOCUS_NODE)
                   :path       (value SHACL/RESULT_PATH)
                   :shape      (value SHACL/SOURCE_SHAPE)})))
         (sort-by (juxt :category :code :focus-node :shape))
         vec)))


(defn validate
  "Validate a complete scoped evidence graph plus selected candidate decisions.
  M2 must supply all competing active bindings and referenced catalogue context.
  A valid result is not authorization, identity confirmation or ledger acceptance."
  {:malli/schema [:=> [:cat :string schema/ValidationContext] schema/ValidationResult]}
  [turtle context]
  (validation/check! schema/ValidationContext context)
  (let [{:keys [workspace-id phase selected]} context
        scope (str "<" base "validation> a <" base "validation-context>; <" base "workspace-id> "
                   (rdf/literal workspace-id) " .\n"
                   (apply str (for [id selected]
                                (str "<" base "validation> <" base "selected> <" (rdf/iri id) "> .\n"))))
        instance @(get validators phase)
        report (locking instance
                 (.validate ^ShaclValidator instance (str @ontology "\n" turtle "\n" scope) base RDFFormat/TURTLE))]
    {:valid?           (.conforms ^ValidationReport report)
     :semantic-version version
     :findings         (findings report)}))
