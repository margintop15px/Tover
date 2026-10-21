(ns tover.workspaces.web-test
  (:require
    [bel.core :as bel]
    [clojure.test :refer [deftest is]]
    [tover.support :as support]))


(deftest inaccessible-workspace-and-stale-team-form-cannot-retarget-mutations
  (support/with-application
    (fn [{:keys [browser users state other-workspace-id]}]
      (let [{:keys [request!]} browser]
        (support/login! browser (first users))
        (request! :get "/team")
        (let [old-context (:context @(:state browser))]
          (is (= 403 (:status (request! :post (bel/action-url :tover.workspaces.web/select-workspace) {"workspace-id" (str (random-uuid))}))))
          (is (= 303 (:status (request! :post (bel/action-url :tover.workspaces.web/select-workspace) {"workspace-id" (str other-workspace-id)}))))
          (let [response (request! :post "/action/tover.teams.core/invite" {"email" "stale@example.test" "role" "member" "_tover-context" old-context})]
            (is (= 409 (:status response)))
            (is (= "workspace-changed" (get-in response [:headers "x-tover-error"])))
            (is (empty? (:invitations @state)))))))))
