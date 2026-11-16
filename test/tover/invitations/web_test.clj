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
            (merge {"email" "otto@example.test" "role" "member" "command-id" (str (random-uuid)) "workspace-id" workspace} params)))


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
  (vec (vals (get-in @state [:db :public.organization_invites]))))


(defn- deliveries
  {:malli/schema [:=> [:cat :any] [:vector :map]]}
  [state]
  (vec (vals (get-in @state [:db :public.invitation_deliveries]))))


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
      (.addMethod ^clojure.lang.MultiFn runtime/coeffect :tover.invitations.persistence/team
                  (fn [_ _ _] (throw (ex-info "unavailable" {:type :unavailable}))))
      (let [body (page ((:request! browser) :get "/team"))]
        (is (str/includes? body "The team could not be loaded."))
        (is (not (str/includes? body "No invitations yet.")))))))


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
        (is (= "/team?recipient=otto%40example.test" (get-in response [:headers "location"])))
        (is (= {:status "pending" :role_id "member" :invited_by fixtures/owner-id} (select-keys offer [:status :role_id :invited_by])))
        (is (= (Duration/ofDays 7) (Duration/between (:created_at offer) (:expires_at offer))))
        (is (= ["queued"] (mapv :status (deliveries state))))
        (let [body (page (follow browser response))]
          (is (str/includes? body "Invitation: Pending"))
          (is (str/includes? body "Delivery: Queued"))
          (is (not (str/includes? body ">Resend<")) "queued work blocks a parallel resend")
          (is (str/includes? body ">Cancel<")))))))


(deftest a-lost-issuing-response
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (as-owner services)
      (let [params {"command-id" "issue-once"}]
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
      (swap! state update-in [:db :public.organization_memberships]
             update-vals #(cond-> % (= fixtures/owner-id (:user_id %)) (assoc :role_id "member")))
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
  (swap! state update-in [:db :public.invitation_deliveries]
         update-vals #(assoc % :status status :attempts 1 :requested_at (.minusSeconds ^Instant (:requested_at %) seconds))))


(deftest resend-and-see-the-request-status
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (as-owner services)
      (invite! (:request! browser) {})
      (let [{:keys [id]} (offer-for state "otto@example.test")
            resend!      #((:request! browser) :post (bel/action-url :tover.invitations.resend.web/resend)
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
      (let [{:keys [id]} (offer-for state "otto@example.test")
            cancel!      #((:request! browser) :post (bel/action-url :tover.invitations.cancel.web/cancel)
                                               {"invitation-id" (str id) "command-id" (str (random-uuid)) "workspace-id" workspace})
            response     (cancel!)]
        (is (= 303 (:status response)))
        (is (= "revoked" (:status (offer-for state "otto@example.test"))))
        (is (= 303 (:status (cancel!))) "a repeated cancel shows the current state")
        (let [body (page (follow browser response))]
          (is (str/includes? body "Invitation: Cancelled"))
          (is (not (str/includes? body ">Resend<"))))
        (is (= (:public.organization_memberships (fixtures/seed)) (get-in @state [:db :public.organization_memberships]))
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
     (:id (offer-for state "other@example.test")))))


(defn- accept!
  {:malli/schema [:=> [:cat [:fn ifn?] :uuid [:? :map]] :map]}
  ([request! id] (accept! request! id {}))
  ([request! id params]
   (request! :post (bel/action-url ::accept/accept)
             (merge {"invitation-id" (str id) "workspace-id" workspace "role" "member" "command-id" (str (random-uuid))} params))))


(defn- membership
  {:malli/schema [:=> [:cat :any :uuid] [:maybe :map]]}
  [state user-id]
  (some #(when (and (= user-id (:user_id %)) (= fixtures/workspace-id (:organization_id %))) %)
        (vals (get-in @state [:db :public.organization_memberships]))))


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
          (is (= {:status "active" :role_id "member"} (select-keys (membership state fixtures/other-id) [:status :role_id])))
          (is (= "accepted" (:status (offer-for state "other@example.test"))))
          (is (nil? (:workspace-id (session/session session-store (support/session-key browser))))
              "joining never selects a workspace")
          (let [body (page (follow browser response))]
            (is (str/includes? body "You joined Development workspace as Member."))
            (is (str/includes? body (str "/workspace?workspace-id=" workspace)))))
        (testing "two accepts and a repeated email link after joining"
          (let [facts  #(count (mapcat :events (:journal @state)))
                before (facts)]
            (is (= 303 (:status (accept! (:request! browser) id))))
            (is (= before (facts)) "an already-member result records no facts"))
          (is (str/includes? (page ((:request! browser) :get (accept/review-path id))) "You are already a member")))))))


(deftest another-account-sees-safe-mismatch-guidance
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (let [id (invited services)]
        (support/sign-in! services browser "member@example.test" {:workspace-id fixtures/workspace-id :uri "/workspace"})
        (let [body (page ((:request! browser) :get (accept/review-path id)))]
          (is (str/includes? body "This invitation is not available to member@example.test."))
          (is (str/includes? body "/auth/sign-out"))
          (is (not (str/includes? body "Development workspace ·"))))
        (accept! (:request! browser) id)
        (is (= "pending" (:status (offer-for state "other@example.test"))))))))


(deftest cancellation-during-accept
  (support/with-application
    (fn [{:keys [browser state] :as services}]
      (let [id (invited services)]
        (support/sign-in! services browser "other@example.test" {:uri "/workspace"})
        ((:request! browser) :get (accept/review-path id))
        (swap! state assoc-in [:db :public.organization_invites id :status] "revoked")
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
        (let [{membership-id :id} (membership state fixtures/other-id)]
          (swap! state update-in [:db :public.organization_memberships membership-id]
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
