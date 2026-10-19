(ns tover.app-test
  (:require [bel.realtime :as realtime]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [org.httpkit.server :as http]
            [tover.app :as app]
            [tover.rdf :as rdf]
            [tover.store :as store])
  (:import [java.nio.file Files]))


(defn- with-data-path
  {:malli/schema [:=> [:cat [:=> [:cat :string] :any]] :any]}
  [f]
  (let [directory (.toFile (Files/createTempDirectory "tover-app-test-" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try (f (.getPath directory))
         (finally
           (doseq [file (reverse (file-seq directory))] (io/delete-file file))))))


(deftest ring-protects-writes-with-the-datastar-header
  (with-data-path
    (fn [path]
      (let [repo (rdf/native-store path)
            realtime (realtime/start!)]
        (try
          (let [handler (app/create-handler {:repo repo :realtime realtime})
                ;; A missing route reaches routing only after passing anti-forgery.
                request {:remote-addr    "127.0.0.1"
                         :request-method :post
                         :uri            "/missing"
                         :headers        {"host" "localhost:3030" "datastar-request" "true"}}]
            (is (= 404 (:status (handler request))))
            (is (= 404 (:status (handler (assoc request :remote-addr "[0:0:0:0:0:0:0:1]")))))
            (is (= 404 (:status (handler (assoc request :remote-addr "192.168.1.2")))))
            (is (= 404 (:status (handler (assoc-in request [:headers "host"] "other.example:3030")))))
            (is (= 403 (:status (handler (update request :headers dissoc "datastar-request")))))
            (is (= 403 (:status (handler (assoc-in request [:headers "datastar-request"] " ")))))
            (is (= 404 (:status (handler (-> request (assoc :request-method :get)
                                             (update :headers dissoc "datastar-request")))))))
          (finally
            (realtime/stop! realtime)
            (rdf/close! repo)))))))


(deftest lifecycle-orders-resources-and-supports-restart
  (with-data-path
    (fn [path]
      (let [opened (atom nil)
            realtimes (atom [])
            stops (atom 0)
            native-store rdf/native-store
            start-realtime realtime/start!]
        (with-redefs [rdf/native-store (fn [path] (reset! opened (native-store path)))
                      realtime/start! (fn []
                                        (let [realtime (start-realtime)]
                                          (swap! realtimes conj realtime)
                                          realtime))
                      http/run-server (fn [handler options]
                                        (is (.isInitialized @opened))
                                        (is (= {:rows [] :history []} (store/snapshot @opened)))
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
          (let [first-system (app/start! (app/config 3031 path))
                first-repo (::app/repo first-system)]
            (try
              (is (identical? @opened first-repo))
              (is (not (contains? (app/config 3031 path) ::app/registry)))
              (is (not (contains? (::app/handler (app/config 3031 path)) :registry)))
              (is (not (contains? first-system ::app/registry)))
              (is (not (contains? (::app/realtime first-system) :repo)))
              (let [response ((::app/handler first-system) {:request-method :get :uri "/" :headers {}})]
                (is (= 200 (:status response)))
                (is (re-find #"<title>Inventory · Tover v2</title>" (:body response)))
                (is (not (re-find #"Demo data|demo-notebook|data-examples|data-on:examples-toggle" (:body response))))
                (is (= {:rows [] :history []} (store/snapshot first-repo))))
              (finally (is (nil? (app/stop! first-system)))))
            (is (not (.isInitialized first-repo)))
            (is (= 1 @stops))
            (let [second-system (app/start! (app/config 3031 path))]
              (try
                (is (.isInitialized (::app/repo second-system)))
                (finally (app/stop! second-system)))
              (is (not (.isInitialized (::app/repo second-system))))
              (is (= 2 @stops)))))))))


(deftest failed-server-start-releases-repository-for-retry
  (with-data-path
    (fn [path]
      (let [opened (atom nil)
            realtime (atom nil)
            attempts (atom 0)
            cause (IllegalStateException. "server unavailable")
            failure (ex-info "server failed to start" {:type :test} cause)
            native-store rdf/native-store
            start-realtime realtime/start!]
        (with-redefs [rdf/native-store (fn [path] (reset! opened (native-store path)))
                      realtime/start! (fn [] (reset! realtime (start-realtime)))
                      http/run-server (fn [_ _]
                                        (is (.isInitialized @opened))
                                        (when (= 1 (swap! attempts inc)) (throw failure))
                                        (Object.))
                      http/server-stop! (fn [_ _] (deliver (promise) true))]
          (let [error (try (app/start! (app/config 3031 path)) nil
                           (catch Throwable error error))]
            (is (some #(identical? failure %) (take-while some? (iterate ex-cause error))))
            (is (some #(identical? cause %) (take-while some? (iterate ex-cause error))))
            (is (:stopped? @(:state @realtime)))
            (is (not (.isInitialized @opened))))
          (let [retry-system (app/start! (app/config 3031 path))]
            (try
              (is (.isInitialized (::app/repo retry-system)))
              (is (= 2 @attempts))
              (finally (app/stop! retry-system)))))))))


(deftest failed-server-stop-releases-repository-for-retry
  (with-data-path
    (fn [path]
      (let [stops (atom 0)
            cause (IllegalStateException. "server stop unavailable")
            failure (ex-info "server failed to stop" {:type :test} cause)]
        (with-redefs [http/run-server (fn [_ _] (Object.))
                      http/server-stop! (fn [_ _]
                                          (when (= 1 (swap! stops inc)) (throw failure))
                                          (deliver (promise) true))]
          (let [system (app/start! (app/config 3031 path))
                repo (::app/repo system)
                error (try (app/stop! system) nil (catch Throwable error error))]
            (is (some #(identical? failure %) (take-while some? (iterate ex-cause error))))
            (is (some #(identical? cause %) (take-while some? (iterate ex-cause error))))
            (is (:stopped? @(:state (::app/realtime system))))
            (is (not (.isInitialized repo))))
          (let [retry-system (app/start! (app/config 3031 path))]
            (try
              (is (.isInitialized (::app/repo retry-system)))
              (finally (app/stop! retry-system))))
          (is (= 2 @stops)))))))


(deftest failed-start-cleans-up-even-when-integrant-halt-fails
  (with-data-path
    (fn [path]
      (let [realtime (atom nil)
            opened (atom nil)
            stops (atom 0)
            native-store rdf/native-store
            start-realtime realtime/start!
            stop-realtime realtime/stop!]
        (with-redefs [rdf/native-store (fn [path] (reset! opened (native-store path)))
                      realtime/start! (fn [] (reset! realtime (start-realtime)))
                      http/run-server (fn [_ _] (throw (IllegalStateException. "start failed")))
                      realtime/stop! (fn [realtime]
                                       (when (= 1 (swap! stops inc))
                                         (throw (IllegalStateException. "halt failed")))
                                       (stop-realtime realtime))]
          (is (thrown? clojure.lang.ExceptionInfo (app/start! (app/config 3031 path))))
          (is (= 2 @stops))
          (is (:stopped? @(:state @realtime)))
          (is (not (.isInitialized @opened))))))))


(deftest stop-joins-an-already-stopping-server
  (with-data-path
    (fn [path]
      (let [joined? (atom false)]
        (with-redefs [http/run-server (fn [_ _] (Object.))
                      http/server-stop! (fn [_ _] nil)
                      http/server-join (fn [_] (reset! joined? true))]
          (let [system (app/start! (app/config 3031 path))]
            (app/stop! system)
            (is @joined?)
            (is (not (.isInitialized (::app/repo system))))))))))


(deftest realtime-stop-failure-still-stops-http-before-closing-rdf
  (with-data-path
    (fn [path]
      (let [calls (atom [])
            http-server (Object.)
            failure (IllegalStateException. "realtime stop failed")
            stop-realtime realtime/stop!
            close-rdf rdf/close!]
        (with-redefs [http/run-server (fn [_ _] http-server)
                      realtime/stop! (fn [realtime]
                                       (swap! calls conj :realtime)
                                       (stop-realtime realtime)
                                       (throw failure))
                      http/server-stop! (fn [server options]
                                          (is (identical? http-server server))
                                          (is (= {:timeout 2000} options))
                                          (swap! calls conj :http-stop)
                                          nil)
                      http/server-join (fn [server]
                                         (is (identical? http-server server))
                                         (swap! calls conj :http-join))
                      rdf/close! (fn [repo]
                                   (swap! calls conj :rdf-close)
                                   (close-rdf repo))]
          (let [system (app/start! (app/config 3031 path))
                error (try (app/stop! system) nil (catch Throwable error error))]
            (is (some #(identical? failure %) (take-while some? (iterate ex-cause error))))
            (is (= [:realtime :http-stop :http-join :rdf-close] @calls))
            (is (not (.isInitialized (::app/repo system))))))))))


(deftest realtime-stop-failure-retains-http-cleanup-failure-as-suppressed
  (with-data-path
    (fn [path]
      (let [failure (IllegalStateException. "realtime stop failed")
            cleanup (IllegalStateException. "http stop failed")
            stop-realtime realtime/stop!]
        (with-redefs [http/run-server (fn [_ _] (Object.))
                      realtime/stop! (fn [realtime]
                                       (stop-realtime realtime)
                                       (throw failure))
                      http/server-stop! (fn [_ _] (throw cleanup))]
          (let [system (app/start! (app/config 3031 path))
                error (try (app/stop! system) nil (catch Throwable error error))]
            (is (some #(identical? failure %) (take-while some? (iterate ex-cause error))))
            (is (= [cleanup] (vec (.getSuppressed failure))))
            (is (not (.isInitialized (::app/repo system))))))))))


(deftest stop-waits-for-http-drain-before-closing-rdf
  (with-data-path
    (fn [path]
      (let [stopping (promise)
            drained (promise)]
        (with-redefs [http/run-server (fn [_ _] (Object.))
                      http/server-stop! (fn [_ _] (deliver stopping true) drained)]
          (let [system (app/start! (app/config 3031 path))
                stopped (future (app/stop! system))]
            (try
              (is (= true (deref stopping 2000 :timeout)))
              (is (.isInitialized (::app/repo system)))
              (is (= :waiting (deref stopped 20 :waiting)))
              (finally (deliver drained true)))
            (is (nil? (deref stopped 2000 :timeout)))
            (is (not (.isInitialized (::app/repo system))))))))))
