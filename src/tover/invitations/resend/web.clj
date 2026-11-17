(ns tover.invitations.resend.web
  "Resend on an offer row of the Team screen; the answer is that offer's current request and delivery state."
  (:require
    [bel.core :refer [defaction]]
    [tover.invitations.resend.model :as model]
    [tover.invitations.team.web :as team]))


(defaction resend
  {:schema     (conj team/Command [:invitation-id :uuid])
   :route-data {:tover/access :manager :bel/error-page ::team/team :tover/problems team/problems}}
  [context input]
  (team/command context ::model/resend-invitation input))
