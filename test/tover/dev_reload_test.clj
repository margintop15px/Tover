(ns tover.dev-reload-test
  (:require
    [bel.core :as bel]
    [bel.session :as session]
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [integrant.core :as ig]
    [ring.hot-reload.core :as hot]
    [ring.hot-reload.watcher :as watcher]
    [tover.app :as app]
    [tover.operation-examples :as operation-examples]
    [user]))


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
  (let [options (atom nil)
        reloader {:test :reloader}
        stopped (atom nil)]
    (with-redefs [hot/hot-reloader (fn [value] (reset! options value) reloader)
                  hot/start! (fn [value] (is (= reloader value)) :test-watcher)
                  hot/stop! (fn [value handle] (reset! stopped [value handle]))]
      (let [browser-reload (user/browser-reload {})]
        (is (= ["src" "resources"] (:watch-paths @options)))
        (is (every? (:watch-extensions @options) [".clj" ".css" ".js"]))
        (is (= {:reloader reloader :watcher :test-watcher} browser-reload))
        (ig/halt-key! :user/browser-reload browser-reload)
        (is (= [reloader :test-watcher] @stopped))))))


(deftest dev-handler-injects-reload-and-preserves-session-identity
  (with-reloader
    (fn [browser-reload]
      (let [created (atom 0)
            options (atom nil)
            sessions (atom [])
            page {:status 200 :headers {"content-type" "text/html"}
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
          (let [handler (user/handler {:browser-reload browser-reload})
                response (handler {:request-method :get :uri "/" :headers {}})
                set-cookie (get-in response [:headers "Set-Cookie"])
                cookie (first (str/split (if (string? set-cookie) set-cookie (first set-cookie)) #";" 2))]
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
                      bel/init! (constantly nil)]
          (let [handler (user/handler {:browser-reload browser-reload})]
            (is (= response (handler {:request-method :get :uri "/asset" :headers {}})))))))))


(deftest dev-config-selects-dev-handler-without-changing-production-config
  (let [production (app/config)
        development (user/dev-config)]
    (is (= (ig/ref :user/handler) (get-in development [::app/server :handler])))
    (is (= (ig/ref :user/browser-reload) (get-in development [:user/handler :browser-reload])))
    (is (= (ig/ref :user/opening-stock) (get-in development [:user/handler :opening-stock])))
    (is (not (contains? development ::app/handler)))
    (is (= (::app/handler production)
           (dissoc (:user/handler development) :browser-reload :opening-stock)))
    (is (= production (app/config)))
    (is (= (ig/ref ::app/handler) (get-in production [::app/server :handler])))
    (is (not (contains? production :user/browser-reload)))))
