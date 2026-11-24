(ns tover.memberships.departure.web
  "Board chapter 37 · Leave a workspace, on the workspace settings page. After leaving, the person lands on the
  workspace chooser with a note naming the workspace they left; nothing is selected for them."
  (:require
    [bel.core :refer [defaction]]
    [bel.runtime :as runtime]
    [com.brunobonacci.mulog :as mu]
    [tover.identity.access :as access]
    [tover.memberships.departure.model :as model]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]
    [tover.shared.ui.forms :refer [form]])
  (:import
    [clojure.lang ExceptionInfo]))


(defn eligibility
  "The reader's own departure eligibility, or nil when it could not be read."
  {:malli/schema [:=> [:cat :map] [:maybe model/Eligibility]]}
  [context]
  (try (runtime/read-model context ::model/departure-eligibility {})
       (catch ExceptionInfo error
         (mu/log ::eligibility-unavailable :error-type (:type (ex-data error)))
         nil)))


(defn leave-section
  {:malli/schema [:=> [:cat [:map [:request :map] [:eligibility [:maybe model/Eligibility]]]] schema/Hiccup]}
  [{:keys [request eligibility]}]
  (let [workspace-id (get-in request [:tover/identity :workspace-id])
        name         (some #(when (= workspace-id (:workspace-id %)) (:name %)) (get-in request [:tover/identity :memberships]))]
    [:section.management-section {:aria-labelledby "leave-title"}
     [:h2#leave-title (i18n/t "Leave workspace")]
     (cond
       (nil? eligibility)
       [:p.management-empty {:role "alert"} (i18n/t "Could not check whether you can leave.") " "
        [:a {:href (:uri request)} (i18n/t "Retry")]]

       (:blocked-by-last-owner? eligibility)
       [:p.management-secondary
        (i18n/t "You are the only active Owner. Make another member an Owner or transfer ownership on the Team page first.")]

       :else
       [:details.management-disclosure
        [:summary (i18n/t "Leave {workspace}" {:workspace name})]
        [:div.management-form.management-action-danger
         [:p.management-secondary
          (i18n/t "You lose access to this workspace. Its history and shared drafts stay. Returning needs a new invitation. Your account and other workspaces are not affected.")]
         (form request ::leave (i18n/t "Leave workspace")
               [:input {:type "hidden" :name "command-id" :value (str (random-uuid))}]
               [:input {:type "hidden" :name "workspace-id" :value (str workspace-id)}]
               [:input {:type "hidden" :name "workspace-name" :value (str name)}]
               [:input {:type "hidden" :name "expected-revision" :value (str (:revision eligibility))}]
               [:label (i18n/t "Reason (optional)") [:input {:name "reason" :maxlength 500}]])]])]))


(defaction leave
  {:schema     [:map [:command-id [:string {:min 1 :max 100}]] [:workspace-id :uuid] [:expected-revision :int]
                [:workspace-name {:optional true} [:maybe [:string {:max 200}]]] [:reason {:optional true} [:maybe :string]]]
   :route-data {:tover/access   :workspace :bel/error-page :tover.workspaces.currency.web/settings
                :tover/problems {:last-owner      409 :changed-since-review 409 :not-a-member 409
                                 :reason-too-long 422 :forbidden            [403 "You are not an active member of this workspace."]}}}
  [{:keys [request]} {:keys [command-id workspace-id workspace-name] :as input}]
  (when-not (= workspace-id (get-in request [:tover/identity :workspace-id]))
    (access/failure 409 :workspace-changed "Workspace changed. Reload this page before continuing."))
  {:type       :command
   :id         ::model/leave-workspace
   :command-id command-id
   :input      (select-keys input [:expected-revision :reason])
   :then       {:type :page :id :tover.workspaces.select.web/page :input (if workspace-name {:left workspace-name} {})}})
