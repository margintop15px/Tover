(ns tover.invitations.cancel.web
  "Cancel on an offer row of the Team screen; the answer shows the actual outcome, also when acceptance won."
  (:require
    [bel.core :refer [defaction]]
    [tover.invitations.cancel.model :as model]
    [tover.invitations.team.web :as team]))


(defaction cancel
  {:schema     (conj team/Command [:invitation-id :uuid])
   :route-data {:tover/access :manager :bel/error-page ::team/team :tover/problems team/problems}}
  [context input]
  (team/command context ::model/cancel-invitation input))
