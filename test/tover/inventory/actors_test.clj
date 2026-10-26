(ns tover.inventory.actors-test
  (:require
    [clojure.test :refer [deftest is]]
    [tover.auth.sessions :as sessions]
    [tover.inventory.actors :as actors]
    [tover.support :as support]))


(defn- context
  {:malli/schema [:=> [:cat :map] :map]}
  [services]
  (merge services (:context services)
         {:request (assoc (support/request services :post "/history")
                          :params {:workspace-id (random-uuid) :access-token "forged-token"})}))


(deftest attribution-uses-current-session-token-and-authorized-workspace
  (let [{:keys [workspaces session-store key] :as services} (support/authenticated-services)
        id (:user-id (:context services))
        proposed (random-uuid)
        calls (atom [])
        directory (support/workspace-provider workspaces
                                              {:actor-labels (fn [token workspace ids]
                                                               (swap! calls conj [token workspace ids])
                                                               {(str id) "Owner Name" (str proposed) "editor@example.test"})})
        record {:actor (str id) :created-by id :proposed-by (str proposed) :reason "Count"}
        actual (first (actors/enrich (assoc (context services) :workspaces directory) [record]))]
    (is (= record (dissoc actual :actor-label :created-by-label :proposed-by-label)))
    (is (= ["Owner Name" "Owner Name" "editor@example.test"]
           ((juxt :actor-label :created-by-label :proposed-by-label) actual)))
    (is (= 1 (count @calls)))
    (is (= (get-in (sessions/session session-store key) [:tokens :access-token]) (ffirst @calls)))
    (is (= (:workspace-id (:context services)) (second (first @calls))))
    (is (= #{id proposed} (set (last (first @calls)))))
    (is (= 2 (count (last (first @calls)))))))


(deftest real-fixture-labels-preserve-identifiers-and-supply-email-fallback
  (let [{:keys [users state] :as services} (support/authenticated-services)
        [owner member other]               (mapv :user-id users)
        records [{:actor (str owner) :created-by (str member)} {:actor (str other)}]]
    (swap! state assoc-in [:users owner :display-name] "  Owner  ")
    (swap! state assoc-in [:users member :display-name] "  ")
    (let [actual (actors/enrich (context services) records)]
      (is (= records (mapv #(dissoc % :actor-label :created-by-label) actual)))
      (is (= "Owner" (get-in actual [0 :actor-label])))
      (is (= "member@example.test" (get-in actual [0 :created-by-label])))
      (is (= "Unavailable user" (get-in actual [1 :actor-label]))))
    (swap! state assoc-in [:users owner :display-name] "Renamed Owner")
    (is (= "Renamed Owner" (:actor-label (first (actors/enrich (context services) records))))
        "Labels are resolved for the current request, not cached across requests")))


(deftest unavailable-provider-and-non-identity-actors-have-safe-presentation
  (let [{:keys [workspaces] :as services} (support/authenticated-services)
        records [{:actor (str (random-uuid)) :proposed-by "old-actor"} {:reason "No actor"}]
        directory (support/workspace-provider workspaces
                                              {:actor-labels (fn [& _] (throw (ex-info "private upstream details" {:status 503})))})
        expected [(assoc (first records) :actor-label "Unavailable user" :proposed-by-label "Unavailable user")
                  (second records)]]
    (is (= expected (actors/enrich (assoc (context services) :workspaces directory) records)))
    (is (= expected (actors/enrich {} records)))
    (is (= [{:actor "system" :actor-label "Unavailable user"}]
           (actors/enrich (assoc (context services) :workspaces directory) [{:actor "system"}])))))


(deftest switching-workspaces-resolves-only-the-selected-workspace-actors
  (let [{:keys [users other-workspace-id] :as services} (support/authenticated-services)
        member (:user-id (second users))
        records [{:actor (str member)}]]
    (is (= "member@example.test" (:actor-label (first (actors/enrich (context services) records)))))
    (is (= "Unavailable user"
           (:actor-label (first (actors/enrich (assoc (context services) :workspace-id other-workspace-id) records)))))))
