(ns tover.support
  (:require
    [bel.core :as bel]
    [bel.realtime :as realtime]
    [charred.api :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [tover.app :as app]
    [tover.auth.core :as auth]
    [tover.auth.security :as security]
    [tover.auth.sessions :as sessions]
    [tover.identity :as identity]
    [tover.session-store :as session-store]
    [tover.shared.rdf :as rdf]
    [tover.shared.supabase :as http]
    [tover.workspaces.core :as workspaces])
  (:import
    [com.sun.net.httpserver HttpHandler HttpServer]
    [java.io ByteArrayInputStream]
    [java.net InetSocketAddress]
    [java.nio.charset StandardCharsets]
    [java.nio.file Files]))


(defn- hidden-value
  {:malli/schema [:=> [:cat :string :string] [:maybe :string]]}
  [body name]
  (second (re-find (re-pattern (str "<input[^>]*name=\"" name "\"[^>]*value=\"([^\"]*)\"")) body)))


(defn browser
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
              encoded     (if (seq params) (subs (http/query params) 1) "")
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
  {:malli/schema [:function
                  [:=> [:cat [:=> [:cat :map] :any]] :any]
                  [:=> [:cat [:=> [:cat :map] :map] [:=> [:cat :map] :any]] :any]]}
  ([f]
   (with-application identity f))
  ([configure f]
   (let [path     (.toFile (Files/createTempDirectory "tover-identity-http-test-" (make-array java.nio.file.attribute.FileAttribute 0)))
         repo     (rdf/native-store path)
         services (configure (assoc (identity/fixtures) :session-store (session-store/memory-store)
                                    :origin "http://localhost:3030" :development? true :repo repo))
         realtime (app/realtime services)
         services (assoc services :realtime realtime)
         handler  (app/create-handler services)]
     (try (f (assoc services :handler handler :browser (browser handler)))
          (finally
            (realtime/stop! realtime)
            (rdf/close! repo)
            (doseq [file (reverse (file-seq path))] (io/delete-file file)))))))


(defn login!
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [request!]} user]
  (request! :get "/auth/login")
  (let [response (request! :post (bel/action-url :tover.auth.web/sign-in) {"email" (:email user) "password" (:password user)})]
    (when (= 303 (:status response)) (request! :get "/workspace"))
    response))


(defn session-key
  {:malli/schema [:=> [:cat :map] :string]}
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
                                {:keys [status body headers write-body] :or {status 200}} (respond request)
                                bytes (.getBytes (if (string? body) body (json/write-json-str body)) StandardCharsets/UTF_8)]
                            (doseq [[key value] headers] (.set (.getResponseHeaders exchange) key value))
                            (.sendResponseHeaders exchange status (if (= status 204) -1 (alength bytes)))
                            (when-not (= status 204)
                              (if write-body
                                (write-body (.getResponseBody exchange) bytes)
                                (.write (.getResponseBody exchange) bytes))))
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


(defn authenticated-services
  {:malli/schema [:=> [:cat] :map]}
  []
  (let [{:keys [auth users] :as fixtures} (identity/fixtures)
        store (session-store/memory-store)
        tokens (auth/sign-in! auth (first users))
        key (sessions/establish! store nil tokens)
        services (assoc fixtures :session-store store)
        context (security/authenticate services key)]
    (assoc services :key key :context context)))


(defn request
  {:malli/schema [:=> [:cat :map :keyword :string] :map]}
  [{:keys [key context]} method uri]
  {:uri     uri :request-method method :session/key key :session {:identity/session-id key}
   :headers {"x-tover-context" (str (:auth-version context))}})


(defn auth-provider
  {:malli/schema [:=> [:cat :any :map] :any]}
  [provider overrides]
  (reify auth/Auth
    (sign-up! [_ input] (auth/sign-up! provider input))
    (sign-in! [_ input] (auth/sign-in! provider input))
    (current-user [_ token] (if-let [f (:current-user overrides)] (f token) (auth/current-user provider token)))
    (refresh! [_ token] (if-let [f (:refresh overrides)] (f token) (auth/refresh! provider token)))
    (sign-out! [_ token] (if-let [f (:sign-out overrides)] (f token) (auth/sign-out! provider token)))
    (verify-email! [_ input] (auth/verify-email! provider input))
    (recover! [_ input] (if-let [f (:recover overrides)] (f input) (auth/recover! provider input)))
    (set-locale! [_ token locale] (if-let [f (:set-locale overrides)] (f token locale) (auth/set-locale! provider token locale)))
    (set-password! [_ token password] (auth/set-password! provider token password))
    (invite! [_ input] (if-let [f (:invite overrides)] (f input) (auth/invite! provider input)))
    (magic-link! [_ input] (auth/magic-link! provider input))))


(defn workspace-provider
  {:malli/schema [:=> [:cat :any :map] :any]}
  [provider overrides]
  (reify workspaces/Directory
    (list-memberships [_ token] (if-let [f (:list-memberships overrides)] (f token) (workspaces/list-memberships provider token)))
    (create-workspace! [_ token name] (workspaces/create-workspace! provider token name))
    (team [_ token workspace-id] (if-let [f (:team overrides)] (f token workspace-id) (workspaces/team provider token workspace-id)))
    (actor-labels [_ token workspace-id actor-ids]
      (if-let [f (:actor-labels overrides)]
        (f token workspace-id actor-ids)
        (workspaces/actor-labels provider token workspace-id actor-ids)))
    (manage-invitation! [_ token input] (if-let [f (:manage-invitation overrides)] (f token input) (workspaces/manage-invitation! provider token input)))
    (revoke-reservation! [_ token workspace-id invite-id] (workspaces/revoke-reservation! provider token workspace-id invite-id))
    (request-member-recovery! [_ token workspace-id user-id] (workspaces/request-member-recovery! provider token workspace-id user-id))
    (accept-invites! [_ token] (if-let [f (:accept-invites overrides)] (f token) (workspaces/accept-invites! provider token)))))
