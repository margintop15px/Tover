(ns tover.workspaces.actor-labels-test
  (:require
    [clojure.test :refer [deftest is]]
    [tover.auth.core :as auth]
    [tover.identity :as identity]
    [tover.support :as support]
    [tover.workspaces.core :as workspaces]
    [tover.workspaces.supabase :as supabase]))


(defn- sign-in
  {:malli/schema [:=> [:cat :any :string] :string]}
  [provider email]
  (:access-token (auth/sign-in! provider {:email email :password "tover-development"})))


(deftest member-labels-use-names-and-email-without-team-admin-access
  (let [{:keys [auth workspaces workspace-id users state]} (identity/fixtures)
        [owner member other] (mapv :user-id users)
        token (sign-in auth "member@example.test")]
    (swap! state assoc-in [:users owner :display-name] "  Owner Name  ")
    (swap! state assoc-in [:users member :display-name] "   ")
    (is (= {(str owner) "Owner Name" (str member) "member@example.test"}
           (workspaces/actor-labels workspaces token workspace-id [owner member other (random-uuid)])))
    (is (= :forbidden (:code (support/error-data #(workspaces/team workspaces token workspace-id)))))
    (swap! state assoc-in [:memberships workspace-id owner :status] :suspended)
    (is (= {(str owner) "Owner Name"}
           (workspaces/actor-labels workspaces token workspace-id [owner])))
    (swap! state update :users dissoc owner)
    (is (= {} (workspaces/actor-labels workspaces token workspace-id [owner])))))


(deftest actor-lookups-require-current-membership-in-the-requested-workspace
  (let [{:keys [auth workspaces workspace-id other-workspace-id users state]} (identity/fixtures)
        member (:user-id (second users))
        token (sign-in auth "member@example.test")]
    (is (= :forbidden
           (:code (support/error-data #(workspaces/actor-labels workspaces token other-workspace-id [member])))))
    (swap! state assoc-in [:memberships workspace-id member :status] :suspended)
    (is (= :forbidden
           (:code (support/error-data #(workspaces/actor-labels workspaces token workspace-id [member])))))
    (is (= :unauthenticated
           (:code (support/error-data #(workspaces/actor-labels workspaces "invalid-token" workspace-id [member])))))))


(deftest adapter-uses-user-token-and-bounded-deduplicated-workspace-rpcs
  (let [workspace-id (random-uuid)
        ids          (mapv (fn [_] (random-uuid)) (range 201))]
    (support/with-server
      (fn [request] {:body (mapv (fn [id] {:userId id :label "Member"}) (get-in request [:body :p_actor_ids]))})
      (fn [{:keys [url requests]}]
        (let [provider (supabase/workspaces {:rest-url url :api-key "public-key"})]
          (is (= {} (workspaces/actor-labels provider "user-token" workspace-id [])))
          (is (empty? @requests))
          (is (= (zipmap (map str ids) (repeat "Member"))
                 (workspaces/actor-labels provider "user-token" workspace-id (conj ids (first ids)))))
          (is (= [200 1] (mapv #(count (get-in % [:body :p_actor_ids])) @requests)))
          (is (every? #(= (str workspace-id) (get-in % [:body :p_workspace_id])) @requests))
          (is (every? #(= "/rpc/workspace_actor_labels" (:path %)) @requests))
          (is (every? #(= "Bearer user-token" (get-in % [:headers "authorization"])) @requests))
          (is (every? #(= "public-key" (get-in % [:headers "apikey"])) @requests)))))))


(deftest adapter-never-renders-unrequested-or-invalid-labels
  (doseq [response [[{:userId (str (random-uuid)) :label "Other workspace"}]
                    [{:label "Missing ID"}]
                    {:unrelated "payload"}]]
    (support/with-server
      (fn [_] {:body response})
      (fn [{:keys [url]}]
        (is (= :invalid-response
               (:code (support/error-data
                        #(workspaces/actor-labels (supabase/workspaces {:rest-url url :api-key "public-key"})
                                                  "user-token" (random-uuid) [(random-uuid)])))))))))
