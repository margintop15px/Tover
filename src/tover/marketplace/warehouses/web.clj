(ns tover.marketplace.warehouses.web
  "Board chapter 43 screen under a connection's page. Warehouse Matching: one editable table of the
  connection's Ozon warehouses. Unmatched rows start with their suggestion, an existing Tover warehouse or a new
  one under the provider's name; Confirm sends the selected rows as one command. The Mapped filter shows the
  same table with each Tover warehouse and Change (a blank warehouse removes the mapping).

  Everything is read for the selected workspace and the connection in the path. Ozon text reaches the page only
  as escaped attribute or text content."
  (:require
    [bel.core :refer [defaction defpage defview]]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [tover.identity.access :as access]
    [tover.inventory.actors :as actors]
    [tover.inventory.operations.web :as operations]
    [tover.marketplace.connections.model :as connections]
    [tover.marketplace.grid :as grid]
    [tover.marketplace.products.web :as products-web]
    [tover.marketplace.warehouses.model :as warehouses]
    [tover.shared.i18n :as i18n]))


(def ^:private access
  {:tover/access :workspace})


(def ^:private states
  ["unmatched" "confirmed"])


(def ^:private state-labels
  {"unmatched" "Unmatched" "confirmed" "Mapped"})


(defn warehouses-path
  {:malli/schema [:=> [:cat :string [:* :any]] :string]}
  [connection-id & [state]]
  (str (operations/marketplace-path connection-id) "/warehouses" (when (= "confirmed" state) "?state=confirmed")))


;; Table


(defn- columns
  "The Tover warehouse picker: unmatched rows may keep a typed new name; a blank mapped row removes the mapping."
  {:malli/schema [:=> [:cat :string] [:vector grid/Column]]}
  [state]
  [{:key :ozon :label "Ozon warehouse" :type :lines :width 260}
   {:key :waiting :label "Waiting operations" :type :text :width 110}
   (cond-> {:key :warehouse :label "Tover warehouse" :type :reference :editable? true :lookup "warehouses" :width 260}
     (= "unmatched" state) (assoc :new-name? true))
   {:key :status :label "Status" :type :lines :width 260}])


(def ^:private selection-actions
  {"unmatched" [{:command "confirm" :label "Confirm" :primary? true}]
   "confirmed" [{:command "change" :label "Change mapping" :primary? true}]})


(defn- type-label
  {:malli/schema [:=> [:cat :string] :string]}
  [warehouse-type]
  (case warehouse-type "fbo" "FBO" "fbs" "FBS" "rfbs" "rFBS" (i18n/t "Supply origin")))


(defn- ozon-lines
  {:malli/schema [:=> [:cat :map] [:vector :string]]}
  [{:keys [name source-id warehouse-type active]}]
  (cond-> [(or name (i18n/t "No name")) (str (type-label warehouse-type) " · " source-id)]
    (not active) (conj (i18n/t "Inactive at Ozon"))))


(defn- warehouse-names
  {:malli/schema [:=> [:cat [:sequential :map]] [:map-of :string :string]]}
  [choices]
  (into {} (map (juxt :warehouse-id :name)) choices))


(defn unmatched-row
  {:malli/schema [:=> [:cat [:map-of :string :string] :map] grid/Row]}
  [names {:keys [external-warehouse-id revision name suggestion waiting-operations] :as row}]
  (let [{:keys [warehouse-id new-name]} suggestion
        chosen (when warehouse-id {:value warehouse-id :label (get names warehouse-id warehouse-id)})]
    {:id      external-warehouse-id :revision revision :label (or name external-warehouse-id)
     :options {:warehouse (if chosen [chosen] [])}
     :cells   {:ozon      (ozon-lines row)
               :waiting   (products-web/count-text waiting-operations)
               :warehouse (or chosen {:value "" :label new-name})
               :status    (cond-> [(i18n/t "Unmatched")]
                            new-name (conj (i18n/t "New warehouse: {name}" {:name new-name})))}}))


(defn mapped-row
  {:malli/schema [:=> [:cat [:map-of :any :any] :map] grid/Row]}
  [actors {:keys [external-warehouse-id mapping-revision name warehouse confirmed-by waiting-operations] :as row}]
  (let [chosen {:value (:warehouse-id warehouse) :label (:name warehouse)}]
    {:id      external-warehouse-id :revision mapping-revision :label (or name external-warehouse-id)
     :options {:warehouse [chosen]}
     :cells   {:ozon      (ozon-lines row)
               :waiting   (products-web/count-text waiting-operations)
               :warehouse chosen
               :status    (cond-> [(i18n/t "Mapped")]
                            (:archived? warehouse) (conj (i18n/t "The warehouse is archived."))
                            confirmed-by (conj (i18n/t "Confirmed by {name}" {:name (or (get actors confirmed-by) (i18n/t "Unavailable user"))})))}}))


(defn- table-rows
  {:malli/schema [:=> [:cat :map :string :string] [:vector grid/Row]]}
  [context connection-id state]
  (if (= "unmatched" state)
    (let [{:keys [rows] choices :warehouses} (runtime/read-model context ::warehouses/unmatched-external-warehouses
                                                                 {:connection-id connection-id})]
      (mapv (partial unmatched-row (warehouse-names choices)) rows))
    (let [rows   (filter #(= "confirmed" (:state %))
                         (:rows (runtime/read-model context ::warehouses/current-warehouse-mappings {:connection-id connection-id})))
          actors (into {} (map (juxt :actor :actor-label))
                       (actors/enrich context (keep (fn [row] (some->> (:confirmed-by row) (hash-map :actor))) rows)))]
      (mapv (partial mapped-row actors) rows))))


(defn table
  "The workbench props of `state` for the connection, or nil when they cannot be read."
  {:malli/schema [:=> [:cat :map :string :string] [:maybe :map]]}
  [context connection-id state]
  (products-web/read-or-nil
    #(hash-map :id (str "marketplace-warehouses-" state)
               :label "Warehouse matching"
               :columns (columns state)
               :selection-actions (selection-actions state)
               :rows (table-rows context connection-id state))))


(def Query
  [:map [:connectionId connections/ConnectionId] [:state (into [:enum] states)] [:requestId [:string {:max 100}]]])


(defview table-view
  {:route-data access :schema Query :query (fn [context input] {:context context :input input})}
  [{:keys [context] {:keys [connectionId state requestId]} :input}]
  (products-web/connection! context connectionId)
  (grid/table-signal requestId (some-> (table context connectionId state) grid/model)))


(defn choices
  "Active warehouses whose name contains `text`."
  {:malli/schema [:=> [:cat [:sequential :map] :string] [:vector grid/Option]]}
  [records text]
  (let [text (str/lower-case (str/trim text))]
    (->> records
         (filter #(and (= "active" (:status %)) (str/includes? (str/lower-case (:name %)) text)))
         (map (fn [{:keys [id name]}] {:value id :label name}))
         (take 30)
         vec)))


(defview choices-view
  {:route-data access
   :schema     [:map [:connectionId connections/ConnectionId] [:lookup [:= "warehouses"]]
                [:text [:string {:max 500}]] [:requestId [:string {:max 200}]]]
   :query      (fn [context input] {:context context :input input})}
  [{:keys [context] {:keys [text requestId]} :input}]
  (grid/choices-signal requestId (products-web/read-or-nil
                                   #(choices (:choices (runtime/read-model context :tover.catalogues.warehouses.model/warehouse-catalogue {}))
                                             text))))


;; Selection actions: one command for the selected rows


(def CommandRow
  [:map [:id warehouses/ExternalWarehouseId] [:revision warehouses/Revision]
   [:values {:optional true} [:map-of :any [:maybe [:string {:max 1000}]]]]])


(def Command
  [:map
   [:connectionId connections/ConnectionId] [:command [:enum "confirm" "change"]]
   [:commandId [:string {:min 1 :max 100}]]
   [:rows [:vector {:min 1 :max 5000} CommandRow]]])


(defn- text
  {:malli/schema [:=> [:cat :map :string] [:maybe :string]]}
  [values key]
  (some-> (get values key) str/trim not-empty))


(def ^:private paths
  "Where each rejection code is shown on its row."
  (zipmap [:warehouse-not-available :name-taken :invalid-name :already-used :warehouse-exists] (repeat "warehouse")))


(defn command-input
  "The command and its input for the selected rows, or the rows that cannot be sent with their reasons."
  {:malli/schema [:=> [:cat :map] [:or [:tuple :qualified-keyword :map] [:map [:rows [:vector :map]]]]]}
  [{:keys [command rows]}]
  (let [rows (mapv #(update % :values (fn [values] (update-keys (or values {}) name))) rows)]
    (case command
      "confirm" (let [missing (remove #(or (text (:values %) "warehouse") (text (:values %) "warehouse-name")) rows)]
                  (if (seq missing)
                    {:rows (mapv (fn [{:keys [id]}] {:id id :path "warehouse" :message (i18n/t "Choose a warehouse or enter a new name.")}) missing)}
                    [::warehouses/confirm-warehouse-mapping
                     {:rows (mapv (fn [{:keys [id revision values]}]
                                    (if-let [warehouse-id (text values "warehouse")]
                                      {:external-warehouse-id id :revision revision :warehouse-id warehouse-id}
                                      {:external-warehouse-id id :revision revision :warehouse-name (text values "warehouse-name")}))
                                  rows)}]))
      "change" (if (next rows)
                 {:rows (mapv (fn [{:keys [id]}] {:id id :path "status" :message (i18n/t "Change one warehouse at a time.")}) rows)}
                 (let [{:keys [id revision values]} (first rows)]
                   [::warehouses/change-warehouse-mapping
                    (cond-> {:external-warehouse-id id :mapping-revision revision}
                      (text values "warehouse") (assoc :warehouse-id (text values "warehouse")))])))))


(defn- warehouses!
  "Fails as unknown unless every external warehouse is one of the connection's in the selected workspace."
  {:malli/schema [:=> [:cat :map :string [:sequential :string]] :nil]}
  [context connection-id ids]
  (let [known (into #{} (map :external-warehouse-id)
                    (:rows (runtime/read-model context ::warehouses/current-warehouse-mappings {:connection-id connection-id})))]
    (when-not (every? known ids)
      (access/failure 404 :unknown-warehouse "Unknown warehouse"))))


(defaction command
  {:route-data access :schema Command}
  [context {:keys [connectionId commandId rows] :as input}]
  (products-web/connection! context connectionId)
  (warehouses! context connectionId (mapv :id rows))
  (let [result (command-input input)]
    (if (map? result)
      (grid/rejected commandId (i18n/t "Some rows are not ready.") :rows (:rows result))
      (let [[command-id command-input] result]
        {:type  :command :id command-id :command-id commandId :input command-input
         :then  {:type :view :id ::grid/committed :input {:commandId commandId}}
         :catch {:type  :view :id ::grid/failed
                 :input {:commandId commandId :idKey "external-warehouse-id" :path "status" :paths paths}}}))))


;; Page


(defn query-warehouses
  {:malli/schema [:=> [:cat :map :map] :map]}
  [context {:keys [connection-id state]}]
  (let [connection (products-web/connection! context connection-id)
        state      (if (some #{state} states) state "unmatched")]
    {:connection connection :connections (operations/query-connections context) :state state :table (table context connection-id state)}))


(defpage warehouse-matching
  {:route      "/operations/marketplace/:connection-id/warehouses"
   :title      "Warehouse matching · Tover"
   :parent     :tover.shared.web.core/shell
   :schema     [:map [:connection-id connections/ConnectionId] [:state {:optional true} [:string {:max 20}]]]
   :route-data access
   :query      query-warehouses}
  [{:keys [connection connections state table]}]
  (let [{:keys [connection-id account-label]} connection]
    {:type :elements
     :html [:section.marketplace-page {:aria-labelledby "warehouse-matching-title" :data-connection-id connection-id}
            [:div.management-page
             (operations/operations-views {:current [:warehouses connection-id] :connections connections})
             (products-web/header {:id        "warehouse-matching-title"    :title      (i18n/t "Warehouse matching")
                                   :subtitle  (str "Ozon · " account-label)
                                   :back-href "/operations"                 :back-label (i18n/t "Back to operations")})]
            (if table
              (grid/workbench (assoc table
                                     :query {:connectionId connection-id :state state}
                                     :query-view ::table-view :command-action ::command :choices-view ::choices-view
                                     :filters (mapv (fn [s] {:label (state-labels s) :href (warehouses-path connection-id s) :current? (= s state)})
                                                    states)))
              [:p.management-empty {:role "alert"} (i18n/t "Warehouses could not be loaded.") " "
               [:a {:href (warehouses-path connection-id state)} (i18n/t "Retry")]])]}))
