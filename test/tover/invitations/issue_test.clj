(ns tover.invitations.issue-test
  "Board scenarios for Invite Teammate, decided without a store."
  (:require
    [bel.core-test :as fixture]
    [bel.runtime :as runtime]
    [clojure.test :refer [deftest is use-fixtures]]
    [tover.invitations.fixtures :as invitations]
    [tover.invitations.issue.model :as model])
  (:import
    [java.time Duration Instant]))


(def workspace #uuid "00000000-0000-0000-0000-0000000000f1")
(def actor #uuid "00000000-0000-0000-0000-0000000000a1")
(def invitation-id #uuid "00000000-0000-0000-0000-0000000000c1")
(def delivery-id #uuid "00000000-0000-0000-0000-0000000000d1")
(def now (Instant/parse "2026-11-12T10:00:00Z"))


(use-fixtures :each fixture/isolated-registry invitations/register)


(defn error-data
  {:malli/schema [:=> [:cat [:fn fn?]] [:maybe :map]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (ex-data error))))


(defn decide
  {:malli/schema [:=> [:cat :map :map] :map]}
  [facts input]
  (runtime/decide ::model/invite-teammate
                  (merge {:actor  actor         :now         now         :scope {:workspace-id workspace}
                          :new-id invitation-id :delivery-id delivery-id}
                         facts)
                  input))


(def offer
  {:invitation-id #uuid "00000000-0000-0000-0000-0000000000e1" :role       "member"
   :expires-at    (.plus now (Duration/ofHours 1))             :created-at (.minus now (Duration/ofHours 1))})


(deftest issues-offer-with-queued-delivery
  (let [{:keys [events]} (decide {} {:email "ann@example.test" :role "admin"})]
    (is (= [{:type ::model/workspace-invitation-issued
             :data {:invitation-id   invitation-id :delivery-id delivery-id                 :workspace-id workspace
                    :email           "ann@example.test" :role "admin" :invited-by actor :status "pending"
                    :expires-at      (Instant/parse "2026-11-19T10:00:00Z") :created-at now
                    :requested-by    actor :requested-at now
                    :delivery-status "queued" :attempts 0 :next-attempt-at now}}]
           (mapv #(select-keys % [:type :data]) events)))))


(deftest email-is-normalized
  (is (= "ann@example.com"
         (get-in (decide {} {:email " Ann@Example.COM "}) [:events 0 :data :email]))))


(deftest role-defaults-to-member
  (is (= "member" (get-in (decide {} {:email "ann@example.test"}) [:events 0 :data :role]))))


(deftest owner-role-is-rejected
  (is (= :validation (:type (error-data #(decide {} {:email "ann@example.test" :role "owner"}))))))


(deftest malformed-email-is-rejected
  (is (= :validation (:type (error-data #(decide {} {:email "not-an-email"}))))))


(deftest active-member-is-not-reinvited
  (is (= :already-member (:type (error-data #(decide {:recipient {:status "active"}} {:email "ann@example.test"}))))))


(deftest suspended-member-is-rejected
  (is (= :member-suspended (:type (error-data #(decide {:recipient {:status "suspended"}} {:email "ann@example.test"}))))))


(deftest same-role-repeat-emits-nothing
  (is (= {:events [] :fx []} (decide {:open offer} {:email "ann@example.test" :role "member"}))))


(deftest different-role-reports-existing-offer
  (let [data (error-data #(decide {:open offer} {:email "ann@example.test" :role "admin"}))]
    (is (= :invitation-exists (:type data)))
    (is (= (:invitation-id offer) (:invitation-id data)))))


(deftest expired-offer-is-replaced
  (let [{:keys [events]} (decide {:open (assoc offer :expires-at (.minusSeconds now 1))} {:email "ann@example.test"})]
    (is (= [::model/workspace-invitation-expired ::model/workspace-invitation-issued] (mapv :type events)))
    (is (= {:invitation-id (:invitation-id offer) :status "expired"} (get-in events [0 :data])))))


(defn allowed?
  {:malli/schema [:=> [:cat :keyword [:maybe :map] :uuid] :boolean]}
  [policy principal scope-workspace]
  (true? (runtime/policy policy {:principal principal :scope {:workspace-id scope-workspace}} {})))


(defn principal
  {:malli/schema [:=> [:cat :keyword] :map]}
  [role]
  {:user-id actor :memberships [{:workspace-id workspace :role role}]})


(deftest manager-policy
  (is (allowed? ::model/invitation-manager (principal :owner) workspace))
  (is (allowed? ::model/invitation-manager (principal :admin) workspace))
  (is (not (allowed? ::model/invitation-manager (principal :member) workspace)))
  (is (not (allowed? ::model/invitation-manager (principal :owner) (random-uuid)))))


(deftest reader-policy-admits-managers-and-processors
  (is (allowed? ::model/invitation-reader (principal :admin) workspace))
  (is (not (allowed? ::model/invitation-reader (principal :member) workspace)))
  (is (allowed? ::model/invitation-reader {:service :tover.invitations/deliver} workspace)))
