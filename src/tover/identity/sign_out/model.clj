(ns tover.identity.sign-out.model
  "02b · Sign out: end this exact Tover sign-in."
  (:require
    [bel.core :refer [defcommand defevent defmodel]]
    [bel.runtime :as runtime]
    [bel.validation :as validation]))


(defmethod runtime/policy ::this-sign-in
  ;; Only the sign-in itself can end itself: a stale page of another sign-in names a different session.
  [_ {:keys [principal scope]} {:keys [session-id]}]
  (boolean (and (= (:user-id principal) (:user-id scope)) (= session-id (:session-id principal)))))


(defevent user-signed-out
  {:schema [:map [:session-id :uuid] [:user-id :uuid] [:ended-at validation/Instant]]})


(defmodel sessions
  ;; app_private.sessions.ended_at: an ended row is never revived (session/live?).
  {:id     :session-id
   :store  :directory
   :schema [:map [:session-id :uuid] [:ended-at validation/Instant]]
   :policy ::this-sign-in
   :events {::user-signed-out {:set [:ended-at]}}})


;; User scope: the journal row is written in the transaction that ends the session, and the session journal
;; policy accepts only live sessions.
(defcommand sign-out
  {:scope :user :input [:map [:session-id :uuid]] :policy ::this-sign-in :emits #{::user-signed-out}}
  [{:keys [actor now]} {:keys [session-id]}]
  {:events [{:type ::user-signed-out :data {:session-id session-id :user-id actor :ended-at now}}]})


(defn command-id
  "Repeating the sign-out of one sign-in recovers its first receipt."
  {:malli/schema [:=> [:cat :uuid] :string]}
  [session-id]
  (str "sign-out/" session-id))
