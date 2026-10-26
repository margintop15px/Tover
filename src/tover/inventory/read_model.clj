(ns tover.inventory.read-model
  "Disposable RDF rows for bounded inventory reads; accepted semantic history remains authoritative."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [tover.inventory.filters :as filters]
    [tover.inventory.model :as model]
    [tover.inventory.semantics :as semantics]
    [tover.inventory.tables :as tables]
    [tover.operations.ui :as operations-ui]
    [tover.shared.i18n :as i18n]
    [tover.shared.rdf :as rdf]
    [tover.shared.schema :as shared])
  (:import
    [java.net URLEncoder]
    [java.nio.charset StandardCharsets]
    [org.eclipse.rdf4j.model Value]
    [org.eclipse.rdf4j.model.impl SimpleValueFactory]
    [org.eclipse.rdf4j.model.vocabulary RDF XSD]
    [org.eclipse.rdf4j.query BindingSet]
    [org.eclipse.rdf4j.repository RepositoryConnection]))


(defn graph
  {:malli/schema [:=> [:cat shared/Scope] rdf/IRI]}
  [scope]
  (rdf/iri (str "urn:tover:workspace:" (:workspace-id scope) ":inventory-read-v1")))


(defn term
  {:malli/schema [:=> [:cat :string] rdf/IRI]}
  [local]
  (rdf/iri (str "urn:tover:inventory-read-v1:" local)))


(defn- subject
  {:malli/schema [:=> [:cat shared/Scope :string :string] rdf/IRI]}
  [scope kind id]
  (rdf/iri (str (graph scope) ":" kind ":" (URLEncoder/encode ^String id StandardCharsets/UTF_8))))


(defn- state-subject
  {:malli/schema [:=> [:cat shared/Scope] rdf/IRI]}
  [scope]
  (rdf/iri (str (graph scope) ":state")))


(defn stock-rows
  "Show durable pending edits over accepted rows; acceptance still uses the ledger snapshot."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [{:keys [rows drafts ledger-revision]}]
  (let [fields      [:name :article :barcode :unit :location :quantity]
        corrections (into {} (for [draft drafts
                                   :when (= :correction (get-in draft [:entry :kind]))]
                               [(get-in draft [:entry :stock-row-id]) draft]))
        accepted    (mapv (fn [row]
                            (let [draft    (get corrections (:id row))
                                  entry    (:entry draft)
                                  proposed (when draft
                                             (merge (select-keys (last (:lines entry)) [:name :article :barcode :unit])
                                                    {:quantity (:stock-count entry)
                                                     :location (:stock-location entry)}))]
                              (cond-> (assoc (merge row proposed)
                                             :ledger-revision ledger-revision
                                             :draft-id (:id draft)
                                             :draft-revision (:revision draft))
                                draft (assoc :_changeClass "sheet-row-modified"
                                             :_accepted (select-keys row fields)
                                             :_changedFields (mapv name (filter #(not= (get row %) (get proposed %)) fields))))))
                          rows)
        added       (for [draft        drafts :when (= :opening (get-in draft [:entry :kind]))
                          [index line] (map-indexed vector (get-in draft [:entry :lines]))]
                      (merge {:name "" :article "" :barcode "" :unit "pcs" :location "" :quantity ""} line
                             {:id              (str "draft:" (:id draft) ":" index)
                              :ledger-revision ledger-revision
                              :draft-id        (:id draft)
                              :draft-revision  (:revision draft)
                              :draft-kind      "opening"
                              :_changeClass    "sheet-row-added"
                              :_changedFields  (mapv name fields)}))]
    (into accepted added)))


(defn- pending-drafts
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [current]
  (->> (:drafts current) vals (remove #(= :composing (:phase %))) (sort-by (juxt :saved-at :id)) vec))


(defn- draft-items
  {:malli/schema [:=> [:cat [:vector :map]] [:map-of :string :map]]}
  [drafts]
  (into {} (for [draft drafts
                 line  (tree-seq coll? seq (:entry draft))
                 :when (and (map? line) (string? (:item-id line)) (not (str/blank? (:item-id line)))
                            (string? (:name line)) (not (str/blank? (:name line))))]
             [(:item-id line) (assoc (select-keys line [:name :article :barcode :unit]) :id (:item-id line))])))


(defn- text
  {:malli/schema [:=> [:cat :any] :string]}
  [value]
  (if (keyword? value) (name value) (str value)))


(defn- filter-values
  {:malli/schema [:=> [:cat :keyword :map :string :map] [:vector :any]]}
  [view row column _items]
  (cond
    (and (= :operations view) (= "name" column))
    (into [] (keep :name) (mapcat #(get row % []) [:lines :inputs :outputs]))
    (and (= :operations view) (= "type" column)) [(or (:operation row) (:type row))]
    :else [(get row (keyword column))]))


(defn- remove-group!
  {:malli/schema [:=> [:cat rdf/Connection rdf/IRI rdf/IRI] :nil]}
  [conn graph group]
  (doseq [{:keys [row]} (rdf/select conn {:select '[?row]
                                          :where  '[[:graph ?graph [[?row ?groupPredicate ?group]]]]}
                                    {'?graph graph '?groupPredicate (term "group") '?group group})]
    (rdf/remove! conn graph row nil nil))
  (rdf/remove! conn graph group nil nil))


(defn- remove-type!
  {:malli/schema [:=> [:cat rdf/Connection rdf/IRI rdf/IRI] :nil]}
  [conn graph type]
  (doseq [{:keys [group]} (rdf/select conn {:select '[?group]
                                            :where  '[[:graph ?graph [[?group ?typePredicate ?type]]]]}
                                      {'?graph graph '?typePredicate RDF/TYPE '?type type})]
    (remove-group! conn graph group)))


(defn- stage-group!
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :keyword :map :map] :nil]}
  [conn scope view row items]
  (let [row     (if (and (= :operations view) (= :purchase (:type row)) (contains? row :receipts))
                  (assoc (dissoc row :receipts) :lines (vec (mapcat :lines (:receipts row))))
                  row)
        graph   (graph scope)
        stock?  (= :stock view)
        group   (subject scope (if stock? "stock" "operation") (:id row))
        grid    (if stock? (tables/stock [row]) (tables/operations [row]))
        columns (distinct (concat (map :key (filters/columns view))
                                  (when-not stock? ["party" "location" "from-location" "to-location" "currency"])))]
    (remove-group! conn graph group)
    (rdf/add! conn graph group RDF/TYPE (term (if stock? "Stock" "Operation")))
    (rdf/add! conn graph group (term "id") (:id row))
    (rdf/add! conn graph group (term "payload") (pr-str row))
    (rdf/add! conn graph group (term "rowCount") (count (:rows grid)))
    (when (and stock? (:item-id row)) (rdf/add! conn graph group (term "itemId") (:item-id row)))
    (when-not stock? (rdf/add! conn graph group (term "operationType") (text (:type row))))
    (when-not stock? (rdf/add! conn graph group (term "cancelled") (boolean (:cancelled? row))))
    (when-not stock?
      (doseq [line (mapcat #(get row % []) [:lines :inputs :outputs]) :when (nil? (:name line))]
        (rdf/add! conn graph group (term "nameItemId") (:item-id line))))
    (doseq [{:keys [key]} (:columns grid)] (rdf/add! conn graph group (term "column") key))
    (doseq [column columns
            value  (filter-values view row column items)]
      (rdf/add! conn graph group (term (str "f-" column)) (str/lower-case (text value)))
      (rdf/add! conn graph group (term (str "v-" column)) (text value)))
    (doseq [column (if stock? ["name" "article" "barcode" "unit" "location" "quantity"]
                       ["occurred-at" "items" "type" "party" "summary" "context" "reason"])]
      (let [value (get row (keyword column))
            value (cond
                    (and stock? (= "quantity" column)) (if (number? value) value (text value))
                    stock? (text value)
                    (= "occurred-at" column) (.createLiteral (SimpleValueFactory/getInstance) ^String value XSD/DATETIME)
                    (= "items" column) value
                    (= "type" column) (:operation row)
                    :else (str/lower-case (text value)))]
        (rdf/add! conn graph group (term (str "s-" column)) value)))
    (when stock? (rdf/add! conn graph group (term "sortClass") (if (number? (:quantity row)) 0 1)))
    (doseq [[index row] (map-indexed vector (:rows grid))]
      (let [physical (rdf/iri (str group ":row:" index))]
        (rdf/add! conn graph physical RDF/TYPE (term "DisplayRow"))
        (rdf/add! conn graph physical (term "id") (:id row))
        (rdf/add! conn graph physical (term "group") group)
        (rdf/add! conn graph physical (term "lineIndex") index))))
  nil)


(defn- select
  {:malli/schema [:=> [:cat rdf/Connection :string :map] [:vector :map]]}
  [conn query bindings]
  (let [prepared (.prepareTupleQuery ^RepositoryConnection conn
                                     (str "PREFIX r: <urn:tover:inventory-read-v1:> " query))]
    (.setMaxExecutionTime prepared 15)
    (doseq [[key value] bindings]
      (.setBinding prepared (name key) (if (instance? Value value) value (rdf/literal value))))
    (with-open [result (.evaluate prepared)]
      (mapv (fn [row]
              (into {} (for [key (.getBindingNames ^BindingSet row)]
                         [(keyword key) (.getValue ^BindingSet row key)])))
            (iterator-seq result)))))


(defn- item-filter
  {:malli/schema [:=> [:cat [:maybe [:set :string]] :string] :map]}
  [item-ids variable]
  (if (nil? item-ids)
    {:clause "" :bindings {}}
    {:clause   (if (empty? item-ids)
                 "FILTER (false) "
                 (str "{ " (str/join " UNION "
                                     (map-indexed (fn [index _]
                                                    (str "{ BIND (?item" index " AS " variable ") }"))
                                                  (sort item-ids))) " } "))
     :bindings (into {} (map-indexed (fn [index id] [(keyword (str "item" index)) id]) (sort item-ids)))}))


(defn- read-payloads
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :string :string [:maybe [:set :string]]] [:vector :map]]}
  [conn scope type path item-ids]
  (let [{:keys [clause bindings]} (item-filter item-ids "?id")]
    (mapv (fn [{:keys [payload]}] (edn/read-string {:readers {}} (rdf/value payload)))
          (select conn (str "SELECT DISTINCT ?node ?payload WHERE { GRAPH ?graph { " clause "?node a r:" type "; "
                            path " ?id; r:payload ?payload. }}")
                  (assoc bindings :graph (graph scope))))))


(defn- stage-effects!
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope [:sequential :map]] :nil]}
  [conn scope effects]
  (reduce (fn [positions effect]
            (let [key   [(:event-id effect) (:phase effect 0)]
                  index (get positions key 0)
                  id    (pr-str [(:operation-id effect) (:operation-revision effect) (:event-id effect)
                                 (:phase effect 0) index])
                  node  (subject scope "effect" id)]
              (rdf/add! conn (graph scope) node RDF/TYPE (term "Effect"))
              (doseq [[field value] {"itemId"            (:item-id effect)            "operationId"   (:operation-id effect)
                                     "operationRevision" (:operation-revision effect) "businessOrder" (:business-order effect)
                                     "eventIndex"        (:event-index effect 0)      "eventId"       (:event-id effect)
                                     "phase"             (:phase effect 0)            "lineIndex"     index                    "lineId" (:line-id effect)
                                     "payload"           (pr-str effect)}]
                (rdf/add! conn (graph scope) node (term field) value))
              (rdf/add! conn (graph scope) node (term "occurredAt")
                        (.createLiteral (SimpleValueFactory/getInstance) ^String (:occurred-at effect) XSD/DATETIME))
              (assoc positions key (inc index)))) {} effects)
  nil)


(defn- stage-productions!
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :map] :nil]}
  [conn scope projection]
  (let [revisions     (into {} (map (juxt :operation-id :operation-revision)) (:records projection))
        relationships (group-by :operation-id (:lineage-relationships projection))]
    (doseq [event (:lineage projection)]
      (let [node (subject scope "production" (:operation-id event))]
        (rdf/add! conn (graph scope) node RDF/TYPE (term "Production"))
        (doseq [[field value] {"operationId"   (:operation-id event) "operationRevision" (get revisions (:operation-id event))
                               "businessOrder" (:business-order event) "payload"         (pr-str event)
                               "relationships" (pr-str (get relationships (:operation-id event) []))}]
          (rdf/add! conn (graph scope) node (term field) value))
        (rdf/add! conn (graph scope) node (term "occurredAt")
                  (.createLiteral (SimpleValueFactory/getInstance) ^String (:occurred-at event) XSD/DATETIME))
        (doseq [[field lines] [["inputItemId" (:inputs event)] ["outputItemId" (:outputs event)]]
                item-id       (distinct (map :item-id lines))]
          (rdf/add! conn (graph scope) node (term field) item-id)))))
  nil)


(defn- stage-accepted-rows!
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :map] :nil]}
  [conn scope projection]
  (doseq [[kind rows] [["AcceptedItem" (vals (:items projection))] ["AcceptedStock" (:rows projection)]]
          row         rows]
    (let [node (subject scope kind (:id row))]
      (rdf/add! conn (graph scope) node RDF/TYPE (term kind))
      (rdf/add! conn (graph scope) node (term "itemId") (if (= kind "AcceptedItem") (:id row) (:item-id row)))
      (rdf/add! conn (graph scope) node (term "payload") (pr-str row))))
  nil)


(defn stage-history!
  "Persist accepted effects and dated production relationships only after ledger changes."
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :map] :nil]}
  [conn scope current]
  (when-not (= (:ledger-revision current)
               (rdf/value-at conn (graph scope) (state-subject scope) (term "historyRevision")))
    (doseq [type ["Effect" "Production" "AcceptedItem" "AcceptedStock"]]
      (remove-type! conn (graph scope) (term type)))
    (stage-effects! conn scope (or (get-in current [:projection :records]) []))
    (stage-productions! conn scope (:projection current))
    (stage-accepted-rows! conn scope (:projection current))
    (rdf/remove! conn (graph scope) (state-subject scope) (term "historyRevision") nil)
    (rdf/add! conn (graph scope) (state-subject scope) (term "historyRevision") (:ledger-revision current)))
  nil)


(defn read-projection
  "Read accepted projection payloads, optionally restricted to the affected stable item IDs."
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope [:? [:maybe [:set :string]]]] :map]}
  ([conn scope] (read-projection conn scope nil))
  ([conn scope item-ids]
   (let [items (read-payloads conn scope "AcceptedItem" "r:itemId" item-ids)
         rows (read-payloads conn scope "AcceptedStock" "r:itemId" item-ids)
         {:keys [clause bindings]} (item-filter item-ids "?id")
         effects                   (select conn (str "SELECT ?payload WHERE { GRAPH ?graph { " clause "?node a r:Effect; r:itemId ?id; "
                                                     "r:payload ?payload; r:occurredAt ?at; r:businessOrder ?order; r:eventIndex ?eventIndex; "
                                                     "r:eventId ?eventId; r:phase ?phase; r:lineIndex ?lineIndex. "
                                                     "}} ORDER BY ?at ?order ?eventIndex ?eventId ?phase ?lineIndex")
                                           (assoc bindings :graph (graph scope)))
         productions               (select conn (str "SELECT DISTINCT ?node ?payload ?relationships ?at ?order ?operationId WHERE { GRAPH ?graph { " clause
                                                     "?node a r:Production; (r:inputItemId|r:outputItemId) ?id; r:payload ?payload; "
                                                     "r:relationships ?relationships; r:occurredAt ?at; r:businessOrder ?order; "
                                                     "r:operationId ?operationId. }} ORDER BY ?at ?order ?operationId")
                                           (assoc bindings :graph (graph scope)))
         decode                    (fn [value] (edn/read-string {:readers {}} (rdf/value value)))]
     {:items                 (into {} (map (juxt :id identity)) items)
      :rows                  (vec (sort-by (juxt #(str/lower-case (:name %)) :location :item-id) rows))
      :balances              (into {} (map (fn [row] [[(:item-id row) (:location row)] (:quantity row)])) rows)
      :records               (mapv #(decode (:payload %)) effects)
      :lineage               (mapv #(decode (:payload %)) productions)
      :lineage-relationships (into [] (mapcat #(decode (:relationships %))) productions)
      :semantic-version      semantics/version})))


(defn stage-state!
  "Replace disposable Stock rows and lightweight draft state in the caller's transaction."
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :map] :nil]}
  [conn scope current]
  (let [graph           (graph scope)
        state           (state-subject scope)
        generation      (inc (or (rdf/value-at conn graph state (term "generation")) 0))
        epoch           (or (rdf/value-at conn graph state (term "epoch")) (str (random-uuid)))
        drafts          (pending-drafts current)
        draft-items     (draft-items drafts)
        items           (merge draft-items (get-in current [:projection :items]))
        stock-signature (pr-str [(:ledger-revision current) (filterv #(#{:opening :correction} (get-in % [:entry :kind])) drafts)])
        item-signature  (pr-str [(:ledger-revision current) draft-items])]
    (when-not (= stock-signature (rdf/value-at conn graph state (term "stockSignature")))
      (remove-type! conn graph (term "Stock"))
      (doseq [row (stock-rows (merge (:projection current) {:drafts drafts :ledger-revision (:ledger-revision current)}))]
        (stage-group! conn scope :stock row items)))
    (when-not (= item-signature (rdf/value-at conn graph state (term "itemSignature")))
      (remove-type! conn graph (term "Item"))
      (doseq [[id item] items]
        (let [subject (subject scope "item" id)]
          (rdf/add! conn graph subject RDF/TYPE (term "Item"))
          (rdf/add! conn graph subject (term "id") id)
          (rdf/add! conn graph subject (term "payload") (pr-str item))
          (when-let [accepted (get-in current [:projection :items id])]
            (rdf/add! conn graph subject (term "acceptedFilterName") (str/lower-case (text (:name accepted)))))
          (doseq [field ["name" "article"]]
            (rdf/add! conn graph subject (term field) (text (get item (keyword field))))
            (rdf/add! conn graph subject (term (str "f-" field)) (str/lower-case (text (get item (keyword field)))))))))
    (stage-history! conn scope current)
    (rdf/remove! conn graph state nil nil)
    (rdf/add! conn graph state (term "generation") generation)
    (rdf/add! conn graph state (term "epoch") epoch)
    (rdf/add! conn graph state (term "ledgerRevision") (:ledger-revision current))
    (rdf/add! conn graph state (term "historyRevision") (:ledger-revision current))
    (rdf/add! conn graph state (term "draftPayload") (pr-str (select-keys current [:drafts :ledger-revision])))
    (rdf/add! conn graph state (term "stockSignature") stock-signature)
    (rdf/add! conn graph state (term "itemSignature") item-signature)
    (rdf/add! conn graph state (term "version") 7))
  nil)


(defn- remove-matching!
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :string :string [:set :string]] :nil]}
  [conn scope type path ids]
  (when (seq ids)
    (let [{:keys [clause bindings]} (item-filter ids "?id")]
      (doseq [{:keys [node]} (select conn (str "SELECT DISTINCT ?node WHERE { GRAPH ?graph { " clause "?node a r:" type
                                               "; " path " ?id. }}")
                                     (assoc bindings :graph (graph scope)))]
        (remove-group! conn (graph scope) node))))
  nil)


(defn- stage-item!
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :string :map [:maybe :map]] :nil]}
  [conn scope id item accepted]
  (let [node (subject scope "item" id)]
    (rdf/add! conn (graph scope) node RDF/TYPE (term "Item"))
    (rdf/add! conn (graph scope) node (term "id") id)
    (rdf/add! conn (graph scope) node (term "payload") (pr-str item))
    (when accepted
      (rdf/add! conn (graph scope) node (term "acceptedFilterName") (str/lower-case (text (:name accepted)))))
    (doseq [field ["name" "article"]]
      (rdf/add! conn (graph scope) node (term field) (text (get item (keyword field))))
      (rdf/add! conn (graph scope) node (term (str "f-" field)) (str/lower-case (text (get item (keyword field)))))))
  nil)


(defn- refresh-overlay!
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :map [:set :string]] :nil]}
  [conn scope current accepted-ids]
  (let [before         (some-> (rdf/value-at conn (graph scope) (state-subject scope) (term "draftPayload"))
                               edn/read-string :drafts)
        changed        (for [id    (into (set (keys before)) (keys (:drafts current)))
                             :when (not= (get before id) (get-in current [:drafts id]))
                             draft [(get before id) (get-in current [:drafts id])]
                             :when draft]
                         draft)
        changed-items  (set (keys (draft-items (vec changed))))
        ids            (into accepted-ids changed-items)
        accepted-items (into {} (map (juxt :id identity)) (read-payloads conn scope "AcceptedItem" "r:itemId" ids))
        accepted-rows  (read-payloads conn scope "AcceptedStock" "r:itemId" ids)
        drafts         (pending-drafts current)
        items          (merge (select-keys (draft-items drafts) ids) accepted-items)
        stock-rows     (filterv (comp ids :item-id)
                                (stock-rows {:rows accepted-rows :drafts drafts :ledger-revision (:ledger-revision current)}))]
    (remove-matching! conn scope "Stock" "r:itemId" ids)
    (doseq [id ids] (rdf/remove! conn (graph scope) (subject scope "item" id) nil nil))
    (doseq [[id item] items] (stage-item! conn scope id item (get accepted-items id)))
    (doseq [row stock-rows]
      (stage-group! conn scope :stock row items)))
  nil)


(defn- stage-light-state!
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :map] :nil]}
  [conn scope current]
  (let [node            (state-subject scope)
        generation      (inc (or (rdf/value-at conn (graph scope) node (term "generation")) 0))
        epoch           (or (rdf/value-at conn (graph scope) node (term "epoch")) (str (random-uuid)))
        drafts          (pending-drafts current)
        stock-signature (pr-str [(:ledger-revision current) (filterv #(#{:opening :correction} (get-in % [:entry :kind])) drafts)])
        item-signature  (pr-str [(:ledger-revision current) (draft-items drafts)])]
    (rdf/remove! conn (graph scope) node nil nil)
    (doseq [[field value] {"generation"      generation "epoch"                   epoch          "ledgerRevision" (:ledger-revision current)
                           "historyRevision" (:ledger-revision current) "version" 7
                           "draftPayload"    (pr-str (select-keys current [:drafts :ledger-revision]))
                           "stockSignature"  stock-signature "itemSignature"      item-signature}]
      (rdf/add! conn (graph scope) node (term field) value)))
  nil)


(defn stage-accepted!
  "Replace affected accepted payloads and their pending overlays in the caller's acceptance transaction."
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :map :map [:set :string] [:set :string]] :nil]}
  [conn scope current projection item-ids entity-ids]
  (doseq [type ["AcceptedItem" "AcceptedStock" "Effect" "Production"]]
    (remove-matching! conn scope type (if (= type "Production") "(r:inputItemId|r:outputItemId)" "r:itemId") item-ids)
    (when (#{"Effect" "Production"} type)
      (remove-matching! conn scope type "r:operationId" entity-ids)))
  (stage-accepted-rows! conn scope projection)
  (stage-effects! conn scope (:records projection))
  (stage-productions! conn scope projection)
  (refresh-overlay! conn scope current item-ids)
  (stage-light-state! conn scope current)
  nil)


(defn stage-records!
  "Upsert each newly accepted entity's latest Operations group without loading earlier revisions."
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope [:sequential :map] :map] :nil]}
  [conn scope records items]
  (doseq [{:keys [entity-id revision sequence business-order actor recorded-at entry cancelled?] :as record} records]
    (let [entry (cond-> (assoc entry :id entity-id :revision revision :business-order business-order
                               :accepted-order sequence :actor actor :recorded-at recorded-at)
                  cancelled? (assoc :cancelled? true))
          row   (binding [i18n/*locale* "en"]
                  (dissoc (first (operations-ui/rows [entry] [record])) :detail))]
      (stage-group! conn scope :operations row items)))
  nil)


(defn ready?
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope] :boolean]}
  [conn scope]
  (= 7 (rdf/value-at conn (graph scope) (state-subject scope) (term "version"))))


(defn ensure!
  "Bootstrap a missing disposable projection from cached accepted rows and individual latest RDF records."
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :map] :nil]}
  [conn scope current]
  (when-not (ready? conn scope)
    (let [history (rdf/iri (str "urn:tover:workspace:" (:workspace-id scope) ":inventory-v1:history"))
          query   (.prepareTupleQuery
                    ^RepositoryConnection conn
                    "PREFIX t: <urn:tover:inventory-v1:>
                    SELECT ?record WHERE { GRAPH ?history {
                      ?record a t:Record ; t:entityId ?id ; t:revision ?revision .
                      FILTER NOT EXISTS { ?later a t:Record ; t:entityId ?id ; t:revision ?newer .
                                        FILTER (?newer > ?revision) }
                    }}")]
      (rdf/remove! conn (graph scope) nil nil nil)
      (.setBinding query "history" history)
      (with-open [records (.evaluate query)]
        (doseq [binding (iterator-seq records)]
          (stage-records! conn scope [(model/read-record conn history (.getValue binding "record"))]
                          (get-in current [:projection :items]))))
      (stage-state! conn scope current)))
  nil)


(defn rebuild!
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :map] :nil]}
  [conn scope current]
  (let [generation (rdf/value-at conn (graph scope) (state-subject scope) (term "generation"))]
    (rdf/remove! conn (graph scope) nil nil nil)
    (ensure! conn scope current)
    (when generation
      (rdf/remove! conn (graph scope) (state-subject scope) (term "generation") nil)
      (rdf/add! conn (graph scope) (state-subject scope) (term "generation") generation)))
  nil)


(defn read-state
  "Read only durable drafts and revision markers; accepted rows stay in RDF until requested."
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope] :map]}
  [conn scope]
  (assoc (edn/read-string {:readers {}} (rdf/value-at conn (graph scope) (state-subject scope) (term "draftPayload")))
         :generation (rdf/value-at conn (graph scope) (state-subject scope) (term "generation"))
         :epoch (rdf/value-at conn (graph scope) (state-subject scope) (term "epoch"))))
