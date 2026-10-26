;; Focused adaptation of HVN's typed input, pure rendering and SSE dispatch.
(ns bel.handler
  (:require
    [bel.core :as core]
    [bel.log :as log]
    [bel.validation :as validation]
    [charred.api :as charred]
    [clojure.string :as str]
    [com.brunobonacci.mulog :as mu]
    [dev.onionpancakes.chassis.core :as chassis]
    [reitit.core :as reitit]
    [reitit.ring :as ring]
    [ring.util.codec :as codec]
    [starfederation.datastar.clojure.adapter.http-kit2 :as hk]
    [starfederation.datastar.clojure.api :as datastar]) (:import [java.io IOException]))


(def Prepared
  [:vector
   [:or
    [:map [:type [:= :elements]] [:html :string] [:options :map]]
    [:map [:type [:= :signals]] [:json :string]]]])


(defn parse-signals
  "Read native form fields or one JSON payload before schema decoding."
  {:malli/schema [:=> [:cat :map] :map]}
  [request]
  (try
    (if (seq (:form-params request))
      (:params request)
      (if-let [raw (datastar/get-signals request)]
        (validation/check! :map (charred/read-json raw :key-fn keyword))
        {}))
    (catch Exception exception
      (throw (ex-info "Invalid JSON payload" {:type :validation :bel/input-error? true} exception)))))


(defn render-view
  {:malli/schema [:=> [:cat :map :qualified-keyword :map] core/Result]}
  [context id input]
  (if-let [definition (core/get-view id)]
    (let [{:bel/keys [query fn output schema]} definition
          input (validation/decode-input schema input)
          data (mu/trace ::view-query [:view id] (query context input))]
      (mu/trace ::view-render [:view id]
        (validation/check! output (fn data))))
    (throw (ex-info "Undeclared view" {:type :validation :id id}))))


(defn render-html
  {:malli/schema [:=> [:cat :any] :string]}
  [hiccup]
  (mu/trace ::html-render {:pairs [] :capture (fn [html] {:characters (count html)})}
    (if (string? hiccup)
      hiccup
      (chassis/html hiccup))))


(defn- patch-options
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [selector patch-mode]}]
  (cond-> {}
    selector (assoc :d*.elements/selector selector)
    patch-mode (assoc :d*.elements/patch-mode (name patch-mode))))


(defn- prepare-elements
  "Keep table roots separate so each fragment retains its HTML parsing context."
  {:malli/schema [:=> [:cat :any :map] Prepared]}
  [hiccup options]
  (if (and (sequential? hiccup)
           (not (and (vector? hiccup) (chassis/element-vector? hiccup))))
    (vec (mapcat #(prepare-elements % options) hiccup))
    (let [html (render-html hiccup)]
      (if (or (not (str/blank? html))
              (and (:d*.elements/selector options) (= "remove" (:d*.elements/patch-mode options))))
        [{:type :elements :html html :options options}]
        []))))


(defn prepare-result
  "Resolve queries, render HTML and validate every instruction before sending any event."
  {:malli/schema [:=> [:cat :map core/Result] Prepared]}
  [context result]
  (letfn [(prepare [result defaults]
            (validation/check! core/Result result)
            (let [result' (if (vector? result) result [result])]
              (mapcat (fn [instruction]
                        (let [options (merge defaults (patch-options instruction))]
                          (case (:type instruction)
                            :view (prepare (render-view context (:id instruction) (or (:input instruction) {})) options)
                            :elements (prepare-elements (:html instruction) options)
                            :signals [{:type :signals
                                       :json (validation/signal-json (or (:schema instruction) [:map-of :keyword :any])
                                                                     (:data instruction))}]
                            :page (throw (ex-info "Page instructions require an action response"
                                                  {:type :validation})))))
                      result')))]
    (mu/trace ::prepare-result []
      (vec (prepare result {})))))


(defn send-prepared!
  {:malli/schema [:=> [:cat :any Prepared] :nil]}
  [sse prepared]
  (mu/trace ::send-prepared [:patches (count prepared)]
    (doseq [{:keys [type html options json]} prepared]
      (when (false? (case type
                      :elements (datastar/patch-elements! sse html options)
                      :signals (datastar/patch-signals! sse json)))
        (throw (IOException. "SSE connection closed before delivery")))))
  nil)


(defn send-result!
  {:malli/schema [:=> [:cat :map :any core/Result] :nil]}
  [context sse result]
  (send-prepared! sse (prepare-result context result)))


(defn execute-action
  {:malli/schema [:=> [:cat :map :qualified-keyword :map] core/ActionResult]}
  [context id input]
  (if-let [definition (core/get-action id)]
    (let [{:bel/keys [fn output schema]} definition
          input (try
                  (validation/decode-input schema input)
                  (catch clojure.lang.ExceptionInfo error
                    (if (= :validation (:type (ex-data error)))
                      (throw (ex-info (.getMessage error) (assoc (ex-data error) :bel/input-error? true) error))
                      (throw error))))]
      (mu/trace ::action [:action id]
        (validation/check! core/ActionResult (validation/check! output (fn context input)))))
    (throw (ex-info "Undeclared action" {:type :validation :id id}))))


(defn- log-error!
  {:malli/schema [:=> [:cat :any] :nil]}
  [exception]
  (mu/log ::request-error :error-type (.getName (class exception)))
  nil)


(defn- error-response
  {:malli/schema [:=> [:cat :map :any] :map]}
  [request exception]
  (log-error! exception)
  {:status  (if (= :validation (:type (ex-data exception))) 422 500)
   :headers {"content-type" "text/plain; charset=utf-8"}
   :body    (if-let [message (:bel/error-message request)]
              (message exception) "The request could not be completed.")})


(defn- short-response
  {:malli/schema [:=> [:cat :map Prepared] :map]}
  [request prepared]
  (let [trace-context (log/capture-context)]
    (hk/->sse-response
      request
      {hk/write-profile hk/buffered-writer-profile
       hk/on-open
       (fn [sse]
         (mu/with-context trace-context
           (try
             (send-prepared! sse prepared)
             (catch Exception exception
               (log-error! exception))
             (finally
               (datastar/close-sse! sse)))))})))


(defn- request-context
  {:malli/schema [:=> [:cat :map :map] :map]}
  [context request]
  (assoc (merge context (:bel/context request)) :request request))


(defn- render-page
  {:malli/schema
   [:=> [:cat :map :qualified-keyword :map [:vector :map]]
    [:vector :any]]}
  [context id input pages]
  (let [pages (mapv (fn [{:bel/keys [query schema] :as page}]
                      [page (mu/trace ::page-query [:page (:bel/id page)]
                              (query context (validation/decode-input schema input)))])
                    pages)]
    (reduce (fn [child [{:bel/keys [fn output] :as page} data]]
              (mu/trace ::page-render [:page (:bel/id page)]
                (let [result (validation/check! output (if child (fn data child) (fn data)))]
                  (validation/check! core/PageResult result)
                  (when-not (and (vector? (:html result)) (chassis/element-vector? (:html result)))
                    (throw (ex-info "Page must render one rooted element" {:type :validation :id id})))
                  (:html result))))
            nil (rseq pages))))


(defn- canonical-url
  {:malli/schema
   [:=> [:cat :map]
    :string]}
  [{:keys [uri query-string]}]
  (let [query (->> (str/split (or query-string "") #"&")
                   (remove #(or (str/blank? %)
                                (= "datastar" (codec/url-decode (first (str/split % #"=" 2))))))
                   (str/join "&"))]
    (str (or uri "/") (when (seq query) (str "?" query)))))


(defn- render-page-response
  {:malli/schema
   [:=> [:cat :map :qualified-keyword [:vector :any] :map]
    :map]}
  [request id hiccup {:keys [document title]}]
  (let [container [:div#bel-page (cond-> {:data-bel-url   (canonical-url request)
                                          :data-bel-title (or title "")
                                          :data-bel-page  (str (symbol (namespace id) (name id)))}
                                   (:bel/navigation? request) (assoc :data-bel-navigation "true")
                                   (:bel/reset-form? request) (assoc :data-bel-reset-form "true"))
                   hiccup]]
    (if (datastar/datastar-request? request)
      (short-response request (prepare-elements container {}))
      {:status  200
       :headers {"content-type" "text/html; charset=utf-8"}
       :body    (str "<!DOCTYPE html>\n"
                     (render-html (if document
                                    (document {:request request :title title} container)
                                    container)))})))


(defn- endpoint-data
  {:malli/schema [:=> [:cat :map] [:maybe :map]]}
  [request]
  (get-in (ring/get-match request) [:result (:request-method request) :data]))


(defn page-handler
  "Render the GET page selected by Reitit, as a document or one Datastar patch."
  {:malli/schema [:=> [:cat :map] [:fn ifn?]]}
  [context]
  (fn [request]
    (let [id (:bel/page (endpoint-data request))]
      (if (and id (core/get-page id))
        (try
          (let [context (request-context context request)
                input   (merge (update-keys (dissoc (:query-params request) "datastar") keyword)
                               (parse-signals request)
                               (:path-params request))
                pages   (core/page-chain id)
                options {:document (last (keep :bel/document pages))
                         :title    ((or (:bel/translate-title request) identity) (:bel/title (last pages)))}
                hiccup  (render-page context id input pages)]
            (render-page-response request id hiccup options))
          (catch Exception exception
            (if (:status (ex-data exception))
              (throw exception)
              (error-response request exception))))
        {:status 404 :body "Page not found"}))))


(defn- destination
  {:malli/schema [:=> [:cat :map :map] :map]}
  [request {:keys [id input path-params]}]
  (let [router (:reitit.core/router request)
        match  (when router (reitit/match-by-name router id (or path-params {})))
        url    (when (instance? reitit.core.Match match)
                 (reitit/match->path match input))]
    (when-not (and url (core/get-page id)
                   (= id (get-in match [:result :get :data :bel/page])))
      (throw (ex-info "Page destination requires a routed page and all path parameters"
                      {:type :validation :id id})))
    {:url url :match (reitit/match-by-path router (:path match))}))


(defn- dispatch-page
  {:malli/schema [:=> [:cat :map :map :map] :map]}
  [request instruction retained-form-params]
  (let [{:keys [url match]} (destination request instruction)
        handler             (get-in match [:result :get :handler])
        [uri query-string]  (str/split url #"\?" 2)
        query-params        (if (seq query-string) (codec/form-decode query-string) {})
        request             (-> request
                                (dissoc :body :body-params)
                                (assoc :request-method :get
                                       :uri uri
                                       :query-string query-string
                                       :query-params query-params
                                       :form-params retained-form-params
                                       :params (merge query-params retained-form-params)
                                       :path-params (:path-params match)
                                       :reitit.core/match match
                                       :bel/navigation? true))]
    (when-not handler
      (throw (ex-info "Page destination has no GET handler" {:type :validation :id (:id instruction)})))
    (handler request)))


(defn page-response
  "Navigate to a named page. Datastar renders through destination middleware; forms redirect."
  {:malli/schema [:=> [:cat :map core/Instruction] :map]}
  [request instruction]
  (validation/check! core/Instruction instruction)
  (if (and (datastar/datastar-request? request) (not (:reload? instruction)))
    (dispatch-page (assoc request :bel/reset-form? true) instruction {})
    (let [{:keys [url]} (destination request instruction)]
      (if (datastar/datastar-request? request)
        (hk/->sse-response
          request
          {:headers   {"Content-Type" "text/event-stream; charset=utf-8"}
           hk/on-open (fn [sse]
                        (try (datastar/redirect! sse url)
                             (finally (datastar/close-sse! sse))))})
        {:status 303 :headers {"location" url} :body ""}))))


(defn error-page-response
  "Render an error destination through its middleware, retaining only explicitly selected fields."
  {:malli/schema [:=> [:cat :map :qualified-keyword :map] :map]}
  [request id retained-form-params]
  (dispatch-page (dissoc request :bel/reset-form?) {:type :page :id id} retained-form-params))


(defn view-handler
  {:malli/schema [:=> [:cat :map] [:fn ifn?]]}
  [context]
  (fn [request]
    (let [id (:bel/view (endpoint-data request))]
      (if-let [definition (when id (core/get-view id))]
        (try
          (let [context     (request-context context request)
                input       (parse-signals request)
                instruction (cond-> {:type :view :id id :input input}
                              (:bel/selector definition) (assoc :selector (:bel/selector definition))
                              (:bel/patch-mode definition) (assoc :patch-mode (:bel/patch-mode definition)))]
            (short-response request (prepare-result context instruction)))
          (catch Exception exception
            (if (:status (ex-data exception)) (throw exception) (error-response request exception))))
        {:status 404 :body "View not found"}))))


(defn action-handler
  {:malli/schema [:=> [:cat :map] [:fn ifn?]]}
  [context]
  (fn [request]
    (let [id (:bel/action (endpoint-data request))]
      (if (and id (core/get-action id))
        (try
          (let [context (request-context context request)
                input   (parse-signals request)
                result  (execute-action context id input)]
            (if (and (map? result) (not (:type result)))
              result
              (merge (if (= :page (:type result))
                       (page-response request result)
                       (short-response request (prepare-result context result)))
                     (when (map? result) (select-keys result [:session :session/key :cookies])))))
          (catch Exception exception
            (if (or (:status (ex-data exception))
                    (and (:bel/error-page (endpoint-data request))
                         (or (:bel/input-error? (ex-data exception))
                             (not= :validation (:type (ex-data exception))))))
              (throw exception)
              (error-response request exception))))
        {:status 404 :body "Action not found"}))))


(defn routes
  "Generate declaration routes and combine distinct HTTP methods sharing the same path."
  {:malli/schema [:=> [:cat :map [:sequential :any]] [:vector :any]]}
  [context extra-routes]
  (let [handlers  {:pages   (page-handler context)
                   :views   (view-handler context)
                   :actions (action-handler context)}
        generated (for [section             [:pages :views :actions]
                        [id definition-var] (sort-by key (get @core/registry section))
                        :let                [{:bel/keys [route route-data]} (meta definition-var)
                                             path (case section
                                                    :pages route
                                                    :views (core/view-url id)
                                                    :actions (core/action-url id))]
                        :when               path]
                    [path {:name id
                           (if (= section :pages) :get :post)
                           (assoc route-data :handler (get handlers section)
                                  (case section :pages :bel/page :views :bel/view :actions :bel/action) id)}])]
    (reduce (fn [result [path data :as route]]
              (when-not (and (= 2 (count route)) (map? data))
                (throw (ex-info "Extra routes must be flat path/data pairs" {:type :validation :route route})))
              (if-let [index (first (keep-indexed #(when (= path (first %2)) %1) result))]
                (let [existing   (second (nth result index))
                      duplicates (seq (filter #(contains? existing %) (keys data)))]
                  (when duplicates
                    (throw (ex-info "Duplicate route data or HTTP method"
                                    {:type :validation :path path :keys (vec duplicates)})))
                  (assoc result index [path (merge existing data)]))
                (conj result route)))
            [] (concat generated extra-routes))))
