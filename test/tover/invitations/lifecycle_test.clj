(ns tover.invitations.lifecycle-test
  "Board scenarios for Invite Teammate with removal, Resend (05a), Cancel (05b) and Accept (06), decided
  from given facts without a store."
  (:require
    [bel.core-test :as fixture]
    [bel.runtime :as runtime]
    [clojure.test :refer [deftest is use-fixtures]]
    [tover.invitations.accept.model :as accept]
    [tover.invitations.cancel.model :as cancel]
    [tover.invitations.fixtures :as invitations]
    [tover.invitations.issue.model :as issue]
    [tover.invitations.resend.model :as resend]
    [tover.invitations.team.model :as team])
  (:import
    [java.time Duration Instant]))


(use-fixtures :each fixture/isolated-registry invitations/register)


(def workspace #uuid "00000000-0000-0000-0000-0000000000f1")
(def manager #uuid "00000000-0000-0000-0000-0000000000a1")
(def recipient #uuid "00000000-0000-0000-0000-0000000000b1")
(def invitation-id #uuid "00000000-0000-0000-0000-0000000000c1")
(def delivery-id #uuid "00000000-0000-0000-0000-0000000000d1")
(def membership-id #uuid "00000000-0000-0000-0000-0000000000e1")
(def now (Instant/parse "2026-11-12T10:00:00Z"))


(def offer
  {:invitation-id invitation-id                   :workspace-id workspace :email "ann@example.test" :role "member"
   :invited-by    manager :status "pending" :created-at (.minus now (Duration/ofDays 1))
   :expires-at    (.plus now (Duration/ofDays 6))})


(def last-request
  {:delivery-id  #uuid "00000000-0000-0000-0000-0000000000d0" :invitation-id invitation-id :workspace-id    workspace
   :requested-by manager :requested-at (.minus now (Duration/ofSeconds 60))                :delivery-status "submitted" :attempts 1})


(defn error-data
  {:malli/schema [:=> [:cat [:fn fn?]] [:maybe :map]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (ex-data error))))


(defn events
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [plan]
  (mapv #(select-keys % [:type :data]) (:events plan)))


;; Invite Teammate after a removal


(defn invite
  {:malli/schema [:=> [:cat :map :map] :map]}
  [facts input]
  (runtime/decide ::issue/invite-teammate
                  (merge {:actor manager :now now :scope {:workspace-id workspace} :new-id invitation-id :delivery-id delivery-id}
                         facts)
                  (merge {:email "ann@example.test"} input)))


(def removed-at (.minus now (Duration/ofHours 1)))


(deftest removed-member-needs-a-reviewed-removal
  (is (= :removal-review-required
         (:type (error-data #(invite {:recipient {:status "removed" :removed-at removed-at}} {})))))
  (is (= :removal-review-required
         (:type (error-data #(invite {:recipient {:status "removed" :removed-at removed-at}}
                                     {:reviewed-removal (.minus removed-at (Duration/ofDays 1))}))))
      "a form prepared before a later removal cannot authorize rejoining"))


(deftest a-former-admin-rejoining-as-member-gets-a-fresh-offer
  (let [plan (invite {:recipient {:status "removed" :removed-at removed-at}
                      :open      (assoc offer :invitation-id #uuid "00000000-0000-0000-0000-0000000000c0" :role "admin"
                                        :created-at (.minus removed-at (Duration/ofDays 1)))}
                     {:reviewed-removal removed-at})]
    (is (= [::issue/workspace-invitation-expired ::issue/workspace-invitation-issued] (mapv :type (:events plan)))
        "the pre-removal offer is closed, not reused")
    (is (= "member" (get-in plan [:events 1 :data :role])))))


(deftest two-admins-offer-different-roles-to-the-same-email
  (is (= :invitation-exists (:type (error-data #(invite {:open offer} {:role "admin"}))))))


;; Resend Invitation (05a)


(defn resend
  {:malli/schema [:=> [:cat :map] :map]}
  [facts]
  (runtime/decide ::resend/resend-invitation
                  (merge {:actor    manager      :now       now :scope       {:workspace-id workspace} :invitation offer
                          :delivery last-request :recipient nil :delivery-id delivery-id}
                         facts)
                  {:invitation-id invitation-id}))


(deftest resend-at-59-seconds-rejects-with-the-next-permitted-time
  (let [data (error-data #(resend {:delivery (assoc last-request :requested-at (.minus now (Duration/ofSeconds 59)))}))]
    (is (= :resend-cooldown (:type data)))
    (is (= (.plus now (Duration/ofSeconds 1)) (:next-resend-at data)))))


(deftest resend-at-60-seconds-succeeds-with-a-new-request
  (is (= [{:type ::resend/invitation-resend-requested
           :data {:delivery-id  delivery-id :invitation-id   invitation-id :workspace-id workspace :requested-by    manager
                  :requested-at now         :delivery-status "queued"      :attempts     0         :next-attempt-at now}}]
         (events (resend {})))))


(deftest a-scheduled-automatic-retry-blocks-a-parallel-resend
  (doseq [status ["queued" "retrying" "unconfirmed"]]
    (is (= [] (events (resend {:delivery (assoc last-request :delivery-status status :requested-at (.minus now (Duration/ofHours 1)))})))
        status)))


(deftest two-managers-race
  ;; The second request sees the first one's queued work and adds nothing.
  (is (= [] (events (resend {:delivery (assoc last-request :delivery-status "queued" :attempts 0 :requested-at now)})))))


(deftest a-new-explicit-cycle-follows-exhausted-retries
  (is (= 1 (count (events (resend {:delivery (assoc last-request :delivery-status "exhausted" :attempts 4 :last-problem "temporary")})))))
  (is (= 1 (count (events (resend {:delivery (assoc last-request :delivery-status "stopped" :last-problem "uncertain")}))))
      "a request stopped as Unconfirmed may be followed by one deliberate resend"))


(deftest permanent-address-failure-needs-cancel-and-a-fresh-invitation
  (is (= :permanent-failure
         (:type (error-data #(resend {:delivery (assoc last-request :delivery-status "failed" :last-problem "permanent")}))))))


(deftest expiry-wins-before-resend
  (is (= :invitation-not-pending (:type (error-data #(resend {:invitation (assoc offer :expires-at now)}))))))


(deftest resend-rejects-terminal-or-foreign-offers
  (doseq [status ["revoked" "accepted" "expired"]]
    (is (= :invitation-not-pending (:type (error-data #(resend {:invitation (assoc offer :status status)}))))))
  (is (= :invitation-changed (:type (error-data #(resend {:invitation (assoc offer :workspace-id (random-uuid))})))))
  (is (= :invitation-changed (:type (error-data #(resend {:invitation nil}))))))


(deftest resend-rechecks-membership-and-removal
  (is (= :already-member (:type (error-data #(resend {:recipient {:status "active"}})))))
  (is (= :member-suspended (:type (error-data #(resend {:recipient {:status "suspended"}})))))
  (is (= :invitation-invalidated
         (:type (error-data #(resend {:recipient {:status "removed" :removed-at (.minus now (Duration/ofHours 1))}}))))))


;; Cancel Invitation (05b)


(defn cancel
  {:malli/schema [:=> [:cat :map] :map]}
  [facts]
  (runtime/decide ::cancel/cancel-invitation
                  (merge {:actor manager :now now :scope {:workspace-id workspace} :invitation offer} facts)
                  {:invitation-id invitation-id}))


(deftest cancel-withdraws-the-pending-offer
  (is (= [{:type ::cancel/workspace-invitation-cancelled :data {:invitation-id invitation-id :status "revoked"}}]
         (events (cancel {})))))


(deftest cancel-races-accept
  (is (= [] (events (cancel {:invitation (assoc offer :status "accepted")})))
      "acceptance won: no cancellation fact, membership untouched"))


(deftest cancellation-succeeds-but-the-response-is-lost
  (is (= [] (events (cancel {:invitation (assoc offer :status "revoked")})))
      "a retry finds the offer already cancelled and records nothing again"))


(deftest a-new-offer-is-issued-before-an-old-cancellation-retry-arrives
  ;; The retry names the old offer, which the new issuance closed; the replacement keeps its own identity.
  (is (= [] (events (cancel {:invitation (assoc offer :status "expired")}))))
  (is (= [] (events (cancel {:invitation (assoc offer :expires-at now)})))))


(deftest cancel-cannot-target-another-workspace
  (is (= :invitation-changed (:type (error-data #(cancel {:invitation (assoc offer :workspace-id (random-uuid))}))))))


;; Accept Workspace Invitation (06)


(def received
  (assoc offer :workspace-name "Shop" :inviter "Olga Owner"))


(defn accept
  {:malli/schema [:=> [:cat :map [:? :map]] :map]}
  ([facts] (accept facts {}))
  ([facts input]
   (runtime/decide ::accept/accept-workspace-invitation
                   (merge {:actor      recipient :now        now :scope         {:user-id recipient} :profile {:user-id recipient :display-name "Ann"}
                           :invitation received  :membership nil :membership-id membership-id}
                          facts)
                   (merge {:invitation-id invitation-id :workspace-id workspace :role "member"} input))))


(deftest accepting-grants-the-offered-role-together
  (is (= [{:type ::accept/workspace-invitation-accepted
           :data {:invitation-id invitation-id :status "accepted" :workspace-id workspace :user-id recipient :role "member"}}
          {:type ::accept/workspace-member-joined
           :data {:membership-id membership-id :workspace-id workspace :user-id recipient :role "member" :status "active" :revision 1}}]
         (events (accept {})))))


(deftest cancellation-during-accept
  (is (= {:type :invitation-not-pending :status "revoked"}
         (select-keys (error-data #(accept {:invitation (assoc received :status "revoked")})) [:type :status]))))


(deftest expiry-while-setup-is-being-completed
  (is (= "expired" (:status (error-data #(accept {:invitation (assoc received :expires-at now)}))))))


(deftest email-or-account-changes-after-review
  ;; Another account (or a changed verified email) does not see the offer at all.
  (is (= :invitation-unavailable (:type (error-data #(accept {:invitation nil}))))))


(deftest a-role-replaced-by-a-new-offer
  (is (= :invitation-changed (:type (error-data #(accept {:invitation (assoc received :role "admin")}))))))


(deftest two-accepts
  ;; The second accept finds the person already a member: no repeated facts, role unchanged.
  (is (= [] (events (accept {:invitation (assoc received :status "accepted")
                             :membership {:membership-id membership-id :role "member" :status "active"}})))))


(deftest removal-after-a-successful-acceptance
  (let [membership {:membership-id membership-id :role "member" :status "removed" :removed-at now}]
    (is (= "accepted" (:status (error-data #(accept {:invitation (assoc received :status "accepted") :membership membership}))))
        "an old accepted offer never grants access again")))


(deftest a-former-admin-rejoining-as-member
  (let [membership {:membership-id membership-id :role "admin" :status "removed"
                    :removed-at    (.minus (:created-at received) (Duration/ofHours 1))}]
    (is (= {:membership-id membership-id :workspace-id workspace :user-id recipient :role "member" :status "active" :revision 1}
           (get-in (accept {:membership membership}) [:events 1 :data]))
        "the existing membership row is reused with exactly the offered role")))


(deftest a-pre-removal-link-arriving-late
  (let [membership {:membership-id membership-id :role "member" :status "removed" :removed-at now}]
    (is (= "invalidated" (:status (error-data #(accept {:membership membership})))))))


(deftest a-suspended-member-attempting-to-use-an-invitation
  (is (= :member-suspended (:type (error-data #(accept {:membership {:membership-id membership-id :role "member" :status "suspended"}}))))))


(deftest profile-must-be-complete
  (is (= :profile-incomplete (:type (error-data #(accept {:profile {:user-id recipient}}))))))


(deftest recipient-policy-needs-an-ordinary-sign-in
  (let [allowed? #(true? (runtime/policy ::accept/recipient {:principal % :scope {:user-id recipient}} {}))]
    (is (allowed? {:user-id recipient :confirmed? true :password-required? false}))
    (is (not (allowed? {:user-id recipient :confirmed? false :password-required? false})))
    (is (not (allowed? {:user-id recipient :confirmed? true :password-required? true})))
    (is (not (allowed? {:user-id manager :confirmed? true :password-required? false})))))


;; Workspace Team


(deftest team-shows-the-latest-offer-per-recipient-with-separate-delivery
  (let [older                 {:invitation-id (random-uuid)                    :workspace-id workspace :email "ANN@example.test" :role "admin" :status "revoked"
                               :created-at    (.minus now (Duration/ofDays 3)) :expires-at   (.plus now (Duration/ofDays 4))}
        {:keys [invitations]} (team/team {:members [] :invitations [older offer] :deliveries [last-request]} nil now)]
    (is (= [{:invitation-id invitation-id       :email       "ann@example.test" :role        "member" :state "pending"
             :expires-at    (:expires-at offer) :can-resend? true               :can-cancel? true}]
           (mapv #(dissoc % :delivery) invitations)))
    (is (= "submitted" (get-in invitations [0 :delivery :status])))))


(deftest removal-invalidates-earlier-pending-offers-in-the-team-view
  (let [member                {:membership-id membership-id :user-id recipient :email "ann@example.test" :role "admin" :status "removed" :removed-at now}
        {:keys [invitations]} (team/team {:members [member] :invitations [offer] :deliveries []} nil now)]
    (is (= {:state "invalidated" :can-resend? false :can-cancel? false}
           (select-keys (first invitations) [:state :can-resend? :can-cancel?])))))


(deftest an-offer-delivered-after-removal-cannot-grant-access
  ;; The queued email may still go out; its link never grants access to a person removed after the offer.
  (is (= {:type :invitation-not-pending :status "invalidated"}
         (select-keys (error-data #(accept {:membership {:membership-id membership-id :role "member" :status "removed"
                                                         :removed-at    (.plusSeconds ^java.time.Instant (:created-at received) 1)}}))
                      [:type :status]))))
