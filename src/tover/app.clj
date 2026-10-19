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
    [ring.middleware.params :refer [wrap-params]]
    [starfederation.datastar.clojure.adapter.http-kit2 :as hk]
    [tover.rdf :as rdf]
    [tover.schema :as schema])
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
  (let [{:keys [realtime]} (validation/check! HandlerOptions options)
        context (select-keys options [:repo :realtime :operation-examples])
        routes (into [["/" {:get (handler/page-handler context :tover.pages/stock)}]
                      ["/view/{*id}" {:post (handler/view-handler context)}]
                      ["/action/{*id}" {:post (handler/action-handler context)}]]
                     (realtime/routes realtime))]
    (-> (ring/ring-handler
          (ring/router routes)
          (ring/routes
            (ring/create-resource-handler {:path "/" :root "public"})
            (ring/create-default-handler)))
        wrap-params
        (wrap-anti-forgery {:safe-header "datastar-request"})
        session/wrap-session
        hk/wrap-start-responding)))


(defn config
  {:malli/schema
   [:function
    [:=> [:cat] Config]
    [:=> [:cat Port schema/Text] Config]]}
  ([]
   (config 3030 ".data/inventory"))

  ([port path]
   (validation/check! Port port)
   (validation/check! schema/Text path)
   {::repo     {:path path}
    ::realtime {}
    ::handler  {:repo     (ig/ref ::repo)
                :realtime (ig/ref ::realtime)}
    ::server   {:repo     (ig/ref ::repo)
                :handler  (ig/ref ::handler)
                :realtime (ig/ref ::realtime)
                :port     port}}))


(defn repo
  {:malli/schema
   [:=> [:cat RepositoryOptions] rdf/Repository]}
  [options]
  (let [{:keys [path]} (validation/check! RepositoryOptions options)]
    (rdf/native-store path)))


(defn realtime
  {:malli/schema
   [:=> [:cat [:map]] realtime/Realtime]}
  [_options]
  (realtime/start!))


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
