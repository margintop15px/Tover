(ns tover.identity.provider-test
  (:require
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [tover.fixtures :as fixtures]
    [tover.identity.provider :as provider]
    [tover.identity.session :as session]
    [tover.support :as support]))


(def user-body
  {:id "00000000-0000-4000-8000-000000000009" :email "new@example.test" :email_confirmed_at "2026-01-01T00:00:00Z"})


(defn recording
  "A fake HTTP layer: records each call and answers with `respond`."
  {:malli/schema [:=> [:cat [:fn fn?]] :map]}
  [respond]
  (let [calls (atom [])]
    {:calls    calls
     :provider {:request! (fn [options] (swap! calls conj options) (respond options))}}))


(defn context
  {:malli/schema [:=> [:cat :map] :map]}
  [resources]
  {:bel/system {:resources resources}})


(deftest sign-in-is-a-password-grant-and-returns-session-tokens
  (let [{:keys [calls provider]} (recording (constantly {:access_token "a" :refresh_token "r" :expires_in 60 :user user-body}))
        tokens                   (provider/call! (context {:provider provider}) ::provider/sign-in
                                                 {:email "new@example.test" :password "secret"})]
    (is (= {:path "/token" :query {:grant_type "password"} :body {:email "new@example.test" :password "secret"}}
           (first @calls)))
    (is (= {:access-token "a" :refresh-token "r" :user {:user-id #uuid "00000000-0000-4000-8000-000000000009"
                                                        :email   "new@example.test" :email-confirmed? true}}
           (dissoc tokens :expires-at)))))


(deftest credential-effects-never-run-from-command-fx
  (let [{:keys [calls provider]} (recording (constantly {}))]
    (doseq [id [::provider/sign-up ::provider/sign-in ::provider/verify ::provider/set-password]]
      (is (= :permanent (:type (support/error-data #(runtime/effect! id {:resources {:provider provider}} {:password "secret"}))))))
    (is (empty? @calls))))


(deftest recovery-and-resend-pass-the-return-link
  (let [{:keys [calls provider]} (recording (constantly {}))
        resources                {:provider provider}]
    (is (= {} (runtime/effect! ::provider/recover {:resources resources} {:email "a@example.test" :redirect-to "http://localhost:3030/auth/confirm"})))
    (is (= {} (runtime/effect! ::provider/resend {:resources resources} {:email "a@example.test"})))
    (is (= [{:path "/recover" :query {:redirect_to "http://localhost:3030/auth/confirm"} :body {:email "a@example.test"}}
            {:path "/resend" :query nil :body {:type "signup" :email "a@example.test"}}]
           @calls))))


(deftest fx-failures-use-the-runtime-problem-types
  (doseq [[status uncertain? expected] [[400 false :permanent] [429 false :temporary] [503 false :temporary] [503 true :uncertain]]]
    (let [{:keys [provider]} (recording (fn [_] (provider/fail! :rate-limited status uncertain?)))
          error              (support/error-data #(runtime/effect! ::provider/recover {:resources {:provider provider}} {:email "a@example.test"}))]
      (is (= expected (:type error)))
      (is (= :rate-limited (:code error))))))


(deftest sign-out-ends-the-provider-session-of-a-tover-session
  (let [services (fixtures/system)
        session  (fixtures/sign-in! services "owner@example.test")
        token    (:access-token (:tokens (session/session (:session-store services) (:key session))))]
    (is (contains? (:tokens @(:state services)) token))
    (is (= {} (runtime/effect! ::provider/sign-out {:resources (select-keys services [:provider :session-store])}
                               {:session-id (:session-id session) :scope "local"})))
    (is (not (contains? (:tokens @(:state services)) token)))))


(deftest http-errors-carry-codes-not-provider-bodies
  (support/with-server
    (fn [{:keys [path]}]
      (case path
        "/token" {:status 400 :body {:error_code "invalid_credentials" :msg "secret detail"}}
        "/recover" {:status 429 :headers {"Retry-After" "30"} :body {}}
        {:status 503 :body "down"}))
    (fn [{:keys [url requests]}]
      (let [provider (provider/client {:auth-url url :api-key "anon" :admin-key "admin"})
            error    (support/error-data #(provider/sign-in! provider {:email "a@example.test" :password "pw"}))]
        (is (= {:type :identity :code :invalid-credentials :status 400 :uncertain? false} (dissoc error :message)))
        (is (not (str/includes? (pr-str error) "secret detail")))
        (is (= {:code :rate-limited :retry-after 30}
               (select-keys (support/error-data #(provider/recover! provider {:email "a@example.test"})) [:code :retry-after])))
        (is (= {:code :unavailable :uncertain? true}
               (select-keys (support/error-data #(provider/resend! provider {:email "a@example.test"})) [:code :uncertain?])))
        (is (= ["grant_type=password" "anon"] ((juxt :query #(get-in % [:headers "apikey"])) (first @requests))))))))
