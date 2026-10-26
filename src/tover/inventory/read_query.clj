(ns tover.inventory.read-query
  "Bounded SELECT queries over the disposable inventory read graph."
  (:require
    [bel.validation :as validation]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [tover.inventory.filters :as filtering]
    [tover.inventory.grid :as grid]
    [tover.inventory.schema :as schema]
    [tover.inventory.tables :as tables]
    [tover.shared.i18n :as i18n]
    [tover.shared.rdf :as rdf]
    [tover.shared.schema :as shared])
  (:import
    [org.eclipse.rdf4j.model Literal Value]
    [org.eclipse.rdf4j.repository RepositoryConnection]))


(def ^:private base
  "urn:tover:inventory-read-v1:")


(def ^:private prefix
  (str "PREFIX r: <" base ">\n"))


(def ^:private stock-sorts
  #{"name" "article" "barcode" "unit" "location" "quantity"})


(def ^:private operation-sorts
  #{"occurred-at" "type" "party" "summary" "context" "items" "reason"})


(defn- graph
  {:malli/schema [:=> [:cat shared/Scope] rdf/IRI]}
  [scope]
  (validation/check! shared/Scope scope)
  (rdf/iri (str "urn:tover:workspace:" (:workspace-id scope) ":inventory-read-v1")))


(defn- term
  {:malli/schema [:=> [:cat :string] rdf/IRI]}
  [name]
  (rdf/iri (str base name)))


(defn- query
  {:malli/schema [:=> [:cat rdf/Connection :string :map] [:vector :map]]}
  [conn text bindings]
  (let [prepared (.prepareTupleQuery ^RepositoryConnection conn ^String (str prefix text))]
    (.setMaxExecutionTime prepared 15)
    (doseq [[name value] bindings]
      (.setBinding prepared name (if (instance? Value value) value (rdf/literal value))))
    (with-open [result (.evaluate prepared)]
      (mapv (fn [row]
              (into {} (map (fn [binding]
                              [(keyword (.getName ^org.eclipse.rdf4j.query.Binding binding))
                               (rdf/value (.getValue ^org.eclipse.rdf4j.query.Binding binding))]) row)))
            (iterator-seq result)))))


(defn- current?
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :map] :boolean]}
  [conn scope input]
  (let [graph (graph scope)
        state (rdf/iri (str graph ":state"))]
    (every? (fn [field]
              (or (not (contains? input field))
                  (= (get input field) (rdf/value-at conn graph state (term (name field))))))
            [:generation :epoch])))


(defn- checked-query
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [view sort direction filters start count anchorId detailRowId pendingRows operationType]
    :or   {view :stock start 0 count 200 pendingRows []} :as input}]
  (let [view      (keyword view)
        sorts     (if (= view :operations) operation-sorts stock-sorts)
        sort      (if (sorts sort) sort (if (= view :operations) "occurred-at" "name"))
        direction (or direction (if (= view :operations) "desc" "asc"))]
    (when-not (and (#{:stock :operations} view) (#{"asc" "desc"} direction)
                   (integer? start) (<= 0 start) (integer? count) (pos? count)
                   (or (nil? anchorId) (string? anchorId))
                   (or (nil? detailRowId) (string? detailRowId))
                   (or (nil? operationType) (string? operationType))
                   (vector? pendingRows) (<= (clojure.core/count pendingRows) 100)
                   (every? string? pendingRows))
      (schema/fail! :invalid-input "Choose a valid inventory window." {:status 400}))
    (assoc input :view view :sort sort :direction direction :filters (filtering/check! view filters)
           :start start :count (min 200 count) :pendingRows (vec (distinct pendingRows)))))


(defn- matching-type-labels
  {:malli/schema [:=> [:cat :map] [:vector :string]]}
  [{:keys [type value]}]
  (let [needle   (str/lower-case (str value))
        matches? (case type
                   ("eq" "notEq") #(= % needle)
                   "begins" #(str/starts-with? % needle)
                   ("contains" "notContains") #(str/includes? % needle))]
    (vec (for [label      ["Purchase" "Return" "Transfer" "Production" "Defect" "Sale" "Write-off" "Payment" "Opening stock" "Data edit"]
               cancelled? [false true]
               :let       [canonical (str label (when cancelled? " · cancelled"))
                           translated (str (i18n/t label) (when cancelled? (i18n/t " · cancelled")))]
               :when      (or (matches? (str/lower-case canonical)) (matches? (str/lower-case translated)))]
           (str/lower-case canonical)))))


(defn- condition-expression
  {:malli/schema [:=> [:cat :keyword :string :string :map] :string]}
  [view field variable {:keys [type matching-labels] :as condition}]
  (let [match   (case type
                  ("empty" "notEmpty") "STR(?value) != \"\""
                  ("eq" "notEq") (str "?value = ?" variable)
                  "begins" (str "STRSTARTS(STR(?value), ?" variable ")")
                  ("contains" "notContains") (str "CONTAINS(STR(?value), ?" variable ")"))
        pattern (if (and (= view :operations) (= field "name"))
                  (str "{ ?group r:f-name ?value } UNION { ?group r:nameItemId ?nameId . "
                       "OPTIONAL { ?item a r:Item ; r:id ?nameId ; r:acceptedFilterName ?fallback } "
                       "BIND(COALESCE(?fallback, \"\") AS ?value) }")
                  (str "?group r:f-" field " ?value ."))]
    (if (contains? condition :matching-labels)
      (str (when (#{"notEq" "notContains"} type) "!")
           "EXISTS { ?group r:f-type ?value . FILTER("
           (if (seq matching-labels)
             (str "?value IN (" (str/join "," (map-indexed (fn [index _] (str "?" variable "_label" index)) matching-labels)) ")")
             "false") ") }")
      (str (when (#{"empty" "notEq" "notContains"} type) "!")
           "EXISTS { " pattern " FILTER(" match ") }"))))


(defn- column-expression
  {:malli/schema [:=> [:cat [:vector :map]] :string]}
  [conditions]
  (loop [[condition & remaining] conditions current [] groups []]
    (let [current (conj current (:expression condition))]
      (if (and (seq remaining) (not= "or" (:relation condition)))
        (recur remaining current groups)
        (let [groups (conj groups (str "(" (str/join " && " current) ")"))]
          (if (seq remaining)
            (recur remaining [] groups)
            (str "(" (str/join " || " groups) ")")))))))


(defn- specification
  {:malli/schema [:=> [:cat shared/Scope :map] :map]}
  [scope {:keys [view sort direction filters operationType] :as input}]
  (let [conditions  (map-indexed (fn [index [field condition]]
                                   (cond-> (assoc condition :field field :variable (str "filter" index))
                                     (and (= view :operations) (= field "type") (= i18n/*locale* "ru")
                                          (not (#{"empty" "notEmpty"} (:type condition))))
                                     (assoc :matching-labels (matching-type-labels condition))))
                                 (for [[field conditions] filters condition conditions] [field condition]))
        expressions (group-by :field (map #(assoc % :expression (condition-expression view (:field %) (:variable %) %)) conditions))
        class?      (and (= view :stock) (= sort "quantity"))
        sort-vars   (cond-> [] class? (conj "sortClass") true (into ["sort" "id"]))
        where       (str "?group a ?kind ; r:id ?id ; r:rowCount ?rowCount ; r:s-" sort " ?sort .\n"
                         (when class? "?group r:sortClass ?sortClass .\n")
                         (when (and (= view :operations) (not (str/blank? operationType)))
                           "?group r:operationType ?operationType .\n")
                         (apply str (for [[_ conditions] (sort-by key expressions)]
                                      (str "FILTER(" (column-expression (vec conditions)) ")\n"))))]
    {:input    input :where where :sort-vars sort-vars
     :bindings (into {"graph" (graph scope) "kind" (term (if (= view :stock) "Stock" "Operation"))}
                     (concat
                       (when (and (= view :operations) (not (str/blank? operationType))) [["operationType" operationType]])
                       (map (fn [{:keys [variable value]}] [variable (str/lower-case (str value))]) conditions)
                       (mapcat (fn [{:keys [variable matching-labels]}]
                                 (map-indexed (fn [index label] [(str variable "_label" index) label]) matching-labels)) conditions)))
     :order    (str " ORDER BY " (str/join " " (map #(str (str/upper-case direction) "(?" % ")") sort-vars)))}))


(defn- select
  {:malli/schema [:=> [:cat rdf/Connection :map :string :string :string :map] [:vector :map]]}
  [conn spec fields extra tail bindings]
  (query conn (str "SELECT " fields " WHERE { GRAPH ?graph { " (:where spec) extra " } }" tail)
         (merge (:bindings spec) bindings)))


(defn- totals
  {:malli/schema [:=> [:cat rdf/Connection :map :string :map] :map]}
  [conn spec extra bindings]
  (first (select conn spec "(SUM(?rowCount) AS ?totalRows) (COUNT(*) AS ?totalGroups)" extra "" bindings)))


(defn- anchor
  {:malli/schema [:=> [:cat rdf/Connection :map :string] [:maybe :map]]}
  [conn spec id]
  (let [lookup (fn [id]
                 (first (select conn spec "?group ?id ?sort ?sortClass ?rowCount" "FILTER(?id = ?anchorId)" " LIMIT 1" {"anchorId" id})))
        exact  (lookup id)]
    (or exact
        (when (= :operations (get-in spec [:input :view]))
          (when-let [[_ group-id index] (re-matches #"(.+)/(\d+)" id)]
            (let [index (try (Long/parseLong index) (catch NumberFormatException _ nil))
                  group (lookup group-id)]
              (when (and index group (< index (:rowCount group))) group)))))))


(defn- preceding
  {:malli/schema [:=> [:cat :map :map] :map]}
  [spec target]
  (let [variables   (:sort-vars spec)
        operator    (if (= "desc" (get-in spec [:input :direction])) ">" "<")
        comparisons (map-indexed
                      (fn [index variable]
                        (str "(" (str/join " && "
                                           (concat
                                             (map #(str "?" % " = ?anchor_" %) (take index variables))
                                             [(str "?" variable " " operator " ?anchor_" variable)])) ")")) variables)]
    {:extra    (str "FILTER(" (str/join " || " comparisons) ")")
     :bindings (into {} (map (fn [variable] [(str "anchor_" variable) (get target (keyword variable))]) variables))}))


(defn- payload
  {:malli/schema [:=> [:cat :string] :map]}
  [value]
  (let [data (edn/read-string
               {:readers {}
                :default (fn [tag _]
                           (schema/fail! :invalid-storage "Unsupported read projection tag." {:tag tag}))}
               value)]
    (when-not (map? data) (schema/fail! :invalid-storage "The read projection payload is not a map." {}))
    data))


(defn- lookup
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :string [:sequential :string]] [:vector :map]]}
  [conn scope kind ids]
  (let [ids (vec (distinct ids))]
    (when (> (count ids) 500) (schema/fail! :invalid-input "Choose at most 500 inventory identifiers." {:status 400}))
    (if (empty? ids)
      []
      (let [variables (mapv #(str "lookup" %) (range (count ids)))
            rows      (query conn
                             (str "SELECT ?id ?payload WHERE { GRAPH ?graph { ?group a ?kind ; r:id ?id ; r:payload ?payload . "
                                  "FILTER(?id IN (" (str/join ", " (map #(str "?" %) variables)) ")) } }")
                             (into {"graph" (graph scope) "kind" (term kind)} (map vector variables ids)))
            revision  (when (= kind "Stock")
                        (rdf/value-at conn (graph scope) (rdf/iri (str (graph scope) ":state")) (term "ledgerRevision")))
            by-id     (into {} (map (fn [row] [(:id row) (cond-> (assoc (payload (:payload row)) :id (:id row))
                                                           (= kind "Stock") (assoc :ledger-revision revision))])) rows)]
        (mapv by-id (filter #(contains? by-id %) ids))))))


(defn items
  "Read only explicitly requested item metadata."
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope [:sequential :string]] [:map-of :string :map]]}
  [conn scope ids]
  (into {} (map (juxt :id identity)) (lookup conn scope "Item" ids)))


(defn operations
  "Read only explicitly requested operation reference metadata."
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope [:sequential :string]] [:vector :map]]}
  [conn scope ids]
  (lookup conn scope "Operation" ids))


(defn contains-group?
  "Check a group's membership in the complete scoped, filtered query."
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :map :string] :boolean]}
  [conn scope input group-id]
  (and (current? conn scope input)
       (boolean (seq (select conn (specification scope (checked-query input)) "?id"
                             "FILTER(?id = ?groupId)" " LIMIT 1" {"groupId" group-id})))))


(defn page
  "Read a bounded physical window, expanding its first and last groups in full."
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :map] [:maybe :map]]}
  [conn scope input]
  (let [{:keys [view start count anchorId detailRowId pendingRows] :as input} (checked-query input)]
    (when (current? conn scope input)
      (let [spec (specification scope input)
            {:keys [totalRows totalGroups]} (totals conn spec "" {})
            totalRows (or totalRows 0)
            requested (when anchorId (anchor conn spec anchorId))
            physical "?physical a r:DisplayRow ; r:group ?group ; r:lineIndex ?lineIndex .\n"
            first-group (or requested
                            (when (pos? totalRows)
                              (first (select conn spec "?group ?id ?sort ?sortClass ?rowCount" physical
                                             (str (:order spec) " ASC(?lineIndex) OFFSET " (min start (dec totalRows)) " LIMIT 1") {}))))
            before (if first-group
                     (let [{:keys [extra bindings]} (preceding spec first-group)] (totals conn spec extra bindings))
                     {:totalRows 0 :totalGroups 0})
            start (or (:totalRows before) 0)
            groups (when first-group
                     (->> (select conn spec "?id ?rowCount" physical
                                  (str (:order spec) " ASC(?lineIndex) OFFSET " start " LIMIT " count) {})
                          (reduce (fn [result group]
                                    (if (= (:id (peek result)) (:id group)) result (conj result group))) [])))
            ids (mapv :id groups)
            columns (into (if (= view :stock) (set (map :key filtering/stock-columns)) #{"type" "occurred-at" "reason"})
                          (map :column (select conn spec "DISTINCT ?column" "?group r:column ?column ." "" {})))
            column-order                    (map :key (if (= view :stock) tables/stock-columns grid/columns))
            pending (lookup conn scope "Stock" pendingRows)
            found (set (map :id pending))]
        (cond-> {:rows        (lookup conn scope (if (= view :stock) "Stock" "Operation") ids)
                 :columns     (filterv columns column-order)
                 :totalRows   totalRows :totalGroups totalGroups :start start
                 :end         (+ start (reduce + 0 (map :rowCount groups)))                    :groupStart (:totalGroups before)
                 :pendingRows pending :missingRows (filterv #(not (found %)) pendingRows)}
          anchorId (assoc :anchorFound (boolean requested))
          detailRowId (assoc :detailFound (boolean (anchor conn spec detailRowId))))))))


(defn- checked-limit
  {:malli/schema [:=> [:cat :int] :int]}
  [limit]
  (when-not (pos? limit) (schema/fail! :invalid-input "Choose a positive suggestion limit." {:status 400}))
  (min 30 limit))


(defn suggest-items
  "Suggest a bounded prefix match on item names and articles."
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :string :int] [:map-of :string :map]]}
  [conn scope text limit]
  (into {} (map (fn [row] [(:id row) (payload (:payload row))]))
        (query conn
               (str "SELECT ?id ?payload WHERE { GRAPH ?graph { ?item a r:Item ; r:id ?id ; r:payload ?payload . "
                    "FILTER(EXISTS { ?item r:f-name ?name . FILTER(STRSTARTS(STR(?name), ?needle)) } || "
                    "EXISTS { ?item r:f-article ?article . FILTER(STRSTARTS(STR(?article), ?needle)) }) } } "
                    "ORDER BY ?id LIMIT " (checked-limit limit))
               {"graph" (graph scope) "needle" (str/lower-case text)})))


(defn suggest-operations
  "Suggest bounded effective purchases and sales using only scalar reference fields."
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :string :int] [:vector :map]]}
  [conn scope text limit]
  (mapv (fn [{:keys [id operationType party occurredAt]}]
          (let [date (if (instance? Literal occurredAt) (.getLabel ^Literal occurredAt) (str occurredAt))]
            {:value id :label (str (grid/label (keyword operationType)) " · " party " · " (subs date 0 10))}))
        (query conn
               (str "SELECT ?id ?operationType ?party ?occurredAt WHERE { GRAPH ?graph { "
                    "?group a r:Operation ; r:id ?id ; r:operationType ?operationType ; r:cancelled false ; "
                    "r:v-party ?party ; r:s-occurred-at ?occurredAt . "
                    "VALUES ?operationType { \"purchase\" \"sale\" } "
                    "BIND(IF(?operationType = \"purchase\", ?purchaseLabel, ?saleLabel) AS ?typeLabel) "
                    "FILTER(STRSTARTS(LCASE(STR(?party)), ?needle) || STRSTARTS(STR(?operationType), ?needle) || STRSTARTS(?typeLabel, ?needle) || "
                    "STRSTARTS(LCASE(STR(?id)), ?needle)) } } "
                    "ORDER BY DESC(?occurredAt) DESC(?id) LIMIT " (checked-limit limit))
               {"graph"         (graph scope) "needle" (str/lower-case text)
                "purchaseLabel" (str/lower-case (i18n/t "Purchase")) "saleLabel" (str/lower-case (i18n/t "Sale"))})))


(defn values
  "Suggest bounded distinct display values without reading their group payloads."
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope [:or :keyword :string] :string :int] [:vector :string]]}
  [conn scope field text limit]
  (let [field   (name field)
        allowed (into (set (map :key (concat filtering/stock-columns filtering/operation-columns)))
                      ["party" "currency" "from-location" "to-location"])]
    (if-not (allowed field)
      []
      (mapv :value
            (query conn
                   (str "SELECT DISTINCT ?value WHERE { GRAPH ?graph { ?group a ?kind ; r:v-" field " ?value . "
                        "VALUES ?kind { r:Stock r:Operation } "
                        "FILTER(STR(?value) != \"\" && STRSTARTS(LCASE(STR(?value)), ?needle)) } } "
                        "ORDER BY ?value LIMIT " (checked-limit limit))
                   {"graph" (graph scope) "needle" (str/lower-case text)})))))
