(ns tover.identity.sign-in.model
  "02 · Sign in and find workspace access: the User Signed In fact and the next authorized step."
  (:require
    [bel.core :refer [defcommand defevent]]
    [bel.runtime :as runtime]
    [bel.validation :as validation]
    [clojure.string :as str]
    [ring.util.codec :as codec]
    [tover.identity.access :as access]))


(defmethod runtime/policy ::ordinary-sign-in
  ;; Only the sign-in itself records its fact, and only once it is an ordinary (not recovery) sign-in.
  [_ {:keys [principal scope]} {:keys [session-id]}]
  (boolean (and (:confirmed? principal) (not (:password-required? principal))
                (= (:user-id principal) (:user-id scope))
                (= session-id (:session-id principal)))))


(def SignIn
  [:map
   [:session-id :uuid]
   [:authenticated-at validation/Instant]
   [:expires-at validation/Instant]])


(defevent user-signed-in
  {:schema (conj SignIn [:user-id :uuid])})


(defcommand sign-in
  {:scope :user :input SignIn :policy ::ordinary-sign-in :emits #{::user-signed-in}}
  [{:keys [actor]} input]
  {:events [{:type ::user-signed-in :data (assoc input :user-id actor)}]})


(defn command-id
  "One User Signed In per sign-in: retries of the same sign-in recover the first receipt."
  {:malli/schema [:=> [:cat :uuid] :string]}
  [session-id]
  (str "sign-in/" session-id))


(defn outcome
  "Classify a provider failure for a public identity screen. Unknown, existing and unconfirmed accounts
  look the same; an unknown write outcome is never reported as success or failure."
  {:malli/schema [:=> [:cat :map] [:enum :unknown :neutral :invalid :throttled :rejected :link-invalid :unavailable]]}
  [{:keys [code uncertain?]}]
  (cond
    uncertain? :unknown
    (= code :email-exists) :neutral
    (contains? #{:invalid-input :weak-password :same-password} code) :invalid
    (= code :rate-limited) :throttled
    (contains? #{:invalid-credentials :email-not-confirmed} code) :rejected
    (contains? #{:verification-expired :verification-invalid :unauthenticated} code) :link-invalid
    :else :unavailable))


(defn destination
  "A requested Tover screen worth returning to: same-origin and not an identity page."
  {:malli/schema [:=> [:cat :any] [:maybe :string]]}
  [return-to]
  (let [path (access/local-path return-to)]
    (when-not (or (= "/" path) (str/starts-with? path "/auth/")) path)))


(defn next-step
  "Where a signed-in person goes now, in order: required recovery password, required profile (keeping the
  requested destination), the requested screen or invitation (its own route rechecks access and never
  accepts anything), else the workspace chooser."
  {:malli/schema [:=> [:cat [:map [:password-required? :boolean] [:profile-complete? :boolean]] :any] :string]}
  [{:keys [password-required? profile-complete?]} return-to]
  (let [requested (destination return-to)]
    (cond
      password-required? "/auth/password"
      (not profile-complete?) (cond-> "/account/profile" requested (str "?return-to=" (codec/url-encode requested)))
      requested requested
      :else "/workspace")))
