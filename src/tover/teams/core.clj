(ns tover.teams.core
  (:require
    [bel.core :refer [defaction defpage]]
    [tover.auth.core :as auth]
    [tover.auth.security :as security]
    [tover.shared.schema :as schema]
    [tover.shared.ui.forms :refer [field form]]
    [tover.workspaces.core :as workspaces])
  (:import
    [clojure.lang ExceptionInfo]))


(defn team-model
  {:malli/schema [:=> [:cat :map] :map]} [{:keys [workspaces request] :as services}]
  (security/require-manager! (:tover/identity request))
  (let [token        (security/access-token services request)
        workspace-id (get-in request [:tover/identity :workspace-id])]
    (workspaces/team workspaces token workspace-id)))


(defn team-content
  {:malli/schema [:=> [:cat :map :map] [:vector :any]]}
  [request {:keys [members invitations]}]
  [:div.identity-content
   [:h2 "Members"]
   (into [:ul]
         (for [member members]
           [:li (:email member) " · " (name (:role member)) " · " (name (:status member))
            (when (= :active (:status member))
              (form request ::recovery "Send recovery email"
                    [:input {:type "hidden" :name "user-id" :value (str (:user-id member))}]))]))
   [:h2 "Invite member"]
   (form request ::invite "Send invitation"
         (field request "email" "Email" "email")
         [:label "Role" [:select {:name "role"}
                         [:option {:value "member" :selected (not= "admin" (get-in request [:params :role]))} "Member"]
                         [:option {:value "admin" :selected (= "admin" (get-in request [:params :role]))} "Admin"]]])
   [:h2 "Invitations"]
   (into [:ul]
         (for [invitation invitations]
           [:li (:email invitation) " · " (name (:status invitation))
            (for [[action title] [[::resend "Resend"] [::cancel "Cancel"]]]
              (form request action title
                    [:input {:type "hidden" :name "invite-id" :value (str (:id invitation))}]))]))])


(defn query-team
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [request] :as context} _input]
  {:request request :model (team-model context)})


(defpage page
  {:route      "/team"
   :title      "Team · Tover"
   :parent     :tover.shared.web.core/shell
   :route-data {:tover/access :manager}
   :query      query-team}
  [{:keys [request model]}]
  {:type :elements
   :html [:section.identity {:aria-labelledby "team-title"}
          [:h1#team-title {:tabindex -1} "Team"]
          (team-content request model)]})


(defn- invite!
  {:malli/schema [:=> [:cat :map :keyword :map] :map]} [{:keys [auth workspaces origin request] :as services} action {:keys [email role invite-id] :or {role :member}}]
  (security/require-manager! (:tover/identity request))
  (let [token        (security/access-token services request)
        workspace-id (get-in request [:tover/identity :workspace-id])
        reservation  (workspaces/manage-invitation! workspaces token
                                                    {:workspace-id workspace-id :action action
                                                     :email        email        :role   role   :invite-id invite-id})]
    (when (not= action :cancel)
      (try
        (try (auth/invite! auth {:email (:email reservation) :redirect-to (str origin "/auth/verify")})
             (catch ExceptionInfo error
               (if (= :email-exists (:code (ex-data error)))
                 (auth/magic-link! auth {:email (:email reservation) :redirect-to (str origin "/auth/verify")})
                 (throw error))))
        (catch ExceptionInfo error
          (if (:uncertain? (ex-data error))
            (security/failure 503 :delivery-unconfirmed "Email delivery is unconfirmed. The invitation is still pending.")
            (do
              (workspaces/revoke-reservation! workspaces token workspace-id (:id reservation))
              (throw error))))))
    {:type :page :id ::page}))


(def InviteInput
  [:map
   [:email schema/Text]
   [:role {:optional true :default :member} [:enum :admin :member]]])


(def InvitationInput
  [:map [:invite-id :uuid]])


(def MemberRecoveryInput
  [:map [:user-id :uuid]])


(defaction invite
  {:schema     InviteInput
   :route-data {:tover/access :manager :bel/error-page ::page :bel/retain ["email" "role"]}}
  [services input]
  (invite! services :create input))


(defaction resend
  {:schema     InvitationInput
   :route-data {:tover/access :manager :bel/error-page ::page}}
  [services input]
  (invite! services :resend input))


(defaction cancel
  {:schema     InvitationInput
   :route-data {:tover/access :manager :bel/error-page ::page}}
  [services input]
  (invite! services :cancel input))


(defaction recovery
  {:schema     MemberRecoveryInput
   :route-data {:tover/access :manager :bel/error-page ::page}}
  [{:keys [auth workspaces origin request] :as services} {:keys [user-id]}]
  (let [reservation (workspaces/request-member-recovery! workspaces (security/access-token services request)
                                                         (get-in request [:tover/identity :workspace-id]) user-id)]
    (auth/recover! auth {:email (:email reservation) :redirect-to (str origin "/auth/verify")})
    {:type :page :id ::page}))
