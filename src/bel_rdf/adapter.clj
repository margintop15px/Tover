(ns bel-rdf.adapter
  "RDF store and outbox. Graph allocation and legacy guards belong to the application."
  (:require
    [bel-rdf.core :as rdf]
    [bel.runtime :as runtime]
    [bel.store :as store]
    [bel.validation :as validation]
    [clojure.string :as str]
    [com.yetanalytics.flint :as flint]
    [taoensso.nippy :as nippy])
  (:import
    [java.net URLDecoder URLEncoder]
    [java.nio.charset StandardCharsets]
    [org.eclipse.rdf4j.model IRI Resource Statement Value]
    [org.eclipse.rdf4j.model.vocabulary RDF]
    [org.eclipse.rdf4j.query Binding Operation]
    [org.eclipse.rdf4j.query.impl SimpleDataset]
    [org.eclipse.rdf4j.repository RepositoryConnection RepositoryException]
    [org.eclipse.rdf4j.sail SailConflictException]))


(defn graph
  "The application's graph for this scope; `context` holds :graphs (fn [scope] {kind iri}) and adapter :scope."
  {:malli/schema [:=> [:cat :map :keyword] rdf/IRI]}
  [{:keys [graphs scope]} kind]
  (or (get (graphs scope) kind)
      (throw (ex-info "Missing RDF graph" {:type :configuration :kind kind}))))


(defn- node
  {:malli/schema [:=> [:cat rdf/IRI :string] rdf/IRI]}
  [graph id]
  (rdf/iri (str graph ":" (URLEncoder/encode ^String id StandardCharsets/UTF_8))))


(defn- term
  {:malli/schema [:=> [:cat :keyword] rdf/IRI]}
  [key]
  (rdf/iri (str "urn:bel:journal:" (name key))))


(defn- qualified-name
  {:malli/schema [:=> [:cat :keyword] :string]}
  [id]
  (subs (str id) 1))


(defn vocabulary
  "Stable generated class and field IRIs. Renaming a model or field requires a migration."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [model-id fields]}]
  (let [base (node (rdf/iri "urn:bel:model") (qualified-name model-id))]
    {:type   base
     :fields (into {}
                   (map (fn [key]
                          [key (node (rdf/iri (str base ":field")) (qualified-name key))]))
                   (keys fields))}))


(defn subject
  {:malli/schema [:=> [:cat :map :qualified-keyword :string] rdf/IRI]}
  [context model-id id]
  (node (node (graph context :domain) (qualified-name model-id)) id))


(defn- field-value
  {:malli/schema [:=> [:cat :map :map rdf/Value] :any]}
  [context field value]
  (if-let [target (:bel/ref field)]
    (let [prefix (str (subject context target ""))]
      (when-not (and (instance? IRI value) (str/starts-with? (str value) prefix))
        (throw (ex-info "Reference is outside its workspace or model" {:type :validation})))
      (URLDecoder/decode (subs (str value) (count prefix)) StandardCharsets/UTF_8))
    (rdf/value value)))


(defn load-model
  {:malli/schema [:=> [:cat :map :map :any] [:maybe :map]]}
  [{:keys [tx] :as context} {:keys [model-id fields] :as model} id]
  (let [graph    (graph context :domain)
        subject  (subject context model-id id)
        vocab    (vocabulary model)
        contexts (into-array Resource [graph])]
    (when (.hasStatement ^RepositoryConnection tx subject RDF/TYPE (:type vocab) false contexts)
      (into {}
            (keep (fn [[key field]]
                    (with-open [values (.getStatements ^RepositoryConnection tx subject (get-in vocab [:fields key]) nil false contexts)]
                      (when (.hasNext values)
                        (let [value (field-value context field (.getObject (.next values)))]
                          (when (.hasNext values)
                            (throw (ex-info "Model field has multiple values" {:type :validation :field key})))
                          [key value])))))
            fields))))


(defn write-model!
  "Replace model-owned fields in the active transaction; retain unrelated RDF predicates."
  {:malli/schema [:=> [:cat :map :map :map] :nil]}
  [{:keys [tx] :as context} {:keys [id model-id fields schema] :as model} row]
  (validation/check! schema row)
  (let [graph  (graph context :domain)
        entity (subject context model-id (get row id))
        vocab  (vocabulary model)]
    (rdf/add! tx graph entity RDF/TYPE (:type vocab))
    (doseq [[key field] fields]
      (let [predicate (get-in vocab [:fields key])]
        (rdf/remove! tx graph entity predicate nil)
        (when (contains? row key)
          (rdf/add! tx graph entity predicate
                    (if-let [target (:bel/ref field)]
                      (subject context target (get row key))
                      (get row key)))))))
  nil)


(defn apply-model!
  "Replace the mapped fields; keep the entity's other fields."
  {:malli/schema [:=> [:cat :map :map [:vector :keyword] :map] :nil]}
  [context model fields data]
  (let [id (:id model)]
    (validation/check! [:map [id (get-in model [:fields id :schema])]] (select-keys data [id]))
    (write-model! context model
                  (merge (apply dissoc (load-model context model (get data id)) fields) data))))


(defn- scoped-query
  {:malli/schema [:=> [:cat :map :map] :map]}
  [form bindings]
  (when (or (seq (remove #{:prefixes :select :select-distinct :where :order-by :limit :offset :group-by :having} (keys form)))
            (not (vector? (:where form)))
            (some #{:graph :service :service-silent :from :from-named :with :using :using-named}
                  (tree-seq coll? seq form))
            (some #(= "graph" (str/replace (name %) #"^[?$]" "")) (keys bindings)))
    (throw (ex-info "RDF queries cannot choose datasets or graph bindings" {:type :validation})))
  (update form :where #(vector [:graph '?graph %])))


(defn- bind!
  {:malli/schema [:=> [:cat [:fn #(instance? Operation %)] rdf/IRI rdf/Bindings] :any]}
  [operation graph bindings]
  (.setMaxExecutionTime ^Operation operation 15)
  (.setDataset ^Operation operation (doto (SimpleDataset.) (.addNamedGraph graph)))
  (doseq [[key value] (assoc bindings :graph graph)]
    (.setBinding ^Operation operation (str/replace (name key) #"^[?$]" "")
                 (if (instance? Value value) value (rdf/literal value)))))


(defn save-pending!
  {:malli/schema [:=> [:cat :map :map] :nil]}
  [{:keys [tx] :as context} delivery]
  (let [graph   (graph context :delivery)
        subject (node graph (:command-id delivery))]
    (rdf/add! tx graph subject (term :accepted) true)
    (rdf/add! tx graph subject (term :pending) (nippy/freeze-to-string delivery)))
  nil)


(defn acceptance
  {:malli/schema [:=> [:cat :map :string] [:maybe :map]]}
  [{:keys [tx] :as context} command-id]
  (let [graph   (graph context :delivery)
        subject (node graph command-id)]
    (when (rdf/value-at tx graph subject (term :accepted))
      {:pending (some-> (rdf/value-at tx graph subject (term :pending)) nippy/thaw-from-string)})))


(defn transact
  {:malli/schema [:=> [:cat :any :keyword [:fn fn?]] :any]}
  [repo isolation f]
  (try
    (rdf/with-transaction [tx {:repo repo :level (if (= :repeatable-read isolation) :snapshot isolation)}]
      (f tx))
    (catch RepositoryException error
      (if (some #(instance? SailConflictException %) (take-while some? (iterate ex-cause error)))
        (throw (ex-info "Concurrent change; retry the same command" {:type :conflict :retryable? true} error))
        (throw error)))))


(defn read-record
  "Decode one pending statement and check it sits in its own scope's delivery graph."
  {:malli/schema [:=> [:cat [:fn fn?] [:fn #(instance? Statement %)]] :map]}
  [graphs ^Statement statement]
  (let [delivery (nippy/thaw-from-string (rdf/value (.getObject statement)))
        graph    (graph {:graphs graphs :scope (store/store-scope (get-in delivery [:request :scope]))} :delivery)]
    (when-not (and (= graph (.getContext statement))
                   (= (node graph (:command-id delivery)) (.getSubject statement)))
      (throw (ex-info "Delivery scope does not match its source" {:type :configuration})))
    delivery))


(defn pending
  {:malli/schema [:=> [:cat :any [:fn fn?]] [:vector :map]]}
  [repo graphs]
  (transact repo :repeatable-read
            (fn [tx]
              (with-open [rows (.getStatements ^RepositoryConnection tx nil (term :pending) nil false (make-array Resource 0))]
                (mapv (fn [^Statement statement]
                        (let [source (str (.getSubject statement))]
                          (try {:source source :delivery (read-record graphs statement)}
                               (catch Exception error {:source source :error error}))))
                      (iterator-seq rows))))))


(defn acknowledge!
  {:malli/schema [:=> [:cat :any [:fn fn?] :map] :nil]}
  [repo graphs delivery]
  (transact repo :serializable
            (fn [tx]
              (let [context {:tx tx :graphs graphs :scope (store/store-scope (get-in delivery [:request :scope]))}
                    graph   (graph context :delivery)
                    subject (node graph (:command-id delivery))
                    stored  (some-> (rdf/value-at tx graph subject (term :pending)) nippy/thaw-from-string)]
                (when stored
                  (when-not (= stored delivery)
                    (throw (ex-info "Delivery changed before acknowledgement" {:type :journal-conflict})))
                  (rdf/remove! tx graph subject (term :pending) nil)))))
  nil)


(defrecord RdfStore [repo graphs guard]
  store/Transactional
  (-transact [_ isolation f] (transact repo isolation f))

  store/Store
  (-load [_ tx scope model id] (load-model {:tx tx :graphs graphs :scope scope} model id))
  (-write! [_ tx scope model fields row] (apply-model! {:tx tx :graphs graphs :scope scope} model fields row))

  store/Outbox
  (-record! [_ tx scope delivery] (save-pending! {:tx tx :graphs graphs :scope scope} delivery))
  (-accepted [_ tx scope command-id]
    (when guard (guard tx scope))
    (acceptance {:tx tx :graphs graphs :scope scope} command-id))
  (-pending [_] (pending repo graphs))
  (-ack! [_ delivery] (acknowledge! repo graphs delivery)))


(alter-meta! #'->RdfStore assoc :malli/schema [:=> [:cat :any [:fn fn?] [:maybe [:fn fn?]]] :map])
(alter-meta! #'map->RdfStore assoc :malli/schema [:=> [:cat :map] :map])


(defn store
  "An RDF store. `graphs` maps an adapter scope to {:domain iri :delivery iri}; optional `guard` (fn [tx scope])
  runs before acceptance lookup and may refuse with :migration-required."
  {:malli/schema [:=> [:cat [:map [:repo :any] [:graphs [:fn fn?]] [:guard {:optional true} [:fn fn?]]]]
                  [:fn #(instance? RdfStore %)]]}
  [{:keys [repo graphs guard]}]
  (->RdfStore repo graphs guard))


(defn- rdf-store-key
  {:malli/schema [:=> [:cat :map] :keyword]}
  [context]
  (let [keys (keep (fn [[key store]] (when (instance? RdfStore store) key))
                   (get-in context [:bel/system :stores]))]
    (if (= 1 (count keys))
      (first keys)
      (throw (ex-info "Expected exactly one RDF store" {:type :configuration :stores (vec keys)})))))


(defmethod runtime/coeffect :rdf/query
  [_ context {:keys [query bindings] :or {bindings {}}}]
  (let [key (rdf-store-key context)]
    (runtime/with-store context key :repeatable-read
      (fn [{:keys [tx scope] :as context}]
        (let [graph    (graph {:graphs (:graphs (store/store context key)) :scope (store/store-scope scope)} :domain)
              query    (scoped-query query bindings)
              prepared (.prepareTupleQuery ^RepositoryConnection tx ^String (flint/format-query query))]
          (bind! prepared graph bindings)
          (with-open [rows (.evaluate prepared)]
            (mapv (fn [row]
                    (into {} (map (fn [binding]
                                    [(keyword (.getName ^Binding binding))
                                     (rdf/value (.getValue ^Binding binding))]) row)))
                  (iterator-seq rows))))))))
