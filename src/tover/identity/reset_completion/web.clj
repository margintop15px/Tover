(ns tover.identity.reset-completion.web
  "02c · Complete an interrupted password reset: finish the retained obligation without asking for the
  password again, and show its status (Ending Other Sign-ins, Completed or Unconfirmed)."
  (:require
    [bel.core :as bel]
    [bel.runtime :as runtime]
    [com.brunobonacci.mulog :as mu]
    [tover.identity.reset-completion.model :as model]
    [tover.identity.session :as session]
    [tover.identity.sign-in.web :as sign-in]
    [tover.identity.sign-out.web :as sign-out]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]
    [tover.shared.ui.forms :refer [form]])
  (:import
    [java.time Instant]))


(defn acting
  "The trusted context of the sign-in `row` that performs the reset."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [context {:keys [id user-id session-id]}]
  (assoc context :user-id user-id :session-id session-id :auth-session-id id))


(defn finish!
  "Complete the reset whose password change the provider accepted at `valid-after`: one acceptance writes
  the sign-in boundary and Password Reset Completed. The resetting sign-in then continues as an ordinary
  sign-in. Every step is idempotent, so a retry repeats none of them."
  {:malli/schema [:=> [:cat :map :string :uuid inst?] :nil]}
  [{:keys [session-store] :as context} key attempt-id valid-after]
  (let [row (session/session session-store key)]
    (when-not row (session/ended!))
    (runtime/execute! (acting context row) ::model/complete-password-reset
                      {:command-id (model/command-id attempt-id) :input {:attempt-id attempt-id :valid-after valid-after}})
    (let [row (session/update-session!
                session-store key
                (fn [row]
                  (let [row (if (get-in row [:data :identity/password-required?])
                              (-> row
                                  (assoc-in [:data :identity/password-required?] false)
                                  (update :data dissoc :identity/reset-pending?)
                                  (assoc :version (random-uuid)))
                              row)]
                    [row row])))]
      (when row (sign-in/record! context row))
      nil)))


(def retry-delays-ms
  "Bounded backoff for a completion that could not finish at once."
  [1000 5000 30000 120000 600000])


(defn resume!
  "Retry `finish!` in the background with bounded backoff, independently of the browser. The boundary stays the
  one fixed at password acceptance. ponytail: in-process only. Blocking older sign-ins survives a restart anyway:
  the retained `requested` attempt blocks them (session/effective-boundary). After a restart the person sees
  Unconfirmed and resubmits or signs in. Tover cannot learn the provider's outcome by itself, because any
  durable record of acceptance fails the same way completion does."
  {:malli/schema [:=> [:cat :map :string :uuid inst?] :nil]}
  [context key attempt-id valid-after]
  (Thread/startVirtualThread
    (fn []
      (loop [[delay & more] retry-delays-ms]
        (Thread/sleep (long delay))
        (when-not (try (finish! context key attempt-id valid-after) true
                       (catch Exception error
                         (mu/log ::completion-deferred :error-type (or (:type (ex-data error)) (.getName (class error))))
                         false))
          (when more (recur more))))))
  nil)


(def StatusProps
  [:map
   [:request :map]
   [:status [:maybe [:enum "requested" "rejected" "completed" "unavailable"]]]
   [:pending? {:optional true} :boolean]])


(defn status-view
  "The reset's known status; `children` is the explicit retry form for an unconfirmed change."
  {:malli/schema [:=> [:cat StatusProps [:* :any]] schema/Hiccup]}
  [{:keys [request status pending?]} & children]
  (case status
    "completed"
    [:div {:role "status"}
     [:h2 (i18n/t "Password reset completed")]
     [:p (i18n/t "Your new password is saved and your other sign-ins have ended.")]
     (form request (bel/action-url ::sign-in/continue) (i18n/t "Continue"))]

    "unavailable"
    [:div {:role "alert"}
     [:p (i18n/t "Could not load the password reset status.")]
     [:p [:a {:href "/auth/password"} (i18n/t "Retry")]]]

    (if pending?
      [:div {:role "status"}
       [:h2 (i18n/t "Ending other sign-ins")]
       [:p (i18n/t "Your new password is saved. Your other sign-ins are being ended.")]
       [:p [:a {:href "/auth/password"} (i18n/t "Retry")]]]
      (into [:div {:role "status"}
             [:h2 (i18n/t "Password change unconfirmed")]
             [:p (i18n/t "We could not confirm whether your password changed. Try again, sign in, or request a new recovery email.")]
             [:p [:a {:href "/auth/recover"} (i18n/t "Request a new recovery email")]]
             (sign-out/sign-out-form {:request request :return-to "/auth/login"})]
            children))))


(defn now
  "The instant the provider's acceptance was observed: the reset's fixed boundary."
  {:malli/schema [:=> [:cat] inst?]}
  []
  (Instant/now))
