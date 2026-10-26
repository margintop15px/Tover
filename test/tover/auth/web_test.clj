(ns tover.auth.web-test
  (:require
    [bel.core :as bel]
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [tover.auth.core :as auth]
    [tover.auth.sessions :as sessions]
    [tover.support :as support]
    [tover.workspaces.core :as workspaces]))


(deftest login-requires-csrf-and-origin-and-rotates-an-opaque-cookie
  (support/with-application
    (fn [{:keys [browser users session-store]}]
      (let [{:keys [request! state]} browser
            login-page               (request! :get "/auth/login")
            anonymous-key            (support/session-key browser)
            input                    {"email" (:email (first users)) "password" (:password (first users))}]
        (is (= 200 (:status login-page)))
        (is (= 403 (:status (request! :post (bel/action-url :tover.auth.web/sign-in) input {:csrf? false}))))
        (is (= 403 (:status (request! :post (bel/action-url :tover.auth.web/sign-in) input {:origin "https://evil.example.test"}))))
        (is (= 403 (:status (request! :post (bel/action-url :tover.auth.web/sign-in) input {:csrf? false :headers {"datastar-request" "true"}}))))
        (let [response (request! :post (bel/action-url :tover.auth.web/sign-in) input)
              key      (support/session-key browser)
              row      (sessions/session session-store key)]
          (is (= 303 (:status response)))
          (is (= "/workspace" (get-in response [:headers "location"])))
          (is (not= anonymous-key key))
          (is (nil? (sessions/session session-store anonymous-key)))
          (is (re-matches #"[A-Za-z0-9_-]{43}" key))
          (is (not (str/includes? (:cookie @state) (get-in row [:tokens :access-token]))))
          (is (not (str/includes? (:cookie @state) (get-in row [:tokens :refresh-token]))))
          (is (str/includes? (str/join " " (get-in response [:headers "Set-Cookie"])) "HttpOnly"))
          (is (str/includes? (str/join " " (get-in response [:headers "Set-Cookie"])) "SameSite=Lax"))
          (is (= "private, no-store" (get-in response [:headers "cache-control"]))))))))


(deftest wrong-password-preserves-email-and-clears-password-value
  (support/with-application
    (fn [{:keys [browser users]}]
      (let [{:keys [request!]} browser
            user               (first users)]
        (request! :get "/auth/login")
        (let [response (request! :post (bel/action-url :tover.auth.web/sign-in) {"email" (:email user) "password" "wrong-private-password"})]
          (is (= 400 (:status response)))
          (is (= "text/html; charset=utf-8" (get-in response [:headers "content-type"])))
          (is (= "invalid-credentials" (get-in response [:headers "x-tover-error"])))
          (is (str/includes? (:body response) (str "value=\"" (:email user) "\"")))
          (is (str/includes? (:body response) "Invalid email or password."))
          (is (not (str/includes? (:body response) "wrong-private-password"))))))))


(deftest unexpected-form-errors-escape-unchanged
  (doseq [error [(ex-info "Unexpected failure" {:type :unexpected})
                 (IllegalStateException. "Unexpected failure")]]
    (support/with-application
      (fn [{:keys [workspaces] :as services}]
        (assoc services :workspaces (support/workspace-provider workspaces
                                                                {:accept-invites (fn [_] (throw error))})))
      (fn [{:keys [browser users]}]
        (let [{:keys [request!]} browser
              user               (first users)]
          (request! :get "/auth/login")
          (is (identical? error
                          (try (request! :post (bel/action-url :tover.auth.web/sign-in) {"email" (:email user) "password" (:password user)})
                               nil
                               (catch Throwable caught caught)))))))))


(deftest password-form-requires-an-authenticated-session
  (support/with-application
    (fn [{:keys [auth browser users]}]
      (let [{:keys [request!]} browser
            user               (first users)
            page               (request! :get "/auth/password")]
        (is (= 303 (:status page)))
        (is (= "/auth/login" (get-in page [:headers "location"])))
        (request! :get "/auth/login")
        (is (= 401 (:status (request! :post (bel/action-url :tover.auth.web/set-password) {"password" "untrusted-replacement"}))))
        (is (= (:user-id user) (get-in (auth/sign-in! auth user) [:user :user-id])))))))


(deftest confirmation-get-does-not-consume-link-and-post-requires-password-setup
  (support/with-application
    (fn [{:keys [auth state browser session-store]}]
      (auth/invite! auth {:email "fresh@example.test"})
      (let [email              (last (:emails @state))
            {:keys [request!]} browser
            input              {"token_hash" (:token-hash email) "type" "invite"}]
        (is (= 200 (:status (request! :get "/auth/confirm" input))))
        (is (= 1 (count (:emails @state))))
        (is (nil? (:tokens (sessions/session session-store (support/session-key browser)))))
        (let [verified (request! :post (bel/action-url :tover.auth.web/verify-email) {"token-hash" (:token-hash email) "type" "invite"})]
          (is (= 303 (:status verified)))
          (is (= "/auth/password" (get-in verified [:headers "location"])))
          (is (empty? (:emails @state)))
          (let [page (request! :get "/auth/password")]
            (is (= 200 (:status page)))
            (is (not (str/includes? (:body page) "fresh@example.test"))))
          (is (= 303 (:status (request! :post (bel/action-url :tover.auth.web/set-password) {"password" "fresh-private-password"}))))
          (is (false? (get-in (sessions/session session-store (support/session-key browser)) [:data :identity/password-required?])))
          (is (map? (auth/sign-in! auth {:email "fresh@example.test" :password "fresh-private-password"}))))))))


(deftest permanent-refresh-failure-does-not-block-the-login-page
  (support/with-application
    (fn [{:keys [auth] :as services}]
      (assoc services :auth (support/auth-provider auth {:refresh (fn [_] (auth/fail! :invalid-refresh-token 400 false))})))
    (fn [{:keys [browser users session-store]}]
      (support/login! browser (first users))
      (sessions/update-session! session-store (support/session-key browser)
                                (fn [row] [(assoc-in row [:tokens :expires-at] 0) nil]))
      (let [response ((:request! browser) :get "/auth/login")]
        (is (= 200 (:status response)))
        (is (str/includes? (:body response) "Sign in"))
        (is (nil? (:tokens (sessions/session session-store (support/session-key browser)))))))))


(deftest verification-saves-one-use-credentials-before-invitation-acceptance
  (support/with-application
    (fn [{:keys [workspaces] :as services}]
      (let [calls (atom 0)]
        (assoc services :accept-calls calls
               :workspaces (support/workspace-provider workspaces
                                                       {:accept-invites (fn [token]
                                                                          (if (= 1 (swap! calls inc))
                                                                            (auth/fail! :unavailable 503 true)
                                                                            (workspaces/accept-invites! workspaces token)))}))))
    (fn [{:keys [browser auth users state session-store accept-calls]}]
      (auth/recover! auth {:email (:email (first users))})
      (let [email              (last (:emails @state))
            {:keys [request!]} browser]
        (request! :get "/auth/confirm" {"token_hash" (:token-hash email) "type" "recovery"})
        (let [response (request! :post (bel/action-url :tover.auth.web/verify-email) {"token-hash" (:token-hash email) "type" "recovery"})]
          (is (= 503 (:status response)))
          (is (str/includes? (:body response) (bel/action-url :tover.auth.web/finish-sign-in))))
        (is (= 1 @accept-calls))
        (is (empty? (:emails @state)))
        (let [row (sessions/session session-store (support/session-key browser))]
          (is (some? (:tokens row)))
          (is (true? (get-in row [:data :identity/password-required?]))))
        (is (= 303 (:status (request! :post (bel/action-url :tover.auth.web/finish-sign-in)))))
        (is (= 2 @accept-calls))
        (is (= 200 (:status (request! :get "/auth/password"))))))))


(deftest recovery-does-not-reveal-whether-email-exists
  (support/with-application
    (fn [{:keys [browser state]}]
      (let [{:keys [request!]} browser]
        (request! :get "/auth/recover")
        (let [known   (request! :post (bel/action-url :tover.auth.web/send-recovery) {"email" "owner@example.test"})
              unknown (request! :post (bel/action-url :tover.auth.web/send-recovery) {"email" "absent@example.test"})]
          (is (= 200 (:status known) (:status unknown)))
          (is (= (:body known) (:body unknown)))
          (is (= ["owner@example.test"] (mapv :email (:emails @state)))))))))


(deftest logout-clears-local-session-even-when-upstream-is-unavailable
  (support/with-application
    (fn [{:keys [auth] :as services}]
      (assoc services :auth (support/auth-provider auth {:sign-out (fn [_] (auth/fail! :unavailable 503 true))})))
    (fn [{:keys [browser users session-store]}]
      (let [{:keys [request!]} browser]
        (support/login! browser (first users))
        (let [key      (support/session-key browser)
              response (request! :post (bel/action-url :tover.auth.web/sign-out))]
          (is (= 303 (:status response)))
          (is (= "/auth/login" (get-in response [:headers "location"])))
          (is (nil? (sessions/session session-store key)))
          (is (= "/auth/login" (get-in (request! :get "/") [:headers "location"]))))))))


(deftest transient-auth-failure-preserves-browser-session
  (let [unavailable? (atom false)]
    (support/with-application
      (fn [{:keys [auth] :as services}]
        (assoc services :auth (support/auth-provider auth
                                                     {:current-user (fn [token]
                                                                      (if @unavailable?
                                                                        (auth/fail! :unavailable 503 false)
                                                                        (auth/current-user auth token)))})))
      (fn [{:keys [browser users session-store]}]
        (support/login! browser (first users))
        (let [key    (support/session-key browser)
              before (sessions/session session-store key)]
          (reset! unavailable? true)
          (is (= 503 (:status ((:request! browser) :get "/workspace"))))
          (is (= key (support/session-key browser)))
          (is (= (:tokens before) (:tokens (sessions/session session-store key))))
          (reset! unavailable? false)
          (is (= 200 (:status ((:request! browser) :get "/workspace")))))))))


(deftest production-session-cookie-is-secure
  (support/with-application
    #(assoc % :development? false)
    (fn [{:keys [browser]}]
      (let [response ((:request! browser) :get "/auth/login")
            cookie   (str/join " " (get-in response [:headers "Set-Cookie"]))]
        (is (str/includes? cookie "; Secure"))
        (is (str/includes? cookie "; HttpOnly"))
        (is (str/includes? cookie "; SameSite=Lax"))))))
