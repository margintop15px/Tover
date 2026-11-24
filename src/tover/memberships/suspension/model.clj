(ns tover.memberships.suspension.model
  "Board chapter 35 · Suspend and reinstate workspace access: suspension keeps the membership and its role;
  reinstatement restores both. Nobody suspends their own membership."
  (:require
    [bel.core :refer [defcommand defevent]]
    [bel.validation :as validation]
    [tover.memberships.model :as model]))


(def Suspended
  [:map
   [:membership-id :uuid]
   [:workspace-id :uuid]
   [:account-id :uuid]
   [:role model/Role]
   [:status [:= "suspended"]]
   [:revision :int]
   [:suspended-by :uuid]
   [:suspended-at validation/Instant]
   [:suspension-reason {:optional true} :string]])


(def Reinstated
  [:map
   [:membership-id :uuid]
   [:workspace-id :uuid]
   [:account-id :uuid]
   [:role model/Role]
   [:status [:= "active"]]
   [:revision :int]
   [:reinstated-by :uuid]
   [:reinstated-at validation/Instant]
   [:reason {:optional true} :string]
   ;; Never present: declared so the memberships model clears the suspension fields.
   [:suspended-at {:optional true} validation/Instant]
   [:suspension-reason {:optional true} :string]])


(defevent workspace-member-suspended {:schema Suspended})
(defevent workspace-member-reinstated {:schema Reinstated})


(defn can-suspend?
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :map]] :boolean]}
  [actor target]
  (and (model/active? target) (not (model/own? actor target)) (model/may-manage? actor target)))


(defn can-reinstate?
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :map]] :boolean]}
  [actor target]
  (and (= "suspended" (:status target)) (model/may-manage? actor target)))


(defn- common
  {:malli/schema [:=> [:cat :map] :map]}
  [target]
  {:membership-id (:membership-id target) :workspace-id (:workspace-id target)       :account-id (:user-id target)
   :role          (:role target)          :revision     (model/next-revision target)})


(defcommand suspend-workspace-member
  {:scope :workspace :input model/Target :policy ::model/manager :cofx model/facts
   :emits #{::workspace-member-suspended}}
  [{:keys [target own actor now]} {:keys [expected-revision] :as input}]
  (let [reason (model/reason input)]
    (model/check-manager! own target)
    (model/check-not-own! own target)
    (model/check-target! target expected-revision #{"active"} model/not-active)
    {:events [{:type ::workspace-member-suspended
               :data (cond-> (assoc (common target) :status "suspended" :suspended-by actor :suspended-at now)
                       reason (assoc :suspension-reason reason))}]}))


(defcommand reinstate-workspace-member
  {:scope :workspace :input model/Target :policy ::model/manager :cofx model/facts
   :emits #{::workspace-member-reinstated}}
  [{:keys [target own actor now]} {:keys [expected-revision] :as input}]
  (let [reason (model/reason input)]
    (model/check-manager! own target)
    (model/check-target! target expected-revision #{"suspended"} model/not-suspended)
    {:events [{:type ::workspace-member-reinstated
               :data (cond-> (assoc (common target) :status "active" :reinstated-by actor :reinstated-at now)
                       reason (assoc :reason reason))}]}))
