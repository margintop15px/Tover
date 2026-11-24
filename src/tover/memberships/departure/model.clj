(ns tover.memberships.departure.model
  "Board chapter 37 · Leave a workspace: an active member ends their own membership. The membership ends in
  the same removed state as a removal, so rejoining uses the same invitation path."
  (:require
    [bel.core :refer [defcommand defevent defreadmodel]]
    [bel.validation :as validation]
    [tover.memberships.model :as model]))


(def Left
  [:map
   [:membership-id :uuid]
   [:workspace-id :uuid]
   [:account-id :uuid]
   [:role model/Role]
   [:status [:= "removed"]]
   [:removal-kind [:= "left"]]
   [:revision :int]
   [:removed-at validation/Instant]
   [:reason {:optional true} :string]])


(defevent workspace-member-left {:schema Left})


(defn last-owner?
  {:malli/schema [:=> [:cat [:maybe :map] [:sequential :uuid]] :boolean]}
  [own owners]
  (and (model/owner? own) (not (model/keeps-an-owner? owners #{(:user-id own)}))))


(def own-facts
  [[:own :tover.memberships.persistence/own (constantly {})]
   [:owners :tover.memberships.persistence/active-owners (constantly {})]])


(def LeaveWorkspace
  [:map [:expected-revision :int] [:reason {:optional true} [:maybe :string]]])


(defcommand leave-workspace
  {:scope :workspace                 :input LeaveWorkspace :policy ::model/member :cofx own-facts
   :emits #{::workspace-member-left}}
  [{:keys [own owners now]} {:keys [expected-revision] :as input}]
  (let [reason (model/reason input)]
    (when-not (model/active? own)
      (model/fail! :not-a-member "You are not an active member of this workspace."))
    (when-not (= expected-revision (:revision own))
      (model/fail! :changed-since-review "Your membership changed. Reload the page."))
    (when (last-owner? own owners)
      (model/fail! :last-owner "You are the only active Owner. Grant or transfer ownership first."))
    {:events [{:type ::workspace-member-left
               :data (cond-> {:membership-id (:membership-id own)      :workspace-id (:workspace-id own)
                              :account-id    (:user-id own)            :role         (:role own)
                              :status        "removed"                 :removal-kind "left"
                              :revision      (model/next-revision own)
                              :removed-at    now}
                       reason (assoc :reason reason))}]}))


(def Eligibility
  [:map
   [:role model/Role]
   [:revision :int]
   [:can-leave? :boolean]
   [:blocked-by-last-owner? :boolean]])


(defreadmodel departure-eligibility
  {:scope :workspace :input [:map] :output Eligibility :policy ::model/member :cofx own-facts}
  [{:keys [own owners]} _]
  (let [blocked? (last-owner? own owners)]
    {:role (:role own) :revision (:revision own) :can-leave? (not blocked?) :blocked-by-last-owner? blocked?}))
