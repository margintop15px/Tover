(ns tover.identity-test
  (:require
    [bel.core :as bel]
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [tover.auth.core :as auth]
    [tover.auth.sessions :as sessions]
    [tover.support :as support]))


(deftest signup-then-verification-creates-workspace-with-owner-membership
  (support/with-application
    (fn [{:keys [browser state session-store]}]
      (let [{:keys [request!]} browser]
        (request! :get "/auth/signup")
        (let [response (request! :post (bel/action-url :tover.auth.web/sign-up) {"email" "signup@example.test" "password" "signup-password" "workspace-name" "New signup workspace"})
              email    (last (:emails @state))]
          (is (= 200 (:status response)))
          (is (str/includes? (:body response) "Check your email"))
          (request! :get "/auth/confirm" {"token_hash" (:token-hash email) "type" "signup"})
          (is (= 303 (:status (request! :post (bel/action-url :tover.auth.web/verify-email) {"token-hash" (:token-hash email) "type" "signup"}))))
          (let [workspace (request! :get "/workspace")]
            (is (str/includes? (:body workspace) "New signup workspace")))
          (is (= 303 (:status (request! :post (bel/action-url :tover.workspaces.web/create-workspace) {"name" "Another workspace"}))))
          (is (= "Another workspace"
                 (get-in @state [:workspaces (:workspace-id (sessions/session session-store (support/session-key browser))) :name])))
          (is (str/includes? (:body (request! :get "/workspace")) "Another workspace")))))))


(deftest password-setup-is-required-before-workspace-administration
  (support/with-application
    (fn [{:keys [browser auth users state]}]
      (auth/recover! auth {:email (:email (first users))})
      (let [email              (last (:emails @state))
            {:keys [request!]} browser]
        (request! :get "/auth/confirm" {"token_hash" (:token-hash email) "type" "recovery"})
        (request! :post (bel/action-url :tover.auth.web/verify-email) {"token-hash" (:token-hash email) "type" "recovery"})
        (request! :get "/auth/password")
        (is (= "/auth/password" (get-in (request! :get "/team") [:headers "location"])))
        (is (= 409 (:status (request! :post (bel/action-url :tover.workspaces.web/create-workspace) {"name" "Blocked workspace"}))))
        (is (= 409 (:status (request! :post "/action/tover.teams.core/invite" {"email" "blocked@example.test" "role" "member"}))))
        (is (empty? (:invitations @state)))))))
