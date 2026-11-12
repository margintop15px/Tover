(ns bel.effects-test
  (:require
    [bel.core :as core :refer [defcommand defevent]]
    [bel.core-test :as fixture]
    [bel.runtime :as runtime]
    [bel.runtime-test :as rt]
    [clojure.test :refer [deftest is use-fixtures]]))


(use-fixtures :each fixture/isolated-registry)


(def behaviour (atom (fn [_] {})))
(def calls (atom 0))
(def effect-contexts (atom []))
(def delivered (atom (promise)))
(def continuation-contexts (atom []))


(defmethod runtime/effect! ::send
  [_ context input]
  (swap! calls inc)
  (swap! effect-contexts conj context)
  (@behaviour input))


(defmethod runtime/policy ::open
  [_ context _]
  (swap! continuation-contexts conj (:principal context))
  true)


(defevent noted
  {:schema [:map [:test/n :int]]})


(defn- ref-of
  [id input]
  {:type :command :id id :input input})


(defcommand note-then
  {:input [:map [:outcome [:map-of :keyword :any]]] :policy ::open :emits #{::noted}}
  [_ input]
  (deliver @delivered [:then input])
  {})


(defcommand note-catch
  {:input [:map [:problem [:map-of :keyword :any]]] :policy ::open :emits #{::noted}}
  [_ input]
  (deliver @delivered [:catch input])
  {})


(defcommand start
  {:input [:map [:n :int]] :policy ::open :emits #{::noted}}
  [_ {:keys [n]}]
  {:fx [{:id    ::send                   :input {:n n}
         :then  (ref-of ::note-then {})
         :catch (ref-of ::note-catch {})}]})


(defcommand start-bad
  {:input [:map] :policy ::open :emits #{}}
  [_ _]
  {:fx [{:id    ::send                   :input {}
         :then  (ref-of ::missing {})
         :catch (ref-of ::note-catch {})}]})


(defcommand start-tuple
  {:input [:map] :policy ::open :emits #{}}
  [_ _]
  {:fx [[::send {:plain true}]]})


(defn- setup
  [f]
  (core/build-registry! ['bel.effects-test])
  (reset! behaviour f)
  (reset! calls 0)
  (reset! effect-contexts [])
  (reset! continuation-contexts [])
  (reset! delivered (promise))
  (rt/context (rt/memory)))


(defn- await-delivery
  []
  (deref @delivered 2000 :timeout))


(deftest then-runs-after-success
  (let [ctx (setup (fn [_] {:message-id "m1"}))]
    (runtime/execute! ctx ::start {:command-id "S1" :input {:n 1}})
    (is (= [:then {:outcome {:message-id "m1"}}] (await-delivery)))
    (is (some #(= "S1/0/then" (:command-id %)) @(:journal (get-in ctx [:bel/system :stores :memory]))))))


(deftest non-map-return-becomes-empty-outcome
  (let [ctx (setup (fn [_] "ok"))]
    (runtime/execute! ctx ::start {:command-id "S2" :input {:n 1}})
    (is (= [:then {:outcome {}}] (await-delivery)))))


(deftest catch-receives-problem-type
  (let [ctx (setup (fn [_] (throw (ex-info "x" {:type :permanent}))))]
    (runtime/execute! ctx ::start {:command-id "S3" :input {:n 1}})
    (is (= [:catch {:problem {:type :permanent :message "x"}}] (await-delivery)))
    (is (some #(= "S3/0/catch" (:command-id %)) @(:journal (get-in ctx [:bel/system :stores :memory]))))))


(deftest unexpected-error-is-uncertain
  (let [ctx (setup (fn [_] (throw (NullPointerException. "boom"))))]
    (runtime/execute! ctx ::start {:command-id "S4" :input {:n 1}})
    (is (= :uncertain (get-in (await-delivery) [1 :problem :type])))))


(deftest long-message-is-truncated
  (let [ctx (setup (fn [_] (throw (ex-info (apply str (repeat 300 "a")) {:type :temporary}))))]
    (runtime/execute! ctx ::start {:command-id "S5" :input {:n 1}})
    (is (= 200 (count (get-in (await-delivery) [1 :problem :message]))))))


(deftest continuations-keep-the-trusted-context
  (let [ctx (setup (fn [_] {}))
        ctx (assoc ctx :bel/service ::svc)]
    (runtime/execute! ctx ::start {:command-id "S6" :input {:n 1}})
    (await-delivery)
    (is (= #{{:service ::svc}} (set @continuation-contexts)))
    (is (every? #(= #{:scope :actor :resources} (set (keys %))) @effect-contexts))))


(deftest recovery-does-not-rerun-descriptors
  (let [ctx (setup (fn [_] {}))]
    (runtime/execute! ctx ::start {:command-id "S7" :input {:n 1}})
    (await-delivery)
    (Thread/sleep 100)
    (runtime/execute! ctx ::start {:command-id "S7" :input {:n 1}})
    (Thread/sleep 200)
    (is (= 1 @calls))))


(deftest undeclared-continuation-command-is-rejected
  (let [ctx (setup (fn [_] {}))]
    (is (= :validation (rt/error-type #(runtime/execute! ctx ::start-bad {:command-id "S8" :input {}}))))
    (is (empty? @(:journal (get-in ctx [:bel/system :stores :memory]))))))


(deftest tuple-effects-unchanged
  (let [ctx (setup (fn [_] {}))]
    (runtime/execute! ctx ::start-tuple {:command-id "S9" :input {}})
    (Thread/sleep 200)
    (is (= 1 @calls))))
