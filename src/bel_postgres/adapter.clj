(ns bel-postgres.adapter
  "Native PostgreSQL transactions and model persistence. Applications supply table bindings."
  (:require
    [bel-postgres.jdbc :as db]
    [bel.runtime :as runtime]
    [bel.store :as store]
    [bel.validation :as validation]
    [next.jdbc :as jdbc])
  (:import [java.sql SQLException]))


(defmulti model-binding (fn [model] (:model-id model)))


(defmethod model-binding :default
  [model]
  (throw (ex-info "Missing native model binding" {:type :configuration :model (:model-id model)})))


(defmethod runtime/load-model :postgres
  [{:keys [tx]} model input]
  (let [{:keys [table columns]} (model-binding model)]
    (some->> (db/execute-one! tx {:select (db/selection columns) :from [table]
                                  :where  [:= (get columns (:id model)) (get input (:id model))]})
             (into {} (remove (comp nil? val))))))


(defn insert-model!
  {:malli/schema [:=> [:cat :map :map :map] :nil]}
  [{:keys [tx]} model row]
  (validation/check! (:schema model) row)
  (let [{:keys [table columns]} (model-binding model)
        values                  (into {} (map (fn [[field column]] [column (get row field)])) columns)]
    ;; Read permission may depend on other rows inserted later in this transaction.
    (db/execute-one! tx {:insert-into table :values [values]}))
  nil)


(defmethod store/apply-model! :postgres
  [context model _fields data]
  (insert-model! context model data))


(defmethod store/open-transaction :postgres
  [_ datasource isolation f]
  (try
    (jdbc/with-transaction [tx datasource {:isolation isolation}]
      (f tx))
    (catch SQLException error
      (case (.getSQLState error)
        "23505" (throw (ex-info "This identity is already in use." {:type :identity-conflict} error))
        ("40001" "40P01")
        (throw (ex-info "Concurrent change; retry the same command" {:type :conflict :retryable? true} error))
        (throw error)))))
