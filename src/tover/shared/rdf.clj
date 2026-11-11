(ns tover.shared.rdf
  "Tover's semantic repository lifecycle and the existing RDF API."
  (:require
    [bel-rdf.core :as rdf]
    [bel.validation :as validation]
    [clojure.java.io :as io]
    [clojure.string :as str])
  (:import
    [java.io File StringReader]
    [java.security MessageDigest]
    [org.eclipse.rdf4j.common.transaction IsolationLevels]
    [org.eclipse.rdf4j.model.vocabulary RDF4J]
    [org.eclipse.rdf4j.repository.sail SailRepository]
    [org.eclipse.rdf4j.rio RDFFormat]
    [org.eclipse.rdf4j.sail.nativerdf NativeStore]
    [org.eclipse.rdf4j.sail.shacl ShaclSail]))


(def Repository rdf/Repository)


(def Connection rdf/Connection)


(def IRI rdf/IRI)


(def Resource rdf/Resource)


(def Value rdf/Value)


(def Literal rdf/Literal)


(def Path rdf/Path)


(def Isolation rdf/Isolation)


(def TransactionOptions rdf/TransactionOptions)


(def LiteralInput rdf/LiteralInput)


(def Scalar rdf/Scalar)


(def Query rdf/Query)


(def QueryRows rdf/QueryRows)


(def Bindings rdf/Bindings)


(defn iri
  {:malli/schema [:=> [:cat rdf/FullIRI] IRI]}
  [value]
  (rdf/iri value))


(defn literal
  {:malli/schema [:=> [:cat LiteralInput] Literal]}
  [value]
  (rdf/literal value))


(defn value
  {:malli/schema [:=> [:cat Value] Scalar]}
  [term]
  (rdf/value term))


(defn- suppress-cleanup!
  {:malli/schema [:=> [:cat [:=> [:cat] :any] [:fn #(instance? Throwable %)]] :any]}
  [cleanup error]
  (try (cleanup) (catch Throwable cleanup-error (.addSuppressed ^Throwable error cleanup-error))))


(defn- install-semantics!
  "Install the immutable semantic package once; refuse silent schema replacement."
  {:malli/schema [:=> [:cat Repository] :nil]}
  [repo]
  (let [ontology  (slurp (io/resource "inventory/ontology.ttl"))
        shapes    (slurp (io/resource "inventory/shapes.ttl"))
        rules     (mapv #(slurp (io/resource (str "inventory/queries/" % ".rq")))
                        ["effects" "balances" "timeline" "lineage"])
        checksum  (apply str (map #(format "%02x" (bit-and % 255))
                                  (.digest (MessageDigest/getInstance "SHA-256")
                                           (.getBytes (str/join "\n" (into [ontology shapes] rules)) "UTF-8"))))
        graph     (iri "urn:tover:inventory-v1:ontology")
        marker    (iri "urn:tover:inventory-v1:package")
        predicate (iri "urn:tover:inventory-v1:packageChecksum")
        contexts  (into-array org.eclipse.rdf4j.model.Resource [graph])]
    (with-open [conn (.getConnection ^org.eclipse.rdf4j.repository.Repository repo)]
      (.begin conn IsolationLevels/SERIALIZABLE)
      (try
        (with-open [existing (.getStatements conn marker predicate nil false contexts)]
          (if (.hasNext existing)
            (when-not (= checksum (.stringValue (.getObject (.next existing))))
              (throw (ex-info "The stored semantic package differs; apply an explicit model migration."
                              {:type :configuration})))
            (do
              (.add conn (StringReader. ontology) "urn:tover:inventory-v1:" RDFFormat/TURTLE contexts)
              (.add conn (StringReader. shapes) "urn:tover:inventory-v1:" RDFFormat/TURTLE
                    (into-array org.eclipse.rdf4j.model.Resource [RDF4J/SHACL_SHAPE_GRAPH]))
              (.add conn marker predicate (literal checksum) contexts))))
        (.commit conn)
        (catch Throwable error
          (when (.isActive conn) (.rollback conn))
          (throw error)))))
  nil)


(defn native-store
  "Open a persistent NativeStore with force-sync enabled; never seed or initialize domain data."
  {:malli/schema [:=> [:cat Path] Repository]}
  [path]
  (validation/check! Path path)
  (let [native (doto (NativeStore. (File. (str path))) (.setForceSync true))
        sail   (doto (ShaclSail. native)
                 (.setParallelValidation false)
                 (.setValidationResultsLimitTotal 100))
        repo   (SailRepository. sail)]
    (try (.init repo) (install-semantics! repo) repo
         (catch Throwable error
           (suppress-cleanup! #(.shutDown repo) error)
           (throw error)))))


(defn close!
  {:malli/schema [:=> [:cat Repository] :nil]}
  [repo]
  (rdf/close! repo))


(defmacro with-transaction
  {:malli/schema [:=> [:cat :any :any [:tuple :symbol :any] [:* :any]] :any]}
  [binding & body]
  `(rdf/with-transaction ~binding ~@body))


(defn add!
  {:malli/schema [:=> [:cat Connection [:maybe Resource] Resource IRI Scalar] :nil]}
  [conn graph subject predicate object]
  (rdf/add! conn graph subject predicate object))


(defn remove!
  {:malli/schema [:=> [:cat Connection [:maybe Resource] [:maybe Resource] [:maybe IRI] [:maybe Scalar]] :nil]}
  [conn graph subject predicate object]
  (rdf/remove! conn graph subject predicate object))


(defn value-at
  {:malli/schema [:=> [:cat Connection [:maybe Resource] Resource IRI] [:maybe Scalar]]}
  [conn graph subject predicate]
  (rdf/value-at conn graph subject predicate))


(defn select
  {:malli/schema [:function
                  [:=> [:cat Connection Query] QueryRows]
                  [:=> [:cat Connection Query Bindings] QueryRows]]}
  ([conn query] (rdf/select conn query))
  ([conn query bindings] (rdf/select conn query bindings)))
