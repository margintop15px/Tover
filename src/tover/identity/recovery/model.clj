(ns tover.identity.recovery.model
  "02a · Recover account access. Request Recovery and the password change are provider effects; Tover
  retains a non-secret reset attempt before requesting the change. Completion is in
  tover.identity.reset-completion.model (02c), the same single Password Reset Completed fact."
  (:require
   [bel.core :refer [defcommand defevent defmodel]]
   [bel.runtime :as runtime]
   [bel.validation :as validation]))


(defmethod runtime/policy ::recovering
  ;; Restricted recovery authority permits only resetting this account's password from this sign-in.
  [_ {:keys [principal scope]} {:keys [session-id]}]
  (boolean (and (:password-required? principal) (= (:user-id principal) (:user-id scope))
                (or (nil? session-id) (= session-id (:session-id principal))))))


(defmethod runtime/policy ::own-account
  [_ {:keys [principal scope]} input]
  (boolean (and (= (:user-id principal) (:user-id scope))
                (or (not (contains? input :user-id)) (= (:user-id input) (:user-id principal))))))


(def Status
  [:enum "requested" "rejected" "completed"])


(defevent password-reset-attempted
  {:schema [:map
            [:attempt-id :uuid]
            [:user-id :uuid]
            [:session-id :uuid]
            [:status Status]
            [:requested-at validation/Instant]]})


(defevent password-reset-rejected
  {:schema [:map [:attempt-id :uuid] [:status Status]]})


(defmodel password-resets
  ;; Password Resets Awaiting Completion (02c). Never holds a password or recovery secret.
  {:id     :attempt-id
   :store  :directory
   :schema [:map
            [:attempt-id :uuid]
            [:user-id :uuid]
            [:session-id :uuid]
            [:status Status]
            [:requested-at validation/Instant]
            [:valid-after {:optional true} validation/Instant]]
   :policy ::own-account
   :events {::password-reset-attempted                                      {:create [:user-id :session-id :status :requested-at]}
            ::password-reset-rejected                                       {:set [:status]}
            :tover.identity.reset-completion.model/password-reset-completed {:set [:status :valid-after]}}})


(defn attempt
  {:malli/schema [:=> [:cat :map :map] [:map [:attempt-id :uuid]]]}
  [_ {:keys [attempt-id]}]
  {:attempt-id attempt-id})


(defcommand reset-password
  {:scope :user :input [:map [:attempt-id :uuid] [:session-id :uuid]] :policy ::recovering
   :emits #{::password-reset-attempted}}
  [{:keys [actor now]} {:keys [attempt-id session-id]}]
  {:events [{:type ::password-reset-attempted
             :data {:attempt-id   attempt-id
                    :user-id      actor
                    :session-id   session-id
                    :status       "requested"
                    :requested-at now}}]})


(defcommand reject-password-reset
  ;; A definite password-policy rejection: neither the password nor any sign-in changed.
  {:scope :user :input [:map [:attempt-id :uuid]] :policy ::recovering
   :cofx  [[:attempt ::password-resets attempt]]
   :emits #{::password-reset-rejected}}
  [{:keys [actor attempt]} {:keys [attempt-id]}]
  (if (and (= actor (:user-id attempt)) (= "requested" (:status attempt)))
    {:events [{:type ::password-reset-rejected
               :data {:attempt-id attempt-id
                      :status     "rejected"}}]}
    {}))
