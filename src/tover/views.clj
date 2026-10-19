(ns tover.views
  (:require
    [bel.core :refer [defview]]
    [bel.expressions :refer [->expr]]
    [bel.validation :as validation]
    [clojure.string :as str]
    [tover.input :as input]
    [tover.operations :as operations]
    [tover.rdf :as rdf]
    [tover.schema :as schema]
    [tover.store :as store]
    [tover.ui :as ui]))


(def Context
  [:map
   [:repo rdf/Repository]
   [:operation-examples {:optional true} :map]])


(defn row-props
  {:malli/schema [:=> [:cat schema/Row] :map]}
  [{:keys [id quantity]}]
  {:on-edit    (->expr (@action :tover.actions/edit {:rowId ~id :query $query}))
   :on-history (->expr (@action :tover.actions/history {:rowId ~id}))
   :value      (str quantity)
   :bind       "quantity"
   :init       (->expr (.focus el) (.select el))
   :on-preview (->expr (@action :tover.actions/preview-quantity {:rowId ~id :quantity $quantity :query $query}))
   :on-keydown (->expr
                 (when (= evt.key "Enter")
                   (.preventDefault evt)
                   (@action :tover.actions/preview-quantity {:rowId ~id :quantity $quantity :query $query}))
                 (when (= evt.key "Escape")
                   (.preventDefault evt)
                   (@action :tover.actions/discard {:query $query})))})


(defn review-props
  {:malli/schema [:=> [:cat [:maybe schema/Proposal]] :map]}
  [proposal]
  {:value      ""
   :bind       "reason"
   :on-accept  (->expr (@action :tover.actions/accept
                                {:rowId  ~(get proposal :row-id) :quantity ~(str (:after proposal))
                                 :reason $reason                 :query    $query}))
   :on-discard (->expr (@action :tover.actions/discard {:query $query}))})


(defn smart-props
  {:malli/schema [:=> [:cat schema/Interpretation] :map]}
  [interpretation]
  {:value      (or (:query interpretation) "")
   :bind       "query"
   :on-input   (->expr (@view :tover.views/stock {:query $query}))
   :on-preview (str "$_uiSubmitted = false; " (->expr (@action :tover.actions/preview-smart {:query $query})))
   :on-keydown (->expr
                 (when (= evt.key "Enter")
                   (.preventDefault evt)
                   (set! $_uiSubmitted false)
                   (@action :tover.actions/preview-smart {:query $query})))
   :choices    (into {} (map (fn [{:keys [id]}]
                               [id (str "$_uiSubmitted = false; " (->expr (@action :tover.actions/choose-smart {:rowId ~id :query $query})))])
                             (:matches interpretation)))})


(defn sheet-rows
  {:malli/schema [:=> [:cat schema/UIModel] [:vector :map]]}
  [{:keys [rows evidence]}]
  (mapv (fn [row]
          (assoc row :quantity (str (:quantity row))
                 :evidence (if (get evidence (:id row)) "Manual edit" "Opening declaration")))
        rows))


(def ui-draft-expr
  "const drafts = JSON.parse(JSON.stringify($_cellDrafts)); const submitted = JSON.parse(JSON.stringify($_submittedCells)); const rows = JSON.parse(JSON.stringify([...($_showOperationExamples ? $_demoRows : $_stockRows), ...$_submittedAddedRows.filter(row => row.demo === $_showOperationExamples), ...$_addedRows])).map(row => ({...row, ...(submitted[row.id] || {})})); for (const [id, values] of Object.entries(evt.detail.changes)) { const row = rows.find(row => row.id === id); const fields = {...drafts[id], ...values}; for (const key of Object.keys(fields)) if (row && fields[key] === row[key]) delete fields[key]; if (Object.keys(fields).length) drafts[id] = fields; else delete drafts[id]; } $_cellDrafts = drafts; $_draftPreview = false; $_gridNotice = ''")


(def ui-add-row-expr
  (str "const values = evt.detail?.values || {}; const row = {id: 'draft-' + crypto.randomUUID(), demo: $_showOperationExamples, name: '', quantity: '', unit: '', location: '', article: '', evidence: 'Manual entry'}; "
       "for (const field of ['name', 'quantity', 'unit', 'location', 'article']) if (typeof values[field] === 'string') row[field] = values[field]; "
       "$_addedRows = [...$_addedRows, row]; $_draftPreview = false; $_gridNotice = ''; "
       "document.querySelector('#stock-revogrid').dispatchEvent(new CustomEvent('stock-row-added', {detail: {rowId: row.id, edit: evt.type === 'click'}}))"))


(def ui-submit-expr
  (str "const submitted = JSON.parse(JSON.stringify($_submittedCells)); const hidden = [...new Set([...$_submittedDeletedRows, ...$_deletedRows])]; "
       "const drafts = JSON.parse(JSON.stringify($_cellDrafts)); const additions = JSON.parse(JSON.stringify($_addedRows)); const baseline = JSON.parse(JSON.stringify([...($_showOperationExamples ? $_demoRows : $_stockRows), ...$_submittedAddedRows.filter(row => row.demo === $_showOperationExamples)])).map(row => ({...row, ...(submitted[row.id] || {})})); "
       "if ($_quantityPending && !$_uiSubmitted && $_quantityRow) drafts[$_quantityRow] = {...drafts[$_quantityRow], quantity: $_quantityValue}; "
       "const now = new Date().toISOString(); const labels = {name: 'Goods', quantity: 'Quantity', unit: 'Unit', location: 'Location', article: 'Article'}; const events = []; "
       "const record = (row, type, before, after, changes = []) => ({id: crypto.randomUUID(), rowId: row.id, name: row.name || 'New row', article: row.article, unit: row.unit, location: row.location, type, before, after, delta: '', changes, reason: '', evidence: type === 'Row added' ? 'Manual entry' : 'Manual edit', recordedAt: now, effectiveAt: now, local: true, demo: $_showOperationExamples}); "
       "for (const row of additions) { const after = {...row, ...(drafts[row.id] || {})}; const changes = Object.entries(labels).filter(([key]) => after[key]).map(([key, field]) => ({field, before: '', after: after[key]})); events.push(record(after, 'Row added', '', after.quantity, changes)); } "
       "for (const row of baseline) { if ($_deletedRows.includes(row.id)) events.push(record(row, 'Row deleted', row.quantity, '')); else if (drafts[row.id]) { const changes = Object.entries(drafts[row.id]).filter(([key, value]) => row[key] !== value).map(([key, value]) => ({field: labels[key] || key, before: row[key] || '', after: value})); if (changes.length) { const after = {...row, ...drafts[row.id]}; events.push(record(after, changes.every(change => change.field === 'Quantity') ? 'Manual adjustment' : 'Stock edited', row.quantity, after.quantity, changes)); } } } "
       "$_localOperations = [...events, ...$_localOperations]; "
       "for (const [id, fields] of Object.entries($_cellDrafts)) submitted[id] = {...submitted[id], ...fields}; "
       "if ($_quantityPending && !$_uiSubmitted && $_quantityRow && !hidden.includes($_quantityRow)) submitted[$_quantityRow] = {...submitted[$_quantityRow], quantity: $_quantityValue}; "
       "$_submittedAddedRows = [...$_submittedAddedRows, ...$_addedRows]; $_addedRows = []; $_submittedCells = submitted; $_submittedDeletedRows = hidden; $_selectedRows = $_selectedRows.filter(id => !hidden.includes(id)); "
       "$_uiSubmitted = true; $_quantityPending = false; $_cellDrafts = {}; $_deletedRows = []; $_draftPreview = false; $_previewCells = []; $_previewBaseline = []; $reason = ''; $_gridNotice = ''"))


(defn stock-props
  {:malli/schema
   [:function
    [:=> [:cat schema/UIModel] :map]
    [:=> [:cat schema/UIModel [:maybe :map]] :map]]}
  ([model]
   (stock-props model nil))

  ([{:keys [rows proposal interpretation operations] :as model} examples]
   {:signals          (validation/signal-json :map {:stockChanged       false
                                                    :_view              "stock" :_operations                   (or operations [])       :_localOperations       []
                                                    :_exampleOperations (or (:entries examples) []) :_demoRows (or (:rows examples) []) :_showOperationExamples (boolean examples)
                                                    :_gridEditing       false :_quantityPending                false                    :_gridNotice            ""
                                                    :_cellDrafts        {} :_stockRows (sheet-rows model)
                                                    :_addedRows         [] :_submittedAddedRows                [] :_previewAddedRows [] :_previewLocalRows []
                                                    :_changedRows       0 :_draftPreview false :_previewCells []
                                                    :_quantityRow       "" :_quantityValue ""
                                                    :_selectedRows      [] :_deletedRows []
                                                    :_submittedCells    {} :_submittedDeletedRows              [] :_uiSubmitted false :_previewBaseline    []})
    :effect           (->expr (when $stockChanged (@view :tover.views/stock {:query $query})))
    :sheet            {:source            "el.operations = JSON.parse(JSON.stringify([...$_localOperations.filter(entry => entry.demo === $_showOperationExamples), ...($_showOperationExamples ? $_exampleOperations : $_operations)])); const deleted = $_deletedRows; const pending = $_quantityPending && !$_uiSubmitted; const added = $_addedRows.map(row => row.id); const ids = Object.keys($_cellDrafts).filter(id => !deleted.includes(id) && !added.includes(id)); $_changedRows = ids.length + (pending && !ids.includes($_quantityRow) && !deleted.includes($_quantityRow) ? 1 : 0); el.readonly = pending; if (!$_gridEditing && !pending) { const drafts = JSON.parse(JSON.stringify($_cellDrafts)); const submitted = JSON.parse(JSON.stringify($_submittedCells)); const selected = $_selectedRows; const hidden = $_submittedDeletedRows; const rows = JSON.parse(JSON.stringify([...($_showOperationExamples ? $_demoRows : $_stockRows), ...$_submittedAddedRows.filter(row => row.demo === $_showOperationExamples), ...$_addedRows])).filter(row => !hidden.includes(row.id)).map(row => ({...row, ...(submitted[row.id] || {}), ...(drafts[row.id] || {}), _deleted: deleted.includes(row.id), _changeClass: [selected.includes(row.id) ? 'sheet-row-selected' : '', deleted.includes(row.id) ? 'sheet-row-deleted' : added.includes(row.id) ? 'sheet-row-added' : drafts[row.id] ? 'sheet-row-modified' : ''].filter(Boolean).join(' ')})); if (JSON.stringify(el.source) !== JSON.stringify(rows)) el.source = rows; }"
                       :on-editing        "$_gridEditing = evt.detail.editing"
                       :on-select-rows    "$_selectedRows = evt.detail.rowIds"
                       :on-delete-rows    "const added = $_addedRows.map(row => row.id); const removed = evt.detail.rowIds; $_addedRows = $_addedRows.filter(row => !removed.includes(row.id)); const drafts = JSON.parse(JSON.stringify($_cellDrafts)); for (const id of removed) if (added.includes(id)) delete drafts[id]; $_cellDrafts = drafts; $_deletedRows = [...new Set([...$_deletedRows, ...removed.filter(id => !added.includes(id))])]; $_selectedRows = $_selectedRows.filter(id => !removed.includes(id)); $_draftPreview = false; $_gridNotice = ''"
                       :on-add-row        ui-add-row-expr
                       :on-draft          ui-draft-expr
                       :on-reset-drafts   (str "document.querySelector('#stock-revogrid').cancelStockEdit().then(() => { $_cellDrafts = {}; $_addedRows = []; $_deletedRows = []; $_draftPreview = false; $_gridNotice = ''; "
                                               (->expr (@action :tover.actions/discard {:query $query})) " })")
                       :on-preview-drafts (str "if ($_draftPreview) { $_draftPreview = false; } else { const drafts = JSON.parse(JSON.stringify($_cellDrafts)); const added = $_addedRows.map(row => row.id); $_previewAddedRows = JSON.parse(JSON.stringify($_addedRows)).map(row => ({...row, ...(drafts[row.id] || {})})); $_previewCells = Object.entries($_cellDrafts).filter(([rowId]) => !added.includes(rowId)).flatMap(([rowId, fields]) => Object.entries(fields).map(([field, value]) => ({rowId, field, value}))); $_previewBaseline = Object.entries($_submittedCells).flatMap(([rowId, fields]) => Object.entries(fields).map(([field, value]) => ({rowId, field, value}))); if ($_quantityPending && !$_uiSubmitted && $_quantityRow) $_previewCells = [...$_previewCells, {rowId: $_quantityRow, field: 'quantity', value: $_quantityValue}]; $_previewLocalRows = JSON.parse(JSON.stringify([...($_showOperationExamples ? $_demoRows : []), ...$_submittedAddedRows.filter(row => row.demo === $_showOperationExamples)])); $_draftPreview = true; "
                                               (->expr (@view :tover.views/draft-preview {:cells $_previewCells :deletedRows $_deletedRows :baseline $_previewBaseline :localRows $_previewLocalRows :addedRows $_previewAddedRows})) " }")
                       :on-submit         ui-submit-expr
                       :on-preview        (str "evt.detail.changes = {[evt.detail.rowId]: {quantity: evt.detail.quantity}}; " ui-draft-expr)
                       :on-error          "$_gridNotice = evt.detail.message"}
    :smart            (assoc (smart-props interpretation)
                             :on-add-row ui-add-row-expr
                             :source "const submitted = JSON.parse(JSON.stringify($_submittedCells)); const drafts = JSON.parse(JSON.stringify($_cellDrafts)); el.rows = JSON.parse(JSON.stringify([...($_showOperationExamples ? $_demoRows : $_stockRows), ...$_submittedAddedRows.filter(row => row.demo === $_showOperationExamples), ...$_addedRows])).filter(row => !$_submittedDeletedRows.includes(row.id) && !$_deletedRows.includes(row.id)).map(row => ({...row, ...(submitted[row.id] || {}), ...(drafts[row.id] || {})}))")
    :operations       {:examples?          (boolean examples)
                       :on-examples-toggle "$_showOperationExamples = evt.detail.enabled"
                       :source             "el.stockRows = JSON.parse(JSON.stringify($_showOperationExamples ? $_demoRows : $_stockRows)); el.examplesEnabled = $_showOperationExamples; el.examplesDisabled = $_gridEditing || $_changedRows > 0 || $_deletedRows.length > 0 || $_addedRows.length > 0; el.entries = JSON.parse(JSON.stringify([...$_localOperations.filter(entry => entry.demo === $_showOperationExamples), ...($_showOperationExamples ? $_exampleOperations : $_operations)]))"}
    :navigation       {:on-stock "$_view = 'stock'" :on-operations "$_view = 'operations'"}
    :rows             (into {} (map (juxt :id row-props) rows))
    :review           (review-props proposal)
    :on-history-close (->expr (@action :tover.actions/close-history {}))}))


(defn stock-model
  {:malli/schema [:=> [:cat store/Grid :string] schema/UIModel]}
  [grid query]
  (let [interpretation (input/interpret (:rows grid) query)]
    {:rows           (if (= :search (:type interpretation)) (:rows interpretation) (:rows grid))
     :evidence       (:evidence grid)
     :history        {:row nil :history []}
     :editing        nil
     :proposal       nil
     :interpretation interpretation}))


(defn query-stock
  {:malli/schema [:=> [:cat Context schema/StockInput] schema/UIModel]}
  [{:keys [repo] :as context} {:keys [query]}]
  (assoc (stock-model (store/stock-grid repo) query) :operations (operations/query context)))


(defview stock
  {:schema schema/StockInput
   :query  query-stock}
  [model]
  (let [props (smart-props (:interpretation model))]
    [{:type :elements
      :html (ui/smart-result (assoc props :interpretation (:interpretation model)))}
     {:type :signals
      :data {:stockChanged false :_stockRows (sheet-rows model) :_operations (or (:operations model) [])}}]))


(defn review
  {:malli/schema [:=> [:cat [:maybe schema/Proposal]] :map]}
  [proposal]
  {:type :elements
   :html (ui/stock-review {:proposal proposal :review (review-props proposal)})})


(defn query-draft-preview
  {:malli/schema [:=> [:cat Context schema/DraftPreviewInput] [:vector :map]]}
  [context {:keys [cells deletedRows baseline localRows addedRows]}]
  (let [rows (reduce (fn [rows {:keys [rowId field value]}]
                       (if (contains? rows rowId) (assoc-in rows [rowId (keyword field)] value) rows))
                     (into {} (map (juxt :id identity)) (concat (sheet-rows (query-stock context {:query ""})) localRows))
                     baseline)
        deleted (set deletedRows)
        labels {"name"     "Goods"    "quantity" "Quantity" "unit"     "Unit"
                "location" "Location" "article"  "Article"  "evidence" "Evidence"}]
    (vec
      (concat
        (keep (fn [{:keys [rowId field value]}]
                (when-not (contains? deleted rowId)
                  (when-let [row (get rows rowId)]
                    (let [before (get row (keyword field))]
                      (when (not= before value)
                        {:row-id rowId  :name  (:name row) :field (get labels field)
                         :before before :after value})))))
              cells)
        (keep (fn [id]
                (when-let [row (get rows id)]
                  {:row-id id :name (:name row) :field "Row" :deleted? true
                   :before (str (:quantity row) " " (:unit row) " · " (:location row))
                   :after  "Deleted"}))
              deletedRows)
        (map (fn [{:keys [id name quantity unit location article]}]
               {:row-id id :name  (if (str/blank? name) "New row" name) :field "Row" :added? true
                :before "" :after (str/join " · " (remove str/blank? [(str/trim (str quantity " " unit)) location article]))})
             addedRows)))))


(defview draft-preview
  {:schema schema/DraftPreviewInput :query query-draft-preview}
  [cells]
  {:type :elements :html (ui/stock-draft-preview {:cells cells :on-submit ui-submit-expr})})


(defn query-history
  {:malli/schema [:=> [:cat Context schema/HistoryInput] schema/HistoryData]}
  [{:keys [repo]} {:keys [rowId]}]
  (select-keys (store/row-history repo rowId) [:row :history]))


(defview history
  {:schema schema/HistoryInput :query query-history}
  [{:keys [row history]}]
  {:type :elements
   :html (ui/history-panel {:row      row :history history
                            :on-click (->expr (@action :tover.actions/close-history {}))})})
