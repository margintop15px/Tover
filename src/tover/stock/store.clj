(ns tover.stock.store
  (:require
    [bel.validation :as validation]
    [clojure.set :as set]
    [malli.transform :as mt]
    [tover.shared.rdf :as rdf]
    [tover.shared.schema :as shared]
    [tover.stock.schema :as schema])
  (:import
    [java.net URLEncoder]
    [java.nio.charset StandardCharsets]
    [java.time Instant]
    [java.util UUID]))


;; The vocabulary remains provisional; graphs and entity identities are workspace-scoped.
(def ^:private base "urn:tover:stock-v0:")

(def ^:private prefixes {:t   (str "<" base ">")
                         :xsd "<http://www.w3.org/2001/XMLSchema#>"})

(def ^:private Timestamp [:fn {:encode/string str} #(instance? Instant %)])

(def ^:private Fields [:map-of :keyword rdf/Scalar])

(def Grid
  [:map
   [:rows schema/Rows]
   [:evidence [:map-of shared/Text schema/HistoryEntry]]])

(def RowRead
  [:map [:row [:maybe schema/Row]]])

(def HistoryRead
  [:map [:row [:maybe schema/Row]]
   [:history [:vector schema/HistoryEntry]]])


(defn- iri
  {:malli/schema [:=> [:cat :string] rdf/IRI]}
  [suffix]
  (rdf/iri (str base suffix)))


(defn- workspace-iri
  {:malli/schema [:=> [:cat shared/Scope :string] rdf/IRI]}
  [{:keys [workspace-id] :as scope} suffix]
  (validation/check! shared/Scope scope)
  (rdf/iri (str "urn:tover:workspace:" workspace-id ":stock-v0:" suffix)))


(defn scope-bindings
  "Bind every inventory query to the selected workspace's named graphs."
  {:malli/schema [:=> [:cat shared/Scope] rdf/Bindings]}
  [scope]
  {'?catalog (workspace-iri scope "catalog")
   '?history (workspace-iri scope "history")})


(defn- entity-iri
  {:malli/schema [:=> [:cat shared/Scope :string shared/Text] rdf/IRI]}
  [scope kind id]
  (workspace-iri scope (str kind ":" (URLEncoder/encode ^String id StandardCharsets/UTF_8))))

(defn- fail!
  {:malli/schema [:=> [:cat :keyword :string] :nil]}
  [type message]
  (throw (ex-info message {:type type})))


(defn- recorded-now
  {:malli/schema [:=> [:cat] :string]}
  []
  (validation/encode! Timestamp (Instant/now) mt/string-transformer))


(defn- add-fields!
  {:malli/schema [:=> [:cat rdf/Connection rdf/Resource rdf/Resource Fields] :nil]}
  [conn graph subject fields]
  (doseq [[key data] fields]
    (rdf/add! conn graph subject (iri (name key)) data)))


(defn- field
  {:malli/schema [:=> [:cat rdf/Connection rdf/Resource rdf/Resource :keyword] [:maybe rdf/Scalar]]}
  [conn graph subject key]
  (rdf/value-at conn graph subject (iri (name key))))


(def ^:private rows-query
  (assoc '{:select   [?id ?name ?article ?unit ?location [(sum ?delta) ?quantity]]
           :where    [[:graph ?catalog
                       [[?row :t/id ?id] [?row :t/name ?name] [?row :t/article ?article]
                        [?row :t/unit ?unit] [?row :t/location ?location]]]
                      [:graph ?history
                       [[?effect :t/row ?row] [?effect :t/delta ?delta]
                        [?effect :t/decision "accepted"]]]]
           :group-by [?id ?name ?article ?unit ?location]
           :order-by [(lcase ?name) ?id]}
         :prefixes prefixes))


(def ^:private history-query
  (assoc '{:select   [?id ?rowId ?before ?after ?delta ?reason ?actor ?recordedAt ?effectiveAt]
           :where    [[:graph ?history
                       [[?effect :t/kind "inline-edit"] [?effect :t/decision "accepted"]
                        [?effect :t/id ?id] [?effect :t/row ?row] [?effect :t/before ?before]
                        [?effect :t/after ?after] [?effect :t/delta ?delta] [?effect :t/reason ?reason]
                        [?effect :t/recorded-at ?recordedAt] [?effect :t/effective-at ?effectiveAt]
                        [:optional [[?effect :t/actor ?actor]]]]]
                      [:graph ?catalog [[?row :t/id ?rowId]]]]
           :order-by [(desc (:xsd/dateTime ?recordedAt)) (desc ?id)]}
         :prefixes prefixes))


(def ^:private latest-evidence-query
  (update history-query :where conj
          '[:filter (not-exists [[:graph ?history
                                  [[?later :t/row ?row] [?later :t/kind "inline-edit"]
                                   [?later :t/decision "accepted"] [?later :t/recorded-at ?laterRecordedAt]
                                   [?later :t/id ?laterId]]]
                                 [:filter (or (> (:xsd/dateTime ?laterRecordedAt) (:xsd/dateTime ?recordedAt))
                                              (and (= (:xsd/dateTime ?laterRecordedAt) (:xsd/dateTime ?recordedAt))
                                                   (> ?laterId ?id)))]])]))


(defn- stock-rows
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope] schema/Rows]}
  [conn scope]
  (validation/check! schema/Rows (rdf/select conn rows-query (scope-bindings scope))))


(defn- current-row
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope shared/Text] [:maybe schema/Row]]}
  [conn scope row-id]
  (first (validation/check! schema/Rows
                            (rdf/select conn rows-query (assoc (scope-bindings scope) '?row (entity-iri scope "row" row-id))))))


(defn- history-entries
  {:malli/schema [:=> [:cat rdf/Connection rdf/Query rdf/Bindings] [:vector schema/HistoryEntry]]}
  [conn query bindings]
  (validation/decode!
    [:vector schema/HistoryEntry]
    (mapv #(set/rename-keys % {:rowId       :row-id
                               :recordedAt  :recorded-at
                               :effectiveAt :effective-at})
          (rdf/select conn query bindings))
    (mt/string-transformer)))


(defn- edits
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope] [:vector schema/HistoryEntry]]}
  [conn scope]
  (history-entries conn history-query (scope-bindings scope)))


(defn- opening-row
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope shared/Text] [:maybe schema/Row]]}
  [conn scope id]
  (let [subject (entity-iri scope "row" id)
        catalog (workspace-iri scope "catalog")
        history (workspace-iri scope "history")]
    (when (some? (field conn catalog subject :id))
      (validation/check!
        schema/Row
        (assoc (into {} (map (fn [key] [key (field conn catalog subject key)])
                             [:id :name :article :unit :location]))
               :quantity (field conn history (entity-iri scope "opening" id) :after))))))


(defn declare-opening!
  "Declare a row and its immutable opening quantity. Repeated declarations must match."
  {:malli/schema [:=> [:cat rdf/Repository shared/Scope schema/Row] schema/Row]}
  [repo scope row]
  (validation/check! shared/Scope scope)
  (validation/check! schema/Row row)
  (rdf/with-transaction [conn {:repo  repo
                               :level :serializable}]
    (if-let [existing (opening-row conn scope (:id row))]
      (if (= existing row)
        existing
        (fail! :opening-conflict "This row already has a different opening declaration."))
      (let [{:keys [id quantity]} row
            subject               (entity-iri scope "row" id)
            catalog               (workspace-iri scope "catalog")
            history               (workspace-iri scope "history")
            recorded-at           (recorded-now)]
        (add-fields! conn catalog subject (dissoc row :quantity))
        (add-fields! conn history (entity-iri scope "opening" id)
                     {:kind         "opening"
                      :row          subject
                      :before       0
                      :after        quantity
                      :delta        quantity
                      :decision     "accepted"
                      :reason       "Opening declaration"
                      :recorded-at  recorded-at
                      :effective-at recorded-at})
        row))))


(defn snapshot
  {:malli/schema [:=> [:cat rdf/Repository shared/Scope] schema/Snapshot]}
  [repo scope]
  (validation/check! shared/Scope scope)
  (rdf/with-transaction [conn {:repo  repo
                               :level :snapshot}]
    (validation/check! schema/Snapshot
                       {:rows    (stock-rows conn scope)
                        :history (edits conn scope)})))


(defn stock-row
  "Read one accepted row."
  {:malli/schema [:=> [:cat rdf/Repository shared/Scope shared/Text] RowRead]}
  [repo scope row-id]
  (validation/check! shared/Scope scope)
  (validation/check! shared/Text row-id)
  (rdf/with-transaction [conn {:repo  repo
                               :level :snapshot}]
    (validation/check! RowRead {:row (current-row conn scope row-id)})))


(defn row-history
  "Read one row and its accepted inline edits, newest first, in one snapshot."
  {:malli/schema [:=> [:cat rdf/Repository shared/Scope shared/Text] HistoryRead]}
  [repo scope row-id]
  (validation/check! shared/Scope scope)
  (validation/check! shared/Text row-id)
  (rdf/with-transaction [conn {:repo  repo
                               :level :snapshot}]
    (validation/check! HistoryRead
                       {:row     (current-row conn scope row-id)
                        :history (history-entries conn history-query (assoc (scope-bindings scope) '?row (entity-iri scope "row" row-id)))})))


(defn stock-grid
  "Read stock and latest evidence in one RDF snapshot."
  {:malli/schema [:=> [:cat rdf/Repository shared/Scope] Grid]}
  [repo scope]
  (validation/check! shared/Scope scope)
  (rdf/with-transaction [conn {:repo repo :level :snapshot}]
    (validation/check!
      Grid
      {:rows     (stock-rows conn scope)
       :evidence (into {} (map (juxt :row-id identity))
                       (history-entries conn latest-evidence-query (scope-bindings scope)))})))


(defn- ensure-changed!
  {:malli/schema [:=> [:cat schema/Quantity schema/Quantity] :nil]}
  [before after]
  (when (= before after) (fail! :validation "Enter a different quantity.")))


(defn proposal
  {:malli/schema [:=> [:cat schema/Row schema/Quantity] schema/Proposal]}
  [row after]
  (validation/check! [:tuple schema/Row schema/Quantity] [row after])
  (ensure-changed! (:quantity row) after)
  (validation/check! schema/Proposal
                     {:row-id (:id row)
                      :row    row
                      :before (:quantity row)
                      :after  after}))


(defn accept-adjustment!
  {:malli/schema [:=> [:cat rdf/Repository shared/Scope schema/Command] schema/Receipt]}
  [repo scope command]
  (validation/check! shared/Scope scope)
  (let [{:keys [row-id after reason actor]} (validation/check! schema/Command command)]
    (rdf/with-transaction [conn {:repo repo :level :serializable}]
      (let [row     (current-row conn scope row-id)
            history (workspace-iri scope "history")]
        (when-not row (fail! :unknown-row "This stock row does not exist."))
        (let [before      (:quantity row)
              id          (UUID/randomUUID)
              recorded-at (recorded-now)
              result      (validation/check! schema/Receipt
                                             {:id id :row-id row-id :before before :after after :delta (- after before)})]
          (ensure-changed! before after)
          ;; shortcut: edits take effect now; backdating needs ordered transition validation.
          (add-fields! conn history (entity-iri scope "edit" (str id))
                       (cond-> (merge (dissoc (validation/encode! schema/Receipt result (mt/json-transformer)) :row-id)
                                      {:kind         "inline-edit"
                                       :row          (entity-iri scope "row" row-id)
                                       :decision     "accepted"
                                       :reason       reason
                                       :recorded-at  recorded-at
                                       :effective-at recorded-at})
                         actor (assoc :actor actor)))
          result)))))
