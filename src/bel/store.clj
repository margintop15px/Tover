(ns bel.store
  "Storage contracts implemented by integrations, and transaction sharing by store key.")


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

