(ns tover.dev-reload-test
  (:require
    [bel.core :as bel]
    [bel.session :as session]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [integrant.core :as ig]
    [ring.hot-reload.core :as hot]
    [ring.hot-reload.watcher :as watcher]
    [tover.app :as app]
    [tover.identity :as identity]
    [tover.operations.examples :as operation-examples]
    [tover.session-store :as session-store]
    [user])
  (:import
    [java.io FileNotFoundException StringReader]
    [java.util.concurrent TimeUnit]))


(defn- with-reloader
  {:malli/schema
   [:=> [:cat [:=> [:cat :map] :any]]
    :any]}
  [f]
  (with-redefs [watcher/beholder-watcher
                (fn [_ _]
                  (reify watcher/Watcher
                    (start! [_ _] :test-watcher)
                    (stop! [_ _])))]
    (let [browser-reload (user/browser-reload {})]
      (try (f browser-reload)
           (finally (ig/halt-key! :user/browser-reload browser-reload))))))


(deftest browser-reload-watches-source-and-assets-and-stops-on-halt
  (let [options  (atom nil)
        reloader {:test :reloader}
        stopped  (atom nil)]
    (with-redefs [hot/hot-reloader (fn [value] (reset! options value) reloader)
                  hot/start!       (fn [value] (is (= reloader value)) :test-watcher)
                  hot/stop!        (fn [value handle] (reset! stopped [value handle]))]
      (let [browser-reload (user/browser-reload {})]
        (is (= ["src" "resources"] (:watch-paths @options)))
        (is (every? (:watch-extensions @options) [".clj" ".css" ".js"]))
        (is (= {:reloader reloader :watcher :test-watcher} browser-reload))
        (ig/halt-key! :user/browser-reload browser-reload)
        (is (= [reloader :test-watcher] @stopped))))))


(deftest dev-handler-injects-reload-and-preserves-session-identity
  (with-reloader
    (fn [browser-reload]
      (let [created  (atom 0)
            options  (atom nil)
            sessions (atom [])
            page     {:status 200 :headers {"content-type" "text/html"}
                      :body   "<!DOCTYPE html><html><body>Stock</body></html>"}]
        (with-redefs [app/create-handler
                      (fn [value]
                        (swap! created inc)
                        (reset! options value)
                        (session/wrap-session
                          (fn [request]
                            (swap! sessions conj (get-in request [:session :bel/session-id]))
                            page)))
                      bel/init! (constantly nil)]
          (let [handler    (user/handler {:browser-reload browser-reload})
                response   (handler {:request-method :get :uri "/" :headers {}})
                set-cookie (get-in response [:headers "Set-Cookie"])
                cookie     (first (str/split (if (string? set-cookie) set-cookie (first set-cookie)) #";" 2))]
            (handler {:request-method :get :uri "/" :headers {"cookie" cookie}})
            (is (str/includes? (:body response) (get-in browser-reload [:reloader :script])))
            (is (str/includes? (:body response) "hot-reload:before-morph"))
            (is (str/includes? (:body response) "event.preventDefault();location.reload();"))
            (is (= 1 @created))
            (is (= {:rows operation-examples/rows :entries operation-examples/entries}
                   (:operation-examples @options)))
            (is (uuid? (first @sessions)))
            (is (= (first @sessions) (second @sessions)))))))))


(deftest dev-handler-leaves-assets-streams-and-html-fragments-unchanged
  (with-reloader
    (fn [browser-reload]
      (doseq [response [{:status 200 :headers {"Content-Type" "text/javascript"}
                         :body   "console.log('stock');"}
                        {:status 200 :headers {"Content-Type" "text/event-stream"}
                         :body   "event: datastar-patch-elements\ndata: Stock\n\n"}
                        {:status 200                :headers {"Content-Type" "text/html"}
                         :body   "<div>Stock</div>"}]]
        (with-redefs [app/create-handler (fn [_] (constantly response))
                      bel/init!          (constantly nil)]
          (let [handler (user/handler {:browser-reload browser-reload})]
            (is (= response (handler {:request-method :get :uri "/asset" :headers {}})))))))))


(deftest explicit-services-compose-without-development-reload-components
  (let [fixtures      (identity/fixtures)
        services      {:auth          (:auth fixtures)             :workspaces   (:workspaces fixtures)
                       :session-store (session-store/memory-store)
                       :origin        "http://localhost:3030"      :development? true}
        configuration (app/system-config 3030 ".data/inventory" services)]
    (is (identical? (:auth services) (get-in configuration [::app/auth :instance])))
    (is (identical? (:workspaces services) (get-in configuration [::app/workspaces :instance])))
    (is (identical? (:session-store services) (get-in configuration [::app/session-store :instance])))
    (is (= (ig/ref ::app/handler) (get-in configuration [::app/server :handler])))
    (is (= (select-keys services [:origin :development?])
           (select-keys (::app/handler configuration) [:origin :development?])))
    (is (not (contains? configuration :user/browser-reload)))))


(defn- isolated-config-check
  "Exercise environment defaults in a fresh JVM without changing the test process environment."
  {:malli/schema [:=> [:cat [:maybe :string]] :map]}
  [mode]
  (let [program
        (pr-str
          '(do
             (require '[tover.app :as app] '[tover.auth.core :as auth] '[tover.workspaces.core :as workspaces]
                      '[integrant.core :as ig] '[ring.middleware.session.store :as ring] '[user])
             (let [environment      {"TOVER_AUTH_URL"    "http://127.0.0.1:58000/auth/v1"
                                     "TOVER_REST_URL"    "http://127.0.0.1:58000/rest/v1"
                                     "TOVER_API_KEY"     "synthetic-public" "TOVER_AUTH_ADMIN_KEY" "synthetic-admin"
                                     "TOVER_ORIGIN"      "http://localhost:3030"
                                     "TOVER_JDBC_URL"    "jdbc:postgresql://127.0.0.1:55432/postgres"
                                     "TOVER_DB_USER"     "tover_app" "TOVER_DB_PASSWORD"           "synthetic-password"
                                     "TOVER_SESSION_KEY" "synthetic-session-key"}
                   production-error (try (app/config) nil
                                         (catch clojure.lang.ExceptionInfo error (:type (ex-data error))))
                   development      (try
                                      (with-redefs [user/local-environment (constantly environment)]
                                        (user/dev-config))
                                      (catch clojure.lang.ExceptionInfo error {:error (:type (ex-data error))}))]
               (println "TOVER_CONFIG_CHECK"
                        (pr-str
                          {:production-error           production-error
                           :development-error          (:error development)
                           :provider-options?          (= {:auth-url  (get environment "TOVER_AUTH_URL")
                                                           :rest-url  (get environment "TOVER_REST_URL")
                                                           :api-key   (get environment "TOVER_API_KEY")
                                                           :admin-key (get environment "TOVER_AUTH_ADMIN_KEY")}
                                                          (:tover.app/auth development)
                                                          (:tover.app/workspaces development))
                           :jdbc-sessions?             (= {:jdbc-url       (get environment "TOVER_JDBC_URL")
                                                           :user           (get environment "TOVER_DB_USER")
                                                           :password       (get environment "TOVER_DB_PASSWORD")
                                                           :encryption-key (get environment "TOVER_SESSION_KEY")}
                                                          (:tover.app/session-store development))
                           :fixture-auth?              (satisfies? auth/Auth (get-in development [:tover.app/auth :instance]))
                           :fixture-workspaces?        (satisfies? workspaces/Directory (get-in development [:tover.app/workspaces :instance]))
                           :fixture-sessions?          (satisfies? ring/SessionStore (get-in development [:tover.app/session-store :instance]))
                           :dev-handler?               (= (ig/ref :user/handler) (get-in development [:tover.app/server :handler]))
                           :reload-dependency?         (= (ig/ref :user/browser-reload) (get-in development [:user/handler :browser-reload]))
                           :seed-dependency?           (= (ig/ref :user/opening-stock) (get-in development [:user/handler :opening-stock]))
                           :scoped-seed?               (uuid? (get-in development [:user/opening-stock :scope :workspace-id]))
                           :production-handler-absent? (not (contains? development :tover.app/handler))
                           :development?               (get-in development [:user/handler :development?])})))))
        builder     (ProcessBuilder. ^java.util.List
                      [(str (io/file (System/getProperty "java.home") "bin" "java"))
                       "-cp" (System/getProperty "java.class.path") "clojure.main" "-e" program])
        environment (.environment builder)
        _           (doseq [key (filter #(str/starts-with? % "TOVER_") (vec (.keySet environment)))]
                      (.remove environment key))
        _           (when mode (.put environment "TOVER_IDENTITY_MODE" mode))
        process     (.start (.redirectErrorStream builder true))
        output      (future (slurp (.getInputStream process)))]
    (try
      (when-not (.waitFor process 30 TimeUnit/SECONDS)
        (throw (ex-info "Configuration subprocess timed out" {})))
      (let [text   (deref output 2000 "")
            result (some #(when (str/starts-with? % "TOVER_CONFIG_CHECK ")
                            (edn/read-string (subs % (count "TOVER_CONFIG_CHECK "))))
                         (str/split-lines text))]
        (when-not (and (zero? (.exitValue process)) result)
          (throw (ex-info "Configuration subprocess failed" {:output text})))
        result)
      (finally (.destroyForcibly process)))))


(deftest production-fails-closed-and-development-defaults-to-local-supabase
  (let [result (isolated-config-check nil)]
    (is (= :configuration (:production-error result)))
    (is (nil? (:development-error result)))
    (is (every? true? (map result [:provider-options? :jdbc-sessions? :dev-handler? :reload-dependency? :production-handler-absent? :development?]))
        (pr-str result))
    (is (every? false? (map result [:fixture-auth? :fixture-workspaces? :fixture-sessions? :seed-dependency? :scoped-seed?]))
        (pr-str result))))


(deftest explicit-fixtures-retain-scoped-seeds-and-memory-services
  (let [result (isolated-config-check "fixtures")]
    (is (nil? (:development-error result)))
    (is (every? true? (map result [:fixture-auth? :fixture-workspaces? :fixture-sessions? :seed-dependency? :scoped-seed?]))
        (pr-str result))
    (is (every? false? (map result [:provider-options? :jdbc-sessions?])) (pr-str result))))


(deftest explicit-supabase-and-unknown-modes-do-not-fall-back-to-local-settings
  (doseq [mode ["supabase" "unknown"]]
    (is (= :configuration (:development-error (isolated-config-check mode))))))


(deftest local-environment-derives-generated-profile-settings
  (doseq [overrides [{} {"IDENTITY_API_PORT" "58111" "IDENTITY_DB_PORT" "55111" "TO_DEV_SITE_URL" "http://localhost:3099"}]]
    (let [settings (merge {"ANON_KEY"            "synthetic-public"   "SERVICE_ROLE_KEY"       "synthetic-admin"
                           "TO_DEV_APP_PASSWORD" "synthetic-password" "SESSION_ENCRYPTION_KEY" "synthetic-session-key"}
                          overrides)
          content  (str/join "\n" (map (fn [[key value]] (str key "=" value)) settings))
          api-port (or (System/getenv "IDENTITY_API_PORT") (get settings "IDENTITY_API_PORT") "58000")
          db-port  (or (System/getenv "IDENTITY_DB_PORT") (get settings "IDENTITY_DB_PORT") "55432")
          origin   (or (System/getenv "TO_DEV_SITE_URL") (get settings "TO_DEV_SITE_URL") "http://localhost:3030")]
      (with-redefs [io/reader (fn [path]
                                (is (= "deployment/local-identity/.env" path))
                                (StringReader. content))]
        (is (= {"TOVER_AUTH_URL"    (str "http://127.0.0.1:" api-port "/auth/v1")
                "TOVER_REST_URL"    (str "http://127.0.0.1:" api-port "/rest/v1")
                "TOVER_API_KEY"     "synthetic-public" "TOVER_AUTH_ADMIN_KEY" "synthetic-admin"
                "TOVER_ORIGIN"      origin "TOVER_JDBC_URL"                   (str "jdbc:postgresql://127.0.0.1:" db-port "/postgres")
                "TOVER_DB_USER"     "tover_app" "TOVER_DB_PASSWORD"           "synthetic-password"
                "TOVER_SESSION_KEY" "synthetic-session-key"}
               (user/local-environment)))))))


(deftest missing-local-profile-and-credentials-fail-closed
  (with-redefs [io/reader (fn [_] (throw (FileNotFoundException. "synthetic-missing-profile")))]
    (is (= :configuration
           (try (user/local-environment) nil
                (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))))
  (with-redefs [io/reader (fn [_] (StringReader. ""))]
    (is (= :configuration
           (try (app/config 3030 ".data/inventory" {:development? true :environment (user/local-environment)}) nil
                (catch clojure.lang.ExceptionInfo error (:type (ex-data error))))))))
