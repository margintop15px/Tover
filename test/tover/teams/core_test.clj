(ns tover.teams.core-test
  (:require
    [bel.core :as bel]
    [bel.handler :as handler]
    [charred.api :as charred]
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [reitit.ring :as ring]
    [ring.middleware.keyword-params :refer [wrap-keyword-params]]
    [tover.auth.core :as auth]
    [tover.auth.security :as security]
    [tover.auth.sessions :as sessions]
    [tover.shared.ui.forms :as forms]
    [tover.shared.web.errors :as errors]
    [tover.support :as support]
    [tover.workspaces.core :as workspaces])
  (:import
    [java.io ByteArrayInputStream]
    [java.time Instant]))


(defn- email-failure
  {:malli/schema [:=> [:cat :any :keyword :boolean] :any]}
  [provider code uncertain?]
  (reify auth/Auth
    (sign-up! [_ input] (auth/sign-up! provider input))
    (sign-in! [_ input] (auth/sign-in! provider input))
    (current-user [_ token] (auth/current-user provider token))
    (refresh! [_ token] (auth/refresh! provider token))
    (sign-out! [_ token] (auth/sign-out! provider token))
    (verify-email! [_ input] (auth/verify-email! provider input))
    (recover! [_ input] (auth/recover! provider input))
    (set-password! [_ token password] (auth/set-password! provider token password))
    (invite! [_ _] (auth/fail! code 503 uncertain?))
    (magic-link! [_ input] (auth/magic-link! provider input))))


(deftest manager-team-flows-use-stored-identities-and-existing-account-fallback
  (support/with-application
    (fn [{:keys [browser users state other-workspace-id]}]
      (let [{:keys [request!]} browser]
        (support/login! browser (first users))
        (is (str/includes? (:body (request! :get "/team")) "member@example.test"))
        (is (= 303 (:status (request! :post "/action/tover.teams.core/invite" {"email" "other@example.test" "role" "member"}))))
        (is (= :magiclink (:type (last (:emails @state)))))
        (let [invite (last (vals (:invitations @state)))]
          (is (= "other@example.test" (:email invite)))
          (is (= 429 (:status (request! :post "/action/tover.teams.core/resend" {"invite-id" (str (:id invite))}))))
          (swap! state assoc-in [:invitations (:id invite) :created-at] (str (.minusSeconds (Instant/now) 61)))
          (is (= 303 (:status (request! :post "/action/tover.teams.core/resend" {"invite-id" (str (:id invite))}))))
          (is (= 2 (count (:emails @state))))
          (let [newest (last (sort-by :created-at (vals (:invitations @state))))]
            (is (not= (:id invite) (:id newest)))
            (is (= 303 (:status (request! :post "/action/tover.teams.core/cancel" {"invite-id" (str (:id newest))}))))
            (is (every? #(= :revoked (:status %)) (vals (:invitations @state))))))
        (request! :get "/team")
        (is (= 303 (:status (request! :post "/action/tover.teams.core/recovery" {"user-id" (str (:user-id (second users))) "email" "forged@example.test"}))))
        (is (= "member@example.test" (:email (last (:emails @state)))))
        (is (= 303 (:status (request! :post (bel/action-url :tover.workspaces.web/select-workspace) {"workspace-id" (str other-workspace-id)}))))
        (request! :get "/workspace")
        (is (= 403 (:status (request! :get "/team"))))))))


(deftest uncertain-email-delivery-preserves-pending-reservation
  (support/with-application
    #(update % :auth email-failure :unavailable true)
    (fn [{:keys [browser users state]}]
      (support/login! browser (first users))
      ((:request! browser) :get "/team")
      (let [response ((:request! browser) :post "/action/tover.teams.core/invite" {"email" "uncertain@example.test" "role" "member"})]
        (is (= 503 (:status response)))
        (is (= "delivery-unconfirmed" (get-in response [:headers "x-tover-error"])))
        (is (str/includes? (:body response) "uncertain@example.test"))
        (is (= :pending (:status (first (vals (:invitations @state))))))))))


(deftest definitive-email-failure-revokes-only-new-reservation
  (support/with-application
    #(update % :auth email-failure :invalid-input false)
    (fn [{:keys [browser users state]}]
      (support/login! browser (first users))
      ((:request! browser) :get "/team")
      (let [response ((:request! browser) :post "/action/tover.teams.core/invite" {"email" "failed@example.test" "role" "member"})]
        (is (= 503 (:status response)))
        (is (= :revoked (:status (first (vals (:invitations @state))))))))))


(defn- form-request
  {:malli/schema [:=> [:cat :map :map] :map]}
  [request input]
  (assoc request :params input :form-params input))


(defn- internal-handler
  {:malli/schema [:=> [:cat :map [:fn ifn?]] [:fn ifn?]]}
  [services page-response]
  (bel/init! {:scan-ns ['tover]})
  (ring/ring-handler
    (ring/router
      (mapv (fn [[path data]]
              [path (if (= "/team" path)
                      (assoc-in data [:get :handler] #(page-response % :team))
                      data)])
            (handler/routes services []))
      {:reitit.ring/default-options-endpoint nil
       :data {:middleware [wrap-keyword-params
                           (security/identity-middleware services)
                           errors/form-exception-middleware]}})))


(deftest internal-forms-retain-native-fallback-and-never-retry-mutations
  (let [request    {:anti-forgery-token "csrf" :tover/identity {:auth-version (random-uuid)}}
        internal   (forms/form request :tover.teams.core/invite "Invite")
        auth       (forms/form request (bel/action-url :tover.auth.web/sign-in) "Sign in")
        expression (get-in internal [1 :data-on:submit])]
    (is (= "post" (get-in internal [1 :method])))
    (is (= "/action/tover.teams.core/invite" (get-in internal [1 :action])))
    (is (str/includes? expression "contentType: 'form'"))
    (is (str/includes? expression "retry: 'never'"))
    (is (str/includes? expression "retryMaxCount: 0"))
    (is (str/includes? expression "requestCancellation: 'cleanup'"))
    (is (nil? (get-in auth [1 :data-on:submit])))))


(deftest datastar-team-success-dispatches-destination-page-without-redirecting
  (let [services (support/authenticated-services)
        rendered (atom [])
        handler  (internal-handler services (fn [request page]
                                              (swap! rendered conj [request page])
                                              {:status 200 :headers {"content-type" "text/html"} :body "team-patch"}))
        request  (-> (support/request services :post "/action/tover.teams.core/invite")
                     (form-request {"email" "fresh@example.test" "role" "admin"})
                     (assoc-in [:headers "datastar-request"] "true"))
        response (handler request)]
    (is (= 200 (:status response)))
    (is (= "team-patch" (:body response)))
    (is (nil? (get-in response [:headers "location"])))
    (is (= :team (second (first @rendered))))
    (is (empty? (:params (ffirst @rendered))))
    (is (= (:workspace-id services) (get-in (ffirst @rendered) [:tover/identity :workspace-id])))
    (is (nil? (:tover/error (ffirst @rendered))))))


(deftest datastar-business-errors-preserve-fields-and-real-error-code
  (let [services (update (support/authenticated-services) :auth email-failure :unavailable true)
        rendered (atom nil)
        handler  (internal-handler services (fn [request page]
                                              (reset! rendered [request page])
                                              {:status 200 :headers {"content-type" "text/html"} :body "error-patch"}))
        request  (-> (support/request services :post "/action/tover.teams.core/invite")
                     (form-request {"email"    "uncertain@example.test" "role"       "admin"
                                    "password" "private-password"       "token-hash" "private-token"})
                     (assoc-in [:headers "datastar-request"] "true"))
        response (handler request)]
    (is (= 200 (:status response)))
    (is (= "delivery-unconfirmed" (get-in response [:headers "x-tover-error"])))
    (is (= "text/html" (get-in response [:headers "content-type"])))
    (is (= "uncertain@example.test" (get-in (first @rendered) [:params :email])))
    (is (= {:email "uncertain@example.test" :role "admin"} (:params (first @rendered))))
    (is (str/includes? (:tover/error (first @rendered)) "Email delivery is unconfirmed"))
    (is (= :team (second @rendered)))
    (is (= :pending (:status (first (vals (:invitations @(:state services)))))))))


(deftest datastar-security-errors-retain-real-status-and-do-not-render-a-form
  (let [services (support/authenticated-services)
        rendered (atom 0)
        handler  (internal-handler services (fn [_ _] (swap! rendered inc) {:status 200}))]
    (is (= 401 (:status (handler {:uri     "/action/tover.teams.core/invite" :request-method :post
                                  :headers {"datastar-request" "true"}}))))
    (let [request (assoc-in (support/request services :post "/action/tover.teams.core/invite")
                            [:headers "datastar-request"] "true")]
      (sessions/select-workspace! (:session-store services) (:key services) (:other-workspace-id services))
      (is (= 409 (:status (handler request)))))
    (is (zero? @rendered))))


(deftest failed-error-page-render-does-not-become-a-successful-patch
  (let [services (update (support/authenticated-services) :auth email-failure :unavailable true)
        handler  (internal-handler services (fn [_ _] {:status 503 :headers {"content-type" "text/plain"} :body "Unavailable"}))
        request  (-> (support/request services :post "/action/tover.teams.core/invite")
                     (form-request {"email" "uncertain@example.test" "role" "member"})
                     (assoc-in [:headers "datastar-request"] "true"))]
    (is (= 503 (:status (handler request))))))


(deftest page-query-authorization-and-upstream-failures-retain-their-status
  (doseq [[code status] [[:forbidden 403] [:unavailable 503]]]
    (support/with-application
      (fn [{:keys [workspaces] :as services}]
        (assoc services :workspaces
               (support/workspace-provider workspaces {:team (fn [_ _] (auth/fail! code status false))})))
      (fn [{:keys [browser users session-store]}]
        (support/login! browser (first users))
        (let [{:keys [request! state]} browser
              key (support/session-key browser)
              tokens                   (:tokens (sessions/session session-store key))
              response                 (request! :get "/team" {} {:headers {"datastar-request" "true"
                                                                            "x-tover-context"  (:context @state)}})]
          (is (= status (:status response)))
          (is (= tokens (:tokens (sessions/session session-store key)))))))))


(deftest internal-form-negotiation-distinguishes-business-conflicts-from-security-errors
  (doseq [[code status expected] [[:invalid-input 400 200] [:not-found 404 200] [:invite-changed 409 200] [:cooldown 429 200]
                                  [:unauthenticated 401 401] [:forbidden 403 403] [:workspace-changed 409 409]
                                  [:workspace-required 409 409] [:password-required 409 409]]]
    (let [services (support/authenticated-services)
          provider (support/workspace-provider (:workspaces services)
                                               {:manage-invitation (fn [_ _] (auth/fail! code status false))})
          handler  (internal-handler (assoc services :workspaces provider)
                                     (fn [_ _] {:status 200 :headers {"content-type" "text/html"} :body "page-patch"}))
          request  (-> (support/request services :post "/action/tover.teams.core/invite")
                       (form-request {"email" "submitted@example.test" "role" "admin"})
                       (assoc-in [:headers "datastar-request"] "true"))
          response (handler request)]
      (is (= expected (:status response)) (name code))
      (is (= (name code) (get-in response [:headers "x-tover-error"]))))))


(deftest destination-failure-retains-its-status-and-error-code
  (let [services (update (support/authenticated-services) :auth email-failure :unavailable true)
        expected {:status 409 :headers {"x-tover-error" "workspace-changed"} :body "Reload this page"}
        handler  (internal-handler services (fn [_ _] expected))
        request  (-> (support/request services :post "/action/tover.teams.core/invite")
                     (form-request {"email" "uncertain@example.test" "role" "member"})
                     (assoc-in [:headers "datastar-request"] "true"))]
    (is (= expected (handler request)))))


(deftest page-effect-reauthorizes-after-workspace-change-during-command
  (let [{:keys [workspaces session-store key other-workspace-id] :as services} (support/authenticated-services)
        services (assoc services :workspaces
                        (support/workspace-provider workspaces
                                                    {:manage-invitation (fn [token input]
                                                                          (let [result (workspaces/manage-invitation! workspaces token input)]
                                                                            (sessions/select-workspace! session-store key other-workspace-id)
                                                                            result))}))
        rendered (atom 0)
        handler (internal-handler services (fn [_ _] (swap! rendered inc) {:status 200}))
        request (-> (support/request services :post "/action/tover.teams.core/invite")
                    (form-request {"email" "race@example.test" "role" "member"})
                    (assoc-in [:headers "datastar-request"] "true"))
        response (handler request)]
    (is (= 409 (:status response)))
    (is (= "workspace-changed" (get-in response [:headers "x-tover-error"])))
    (is (zero? @rendered))))


(deftest canonical-team-actions-use-decoded-json-input
  (let [{:keys [users state] :as services} (support/authenticated-services)
        handler (internal-handler services (fn [_ _] {:status 200 :headers {} :body "team-patch"}))
        request! (fn [id input]
                   (handler (-> (support/request services :post (bel/action-url id))
                                (assoc :params {}
                                       :body (ByteArrayInputStream. (.getBytes (charred/write-json-str input) "UTF-8")))
                                (assoc-in [:headers "content-type"] "application/json")
                                (assoc-in [:headers "datastar-request"] "true"))))]
    (is (= 200 (:status (request! :tover.teams.core/invite {:email "json@example.test" :role "admin"}))))
    (let [invitation (first (vals (:invitations @state)))]
      (is (= "json@example.test" (:email invitation)))
      (is (= :admin (:role invitation)))
      (swap! state assoc-in [:invitations (:id invitation) :created-at] (str (.minusSeconds (Instant/now) 61)))
      (is (= 200 (:status (request! :tover.teams.core/resend {:invite-id (str (:id invitation))})))))
    (let [pending (first (filter #(= :pending (:status %)) (vals (:invitations @state))))]
      (is (= 200 (:status (request! :tover.teams.core/cancel {:invite-id (str (:id pending))})))))
    (is (every? #(= :revoked (:status %)) (vals (:invitations @state))))
    (let [input {:user-id (str (:user-id (second users))) :email "forged@example.test"}]
      (is (= 200 (:status (request! :tover.teams.core/recovery input)))))
    (is (= "member@example.test" (:email (last (:emails @state)))))))


(deftest team-action-execution-uses-input-and-defaults-role
  (let [{:keys [context] :as services} (support/authenticated-services)
        request (assoc (support/request services :post (bel/action-url :tover.teams.core/invite))
                       :tover/identity context
                       :params {:email "forged@example.test" :role "admin"})]
    (bel/init! {:scan-ns ['tover]})
    (is (= {:type :page :id :tover.teams.core/page}
           (handler/execute-action (assoc services :request request) :tover.teams.core/invite
                                   {:email "decoded@example.test"})))
    (let [invitation (first (vals (:invitations @(:state services))))]
      (is (= "decoded@example.test" (:email invitation)))
      (is (= :member (:role invitation))))))


(deftest typed-team-input-errors-preserve-form-fields-without-executing-command
  (doseq [[datastar? expected-status] [[true 200] [false 422]]]
    (let [services (support/authenticated-services)
          retained (atom nil)
          handler  (internal-handler services (fn [request _]
                                                (reset! retained (:params request))
                                                {:status 200 :headers {} :body "validation-page"}))
          request  (cond-> (-> (support/request services :post (bel/action-url :tover.teams.core/invite))
                               (form-request {"email" "submitted@example.test" "role" "owner" "password" "private"}))
                     datastar? (assoc-in [:headers "datastar-request"] "true"))
          response (handler request)]
      (is (= expected-status (:status response)))
      (is (= "invalid-input" (get-in response [:headers "x-tover-error"])))
      (is (= {:email "submitted@example.test" :role "owner"} @retained))
      (is (empty? (:invitations @(:state services))))
      (is (empty? (:emails @(:state services)))))))
