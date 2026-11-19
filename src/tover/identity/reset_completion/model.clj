(ns tover.identity.reset-completion.model
  "02c · Complete an interrupted password reset. Completing writes the account's sign-in boundary
  (app_private.session_boundaries) and the single Password Reset Completed fact in one acceptance, so every
  Tover sign-in established before the confirmed password change, except the resetting one, stops working.
  The boundary is fixed when the provider accepted the password and never moves backwards on retry."
  (:require
    [bel.core :refer [defcommand defevent defmodel defreadmodel]]
    [bel.validation :as validation]
    [tover.identity.recovery.model :as recovery]))


(defevent password-reset-completed
  {:schema [:map [:attempt-id :uuid] [:user-id :uuid] [:status recovery/Status] [:valid-after validation/Instant]
            [:kept-session-id {:optional true} :uuid]]})


(defmodel session-boundaries
  {:id     :user-id
   :store  :directory
   :schema [:map [:user-id :uuid] [:valid-after validation/Instant] [:kept-session-id {:optional true} :uuid]]
   :policy ::recovery/own-account
   :events {::password-reset-completed {:set [:valid-after :kept-session-id]}}})


(defn- own-boundary
  {:malli/schema [:=> [:cat :map :map] [:map [:user-id :uuid]]]}
  [{:keys [actor]} _]
  {:user-id actor})


(defcommand complete-password-reset
  {:scope :user :input [:map [:attempt-id :uuid] [:valid-after validation/Instant]] :policy ::recovery/own-account
   :cofx  [[:attempt ::recovery/password-resets recovery/attempt]
           [:boundary ::session-boundaries own-boundary]]
   :emits #{::password-reset-completed}}
  [{:keys [actor attempt boundary]} {:keys [attempt-id valid-after]}]
  (when-not (and attempt (= actor (:user-id attempt)))
    (throw (ex-info "This password reset was not found." {:type :not-found})))
  (if (= "completed" (:status attempt))
    {}
    ;; A newer boundary already ends every sign-in this one would: keep it, never end newer sign-ins.
    (let [newer?   (and boundary (not (.isBefore ^java.time.Instant (:valid-after boundary) valid-after)))
          boundary (if newer? boundary {:valid-after valid-after :kept-session-id (:session-id attempt)})]
      {:events [{:type ::password-reset-completed
                 :data (cond-> {:attempt-id attempt-id :user-id actor :status "completed" :valid-after (:valid-after boundary)}
                         (:kept-session-id boundary) (assoc :kept-session-id (:kept-session-id boundary)))}]})))


(defn command-id
  "The completion identity of one reset attempt: retries recover the first receipt."
  {:malli/schema [:=> [:cat :uuid] :string]}
  [attempt-id]
  (str "reset/" attempt-id))


(defreadmodel reset-status
  {:scope  :user :input [:map [:attempt-id :uuid]] :policy ::recovery/own-account
   :output [:maybe [:map [:status recovery/Status]]]
   :cofx   [[:attempt ::recovery/password-resets recovery/attempt]]}
  [{:keys [actor attempt]} _]
  (when (= actor (:user-id attempt))
    {:status (:status attempt)}))
