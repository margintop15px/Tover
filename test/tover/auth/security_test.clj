(ns tover.auth.security-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [reitit.ring :as ring]
    [tover.auth.core :as auth]
    [tover.auth.security :as security]
    [tover.auth.sessions :as sessions]
    [tover.support :as support]))


(deftest protected-requests-use-current-server-identity
  (let [{:keys [users context] :as services} (support/authenticated-services)
        handler (security/wrap-identity #(hash-map :status 200 :identity (:tover/identity %)) services)
        response (handler (merge (support/request services :post "/action/anything")
                                 {:params {:user-id (str (:user-id (last users))) :role "owner" :workspace-id "forged"}}))]
    (is (= 200 (:status response)))
    (is (= (:user-id (first users)) (get-in response [:identity :user-id])))
    (is (= (:workspace-id context) (get-in response [:identity :workspace-id])))
    (is (= :owner (get-in response [:identity :role])))))


(deftest route-policy-protects-new-pages-without-path-prefix-rules
  (let [services (support/authenticated-services)]
    (doseq [policy [:authenticated :workspace :manager :password :finish]]
      (let [handler    (security/wrap-identity (constantly {:status 200}) services policy)
            document   (handler {:uri "/new-internal-page" :request-method :get :headers {}})
            navigation (handler {:uri     "/new-internal-page"        :request-method :get
                                 :headers {"datastar-request" "true"}})
            mutation   (handler {:uri "/new-internal-page" :request-method :post :headers {}})]
        (is (= {:status 303 :headers {"location" "/auth/login"} :body ""} document))
        (is (= 401 (:status navigation)))
        (is (= 401 (:status mutation)))))
    (let [handler (security/wrap-identity (constantly {:status 200}) services :optional)]
      (is (= 200 (:status (handler {:uri "/auth/login" :request-method :get :headers {}})))))))


(deftest membership-revocation-invalidates-old-page-before-action-execution
  (let [{:keys [state users workspace-id other-workspace-id key session-store] :as services} (support/authenticated-services)
        calls (atom 0)
        handler (security/wrap-identity (fn [_] (swap! calls inc) {:status 200}) services)
        old-request (support/request services :post "/action/anything")]
    (swap! state assoc-in [:memberships workspace-id (:user-id (first users)) :status] :suspended)
    (let [response (handler old-request)]
      (is (= 409 (:status response)))
      (is (= "workspace-changed" (get-in response [:headers "x-tover-error"])))
      (is (zero? @calls))
      (is (= other-workspace-id (:workspace-id (sessions/session session-store key)))))))


(deftest no-active-membership-cannot-enter-operational-handlers
  (let [{:keys [state users workspace-id other-workspace-id] :as services} (support/authenticated-services)
        handler (security/wrap-identity (constantly {:status 200}) services)
        user-id (:user-id (first users))]
    (doseq [workspace [workspace-id other-workspace-id]]
      (swap! state assoc-in [:memberships workspace user-id :status] :suspended))
    (is (= "/workspace" (get-in (handler (support/request services :get "/")) [:headers "location"])))
    (is (= 409 (:status (handler (support/request services :post "/action/anything")))))))


(deftest every-protected-mutation-rejects-stale-context
  (let [{:keys [session-store key other-workspace-id] :as services} (support/authenticated-services)
        calls (atom 0)
        handler (fn [policy]
                  (security/wrap-identity (fn [_] (swap! calls inc) {:status 200}) services policy))]
    (sessions/select-workspace! session-store key other-workspace-id)
    (doseq [[uri policy] [["/action/anything" :workspace] ["/view/anything" :workspace]
                          ["/action/tover.workspaces.web/select-workspace" :authenticated] ["/action/tover.workspaces.web/create-workspace" :authenticated]
                          ["/action/tover.teams.core/invite" :manager] ["/action/tover.teams.core/resend" :manager] ["/action/tover.teams.core/cancel" :manager]
                          ["/action/tover.teams.core/recovery" :manager] ["/action/tover.auth.web/set-password" :password] ["/action/tover.auth.web/sign-out" :logout]]]
      (testing uri
        (let [response ((handler policy) (support/request services :post uri))]
          (is (= 409 (:status response)))
          (is (= "workspace-changed" (get-in response [:headers "x-tover-error"]))))))
    (is (zero? @calls))))


(deftest datastar-navigation-rejects-stale-page-context
  (let [{:keys [session-store key other-workspace-id] :as services} (support/authenticated-services)
        handler (security/wrap-identity (constantly {:status 200}) services :authenticated)]
    (sessions/select-workspace! session-store key other-workspace-id)
    (let [response (handler (assoc-in (support/request services :get "/workspace") [:headers "datastar-request"] "true"))]
      (is (= 409 (:status response)))
      (is (= "workspace-changed" (get-in response [:headers "x-tover-error"]))))))


(deftest workspace-selection-remains-accessible-without-memberships
  (let [{:keys [state users workspace-id other-workspace-id] :as services} (support/authenticated-services)
        user-id (:user-id (first users))]
    (doseq [workspace [workspace-id other-workspace-id]]
      (swap! state assoc-in [:memberships workspace user-id :status] :suspended))
    (let [handler (security/wrap-identity (constantly {:status 200}) services :authenticated)]
      (is (= 200 (:status (handler (support/request services :get "/workspace"))))))))


(deftest logout-policy-does-not-call-upstream-authentication
  (let [{:keys [auth] :as services} (support/authenticated-services)
        unavailable                 (support/auth-provider auth {:current-user (fn [_] (throw (IllegalStateException. "Must not verify logout")))})
        handler (security/wrap-identity (constantly {:status 200}) (assoc services :auth unavailable) :logout)]
    (is (= 200 (:status (handler (support/request services :post "/action/tover.auth.web/sign-out")))))))


(deftest transient-auth-failure-preserves-session-but-revocation-removes-it
  (doseq [[code status retained?] [[:unavailable 503 true] [:unauthenticated 401 false]]]
    (let [{:keys [session-store key] :as services} (support/authenticated-services)
          provider (support/auth-provider (:auth services) {:current-user (fn [_] (auth/fail! code status false))})
          handler (security/wrap-identity (constantly {:status 200}) (assoc services :auth provider))
          before (sessions/session session-store key)
          response (handler (support/request services :post "/action/anything"))]
      (is (= status (:status response)))
      (is (= (when retained? before) (sessions/session session-store key))))))


(deftest definitive-refresh-rejection-clears-authentication
  (let [{:keys [session-store key] :as services} (support/authenticated-services)
        provider (support/auth-provider (:auth services) {:refresh (fn [_] (auth/fail! :invalid-refresh-token 400 false))})
        handler (security/wrap-identity (constantly {:status 200}) (assoc services :auth provider))]
    (sessions/update-session! session-store key (fn [row] [(assoc-in row [:tokens :expires-at] 0) nil]))
    (is (= 401 (:status (handler (support/request services :post "/action/anything")))))
    (is (nil? (sessions/session session-store key)))))


(deftest rotated-credentials-survive-a-later-verification-outage
  (doseq [stage [:current-user :list-memberships]]
    (testing (name stage)
      (let [{:keys [auth workspaces session-store key] :as services} (support/authenticated-services)
            old-tokens (:tokens (sessions/session session-store key))
            fail! (fn [_] (auth/fail! :unavailable 503 false))
            failing (case stage
                      :current-user (assoc services :auth (support/auth-provider auth {:current-user fail!}))
                      :list-memberships (assoc services :workspaces (support/workspace-provider workspaces {:list-memberships fail!})))
            handler (security/wrap-identity (constantly {:status 200}) failing)]
        (sessions/update-session! session-store key (fn [row] [(assoc-in row [:tokens :expires-at] 0) nil]))
        (is (= 503 (:status (handler (support/request services :post "/action/anything")))))
        (let [tokens (:tokens (sessions/session session-store key))]
          (is (not= (:refresh-token old-tokens) (:refresh-token tokens)))
          (is (= (:user old-tokens) (auth/current-user auth (:access-token tokens))))
          (is (= 200 (:status ((security/wrap-identity (constantly {:status 200}) services)
                               (support/request services :post "/action/anything"))))))))))


(deftest production-origin-checks-do-not-trust-datastar-header
  (let [handler (security/wrap-origin (constantly {:status 200}) "https://app.example.test")
        request {:uri "/action/anything" :request-method :post :headers {"datastar-request" "true"}}]
    (is (= 403 (:status (handler request))))
    (is (= 403 (:status (handler (assoc-in request [:headers "origin"] "https://attacker.example.test")))))
    (is (= 200 (:status (handler (assoc-in request [:headers "origin"] "https://app.example.test")))))
    (is (= 403 (:status (handler (assoc request :headers {"origin" "https://app.example.test" "sec-fetch-site" "cross-site"})))))
    (is (= 200 (:status (handler {:uri "/" :request-method :get :headers {}}))))
    (is (= 403 (:status (handler {:uri     "/_bel/realtime/stream"                    :request-method :get
                                  :headers {"origin" "https://attacker.example.test"}}))))))


(deftest identity-context-is-trusted-and-caller-token-survives-internal-dispatch
  (let [{:keys [context] :as services} (support/authenticated-services)
        handler (security/wrap-identity
                  (fn [request] {:status 200 :context (:bel/context request) :headers (:headers request)})
                  services :manager)
        token (str (:auth-version context))
        request (-> (support/request services :post "/action/tover.teams.core/invite")
                    (assoc :bel/context {:role :owner :workspace-id (random-uuid)}
                           :params {:_tover-context token})
                    (update :headers dissoc "x-tover-context"))
        response (handler request)]
    (is (= 200 (:status response)))
    (is (= (:user-id context) (get-in response [:context :user-id])))
    (is (= (:workspace-id context) (get-in response [:context :workspace-id])))
    (is (= token (get-in response [:headers "x-tover-context"])))
    (is (= 409 (:status (handler (assoc-in request [:headers "x-tover-context"] "stale-header")))))))


(deftest compiled-routes-require-explicit-access-policy
  (let [services (support/authenticated-services)
        options  {:reitit.ring/default-options-endpoint nil
                  :data {:middleware [(security/identity-middleware services)]}}]
    (is (thrown? clojure.lang.ExceptionInfo
                 (ring/router [["/unclassified" {:get (constantly {:status 200})}]] options)))
    (let [handler (ring/ring-handler
                    (ring/router [["/classified" {:get {:tover/access :authenticated
                                                        :handler      (constantly {:status 200})}}]] options))]
      (is (= 303 (:status (handler {:uri "/classified" :request-method :get :headers {}})))))))
