(ns tover.memberships.departure-test
  "Board chapter 37 · Leave a workspace: one test per slice example of the command and eligibility."
  (:require
    [bel.core-test :as fixture]
    [clojure.test :refer [deftest is use-fixtures]]
    [tover.memberships.departure.model :as departure]
    [tover.memberships.support :as s :refer [ann cid dee]]))


(use-fixtures :each fixture/isolated-registry s/register)


(defn leave
  {:malli/schema [:=> [:cat :map [:sequential :uuid]] :map]}
  [actor owners]
  (s/decide ::departure/leave-workspace actor nil owners {:expected-revision (:revision actor)}))


;; Review leaving the current workspace


(deftest member-sees-leave
  (is (false? (departure/last-owner? cid (s/owners ann)))))


(deftest last-active-owner-is-blocked
  (is (true? (departure/last-owner? ann (s/owners ann)))))


(deftest owner-with-a-co-owner-may-leave
  (is (false? (departure/last-owner? ann (s/owners ann dee)))))


;; End the person's own membership


(deftest member-leaves
  (let [cid (assoc cid :revision 2)]
    (is (= [{:type ::departure/workspace-member-left
             :data {:membership-id (:membership-id cid) :workspace-id s/workspace :account-id   (:user-id cid)
                    :role          "member"             :status       "removed"   :removal-kind "left"
                    :revision      3                    :removed-at   s/now}}]
           (s/events (leave cid (s/owners ann)))))))


(deftest owner-leaves-while-a-co-owner-remains
  (is (= "owner" (get-in (s/events (leave ann (s/owners ann dee))) [0 :data :role]))))


(deftest last-active-owner-cannot-leave
  (is (= :last-owner (s/rejection #(leave ann (s/owners ann))))))


(deftest concurrent-co-owner-departure
  ;; Ann's departure committed first; Dee's command, rerun under the owner lock, sees only herself.
  (is (seq (s/events (leave ann (s/owners ann dee)))))
  (is (= :last-owner (s/rejection #(leave dee (s/owners dee))))))


(deftest suspended-person-cannot-leave
  (is (= :not-a-member (s/rejection #(leave (assoc cid :status "suspended") (s/owners ann))))))


(deftest stale-departure-review
  (is (= :changed-since-review
         (s/rejection #(s/decide ::departure/leave-workspace cid nil (s/owners ann) {:expected-revision 1})))))
