(ns tover.inventory.windows
  (:require
    [tover.inventory.schema :as schema]))


(def window-size
  200)


(def ^:private session-limit
  32)


(def ^:private idle-timeout-ms
  (* 5 60 1000))


(defonce ^:dynamic *cache*
  (atom {:queries {} :reads {}}))


(def ^{:dynamic true :malli/schema [:=> [:cat] :int]} *clock*
  #(System/currentTimeMillis))


(defn- scope
  {:malli/schema [:=> [:cat :map] [:vector :any]]}
  [{:keys [repo user-id workspace-id] :as context}]
  (when-not (and repo (uuid? user-id) (uuid? workspace-id))
    (schema/fail! :forbidden "Sign in to view workspace inventory." {:status 403}))
  [repo user-id workspace-id (or (:locale context) "en")])


(defn- prune
  {:malli/schema [:=> [:cat :map :int] :map]}
  [entries now]
  (into {} (remove (fn [[_ entry]] (>= (- now (:touched entry)) idle-timeout-ms))) entries))


(defn- remember
  {:malli/schema [:=> [:cat :map :any :map :int] :map]}
  [entries key entry now]
  (let [entries (assoc (prune entries now) key (assoc entry :touched now))
        excess  (- (count entries) session-limit)]
    (if (pos? excess)
      (apply dissoc entries (map first (take excess (sort-by (comp :touched val) (dissoc entries key)))))
      entries)))


(defn read-index!
  "Share an immutable read snapshot between queries in one scoped data generation."
  {:malli/schema [:=> [:cat :map :any [:=> [:cat] :map]] :map]}
  [context generation build]
  (let [owner (scope context)
        key   [owner generation]]
    (locking *cache*
      (let [now   (*clock*)
            reads (prune (:reads @*cache*) now)
            value (if-let [entry (get reads key)] (:value entry) (build))]
        (swap! *cache* assoc :reads (remember reads key {:scope owner :value value} now))
        value))))


(defn- groups
  {:malli/schema [:=> [:cat [:vector :map]] [:vector :map]]}
  [rows]
  (loop [index 0 result [] seen #{}]
    (if (= index (count rows))
      result
      (let [id  (or (:groupId (nth rows index)) (:id (nth rows index)))
            end (loop [cursor (inc index)]
                  (if (and (< cursor (count rows))
                           (= id (or (:groupId (nth rows cursor)) (:id (nth rows cursor)))))
                    (recur (inc cursor))
                    cursor))]
        (when (or (nil? id) (seen id))
          (schema/fail! :invalid-grid "Inventory groups must have unique contiguous identities." {}))
        (recur end (conj result {:id id :index (count result) :start index :end end}) (conj seen id))))))


(defn- read-model
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [rows merges editStates] :as model}]
  (let [rows       (vec rows)
        grouped    (groups rows)
        row-groups (vec (mapcat (fn [group] (repeat (- (:end group) (:start group)) group)) grouped))
        anchors    (merge (into {} (map (juxt :id :start)) grouped)
                          (into {} (map-indexed (fn [index row] [(:id row) index])) rows))]
    (when-not (= (count rows) (count (set (map :id rows))))
      (schema/fail! :invalid-grid "Inventory rows must have unique identities." {}))
    (doseq [{:keys [row rowspan] :or {rowspan 1}} merges]
      (when-not (and (integer? row) (integer? rowspan) (pos? rowspan)
                     (<= 0 row) (<= (+ row rowspan) (count rows))
                     (= (:index (nth row-groups row)) (:index (nth row-groups (+ row (dec rowspan))))))
        (schema/fail! :invalid-grid "Merged cells must stay inside one inventory group." {})))
    {:model        (-> model (dissoc :details :editStates) (assoc :rows rows :merges (vec merges)))
     :groups       grouped :row-groups row-groups :anchors anchors
     :edit-targets (group-by #(get-in % [:target :rowId])
                             (keep #(when (get-in % [:target :rowId]) (select-keys % [:target :value]))
                                   (concat (mapcat :cells rows) editStates)))}))


(defn- checked-range
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [start count anchorId detailRowId pendingRows] :or {start 0 count window-size pendingRows []}}]
  (when-not (and (integer? start) (<= 0 start) (integer? count) (pos? count)
                 (or (nil? anchorId) (string? anchorId))
                 (or (nil? detailRowId) (string? detailRowId))
                 (vector? pendingRows) (<= (clojure.core/count pendingRows) 100) (every? string? pendingRows))
    (schema/fail! :invalid-input "Choose a valid inventory window." {:status 400}))
  {:start start :count (min window-size count) :anchorId anchorId :detailRowId detailRowId :pendingRows (vec (distinct pendingRows))})


(defn- project
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [id query-key model groups row-groups anchors edit-targets]} input]
  (let [{:keys [start count anchorId detailRowId pendingRows]} (checked-range input)
        total (clojure.core/count (:rows model))
        found (when anchorId (get anchors anchorId))
        requested (if (some? found) found start)
        first-row (min requested (max 0 (dec total)))
        start (if (pos? total) (:start (nth row-groups first-row)) 0)
        last-row (min (dec total) (+ start count -1))
        end (if (pos? total) (:end (nth row-groups last-row)) 0)
        group-start (if (pos? total) (:index (nth row-groups start)) 0)]
    (assoc model
           :rows (mapv (fn [index row]
                         (assoc row :rowIndex index :groupIndex (:index (nth row-groups index))))
                       (range start end) (subvec (:rows model) start end))
           :merges (into [] (comp (filter #(<= start (:row %) (dec end)))
                                  (map #(update % :row - start))) (:merges model))
           :editStates (vec (mapcat edit-targets pendingRows))
           :missingRows (filterv #(not (contains? edit-targets %)) pendingRows)
           :viewport (cond-> {:queryId id    :queryKey query-key :totalRows  total       :totalGroups (clojure.core/count groups)
                              :start   start :end      end       :groupStart group-start :windowSize  window-size                 :view (:id model)}
                       anchorId (assoc :anchorFound (some? found))
                       detailRowId (assoc :detailFound (contains? anchors detailRowId))))))


(defn- database-window
  {:malli/schema [:=> [:cat :map :map [:maybe :map]] [:maybe :map]]}
  [{:keys [id query-key read]} input first-model]
  (when-let [model (or first-model (read (checked-range input)))]
    (update model :viewport merge {:queryId id :queryKey query-key :windowSize window-size :view (:id model)})))


(defn initial!
  "Open or reuse a scoped immutable query and return complete groups around its first anchor."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [context {:keys [queryKey generation build read contains first-model] :as input}]
  (let [owner (scope context)]
    (checked-range input)
    (when-not (or (ifn? build) (ifn? read))
      (schema/fail! :invalid-input "An inventory query builder is required." {:status 400}))
    (let [entry (locking *cache*
                  (let [now      (*clock*)
                        queries  (prune (:queries @*cache*) now)
                        existing (some (fn [[_ entry]]
                                         (when (and (= owner (:scope entry)) (= queryKey (:query-key entry)) (= generation (:generation entry))) entry)) queries)
                        entry    (or existing (merge (if read {:read read :contains contains} (read-model (build)))
                                                     {:id (str (random-uuid)) :scope owner :query-key queryKey :generation generation}))]
                    (swap! *cache* assoc :queries (remember queries (:id entry) entry now))
                    entry))]
      (if (:read entry) (database-window entry input first-model) (project entry input)))))


(defn fetch!
  "Fetch a cached window. Unknown, expired, invalidated or differently scoped tokens return nil."
  {:malli/schema [:=> [:cat :map :map] [:maybe :map]]}
  [context {:keys [queryId generation] :as input}]
  (let [owner (scope context)]
    (checked-range input)
    (let [entry (locking *cache*
                  (let [now         (*clock*)
                        queries     (prune (:queries @*cache*) now)
                        entry       (get queries queryId)
                        authorized? (and entry (= owner (:scope entry)))
                        current?    (or (not (contains? input :generation)) (= generation (:generation entry)))]
                    (swap! *cache* assoc :queries (cond
                                                    (and authorized? current?) (remember queries queryId entry now)
                                                    authorized? (dissoc queries queryId)
                                                    :else queries))
                    (when (and authorized? current?) entry)))]
      (when entry
        (if (:read entry) (database-window entry input nil) (project entry input))))))


(defn contains-group?
  "Authorize a group lookup against the query that exposed it, without including detail payloads."
  {:malli/schema [:=> [:cat :map :string :string] :boolean]}
  [context query-id group-id]
  (let [owner (scope context)
        entry (locking *cache*
                (let [now         (*clock*)
                      queries     (prune (:queries @*cache*) now)
                      entry       (get queries query-id)
                      authorized? (and entry (= owner (:scope entry)))]
                  (swap! *cache* assoc :queries (if authorized? (remember queries query-id entry now) queries))
                  (when authorized? entry)))]
    (boolean (and entry (if-let [contains (:contains entry)]
                          (contains group-id)
                          (some #(= group-id (:id %)) (:groups entry)))))))


(defn invalidate!
  "Expire this repository/workspace's cached data for every account after a committed mutation."
  {:malli/schema [:=> [:cat :map] :nil]}
  [context]
  (let [[repo _ workspace-id] (scope context)
        keep?                 (fn [[_ {:keys [scope]}]] (not (and (= repo (nth scope 0)) (= workspace-id (nth scope 2)))))]
    (locking *cache*
      (swap! *cache* #(-> % (update :queries (partial into {} (filter keep?)))
                          (update :reads (partial into {} (filter keep?))))))
    nil))
