(ns tover.memberships.removal-test
  "Board chapter 36 · Remove workspace access: one test per slice example."
  (:require
    [bel.core-test :as fixture]
    [bel.runtime :as runtime]
    [clojure.test :refer [deftest is use-fixtures]]
    [tover.invitations.accept.model :as accept]
    [tover.memberships.removal.model :as removal]
    [tover.memberships.support :as s :refer [ann bob cid]])
  (:import
    [java.time Duration]))


(use-fixtures :each fixture/isolated-registry s/register)


(defn remove-member
  {:malli/schema [:=> [:cat :map [:maybe :map]] :map]}
  [actor target]
  (s/decide ::removal/remove-workspace-member actor target (s/owners ann) {}))


;; Review a membership for removal


(deftest owner-sees-remove-on-a-suspended-member
  (is (true? (removal/can-remove? ann (assoc cid :status "suspended")))))


(deftest no-remove-on-own-row
  (is (false? (removal/can-remove? bob bob))))


(deftest admin-cannot-remove-an-owner-row
  (is (false? (removal/can-remove? bob ann))))


;; Remove the reviewed membership


(deftest owner-removes-an-admin
  (is (= [{:type ::removal/workspace-member-removed
           :data {:membership-id (:membership-id bob) :workspace-id    s/workspace :account-id (:user-id bob)
                  :role          "admin"              :previous-status "active"    :status     "removed"
                  :removal-kind  "removed"            :revision        5
                  :removed-by    (:user-id ann)       :removed-at      s/now}}]
         (s/events (remove-member ann bob)))))


(deftest removing-a-suspended-member
  (is (= "suspended" (get-in (s/events (remove-member ann (assoc cid :status "suspended"))) [0 :data :previous-status]))))


(deftest old-invitation-cannot-bring-them-back
  (let [removed (get-in (s/events (remove-member ann bob)) [0 :data])
        offer   {:invitation-id  #uuid "00000000-0000-0000-0000-0000000003c1" :workspace-id s/workspace
                 :workspace-name "W" :email "bob@example.test" :role "admin" :status "pending"
                 :created-at     (.minus s/now (Duration/ofDays 1))           :expires-at   (.plus s/now (Duration/ofDays 6))}]
    (is (= {:type :invitation-not-pending :status "invalidated"}
           (try (runtime/decide ::accept/accept-workspace-invitation
                                {:actor         (:user-id bob) :now               (.plus s/now (Duration/ofMinutes 1))
                                 :profile       {:display-name "Bob"} :invitation offer
                                 :membership    {:membership-id (:membership-id bob) :role       "admin"               :status "removed"
                                                 :revision      5                    :removed-at (:removed-at removed)}
                                 :membership-id (:membership-id bob)}
                                {:invitation-id (:invitation-id offer) :workspace-id s/workspace :role "admin"})
                nil
                (catch clojure.lang.ExceptionInfo error (select-keys (ex-data error) [:type :status])))))))


(deftest cannot-remove-yourself
  (is (= :own-membership (s/rejection #(remove-member bob bob)))))


(deftest admin-cannot-remove-an-owner
  (is (= :not-permitted (s/rejection #(remove-member bob ann)))))


(deftest already-removed
  (is (= :already-removed (s/rejection #(remove-member ann (assoc cid :status "removed"))))))
