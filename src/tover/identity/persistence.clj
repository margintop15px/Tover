(ns tover.identity.persistence
  "Native table bindings for identity slice models (01–02c)."
  (:require
    [tover.identity.recovery.model :as recovery]
    [tover.identity.reset-completion.model :as completion]
    [tover.identity.session :as session]
    [tover.identity.sign-out.model :as sign-out]))


(def tables
  {::sign-out/ended-sessions  (session/session-table [:ended-at])
   ::recovery/password-resets {:table   :app_private.password_resets
                               :columns {:attempt-id  :attempt_id  :user-id      :user_id      :session-id :session_id
                                         :status      :status      :requested-at :requested_at
                                         :valid-after :valid_after}}
   ::completion/boundaries    {:table   :app_private.session_boundaries
                               :columns {:user-id :user_id :valid-after :valid_after :kept-session-id :kept_session_id}}})
