(ns tover.workspaces.create-test
  "Board chapter 04 examples for Create Workspace, decided without a store.
  Retry, removed-membership and workspace-switch examples need acceptance and live in
  workspace-creation-integration."
  (:require
    [bel.core :as bel]
    [bel.core-test :as fixture]
    [bel.runtime :as runtime]
    [clojure.test :refer [deftest is use-fixtures]]
    [tover.workspaces.create.model :as model])
  (:import
    [java.time Instant]))


(def alice #uuid "00000000-0000-0000-0000-0000000000a1")
(def bob #uuid "00000000-0000-0000-0000-0000000000b1")
(def workspace #uuid "00000000-0000-0000-0000-0000000000f1")
(def membership #uuid "00000000-0000-0000-0000-0000000000c1")
(def now (Instant/parse "2026-11-14T10:00:00Z"))


(defn register
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (bel/build-registry! ['tover.accounts.profile.model 'tover.workspaces.create.model])
  (f))


(use-fixtures :each fixture/isolated-registry register)


(defn error-type
  {:malli/schema [:=> [:cat [:fn fn?]] [:maybe :keyword]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))


(defn decide
  {:malli/schema [:=> [:cat :map :string] :map]}
  [facts name]
  (runtime/decide ::model/create-workspace
                  (merge {:actor   alice                   :now           now        :scope {:user-id alice}
                          :profile {:display-name "Alice"} :membership-id membership}
                         facts)
                  {:workspace-id workspace :name name}))


(defn allowed?
  {:malli/schema [:=> [:cat :map] :boolean]}
  [principal]
  (runtime/policy ::model/ordinary-user {:principal principal :scope {:user-id alice}} nil))


(deftest creates-workspace-and-owner
  (is (= [{:type ::model/workspace-created
           :data {:workspace-id workspace :name "North Workshop" :created-by alice :created-at now}}
          {:type ::model/workspace-owner-assigned
           :data {:membership-id membership :workspace-id workspace :user-id alice :role "owner" :status "active"}}]
         (map #(select-keys % [:type :data]) (:events (decide {} "  North Workshop  "))))))


(deftest name-boundaries-count-grapheme-clusters
  (let [accepted? (fn [name] (= 2 (count (:events (decide {} name)))))
        family    "👨‍👩‍👧‍👦"
        accented  "é"]
    (is (accepted? "x"))
    (is (accepted? (apply str (repeat 100 "x"))))
    (is (accepted? (apply str (repeat 100 family))))
    (is (accepted? (apply str (repeat 100 accented))))
    (is (= :validation (error-type #(decide {} "   "))))
    (is (= :validation (error-type #(decide {} (apply str (repeat 101 "x"))))))
    (is (= :validation (error-type #(decide {} (apply str (repeat 101 accented))))))))


(deftest profile-incomplete-rejects
  (is (= :profile-incomplete (error-type #(decide {:profile {}} "North Workshop")))))


(deftest sign-in-must-be-ordinary-and-own
  (is (allowed? {:user-id alice :confirmed? true :password-required? false}))
  (is (not (allowed? {:user-id alice :confirmed? true :password-required? true})))
  (is (not (allowed? {:user-id alice :confirmed? false :password-required? false})))
  (is (not (allowed? {:user-id bob :confirmed? true :password-required? false}))))
