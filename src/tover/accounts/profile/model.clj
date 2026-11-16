(ns tover.accounts.profile.model
  (:require
    [bel.core :refer [defcommand defevent defmodel defreadmodel]]
    [bel.runtime :as runtime]
    [clojure.string :as str]))


(defn graphemes
  "User-perceived characters: Unicode extended grapheme clusters."
  {:malli/schema [:=> [:cat :string] :int]}
  [value]
  (count (re-seq #"\X" value)))


(defn valid-name?
  "Trimmed and 1–100 grapheme clusters: the rule for display names and workspace names."
  {:malli/schema [:=> [:cat [:maybe :string]] :boolean]}
  [value]
  (boolean (and (string? value) (= value (str/trim value)) (<= 1 (graphemes value) 100))))


(def Name
  "Board chapters 03 and 04: trimmed, then 1–100 extended grapheme clusters."
  [:and
   [:string {:decode/string (fn [value]
                              (if (string? value) (str/trim value) value))}]
   [:fn {:error/message "Name must have 1 to 100 characters."} valid-name?]])


(def DisplayName
  Name)


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


(defn change
  "Save one revisioned profile field: unchanged is a no-op, a stale revision returns the latest value."
  {:malli/schema [:=> [:cat :qualified-keyword [:maybe :map] :keyword :keyword :any :int] :map]}
  [event profile field revision value expected]
  (cond
    (= value (get profile field))
    {}

    (not= expected (get profile revision))
    (throw (ex-info "This profile changed. Review the latest value."
                    {:type :stale-profile :latest (select-keys profile [field revision])}))

    :else
    {:events [{:type event :data {:user-id (:user-id profile) field value revision (inc expected)}}]}))


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
