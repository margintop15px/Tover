(ns bel.runtime
  "Command/read orchestration, authorization boundaries and recoverable acceptance."
  (:require
    [bel.core :as core]
    [bel.journal :as journal]
    [bel.store :as store]
    [bel.validation :as validation]
    [com.brunobonacci.mulog :as mu]
    [malli.core :as m]
    [malli.transform :as mt])
  (:import
    [java.time Instant]))


(defmulti policy (fn [id _context _input] id))
(defmulti coeffect (fn [id _context _input] id))
(defmulti effect! (fn [id _context _input] id))
(defmulti snapshot (fn [context _read] (get-in context [:bel/runtime :adapter])))
(defmulti load-model (fn [context _definition _input] (get-in context [:bel/runtime :adapter])))
(defmulti accept! (fn [context _invocation _decide] (get-in context [:bel/runtime :adapter])))
(defmulti authority (fn [id _context] id))
(defmulti check-source! (fn [id _context] id))


(defmethod policy :default
  [id _ _]
  (throw (ex-info "Undeclared policy" {:type :configuration :id id})))


(defmethod coeffect :default
  [id _ _]
  (throw (ex-info "Undeclared coeffect" {:type :configuration :id id})))


(defmethod coeffect :bel/uuid
  [_ _ _]
  (random-uuid))


(defmethod authority :default
  [id _]
  (throw (ex-info "Missing application authority check" {:type :configuration :id id})))


(defmethod check-source! nil
  [_ _]
  nil)


(defmethod check-source! :default
  [id _]
  (throw (ex-info "Missing source compatibility check" {:type :configuration :id id})))


(defmethod load-model :default
  [_ _ _]
  (throw (ex-info "Missing model adapter" {:type :configuration})))


(defn prepare-context
  "Resolve configured facts and resources from trusted application context."
  {:malli/schema [:function [:=> [:cat :map] :map] [:=> [:cat :map :map] :map]]}
  ([ctx]
   (let [{:keys [scope actor resources]} (:bel/runtime ctx)]
     (cond-> ctx
       scope (assoc :scope (select-keys ctx scope))
       actor (assoc :actor (get ctx actor))
       resources (assoc :resources (select-keys ctx resources)))))

  ([ctx definition]
   (let [ctx (prepare-context ctx)]
     (if-let [scope (:scope definition)]
       (let [key ({:user :user-id :session :session-id :workspace :workspace-id} scope)
             id  (get ctx key)]
         (when (nil? id)
           (throw (ex-info "Missing authorized context" {:type :forbidden :status 403})))
         (assoc ctx :scope {key id}))
       ctx))))


(defn- definition!
  {:malli/schema [:=> [:cat :map :keyword :qualified-keyword] :map]}
  [definitions kind id]
  (or (get-in definitions [kind id])
      (throw (ex-info "Undeclared operation" {:type :configuration :kind kind :id id}))))


(defn- decode
  {:malli/schema [:=> [:cat :any :map] :map]}
  [schema input]
  (validation/check! schema
                     (m/decode schema input (mt/transformer validation/input-transformer
                                                            mt/strip-extra-keys-transformer))))


(defn authorize!
  "Check the current policy inside the transaction, including receipt recovery."
  {:malli/schema [:=> [:cat :map :map :map] :nil]}
  [definition context input]
  (when-not (true? (policy (:policy definition) context input))
    (throw (ex-info "Access denied" {:type :forbidden :status 403})))
  nil)


(defn with-authority
  "Hold application authority locks through the owning-store callback and its commit."
  {:malli/schema [:=> [:cat :map :keyword [:fn fn?]] :any]}
  [context isolation f]
  (if-let [guard (get-in context [:bel/runtime :authority])]
    (store/transact context guard
                    (if (store/same-resource? guard (store/domain context)) isolation :read-committed)
                    #(f (authority (:check guard) %)))
    (f context)))


(defmethod snapshot :default
  [context read]
  (with-authority context :repeatable-read
    #(store/transact % (store/domain context) :repeatable-read read)))


(defn- colocated-journal?
  {:malli/schema [:=> [:cat :map] :boolean]}
  [context]
  (store/same-resource? (store/domain context) (get-in context [:bel/runtime :journal])))


(defn- recover
  {:malli/schema [:=> [:cat :map :map] [:maybe :map]]}
  [context invocation]
  (authorize! (:bel/command context) context (get-in invocation [:request :input]))
  (check-source! (get-in context [:bel/runtime :check-source]) context)
  (when-let [prior (if (colocated-journal? context)
                     (journal/receipt context (:command-id invocation))
                     (when-let [accepted (store/acceptance context (:command-id invocation))]
                       (or (:pending accepted)
                           (journal/receipt context (:command-id invocation))
                           (throw (ex-info "Accepted command is not available in the journal; retry without changing its identity"
                                           {:type :journal-unavailable :retryable? true :command-id (:command-id invocation)})))))]
    (if (= (:request invocation) (:request prior))
      (:result prior)
      (throw (ex-info "This command identifier belongs to another request" {:type :idempotency-conflict})))))


(defn apply-event!
  "Interpret model mappings once; adapters persist the selected accepted fields."
  {:malli/schema [:=> [:cat :map :map] :nil]}
  [context event]
  (doseq [[_ model] (get-in context [:bel/definitions :models])
          :let      [fields (get-in model [:events (:type event) :set])]
          :when     fields]
    (store/apply-model! context model fields (select-keys (:data event) (conj fields (:id model)))))
  nil)


(defmethod accept! :default
  [context invocation decide]
  (try
    (with-authority context :serializable
      #(store/transact % (store/domain context) :serializable
                       (fn [context]
                         (or (recover context invocation)
                             (let [{:keys [events fx]} (decide context)
                                   result              {:receipt {:command-id (:command-id invocation) :status :accepted :event-ids (mapv :id events)}
                                                        :fx      fx}]
                               (doseq [event events] (apply-event! context event))
                               (if (colocated-journal? context)
                                 (journal/deliver! context (assoc invocation :events events :result result))
                                 (store/save-acceptance! context invocation events result))
                               result)))))
    (catch clojure.lang.ExceptionInfo error
      (case (:type (ex-data error))
        :identity-conflict
        ;; A concurrent acceptance may have committed after the failed transaction's snapshot.
        (snapshot context #(or (recover % invocation)
                               (throw (ex-info "This identity is already in use."
                                               {:type :identity-conflict :command-id (:command-id invocation)} error))))
        :conflict (throw (ex-info (.getMessage error) (assoc (ex-data error) :command-id (:command-id invocation)) error))
        (throw error)))))


(defn history
  "Read journal history under current application authority."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [context]
  (with-authority (prepare-context context) :read-committed journal/history))


(declare read*)


(defn- load-facts
  {:malli/schema [:=> [:cat :map :map :map :map :map [:set :qualified-keyword]] :map]}
  [definitions definition context input facts seen]
  (reduce
    (fn [facts [local source make-input]]
      (let [request  (if make-input (make-input facts input) {})
            model?   (or (contains? (:read-models definitions) source) (contains? (:models definitions) source))
            adapter? (contains? (methods coeffect) source)]
        (when (and model? adapter?)
          (throw (ex-info "Ambiguous coeffect source" {:type :configuration :id source})))
        (assoc facts local
               (if model?
                 (read* definitions context source request seen)
                 (coeffect source (select-keys context [:tx :scope :actor :resources :bel/runtime :bel/transactions]) request)))))
    facts
    (:cofx definition)))


(defn- read*
  {:malli/schema [:=> [:cat :map :map :qualified-keyword :map [:set :qualified-keyword]] :any]}
  [definitions context id input seen]
  (when (contains? seen id)
    (throw (ex-info "Cyclic read-model dependency" {:type :configuration :id id})))

  (if-let [model (get-in definitions [:models id])]
    (let [key   (:id model)
          input (decode [:map [key [:maybe (get-in model [:fields key :schema])]]] input)]
      (authorize! model context input)
      (when (some? (get input key))
        (validation/check! [:maybe (:schema model)] (load-model context model input))))

    (let [definition (definition! definitions :read-models id)
          input      (decode (:input definition) input)]
      (when (and (:scope definition)
                 (not= (:scope context) (:scope (prepare-context context definition))))
        (throw (ex-info "Nested reads must retain the transaction scope" {:type :configuration :id id})))

      (authorize! definition context input)
      (let [result ((:handler definition)
                    (load-facts definitions definition context input (select-keys context [:scope :actor]) (conj seen id)) input)]
        (validation/check! (:output definition) result)))))


(defn read-model
  {:malli/schema [:=> [:cat :map :qualified-keyword :map] :any]}
  [context id input]
  (let [definitions (core/operations)
        definition  (or (get-in definitions [:read-models id]) (get-in definitions [:models id]))
        context     (prepare-context context (or definition {}))]
    (snapshot (assoc context :bel/definitions definitions) #(read* definitions % id input #{}))))


(defn- plan
  {:malli/schema [:=> [:cat :map :map :map :map] :map]}
  [definitions definition facts input]
  (let [plan ((:handler definition) facts input)]
    (validation/check! [:map
                        [:events {:optional true} [:vector [:map [:type :qualified-keyword] [:data :map]]]]
                        [:fx {:optional true} [:vector [:tuple :qualified-keyword :map]]]]
                       plan)
    (when (seq (remove #{:events :fx} (keys plan)))
      (throw (ex-info "Unsupported command plan" {:type :validation})))

    (doseq [[id _] (:fx plan)]
      (when-not (contains? (methods effect!) id)
        (throw (ex-info "Undeclared effect" {:type :validation :id id}))))

    {:fx     (:fx plan [])
     :events (mapv (fn [{:keys [type data] :as event}]
                     (when (or (seq (remove #{:type :data} (keys event))) (not (contains? (:emits definition) type)))
                       (throw (ex-info "Undeclared event emission" {:type :validation :event type})))
                     (validation/check! (:schema (definition! definitions :events type)) data)
                     {:id    (random-uuid)  :type       type                :data data
                      :actor (:actor facts) :command-id (:command-id facts) :at   (:now facts)})
                   (:events plan []))}))


(defn- dispatch-effects!
  "Best-effort, repeatable notifications only. A retry may publish them again."
  {:malli/schema [:=> [:cat :map [:vector [:tuple :qualified-keyword :map]]] :nil]}
  [context effects]
  (try
    (when (seq effects)
      (Thread/startVirtualThread
        (fn []
          (doseq [[id input] effects]
            (try
              (effect! id (select-keys context [:scope :actor :resources]) input)
              (catch Exception error
                (mu/log ::effect-failed :effect id :error-type (.getName (class error)))))))))
    (catch Exception error
      (mu/log ::effect-dispatch-failed :error-type (.getName (class error)))))
  nil)


(defn execute!
  "Run new decisions inside acceptance; authorized retries return the recorded result."
  {:malli/schema [:=> [:cat :map :qualified-keyword :map] :map]}
  [context id invocation]
  (validation/check! [:map [:command-id [:string {:min 1 :max 100}]] [:input :map]] invocation)
  (let [definitions (core/operations)
        definition  (definition! definitions :commands id)
        context     (prepare-context context definition)
        input       (decode (:input definition) (:input invocation))
        facts       (select-keys context [:scope :actor])
        invocation  {:command-id (:command-id invocation)
                     :request    {:command id :scope (:scope facts) :actor (:actor facts) :input input}}
        {:keys [receipt fx]}
        (accept! (assoc context :bel/definitions definitions :bel/command definition) invocation
                 (fn [context]
                   (let [facts (assoc facts :command-id (:command-id invocation) :now (Instant/now))]
                     (plan definitions definition
                           (load-facts definitions definition context input facts #{}) input))))]
    (dispatch-effects! context fx)
    receipt))
