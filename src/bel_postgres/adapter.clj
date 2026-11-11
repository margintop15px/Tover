(ns bel-postgres.adapter
  "Native PostgreSQL store and journal. Applications supply table bindings as data."
  (:require
    [bel-postgres.jdbc :as db]
    [bel-postgres.journal :as journal]
    [bel.store :as store]
    [bel.validation :as validation]
    [next.jdbc :as jdbc])
  (:import [java.sql SQLException]))


(defn binding!
  {:malli/schema [:=> [:cat :map :map] :map]}
  [tables model]
  (or (get tables (:model-id model))
      (throw (ex-info "Missing native model binding" {:type :configuration :model (:model-id model)}))))


(defn load-model
  {:malli/schema [:=> [:cat :map :map :any] [:maybe :map]]}
  [{:keys [tx tables]} model id]
  (let [{:keys [table columns]} (binding! tables model)]
    (some->> (db/execute-one! tx {:select (db/selection columns)            :from [table]
                                  :where  [:= (get columns (:id model)) id]})
             (into {} (remove (comp nil? val))))))


(defn insert-model!
  {:malli/schema [:=> [:cat :map :map :map] :nil]}
  [{:keys [tx tables]} model row]
  (validation/check! (:schema model) row)
  (let [{:keys [table columns]} (binding! tables model)
        values                  (into {} (map (fn [[field column]] [column (get row field)])) columns)]
    ;; Read permission may depend on other rows inserted later in this transaction.
    (db/execute-one! tx {:insert-into table :values [values]}))
  nil)


(defn transact
  {:malli/schema [:=> [:cat :any :keyword [:fn fn?]] :any]}
  [datasource isolation f]
  (try
    (jdbc/with-transaction [tx datasource {:isolation isolation}]
      (f tx))
    (catch SQLException error
      (case (.getSQLState error)
        "23505" (throw (ex-info "This identity is already in use." {:type :identity-conflict} error))
        ("40001" "40P01")
        (throw (ex-info "Concurrent change; retry the same command" {:type :conflict :retryable? true} error))
        (throw error)))))


(defrecord PgStore [datasource tables]
  store/Transactional
  (-transact [_ isolation f] (transact datasource isolation f))

  store/Store
  (-load [_ tx _ model id] (load-model {:tx tx :tables tables} model id))
  ;; ponytail: creation mappings only; P2 turns this into an upsert of the mapped fields.
  (-write! [_ tx _ model _ row] (insert-model! {:tx tx :tables tables} model row))

  store/Journal
  (-receipt [_ tx scope command-id] (journal/receipt {:tx tx :scope scope} command-id))
  (-append! [_ tx scope delivery] (journal/write-batch! {:tx tx :scope scope} delivery))
  (-history [_ tx scope] (journal/read-history {:tx tx :scope scope})))


(alter-meta! #'->PgStore assoc :malli/schema [:=> [:cat :any :map] :map])
(alter-meta! #'map->PgStore assoc :malli/schema [:=> [:cat :map] :map])


(defn store
  "A PostgreSQL store; `tables` maps model IDs to {:table … :columns {field column}}."
  {:malli/schema [:=> [:cat [:map [:datasource :any] [:tables {:optional true} :map]]] [:fn #(instance? PgStore %)]]}
  [{:keys [datasource tables]}]
  (->PgStore datasource (or tables {})))
