(ns tover.accounts.profile-web-test
  "Profile setup and the signed-in language switch through the application handler."
  (:require
    [bel.core :as bel]
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [tover.auth.core :as auth]
    [tover.auth.security :as security]
    [tover.auth.sessions :as sessions]
    [tover.support :as support]))


(defn- unnamed
  {:malli/schema [:=> [:cat [:sequential :map]] :map]}
  [users]
  (some #(when (= "unnamed@example.test" (:email %)) %) users))


(defn- form-value
  {:malli/schema [:=> [:cat :string :string] [:maybe :string]]}
  [body name]
  (second (re-find (re-pattern (str "name=\"" name "\"[^>]*value=\"([^\"]*)\"")) body)))


(defn- profile-form
  "Open the profile page and return the hidden values its form posts."
  {:malli/schema [:=> [:cat [:fn ifn?] :map] :map]}
  [request! params]
  (let [body (:body (request! :get "/account/profile" params))]
    (into {} (for [name ["account" "name-revision" "language-revision" "return-to"]] [name (form-value body name)]))))


(deftest continue-saves-name-and-next-request-is-not-gated
  (support/with-application
    (fn [{:keys [browser users state]}]
      (let [{:keys [request!]} browser
            user               (unnamed users)]
        (support/login! browser user)
        (let [form     (profile-form request! {"return-to" "/workspace"})
              response (request! :post (bel/action-url :tover.accounts.profile.web/save-name)
                                 (assoc form "display-name" "  Алиса  "))]
          (is (= "/workspace" (get form "return-to")))
          (is (= 303 (:status response)))
          (is (= "/workspace" (get-in response [:headers "location"])))
          (is (= "Алиса" (get-in @state [:users (:user-id user) :display-name])))
          (is (= 200 (:status (request! :get "/workspace")))))))))


(deftest refresh-failure-does-not-fail-the-save
  (support/with-application
    (fn [{:keys [auth] :as services}]
      (let [failing? (atom false)]
        (assoc services :failing? failing?
               :auth (support/auth-provider auth {:refresh (fn [token]
                                                             (if @failing?
                                                               (throw (ex-info "Auth is down" {:type :unavailable}))
                                                               (auth/refresh! auth token)))}))))
    (fn [{:keys [browser users state failing?]}]
      (let [{:keys [request!]} browser
            user               (unnamed users)]
        (support/login! browser user)
        (let [form (profile-form request! {"return-to" "/workspace"})]
          (reset! failing? true)
          (let [response (request! :post (bel/action-url :tover.accounts.profile.web/save-name)
                                   (assoc form "display-name" "Alice"))]
            (is (= 303 (:status response)))
            (is (= "/workspace" (get-in response [:headers "location"])))
            (is (= "Alice" (get-in @state [:users (:user-id user) :display-name])))))))))


(deftest language-switch-keeps-the-draft-name
  (support/with-application
    (fn [{:keys [browser users]}]
      (let [{:keys [request!]} browser]
        (support/login! browser (unnamed users))
        (let [form     (profile-form request! {"return-to" "/workspace"})
              response (request! :post (bel/action-url :tover.auth.web/set-language)
                                 (assoc form "display-name" "Draft" "locale" "ru"))]
          (is (= 200 (:status response)))
          (is (str/includes? (:body response) "value=\"Draft\""))
          (is (str/includes? (:body response) "lang=\"ru\"")))))))


(deftest signed-in-language-writes-the-profile-not-auth
  (support/with-application
    (fn [{:keys [browser users state session-store] :as services}]
      (let [{:keys [request!]} browser
            user               (first users)
            metadata           (get-in @state [:users (:user-id user) :locale])]
        (support/login! browser user)
        (let [body     (:body (request! :get "/workspace"))
              response (request! :post (bel/action-url :tover.auth.web/set-language)
                                 {"locale"            "ru" "return-to" "/workspace"
                                  "account"           (form-value body "account")
                                  "language-revision" (form-value body "language-revision")})]
          (is (= 303 (:status response)))
          (is (= "/workspace" (get-in response [:headers "location"])))
          (is (= metadata (get-in @state [:users (:user-id user) :locale])))
          (is (= "ru" (get-in @state [:users (:user-id user) :language])))
          (is (= "ru" (get-in (security/authenticate services (support/session-key browser)) [:user :locale])))
          (is (some? (sessions/session session-store (support/session-key browser)))))))))


(deftest failed-language-save-does-not-silently-succeed
  (support/with-application
    (fn [{:keys [browser users state]}]
      (let [{:keys [request!]} browser
            user               (first users)
            save!              #(request! :post (bel/action-url :tover.auth.web/set-language)
                                          (merge {"locale" "ru" "return-to" "/workspace" "account" (str (:user-id user))} %))]
        (support/login! browser user)
        (request! :get "/workspace")
        (swap! state update-in [:users (:user-id user)] assoc :language "en" :language-revision 3)
        (let [stale (save! {"language-revision" "2"})]
          (is (= 409 (:status stale)))
          (is (str/includes? (:body stale) "English"))
          (is (str/includes? (:body stale) "href=\"/workspace\""))
          (is (nil? (get-in stale [:cookies "tover-language"]))))
        (let [invalid (save! {"language-revision" "latest"})]
          (is (= 422 (:status invalid)))
          (is (nil? (get-in invalid [:headers "location"])))
          (is (nil? (get-in invalid [:cookies "tover-language"]))))
        (let [foreign (save! {"language-revision" "3" "account" (str (:user-id (second users)))})]
          (is (= 403 (:status foreign))))
        (is (= {:language "en" :language-revision 3}
               (select-keys (get-in @state [:users (:user-id user)]) [:language :language-revision])))))))


(deftest recovery-session-cannot-open-profile
  (support/with-application
    (fn [{:keys [browser users session-store]}]
      (let [{:keys [request!]} browser]
        (support/login! browser (first users))
        (sessions/update-session! session-store (support/session-key browser)
                                  #(vector (assoc-in % [:data :identity/password-required?] true) nil))
        (let [response (request! :get "/account/profile")]
          (is (= 303 (:status response)))
          (is (= "/auth/password" (get-in response [:headers "location"]))))))))


(deftest recovery-session-switches-language-in-this-browser-only
  (support/with-application
    (fn [{:keys [browser users session-store state]}]
      (let [{:keys [request!]} browser
            user               (first users)]
        (swap! state assoc-in [:users (:user-id user) :language] "ru")
        (support/login! browser user)
        (sessions/update-session! session-store (support/session-key browser)
                                  #(vector (assoc-in % [:data :identity/password-required?] true) nil))
        (let [response (request! :post (bel/action-url :tover.auth.web/set-language)
                                 {"locale" "en" "return-to" "/auth/password" "account" (str (:user-id user)) "language-revision" "0"})]
          (is (= 303 (:status response)))
          (is (= "/auth/password" (get-in response [:headers "location"])))
          (is (= "ru" (get-in @state [:users (:user-id user) :language]))))
        (is (str/includes? (:body (request! :get "/auth/password")) "lang=\"en\""))))))


(deftest accepted-saves-refresh-every-session-of-the-account
  (support/with-application
    (fn [{:keys [handler browser users] :as services}]
      (let [user  (first users)
            other (support/browser handler)]
        (support/login! browser user)
        (support/login! other user)
        (let [body (:body ((:request! browser) :get "/workspace"))]
          ((:request! browser) :post (bel/action-url :tover.auth.web/set-language)
                               {"locale"            "ru" "return-to" "/workspace" "account" (form-value body "account")
                                "language-revision" (form-value body "language-revision")}))
        ;; The effect runs after the response; wait for it boundedly.
        (let [locale #(get-in (security/authenticate services (support/session-key other)) [:user :locale])]
          (is (= "ru" (loop [attempt 0]
                        (let [value (locale)]
                          (if (or (= "ru" value) (= attempt 100)) value (do (Thread/sleep 20) (recur (inc attempt)))))))))))))


(deftest failed-name-saves-keep-the-proposed-name
  (support/with-application
    (fn [{:keys [browser users state]}]
      (let [{:keys [request!]} browser
            user               (unnamed users)
            save!              #(request! :post (bel/action-url :tover.accounts.profile.web/save-name) %)]
        (support/login! browser user)
        (let [form (profile-form request! {"return-to" "/workspace"})]
          ;; Another tab saved first.
          (swap! state update-in [:users (:user-id user)] assoc :display-name "Bob" :name-revision 1)
          (let [stale (save! (assoc form "display-name" "Rob"))]
            (is (= 409 (:status stale)))
            (is (str/includes? (:body stale) "value=\"Rob\""))
            (is (str/includes? (:body stale) "Saved name: Bob"))
            (is (= "1" (form-value (subs (:body stale) (str/index-of (:body stale) "profile-form")) "name-revision"))))
          (let [blank (save! (assoc form "display-name" "   " "name-revision" "1"))]
            (is (= 422 (:status blank)))
            (is (str/includes? (:body blank) "Enter a name of 1 to 100 characters.")))
          (is (= "Bob" (get-in @state [:users (:user-id user) :display-name]))))))))
