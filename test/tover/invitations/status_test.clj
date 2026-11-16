(ns tover.invitations.status-test
  "Invitation Status scenarios, read from given facts without a store."
  (:require
    [bel.core :as bel]
    [bel.core-test :as fixture]
    [clojure.test :refer [deftest is use-fixtures]]
    [tover.invitations.fixtures :as invitations]
    [tover.invitations.status.model :as model])
  (:import
    [java.time Duration Instant]))


(def now (Instant/parse "2026-11-12T10:00:00Z"))


(def invitation
  {:invitation-id #uuid "00000000-0000-0000-0000-0000000000c1"
   :email         "ann@example.test"
   :role          "member"
   :status        "pending"
   :expires-at    (.plus now (Duration/ofHours 100))})


(defn delivery
  {:malli/schema [:=> [:cat :map] :map]}
  [fields]
  (merge {:delivery-id     #uuid "00000000-0000-0000-0000-0000000000d1"
          :invitation-id   (:invitation-id invitation)
          :requested-at    (.minus now (Duration/ofSeconds 20))
          :delivery-status "queued"
          :attempts        0}
         fields))


(use-fixtures :each fixture/isolated-registry invitations/register)


(defn status
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :map]] :any]}
  [invitation delivery]
  ((:handler (get-in (bel/operations) [:read-models ::model/invitation-status]))
   {:invitation invitation :delivery delivery :clock now} {:email "ann@example.test"}))


(deftest pending-shows-queued
  (is (= {:invitation-id (:invitation-id invitation) :email "ann@example.test"       :role "member"
          :status        "pending" :expires-at              (:expires-at invitation)
          :delivery      {:status          "queued" :attempts-remaining 4   :problem        nil :active? true
                          :next-attempt-at nil      :reconcile-by       nil :next-resend-at (.plus now (Duration/ofSeconds 40))}}
         (status invitation (delivery {})))))


(deftest retrying-shows-failed-with-next-attempt
  (let [next (.plus now (Duration/ofMinutes 1))]
    (is (= {:status  "failed" :attempts-remaining 3   :next-attempt-at next :next-resend-at (.plus now (Duration/ofSeconds 40))
            :problem nil      :reconcile-by       nil :active?         true}
           (:delivery (status invitation (delivery {:delivery-status "retrying" :attempts 1 :next-attempt-at next})))))))


(deftest exhausted-has-zero-remaining
  (is (= {:status "failed" :attempts-remaining 0}
         (select-keys (:delivery (status invitation (delivery {:delivery-status "exhausted" :attempts 4})))
                      [:status :attempts-remaining]))))


(deftest unconfirmed-and-submitted-keep-their-names
  (is (= "unconfirmed" (get-in (status invitation (delivery {:delivery-status "unconfirmed" :attempts 1})) [:delivery :status])))
  (is (= "submitted" (get-in (status invitation (delivery {:delivery-status "submitted" :attempts 1})) [:delivery :status]))))


(deftest past-deadline-reads-expired
  (is (= "expired" (:status (status (assoc invitation :expires-at now) nil))))
  (is (= "revoked" (:status (status (assoc invitation :status "revoked" :expires-at now) nil)))))


(deftest resend-time-is-60s-after-request
  (let [requested (.minus now (Duration/ofSeconds 5))]
    (is (= (.plus requested (Duration/ofSeconds 60))
           (get-in (status invitation (delivery {:requested-at requested})) [:delivery :next-resend-at])))))


(deftest no-invitation-is-nil
  (is (nil? (status nil nil))))


(deftest unconfirmed-shows-its-reconciliation-deadline
  (let [deadline (.plus now (Duration/ofSeconds 30))]
    (is (= {:status "unconfirmed" :reconcile-by deadline :active? true}
           (select-keys (:delivery (status invitation (delivery {:delivery-status "unconfirmed" :attempts 1 :next-attempt-at deadline})))
                        [:status :reconcile-by :active?])))))


(deftest stopped-after-reconciliation-reads-stopped-unconfirmed
  (is (= {:status "stopped-unconfirmed" :active? false :problem "uncertain"}
         (select-keys (:delivery (status invitation (delivery {:delivery-status "stopped" :attempts 1 :last-problem "uncertain"})))
                      [:status :active? :problem])))
  (is (= "stopped" (get-in (status invitation (delivery {:delivery-status "stopped" :attempts 0})) [:delivery :status]))))
