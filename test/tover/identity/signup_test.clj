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
    [tover.identity.sign-in-test :refer [alert plain signed-in-events]]
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
      (let [memberships (count (get-in @state [:db :memberships]))
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
          (is (= "en" (get-in @state [:db :profiles (:id user) :language])))
          (is (= memberships (count (get-in @state [:db :memberships]))))
          (is (empty? (:journal @state)))
          (is (not-any? :tokens (vals (get-in @state [:db :sessions])))))))))


(deftest an-existing-account-gets-the-same-neutral-answer-and-keeps-its-preferences
  (support/with-application
    (fn [{:keys [browser state]}]
      (let [before   (get-in @state [:db :profiles fixtures/owner-id])
            users    (count (get-in @state [:db :auth.users]))
            response (register! browser "owner@example.test" "another-password")]
        (is (= 200 (:status response)))
        (is (str/includes? (:body response) "Email entered: owner@example.test"))
        (is (nil? (alert (:body response))))
        (is (= users (count (get-in @state [:db :auth.users]))))
        (is (= before (get-in @state [:db :profiles fixtures/owner-id])))))))


(deftest refused-input-shows-a-field-error-and-does-not-start-the-limit
  (let [calls (atom 0)]
    (support/with-application
      (fn [services] (update services :provider (fn [{:keys [request!]}] {:request! #(do (swap! calls inc) (request! %))})))
      (fn [{:keys [browser state]}]
        (doseq [[password message] [["" "Enter a password of at least 6 characters."]
                                    ["12345" "Enter a password of at least 6 characters."]
                                    [(apply str (repeat 37 "я")) "This password is too long. Use a shorter one."]]]
          (let [response (register! browser alice password)]
            (is (= 422 (:status response)))
            (is (= message (alert (:body response))))
            (is (str/includes? (:body response) (str "value=\"" alice "\"")))
            (is (or (= "" password) (not (str/includes? (:body response) password))))))
        (is (= "Enter a valid email address." (alert (:body (register! browser "alice" "a-good-password")))))
        (is (zero? @calls))
        (is (= 200 (:status (register! browser alice "a-good-password"))))
        (is (some? (user-row state alice)))))))


(defn- answer
  "What a visitor can tell apart about a response, with `email` masked."
  {:malli/schema [:=> [:cat :map :string] :map]}
  [response email]
  (update (select-keys response [:status :body]) :body plain email))


(defn- resend!
  {:malli/schema [:=> [:cat :map :string] :map]}
  [{:keys [request!]} email]
  (request! :get "/auth/resend")
  (request! :post (bel/action-url ::resend/resend) {"email" email}))


(deftest repeated-signup-looks-the-same-for-every-email
  (support/with-application
    (fn [{:keys [browser state]}]
      (swap! state update-in [:db :auth.users fixtures/member-id] dissoc :email_confirmed_at)
      (let [emails  [alice "member@example.test" "owner@example.test"]
            answers (fn [] (set (map #(answer (register! browser % "a-good-password") %) emails)))
            account (get-in @state [:db :auth.users fixtures/owner-id])
            profile (get-in @state [:db :profiles fixtures/owner-id])
            first?  (answers)
            again   (answers)]
        (is (= 1 (count first?) (count again)))
        (is (= 200 (:status (first first?))))
        (is (= 429 (:status (first again))))
        (is (str/includes? (:body (first again)) "Check your email"))
        (is (str/includes? (:body (first again)) "Try again in 60 seconds."))
        (is (= account (get-in @state [:db :auth.users fixtures/owner-id])))
        (is (= profile (get-in @state [:db :profiles fixtures/owner-id])))))))


(deftest signup-and-resend-share-the-limit
  (support/with-application
    (fn [{:keys [browser]}]
      (doseq [email [alice "owner@example.test"]]
        (register! browser email "a-good-password")
        (is (= 429 (:status (resend! browser (str/upper-case email)))))))))


(deftest the-outcome-of-the-provider-is-not-shown
  (doseq [[submit! label] [[#(register! % alice "a-good-password") "signup"] [#(resend! % alice) "resend"]]
          :let            [accepted (support/with-application (fn [{:keys [browser]}] (answer (submit! browser) alice)))]
          [code status uncertain?] [[:rate-limited 429 false] [:unavailable 503 true] [:unavailable 503 false] [:weak-password 422 false]]]
    (support/with-application
      #(assoc % :provider {:request! (fn [_] (provider/fail! code status uncertain?))})
      (fn [{:keys [browser]}]
        (is (= accepted (answer (submit! browser) alice)) (str label " " code))))))


(deftest the-answer-does-not-wait-for-the-provider
  (let [call-later! provider/call-later!
        release     (promise)
        asked       (promise)]
    (support/with-application
      #(assoc % :provider {:request! (fn [_] (deliver asked true) (deref release 5000 nil) (provider/fail! :unavailable 503 true))})
      (fn [{:keys [browser]}]
        (with-redefs [provider/call-later! call-later!]
          (try
            (is (= 200 (:status (register! browser alice "a-good-password"))))
            (is (= true (deref asked 2000 nil)))
            (finally (deliver release true))))))))


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
          (is (not-any? #(= (:id user) (:user_id %)) (vals (get-in @state [:db :memberships])))))
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


(deftest resend-has-the-same-limit-for-every-email
  (support/with-application
    (fn [{:keys [browser state]}]
      (register! browser alice "a-good-password")
      (swap! state assoc :elapsed 60)
      (let [emails  ["nobody@example.test" alice "owner@example.test"]
            answers (fn [] (set (map #(answer (resend! browser %) %) emails)))
            first?  (answers)
            again   (answers)]
        (is (= 1 (count first?) (count again)))
        (is (str/includes? (:body (first first?)) "If confirmation is needed and the message can be sent"))
        (is (= 429 (:status (first again))))
        (testing "a request over the limit is not sent and the account is unchanged"
          (is (= 2 (count (filter #(= alice (:email %)) (:emails @state)))))
          (is (= 1 (count (filter #(= alice (:email %)) (vals (get-in @state [:db :auth.users]))))))
          (is (some? (:email_confirmed_at (user-row state "owner@example.test")))))
        (testing "after the limit the provider is asked again"
          (swap! state assoc :elapsed 120)
          (is (= 200 (:status (resend! browser alice))))
          (is (= 3 (count (filter #(= alice (:email %)) (:emails @state))))))))))


(deftest two-clicks-at-once-ask-the-provider-once
  (let [calls (atom 0)]
    (support/with-application
      #(assoc % :provider {:request! (fn [_] (swap! calls inc) {})})
      (fn [{:keys [handler]}]
        (let [browsers (mapv (fn [_] (support/browser handler)) (range 2))
              statuses (mapv (comp :status deref) (mapv #(future (resend! % alice)) browsers))]
          (is (= [200 429] (sort statuses)))
          (is (= 1 @calls)))))))


(deftest a-signed-in-person-on-the-recovery-screen-sees-only-their-account
  (support/with-application
    (fn [{:keys [browser] :as services}]
      (support/sign-in! services browser "owner@example.test")
      (let [body (:body ((:request! browser) :get "/auth/resend"))]
        (is (str/includes? body "You are signed in as owner@example.test."))
        (is (str/includes? body "tover.identity.sign-out.web/sign-out"))))))


(deftest every-password-field-has-a-show-control
  (support/with-application
    (fn [{:keys [browser handler] :as services}]
      (let [recovering (support/browser handler)]
        (support/sign-in! services recovering "owner@example.test" {:password-required? true :uri "/auth/password"})
        (doseq [[{:keys [request!]} uri autocomplete] [[browser "/auth/login" "current-password"]
                                                       [browser "/auth/signup" "new-password"]
                                                       [browser "/auth/confirm?token_hash=abc&type=recovery" "new-password"]
                                                       [recovering "/auth/password" "new-password"]]
                :let                                  [[path query] (str/split uri #"\?")
                                                       body (:body (if query
                                                                     (request! :get path {"token_hash" "abc" "type" "recovery"})
                                                                     (request! :get path)))]]
          (is (= 1 (count (re-seq #"type=\"password\"" body)) (count (re-seq #"class=\"password-toggle\"" body))) uri)
          (is (str/includes? body (str "autocomplete=\"" autocomplete "\"")) uri)
          (is (re-find #"<button[^>]*class=\"password-toggle\"[^>]*>" body) uri)
          (is (str/includes? (re-find #"<button[^>]*class=\"password-toggle\"[^>]*>" body) "type=\"button\"") uri)
          (is (str/includes? (re-find #"<button[^>]*class=\"password-toggle\"[^>]*>" body) "aria-pressed=\"false\"") uri))))))


(deftest signing-out-of-an-invitation-survives-signup-and-profile-setup
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (let [path    (str "/invitations/" (random-uuid))
            session (support/sign-in! services browser "owner@example.test" {:uri "/workspace"})]
        ((:request! browser) :post (bel/action-url :tover.identity.sign-out.web/sign-out)
                             {"session-id" (str (:session-id session)) "return-to" path})
        ((:request! browser) :get path)
        (register! browser alice "a-good-password")
        (let [response (confirm! browser (link state alice))]
          (is (= (str "/account/profile?return-to=" (java.net.URLEncoder/encode path "UTF-8"))
                 (get-in response [:headers "location"]))))))))
