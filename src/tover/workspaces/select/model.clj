(ns tover.workspaces.select.model
  "Board chapter 07: the workspace selected by one Tover sign-in. The selection lives on the session row;
  its `version` changes with every accepted selection, so forms and streams of the old one go stale."
  (:require
    [bel.core :refer [defcommand defevent defmodel]]
    [bel.runtime :as runtime]))


(def Selection
  [:map
   [:session-id :uuid]
   [:workspace-id {:optional true} :uuid]
   [:version :uuid]])


(def SelectWorkspace
  [:map
   [:workspace-id :uuid]
   [:expected-version :uuid]])


(defmethod runtime/policy ::own-sign-in
  [_ {{:keys [session-id confirmed? password-required?]} :principal scope :scope} input]
  (boolean (and confirmed? (not password-required?) session-id (= session-id (:session-id scope))
                (or (not (contains? input :session-id)) (= session-id (:session-id input))))))


(defevent workspace-selected
  {:schema [:map [:session-id :uuid] [:workspace-id :uuid] [:version :uuid]]})


(defmodel selections
  {:id     :session-id
   :store  :directory
   :schema Selection
   :policy ::own-sign-in
   :events {::workspace-selected {:set [:workspace-id :version]}}})


(defcommand select-workspace
  {:scope  :session
   :input  SelectWorkspace
   :policy ::own-sign-in
   :cofx   [[:selection ::selections (fn [{:keys [scope]} _] (select-keys scope [:session-id]))]
            [:membership :tover.workspaces.persistence/membership (fn [_ input] (select-keys input [:workspace-id]))]
            [:version :bel/uuid]]
   :emits  #{::workspace-selected}}
  [{:keys [selection membership version]} {:keys [workspace-id expected-version]}]
  (cond
    (not= "active" (:status membership))
    (throw (ex-info "This workspace is not accessible." {:type :workspace-unavailable}))

    (= workspace-id (:workspace-id selection))
    {}

    (not= expected-version (:version selection))
    (throw (ex-info "Workspace changed. Reload this page before continuing."
                    {:type :stale-selection :latest (select-keys selection [:workspace-id])}))

    :else
    {:events [{:type ::workspace-selected
               :data {:session-id (:session-id selection) :workspace-id workspace-id :version version}}]}))
