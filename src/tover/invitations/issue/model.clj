(ns tover.invitations.issue.model
  "Invite Teammate: one open offer per workspace and recipient, queued for email delivery.
  Later events of the delivery, resend, cancel and accept slices are mapped here by fully qualified keyword."
  (:require
    [bel.core :refer [defcommand defevent defmodel]]
    [bel.runtime :as runtime]
    [bel.validation :as validation]
    [clojure.string :as str]
    [tover.workspaces.create.model :as workspaces])
  (:import
    [java.time Duration]))


(def lifetime
  "How long an offer stays open."
  (Duration/ofHours 168))


(defn normalize
  "Recipient matching: trimmed and lower-cased, never guessing that different addresses are one person."
  {:malli/schema [:=> [:cat :string] :string]}
  [email]
  (str/lower-case (str/trim email)))


(def Email
  [:and
   [:string {:max           320
             :decode/string (fn [value]
                              (if (string? value) (normalize value) value))}]
   [:re #"^[^@\s]+@[^@\s]+$"]])


(def Role
  [:enum {:default "member"} "member" "admin"])


(def InviteTeammate
  "`invitation-id` is the proposed id of a new offer, stable across retries of one form; without it the
  offer gets a fresh id.
  `reviewed-removal` is the removal instant the manager reviewed before inviting a removed member again."
  [:map
   [:invitation-id {:optional true} :uuid]
   [:email Email]
   [:role Role]
   [:reviewed-removal {:optional true} validation/Instant]])


(def Recipient
  "The recipient's membership in the workspace, if any."
  [:maybe [:map [:status :string] [:removed-at {:optional true} [:maybe validation/Instant]]]])


(defn invalidated?
  "Removal invalidates every offer created at or before the latest removal."
  {:malli/schema [:=> [:cat [:maybe :map] [:map [:created-at validation/Instant]]] :boolean]}
  [membership {:keys [created-at]}]
  (boolean (and (= "removed" (:status membership)) (:removed-at membership)
                (not (.isAfter ^java.time.Instant created-at (:removed-at membership))))))


(defn check-recipient!
  "Active members are already on the team; suspended access needs reinstatement, not an invitation."
  {:malli/schema [:=> [:cat Recipient] :nil]}
  [recipient]
  (case (:status recipient)
    "active" (throw (ex-info "That person is already on the team." {:type :already-member}))
    "suspended" (throw (ex-info "That person's access is suspended." {:type :member-suspended}))
    nil))


(def Invitation
  [:map
   [:invitation-id :uuid]
   [:workspace-id {:bel/ref ::workspaces/workspaces} :uuid]
   [:email :string]
   [:role :string]
   [:invited-by :uuid]
   [:status [:enum "pending" "accepted" "expired" "revoked"]]
   [:expires-at validation/Instant]
   [:created-at validation/Instant]])


;; Optional scalar fields stand for nullable columns: Bel models accept only scalar schemas, and the adapter omits nulls.
(def Delivery
  [:map
   [:delivery-id :uuid]
   [:invitation-id {:bel/ref ::invitations} :uuid]
   [:workspace-id {:bel/ref ::workspaces/workspaces} :uuid]
   [:requested-by :uuid]
   [:requested-at validation/Instant]
   [:delivery-status [:enum "queued" "retrying" "unconfirmed" "submitted" "failed" "exhausted" "stopped"]]
   [:attempts :int]
   [:next-attempt-at {:optional true} validation/Instant]
   [:last-problem {:optional true} [:enum "temporary" "permanent" "uncertain"]]
   [:attempted-at {:optional true} validation/Instant]
   [:submitted-at {:optional true} validation/Instant]])


(def Issued
  [:map
   [:invitation-id :uuid]
   [:delivery-id :uuid]
   [:workspace-id :uuid]
   [:email :string]
   [:role :string]
   [:invited-by :uuid]
   [:status [:= "pending"]]
   [:expires-at validation/Instant]
   [:created-at validation/Instant]
   [:requested-by :uuid]
   [:requested-at validation/Instant]
   [:delivery-status [:= "queued"]]
   [:attempts [:= 0]]
   [:next-attempt-at validation/Instant]])


(defn- manager?
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :map]] :boolean]}
  [principal scope]
  (boolean (some (fn [{:keys [workspace-id role]}]
                   (and (= workspace-id (:workspace-id scope)) (contains? #{:owner :admin} role)))
                 (:memberships principal))))


(defmethod runtime/policy ::invitation-manager
  [_ {:keys [principal scope]} _]
  (manager? principal scope))


(defmethod runtime/policy ::invitation-reader
  [_ {:keys [principal scope]} _]
  (or (manager? principal scope) (some? (:service principal))))


(defevent workspace-invitation-issued
  {:schema Issued})


(defevent workspace-invitation-expired
  {:schema [:map [:invitation-id :uuid] [:status [:= "expired"]]]})


(defmodel invitations
  {:id     :invitation-id
   :store  :directory
   :schema Invitation
   :policy ::invitation-reader
   :events {::workspace-invitation-issued {:create [:workspace-id :email :role :invited-by :status :expires-at :created-at]}
            ::workspace-invitation-expired {:set [:status]}
            :tover.invitations.cancel.model/workspace-invitation-cancelled {:set [:status]}
            :tover.invitations.accept.model/workspace-invitation-accepted  {:set [:status]}}})


(defmodel invitation-deliveries
  {:id     :delivery-id
   :store  :directory
   :schema Delivery
   :policy ::invitation-reader
   :events {::workspace-invitation-issued {:create [:invitation-id :workspace-id :requested-by :requested-at
                                                    :delivery-status :attempts :next-attempt-at]}
            :tover.invitations.resend.model/invitation-resend-requested
            {:create [:invitation-id :workspace-id :requested-by :requested-at :delivery-status :attempts :next-attempt-at]}
            :tover.invitations.delivery.model/invitation-delivery-attempted
            {:set [:attempts :delivery-status :next-attempt-at :attempted-at]}
            :tover.invitations.delivery.model/invitation-delivery-stopped
            {:set [:delivery-status :next-attempt-at]}
            :tover.invitations.delivery.model/invitation-delivery-stopped-unconfirmed
            {:set [:delivery-status :next-attempt-at :last-problem]}
            :tover.invitations.delivery.model/invitation-email-submitted
            {:set [:delivery-status :submitted-at :next-attempt-at]}
            :tover.invitations.delivery.model/invitation-email-failed
            {:set [:delivery-status :last-problem :next-attempt-at]}}})


(defcommand invite-teammate
  {:scope :workspace :input InviteTeammate :policy ::invitation-manager
   :cofx  [[:open :tover.invitations.persistence/open-invitation (fn [_ input] (select-keys input [:email]))]
           [:recipient :tover.invitations.persistence/recipient-access (fn [_ input] (select-keys input [:email]))]
           [:new-id :bel/uuid]
           [:delivery-id :bel/uuid]]
   :emits #{::workspace-invitation-issued ::workspace-invitation-expired}}
  [{:keys [actor now open recipient new-id delivery-id scope]} {:keys [invitation-id email role reviewed-removal]}]
  (check-recipient! recipient)
  ;; A form prepared before the latest removal, or without reviewing it, cannot authorize rejoining.
  (when (and (= "removed" (:status recipient)) (not= reviewed-removal (:removed-at recipient)))
    (throw (ex-info "This person was removed from the workspace. Review the removal before inviting them again."
                    {:type :removal-review-required})))
  ;; An offer invalidated by a later removal can no longer be accepted; it is closed like an expired one.
  (let [live? (and open (.isAfter ^java.time.Instant (:expires-at open) now) (not (invalidated? recipient open)))]
    (cond
      (and live? (= role (:role open))) {}
      live? (throw (ex-info "An invitation is already open." {:type :invitation-exists :invitation-id (:invitation-id open)}))
      :else {:events (cond-> []
                       open (conj {:type ::workspace-invitation-expired
                                   :data {:invitation-id (:invitation-id open) :status "expired"}})
                       :always (conj {:type ::workspace-invitation-issued
                                      :data {:invitation-id   (or invitation-id new-id)               :delivery-id delivery-id
                                             :workspace-id    (:workspace-id scope)
                                             :email           email :role role :invited-by          actor
                                             :status          "pending"
                                             :expires-at      (.plus ^java.time.Instant now lifetime)
                                             :created-at      now
                                             :requested-by    actor :requested-at now
                                             :delivery-status "queued" :attempts 0 :next-attempt-at now}}))})))
