(ns tover.app
  (:require
    [aero.core :as aero]
    [bel.core :as bel]
    [bel.handler :as handler]
    [bel.log :as log]
    [bel.realtime :as realtime]
    [bel.session :as session]
    [bel.system :as system]
    [bel.validation :as validation]
    [clojure.java.io :as io]
    [clojure.string :as str]
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
    [tover.identity.access :as access]
    [tover.identity.provider :as provider]
    [tover.identity.session :as session-store]
    [tover.inventory.smart-web]
    [tover.shared.email :as email]
    [tover.shared.i18n :as i18n]
    [tover.shared.rdf :as rdf]
    [tover.shared.schema :as schema]
    [tover.shared.web.errors :as errors]))


(def Config
  [:map-of [:or :qualified-keyword [:vector :qualified-keyword]] :map])


(def Port
  [:int {:min 1 :max 65535}])


(def RepositoryOptions
  [:map [:path schema/Text]])


(def HandlerOptions
  [:map
   [:repo rdf/Repository]
   [:realtime realtime/Realtime]
   [:provider provider/Provider]
   [:session-store :any]
   [:origin schema/Text]
   [:development? {:optional true} :boolean]
   [:identity-health {:optional true} :map]
   [:bel/system {:optional true} :map]])


(def ServerOptions
  [:map
   [:repo rdf/Repository]
   [:handler schema/Handler]
   [:realtime realtime/Realtime]
   [:port Port]])


(def StopFn
  [:=> [:cat] :any])


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
  (when-let [{:keys [datasource auth-url api-key]} identity-health]
    (with-open [connection (jdbc/get-connection datasource)]
      (jdbc/execute-one! connection ["select 1 from app_private.sessions limit 1"] {:timeout 2}))
    (provider/health! {:auth-url auth-url :api-key api-key}))
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
  ;; Isolated milestones enter live discovery only with their complete cutover.
  (bel/init! {:scan-ns ['tover] :scan-exclude-ns ['tover.catalogues.rename 'tover.catalogues.web]})

  (let [{:keys [realtime session-store origin development?]} (validation/check! HandlerOptions options)
        context (select-keys options [:repo :realtime :provider :session-store :origin :bel/system])
        extra-routes (mapv (fn [[path data]]
                             [path (assoc data :tover/access :workspace)])
                           (realtime/routes realtime))
        routed (ring/ring-handler
                 (ring/router (handler/routes context extra-routes)
                              {:reitit.ring/default-options-endpoint nil
                               :data {:middleware [wrap-keyword-params
                                                   (access/access-middleware context)
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
                        (access/wrap-origin origin)
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
   ::provider      {:instance (:provider services)}
   ::session-store {:instance (:session-store services)}
   ::realtime      {:provider      (ig/ref ::provider)
                    :session-store (ig/ref ::session-store)
                    :repo          (ig/ref ::repo)}
   ::handler       (merge (select-keys services [:origin :development? :identity-health :bel/system])
                          {:repo          (ig/ref ::repo)
                           :realtime      (ig/ref ::realtime)
                           :provider      (ig/ref ::provider)
                           :session-store (ig/ref ::session-store)})
   ::server        {:repo          (ig/ref ::repo)
                    :handler       (ig/ref ::handler)
                    :realtime      (ig/ref ::realtime)
                    :session-store (ig/ref ::session-store)
                    :port          port}})


(defn- env
  {:malli/schema [:=> [:cat :map :string] [:maybe :string]]}
  [{:keys [environment]} name]
  (not-empty (get (or environment (System/getenv)) name)))


(defmethod aero/reader 'ig/ref [_ _ key] (ig/ref key))
(defmethod aero/reader 'tover/env [opts _ name] (env opts name))
(defmethod aero/reader 'tover/required [opts _ name] (or (env opts name) {::missing name}))
(defmethod aero/reader 'tover/when [_ _ [condition value]] (when condition value))
(defmethod aero/reader 'tover/flag [opts _ [name default]]
  (case (env opts name)
    nil default
    "true" true
    "false" false
    (throw (ex-info (str "Invalid " name ": expected true or false") {:type :configuration}))))
(defmethod aero/reader 'tover/int [opts _ [name default]]
  (if-let [value (env opts name)]
    (try (Integer/parseInt value)
         (catch NumberFormatException _ (throw (ex-info (str "Invalid " name) {:type :configuration}))))
    default))


(defn config
  "Read resources/tover/config.edn for `profile` (:prod or :dev). `environment` replaces the process environment."
  {:malli/schema [:function
                  [:=> [:cat] Config]
                  [:=> [:cat [:map [:profile [:enum :prod :dev]]
                              [:environment {:optional true} [:map-of :string [:maybe :string]]]]] Config]]}
  ([] (config {:profile :prod}))
  ([{:keys [profile] :as options}]
   (let [configuration (into {} (remove (comp nil? val))
                             (dissoc (aero/read-config (io/resource "tover/config.edn") options) :tover/journal?))
         missing       (->> (tree-seq coll? seq configuration) (keep #(when (map? %) (::missing %))) distinct sort vec)
         origin        (get-in configuration [::handler :origin])]
     (when (seq missing)
       (throw (ex-info (str "Missing configuration: " (str/join ", " missing)) {:type :configuration :missing missing})))
     (when-not (or (re-matches #"https://[^/]+" origin)
                   (and (= :dev profile) (re-matches #"http://(?:localhost|127\.0\.0\.1|\[::1\])(?::[0-9]+)?" origin)))
       (throw (ex-info "Production origin must be an HTTPS origin" {:type :configuration})))
     configuration)))


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
                    (let [identity (try (access/current-identity options auth-session-id)
                                        (catch clojure.lang.ExceptionInfo _ nil))]
                      (when (and identity (= scope-id (:workspace-id identity)) (= auth-version (:auth-version identity))
                                 (or (nil? (get-in identity [:user :locale]))
                                     (= locale (get-in identity [:user :locale]))))
                        (merge (select-keys options [:repo]) {:locale locale} identity)))))}))


(defn provider
  {:malli/schema [:=> [:cat :map] provider/Provider]} [{:keys [instance] :as options}]
  (or instance (provider/client options)))


(defn session-store
  {:malli/schema [:=> [:cat :map] :any]} [{:keys [instance] :as options}]
  (or instance (session-store/jdbc-store options)))


(defn mailer
  {:malli/schema [:=> [:cat email/Config] [:fn fn?]]}
  [options]
  (email/smtp-mailer options))


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
  (let [{:keys [handler realtime port]} (validation/check! ServerOptions options)
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
        (throw error))
      nil)))


(defmethod ig/halt-key! ::server [_ stop]
  (stop))


(defmethod ig/halt-key! ::realtime [_ realtime]
  (realtime/stop! realtime))


(defmethod ig/halt-key! ::repo [_ repository]
  (rdf/close! repository))


(defmethod ig/halt-key! ::logging [_ stop]
  (stop))


(defn -main
  {:malli/schema
   [:=> [:cat [:* :string]] :any]}
  [& _]
  (let [system (system/start! (config))]
    (-> (Runtime/getRuntime)
        (.addShutdownHook (Thread. ^Runnable #(system/stop! system))))))
