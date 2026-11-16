(ns tover.identity.recovery-test
  "Board chapters 02a · Recover account access and 02c · Complete an interrupted password reset (slice prose
  and listed scenarios; the chapters have no ## Examples)."
  (:require
    [bel.core :as bel]
    [bel.core-test :as fixture]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [tover.fixtures :as fixtures]
    [tover.identity.access :as access]
    [tover.identity.provider :as provider]
    [tover.identity.recovery.web :as web]
    [tover.identity.reset-completion.model :as completion]
    [tover.identity.reset-completion.web :as completion-web]
    [tover.identity.sign-in-test :refer [alert signed-in-events]]
    [tover.support :as support])
  (:import
    [java.time Instant]))


(defn request-recovery!
  {:malli/schema [:=> [:cat :map :string] :map]}
  [{:keys [request!]} email]
  (request! :get "/auth/recover")
  (request! :post (bel/action-url ::web/request-recovery) {"email" email}))


(defn recovery-token
  {:malli/schema [:=> [:cat :any :string] [:maybe :string]]}
  [state email]
  (:token-hash (last (filter #(and (= email (:email %)) (= :recovery (:type %))) (:emails @state)))))


(defn submit-reset!
  "Open the recovery link page, then explicitly submit the new password."
  {:malli/schema [:=> [:cat :map :string :string] :map]}
  [{:keys [request!]} token password]
  (request! :get "/auth/confirm" {"token_hash" token "type" "recovery"})
  (request! :post (bel/action-url ::web/reset-password) {"token-hash" token "password" password}))


(defn completed-events
  {:malli/schema [:=> [:cat :any] [:vector :map]]}
  [state]
  (into [] (comp (mapcat :events) (filter #(= ::completion/password-reset-completed (:type %))) (map :data)) (:journal @state)))


(defn rejecting
  "The fake provider, except that the password \"weak\" is rejected and \"lost\" has an unknown outcome."
  {:malli/schema [:=> [:cat :map] :map]}
  [services]
  (update services :provider
          (fn [{:keys [request!]}]
            {:request! (fn [{:keys [method body] :as options}]
                         (case [method (:password body)]
                           [:put "weak"] (provider/fail! :weak-password 422 false)
                           [:put "lost"] (provider/fail! :unavailable 503 true)
                           (request! options)))})))


(deftest a-recovery-request-is-neutral-and-changes-nothing
  (support/with-application
    (fn [{:keys [browser handler state] :as services}]
      (let [other   (support/sign-in! services (support/browser handler) "owner@example.test")
            known   (request-recovery! browser "owner@example.test")
            unknown (request-recovery! browser "nobody@example.test")]
        (doseq [response [known unknown]]
          (is (= 200 (:status response)))
          (is (str/includes? (:body response) "If recovery is available and the message can be sent"))
          (is (nil? (alert (:body response)))))
        (is (some? (recovery-token state "owner@example.test")))
        (is (nil? (recovery-token state "nobody@example.test")))
        (testing "requesting recovery never ends a sign-in or changes the password"
          (is (some? (access/current-identity services (:key other))))
          (is (= fixtures/password (get-in @state [:db :auth.users fixtures/owner-id :password]))))))))


(deftest opening-the-recovery-link-changes-nothing
  (support/with-application
    (fn [{:keys [browser state]}]
      (request-recovery! browser "owner@example.test")
      (let [token    (recovery-token state "owner@example.test")
            response ((:request! browser) :get "/auth/confirm" {"token_hash" token "type" "recovery"})]
        (is (str/includes? (:body response) (bel/action-url ::web/reset-password)))
        (is (str/includes? (:body response) "Resetting your password ends your other sign-ins."))
        (is (some? (recovery-token state "owner@example.test")))
        (is (not-any? :tokens (vals (get-in @state [:db :app_private.sessions]))))))))


(deftest a-reset-in-a-fresh-browser-ends-other-sign-ins-and-continues
  (support/with-application
    (fn [{:keys [browser handler state] :as services}]
      (let [other (support/sign-in! services (support/browser handler) "owner@example.test" {:workspace-id fixtures/workspace-id})]
        (request-recovery! browser "owner@example.test")
        (let [phone    (support/browser handler)
              response (submit-reset! phone (recovery-token state "owner@example.test") "a-new-password")
              key      (support/session-key phone)
              [event]  (completed-events state)]
          (is (= 303 (:status response)))
          (is (= "/workspace" (get-in response [:headers "location"])))
          (is (= "a-new-password" (get-in @state [:db :auth.users fixtures/owner-id :password])))
          (is (= {:user-id fixtures/owner-id :status "completed"} (select-keys event [:user-id :status])))
          (testing "the earlier sign-in is ended; the resetting one is kept as an ordinary sign-in"
            (is (nil? (access/current-identity services (:key other))))
            (is (= (:kept-session-id event) (:session-id (access/current-identity services key))))
            (is (false? (:password-required? (access/current-identity services key))))
            (is (= 200 (:status ((:request! phone) :get "/workspace")))))
          (testing "the reset preserves the profile and records the new ordinary sign-in once"
            (is (= "Olga Owner" (get-in @state [:db :public.profiles fixtures/owner-id :display_name])))
            (is (= [(:kept-session-id event)] (map :session-id (signed-in-events state))))))))))


(deftest a-completion-retry-never-ends-a-newer-sign-in
  (support/with-application
    (fn [{:keys [browser handler state] :as services}]
      (request-recovery! browser "owner@example.test")
      (submit-reset! browser (recovery-token state "owner@example.test") "a-new-password")
      (let [{:keys [attempt-id valid-after]} (first (completed-events state))
            newer (support/sign-in! services (support/browser handler) "owner@example.test")
            context (fixtures/context services {:key (support/session-key browser)})]
        (completion-web/finish! context (support/session-key browser) attempt-id valid-after)
        (is (= 1 (count (completed-events state))))
        (is (some? (access/current-identity services (:key newer))))))))


(deftest a-rejected-password-changes-nothing-and-permits-a-corrected-submission
  (support/with-application
    rejecting
    (fn [{:keys [browser handler state] :as services}]
      (let [other (support/sign-in! services (support/browser handler) "owner@example.test")]
        (request-recovery! browser "owner@example.test")
        (let [response (submit-reset! browser (recovery-token state "owner@example.test") "weak")]
          (is (= 422 (:status response)))
          (is (= "Choose a stronger password." (alert (:body response))))
          (is (= fixtures/password (get-in @state [:db :auth.users fixtures/owner-id :password])))
          (is (some? (access/current-identity services (:key other))))
          (is (empty? (completed-events state))))
        (testing "the restricted recovery sign-in grants no workspace access"
          (is (= "/auth/password" (get-in ((:request! browser) :get "/workspace") [:headers "location"]))))
        (let [response ((:request! browser) :post (bel/action-url ::web/set-password) {"password" "a-better-password"})]
          (is (= 303 (:status response)))
          (is (= "a-better-password" (get-in @state [:db :auth.users fixtures/owner-id :password])))
          (is (= 1 (count (completed-events state))))
          (is (nil? (access/current-identity services (:key other)))))))))


(deftest an-unknown-password-change-stays-unconfirmed-blocks-older-sign-ins-and-is-never-resubmitted
  (let [puts (atom 0)]
    (support/with-application
      (fn [services]
        (update (rejecting services) :provider
                (fn [{:keys [request!]}] {:request! #(do (when (= :put (:method %)) (swap! puts inc)) (request! %))})))
      (fn [{:keys [browser handler state] :as services}]
        (let [other (support/sign-in! services (support/browser handler) "owner@example.test")]
          (request-recovery! browser "owner@example.test")
          (let [response (submit-reset! browser (recovery-token state "owner@example.test") "lost")]
            (is (= 503 (:status response)))
            (is (str/includes? (:body response) "Password change unconfirmed"))
            (is (str/includes? (:body response) "/auth/recover"))
            (is (= 1 @puts))
            (is (empty? (completed-events state)))
            (testing "older sign-ins stay blocked while the outcome is unknown; signing in again works"
              (is (nil? (access/current-identity services (:key other))))
              (is (some? (access/current-identity
                           services (:key (support/sign-in! services (support/browser handler) "owner@example.test")))))))
          (testing "the status survives a later visit"
            (is (str/includes? (:body ((:request! browser) :get "/auth/password")) "Password change unconfirmed"))))))))


(deftest an-interrupted-completion-is-resumed-without-another-password-entry
  (let [failures (atom 1)
        finish   completion-web/finish!]
    (with-redefs [completion-web/retry-delays-ms [0 0]
                  completion-web/finish!         (fn [& args]
                                                   ;; The first attempt fails; the background retry succeeds.
                                                   (if (neg? (swap! failures dec))
                                                     (apply finish args)
                                                     (throw (ex-info "database unavailable" {}))))]
      (support/with-application
        (fn [{:keys [browser handler state] :as services}]
          (let [other (support/sign-in! services (support/browser handler) "owner@example.test")]
            (request-recovery! browser "owner@example.test")
            (let [response (submit-reset! browser (recovery-token state "owner@example.test") "a-new-password")]
              (is (= 202 (:status response)))
              (is (str/includes? (:body response) "Ending other sign-ins"))
              (loop [n 0] (when (and (< n 100) (empty? (completed-events state))) (Thread/sleep 20) (recur (inc n))))
              (is (= 1 (count (completed-events state))))
              (is (nil? (access/current-identity services (:key other))))
              (is (str/includes? (:body ((:request! browser) :get "/auth/password")) "Password reset completed")))))))))


(deftest a-signed-in-person-must-choose-before-using-a-recovery-link
  (support/with-application
    (fn [{:keys [browser handler state] :as services}]
      (request-recovery! (support/browser handler) "member@example.test")
      (support/sign-in! services browser "owner@example.test")
      (let [token    (recovery-token state "member@example.test")
            response ((:request! browser) :post (bel/action-url ::web/reset-password) {"token-hash" token "password" "x-password"})]
        (is (= 409 (:status response)))
        (is (str/includes? (:body response) "tover.identity.sign-out.web/sign-out"))
        (is (some? (recovery-token state "member@example.test")))
        (is (= fixtures/password (get-in @state [:db :auth.users fixtures/member-id :password])))))))


(deftest an-expired-or-used-recovery-link-offers-a-new-request
  (support/with-application
    (fn [{:keys [browser]}]
      (let [response (submit-reset! browser "used-token" "a-new-password")]
        (is (= 400 (:status response)))
        (is (= "This link has expired or has already been used." (alert (:body response))))
        (is (str/includes? (:body response) (bel/action-url ::web/request-recovery)))))))


;; Completion decisions without a store


(def alice #uuid "00000000-0000-0000-0000-0000000000a1")
(def attempt #uuid "00000000-0000-0000-0000-0000000000e1")
(def resetting #uuid "00000000-0000-0000-0000-0000000000c1")
(def accepted (Instant/parse "2026-11-16T10:00:00Z"))


(defn- decide
  {:malli/schema [:=> [:cat :map] :map]}
  [facts]
  (fixture/isolated-registry
    (fn []
      (bel/build-registry! ['tover.identity.recovery.model 'tover.identity.reset-completion.model])
      (runtime/decide ::completion/complete-password-reset
                      (merge {:actor   alice :scope {:user-id alice}
                              :attempt {:attempt-id attempt :user-id alice :session-id resetting :status "requested"}}
                             facts)
                      {:attempt-id attempt :valid-after accepted}))))


(deftest completion-fixes-the-boundary-at-password-acceptance-and-keeps-the-resetting-sign-in
  (is (= [{:attempt-id attempt :user-id alice :status "completed" :valid-after accepted :kept-session-id resetting}]
         (map :data (:events (decide {}))))))


(deftest completion-is-emitted-once
  (is (empty? (:events (decide {:attempt {:attempt-id attempt :user-id alice :session-id resetting :status "completed"}})))))


(deftest a-newer-boundary-is-never-moved-back
  (let [later (.plusSeconds accepted 60)
        other #uuid "00000000-0000-0000-0000-0000000000c2"]
    (is (= {:valid-after later :kept-session-id other}
           (select-keys (:data (first (:events (decide {:boundary {:user-id alice :valid-after later :kept-session-id other}}))))
                        [:valid-after :kept-session-id])))))
