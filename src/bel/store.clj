(ns bel.store
  "Storage contracts implemented by integrations, and transaction sharing by store key."
  (:require [bel.validation :as validation]))


(defprotocol Transactional
  (^{:malli/schema [:=> [:cat :any :keyword [:fn fn?]] :any]}
    -transact [this isolation f] "Run (f tx); translate native errors to Bel error types."))


(defprotocol Store
  (^{:malli/schema [:=> [:cat :any :any :map :map :any] [:maybe :map]]}
    -load [store tx scope model id] "Entity map or nil.")
  (^{:malli/schema [:=> [:cat :any :any :map :map [:vector :keyword] :map] :any]}
    -write! [store tx scope model fields row] "Replace the mapped fields of one entity."))


(defprotocol Outbox
  (^{:malli/schema [:=> [:cat :any :any :map :map] :any]}
    -record! [store tx scope delivery] "Accepted batch and permanent acceptance marker.")
  (^{:malli/schema [:=> [:cat :any :any :map :string] [:maybe :map]]}
    -accepted [store tx scope command-id] "nil or {:pending delivery-or-nil}.")
  (^{:malli/schema [:=> [:cat :any] [:sequential :map]]}
    -pending [store] "Seq of {:source s :delivery d} or {:source s :error e}.")
  (^{:malli/schema [:=> [:cat :any :map] :any]}
    -ack! [store delivery] "Remove the pending payload; keep the marker."))


(defprotocol Journal
  (^{:malli/schema [:=> [:cat :any :any :map :string] [:maybe :map]]}
    -receipt [journal tx scope command-id])
  (^{:malli/schema [:=> [:cat :any :any :map :map] :any]}
    -append! [journal tx scope delivery] "Idempotent; :journal-conflict on different content.")
  (^{:malli/schema [:=> [:cat :any :any :map] [:sequential :map]]}
    -history [journal tx scope]))


(defn store
  {:malli/schema [:=> [:cat :map :keyword] :any]}
  [context key]
  (or (get-in context [:bel/system :stores key])
      (throw (ex-info "Unknown store" {:type :configuration :store key}))))


(defn with-store
  "Join this operation's open transaction for the store key, or hold a new one through f."
  {:malli/schema [:=> [:cat :map :keyword :keyword [:fn fn?]] :any]}
  [context key isolation f]
  (if-let [tx (get-in context [:bel/transactions key])]
    (f (assoc context :tx tx))
    (-transact (store context key) isolation
               (fn [tx] (f (-> context (assoc :tx tx) (assoc-in [:bel/transactions key] tx)))))))


(def ^:private scope-kinds {:user-id :user :session-id :session :workspace-id :workspace})


(defn store-scope
  "Adapter scope without application key names."
  {:malli/schema [:=> [:cat :map] [:map [:kind :keyword] [:id :any]]]}
  [scope]
  (let [[[key id] & more] (seq scope)]
    (if (and (nil? more) (contains? scope-kinds key))
      {:kind (scope-kinds key) :id id}
      (throw (ex-info "Unsupported scope" {:type :configuration :scope (vec (keys scope))})))))


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
