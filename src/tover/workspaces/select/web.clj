(ns tover.workspaces.select.web
  "Board chapter 07: the workspace chooser and `Select Workspace` for this sign-in. The page also offers
  chapter 04's create form and the sign-out control."
  (:require
    [bel.core :as bel :refer [defaction defpage]]
    [bel.handler :as html]
    [bel.realtime :as realtime]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [tover.identity.access :as access]
    [tover.identity.sign-out.web :as sign-out]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]
    [tover.shared.web.core :as web]
    [tover.workspaces.create.web :as create]
    [tover.workspaces.select.model :as model])
  (:import
    [clojure.lang ExceptionInfo]))


(defn reference
  "A short stable reference that tells apart workspaces with the same name."
  {:malli/schema [:=> [:cat :uuid] :string]}
  [workspace-id]
  (subs (str workspace-id) 0 8))


(defn workspace-content
  {:malli/schema [:=> [:cat :map] schema/Hiccup]}
  [request]
  (let [{:keys [memberships workspace-id]} (:tover/identity request)
        repeated (into #{} (keep (fn [[name n]] (when (> n 1) name))) (frequencies (map :name memberships)))
        return-to (access/local-path (get-in request [:params :return-to]))
        ;; A link may name a workspace (an accepted invitation's "Open workspace"); it is offered, never selected.
        target (let [id (parse-uuid (str (get-in request [:params :workspace-id])))]
                 (some #(when (= id (:workspace-id %)) %) memberships))]
    [:div.management-content
     (when target
       [:section.management-section {:aria-label (i18n/t "Open workspace") :data-target-workspace-id (str (:workspace-id target))}
        [:p [:span.management-name (:name target)]]
        (create/select-form request (:workspace-id target) return-to (i18n/t "Open workspace"))])
     [:details.management-disclosure {:open (or (empty? memberships) (contains? (:params request) :name))}
      [:summary (i18n/t "Create workspace")]
      (create/create-form {:request request})]
     [:section.management-section {:aria-label (i18n/t "Workspaces")}
      (if (seq memberships)
        [:div.management-table-wrap
         [:table.management-table
          [:thead
           [:tr
            [:th {:scope "col"} (i18n/t "Workspace")]
            [:th.management-role {:scope "col"} (i18n/t "Role")]
            [:th {:scope "col"} (i18n/t "Actions")]]]
          (into [:tbody]
                (for [membership memberships
                      :let       [role (i18n/t (str/capitalize (name (:role membership))))]]
                  [:tr {:data-workspace-id (str (:workspace-id membership))}
                   [:td.management-primary
                    [:span.management-name (:name membership)]
                    (when (= workspace-id (:workspace-id membership))
                      [:span.management-current (i18n/t "Current workspace")])
                    (when (contains? repeated (:name membership))
                      [:span.management-secondary (reference (:workspace-id membership))])
                    [:span.management-mobile-meta role]]
                   [:td.management-role role]
                   [:td.management-actions
                    (create/select-form request (:workspace-id membership) return-to (i18n/t "Open"))]]))]]
        [:div.management-empty
         [:p (i18n/t "No workspaces yet.")]
         [:p (i18n/t "Create a workspace or open an invitation you received.")]])]
     [:footer.management-footer
      (sign-out/sign-out-form {:request request})]]))


(defpage page
  {:route      "/workspace"
   :title      "Workspaces · Tover"
   :parent     :tover.shared.web.core/shell
   :route-data {:tover/access :authenticated}
   :query      web/query-request}
  [request]
  {:type :elements
   :html [:section.management-page {:aria-labelledby "workspace-title"}
          [:header.management-header
           [:h1#workspace-title {:tabindex -1} (i18n/t "Workspaces")]]
          (workspace-content request)]})


(defn- error-status
  {:malli/schema [:=> [:cat [:fn #(instance? Throwable %)]] :int]}
  [error]
  (case (:type (ex-data error))
    (:workspace-unavailable :forbidden) 403
    (:stale-selection :idempotency-conflict) 409
    :validation 422
    500))


(defn- error-message
  {:malli/schema [:=> [:cat [:fn #(instance? Throwable %)]] :string]}
  [error]
  (case (:type (ex-data error))
    (:workspace-unavailable :forbidden) (i18n/t "This workspace is not accessible.")
    (:stale-selection :idempotency-conflict) (i18n/t "Workspace changed. Reload this page before continuing.")
    (i18n/error-message error)))


(defaction select
  "Select an explicit, currently authorized workspace for this sign-in, then open the requested screen (Home
  by default). A rejection keeps the accepted selection and shows the chooser again; streams of the previous
  selection close so its private updates cannot reach the new screen."
  {:schema     [:map [:workspace-id :uuid] [:expected-version :uuid] [:command-id [:string {:min 1 :max 100}]]
                [:return-to {:optional true} :string]]
   :route-data {:tover/access :authenticated}}
  [{:keys [request realtime] :as context} {:keys [workspace-id expected-version command-id return-to]}]
  (try
    (let [receipt (runtime/execute! context ::model/select-workspace
                                    {:command-id command-id :input {:workspace-id workspace-id :expected-version expected-version}})]
      (when (and realtime (seq (:event-ids receipt)))
        (realtime/close-session! realtime (get-in request [:tover/identity :auth-session-id])))
      {:status 303 :headers {"location" (access/local-path return-to)} :body ""})
    (catch ExceptionInfo error
      (let [response (html/error-page-response (assoc request :tover/error (error-message error)) ::page {})]
        (assoc response :status (error-status error))))))
