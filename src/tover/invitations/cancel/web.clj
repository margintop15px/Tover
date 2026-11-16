(ns tover.invitations.cancel.web
  "Cancel on an offer row of the Team screen; the answer shows the actual outcome, also when acceptance won."
  (:require
    [bel.core :refer [defaction]]
    [bel.runtime :as runtime]
    [tover.invitations.cancel.model :as model]
    [tover.invitations.issue.model :as issue]
    [tover.invitations.team.web :as team]))


(defaction cancel
  {:schema     (conj team/Command [:invitation-id :uuid])
   :route-data {:tover/access :manager :bel/error-page ::team/team}}
  [context input]
  (team/execute! context ::model/cancel-invitation input)
  (team/after (:email (runtime/read-model context ::issue/invitations (select-keys input [:invitation-id])))))
