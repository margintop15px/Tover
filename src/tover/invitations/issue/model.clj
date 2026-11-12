(ns tover.invitations.issue.model
  "Invite Teammate: one open offer per workspace and recipient, queued for email delivery.
  The delivery model's later events are mapped by Task 6 using fully qualified keywords."
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


(def Email
  [:and
   [:string {:max           320
             :decode/string (fn [value]
                              (if (string? value) (str/lower-case (str/trim value)) value))}]
   [:re #"^[^@\s]+@[^@\s]+$"]])


(def Role
  [:enum {:default "member"} "member" "admin"])


(def InviteTeammate
  [:map
   [:email Email]
   [:role Role]])


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
   :events {::workspace-invitation-issued  {:create [:workspace-id :email :role :invited-by :status :expires-at :created-at]}
            ::workspace-invitation-expired {:set [:status]}}})


(defmodel deliveries
  {:id     :delivery-id
   :store  :directory
   :schema Delivery
   :policy ::invitation-reader
   :events {::workspace-invitation-issued {:create [:invitation-id :workspace-id :requested-by :requested-at
                                                    :delivery-status :attempts :next-attempt-at]}
            :tover.invitations.delivery.model/invitation-delivery-attempted
            {:set [:attempts :delivery-status :next-attempt-at :attempted-at]}
            :tover.invitations.delivery.model/invitation-delivery-stopped
            {:set [:delivery-status :next-attempt-at]}
            :tover.invitations.delivery.model/invitation-email-submitted
            {:set [:delivery-status :submitted-at]}
            :tover.invitations.delivery.model/invitation-email-failed
            {:set [:delivery-status :last-problem :next-attempt-at]}}})


(defcommand invite-teammate
  {:scope :workspace :input InviteTeammate :policy ::invitation-manager
   :cofx  [[:open :tover.invitations.persistence/open-invitation (fn [_ input] (select-keys input [:email]))]
           [:recipient :tover.invitations.persistence/recipient-membership (fn [_ input] (select-keys input [:email]))]
           [:invitation-id :bel/uuid]
           [:delivery-id :bel/uuid]]
   :emits #{::workspace-invitation-issued ::workspace-invitation-expired}}
  [{:keys [actor now open recipient invitation-id delivery-id scope]} {:keys [email role]}]
  (case recipient
    "active" (throw (ex-info "That person is already on the team." {:type :already-member}))
    "suspended" (throw (ex-info "That person's access is suspended." {:type :member-suspended}))
    nil)
  (let [live? (and open (.isAfter ^java.time.Instant (:expires-at open) now))]
    (cond
      (and live? (= role (:role open))) {}
      live? (throw (ex-info "An invitation is already open." {:type :invitation-exists :invitation-id (:invitation-id open)}))
      :else {:events (cond-> []
                       open (conj {:type ::workspace-invitation-expired
                                   :data {:invitation-id (:invitation-id open) :status "expired"}})
                       :always (conj {:type ::workspace-invitation-issued
                                      :data {:invitation-id   invitation-id :delivery-id delivery-id
                                             :workspace-id    (:workspace-id scope)
                                             :email           email :role                role        :invited-by      actor
                                             :status          "pending"
                                             :expires-at      (.plus ^java.time.Instant now lifetime)
                                             :created-at      now
                                             :requested-by    actor :requested-at        now
                                             :delivery-status "queued" :attempts         0           :next-attempt-at now}}))})))
