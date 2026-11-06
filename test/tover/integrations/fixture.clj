(ns tover.integrations.fixture
  "Synthetic contract graphs only; not a persistence codec or an Ozon normalizer."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [tover.integrations.semantics :as semantics]
    [tover.shared.rdf :as rdf])
  (:import
    [java.io StringWriter]
    [java.net URLEncoder]
    [org.eclipse.rdf4j.model Model Resource]
    [org.eclipse.rdf4j.model.impl LinkedHashModel SimpleValueFactory]
    [org.eclipse.rdf4j.model.vocabulary RDF XSD]
    [org.eclipse.rdf4j.rio RDFFormat Rio]))


(def at
  "2026-11-06T08:00:00Z")
(def hash-value
  (apply str (repeat 64 "a")))

(def reference-keys
  #{:connection-id :store-id :policy-id :current-revision-id :entity-id :product-id
    :source-revision-id :target-id :source-revision-ids :dependencies :mapping-ids
    :optional-field-ids :warehouse-id :from-location :to-location})

(def timestamp-keys
  #{:observed-at :last-observed-at :provider-updated-at :occurred-at :confirmed-at :accepted-at})


(defn node
  {:malli/schema [:=> [:cat :string :string] rdf/IRI]}
  [workspace id]
  (rdf/iri (str "urn:tover:fixture:" (URLEncoder/encode workspace "UTF-8") ":" (URLEncoder/encode id "UTF-8"))))


(defn term
  {:malli/schema [:=> [:cat :keyword] rdf/IRI]}
  [key]
  (rdf/iri (str semantics/base (str/replace (name key) #"\?$" ""))))


(declare write-map!)


(defn- write-value!
  {:malli/schema [:=> [:cat :any :string rdf/Resource :keyword :any] :nil]}
  [model workspace subject key value]
  (let [factory (SimpleValueFactory/getInstance)
        object  (cond
                  (= key :mapping-revisions) (rdf/literal (pr-str value))
                  (map? value) (let [child (.createBNode factory)]
                                 (write-map! model workspace child value)
                                 child)
                  (reference-keys key) (node workspace value)
                  (timestamp-keys key) (.createLiteral factory ^String value XSD/DATETIME)
                  (keyword? value) (term value)
                  :else (rdf/literal value))]
    (.add ^Model model subject (term key) object (make-array Resource 0)))
  nil)


(defn write-map!
  {:malli/schema [:=> [:cat :any :string rdf/Resource :map] :nil]}
  [model workspace subject data]
  (doseq [[key value] data]
    (cond
      (= :kind key) (.add ^Model model subject RDF/TYPE (term value) (make-array Resource 0))
      (vector? value) (doseq [[index entry] (map-indexed vector value)]
                        (write-value! model workspace subject key (if (map? entry) (assoc entry :position index) entry)))
      :else (write-value! model workspace subject key value)))
  nil)


(defn graph
  {:malli/schema [:=> [:cat [:vector :map]] :any]}
  [records]
  (let [model (LinkedHashModel.)]
    (doseq [{:keys [workspace-id id] :as record} records]
      (write-map! model workspace-id (node workspace-id id) record))
    model))


(defn turtle
  {:malli/schema [:=> [:cat :any] :string]}
  [model]
  (let [writer (StringWriter.)]
    (Rio/write ^Model model writer RDFFormat/TURTLE)
    (str writer)))


(defn check
  {:malli/schema [:=> [:cat [:vector :map] :keyword [:vector :string]] :map]}
  [records phase selected]
  (semantics/validate (turtle (graph records))
                      {:workspace-id "w" :phase phase :selected (mapv #(str (node "w" %)) selected)}))


(defn record
  {:malli/schema [:=> [:cat :keyword :string :map] :map]}
  [kind id fields]
  (merge {:kind kind :id id :workspace-id "w" :revision 1} fields))


(defn revision
  {:malli/schema [:=> [:cat :string :string :keyword :map] :map]}
  [id entity domain facts]
  (record :source-revision id
          {:connection-id "c"  :entity-id    entity     :domain          domain             :source-id (str "source-" entity)
           :observed-at   at   :payload-hash hash-value :adapter-version "ozon-contract-v1"
           :complete?     true :facts        facts      :identity-basis  "piece:size-56"}))


(defn identifier-binding
  {:malli/schema [:=> [:cat :string :string :keyword :string] :map]}
  [id product type value]
  (record :identifier-binding id
          {:connection-id "c"  :product-id product :source-revision-id (str product "-r1")
           :type          type :value      value   :active?            true                :observed-at at}))


(defn mapping
  {:malli/schema [:=> [:cat :string :string :string] :map]}
  [id entity target]
  (record :mapping id
          {:connection-id "c"        :entity-id    entity   :target-id    target :source-revision-id (str entity "-r1")
           :state         :confirmed :confirmed-by "user-1" :confirmed-at at     :identity-basis     "piece:size-56"}))


(defn baseline
  {:malli/schema [:=> [:cat] [:vector :map]]}
  []
  [(record :store "store" {})
   (record :policy "policy" {:preset :barcode-per-goods :required-fields []})
   (record :connection "c" {:provider "ozon" :account-id "account-1" :store-id "store"
                            :enabled? true   :policy-id  "policy"})
   (record :product "p" {:connection-id "c" :source-id "source-p" :current-revision-id "p-r1"})
   (revision "p-r1" "p" :product {:name "Shirt" :article "OLD" :unit "piece"})
   (record :goods "goods" {:unit "piece"})
   (mapping "m" "p" "goods")
   (record :warehouse "wh" {:connection-id "c" :source-id "source-wh" :current-revision-id "wh-r1"})
   (revision "wh-r1" "wh" :warehouse {:name "Warehouse"})
   (record :location "location" {})
   (mapping "wm" "wh" "location")])


(defn change
  {:malli/schema [:=> [:cat [:vector :map] :string [:=> [:cat :map] :map]] [:vector :map]]}
  [records id f]
  (mapv #(if (= id (:id %)) (f %) %) records))


(defn cases
  {:malli/schema [:=> [:cat] :map]}
  []
  (edn/read-string (slurp (io/file "test/tover/integrations/ozon-cases.edn"))))
