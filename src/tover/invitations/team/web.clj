(ns tover.invitations.team.web
  "The Team screen: members, the latest offer per recipient with separate offer and delivery states, Invite
  Teammate, and Resend/Cancel on eligible offers. Manager actions share `execute!` and answer with this page."
  (:require
    [bel.core :as bel :refer [defpage]]
    [bel.runtime :as runtime]
    [bel.validation :as validation]
    [clojure.string :as str]
    [com.brunobonacci.mulog :as mu]
    [tover.identity.access :as access]
    [tover.invitations.issue.model :as issue]
    [tover.invitations.status.model :as status]
    [tover.invitations.team.model :as model]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]
    [tover.shared.ui.forms :refer [form]])
  (:import
    [clojure.lang ExceptionInfo]
    [java.time Instant ZoneOffset]
    [java.time.format DateTimeFormatter]))


;; Shared presentation


(def ^:private time-format
  (.withZone (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm 'UTC'") ZoneOffset/UTC))


(defn moment
  "An instant as a <time> element."
  {:malli/schema [:=> [:cat [:fn #(instance? Instant %)]] schema/Hiccup]}
  [instant]
  [:time {:datetime (str instant)} (.format ^DateTimeFormatter time-format instant)])


(def ^:private role-labels
  {"owner" "Owner" "admin" "Admin" "member" "Member"})


(defn role-label
  {:malli/schema [:=> [:cat [:maybe :string]] :string]}
  [role]
  (i18n/t (get role-labels role "Member")))


(def ^:private offer-labels
  {"pending"     "Pending"                "accepted" "Accepted" "revoked" "Cancelled" "expired" "Expired"
   "invalidated" "Invalidated by removal"})


(defn offer-label
  {:malli/schema [:=> [:cat :string] :string]}
  [state]
  (i18n/t (get offer-labels state state)))


(def ^:private delivery-labels
  {"queued"              "Queued"                "submitted" "Submitted" "failed" "Failed" "unconfirmed" "Unconfirmed"
   "stopped"             "Stopped"
   "stopped-unconfirmed" "Stopped — Unconfirmed"})


(defn delivery-details
  "Delivery state, kept apart from the offer: retries, reconciliation and the shared resend time."
  {:malli/schema [:=> [:cat [:maybe status/Delivery] [:fn #(instance? Instant %)]] schema/Hiccup]}
  [delivery now]
  (if-not delivery
    [:span.management-secondary (i18n/t "No delivery requested")]
    (let [{:keys [problem attempts-remaining next-attempt-at reconcile-by next-resend-at active?]} delivery]
      [:span.management-delivery
       (i18n/t "Delivery: {status}" {:status (i18n/t (get delivery-labels (:status delivery)))})
       (when (= "permanent" problem)
         [:span.management-secondary (i18n/t "The address was rejected. Check it, then cancel and invite again.")])
       (when (and (= "failed" (:status delivery)) (not= "permanent" problem))
         [:span.management-secondary (i18n/t "Attempts left: {count}" {:count attempts-remaining})])
       (when next-attempt-at
         [:span.management-secondary (i18n/t "Next attempt") " " (moment next-attempt-at)])
       (when reconcile-by
         [:span.management-secondary (i18n/t "Waiting for the email service until") " " (moment reconcile-by)])
       (when (= "stopped-unconfirmed" (:status delivery))
         [:span.management-secondary (i18n/t "An earlier copy may still arrive. Resending can deliver another copy of the same invitation.")])
       (when (and (not active?) (.isBefore ^Instant now next-resend-at))
         [:span.management-secondary (i18n/t "Resend available at") " " (moment next-resend-at)])])))


;; Commands from the Team screen


(def ^:private problems
  "Business rejections as page errors; the message is the command's catalog message."
  {:already-member     409 :member-suspended       409 :invitation-exists      409 :removal-review-required 409
   :invitation-changed 409 :invitation-invalidated 409 :invitation-not-pending 409 :permanent-failure       409
   :resend-cooldown    429 :conflict               409 :identity-conflict      409})


(defn execute!
  "Run a manager command for the selected workspace. The form's workspace must still be the selected one;
  rejections become errors the Team page shows."
  {:malli/schema [:=> [:cat :map :qualified-keyword :map] :any]}
  [{:keys [request] :as context} command {:keys [command-id workspace-id] :as input}]
  (when-not (= workspace-id (get-in request [:tover/identity :workspace-id]))
    (access/failure 409 :workspace-changed "Workspace changed. Reload this page before continuing."))
  (try
    (runtime/execute! context command {:command-id command-id :input (dissoc input :command-id :workspace-id)})
    (catch ExceptionInfo error
      (if-let [status (problems (:type (ex-data error)))]
        (throw (ex-info (if (contains? #{:conflict :identity-conflict} (:type (ex-data error)))
                          "This invitation changed. Reload the page."
                          (.getMessage error))
                        {:type :invitation :status status :code (:type (ex-data error))}
                        error))
        (throw error)))))


(def Command
  "Fields every manager form posts: a retry-stable command id and the workspace it was rendered for."
  [:map [:command-id [:string {:min 1 :max 100}]] [:workspace-id :uuid]])


(defn command-fields
  {:malli/schema [:=> [:cat :map] [:vector :any]]}
  [request]
  [[:input {:type "hidden" :name "command-id" :value (str (random-uuid))}]
   [:input {:type "hidden" :name "workspace-id" :value (str (get-in request [:tover/identity :workspace-id]))}]])


(defn after
  "Back to the Team screen focused on the recipient of offer `invitation-id`. The URL carries only the offer id:
  a recipient's email never goes into URLs, logs or browser history."
  {:malli/schema [:=> [:cat [:maybe :uuid]] :map]}
  [invitation-id]
  (cond-> {:type :page :id ::team}
    invitation-id (assoc :input {:invitation (str invitation-id)})))


;; Page


(defn- role-select
  {:malli/schema [:=> [:cat [:maybe :string]] schema/Hiccup]}
  [selected]
  [:label (i18n/t "Role")
   [:select {:name "role"}
    [:option {:value "member" :selected (not= "admin" selected)} (i18n/t "Member")]
    [:option {:value "admin" :selected (= "admin" selected)} (i18n/t "Admin")]]])


(defn- offer-actions
  {:malli/schema [:=> [:cat :map model/Offer] schema/Hiccup]}
  [request {:keys [invitation-id can-resend? can-cancel?]}]
  [:td.management-actions
   (when can-resend?
     (apply form request :tover.invitations.resend.web/resend (i18n/t "Resend")
            [:input {:type "hidden" :name "invitation-id" :value (str invitation-id)}]
            (command-fields request)))
   (when can-cancel?
     [:div.management-action-danger
      (apply form request :tover.invitations.cancel.web/cancel (i18n/t "Cancel")
             [:input {:type "hidden" :name "invitation-id" :value (str invitation-id)}]
             (command-fields request))])])


(defn- members-section
  {:malli/schema [:=> [:cat :map [:vector model/Member]] schema/Hiccup]}
  [request members]
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
                   :let   [role (role-label (:role member))
                           state (i18n/t (str/capitalize (:status member)))
                           named? (not (str/blank? (:name member)))]]
               [:tr
                [:td.management-primary
                 [:span.management-name (if named? (:name member) (:email member))]
                 (when named? [:span.management-secondary (:email member)])
                 [:span.management-mobile-meta role " · " state]]
                [:td.management-role role]
                [:td.management-status state]
                [:td.management-actions
                 ;; A removed member rejoins only through a fresh offer reviewed after the latest removal.
                 (when (= "removed" (:status member))
                   [:details.management-disclosure
                    [:summary (i18n/t "Invite again")]
                    [:div.management-form
                     (apply form request :tover.invitations.issue.web/invite (i18n/t "Send invitation")
                            [:input {:type "hidden" :name "email" :value (:email member)}]
                            [:input {:type "hidden" :name "reviewed-removal" :value (str (:removed-at member))}]
                            [:p.management-secondary (i18n/t "Removed on") " " (moment (:removed-at member))]
                            (role-select nil)
                            (command-fields request))]])]]))]]
     [:p.management-empty (i18n/t "No members yet.")])])


(defn- invitations-section
  {:malli/schema [:=> [:cat :map [:vector model/Offer] validation/Instant] schema/Hiccup]}
  [request invitations now]
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
                [:th {:scope "col"} (i18n/t "Delivery")]
                [:th {:scope "col"} (i18n/t "Actions")]]]
       (into [:tbody]
             (for [invitation invitations
                   :let       [role (role-label (:role invitation))
                               state (offer-label (:state invitation))]]
               [:tr {:data-invitation-id (str (:invitation-id invitation))}
                [:td.management-primary
                 [:span.management-name (:email invitation)]
                 [:span.management-secondary (i18n/t "Expires") " " (moment (:expires-at invitation))]
                 [:span.management-mobile-meta role " · " state]]
                [:td.management-role role]
                [:td.management-status state]
                [:td (delivery-details (:delivery invitation) now)]
                (offer-actions request invitation)]))]]
     [:p.management-empty (i18n/t "No invitations yet.")])])


(defn- recipient-status
  "The known result for one recipient, shown even when the full team cannot load."
  {:malli/schema [:=> [:cat [:maybe :map] validation/Instant] [:maybe schema/Hiccup]]}
  [offer now]
  (when offer
    [:section.management-section {:role "status" :aria-live "polite"}
     [:p.management-name (:email offer) " · " (role-label (:role offer)) " · "
      (i18n/t "Invitation: {status}" {:status (offer-label (status/effective-status offer now))})]
     [:p.management-secondary (i18n/t "Expires") " " (moment (:expires-at offer))]
     (delivery-details (:delivery offer) now)]))


(defn- attempt
  "Run a read; a failure becomes {:failed? true} so the page offers Retry instead of an empty team."
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (try (f)
       (catch ExceptionInfo error
         (if (:status (ex-data error)) (throw error) (do (mu/log ::read-failed :error-type (:type (ex-data error))) {:failed? true})))
       (catch Exception error
         (mu/log ::read-failed :error-type (.getName (class error)))
         {:failed? true})))


(defn query-team
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [request] :as context} {:keys [invitation]}]
  (let [invitation-id (when (string? invitation) (parse-uuid invitation))]
    {:request   request
     :now       (Instant/now)
     :team      (attempt #(runtime/read-model context ::model/workspace-team {}))
     ;; The recipient's current offer; an unknown or foreign id shows nothing.
     :recipient (when invitation-id
                  (attempt #(when-let [email (:email (runtime/read-model context ::issue/invitations {:invitation-id invitation-id}))]
                              (runtime/read-model context ::status/invitation-status {:email (issue/normalize email)}))))}))


(defn- retry
  {:malli/schema [:=> [:cat :map] schema/Hiccup]}
  [request]
  [:p.management-empty {:role "alert"}
   (i18n/t "The team could not be loaded.") " "
   [:a {:href (str (:uri request) (when-let [query (:query-string request)] (str "?" query)))} (i18n/t "Retry")]])


(defpage team
  {:route      "/team"
   :title      "Team · Tover"
   :parent     :tover.shared.web.core/shell
   :route-data {:tover/access :manager}
   :query      query-team}
  [{:keys [request team recipient now]}]
  (let [params    (:params request)
        workspace (some #(when (= (:workspace-id %) (get-in request [:tover/identity :workspace-id])) %)
                        (get-in request [:tover/identity :memberships]))]
    {:type :elements
     :html [:section.management-page {:aria-labelledby "team-title"}
            [:header.management-header
             [:h1#team-title {:tabindex -1} (i18n/t "Team")]
             ;; The short id tells apart workspaces with the same name.
             [:p.management-subtitle (:name workspace) " · " (subs (str (:workspace-id workspace)) 0 8)]]
            [:div.management-content
             [:details.management-disclosure {:open (or (contains? params :email) (contains? params :role))}
              [:summary (i18n/t "Invite member")]
              [:div.management-form
               (apply form request :tover.invitations.issue.web/invite (i18n/t "Send invitation")
                      [:label (i18n/t "Email")
                       [:input {:name "email" :type "email" :required true :value (get params :email "")}]]
                      (role-select (get params :role))
                      (command-fields request))
               [:p.management-secondary (i18n/t "The invitation is valid for 7 days.")]]]
             (cond
               (:failed? recipient) (retry request)
               recipient (recipient-status recipient now))
             (if (:failed? team)
               (retry request)
               (list (members-section request (:members team))
                     (invitations-section request (:invitations team) now)))]]}))
