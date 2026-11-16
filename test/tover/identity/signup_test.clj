(ns tover.identity.signup-test
  "Board chapters 01 · Create and confirm an account and 01a · Recover signup and resend confirmation
  (slice prose; the chapters have no ## Examples)."
  (:require
    [bel.core :as bel]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [tover.fixtures :as fixtures]
    [tover.identity.access :as access]
    [tover.identity.confirm.web :as confirm]
    [tover.identity.provider :as provider]
    [tover.identity.resend.web :as resend]
    [tover.identity.sign-in-test :refer [alert signed-in-events]]
    [tover.identity.signup.web :as signup]
    [tover.support :as support]))


(def alice
  "alice@example.test")


(defn register!
  {:malli/schema [:=> [:cat :map :string :string] :map]}
  [{:keys [request!]} email password]
  (request! :get "/auth/signup")
  (request! :post (bel/action-url ::signup/register) {"email" email "password" password}))


(defn user-row
  {:malli/schema [:=> [:cat :any :string] [:maybe :map]]}
  [state email]
  (some #(when (= email (:email %)) %) (vals (get-in @state [:db :auth.users]))))


(defn link
  "The query parameters of the link in the provider's last email to `email`."
  {:malli/schema [:=> [:cat :any :string] [:maybe [:map-of :string :string]]]}
  [state email]
  (when-let [{:keys [token-hash type]} (last (filter #(= email (:email %)) (:emails @state)))]
    {"token_hash" token-hash "type" (name type)}))


(defn confirm!
  "Open the link page, then explicitly confirm."
  {:malli/schema [:=> [:cat :map [:map-of :string :string]] :map]}
  [{:keys [request!]} params]
  (request! :get "/auth/confirm" params)
  (request! :post (bel/action-url ::confirm/confirm-email) {"token-hash" (get params "token_hash") "type" (get params "type")}))


(deftest registration-creates-only-an-unconfirmed-account-and-a-neutral-check-your-email
  (support/with-application
    (fn [{:keys [browser state]}]
      (let [memberships (count (get-in @state [:db :public.organization_memberships]))
            response    (register! browser alice "a-good-password")
            user        (user-row state alice)
            body        (:body response)]
        (is (= 200 (:status response)))
        (is (str/includes? body "Check your email"))
        (is (str/includes? body (str "Email entered: " alice)))
        (is (str/includes? body "/auth/signup"))
        (is (str/includes? body "/auth/login"))
        (is (str/includes? body (bel/action-url ::resend/resend)))
        (is (nil? (:email_confirmed_at user)))
        (testing "the new account starts with the signup language; nothing else is created"
          (is (= "en" (get-in @state [:db :public.profiles (:id user) :language])))
          (is (= memberships (count (get-in @state [:db :public.organization_memberships]))))
          (is (empty? (:journal @state)))
          (is (not-any? :tokens (vals (get-in @state [:db :app_private.sessions])))))))))


(deftest an-existing-account-gets-the-same-neutral-answer-and-keeps-its-preferences
  (support/with-application
    (fn [{:keys [browser state]}]
      (let [before   (get-in @state [:db :public.profiles fixtures/owner-id])
            users    (count (get-in @state [:db :auth.users]))
            response (register! browser "owner@example.test" "another-password")]
        (is (= 200 (:status response)))
        (is (str/includes? (:body response) "Email entered: owner@example.test"))
        (is (nil? (alert (:body response))))
        (is (= users (count (get-in @state [:db :auth.users]))))
        (is (= before (get-in @state [:db :public.profiles fixtures/owner-id])))))))


(deftest invalid-input-shows-a-field-error-retains-the-email-and-clears-the-password
  (support/with-application
    (fn [{:keys [browser state]}]
      (let [response (register! browser alice "")]
        (is (= 422 (:status response)))
        (is (= "Check the entered fields." (alert (:body response))))
        (is (str/includes? (:body response) (str "value=\"" alice "\"")))
        (is (nil? (user-row state alice)))))))


(deftest a-lost-registration-response-offers-recovery-and-is-not-repeated
  (let [calls (atom 0)]
    (support/with-application
      #(assoc % :provider {:request! (fn [_] (swap! calls inc) (provider/fail! :unavailable 503 true))})
      (fn [{:keys [browser]}]
        (let [response (register! browser alice "a-good-password")]
          (is (= 503 (:status response)))
          (is (str/includes? (:body response) "We could not confirm whether this request went through."))
          (is (str/includes? (:body response) (bel/action-url ::resend/resend)))
          (is (= 1 @calls)))))))


(deftest opening-the-confirmation-link-changes-nothing
  (support/with-application
    (fn [{:keys [browser state]}]
      (register! browser alice "a-good-password")
      (let [url      (link state alice)
            emails   (:emails @state)
            response ((:request! browser) :get "/auth/confirm" url)]
        (is (= 200 (:status response)))
        (is (str/includes? (:body response) (bel/action-url ::confirm/confirm-email)))
        (is (str/includes? (:body response) "/auth/resend"))
        (is (nil? (:email_confirmed_at (user-row state alice))))
        (is (= emails (:emails @state)))
        (is (empty? (:journal @state)))))))


(deftest explicit-confirmation-in-a-fresh-browser-signs-in-and-continues-to-profile-setup
  (support/with-application
    (fn [{:keys [browser state handler]}]
      (register! browser alice "a-good-password")
      (let [phone    (support/browser handler)
            response (confirm! phone (link state alice))
            user     (user-row state alice)]
        (is (= 303 (:status response)))
        (is (= "/account/profile" (get-in response [:headers "location"])))
        (is (some? (:email_confirmed_at user)))
        (is (= [(:id user)] (map :user-id (signed-in-events state))))
        (testing "confirmation creates no workspace or membership"
          (is (not-any? #(= (:id user) (:user_id %)) (vals (get-in @state [:db :public.organization_memberships])))))
        (is (= 200 (:status ((:request! phone) :get "/account/profile"))))))))


(deftest a-used-link-routes-to-signup-recovery-without-claiming-anything
  (support/with-application
    (fn [{:keys [browser state handler]}]
      (register! browser alice "a-good-password")
      (let [url (link state alice)]
        (confirm! (support/browser handler) url)
        (let [again    (support/browser handler)
              response (confirm! again url)]
          (is (= 400 (:status response)))
          (is (= "This link has expired or has already been used." (alert (:body response))))
          (is (str/includes? (:body response) (bel/action-url ::resend/resend)))
          (is (= 1 (count (signed-in-events state)))))))))


(deftest a-signed-in-person-must-keep-the-account-or-sign-out-before-using-a-link
  (support/with-application
    (fn [{:keys [browser state handler] :as services}]
      (register! (support/browser handler) alice "a-good-password")
      (support/sign-in! services browser "owner@example.test")
      (let [url      (link state alice)
            key      (support/session-key browser)
            page     ((:request! browser) :get "/auth/confirm" url)
            response ((:request! browser) :post (bel/action-url ::confirm/confirm-email)
                                          {"token-hash" (get url "token_hash") "type" "signup"})]
        (is (str/includes? (:body page) "You are signed in as owner@example.test."))
        (is (str/includes? (:body page) "Continue with this account"))
        (is (str/includes? (:body page) "tover.identity.sign-out.web/sign-out"))
        (is (= 409 (:status response)))
        (testing "the link stays unused and the current sign-in is kept"
          (is (nil? (:email_confirmed_at (user-row state alice))))
          (is (= fixtures/owner-id (:user-id (access/current-identity services key)))))))))


(deftest resend-is-neutral-for-every-email
  (support/with-application
    (fn [{:keys [browser state]}]
      (register! browser alice "a-good-password")
      (let [{:keys [request!]} browser
            resend!            #(request! :post (bel/action-url ::resend/resend) {"email" %})
            first-click        (resend! alice)
            second-click       (resend! alice)
            unknown            (resend! "nobody@example.test")
            confirmed          (resend! "owner@example.test")]
        (doseq [response [first-click second-click unknown confirmed]]
          (is (= 200 (:status response)))
          (is (str/includes? (:body response) "If confirmation is needed and the message can be sent"))
          (is (nil? (alert (:body response)))))
        (testing "two clicks create no second account and confirmed accounts stay confirmed"
          (is (= 1 (count (filter #(= alice (:email %)) (vals (get-in @state [:db :auth.users]))))))
          (is (some? (:email_confirmed_at (user-row state "owner@example.test"))))
          (is (= 3 (count (filter #(= alice (:email %)) (:emails @state)))))
          (is (empty? (filter #(#{"nobody@example.test" "owner@example.test"} (:email %)) (:emails @state)))))))))


(deftest a-throttled-resend-says-when-to-retry-without-claiming-delivery
  (support/with-application
    #(assoc % :provider {:request! (fn [_] (provider/fail! :rate-limited 429 false {:retry-after 60}))})
    (fn [{:keys [browser]}]
      (let [_        ((:request! browser) :get "/auth/resend")
            response ((:request! browser) :post (bel/action-url ::resend/resend) {"email" alice})]
        (is (= 429 (:status response)))
        (is (str/includes? (:body response) "Too many requests. Try again in 60 seconds."))
        (is (str/includes? (:body response) "/auth/login"))))))


(deftest a-signed-in-person-on-the-recovery-screen-sees-only-their-account
  (support/with-application
    (fn [{:keys [browser] :as services}]
      (support/sign-in! services browser "owner@example.test")
      (let [body (:body ((:request! browser) :get "/auth/resend"))]
        (is (str/includes? body "You are signed in as owner@example.test."))
        (is (str/includes? body "tover.identity.sign-out.web/sign-out"))))))
