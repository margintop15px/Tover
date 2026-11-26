(ns tover.marketplace.connections.web
  "Board chapter 40 screens: Marketplace Connections (the list and the connect form, on the workspace settings page)
  and Connection Details
  (status, coverage, Pause, Resume and the write-only reconnect form). Every member reads; Owner and Admin act.

  Connect and Replace verify the credentials with the provider and seal the API key before returning the
  command descriptor (a read that builds the input). The key is sealed only when the command can accept it,
  so a rejected attempt stores nothing; the API key field is never rendered with a value."
  (:require
    [bel.core :refer [defaction defpage]]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [ring.util.codec :as codec]
    [tover.identity.access :as access]
    [tover.invitations.team.web :as team]
    [tover.marketplace.connections.model :as model]
    [tover.marketplace.connector :as connector]
    [tover.marketplace.sync.model :as sync]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]
    [tover.shared.ui.forms :refer [form]])
  (:import
    [clojure.lang ExceptionInfo]))


(def problems
  "Board rejections, shown on the page the form came from."
  (let [changed [409 "This connection changed. Reload the page."]]
    {:credentials-rejected     422 :could-not-verify    503 :products-not-readable 422
     :already-connected        409 :unknown-connection  404 :needs-credentials     409
     :different-seller-account 422 :conflict            changed :identity-conflict changed
     :connection-paused        409 :throttled           409 :idempotency-conflict  changed
     :not-configured           503 :journal-unavailable [503 "The result is not recorded yet. Try again."]
     :validation               [422 "Check the entered fields."]}))


(def Command
  team/Command)


;; Commands


(defn- selected!
  "The form's workspace must still be the selected one."
  {:malli/schema [:=> [:cat :map :uuid] :nil]}
  [{:keys [request]} workspace-id]
  (when-not (= workspace-id (get-in request [:tover/identity :workspace-id]))
    (access/failure 409 :workspace-changed "Workspace changed. Reload this page before continuing.")))


(defn- descriptor
  "A manager command for the selected workspace; success opens the connection's page."
  {:malli/schema [:=> [:cat :map :qualified-keyword :map] :map]}
  [context id {:keys [command-id workspace-id connection-id] :as input}]
  (selected! context workspace-id)
  {:type  :command :id id :command-id command-id
   :input (dissoc input :command-id :workspace-id)
   :then  {:type :page :id ::connection :path-params {:connection-id connection-id}}})


(defn credentials
  "Replace `:api-key` with the provider's verification and, when `acceptable?` holds for the Client-Id, the
  secret reference of the sealed key. Keys earlier attempts sealed without an accepted command are destroyed
  first; without a connector key nothing is sent to the provider. An attempt accepted before (or while the
  provider answered) keeps its recorded input: nothing is asked or sealed again."
  {:malli/schema [:=> [:cat :map :qualified-keyword :map [:fn fn?]] :map]}
  [context command {:keys [command-id workspace-id client-id api-key] :as input} acceptable?]
  (let [_           (selected! context workspace-id)
        marketplace (get-in context [:bel/system :resources :marketplace])
        _           (when-not (:key marketplace)
                      (throw (ex-info "Marketplace connections are not configured" {:type :not-configured})))
        client-id   (str/trim client-id)
        secret-ref  (connector/secret-ref marketplace command-id client-id api-key)
        ;; Named fields only: a form also posts its anti-forgery token and context version.
        entered     (assoc (select-keys input [:connection-id :provider]) :client-id client-id)
        attempt     (select-keys input [:command-id :workspace-id])
        accepted    #(some-> (runtime/read-model context ::model/accepted-attempt
                                                 {:command-id command-id :command command
                                                  :entered    (assoc entered :secret-ref secret-ref)})
                             (merge attempt))]
    (or (accepted)
        (let [_            (connector/destroy-unreferenced! marketplace workspace-id #{secret-ref})
              verification ((:verify marketplace) {:client-id client-id :api-key api-key})]
          (or (accepted)
              (cond-> (merge attempt entered verification)
                (and (= "verified" (:verification-outcome verification))
                     (some #{"products"} (:readable-domains verification))
                     (acceptable? client-id))
                (assoc :secret-ref (connector/store-secret! marketplace workspace-id secret-ref api-key))))))))


(def Credentials
  [:map [:client-id model/ClientId] [:api-key [:string {:min 1 :max 500}]]])


(defaction connect
  {:schema     (into Command (concat [[:connection-id model/ConnectionId] [:provider model/Provider]]
                                     (rest Credentials)))
   :route-data {:tover/access   :manager :bel/error-page :tover.workspaces.currency.web/settings :bel/retain ["provider"]
                :tover/problems problems}}
  [context input]
  (let [{:keys [connections]}
        (runtime/read-model context ::model/marketplace-connections-and-requirements {})
        ;; The attempt's own connection is no obstacle: a second click recovers the first one's outcome.
        acceptable? (fn [client-id]
                      (not-any? #(and (= client-id (:provider-account-id %))
                                      (not= (:connection-id input) (:connection-id %)))
                                connections))]
    (descriptor context ::model/connect-marketplace-account (credentials context ::model/connect-marketplace-account input acceptable?))))


(defaction replace-credentials
  {:schema     (into Command (concat [[:connection-id model/ConnectionId]] (rest Credentials)))
   :route-data {:tover/access :manager :bel/error-page ::connection :tover/problems problems}}
  [context input]
  (let [account (:provider-account-id (runtime/read-model context ::model/connection-status-and-sync-coverage
                                                          (select-keys input [:connection-id])))]
    (descriptor context ::model/replace-connection-credentials
                (dissoc (credentials context ::model/replace-connection-credentials input #(= account %)) :account-label))))


(defaction pause
  {:schema     (conj Command [:connection-id model/ConnectionId])
   :route-data {:tover/access :manager :bel/error-page ::connection :tover/problems problems}}
  [context input]
  (descriptor context ::model/pause-marketplace-connection input))


(defaction sync-now
  "Any active member. During a run nothing new starts and the page shows that run."
  {:schema     (conj Command [:connection-id model/ConnectionId])
   :route-data {:tover/access :workspace :bel/error-page ::connection :tover/problems problems}}
  [context {:keys [command-id] :as input}]
  (descriptor context ::sync/start-marketplace-sync
              (assoc input :attempt-id command-id :run-id (str (random-uuid)) :trigger "member")))


(defaction resume
  {:schema     (conj Command [:connection-id model/ConnectionId])
   :route-data {:tover/access :manager :bel/error-page ::connection :tover/problems problems}}
  [context input]
  (descriptor context ::model/resume-marketplace-connection input))


;; Presentation


(defn- state-label
  {:malli/schema [:=> [:cat :map] :string]}
  [{:keys [sync-state credentials-state]}]
  (cond
    (= "rejected" credentials-state) (i18n/t "Needs credentials")
    (= "paused" sync-state) (i18n/t "Paused")
    :else (i18n/t "Active")))


(defn- hidden
  {:malli/schema [:=> [:cat :string :any] schema/Hiccup]}
  [name value]
  [:input {:type "hidden" :name name :value (str value)}])


(defn- credential-fields
  "Write-only: the API key is never rendered with a value."
  {:malli/schema [:=> [:cat [:maybe :string]] [:vector :any]]}
  [client-id]
  [[:label (i18n/t "Client ID")
    [:input {:name "client-id" :type "text" :required true :inputmode "numeric" :autocomplete "off" :value (or client-id "")}]]
   [:label (i18n/t "API key")
    [:input {:name "api-key" :type "password" :required true :autocomplete "new-password"}]]])


(defn- connection-link
  "Encoded: the path also stands in a Datastar expression."
  {:malli/schema [:=> [:cat :string] :string]}
  [connection-id]
  (str "/settings/marketplace/" (codec/url-encode connection-id)))


(defn- connect-form
  {:malli/schema [:=> [:cat :map] schema/Hiccup]}
  [request]
  [:div.management-form
   (apply form request ::connect (i18n/t "Connect")
          (concat (team/command-fields request)
                  [(hidden "connection-id" (random-uuid))
                   (hidden "provider" "ozon")]
                  (credential-fields nil)))
   [:p.management-secondary (i18n/t "Ozon is read only: Tover never changes anything in your seller account.")]])


(defn query-connections
  "The model of `connections-section`, read on the workspace settings page."
  {:malli/schema [:=> [:cat :map] :map]}
  [context]
  (runtime/read-model context ::model/marketplace-connections-and-requirements {}))


(defn connections-section
  "Marketplace Connections: the list and the connect form, a section of the workspace settings page."
  {:malli/schema [:=> [:cat [:map [:request :map] [:model :map]]] schema/Hiccup]}
  [{:keys [request model]}]
  (let [existing (get-in request [:tover/rejection :connection-id])]
    [:section.management-section {:aria-labelledby "marketplace-title"}
     [:h2#marketplace-title (i18n/t "Marketplace connections")]
     (when existing
       [:p [:a {:href (connection-link existing)} (i18n/t "Open the existing connection")]])
     (if (seq (:connections model))
       [:div.management-table-wrap
        [:table.management-table {:aria-labelledby "marketplace-title"}
         [:thead [:tr [:th {:scope "col"} (i18n/t "Seller account")]
                  [:th {:scope "col"} (i18n/t "Status")] [:th {:scope "col"} (i18n/t "Last sync")]]]
         (into [:tbody]
               (for [{:keys [connection-id account-label last-completed-sync-at] :as row} (:connections model)]
                 [:tr {:data-connection-id connection-id}
                  [:td.management-primary [:a {:href (connection-link connection-id)} "Ozon · " account-label]]
                  [:td (state-label row)]
                  [:td (if last-completed-sync-at (team/moment last-completed-sync-at) (i18n/t "Not yet"))]]))]]
       [:p.management-empty (i18n/t "No marketplace connections yet.")])
     (when (:can-manage? model)
       [:details#connect-account.management-disclosure {:open (or (empty? (:connections model)) (contains? (:params request) :provider))}
        [:summary (i18n/t "Connect a seller account")]
        (connect-form request)])]))


(defn query-connection
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [request] :as context} {:keys [connection-id]}]
  (let [input  {:connection-id connection-id}
        status (try (runtime/read-model context ::model/connection-status-and-sync-coverage input)
                    (catch ExceptionInfo error
                      (if (= :unknown-connection (:type (ex-data error)))
                        (access/failure 404 :unknown-connection "Unknown connection")
                        (throw error))))]
    {:request   request
     :status    status
     :paused    (when (= "paused" (:sync-state status)) (runtime/read-model context ::model/paused-connection input))
     :attention (when (= "rejected" (:credentials-state status))
                  (runtime/read-model context ::model/connection-needing-attention input))
     :sync      (runtime/read-model context ::sync/sync-status-and-coverage input)}))


(def ^:private reason-labels
  {"paused" "Paused" "needs credentials" "Needs credentials" "no baseline" "No cutover baseline"})


(def domain-labels
  {"warehouses"   "Warehouses"   "products"              "Products"              "sku-aliases"     "SKU aliases"
   "attributes"   "Attributes"   "attribute-definitions" "Attribute definitions" "prices"          "Prices"
   "stock-totals" "Stock totals" "stock-by-warehouse"    "Stock by warehouse"    "stock-analytics" "Stock analytics"
   "fbs-postings" "FBS postings" "fbo-postings"          "FBO postings"          "returns"         "Returns"
   "rfbs-returns" "rFBS returns" "supplies"              "Supplies"              "removals"        "Removals"})


(defn- action-form
  {:malli/schema [:=> [:cat :map :qualified-keyword :string :string] schema/Hiccup]}
  [request action label connection-id]
  (apply form request action label (conj (team/command-fields request) (hidden "connection-id" connection-id))))


(defn- sync-panel
  "Marketplace Sync Status: refreshes every few seconds while a run is open. Plain categories only."
  {:malli/schema [:=> [:cat :map :string [:* :any]] schema/Hiccup]}
  [{:keys [current-run last-outcome last-failure found-counts scheduling-stopped?]} connection-id & details]
  [:section.management-section
   (cond-> {:aria-labelledby "sync-title" :role "status"}
     current-run (assoc :data-on-interval__duration.5s (str "@get('" (connection-link connection-id) "')")))
   [:div.management-section-heading [:h2#sync-title (i18n/t "Sync")]]
   (cond
     current-run
     [:p (i18n/t "Sync running since") " " (team/moment (:started-at current-run)) " · "
      (i18n/t "{done} of {total} data kinds read" {:done  (count (:finished-domains current-run))
                                                   :total (+ (count (:finished-domains current-run))
                                                             (count (:unfinished-domains current-run)))})]
     (= "failed" last-outcome)
     [:p (i18n/t "Last sync failed on {domain}: {reason}" {:domain (i18n/t (domain-labels (:failed-domain last-failure)))
                                                           :reason (i18n/t (:reason last-failure))})
      " " (team/moment (:failed-at last-failure))]
     (= "completed" last-outcome)
     [:p (i18n/t "Last sync found {listings} new listings, {warehouses} new warehouses and {operations} new operations."
                 {:listings (:listings found-counts) :warehouses (:warehouses found-counts) :operations (:operations found-counts)})])
   (when scheduling-stopped?
     [:p.management-secondary (i18n/t "Scheduling stopped after 5 failed syncs. Use Sync now to try again.")])
   details])


(defpage connection
  {:route      "/settings/marketplace/:connection-id"
   :title      "Marketplace connection · Tover"
   :parent     :tover.shared.web.core/shell
   :schema     [:map [:connection-id model/ConnectionId]]
   :route-data {:tover/access :workspace}
   :query      query-connection}
  [{:keys [request status paused attention sync]}]
  (let [{:keys [connection-id account-label can-manage?]} status]
    {:type :elements
     :html [:section.management-page {:aria-labelledby "connection-title" :data-connection-id connection-id}
            [:header.management-header
             [:h1#connection-title {:tabindex -1} "Ozon · " account-label]
             [:p.management-subtitle (state-label status)]]
            [:div.management-content
             [:p [:a {:href "/settings"} (i18n/t "All connections")]]
             (when attention
               [:section.management-section {:role "status"}
                [:p (i18n/t "Ozon rejected the credentials.") " " (team/moment (:rejected-at attention))]
                [:p.management-secondary (i18n/t "Nothing syncs or is accepted automatically until the credentials are replaced.")]])
             (when paused
               [:section.management-section
                [:p (i18n/t "Paused by {name}" {:name (or (get-in paused [:paused-by :display-name]) (i18n/t "Unavailable user"))})
                 " " (team/moment (:paused-at paused))]
                (when (:can-resume? paused) [:div.management-buttons (action-form request ::resume (i18n/t "Resume") connection-id)])])
             (when (:currency-notice? status)
               [:p.management-section {:role "status"}
                (i18n/t "The seller account uses {account} and the workspace uses {workspace}. Imported prices and costs are left blank."
                        {:account (:account-currency status) :workspace (:workspace-currency status)})])
             (sync-panel
               sync connection-id
               [:dl.management-details
                [:dt (i18n/t "Account currency")]
                [:dd (:account-currency status)]
                (when-let [at (:key-expires-at status)]
                  (list [:dt (i18n/t "API key expires")] [:dd (team/moment at)]))
                [:dt (i18n/t "Last sync")]
                [:dd (if-let [at (:last-completed-sync-at status)] (team/moment at) (i18n/t "Not yet"))]
                [:dt (i18n/t "Next sync")]
                [:dd (if-let [at (:next-sync-at status)] (team/moment at) (i18n/t "Not scheduled"))]
                [:dt (i18n/t "Unmatched listings")]
                [:dd [:a {:href (str (connection-link connection-id) "/products")} (:unmatched-listings status)]]
                [:dt (i18n/t "Unmatched warehouses")]
                [:dd (:unmatched-warehouses status)]
                [:dt (i18n/t "Scopes without baseline")]
                [:dd (:scopes-without-baseline status)]
                [:dt (i18n/t "Imported operations by next step")]
                [:dd (if-let [steps (seq (:imported-operations-by-next-step status))]
                       (str/join ", " (map (fn [{:keys [next-step count]}] (str next-step ": " count)) steps))
                       "0")]
                [:dt (i18n/t "Automatic acceptance")]
                [:dd (if (:automatic-acceptance-possible? status)
                       (i18n/t "On")
                       (str/join ", " (map #(i18n/t (reason-labels %)) (:blocking-reasons status))))]]
               (let [buttons (remove nil? [(when (and (:can-sync-now sync) (not (:current-run sync)))
                                             (action-form request ::sync-now (i18n/t "Sync now") connection-id))
                                           (when (and can-manage? (= "active" (:sync-state status)))
                                             (action-form request ::pause (i18n/t "Pause") connection-id))])]
                 (when (seq buttons) (into [:div.management-buttons] buttons))))
             [:div.management-section.management-table-wrap
              [:table.management-table
               [:thead [:tr [:th {:scope "col"} (i18n/t "Data")] [:th {:scope "col"} (i18n/t "Covered until")]]]
               (into [:tbody]
                     (for [{:keys [domain readable? covered-until]} (:domains status)]
                       [:tr [:td (i18n/t (domain-labels domain))]
                        [:td (cond (not readable?) (i18n/t "Unavailable for this API key")
                                   covered-until (team/moment covered-until)
                                   :else (i18n/t "Not yet"))]]))]]
             (when can-manage?
               (list
                 ;; The refresh keeps it open or closed; the id keeps that from reaching another connection's page.
                 [:details.management-disclosure {:id                 (str "replace-credentials-" connection-id)
                                                  :open               (some? attention)
                                                  :data-preserve-attr "open"}
                  [:summary (i18n/t "Replace credentials")]
                  [:div.management-form
                   (apply form request ::replace-credentials (i18n/t "Replace credentials")
                          (concat (team/command-fields request) [(hidden "connection-id" connection-id)]
                                  (credential-fields (:provider-account-id status))))]]))]]}))
