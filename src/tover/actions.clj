(ns tover.actions
  (:require
    [bel.core :refer [defaction]]
    [bel.realtime :as realtime]
    [tover.input :as input]
    [tover.rdf :as rdf]
    [tover.schema :as schema]
    [tover.store :as store]
    [tover.ui :as ui]
    [tover.views :as views])
  (:import
    [clojure.lang ExceptionInfo]))


(def Context
  [:map [:repo rdf/Repository] [:realtime realtime/Realtime]])


(defn notice
  {:malli/schema [:=> [:cat :string :boolean] :map]}
  [message error?]
  {:type :elements :html (ui/footer {:notice message :error? error?})})


(defaction edit
  {:schema schema/EditInput}
  [{:keys [repo] :as context} {:keys [rowId query]}]
  (if-let [row (:row (store/stock-row repo rowId))]
    (into [{:type :signals :data {:quantity (str (:quantity row)) :reason ""}}
           (notice "Enter a quantity and preview the adjustment." false)]
          (views/stock (assoc (views/query-stock context {:query query}) :editing {:row row})))
    (notice "This stock row does not exist." true)))


(defaction preview-quantity
  {:schema schema/PreviewQuantityInput}
  [{:keys [repo] :as context} {:keys [rowId quantity query]}]
  (try
    (if-let [row (:row (store/stock-row repo rowId))]
      (let [proposal (store/proposal row (input/quantity quantity))]
        (into [{:type :signals :data {:reason "" :_quantityPending true :_quantityRow (:row-id proposal) :_quantityValue (str (:after proposal))}}
               (notice "Review this adjustment before accepting." false)
               (views/review proposal)]
              (views/stock (views/query-stock context {:query query}))))
      [(views/review nil)
       (notice "This stock row does not exist." true)
       {:type :signals :data {:_quantityPending false}}])
    (catch ExceptionInfo error
      [(views/review nil)
       (notice (.getMessage error) true)
       {:type :signals :data {:_quantityPending false}}])))


(defn smart-preview
  {:malli/schema [:=> [:cat Context schema/SmartInput [:maybe schema/Text]] :any]}
  [context {:keys [query]} choice]
  (let [model (views/query-stock context {:query query})
        {:keys [type row matches after message]} (:interpretation model)
        row (if choice (some #(when (= choice (:id %)) %) (or matches (when row [row]))) row)]
    (cond
      (and row (#{:adjustment :choose} type))
      (try
        (into [{:type :signals :data {:reason "" :_quantityPending true :_quantityRow (:id row) :_quantityValue (str after)}}
               (notice "Review this adjustment before accepting." false)
               (views/review (store/proposal row after))]
              (views/stock model))
        (catch ExceptionInfo error
          [(views/review nil)
           (notice (.getMessage error) true)
           {:type :signals :data {:_quantityPending false}}]))

      (= type :error) (notice message true)
      (= type :choose) (conj (views/stock model) (notice "Choose goods to preview this adjustment." false))
      :else (notice "Use set <article> to <quantity> to preview an adjustment." false))))


(defaction preview-smart
  {:schema schema/SmartInput}
  [context input]
  (smart-preview context input nil))


(defaction choose-smart
  {:schema schema/ChooseInput}
  [context {:keys [rowId query]}]
  (smart-preview context {:query query} rowId))


(defaction accept
  {:schema schema/AcceptInput}
  [{:keys [repo realtime] :as context} {:keys [rowId quantity reason query]}]
  (try
    (let [receipt (store/accept-adjustment! repo {:row-id rowId :after (input/quantity quantity) :reason reason})]
      (try (realtime/publish! realtime (dissoc context :request) {:topic :tover.streams/stock :data {}})
           (catch Exception error
             (binding [*out* *err*] (println "Stock notification failed:" (.getMessage error)))))
      (into (views/stock (views/query-stock context {:query query}))
            [(views/review nil)
             {:type :signals :data {:reason "" :_quantityPending false :_gridNotice ""}}
             (notice (str "Accepted " (:before receipt) " → " (:after receipt) ". Inventory adjustment recorded.") false)]))
    (catch ExceptionInfo error
      (notice (.getMessage error) true))))


(defaction discard
  {:schema schema/StockInput}
  [context input]
  (into (views/stock (views/query-stock context input))
        [(views/review nil)
         {:type :signals :data {:reason "" :_quantityPending false :_gridNotice ""}}
         (notice "Draft discarded." false)]))


(defaction history
  {:schema schema/HistoryInput}
  [context input]
  (views/history (views/query-history context input)))


(defaction close-history
  {:schema [:map]}
  [_context _input]
  {:type :elements :html (ui/history-panel {:row nil :history []})})
