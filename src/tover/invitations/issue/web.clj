(ns tover.invitations.issue.web
  "Invite Teammate from the Team screen; the result shows the offer at once, without waiting for email."
  (:require
    [bel.core :refer [defaction]]
    [bel.runtime :as runtime]
    [bel.validation :as validation]
    [tover.invitations.issue.model :as model]
    [tover.invitations.status.model :as status]
    [tover.invitations.team.web :as team]))


(defaction invite
  {:schema     (-> team/Command
                   (conj [:email model/Email] [:role model/Role] [:reviewed-removal {:optional true} validation/Instant]))
   :route-data {:tover/access :manager :bel/error-page ::team/team :bel/retain ["email" "role"]}}
  [context input]
  (team/execute! context ::model/invite-teammate input)
  ;; The open offer for this email: the one just issued, or the unchanged one an identical retry found.
  (team/after (:invitation-id (runtime/read-model context ::status/invitation-status {:email (model/normalize (:email input))}))))
