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
    [tover.operation-examples :as operation-examples]
    [tover.rdf :as rdf]
    [tover.schema :as schema]
    [tover.store :as store]))


(def seed-rows
  [{:id "notebook" :name "Notebook A5" :article "NB-A5" :unit "pcs" :location "Main shelf" :quantity 24}
   {:id "tea" :name "Green tea, 100 g" :article "TEA-100" :unit "packs" :location "Main shelf" :quantity 18}
   {:id "cable" :name "USB-C cable, 1 m" :article "USB-C-1" :unit "pcs" :location "Storage box" :quantity 12}])


(defn seed!
  {:malli/schema [:=> [:cat rdf/Repository] schema/Rows]}
  [repo]
  (mapv #(store/declare-opening! repo %) seed-rows))


;; Integrant's default init-key resolves :user/malli to this function.
(defn malli
  {:malli/schema [:=> [:cat :map] :int]}
  [_options]
  (bel/init! {:scan-ns ['tover]})
  (instrumentation/start!))


(defmethod ig/halt-key! ::malli [_ _]
  (instrumentation/stop!))


(def OpeningStockOptions
  [:map [:repo rdf/Repository] [:instrumentation :int]])


(defn opening-stock
  {:malli/schema [:=> [:cat OpeningStockOptions] schema/Rows]}
  [{:keys [repo]}]
  (seed! repo))


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


(defn dev-config
  {:malli/schema [:=> [:cat] app/Config]}
  []
  (-> (app/config)
      (assoc ::malli {}
             ::browser-reload {}
             ::opening-stock {:repo            (ig/ref ::app/repo)
                              :instrumentation (ig/ref ::malli)})
      (assoc ::handler (assoc (::app/handler (app/config))
                              :opening-stock (ig/ref ::opening-stock)
                              :browser-reload (ig/ref ::browser-reload)))
      (dissoc ::app/handler)
      (assoc-in [::app/server :handler] (ig/ref ::handler))))


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
