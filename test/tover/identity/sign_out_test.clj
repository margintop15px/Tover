(ns tover.identity.sign-out-test
  "Board chapter 02b · Sign out (slice prose and its listed scenarios)."
  (:require
    [bel.core :as bel]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [tover.fixtures :as fixtures]
    [tover.identity.access :as access]
    [tover.identity.provider :as provider]
    [tover.identity.session :as session]
    [tover.identity.sign-out.model :as model]
    [tover.identity.sign-out.web :as web]
    [tover.support :as support]))


(def sign-out-url
  (bel/action-url ::web/sign-out))


(defn signed-out-events
  {:malli/schema [:=> [:cat :any] [:vector :map]]}
  [state]
  (into [] (comp (mapcat :events) (filter #(= ::model/user-signed-out (:type %))) (map :data)) (:journal @state)))


(defn sign-out!
  {:malli/schema [:=> [:cat :map :map [:? :map]] :map]}
  ([browser session] (sign-out! browser session {}))
  ([{:keys [request!]} session params]
   (request! :post sign-out-url (merge {"session-id" (str (:session-id session))} params))))


(deftest sign-out-ends-this-sign-in-and-leaves-other-devices-signed-in
  (support/with-application
    (fn [{:keys [browser handler state session-store] :as services}]
      (let [laptop   (support/sign-in! services browser "owner@example.test" {:workspace-id fixtures/workspace-id})
            phone    (support/browser handler)
            other    (support/sign-in! services phone "owner@example.test" {:workspace-id fixtures/workspace-id})
            response (sign-out! browser laptop)
            [event]  (signed-out-events state)]
        (is (= 303 (:status response)))
        (is (= "/auth/login" (get-in response [:headers "location"])))
        (is (nil? (support/session-key browser)))
        (is (= {:session-id (:session-id laptop) :user-id fixtures/owner-id} (select-keys event [:session-id :user-id])))
        (testing "the ended row is kept but grants nothing, also for an old copy of the cookie"
          (is (some? (get-in @state [:db :sessions (:key laptop) :ended_at])))
          (is (nil? (session/session session-store (:key laptop))))
          (is (nil? (access/current-identity services (:key laptop)))))
        (testing "the other device is unaffected"
          (is (= (:session-id other) (:session-id (access/current-identity services (:key other)))))
          (is (= 200 (:status ((:request! phone) :get "/workspace")))))
        (testing "protected screens now require sign-in again"
          (is (= "/auth/login" (get-in ((:request! browser) :get "/workspace") [:headers "location"]))))))))


(deftest repeating-sign-out-is-a-safe-no-op
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (let [session (support/sign-in! services browser "owner@example.test")]
        (sign-out! browser session)
        ((:request! browser) :get "/auth/login")
        (let [again (sign-out! browser session)]
          (is (= 303 (:status again)))
          (is (= "/auth/login" (get-in again [:headers "location"])))
          (is (= 1 (count (signed-out-events state)))))))))


(deftest an-unavailable-identity-service-cannot-block-sign-out
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (let [session  (support/sign-in! services browser "owner@example.test")
            started  (System/nanoTime)
            ;; The provider's sign-out hangs, then fails.
            response (with-redefs [provider/sign-out! (fn [& _] (Thread/sleep 5000) (throw (ex-info "unavailable" {})))]
                       (sign-out! browser session))]
        (is (= 303 (:status response)))
        (is (< (/ (- (System/nanoTime) started) 1e6) 2000))
        (is (= 1 (count (signed-out-events state))))))))


(deftest a-late-sign-out-of-an-old-sign-in-cannot-end-a-newer-one
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (let [old    (support/sign-in! services browser "owner@example.test")
            _      (sign-out! browser old)
            newer  (support/sign-in! services browser "member@example.test")
            ;; A delayed request from the old page carries the old sign-in and its context.
            late   (sign-out! browser old {"_tover-context" (str (:version old))})
            stolen (sign-out! browser old)]
        (is (= 409 (:status late)))
        (is (= 303 (:status stolen)))
        (is (= (:session-id newer) (:session-id (access/current-identity services (:key newer)))))
        (is (= [(:session-id old)] (map :session-id (signed-out-events state))))))))


(deftest sign-out-continues-to-the-chosen-public-screen-without-using-its-link
  (support/with-application
    (fn [{:keys [browser] :as services}]
      (let [session  (support/sign-in! services browser "owner@example.test")
            landing  "/auth/confirm?token_hash=abc&type=signup"
            response (sign-out! browser session {"return-to" landing})]
        (is (= landing (get-in response [:headers "location"])))
        (doseq [path ["/workspace" "/stock" "//host" "/invitations/x" (str "/invitations/" (random-uuid) "/joined")
                      (str "/invitations/" (random-uuid) "?problem=x")]]
          (is (= "/auth/login" (web/public-destination path)) path))
        (let [invitation (str "/invitations/" (random-uuid))]
          (is (= invitation (web/public-destination invitation))))
        (is (= "/auth/login" (web/public-destination "https://evil.example/auth/confirm")))
        (is (str/includes? (:body ((:request! browser) :get "/auth/confirm" {"token_hash" "abc" "type" "signup"}))
                           "Confirm this email address"))))))


(deftest the-sign-out-screen-only-offers-the-form
  (support/with-application
    (fn [{:keys [browser handler] :as services}]
      (let [session (support/sign-in! services browser "owner@example.test")
            page    ((:request! browser) :get "/auth/sign-out")]
        (is (= 200 (:status page)))
        (is (str/includes? (:body page) "You are signed in as owner@example.test."))
        (is (str/includes? (:body page) "tover.identity.sign-out.web/sign-out"))
        (is (some? (access/current-identity services (:key session))))
        (is (str/includes? (:body ((:request! (support/browser handler)) :get "/auth/sign-out")) "You are signed out."))))))


(deftest sign-out-that-cannot-be-confirmed-keeps-the-sign-in-and-offers-retry
  (support/with-application
    (fn [{:keys [browser] :as services}]
      (let [session  (support/sign-in! services browser "owner@example.test")
            response (with-redefs [runtime/execute! (fn [& _] (throw (ex-info "database unavailable" {:type :unavailable})))]
                       (sign-out! browser session))]
        (is (= 503 (:status response)))
        (is (str/includes? (:body response) "We could not confirm that you signed out. Try again."))
        (is (= (:key session) (support/session-key browser)))
        (is (some? (access/current-identity services (:key session))))))))
