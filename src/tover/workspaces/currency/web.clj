(ns tover.workspaces.currency.web
  "Board chapter 04a · Workspace settings: the workspace currency. Owners pick from ISO 4217 codes; other members
  see it read-only. A failed read offers Retry, never a guessed currency."
  (:require
    [bel.core :as bel :refer [defaction defpage]]
    [bel.runtime :as runtime]
    [com.brunobonacci.mulog :as mu]
    [tover.memberships.departure.web :as departure]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]
    [tover.shared.ui.forms :refer [csrf-field]]
    [tover.workspaces.currency.model :as model])
  (:import
    [clojure.lang ExceptionInfo]))


(defn- hidden
  {:malli/schema [:=> [:cat :string :any] schema/Hiccup]}
  [name value]
  [:input {:type "hidden" :name name :value (str value)}])


(defn query-settings
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [request] :as context} _]
  {:request  request
   :setting  (try (runtime/read-model context ::model/workspace-currency {})
                  (catch ExceptionInfo error
                    (when (= :forbidden (:type (ex-data error))) (throw error))
                    (mu/log ::currency-unavailable :error-type (:type (ex-data error)))
                    nil))
   :leave    (departure/eligibility context)
   :rejected (:tover/rejection request)})


(defn currency-form
  {:malli/schema [:=> [:cat :map] schema/Hiccup]}
  [{:keys [request] {:keys [currency can-change?]} :setting}]
  (if can-change?
    [:div.management-form
     [:form {:method "post" :action (bel/action-url ::save)}
      (csrf-field request)
      (hidden "_tover-context" (get-in request [:tover/identity :auth-version]))
      (hidden "command-id" (str (random-uuid)))
      (hidden "workspace-id" (get-in request [:tover/identity :workspace-id]))
      [:label (i18n/t "Currency")
       [:select {:name "currency"}
        (for [code @model/codes]
          [:option {:value code :selected (= code currency)} code])]]
      [:button {:type "submit"} (i18n/t "Save")]]
     [:p.management-secondary (i18n/t "All money amounts are in this currency. Changing it relabels them; nothing is converted.")]]
    [:div.management-form
     [:dl [:dt (i18n/t "Currency")] [:dd {:data-currency currency} currency]]]))


(defpage settings
  {:route      "/settings"
   :title      "Settings · Tover"
   :parent     :tover.shared.web.core/shell
   :route-data {:tover/access :workspace}
   :query      query-settings}
  [{:keys [request setting] :as props}]
  {:type :elements
   :html [:section.management-page {:aria-labelledby "settings-title"}
          [:header.management-header [:h1#settings-title {:tabindex -1} (i18n/t "Workspace settings")]]
          (if setting
            (currency-form props)
            [:div.management-empty {:role "alert"}
             [:p (i18n/t "Could not load the workspace currency.")]
             [:p [:a {:href (:uri request)} (i18n/t "Retry")]]])
          (departure/leave-section {:request request :eligibility (:leave props)})]})


(defaction save
  {:schema     [:map [:workspace-id :uuid] [:currency :string] [:command-id {:optional true} [:string {:min 1 :max 100}]]]
   :route-data {:tover/access   :workspace :bel/error-page ::settings :bel/retain ["currency"]
                :tover/problems {:not-an-owner     [403 "Only an owner can change the currency."]
                                 :forbidden        [403 "Only an owner can change the currency."]
                                 :unknown-currency [422 "Choose a currency from the list."]
                                 :not-permitted    [409 "This workspace is no longer selected."]}}}
  [_ {:keys [command-id] :as input}]
  {:type       :command
   :id         ::model/change-workspace-currency
   :command-id (or command-id (str (random-uuid)))
   :input      (select-keys input [:workspace-id :currency])
   :then       {:status 303 :headers {"location" "/settings"} :body ""}})
