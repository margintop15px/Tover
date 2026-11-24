(ns tover.memberships.role.model
  "Board chapter 34 · Change workspace roles: an Owner or Admin sets an active member's role to Admin or
  Member. Owner is given only through chapters 38 and 39."
  (:require
    [bel.core :refer [defcommand defevent]]
    [bel.validation :as validation]
    [tover.memberships.model :as model]))


(def RoleChanged
  [:map
   [:membership-id :uuid]
   [:workspace-id :uuid]
   [:account-id :uuid]
   [:role model/Role]
   [:previous-role model/Role]
   [:revision :int]
   [:changed-by :uuid]
   [:changed-at validation/Instant]
   [:reason {:optional true} :string]])


(defevent workspace-member-role-changed {:schema RoleChanged})


(defn settable-roles
  "Roles manager `actor` may set on `target` here; empty when none."
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :map]] [:vector :string]]}
  [actor target]
  (if (and (model/active? target) (model/may-manage? actor target)) ["admin" "member"] []))


(defn role-changed
  "The event for `membership` taking `role`."
  {:malli/schema [:=> [:cat :map :string :map] :map]}
  [membership role {:keys [actor now reason]}]
  {:type ::workspace-member-role-changed
   :data (cond-> {:membership-id (:membership-id membership) :workspace-id (:workspace-id membership)
                  :account-id    (:user-id membership)       :role         role
                  :previous-role (:role membership)          :revision     (model/next-revision membership)
                  :changed-by    actor :changed-at now}
           reason (assoc :reason reason))})


(def ChangeRole
  (conj model/Target [:role [:enum "admin" "member"]]))


(defcommand change-workspace-member-role
  {:scope :workspace :input ChangeRole :policy ::model/manager :cofx model/facts
   :emits #{::workspace-member-role-changed}}
  [{:keys [target own owners actor now]} {:keys [expected-revision role] :as input}]
  (let [reason (model/reason input)]
    (model/check-manager! own target)
    (model/check-target! target expected-revision #{"active"} model/not-active)
    (cond
      (= role (:role target)) {}
      (and (= "owner" (:role target)) (not (model/keeps-an-owner? owners #{(:user-id target)})))
      (model/fail! :last-owner "The workspace needs at least one active Owner.")
      :else {:events [(role-changed target role {:actor actor :now now :reason reason})]})))
