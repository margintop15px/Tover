(ns tover.memberships.ownership-test
  "Board chapters 38 · Grant workspace ownership and 39 · Transfer workspace ownership: one test per slice example."
  (:require
    [bel.core-test :as fixture]
    [clojure.test :refer [deftest is use-fixtures]]
    [tover.memberships.ownership.model :as ownership]
    [tover.memberships.role.model :as role]
    [tover.memberships.support :as s :refer [ann bob cid dee]]))


(use-fixtures :each fixture/isolated-registry s/register)


(defn grant
  {:malli/schema [:=> [:cat :map :map] :map]}
  [actor target]
  (s/decide ::ownership/grant-workspace-ownership actor target (s/owners ann dee) {}))


(defn transfer
  {:malli/schema [:=> [:cat :map :map] :map]}
  [actor target]
  (s/decide ::ownership/transfer-workspace-ownership actor target (s/owners ann dee)
            {:my-expected-revision (:revision actor)}))


;; Review an additional Owner appointment / Review an ownership handover


(deftest owner-sees-make-owner
  (is (true? (ownership/eligible? ann bob))))


(deftest admin-does-not-see-it
  (is (false? (ownership/eligible? bob cid))))


(deftest suspended-member-is-not-eligible
  (is (false? (ownership/eligible? ann (assoc cid :status "suspended")))))


(deftest not-offered-on-an-owner
  (is (false? (ownership/eligible? ann dee))))


;; Grant ownership to the reviewed active member


(deftest owner-grants-ownership
  (is (= [{:type ::ownership/workspace-ownership-granted
           :data {:membership-id (:membership-id bob) :workspace-id  s/workspace :account-id (:user-id bob)
                  :role          "owner"              :previous-role "admin"     :revision   5
                  :granted-by    (:user-id ann)       :granted-at    s/now}}]
         (s/events (grant ann bob)))))


(deftest already-owner
  (is (= [] (s/events (grant ann dee)))))


(deftest admin-cannot-grant
  (is (= :not-permitted (s/rejection #(grant bob cid)))))


(deftest suspended-target
  (is (= :not-active (s/rejection #(grant ann (assoc cid :status "suspended"))))))


;; Transfer ownership atomically


(deftest owner-hands-over
  (let [cid (assoc cid :revision 2)]
    (is (= [{:type ::ownership/workspace-ownership-transferred
             :data {:membership-id                (:membership-id cid) :workspace-id s/workspace
                    :account-id                   (:user-id cid)       :role         "owner"
                    :previous-role                "member"             :revision     3
                    :previous-owner-membership-id (:membership-id ann)
                    :previous-owner-account-id    (:user-id ann)
                    :previous-owner-revision      2
                    :transferred-at               s/now}}
            {:type ::role/workspace-member-role-changed
             :data {:membership-id (:membership-id ann) :workspace-id  s/workspace :account-id (:user-id ann)
                    :role          "admin"              :previous-role "owner"     :revision   2
                    :changed-by    (:user-id ann)       :changed-at    s/now}}]
           (s/events (transfer ann cid))))))


(deftest target-changed-since-review
  (is (= :changed-since-review
         (s/rejection #(s/decide ::ownership/transfer-workspace-ownership ann (assoc cid :revision 9) (s/owners ann)
                                 {:expected-revision 3 :my-expected-revision 1})))))


(deftest actor-changed-since-review
  (is (= :not-permitted (s/rejection #(transfer (assoc ann :role "admin") cid))))
  (is (= :changed-since-review
         (s/rejection #(s/decide ::ownership/transfer-workspace-ownership ann cid (s/owners ann)
                                 {:my-expected-revision 0})))))


(deftest target-already-owner
  (is (= :already-owner (s/rejection #(transfer ann dee)))))
