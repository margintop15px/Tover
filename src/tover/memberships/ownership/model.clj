(ns tover.memberships.ownership.model
  "Board chapters 38 · Grant workspace ownership and 39 · Transfer workspace ownership. Only an active Owner
  acts, on another active Admin or Member. A transfer also makes the acting Owner Admin in the same commit;
  the target is promoted first so the row policies still see an Owner acting."
  (:require
    [bel.core :refer [defcommand defevent]]
    [bel.validation :as validation]
    [tover.memberships.model :as model]
    [tover.memberships.role.model :as role]))


(def Granted
  [:map
   [:membership-id :uuid]
   [:workspace-id :uuid]
   [:account-id :uuid]
   [:role [:= "owner"]]
   [:previous-role [:enum "admin" "member"]]
   [:revision :int]
   [:granted-by :uuid]
   [:granted-at validation/Instant]
   [:reason {:optional true} :string]])


(def Transferred
  "`membership-id`, `role` and `revision` are the new Owner's, so the memberships model applies them."
  [:map
   [:membership-id :uuid]
   [:workspace-id :uuid]
   [:account-id :uuid]
   [:role [:= "owner"]]
   [:previous-role [:enum "admin" "member"]]
   [:revision :int]
   [:previous-owner-membership-id :uuid]
   [:previous-owner-account-id :uuid]
   [:previous-owner-revision :int]
   [:transferred-at validation/Instant]
   [:reason {:optional true} :string]])


(defevent workspace-ownership-granted {:schema Granted})
(defevent workspace-ownership-transferred {:schema Transferred})


(defn eligible?
  "Whether `actor` may grant or transfer ownership to `target`."
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :map]] :boolean]}
  [actor target]
  (and (model/owner? actor) (model/active? target) (not (model/own? actor target))
       (contains? #{"admin" "member"} (:role target))))


(defn- check-owner!
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :map]] :nil]}
  [own target]
  (when-not (model/owner? own)
    (model/fail! :not-permitted "Only an active Owner can do this."))
  (when (model/own? own target)
    (model/fail! :own-membership "Choose another member."))
  nil)


(defcommand grant-workspace-ownership
  {:scope :workspace :input model/Target :policy ::model/owner :cofx model/facts
   :emits #{::workspace-ownership-granted}}
  [{:keys [target own actor now]} {:keys [expected-revision] :as input}]
  (let [reason (model/reason input)]
    (check-owner! own target)
    (model/check-target! target expected-revision #{"active"} model/not-active)
    (if (= "owner" (:role target))
      {}
      {:events [{:type ::workspace-ownership-granted
                 :data (cond-> {:membership-id (:membership-id target) :workspace-id (:workspace-id target)
                                :account-id    (:user-id target)       :role         "owner"
                                :previous-role (:role target)          :revision     (model/next-revision target)
                                :granted-by    actor                   :granted-at   now}
                         reason (assoc :reason reason))}]})))


(def TransferOwnership
  (conj model/Target [:my-expected-revision :int]))


(defcommand transfer-workspace-ownership
  {:scope :workspace :input TransferOwnership :policy ::model/owner :cofx model/facts
   :emits #{::workspace-ownership-transferred ::role/workspace-member-role-changed}}
  [{:keys [target own actor now]} {:keys [expected-revision my-expected-revision] :as input}]
  (let [reason (model/reason input)]
    (check-owner! own target)
    (when-not (= my-expected-revision (:revision own))
      (model/fail! :changed-since-review "Your membership changed. Reload the page."))
    (model/check-target! target expected-revision #{"active"} model/not-active)
    (when (= "owner" (:role target))
      (model/fail! :already-owner "This member is already an Owner."))
    {:events [{:type ::workspace-ownership-transferred
               :data (cond-> {:membership-id                (:membership-id target)   :workspace-id (:workspace-id target)
                              :account-id                   (:user-id target)         :role         "owner"
                              :previous-role                (:role target)            :revision     (model/next-revision target)
                              :previous-owner-membership-id (:membership-id own)
                              :previous-owner-account-id    (:user-id own)
                              :previous-owner-revision      (model/next-revision own)
                              :transferred-at               now}
                       reason (assoc :reason reason))}
              (role/role-changed own "admin" {:actor actor :now now :reason reason})]}))
