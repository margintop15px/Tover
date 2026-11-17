(ns tover.shared.web.errors
  (:require
    [bel.handler :as html]
    [reitit.ring.middleware.exception :as exception]
    [tover.identity.access :as access]
    [tover.shared.i18n :as i18n])
  (:import
    [clojure.lang ExceptionInfo]))


(defn- form-error-response
  "Render the route's error page for a known failure: an error with `:status`, invalid input, or a business
  rejection whose `:type` is in the route's `:tover/problems` (type -> status, or [status message])."
  {:malli/schema [:=> [:cat :qualified-keyword [:sequential :string] :map :any :map] :map]}
  [page-id retained problems error request]
  (if-let [status (or (:status (ex-data error))
                      (when (:bel/input-error? (ex-data error)) 422)
                      (let [problem (problems (:type (ex-data error)))] (if (vector? problem) (first problem) problem)))]
    (let [problem   (problems (:type (ex-data error)))
          error     (if (vector? problem)
                      (ex-info (second problem) (assoc (ex-data error) :message-key (second problem)) error)
                      error)
          code      (or (:code (ex-data error)) (when (:bel/input-error? (ex-data error)) :invalid-input)
                        (when problem (:type (ex-data error))) :identity-error)
          security? (or (contains? #{401 403} status)
                        (contains? #{:workspace-changed :workspace-required :password-required} code))]
      (if (and security? (access/datastar-request? request))
        (throw error)
        (let [response (html/error-page-response
                         (assoc request :tover/error (i18n/error-message error))
                         page-id (select-keys (:form-params request) retained))]
          (if (= 200 (:status response))
            (-> response
                (assoc :status (if (access/datastar-request? request) 200 status))
                (assoc-in [:headers "x-tover-error"] (name code)))
            response))))
    (throw error)))


(def form-exception-middleware
  {:name    ::form-exceptions
   :compile (fn [{:bel/keys [error-page retain] :tover/keys [problems]} _options]
              (when error-page
                (exception/create-exception-middleware
                  {ExceptionInfo       (partial form-error-response error-page (or retain []) (or problems {}))
                   ::exception/default (fn [error _request] (throw error))})))})
