(ns tover.app-test
  (:require [bel.core :as bel]
            [bel.handler :as bel-handler]
            [bel.realtime :as realtime]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [com.brunobonacci.mulog :as mu]
            [integrant.core :as ig]
            [org.httpkit.server :as http]
            [ring.middleware.session.store :as ring-store]
            [tover.app :as app]
            [tover.auth.security :as security]
            [tover.identity :as identity]
            [tover.session-store :as session-store]
            [tover.shared.jdbc :as database]
            [tover.shared.rdf :as rdf]
            [tover.stock.store :as store])
  (:import [java.nio.file Files]))


(defn- with-data-path
  {:malli/schema [:=> [:cat [:=> [:cat :string] :any]] :any]}
  [f]
  (let [directory (.toFile (Files/createTempDirectory "tover-app-test-" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try (f (.getPath directory))
         (finally
           (doseq [file (reverse (file-seq directory))] (io/delete-file file))))))


(def ^:private scope
  {:workspace-id #uuid "00000000-0000-0000-0000-000000000001"})


(defn- fixture-services
  {:malli/schema [:=> [:cat] :map]}
  []
  (merge (select-keys (identity/fixtures) [:auth :workspaces])
         {:session-store (session-store/memory-store)
          :origin        "http://localhost:3031"
          :development?  true}))


(defn- fixture-config
  {:malli/schema [:=> [:cat app/Port :string] app/Config]}
  [port path]
  (app/system-config port path (fixture-services)))


(deftest datastar-header-cannot-bypass-csrf-or-origin-checks
  (with-data-path
    (fn [path]
      (let [repo (rdf/native-store path)
            rt   (realtime/start!)]
        (try
          (let [handler (app/create-handler (merge (fixture-services) {:repo repo :realtime rt}))
                request {:request-method :post :uri "/missing"
                         :headers        {"host"             "localhost:3031" "origin" "http://localhost:3031"
                                          "datastar-request" "true"}}]
            (is (= 403 (:status (handler request))))
            (is (= 403 (:status (handler (update request :headers dissoc "origin")))))
            (is (= 403 (:status (handler (assoc-in request [:headers "origin"] "http://attacker.example")))))
            (is (= 403 (:status (handler (assoc-in request [:headers "sec-fetch-site"] "cross-site")))))
            (is (= 404 (:status (handler (-> request (assoc :request-method :get)
                                             (update :headers dissoc "datastar-request" "origin"))))))
            (let [response (handler {:request-method :get :uri "/" :headers {}})]
              (is (= 303 (:status response)))
              (is (= "/auth/login" (get-in response [:headers "location"]))))
            (let [response (handler {:request-method :get :uri "/operations" :headers {}})]
              (is (contains? #{303 401} (:status response))))
            (is (= 405 (:status (handler {:request-method :options :uri "/" :headers {}})))))
          (finally
            (realtime/stop! rt)
            (rdf/close! repo)))))))


(deftest health-and-readiness-are-public-and-fail-closed-without-sensitive-details
  (with-data-path
    (fn [path]
      (let [repo (rdf/native-store path)
            rt   (realtime/start!)]
        (try
          (let [handler (app/create-handler (merge (fixture-services) {:repo repo :realtime rt}))
                request {:request-method :get :headers {}}]
            (is (= 200 (:status (handler (assoc request :uri "/healthz")))))
            (is (= 200 (:status (handler (assoc request :uri "/readyz")))))
            (is (= "" (:body (handler (assoc request :uri "/readyz" :request-method :head)))))
            (rdf/close! repo)
            (is (= 200 (:status (handler (assoc request :uri "/healthz")))))
            (let [response (handler (assoc request :uri "/readyz"))]
              (is (= 503 (:status response)))
              (is (= "unavailable\n" (:body response)))))
          (finally (realtime/stop! rt) (rdf/close! repo)))))))


(deftest static-assets-revalidate-only-in-production-and-bypass-sessions
  (doseq [development? [false true]]
    (with-data-path
      (fn [path]
        (let [repo     (rdf/native-store path)
              rt       (realtime/start!)
              services (fixture-services)
              delegate (:session-store services)
              calls    (atom [])
              sessions (reify ring-store/SessionStore
                         (read-session [_ key]
                           (swap! calls conj :read)
                           (ring-store/read-session delegate key))
                         (write-session [_ key data]
                           (swap! calls conj :write)
                           (ring-store/write-session delegate key data))
                         (delete-session [_ key]
                           (swap! calls conj :delete)
                           (ring-store/delete-session delegate key)))]
          (try
            (let [handler     (app/create-handler (assoc services :repo repo :realtime rt
                                                         :session-store sessions :development? development?))
                  request     {:request-method :get :uri "/app.css" :headers {"cookie" "tover-session=unused"}}
                  response    (handler request)
                  modified    (get-in response [:headers "Last-Modified"])
                  conditional (handler (assoc-in request [:headers "if-modified-since"] modified))
                  head        (handler (assoc request :request-method :head))]
              (is (= 200 (:status response)))
              (is (some? (:body response)))
              (is (= "text/css" (get-in response [:headers "Content-Type"])))
              (is (string? modified))
              (is (= (if development? "no-store" "public, no-cache")
                     (get-in response [:headers "cache-control"])))
              (is (= (if development? 200 304) (:status conditional)))
              (when-not development?
                (is (nil? (:body conditional)))
                (is (nil? (get-in conditional [:headers "Content-Length"]))))
              (is (= 200 (:status head)))
              (is (nil? (:body head)))
              (is (nil? (get-in response [:headers "Set-Cookie"])))
              (is (empty? @calls) "Static requests never touch the session store")
              (is (= 200 (:status (handler (assoc-in request [:headers "if-modified-since"]
                                                     "Thu, 01 Jan 1970 00:00:00 GMT")))))
              (let [protected (handler (assoc request :uri "/operations"))
                    missing   (handler (assoc request :uri "/missing-asset.js"))
                    post      (handler (assoc request :request-method :post))]
                (is (= 303 (:status protected)))
                (is (= "private, no-store" (get-in protected [:headers "cache-control"])))
                (is (= 404 (:status missing)))
                (is (= "private, no-store" (get-in missing [:headers "cache-control"])))
                (is (= 403 (:status post)))
                (is (seq @calls))))
            (finally (realtime/stop! rt) (rdf/close! repo))))))))


(deftest operational-handlers-are-built-once-with-current-verified-request-context
  (with-data-path
    (fn [path]
      (let [repo             (rdf/native-store path)
            rt               (realtime/start!)
            context          {:repo repo :realtime rt}
            first-identity   {:workspace-id (:workspace-id scope) :user-id #uuid "10000000-0000-4000-8000-000000000001" :auth-version 1}
            second-identity  (assoc first-identity :workspace-id #uuid "00000000-0000-0000-0000-000000000002" :auth-version 2)
            current-identity (atom first-identity)
            created          (atom {})
            received         (atom [])
            factory          (fn [kind base-context]
                               (swap! created update kind (fnil conj []) (select-keys base-context (keys context)))
                               (fn [request]
                                 (swap! received conj (:bel/context request))
                                 {:status 200 :headers {"content-type" "text/plain"} :body "Stock"}))]
        (try
          (with-redefs [bel/init!                  (constantly nil)
                        bel-handler/page-handler   #(factory :page %)
                        bel-handler/view-handler   #(factory :view %)
                        bel-handler/action-handler #(factory :action %)
                        security/wrap-identity     (fn [handler _ _policy]
                                                     (fn [request]
                                                       (handler (assoc request :tover/identity @current-identity :bel/context @current-identity))))]
            (let [handler (app/create-handler (merge (fixture-services) context))
                  forged  {:workspace-id #uuid "00000000-0000-0000-0000-000000000099" :repo :forged}
                  request {:request-method :get   :uri         "/"    :headers {}
                           :tover/identity forged :bel/context forged}]
              (is (= 200 (:status (handler request))))
              (reset! current-identity second-identity)
              (is (= 200 (:status (handler request))))
              (is (= {:page [context] :view [context] :action [context]} @created))
              (is (= [first-identity second-identity] @received))))
          (finally
            (realtime/stop! rt)
            (rdf/close! repo)))))))


(deftest lifecycle-orders-resources-and-supports-restart
  (with-data-path
    (fn [path]
      (let [opened         (atom nil)
            realtimes      (atom [])
            stops          (atom 0)
            native-store   rdf/native-store
            start-realtime realtime/start!]
        (with-redefs [rdf/native-store  (fn [path] (reset! opened (native-store path)))
                      realtime/start!   (fn [& args]
                                          (let [realtime (apply start-realtime args)]
                                            (swap! realtimes conj realtime)
                                            realtime))
                      http/run-server   (fn [handler options]
                                          (is (.isInitialized @opened))
                                          (is (= {:rows [] :history []} (store/snapshot @opened scope)))
                                          (is (fn? handler))
                                          (is (= {:port 3031
                                                  :legacy-return-value?       false
                                                  :legacy-unsafe-remote-addr? false} options))
                                          (Object.))
                      http/server-stop! (fn [_ options]
                                          (is (.isInitialized @opened))
                                          (is (= {:timeout 2000} options))
                                          (is (:stopped? @(:state (peek @realtimes))))
                                          (swap! stops inc)
                                          (deliver (promise) true))]
          (let [first-system (app/start! (fixture-config 3031 path))
                first-repo   (::app/repo first-system)]
            (try
              (is (identical? @opened first-repo))
              (is (not (contains? (fixture-config 3031 path) ::app/registry)))
              (is (not (contains? (::app/handler (fixture-config 3031 path)) :registry)))
              (is (not (contains? first-system ::app/registry)))
              (is (not (contains? (::app/realtime first-system) :repo)))
              (let [response ((::app/handler first-system) {:request-method :get :uri "/" :headers {}})]
                (is (= 303 (:status response)))
                (is (= "/auth/login" (get-in response [:headers "location"])))
                (is (= {:rows [] :history []} (store/snapshot first-repo scope))))
              (finally (is (nil? (app/stop! first-system)))))
            (is (not (.isInitialized first-repo)))
            (is (= 1 @stops))
            (let [second-system (app/start! (fixture-config 3031 path))]
              (try
                (is (.isInitialized (::app/repo second-system)))
                (finally (app/stop! second-system)))
              (is (not (.isInitialized (::app/repo second-system))))
              (is (= 2 @stops)))))))))


(deftest failed-server-start-releases-repository-for-retry
  (with-data-path
    (fn [path]
      (let [opened         (atom nil)
            realtime       (atom nil)
            attempts       (atom 0)
            cause          (IllegalStateException. "server unavailable")
            failure        (ex-info "server failed to start" {:type :test} cause)
            native-store   rdf/native-store
            start-realtime realtime/start!]
        (with-redefs [rdf/native-store  (fn [path] (reset! opened (native-store path)))
                      realtime/start!   (fn [& args] (reset! realtime (apply start-realtime args)))
                      http/run-server   (fn [_ _]
                                          (is (.isInitialized @opened))
                                          (when (= 1 (swap! attempts inc)) (throw failure))
                                          (Object.))
                      http/server-stop! (fn [_ _] (deliver (promise) true))]
          (let [error (try (app/start! (fixture-config 3031 path)) nil
                           (catch Throwable error error))]
            (is (some #(identical? failure %) (take-while some? (iterate ex-cause error))))
            (is (some #(identical? cause %) (take-while some? (iterate ex-cause error))))
            (is (:stopped? @(:state @realtime)))
            (is (not (.isInitialized @opened))))
          (let [retry-system (app/start! (fixture-config 3031 path))]
            (try
              (is (.isInitialized (::app/repo retry-system)))
              (is (= 2 @attempts))
              (finally (app/stop! retry-system)))))))))


(deftest failed-server-stop-releases-repository-for-retry
  (with-data-path
    (fn [path]
      (let [stops   (atom 0)
            cause   (IllegalStateException. "server stop unavailable")
            failure (ex-info "server failed to stop" {:type :test} cause)]
        (with-redefs [http/run-server   (fn [_ _] (Object.))
                      http/server-stop! (fn [_ _]
                                          (when (= 1 (swap! stops inc)) (throw failure))
                                          (deliver (promise) true))]
          (let [system (app/start! (fixture-config 3031 path))
                repo   (::app/repo system)
                error  (try (app/stop! system) nil (catch Throwable error error))]
            (is (some #(identical? failure %) (take-while some? (iterate ex-cause error))))
            (is (some #(identical? cause %) (take-while some? (iterate ex-cause error))))
            (is (:stopped? @(:state (::app/realtime system))))
            (is (not (.isInitialized repo))))
          (let [retry-system (app/start! (fixture-config 3031 path))]
            (try
              (is (.isInitialized (::app/repo retry-system)))
              (finally (app/stop! retry-system))))
          (is (= 2 @stops)))))))


(deftest failed-start-cleans-up-even-when-integrant-halt-fails
  (with-data-path
    (fn [path]
      (let [realtime       (atom nil)
            opened         (atom nil)
            stops          (atom 0)
            native-store   rdf/native-store
            start-realtime realtime/start!
            stop-realtime  realtime/stop!]
        (with-redefs [rdf/native-store (fn [path] (reset! opened (native-store path)))
                      realtime/start!  (fn [& args] (reset! realtime (apply start-realtime args)))
                      http/run-server  (fn [_ _] (throw (IllegalStateException. "start failed")))
                      realtime/stop!   (fn [realtime]
                                         (when (= 1 (swap! stops inc))
                                           (throw (IllegalStateException. "halt failed")))
                                         (stop-realtime realtime))]
          (is (thrown? clojure.lang.ExceptionInfo (app/start! (fixture-config 3031 path))))
          (is (= 2 @stops))
          (is (:stopped? @(:state @realtime)))
          (is (not (.isInitialized @opened))))))))


(deftest stop-joins-an-already-stopping-server
  (with-data-path
    (fn [path]
      (let [joined? (atom false)]
        (with-redefs [http/run-server   (fn [_ _] (Object.))
                      http/server-stop! (fn [_ _] nil)
                      http/server-join  (fn [_] (reset! joined? true))]
          (let [system (app/start! (fixture-config 3031 path))]
            (app/stop! system)
            (is @joined?)
            (is (not (.isInitialized (::app/repo system))))))))))


(deftest publisher-is-stopped-on-normal-and-failed-startup
  (doseq [fail-start? [false true]]
    (with-data-path
      (fn [path]
        (let [started       (atom 0)
              stopped       (atom 0)
              before        (mu/global-context)
              configuration (-> (fixture-config 3031 path)
                                (assoc ::app/logging {:env :prod})
                                (assoc-in [::app/handler :logging] (ig/ref ::app/logging)))]
          (with-redefs [mu/start-publisher! (fn [_] (swap! started inc) #(swap! stopped inc))
                        http/run-server     (fn [& _]
                                              (if fail-start?
                                                (throw (ex-info "startup failed" {}))
                                                (Object.)))
                        http/server-stop!   (fn [& _] (delay true))]
            (if fail-start?
              (is (thrown? clojure.lang.ExceptionInfo (app/start! configuration)))
              (app/stop! (app/start! configuration)))
            (is (= 1 @started @stopped))
            (is (= before (mu/global-context)))))))))


(deftest realtime-stop-failure-still-stops-http-before-closing-rdf
  (with-data-path
    (fn [path]
      (let [calls         (atom [])
            http-server   (Object.)
            failure       (IllegalStateException. "realtime stop failed")
            stop-realtime realtime/stop!
            close-rdf     rdf/close!]
        (with-redefs [http/run-server   (fn [_ _] http-server)
                      realtime/stop!    (fn [realtime]
                                          (swap! calls conj :realtime)
                                          (stop-realtime realtime)
                                          (throw failure))
                      http/server-stop! (fn [server options]
                                          (is (identical? http-server server))
                                          (is (= {:timeout 2000} options))
                                          (swap! calls conj :http-stop)
                                          nil)
                      http/server-join  (fn [server]
                                          (is (identical? http-server server))
                                          (swap! calls conj :http-join))
                      rdf/close!        (fn [repo]
                                          (swap! calls conj :rdf-close)
                                          (close-rdf repo))]
          (let [system (app/start! (fixture-config 3031 path))
                error  (try (app/stop! system) nil (catch Throwable error error))]
            (is (some #(identical? failure %) (take-while some? (iterate ex-cause error))))
            (is (= [:realtime :http-stop :http-join :rdf-close] @calls))
            (is (not (.isInitialized (::app/repo system))))))))))


(deftest realtime-stop-failure-retains-http-cleanup-failure-as-suppressed
  (with-data-path
    (fn [path]
      (let [failure       (IllegalStateException. "realtime stop failed")
            cleanup       (IllegalStateException. "http stop failed")
            stop-realtime realtime/stop!]
        (with-redefs [http/run-server   (fn [_ _] (Object.))
                      realtime/stop!    (fn [realtime]
                                          (stop-realtime realtime)
                                          (throw failure))
                      http/server-stop! (fn [_ _] (throw cleanup))]
          (let [system (app/start! (fixture-config 3031 path))
                error  (try (app/stop! system) nil (catch Throwable error error))]
            (is (some #(identical? failure %) (take-while some? (iterate ex-cause error))))
            (is (= [cleanup] (vec (.getSuppressed failure))))
            (is (not (.isInitialized (::app/repo system))))))))))


(deftest stop-waits-for-http-drain-before-closing-rdf
  (with-data-path
    (fn [path]
      (let [stopping      (promise)
            drained       (promise)
            pool          (Object.)
            closed?       (atom false)
            configuration (-> (fixture-config 3031 path)
                              (assoc ::app/datasource {})
                              (assoc-in [::app/session-store :datasource] (ig/ref ::app/datasource)))]
        (with-redefs [http/run-server     (fn [_ _] (Object.))
                      http/server-stop!   (fn [_ _] (deliver stopping true) drained)
                      database/datasource (constantly pool)
                      database/close!     (fn [value] (is (identical? pool value)) (reset! closed? true) nil)]
          (let [system  (app/start! configuration)
                stopped (future (app/stop! system))]
            (try
              (is (= true (deref stopping 2000 :timeout)))
              (is (.isInitialized (::app/repo system)))
              (is (false? @closed?))
              (is (= :waiting (deref stopped 20 :waiting)))
              (finally (deliver drained true)))
            (is (nil? (deref stopped 2000 :timeout)))
            (is @closed?)
            (is (not (.isInitialized (::app/repo system))))))))))


(deftest pool-is-released-after-http-on-normal-and-failed-lifecycle
  (doseq [failure [nil :start :stop]]
    (with-data-path
      (fn [path]
        (let [pool          (Object.)
              calls         (atom [])
              closed?       (atom false)
              configuration (-> (fixture-config 3031 path)
                                (assoc ::app/datasource {})
                                (assoc-in [::app/session-store :datasource] (ig/ref ::app/datasource)))]
          (with-redefs [database/datasource (fn [_] (swap! calls conj :pool-open) pool)
                        database/close!     (fn [value]
                                              (is (identical? pool value))
                                              (when (compare-and-set! closed? false true)
                                                (swap! calls conj :pool-close))
                                              nil)
                        http/run-server     (fn [& _]
                                              (swap! calls conj :http-start)
                                              (when (= :start failure) (throw (IllegalStateException. "start failed")))
                                              (Object.))
                        http/server-stop!   (fn [& _]
                                              (swap! calls conj :http-stop)
                                              (when (= :stop failure) (throw (IllegalStateException. "stop failed")))
                                              (delay true))]
            (if (= :start failure)
              (is (thrown? clojure.lang.ExceptionInfo (app/start! configuration)))
              (let [system (app/start! configuration)]
                (if (= :stop failure)
                  (is (thrown? clojure.lang.ExceptionInfo (app/stop! system)))
                  (is (nil? (app/stop! system))))))
            (is @closed?)
            (is (= (if (= :start failure)
                     [:pool-open :http-start :pool-close]
                     [:pool-open :http-start :http-stop :pool-close])
                   @calls))))))))


(deftest domain-declarations-preserve-routes-without-obsolete-ids
  (let [registry (bel/init! {:scan-ns ['tover]})
        routes   (into {} (keep (fn [[id definition]]
                                  (when-let [route (:bel/route (meta definition))]
                                    [id route])))
                       (:pages registry))]
    (is (= {:tover.auth.web/login      "/auth/login"
            :tover.auth.web/signup     "/auth/signup"
            :tover.auth.web/recover    "/auth/recover"
            :tover.auth.web/verify     "/auth/verify"
            :tover.auth.web/confirm    "/auth/confirm"
            :tover.auth.web/password   "/auth/password"
            :tover.stock.web/page      "/"
            :tover.operations.web/page "/operations"
            :tover.workspaces.web/page "/workspace"
            :tover.teams.core/page     "/team"}
           routes))
    (doseq [id [:tover.stock.web/page :tover.operations.web/page
                :tover.workspaces.web/page :tover.teams.core/page]]
      (is (= :tover.shared.web.core/shell (:bel/parent (bel/get-page id)))))
    (is (every? (fn [id]
                  (not (contains? #{"tover.actions" "tover.views" "tover.pages"
                                    "tover.streams" "tover.identity-http"}
                                  (namespace id))))
                (mapcat keys (vals registry))))
    (is (some? (bel/get-action :tover.stock.web/open-history)))
    (is (= "/action/tover.teams.core/invite" (bel/action-url :tover.teams.core/invite)))
    (is (= "/view/tover.stock.web/stock" (bel/view-url :tover.stock.web/stock)))
    (is (some? (bel/get-stream :tover.stock.web/changed)))))
