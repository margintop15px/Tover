(ns tover.invitations.cancel.model
  "Cancel Invitation: withdraw the exact pending offer. Membership and replacement offers are never touched."
  (:require
    [bel.core :refer [defcommand defevent]]
    [tover.invitations.issue.model :as issue]
    [tover.invitations.resend.model :as resend]
    [tover.invitations.status.model :as status]))


(defevent workspace-invitation-cancelled
  {:schema [:map [:invitation-id :uuid] [:status [:= "revoked"]]]})


(defcommand cancel-invitation
  {:scope :workspace :policy ::issue/invitation-manager
   :input [:map [:invitation-id :uuid]]
   :cofx  [[:invitation ::issue/invitations (fn [_ input] (select-keys input [:invitation-id]))]]
   :emits #{::workspace-invitation-cancelled}}
  [{:keys [now scope invitation]} {:keys [invitation-id]}]
  ;; Accepted, expired or already cancelled offers keep their state; the view shows what actually happened.
  (if (= "pending" (status/effective-status (resend/offer! invitation scope) now))
    {:events [{:type ::workspace-invitation-cancelled :data {:invitation-id invitation-id :status "revoked"}}]}
    {}))
