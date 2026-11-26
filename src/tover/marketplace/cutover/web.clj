(ns tover.marketplace.cutover.web
  "Board chapter 44 screen: the Cutover view of the Operations page, one per connection. Cutover Setup is the instant
  picker above one editable table of the connection's scopes, prefilled from the observation where it is
  trustworthy; Propose sends the selected ready scopes as one command. The proposed entries land in the shared draft
  and are reviewed and accepted there, on the Stock page. Cutover Coverage is the read-only table below.

  Everything is read for the selected workspace and the connection in the path."
  (:require
    [bel.core :refer [defaction defpage defview]]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [ring.util.codec :as codec]
    [tover.inventory.operations.web :as operations]
    [tover.marketplace.connections.model :as connections]
    [tover.marketplace.cutover.model :as cutover]
    [tover.marketplace.grid :as grid]
    [tover.marketplace.products.web :as products-web]
    [tover.marketplace.provenance :as provenance]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]))


(def ^:private access
  {:tover/access :workspace})


(defn cutover-path
  {:malli/schema [:=> [:cat :string] :string]}
  [connection-id]
  (str (operations/marketplace-path connection-id) "/cutover"))


;; Labels


(defn- names
  "Display names of stock items (`Product / variant`) and warehouses."
  {:malli/schema [:=> [:cat [:maybe :map]] :map]}
  [{:keys [products stock-items warehouses]}]
  (let [product (into {} (map (juxt :product-id :name)) products)]
    (merge (into {} (map (juxt :warehouse-id :name)) warehouses)
           (into {} (map (fn [{:keys [stock-item-id product-id variant-label]}]
                           [stock-item-id (str (product product-id product-id)
                                               (when-let [v (some-> variant-label str/trim not-empty)] (str " / " v)))]))
                 stock-items))))


(def ^:private readiness-labels
  {"ready"                 "Ready"                 "pending"      "Pending"      "has baseline" "Has baseline" "needs product match" "Needs product match"
   "needs warehouse match" "Needs warehouse match" "not observed" "Not observed"})


(defn- signed
  {:malli/schema [:=> [:cat :int] :string]}
  [n]
  (cond (pos? n) (str "+" n) (neg? n) (str "−" (abs n)) :else "0"))


;; Cutover Setup table


(def ^:private columns
  [{:key :item :label "Stock item" :type :text :width 220}
   {:key :warehouse :label "Warehouse" :type :text :width 160}
   {:key :readiness :label "Readiness" :type :lines :width 220}
   {:key :observed :label "Observed" :type :lines :width 170}
   {:key :balance :label "Tover balance" :type :text :width 110}
   {:key :result :label "Result" :type :text :width 110}
   {:key :quantity :label "Quantity" :type :text :editable? true :width 100}
   {:key :unit-cost :label "Unit cost" :type :text :editable? true :width 110}
   {:key :cost-source :label "Cost source" :type :text :width 120}])


(defn- revisions
  "The mapping revisions a ready row sends back: `p|<listing>` and `w|<external warehouse>`."
  {:malli/schema [:=> [:cat [:maybe :map]] [:map-of :keyword :int]]}
  [{:keys [products warehouses]}]
  (merge (update-keys products #(keyword (str "p|" %))) (update-keys warehouses #(keyword (str "w|" %)))))


(defn mapping-revisions
  "The revisions a row sent, as the command takes them."
  {:malli/schema [:=> [:cat [:maybe :map]] [:map [:product-mapping-revisions :map] [:warehouse-mapping-revisions :map]]]}
  [sent]
  (let [pick (fn [prefix] (into {} (keep (fn [[k v]] (let [k (name k)] (when (str/starts-with? k prefix) [(subs k 2) v])))) sent))]
    {:product-mapping-revisions (pick "p|") :warehouse-mapping-revisions (pick "w|")}))


(defn scope-row
  "One scope of `Cutover Readiness and Observed Balances`; a pending scope shows its proposed count and the
  difference against Tover's balance (`reviewed`, from `Cutover Review`). A ready scope sends `run-id`, the pass it
  was read from, back as its observation."
  {:malli/schema [:=> [:cat :map [:maybe :map] [:maybe :string] :map] grid/Row]}
  [label reviewed run-id {:keys [stock-item-id warehouse-id external-product-id external-warehouse-id readiness observed-quantity observed-at
                          balance result suggested-quantity suggested-cost cost-source mapping-revisions]}]
  (let [id (cond stock-item-id (cutover/scope-id stock-item-id warehouse-id)
                 external-product-id (str "product:" external-product-id)
                 :else (str "warehouse:" external-warehouse-id))]
    (cond-> {:id          id :revision 1
             :label       (or (some-> stock-item-id label) external-product-id external-warehouse-id)
             :selectable? (= "ready" readiness)
             :cells       {:item        (if stock-item-id (label stock-item-id stock-item-id) (or external-product-id ""))
                           :warehouse   (if warehouse-id (label warehouse-id warehouse-id) (or external-warehouse-id ""))
                           :readiness   (cond-> [(i18n/t (readiness-labels readiness readiness))]
                                          reviewed (conj (i18n/t "Proposed {count}, difference {difference}"
                                                                 {:count      (:proposed-quantity reviewed)
                                                                  :difference (signed (:difference reviewed))})))
                           :observed    (if observed-quantity [(str observed-quantity) observed-at] [])
                           :balance     (some-> balance str)
                           :result      (some-> result str/capitalize i18n/t)
                           :quantity    (if reviewed (str (:proposed-quantity reviewed)) (some-> suggested-quantity str))
                           :unit-cost   (when (= "opening" result) suggested-cost)
                           :cost-source (some-> cost-source i18n/t)}}
      mapping-revisions (assoc :revisions (revisions mapping-revisions))
      (and run-id (= "ready" readiness)) (assoc :observation run-id))))


(defn table
  "The Cutover Setup workbench props, or nil when they cannot be read."
  {:malli/schema [:=> [:cat :map :string [:maybe :string]] [:maybe :map]]}
  [context connection-id instant]
  (products-web/read-or-nil
    #(let [read (runtime/read-model context ::cutover/cutover-readiness-and-observed-balances
                                    (cond-> {:connection-id connection-id} instant (assoc :instant instant)))
           label (names (runtime/read-model context :tover.inventory.references/draft-catalogue {}))
           reviewed (into {} (for [r     (:rows (runtime/read-model context ::provenance/cutover-review {}))
                                   :when (= connection-id (:connection-id r))]
                               [(cutover/scope-id (:stock-item-id r) (:warehouse-id r)) r]))]
       {:id                "marketplace-cutover"
        :label             "Cutover"
        :columns           columns
        ;; Only an Owner or Admin proposes; the command checks it again.
        :selection-actions (if (:can-propose? read) [{:command "propose" :label "Propose" :primary? true}] [])
        :read              (dissoc read :scopes)
        :rows              (mapv (fn [s] (scope-row label (when (:stock-item-id s) (reviewed (cutover/scope-id (:stock-item-id s) (:warehouse-id s))))
                                                  (:run-id read) s))
                                 (:scopes read))})))


(def Query
  [:map [:connectionId connections/ConnectionId] [:instant {:optional true} [:maybe [:string {:max 100}]]]
   [:requestId [:string {:max 100}]]])


(defview table-view
  {:route-data access :schema Query :query (fn [context input] {:context context :input input})}
  [{:keys [context] {:keys [connectionId instant requestId]} :input}]
  (products-web/connection! context connectionId)
  (grid/table-signal requestId (some-> (table context connectionId instant) grid/model)))


;; Cutover Coverage table


(def ^:private coverage-columns
  [{:key :item :label "Stock item" :type :text :width 220}
   {:key :warehouse :label "Warehouse" :type :text :width 160}
   {:key :coverage :label "Coverage" :type :lines :width 260}
   {:key :waiting :label "Waiting operations" :type :text :width 130}])


(defn coverage-rows
  {:malli/schema [:=> [:cat :map cutover/Coverage] [:vector grid/Row]]}
  [label {:keys [baselines without-baseline]}]
  ;; ponytail: a baselined scope shows 0 waiting; chapter 45 supplies the imported operations still in Review.
  (into (mapv (fn [{:keys [stock-item-id warehouse-id instant]}]
                {:id    (cutover/scope-id stock-item-id warehouse-id) :revision 1 :label (label stock-item-id stock-item-id)
                 :cells {:item     (label stock-item-id stock-item-id)                   :warehouse (label warehouse-id warehouse-id)
                         :coverage [(i18n/t "Baseline at {instant}" {:instant instant})] :waiting   "0"}})
              baselines)
        (map (fn [{:keys [stock-item-id warehouse-id readiness waiting-operations]}]
               {:id    (cutover/scope-id stock-item-id warehouse-id) :revision 1 :label (label stock-item-id stock-item-id)
                :cells {:item     (label stock-item-id stock-item-id) :warehouse (label warehouse-id warehouse-id)
                        :coverage [(i18n/t "No cutover baseline") (i18n/t (readiness-labels readiness readiness))]
                        :waiting  (str waiting-operations)}}))
        without-baseline))


(defn coverage-table
  {:malli/schema [:=> [:cat :map :string] [:maybe :map]]}
  [context connection-id]
  (products-web/read-or-nil
    #(let [coverage (runtime/read-model context ::cutover/baseline-coverage-by-stock-item-and-warehouse {:connection-id connection-id})
           label (names (runtime/read-model context :tover.inventory.references/draft-catalogue {}))]
       {:id "marketplace-cutover-coverage" :label "Cutover coverage" :columns coverage-columns :rows (coverage-rows label coverage)})))


(defview coverage-view
  {:route-data access :schema Query :query (fn [context input] {:context context :input input})}
  [{:keys [context] {:keys [connectionId requestId]} :input}]
  (products-web/connection! context connectionId)
  (grid/table-signal requestId (some-> (coverage-table context connectionId) grid/model)))


;; Propose: one command for the selected rows


(def CommandRow
  [:map [:id [:string {:min 1 :max 1000}]] [:revision :int]
   [:values {:optional true} [:map-of :any [:maybe [:string {:max 1000}]]]]
   [:revisions {:optional true} [:map-of :any :int]]
   [:observation {:optional true} [:maybe [:string {:max 200}]]]])


(def Command
  [:map
   [:connectionId connections/ConnectionId] [:instant {:optional true} [:maybe [:string {:max 100}]]]
   [:command [:enum "propose"]] [:commandId [:string {:min 1 :max 100}]]
   [:rows [:vector {:min 1 :max 5000} CommandRow]]])


(def ^:private paths
  "Where each rejection code is shown on its row."
  {:cost-required "unit-cost" :invalid-cost "unit-cost" :invalid-quantity "quantity"})


(defn command-input
  "The proposal for the selected rows; `scopes` are the read's rows by id. A row that is no scope of the view is
  sent as it is, and the command names why."
  {:malli/schema [:=> [:cat :map [:map-of :string :map] [:maybe :string]] :map]}
  [{:keys [connectionId rows]} scopes instant]
  {:connection-id connectionId
   :instant       (or instant "")
   :scopes        (mapv (fn [{:keys [id values observation] sent :revisions}]
                          (let [values (update-keys (or values {}) name)
                                {:keys [stock-item-id warehouse-id]} (get scopes id)
                                [item warehouse] (if stock-item-id [stock-item-id warehouse-id] (str/split id #"\|" 2))]
                            (merge {:stock-item-id item :warehouse-id (or warehouse "") :quantity (or (some-> (get values "quantity") str/trim) "")}
                                   (mapping-revisions sent)
                                   (when-let [cost (some-> (get values "unit-cost") str/trim not-empty)] {:unit-cost cost})
                                   (when (not-empty observation) {:observation observation}))))
                        rows)})


(defaction command
  {:route-data access :schema Command}
  [context {:keys [connectionId instant commandId] :as input}]
  ;; Reads the scopes to resolve row ids and the instant shown; the command rechecks everything.
  (products-web/connection! context connectionId)
  (let [read   (runtime/read-model context ::cutover/cutover-readiness-and-observed-balances
                                   (cond-> {:connection-id connectionId} (not-empty instant) (assoc :instant instant)))
        scopes (into {} (keep #(when (:stock-item-id %) [(cutover/scope-id (:stock-item-id %) (:warehouse-id %)) %])) (:scopes read))]
    {:type  :command :id ::cutover/propose-marketplace-cutover :command-id commandId
     :input (command-input input scopes (or (not-empty instant) (:instant read)))
     :then  {:type :view :id ::grid/committed :input {:commandId commandId}}
     :catch {:type :view :id ::grid/failed :input {:commandId commandId :idKey "scope-id" :path "readiness" :paths paths}}}))


;; Page


(defn instant-picker
  "Cutover Setup's instant: a GET of this view at another instant; the earliest allowed one and the last snapshot are
  named."
  {:malli/schema [:=> [:cat [:map [:connection-id :string] [:read [:maybe :map]]]] schema/Hiccup]}
  [{:keys [connection-id read]}]
  (let [{:keys [instant default-instant earliest]} read]
    [:div.management-form.cutover-instant
     [:form {:method "get" :action (cutover-path connection-id)}
      [:label (i18n/t "Cutover instant")
       [:input {:type        "text"                 :name "instant" :value (or instant default-instant "") :required true :maxlength 100
                :placeholder "2026-11-20T10:00:00Z"}]]
      [:button {:type "submit"} (i18n/t "Show")]]
     [:p.management-secondary
      (str/join " · " (remove nil? [(i18n/t "An instant with its UTC offset, such as Z or +03:00.")
                                    (if earliest (i18n/t "Earliest allowed: {instant}" {:instant earliest})
                                        (i18n/t "Nothing can be proposed until every operation domain has been read once."))
                                    (when default-instant (i18n/t "Last snapshot: {instant}" {:instant default-instant}))]))]
     [:p.management-secondary
      (i18n/t "Proposed entries wait in the shared draft.") " "
      [:a.cutover-review {:href (str "/?cutover=" (codec/url-encode connection-id))} (i18n/t "Review and accept the proposed entries")]]]))


(defn query-cutover
  {:malli/schema [:=> [:cat :map :map] :map]}
  [context {:keys [connection-id instant]}]
  (let [connection (products-web/connection! context connection-id)
        instant    (some-> instant str/trim not-empty)]
    {:connection  connection
     :connections (operations/query-connections context)
     :instant     instant
     :table       (table context connection-id instant)
     :coverage    (coverage-table context connection-id)}))


(defpage cutover-view
  {:route      "/operations/marketplace/:connection-id/cutover"
   :title      "Cutover · Tover"
   :parent     :tover.shared.web.core/shell
   :schema     [:map [:connection-id connections/ConnectionId] [:instant {:optional true} [:string {:max 100}]]]
   :route-data access
   :query      query-cutover}
  [{:keys [connection connections table coverage]}]
  (let [{:keys [connection-id account-label]} connection
        retry (fn [what] [:p.management-empty {:role "alert"} (i18n/t what) " " [:a {:href (cutover-path connection-id)} (i18n/t "Retry")]])]
    {:type :elements
     :html [:section.marketplace-page {:aria-labelledby "cutover-title" :data-connection-id connection-id}
            [:div.management-page
             (operations/operations-views {:current [:cutover connection-id] :connections connections})
             (products-web/header {:id        "cutover-title" :title      (i18n/t "Cutover")            :subtitle (str "Ozon · " account-label)
                                   :back-href "/operations"   :back-label (i18n/t "Back to operations")})
             (instant-picker {:connection-id connection-id :read (:read table)})]
            (if table
              (grid/workbench (-> table
                                  (dissoc :read)
                                  ;; The instant read, not only a typed one: a later pass must not move what Propose sends.
                                  (assoc :query (cond-> {:connectionId connection-id}
                                                  (:instant (:read table)) (assoc :instant (:instant (:read table))))
                                         :query-view ::table-view :command-action ::command)))
              (retry "Cutover scopes could not be loaded."))
            [:div.management-page [:h2 (i18n/t "Cutover coverage")]]
            (if coverage
              (grid/workbench (assoc coverage :query {:connectionId connection-id} :query-view ::coverage-view :command-action ::command))
              (retry "Cutover coverage could not be loaded."))]}))
