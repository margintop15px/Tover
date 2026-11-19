(ns tover.workspaces.workspace-web-test
  "Chapters 04 and 07 on live routes: the workspace chooser, Select Workspace and the create outcome."
  (:require
    [bel.core :as bel]
    [bel.handler :as handler]
    [bel.realtime :as realtime]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [tover.fixtures :as fixtures]
    [tover.identity.access :as access]
    [tover.identity.sign-out.web :as sign-out]
    [tover.support :as support]
    [tover.workspaces.create.web :as create]
    [tover.workspaces.select.model :as model]
    [tover.workspaces.select.web :as select]))


(defn- form-value
  {:malli/schema [:=> [:cat :string :string] [:maybe :string]]}
  [body name]
  (second (re-find (re-pattern (str "name=\"" name "\"[^>]*value=\"([^\"]*)\"")) body)))


(defn- open-params
  "The Open form of `workspace-id` on the chooser `body`."
  {:malli/schema [:=> [:cat :string :uuid] :map]}
  [body workspace-id]
  (let [row (second (re-find (re-pattern (str "(?s)<tr data-workspace-id=\"" workspace-id "\"(.*?)</tr>")) body))]
    (into {} (for [name ["workspace-id" "expected-version" "command-id" "return-to"]] [name (form-value row name)]))))


(defn- selected
  {:malli/schema [:=> [:cat :map :map] [:maybe :uuid]]}
  [{:keys [state]} session]
  (get-in @state [:db :sessions (:key session) :workspace_id]))


(defn- version
  {:malli/schema [:=> [:cat :map :map] [:maybe :uuid]]}
  [{:keys [state]} session]
  (get-in @state [:db :sessions (:key session) :version]))


(deftest chooser-lists-current-memberships-and-the-selection
  (support/with-application
    (fn [{:keys [browser] :as services}]
      (support/sign-in! services browser "owner@example.test" {:workspace-id fixtures/workspace-id})
      (let [body (:body ((:request! browser) :get "/workspace"))]
        (is (str/includes? body "Development workspace"))
        (is (str/includes? body "Other workspace"))
        (is (re-find (re-pattern (str "(?s)<tr data-workspace-id=\"" fixtures/workspace-id "\">(?:(?!</tr>).)*Current workspace.*?</tr>")) body))
        (is (str/includes? body (str "action=\"" (bel/action-url ::sign-out/sign-out) "\"")))
        (is (str/includes? body (bel/action-url ::create/create)))))))


(deftest the-chooser-signs-out-this-sign-in
  (support/with-application
    (fn [{:keys [browser] :as services}]
      (let [session (support/sign-in! services browser "owner@example.test")
            body    (:body ((:request! browser) :get "/workspace"))
            footer  (second (re-find #"(?s)<footer class=\"management-footer\">(.*?)</footer>" body))
            params  (into {} (for [name ["_tover-context" "session-id"]] [name (form-value footer name)]))]
        (is (= (str (:version session)) (get params "_tover-context")))
        (let [response ((:request! browser) :post (bel/action-url ::sign-out/sign-out) params)]
          (is (= "/auth/login" (get-in response [:headers "location"]))))
        (is (nil? (access/current-identity services (:key session))))))))


(deftest repeated-names-show-a-distinguishing-reference
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (swap! state assoc-in [:db :workspaces fixtures/other-workspace-id :name] "Development workspace")
      (support/sign-in! services browser "owner@example.test")
      (let [body (:body ((:request! browser) :get "/workspace"))]
        (is (str/includes? body (select/reference fixtures/workspace-id)))
        (is (str/includes? body (select/reference fixtures/other-workspace-id)))))))


(deftest no-memberships-offers-creation-only
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (swap! state assoc-in [:db :profiles fixtures/unnamed-id :display_name] "Una")
      (support/sign-in! services browser "unnamed@example.test")
      (let [body (:body ((:request! browser) :get "/workspace"))]
        (is (str/includes? body "No workspaces yet."))
        (is (str/includes? body "<details class=\"management-disclosure\" open"))
        (is (not (str/includes? body "href=\"/operations\"")))))))


(deftest selecting-a-workspace-opens-home-and-closes-old-streams
  (support/with-application
    (fn [{:keys [browser realtime] :as services}]
      (let [session (support/sign-in! services browser "owner@example.test" {:workspace-id fixtures/workspace-id})
            body    (:body ((:request! browser) :get "/workspace"))
            closed  (atom [])]
        (with-redefs [realtime/close-session! (fn [_ key keep] (swap! closed conj [key keep]))]
          (let [response ((:request! browser) :post (bel/action-url ::select/select)
                                              (open-params body fixtures/other-workspace-id))]
            (is (= 303 (:status response)))
            (is (= "/" (get-in response [:headers "location"]))))
          ;; The command's effect runs after commit; it keeps streams opened for the new workspace.
          (loop [n 100] (when (and (empty? @closed) (pos? n)) (Thread/sleep 10) (recur (dec n)))))
        (is (= fixtures/other-workspace-id (selected services session)))
        (is (not= (:version session) (version services session)))
        (is (= [[(:key session) fixtures/other-workspace-id]] @closed))
        (is (= fixtures/other-workspace-id (:workspace-id (access/current-identity services (:key session)))))
        (is (some? realtime))))))


(deftest the-same-workspace-is-chosen-again
  (support/with-application
    (fn [{:keys [browser] :as services}]
      (let [session (support/sign-in! services browser "owner@example.test" {:workspace-id fixtures/workspace-id})
            body    (:body ((:request! browser) :get "/workspace"))
            before  (version services session)
            params  (assoc (open-params body fixtures/workspace-id) "return-to" "/operations")]
        (is (= "/operations" (get-in ((:request! browser) :post (bel/action-url ::select/select) params) [:headers "location"])))
        (is (= before (version services session)))
        (is (= fixtures/workspace-id (selected services session)))))))


(deftest a-workspace-without-membership-is-rejected
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (let [session (support/sign-in! services browser "member@example.test" {:workspace-id fixtures/workspace-id})
            body    (:body ((:request! browser) :get "/workspace"))
            params  (assoc (open-params body fixtures/workspace-id) "workspace-id" (str fixtures/other-workspace-id))
            before  (version services session)]
        (let [response ((:request! browser) :post (bel/action-url ::select/select) params)]
          (is (= 403 (:status response)))
          (is (str/includes? (:body response) "This workspace is not accessible.")))
        (is (= fixtures/workspace-id (selected services session)))
        (is (= before (version services session)))
        (is (empty? (filter #(= ::model/workspace-selected (get-in % [:events 0 :type])) (:journal @state))))))))


(deftest two-tabs-choose-different-workspaces
  (support/with-application
    (fn [{:keys [browser] :as services}]
      (let [session (support/sign-in! services browser "owner@example.test" {:workspace-id fixtures/workspace-id})
            seen    (:version session)
            context (fixtures/context services session)]
        ;; The other tab selects first; this tab's choice still names the selection it saw.
        (runtime/execute! context ::model/select-workspace
                          {:command-id "tab-b" :input {:workspace-id fixtures/other-workspace-id :expected-version seen}})
        (is (= :stale-selection
               (:type (support/error-data
                        #(runtime/execute! (fixtures/context services session) ::model/select-workspace
                                           {:command-id "tab-a" :input {:workspace-id fixtures/workspace-id :expected-version seen}})))))
        (is (= fixtures/other-workspace-id (selected services session)))
        ;; The old page's form is refused before it reaches the command.
        (let [response ((:request! browser) :post (bel/action-url ::select/select)
                                            {"workspace-id" (str fixtures/workspace-id) "expected-version" (str seen)
                                             "command-id"   "tab-a-form"})]
          (is (= 409 (:status response))))))))


(deftest an-older-choice-retried-after-a-newer-one-keeps-the-newer
  (support/with-application
    (fn [{:keys [browser] :as services}]
      (let [session (support/sign-in! services browser "owner@example.test" {:workspace-id fixtures/workspace-id})
            seen    (:version session)
            first!  #(runtime/execute! (fixtures/context services session) ::model/select-workspace
                                       {:command-id "first" :input {:workspace-id fixtures/other-workspace-id :expected-version seen}})
            receipt (first!)]
        (runtime/execute! (fixtures/context services session) ::model/select-workspace
                          {:command-id "second" :input {:workspace-id fixtures/workspace-id :expected-version (version services session)}})
        (is (= receipt (first!)))
        (is (= fixtures/workspace-id (selected services session)))))))


(deftest lost-access-never-falls-back-to-another-workspace
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (let [session (support/sign-in! services browser "owner@example.test" {:workspace-id fixtures/other-workspace-id})]
        (swap! state update-in [:db :memberships]
               (fn [rows] (into {} (map (fn [[id row]] [id (cond-> row
                                                             (and (= fixtures/owner-id (:user_id row))
                                                                  (= fixtures/other-workspace-id (:workspace_id row)))
                                                             (assoc :status "suspended"))])) rows)))
        (let [response ((:request! browser) :get "/")]
          (is (= 303 (:status response)))
          (is (= "/workspace" (get-in response [:headers "location"]))))
        (is (nil? (:workspace-id (access/current-identity services (:key session)))))))))


(deftest header-shows-workspace-tabs-only-with-a-selection
  (support/with-application
    (fn [{:keys [handler browser] :as services}]
      (support/sign-in! services browser "owner@example.test")
      (let [body (:body ((:request! browser) :get "/workspace"))]
        (is (not (str/includes? body "href=\"/operations\"")))
        (is (not (str/includes? body "href=\"/team\"")))
        (is (str/includes? body "href=\"/account/profile\""))
        (is (str/includes? body "/action/tover.accounts.profile.web/set-language")))
      (let [owner  (support/browser handler)
            member (support/browser handler)]
        (support/sign-in! services owner "owner@example.test" {:workspace-id fixtures/workspace-id :uri "/workspace"})
        (support/sign-in! services member "member@example.test" {:workspace-id fixtures/workspace-id :uri "/workspace"})
        (let [body (:body ((:request! owner) :get "/workspace"))]
          (is (str/includes? body "href=\"/operations\""))
          (is (str/includes? body "href=\"/team\""))
          (is (str/includes? body "Development workspace")))
        (is (not (str/includes? (:body ((:request! member) :get "/workspace")) "href=\"/team\"")))))))


;; 04 · Create a workspace on the live page


(defn- action-context
  "The trusted context a Datastar post from this session receives."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [services session]
  (let [context (fixtures/context services session)]
    (assoc context :request {:tover/identity     (dissoc context :locale :session-store :provider :origin :bel/system)
                             :anti-forgery-token (:csrf session)})))


(defn- create!
  {:malli/schema [:=> [:cat :map :map :map] :string]}
  [services session input]
  (let [context (action-context services session)]
    (apply str (map :html (handler/prepare-result context (handler/execute-action context ::create/create (update-keys input keyword)))))))


(deftest create-workspace-shows-the-outcome-and-keeps-the-selection
  (support/with-application
    (fn [{:keys [state] :as services}]
      (let [session (fixtures/sign-in! services "owner@example.test" {:workspace-id fixtures/workspace-id})
            north   (random-uuid)
            html    (create! services session {"command-id" "attempt-a" "workspace-id" (str north) "name" "  North Workshop  "})]
        (is (str/includes? html "North Workshop"))
        (is (str/includes? html "Your role: Owner"))
        (is (str/includes? html "Open workspace"))
        (is (str/includes? html "Invite teammates"))
        (is (str/includes? html (str "name=\"workspace-id\" value=\"" north "\"")))
        (is (str/includes? html "name=\"return-to\" value=\"/team\""))
        (is (= "North Workshop" (get-in @state [:db :workspaces north :name])))
        (is (= fixtures/workspace-id (selected services session)))
        (is (= (:version session) (version services session)))))))


(deftest invalid-names-keep-the-attempt-and-the-full-input
  (support/with-application
    (fn [{:keys [state] :as services}]
      (let [session (fixtures/sign-in! services "owner@example.test" {:workspace-id fixtures/workspace-id})
            north   (random-uuid)
            long    (apply str (repeat 101 "x"))
            html    (create! services session {"command-id" "attempt-b" "workspace-id" (str north) "name" long})]
        (is (str/includes? html "Enter a workspace name of 1 to 100 characters."))
        (is (str/includes? html (str "value=\"" long "\"")))
        (is (str/includes? html "value=\"attempt-b\""))
        (is (nil? (get-in @state [:db :workspaces north])))
        (let [html (create! services session {"command-id" "attempt-b" "workspace-id" (str north) "name" "North"})]
          (is (str/includes? html "Your role: Owner")))))))


(deftest a-retried-attempt-with-a-changed-name-shows-the-original-outcome
  (support/with-application
    (fn [{:keys [state] :as services}]
      (let [session (fixtures/sign-in! services "owner@example.test" {:workspace-id fixtures/workspace-id})
            north   (random-uuid)
            input   {"command-id" "attempt-c" "workspace-id" (str north) "name" "North"}]
        (create! services session input)
        (let [html (create! services session (assoc input "name" "Renamed"))]
          (is (str/includes? html "North"))
          (is (str/includes? html "Your role: Owner"))
          (is (not (str/includes? html "Renamed"))))
        (is (= 1 (count (filter #(= "North" (:name %)) (vals (get-in @state [:db :workspaces]))))))))))


(deftest outcome-after-the-owner-membership-was-removed
  (support/with-application
    (fn [{:keys [state] :as services}]
      (let [session (fixtures/sign-in! services "owner@example.test" {:workspace-id fixtures/workspace-id})
            north   (random-uuid)
            input   {"command-id" "attempt-d" "workspace-id" (str north) "name" "North"}]
        (create! services session input)
        (swap! state update-in [:db :memberships]
               (fn [rows] (into {} (remove #(= north (:workspace_id (val %)))) rows)))
        (let [html (create! services session input)]
          (is (str/includes? html "You no longer have access to this workspace."))
          (is (not (str/includes? html "Open workspace"))))
        (is (empty? (filter #(= north (:workspace_id %)) (vals (get-in @state [:db :memberships])))))))))


(deftest open-workspace-from-the-outcome-selects-the-new-workspace
  (support/with-application
    (fn [{:keys [browser] :as services}]
      (let [session  (support/sign-in! services browser "owner@example.test" {:workspace-id fixtures/workspace-id :uri "/workspace"})
            north    (random-uuid)
            html     (create! services session {"command-id" "attempt-e" "workspace-id" (str north) "name" "North"})
            invite   (second (re-find #"(?s)(<form[^>]*>(?:(?!</form>).)*value=\"/team\".*?</form>)" html))
            params   (into {} (for [name ["workspace-id" "expected-version" "command-id" "return-to"]] [name (form-value invite name)]))
            response ((:request! browser) :post (bel/action-url ::select/select) params)]
        (is (= "/team" (get-in response [:headers "location"])))
        (is (= north (selected services session)))))))
