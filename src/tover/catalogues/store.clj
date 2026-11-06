(ns tover.catalogues.store
  "Mutable workspace reference data; this graph never owns inventory quantities."
  (:require
    [bel.validation :as validation]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.walk :as walk]
    [tover.catalogues.schema :as catalogue]
    [tover.inventory.schema :as schema]
    [tover.shared.rdf :as rdf]
    [tover.shared.schema :as shared])
  (:import
    [java.net URLEncoder]
    [java.nio.charset StandardCharsets]
    [java.time Instant]
    [java.util UUID]
    [org.eclipse.rdf4j.model Value]
    [org.eclipse.rdf4j.query Binding]
    [org.eclipse.rdf4j.repository RepositoryConnection]))


(defn graph
  {:malli/schema [:=> [:cat shared/Scope] rdf/IRI]}
  [scope]
  (validation/check! shared/Scope scope)
  (rdf/iri (str "urn:tover:workspace:" (:workspace-id scope) ":catalogue-v1")))


(defn term
  {:malli/schema [:=> [:cat :string] rdf/IRI]}
  [local]
  (rdf/iri (str "urn:tover:catalogue-v1:" local)))


(defn subject
  {:malli/schema [:=> [:cat shared/Scope :keyword :string] rdf/IRI]}
  [scope kind id]
  (rdf/iri (str (graph scope) ":" (name kind) ":" (URLEncoder/encode ^String id StandardCharsets/UTF_8))))


(defn query
  {:malli/schema [:=> [:cat rdf/Connection :string :map] [:vector :map]]}
  [conn text bindings]
  (let [prepared (.prepareTupleQuery ^RepositoryConnection conn (str "PREFIX c: <urn:tover:catalogue-v1:> " text))]
    (.setMaxExecutionTime prepared 15)
    (doseq [[key value] bindings]
      (.setBinding prepared (name key) (if (instance? Value value) value (rdf/literal value))))
    (with-open [result (.evaluate prepared)]
      (mapv (fn [row]
              (into {} (map (fn [binding]
                              [(keyword (.getName ^Binding binding)) (rdf/value (.getValue ^Binding binding))]) row)))
            (iterator-seq result)))))


(defn record
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :keyword :string] [:maybe :map]]}
  [conn scope kind id]
  (some->> (rdf/value-at conn (graph scope) (subject scope kind id) (term "payload"))
           (edn/read-string {:readers {}})))


(defn revision
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope] :int]}
  [conn scope]
  (or (rdf/value-at conn (graph scope) (graph scope) (term "revision")) 0))


(defn- put!
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :keyword :map] :map]}
  [conn scope kind row]
  (let [node (subject scope kind (:id row))]
    (rdf/remove! conn (graph scope) node nil nil)
    (doseq [[key value] {"kind" (name kind) "id" (:id row) "payload" (pr-str row)}]
      (rdf/add! conn (graph scope) node (term key) value))
    (doseq [[field _] (get catalogue/columns kind)]
      (rdf/add! conn (graph scope) node (term (name field)) (str (get row field "")))
      (when-let [related (and (not (str/blank? (get row field))) (catalogue/references field))]
        (rdf/add! conn (graph scope) node (term (str "ref-" (name field))) (subject scope related (get row field)))))
    (doseq [alias (:aliases row)] (rdf/add! conn (graph scope) node (term "alias") alias))
    (when-let [key (:location-key row)] (rdf/add! conn (graph scope) node (term "location-key") key))
    (let [next (inc (revision conn scope))]
      (rdf/remove! conn (graph scope) (graph scope) (term "revision") nil)
      (rdf/add! conn (graph scope) (graph scope) (term "revision") next))
    row))


(defn find-alias
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :keyword :string] [:maybe :map]]}
  [conn scope kind value]
  (some->> (first (query conn "SELECT ?payload WHERE { GRAPH ?graph { ?row c:kind ?kind; c:alias ?alias; c:payload ?payload. }} LIMIT 1"
                         {:graph (graph scope) :kind (name kind) :alias value}))
           :payload (edn/read-string {:readers {}})))


(defn- stable-id
  {:malli/schema [:=> [:cat :keyword :string] :string]}
  [kind value]
  (str (UUID/nameUUIDFromBytes (.getBytes (str (name kind) "/" value) StandardCharsets/UTF_8))))


(defn exact-records
  "Two matches distinguish an existing name from an ambiguous one."
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :keyword :string] [:vector :map]]}
  [conn scope kind value]
  (mapv #(edn/read-string {:readers {}} (:payload %))
        (query conn "SELECT ?payload WHERE { GRAPH ?graph { ?row c:kind ?kind; c:name ?name; c:payload ?payload. }} ORDER BY ?row LIMIT 2"
               {:graph (graph scope) :kind (name kind) :name value})))


(defn register!
  "Add an accepted reference once; later document snapshots cannot replace its metadata."
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :keyword :map :string] [:maybe :map]]}
  [conn scope kind row actor]
  (when-not (str/blank? (:name row))
    (let [named? (#{:warehouses :suppliers} kind)
          id     (or (:id row) (stable-id kind (:name row)))]
      (or (record conn scope kind id)
          (when named? (find-alias conn scope kind (:name row)))
          (put! conn scope kind
                (cond-> (merge (when (= :goods kind) {:article "" :barcode "" :unit "pcs"}) row
                               {:id id :revision 1 :actor actor :updated-at (str (Instant/now))})
                  named? (assoc :aliases [(:name row)])
                  (= :warehouses kind) (assoc :location-key (:name row))))))))


(defn register-projection!
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :map :string] :nil]}
  [conn scope projection actor]
  (doseq [item (vals (:items projection))] (register! conn scope :goods item actor))
  (doseq [location (distinct (keep :location (:rows projection)))]
    (register! conn scope :warehouses {:name location} actor))
  nil)


(defn register-operation!
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :map :string] :nil]}
  [conn scope entry actor]
  (when (= :purchase (:type entry)) (register! conn scope :suppliers {:name (:party entry)} actor))
  nil)


(defn display
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :keyword :map] :map]}
  [conn scope kind row]
  (reduce (fn [row [field _]]
            (if-let [related (catalogue/references field)]
              (assoc-in row [:labels field] (or (:name (when (not (str/blank? (get row field)))
                                                         (record conn scope related (get row field)))) "")) row))
          row (get catalogue/columns kind)))


(defn item
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :map] :map]}
  [conn scope data]
  (if-let [goods (record conn scope :goods (or (:item-id data) (:id data)))]
    (let [store     (when-not (str/blank? (:store-id goods)) (record conn scope :stores (:store-id goods)))
          warehouse (when-not (str/blank? (:warehouse-id store)) (record conn scope :warehouses (:warehouse-id store)))]
      (cond-> (merge data (select-keys goods catalogue/goods-fields))
        warehouse (assoc :default-location (:location-key warehouse))))
    data))


(defn goods
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope [:maybe [:set :string]]] [:map-of :string :map]]}
  [conn scope ids]
  (into {} (map (fn [row] [(:id row) (select-keys (item conn scope row) (conj catalogue/goods-fields :id :default-location))]))
        (if ids
          (keep #(record conn scope :goods %) ids)
          (map #(edn/read-string {:readers {}} (:payload %))
               (query conn "SELECT ?payload WHERE { GRAPH ?graph { ?row c:kind \"goods\"; c:payload ?payload. }}"
                      {:graph (graph scope)})))))


(defn stock-row
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :map] :map]}
  [conn scope row]
  (let [label (when (:location row) (:name (find-alias conn scope :warehouses (:location row))))]
    (cond-> (dissoc (item conn scope row) :location-label)
      (and label (not= label (:location row))) (assoc :location-label label))))


(defn canonical-entry
  "Resolve warehouse labels before saving drafts, retaining the ledger's original balance keys."
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :map] :map]}
  [conn scope entry]
  (walk/postwalk (fn [node]
                   (if (map? node)
                     (reduce (fn [value key]
                               (if-let [location (and (string? (get value key))
                                                      (find-alias conn scope :warehouses (get value key)))]
                                 (assoc value key (:location-key location)) value))
                             node [:location :from-location :to-location :stock-location]) node)) entry))


(defn- reference-id!
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :keyword :string :string] :string]}
  [conn scope kind value actor]
  (schema/text! value :name)
  (let [matches (if-let [alias (when (= :warehouses kind) (find-alias conn scope kind value))]
                  [alias] (exact-records conn scope kind value))]
    (when (> (count matches) 1)
      (schema/fail! :validation "Several records have this name. Choose a catalogue record." {}))
    (:id (or (first matches) (register! conn scope kind {:id (str (UUID/randomUUID)) :name value} actor)))))


(defn check-new-goods!
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope [:sequential :map]] :nil]}
  [conn scope entries]
  (doseq [line (filter #(and (map? %) (:catalogue-new? %)) (tree-seq coll? seq entries))]
    (when (and (not (record conn scope :goods (:item-id line)))
               (seq (exact-records conn scope :goods (:name line))))
      (schema/fail! :validation "This goods name now exists. Choose a catalogue item before submitting." {})))
  nil)


(defn save!
  "Apply one validated field patch in the caller's transaction, retaining a durable retry receipt."
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope catalogue/Save :string] :map]}
  [conn scope {:keys [kind id revision path value commandId] :as input} actor]
  (validation/check! catalogue/Save input)
  (let [kind        (keyword kind) field (keyword path)
        receipt     (subject scope :receipts commandId)
        fingerprint (assoc (select-keys input [:kind :id :revision :path :value :referenceName]) :actor actor)]
    (if-let [saved (some->> (rdf/value-at conn (graph scope) receipt (term "payload")) (edn/read-string {:readers {}}))]
      (if (= fingerprint (:request saved)) (:row saved)
          (schema/fail! :idempotency-conflict "This save identifier already belongs to another edit." {}))
      (let [current (record conn scope kind id)]
        (when-not (some #(= field (first %)) (get catalogue/columns kind))
          (schema/fail! :validation "Choose an available catalogue field." {}))
        (when-not (= revision (or (:revision current) 0))
          (schema/fail! :stale-catalogue "This record changed. Choose which value to keep." {:current current}))
        (when (and (nil? current) (not= field :name))
          (schema/fail! :validation "Enter a name before other catalogue fields." {}))
        (when (and (:referenceName input) (or (nil? (catalogue/references field)) (seq value)))
          (schema/fail! :validation "Choose a record or enter a new relationship name." {}))
        (when (= :name field) (schema/text! value field))
        (when (and (= field :purpose) (not (#{"" "storage" "sales" "production"} value)))
          (schema/fail! :validation "Choose an available warehouse purpose." {}))
        (when-let [related (and (not (str/blank? value)) (catalogue/references field))]
          (when-not (record conn scope related value)
            (schema/fail! :validation "Choose an available record in this workspace." {})))
        (when (and (= field :name) (#{:warehouses :suppliers} kind))
          (when-let [other (find-alias conn scope kind value)]
            (when-not (= id (:id other))
              (schema/fail! :validation "This name belongs to another record, including its previous names." {}))))
        (let [value (if-let [reference-name (:referenceName input)]
                      (reference-id! conn scope (catalogue/references field) reference-name actor) value)
              row   (cond-> (merge (when (= :goods kind) {:article "" :barcode "" :unit "pcs"}) current
                                   {:id id :revision (inc revision) :actor actor :updated-at (str (Instant/now)) field value})
                      (and (= field :name) (#{:warehouses :suppliers} kind))
                      (update :aliases #(vec (distinct (conj (or % []) value))))
                      (and (nil? current) (= kind :warehouses)) (assoc :location-key value))]
          (put! conn scope kind row)
          (rdf/add! conn (graph scope) receipt (term "payload") (pr-str {:request fingerprint :row row}))
          row)))))


(defn correction-entry
  "Capture only intentional metadata edits and their base revision alongside a shared correction."
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope :map [:maybe :map]] :map]}
  [conn scope entry previous]
  (cond
    (= :revision (:kind entry))
    (update entry :replacement #(correction-entry conn scope % (:replacement previous)))

    (= :correction (:kind entry))
    (assoc entry :catalogue-edits
           (mapv (fn [line]
                   (let [id        (:item-id line)
                         current   (record conn scope :goods id)
                         prior     (some #(when (= id (:item-id %)) %) (:lines previous))
                         edited    (some #(when (= id (:id %)) %) (:catalogue-edits previous))
                         changed   (filterv (fn [field]
                                              (and (contains? line field)
                                                   (not= (get line field) (get (or prior current) field))))
                                            catalogue/goods-fields)
                         fields    (vec (distinct (concat (:fields edited) changed)))
                         revisions (merge (zipmap (:fields edited) (repeat (:revision edited)))
                                          (:field-revisions edited)
                                          (zipmap changed (repeat (or (:revision current) 0))))]
                     {:id id :revision (or (:revision current) 0) :fields fields :field-revisions revisions}))
                 (vals (into {} (map (juxt :item-id identity)) (:lines entry)))))

    :else entry))


(defn check-corrections!
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope [:sequential :map]] :nil]}
  [conn scope entries]
  (doseq [entry entries              edit (:catalogue-edits (if (= :revision (:kind entry)) (:replacement entry) entry))
          :when (seq (:fields edit))]
    (when-not (every? #(= (get (:field-revisions edit) % (:revision edit))
                          (or (:revision (record conn scope :goods (:id edit))) 0)) (:fields edit))
      (schema/fail! :stale-catalogue "Goods changed after this correction was drafted. Review its metadata before submitting." {:item-id (:id edit)})))
  nil)


(defn apply-corrections!
  {:malli/schema [:=> [:cat rdf/Connection shared/Scope [:sequential :map] :string] :nil]}
  [conn scope entries actor]
  (doseq [entry entries
          :let  [entry (if (= :revision (:kind entry)) (:replacement entry) entry)]
          edit  (:catalogue-edits entry) :when (seq (:fields edit))]
    (let [current (record conn scope :goods (:id edit))
          line    (last (filter #(= (:id edit) (:item-id %)) (:lines entry)))]
      (when current
        (put! conn scope :goods (merge current (select-keys line (:fields edit))
                                       {:revision (inc (:revision current)) :actor actor :updated-at (str (Instant/now))})))))
  nil)
