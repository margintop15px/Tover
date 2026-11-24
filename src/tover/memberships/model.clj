(ns tover.memberships.model
  "Workspace Memberships (board chapters 34–39): what every membership command shares. A membership is one
  row per workspace and account; `revision` advances with every change so a command can reject a stale
  review. Rules and rejections live in the board's element details; these functions only apply them."
  (:require
    [bel.core :refer [defmodel]]
    [bel.runtime :as runtime]
    [bel.validation :as validation]
    [clojure.string :as str]))


(def Role
  [:enum "owner" "admin" "member"])


(def Status
  [:enum "active" "suspended" "removed"])


(def Membership
  [:map
   [:membership-id :uuid]
   [:workspace-id :uuid]
   [:user-id :uuid]
   [:role :string]
   [:status :string]
   [:revision :int]
   [:removed-at {:optional true} validation/Instant]
   [:removal-kind {:optional true} [:enum "removed" "left"]]
   [:suspended-at {:optional true} validation/Instant]
   [:suspension-reason {:optional true} :string]])


(def Target
  "Input every command on another member's row carries."
  [:map
   [:membership-id :uuid]
   [:expected-revision :int]
   [:reason {:optional true} [:maybe :string]]])


(defn fail!
  {:malli/schema [:=> [:cat :keyword :string] :nil]}
  [type message]
  (throw (ex-info message {:type type})))


(defn next-revision
  {:malli/schema [:=> [:cat [:maybe :map]] :int]}
  [membership]
  (inc (:revision membership 0)))


(defn reason
  "The optional reason: trimmed, blank is absent, at most 500 characters."
  {:malli/schema [:=> [:cat :map] [:maybe :string]]}
  [input]
  (let [text (some-> (:reason input) str/trim)]
    (when (and text (< 500 (count text)))
      (fail! :reason-too-long "The reason can be at most 500 characters."))
    (not-empty text)))


(defn active?
  {:malli/schema [:=> [:cat [:maybe :map]] :boolean]}
  [membership]
  (= "active" (:status membership)))


(defn owner?
  "An active Owner membership."
  {:malli/schema [:=> [:cat [:maybe :map]] :boolean]}
  [membership]
  (and (active? membership) (= "owner" (:role membership))))


(defn manager?
  "An active Owner or Admin membership."
  {:malli/schema [:=> [:cat [:maybe :map]] :boolean]}
  [membership]
  (and (active? membership) (contains? #{"owner" "admin"} (:role membership))))


(defn may-manage?
  "Whether manager `actor` may act on `target`: Owners on anyone, Admins on Admins and Members."
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :map]] :boolean]}
  [actor target]
  (and (manager? actor) (some? target) (or (= "owner" (:role actor)) (not= "owner" (:role target)))))


(defn own?
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :map]] :boolean]}
  [actor target]
  (and (some? actor) (= (:membership-id actor) (:membership-id target))))


(def not-active [:not-active "This member's access is suspended or removed."])
(def not-suspended [:not-suspended "This member is not suspended."])
(def already-removed [:already-removed "This member was already removed."])


(defn check-target!
  "The reviewed target still exists at `expected-revision` and has one of `statuses`, else `rejection`."
  {:malli/schema [:=> [:cat [:maybe :map] :int [:set :string] [:tuple :keyword :string]] :nil]}
  [target expected-revision statuses [type message]]
  (when-not (and target (= expected-revision (:revision target)))
    (fail! :changed-since-review "This member changed. Reload the page."))
  (when-not (contains? statuses (:status target))
    (fail! type message))
  nil)


(defn check-manager!
  "The actor may manage `target` (when there is one)."
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :map]] :nil]}
  [actor target]
  (when-not (manager? actor)
    (fail! :not-permitted "You can no longer manage this workspace's members."))
  (when (and target (not (may-manage? actor target)))
    (fail! :not-permitted "Only an Owner can change an Owner's membership."))
  nil)


(defn check-not-own!
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :map]] :nil]}
  [actor target]
  (when (own? actor target)
    (fail! :own-membership "Use Leave workspace for your own membership."))
  nil)


(defn keeps-an-owner?
  "Whether the workspace still has an active Owner once `leaving` (account ids) stop being active Owners."
  {:malli/schema [:=> [:cat [:sequential :uuid] [:set :uuid]] :boolean]}
  [owners leaving]
  (boolean (some (complement leaving) owners)))


(defn- role-in
  {:malli/schema [:=> [:cat :map] [:maybe :keyword]]}
  [{:keys [principal scope]}]
  (some #(when (= (:workspace-id scope) (:workspace-id %)) (keyword (:role %))) (:memberships principal)))


(defmethod runtime/policy ::manager
  [_ context _]
  (contains? #{:owner :admin} (role-in context)))


(defmethod runtime/policy ::owner
  [_ context _]
  (= :owner (role-in context)))


(defmethod runtime/policy ::member
  [_ context _]
  (some? (role-in context)))


(defmodel memberships
  {:id     :membership-id
   :store  :directory
   :schema Membership
   :policy ::member
   ;; Absent optional fields are written as NULL, so reinstatement clears the suspension fields.
   :events {:tover.memberships.role.model/workspace-member-role-changed        {:set [:role :revision]}
            :tover.memberships.suspension.model/workspace-member-suspended     {:set [:status :revision :suspended-at :suspension-reason]}
            :tover.memberships.suspension.model/workspace-member-reinstated    {:set [:status :revision :suspended-at :suspension-reason]}
            :tover.memberships.removal.model/workspace-member-removed          {:set [:status :revision :removed-at :removal-kind]}
            :tover.memberships.departure.model/workspace-member-left           {:set [:status :revision :removed-at :removal-kind]}
            :tover.memberships.ownership.model/workspace-ownership-granted     {:set [:role :revision]}
            :tover.memberships.ownership.model/workspace-ownership-transferred {:set [:role :revision]}}})


(def facts
  "Coeffects every command on a member's row reads: the target, the actor's own membership, the locked Owners."
  [[:target :tover.memberships.persistence/target (fn [_ input] (select-keys input [:membership-id]))]
   [:own :tover.memberships.persistence/own (constantly {})]
   [:owners :tover.memberships.persistence/active-owners (constantly {})]])
