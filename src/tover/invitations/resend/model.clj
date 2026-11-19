(ns tover.invitations.resend.model
  "Resend Invitation: another bounded delivery request for the same pending offer, link and expiry."
  (:require
    [bel.core :refer [defcommand defevent]]
    [bel.validation :as validation]
    [tover.invitations.issue.model :as issue]
    [tover.invitations.status.model :as status])
  (:import
    [java.time Instant]))


(defevent invitation-resend-requested
  {:schema [:map
            [:delivery-id :uuid]
            [:invitation-id :uuid]
            [:workspace-id :uuid]
            [:requested-by :uuid]
            [:requested-at validation/Instant]
            [:delivery-status [:= "queued"]]
            [:attempts [:= 0]]
            [:next-attempt-at validation/Instant]]})


(defn offer!
  "The exact offer in this workspace, or a rejection the manager can act on."
  {:malli/schema [:=> [:cat [:maybe :map] :map] [:map [:created-at validation/Instant]]]}
  [invitation scope]
  (when-not (= (:workspace-id scope) (:workspace-id invitation))
    (throw (ex-info "This invitation changed. Reload the page." {:type :invitation-changed})))
  invitation)


(defcommand resend-invitation
  {:scope :workspace :policy ::issue/invitation-manager
   :input [:map [:invitation-id :uuid]]
   :cofx  [[:invitation ::issue/invitations (fn [_ input] (select-keys input [:invitation-id]))]
           [:delivery :tover.invitations.persistence/latest-delivery (fn [_ input] (select-keys input [:invitation-id]))]
           [:recipient :tover.invitations.persistence/recipient-access
            (fn [{:keys [invitation]} _] {:email (or (:email invitation) "")})]
           [:delivery-id :bel/uuid]]
   :emits #{::invitation-resend-requested}}
  [{:keys [actor now scope invitation delivery recipient delivery-id]} {:keys [invitation-id]}]
  (let [offer   (offer! invitation scope)
        current (some-> delivery status/delivery-status)]
    (issue/check-recipient! recipient)
    (when (issue/invalidated? recipient offer)
      (throw (ex-info "This invitation was invalidated by a later removal. Send a new invitation."
                      {:type :invitation-invalidated})))
    (when-not (= "pending" (status/effective-status offer now))
      (throw (ex-info "Only a pending invitation can be resent." {:type :invitation-not-pending})))
    (cond
      ;; Queued, sending or scheduled work already handles this offer: report it instead of a parallel request.
      (:active? current) {}

      (= "permanent" (:problem current))
      (throw (ex-info "The address was rejected. Check it, then cancel and invite again." {:type :permanent-failure}))

      (and current (.isBefore ^Instant now (:next-resend-at current)))
      (throw (ex-info "Wait 60 seconds after the last request before resending." {:type :resend-cooldown :next-resend-at (:next-resend-at current)}))

      :else
      {:events [{:type ::invitation-resend-requested
                 :data {:delivery-id     delivery-id :invitation-id invitation-id :workspace-id    (:workspace-id scope)
                        :requested-by    actor       :requested-at  now           :delivery-status "queued"              :attempts 0
                        :next-attempt-at now}}]})))
