(ns tover.workspaces.create.model
  (:require
    [bel.core :refer [defcommand defevent defmodel defreadmodel]]
    [bel.runtime :as runtime]
    [bel.validation :as validation]
    [clojure.string :as str]))


(def WorkspaceName
  [:string {:min           1
            :max           200
            :decode/string (fn [value]
                             (if (string? value) (str/trim value) value))}])


(def CreateWorkspace
  [:map
   [:workspace-id :uuid]
   [:name WorkspaceName]])


(def CreationQuery
  [:map
   [:command-id [:string {:min 1 :max 100}]]
   [:workspace-id :uuid]])


(def Profile
  [:map
   [:user-id :uuid]
   [:display-name {:optional true} :string]])


(def Workspace
  [:map
   [:workspace-id :uuid]
   [:name :string]
   [:created-by {:bel/ref ::profiles} :uuid]
   [:created-at validation/Instant]])


(def Membership
  [:map
   [:membership-id :uuid]
   [:workspace-id {:bel/ref ::workspaces} :uuid]
   [:user-id {:bel/ref ::profiles} :uuid]
   [:role :string]
   [:status :string]])


(defmethod runtime/policy ::ordinary-user
  [_ {:keys [confirmed? password-required? actor scope]} _]
  (boolean (and confirmed? (not password-required?) (= actor (:user-id scope)))))


(defevent workspace-created
  {:schema Workspace})


(defevent workspace-owner-assigned
  {:schema Membership})


(defmodel profiles
  {:id     :user-id
   :store  :directory
   :schema Profile
   :policy ::ordinary-user})


(defmodel workspaces
  {:id     :workspace-id
   :store  :directory
   :schema Workspace
   :policy ::ordinary-user
   :events {::workspace-created {:set [:name :created-by :created-at]}}})


(defmodel memberships
  {:id     :membership-id
   :store  :directory
   :schema Membership
   :policy ::ordinary-user
   :events {::workspace-owner-assigned {:set [:workspace-id :user-id :role :status]}}})


(defcommand create-workspace
  {:scope :user :input CreateWorkspace :policy ::ordinary-user
   :cofx  [[:profile ::profiles (fn [{:keys [actor]} _] {:user-id actor})]
           [:membership-id :bel/uuid]]
   :emits #{::workspace-created ::workspace-owner-assigned}}
  [{:keys [actor now profile membership-id]} {:keys [workspace-id name]}]
  (when (or (str/blank? (:display-name profile)) (> (count (:display-name profile)) 100))
    (throw (ex-info "Complete your profile first." {:type :profile-incomplete})))
  {:events [{:type ::workspace-created
             :data {:workspace-id workspace-id
                    :name         name
                    :created-by   actor
                    :created-at   now}}
            {:type ::workspace-owner-assigned
             :data {:membership-id membership-id
                    :workspace-id  workspace-id
                    :user-id       actor
                    :role          "owner"
                    :status        "active"}}]})


(defreadmodel created-workspace
  {:scope  :user :input CreationQuery
   :output [:map [:workspace-id :uuid] [:name :string] [:can-open? :boolean]
            [:role {:optional true} :string]]
   :policy ::ordinary-user
   :cofx   [[:creation :postgres/receipt (fn [_ input] (select-keys input [:command-id]))]
            [:membership :tover.workspaces.persistence/membership (fn [_ input] (select-keys input [:workspace-id]))]]}
  [{:keys [creation membership]} {:keys [workspace-id]}]
  (when-not (and (= ::create-workspace (get-in creation [:request :command]))
                 (= workspace-id (get-in creation [:request :input :workspace-id])))
    (throw (ex-info "Creation has not been confirmed. Retry the original request."
                    {:type :creation-unconfirmed})))
  (cond-> {:workspace-id workspace-id
           :name         (get-in creation [:request :input :name])
           :can-open?    (= "active" (:status membership))}
    (= "active" (:status membership))
    (assoc :role (:role membership))))
