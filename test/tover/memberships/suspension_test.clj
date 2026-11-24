(ns tover.memberships.suspension-test
  "Board chapter 35 · Suspend and reinstate workspace access: one test per slice example."
  (:require
    [bel.core-test :as fixture]
    [clojure.test :refer [deftest is use-fixtures]]
    [tover.memberships.support :as s :refer [ann bob cid dee]]
    [tover.memberships.suspension.model :as suspension]))


(use-fixtures :each fixture/isolated-registry s/register)


(defn suspend
  {:malli/schema [:=> [:cat :map [:maybe :map]] :map]}
  [actor target]
  (s/decide ::suspension/suspend-workspace-member actor target (s/owners ann dee) {}))


(defn reinstate
  {:malli/schema [:=> [:cat :map [:maybe :map]] :map]}
  [actor target]
  (s/decide ::suspension/reinstate-workspace-member actor target (s/owners ann dee) {}))


;; Review temporary access suspension


(deftest admin-sees-suspend-on-a-member
  (is (true? (suspension/can-suspend? bob cid))))


(deftest no-suspend-on-own-row
  (is (false? (suspension/can-suspend? ann ann))))


(deftest admin-cannot-suspend-an-owner-row
  (is (false? (suspension/can-suspend? bob ann))))


;; Suspend the reviewed membership


(deftest admin-suspends-a-member
  (let [cid (assoc cid :revision 2)]
    (is (= [{:type ::suspension/workspace-member-suspended
             :data {:membership-id (:membership-id cid) :workspace-id s/workspace :account-id (:user-id cid)
                    :role          "member"             :status       "suspended" :revision   3
                    :suspended-by  (:user-id bob)       :suspended-at s/now}}]
           (s/events (suspend bob cid))))))


(deftest cannot-suspend-yourself
  (is (= :own-membership (s/rejection #(suspend ann ann)))))


(deftest admin-cannot-suspend-an-owner
  (is (= :not-permitted (s/rejection #(suspend bob ann)))))


(deftest stale-suspension-review
  (is (= :changed-since-review
         (s/rejection #(s/decide ::suspension/suspend-workspace-member bob (assoc cid :revision 4) (s/owners ann)
                                 {:expected-revision 3})))))


(deftest suspension-reason-is-kept
  (is (= "audit" (get-in (s/events (s/decide ::suspension/suspend-workspace-member bob cid (s/owners ann)
                                             {:reason "audit"}))
                         [0 :data :suspension-reason]))))


;; Review the suspended membership for reinstatement


(deftest owner-sees-reinstate
  (is (true? (suspension/can-reinstate? ann (assoc bob :status "suspended")))))


(deftest admin-cannot-reinstate-an-owner-row
  (is (false? (suspension/can-reinstate? bob (assoc dee :status "suspended")))))


(deftest removed-member-is-not-reinstated
  (is (false? (suspension/can-reinstate? ann (assoc cid :status "removed")))))


;; Reinstate the reviewed suspended membership


(deftest owner-reinstates-an-admin
  (let [bob (assoc bob :status "suspended" :revision 5)]
    (is (= [{:type ::suspension/workspace-member-reinstated
             :data {:membership-id (:membership-id bob) :workspace-id  s/workspace :account-id (:user-id bob)
                    :role          "admin"              :status        "active"    :revision   6
                    :reinstated-by (:user-id ann)       :reinstated-at s/now}}]
           (s/events (reinstate ann bob))))))


(deftest admin-cannot-reinstate-an-owner
  (is (= :not-permitted (s/rejection #(reinstate bob (assoc dee :status "suspended"))))))


(deftest not-suspended
  (is (= :not-suspended (s/rejection #(reinstate ann (assoc cid :status "removed"))))))


(deftest stale-reinstatement-review
  (is (= :changed-since-review
         (s/rejection #(s/decide ::suspension/reinstate-workspace-member ann (assoc cid :status "suspended" :revision 7)
                                 (s/owners ann) {:expected-revision 5})))))
