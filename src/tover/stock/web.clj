(ns tover.stock.web
  (:require
    [bel.core :refer [defaction defpage defstream defview]]
    [bel.realtime :as realtime]
    [charred.api :as charred]
    [clojure.string :as str]
    [tover.inventory.actors :as actors]
    [tover.inventory.completion :as completion]
    [tover.inventory.filters :as filtering]
    [tover.inventory.item-selection :as item-selection]
    [tover.inventory.layouts :as layouts]
    [tover.inventory.ledger :as ledger]
    [tover.inventory.read-query :as read-query]
    [tover.inventory.store :as inventory-store]
    [tover.inventory.windows :as windows]
    [tover.inventory.workbench :as workbench]
    [tover.operations.ui :as operations-ui]
    [tover.shared.i18n :as i18n])
  (:import
    [clojure.lang ExceptionInfo]
    [java.nio.charset StandardCharsets]
    [java.time Instant]
    [java.util UUID]))


(def Context
  :map)


(def Input
  [:map
   [:view {:optional true} [:enum "stock" "operations"]]
   [:query {:optional true} [:string {:max 2000}]]
   [:filters {:optional true} :map]
   [:filterRequest {:optional true} [:int {:min 0}]]
   [:sort {:optional true} [:enum "name" "article" "barcode" "unit" "location" "quantity" "occurred-at" "type" "party" "summary" "context" "items" "reason"]]
   [:direction {:optional true} [:enum "asc" "desc"]]
   [:operationType {:optional true} [:string {:max 30}]]])


(defn snapshot-response
  {:malli/schema [:=> [:cat :map :map] :map]}
  [context input]
  {:type :signals :data {:_inventorySnapshot (workbench/view-snapshot (workbench/query-stock context input))}})


(defn response
  {:malli/schema [:=> [:cat :map :map] [:vector :map]]}
  [context input]
  (let [{:keys [model view filterRequest] :as props} (workbench/query-stock context input)
        selector (str "#inventory-evidence-workbench[data-view='" (name view) "'][data-filter-request='" filterRequest "'] ")]
    [{:type :signals :data {:_inventorySnapshot (workbench/view-snapshot props)}}
     {:type :elements :selector (str selector "#inventory-toolbar") :html (workbench/change-toolbar model view)}]))


(defn notice
  {:malli/schema [:=> [:cat [:or :string [:vector :any]] :boolean] :map]}
  [message error?]
  {:type :elements :selector "#inventory-notice" :patch-mode :replace :html [:div#inventory-notice {:role (if error? "alert" "status") :class (when error? "error") :data-ignore-morph ""} message]})


(defn failure
  {:malli/schema [:=> [:cat [:fn #(instance? Exception %)]] [:vector :map]]}
  [error]
  (let [{:keys [current attempt]} (ex-data error)
        path (:path attempt)
        display                   (fn [value] (if (= :occurred-at (last path)) (workbench/human-time {:value value}) (str value)))]
    [{:type :signals :data {:saveStatus "Failed" :_reviewPending false}}
     (notice [:span (i18n/error-message error)
              (when (and current path) [:span (i18n/t " Saved value: ") (display (get-in (:entry current) path)) (i18n/t ". Your edit: ") (display (:value attempt)) "."])
              (when current (i18n/t " Reload the saved draft before retrying."))] true)]))


(defn publish!
  {:malli/schema [:=> [:cat :map [:vector :qualified-keyword]] :nil]}
  [{:keys [realtime workspace-id] :as context} topics]
  (when (and (:repo context) (uuid? (:user-id context)) (uuid? workspace-id))
    (windows/invalidate! context))
  (doseq [topic topics]
    (when realtime
      (try
        (realtime/publish! realtime (select-keys context [:repo :realtime]) {:topic topic :data {} :scope-id workspace-id})
        (catch Exception error
          (binding [*out* *err*] (println "Inventory notification failed:" (.getMessage error))))))))


(defn saved
  {:malli/schema [:=> [:cat :map :map] [:vector :map]]}
  [context input]
  (publish! context [:tover.stock.web/draft-changed])
  (into (response context input)
        [{:type :signals :data {:saveStatus "Saved" :_review nil :_reviewDirty true}}
         (notice "" false)
         {:type :elements :selector "#inventory-review" :patch-mode :replace :html [:section#inventory-review {:data-ignore-morph ""}]}]))


(defn line
  {:malli/schema [:=> [:cat] :map]}
  []
  {:item-id (str (random-uuid)) :name "" :article "" :barcode "" :unit "pcs" :location "" :quantity ""})


(defn new-entry
  {:malli/schema [:=> [:cat [:maybe :keyword] [:maybe :keyword]] :map]}
  [kind type]
  (let [entry {:kind (or kind :operation) :type type :occurred-at (str (Instant/now)) :reason ""}]
    (cond
      (= :payment type) (assoc entry :party "" :direction :outgoing :amount "" :currency "")
      (= :production type) (assoc entry :inputs [(line)] :outputs [(line)])
      :else (cond-> (assoc entry :lines [(line)])
              (#{:purchase :sale} type) (assoc :party "")
              (= :return type) (assoc :direction :incoming)))))


(defn save!
  {:malli/schema [:=> [:cat :map [:maybe :string] [:maybe :int] :map] :map]}
  [context id revision entry]
  (let [entry (cond-> entry
                (= :purchase (:type entry)) (dissoc :receipts)
                (= :purchase (get-in entry [:replacement :type])) (update :replacement dissoc :receipts))]
    (inventory-store/save-entry! (:repo context) context
                                 (cond-> {:entry entry :actor (str (:user-id context))}
                                   id (assoc :id id)
                                   (some? revision) (assoc :expected-revision revision)))))


(defn draft
  {:malli/schema [:=> [:cat :map :string :int] :map]}
  [context id revision]
  (let [current (some #(when (= id (:id %)) %) (:drafts (inventory-store/snapshot (:repo context) context)))]
    (when-not (and current (= revision (:revision current)))
      (throw (ex-info "This entry changed since you opened it. Review its saved value before editing again."
                      {:type :stale-draft :current current})))
    current))


(defn path-parts
  {:malli/schema [:=> [:cat [:maybe :string]] [:vector :any]]}
  [path]
  (let [parts  (str/split (or path "") #"/")
        fields #{"replacement" "type" "occurred-at" "party" "reason" "direction" "amount" "currency" "original-operation-id"
                 "from-location" "to-location" "stock-count" "stock-location" "lines" "inputs" "outputs" "item-id" "name" "article" "barcode" "unit" "location" "quantity"}]
    (when (or (> (count parts) 6) (some #(not (or (contains? fields %) (re-matches #"\d{1,3}" %))) parts))
      (throw (ex-info "Unknown inventory field." {:type :validation})))
    (mapv #(if (re-matches #"\d+" %) (Long/parseLong %) (keyword %)) parts)))


(defn field-value
  {:malli/schema [:=> [:cat [:vector :any] :string] :any]}
  [path value]
  (case (last path)
    (:type :direction) (keyword value)
    :quantity (if (re-matches #"\d+" value) (try (Long/parseLong value) (catch NumberFormatException _ value)) value)
    value))


(defn save-stock-correction!
  {:malli/schema [:=> [:cat :map :map] :map]}
  [context {:keys [rowId field value ledgerRevision draftId draftRevision]}]
  (let [draftId       (not-empty draftId)
        draftRevision (when (pos? (or draftRevision 0)) draftRevision)
        snapshot      (inventory-store/snapshot (:repo context) context)
        row           (some #(when (= rowId (:id %)) %) (:rows snapshot))]
    (when-not (= ledgerRevision (:ledger-revision snapshot))
      (throw (ex-info "Stock changed while you edited it. Refresh and review the current quantity." {:type :stale-ledger})))
    (when-not row (throw (ex-info "This stock row is no longer available." {:type :validation})))
    (let [existing    (some #(when (and (= :correction (get-in % [:entry :kind]))
                                        (= rowId (get-in % [:entry :stock-row-id]))) %) (:drafts snapshot))
          _           (when-not (and (= draftId (:id existing)) (= draftRevision (:revision existing)))
                        (throw (ex-info "This stock correction changed. Review the saved draft before editing again." {:type :stale-draft :current existing})))
          _           (when (and existing (not= ledgerRevision (get-in existing [:entry :stock-ledger-revision])))
                        (throw (ex-info "Accepted stock changed since this correction was drafted. Review the draft before editing again." {:type :stale-ledger})))
          base        (or (:entry existing) {:kind         :correction :occurred-at        (str (Instant/now)) :reason (str (i18n/t "Manual ") field (i18n/t " correction"))
                                             :stock-row-id rowId :stock-ledger-revision    ledgerRevision
                                             :stock-count  (:quantity row) :stock-location (:location row)
                                             :lines        [(select-keys row [:item-id :name :article :barcode :unit :location :quantity])]})
          counted     (if (= field "quantity") (field-value [:quantity] value) (:stock-count base))
          location    (if (= field "location") value (:stock-location base))
          item        (cond-> (select-keys (last (:lines base)) [:item-id :name :article :barcode :unit])
                        (#{"name" "article" "barcode" "unit"} field) (assoc (keyword field) value))
          destination (some #(when (and (= (:item-id row) (:item-id %)) (= location (:location %))) %) (:rows snapshot))
          lines       (if (= location (:location row))
                        [(assoc item :location location :quantity counted)]
                        [(assoc item :location (:location row) :quantity 0)
                         (assoc item :location location :quantity (if (integer? counted) (+' counted (or (:quantity destination) 0)) counted))])
          entry       (assoc base :stock-count counted :stock-location location :lines lines)
          id          (or (:id existing) (str (UUID/nameUUIDFromBytes (.getBytes (str (:workspace-id context) "/" ledgerRevision "/" rowId) StandardCharsets/UTF_8))))]
      (save! context id (:revision existing) entry))))


(defn switch-type
  {:malli/schema [:=> [:cat :map :keyword] :map]}
  [entry type]
  (when-not (contains? (set (map first workbench/operation-types)) type)
    (throw (ex-info "Choose an available operation type." {:type :validation})))
  (let [allowed (case type
                  :purchase [:party :amount :currency :lines]
                  :sale [:party :amount :currency :lines]
                  :return [:party :direction :original-operation-id :lines]
                  :transfer [:from-location :to-location :lines]
                  :production [:inputs :outputs]
                  :payment [:party :direction :original-operation-id :amount :currency]
                  [:lines])]
    (merge (new-entry :operation type)
           (select-keys entry (concat [:kind :occurred-at :reason] allowed))
           {:type type})))


(defn save-field*
  {:malli/schema [:=> [:cat :map :map] :map]}
  [context {:keys [id revision path value]}]
  (let [current     (draft context id revision)
        path        (path-parts path)
        stock-field (get {:stock-count "quantity" :stock-location "location" :name "name" :article "article" :barcode "barcode" :unit "unit" :quantity "quantity" :location "location"} (last path))
        entry       (assoc-in (:entry current) path (field-value path value))
        entry       (if (= :type (last path))
                      (let [prefix      (pop path)
                            replacement (switch-type (get-in (:entry current) prefix (:entry current)) (keyword value))]
                        (if (seq prefix) (assoc-in entry prefix replacement) replacement)) entry)
        entry       (if (and (str/blank? value) (#{:original-operation-id :amount :currency} (last path)))
                      (if (seq (pop path)) (update-in entry (pop path) dissoc (last path)) (dissoc entry (last path))) entry)
        entry       (if (= :item-id (last path))
                      (let [snapshot (inventory-store/snapshot (:repo context) context)]
                        (item-selection/select-item (assoc snapshot :items (workbench/item-catalog snapshot)) entry (pop path) value))
                      entry)]
    (try
      (if (and stock-field (= :correction (get-in current [:entry :kind])) (get-in current [:entry :stock-row-id]))
        (save-stock-correction! context {:rowId          (get-in current [:entry :stock-row-id])          :field   stock-field :value         value
                                         :ledgerRevision (get-in current [:entry :stock-ledger-revision]) :draftId id          :draftRevision revision})
        (save! context id revision entry))
      (catch ExceptionInfo error (throw (ex-info (.getMessage error) (assoc (ex-data error) :attempt {:path path :value value})))))))


(defview stock
  {:route-data {:tover/access :workspace} :schema Input
   :query      (fn [context input] {:context context :input input})}
  [{:keys [context input]}]
  (response context input))


(defview grid-window
  {:route-data {:tover/access :workspace}
   :schema     [:map
                [:queryId [:string {:min 1 :max 100}]]
                [:requestId [:string {:min 1 :max 100}]]
                [:view [:enum "stock" "operations"]]
                [:start [:int {:min 0}]]
                [:count {:optional true} [:int {:min 1 :max 200}]]
                [:anchorId {:optional true} [:maybe [:string {:max 500}]]]
                [:detailRowId {:optional true} [:maybe [:string {:max 500}]]]
                [:pendingRows {:optional true} [:maybe [:vector {:max 100} [:string {:max 500}]]]]]
   :query      (fn [context input] {:context context :input input})}
  [{:keys [context input]}]
  (let [input   (into {} (remove (comp nil? val)) input)
        fetched (windows/fetch! context input)
        model   (when (= (:view input) (:id fetched)) fetched)
        result  (cond-> (select-keys input [:queryId :requestId :view])
                  model (assoc :status "ok" :model (workbench/configured-grid (workbench/layout-settings context) model))
                  (nil? model) (assoc :status "expired"))]
    {:type :signals :data {:_gridWindowJson (charred/write-json-str result)}}))


(defview operation-details
  {:route-data {:tover/access :workspace}
   :schema     [:map
                [:queryId [:string {:min 1 :max 100}]]
                [:requestId [:string {:min 1 :max 100}]]
                [:operationId [:string {:min 1 :max 500}]]]
   :query      (fn [context input] {:context context :input input})}
  [{:keys [context input]}]
  (let [repo (:repo context)]
    (locking repo
      (let [{:keys [queryId operationId]} input
            result (select-keys input [:queryId :requestId :operationId])]
        (if-not (windows/contains-group? context queryId operationId)
          {:type :signals :data {:_operationDetailJson (charred/write-json-str (assoc result :status "expired"))}}
          (let [records (inventory-store/operation-records (:repo context) context operationId)
                entry   (first (ledger/effective-entries records))
                row     (first (operations-ui/rows (if entry [entry] []) (actors/enrich context records)))
                detail  (select-keys (:detail row) [:heading :summary-metadata :cancelled])]
            {:type :signals
             :data {:_operationDetailJson (charred/write-json-str
                                            (if row
                                              (assoc result :status "ok" :detail detail :entry (select-keys row [:id :operation :type]))
                                              (assoc result :status "expired")))}}))))))


(defview item-options
  {:route-data {:tover/access :workspace}
   :schema     [:map [:requestId [:string {:min 1 :max 100}]]
                [:text [:string {:max 2000}]]]
   :query      (fn [context input] {:context context :input input})}
  [{:keys [context input]}]
  (let [items   (inventory-store/read-snapshot (:repo context) context
                                               (fn [conn _] (read-query/suggest-items conn context (:text input) 30)))
        options (mapv (fn [[id item]]
                        {:value id :label (str (:name item) (when-not (str/blank? (:article item)) (str " · " (:article item))))})
                      (sort-by #(get-in % [1 :name]) items))]
    {:type :signals :data {:_itemOptionsJson (charred/write-json-str {:requestId (:requestId input) :status "ok" :options options})}}))


(defview reference-options
  {:route-data {:tover/access :workspace}
   :schema     [:map [:requestId [:string {:min 1 :max 100}]]
                [:text [:string {:max 2000}]]]
   :query      (fn [context input] {:context context :input input})}
  [{:keys [context input]}]
  (let [options (inventory-store/read-snapshot (:repo context) context
                                               (fn [conn _] (read-query/suggest-operations conn context (:text input) 30)))]
    {:type :signals :data {:_referenceOptionsJson (charred/write-json-str {:requestId (:requestId input) :status "ok" :options options})}}))


(defaction update-filters
  {:route-data {:tover/access :workspace}
   :schema     [:map [:command [:enum "remove" "reset"]] [:filters :map]
                [:column {:optional true} :string] [:conditionId {:optional true} :string]
                [:view [:enum "stock" "operations"]] [:filterRequest :int]]}
  [context {:keys [command filters column conditionId view] :as input}]
  (try
    (let [filters (filtering/check! (keyword view) filters)
          filters (if (= command "reset") {}
                      (let [remaining (vec (remove #(= conditionId (:id %)) (get filters column)))]
                        (if (seq remaining) (assoc filters column remaining) (dissoc filters column))))]
      ;; The snapshot removes the action's filter button, ending its response stream.
      (update-in (response context (assoc input :filters filters)) [0 :data] assoc :saveStatus "Saved"))
    (catch ExceptionInfo error (failure error))))


(defaction save-layout
  {:route-data {:tover/access :workspace}
   :schema     [:map [:gridId :string] [:widths {:optional true} [:maybe :map]]
                [:order {:optional true} [:maybe [:vector :string]]]]}
  [context input]
  (try
    (layouts/save! context (into {} (remove (comp nil? val)) input))
    {:type :signals :data {:_columnLayoutsJson (charred/write-json-str (layouts/load-all context))
                           :_layoutStatus      "Saved"}}
    (catch ExceptionInfo _
      {:type :signals :data {:_layoutStatus "Failed"}})))


(defaction add-entry
  {:route-data {:tover/access :workspace} :schema Input}
  [context {:keys [kind type] :as input}]
  (try
    (let [kind (when kind (keyword kind))
          type (when-not (= :opening kind) (keyword (or type "purchase")))]
      (when (and type (not (contains? (set (map first workbench/operation-types)) type)))
        (throw (ex-info "Choose an operation type." {:type :validation})))
      (save! context nil nil (new-entry kind type))
      (saved context input))
    (catch ExceptionInfo error (failure error))))


(defaction save-field
  {:route-data {:tover/access :workspace}
   :schema     [:map [:id :string] [:revision :int] [:path :string] [:value [:string {:max 2000}]]
                [:view {:optional true} :string] [:query {:optional true} :string]]}
  [context input]
  (try (save-field* context input) (saved context input)
       (catch ExceptionInfo error
         (failure (ex-info (.getMessage error)
                           (assoc (ex-data error) :attempt {:path (try (path-parts (:path input)) (catch Exception _ nil)) :value (:value input)}))))))


(defaction draft-command
  {:route-data {:tover/access :workspace}
   :schema     [:map [:id :string] [:revision :int] [:command [:enum "discard" "add-line" "remove-line"]]
                [:path {:optional true} :string] [:view {:optional true} :string] [:query {:optional true} :string]]}
  [context {:keys [id revision command path] :as input}]
  (try
    (let [current (draft context id revision)
          prefix  (if (= :revision (get-in current [:entry :kind])) [:replacement] [])
          entry   (:entry current)
          path    (when (seq path) (into prefix (path-parts path)))]
      (if (= command "discard")
        (inventory-store/discard-entry! (:repo context) context {:id id :expected-revision revision})
        (save! context id revision
               (case command
                 "add-line" (update-in entry path (fnil conj []) (line))
                 "remove-line" (update-in entry (pop path) #(vec (concat (take (peek path) %) (drop (inc (peek path)) %))))))))
    (saved context input)
    (catch ExceptionInfo error (failure error))))


(defaction edit-stock
  {:route-data {:tover/access :workspace}
   :schema     [:map [:rowId :string] [:field [:enum "name" "article" "barcode" "unit" "location" "quantity"]]
                [:value [:string {:max 500}]] [:ledgerRevision :int]
                [:draftId {:optional true} [:maybe :string]] [:draftRevision {:optional true} :int]
                [:draftKind {:optional true} :string]
                [:view {:optional true} :string] [:query {:optional true} :string]]}
  [context input]
  (try
    (if (str/starts-with? (:rowId input) "draft:")
      (let [current (draft context (:draftId input) (:draftRevision input))
            index   (some (fn [[index _]] (when (= (:rowId input) (str "draft:" (:id current) ":" index)) index))
                          (map-indexed vector (get-in current [:entry :lines])))]
        (when-not (and (= :opening (get-in current [:entry :kind])) (some? index))
          (throw (ex-info "This new stock row is no longer available." {:type :stale-draft})))
        (let [field (keyword (:field input))]
          (save! context (:id current) (:revision current)
                 (assoc-in (:entry current) [:lines index field] (field-value [field] (:value input))))))
      (save-stock-correction! context input))
    (saved context (assoc input :ackRowId (:rowId input)))
    (catch ExceptionInfo error
      (let [current (:current (ex-data error))
            field   (keyword (:field input))
            entry   (:entry current)
            path    (if (= :correction (:kind entry))
                      (case field :quantity [:stock-count] :location [:stock-location]
                            [:lines (max 0 (dec (count (:lines entry)))) field])
                      [:lines 0 field])]
        (failure (ex-info (.getMessage error) (assoc (ex-data error) :attempt {:path path :value (:value input)})))))))


(defaction revise-entry
  {:route-data {:tover/access :workspace}
   :schema     [:map [:targetId :string] [:kind [:enum "revision" "cancellation"]] [:view {:optional true} :string] [:query {:optional true} :string]]}
  [context {:keys [targetId kind] :as input}]
  (try
    (let [target (some #(when (= targetId (:id %)) %) (:operations (inventory-store/snapshot (:repo context) context)))]
      (when-not target (throw (ex-info "The accepted operation is no longer effective." {:type :validation})))
      (let [replacement (cond-> (dissoc target :id :recorded-at :actor)
                          (= :purchase (:type target)) (dissoc :receipts))]
        (save! context nil nil (cond-> {:kind (keyword kind) :target-id targetId :reason ""}
                                 (= kind "revision") (assoc :replacement replacement)))))
    (saved context input)
    (catch ExceptionInfo error (failure error))))


(defaction revert-drafts
  {:route-data {:tover/access :workspace}
   :schema     [:map [:selected [:vector [:map [:id :string] [:revision :int]]]]
                [:view {:optional true} :string] [:query {:optional true} :string]]}
  [context {:keys [selected] :as input}]
  (try
    (inventory-store/discard-selected! (:repo context) context selected)
    (into (saved context input)
          [{:type :signals :data {:_selectedEntries [] :_draftPreview false}}])
    (catch ExceptionInfo error (failure error))))


(defaction close-preview
  {:route-data {:tover/access :workspace} :schema [:map]}
  [_context _input]
  [{:type :signals :data {:_draftPreview false :_review nil :_reviewDirty false :_reviewPending false}}
   {:type :elements :selector "#inventory-review" :patch-mode :replace :html [:section#inventory-review {:data-ignore-morph ""}]}])


(defaction preview
  {:route-data {:tover/access :workspace}
   :schema     [:map [:selected [:vector :string]] [:view {:optional true} :string] [:query {:optional true} :string]]}
  [context {:keys [selected view query]}]
  (try
    (let [snapshot (workbench/draft-snapshot context)
          ids      (set selected)
          drafts   (filterv #(contains? ids (:id %)) (:drafts snapshot))
          selected (mapv #(select-keys % [:id :revision]) drafts)
          _        (when (and (seq (:drafts snapshot)) (not= (count ids) (count selected)))
                     (throw (ex-info "Some selected entries were removed. Refresh the draft and select again." {:type :stale-draft})))
          errors   (vec (concat (when (empty? selected) [{:message "Select at least one change to submit."}])
                                (mapcat (fn [draft]
                                          (map #(assoc % :entry-id (:id draft))
                                               (:errors (completion/check (:entry draft) (set (keys (workbench/item-catalog snapshot))))))) drafts)))
          result   (if (seq errors)
                     {:valid? false :errors errors :selected selected :expected-ledger-revision (:ledger-revision snapshot)}
                     (inventory-store/preview (:repo context) context {:selected selected :expected-ledger-revision (:ledger-revision snapshot)}))
          result   (update result :errors
                           (fn [errors]
                             (mapv (fn [error]
                                     (if-let [draft (some #(when (= (:operation-id error) (or (get-in % [:entry :target-id]) (:id %))) %) drafts)]
                                       (assoc error :entry-id (:id draft)) error)) errors)))
          review   (assoc (select-keys result [:selected :expected-ledger-revision]) :command-id (str (random-uuid)))]
      (if (empty? (:drafts snapshot))
        (close-preview context {})
        [{:type :signals :data {:_review (when (:valid? result) review) :_draftPreview true :_reviewPending false :_reviewDirty false :_selectedEntries (mapv :id selected)}}
         {:type :elements :selector "#inventory-review" :patch-mode :replace :html (workbench/preview-panel (assoc (merge result (workbench/layout-settings context)) :items (merge (:items snapshot) (:items result)) :drafts drafts
                                                                                                                   :entries (mapv :entry (filter #(contains? ids (:id %)) (:drafts snapshot))))
                                                                                                            (keyword (or view "stock")) (or query ""))}]))
    (catch ExceptionInfo error (failure error))))


(defaction submit
  {:route-data {:tover/access :workspace}
   :schema     [:map [:review [:map [:selected [:vector [:map [:id :string] [:revision :int]]]]
                               [:expected-ledger-revision :int] [:command-id :string]]]
                [:view {:optional true} :string] [:query {:optional true} :string]]}
  [context {:keys [review] :as input}]
  (try
    (let [receipt (inventory-store/submit! (:repo context) context (assoc review :actor (str (:user-id context))))]
      (publish! context [:tover.stock.web/draft-changed :tover.stock.web/changed :tover.operations.web/changed])
      (into (response context input)
            [{:type :signals :data {:_review nil :_selectedEntries [] :_draftPreview false :saveStatus "Saved"}}
             {:type :elements :selector "#inventory-review" :patch-mode :replace :html [:section#inventory-review {:data-ignore-morph ""}]}
             (notice (i18n/t "Submitted {count} entries. Receipt {receipt}" {:count (count (:selected review)) :receipt (:command-id receipt)}) false)]))
    (catch ExceptionInfo error (failure error))))


(def RequestId
  [:re #"[A-Za-z0-9-]{1,64}"])


(defn history-selector
  {:malli/schema [:=> [:cat :string] :string]}
  [request-id]
  (str "#history-panel[data-request-id='" request-id "']"))


(defn history-snapshot
  {:malli/schema [:=> [:cat :map :string :string [:? :boolean]] :map]}
  [context item-id row-id & [include-ancestors?]]
  (let [history (inventory-store/item-history (:repo context) context item-id
                                              {:include-ancestors? (if (nil? include-ancestors?) true include-ancestors?) :row-id row-id})]
    (-> history
        (update :audit-records #(actors/enrich context %))
        (assoc :snapshot-at (str (Instant/now))))))


(defn history-detail
  {:malli/schema [:=> [:cat :map :map :string :int] :map]}
  [context history operation-id revision]
  (let [own?      (some #(and (= operation-id (:entity-id %)) (= revision (:revision %))) (:audit-records history))
        ancestor? (some #(and (= operation-id (:operation-id %)) (= revision (:operation-revision %))) (:timeline-records history))
        _         (when-not (or own? ancestor?)
                    (throw (ex-info "This history record is unavailable." {:type :validation})))
        record    (or (some #(when (and (= operation-id (:entity-id %)) (= revision (:revision %))) %) (:audit-records history))
                      (inventory-store/operation-record (:repo context) context operation-id revision))
        _         (when-not record (throw (ex-info "This history record is unavailable." {:type :validation})))
        record    (first (actors/enrich context [record]))
        entry     (assoc (:entry record) :id operation-id :revision revision :recorded-at (:recorded-at record))]
    (assoc (:detail (first (operations-ui/rows [entry] [record]))) :operation-id operation-id :revision revision)))


(defaction open-history
  {:route-data {:tover/access :workspace}
   :schema     [:map [:itemId :string] [:rowId :string] [:requestId RequestId]
                [:mode {:optional true} [:enum "history" "lineage"]]
                [:operationId {:optional true} :string] [:revision {:optional true} [:int {:min 0}]]]}
  [context {:keys [itemId rowId requestId mode operationId revision] :or {mode "history"}}]
  (try
    (let [history (history-snapshot context itemId rowId (= "lineage" mode))
          detail  (when (and (= "lineage" mode) (seq operationId) (pos? (or revision 0)))
                    (history-detail context history operationId revision))]
      {:type :elements :selector (history-selector requestId) :patch-mode :replace
       :html (workbench/history-panel (assoc (merge history (workbench/layout-settings context)) :row-id rowId :request-id requestId :mode mode :detail detail
                                             :operation-id operationId :revision revision))})
    (catch ExceptionInfo error (failure error))))


(defaction close-history
  {:route-data {:tover/access :workspace}
   :schema     [:map [:rowId :string] [:requestId RequestId]]}
  [_context {:keys [rowId requestId]}]
  {:type :elements :selector (history-selector requestId) :patch-mode :replace
   :html [:section#history-panel {:data-ignore-morph ""
                                  :data-init         (str "const g=document.getElementById('stock-grid'); g.rowDetail=null; g.focusRowAction(" (pr-str rowId) ")")}]})


(defaction open-history-operation
  {:route-data {:tover/access :workspace}
   :schema     [:map [:itemId :string] [:rowId :string] [:requestId RequestId]
                [:operationId :string] [:revision [:int {:min 1}]]]}
  [context {:keys [itemId rowId requestId operationId revision]}]
  (try
    (let [history (history-snapshot context itemId rowId)
          detail  (history-detail context history operationId revision)]
      {:type :elements :selector (str (history-selector requestId) " #history-operation-details") :patch-mode :replace
       :html (workbench/history-operation detail)})
    (catch ExceptionInfo error (failure error))))


(defaction close-history-operation
  {:route-data {:tover/access :workspace} :schema [:map [:requestId RequestId]]}
  [_context {:keys [requestId]}]
  {:type :elements :selector (str (history-selector requestId) " #history-operation-details") :patch-mode :replace
   :html [:section#history-operation-details
          {:data-ignore-morph ""
           :data-init         "const p=el.closest('#history-panel'), trigger=[...p.querySelectorAll('.inventory-timeline button')].find(b=>b.dataset.operationId===p.dataset.operationId && b.dataset.operationRevision===p.dataset.operationRevision); delete p.dataset.operationId; delete p.dataset.operationRevision; (p._historyTrigger?.isConnected ? p._historyTrigger : trigger)?.focus({preventScroll:true})"}]})


(defstream changed
  {:resnapshot-on-open? true}
  [_context _event]
  {:type :signals :data {:inventoryChanged (str (random-uuid))}})


(defstream draft-changed
  [_context _event]
  {:type :signals :data {:inventoryChanged (str (random-uuid))}})


(defpage page
  {:route      "/" :title "Stock · Tover" :parent :tover.shared.web.core/shell
   :route-data {:tover/access :workspace}
   :query      (fn [context input] (workbench/query-stock context (assoc input :view :stock)))}
  [props]
  {:type :elements :html (workbench/stock-page props)})
