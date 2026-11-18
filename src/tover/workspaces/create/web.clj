(ns tover.workspaces.create.web
  "Board chapter 04 on the workspace page: the create form, the accepted outcome and its next actions.
  Creation never selects; Open Workspace and Invite Teammates select the new workspace explicitly (07)."
  (:require
    [bel.core :as bel :refer [defaction defview]]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [com.brunobonacci.mulog :as mu]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]
    [tover.shared.ui.forms :refer [form]]
    [tover.workspaces.create.model :as model])
  (:import
    [clojure.lang ExceptionInfo]))


(def Attempt
  [:map [:command-id :string] [:workspace-id :uuid]])


(def FormProps
  [:map [:request :map] [:attempt {:optional true} Attempt] [:name {:optional true} [:maybe :string]]
   [:error {:optional true} [:maybe :string]]])


(defn- hidden
  {:malli/schema [:=> [:cat :string :any] schema/Hiccup]}
  [name value]
  [:input {:type "hidden" :name name :value (str value)}])


(defn create-form
  "One field. The attempt's ids are fixed when the form renders, so a resubmission recovers the same attempt."
  {:malli/schema [:=> [:cat FormProps] schema/Hiccup]}
  [{:keys [request attempt name error]}]
  (let [{:keys [command-id workspace-id]} (or attempt {:command-id (str (random-uuid)) :workspace-id (random-uuid)})]
    [:div#workspace-creation.management-form
     (when error [:p {:role "alert"} error])
     (form request ::create (i18n/t "Create workspace")
           (hidden "command-id" command-id)
           (hidden "workspace-id" workspace-id)
           [:label (i18n/t "Workspace name")
            ;; No maxlength: an overlong name is rejected with the full attempt kept, never truncated.
            [:input {:name "name" :type "text" :required true :value (or name "")}]
            [:span.management-secondary (i18n/t "1 to 100 characters. You become its first owner.")]])]))


(defn select-form
  "Select `workspace-id` for this sign-in (07), then open `return-to`."
  {:malli/schema [:=> [:cat :map :uuid :string :string] schema/Hiccup]}
  [request workspace-id return-to label]
  (form request (bel/action-url :tover.workspaces.select.web/select) label
        (hidden "workspace-id" workspace-id)
        (hidden "expected-version" (get-in request [:tover/identity :auth-version]))
        (hidden "command-id" (random-uuid))
        (hidden "return-to" return-to)))


(defn- query-ready
  "The outcome and current access, read separately from creation: a failed read keeps the accepted creation."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [request] :as context} input]
  {:request request
   :input   input
   :model   (try (runtime/read-model context ::model/created-workspace (select-keys input [:command-id :workspace-id]))
                 (catch ExceptionInfo error
                   (mu/log ::outcome-unavailable :error-type (:type (ex-data error)))
                   nil))})


(defn- outcome
  {:malli/schema [:=> [:cat :map] schema/Hiccup]}
  [{:keys [request input model]}]
  (if-not model
    (create-form {:request request :attempt (select-keys input [:command-id :workspace-id]) :name (:name input)
                  :error   (i18n/t "The workspace may already exist. Retry to check this request.")})
    [:section#workspace-creation.management-form {:data-workspace-id (str (:workspace-id model)) :role "status"}
     [:p [:span.management-name (:name model)]
      [:span.management-secondary
       (i18n/t "Workspace created")
       (when (:can-open? model) (list " · " (i18n/t "Your role: {role}" {:role (i18n/t (str/capitalize (:role model)))})))]]
     (if (:can-open? model)
       [:div.management-buttons
        (select-form request (:workspace-id model) "/" (i18n/t "Open workspace"))
        (when (contains? #{"owner" "admin"} (:role model))
          (select-form request (:workspace-id model) "/team" (i18n/t "Invite teammates")))]
       [:p (i18n/t "You no longer have access to this workspace.") " "
        [:a {:href "/workspace"} (i18n/t "Back to workspace choices")]])]))


(def ReadyInput
  (conj model/CreationQuery [:name {:optional true} :string]))


(defview ready
  {:schema     ReadyInput
   :query      query-ready
   :route-data {:tover/access :authenticated}}
  [props]
  {:type :elements :html (outcome props)})


(defn- problem
  {:malli/schema [:=> [:cat :map] :string]}
  [error]
  (case (:type error)
    :validation (i18n/t "Enter a workspace name of 1 to 100 characters.")
    :profile-incomplete (i18n/t "Add your name before continuing.")
    (i18n/t "The workspace may already exist. Retry to check this request.")))


(defn- query-failed
  "A known rejection keeps the attempt for a corrected submission. An attempt id that already belongs to an
  accepted creation (the name was edited before a retry) shows that original outcome."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [request] :as context} {:keys [error] :as input}]
  (if (= :idempotency-conflict (:type error))
    (assoc (query-ready context input) :outcome? true)
    {:request request         :attempt (select-keys input [:command-id :workspace-id]) :name (:name input)
     :error   (problem error)}))


(defview failed
  {:schema     [:map [:command-id :string] [:workspace-id :uuid] [:name :string] [:error :map]]
   :query      query-failed
   :route-data {:tover/access :authenticated}}
  [props]
  {:type :elements
   :html (if (:outcome? props) (outcome (dissoc props :outcome?)) (create-form props))})


(defaction create
  {:schema     [:map [:command-id [:string {:min 1 :max 100}]] [:workspace-id :uuid] [:name :string]]
   :route-data {:tover/access :authenticated}}
  [_ {:keys [command-id workspace-id name]}]
  {:type  :command :id ::model/create-workspace :command-id command-id
   :input {:workspace-id workspace-id :name name}
   :then  {:type :view :id ::ready :input {:command-id command-id :workspace-id workspace-id :name name}}
   :catch {:type :view :id ::failed :input {:command-id command-id :workspace-id workspace-id :name name}}})
