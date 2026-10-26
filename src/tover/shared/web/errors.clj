(ns tover.shared.web.errors
  (:require
    [bel.handler :as html]
    [reitit.ring.middleware.exception :as exception]
    [tover.auth.security :as security]
    [tover.shared.i18n :as i18n])
  (:import
    [clojure.lang ExceptionInfo]))


(defn- form-error-response
  {:malli/schema [:=> [:cat :qualified-keyword [:sequential :string] :any :map] :map]}
  [page-id retained error request]
  (if-let [status (or (:status (ex-data error)) (when (:bel/input-error? (ex-data error)) 422))]
    (let [code      (or (:code (ex-data error)) (when (:bel/input-error? (ex-data error)) :invalid-input) :identity-error)
          security? (or (contains? #{401 403} status)
                        (contains? #{:workspace-changed :workspace-required :password-required} code))]
      (if (and security? (security/datastar-request? request))
        (throw error)
        (let [response (html/error-page-response
                         (assoc request :tover/error (i18n/error-message error))
                         page-id (select-keys (:form-params request) retained))]
          (if (= 200 (:status response))
            (-> response
                (assoc :status (if (security/datastar-request? request) 200 status))
                (assoc-in [:headers "x-tover-error"] (name code)))
            response))))
    (throw error)))


(def form-exception-middleware
  {:name    ::form-exceptions
   :compile (fn [{:bel/keys [error-page retain]} _options]
              (when error-page
                (exception/create-exception-middleware
                  {ExceptionInfo       (partial form-error-response error-page (or retain []))
                   ::exception/default (fn [error _request] (throw error))})))})
