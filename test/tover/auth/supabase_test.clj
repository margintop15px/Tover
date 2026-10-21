(ns tover.auth.supabase-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [org.httpkit.client :as http-client]
    [tover.auth.core :as auth]
    [tover.auth.supabase :as supabase]
    [tover.support :as support])
  (:import
    [java.io IOException OutputStream]
    [java.util.concurrent CountDownLatch TimeUnit]))


(def upstream-user
  {:id           (str support/user-id) :email         "person@example.test"        :email_confirmed_at "2026-01-01T00:00:00Z"
   :app_metadata {:provider "email"}   :user_metadata {:organization_role "owner"}})


(def upstream-tokens
  {:access_token "access-secret" :refresh_token "refresh-secret" :expires_at 2000000000
   :user         upstream-user})


(defn adapter
  {:malli/schema [:=> [:cat :string] :any]}
  [url]
  (supabase/auth {:auth-url (str url "/custom-auth/") :api-key "public-key" :admin-key "admin-secret"}))


(deftest token-responses-and-users-have-application-owned-shapes
  (support/with-server
    (fn [request] {:body (if (= :get (:method request)) upstream-user upstream-tokens)})
    (fn [{:keys [url requests]}]
      (let [provider  (adapter url)
            signed-in (auth/sign-in! provider {:email           "person@example.test" :password "password-secret"
                                               :organization_id "forged"})]
        (is (= {:access-token "access-secret" :refresh-token "refresh-secret" :expires-at 2000000000
                :user         {:user-id support/user-id :email "person@example.test" :email-confirmed? true}}
               signed-in))
        (is (= (:user signed-in) (auth/current-user provider (:access-token signed-in))))
        (is (= {:email "person@example.test" :password "password-secret"} (:body (first @requests))))
        (is (= "/custom-auth/token" (:path (first @requests))))
        (is (= "grant_type=password" (:query (first @requests))))
        (is (= "public-key" (get-in (first @requests) [:headers "apikey"])))
        (is (nil? (get-in (first @requests) [:headers "authorization"])))
        (is (= "Bearer access-secret" (get-in (second @requests) [:headers "authorization"])))))))


(deftest refresh-and-logout-use-local-session-http-contracts
  (support/with-server
    (fn [request] (if (str/ends-with? (:path request) "/logout") {:status 204} {:body upstream-tokens}))
    (fn [{:keys [url requests]}]
      (let [provider (adapter url)]
        (is (= "refresh-secret" (:refresh-token (auth/refresh! provider "old-refresh-secret"))))
        (is (nil? (auth/sign-out! provider "access-secret")))
        (is (= "grant_type=refresh_token" (:query (first @requests))))
        (is (= {:refresh_token "old-refresh-secret"} (:body (first @requests))))
        (is (= "scope=local" (:query (second @requests))))))))


(deftest signup-forwards-only-supported-organization-metadata
  (support/with-server
    (fn [_] {:body {:user (assoc upstream-user :email_confirmed_at nil)}})
    (fn [{:keys [url requests]}]
      (is (= {:user {:user-id support/user-id :email "person@example.test" :email-confirmed? false}}
             (auth/sign-up! (adapter url)
                            {:email       "person@example.test" :password "password-secret" :workspace-name "My workspace"
                             :redirect-to "https://app.example.test/auth/confirm"
                             :data        {:organization_id "forged" :organization_role "owner"}})))
      (is (= {:email "person@example.test"               :password "password-secret"
              :data  {:organization_name "My workspace"}}
             (:body (first @requests))))
      (is (= "redirect_to=https%3A%2F%2Fapp.example.test%2Fauth%2Fconfirm" (:query (first @requests)))))))


(deftest email-and-password-flows-use-explicit-http-requests
  (support/with-server
    (fn [request]
      {:body (cond
               (str/ends-with? (:path request) "/verify") upstream-tokens
               (str/ends-with? (:path request) "/user") upstream-user
               :else {})})
    (fn [{:keys [url requests]}]
      (let [provider (adapter url)
            email    {:email "person@example.test" :redirect-to "https://app.example.test/auth/confirm"}]
        (auth/verify-email! provider {:type :recovery :token-hash "hash-secret"})
        (auth/recover! provider email)
        (auth/set-password! provider "access-secret" "new-password-secret")
        (auth/invite! provider email)
        (auth/magic-link! provider email)
        (is (= {:token_hash "hash-secret" :type "recovery"} (:body (nth @requests 0))))
        (is (= "redirect_to=https%3A%2F%2Fapp.example.test%2Fauth%2Fconfirm" (:query (nth @requests 1))))
        (is (= :put (:method (nth @requests 2))))
        (is (= {:password "new-password-secret"} (:body (nth @requests 2))))
        (is (= "Bearer access-secret" (get-in (nth @requests 2) [:headers "authorization"])))
        (is (= "Bearer admin-secret" (get-in (nth @requests 3) [:headers "authorization"])))
        (is (= "admin-secret" (get-in (nth @requests 3) [:headers "apikey"])))
        (is (= {:email "person@example.test" :create_user false} (:body (nth @requests 4))))
        (is (nil? (get-in (nth @requests 4) [:headers "authorization"])))))))


(deftest provider-errors-never-include-provider-bodies-or-credentials
  (doseq [[status code expected uncertain?]
          [[400 "invalid_credentials" :invalid-credentials false]
           [422 "email_exists" :email-exists false]
           [403 "otp_expired" :verification-expired false]
           [503 "unexpected_internal_value" :unavailable true]]]
    (testing (str status " " code)
      (support/with-server
        (fn [_] {:status status :body {:error_code code :msg "password-secret refresh-secret admin-secret"}})
        (fn [{:keys [url]}]
          (let [error (support/error-data #(auth/sign-in! (adapter url) {:email "person@example.test" :password "password-secret"}))]
            (is (= {:type    :identity                  :code expected :status status :uncertain? uncertain?
                    :message "Identity request failed."}
                   error))
            (is (not (re-find #"password-secret|refresh-secret|admin-secret|unexpected_internal_value" (pr-str error))))))))))


(deftest invalid-json-and-input-errors-are-sanitized
  (support/with-server
    (fn [_] {:body "upstream-secret: not JSON"})
    (fn [{:keys [url requests]}]
      (is (= :invalid-response (:code (support/error-data #(auth/sign-in! (adapter url) {:email "a" :password "password-secret"})))))
      (let [error (support/error-data #(auth/sign-in! (adapter url) {:email "a" :password nil :private "password-secret"}))]
        (is (= :invalid-input (:code error)))
        (is (not (str/includes? (pr-str error) "password-secret"))))
      (is (= 1 (count @requests))))))


(deftest redirects-do-not-forward-credentials
  (support/with-server
    (fn [_] {:status 307 :headers {"Location" "http://127.0.0.1:1/credentials"} :body {}})
    (fn [{:keys [url requests]}]
      (is (= :invalid-input (:code (support/error-data #(auth/sign-in! (adapter url) {:email "a" :password "secret"})))))
      (is (= 1 (count @requests))))))


(deftest upstream-deadline-is-bounded-and-write-is-not-retried
  (let [release (CountDownLatch. 1)]
    (support/with-server
      (fn [_]
        (.await release 2 TimeUnit/SECONDS)
        {:body upstream-tokens})
      (fn [{:keys [url requests]}]
        (let [provider (supabase/auth {:auth-url url :api-key "public" :admin-key "admin" :timeout-ms 100})
              started  (System/nanoTime)]
          (try
            (is (= {:type    :identity                  :code :unavailable :status 503 :uncertain? true
                    :message "Identity request failed."}
                   (support/error-data #(auth/sign-in! provider {:email "a" :password "secret"}))))
            (is (< (/ (- (System/nanoTime) started) 1000000) 1500))
            (is (<= (count @requests) 1))
            (finally (.countDown release))))))))


(deftest response-body-deadline-preserves-read-and-write-certainty
  (doseq [[request! uncertain?]
          [[#(auth/current-user % "access-secret") false]
           [#(auth/sign-in! % {:email "a" :password "secret"}) true]]]
    (let [release      (CountDownLatch. 1)
          disconnected (promise)]
      (support/with-server
        (fn [_]
          {:body       upstream-tokens
           :write-body (fn [^OutputStream stream ^bytes bytes]
                         (try
                           (doseq [chunk (partition-all 4 bytes)]
                             (.write stream (byte-array chunk))
                             (.flush stream)
                             (.await release 25 TimeUnit/MILLISECONDS))
                           (catch IOException _ (deliver disconnected true))))})
        (fn [{:keys [url requests]}]
          (let [provider (supabase/auth {:auth-url url :api-key "public" :admin-key "admin" :timeout-ms 100})
                started  (System/nanoTime)]
            (try
              (is (= {:type    :identity                  :code :unavailable :status 503 :uncertain? uncertain?
                      :message "Identity request failed."}
                     (support/error-data #(request! provider))))
              (is (< (/ (- (System/nanoTime) started) 1000000) 1500))
              (is (= 1 (count @requests)))
              (is (true? (deref disconnected 750 false)))
              (finally (.countDown release)))))))))


(deftest request-creation-is-included-in-upstream-deadline
  (let [release  (CountDownLatch. 1)
        provider (supabase/auth {:auth-url "http://127.0.0.1:1" :api-key "public" :admin-key "admin" :timeout-ms 100})
        started  (System/nanoTime)]
    (with-redefs [http-client/request (fn [_]
                                        (.await release 2 TimeUnit/SECONDS)
                                        (promise))]
      (try
        (is (= {:type    :identity                  :code :unavailable :status 503 :uncertain? true
                :message "Identity request failed."}
               (support/error-data #(auth/sign-in! provider {:email "a" :password "secret"}))))
        (is (< (/ (- (System/nanoTime) started) 1000000) 1500))
        (finally (.countDown release))))))


(deftest retry-after-accepts-only-bounded-seconds
  (doseq [[header seconds] [["42" 42] ["0" 0] ["-1" nil] ["1000000" nil]
                            ["Wed, 21 Oct 2015 07:28:00 GMT" nil]]]
    (testing header
      (support/with-server
        (fn [_] {:status 429 :headers {"Retry-After" header} :body {}})
        (fn [{:keys [url]}]
          (is (= (cond-> {:type    :identity                  :code :rate-limited :status 429 :uncertain? false
                          :message "Identity request failed."}
                   seconds (assoc :retry-after seconds))
                 (support/error-data #(auth/sign-in! (adapter url) {:email "a" :password "secret"})))))))))
