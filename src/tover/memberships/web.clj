(ns tover.memberships.web
  "Team page actions on a member row (board chapters 34–36, 38, 39). Each returns a command descriptor through
  `tover.invitations.team.web/command` and comes back to the Team page naming what the attempt did; a manager who
  changed their own role goes Home, because the Team page may no longer be theirs."
  (:require
    [bel.core :refer [defaction]]
    [tover.invitations.team.web :as team]
    [tover.memberships.ownership.model :as ownership]
    [tover.memberships.removal.model :as removal]
    [tover.memberships.role.model :as role]
    [tover.memberships.suspension.model :as suspension]))


(def Row
  (into team/Command [[:membership-id :uuid] [:expected-revision :int] [:reason {:optional true} [:maybe :string]]]))


(def ^:private route-data
  {:tover/access :manager :bel/error-page ::team/team :tover/problems team/problems})


(defn- member-command
  "The row command; success shows the Team page with `done` for this member."
  {:malli/schema [:=> [:cat :map :qualified-keyword :map :string] :map]}
  [context id input done]
  (assoc (team/command context id (dissoc input :own))
         :then {:type :page :id ::team/team :input {:member (str (:membership-id input)) :done done}}))


(defaction change-role
  {:schema     (conj Row [:role [:enum "admin" "member"]] [:own {:optional true} :boolean])
   :route-data route-data}
  [context input]
  (cond-> (member-command context ::role/change-workspace-member-role input "role-changed")
    (:own input) (assoc :then {:type :page :id :tover.inventory.web/page})))


(defaction suspend
  {:schema Row :route-data route-data}
  [context input]
  (member-command context ::suspension/suspend-workspace-member input "suspended"))


(defaction reinstate
  {:schema Row :route-data route-data}
  [context input]
  (member-command context ::suspension/reinstate-workspace-member input "reinstated"))


(defaction remove-member
  {:schema Row :route-data route-data}
  [context input]
  (member-command context ::removal/remove-workspace-member input "removed"))


(defaction grant-ownership
  {:schema Row :route-data route-data}
  [context input]
  (member-command context ::ownership/grant-workspace-ownership input "ownership-granted"))


(defaction transfer-ownership
  {:schema (conj Row [:my-expected-revision :int]) :route-data route-data}
  [context input]
  (member-command context ::ownership/transfer-workspace-ownership input "ownership-transferred"))
