(ns tover.memberships.role-test
  "Board chapter 34 · Change workspace roles: one test per slice example."
  (:require
    [bel.core-test :as fixture]
    [clojure.test :refer [deftest is use-fixtures]]
    [tover.memberships.role.model :as role]
    [tover.memberships.support :as s :refer [ann bob cid dee]]))


(use-fixtures :each fixture/isolated-registry s/register)


(defn change
  {:malli/schema [:=> [:cat :map :map [:sequential :uuid] :string] :map]}
  [actor target owners new-role]
  (s/decide ::role/change-workspace-member-role actor target owners {:role new-role}))


;; Review a member's role and permitted changes


(deftest owner-sees-settable-roles
  (is (= ["admin" "member"] (role/settable-roles ann bob))))


(deftest admin-cannot-change-an-owner
  (is (= [] (role/settable-roles bob ann))))


(deftest suspended-member-has-no-role-change
  (is (= [] (role/settable-roles ann (assoc cid :status "suspended")))))


;; Change the reviewed membership role


(deftest owner-promotes-a-member
  (is (= [{:type ::role/workspace-member-role-changed
           :data {:membership-id (:membership-id cid) :workspace-id  s/workspace :account-id (:user-id cid)
                  :role          "admin"              :previous-role "member"    :revision   4
                  :changed-by    (:user-id ann)       :changed-at    s/now}}]
         (s/events (change ann cid (s/owners ann) "admin")))))


(deftest owner-demotes-another-owner
  (is (= "admin" (get-in (s/events (change ann dee (s/owners ann dee) "admin")) [0 :data :role]))))


(deftest last-active-owner-cannot-demote-themselves
  ;; Dee is a suspended Owner, so only Ann counts.
  (is (= :last-owner (s/rejection #(change ann ann (s/owners ann) "admin")))))


(deftest admin-cannot-target-an-owner
  (is (= :not-permitted (s/rejection #(change bob ann (s/owners ann) "member")))))


(deftest stale-review
  (is (= :changed-since-review
         (s/rejection #(s/decide ::role/change-workspace-member-role ann (assoc cid :revision 5) (s/owners ann)
                                 {:role "admin" :expected-revision 3})))))


(deftest same-role-changes-nothing
  (is (= [] (s/events (change ann (assoc cid :role "admin") (s/owners ann) "admin")))))


(deftest admin-demotes-themselves
  (is (= "member" (get-in (s/events (change bob bob (s/owners ann) "member")) [0 :data :role]))))


(deftest reason-is-recorded
  (is (= "promotion" (get-in (s/events (s/decide ::role/change-workspace-member-role ann cid (s/owners ann)
                                                 {:role "admin" :reason " promotion "}))
                             [0 :data :reason]))))


(deftest suspended-actor-is-refused
  (is (= :not-permitted (s/rejection #(change (assoc bob :status "suspended") cid (s/owners ann) "admin")))))
