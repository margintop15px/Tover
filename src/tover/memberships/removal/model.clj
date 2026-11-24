(ns tover.memberships.removal.model
  "Board chapter 36 · Remove workspace access: a manager ends another person's active or suspended membership.
  The removal time invalidates every earlier offer to them (`tover.invitations.issue.model/invalidated?`)."
  (:require
    [bel.core :refer [defcommand defevent]]
    [bel.validation :as validation]
    [tover.memberships.model :as model]))


(def Removed
  [:map
   [:membership-id :uuid]
   [:workspace-id :uuid]
   [:account-id :uuid]
   [:role model/Role]
   [:previous-status [:enum "active" "suspended"]]
   [:status [:= "removed"]]
   [:removal-kind [:= "removed"]]
   [:revision :int]
   [:removed-by :uuid]
   [:removed-at validation/Instant]
   [:reason {:optional true} :string]])


(defevent workspace-member-removed {:schema Removed})


(defn can-remove?
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :map]] :boolean]}
  [actor target]
  (and (contains? #{"active" "suspended"} (:status target)) (not (model/own? actor target))
       (model/may-manage? actor target)))


(defcommand remove-workspace-member
  {:scope :workspace                    :input model/Target :policy ::model/manager :cofx model/facts
   :emits #{::workspace-member-removed}}
  [{:keys [target own actor now]} {:keys [expected-revision] :as input}]
  (let [reason (model/reason input)]
    (model/check-manager! own target)
    (model/check-not-own! own target)
    (model/check-target! target expected-revision #{"active" "suspended"} model/already-removed)
    {:events [{:type ::workspace-member-removed
               :data (cond-> {:membership-id   (:membership-id target)      :workspace-id (:workspace-id target)
                              :account-id      (:user-id target)            :role         (:role target)
                              :previous-status (:status target)             :status       "removed"              :removal-kind "removed"
                              :revision        (model/next-revision target)
                              :removed-by      actor :removed-at now}
                       reason (assoc :reason reason))}]}))
