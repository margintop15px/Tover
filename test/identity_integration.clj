(ns identity-integration
  "Explicit disposable-target checks using the actual Clojure adapters and JDBC store."
  (:require
    [bel.core :as bel]
    [clojure.string :as str]
    [clojure.test :refer [deftest is run-tests]]
    [ring.middleware.session.store :as ring]
    [tover.auth.core :as auth]
    [tover.auth.security :as security]
    [tover.auth.sessions :as sessions]
    [tover.auth.sessions-test :as session-tests]
    [tover.auth.supabase :as supabase]
    [tover.shared.supabase :as http]
    [tover.support :as support]
    [tover.workspaces.core :as workspaces]
    [tover.workspaces.supabase :as workspace-provider]))


(def ^:dynamic *options*
  nil)


(defn options []
  (let [local    (try (into {} (map #(str/split % #"=" 2))
                            (remove #(or (str/blank? %) (str/starts-with? % "#"))
                                    (str/split-lines (slurp "deployment/local-identity/.env"))))
                      (catch java.io.FileNotFoundException _ {}))
        value    (fn [name fallback] (or (System/getenv name) fallback))
        provider {:auth-url  (value "IDENTITY_TEST_AUTH_URL" "http://127.0.0.1:58000/auth/v1")
                  :rest-url  (value "IDENTITY_TEST_REST_URL" "http://127.0.0.1:58000/rest/v1")
                  :api-key   (value "IDENTITY_TEST_PUBLIC_KEY" (get local "ANON_KEY"))
                  :admin-key (value "IDENTITY_TEST_SERVICE_KEY" (get local "SERVICE_ROLE_KEY"))}
        remote?  (some #(not (contains? #{"localhost" "127.0.0.1" "::1"} (.getHost (java.net.URI/create %))))
                       [(:auth-url provider) (:rest-url provider)])]
    (when (and remote? (not= "disposable" (System/getenv "IDENTITY_TEST_TARGET")))
      (throw (ex-info "Integration requires an explicitly disposable remote target" {})))
    (when (and remote? (not-every? #(not-empty (System/getenv %))
                                   ["IDENTITY_TEST_PUBLIC_KEY" "IDENTITY_TEST_SERVICE_KEY"]))
      (throw (ex-info "Remote integration requires explicit test API credentials" {})))
    (when (and remote? (System/getenv "IDENTITY_TEST_JDBC_URL")
               (not-every? #(not-empty (System/getenv %))
                           ["IDENTITY_TEST_DB_USER" "IDENTITY_TEST_DB_PASSWORD" "IDENTITY_TEST_SESSION_KEY"]))
      (throw (ex-info "Remote JDBC integration requires explicit test database credentials" {})))
    {:provider provider
     :session  (when (or (not remote?) (System/getenv "IDENTITY_TEST_JDBC_URL"))
                 {:jdbc-url       (value "IDENTITY_TEST_JDBC_URL" "jdbc:postgresql://127.0.0.1:55432/postgres")
                  :user           (value "IDENTITY_TEST_DB_USER" "tover_app")
                  :password       (value "IDENTITY_TEST_DB_PASSWORD" (get local "TO_DEV_APP_PASSWORD"))
                  :encryption-key (value "IDENTITY_TEST_SESSION_KEY" (get local "SESSION_ENCRYPTION_KEY"))})}))


(deftest live-clojure-provider-contract
  (let [provider    (:provider *options*)
        auth        (supabase/auth provider)
        directory   (workspace-provider/workspaces provider)
        email       (str "clojure-m2-" (random-uuid) "@example.test")
        password    (sessions/random-key)
        _           (http/request! {:client (http/client) :api-key (:admin-key provider)}
                                   {:url  (str (:auth-url provider) "/admin/users") :token (:admin-key provider)
                                    :body {:email         email :password password :email_confirm true
                                           :user_metadata {:organization_name "Clojure integration workspace"}}})
        tokens      (auth/sign-in! auth {:email email :password password})
        memberships (workspaces/list-memberships directory (:access-token tokens))]
    (is (= email (:email (auth/current-user auth (:access-token tokens)))))
    (is (= :owner (:role (first memberships))))
    (is (= {(str (get-in tokens [:user :user-id])) email}
           (workspaces/actor-labels directory (:access-token tokens) (:workspace-id (first memberships))
                                    [(get-in tokens [:user :user-id]) (random-uuid)])))
    (is (= 0 (workspaces/accept-invites! directory (:access-token tokens))))
    (when-let [settings (:session *options*)]
      (support/with-application
        #(assoc % :auth auth :workspaces directory :session-store (sessions/jdbc-store settings))
        (fn [{:keys [browser]}]
          (let [request! (:request! browser)]
            (is (= 200 (:status (request! :get "/auth/login"))))
            (is (= 303 (:status (request! :post (bel/action-url :tover.auth.web/sign-in) {"email" email "password" password}))))
            (is (= 200 (:status (request! :get "/workspace"))))
            (is (= 200 (:status (request! :get "/"))))
            (is (= 303 (:status (request! :post (bel/action-url :tover.workspaces.web/create-workspace) {"name" "Live Clojure workspace"}))))
            (is (= 200 (:status (request! :get "/workspace"))))
            (is (= 303 (:status (request! :post (bel/action-url :tover.auth.web/sign-out)))))
            (is (= 303 (:status (request! :get "/"))))))))
    (when-let [settings (:session *options*)]
      (let [store    (sessions/jdbc-store settings)
            key      (sessions/establish! store nil tokens)
            services {:auth auth :workspaces directory :session-store store}]
        (try
          (is (= (:workspace-id (first memberships)) (:workspace-id (security/authenticate services key))))
          (sessions/update-session! store key #(vector (assoc-in % [:tokens :expires-at] 0) nil))
          (let [requests (mapv (fn [_] (future (security/authenticate services key))) (range 4))]
            (doseq [request requests]
              (is (= (:user-id (:user tokens)) (:user-id (deref request 30000 nil))))))
          (is (not= (:refresh-token tokens) (get-in (sessions/session store key) [:tokens :refresh-token])))
          (finally (ring/delete-session store key)))))
    (let [rotated (if (:session *options*) tokens (auth/refresh! auth (:refresh-token tokens)))]
      (is (string? (:refresh-token rotated)))
      (auth/sign-out! auth (:access-token rotated))
      (is (thrown? clojure.lang.ExceptionInfo (auth/current-user auth (:access-token rotated)))))))


(deftest live-jdbc-session-contract
  (if-let [settings (:session *options*)]
    (session-tests/check-jdbc! settings)
    (is (nil? (:session *options*)) "Hosted JDBC credentials were not configured; provider checks run separately.")))


(defn -main [& _]
  (binding [*options* (options)]
    (let [result (run-tests 'identity-integration)]
      (shutdown-agents)
      (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))))
