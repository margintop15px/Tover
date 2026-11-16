(ns identity-integration
  "Explicit disposable-target checks: the real GoTrue provider, the encrypted PostgreSQL session store and the
  Bel directory behind the application handler. Run through scripts/test-identity-integration.sh."
  (:require
    [bel.core :as bel]
    [bel.realtime :as realtime]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is run-tests testing]]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as result-set]
    [org.httpkit.client :as client]
    [org.httpkit.server :as server]
    [ring.middleware.session.store :as ring]
    [tover.app :as app]
    [tover.identity.access :as access]
    [tover.identity.provider :as provider]
    [tover.identity.session :as session]
    [tover.shared.bel :as wiring]
    [tover.shared.jdbc :as database]
    [tover.shared.rdf :as rdf]
    [tover.support :as support])
  (:import
    [java.nio.file Files]))


(def ^:dynamic *options*
  nil)


(defn options []
  (let [local    (try (into {} (map #(str/split % #"=" 2))
                            (remove #(or (str/blank? %) (str/starts-with? % "#"))
                                    (str/split-lines (slurp "deployment/local-identity/.env"))))
                      (catch java.io.FileNotFoundException _ {}))
        value    (fn [name fallback] (or (System/getenv name) fallback))
        provider {:auth-url  (value "IDENTITY_TEST_AUTH_URL" "http://127.0.0.1:58000/auth/v1")
                  :api-key   (value "IDENTITY_TEST_PUBLIC_KEY" (get local "ANON_KEY"))
                  :admin-key (value "IDENTITY_TEST_SERVICE_KEY" (get local "SERVICE_ROLE_KEY"))}
        remote?  (not (contains? #{"localhost" "127.0.0.1" "::1"} (.getHost (java.net.URI/create (:auth-url provider)))))]
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


(defn- account!
  "A confirmed account named `name` created through the provider's admin API; returns {:email :password :user-id}."
  [provider name]
  (let [email    (str "clojure-identity-" (random-uuid) "@example.test")
        password (session/random-key)
        user     ((:request! provider) {:admin? true :path "/admin/users"
                                        :body   {:email         email :password password :email_confirm true
                                                 ;; Signup metadata may name the profile, never create a workspace.
                                                 :user_metadata {:name name :organization_name "Ignored workspace"}}})]
    {:email email :password password :user-id (parse-uuid (:id user))}))


(defn- with-live-application
  "The application handler over the live provider, session store and Bel directory."
  [settings f]
  (let [path     (.toFile (Files/createTempDirectory "tover-identity-integration-" (make-array java.nio.file.attribute.FileAttribute 0)))
        repo     (rdf/native-store path)
        services {:repo          repo
                  :provider      (provider/client (:provider *options*))
                  :session-store (session/jdbc-store settings)
                  :origin        "http://localhost:3030"}
        live     (app/realtime services)
        services (assoc services :realtime live)
        services (assoc services :bel/system (wiring/system (assoc services :datasource (:datasource settings))))
        handler  (app/create-handler services)]
    (try (f (assoc services :handler handler :browser (support/browser handler)))
         (finally
           (realtime/stop! live)
           (rdf/close! repo)
           (doseq [file (reverse (file-seq path))] (io/delete-file file))))))


(defn- with-http
  [handler f]
  (let [http-server (server/run-server handler {:port 0 :legacy-return-value? false})]
    (try (f (str "http://127.0.0.1:" (server/server-port http-server)))
         (finally @(server/server-stop! http-server {:timeout 2000})))))


(defn- datastar!
  "A Datastar request from `browser` over real HTTP (Bel actions answer with server-sent events)."
  [base browser method uri form]
  @(client/request
     {:url         (str base uri) :method method :timeout 10000
      :headers     (cond-> {"datastar-request" "true" "origin" "http://localhost:3030"
                            "cookie"           (:cookie @(:state browser))
                            "x-tover-context"  (:context @(:state browser))}
                     form (assoc "content-type" "application/x-www-form-urlencoded"))
      :form-params (when form (assoc form "__anti-forgery-token" (:csrf @(:state browser))))}))


(defn- hidden-value
  [body name]
  (second (re-find (re-pattern (str "name=\"" name "\"[^>]*value=\"([^\"]*)\"")) body)))


(defn- query
  [settings sql & params]
  (jdbc/execute! (:datasource settings) (into [sql] params) {:builder-fn result-set/as-unqualified-lower-maps}))


(defn- query-as
  "Query as the runtime role on behalf of `user-id`, under its row policies."
  [settings user-id sql & params]
  (jdbc/with-transaction [tx (:datasource settings)]
    (jdbc/execute-one! tx ["select set_config('request.jwt.claim.sub', ?, true)" (str user-id)])
    (jdbc/execute! tx (into [sql] params) {:builder-fn result-set/as-unqualified-lower-maps})))


(deftest live-provider-contract
  (let [provider (provider/client (:provider *options*))
        {:keys [email password user-id]} (account! provider "Live tester")
        tokens (provider/sign-in! provider {:email email :password password})
        rotated (provider/refresh! provider (:refresh-token tokens))]
    (is (= user-id (get-in tokens [:user :user-id])))
    (is (true? (get-in tokens [:user :email-confirmed?])))
    (is (not= (:refresh-token tokens) (:refresh-token rotated)))
    (is (= :rejected (try (provider/sign-in! provider {:email email :password "wrong-password"}) nil
                          (catch clojure.lang.ExceptionInfo error
                            (when (#{400 401 422} (:status (ex-data error))) :rejected)))))
    (provider/sign-out! provider (:access-token rotated) "local")
    (is (thrown? clojure.lang.ExceptionInfo (provider/refresh! provider (:refresh-token rotated))))))


(deftest live-application-journey
  (when-let [settings (:session *options*)]
    (with-live-application
      settings
      (fn [{:keys [browser handler session-store] :as services}]
        (let [{:keys [email password user-id]} (account! (:provider services) "Live tester")
              request! (:request! browser)
              invitation (str "/invitations/" (random-uuid))]
          (testing "signup metadata names the profile and creates no workspace"
            (is (= [{:display_name "Live tester"}] (query-as settings user-id "select display_name from public.profiles where user_id=?" user-id)))
            (is (= [] (query-as settings user-id "select organization_id from public.organization_memberships where user_id=?" user-id))))
          (testing "a signed-out invitation link survives sign-in without being accepted"
            (is (= "/auth/login" (get-in (request! :get invitation) [:headers "location"])))
            (request! :get "/auth/login")
            (is (= invitation (get-in (request! :post (bel/action-url :tover.identity.sign-in.web/sign-in)
                                                {"email" email "password" password})
                                      [:headers "location"])))
            (is (= 1 (count (query settings "select id from app_private.sessions where user_id=? and ended_at is null" user-id)))))
          (let [chooser (:body (request! :get "/workspace"))
                north   (random-uuid)]
            (is (str/includes? chooser "No workspaces yet."))
            (with-http handler
              (fn [base]
                (testing "Create Workspace through the Bel action over HTTP"
                  (let [response (datastar! base browser :post (bel/action-url :tover.workspaces.create.web/create)
                                            {"command-id"     (str (random-uuid))          "workspace-id" (str north) "name" "Live workshop"
                                             "_tover-context" (:context @(:state browser))})]
                    (is (= 200 (:status response)))
                    (is (str/includes? (:body response) "Workspace created"))
                    (is (= [{:role_id "owner" :status "active"}]
                           (query-as settings user-id "select role_id, status from public.organization_memberships where organization_id=? and user_id=?" north user-id)))))
                (let [body  (:body (request! :get "/workspace" {"workspace-id" (str north)}))
                      offer (second (re-find #"(?s)data-target-workspace-id=\"[^\"]*\">(.*?)</section>" body))]
                  (is (= 303 (:status (request! :post (bel/action-url :tover.workspaces.select.web/select)
                                                (into {} (for [name ["workspace-id" "expected-version" "command-id" "return-to"]]
                                                           [name (hidden-value offer name)])))))))
                (is (= 200 (:status (request! :get "/"))))
                (let [snapshot #(query settings "select updated_at,md5(data) as data,md5(tokens) as tokens from app_private.sessions where user_id=? order by id" user-id)
                      before   (snapshot)]
                  (dotimes [_ 3]
                    (is (= 200 (:status (request! :get "/operations"))))
                    (is (= 200 (:status (request! :get "/")))))
                  (doseq [uri ["/operations?datastar=%7B%7D" "/?datastar=%7B%7D"]]
                    (let [response (datastar! base browser :get uri nil)]
                      (is (= 200 (:status response)))
                      (is (str/includes? (:body response) "datastar-patch-elements"))))
                  (is (= before (snapshot)) "Authenticated navigation does not rewrite the session")))))
          (testing "concurrent requests refresh expiring provider tokens once under the row lock"
            (let [key    (support/session-key browser)
                  before (:tokens (session/session session-store key))]
              (session/update-session! session-store key #(vector (assoc-in % [:tokens :expires-at] 0) nil))
              (doseq [result (mapv (fn [_] (future (access/current-identity services key))) (range 4))]
                (is (= user-id (:user-id (deref result 30000 nil)))))
              (is (not= (:refresh-token before) (get-in (session/session session-store key) [:tokens :refresh-token])))))
          (testing "Sign Out ends this sign-in"
            (let [body (:body (request! :get "/workspace"))
                  form (second (re-find #"(?s)<form class=\"sign-out\"(.*?)</form>" body))]
              (is (= "/auth/login" (get-in (request! :post (bel/action-url :tover.identity.sign-out.web/sign-out)
                                                     {"session-id" (hidden-value form "session-id")})
                                           [:headers "location"])))
              (is (= "/auth/login" (get-in (request! :get "/") [:headers "location"]))))))))))


(deftest live-jdbc-session-contract
  (if-let [settings (:session *options*)]
    (let [store    (session/jdbc-store settings)
          tokens   {:access-token "synthetic-access" :refresh-token "synthetic-refresh" :expires-at 4102444800
                    :user         {:user-id (random-uuid) :email "synthetic@example.test" :email-confirmed? true}}
          key      (session/random-key)
          _        (session/transact! store key (fn [_ _] [(assoc (session/new-row key) :tokens tokens :user-id (get-in tokens [:user :user-id])) nil]))
          snapshot #(first (query settings "select data,tokens,updated_at from app_private.sessions where id=?" key))]
      (try
        (testing "an anonymous session gets its own key and never sees tokens"
          (let [anonymous (ring/write-session store nil {:draft "retained"})]
            (is (not= key anonymous))
            (is (= "retained" (:draft (ring/read-session store anonymous))))
            (ring/delete-session store anonymous)))
        (is (nil? (:identity/tokens (ring/read-session store key))))
        (ring/write-session store key (ring/read-session store key))
        (let [before (snapshot)]
          (is (not (str/includes? (:tokens before) "synthetic")) "Tokens are encrypted at rest")
          (dotimes [_ 3]
            (session/session store key)
            (session/update-session! store key #(vector % nil))
            (ring/write-session store key (ring/read-session store key)))
          (is (= before (snapshot)) "Unchanged reads and writes preserve ciphertext and updated_at")
          (is (thrown? IllegalStateException
                       (session/update-session! store key (fn [_] (throw (IllegalStateException. "rollback"))))))
          (is (= before (snapshot))))
        (testing "an ended key is never revived"
          (session/update-session! store key #(vector (assoc % :ended-at (java.time.Instant/now)) nil))
          (let [renewed (ring/write-session store key {:draft "after"})]
            (is (not= key renewed))
            (ring/delete-session store renewed)))
        (let [borrowed (mapv (fn [_] (database/connection (:datasource settings))) (range 8))
              started  (System/nanoTime)]
          (try
            (is (thrown? java.sql.SQLException (database/connection (:datasource settings))))
            (is (< 1500 (/ (- (System/nanoTime) started) 1e6) 5000))
            (finally (run! #(.close ^java.sql.Connection %) borrowed))))
        (is (= [{:one 1}] (query settings "select 1 as one")) "Pool recovers after exhaustion")
        (finally (ring/delete-session store key))))
    (is (nil? (:session *options*)) "Hosted JDBC credentials were not configured; provider checks run separately.")))


(defn -main [& _]
  (let [settings   (options)
        datasource (some-> (:session settings) database/datasource)
        result     (try
                     (binding [*options* (cond-> settings
                                           datasource (assoc-in [:session :datasource] datasource))]
                       (run-tests 'identity-integration))
                     (finally
                       (when datasource (database/close! datasource))))]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
