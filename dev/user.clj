(ns user
  (:require
    [bel.core :as bel]
    [clojure.java.io :as io]
    [instrumentation :as instrumentation]
    [integrant.core :as ig]
    [integrant.repl :as repl]
    [nrepl.server :as nrepl]
    [ring.hot-reload.core :as hot]
    [ring.hot-reload.inject :as inject]
    [ring.middleware.reload :refer [wrap-reload]]
    [tover.app :as app])
  (:import
    [java.io FileNotFoundException]
    [java.util Properties]))


;; Integrant's default init-key resolves :user/malli to this function.
(defn malli
  {:malli/schema [:=> [:cat :map] :int]}
  [_options]
  (bel/init! {:scan-ns ['tover]})
  (instrumentation/start!))


(defmethod ig/halt-key! ::malli [_ _]
  (instrumentation/stop!))


(defn browser-reload
  {:malli/schema [:=> [:cat :map] :map]}
  [_options]
  (let [reloader (hot/hot-reloader
                   {:watch-paths      ["src" "resources"]
                    :watch-extensions #{".clj" ".cljc" ".edn" ".html" ".css" ".js"}})]
    {:reloader reloader
     :watcher  (hot/start! reloader)}))


(defmethod ig/halt-key! ::browser-reload [_ {:keys [reloader watcher]}]
  (hot/stop! reloader watcher))


(defn handler
  {:malli/schema [:=> [:cat :map] [:fn ifn?]]}
  [{:keys [browser-reload] :as options}]
  (let [base-handler (app/create-handler options)]
    (-> (fn [request]
          (bel/init! {:scan-ns ['tover]})
          ;; Jspreadsheet and Datastar need a fresh page after source changes.
          (inject/inject-into-response
            (base-handler request)
            (str "document.addEventListener('hot-reload:before-morph',"
                 "event=>{event.preventDefault();location.reload();});")))
        (wrap-reload {:dirs ["src"]})
        (hot/wrap-hot-reload (:reloader browser-reload)))))


(defn local-environment
  {:malli/schema
   [:=> [:cat] [:map-of :string [:maybe :string]]]}
  []
  (let [profile  (try
                   (with-open [reader (io/reader "deployment/local-identity/.env")]
                     (doto (Properties.) (.load reader)))
                   (catch FileNotFoundException _
                     (throw (ex-info "Missing local Supabase profile. Run deployment/local-identity/identity.sh init and up."
                                     {:type :configuration}))))
        settings (merge (into {} profile)
                        (select-keys (System/getenv) ["IDENTITY_API_PORT" "IDENTITY_DB_PORT" "TO_DEV_SITE_URL"]))
        api      (str "http://127.0.0.1:" (get settings "IDENTITY_API_PORT" "58000"))]
    {"TOVER_AUTH_URL"       (str api "/auth/v1")
     "TOVER_REST_URL"       (str api "/rest/v1")
     "TOVER_API_KEY"        (get settings "ANON_KEY")
     "TOVER_AUTH_ADMIN_KEY" (get settings "SERVICE_ROLE_KEY")
     "TOVER_ORIGIN"         (get settings "TO_DEV_SITE_URL" "http://localhost:3030")
     "TOVER_JDBC_URL"       (str "jdbc:postgresql://127.0.0.1:" (get settings "IDENTITY_DB_PORT" "55432") "/postgres")
     "TOVER_DB_USER"        "tover_app"
     "TOVER_DB_PASSWORD"    (get settings "TO_DEV_APP_PASSWORD")
     "TOVER_SESSION_KEY"    (get settings "SESSION_ENCRYPTION_KEY")}))


(defn dev-config
  {:malli/schema [:=> [:cat] app/Config]}
  []
  (let [mode            (or (System/getenv "TOVER_IDENTITY_MODE") "local")
        path            (or (System/getenv "TOVER_INVENTORY_PATH") ".data/tester-inventory-v1")
        configuration   (case mode
                          "local" (app/config 3030 path {:development? true :environment (local-environment)})
                          "supabase" (app/config 3030 path {:development? true})
                          (throw (ex-info "Unknown development identity mode" {:type :configuration})))
        handler-options (assoc (::app/handler configuration) :browser-reload (ig/ref ::browser-reload))]
    (-> configuration
        (assoc ::malli {} ::browser-reload {} ::handler handler-options)
        (dissoc ::app/handler)
        (assoc-in [::app/server :handler] (ig/ref ::handler)))))


(repl/set-prep! dev-config)


(repl/set-reload-options! {:dirs ["src" "dev"]})


(defn start!
  {:malli/schema [:=> [:cat] [:enum :initiated]]}
  []
  (repl/go))


(defn stop!
  {:malli/schema [:=> [:cat] [:enum :halted]]}
  []
  (repl/halt))


(defn refresh!
  {:malli/schema [:=> [:cat] [:enum :resumed]]}
  []
  (repl/reset))


(defn -main
  {:malli/schema [:=> [:cat [:* :string]] :any]}
  [& _]
  (start!)

  (let [server (nrepl/start-server :bind "127.0.0.1")]
    (spit ".nrepl-port" (:port server))
    (println "nREPL:" (:port server))
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable
                        (fn []
                          ((requiring-resolve 'integrant.repl/halt))
                          (nrepl/stop-server server)
                          (io/delete-file ".nrepl-port" true)))))
  ;; Keep the JVM alive when the HTTP server is stopped from the REPL.
  @(promise))


(comment
  (start!)
  (stop!)
  (refresh!))
