(ns tover.invitations.accept.model
  "Accept Workspace Invitation: the verified recipient explicitly accepts the reviewed offer; acceptance and the
  active membership grant commit together. Nothing here selects a workspace."
  (:require
    [bel.core :refer [defcommand defevent defmodel defreadmodel]]
    [bel.runtime :as runtime]
    [bel.validation :as validation]
    [clojure.string :as str]
    [tover.accounts.profile.model :as profile]
    [tover.invitations.issue.model :as issue]
    [tover.invitations.status.model :as status]
    [tover.memberships.model :as memberships]))


(defmethod runtime/policy ::recipient
  [_ {{:keys [user-id confirmed? password-required?]} :principal scope :scope} _]
  (boolean (and confirmed? (not password-required?) (= user-id (:user-id scope)))))


(defevent workspace-invitation-accepted
  {:schema [:map
            [:invitation-id :uuid]
            [:status [:= "accepted"]]
            [:workspace-id :uuid]
            [:user-id :uuid]
            [:role :string]]})


(defevent workspace-member-joined
  {:schema [:map
            [:membership-id :uuid]
            [:workspace-id :uuid]
            [:user-id :uuid]
            [:role :string]
            [:status [:= "active"]]
            [:revision :int]
            ;; Never present: declared so a rejoin clears the earlier removal kind.
            [:removal-kind {:optional true} :string]]})


(defmodel memberships
  {:id     :membership-id
   :store  :directory
   :schema memberships/Membership
   :policy ::recipient
   ;; Rejoining clears the earlier removal kind; removed-at stays so offers from before it remain invalid.
   :events {::workspace-member-joined {:set [:workspace-id :user-id :role :status :revision :removal-kind]}}})


(def Reviewed
  "What the person reviewed; a changed offer needs a fresh review."
  [:map
   [:invitation-id :uuid]
   [:workspace-id :uuid]
   [:role issue/Role]])


(def Received
  [:maybe
   [:map
    [:invitation-id :uuid]
    [:workspace-id :uuid]
    [:workspace-name :string]
    [:email :string]
    [:role :string]
    [:inviter {:optional true} :string]
    [:status :string]
    [:expires-at validation/Instant]
    [:created-at validation/Instant]]])


(def OwnMembership
  [:maybe [:map [:membership-id :uuid] [:role :string] [:status :string] [:revision {:optional true} :int]
           [:removed-at {:optional true} validation/Instant]]])


(defn offer-state
  "The offer as the recipient may act on it: pending, accepted, revoked, expired or invalidated."
  {:malli/schema [:=> [:cat [:map [:status :string] [:expires-at validation/Instant] [:created-at validation/Instant]]
                       OwnMembership validation/Instant] :string]}
  [invitation membership now]
  (let [state (status/effective-status invitation now)]
    (if (and (= "pending" state) (issue/invalidated? membership invitation)) "invalidated" state)))


(defn- profile-complete?
  {:malli/schema [:=> [:cat [:maybe :map]] :boolean]}
  [profile]
  (let [name (some-> (:display-name profile) str/trim)]
    (boolean (and (seq name) (<= (count (:display-name profile)) 100)))))


(def ^:private received-cofx
  [[:invitation :tover.invitations.persistence/received-invitation (fn [_ input] (select-keys input [:invitation-id]))]
   [:membership :tover.invitations.persistence/own-membership
    (fn [{:keys [invitation]} input] {:workspace-id (or (:workspace-id invitation) (:workspace-id input))})]])


(defcommand accept-workspace-invitation
  {:scope :user :input Reviewed :policy ::recipient
   :cofx  (into [[:profile ::profile/profiles profile/own-profile]] (conj received-cofx [:membership-id :bel/uuid]))
   :emits #{::workspace-invitation-accepted ::workspace-member-joined}}
  [{:keys [actor now profile invitation membership membership-id]} {:keys [invitation-id workspace-id role]}]
  (when-not (profile-complete? profile)
    (throw (ex-info "Complete your profile first." {:type :profile-incomplete})))
  ;; Only the verified recipient sees the offer; anyone else gets the same answer as for a missing one.
  (when-not invitation
    (throw (ex-info "This invitation is not available to this account." {:type :invitation-unavailable})))
  (when-not (and (= workspace-id (:workspace-id invitation)) (= role (:role invitation)))
    (throw (ex-info "This invitation changed. Review it again." {:type :invitation-changed})))
  (case (:status membership)
    ;; Already on the team: no acceptance or join that did not happen; the role stays unchanged.
    "active" {}
    "suspended" (throw (ex-info "Your access to this workspace is suspended. Ask an owner to reinstate it."
                                {:type :member-suspended}))
    (let [state (offer-state invitation membership now)]
      (when-not (= "pending" state)
        (throw (ex-info "This invitation can no longer be accepted." {:type :invitation-not-pending :status state})))
      {:events [{:type ::workspace-invitation-accepted
                 :data {:invitation-id invitation-id :status "accepted" :workspace-id workspace-id :user-id actor :role role}}
                {:type ::workspace-member-joined
                 :data {:membership-id (or (:membership-id membership) membership-id)
                        :workspace-id  workspace-id :user-id actor :role role :status "active"
                        :revision      (memberships/next-revision membership)}}]})))


(def Review
  [:map
   [:available? :boolean]
   [:invitation-id :uuid]
   [:workspace-id {:optional true} :uuid]
   [:workspace-name {:optional true} :string]
   [:inviter {:optional true} :string]
   [:role {:optional true} :string]
   [:expires-at {:optional true} validation/Instant]
   [:state {:optional true} [:enum "pending" "accepted" "revoked" "expired" "invalidated"]]
   [:membership {:optional true} [:maybe [:enum "active" "suspended" "removed" "invited"]]]
   [:current-role {:optional true} [:maybe :string]]])


(defreadmodel my-invitation
  {:scope  :user
   :input  [:map [:invitation-id :uuid]]
   :output Review
   :policy ::recipient
   :cofx   (conj received-cofx [:clock :bel/now])}
  [{:keys [invitation membership clock]} {:keys [invitation-id]}]
  (if-not invitation
    {:available? false :invitation-id invitation-id}
    (merge {:available? true :state (offer-state invitation membership clock) :membership (:status membership)}
           (select-keys invitation [:invitation-id :workspace-id :workspace-name :inviter :role :expires-at])
           (when (= "active" (:status membership)) {:current-role (:role membership)}))))


(defn acceptable?
  "Whether Accept Invitation may be offered for this review."
  {:malli/schema [:=> [:cat Review] :boolean]}
  [{:keys [available? state membership]}]
  (boolean (and available? (= "pending" state) (not (contains? #{"active" "suspended"} membership)))))
