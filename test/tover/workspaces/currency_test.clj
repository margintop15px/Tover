(ns tover.workspaces.currency-test
  "Board chapter 04a · Workspace currency: one test per slice example."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.runtime :as runtime]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [tover.inventory.store-test :as fixture]
    [tover.support :as support]
    [tover.workspaces.currency.model :as model]
    [tover.workspaces.currency.web :as web])
  (:import
    [java.time Instant]))


(use-fixtures :each bel-test/isolated-registry
  (fn [f]
    (bel/build-registry! ['tover.shared.web.core 'tover.workspaces.currency.model 'tover.workspaces.currency.web])
    (f))
  (fn [f]
    ;; Membership guards run in the store integration suite; here the principal comes from the context.
    (with-redefs [runtime/with-authority (fn [context _touched _isolation read]
                                           (read (assoc context :principal (select-keys context [:user-id :memberships]))))]
      (f))))


(def workspace (:workspace-id fixture/scope))
(def owner #uuid "00000000-0000-0000-0000-000000000011")
(def at (Instant/parse "2026-11-20T10:00:00Z"))


(defn decide
  {:malli/schema [:=> [:cat [:maybe :map] :map] :map]}
  [row input]
  (runtime/decide ::model/change-workspace-currency
                  {:scope {:workspace-id workspace} :actor owner :now at :currency-row row}
                  (merge {:workspace-id workspace} input)))


(defn rejection
  {:malli/schema [:=> [:cat [:fn fn?]] [:maybe :map]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (ex-data error))))


(defn context
  {:malli/schema [:=> [:cat :any :keyword] :map]}
  [repo role]
  (runtime/prepare-context {:bel/system  (support/bel-system {:repo repo})      :repo repo :workspace-id workspace :user-id owner
                            :memberships [{:workspace-id workspace :role role}]}))


(deftest new-workspace-shows-eur
  (fixture/with-store
    (fn [repo _]
      (is (= {:currency "EUR" :can-change? true}
             (runtime/read-model (context repo :owner) ::model/workspace-currency {}))))))


(deftest owner-changes-currency
  (is (= [{:type ::model/workspace-currency-changed
           :data {:currency-key "currency" :currency "RUB" :previous-currency "EUR" :changed-by owner :changed-at at}}]
         (mapv #(select-keys % [:type :data]) (:events (decide nil {:currency "RUB"}))))))


(deftest changed-currency-is-shown-and-relabels
  (fixture/with-store
    (fn [repo _]
      (runtime/execute! (context repo :owner) ::model/change-workspace-currency
                        {:command-id "c1" :input {:workspace-id workspace :currency "RUB"}})
      (testing "every member reads the new code; amounts carry none of their own, so they relabel"
        (is (= {:currency "RUB" :can-change? false}
               (runtime/read-model (context repo :member) ::model/workspace-currency {})))))))


(deftest non-owner-sees-it-read-only
  (fixture/with-store
    (fn [repo _]
      (is (false? (:can-change? (runtime/read-model (context repo :member) ::model/workspace-currency {})))))))


(deftest same-value-records-nothing
  (is (= [] (:events (decide {:currency "RUB"} {:currency "RUB"})))))


(deftest non-owner-rejects
  (fixture/with-store
    (fn [repo _]
      (is (= :forbidden
             (:type (rejection #(runtime/execute! (context repo :admin) ::model/change-workspace-currency
                                                  {:command-id "c1" :input {:workspace-id workspace :currency "RUB"}}))))))))


(deftest lowercase-is-normalized-and-unknown-rejects
  (is (= "RUB" (get-in (decide nil {:currency " rub "}) [:events 0 :data :currency])))
  (is (= :unknown-currency (:type (rejection #(decide nil {:currency "XYZ"}))))))


(deftest other-workspace-id-is-not-permitted
  (is (= :not-permitted (:type (rejection #(decide nil {:workspace-id #uuid "00000000-0000-0000-0000-000000000099" :currency "RUB"}))))))


(deftest settings-save-returns-a-descriptor
  (with-redefs [runtime/execute! (fn [& _] (throw (Exception. "Action must be pure")))]
    (is (= {:type  :command :id ::model/change-workspace-currency :command-id "c1"
            :input {:workspace-id workspace :currency "RUB"}
            :then  {:status 303 :headers {"location" "/settings"} :body ""}}
           (web/save {} {:workspace-id workspace :currency "RUB" :command-id "c1"})))))
