(ns tover.catalogues.query
  (:require
    [bel.validation :as validation]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [tover.catalogues.schema :as catalogue]
    [tover.catalogues.store :as store]
    [tover.inventory.filters :as filters]
    [tover.inventory.schema :as schema]
    [tover.shared.i18n :as i18n]
    [tover.shared.rdf :as rdf]
    [tover.shared.schema :as shared]))


(defn- condition
  {:malli/schema [:=> [:cat :string :string :string] :string]}
  [field parameter type]
  (case type
    "empty" (str field " = \"\"")
    "notEmpty" (str field " != \"\"")
    "eq" (str field " = " parameter)
    "notEq" (str field " != " parameter)
    "begins" (str "STRSTARTS(" field ", " parameter ")")
    "contains" (str "CONTAINS(" field ", " parameter ")")
    "notContains" (str "!CONTAINS(" field ", " parameter ")")))


(defn- spec
  {:malli/schema [:=> [:cat shared/Scope :map] :map]}
  [scope {:keys [kind sort direction query] :as input}]
  (validation/check! catalogue/Query input)
  (let [kind      (keyword kind)
        columns   (mapv (comp name first) (get catalogue/columns kind))
        filters   (filters/check! (str "catalogue-" (name kind)) (:filters input))
        sort      (or sort "name")
        _         (when-not (some #{sort} columns) (schema/fail! :validation "Choose an available catalogue field." {}))
        variables (zipmap columns (map #(str "?f" %) (range)))
        clauses   (map-indexed
                    (fn [index column]
                      (let [raw   (str "?raw" index) ref (str "?ref" index)
                            label (if (= column "purpose")
                                    (str "IF(" raw " = \"storage\", ?storage, IF(" raw " = \"sales\", ?sales, IF(" raw " = \"production\", ?production, " raw ")))")
                                    raw)]
                        (str "OPTIONAL { ?node c:"
                             (if (catalogue/references (keyword column)) (str "ref-" column " " ref " . " ref " c:name") column)
                             " " raw " . } BIND(LCASE(COALESCE(" label ", \"\")) AS " (variables column) ") ")))
                    columns)
        tests     (for [[column conditions] filters]
                    (reduce (fn [{:keys [expression bindings join]} [index {:keys [type value relation]}]]
                              (let [parameter (str "v" (.indexOf columns column) "_" index)
                                    part      (str "(" (condition (variables column) (str "?" parameter) type) ")")]
                                {:expression (str expression join part)
                                 :join       (if (= relation "or") " || " " && ")
                                 :bindings   (assoc bindings (keyword parameter) (str/lower-case (or value "")))}))
                            {:bindings {}} (map-indexed vector conditions)))
        where     (str "GRAPH ?graph { ?node c:kind ?kind; c:id ?id; c:payload ?payload . "
                       (str/join clauses) "FILTER(STRSTARTS(?f0, ?needle)) "
                       (apply str (map #(str "FILTER(" (:expression %) ") ") tests)) "}")]
    {:where    where :sort-variable (variables sort) :direction (if (= direction "desc") "DESC" "ASC")
     :bindings (apply merge {:graph   (store/graph scope) :kind  (name kind)      :needle     (str/lower-case (or query ""))
                             :storage (i18n/t "Storage")  :sales (i18n/t "Sales") :production (i18n/t "Production")} (map :bindings tests))}))


(defn page
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :map] :map]}
  [conn scope {:keys [kind start count anchorId pendingRows] :or {start 0 count 200 pendingRows []} :as input}]
  (when-not (and (integer? start) (<= 0 start) (integer? count) (pos? count)
                 (vector? pendingRows) (<= (clojure.core/count pendingRows) 100) (every? string? pendingRows))
    (schema/fail! :validation "Choose a valid inventory window." {}))
  (let [{:keys [where bindings sort-variable direction]} (spec scope input)
        total (:n (first (store/query conn (str "SELECT (COUNT(*) AS ?n) WHERE { " where " }") bindings)))
        anchor (when anchorId (first (store/query conn (str "SELECT " sort-variable " WHERE { " where " FILTER(?id = ?anchor) }") (assoc bindings :anchor anchorId))))
        offset (if anchor
                 (:n (first (store/query conn (str "SELECT (COUNT(*) AS ?n) WHERE { " where " FILTER(" sort-variable
                                                   (if (= direction "DESC") " > " " < ") "?anchorValue || (" sort-variable " = ?anchorValue && ?id < ?anchor)) }")
                                         (assoc bindings :anchor anchorId :anchorValue (get anchor (keyword (subs sort-variable 1)))))))
                 (min start (max 0 (dec total))))
        rows (store/query conn (str "SELECT ?payload WHERE { " where " } ORDER BY " direction "(" sort-variable ") ASC(?id) OFFSET " offset " LIMIT " (min 200 count)) bindings)
        decode #(store/display conn scope (keyword kind) (edn/read-string {:readers {}} (:payload %)))
        pending (keep #(some->> (store/record conn scope (keyword kind) %) (store/display conn scope (keyword kind))) pendingRows)]
    {:rows        (mapv decode rows) :pending (vec pending)
     :missingRows (filterv #(not (some (fn [row] (= % (:id row))) pending)) pendingRows)
     :viewport    (cond-> {:start offset :end (+ offset (clojure.core/count rows)) :groupStart offset :totalRows total :totalGroups total}
                    anchorId (assoc :anchorFound (boolean anchor)))}))


(defn choices
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :keyword :string] [:vector :map]]}
  [conn scope kind text]
  (mapv (fn [row] (cond-> {:value (:id row) :label (:name row)}
                    (= kind :warehouses) (assoc :locationKey (:location-key row))))
        (:rows (page conn scope {:kind (name kind) :query text :count 30}))))
