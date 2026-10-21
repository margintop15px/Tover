(ns tover.shared.supabase
  (:require
    [charred.api :as json]
    [clojure.string :as str]
    [org.httpkit.client :as http]
    [tover.auth.core :as auth])
  (:import
    [java.net URI URLEncoder]
    [java.nio.charset StandardCharsets]
    [java.util.concurrent ExecutionException]
    [org.httpkit.client HttpClient IFilter]))


(defn client
  {:malli/schema [:=> [:cat] [:fn #(instance? HttpClient %)]]}
  []
  @http/default-client)


(defn base-url
  {:malli/schema [:=> [:cat :any] :string]}
  [value]
  (auth/check-input! :string value)
  (try
    (let [uri (URI/create value)]
      (when-not (and (contains? #{"http" "https"} (.getScheme uri))
                     (.getHost uri)
                     (nil? (.getUserInfo uri))
                     (nil? (.getRawQuery uri))
                     (nil? (.getRawFragment uri)))
        (auth/fail! :invalid-configuration 500 false))
      (str/replace value #"/+$" ""))
    (catch IllegalArgumentException _
      (auth/fail! :invalid-configuration 500 false))))


(defn query
  {:malli/schema [:=> [:cat :map] :string]}
  [params]
  (if (seq params)
    (str "?" (str/join "&" (for [[key value] params]
                             (str (URLEncoder/encode (name key) StandardCharsets/UTF_8)
                                  "=" (URLEncoder/encode (str value) StandardCharsets/UTF_8)))))
    ""))


(def ^:private error-codes
  {"invalid_credentials"        :invalid-credentials
   "email_not_confirmed"        :email-not-confirmed
   "email_exists"               :email-exists
   "user_already_exists"        :email-exists
   "otp_expired"                :verification-expired
   "otp_disabled"               :verification-invalid
   "bad_jwt"                    :unauthenticated
   "no_authorization"           :unauthenticated
   "session_not_found"          :unauthenticated
   "user_not_found"             :unauthenticated
   "refresh_token_not_found"    :invalid-refresh-token
   "refresh_token_already_used" :invalid-refresh-token
   "weak_password"              :weak-password
   "same_password"              :same-password
   "over_email_send_rate_limit" :rate-limited
   "over_request_rate_limit"    :rate-limited
   "email_address_invalid"      :invalid-input
   "validation_failed"          :invalid-input
   "42501" :forbidden
   "PGRST301"                   :unauthenticated
   "PGRST302"                   :unauthenticated
   "PGRST303"                   :unauthenticated})


(defn- read-body
  {:malli/schema [:=> [:cat :string :boolean] :any]}
  [body uncertain?]
  (when-not (str/blank? body)
    (try (json/read-json body :key-fn keyword)
         (catch Exception _
           (auth/fail! :invalid-response 502 uncertain?)))))


(defn request!
  "Bound the whole exchange, including body reads. Never follow redirects or retry writes."
  {:malli/schema [:=> [:cat :map :map] :any]}
  [{:keys [client api-key timeout-ms] :or {timeout-ms 15000}}
   {:keys [method url token body] :or {method :post}}]
  (let [timeout-ms (min 15000 (max 1 timeout-ms))
        deadline   (+ (System/nanoTime) (* timeout-ms 1000000))
        write?     (not= :get method)
        pending    (future
                     @(http/request
                        {:client           client
                         :url              url
                         :method           method
                         :headers          (cond-> {"Accept" "application/json" "Content-Type" "application/json" "apikey" api-key}
                                             token (assoc "Authorization" (str "Bearer " token)))
                         :body             (when (some? body) (json/write-json-str body))
                         :as               :text
                         :timeout          timeout-ms
                         :follow-redirects false
                         :keepalive        -1
                      ;; Abort active responses after the caller's deadline too.
                         :filter           (reify IFilter
                                             (^boolean accept [_ ^java.util.Map _headers] (< (System/nanoTime) deadline))
                                             (^boolean accept [_ ^org.httpkit.DynamicBytes _body] (< (System/nanoTime) deadline)))}))
        {:keys [status headers body error]}
        (try
          ;; Include synchronous DNS and body reads in the caller's deadline.
          (deref pending timeout-ms {:error :timeout})
          (catch ExecutionException _ {:error :request-failed})
          (catch InterruptedException _
            (.interrupt (Thread/currentThread))
            {:error :interrupted}))
        body       (or body "")]
    (when error
      (future-cancel pending)
      (auth/fail! :unavailable 503 write?))
    (if (<= 200 status 299)
      (read-body body write?)
      (let [data        (try (read-body body false) (catch Exception _ nil))
            code        (or (get error-codes (:error_code data)) (get error-codes (:code data))
                            (cond (>= status 500) :unavailable
                                  (= status 429) :rate-limited
                                  (= status 401) :unauthenticated
                                  (= status 403) :forbidden
                                  :else :invalid-input))
            retry-after (:retry-after headers)
            retry-after (when (and retry-after (re-matches #"\d{1,6}" retry-after))
                          (Long/parseLong retry-after))]
        (auth/fail! code (if (>= status 500) 503 status)
                    (and write? (>= status 500))
                    (cond-> {} retry-after (assoc :retry-after retry-after)))))))
