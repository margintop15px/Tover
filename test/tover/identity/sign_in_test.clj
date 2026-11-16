(ns tover.identity.sign-in-test
  "Board chapter 02 · Sign in and find workspace access (slice prose; the chapter has no ## Examples)."
  (:require
    [bel.core :as bel]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [tover.fixtures :as fixtures]
    [tover.identity.access :as access]
    [tover.identity.provider :as provider]
    [tover.identity.session :as session]
    [tover.identity.sign-in.model :as model]
    [tover.identity.sign-in.web :as web]
    [tover.support :as support]))


(def sign-in-url
  (bel/action-url ::web/sign-in))


(defn signed-in-events
  {:malli/schema [:=> [:cat :any] [:vector :map]]}
  [state]
  (into [] (comp (mapcat :events) (filter #(= ::model/user-signed-in (:type %))) (map :data)) (:journal @state)))


(defn alert
  "The page's alert text (the message catalog in the head mentions every message)."
  {:malli/schema [:=> [:cat :string] [:maybe :string]]}
  [body]
  (second (re-find #"role=\"alert\">([^<]*)<" body)))


(defn sign-in!
  {:malli/schema [:=> [:cat :map :string :string [:? :map]] :map]}
  ([browser email password] (sign-in! browser email password {}))
  ([{:keys [request!]} email password params]
   (request! :get "/auth/login")
   (request! :post sign-in-url (merge {"email" email "password" password} params))))


(deftest next-step-follows-the-agreed-order
  (let [ready {:password-required? false :profile-complete? true}]
    (is (= "/auth/password" (model/next-step (assoc ready :password-required? true :profile-complete? false) "/stock")))
    (is (= "/account/profile?return-to=%2Finvitations%2Fabc" (model/next-step (assoc ready :profile-complete? false) "/invitations/abc")))
    (is (= "/account/profile" (model/next-step (assoc ready :profile-complete? false) nil)))
    (is (= "/invitations/abc" (model/next-step ready "/invitations/abc")))
    (testing "only safe Tover destinations are kept"
      (is (= "/workspace" (model/next-step ready "https://evil.example/")))
      (is (= "/workspace" (model/next-step ready "//evil.example/")))
      (is (= "/workspace" (model/next-step ready "/auth/login"))))
    (is (= "/workspace" (model/next-step ready nil)))))


(deftest valid-credentials-establish-a-sign-in-and-record-user-signed-in-once
  (support/with-application
    (fn [{:keys [browser state session-store] :as services}]
      (let [response (sign-in! browser "owner@example.test" fixtures/password)
            key      (support/session-key browser)
            identity (access/current-identity services key)
            [event]  (signed-in-events state)]
        (is (= 303 (:status response)))
        (is (= "/workspace" (get-in response [:headers "location"])))
        (is (= fixtures/owner-id (:user-id identity)))
        (testing "sign-in never selects a workspace or accepts anything"
          (is (nil? (:workspace-id identity))))
        (is (= {:user-id fixtures/owner-id :session-id (:session-id identity)} (select-keys event [:user-id :session-id])))
        (is (= 30 (.toDays (java.time.Duration/between (:authenticated-at event) (:expires-at event)))))
        (testing "a usable sign-in continues without another prompt or fact"
          (let [again ((:request! browser) :get "/auth/login")]
            (is (= 303 (:status again)))
            (is (= "/workspace" (get-in again [:headers "location"])))
            (is (= 1 (count (signed-in-events state))))))
        (is (some? session-store))))))


(deftest required-profile-comes-first-and-keeps-the-requested-destination
  (support/with-application
    (fn [{:keys [browser]}]
      (let [response (sign-in! browser "unnamed@example.test" fixtures/password {"return-to" "/invitations/abc"})]
        (is (= "/account/profile?return-to=%2Finvitations%2Fabc" (get-in response [:headers "location"])))))))


(deftest requested-screen-wins-over-the-chooser
  (support/with-application
    (fn [{:keys [browser]}]
      (let [response (sign-in! browser "owner@example.test" fixtures/password {"return-to" "/invitations/abc"})]
        (is (= "/invitations/abc" (get-in response [:headers "location"])))))))


(deftest a-destination-kept-in-the-session-survives-sign-in-and-profile-setup
  ;; A protected route (an invitation) keeps its path as :identity/return-to before sending to Sign In.
  (support/with-application
    (fn [{:keys [browser session-store]}]
      (let [{:keys [request!]} browser]
        (request! :get "/auth/login")
        (session/update-session! session-store (support/session-key browser)
                                 (fn [row] [(assoc-in row [:data :identity/return-to] "/invitations/abc") nil]))
        (let [response (request! :post sign-in-url {"email" "unnamed@example.test" "password" fixtures/password})]
          (is (= "/account/profile?return-to=%2Finvitations%2Fabc" (get-in response [:headers "location"]))))))))


(deftest rejected-sign-in-is-neutral-retains-the-email-and-clears-the-password
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      ;; An unconfirmed account and a wrong password get the same answer.
      (swap! state update-in [:db :auth.users fixtures/member-id] dissoc :email_confirmed_at)
      (let [wrong       (sign-in! browser "owner@example.test" "wrong-password")
            unconfirmed (sign-in! browser "member@example.test" fixtures/password)]
        (doseq [[response email] [[wrong "owner@example.test"] [unconfirmed "member@example.test"]]]
          (is (= 400 (:status response)))
          (is (= "Invalid email or password." (alert (:body response))))
          (is (str/includes? (:body response) (str "value=\"" email "\"")))
          (is (not (str/includes? (:body response) fixtures/password)))
          (is (str/includes? (:body response) "/auth/resend"))
          (is (str/includes? (:body response) "/auth/recover")))
        (is (nil? (access/current-identity services (support/session-key browser))))
        (is (empty? (signed-in-events state)))))))


(deftest temporary-service-failure-is-not-a-wrong-password
  (support/with-application
    #(assoc % :provider {:request! (fn [_] (provider/fail! :unavailable 503 false))})
    (fn [{:keys [browser]}]
      (let [response (sign-in! browser "owner@example.test" fixtures/password)]
        (is (= 503 (:status response)))
        (is (= "The service is unavailable. Please try again." (alert (:body response))))))))


(deftest repeated-attempts-are-limited-and-say-when-to-retry
  (support/with-application
    #(assoc % :provider {:request! (fn [_] (provider/fail! :rate-limited 429 false {:retry-after 30}))})
    (fn [{:keys [browser]}]
      (let [response (sign-in! browser "owner@example.test" fixtures/password)]
        (is (= 429 (:status response)))
        (is (= "Too many requests. Try again in 30 seconds." (alert (:body response))))))))


(deftest failed-identity-read-keeps-the-sign-in-and-offers-retry
  (support/with-application
    (fn [{:keys [browser state session-store]}]
      (let [read     access/current-identity
            response (with-redefs [access/current-identity
                                   (fn [services key]
                                     (if (:tokens (session/session session-store key))
                                       (throw (ex-info "directory unavailable" {}))
                                       (read services key)))]
                       (sign-in! browser "owner@example.test" fixtures/password))]
        (is (= 503 (:status response)))
        (is (str/includes? (:body response) "You are signed in, but your account could not be loaded."))
        (is (some? (support/session-key browser)))
        (is (= 1 (count (signed-in-events state))))
        (testing "Retry repeats only the read, not the sign-in"
          (let [retry ((:request! browser) :post (bel/action-url ::web/continue) {})]
            (is (= 303 (:status retry)))
            (is (= "/workspace" (get-in retry [:headers "location"])))
            (is (= 1 (count (signed-in-events state))))))))))


(deftest an-obsolete-attempt-never-replaces-the-current-sign-in
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (support/sign-in! services browser "owner@example.test")
      (let [key      (support/session-key browser)
            response ((:request! browser) :post sign-in-url {"email" "member@example.test" "password" fixtures/password})]
        (is (= 303 (:status response)))
        (is (= key (support/session-key browser)))
        (is (= fixtures/owner-id (:user-id (access/current-identity services key))))
        (is (empty? (signed-in-events state)))))))


(deftest a-signed-out-request-continues-there-after-sign-in-and-profile-setup
  (support/with-application
    (fn [{:keys [browser]}]
      (let [path     "/invitations/7f4a2a8e-1c0b-4d7e-9a55-0f1e2d3c4b5a"
            redirect ((:request! browser) :get path)]
        (is (= "/auth/login" (get-in redirect [:headers "location"])))
        (testing "the requested path is kept for sign-in, not acted on"
          (is (= path (get-in (sign-in! browser "owner@example.test" fixtures/password) [:headers "location"])))))))
  (support/with-application
    (fn [{:keys [browser]}]
      ((:request! browser) :get "/invitations/abc?x=1")
      (is (= "/account/profile?return-to=%2Finvitations%2Fabc%3Fx%3D1"
             (get-in (sign-in! browser "unnamed@example.test" fixtures/password) [:headers "location"]))))))


(deftest a-complete-profile-uses-the-display-name-rule
  (let [family "👨‍👩‍👧"]
    (is (session/profile-complete? {:display-name (apply str (repeat 100 family))}))
    (is (not (session/profile-complete? {:display-name (apply str (repeat 101 family))})))
    (is (session/profile-complete? {:display-name " Ana "}))
    (is (not (session/profile-complete? {:display-name "   "})))
    (is (not (session/profile-complete? nil)))))
