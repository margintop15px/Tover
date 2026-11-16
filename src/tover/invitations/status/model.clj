(ns tover.invitations.status.model
  "Invitation Status: the latest offer for a recipient and its delivery, read with current permission."
  (:require
    [bel.core :refer [defreadmodel]]
    [bel.validation :as validation]
    [tover.invitations.issue.model :as issue])
  (:import
    [java.time Duration Instant]))


(def attempt-budget 4)


(def resend-cooldown (Duration/ofSeconds 60))


(def StatusQuery
  [:map [:email issue/Email]])


(def Delivery
  "Board vocabulary. `reconcile-by` is an Unconfirmed attempt's 60-second deadline; `active?` means queued,
  sending or scheduled work exists, so no parallel resend may start."
  [:map
   [:status [:enum "queued" "submitted" "failed" "unconfirmed" "stopped" "stopped-unconfirmed"]]
   [:problem {:optional true} [:maybe [:enum "temporary" "permanent" "uncertain"]]]
   [:attempts-remaining :int]
   [:next-attempt-at [:maybe validation/Instant]]
   [:reconcile-by {:optional true} [:maybe validation/Instant]]
   [:active? {:optional true} :boolean]
   [:next-resend-at validation/Instant]])


(def Status
  [:maybe
   [:map
    [:invitation-id :uuid]
    [:email :string]
    [:role :string]
    [:status [:enum "pending" "expired" "accepted" "revoked"]]
    [:expires-at validation/Instant]
    [:delivery {:optional true} Delivery]]])


(def ^:private delivery-statuses
  {"queued"      "queued"
   "retrying"    "failed"
   "exhausted"   "failed"
   "failed"      "failed"
   "unconfirmed" "unconfirmed"
   "submitted"   "submitted"
   "stopped"     "stopped"})


(defn delivery-status
  "Board vocabulary: retries and exhaustion read as Failed, with remaining attempts shown separately; a request
  stopped after Unconfirmed reconciliation reads Stopped — Unconfirmed."
  {:malli/schema [:=> [:cat :map] Delivery]}
  [{:keys [delivery-status attempts next-attempt-at requested-at last-problem]}]
  {:status             (if (and (= "stopped" delivery-status) (= "uncertain" last-problem))
                         "stopped-unconfirmed"
                         (delivery-statuses delivery-status))
   :problem            last-problem
   :attempts-remaining (- attempt-budget attempts)
   :next-attempt-at    (when (= "retrying" delivery-status) next-attempt-at)
   :reconcile-by       (when (= "unconfirmed" delivery-status) next-attempt-at)
   :active?            (contains? #{"queued" "retrying" "unconfirmed"} delivery-status)
   :next-resend-at     (.plus ^Instant requested-at resend-cooldown)})


(defn effective-status
  {:malli/schema [:=> [:cat :map validation/Instant] :string]}
  [{:keys [status expires-at]} now]
  (if (and (= "pending" status) (not (.isAfter ^Instant expires-at now)))
    "expired"
    status))


(defreadmodel invitation-status
  {:input  StatusQuery
   :output Status
   :policy ::issue/invitation-manager
   :cofx   [[:invitation :tover.invitations.persistence/latest-invitation (fn [_ input] (select-keys input [:email]))]
            [:delivery :tover.invitations.persistence/latest-delivery
             (fn [{:keys [invitation]} _] {:invitation-id (:invitation-id invitation)})]
            [:clock :bel/now]]}
  [{:keys [invitation delivery clock]} _]
  (when invitation
    (cond-> (-> (select-keys invitation [:invitation-id :email :role :expires-at])
                (assoc :status (effective-status invitation clock)))
      delivery (assoc :delivery (delivery-status delivery)))))
