(ns tover.memberships.web-test
  "Team page membership actions (board chapters 34–36, 38, 39) through the application handler, over the
  offline fixtures: Olga Owner and Max Member in the development workspace."
  (:require
    [bel.core :as bel]
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [ring.util.codec :as codec]
    [tover.fixtures :as fixtures]
    [tover.invitations.fixtures :as invitations]
    [tover.support :as support]))


(use-fixtures :each invitations/with-memory-coeffects)


(def workspace (str fixtures/workspace-id))


(defn- page
  {:malli/schema [:=> [:cat :map] :string]}
  [response]
  (str/replace (str (:body response)) #"<meta name=\"tover-messages\"[^>]*>" ""))


(defn- follow
  {:malli/schema [:=> [:cat :map :map] :map]}
  [browser response]
  (let [[uri query] (str/split (get-in response [:headers "location"]) #"\?" 2)]
    ((:request! browser) :get uri (if query (codec/form-decode query) {}))))


(defn- membership
  {:malli/schema [:=> [:cat :map :uuid] :map]}
  [{:keys [state]} user-id]
  (some #(when (and (= user-id (:user_id %)) (= fixtures/workspace-id (:workspace_id %))) %)
        (vals (get-in @state [:db :memberships]))))


(defn- act!
  "POST a row action for `user-id`'s membership at its current revision."
  {:malli/schema [:=> [:cat :map :qualified-keyword :uuid :map] :map]}
  [{:keys [browser] :as services} action user-id params]
  (let [{:keys [membership_id revision]} (membership services user-id)]
    ((:request! browser) :post (bel/action-url action)
                         (merge {"command-id"    (str (random-uuid)) "workspace-id"      workspace
                                 "membership-id" (str membership_id) "expected-revision" (str (or revision 1))}
                                params))))


(defn- as-owner
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [browser] :as services}]
  (support/sign-in! services browser "owner@example.test" {:workspace-id fixtures/workspace-id :uri "/team"}))


(deftest owner-row-actions
  (support/with-application
    (fn [{:keys [browser] :as services}]
      (as-owner services)
      (let [body (page ((:request! browser) :get "/team"))]
        (is (str/includes? body "Save role"))
        (is (str/includes? body "Suspend access"))
        (is (str/includes? body "Remove from workspace"))
        (is (str/includes? body "Make owner"))
        (is (str/includes? body "Transfer ownership"))))))


(deftest owner-promotes-a-member-through-the-team-page
  (support/with-application
    (fn [{:keys [browser] :as services}]
      (as-owner services)
      (let [response (act! services :tover.memberships.web/change-role fixtures/member-id {"role" "admin"})]
        (is (= "admin" (:role (membership services fixtures/member-id))))
        (is (= 2 (:revision (membership services fixtures/member-id))))
        (is (str/includes? (page (follow browser response)) "Role changed."))))))


(deftest stale-row-is-refused
  (support/with-application
    (fn [{:keys [browser] :as services}]
      (as-owner services)
      (let [{:keys [membership_id]} (membership services fixtures/member-id)
            response                ((:request! browser) :post (bel/action-url :tover.memberships.web/suspend)
                                                         {"command-id"    (str (random-uuid)) "workspace-id"      workspace
                                                          "membership-id" (str membership_id) "expected-revision" "7"})]
        (is (= 409 (:status response)))
        (is (= "active" (:status (membership services fixtures/member-id))))))))


(deftest suspend-reinstate-and-remove
  (support/with-application
    (fn [{:keys [browser] :as services}]
      (as-owner services)
      (act! services :tover.memberships.web/suspend fixtures/member-id {"reason" "audit"})
      (is (= {:status "suspended" :role "member" :suspension_reason "audit"}
             (select-keys (membership services fixtures/member-id) [:status :role :suspension_reason])))
      (is (str/includes? (page ((:request! browser) :get "/team")) "audit"))
      (act! services :tover.memberships.web/reinstate fixtures/member-id {})
      (is (= {:status "active" :suspension_reason nil}
             (select-keys (membership services fixtures/member-id) [:status :suspension_reason])))
      (act! services :tover.memberships.web/remove-member fixtures/member-id {})
      (is (= {:status "removed" :removal_kind "removed"}
             (select-keys (membership services fixtures/member-id) [:status :removal_kind])))
      (is (some? (:removed_at (membership services fixtures/member-id)))))))


(deftest owner-cannot-suspend-themselves
  (support/with-application
    (fn [services]
      (as-owner services)
      (is (= 409 (:status (act! services :tover.memberships.web/suspend fixtures/owner-id {}))))
      (is (= "active" (:status (membership services fixtures/owner-id)))))))


(deftest owner-transfers-ownership
  (support/with-application
    (fn [services]
      (as-owner services)
      (act! services :tover.memberships.web/transfer-ownership fixtures/member-id {"my-expected-revision" "1"})
      (is (= "owner" (:role (membership services fixtures/member-id))))
      (is (= "admin" (:role (membership services fixtures/owner-id)))))))


(deftest member-is-refused
  (support/with-application
    (fn [{:keys [browser] :as services}]
      (support/sign-in! services browser "member@example.test" {:workspace-id fixtures/workspace-id :uri "/"})
      (is (= 403 (:status (act! services :tover.memberships.web/suspend fixtures/owner-id {}))))
      (is (= "active" (:status (membership services fixtures/owner-id)))))))


;; 37 · Leave a workspace


(deftest member-leaves-from-settings
  (support/with-application
    (fn [{:keys [browser] :as services}]
      (support/sign-in! services browser "member@example.test" {:workspace-id fixtures/workspace-id :uri "/settings"})
      (let [settings (page ((:request! browser) :get "/settings"))
            response ((:request! browser) :post (bel/action-url :tover.memberships.departure.web/leave)
                                          {"command-id"        (str (random-uuid)) "workspace-id"   workspace
                                           "expected-revision" "1"                 "workspace-name" "Development workspace"})]
        (is (str/includes? settings "Leave Development workspace"))
        (is (= {:status "removed" :removal_kind "left"}
               (select-keys (membership services fixtures/member-id) [:status :removal_kind])))
        (is (str/includes? (page (follow browser response)) "You left Development workspace."))))))


(deftest sole-owner-is-blocked-from-leaving
  (support/with-application
    (fn [{:keys [browser] :as services}]
      (support/sign-in! services browser "owner@example.test" {:workspace-id fixtures/workspace-id :uri "/settings"})
      (is (str/includes? (page ((:request! browser) :get "/settings")) "You are the only active Owner."))
      (is (= 409 (:status ((:request! browser) :post (bel/action-url :tover.memberships.departure.web/leave)
                                               {"command-id" (str (random-uuid)) "workspace-id" workspace "expected-revision" "1"}))))
      (is (= "active" (:status (membership services fixtures/owner-id)))))))
