(ns tover.teams.core
  (:require
    [bel.core :refer [defaction defpage]]
    [clojure.string :as str]
    [tover.auth.core :as auth]
    [tover.auth.security :as security]
    [tover.shared.i18n :as i18n]
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
  [:div.management-content
   [:details.management-disclosure
    {:open (or (contains? (:params request) :email) (contains? (:params request) :role))}
    [:summary (i18n/t "Invite member")]
    [:div.management-form
     (form request ::invite (i18n/t "Send invitation")
           (field request "email" (i18n/t "Email") "email")
           [:label (i18n/t "Role") [:select {:name "role"}
                                    [:option {:value "member" :selected (not= "admin" (get-in request [:params :role]))} (i18n/t "Member")]
                                    [:option {:value "admin" :selected (= "admin" (get-in request [:params :role]))} (i18n/t "Admin")]]])]]
   [:section.management-section {:aria-labelledby "team-members-title"}
    [:div.management-section-heading
     [:h2#team-members-title (i18n/t "Members")]
     [:span.management-count (count members)]]
    (if (seq members)
      [:div.management-table-wrap
       [:table.management-table {:aria-labelledby "team-members-title"}
        [:thead [:tr
                 [:th {:scope "col"} (i18n/t "Member")]
                 [:th.management-role {:scope "col"} (i18n/t "Role")]
                 [:th.management-status {:scope "col"} (i18n/t "Status")]
                 [:th {:scope "col"} (i18n/t "Actions")]]]
        (into [:tbody]
              (for [member members
                    :let   [role (i18n/t (str/capitalize (name (:role member))))
                            status (i18n/t (str/capitalize (name (:status member))))
                            named? (not (str/blank? (:name member)))]]
                [:tr
                 [:td.management-primary
                  [:span.management-name (if named? (:name member) (:email member))]
                  (when named? [:span.management-secondary (:email member)])
                  [:span.management-mobile-meta role " · " status]]
                 [:td.management-role role]
                 [:td.management-status status]
                 [:td.management-actions
                  (when (= :active (:status member))
                    (form request ::recovery (i18n/t "Send recovery email")
                          [:input {:type "hidden" :name "user-id" :value (str (:user-id member))}]))]]))]]
      [:p.management-empty (i18n/t "No members yet.")])]
   [:section.management-section {:aria-labelledby "team-invitations-title"}
    [:div.management-section-heading
     [:h2#team-invitations-title (i18n/t "Invitations")]
     [:span.management-count (count invitations)]]
    (if (seq invitations)
      [:div.management-table-wrap
       [:table.management-table {:aria-labelledby "team-invitations-title"}
        [:thead [:tr
                 [:th {:scope "col"} (i18n/t "Email")]
                 [:th.management-role {:scope "col"} (i18n/t "Role")]
                 [:th.management-status {:scope "col"} (i18n/t "Status")]
                 [:th {:scope "col"} (i18n/t "Actions")]]]
        (into [:tbody]
              (for [invitation invitations
                    :let       [role (i18n/t (str/capitalize (name (or (:role invitation) :member))))
                                status (i18n/t (str/capitalize (name (:status invitation))))]]
                [:tr
                 [:td.management-primary
                  [:span.management-name (:email invitation)]
                  [:span.management-mobile-meta role " · " status]]
                 [:td.management-role role]
                 [:td.management-status status]
                 [:td.management-actions
                  (form request ::resend (i18n/t "Resend")
                        [:input {:type "hidden" :name "invite-id" :value (str (:id invitation))}])
                  [:div.management-action-danger
                   (form request ::cancel (i18n/t "Cancel")
                         [:input {:type "hidden" :name "invite-id" :value (str (:id invitation))}])]]]))]]
      [:p.management-empty (i18n/t "No invitations yet.")])]])


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
   :html [:section.management-page {:aria-labelledby "team-title"}
          [:header.management-header
           [:h1#team-title {:tabindex -1} (i18n/t "Team")]
           [:p.management-subtitle
            (:name (first (filter #(= (:workspace-id %) (get-in request [:tover/identity :workspace-id]))
                                  (get-in request [:tover/identity :memberships]))))]]
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
