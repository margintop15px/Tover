(ns bel-postgres.journal
  "Shared accepted-event and receipt storage. Delivery never reruns a command."
  (:require
    [bel-postgres.jdbc :as db]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]
    [taoensso.nippy :as nippy]))


(defn- scope-columns
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [kind id]} :scope}]
  (if (contains? #{:user :workspace} kind)
    {:scope_kind (name kind) :scope_id id}
    (throw (ex-info "Unsupported journal scope" {:type :configuration}))))


(defn- scope-clause
  {:malli/schema [:=> [:cat :map] [:vector :any]]}
  [context]
  (let [{:keys [scope_kind scope_id]} (scope-columns context)]
    [:and [:= :scope_kind scope_kind] [:= :scope_id scope_id]]))


(defn receipt
  {:malli/schema [:=> [:cat :map :string] [:maybe :map]]}
  [{:keys [tx] :as context} command-id]
  (some-> (db/execute-one! tx {:select [:payload] :from [:app_private.bel_receipts]
                               :where  [:and (scope-clause context) [:= :command_id command-id]]})
          :payload nippy/thaw))


(defn- event-row
  {:malli/schema [:=> [:cat :map] :map]}
  [event]
  {:id          (:id event)                  :actor_id    (:actor event)                   :command_id (:command-id event)
   :event_type  (subs (str (:type event)) 1) :occurred_at (:at event)
   :event_index (:index event)               :data        [:lift (db/jsonb (:data event))]})


(defn append-event!
  {:malli/schema [:=> [:cat :map :map] :nil]}
  [{:keys [tx] :as context} event]
  (db/execute-one! tx {:insert-into :app_private.bel_events
                       :values      [(merge (scope-columns context) (event-row event))]})
  nil)


(defn- receipt-row
  {:malli/schema [:=> [:cat :map :map :map] :map]}
  [context invocation result]
  (merge (scope-columns context)
         {:command_id (:command-id invocation) :actor_id (get-in invocation [:request :actor])
          :payload    (nippy/freeze {:request (:request invocation) :result result})}))


(defn save-receipt!
  {:malli/schema [:=> [:cat :map :map :map] :boolean]}
  [{:keys [tx] :as context} invocation result]
  (boolean
    (db/execute-one! tx {:insert-into :app_private.bel_receipts
                         :values      [(receipt-row context invocation result)]
                         :on-conflict [:scope_kind :scope_id :command_id]
                         :do-nothing  true :returning [:command_id]})))


(defn- same-events?
  {:malli/schema [:=> [:cat :map :string [:vector :map]] :boolean]}
  [{:keys [tx] :as context} command-id events]
  (let [matches (map-indexed
                  (fn [index event]
                    (into [:and] (map (fn [[column value]] [:= column value]))
                          (event-row (assoc event :index index)))) events)
        ;; Let PostgreSQL compare JSONB and timestamps using the same types as insertion.
        match   (if (seq matches) (into [:or] matches) false)]
    (= {:total (count events) :matching (count events)}
       (db/execute-one! tx {:select [[[:count :*] :total] [[:count [:case match 1]] :matching]]
                            :from   [:app_private.bel_events]
                            :where  [:and (scope-clause context) [:= :command_id command-id]]}))))


(defn write-batch!
  {:malli/schema [:=> [:cat :map :map] :nil]}
  [context {:keys [request result events command-id] :as delivery}]
  (if (save-receipt! context delivery result)
    (doseq [[index event] (map-indexed vector events)]
      (append-event! context (assoc event :index index)))
    (when-not (and (= {:request request :result result} (receipt context command-id))
                   (same-events? context command-id events))
      (throw (ex-info "Journal contains another acceptance for this command"
                      {:type :journal-conflict :command-id command-id}))))
  nil)


(defn read-history
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [{:keys [tx] :as context}]
  (jdbc/execute! tx
                 (sql/format {:select   [:id :command_id :actor_id :event_type :occurred_at :recorded_at :event_index :data]
                              :from     [:app_private.bel_events] :where (scope-clause context)
                              :order-by [:recorded_at :command_id :event_index :id]} {:quoted   true})
                 {:builder-fn rs/as-unqualified-lower-maps}))
