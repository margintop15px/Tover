(ns tover.inventory.layouts
  (:require
    [clojure.edn :as edn]
    [tover.inventory.grid :as grid]
    [tover.inventory.schema :as schema]
    [tover.inventory.tables :as tables]
    [tover.shared.rdf :as rdf]))


(def ^:private utility-columns
  #{"select" "actions"})


(def ^:private known-columns
  (let [stock      (set (map :key tables/stock-columns))
        operations (set (remove utility-columns (map :key grid/columns)))]
    {"stock"           stock
     "operations"      operations
     "shared-draft"    operations
     "item-history"    (set (map :key tables/history-columns))
     "resulting-stock" stock}))


(def ^:private payload-predicate
  (rdf/iri "urn:tover:ui-layout-v1:payload"))


(defn- user-graph
  {:malli/schema [:=> [:cat :map] rdf/IRI]}
  [{:keys [user-id]}]
  (when-not (uuid? user-id)
    (schema/fail! :forbidden "Sign in to save table settings." {:status 403}))
  (rdf/iri (str "urn:tover:user:" user-id ":ui-layout-v1")))


(defn- read-layouts
  {:malli/schema [:=> [:cat rdf/Connection rdf/IRI] :map]}
  [conn graph]
  (if-let [value (rdf/value-at conn graph graph payload-predicate)]
    (edn/read-string {:readers {} :default (fn [tag _]
                                             (schema/fail! :invalid-storage "Unsupported table settings data." {:tag tag}))}
                     value)
    {}))


(defn load-all
  "Read this account's table settings across workspaces, independently of inventory."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [repo user-id] :as context}]
  (if user-id
    (let [graph (user-graph context)]
      (rdf/with-transaction [conn {:repo repo :level :snapshot}]
        (read-layouts conn graph)))
    {}))


(defn- invalid!
  {:malli/schema [:=> [:cat :string] :any]}
  [message]
  (schema/fail! :invalid-input message {:status 400}))


(defn- column-key
  {:malli/schema [:=> [:cat :any] [:maybe :string]]}
  [value]
  (cond
    (string? value) value
    (and (keyword? value) (nil? (namespace value))) (name value)))


(defn- check-patch!
  {:malli/schema [:=> [:cat :any] :map]}
  [{:keys [gridId widths order] :as input}]
  (let [columns    (get known-columns gridId)
        width-keys (when (map? widths) (mapv column-key (keys widths)))]
    (when-not (and (map? input) columns
                   (or (contains? input :widths) (contains? input :order)))
      (invalid! "Choose a supported table and its column settings."))
    (when (contains? input :widths)
      (when-not (and (map? widths) (<= (count widths) (count columns))
                     (= (count widths) (count (set width-keys)))
                     (every? columns width-keys)
                     (every? #(and (integer? %) (<= 50 % 2400)) (vals widths)))
        (invalid! "Column widths must be whole pixels from 50 to 2400 for supported columns.")))
    (when (contains? input :order)
      (when-not (and (vector? order) (<= (count order) (count columns))
                     (= (count order) (count (set order))) (every? columns order))
        (invalid! "Column order must contain supported columns without duplicates.")))
    (cond-> {:gridId gridId}
      (contains? input :widths) (assoc :widths (zipmap width-keys (vals widths)))
      (contains? input :order) (assoc :order order))))


(defn- merge-order
  {:malli/schema [:=> [:cat [:vector :string] [:vector :string]] [:vector :string]]}
  [previous proposed]
  (let [visible (set proposed)]
    (reduce (fn [result [index key]]
              (if (visible key)
                result
                (let [position (min index (count result))]
                  (into (conj (subvec result 0 position) key) (subvec result position)))))
            proposed (map-indexed vector previous))))


(defn save!
  "Commit a width/order patch for the authenticated account, preserving unrelated settings."
  {:malli/schema [:=> [:cat :map :any] :map]}
  [{:keys [repo] :as context} input]
  (let [graph (user-graph context)
        {:keys [gridId widths order] :as patch} (check-patch! input)]
    (locking repo
      (rdf/with-transaction [conn {:repo repo :level :serializable}]
        (let [all     (read-layouts conn graph)
              current (get all gridId {:widths {} :order [] :version 0})
              saved   (cond-> (update current :version (fnil inc 0))
                        (contains? patch :widths) (update :widths merge widths)
                        (seq widths) (assoc :lastColumn (last (keys widths)))
                        (contains? patch :order) (update :order merge-order order))]
          (rdf/remove! conn graph graph payload-predicate nil)
          (rdf/add! conn graph graph payload-predicate (pr-str (assoc all gridId saved)))
          saved)))))


(defn apply-layout
  "Project saved logical columns while retaining stable edit targets and merged-cell anchors."
  {:malli/schema [:=> [:cat :map [:maybe :map]] :map]}
  [{:keys [columns rows merges] :as model} {:keys [widths order version lastColumn]}]
  (let [widths      (or widths {})
        present     (set (map :key columns))
        movable     (remove utility-columns (map :key columns))
        ordered     (vec (concat (when (present "select") ["select"])
                                 (filter present (remove utility-columns order))
                                 (remove (set order) movable)
                                 (when (present "actions") ["actions"])))
        by-key      (zipmap (map :key columns) (range))
        indexes     (mapv by-key ordered)
        coordinates (zipmap indexes (range))
        projected   (assoc model
                           :columns (mapv (fn [index]
                                            (let [column (nth columns index)]
                                              (cond-> column
                                                (contains? widths (:key column))
                                                (assoc :width (get widths (:key column)))))) indexes)
                           :rows (mapv #(update % :cells (fn [cells] (mapv (partial nth cells) indexes))) rows)
                           :savedWidths widths :columnOrder (or order []) :layoutVersion (or version 0)
                           :lastColumn lastColumn)]
    (reduce (fn [result {:keys [row col colspan] :or {colspan 1} :as merge}]
              (let [mapped (sort (map coordinates (range col (+ col colspan))))
                    start  (first mapped)]
                (when-not (= mapped (range start (+ start colspan)))
                  (invalid! "These columns must stay together for their merged cells."))
                (cond-> (update result :merges conj (assoc merge :col start))
                  (not= start (get coordinates col))
                  (update-in [:rows row :cells]
                             (fn [cells]
                               (-> cells
                                   (assoc start (get-in model [:rows row :cells col]))
                                   (assoc (get coordinates col) {})))))))
            (assoc projected :merges []) merges)))
