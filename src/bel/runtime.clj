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


(defmethod policy :default
  [id _ _]
  (throw (ex-info "Undeclared policy" {:type :configuration :id id})))


(defmethod coeffect :default
  [id _ _]
  (throw (ex-info "Undeclared coeffect" {:type :configuration :id id})))


(defmethod coeffect :bel/uuid
  [_ _ _]
  (random-uuid))


(defmethod coeffect :bel/now
  [_ _ _]
  (Instant/now))


(defmethod coeffect :bel/receipt
  [_ context {:keys [command-id]}]
  (journal/receipt context command-id))


(def ^:private scope-keys {:user :user-id :session :session-id :workspace :workspace-id})


(defn prepare-context
  "Resolve trusted scope, actor and effect resources. A declared scope wins over the system default."
  {:malli/schema [:function [:=> [:cat :map] :map] [:=> [:cat :map :map] :map]]}
  ([ctx] (prepare-context ctx {}))

  ([ctx definition]
   (let [ctx  (assoc ctx :actor (:user-id ctx) :resources (get-in ctx [:bel/system :resources]))
         kind (or (:scope definition) (get-in ctx [:bel/system :scope]))]
     (if kind
       (let [key (scope-keys kind)
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
  (when-not (true? (policy (:policy definition) (select-keys context [:principal :scope]) input))
    (throw (ex-info "Access denied" {:type :forbidden :status 403})))
  nil)


(defprotocol Authority
  (^{:malli/schema [:=> [:cat :any :any :map] :map]}
    -principal [authority tx context] "Lock and verify the current session; return the principal map or throw :forbidden."))


(defn with-authority
  "Hold application authority locks through the owning-store callback and its commit.
  The authority store opens at `isolation` when the operation also uses it (`touched`), else Read Committed."
  {:malli/schema [:=> [:cat :map [:set :keyword] :keyword [:fn fn?]] :any]}
  [context touched isolation f]
  (if-let [service (:bel/service context)]
    (f (assoc context :principal {:service service}))
    (if-let [{key :store impl :impl} (get-in context [:bel/system :authority])]
      (store/with-store context key (if (contains? touched key) isolation :read-committed)
        #(f (assoc % :principal (-principal impl (:tx %) %))))
      (f (assoc context :principal nil)))))


(defn with-store
  "Join this operation's transaction for a store key, or hold a new one through f."
  {:malli/schema [:=> [:cat :map :keyword :keyword [:fn fn?]] :any]}
  [context key isolation f]
  (store/with-store context key isolation f))


(defn- with-stores
  {:malli/schema [:=> [:cat :map [:maybe [:sequential :keyword]] :keyword [:fn fn?]] :any]}
  [context keys isolation f]
  (if-let [[key & more] (seq keys)]
    (store/with-store context key isolation #(with-stores % more isolation f))
    (f context)))


(defn- read-stores
  "Stores of every model reachable through declared coeffects."
  {:malli/schema [:=> [:cat :map :qualified-keyword [:set :qualified-keyword]] [:set :keyword]]}
  [definitions id seen]
  (if-let [model (get-in definitions [:models id])]
    #{(:store model)}
    (if-let [definition (when-not (contains? seen id) (get-in definitions [:read-models id] (get-in definitions [:commands id])))]
      (into #{} (mapcat #(read-stores definitions (second %) (conj seen id))) (:cofx definition))
      #{})))


(defn- owning-store
  "A command writes to the store of the models its events map to, or only to the journal."
  {:malli/schema [:=> [:cat :map :map :map] :keyword]}
  [context definitions definition]
  (let [stores (into #{} (keep (fn [[_ model]] (when (some (:emits definition) (keys (:events model))) (:store model))))
                     (:models definitions))]
    (case (count stores)
      0 (get-in context [:bel/system :journal])
      1 (let [key (first stores)]
          (when-not (or (= key (get-in context [:bel/system :journal])) (satisfies? store/Outbox (store/store context key)))
            (throw (ex-info "A store that is not the journal must be an outbox" {:type :configuration :store key})))
          key)
      (throw (ex-info "A command writes to one store" {:type :configuration :stores stores})))))


(defn- adapter-call
  {:malli/schema [:=> [:cat :map :keyword :keyword [:fn fn?]] :any]}
  [context key isolation f]
  (store/with-store context key isolation #(f (store/store % key) (:tx %) (store/store-scope (:scope %)))))


(defn- load-model
  {:malli/schema [:=> [:cat :map :map :map] [:maybe :map]]}
  [context model input]
  (adapter-call context (:store model) :repeatable-read #(store/-load %1 %2 %3 model (get input (:id model)))))


(defn- colocated?
  {:malli/schema [:=> [:cat :map :keyword] :boolean]}
  [context owning]
  (= owning (get-in context [:bel/system :journal])))


(defn- recover
  {:malli/schema [:=> [:cat :map :keyword :map] [:maybe :map]]}
  [context owning invocation]
  (authorize! (:bel/command context) context (get-in invocation [:request :input]))
  (when-let [prior (if (colocated? context owning)
                     (journal/receipt context (:command-id invocation))
                     (when-let [accepted (adapter-call context owning :serializable
                                                       #(store/-accepted %1 %2 %3 (:command-id invocation)))]
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
          :let      [{:keys [set create delete]} (get-in model [:events (:type event)])
                     fields (or set create)]
          :when     (or fields delete)]
    (adapter-call context (:store model) :serializable
                  (fn [store tx scope]
                    (let [row (select-keys (:data event) (conj (or fields []) (:id model)))]
                      (cond
                        delete (store/-delete! store tx scope model (get row (:id model)))
                        create (store/-create! store tx scope model row)
                        :else (store/-write! store tx scope model fields row))))))
  nil)


(defn- accept
  {:malli/schema [:=> [:cat :map :keyword [:sequential :keyword] :map [:fn fn?]] [:map [:result :map] [:fresh? :boolean]]]}
  [context owning reads invocation decide]
  (try
    (with-authority context (conj (set reads) owning) :serializable
      #(store/with-store % owning :serializable
         (fn [context]
           (if-let [prior (recover context owning invocation)]
             {:result prior :fresh? false}
             (let [{:keys [events fx]} (with-stores context reads :repeatable-read decide)
                   result              {:receipt {:command-id (:command-id invocation) :status :accepted :event-ids (mapv :id events)}
                                        :fx      fx}
                   delivery            (assoc invocation :events events :result result)]
               (doseq [event events] (apply-event! context event))
               (if (colocated? context owning)
                 (journal/deliver! context delivery)
                 (adapter-call context owning :serializable (fn [s tx scope] (store/-record! s tx scope delivery))))
               {:result result :fresh? true})))))
    (catch clojure.lang.ExceptionInfo error
      (case (:type (ex-data error))
        :identity-conflict
        ;; A concurrent acceptance may have committed after the failed transaction's snapshot.
        (with-authority context (conj (set reads) owning) :repeatable-read
          #(store/with-store % owning :repeatable-read
             (fn [context]
               (if-let [prior (recover context owning invocation)]
                 {:result prior :fresh? false}
                 (throw (ex-info "This identity is already in use."
                                 {:type :identity-conflict :command-id (:command-id invocation)} error))))))
        :conflict (throw (ex-info (.getMessage error) (assoc (ex-data error) :command-id (:command-id invocation)) error))
        (throw error)))))


(defn history
  "Read journal history under current application authority."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [context]
  (with-authority (prepare-context context) #{} :read-committed journal/history))


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
                 (coeffect source (select-keys context [:scope :actor :principal :bel/system :bel/transactions]) request)))))
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


(defn- snapshot
  "Open, up front, every store a read references; authority holds its locks around them."
  {:malli/schema [:=> [:cat :map :map :qualified-keyword [:fn fn?]] :any]}
  [context definitions id read]
  (let [stores (sort (read-stores definitions id #{}))]
    ;; Coeffect-only reads (no model store) still snapshot the authority store.
    (with-authority context (into #{} (or (seq stores) (keep identity [(get-in context [:bel/system :authority :store])])))
      :repeatable-read
      #(with-stores % stores :repeatable-read read))))


(defn read-model
  {:malli/schema [:=> [:cat :map :qualified-keyword :map] :any]}
  [context id input]
  (let [definitions (core/operations)
        definition  (or (get-in definitions [:read-models id]) (get-in definitions [:models id]))
        context     (prepare-context context (or definition {}))]
    (snapshot (assoc context :bel/definitions definitions) definitions id #(read* definitions % id input #{}))))


(defn- plan
  {:malli/schema [:=> [:cat :map :map :map :map] :map]}
  [definitions definition facts input]
  (let [plan ((:handler definition) facts input)]
    (validation/check! [:map
                        [:events {:optional true} [:vector [:map [:type :qualified-keyword] [:data :map]]]]
                        [:fx {:optional true} [:vector [:or [:tuple :qualified-keyword :map] core/EffectDescriptor]]]]
                       plan)
    (when (seq (remove #{:events :fx} (keys plan)))
      (throw (ex-info "Unsupported command plan" {:type :validation})))

    (doseq [fx   (:fx plan)
            :let [descriptor? (map? fx)
                  id (if descriptor? (:id fx) (first fx))]]
      (when-not (contains? (methods effect!) id)
        (throw (ex-info "Undeclared effect" {:type :validation :id id})))
      (when descriptor?
        (doseq [k    [:then :catch]
                :let [command (get-in fx [k :id])]]
          (when-not (contains? (:commands definitions) command)
            (throw (ex-info "Undeclared continuation command" {:type :validation :id command}))))))

    {:fx     (:fx plan [])
     :events (mapv (fn [{:keys [type data] :as event}]
                     (when (or (seq (remove #{:type :data} (keys event))) (not (contains? (:emits definition) type)))
                       (throw (ex-info "Undeclared event emission" {:type :validation :event type})))
                     (validation/check! (:schema (definition! definitions :events type)) data)
                     {:id    (random-uuid)  :type       type                :data data
                      :actor (:actor facts) :command-id (:command-id facts) :at   (:now facts)})
                   (:events plan []))}))


(declare execute!)


(defn- problem
  {:malli/schema [:=> [:cat [:fn #(instance? Throwable %)]] [:map [:type :keyword] [:message :string]]]}
  [error]
  (let [t (:type (ex-data error))]
    {:type    (if (#{:temporary :permanent :uncertain} t) t :uncertain)
     :message (let [m (str (.getMessage ^Throwable error))] (subs m 0 (min 200 (count m))))}))


(defn- retrying
  "Call `f`, repeating a failure marked :retryable? up to `attempts` times with jittered exponential backoff
  (20 ms doubling, capped at 1 s). `f` must keep its command identity."
  {:malli/schema [:=> [:cat :int [:fn fn?]] :any]}
  [attempts f]
  (loop [attempt 1]
    (let [result (try {:value (f)}
                      (catch clojure.lang.ExceptionInfo error
                        (if (and (:retryable? (ex-data error)) (< attempt attempts))
                          (let [base (min 1000 (* 20 (bit-shift-left 1 (dec attempt))))]
                            (Thread/sleep (long (+ (quot base 2) (rand-int base)))))
                          (throw error))))]
      (if (map? result) (:value result) (recur (inc attempt))))))


(defn- continue!
  "Run a continuation command with the trusted context of the originating command."
  {:malli/schema [:=> [:cat :map :string :int :keyword :map :map] :nil]}
  [context command-id i kind ref extra]
  (try
    ;; A failed attempt dispatched nothing, so retrying never repeats an effect; giving up loses the outcome.
    (retrying 10 #(execute! context (:id ref) {:command-id (str command-id "/" i "/" (name kind))
                                               :input      (merge (:input ref) extra)}))
    (catch Exception error
      (mu/log ::continuation-failed :command (:id ref) :error-type (or (:type (ex-data error)) (.getName (class error))))))
  nil)


(defn- run-effect!
  {:malli/schema [:=> [:cat :map :string :int [:or [:tuple :qualified-keyword :map] core/EffectDescriptor]] :nil]}
  [context command-id i fx]
  (if (map? fx)
    (let [{:keys [id input] on-ok :then on-error :catch} fx
          result (try {:outcome (let [r (effect! id (select-keys context [:scope :actor :resources]) input)]
                                  (if (map? r) r {}))}
                      (catch Throwable error
                        (mu/log ::effect-failed :effect id :error-type (.getName (class error)))
                        {:problem (problem error)}))]
      (if (contains? result :outcome)
        (continue! context command-id i :then on-ok {:outcome (:outcome result)})
        (continue! context command-id i :catch on-error {:problem (:problem result)})))
    (let [[id input] fx]
      (try
        (effect! id (select-keys context [:scope :actor :resources]) input)
        (catch Exception error
          (mu/log ::effect-failed :effect id :error-type (.getName (class error)))))))
  nil)


(defn- dispatch-effects!
  "Best-effort notifications after commit. Tuple effects may repeat on retry; descriptors run once."
  {:malli/schema [:=> [:cat :map :string :boolean [:vector :any]] :nil]}
  [context command-id fresh? effects]
  (try
    (let [effects (if fresh? effects (filterv vector? effects))]
      (when (seq effects)
        (Thread/startVirtualThread
          (fn []
            (doseq [[i fx] (map-indexed vector effects)]
              (run-effect! context command-id i fx))))))
    (catch Exception error
      (mu/log ::effect-dispatch-failed :error-type (.getName (class error)))))
  nil)


(defn decide
  "Run a command's pure decision with given facts through the runtime's plan checks. For scenario tests."
  {:malli/schema [:=> [:cat :qualified-keyword :map :map] :map]}
  [id facts input]
  (let [definitions (core/operations)
        definition  (definition! definitions :commands id)]
    (plan definitions definition facts (decode (:input definition) input))))


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
        owning      (owning-store context definitions definition)
        {{:keys [receipt fx]} :result fresh? :fresh?}
        (accept (assoc context :bel/definitions definitions :bel/command definition) owning
                (sort (disj (read-stores definitions id #{}) owning)) invocation
                (fn [context]
                  (let [facts (assoc facts :command-id (:command-id invocation) :now (Instant/now))]
                    (plan definitions definition
                          (load-facts definitions definition context input facts #{}) input))))]
    (dispatch-effects! context (:command-id invocation) fresh? fx)
    receipt))


(defn processor-command-id
  "Derive a stable, bounded command ID so reruns of the same request are idempotent."
  {:malli/schema [:=> [:cat :qualified-keyword :map] [:string {:min 66 :max 66}]]}
  [processor-id request]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes ^String (pr-str [processor-id (:id request) (:scope request) (:input request)]) "UTF-8"))]
    (str "p/" (apply str (map #(format "%02x" (bit-and % 0xff)) digest)))))


(defn- run-request!
  "Execute one processor request; a failed one may name a replacement through its `:catch`."
  {:malli/schema [:=> [:cat :map :qualified-keyword :map :map] :map]}
  [system id counts request]
  (let [error (try
                (validation/check! core/ProcessorRequest request)
                (retrying 3 #(execute! (merge (:scope request) {:bel/system system :bel/service id :user-id (:actor request)})
                                       (:id request) {:command-id (processor-command-id id request) :input (:input request)}))
                nil
                (catch Exception error
                  (mu/log ::processor-failed :processor id :command (:id request)
                          :error-type (or (:type (ex-data error)) (.getName (class error))))
                  error))]
    (if-not error
      (update counts :executed inc)
      (let [counts      (update counts :failed inc)
            replacement (when-let [on-failure (:catch request)]
                          (try (on-failure error)
                               (catch Exception error
                                 (mu/log ::processor-failed :processor id :command (:id request) :error-type (.getName (class error)))
                                 nil)))]
        (if replacement
          (recur system id counts (dissoc replacement :catch))
          counts)))))


(defn- run-processor!
  {:malli/schema [:=> [:cat :map :qualified-keyword :map] :map]}
  [system id {:keys [todo handler]}]
  (let [items (coeffect todo {:bel/system system} {})]
    (reduce (fn [counts item]
              (let [requests (try (let [result (handler item)] (if (map? result) [result] (vec result)))
                                  (catch Exception error
                                    (mu/log ::processor-failed :processor id :error-type (or (:type (ex-data error)) (.getName (class error))))
                                    nil))]
                (if (nil? requests)
                  (update counts :failed inc)
                  (reduce #(run-request! system id %1 %2) counts requests))))
            {:executed 0 :failed 0} items)))


(defn run-processors!
  "Run every declared processor once; failures are counted per request, never thrown."
  {:malli/schema [:=> [:cat :map] [:map [:executed :int] [:failed :int]]]}
  [system]
  (reduce (fn [counts [id processor]]
            (merge-with + counts
                        (try (run-processor! system id processor)
                             (catch Exception error
                               (mu/log ::processor-failed :processor id :error-type (or (:type (ex-data error)) (.getName (class error))))
                               {:failed 1}))))
          {:executed 0 :failed 0} (:processors (core/operations))))
