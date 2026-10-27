(ns tover.app
  (:require
    [bel.core :as bel]
    [bel.handler :as handler]
    [bel.log :as log]
    [bel.realtime :as realtime]
    [bel.session :as session]
    [bel.validation :as validation]
    [integrant.core :as ig]
    [next.jdbc :as jdbc]
    [org.httpkit.server :as http]
    [reitit.ring :as ring]
    [ring.middleware.anti-forgery :refer [wrap-anti-forgery]]
    [ring.middleware.head :refer [wrap-head]]
    [ring.middleware.keyword-params :refer [wrap-keyword-params]]
    [ring.middleware.not-modified :refer [wrap-not-modified]]
    [ring.middleware.params :refer [wrap-params]]
    [starfederation.datastar.clojure.adapter.http-kit2 :as hk]
    [tover.auth.core :as auth]
    [tover.auth.security :as security]
    [tover.auth.sessions :as sessions]
    [tover.auth.supabase :as auth-supabase]
    [tover.inventory.smart-web]
    [tover.shared.i18n :as i18n]
    [tover.shared.jdbc :as database]
    [tover.shared.rdf :as rdf]
    [tover.shared.schema :as schema]
    [tover.shared.supabase :as supabase]
    [tover.shared.web.errors :as errors]
    [tover.workspaces.core :as workspaces]
    [tover.workspaces.supabase :as workspace-supabase])
  (:import
    [clojure.lang ExceptionInfo]))


(def Config
  [:map-of :keyword :map])


(def Port
  [:int {:min 1 :max 65535}])


(def RepositoryOptions
  [:map [:path schema/Text]])


(def HandlerOptions
  [:map
   [:repo rdf/Repository]
   [:realtime realtime/Realtime]
   [:auth [:fn #(satisfies? auth/Auth %)]]
   [:workspaces [:fn #(satisfies? workspaces/Directory %)]]
   [:session-store :any]
   [:origin schema/Text]
   [:development? {:optional true} :boolean]
   [:identity-health {:optional true} :map]])


(def ServerOptions
  [:map
   [:repo rdf/Repository]
   [:handler schema/Handler]
   [:realtime realtime/Realtime]
   [:port Port]])


(def StopFn
  [:=> [:cat] :any])


(def SystemState
  [:map
   [::repo rdf/Repository]
   [::realtime realtime/Realtime]
   [::auth :any]
   [::workspaces :any]
   [::session-store :any]
   [::datasource {:optional true} :any]
   [::logging {:optional true} StopFn]
   [::handler schema/Handler]
   [::server StopFn]])


(defn- cleanup-error
  "Run every cleanup, preserving the first failure and any suppressed failures."
  {:malli/schema
   [:=> [:cat [:maybe [:fn #(instance? Throwable %)]] [:vector [:=> [:cat] :any]]]
    [:maybe [:fn #(instance? Throwable %)]]]}
  [failure cleanups]
  (reduce (fn [failure cleanup]
            (try (cleanup) failure
                 (catch Throwable error
                   (if failure
                     (do (.addSuppressed ^Throwable failure error) failure)
                     error))))
          failure cleanups))


(defn- readiness!
  "Probe storage and configured identity services without creating users or sessions."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [{:keys [repo identity-health]}]
  (when-not (.isInitialized ^org.eclipse.rdf4j.repository.Repository repo)
    (throw (ex-info "Inventory is not initialized." {:type :unavailable})))
  (rdf/with-transaction [connection {:repo repo :level :snapshot}]
    (.size ^org.eclipse.rdf4j.repository.RepositoryConnection connection
           (make-array org.eclipse.rdf4j.model.Resource 0)))
  (when-let [{:keys [datasource auth-url rest-url api-key]} identity-health]
    (with-open [connection (database/connection datasource)]
      (jdbc/execute-one! connection ["select 1 from app_private.sessions limit 1"] {:timeout 2}))
    (doseq [url [(str auth-url "/health") (str rest-url "/")]]
      (supabase/request! {:client (supabase/client) :api-key api-key :timeout-ms 2000}
                         {:url url :method :get})))
  true)


(defn- health-response
  {:malli/schema [:=> [:cat :map :map] :map]}
  [options request]
  (if-not (contains? #{:get :head} (:request-method request))
    {:status 405 :headers {"allow" "GET, HEAD"} :body ""}
    (let [ready? (or (= "/healthz" (:uri request))
                     (try (readiness! options) (catch Exception _ false)))]
      {:status  (if ready? 200 503)
       :headers {"content-type" "text/plain; charset=utf-8" "cache-control" "no-store"}
       :body    (if (= :head (:request-method request)) "" (if ready? "ok\n" "unavailable\n"))})))


(defn- resource-handler
  {:malli/schema [:=> [:cat :boolean] schema/Handler]}
  [development?]
  (let [resources (ring/create-resource-handler {:path "/" :root "public" :index-files []})
        handler   (fn [request]
                    (when (contains? #{:get :head} (:request-method request))
                      (when-let [response (resources request)]
                        (update response :headers merge
                                {"cache-control"   (if development? "no-store" "public, no-cache")
                                 "referrer-policy" "strict-origin"}))))]
    (wrap-head (if development? handler (wrap-not-modified handler)))))


(defn create-handler
  {:malli/schema
   [:=> [:cat HandlerOptions] schema/Handler]}
  [options]
  (bel/init! {:scan-ns ['tover]})

  (let [{:keys [realtime session-store origin development?]} (validation/check! HandlerOptions options)
        context (select-keys options [:repo :realtime :auth :workspaces :session-store :origin])
        extra-routes (mapv (fn [[path data]]
                             [path (assoc data :tover/access :workspace)])
                           (realtime/routes realtime))
        routed (ring/ring-handler
                 (ring/router (handler/routes context extra-routes)
                              {:reitit.ring/default-options-endpoint nil
                               :data {:middleware [wrap-keyword-params
                                                   (security/identity-middleware context)
                                                   errors/form-exception-middleware]}})
                 (ring/create-default-handler))
        secured (fn [request]
                  (let [response (if (contains? #{"/healthz" "/readyz"} (:uri request))
                                   (health-response options request)
                                   (routed request))]
                    (update response :headers merge
                            {"cache-control" "private, no-store" "referrer-policy" "strict-origin"})))
        application (-> secured
                        (wrap-anti-forgery {:error-handler (fn [_request]
                                                             {:status 403 :headers {"content-type" "text/plain; charset=utf-8"}
                                                              :body   (i18n/t "Invalid anti-forgery token")})})
                        (security/wrap-origin origin)
                        i18n/wrap-locale
                        wrap-params
                        (session/wrap-session {:store        session-store :cookie-name "tover-session"
                                               :cookie-attrs {:http-only true               :same-site :lax :path "/"
                                                              :secure    (not development?)}}))
        resources (resource-handler (boolean development?))]
    (-> (fn [request] (or (resources request) (application request)))
        log/wrap-request-context
        hk/wrap-start-responding)))


(defn system-config
  "Compose explicit services; isolated tests may supply their own adapters."
  {:malli/schema [:=> [:cat Port schema/Text :map] Config]}
  [port path services]
  (validation/check! Port port)
  (validation/check! schema/Text path)
  {::repo          {:path path}
   ::auth          {:instance (:auth services)}
   ::workspaces    {:instance (:workspaces services)}
   ::session-store {:instance (:session-store services)}
   ::realtime      {:auth          (ig/ref ::auth)
                    :workspaces    (ig/ref ::workspaces)
                    :session-store (ig/ref ::session-store)
                    :repo          (ig/ref ::repo)}
   ::handler       (merge (select-keys services [:origin :development? :identity-health])
                          {:repo          (ig/ref ::repo)
                           :realtime      (ig/ref ::realtime)
                           :auth          (ig/ref ::auth)
                           :workspaces    (ig/ref ::workspaces)
                           :session-store (ig/ref ::session-store)})
   ::server        {:repo          (ig/ref ::repo)
                    :handler       (ig/ref ::handler)
                    :realtime      (ig/ref ::realtime)
                    :session-store (ig/ref ::session-store)
                    :port          port}})


(defn config
  {:malli/schema
   [:function
    [:=> [:cat] Config]
    [:=> [:cat Port schema/Text] Config]
    [:=> [:cat Port schema/Text
          [:map
           [:development? :boolean]
           [:environment {:optional true} [:map-of :string [:maybe :string]]]]] Config]]}
  ([]
   (let [port (try (Integer/parseInt (or (System/getenv "TOVER_PORT") "3030"))
                   (catch NumberFormatException _
                     (throw (ex-info "Invalid TOVER_PORT" {:type :configuration}))))]
     (config port (or (System/getenv "TOVER_INVENTORY_PATH") ".data/inventory-v1"))))

  ([port path]
   (config port path {:development? false}))

  ([port path {:keys [development? environment]}]
   (let [environment   (or environment (System/getenv))
         required      (fn [name]
                         (or (not-empty (get environment name))
                             (throw (ex-info (str "Missing configuration: " name) {:type :configuration}))))
         provider      {:auth-url (required "TOVER_AUTH_URL") :rest-url  (required "TOVER_REST_URL")
                        :api-key  (required "TOVER_API_KEY")  :admin-key (required "TOVER_AUTH_ADMIN_KEY")}
         origin        (required "TOVER_ORIGIN")
         configuration (system-config port path {:origin origin :development? development?})]
     (when-not (or (re-matches #"https://[^/]+" origin)
                   (and development? (re-matches #"http://(?:localhost|127\.0\.0\.1|\[::1\])(?::[0-9]+)?" origin)))
       (throw (ex-info "Production origin must be an HTTPS origin" {:type :configuration})))
     (-> configuration
         (assoc ::auth provider ::workspaces provider
                ::logging {:env (if development? :dev :prod)})
         (assoc-in [::handler :logging] (ig/ref ::logging))
         (assoc-in [::realtime :logging] (ig/ref ::logging))
         (assoc-in [::handler :identity-health]
                   (merge (select-keys provider [:auth-url :rest-url :api-key])
                          {:datasource (ig/ref ::datasource)}))
         (assoc ::datasource {:jdbc-url (required "TOVER_JDBC_URL")
                              :user     (required "TOVER_DB_USER")  :password (required "TOVER_DB_PASSWORD")})
         (assoc ::session-store {:datasource     (ig/ref ::datasource)
                                 :encryption-key (required "TOVER_SESSION_KEY")})))))


(defn repo
  {:malli/schema
   [:=> [:cat RepositoryOptions] rdf/Repository]}
  [options]
  (let [{:keys [path]} (validation/check! RepositoryOptions options)]
    (rdf/native-store path)))


(defn realtime
  {:malli/schema
   [:=> [:cat [:map]] realtime/Realtime]}
  [options]
  (realtime/start!
    {:render    (fn [context render]
                  (binding [i18n/*locale* (or (get-in context [:user :locale]) (:locale context) "en")]
                    (render)))
     :authorize (fn [{:keys [auth-session-id auth-version scope-id locale]}]
                  (when auth-session-id
                    (let [identity (security/authenticate options auth-session-id)]
                      (when (and identity (= scope-id (:workspace-id identity)) (= auth-version (:auth-version identity))
                                 (or (nil? (get-in identity [:user :locale]))
                                     (= locale (get-in identity [:user :locale]))))
                        (merge (select-keys options [:repo]) {:locale locale} identity)))))}))


(defn auth
  {:malli/schema [:=> [:cat :map] [:fn #(satisfies? auth/Auth %)]]} [{:keys [instance] :as options}]
  (or instance (auth-supabase/auth options)))


(defn workspaces
  {:malli/schema [:=> [:cat :map] [:fn #(satisfies? workspaces/Directory %)]]} [{:keys [instance] :as options}]
  (or instance (workspace-supabase/workspaces options)))


(defn session-store
  {:malli/schema [:=> [:cat :map] :any]} [{:keys [instance] :as options}]
  (or instance (sessions/jdbc-store options)))


(defn datasource
  {:malli/schema [:=> [:cat :map] :any]}
  [options]
  (database/datasource options))


(defn logging
  {:malli/schema [:=> [:cat :map] StopFn]}
  [options]
  (log/start! options))


(defn handler
  {:malli/schema
   [:=> [:cat HandlerOptions] schema/Handler]}
  [options]
  (create-handler options))


(defn server
  {:malli/schema
   [:=> [:cat ServerOptions] StopFn]}
  [options]
  (let [{:keys [repo handler realtime port]} (validation/check! ServerOptions options)
        http-server (http/run-server
                      handler
                      {:port port
                       :legacy-return-value?       false
                       :legacy-unsafe-remote-addr? false})]
    (println (str "Tover v2 Stock: http://localhost:" port))
    (fn []
      (when-let [error (cleanup-error nil
                                      [#(realtime/stop! realtime)
                                       #(if-let [stopped (http/server-stop! http-server {:timeout 2000})]
                                          @stopped
                                          (http/server-join http-server))])]
        ;; Integrant stops halting on an error; release the store before propagating it.
        (throw (cleanup-error error [#(rdf/close! repo)])))
      nil)))


(defmethod ig/halt-key! ::server [_ stop]
  (stop))


(defmethod ig/halt-key! ::realtime [_ realtime]
  (realtime/stop! realtime))


(defmethod ig/halt-key! ::repo [_ repository]
  (rdf/close! repository))


(defmethod ig/halt-key! ::datasource [_ datasource]
  (database/close! datasource))


(defmethod ig/halt-key! ::logging [_ stop]
  (stop))


(defn stop!
  {:malli/schema
   [:=> [:cat SystemState] :nil]}
  [system]
  (when-let [error (cleanup-error nil
                                  [#(ig/halt! system)
                                   #(when-let [datasource (::datasource system)] (database/close! datasource))
                                   #(when-let [stop (::logging system)] (stop))])]
    (throw error))
  nil)


(defn start!
  {:malli/schema
   [:=> [:cat Config] SystemState]}
  [configuration]
  (try
    (ig/init (validation/check! Config configuration))
    (catch ExceptionInfo error
      (when-let [partial-system (:system (ex-data error))]
        (when-let [cleanup (cleanup-error nil [#(ig/halt! partial-system)])]
          (.addSuppressed error cleanup)
          (cleanup-error error
                         (cond-> []
                           (::realtime partial-system) (conj #(realtime/stop! (::realtime partial-system)))
                           (::repo partial-system) (conj #(rdf/close! (::repo partial-system)))
                           (::datasource partial-system) (conj #(database/close! (::datasource partial-system)))
                           (::logging partial-system) (conj #((::logging partial-system)))))))
      (throw error))))


(defn -main
  {:malli/schema
   [:=> [:cat [:* :string]] :any]}
  [& _]
  (let [system (start! (config))]
    (-> (Runtime/getRuntime)
        (.addShutdownHook (Thread. ^Runnable #(stop! system))))))
