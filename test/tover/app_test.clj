(ns tover.app-test
  (:require [bel.core :as bel]
            [bel.delivery :as delivery]
            [bel.handler :as bel-handler]
            [bel.realtime :as realtime]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [com.brunobonacci.mulog :as mu]
            [integrant.core :as ig]
            [org.httpkit.server :as http]
            [ring.middleware.session.store :as ring-store]
            [tover.app :as app]
            [tover.fixtures :as fixtures]
            [tover.identity.access :as access]
            [tover.shared.jdbc :as database]
            [tover.shared.rdf :as rdf]
            [tover.stock.store :as store]
            [tover.support :as support])
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
  (assoc (select-keys (fixtures/system) [:provider :session-store])
         :origin "http://localhost:3031"
         :development? true))


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
            (is (some? (bel/get-action :tover.catalogues.web/save)))
            (is (nil? (bel/get-action :tover.catalogues.rename.web/save)))
            (is (nil? (bel/get-view :tover.catalogues.rename.web/row)))
            (is (some? (bel/get-action :tover.workspaces.create.web/create)))
            (is (some? (bel/get-view :tover.workspaces.create.web/ready)))
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
                        access/wrap-access         (fn [handler _ _policy]
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
    ;; ponytail: workspace placeholder until chunk B adds the chooser.
    (is (= {:tover.identity.sign-in.web/login             "/auth/login"
            :tover.identity.signup.web/signup             "/auth/signup"
            :tover.identity.resend.web/recover-signup     "/auth/resend"
            :tover.identity.confirm.web/confirm           "/auth/confirm"
            :tover.identity.recovery.web/recover          "/auth/recover"
            :tover.identity.recovery.web/password         "/auth/password"
            :tover.identity.sign-out.web/confirm-sign-out "/auth/sign-out"
            :tover.identity.placeholders/workspace        "/workspace"
            :tover.accounts.profile.web/profile           "/account/profile"
            :tover.stock.web/page "/"
            :tover.operations.web/page                    "/operations"
            :tover.catalogues.web/index                   "/catalogues"
            :tover.catalogues.web/page                    "/catalogues/:kind"}
           (select-keys routes [:tover.identity.sign-in.web/login :tover.identity.signup.web/signup
                                :tover.identity.resend.web/recover-signup :tover.identity.confirm.web/confirm
                                :tover.identity.recovery.web/recover :tover.identity.recovery.web/password
                                :tover.identity.sign-out.web/confirm-sign-out
                                :tover.identity.placeholders/workspace :tover.accounts.profile.web/profile
                                :tover.stock.web/page :tover.operations.web/page
                                :tover.catalogues.web/index :tover.catalogues.web/page])))
    (is (not-any? #(re-find #"^tover\.(auth|teams)\b|^tover\.workspaces\.web$" (namespace %)) (keys routes)))
    (doseq [id [:tover.stock.web/page :tover.operations.web/page
                :tover.catalogues.web/index :tover.catalogues.web/page]]
      (is (= :tover.shared.web.core/shell (:bel/parent (bel/get-page id)))))
    (is (every? (fn [id]
                  (not (contains? #{"tover.actions" "tover.views" "tover.pages"
                                    "tover.streams" "tover.identity-http"}
                                  (namespace id))))
                (mapcat keys (vals registry))))
    (is (some? (bel/get-action :tover.stock.web/open-history)))
    (is (= "/view/tover.stock.web/stock" (bel/view-url :tover.stock.web/stock)))
    (is (some? (bel/get-stream :tover.stock.web/changed)))))


(def ^:private environment
  {"TOVER_AUTH_URL"    "http://127.0.0.1:58000/auth/v1"
   "TOVER_API_KEY"     "synthetic-public" "TOVER_AUTH_ADMIN_KEY" "synthetic-admin"
   "TOVER_ORIGIN"      "http://localhost:3030"
   "TOVER_JDBC_URL"    "jdbc:postgresql://127.0.0.1:55432/postgres"
   "TOVER_DB_USER"     "tover_app" "TOVER_DB_PASSWORD"           "synthetic-password"
   "TOVER_SESSION_KEY" "synthetic-session-key"})


(defn- error-data
  {:malli/schema [:=> [:cat [:=> [:cat] :any]] [:maybe :map]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (ex-data error))))


(deftest bel-system-is-configured-for-the-handler
  (let [c (app/config 3031 "x" {:development? true :environment environment})]
    (is (= {:repo     (ig/ref ::app/repo)     :datasource    (ig/ref ::app/datasource)    :realtime (ig/ref ::app/realtime)
            :provider (ig/ref ::app/provider) :session-store (ig/ref ::app/session-store) :origin   "http://localhost:3030"}
           (::app/bel-system c)))
    (is (= (ig/ref ::app/bel-system) (get-in c [::app/handler :bel/system])))
    (is (not-any? #(contains? c %) [::app/journal-datasource ::app/delivery-system :bel.delivery/worker]))
    (is (= {:realtime ::realtime :provider ::provider :session-store ::sessions :origin "http://localhost:3030"}
           (:resources (app/bel-system {:realtime ::realtime              :provider ::provider :session-store ::sessions
                                        :origin   "http://localhost:3030"}))))))


(deftest journal-variables-configure-the-worker
  (let [c (app/config 3031 "x" {:development? true
                                :environment  (assoc environment "TOVER_JOURNAL_DB_USER" "tover_journal"
                                                     "TOVER_JOURNAL_DB_PASSWORD" "synthetic-journal")})]
    (is (= {:jdbc-url (get environment "TOVER_JDBC_URL") :user "tover_journal" :password "synthetic-journal"}
           (::app/journal-datasource c)))
    (is (= {:repo (ig/ref ::app/repo) :datasource (ig/ref ::app/journal-datasource) :origin "http://localhost:3030"}
           (::app/delivery-system c)))
    (is (= (ig/ref ::app/delivery-system) (:bel.delivery/worker c)))
    (is (not (contains? c ::app/mailer)))))


(deftest smtp-variables-give-the-worker-a-mailer
  (let [c (app/config 3031 "x" {:development? true
                                :environment  (assoc environment "TOVER_JOURNAL_DB_USER" "tover_journal"
                                                     "TOVER_JOURNAL_DB_PASSWORD" "synthetic-journal"
                                                     "TOVER_SMTP_HOST" "127.0.0.1" "TOVER_SMTP_PORT" "1025"
                                                     "TOVER_SMTP_FROM" "tover@example.test" "TOVER_SMTP_STARTTLS" "false")})]
    (is (= {:host "127.0.0.1" :port 1025 :user nil :password nil :from "tover@example.test" :sender-name nil :starttls? false}
           (::app/mailer c)))
    (is (= (ig/ref ::app/mailer) (get-in c [::app/delivery-system :mailer])))))


(deftest one-journal-variable-is-a-configuration-error
  (doseq [extra [{"TOVER_JOURNAL_DB_USER" "tover_journal"} {"TOVER_JOURNAL_DB_PASSWORD" "synthetic-journal"}]]
    (is (= :configuration
           (:type (error-data #(app/config 3031 "x" {:development? true :environment (merge environment extra)})))))))


(deftest worker-runs-with-the-system-and-halts-before-its-resources
  (with-data-path
    (fn [path]
      (let [pool          (Object.)
            calls         (atom [])
            closed?       (atom false)
            start         delivery/start!
            configuration (-> (fixture-config 3031 path)
                              (assoc ::app/journal-datasource {}
                                     ::app/delivery-system {:repo (ig/ref ::app/repo) :datasource (ig/ref ::app/journal-datasource)}
                                     :bel.delivery/worker (ig/ref ::app/delivery-system)))]
        (with-redefs [database/datasource (fn [_] pool)
                      database/close!     (fn [value]
                                            (is (identical? pool value))
                                            (when (compare-and-set! closed? false true) (swap! calls conj :pool-close))
                                            nil)
                      delivery/start!     (fn [system]
                                            (let [worker (start system)]
                                              (reify java.lang.AutoCloseable
                                                (close [_] (.close ^java.lang.AutoCloseable worker) (swap! calls conj :worker-close)))))]
          (let [system (app/start! configuration)]
            (is (instance? java.lang.AutoCloseable (:bel.delivery/worker system)))
            (is (nil? (app/stop! system)))
            (is (= [:worker-close :pool-close] @calls))))))))


(deftest unnamed-user-is-sent-to-profile-with-return-to
  (support/with-application
    (fn [{:keys [browser] :as services}]
      (let [{:keys [request!]} browser]
        (support/sign-in! services browser "unnamed@example.test")
        (doseq [[uri query location] [["/catalogues/x" {} "/account/profile?return-to=%2Fcatalogues%2Fx"]
                                      ["/workspace" {"a" "1"} "/account/profile?return-to=%2Fworkspace%3Fa%3D1"]]]
          (let [response (request! :get uri query)]
            (is (= 303 (:status response)) uri)
            (is (= location (get-in response [:headers "location"])) uri)))
        (is (= 200 (:status (request! :get "/account/profile"))))
        (let [response (request! :get "/workspace" {} {:headers {"datastar-request" "true" "x-tover-context" (:context @(:state browser))}})]
          (is (= 409 (:status response)))
          (is (= "profile-required" (get-in response [:headers "x-tover-error"])))
          (is (= "/account/profile?return-to=%2Fworkspace" (get-in response [:headers "x-tover-redirect"]))))))))


(deftest return-to-rejects-other-origins
  ;; Browsers strip tab and newline, so "/\t/evil.test" would resolve to //evil.test.
  (doseq [value ["//evil.test" "https://evil.test" "/\\evil" "/a\r\nLocation: //evil.test" "/\t/evil.test" "/\n/evil.test"
                 "/\u0000x" "" nil]]
    (is (= "/" (access/local-path value)) (pr-str value)))
  (doseq [value ["/" "/workspace" "/catalogues/goods?q=a%20b"]]
    (is (= value (access/local-path value)))))
