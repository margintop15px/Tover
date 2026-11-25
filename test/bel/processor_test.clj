(ns bel.processor-test
  (:require
    [bel.core :as core :refer [defcommand defevent defprocessor]]
    [bel.core-test :as fixture]
    [bel.runtime :as runtime]
    [bel.runtime-test :as rt]
    [clojure.test :refer [deftest is use-fixtures]]))


(use-fixtures :each fixture/isolated-registry)


(def workspace (random-uuid))
(def actor (random-uuid))
(def items (atom []))
(def policy-seen (atom []))
(def conflicts (atom 0))


(defmethod runtime/coeffect ::due
  [_ _ _]
  @items)


(defmethod runtime/policy ::service-only
  [_ context _]
  (swap! policy-seen conj (:principal context))
  (= {:service ::the-processor} (:principal context)))


(defevent recorded
  {:schema [:map [:test/n :int]]})


(defcommand record
  {:input [:map [:n :int]] :policy ::service-only :emits #{::recorded} :scope :workspace}
  [{:keys [command-id]} {:keys [n]}]
  (when (= n -1) (throw (ex-info "Rejected" {:type :validation})))
  (when (and (= n -2) (pos? (first (swap-vals! conflicts dec))))
    (throw (ex-info "Concurrent change" {:type :conflict :retryable? true})))
  {:events [{:type ::recorded :data {:test/n n}}]})


(defprocessor the-processor
  {:todo ::due}
  [n]
  {:type :command :id ::record :scope {:workspace-id workspace} :actor actor :input {:n n}})


(defprocessor bad-processor
  {:todo ::due}
  [_]
  {:id ::x})


(defn- system
  [store]
  (:bel/system (assoc-in (rt/context store) [:bel/system :scope] :workspace)))


(defn- setup
  [ids n]
  (core/build-registry! ['bel.processor-test])
  (reset! items n)
  (reset! policy-seen [])
  (let [store (rt/memory)]
    [store (-> (system store) (assoc-in [:authority :impl] (rt/->TestPrincipal)))]))


(defn- run-only
  [id system]
  (let [ops core/operations]
    (with-redefs [core/operations #(update (ops) :processors select-keys [id])]
      (runtime/run-processors! system))))


(deftest processor-runs-due-items
  (let [[store system] (setup nil [1 2])]
    (is (= {:executed 2 :failed 0} (run-only ::the-processor system)))
    (is (= 2 (count @(:journal store))))))


(deftest rerun-is-idempotent
  (let [[store system] (setup nil [1 2])]
    (run-only ::the-processor system)
    (let [journal @(:journal store)]
      (is (= {:executed 2 :failed 0} (run-only ::the-processor system)))
      (is (= journal @(:journal store))))))


(deftest service-principal-reaches-policy
  (let [[_ system] (setup nil [1])]
    (run-only ::the-processor system)
    (is (some #{{:service ::the-processor}} @policy-seen))
    (is (= :forbidden (rt/error-type #(runtime/execute! (assoc (rt/context) :workspace-id workspace :user-id actor :allow? true)
                                                        ::record {:command-id "C" :input {:n 1}}))))))


(deftest one-failure-does-not-stop-others
  (let [[store system] (setup nil [-1 2])]
    (is (= {:executed 1 :failed 1} (run-only ::the-processor system)))
    (is (= 1 (count @(:journal store))))))


(defmethod runtime/policy ::catching-only
  [_ context _]
  (= {:service ::catching-processor} (:principal context)))


(defcommand record-or-reject
  {:input [:map [:n :int]] :policy ::catching-only :emits #{::recorded} :scope :workspace}
  [_ {:keys [n]}]
  (when (neg? n) (throw (ex-info "Rejected" {:type (if (= -1 n) :validation :other)})))
  {:events [{:type ::recorded :data {:test/n n}}]})


(defprocessor catching-processor
  {:todo ::due}
  [n]
  (let [request {:type :command :id ::record-or-reject :scope {:workspace-id workspace} :actor actor :input {:n n}}]
    (assoc request :catch (fn [error] (when (= :validation (:type (ex-data error))) (assoc request :input {:n 7}))))))


(deftest failed-request-may-name-another
  (let [[store system] (setup nil [-1 -2 3])]
    (is (= {:executed 2 :failed 2} (run-only ::catching-processor system)) "-1 becomes 7; nothing replaces -2")
    (is (= 2 (count @(:journal store))))
    (is (= {:executed 2 :failed 2} (run-only ::catching-processor system)))
    (is (= 2 (count @(:journal store))) "the replacement keeps its identity too")))


(deftest command-id-is-derived
  (let [req {:id ::record :scope {:workspace-id workspace} :input {:n 1}}
        id  (runtime/processor-command-id ::p req)]
    (is (= 66 (count id)))
    (is (.startsWith ^String id "p/"))
    (is (= id (runtime/processor-command-id ::p (assoc req :input {:n 1}))))
    (is (not= id (runtime/processor-command-id ::p (assoc req :input {:n 2}))))))


(deftest bad-request-shape-is-a-failure
  (let [[store system] (setup nil [1])]
    (is (= {:executed 0 :failed 1} (run-only ::bad-processor system)))
    (is (empty? @(:journal store)))))


(defprocessor hostile-processor
  {:todo ::due}
  [n]
  {:type :command :id ::record :scope {:workspace-id workspace :bel/service (random-uuid)} :actor actor :input {:n n}})


(deftest hostile-scope-key-is-a-failure
  (let [[store system] (setup nil [1])]
    (is (= {:executed 0 :failed 1} (run-only ::hostile-processor system)))
    (is (empty? @(:journal store)))
    (is (empty? @policy-seen))))


(deftest retryable-conflict-is-retried-with-the-same-identity
  (let [[store system] (setup nil [-2])]
    (reset! conflicts 1)
    (is (= {:executed 1 :failed 0} (run-only ::the-processor system)))
    (is (= 1 (count @(:journal store))))))


(deftest persistent-conflict-is-a-failure
  (let [[_ system] (setup nil [-2])]
    (reset! conflicts 10)
    (is (= {:executed 0 :failed 1} (run-only ::the-processor system)))))
