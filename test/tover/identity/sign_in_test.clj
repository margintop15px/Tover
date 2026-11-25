(ns tover.identity.sign-in-test
  "Board chapter 02 · Sign in and find workspace access (slice prose; the chapter has no ## Examples)."
  (:require
    [bel.core :as bel]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [tover.fixtures :as fixtures]
    [tover.identity.access :as access]
    [tover.identity.limits :as limits]
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


(defn plain
  "A page body without the parts that differ per request (the CSRF token) and the entered email."
  {:malli/schema [:=> [:cat :string :string] :string]}
  [body email]
  (cond-> (str/replace body #"name=\"__anti-forgery-token\" value=\"[^\"]*\"|value=\"[^\"]*\" name=\"__anti-forgery-token\"" "")
    (seq email) (str/replace email "EMAIL")))


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


(deftest a-visit-from-an-invitation-link-says-to-sign-in-or-create-an-account
  (support/with-application
    (fn [{:keys [browser]}]
      (let [{:keys [request!]} browser
            hint               "role=\"status\">To open your invitation"]
        (is (not (str/includes? (:body (request! :get "/auth/login")) hint)))
        (is (= "/auth/login" (get-in (request! :get (str "/invitations/" (random-uuid))) [:headers "location"])))
        (is (str/includes? (:body (request! :get "/auth/login")) hint))
        (is (str/includes? (:body (request! :get "/auth/signup")) hint))))))


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


(defn- from
  "One sign-in attempt from a network address, in a browser of its own."
  {:malli/schema [:=> [:cat [:fn ifn?] :string :string :string] :map]}
  [handler address email password]
  (let [{:keys [request!]} (support/browser handler)]
    (request! :get "/auth/login")
    (request! :post sign-in-url {"email" email "password" password} {:address address})))


(defn- fail-times!
  {:malli/schema [:=> [:cat [:fn ifn?] :int :string :string] :nil]}
  [handler n address email]
  (dotimes [_ n] (from handler address email "wrong-password")))


(defn- record-failures!
  "Failed attempts straight in the store, for limits too large to reach through pages."
  {:malli/schema [:=> [:cat :any [:sequential [:tuple :string :string]]] :nil]}
  [store pairs]
  (doseq [[email address] pairs]
    (limits/-settle-attempt! store (:attempt-id (limits/-start-attempt! store (limits/email-key email) address)) :failed)))


(defn- counting
  "Services whose provider calls are counted in `calls`."
  {:malli/schema [:=> [:cat :any] [:fn ifn?]]}
  [calls]
  (fn [services]
    (update services :provider (fn [{:keys [request!]}] {:request! #(do (swap! calls inc) (request! %))}))))


(def ^:private limited
  "Too many sign-in attempts. Try again in 15 min.")


(deftest limit-looks-the-same-for-every-email
  (support/with-application
    (fn [{:keys [handler state]}]
      (swap! state update-in [:db :auth.users fixtures/member-id] dissoc :email_confirmed_at)
      (let [answers (fn [email]
                      (mapv (fn [_] (let [response (from handler "192.0.2.7" email "wrong-password")]
                                      (update (select-keys response [:status :body]) :body plain email)))
                            (range 11)))
            [unknown & others] (map answers ["nobody@example.test" "member@example.test" "owner@example.test"])]
        (is (every? #{unknown} others))
        (is (= (conj (vec (repeat 10 400)) 429) (map :status unknown)))
        (is (= limited (alert (:body (last unknown)))))
        (is (str/includes? (:body (last unknown)) "/auth/recover"))))))


(deftest guessing-many-emails-from-one-address
  (support/with-application
    (fn [{:keys [handler limits]}]
      (record-failures! limits (map (fn [n] [(str "guess" n "@example.test") "192.0.2.7"]) (range 100)))
      (let [response (from handler "192.0.2.7" "owner@example.test" fixtures/password)]
        (is (= 429 (:status response)))
        (is (= limited (alert (:body response)))))
      (is (= 303 (:status (from handler "192.0.2.8" "owner@example.test" fixtures/password)))))))


(deftest service-failure-is-not-a-failed-attempt
  (doseq [[code status uncertain? shown] [[:unavailable 503 false 503] [:unavailable 503 true 503] [:rate-limited 429 false 429]]]
    (support/with-application
      #(assoc % :provider {:request! (fn [_] (provider/fail! code status uncertain?))})
      (fn [{:keys [handler state]}]
        (dotimes [_ 11]
          (is (= shown (:status (from handler "192.0.2.7" "owner@example.test" fixtures/password)))))
        (is (empty? (:attempts @state)))))))


(deftest a-sign-in-that-cannot-be-recorded-is-not-a-failed-attempt
  (support/with-application
    (fn [{:keys [handler state]}]
      (with-redefs [web/record! (fn [& _] (throw (ex-info "unavailable" {})))]
        (is (= 503 (:status (from handler "192.0.2.7" "owner@example.test" fixtures/password)))))
      (is (empty? (:attempts @state))))))


(deftest tenth-failure-from-one-place
  (let [calls (atom 0)]
    (support/with-application
      (counting calls)
      (fn [{:keys [handler state]}]
        (fail-times! handler 10 "192.0.2.7" "owner@example.test")
        (reset! calls 0)
        (let [response (from handler "192.0.2.7" "  Owner@Example.test " fixtures/password)]
          (is (= 429 (:status response)))
          (is (= limited (alert (:body response))))
          (is (str/includes? (:body response) "Owner@Example.test"))
          (is (not (str/includes? (:body response) fixtures/password)))
          (testing "it is not sent to the provider"
            (is (zero? @calls))))
        (is (= 303 (:status (from handler "192.0.2.8" "owner@example.test" fixtures/password))))
        (testing "and is allowed again after the window"
          (swap! state assoc :elapsed (* 15 60))
          (is (= 303 (:status (from handler "192.0.2.7" "owner@example.test" fixtures/password)))))))))


(deftest one-email-from-many-addresses
  (support/with-application
    (fn [{:keys [handler limits]}]
      (record-failures! limits (map (fn [n] ["owner@example.test" (str "198.51.100." n)]) (range 100)))
      (is (= 429 (:status (from handler "192.0.2.7" "owner@example.test" fixtures/password)))))))


(deftest success-clears-only-its-own-count
  (support/with-application
    (fn [{:keys [handler]}]
      (fail-times! handler 9 "192.0.2.7" "owner@example.test")
      (fail-times! handler 10 "192.0.2.8" "owner@example.test")
      (is (= 303 (:status (from handler "192.0.2.7" "owner@example.test" fixtures/password))))
      (fail-times! handler 9 "192.0.2.7" "owner@example.test")
      (is (= 303 (:status (from handler "192.0.2.7" "owner@example.test" fixtures/password))))
      (is (= 429 (:status (from handler "192.0.2.8" "owner@example.test" fixtures/password)))))))


(deftest one-ipv6-network
  (support/with-application
    (fn [{:keys [handler]}]
      (doseq [n (range 10)] (from handler (str "2001:db8:1:2::" n) "owner@example.test" "wrong-password"))
      (is (= 429 (:status (from handler "2001:db8:1:2:ffff::1" "owner@example.test" fixtures/password))))
      (is (= 303 (:status (from handler "2001:db8:1:3::1" "owner@example.test" fixtures/password)))))))


(deftest attempts-at-once
  (let [calls (atom 0)]
    (support/with-application
      #(assoc % :provider {:request! (fn [_] (swap! calls inc) (Thread/sleep 300) (provider/fail! :invalid-credentials 400 false))})
      (fn [{:keys [handler]}]
        (let [statuses (mapv deref (mapv (fn [_] (future (:status (from handler "192.0.2.7" "owner@example.test" "wrong-password")))) (range 11)))]
          (is (= {400 10 429 1} (frequencies statuses)))
          (is (= 10 @calls)))))))


(deftest a-disabled-account-answers-like-a-wrong-password-and-counts
  (is (= :invalid-credentials (get @#'provider/error-codes "user_banned"))))


(deftest input-the-provider-refuses-counts-as-a-wrong-password
  (support/with-application
    #(assoc % :provider {:request! (fn [_] (provider/fail! :invalid-input 400 false))})
    (fn [{:keys [handler state]}]
      (let [response (from handler "192.0.2.7" "owner@example.test" "x")]
        (is (= 400 (:status response)))
        (is (= "Invalid email or password." (alert (:body response)))))
      (is (= ["failed"] (map :status (vals (:attempts @state))))))))


(deftest a-blank-email-or-password-starts-no-attempt
  (support/with-application
    (fn [{:keys [handler state]}]
      (doseq [[email password] [["" "x"] ["owner@example.test" ""]]]
        (is (= "Invalid email or password." (alert (:body (from handler "192.0.2.7" email password))))))
      (is (empty? (:attempts @state))))))


(deftest an-address-given-by-the-visitor-is-ignored
  (support/with-application
    (fn [{:keys [handler state]}]
      (let [{:keys [request!]} (support/browser handler)]
        (request! :get "/auth/login")
        (request! :post sign-in-url {"email" "owner@example.test" "password" "wrong"}
                  {:address "192.0.2.7" :headers {"x-forwarded-for" "203.0.113.5"}}))
      (is (= ["192.0.2.7"] (map :address (vals (:attempts @state))))))))


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


(deftest an-expired-sign-in-is-signed-out-and-signs-in-again
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (let [{:keys [key]} (support/sign-in! services browser "owner@example.test" {:workspace-id fixtures/workspace-id})]
        (swap! state assoc-in [:db :sessions key :expires_at] (.minusSeconds (java.time.Instant/now) 1))
        (is (nil? (access/current-identity services key)))
        (is (= "/auth/login" (get-in ((:request! browser) :get "/workspace") [:headers "location"])))
        (is (= 303 (:status (sign-in! browser "owner@example.test" fixtures/password))))
        (is (= fixtures/owner-id (:user-id (access/current-identity services (support/session-key browser)))))))))


(deftest a-complete-profile-uses-the-display-name-rule
  (let [family "👨‍👩‍👧"]
    (is (session/profile-complete? {:display-name (apply str (repeat 100 family))}))
    (is (not (session/profile-complete? {:display-name (apply str (repeat 101 family))})))
    (is (session/profile-complete? {:display-name " Ana "}))
    (is (not (session/profile-complete? {:display-name "   "})))
    (is (not (session/profile-complete? nil)))))
