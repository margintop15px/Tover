(ns tover.support
  "Application-level test helpers over the offline fixtures in `tover.fixtures`."
  (:require
    [bel-postgres.adapter :as postgres]
    [bel-rdf.adapter :as bel-rdf]
    [bel.realtime :as realtime]
    [bel.system :as system]
    [charred.api :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [tover.app :as app]
    [tover.catalogues.persistence :as catalogues]
    [tover.fixtures :as fixtures]
    [tover.identity.provider :as provider]
    ;; Directory coeffects that commands name by keyword.
    [tover.invitations.persistence]
    [tover.shared.rdf :as rdf]
    [tover.workspaces.persistence])
  (:import
    [com.sun.net.httpserver HttpHandler HttpServer]
    [java.io ByteArrayInputStream]
    [java.net InetSocketAddress]
    [java.nio.charset StandardCharsets]
    [java.nio.file Files]))


(defn bel-system
  "The web Bel system as tover.app composes it, over whichever of :datasource and :repo is given."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [datasource repo] :as resources}]
  (system/system
    {:stores    (cond-> {}
                  datasource (assoc :directory (postgres/store {:datasource datasource}))
                  repo (assoc :catalogue (bel-rdf/store {:repo repo :graphs catalogues/graphs :guard catalogues/legacy-guard})))
     :journal   :directory
     :scope     :workspace
     :authority {:store :directory :impl 'tover.identity.session/->CurrentSession}
     :resources (select-keys resources [:realtime :provider :session-store :origin])}))


(defn delivery-system
  "The worker's Bel system as tover.app composes it."
  {:malli/schema [:=> [:cat [:map [:datasource :any]]] :map]}
  [{:keys [datasource repo mailer origin]}]
  (system/system
    {:stores    (cond-> {:directory (postgres/store {:datasource datasource})}
                  repo (assoc :catalogue (bel-rdf/store {:repo repo :graphs catalogues/graphs})))
     :journal   :directory
     :scope     :workspace
     :resources {:mailer mailer :origin origin}}))


(defn- hidden-value
  {:malli/schema [:=> [:cat :string :string] [:maybe :string]]}
  [body name]
  (second (re-find (re-pattern (str "<input[^>]*name=\"" name "\"[^>]*value=\"([^\"]*)\"")) body)))


(defn browser
  "A cookie-keeping client. (request! method uri params options) adds the CSRF token and context version
  last seen on a page to POSTs; options :csrf? false, :context? false, :origin, :headers."
  {:malli/schema [:=> [:cat [:fn ifn?]] :map]}
  [handler]
  (let [state (atom {})]
    {:state state
     :request!
     (fn request!
       ([method uri]
        (request! method uri {} {}))
       ([method uri params]
        (request! method uri params {}))
       ([method uri params options]
        (let [post?       (= method :post)
              params      (merge (cond-> {}
                                   (and post? (not= false (:csrf? options)))
                                   (assoc "__anti-forgery-token" (:csrf @state))
                                   (and post? (not= false (:context? options)))
                                   (assoc "_tover-context" (:context @state))) params)
              encoded     (if (seq params) (subs (provider/query params) 1) "")
              origin      (if (contains? options :origin) (:origin options) "http://localhost:3030")
              response    (handler (cond-> {:uri     uri :request-method method :scheme :http :server-name "localhost" :server-port 3030
                                            :headers (merge (cond-> {"host" "localhost:3030"}
                                                              (:cookie @state) (assoc "cookie" (:cookie @state))
                                                              origin (assoc "origin" origin)
                                                              post? (assoc "content-type" "application/x-www-form-urlencoded"))
                                                            (:headers options))}
                                     post? (assoc :body (ByteArrayInputStream. (.getBytes encoded "UTF-8")))
                                     (and (not post?) (seq params)) (assoc :query-string encoded)))
              set-cookie  (get-in response [:headers "Set-Cookie"])
              set-cookies (if (string? set-cookie) [set-cookie] set-cookie)
              body        (if (string? (:body response)) (:body response) "")]
          (when (seq set-cookies)
            (swap! state
                   (fn [current]
                     (let [cookies (reduce (fn [cookies cookie]
                                             (let [[name value] (str/split (first (str/split cookie #";")) #"=" 2)]
                                               (if (str/includes? (str/lower-case cookie) "max-age=0")
                                                 (dissoc cookies name) (assoc cookies name value))))
                                           (:cookies current {}) set-cookies)]
                       (assoc current :cookies cookies :cookie (str/join "; " (map (fn [[k v]] (str k "=" v)) cookies)))))))
          (when-let [csrf (hidden-value body "__anti-forgery-token")] (swap! state assoc :csrf csrf))
          (when-let [context (second (re-find #"<meta name=\"tover-context\" content=\"([^\"]*)\"" body))]
            (swap! state assoc :context context))
          response)))}))


(defn with-application
  "Run f with the fixture services, a running handler and a `:browser`. Services also carry :users (owner,
  member, other, unnamed), :workspace-id and :other-workspace-id. `configure` may replace services first."
  {:malli/schema [:function
                  [:=> [:cat [:=> [:cat :map] :any]] :any]
                  [:=> [:cat [:=> [:cat :map] :map] [:=> [:cat :map] :any]] :any]]}
  ([f]
   (with-application identity f))
  ([configure f]
   (let [path     (.toFile (Files/createTempDirectory "tover-http-test-" (make-array java.nio.file.attribute.FileAttribute 0)))
         repo     (rdf/native-store path)
         services (fixtures/system)
         services (configure (assoc services :repo repo
                                    :users (mapv #(fixtures/user services %)
                                                 ["owner@example.test" "member@example.test" "other@example.test" "unnamed@example.test"])
                                    :workspace-id fixtures/workspace-id :other-workspace-id fixtures/other-workspace-id))
         realtime (app/realtime services)
         services (assoc services :realtime realtime)
         services (assoc services :bel/system (fixtures/bel-system services))
         handler  (app/create-handler services)]
     (try (f (assoc services :handler handler :browser (browser handler)))
          (finally
            (realtime/stop! realtime)
            (rdf/close! repo)
            (doseq [file (reverse (file-seq path))] (io/delete-file file)))))))


(defn sign-in!
  "Give `browser` a live session for `email` (see fixtures/sign-in! for opts) and load `uri` to pick up its
  CSRF token and context version. Returns the session."
  {:malli/schema [:=> [:cat :map :map :string [:? :map]] :map]}
  ([services browser email] (sign-in! services browser email {}))
  ([services {:keys [state request!]} email opts]
   (let [session (fixtures/sign-in! services email (dissoc opts :uri))]
     (swap! state assoc :cookie (str "tover-session=" (:key session)) :cookies {"tover-session" (:key session)}
            :csrf (:csrf session) :context (str (:version session)))
     (request! :get (:uri opts "/account/profile"))
     session)))


(defn session-key
  {:malli/schema [:=> [:cat :map] [:maybe :string]]}
  [{:keys [state]}]
  (get-in @state [:cookies "tover-session"]))


(def user-id
  #uuid "10000000-0000-4000-8000-000000000001")


(defn with-server
  "Run a real loopback HTTP adapter test without contacting an identity provider."
  {:malli/schema [:=> [:cat [:=> [:cat :map] :map] [:=> [:cat :map] :any]] :any]}
  [respond f]
  (let [requests (atom [])
        server   (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (try
                          (let [request {:method  (keyword (str/lower-case (.getRequestMethod exchange)))
                                         :path    (.getPath (.getRequestURI exchange))
                                         :query   (.getRawQuery (.getRequestURI exchange))
                                         :headers (into {} (map (fn [[k v]] [(str/lower-case k) (first v)]))
                                                        (.getRequestHeaders exchange))
                                         :body    (let [body (slurp (.getRequestBody exchange))]
                                                    (when-not (str/blank? body) (json/read-json body :key-fn keyword)))}
                                _ (swap! requests conj request)
                                {:keys [status body headers]} (merge {:status 200} (respond request))
                                bytes (.getBytes (if (string? body) body (json/write-json-str body)) StandardCharsets/UTF_8)]
                            (doseq [[key value] headers] (.set (.getResponseHeaders exchange) key value))
                            (.sendResponseHeaders exchange status (if (= status 204) -1 (alength bytes)))
                            (when-not (= status 204)
                              (.write (.getResponseBody exchange) bytes)))
                          (catch java.io.IOException _ nil)
                          (finally (.close exchange))))))
    (.start server)
    (try
      (f {:url (str "http://127.0.0.1:" (.getPort (.getAddress server))) :requests requests})
      (finally (.stop server 0)))))


(defn error-data
  {:malli/schema [:=> [:cat [:=> [:cat] :any]] :map]}
  [f]
  (try (f) {}
       (catch clojure.lang.ExceptionInfo error
         (assoc (ex-data error) :message (.getMessage error)))))
