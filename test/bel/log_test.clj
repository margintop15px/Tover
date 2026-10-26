(ns bel.log-test
  (:require
    [bel.core :as core]
    [bel.handler :as handler]
    [bel.log :as log]
    [clojure.test :refer [deftest is]]
    [com.brunobonacci.mulog :as mu]
    [com.brunobonacci.mulog.buffer :as buffer]
    [com.brunobonacci.mulog.core :as mu-core]
    [starfederation.datastar.clojure.adapter.http-kit2 :as hk]
    [starfederation.datastar.clojure.api :as datastar]))


(defn- capture
  [f]
  (let [logger (atom (buffer/ring-buffer 1000))
        result (binding [mu-core/*default-logger* logger] (f))]
    {:result result
     :events (mapv (fn [[_ pairs]] (apply #'mu-core/merge-pairs pairs)) (buffer/items @logger))}))


(deftest request-traces-correlate-with-response-timing-without-request-data
  (let [before  (mu/local-context)
        {:keys [result events]}
        (capture
          #((log/wrap-request-context
              (fn [_]
                (mu/trace ::query [] :queried)
                {:status 201 :headers {"server-timing" "existing;dur=1"} :body "unchanged"}))
            {:request-method :get
             :uri            "/private-canary"
             :query-string   "password=query-canary"
             :headers        {"x-request-id" "untrusted-canary" "authorization" "bearer-canary"}}))
        request (first (filter #(= :bel.log/http-request (:mulog/event-name %)) events))
        query   (first (filter #(= ::query (:mulog/event-name %)) events))]
    (is (= 201 (:status result)))
    (is (= "unchanged" (:body result)))
    (is (uuid? (parse-uuid (get-in result [:headers "x-request-id"]))))
    (is (= (:request-id request) (:request-id query) (get-in result [:headers "x-request-id"])))
    (is (= (:mulog/trace-id request) (:mulog/parent-trace query) (:mulog/root-trace query)))
    (is (= :ok (:mulog/outcome request)))
    (is (nat-int? (:mulog/duration request)))
    (is (= 201 (:status request)))
    (is (re-matches #"existing;dur=1, app;dur=[0-9]+\.[0-9]{3}" (get-in result [:headers "server-timing"])))
    (is (not (re-find #"canary" (pr-str events))))
    (is (= before (mu/local-context)))))


(deftest short-sse-writes-keep-the-request-trace-across-threads
  (let [opened (atom nil)
        closed (atom 0)
        view   {:bel/schema [:map] :bel/output core/Result
                :bel/query  (fn [_ _] {:value "safe"})
                :bel/fn     (fn [_] {:type :elements :html [:div "safe"]})}]
    (with-redefs [core/get-view            (constantly view)
                  hk/->sse-response        (fn [_ options] (reset! opened (get options hk/on-open)) {:status 200})
                  datastar/patch-elements! (fn [& _] true)
                  datastar/close-sse!      (fn [_] (swap! closed inc))]
      (let [{:keys [result events]}
            (capture
              (fn []
                (let [response  ((log/wrap-request-context (handler/view-handler {}))
                                 {:request-method    :post :body "{}" :headers {"datastar-request" "true"}
                                  :reitit.core/match {:result {:post {:data {:bel/view ::example}}}}})
                      delivered (future (@opened :synthetic-sse))]
                  (is (nil? (deref delivered 2000 :timeout)))
                  response)))
            root  (first (filter #(= :bel.log/http-request (:mulog/event-name %)) events))
            write (first (filter #(= :bel.handler/send-prepared (:mulog/event-name %)) events))]
        (is (= 200 (:status result)))
        (is (= 1 @closed))
        (is (= 1 (:patches write)))
        (is (= (:request-id root) (:request-id write)))
        (is (= (:mulog/trace-id root) (:mulog/root-trace write) (:mulog/parent-trace write)))
        (is (some #(= :bel.handler/view-query (:mulog/event-name %)) events))
        (is (some #(= :bel.handler/view-render (:mulog/event-name %)) events))))))


(deftest publishers-sanitize-errors-and-stop-only-once
  (let [configuration (atom nil)
        stops         (atom 0)
        before        (mu/global-context)
        failure       (ex-info "exception-canary" {:password "data-canary"})]
    (with-redefs [mu/start-publisher! (fn [options]
                                        (reset! configuration options)
                                        #(swap! stops inc))]
      (let [stop (log/start! {:env :prod})]
        (try
          (let [{:keys [result events]}
                (capture #(try
                            (mu/with-context {:session-id "session-canary"}
                              (mu/trace ::failure [] (throw failure)))
                            (catch Exception error error)))
                published (vec ((:transform @configuration) events))]
            (is (identical? failure result))
            (is (= :console-json (:type @configuration)))
            (is (= :error (:mulog/outcome (first published))))
            (is (= "clojure.lang.ExceptionInfo" (:error-type (first published))))
            (is (not (re-find #"canary" (pr-str published)))))
          (finally
            (stop)
            (stop)))))
    (is (= 1 @stops))
    (is (= before (mu/global-context)))))


(deftest failed-publisher-start-restores-global-context
  (let [before  (mu/global-context)
        failure (IllegalStateException. "publisher unavailable")]
    (with-redefs [mu/start-publisher! (fn [_] (throw failure))]
      (is (identical? failure (try (log/start! {:env :prod})
                                   (catch Exception error error)))))
    (is (= before (mu/global-context)))))
