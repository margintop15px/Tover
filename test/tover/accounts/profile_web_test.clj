(ns tover.accounts.profile-web-test
  "Profile setup and the signed-in language switch through the application handler."
  (:require
    [bel.core :as bel]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [tover.fixtures :as fixtures]
    [tover.identity.access :as access]
    [tover.identity.session :as session]
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
  (let [body (:body (request! :get "/account/profile" params))
        body (subs body (str/index-of body "profile-form"))]
    (into {} (for [name ["account" "name-revision" "language-revision" "return-to"]] [name (form-value body name)]))))


(deftest continue-saves-name-and-next-request-is-not-gated
  (support/with-application
    (fn [{:keys [browser users state] :as services}]
      (let [{:keys [request!]} browser
            user               (unnamed users)]
        (support/sign-in! services browser (:email user) {:workspace-id fixtures/workspace-id})
        (let [form     (profile-form request! {"return-to" "/workspace"})
              response (request! :post (bel/action-url :tover.accounts.profile.web/save-name)
                                 (assoc form "display-name" "  Алиса  "))]
          (is (= "/workspace" (get form "return-to")))
          (is (= 303 (:status response)))
          (is (= "/workspace" (get-in response [:headers "location"])))
          (is (= "Алиса" (get-in @state [:db :public.profiles (:user-id user) :display_name])))
          (is (= 200 (:status (request! :get "/workspace")))))))))


(deftest language-switch-keeps-the-draft-name
  (support/with-application
    (fn [{:keys [browser users] :as services}]
      (let [{:keys [request!]} browser]
        (support/sign-in! services browser "unnamed@example.test")
        (let [form     (profile-form request! {"return-to" "/workspace"})
              response (request! :post (bel/action-url :tover.accounts.profile.web/set-language)
                                 (assoc form "display-name" "Draft" "locale" "ru"))]
          (is (= 200 (:status response)))
          (is (str/includes? (:body response) "value=\"Draft\""))
          (is (str/includes? (:body response) "lang=\"ru\"")))))))


(deftest signed-in-language-writes-the-profile-not-auth
  (support/with-application
    (fn [{:keys [browser users state session-store] :as services}]
      (let [{:keys [request!]} browser
            user               (first users)
            metadata           (get-in @state [:db :auth.users (:user-id user) :locale])]
        (support/sign-in! services browser (:email user) {:workspace-id fixtures/workspace-id})
        (let [body     (:body (request! :get "/workspace"))
              ;; The header switch posts only the language; the revision is read when it is saved.
              response (request! :post (bel/action-url :tover.accounts.profile.web/set-language) {"locale" "ru" "return-to" "/workspace"})]
          (is (nil? (form-value body "language-revision")))
          (is (nil? (form-value body "account")))
          (is (= 303 (:status response)))
          (is (= "/workspace" (get-in response [:headers "location"])))
          (is (= metadata (get-in @state [:db :auth.users (:user-id user) :locale])))
          (is (= "ru" (get-in @state [:db :public.profiles (:user-id user) :language])))
          (is (= "ru" (get-in (access/current-identity services (support/session-key browser)) [:user :locale])))
          (is (some? (session/session session-store (support/session-key browser)))))))))


(deftest failed-language-save-does-not-silently-succeed
  (support/with-application
    (fn [{:keys [browser users state] :as services}]
      (let [{:keys [request!]} browser
            user               (first users)
            save!              #(request! :post (bel/action-url :tover.accounts.profile.web/set-language)
                                          (merge {"locale" "ru" "return-to" "/workspace" "account" (str (:user-id user))} %))]
        (support/sign-in! services browser (:email user) {:workspace-id fixtures/workspace-id})
        (request! :get "/workspace")
        (swap! state update-in [:db :public.profiles (:user-id user)] assoc :language "en" :language_revision 3)
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
        (is (= {:language "en" :language_revision 3}
               (select-keys (get-in @state [:db :public.profiles (:user-id user)]) [:language :language_revision])))))))


(deftest recovery-session-cannot-open-profile
  (support/with-application
    (fn [{:keys [browser users session-store] :as services}]
      (let [{:keys [request!]} browser]
        (support/sign-in! services browser "owner@example.test" {:workspace-id fixtures/workspace-id})
        (session/update-session! session-store (support/session-key browser)
                                 #(vector (assoc-in % [:data :identity/password-required?] true) nil))
        (let [response (request! :get "/account/profile")]
          (is (= 303 (:status response)))
          (is (= "/auth/password" (get-in response [:headers "location"]))))))))


(deftest recovery-session-switches-language-in-this-browser-only
  (support/with-application
    (fn [{:keys [browser users session-store state] :as services}]
      (let [{:keys [request!]} browser
            user               (first users)]
        (swap! state assoc-in [:db :public.profiles (:user-id user) :language] "ru")
        (support/sign-in! services browser (:email user) {:workspace-id fixtures/workspace-id})
        (session/update-session! session-store (support/session-key browser)
                                 #(vector (assoc-in % [:data :identity/password-required?] true) nil))
        (let [response (request! :post (bel/action-url :tover.identity.shell/set-language)
                                 {"locale" "en" "return-to" "/auth/password" "account" (str (:user-id user)) "language-revision" "0"})]
          (is (= 303 (:status response)))
          (is (= "/auth/password" (get-in response [:headers "location"])))
          (is (= "ru" (get-in @state [:db :public.profiles (:user-id user) :language]))))
        (is (str/includes? (:body (request! :get "/auth/password")) "lang=\"en\""))))))


(deftest accepted-saves-refresh-every-session-of-the-account
  (support/with-application
    (fn [{:keys [handler browser users] :as services}]
      (let [user  (first users)
            other (support/browser handler)]
        (support/sign-in! services browser (:email user) {:workspace-id fixtures/workspace-id})
        (support/sign-in! services other (:email user) {:workspace-id fixtures/workspace-id})
        ((:request! browser) :get "/workspace")
        ((:request! browser) :post (bel/action-url :tover.accounts.profile.web/set-language) {"locale" "ru" "return-to" "/workspace"})
        ;; The effect runs after the response; wait for it boundedly.
        (let [locale #(get-in (access/current-identity services (support/session-key other)) [:user :locale])]
          (is (= "ru" (loop [attempt 0]
                        (let [value (locale)]
                          (if (or (= "ru" value) (= attempt 100)) value (do (Thread/sleep 20) (recur (inc attempt)))))))))))))


(deftest failed-name-saves-keep-the-proposed-name
  (support/with-application
    (fn [{:keys [browser users state] :as services}]
      (let [{:keys [request!]} browser
            user               (unnamed users)
            save!              #(request! :post (bel/action-url :tover.accounts.profile.web/save-name) %)]
        (support/sign-in! services browser (:email user) {:workspace-id fixtures/workspace-id})
        (let [form (profile-form request! {"return-to" "/workspace"})]
          ;; Another tab saved first.
          (swap! state update-in [:db :public.profiles (:user-id user)] assoc :display_name "Bob" :name_revision 1)
          (let [stale (save! (assoc form "display-name" "Rob"))]
            (is (= 409 (:status stale)))
            (is (str/includes? (:body stale) "value=\"Rob\""))
            (is (str/includes? (:body stale) "Saved name: Bob"))
            (is (= "1" (form-value (subs (:body stale) (str/index-of (:body stale) "profile-form")) "name-revision"))))
          (let [blank (save! (assoc form "display-name" "   " "name-revision" "1"))]
            (is (= 422 (:status blank)))
            (is (str/includes? (:body blank) "Enter a name of 1 to 100 characters.")))
          (is (= "Bob" (get-in @state [:db :public.profiles (:user-id user) :display_name]))))))))


(deftest shell-pages-do-not-read-the-profile
  (support/with-application
    (fn [{:keys [browser users] :as services}]
      (support/sign-in! services browser "owner@example.test" {:workspace-id fixtures/workspace-id})
      (let [read runtime/read-model]
        (with-redefs [runtime/read-model (fn [context id input]
                                           (if (= id :tover.accounts.profile.model/my-profile)
                                             (throw (ex-info "No profile read on page renders" {}))
                                             (read context id input)))]
          (is (= 200 (:status ((:request! browser) :get "/workspace")))))))))


(deftest identity-document-switch-saves-the-signed-in-language
  (support/with-application
    (fn [{:keys [browser users state] :as services}]
      (let [{:keys [request!]} browser
            user               (first users)]
        (support/sign-in! services browser (:email user) {:workspace-id fixtures/workspace-id})
        ;; A signed-in identity page renders the same switch: locale and return-to only.
        (let [body     (:body (request! :get "/auth/login"))
              action   (second (re-find #"<form class=\"language-switch\"[^>]*action=\"([^\"]+)\"" body))
              response (request! :post action {"locale" "ru" "return-to" (form-value body "return-to")})]
          (is (= (bel/action-url :tover.accounts.profile.web/set-language) action))
          (is (nil? (form-value body "language-revision")))
          (is (= 303 (:status response)))
          (is (= "/auth/login" (get-in response [:headers "location"])))
          (is (= {:language "ru" :language_revision 1}
                 (select-keys (get-in @state [:db :public.profiles (:user-id user)]) [:language :language_revision]))))))))


(defn- name-changes
  {:malli/schema [:=> [:cat :any] :int]}
  [state]
  (count (filter #(= :tover.accounts.profile.model/display-name-changed (:type %)) (mapcat :events (:journal @state)))))


(deftest setup-says-continue-and-later-edits-say-save
  (support/with-application
    (fn [{:keys [handler browser] :as services}]
      (support/sign-in! services browser "unnamed@example.test")
      (is (str/includes? (:body ((:request! browser) :get "/account/profile")) ">Continue</button>"))
      (let [named (support/browser handler)]
        (support/sign-in! services named "owner@example.test")
        (is (str/includes? (:body ((:request! named) :get "/account/profile")) ">Save</button>"))))))


(deftest continue-without-a-destination-opens-the-workspace-chooser
  (support/with-application
    (fn [{:keys [browser] :as services}]
      (support/sign-in! services browser "unnamed@example.test")
      (let [{:keys [request!]} browser
            form               (profile-form request! {})
            response           (request! :post (bel/action-url :tover.accounts.profile.web/save-name)
                                         (assoc form "display-name" "Una"))]
        (is (= "/workspace" (get form "return-to")))
        (is (= "/workspace" (get-in response [:headers "location"])))))))


(deftest an-accepted-save-with-a-lost-response-is-recovered
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (support/sign-in! services browser "unnamed@example.test")
      (let [{:keys [request!]} browser
            params             (assoc (profile-form request! {"return-to" "/workspace"})
                                      "display-name" "Una" "command-id" "save-1")
            save!              #(request! :post (bel/action-url :tover.accounts.profile.web/save-name) params)]
        (is (= 303 (:status (save!))))
        ;; The response was lost; the browser repeats the same request.
        (is (= 303 (:status (save!))))
        (is (= 1 (name-changes state)))
        (is (= "Una" (get-in @state [:db :public.profiles fixtures/unnamed-id :display_name])))))))


(deftest an-old-save-retried-after-a-newer-name-is-not-reapplied
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (support/sign-in! services browser "unnamed@example.test")
      (let [{:keys [request!]} browser
            save!              #(request! :post (bel/action-url :tover.accounts.profile.web/save-name) %)
            old                (assoc (profile-form request! {"return-to" "/workspace"}) "display-name" "Una" "command-id" "old")]
        (save! old)
        (save! (assoc (profile-form request! {"return-to" "/workspace"}) "display-name" "Uma" "command-id" "new"))
        (is (= 303 (:status (save! old))))
        (is (= "Uma" (get-in @state [:db :public.profiles fixtures/unnamed-id :display_name])))
        (is (= 2 (name-changes state)))))))


(deftest a-form-of-account-a-is-rejected-under-b-sign-in
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (support/sign-in! services browser "unnamed@example.test")
      (let [{:keys [request!]} browser
            form               (profile-form request! {"return-to" "/workspace"})]
        ;; Bob signs in on the same browser before Alice's form is submitted.
        (support/sign-in! services browser "member@example.test")
        (let [response (request! :post (bel/action-url :tover.accounts.profile.web/save-name)
                                 (assoc form "display-name" "Una" "name-revision" "1"))]
          (is (= 403 (:status response))))
        (is (= "Max Member" (get-in @state [:db :public.profiles fixtures/member-id :display_name])))
        (is (nil? (get-in @state [:db :public.profiles fixtures/unnamed-id :display_name])))))))


(deftest an-overlong-name-is-rejected-and-kept-in-full
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (support/sign-in! services browser "unnamed@example.test")
      (let [{:keys [request!]} browser
            long               (apply str (repeat 101 "é"))
            response           (request! :post (bel/action-url :tover.accounts.profile.web/save-name)
                                         (assoc (profile-form request! {"return-to" "/workspace"}) "display-name" long))]
        (is (= 422 (:status response)))
        (is (str/includes? (:body response) (str "value=\"" long "\"")))
        (is (nil? (get-in @state [:db :public.profiles fixtures/unnamed-id :display_name])))))))


(deftest a-failed-profile-read-offers-retry
  (support/with-application
    (fn [{:keys [browser] :as services}]
      (support/sign-in! services browser "owner@example.test")
      (let [read runtime/read-model]
        (with-redefs [runtime/read-model (fn [context id input]
                                           (if (= id :tover.accounts.profile.model/my-profile)
                                             (throw (ex-info "Directory unavailable" {:type :unavailable}))
                                             (read context id input)))]
          (let [body (:body ((:request! browser) :get "/account/profile"))]
            (is (str/includes? body "Could not load your profile."))
            (is (str/includes? body "Retry"))
            (is (not (str/includes? body "name=\"display-name\"")))))))))
