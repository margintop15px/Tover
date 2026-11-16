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
  (let [body (:body (request! :get "/account/profile" params))]
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
              response (request! :post (bel/action-url :tover.identity.shell/set-language)
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
              response (request! :post (bel/action-url :tover.identity.shell/set-language) {"locale" "ru" "return-to" "/workspace"})]
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
            save!              #(request! :post (bel/action-url :tover.identity.shell/set-language)
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
        ((:request! browser) :post (bel/action-url :tover.identity.shell/set-language) {"locale" "ru" "return-to" "/workspace"})
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
        (let [body     (:body (request! :get "/auth/sign-out"))
              response (request! :post (bel/action-url :tover.identity.shell/set-language)
                                 {"locale" "ru" "return-to" (form-value body "return-to")})]
          (is (nil? (form-value body "language-revision")))
          (is (= 303 (:status response)))
          (is (= "/auth/sign-out" (get-in response [:headers "location"])))
          (is (= {:language "ru" :language_revision 1}
                 (select-keys (get-in @state [:db :public.profiles (:user-id user)]) [:language :language_revision]))))))))
