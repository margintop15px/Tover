(ns tover.stock.web
  (:require
    [bel.core :refer [defaction defpage defstream defview]]
    [bel.expressions :refer [->expr]]
    [bel.realtime :as realtime]
    [tover.inventory.workbench :as workbench]
    [tover.shared.rdf :as rdf]
    [tover.shared.schema :as shared]
    [tover.stock.input :as input]
    [tover.stock.schema :as schema]
    [tover.stock.store :as store]
    [tover.stock.ui :as ui]
    [tover.stock.view-model :as view-model])
  (:import
    [clojure.lang ExceptionInfo]))


(defview stock
  {:route-data {:tover/access :workspace}
   :schema     schema/StockInput
   :query      view-model/query-stock}
  [model]
  (let [props (view-model/smart-props (:interpretation model))]
    [{:type :elements
      :html (ui/smart-result (assoc props :interpretation (:interpretation model)))}
     {:type :signals
      :data {:stockChanged false :_stockRows (view-model/sheet-rows model) :_operations (or (:operations model) [])}}]))


(defn review
  {:malli/schema [:=> [:cat [:maybe schema/Proposal]] :map]}
  [proposal]
  {:type :elements
   :html (ui/stock-review {:proposal proposal :review (view-model/review-props proposal)})})


(defview draft-preview
  {:route-data {:tover/access :workspace}
   :schema     schema/DraftPreviewInput   :query view-model/query-draft-preview}
  [cells]
  {:type :elements :html (ui/stock-draft-preview {:cells cells :on-submit view-model/ui-submit-expr})})


(defview history
  {:route-data {:tover/access :workspace}
   :schema     schema/HistoryInput        :query view-model/query-history}
  [{:keys [row history]}]
  {:type :elements
   :html (ui/history-panel {:row      row :history history
                            :on-click (->expr (@action :tover.stock.web/close-history {} {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0}))})})


(def Context
  [:map [:repo rdf/Repository] [:realtime realtime/Realtime]
   [:user-id :uuid] [:workspace-id :uuid] [:role [:enum :owner :admin :member]]])


(defn notice
  {:malli/schema [:=> [:cat :string :boolean] :map]}
  [message error?]
  {:type :elements :html (ui/footer {:notice message :error? error?})})


(defaction edit
  {:route-data {:tover/access :workspace}
   :schema     schema/EditInput}
  [{:keys [repo] :as context} {:keys [rowId query]}]
  (if-let [row (:row (store/stock-row repo context rowId))]
    (into [{:type :signals :data {:quantity (str (:quantity row)) :reason ""}}
           (notice "Enter a quantity and preview the adjustment." false)]
          (stock (assoc (view-model/query-stock context {:query query}) :editing {:row row})))
    (notice "This stock row does not exist." true)))


(defaction preview-quantity
  {:route-data {:tover/access :workspace}
   :schema     schema/PreviewQuantityInput}
  [{:keys [repo] :as context} {:keys [rowId quantity query]}]
  (try
    (if-let [row (:row (store/stock-row repo context rowId))]
      (let [proposal (store/proposal row (input/quantity quantity))]
        (into [{:type :signals :data {:reason "" :_quantityPending true :_quantityRow (:row-id proposal) :_quantityValue (str (:after proposal))}}
               (notice "Review this adjustment before accepting." false)
               (review proposal)]
              (stock (view-model/query-stock context {:query query}))))
      [(review nil)
       (notice "This stock row does not exist." true)
       {:type :signals :data {:_quantityPending false}}])
    (catch ExceptionInfo error
      [(review nil)
       (notice (.getMessage error) true)
       {:type :signals :data {:_quantityPending false}}])))


(defn smart-preview
  {:malli/schema [:=> [:cat Context schema/SmartInput [:maybe shared/Text]] :any]}
  [context {:keys [query]} choice]
  (let [model (view-model/query-stock context {:query query})
        {:keys [type row matches after message]} (:interpretation model)
        row (if choice (some #(when (= choice (:id %)) %) (or matches (when row [row]))) row)]
    (cond
      (and row (#{:adjustment :choose} type))
      (try
        (into [{:type :signals :data {:reason "" :_quantityPending true :_quantityRow (:id row) :_quantityValue (str after)}}
               (notice "Review this adjustment before accepting." false)
               (review (store/proposal row after))]
              (stock model))
        (catch ExceptionInfo error
          [(review nil)
           (notice (.getMessage error) true)
           {:type :signals :data {:_quantityPending false}}]))

      (= type :error) (notice message true)
      (= type :choose) (conj (stock model) (notice "Choose goods to preview this adjustment." false))
      :else (notice "Use set <article> to <quantity> to preview an adjustment." false))))


(defaction preview-smart
  {:route-data {:tover/access :workspace}
   :schema     schema/SmartInput}
  [context input]
  (smart-preview context input nil))


(defaction choose-smart
  {:route-data {:tover/access :workspace}
   :schema     schema/ChooseInput}
  [context {:keys [rowId query]}]
  (smart-preview context {:query query} rowId))


(defaction accept
  {:route-data {:tover/access :workspace}
   :schema     schema/AcceptInput}
  [{:keys [repo realtime user-id workspace-id] :as context} {:keys [rowId quantity reason query]}]
  (try
    (let [receipt (store/accept-adjustment! repo context {:row-id rowId :after (input/quantity quantity) :reason reason :actor (str user-id)})]
      (try (realtime/publish! realtime (select-keys context [:repo :realtime :operation-examples]) {:topic :tover.stock.web/changed :data {} :scope-id workspace-id})
           (catch Exception error
             (binding [*out* *err*] (println "Stock notification failed:" (.getMessage error)))))
      (into (stock (view-model/query-stock context {:query query}))
            [(review nil)
             {:type :signals :data {:reason "" :_quantityPending false :_gridNotice ""}}
             (notice (str "Accepted " (:before receipt) " → " (:after receipt) ". Inventory adjustment recorded.") false)]))
    (catch ExceptionInfo error
      (notice (.getMessage error) true))))


(defaction discard
  {:route-data {:tover/access :workspace}
   :schema     schema/StockInput}
  [context input]
  (into (stock (view-model/query-stock context input))
        [(review nil)
         {:type :signals :data {:reason "" :_quantityPending false :_gridNotice ""}}
         (notice "Draft discarded." false)]))


(defaction open-history
  {:route-data {:tover/access :workspace}
   :schema     schema/HistoryInput}
  [context input]
  (history (view-model/query-history context input)))


(defaction close-history
  {:route-data {:tover/access :workspace}
   :schema     [:map]}
  [_context _input]
  {:type :elements :html (ui/history-panel {:row nil :history []})})


(defstream changed
  [_ctx _event]
  {:type   :signals
   :schema [:map [:stockChanged :boolean]]
   :data   {:stockChanged true}})


(defpage page
  {:route      "/"
   :title      "Stock · Tover"
   :parent     :tover.shared.web.core/shell
   :route-data {:tover/access :workspace}
   :query      (fn [context _input] (workbench/query-stock context {:view :stock}))}
  [props]
  {:type :elements
   :html (workbench/stock-page props)})
