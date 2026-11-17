(ns tover.invitations.issue.web
  "Invite Teammate from the Team screen; the result shows the offer at once, without waiting for email."
  (:require
    [bel.core :refer [defaction]]
    [bel.validation :as validation]
    [tover.invitations.issue.model :as model]
    [tover.invitations.team.web :as team]))


(defaction invite
  {:schema     (-> team/Command
                   (conj [:invitation-id :uuid] [:email model/Email] [:role model/Role]
                         [:reviewed-removal {:optional true} validation/Instant]))
   :route-data {:tover/access   :manager      :bel/error-page ::team/team :bel/retain ["email" "role"]
                :tover/problems team/problems}}
  [context input]
  (team/command context ::model/invite-teammate input))
