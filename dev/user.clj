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
    [tover.app :as app]
    [tover.identity :as identity]
    [tover.operations.examples :as operation-examples]
    [tover.session-store :as session-store]
    [tover.shared.rdf :as rdf]
    [tover.shared.schema :as shared]
    [tover.stock.schema :as schema]
    [tover.stock.store :as store])
  (:import
    [java.io FileNotFoundException]
    [java.util Properties]))


(def seed-rows
  [{:id "notebook" :name "Notebook A5" :article "NB-A5" :unit "pcs" :location "Main shelf" :quantity 24}
   {:id "tea" :name "Green tea, 100 g" :article "TEA-100" :unit "packs" :location "Main shelf" :quantity 18}
   {:id "cable" :name "USB-C cable, 1 m" :article "USB-C-1" :unit "pcs" :location "Storage box" :quantity 12}])


(defn seed!
  {:malli/schema [:=> [:cat rdf/Repository shared/Scope] schema/Rows]}
  [repo scope]
  (mapv #(store/declare-opening! repo scope %) seed-rows))


;; Integrant's default init-key resolves :user/malli to this function.
(defn malli
  {:malli/schema [:=> [:cat :map] :int]}
  [_options]
  (bel/init! {:scan-ns ['tover]})
  (instrumentation/start!))


(defmethod ig/halt-key! ::malli [_ _]
  (instrumentation/stop!))


(def OpeningStockOptions
  [:map [:repo rdf/Repository] [:scope shared/Scope] [:instrumentation :int]])


(defn opening-stock
  {:malli/schema [:=> [:cat OpeningStockOptions] schema/Rows]}
  [{:keys [repo scope]}]
  (seed! repo scope))


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
  (let [base-handler (app/create-handler (assoc options :operation-examples
                                                {:rows operation-examples/rows :entries operation-examples/entries}))]
    (-> (fn [request]
          (bel/init! {:scan-ns ['tover]})
          ;; RevoGrid and Datastar need a fresh page after source changes.
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
        fixtures        (when (= mode "fixtures") (identity/fixtures))
        configuration   (case mode
                          "fixtures" (app/system-config 3030 ".data/inventory"
                                                        {:auth          (:auth fixtures)             :workspaces   (:workspaces fixtures)
                                                         :session-store (session-store/memory-store)
                                                         :origin        "http://localhost:3030"      :development? true})
                          "local" (app/config 3030 ".data/inventory" {:development? true :environment (local-environment)})
                          "supabase" (app/config 3030 ".data/inventory" {:development? true})
                          (throw (ex-info "Unknown development identity mode" {:type :configuration})))
        handler-options (cond-> (assoc (::app/handler configuration) :browser-reload (ig/ref ::browser-reload))
                          fixtures (assoc :opening-stock (ig/ref ::opening-stock)))]
    (cond-> (-> configuration
                (assoc ::malli {} ::browser-reload {} ::handler handler-options)
                (dissoc ::app/handler)
                (assoc-in [::app/server :handler] (ig/ref ::handler)))
      fixtures (assoc ::opening-stock {:repo            (ig/ref ::app/repo)
                                       :scope           {:workspace-id (:workspace-id fixtures)}
                                       :instrumentation (ig/ref ::malli)}))))


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
                      (Thread. ^Runnable (fn []
                                           ((requiring-resolve 'integrant.repl/halt))
                                           (nrepl/stop-server server)
                                           (io/delete-file ".nrepl-port" true)))))
  ;; Keep the JVM alive when the HTTP server is stopped from the REPL.
  @(promise))


(comment
  (start!)
  (stop!)
  (refresh!))
