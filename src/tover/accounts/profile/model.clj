(ns tover.accounts.profile.model
  (:require
    [bel.core :refer [defcommand defevent defmodel defreadmodel]]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [com.brunobonacci.mulog :as mu]
    [tover.auth.security :as security]
    [tover.auth.sessions :as sessions]))


(def DisplayName
  [:string {:min           1
            :max           100
            :decode/string (fn [value]
                             (if (string? value) (str/trim value) value))}])


(def Language
  [:enum "en" "ru"])


(def Profile
  [:map
   [:user-id :uuid]
   [:display-name {:optional true} :string]
   [:language {:optional true} Language]
   [:name-revision :int]
   [:language-revision :int]])


(defmethod runtime/policy ::profile-owner
  [_ {{:keys [user-id confirmed? password-required?]} :principal scope :scope} input]
  (boolean (and confirmed? (not password-required?) (= user-id (:user-id scope))
                (or (not (contains? input :account)) (= (:account input) user-id)))))


;; The accepted save stands even when a session cannot be refreshed; its claims catch up on the next token refresh.
(defmethod runtime/effect! :tover.auth/refresh-claims
  [_ {:keys [actor resources]} _]
  (doseq [key (sessions/session-keys-for-user (:session-store resources) actor)]
    (try
      (security/refresh-claims! resources key)
      (catch Exception error
        (mu/log ::refresh-claims-failed :error-type (.getName (class error)))))))


(defevent display-name-changed
  {:schema [:map [:user-id :uuid] [:display-name DisplayName] [:name-revision :int]]})


(defevent account-language-changed
  {:schema [:map [:user-id :uuid] [:language Language] [:language-revision :int]]})


(defmodel profiles
  {:id     :user-id
   :store  :directory
   :schema Profile
   :policy ::profile-owner
   :events {::display-name-changed     {:set [:display-name :name-revision]}
            ::account-language-changed {:set [:language :language-revision]}}})


(defn own-profile
  {:malli/schema [:=> [:cat :map :map] [:map [:user-id :uuid]]]}
  [{:keys [actor]} _]
  {:user-id actor})


(def refresh-claims
  [:tover.auth/refresh-claims {}])


(defn change
  "Save one revisioned profile field: unchanged is a no-op, a stale revision returns the latest value.
  Every outcome refreshes the session claims."
  {:malli/schema [:=> [:cat :qualified-keyword [:maybe :map] :keyword :keyword :any :int] :map]}
  [event profile field revision value expected]
  (cond
    (= value (get profile field))
    {:fx [refresh-claims]}

    (not= expected (get profile revision))
    (throw (ex-info "This profile changed. Review the latest value."
                    {:type :stale-profile :latest (select-keys profile [field revision])}))

    :else
    {:events [{:type event :data {:user-id (:user-id profile) field value revision (inc expected)}}]
     :fx     [refresh-claims]}))


(defcommand set-display-name
  {:scope  :user
   :input  [:map [:account :uuid] [:display-name DisplayName] [:name-revision :int]]
   :policy ::profile-owner
   :cofx   [[:profile ::profiles own-profile]]
   :emits  #{::display-name-changed}}
  [{:keys [actor profile]} {:keys [display-name name-revision]}]
  (change ::display-name-changed (assoc profile :user-id actor) :display-name :name-revision display-name name-revision))


(defcommand change-account-language
  {:scope  :user
   :input  [:map [:account :uuid] [:language Language] [:language-revision :int]]
   :policy ::profile-owner
   :cofx   [[:profile ::profiles own-profile]]
   :emits  #{::account-language-changed}}
  [{:keys [actor profile]} {:keys [language language-revision]}]
  (change ::account-language-changed (assoc profile :user-id actor) :language :language-revision language language-revision))


(defreadmodel my-profile
  {:scope  :user
   :input  [:map]
   :policy ::profile-owner
   :output Profile
   :cofx   [[:profile ::profiles own-profile]]}
  [{:keys [profile]} _]
  profile)
