(ns tover.app
  (:require
    [bel.core :as bel]
    [bel.handler :as handler]
    [bel.realtime :as realtime]
    [bel.session :as session]
    [bel.validation :as validation]
    [integrant.core :as ig]
    [org.httpkit.server :as http]
    [reitit.ring :as ring]
    [ring.middleware.anti-forgery :refer [wrap-anti-forgery]]
    [ring.middleware.keyword-params :refer [wrap-keyword-params]]
    [ring.middleware.params :refer [wrap-params]]
    [starfederation.datastar.clojure.adapter.http-kit2 :as hk]
    [tover.auth.core :as auth]
    [tover.auth.security :as security]
    [tover.auth.sessions :as sessions]
    [tover.auth.supabase :as auth-supabase]
    [tover.shared.rdf :as rdf]
    [tover.shared.schema :as schema]
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
   [:operation-examples {:optional true} :map]])


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


(defn create-handler
  {:malli/schema
   [:=> [:cat HandlerOptions] schema/Handler]}
  [options]
  (bel/init! {:scan-ns ['tover]})

  (let [{:keys [realtime session-store origin development?]} (validation/check! HandlerOptions options)
        context (select-keys options [:repo :realtime :operation-examples :auth :workspaces :session-store :origin])
        extra-routes (mapv (fn [[path data]]
                             [path (assoc data :tover/access :workspace)])
                           (realtime/routes realtime))
        routed (ring/ring-handler
                 (ring/router (handler/routes context extra-routes)
                              {:reitit.ring/default-options-endpoint nil
                               :data {:middleware [wrap-keyword-params
                                                   (security/identity-middleware context)
                                                   errors/form-exception-middleware]}})
                 (ring/routes
                   (ring/create-resource-handler {:path "/" :root "public"})
                   (ring/create-default-handler)))
        secured (fn [request]
                  (let [response (routed request)]
                    (update response :headers merge
                            {"cache-control" "private, no-store" "referrer-policy" "strict-origin"})))]
    (-> secured
        wrap-anti-forgery
        (security/wrap-origin origin)
        wrap-params
        (session/wrap-session {:store        session-store :cookie-name "tover-session"
                               :cookie-attrs {:http-only true               :same-site :lax :path "/"
                                              :secure    (not development?)}})
        hk/wrap-start-responding)))


(defn system-config
  "Compose explicit implementations; development supplies fixtures through Integrant."
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
   ::handler       (merge (select-keys services [:origin :development?])
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
   (config 3030 ".data/inventory"))

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
         (assoc ::auth provider ::workspaces provider)
         (assoc ::session-store {:jdbc-url       (required "TOVER_JDBC_URL")
                                 :user           (required "TOVER_DB_USER")     :password (required "TOVER_DB_PASSWORD")
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
    {:authorize (fn [{:keys [auth-session-id auth-version scope-id]}]
                  (when auth-session-id
                    (let [identity (security/authenticate options auth-session-id)]
                      (when (and identity (= scope-id (:workspace-id identity)) (= auth-version (:auth-version identity)))
                        (merge (select-keys options [:repo]) identity)))))}))


(defn auth
  {:malli/schema [:=> [:cat :map] [:fn #(satisfies? auth/Auth %)]]} [{:keys [instance] :as options}]
  (or instance (auth-supabase/auth options)))


(defn workspaces
  {:malli/schema [:=> [:cat :map] [:fn #(satisfies? workspaces/Directory %)]]} [{:keys [instance] :as options}]
  (or instance (workspace-supabase/workspaces options)))


(defn session-store
  {:malli/schema [:=> [:cat :map] :any]} [{:keys [instance] :as options}]
  (or instance (sessions/jdbc-store options)))


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


(defn stop!
  {:malli/schema
   [:=> [:cat SystemState] :nil]}
  [system]
  (ig/halt! system))


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
                           (::repo partial-system) (conj #(rdf/close! (::repo partial-system)))))))
      (throw error))))


(defn -main
  {:malli/schema
   [:=> [:cat [:* :string]] :any]}
  [& _]
  (let [system (start! (config))]
    (-> (Runtime/getRuntime)
        (.addShutdownHook (Thread. ^Runnable #(stop! system))))))
