(ns tover.workspaces.select-test
  "Board chapter 07 examples for Select Workspace, decided without a store."
  (:require
    [bel.core :as bel]
    [bel.core-test :as fixture]
    [bel.runtime :as runtime]
    [clojure.test :refer [deftest is use-fixtures]]
    [tover.workspaces.select.model :as model]))


(def alice #uuid "00000000-0000-0000-0000-0000000000a1")
(def sign-in #uuid "00000000-0000-0000-0000-0000000000e1")
(def other-sign-in #uuid "00000000-0000-0000-0000-0000000000e2")
(def south #uuid "00000000-0000-0000-0000-0000000000f1")
(def north #uuid "00000000-0000-0000-0000-0000000000f2")
(def seen #uuid "00000000-0000-0000-0000-0000000000d1")
(def newer #uuid "00000000-0000-0000-0000-0000000000d2")
(def next-version #uuid "00000000-0000-0000-0000-0000000000d3")


(defn register
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (bel/build-registry! ['tover.workspaces.select.model])
  (f))


(use-fixtures :each fixture/isolated-registry register)


(defn error-type
  {:malli/schema [:=> [:cat [:fn fn?]] [:maybe :keyword]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))


(defn decide
  {:malli/schema [:=> [:cat :map :map] :map]}
  [facts input]
  (runtime/decide ::model/select-workspace
                  (merge {:actor      alice :scope {:session-id sign-in}
                          :selection  {:session-id sign-in :workspace-id south :version seen}
                          :membership {:role "member" :status "active"}
                          :version    next-version}
                         facts)
                  (merge {:workspace-id north :expected-version seen} input)))


(defn allowed?
  {:malli/schema [:=> [:cat :map] :boolean]}
  [principal]
  (runtime/policy ::model/own-sign-in {:principal principal :scope {:session-id sign-in}} {}))


(deftest selects-the-requested-workspace-for-this-sign-in
  (is (= [{:type ::model/workspace-selected :data {:session-id sign-in :workspace-id north :version next-version}}]
         (map #(select-keys % [:type :data]) (:events (decide {} {}))))))


(deftest the-same-workspace-is-chosen-again
  (is (= [] (:events (decide {} {:workspace-id south}))))
  (is (= [] (:events (decide {} {:workspace-id south :expected-version newer})))))


(deftest two-tabs-choose-different-workspaces
  ;; The other tab's choice was accepted first; this tab saw the older selection.
  (is (= :stale-selection
         (error-type #(decide {:selection {:session-id sign-in :workspace-id north :version newer}}
                              {:workspace-id south :expected-version seen})))))


(deftest membership-disappears-during-selection
  (is (= :workspace-unavailable (error-type #(decide {:membership nil} {}))))
  (is (= :workspace-unavailable (error-type #(decide {:membership {:role "member" :status "suspended"}} {}))))
  (is (= :workspace-unavailable (error-type #(decide {:membership nil} {:workspace-id south})))))


(deftest only-this-ordinary-sign-in-may-select
  (is (allowed? {:user-id alice :session-id sign-in :confirmed? true :password-required? false}))
  (is (not (allowed? {:user-id alice :session-id other-sign-in :confirmed? true :password-required? false})))
  (is (not (allowed? {:user-id alice :session-id sign-in :confirmed? true :password-required? true})))
  (is (not (allowed? {:user-id alice :session-id sign-in :confirmed? false :password-required? false}))))
