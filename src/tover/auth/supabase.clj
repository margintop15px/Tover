(ns tover.auth.supabase
  (:require
    [clojure.string :as str]
    [tover.auth.core :as auth]
    [tover.shared.supabase :as http])
  (:import
    [java.time Instant]
    [java.util UUID]))


(def ^:private Text
  [:string {:min 1}])


(def ^:private EmailInput
  [:map [:email Text] [:redirect-to {:optional true} Text]])


(defn- user
  {:malli/schema [:=> [:cat :any] auth/User]}
  [data]
  (try
    (auth/check-input! [:map [:id :string] [:email :string]] data)
    {:user-id          (UUID/fromString (:id data))
     :email            (:email data)
     :email-confirmed? (boolean (:email_confirmed_at data))}
    (catch Exception _
      (auth/fail! :invalid-response 502 false))))


(defn- tokens
  {:malli/schema [:=> [:cat :any] auth/Tokens]}
  [data]
  (try
    (auth/check-input! [:map [:access_token Text] [:refresh_token Text]
                        [:expires_in {:optional true} :int] [:expires_at {:optional true} :int]] data)
    (let [expires-at (or (:expires_at data)
                         (when-let [seconds (:expires_in data)]
                           (+ (.getEpochSecond (Instant/now)) seconds)))]
      (when-not (and (integer? expires-at) (pos? expires-at))
        (auth/fail! :invalid-response 502 true))
      {:access-token  (:access_token data)
       :refresh-token (:refresh_token data)
       :expires-at    expires-at
       :user          (user (:user data))})
    (catch Exception _
      (auth/fail! :invalid-response 502 true))))


(defn auth
  "The same Auth HTTP adapter serves hosted and self-hosted Supabase."
  {:malli/schema [:=> [:cat :map] [:fn #(satisfies? auth/Auth %)]]}
  [{:keys [auth-url api-key admin-key timeout-ms]}]
  (auth/check-input! Text api-key)
  (auth/check-input! Text admin-key)
  (let [base      (http/base-url auth-url)
        transport (cond-> {:client (http/client) :api-key api-key}
                    timeout-ms (assoc :timeout-ms timeout-ms))
        request!  (fn [path options]
                    (http/request! (cond-> transport (:admin? options) (assoc :api-key admin-key))
                                   (assoc options :url (str base path))))
        email!    (fn [path input admin?]
                    (auth/check-input! EmailInput input)
                    (request! (str path (http/query (when-let [redirect (:redirect-to input)]
                                                      {:redirect_to redirect})))
                              {:admin? admin?
                               :token  (when admin? admin-key)
                               :body   (cond-> {:email (:email input)}
                                         (= path "/otp") (assoc :create_user false))})
                    nil)]
    (reify auth/Auth
      (sign-up! [_ input]
        (auth/check-input! [:map [:email Text] [:password Text]
                            [:workspace-name {:optional true} :string]
                            [:redirect-to {:optional true} Text]] input)
        (let [data (request! (str "/signup" (http/query (when-let [redirect (:redirect-to input)]
                                                          {:redirect_to redirect})))
                             {:body (cond-> (select-keys input [:email :password])
                                      (not (str/blank? (:workspace-name input)))
                                      (assoc :data {:organization_name (:workspace-name input)}))})]
          (if (:access_token data) (tokens data) {:user (user (or (:user data) data))})))
      (sign-in! [_ input]
        (auth/check-input! [:map [:email Text] [:password Text]] input)
        (tokens (request! "/token?grant_type=password" {:body (select-keys input [:email :password])})))
      (current-user [_ access-token]
        (user (request! "/user" {:method :get :token access-token})))
      (refresh! [_ refresh-token]
        (tokens (request! "/token?grant_type=refresh_token" {:body {:refresh_token refresh-token}})))
      (sign-out! [_ access-token]
        (request! "/logout?scope=local" {:token access-token})
        nil)
      (verify-email! [_ input]
        (auth/check-input! [:map [:token-hash Text]
                            [:type [:enum :signup :invite :recovery :magiclink :email]]] input)
        (tokens (request! "/verify" {:body {:token_hash (:token-hash input) :type (name (:type input))}})))
      (recover! [_ input]
        (email! "/recover" input false))
      (set-password! [_ access-token password]
        (auth/check-input! Text password)
        (user (request! "/user" {:method :put :token access-token :body {:password password}})))
      (invite! [_ input]
        (email! "/invite" input true))
      (magic-link! [_ input]
        (email! "/otp" input false)))))
