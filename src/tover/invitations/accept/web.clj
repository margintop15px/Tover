(ns tover.invitations.accept.web
  "Review Workspace Invitation (`/invitations/{id}`, the email's link), explicit Accept or Not Now, and the
  Workspace Joined outcome (`/invitations/{id}/joined`). Opening the link changes nothing; accepting never
  selects a workspace."
  (:require
    [bel.core :as bel :refer [defaction defpage]]
    [bel.runtime :as runtime]
    [tover.identity.sign-out.web :as sign-out]
    [tover.invitations.accept.model :as model]
    [tover.invitations.team.web :as team]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]
    [tover.shared.ui.forms :refer [form]]
    [tover.workspaces.create.web :as create])
  (:import
    [clojure.lang ExceptionInfo]))


(def destination-key
  "Session key holding the invitation a signed-out visit should return to (written by sign-in and signup,
  chunk A). Not Now and Back to Workspaces clear it; nothing here accepts on sign-in."
  :identity/return-to)


(defn review-path
  "The review route for an invitation; the invitation email links here."
  {:malli/schema [:=> [:cat :uuid] :string]}
  [invitation-id]
  (str "/invitations/" invitation-id))


(def ^:private problems
  {"invitation-unavailable" "This invitation is not available to this account."
   "invitation-changed"     "This invitation changed. Review it again."
   "invitation-not-pending" "This invitation can no longer be accepted."
   "member-suspended"       "Your access to this workspace is suspended. Ask an owner to reinstate it."
   "profile-incomplete"     "Complete your profile first."})


(def ^:private states
  {"accepted"    "This invitation was already accepted."
   "revoked"     "This invitation was cancelled. Ask the workspace owner or admin for a new one."
   "expired"     "This invitation has expired. Ask the workspace owner or admin for a new one."
   "invalidated" "This invitation is no longer valid. Ask the workspace owner or admin for a new one."})


(defn- attempt
  "The review, or {:failed? true} when it could not be read: Retry, never a guessed state."
  {:malli/schema [:=> [:cat :map :uuid] :map]}
  [context invitation-id]
  (try (runtime/read-model context ::model/my-invitation {:invitation-id invitation-id})
       (catch ExceptionInfo error
         (if (:status (ex-data error)) (throw error) {:failed? true :invitation-id invitation-id}))))


(defn query-review
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [request] :as context} {:keys [invitation-id problem]}]
  {:request request
   :review  (attempt context invitation-id)
   :problem (get problems problem)})


(defn- leave-form
  {:malli/schema [:=> [:cat :map :string] schema/Hiccup]}
  [request label]
  (form request (bel/action-url ::leave) label))


(defn- open-button
  "Select this workspace and open it: one explicit click (07), never a selection on page load."
  {:malli/schema [:=> [:cat :map :uuid] schema/Hiccup]}
  [request workspace-id]
  (create/select-form request workspace-id "/" (i18n/t "Open workspace")))


(defn- details
  {:malli/schema [:=> [:cat model/Review] schema/Hiccup]}
  [{:keys [workspace-id workspace-name inviter role expires-at]}]
  [:dl.invitation-details
   [:dt (i18n/t "Workspace")] [:dd workspace-name " " [:span (subs (str workspace-id) 0 8)]]
   (when inviter (list [:dt (i18n/t "Invited by")] [:dd inviter]))
   [:dt (i18n/t "Role")] [:dd (team/role-label role)]
   [:dt (i18n/t "Expires")] [:dd (team/moment expires-at)]])


(defn- card
  "The offer in one box: its details or a message, then the actions in one row."
  {:malli/schema [:=> [:cat [:* :any]] schema/Hiccup]}
  [& body]
  (into [:div.management-form] (remove nil? body)))


(defn- actions
  {:malli/schema [:=> [:cat [:* :any]] schema/Hiccup]}
  [& buttons]
  (into [:div.management-buttons] (remove nil? buttons)))


(defn- retry
  {:malli/schema [:=> [:cat :map] schema/Hiccup]}
  [request]
  [:p {:role "alert"} (i18n/t "The invitation could not be loaded.") " " [:a {:href (:uri request)} (i18n/t "Retry")]])


(defpage review
  {:route      "/invitations/:invitation-id"
   :title      "Invitation · Tover"
   :parent     :tover.shared.web.core/shell
   :schema     [:map [:invitation-id :uuid] [:problem {:optional true} :string]]
   :route-data {:tover/access :authenticated}
   :query      query-review}
  [{:keys [request review problem]}]
  (let [{:keys [invitation-id available? state membership workspace-id current-role]} review]
    {:type :elements
     :html [:section.management-page {:aria-labelledby "invitation-title"}
            [:header.management-header [:h1#invitation-title {:tabindex -1} (i18n/t "Workspace invitation")]]
            (when problem [:p {:role "alert"} (i18n/t problem)])
            (cond
              (:failed? review)
              (retry request)

              (not available?)
              (card
                [:p (i18n/t "This invitation is not available to {email}." {:email (get-in request [:tover/identity :user :email] "")})]
                [:p (i18n/t "This invitation may have been sent to another email.") " "
                 (i18n/t "Signing out leads to the sign-in page. Sign in with the right account to return to this invitation.")]
                (actions (sign-out/sign-out-form {:request request :return-to (review-path invitation-id)})
                         (leave-form request (i18n/t "Back to workspaces"))))

              (= "active" membership)
              (card
                (details review)
                [:p {:role "status"} (i18n/t "You are already a member of this workspace as {role}." {:role (team/role-label current-role)})]
                (actions (open-button request workspace-id) (leave-form request (i18n/t "Back to workspaces"))))

              (= "suspended" membership)
              (card
                (details review)
                [:p (i18n/t "Your access to this workspace is suspended. Ask an owner to reinstate it.")]
                (actions (leave-form request (i18n/t "Back to workspaces"))))

              (model/acceptable? review)
              (card
                (details review)
                (actions
                  (form request (bel/action-url ::accept) (i18n/t "Accept invitation")
                        [:input {:type "hidden" :name "invitation-id" :value (str invitation-id)}]
                        [:input {:type "hidden" :name "workspace-id" :value (str workspace-id)}]
                        [:input {:type "hidden" :name "role" :value (:role review)}]
                        [:input {:type "hidden" :name "command-id" :value (str (random-uuid))}])
                  (leave-form request (i18n/t "Not now"))))

              :else
              (card
                (details review)
                [:p (i18n/t (get states state))]
                (actions (leave-form request (i18n/t "Back to workspaces")))))]}))


(defaction accept
  "Business rejections go back to the review, which shows the current offer and the reason."
  {:schema     (conj model/Reviewed [:command-id [:string {:min 1 :max 100}]])
   :route-data {:tover/access :authenticated}}
  [_ {:keys [command-id invitation-id] :as input}]
  (let [path-params {:invitation-id (str invitation-id)}]
    {:type  :command :id ::model/accept-workspace-invitation :command-id command-id
     :input (dissoc input :command-id)
     :then  {:type :page :id ::joined :path-params path-params}
     :catch {:type :page :id ::review :path-params path-params}}))


(defaction leave
  {:schema     [:map]
   :route-data {:tover/access :authenticated}}
  [{:keys [request]} _]
  ;; bel.session merges the response session into the current one, so the destination is cleared with nil.
  (cond-> {:status 303 :headers {"location" "/workspace"} :body ""}
    (some? (get-in request [:session destination-key])) (assoc :session {destination-key nil})))


(defn query-joined
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [request] :as context} {:keys [invitation-id]}]
  {:request request :review (attempt context invitation-id)})


(defpage joined
  {:route      "/invitations/:invitation-id/joined"
   :title      "Workspace joined · Tover"
   :parent     :tover.shared.web.core/shell
   :schema     [:map [:invitation-id :uuid]]
   :route-data {:tover/access :authenticated}
   :query      query-joined}
  [{:keys [request review]}]
  (let [{:keys [invitation-id state membership workspace-id workspace-name current-role]} review]
    {:type :elements
     :html [:section.management-page {:aria-labelledby "joined-title"}
            [:header.management-header [:h1#joined-title {:tabindex -1} (i18n/t "Workspace invitation")]]
            (cond
              (:failed? review)
              [:div [:p (i18n/t "Your acceptance is saved. Current access could not be loaded.")] (retry request)]

              (and (= "accepted" state) (= "active" membership))
              [:div.management-form {:role "status"}
               [:p (i18n/t "You joined {workspace} as {role}." {:workspace workspace-name :role (team/role-label current-role)})]
               (actions (open-button request workspace-id) (leave-form request (i18n/t "Back to workspaces")))]

              (= "accepted" state)
              (card
                [:p (i18n/t "Your acceptance was recorded, but you no longer have access to this workspace.")]
                (actions (leave-form request (i18n/t "Back to workspaces"))))

              :else
              [:p [:a {:href (review-path invitation-id)} (i18n/t "Review the invitation")]])]}))
