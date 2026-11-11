(ns bel.runtime-test
  (:require
    [bel.core :as core :refer [defcommand defevent defmodel defreadmodel]]
    [bel.core-test :as fixture]
    [bel.runtime :as runtime]
    [clojure.test :refer [deftest is use-fixtures]]))


(use-fixtures :each fixture/isolated-registry)


(defmethod runtime/policy ::allowed
  [_ context _]
  (:allow? context))


(defmethod runtime/policy ::nested
  [_ context _]
  (:nested? context))


(defmethod runtime/coeffect ::echo
  [_ context input]
  (swap! (get-in context [:resources :seen]) conj (:tx context))
  input)


(defmethod runtime/snapshot ::memory
  [context read]
  (read (assoc context :tx (Object.))))


(defmethod runtime/accept! ::memory
  [context invocation decide]
  (let [context (assoc context :tx (Object.))
        _       (runtime/authorize! (:bel/command context) context (get-in invocation [:request :input]))
        plan    (decide context)]
    {:receipt (select-keys plan [:events]) :fx (:fx plan)}))


(defreadmodel child
  {:input [:map [:value :int]]                   :output :map :policy ::nested
   :cofx  [[:value ::echo (fn [_ input] input)]]}
  [{:keys [value]} _]
  value)


(defreadmodel composed
  {:input [:map [:value :int]] :output :map :policy ::allowed
   :cofx  [[:empty ::echo]
           [:child ::child (fn [_ input] input)]
           [:last ::echo (fn [facts _] {:value (inc (get-in facts [:child :value]))})]]}
  [facts _]
  facts)


(defevent changed
  {:schema [:map [:test/value :int]]})


(defcommand change
  {:input [:map [:value :int]] :policy ::allowed :emits #{::changed}}
  [_ input]
  {:events [{:type ::changed :data {:test/value (:value input)}}]})


(defn context
  []
  {:scope     {}                :actor       "alice"             :allow? true :nested? true
   :resources {:seen (atom [])} :bel/runtime {:adapter ::memory}})


(defn error-type
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))


(deftest ordered-coeffects-and-nested-authorization-share-one-snapshot
  (core/build-registry! ['bel.runtime-test])
  (let [ctx    (context)
        result (runtime/read-model ctx ::composed {:value "4" :actor "mallory"})
        seen   @(get-in ctx [:resources :seen])]
    (is (= [{} {:value 4} {:value 5}] ((juxt :empty :child :last) result)))
    (is (= "alice" (:actor result)))
    (is (not (contains? result :tx)))
    (is (= 3 (count seen)))
    (is (apply identical? (take 2 seen)))
    (is (identical? (first seen) (last seen)))
    (is (= :forbidden (error-type #(runtime/read-model (assoc ctx :nested? false) ::composed {:value 4}))))
    (is (= :forbidden (error-type #(runtime/read-model (assoc ctx :allow? :truthy) ::composed {:value 4}))))))


(deftest invalid-bindings-and-unsupported-options-fail-at-declaration
  (let [opts     (:bel/options (meta #'composed))
        metadata (meta #'composed)]
    (try
      (doseq [bad [{:cofx [[:scope ::echo]]}
                   {:cofx [1]}
                   {:cofx [[:x ::echo] [:x ::echo]]}
                   {:cofx [[:x ::echo nil]]}
                   {:cofx [[:x ::echo {}]]}
                   {:cofx [[:x :unqualified]]}
                   {:cofx-schema {}}
                   {:scope :account}
                   {:scope :sign-in}
                   {:scope ::user}
                   {:store :rdf}]]
        (is (= :validation (error-type #(core/attach-operation! #'composed :bel/read-model ::composed (merge opts bad))))))
      (doseq [[kind opts] [[:bel/action {:command ::change :effects []}]
                           [:bel/action {:command ::change :query identity}]
                           [:bel/view {:read-model ::child :cofx-schema {}}]]]
        (is (= :validation (error-type #(core/attach-definition! #'composed kind ::composed opts)))))
      (finally (reset-meta! #'composed metadata)))))


(deftest explicit-scopes-use-only-trusted-identifiers
  (core/build-registry! ['bel.runtime-test])
  (let [definitions (core/operations)
        ctx         (assoc (context) :user-id "alice" :session-id "session-A" :workspace-id "workspace-A")]
    (doseq [[scope key] [[:user :user-id] [:session :session-id] [:workspace :workspace-id]]]
      (with-redefs [core/operations (constantly (assoc-in definitions [:read-models ::composed :scope] scope))]
        (is (= {key (get ctx key)}
               (:scope (runtime/read-model ctx ::composed {:value 1 :user-id "mallory" :workspace-id "workspace-B"}))))
        (is (= :forbidden (error-type #(runtime/read-model (dissoc ctx key) ::composed {:value 1}))))))
    (with-redefs [core/operations (constantly (-> definitions
                                                  (assoc-in [:read-models ::composed :scope] :user)
                                                  (assoc-in [:read-models ::child :scope] :workspace)))]
      (is (= :configuration (error-type #(runtime/read-model ctx ::composed {:value 1})))))))


(defmethod runtime/policy ::transaction-required
  [_ context _]
  (some? (:tx context)))


(deftest authorization-runs-in-the-acceptance-transaction
  (core/build-registry! ['bel.runtime-test])
  (let [definitions (core/operations)]
    (with-redefs [core/operations (constantly (assoc-in definitions [:commands ::change :policy] ::transaction-required))]
      (is (= 1 (count (:events (runtime/execute! (context) ::change {:command-id "inside-tx" :input {:value 1}}))))))))


(deftest cycles-missing-sources-and-adapter-collisions-reject
  (core/build-registry! ['bel.runtime-test])
  (let [original (core/operations)]
    (doseq [source [::composed ::missing]]
      (with-redefs [core/operations (constantly (assoc-in original [:read-models ::composed :cofx] [[:loop source]]))]
        (is (= :configuration (error-type #(runtime/read-model (context) ::composed {:value 4}))))))
    (.addMethod ^clojure.lang.MultiFn runtime/coeffect ::child (fn [& _] {}))
    (try
      (is (= :configuration (error-type #(runtime/read-model (context) ::composed {:value 4}))))
      (finally (remove-method runtime/coeffect ::child)))))


(deftest plans-can-only-emit-declared-valid-events
  (core/build-registry! ['bel.runtime-test])
  (let [original (core/operations)]
    (doseq [plan [{:events [{:type ::missing :data {}}]}
                  {:events [{:type ::changed :data {:test/value "bad"}}]}
                  {:events [{:type ::changed :data {:test/value 1} :id "spoofed"}]}
                  {:fx [[:http/request {}]]}
                  {:fx true}
                  {:commands []}
                  {:events nil}]]
      (with-redefs [core/operations (constantly (assoc-in original [:commands ::change :handler] (constantly plan)))]
        (is (= :validation (error-type #(runtime/execute! (context) ::change {:command-id "C" :input {:value 1}})))))))
  (let [result (runtime/execute! (context) ::change {:command-id "C" :input {:value "1" :actor "mallory"}})]
    (is (= {:test/value 1} (get-in result [:events 0 :data])))
    (is (= "alice" (get-in result [:events 0 :actor])))
    (is (uuid? (get-in result [:events 0 :id])))
    (is (instance? java.time.Instant (get-in result [:events 0 :at])))))


(defmodel entity
  {:id :id :schema [:map [:id :string] [:value :int]] :policy ::nested})


(defmethod runtime/load-model ::memory
  [context _ input]
  (get-in context [:resources :rows (:id input)]))


(defreadmodel entity-screen
  {:input [:map [:id :string]] :output [:maybe :map] :policy ::allowed
   :cofx  [[:entity ::entity (fn [_ input] input)]]}
  [{:keys [entity]} _]
  entity)


(deftest model-lookups-validate-input-output-and-nested-policy
  (core/build-registry! ['bel.runtime-test])
  (let [ctx (assoc-in (context) [:resources :rows] {"G" {:id "G" :value 4}})]
    (is (= {:id "G" :value 4} (runtime/read-model ctx ::entity-screen {:id "G"})))
    (is (nil? (runtime/read-model ctx ::entity {:id "missing"})))
    (is (nil? (runtime/read-model ctx ::entity {:id nil})))
    (is (= :forbidden (error-type #(runtime/read-model (assoc ctx :nested? false) ::entity-screen {:id "G"}))))
    (is (= :validation (error-type #(runtime/read-model ctx ::entity {}))))
    (is (= :validation (error-type #(runtime/read-model (assoc-in ctx [:resources :rows "G" :value] "bad") ::entity {:id "G"}))))))


(deftest models-reject-ambiguous-storage-and-mapping-declarations
  (let [opts        (:bel/options (meta #'entity))
        declaration (select-keys opts [:id :schema :policy :events])
        metadata    (meta #'entity)]
    (try
      (doseq [bad [{:id :missing}
                   {:schema [:map [:id {:optional true} :string]]}
                   {:schema [:map [:id :int]]}
                   {:schema [:map [:id :string] [:values [:vector :string]]]}
                   {:schema [:map [:id :string] [:ref {:bel/ref ::entity} :int]]}
                   {:events {::changed {:set [:id]}}}
                   {:events {::changed {:set [:value :value]}}}
                   {:events {::changed {:set [:unknown]}}}
                   {:events {::changed {:set [:value] :call identity}}}
                   {:vocabulary {}}]]
        (is (= :validation (error-type #(core/attach-model! #'entity ::entity (merge declaration bad))))))
      (finally (reset-meta! #'entity metadata)))))


(deftest model-discovery-rejects-undeclared-reference-and-event-targets
  (let [opts     (select-keys (:bel/options (meta #'entity)) [:id :schema :policy :events])
        metadata (meta #'entity)]
    (try
      (doseq [bad [{:schema [:map [:id :string] [:link {:bel/ref ::missing} :string]]}
                   {:events {::missing {:set [:value]}}}]]
        (core/attach-model! #'entity ::entity (merge opts bad))
        (is (= :configuration (error-type #(core/build-registry! ['bel.runtime-test])))))
      (finally (reset-meta! #'entity metadata)))))


(deftest mapped-fields-must-be-declared-by-the-event
  (let [metadata (meta #'entity)
        opts     (select-keys (:bel/options metadata) [:id :schema :policy])]
    (try
      (core/attach-model! #'entity ::entity
                          (assoc opts :schema (conj (:schema opts) [:label {:optional true} :string])
                                 :events {::changed {:set [:label]}}))
      (let [failure (try (core/build-registry! ['bel.runtime-test])
                         (catch clojure.lang.ExceptionInfo error (ex-data error)))]
        (is (= :configuration (:type failure)))
        (is (= #{:id :label} (set (:fields failure)))))
      (finally (reset-meta! #'entity metadata)))))
