(ns tover.invitations.web-test
  "The Team screen, Resend/Cancel and the recipient's review and acceptance through the application handler,
  over the offline fixtures with in-memory invitation reads."
  (:require
    [bel.core :as bel]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [ring.util.codec :as codec]
    [tover.fixtures :as fixtures]
    [tover.identity.session :as session]
    [tover.identity.sign-in-test :refer [sign-in!]]
    [tover.invitations.accept.web :as accept]
    [tover.invitations.fixtures :as invitations]
    [tover.support :as support])
  (:import
    [java.time Duration Instant]))


(use-fixtures :each invitations/with-memory-coeffects)


(def workspace (str fixtures/workspace-id))


(defn- invite!
  {:malli/schema [:=> [:cat [:fn ifn?] :map] :map]}
  [request! params]
  (request! :post (bel/action-url :tover.invitations.issue.web/invite)
            (merge {"email"        "otto@example.test" "role" "member" "command-id" (str (random-uuid)) "invitation-id" (str (random-uuid))
                    "workspace-id" workspace} params)))


(defn- page
  "The response body without the message catalog every document embeds."
  {:malli/schema [:=> [:cat :map] :string]}
  [response]
  (str/replace (str (:body response)) #"<meta name=\"tover-messages\"[^>]*>" ""))


(defn- follow
  "GET the redirect location, passing its query as parameters."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [browser response]
  (let [[uri query] (str/split (get-in response [:headers "location"]) #"\?" 2)]
    ((:request! browser) :get uri (if query (codec/form-decode query) {}))))


(defn- offers
  {:malli/schema [:=> [:cat :any] [:vector :map]]}
  [state]
  (vec (vals (get-in @state [:db :invitations]))))


(defn- deliveries
  {:malli/schema [:=> [:cat :any] [:vector :map]]}
  [state]
  (vec (vals (get-in @state [:db :invitation_deliveries]))))


(defn- offer-for
  {:malli/schema [:=> [:cat :any :string] [:maybe :map]]}
  [state email]
  (some #(when (= email (:email %)) %) (offers state)))


(defn- as-owner
  "Sign the browser in as the owner of the fixture workspace."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [browser] :as services}]
  (support/sign-in! services browser "owner@example.test" {:workspace-id fixtures/workspace-id :uri "/team"}))


(deftest review-team-and-pending-invitations
  (support/with-application
    (fn [{:keys [browser] :as services}]
      (as-owner services)
      (let [body (page ((:request! browser) :get "/team"))]
        (is (str/includes? body "Max Member"))
        (is (str/includes? body "Development workspace"))
        (is (str/includes? body "name=\"email\""))
        (is (str/includes? body "No invitations yet."))))))


(deftest a-failed-team-read-offers-retry
  (support/with-application
    (fn [{:keys [browser] :as services}]
      (as-owner services)
      (let [response (invite! (:request! browser) {})]
        (is (str/includes? (page (follow browser response)) "management-status\">Pending<"))
        (is (not (str/includes? (page (follow browser response)) "Invitation: Pending"))
            "with the team loaded, the new offer shows only in its row")
        (.addMethod ^clojure.lang.MultiFn runtime/coeffect :tover.invitations.persistence/team
                    (fn [_ _ _] (throw (ex-info "unavailable" {:type :unavailable}))))
        (let [body (page (follow browser response))]
          (is (str/includes? body "The team could not be loaded."))
          (is (str/includes? body "Invitation: Pending") "the known result of the new offer stays visible")
          (is (not (str/includes? body "No invitations yet."))))))))


(deftest an-accepted-invitation-is-listed-only-as-a-member
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (as-owner services)
      (invite! (:request! browser) {})
      (let [{id :invitation_id} (offer-for state "otto@example.test")]
        (swap! state assoc-in [:db :invitations id :status] "accepted")
        (is (not (str/includes? (page ((:request! browser) :get "/team")) (str "data-invitation-id=\"" id "\""))))))))


(deftest members-cannot-manage-the-team
  (support/with-application
    (fn [{:keys [browser] :as services}]
      (support/sign-in! services browser "member@example.test" {:workspace-id fixtures/workspace-id :uri "/team"})
      (is (= 403 (:status ((:request! browser) :get "/team")))))))


(deftest issue-and-show-the-invitation-immediately
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (as-owner services)
      (let [response (invite! (:request! browser) {"email" " Otto@Example.test "})
            offer    (offer-for state "otto@example.test")]
        (is (= 303 (:status response)))
        (is (= (str "/team?invitation=" (:invitation_id offer)) (get-in response [:headers "location"])))
        (is (not (str/includes? (get-in response [:headers "location"]) "otto")) "no email in the URL")
        (is (= {:status "pending" :role "member" :invited_by fixtures/owner-id} (select-keys offer [:status :role :invited_by])))
        (is (= (Duration/ofDays 7) (Duration/between (:created_at offer) (:expires_at offer))))
        (is (= ["queued"] (mapv :delivery_status (deliveries state))))
        (let [body (page (follow browser response))]
          (is (str/includes? body "management-status\">Pending<"))
          (is (str/includes? body "Delivery: Queued"))
          (is (not (str/includes? body ">Resend<")) "queued work blocks a parallel resend")
          (is (str/includes? body ">Cancel<")))))))


(deftest a-lost-issuing-response
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (as-owner services)
      (let [params {"command-id" "issue-once" "invitation-id" (str (random-uuid))}]
        (invite! (:request! browser) params)
        (invite! (:request! browser) params)
        (invite! (:request! browser) {})
        (is (= 1 (count (offers state))))
        (is (= 1 (count (deliveries state))))))))


(deftest an-account-or-workspace-switch-before-submission
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (as-owner services)
      (let [response (invite! (:request! browser) {"workspace-id" (str fixtures/other-workspace-id)})]
        (is (= 409 (:status response)))
        (is (empty? (offers state)))))))


(deftest a-role-change-while-a-form-is-open
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (as-owner services)
      (swap! state update-in [:db :memberships]
             update-vals #(cond-> % (= fixtures/owner-id (:user_id %)) (assoc :role "member")))
      (is (= 403 (:status (invite! (:request! browser) {}))))
      (is (empty? (offers state))))))


(deftest active-members-are-not-invited
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (as-owner services)
      (let [response (invite! (:request! browser) {"email" "member@example.test"})]
        (is (= 409 (:status response)))
        (is (str/includes? (page response) "That person is already on the team."))
        (is (empty? (offers state)))))))


(defn- age-requests!
  "Move every delivery request `seconds` into the past and finish it as `status`."
  {:malli/schema [:=> [:cat :any :int :string] :any]}
  [state seconds status]
  (swap! state update-in [:db :invitation_deliveries]
         update-vals #(assoc % :delivery_status status :attempts 1 :requested_at (.minusSeconds ^Instant (:requested_at %) seconds))))


(deftest resend-and-see-the-request-status
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (as-owner services)
      (invite! (:request! browser) {})
      (let [{id :invitation_id} (offer-for state "otto@example.test")
            resend!             #((:request! browser) :post (bel/action-url :tover.invitations.resend.web/resend)
                                                      {"invitation-id" (str id) "command-id" (str (random-uuid)) "workspace-id" workspace})]
        (testing "queued work answers with its status instead of a parallel request"
          (is (= 303 (:status (resend!))))
          (is (= 1 (count (deliveries state)))))
        (testing "the shared cooldown"
          (age-requests! state 59 "submitted")
          (let [response (resend!)]
            (is (= 429 (:status response)))
            (is (str/includes? (page response) "Wait 60 seconds after the last request before resending."))))
        (testing "after 60 seconds a new request keeps the same offer and expiry"
          (age-requests! state 1 "submitted")
          (let [before (offer-for state "otto@example.test")]
            (is (= 303 (:status (resend!))))
            (is (= 2 (count (deliveries state))))
            (is (= before (offer-for state "otto@example.test")))))))))


(deftest cancel-and-see-the-withdrawn-invitation
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (as-owner services)
      (invite! (:request! browser) {})
      (let [{id :invitation_id} (offer-for state "otto@example.test")
            cancel!             #((:request! browser) :post (bel/action-url :tover.invitations.cancel.web/cancel)
                                                      {"invitation-id" (str id) "command-id" (str (random-uuid)) "workspace-id" workspace})
            response            (cancel!)]
        (is (= 303 (:status response)))
        (is (= "revoked" (:status (offer-for state "otto@example.test"))))
        (is (= 303 (:status (cancel!))) "a repeated cancel shows the current state")
        (let [body (page (follow browser response))]
          (is (str/includes? body "management-status\">Cancelled<"))
          (is (not (str/includes? body ">Resend<"))))
        (is (= (:memberships (fixtures/seed)) (get-in @state [:db :memberships]))
            "no membership changes")))))


;; Recipient


(defn- invited
  "The owner invites other@example.test; returns the invitation id."
  {:malli/schema [:=> [:cat :map [:? :string]] :uuid]}
  ([services] (invited services "member"))
  ([{:keys [state] :as services} role]
   (let [owner (support/browser (:handler services))]
     (support/sign-in! services owner "owner@example.test" {:workspace-id fixtures/workspace-id :uri "/team"})
     (invite! (:request! owner) {"email" "other@example.test" "role" role})
     (:invitation_id (offer-for state "other@example.test")))))


(defn- accept!
  {:malli/schema [:=> [:cat [:fn ifn?] :uuid [:? :map]] :map]}
  ([request! id] (accept! request! id {}))
  ([request! id params]
   (request! :post (bel/action-url ::accept/accept)
             (merge {"invitation-id" (str id) "workspace-id" workspace "role" "member" "command-id" (str (random-uuid))} params))))


(defn- membership
  {:malli/schema [:=> [:cat :any :uuid] [:maybe :map]]}
  [state user-id]
  (some #(when (and (= user-id (:user_id %)) (= fixtures/workspace-id (:workspace_id %))) %)
        (vals (get-in @state [:db :memberships]))))


(deftest review-the-received-invitation-and-accept-it
  (support/with-application
    (fn [{:keys [browser state session-store] :as services}]
      (let [id (invited services)]
        (support/sign-in! services browser "other@example.test" {:uri "/workspace"})
        (testing "opening the link changes nothing"
          (let [body (page ((:request! browser) :get (accept/review-path id)))]
            (is (str/includes? body "Development workspace"))
            (is (str/includes? body "Olga Owner"))
            (is (str/includes? body (bel/action-url ::accept/accept)))
            (is (nil? (membership state fixtures/other-id)))))
        (let [response (accept! (:request! browser) id)]
          (is (= 303 (:status response)))
          (is (= (str (accept/review-path id) "/joined") (get-in response [:headers "location"])))
          (is (= {:status "active" :role "member"} (select-keys (membership state fixtures/other-id) [:status :role])))
          (is (= "accepted" (:status (offer-for state "other@example.test"))))
          (is (nil? (:workspace-id (session/session session-store (support/session-key browser))))
              "joining never selects a workspace")
          (let [body (page (follow browser response))]
            (is (str/includes? body "You joined Development workspace as Member."))
            (is (str/includes? body (bel/action-url :tover.workspaces.select.web/select)))
            (is (str/includes? body (str "name=\"workspace-id\" value=\"" workspace "\"")))))
        (testing "two accepts and a repeated email link after joining"
          (let [facts  #(count (mapcat :events (:journal @state)))
                before (facts)]
            (is (= 303 (:status (accept! (:request! browser) id))))
            (is (= before (facts)) "an already-member result records no facts"))
          (is (str/includes? (page ((:request! browser) :get (accept/review-path id))) "You are already a member")))))))


(defn- opened-in-another-account
  "An invitation to other@example.test opened by member@example.test: its id and page."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [browser] :as services}]
  (let [id      (invited services)
        session (support/sign-in! services browser "member@example.test" {:workspace-id fixtures/workspace-id :uri "/workspace"})]
    {:id id :session session :body (page ((:request! browser) :get (accept/review-path id)))}))


(defn- sign-out-there!
  "Submit the page's own Sign Out form and follow it; returns the Sign In page."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [browser {:keys [body]}]
  (let [form  (second (re-find #"(?s)<form class=\"sign-out\"(.*?)</form>" body))
        value #(second (re-find (re-pattern (str "name=\"" % "\" value=\"([^\"]*)\"")) form))
        left  ((:request! browser) :post (bel/action-url :tover.identity.sign-out.web/sign-out)
                                   {"session-id" (value "session-id") "return-to" (value "return-to")})
        gate  (follow browser left)]
    (is (= "/auth/login" (get-in gate [:headers "location"])))
    (follow browser gate)))


(deftest invitation-opened-in-another-account
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (let [{:keys [id body]} (opened-in-another-account services)]
        (is (str/includes? body "This invitation is not available to member@example.test."))
        (is (str/includes? body "This invitation may have been sent to another email."))
        (is (str/includes? body "Signing out leads to the sign-in page."))
        (is (str/includes? body (bel/action-url :tover.identity.sign-out.web/sign-out)))
        (is (str/includes? body "Back to workspaces"))
        (is (not (str/includes? body "other@example.test")))
        (is (not (str/includes? body (str "Development workspace <span>"))))
        (accept! (:request! browser) id)
        (is (= "pending" (:status (offer-for state "other@example.test"))))))))


(deftest sign-out-and-return-with-the-right-account
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (let [{:keys [id] :as opened} (opened-in-another-account services)
            login                   (sign-out-there! browser opened)]
        (is (= 200 (:status login)))
        (is (not (str/includes? (:body login) (str id))))
        (is (not (str/includes? (page login) "other@example.test")))
        (let [response (sign-in! browser "other@example.test" fixtures/password)]
          (is (= (accept/review-path id) (get-in response [:headers "location"])))
          (is (str/includes? (page (follow browser response)) (bel/action-url ::accept/accept))))
        (is (= "pending" (:status (offer-for state "other@example.test"))))))))


(deftest sign-in-to-the-wrong-account-again
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (let [opened (opened-in-another-account services)]
        (sign-out-there! browser opened)
        (is (str/includes? (page (follow browser (sign-in! browser "member@example.test" fixtures/password)))
                           "This invitation is not available to member@example.test."))
        (is (= "pending" (:status (offer-for state "other@example.test"))))))))


(deftest back-to-workspaces-keeps-the-account-and-drops-the-destination
  (support/with-application
    (fn [{:keys [browser session-store] :as services}]
      (let [{:keys [session]} (opened-in-another-account services)]
        (sign-in! browser "member@example.test" fixtures/password)
        (is (= 303 (:status ((:request! browser) :post (bel/action-url ::accept/leave) {}))))
        (is (= (:key session) (support/session-key browser)))
        (is (nil? (get-in (session/session session-store (:key session)) [:data :identity/return-to])))))))


(deftest cancellation-during-accept
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (let [id (invited services)]
        (support/sign-in! services browser "other@example.test" {:uri "/workspace"})
        ((:request! browser) :get (accept/review-path id))
        (swap! state assoc-in [:db :invitations id :status] "revoked")
        (let [response (accept! (:request! browser) id)]
          (is (= (str (accept/review-path id) "?problem=invitation-not-pending") (get-in response [:headers "location"])))
          (is (nil? (membership state fixtures/other-id)))
          (let [body (page (follow browser response))]
            (is (str/includes? body "This invitation can no longer be accepted."))
            (is (str/includes? body "This invitation was cancelled."))
            (is (not (str/includes? body (bel/action-url ::accept/accept))))))))))


(deftest membership-removed-before-open
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (let [id (invited services)]
        (support/sign-in! services browser "other@example.test" {:uri "/workspace"})
        (accept! (:request! browser) id)
        (let [{membership-id :membership_id} (membership state fixtures/other-id)]
          (swap! state update-in [:db :memberships membership-id]
                 assoc :status "removed" :removed_at (Instant/now)))
        (is (str/includes? (page ((:request! browser) :get (str (accept/review-path id) "/joined")))
                           "you no longer have access"))))))


(deftest not-now-followed-by-ordinary-sign-in-routing
  (support/with-application
    (fn [{:keys [browser state session-store] :as services}]
      (let [id (invited services)]
        (support/sign-in! services browser "other@example.test" {:uri "/workspace" :data {accept/destination-key (accept/review-path id)}})
        (let [response ((:request! browser) :post (bel/action-url ::accept/leave) {})]
          (is (= 303 (:status response)))
          (is (= "/workspace" (get-in response [:headers "location"])))
          (is (nil? (get (:data (session/session session-store (support/session-key browser))) accept/destination-key)))
          (is (= "pending" (:status (offer-for state "other@example.test"))) "Not Now leaves the offer pending"))))))
