;; Focused adaptation of HVN's typed input, pure rendering and SSE dispatch.
(ns bel.handler
  (:require
    [bel.core :as core]
    [bel.validation :as validation]
    [charred.api :as charred]
    [clojure.string :as str]
    [dev.onionpancakes.chassis.core :as chassis]
    [starfederation.datastar.clojure.adapter.http-kit2 :as hk]
    [starfederation.datastar.clojure.api :as datastar]) (:import [java.io IOException]))


(def Prepared
  [:vector
   [:or
    [:map [:type [:= :elements]] [:html :string] [:options :map]]
    [:map [:type [:= :signals]] [:json :string]]]])


(defn parse-signals
  "Read the request payload once. JSON parsing is followed by schema decoding."
  {:malli/schema [:=> [:cat :map] :map]}
  [request]
  (try
    (if-let [raw (datastar/get-signals request)]
      (validation/check! :map (charred/read-json raw :key-fn keyword))
      {})
    (catch Exception exception
      (throw (ex-info "Invalid JSON payload" {:type :validation} exception)))))


(defn render-view
  {:malli/schema [:=> [:cat :map :qualified-keyword :map] core/Result]}
  [context id input]
  (if-let [definition (or (core/get-view id) (core/get-page id))]
    (let [{:bel/keys [query fn output schema]} definition
          input (validation/decode-input schema input)
          data (query context input)]
      (validation/check! output (fn data)))
    (throw (ex-info "Undeclared view" {:type :validation :id id}))))


(defn render-html
  {:malli/schema [:=> [:cat :any] :string]}
  [hiccup]
  (if (string? hiccup)
    hiccup
    (chassis/html hiccup)))


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
                                                                     (:data instruction))}])))
                      result')))]
    (vec (prepare result {}))))


(defn send-prepared!
  {:malli/schema [:=> [:cat :any Prepared] :nil]}
  [sse prepared]
  (doseq [{:keys [type html options json]} prepared]
    (when (false? (case type
                    :elements (datastar/patch-elements! sse html options)
                    :signals (datastar/patch-signals! sse json)))
      (throw (IOException. "SSE connection closed before delivery"))))
  nil)


(defn send-result!
  {:malli/schema [:=> [:cat :map :any core/Result] :nil]}
  [context sse result]
  (send-prepared! sse (prepare-result context result)))


(defn execute-action
  {:malli/schema [:=> [:cat :map :qualified-keyword :map] core/Result]}
  [context id input]
  (if-let [definition (core/get-action id)]
    (let [{:bel/keys [fn output schema]} definition
          input (validation/decode-input schema input)]
      (validation/check! output (fn context input)))
    (throw (ex-info "Undeclared action" {:type :validation :id id}))))


(defn- log-error!
  {:malli/schema [:=> [:cat :any] :nil]}
  [exception]
  (binding [*out* *err*]
    (println "Datastar request failed:" (.getMessage exception) (:errors (ex-data exception))))
  nil)


(defn- error-response
  {:malli/schema [:=> [:cat :any] :map]}
  [exception]
  (log-error! exception)
  {:status  (if (= :validation (:type (ex-data exception))) 422 500)
   :headers {"content-type" "text/plain; charset=utf-8"}
   :body    "The request could not be completed."})


(defn- short-response
  {:malli/schema [:=> [:cat :map Prepared] :map]}
  [request prepared]
  (hk/->sse-response
    request
    {hk/on-open
     (fn [sse]
       (try
         (send-prepared! sse prepared)
         (catch Exception exception
           (log-error! exception))
         (finally
           (datastar/close-sse! sse))))}))


(defn page-handler
  {:malli/schema [:=> [:cat :map :qualified-keyword] [:fn ifn?]]}
  [context id]
  (fn [request]
    (if (core/get-page id)
      (try
        (let [context (assoc context :request request)
              input (parse-signals request)
              prepared (prepare-result context (render-view context id input))]
          (when (some #(= :signals (:type %)) prepared)
            (throw (ex-info "Page results must contain only elements" {:type :validation})))
          {:status  200
           :headers {"content-type" "text/html; charset=utf-8"}
           :body    (str "<!DOCTYPE html>\n" (str/join (map :html prepared)))})
        (catch Exception exception
          (error-response exception)))
      {:status 404 :body "Page not found"})))


(defn view-handler
  {:malli/schema [:=> [:cat :map] [:fn ifn?]]}
  [context]
  (fn [request]
    (let [id (keyword (get-in request [:path-params :id]))]
      (if-let [definition (core/get-view id)]
        (try
          (let [context (assoc context :request request)
                input (parse-signals request)
                instruction (cond-> {:type :view :id id :input input}
                              (:bel/selector definition) (assoc :selector (:bel/selector definition))
                              (:bel/patch-mode definition) (assoc :patch-mode (:bel/patch-mode definition)))]
            (short-response request (prepare-result context instruction)))
          (catch Exception exception
            (error-response exception)))
        {:status 404 :body "View not found"}))))


(defn action-handler
  {:malli/schema [:=> [:cat :map] [:fn ifn?]]}
  [context]
  (fn [request]
    (let [id (keyword (get-in request [:path-params :id]))]
      (if (core/get-action id)
        (try
          (let [context (assoc context :request request)
                input (parse-signals request)]
            (short-response request (prepare-result context (execute-action context id input))))
          (catch Exception exception
            (error-response exception)))
        {:status 404 :body "Action not found"}))))
