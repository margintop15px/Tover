(ns tover.inventory.read-query-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [tover.inventory.read-model :as read-model]
    [tover.inventory.read-query :as read-query]
    [tover.inventory.tables :as tables]
    [tover.shared.i18n :as i18n]
    [tover.shared.rdf :as rdf])
  (:import
    [java.net URLEncoder]
    [java.nio.charset StandardCharsets]
    [org.eclipse.rdf4j.model.impl SimpleValueFactory]
    [org.eclipse.rdf4j.model.vocabulary RDF XSD]
    [org.eclipse.rdf4j.repository.sail SailRepository]
    [org.eclipse.rdf4j.sail.memory MemoryStore]))


(defn- graph
  {:malli/schema [:=> [:cat :map] rdf/IRI]}
  [scope]
  (rdf/iri (str "urn:tover:workspace:" (:workspace-id scope) ":inventory-read-v1")))


(defn- term
  {:malli/schema [:=> [:cat :string] rdf/IRI]}
  [name]
  (rdf/iri (str "urn:tover:inventory-read-v1:" name)))


(defn- date
  {:malli/schema [:=> [:cat :string] rdf/Literal]}
  [value]
  (.createLiteral (SimpleValueFactory/getInstance) ^String value XSD/DATETIME))


(defn- with-store
  {:malli/schema [:=> [:cat [:=> [:cat rdf/Connection :map] :any]] :any]}
  [run]
  (let [repo  (doto (SailRepository. (MemoryStore.)) .init)
        scope {:workspace-id (random-uuid) :user-id (random-uuid)}]
    (try
      (rdf/with-transaction [conn {:repo repo :level :serializable}]
        (rdf/add! conn (graph scope) (rdf/iri (str (graph scope) ":state")) (term "generation") 1)
        (rdf/add! conn (graph scope) (rdf/iri (str (graph scope) ":state")) (term "epoch") "initial")
        (run conn scope))
      (finally (.shutDown repo)))))


(defn- seed-group!
  {:malli/schema [:=> [:cat rdf/Connection :map :map] rdf/IRI]}
  [conn scope {:keys [view data sorts filters raw columns row-count sort-class]
               :or   {view :stock row-count 1 sort-class 0}}]
  (let [graph (graph scope)
        group (rdf/iri (str graph ":group:" (name view) ":" (URLEncoder/encode ^String (:id data) StandardCharsets/UTF_8)))]
    (rdf/add! conn graph group RDF/TYPE (term (if (= view :stock) "Stock" "Operation")))
    (doseq [entry {"id"            (:id data) "payload" (pr-str data) "rowCount" row-count "sortClass" sort-class
                   "operationType" (or (some-> (:type data) name) "")}]
      (rdf/add! conn graph group (term (key entry)) (val entry)))
    (when (= view :operations)
      (rdf/add! conn graph group (term "cancelled") (boolean (:cancelled? data))))
    (doseq [[key value] sorts] (rdf/add! conn graph group (term (str "s-" key)) value))
    (doseq [[key values] filters value values]
      (rdf/add! conn graph group (term (str "f-" key)) (str/lower-case (str value))))
    (doseq [[key values] raw value values]
      (rdf/add! conn graph group (term (str "v-" key)) value))
    (doseq [column columns] (rdf/add! conn graph group (term "column") column))
    (doseq [index (range row-count)]
      (let [row (rdf/iri (str group ":row:" index))]
        (rdf/add! conn graph row RDF/TYPE (term "DisplayRow"))
        (rdf/add! conn graph row (term "group") group)
        (rdf/add! conn graph row (term "lineIndex") index)))
    group))


(defn- seed-operation!
  {:malli/schema [:=> [:cat rdf/Connection :map :string :int :map] rdf/IRI]}
  [conn scope id rows options]
  (seed-group! conn scope
               (merge {:view      :operations                   :data {:id id :kind :operation :type :purchase}
                       :row-count rows :sorts {"occurred-at" (date "2026-10-20T12:00:00Z") "type" "Purchase"}
                       :columns   ["type" "occurred-at" "name"]} options)))


(defn- ids
  {:malli/schema [:=> [:cat :map] [:vector :string]]}
  [page]
  (mapv :id (:rows page)))


(defn- condition
  {:malli/schema [:=> [:cat :string :string [:? :string]] :map]}
  [type value & [relation]]
  (cond-> {:id (str (random-uuid)) :type type :value value} relation (assoc :relation relation)))


(deftest filters-cover-the-complete-query-and-preserve-multivalue-negatives
  (with-store
    (fn [conn scope]
      (doseq [[id names] [["a" ["Alpha" "Beta"]] ["b" ["Alpha"]] ["c" [""]] ["d" []]]]
        (seed-operation! conn scope id 1 {:filters {"name" names "reason" ["Evidence"]}}))
      (let [group (seed-operation! conn scope "e" 1 {:filters {"reason" ["Other"]}})
            item  (rdf/iri (str (graph scope) ":item:fallback"))]
        (rdf/add! conn (graph scope) group (term "nameItemId") "fallback")
        (rdf/add! conn (graph scope) item RDF/TYPE (term "Item"))
        (rdf/add! conn (graph scope) item (term "id") "fallback")
        (rdf/add! conn (graph scope) item (term "acceptedFilterName") "gamma"))
      (doseq [example [["eq" "alpha" ["a" "b"]]
                       ["notEq" "alpha" ["c" "d" "e"]]
                       ["notContains" "ta" ["b" "c" "d" "e"]]
                       ["eq" "" ["c"]]
                       ["notEq" "" ["a" "b" "d" "e"]]
                       ["empty" "" ["c" "d"]]
                       ["notEmpty" "" ["a" "b" "e"]]]]
        (let [[type value expected] example
              input                 {:view :operations :direction "asc" :filters {"name" [(condition type value)]}}]
          (is (= expected (ids (read-query/page conn scope input))) type)))
      (is (= ["b" "e"]
             (ids (read-query/page conn scope {:view    :operations :direction "asc"
                                               :filters {"name" [(condition "contains" "ALPHA" "and")
                                                                 (condition "notContains" "beta" "or")
                                                                 (condition "eq" "gamma")]}}))))
      (is (= ["b"]
             (ids (read-query/page conn scope {:view    :operations :direction "asc"
                                               :filters {"name"   [(condition "contains" "alpha") (condition "notContains" "beta")]
                                                         "reason" [(condition "eq" "evidence")]}}))))
      (is (= [] (ids (read-query/page conn scope {:view :operations :filters {"name" [(condition "eq" "\") } UNION { ?a ?b ?c")]}})))))))


(deftest ordering-keeps-numeric-classes-instants-and-id-ties
  (with-store
    (fn [conn scope]
      (doseq [[id quantity class] [["one" 1 0] ["two-a" 2 0] ["two-b" 2 0] ["blank" "" 1] ["text" "3" 1]]]
        (seed-group! conn scope {:data {:id id :quantity quantity} :sorts {"quantity" quantity} :sort-class class}))
      (is (= ["one" "two-a" "two-b" "blank" "text"] (ids (read-query/page conn scope {:sort "quantity"}))))
      (is (= ["text" "blank" "two-b" "two-a" "one"] (ids (read-query/page conn scope {:sort "quantity" :direction "desc"}))))
      (is (= ["blank"] (ids (read-query/page conn scope {:sort "quantity" :start 3 :count 1}))))
      (is (= 3 (:start (read-query/page conn scope {:sort "quantity" :anchorId "blank" :count 1}))))
      (seed-operation! conn scope "earlier" 1 {:sorts {"occurred-at" (date "2026-10-20T13:00:00+02:00")}})
      (seed-operation! conn scope "later-a" 1 {:sorts {"occurred-at" (date "2026-10-20T12:00:00Z")}})
      (seed-operation! conn scope "later-b" 1 {:sorts {"occurred-at" (date "2026-10-20T12:00:00Z")}})
      (is (= ["earlier" "later-a" "later-b"] (ids (read-query/page conn scope {:view :operations :direction "asc"}))))
      (is (= ["later-b" "later-a" "earlier"] (ids (read-query/page conn scope {:view :operations})))))))


(deftest physical-windows-expand-whole-groups-and-return-global-columns
  (with-store
    (fn [conn scope]
      (seed-operation! conn scope "a" 2 {})
      (seed-operation! conn scope "b" 501 {})
      (seed-operation! conn scope "c" 3 {:columns ["party"]})
      (let [input {:view :operations :direction "asc" :start 250}
            page  (read-query/page conn scope input)]
        (is (= ["b"] (ids page)))
        (is (= [506 3 2 503 1] ((juxt :totalRows :totalGroups :start :end :groupStart) page)))
        (is (= ["type" "occurred-at" "party" "name" "reason"] (:columns page)))
        (doseq [anchor ["b" "b/0" "b/400"]]
          (let [anchored (read-query/page conn scope (assoc input :start 0 :anchorId anchor))]
            (is (= ["b"] (ids anchored)))
            (is (= 2 (:start anchored)))
            (is (:anchorFound anchored))))
        (let [missing (read-query/page conn scope (assoc input :start 0 :count 1 :anchorId "b/501"))]
          (is (= ["a"] (ids missing)))
          (is (false? (:anchorFound missing))))
        (let [last-page (read-query/page conn scope (assoc input :start 9000))]
          (is (= ["c"] (ids last-page)))
          (is (= [503 506 2] ((juxt :start :end :groupStart) last-page))))
        (is (= ["a" "b"] (ids (read-query/page conn scope (assoc input :start 0 :count 3)))))))))


(deftest query-membership-scopes-generations-and-pending-stock-rows
  (with-store
    (fn [conn scope]
      (seed-group! conn scope {:data {:id "visible"} :sorts {"name" "A"} :filters {"name" ["A"]}})
      (seed-group! conn scope {:data {:id "hidden"} :sorts {"name" "Z"} :filters {"name" ["Z"]}})
      (let [input {:filters     {"name" [(condition "eq" "a")]} :generation  1        :epoch "initial"
                   :pendingRows ["hidden" "missing" "hidden"]   :detailRowId "hidden"}
            page  (read-query/page conn scope input)]
        (is (= ["visible"] (ids page)))
        (is (= ["hidden"] (mapv :id (:pendingRows page))))
        (is (= ["missing"] (:missingRows page)))
        (is (false? (:detailFound page)))
        (is (read-query/contains-group? conn scope input "visible"))
        (is (false? (read-query/contains-group? conn scope input "hidden")))
        (is (nil? (read-query/page conn scope (assoc input :generation 2))))
        (is (false? (read-query/contains-group? conn scope (assoc input :generation 2) "visible")))
        (rdf/remove! conn (graph scope) (rdf/iri (str (graph scope) ":state")) (term "epoch") nil)
        (rdf/add! conn (graph scope) (rdf/iri (str (graph scope) ":state")) (term "epoch") "replacement")
        (is (nil? (read-query/page conn scope input)))
        (is (false? (read-query/contains-group? conn scope input "visible")))
        (is (= ["visible"] (ids (read-query/page conn scope (assoc input :epoch "replacement")))))
        (let [empty-page (read-query/page conn (assoc scope :workspace-id (random-uuid)) {})]
          (is (= [0 0 0 0 0] ((juxt :totalRows :totalGroups :start :end :groupStart) empty-page)))
          (is (= ["name" "quantity" "unit" "location" "article" "barcode"] (:columns empty-page)))
          (is (empty? (:rows empty-page))))
        (is (= ["type" "occurred-at" "reason"]
               (:columns (read-query/page conn scope {:view :operations}))))))))


(deftest payload-decoding-and-suggestions-stay-bounded
  (with-store
    (fn [conn scope]
      (doseq [index (range 300)]
        (seed-group! conn scope {:data  {:id (format "row-%03d" index)}
                                 :sorts {"name" (format "Goods %03d" index)}
                                 :raw   {"location" [(str "Depot " index)]}}))
      (let [reads  (atom 0)
            decode @#'tover.inventory.read-query/payload]
        (with-redefs-fn {#'tover.inventory.read-query/payload (fn [value] (swap! reads inc) (decode value))}
          (fn [] (is (= 10 (count (:rows (read-query/page conn scope {:start 250 :count 10})))))))
        (is (= 10 @reads) "Only the selected group payloads are decoded"))
      (is (= 30 (count (read-query/values conn scope :location "DEPOT " 200))))
      (is (= ["Depot 299"] (read-query/values conn scope :location "depot 299" 30)))
      (is (= [] (read-query/values conn scope :amount "" 30)))
      (let [item (rdf/iri (str (graph scope) ":item:one"))]
        (rdf/add! conn (graph scope) item RDF/TYPE (term "Item"))
        (doseq [entry {"id"     "one"        "payload"   (pr-str {:id "one" :name "Blue shirt" :article "ART-1"})
                       "f-name" "blue shirt" "f-article" "art-1"}]
          (rdf/add! conn (graph scope) item (term (key entry)) (val entry))))
      (is (= #{"one"} (set (keys (read-query/suggest-items conn scope "BLUE" 30)))))
      (is (= #{"one"} (set (keys (read-query/suggest-items conn scope "art-" 30)))))
      (is (= {} (read-query/items conn scope ["unknown"])))
      (is (= "Blue shirt" (get-in (read-query/items conn scope ["one"]) ["one" :name])))
      (seed-operation! conn scope "purchase" 1 {:data {:id "purchase" :type :purchase :party "Supplier"}})
      (is (= ["purchase"] (mapv :id (read-query/operations conn scope ["unknown" "purchase"])))))))


(deftest operation-suggestions-read-only-bounded-effective-reference-scalars
  (with-store
    (fn [conn scope]
      (doseq [index (range 35)]
        (seed-operation! conn scope (format "operation-%03d" index) 1
                         {:data {:id (format "operation-%03d" index) :type (if (zero? index) :sale :purchase)}
                          :raw  {"party" [(str "Supplier " index)]}}))
      (seed-operation! conn scope "cancelled" 1 {:data {:id "cancelled" :type :purchase :cancelled? true}
                                                 :raw  {"party" ["Supplier cancelled"]}})
      (seed-operation! conn scope "return" 1 {:data {:id "return" :type :return}
                                              :raw  {"party" ["Supplier return"]}})
      (with-redefs-fn {#'tover.inventory.read-query/payload (fn [_] (throw (ex-info "No reference payload should be read." {})))}
        (fn []
          (let [options (read-query/suggest-operations conn scope "" 200)]
            (is (= 30 (count options)))
            (is (= "operation-034" (:value (first options))))
            (is (= "Purchase · Supplier 34 · 2026-10-20" (:label (first options))))
            (is (not-any? #(#{"cancelled" "return"} (:value %)) options)))
          (is (= ["operation-030"] (mapv :value (read-query/suggest-operations conn scope "SUPPLIER 30" 30))))
          (is (= ["operation-000"] (mapv :value (read-query/suggest-operations conn scope "sale" 30))))
          (is (= ["operation-001"] (mapv :value (read-query/suggest-operations conn scope "operation-001" 30))))
          (is (= [] (read-query/suggest-operations conn (assoc scope :workspace-id (random-uuid)) "" 30))))))))


(deftest russian-operation-filters-match-translated-and-canonical-labels
  (with-store
    (fn [conn scope]
      (seed-operation! conn scope "purchase" 1 {:filters {"type" ["Purchase"]} :raw {"party" ["Supplier"]}})
      (seed-operation! conn scope "sale" 1 {:data    {:id "sale" :kind :operation :type :sale}
                                            :filters {"type" ["Sale"]} :raw {"party" ["Customer"]}})
      (seed-operation! conn scope "cancelled" 1 {:data    {:id "cancelled" :kind :operation :type :purchase :cancelled? true}
                                                 :filters {"type" ["Purchase · cancelled"]}})
      (binding [i18n/*locale* "ru"]
        (doseq [[type value expected] [["begins" "зак" #{"purchase" "cancelled"}]
                                       ["eq" "Закупка" #{"purchase"}]
                                       ["eq" "Purchase" #{"purchase"}]
                                       ["notContains" "закуп" #{"sale"}]
                                       ["notEq" "Закупка" #{"sale" "cancelled"}]
                                       ["begins" "unmatched" #{}]]]
          (is (= expected (set (ids (read-query/page conn scope {:view    :operations
                                                                 :filters {"type" [{:id "condition" :type type :value value}]}}))))))
        (is (= ["purchase"] (mapv :value (read-query/suggest-operations conn scope "зак" 30))))
        (is (str/starts-with? (:label (first (read-query/suggest-operations conn scope "зак" 30))) "Закупка"))))))


(deftest accepted-operation-projections-stay-neutral-when-submitted-in-russian
  (with-store
    (fn [conn scope]
      (let [entry  {:kind  :operation :type :purchase :occurred-at "2026-10-20T12:00:00Z" :party "Incoming"
                    :lines [{:id "line" :item-id "goods" :name "Purchase" :quantity 3 :unit "pcs" :location "Stock"}]}
            record {:entity-id   "from-russian"         :revision 1     :sequence 1 :business-order 1 :actor "user"
                    :recorded-at "2026-10-20T12:30:00Z" :entry    entry}]
        (binding [i18n/*locale* "ru"] (read-model/stage-records! conn scope [record] {}))
        (let [rows (:rows (read-query/page conn scope {:view :operations}))
              cell (fn [locale]
                     (binding [i18n/*locale* locale]
                       (let [model (tables/operations rows)
                             index (first (keep-indexed #(when (= "type" (:key %2)) %1) (:columns model)))]
                         (get-in model [:rows 0 :cells index :value]))))]
          (is (= "Purchase" (:operation (first rows))))
          (is (= "Purchase" (get-in rows [0 :lines 0 :name])))
          (is (= "Incoming" (:party (first rows))))
          (is (= "Закупка" (cell "ru")))
          (is (= "Purchase" (cell "en"))))))))
