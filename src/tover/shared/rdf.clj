(ns tover.shared.rdf
  (:require [bel.validation :as validation]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.yetanalytics.flint :as flint]
            [malli.transform :as mt])
  (:import [java.io File StringReader]
           [java.math BigDecimal BigInteger]
           [java.net URI]
           [java.security MessageDigest]
           [org.eclipse.rdf4j.common.transaction IsolationLevels]
           [org.eclipse.rdf4j.model.datatypes XMLDatatypeUtil]
           [org.eclipse.rdf4j.model.impl SimpleValueFactory]
           [org.eclipse.rdf4j.model.vocabulary RDF4J XSD]
           [org.eclipse.rdf4j.repository.sail SailRepository]
           [org.eclipse.rdf4j.rio RDFFormat]
           [org.eclipse.rdf4j.sail.nativerdf NativeStore]
           [org.eclipse.rdf4j.sail.shacl ShaclSail]))


(def Repository [:fn #(instance? org.eclipse.rdf4j.repository.Repository %)])


(def Connection [:fn #(instance? org.eclipse.rdf4j.repository.RepositoryConnection %)])


(def IRI [:fn #(instance? org.eclipse.rdf4j.model.IRI %)])


(def Resource [:fn #(instance? org.eclipse.rdf4j.model.Resource %)])


(def Value [:fn #(instance? org.eclipse.rdf4j.model.Value %)])


(def Literal [:fn #(instance? org.eclipse.rdf4j.model.Literal %)])


(def Path [:or :string [:fn #(instance? File %)] [:fn #(instance? java.nio.file.Path %)]])


(def Isolation [:enum :none :read-committed :snapshot-read :snapshot :serializable])


(def TransactionOptions [:map [:repo Repository] [:level Isolation]])


(def LiteralInput [:or :string :boolean [:fn integer?] [:fn decimal?] [:fn float?]])


(def Scalar [:or LiteralInput Value])


(def Query [:map])


(def QueryRows [:vector [:map-of :keyword Scalar]])


(def Bindings [:map-of [:or :keyword :symbol :string] Scalar])


(def ^:private Failure [:fn #(instance? Throwable %)])


(def ^:private FullIRI
  [:and :string
   [:fn {:error/message "must be an absolute IRI"}
    #(try (.isAbsolute (URI. %)) (catch Exception _ false))]])


(def ^:private value-factory (SimpleValueFactory/getInstance))


(def ^:private isolation-levels
  {:none          IsolationLevels/NONE          :read-committed IsolationLevels/READ_COMMITTED
   :snapshot-read IsolationLevels/SNAPSHOT_READ :snapshot       IsolationLevels/SNAPSHOT
   :serializable  IsolationLevels/SERIALIZABLE})


(defn- to-rdf
  {:malli/schema [:=> [:cat Scalar] Value]}
  [data]
  (cond
    (instance? org.eclipse.rdf4j.model.Value data) data
    (string? data) (.createLiteral value-factory ^String data)
    (boolean? data) (.createLiteral value-factory (boolean data))
    (integer? data) (if (<= Long/MIN_VALUE data Long/MAX_VALUE)
                      (.createLiteral value-factory (long data))
                      (.createLiteral value-factory ^BigInteger (biginteger data)))
    (decimal? data) (.createLiteral value-factory ^BigDecimal data)
    (instance? Float data) (.createLiteral value-factory (float data))
    :else (.createLiteral value-factory (double data))))


(defn- from-rdf
  {:malli/schema [:=> [:cat Value] Scalar]}
  [term]
  (if (instance? org.eclipse.rdf4j.model.Literal term)
    (let [^org.eclipse.rdf4j.model.Literal term term
          datatype (.getDatatype term)]
      (cond
        (.isPresent (.getLanguage term)) term
        (= XSD/STRING datatype) (.getLabel term)
        (= XSD/BOOLEAN datatype) (.booleanValue term)
        (XMLDatatypeUtil/isIntegerDatatype datatype)
        (let [integer (.integerValue term)]
          (if (<= Long/MIN_VALUE integer Long/MAX_VALUE) (long integer) (bigint integer)))
        (= XSD/DECIMAL datatype) (.decimalValue term)
        (= XSD/DOUBLE datatype) (.doubleValue term)
        (= XSD/FLOAT datatype) (.floatValue term)
        :else term))
    term))


(def ^:private RDFData
  [:or {:encode/rdf to-rdf :decode/rdf from-rdf} LiteralInput Value])


(def ^:private rdf-transformer (mt/transformer {:name :rdf}))


(defn iri
  "Create an IRI from its complete, absolute identifier."
  {:malli/schema [:=> [:cat FullIRI] IRI]}
  [full-iri]
  (.createIRI value-factory ^String (validation/check! FullIRI full-iri)))


(defn literal
  "Encode a Clojure string, boolean or numeric value as an RDF literal."
  {:malli/schema [:=> [:cat LiteralInput] Literal]}
  [data]
  (validation/check! LiteralInput data)
  (validation/check! Literal (validation/encode! RDFData data rdf-transformer)))


(defn value
  "Decode common literals; retain RDF identity and unsupported datatype/language metadata."
  {:malli/schema [:=> [:cat Value] Scalar]}
  [term]
  (validation/check! Value term)
  (validation/decode! RDFData term rdf-transformer))


(defn- rdf-value
  {:malli/schema [:=> [:cat Scalar] Value]}
  [data]
  (validation/encode! RDFData data rdf-transformer))


(defn- suppress-cleanup!
  {:malli/schema [:=> [:cat [:=> [:cat] :any] Failure] :any]}
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
  (.shutDown ^org.eclipse.rdf4j.repository.Repository (validation/check! Repository repo)))


(defmacro with-transaction
  "Bind [conn {:repo repository :level isolation}], commit, and rollback/close on failure."
  {:malli/schema [:=> [:cat :any :any [:tuple :symbol :any] [:* :any]] :any]}
  [[conn options] & body]
  `(let [{repo# :repo level# :level} (validation/check! TransactionOptions ~options)
         ~conn (.getConnection ^org.eclipse.rdf4j.repository.Repository repo#)
         error# (volatile! nil)]
     (try
       (.begin ^org.eclipse.rdf4j.repository.RepositoryConnection ~conn (get @#'isolation-levels level#))
       (let [result# (do ~@body)]
         (.commit ^org.eclipse.rdf4j.repository.RepositoryConnection ~conn)
         result#)
       (catch Throwable failure#
         (vreset! error# failure#)
         (@#'suppress-cleanup!
           #(when (.isActive ^org.eclipse.rdf4j.repository.RepositoryConnection ~conn)
              (.rollback ^org.eclipse.rdf4j.repository.RepositoryConnection ~conn))
           failure#)
         (throw failure#))
       (finally
         (if-let [failure# @error#]
           (@#'suppress-cleanup! #(.close ^org.eclipse.rdf4j.repository.RepositoryConnection ~conn) failure#)
           (.close ^org.eclipse.rdf4j.repository.RepositoryConnection ~conn))))))


(defn add!
  {:malli/schema [:=> [:cat Connection [:maybe Resource] Resource IRI Scalar] :nil]}
  [conn graph subject predicate object]
  (validation/check! [:tuple Connection [:maybe Resource] Resource IRI Scalar]
                     [conn graph subject predicate object])
  (.add ^org.eclipse.rdf4j.repository.RepositoryConnection conn subject predicate (rdf-value object)
        (into-array org.eclipse.rdf4j.model.Resource [graph])))


(defn remove!
  "Remove matches in one graph; nil subject/predicate/object are wildcards."
  {:malli/schema [:=> [:cat Connection [:maybe Resource] [:maybe Resource] [:maybe IRI] [:maybe Scalar]] :nil]}
  [conn graph subject predicate object]
  (validation/check! [:tuple Connection [:maybe Resource] [:maybe Resource] [:maybe IRI] [:maybe Scalar]]
                     [conn graph subject predicate object])
  (.remove ^org.eclipse.rdf4j.repository.RepositoryConnection conn subject predicate
           (when (some? object) (rdf-value object))
           (into-array org.eclipse.rdf4j.model.Resource [graph])))


(defn value-at
  {:malli/schema [:=> [:cat Connection [:maybe Resource] Resource IRI] [:maybe Scalar]]}
  [conn graph subject predicate]
  (validation/check! [:tuple Connection [:maybe Resource] Resource IRI] [conn graph subject predicate])
  (with-open [statements (.getStatements ^org.eclipse.rdf4j.repository.RepositoryConnection conn
                                         subject predicate nil false
                                         (into-array org.eclipse.rdf4j.model.Resource [graph]))]
    (when (.hasNext statements) (value (.getObject (.next statements))))))


(defn select
  "Run a Flint SELECT form with optional variable bindings; realize typed results before closing."
  {:malli/schema [:function
                  [:=> [:cat Connection Query] QueryRows]
                  [:=> [:cat Connection Query Bindings] QueryRows]]}
  ([conn query] (select conn query {}))
  ([conn query bindings]
   (validation/check! [:tuple Connection Query Bindings] [conn query bindings])
   (let [prepared (.prepareTupleQuery ^org.eclipse.rdf4j.repository.RepositoryConnection conn
                                      ^String (flint/format-query query))]
     (doseq [[variable data] bindings]
       (.setBinding prepared (str/replace (name variable) #"^[?$]" "") (rdf-value data)))
     (with-open [results (.evaluate prepared)]
       (validation/check!
         QueryRows
         (mapv (fn [bindings]
                 (into {} (map (fn [binding] [(keyword (.getName binding)) (value (.getValue binding))]) bindings)))
               (iterator-seq results)))))))


