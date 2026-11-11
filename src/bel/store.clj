(ns bel.store
  "Database transactions and domain persistence capabilities."
  (:require [bel.validation :as validation]))


(defmulti open-transaction (fn [adapter _resource _isolation _f] adapter))
(defmulti apply-model! (fn [context _model _fields _data] (get-in context [:bel/runtime :adapter])))
(defmulti acceptance (fn [context _command-id] (get-in context [:bel/runtime :adapter])))
(defmulti save-acceptance! (fn [context _invocation _events _result] (get-in context [:bel/runtime :adapter])))


(defmethod open-transaction :default
  [adapter _ _ _]
  (throw (ex-info "Missing transaction adapter" {:type :configuration :adapter adapter})))


(defmethod acceptance :default
  [_ _]
  (throw (ex-info "This domain adapter does not support deferred journal delivery" {:type :configuration})))


(defmethod save-acceptance! :default
  [_ _ _ _]
  (throw (ex-info "This domain adapter does not support deferred journal delivery" {:type :configuration})))


(defn domain
  {:malli/schema [:=> [:cat :map] :map]}
  [context]
  (select-keys (:bel/runtime context) [:adapter :resource]))


(defn same-resource?
  {:malli/schema [:=> [:cat :map :map] :boolean]}
  [a b]
  (= (select-keys a [:adapter :resource]) (select-keys b [:adapter :resource])))


(defn transact
  "Join an already open named resource, or hold a new transaction through the callback."
  {:malli/schema [:=> [:cat :map :map :keyword [:fn fn?]] :any]}
  [context {:keys [adapter resource] :as store} isolation f]
  (validation/check! [:map [:adapter :keyword] [:resource :keyword]] store)
  (if-let [open (get-in context [:bel/transactions resource])]
    (do
      (when-not (= adapter (:adapter open))
        (throw (ex-info "One resource cannot use different transaction adapters" {:type :configuration :resource resource})))
      (f (assoc context :tx (:connection open))))
    (open-transaction adapter (get-in context [:resources resource]) isolation
                      (fn [tx]
                        (f (-> context
                               (assoc :tx tx)
                               (assoc-in [:bel/transactions resource] {:adapter adapter :connection tx})))))))
