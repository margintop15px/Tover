(ns bel-postgres.adapter-test
  (:require
    [bel-postgres.adapter :as postgres]
    [clojure.test :refer [deftest is]]))


(deftest bindings-are-snake-case-model-and-field-names
  (is (= {:table   :invitation_deliveries
          :columns {:delivery-id :delivery_id :workspace-id :workspace_id :attempts :attempts}}
         (postgres/table-binding {:model-id :tover.invitations.issue.model/invitation-deliveries
                                  :fields   {:delivery-id {} :workspace-id {} :attempts {}}}))))
