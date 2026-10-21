(ns tover.stock.ui
  (:require
    [bel.validation :as validation]
    [malli.transform :as mt]
    [tover.shared.schema :as shared]
    [tover.stock.schema :as schema])
  (:import
    [java.net URLEncoder]
    [java.nio.charset StandardCharsets]))


(defn element-id
  {:malli/schema [:=> [:cat :string shared/Text] :string]}
  [prefix id]
  (str prefix (URLEncoder/encode ^String id StandardCharsets/UTF_8)))


(defn smart-result
  {:malli/schema [:=> [:cat [:map
                             [:interpretation schema/Interpretation]]]
                  shared/Hiccup]}
  [{:keys [interpretation choices]}]
  [:div#smart-result
   (case (:type interpretation)
     :adjustment [:div#evidence-parse (str "Inventory adjustment: " (get-in interpretation [:row :name])
                                           " → " (:after interpretation) " " (get-in interpretation [:row :unit])
                                           ". Press Enter to preview.")]
     :choose (into [:div#evidence-options {:aria-label "Choose goods"}]
                   (map (fn [row]
                          [:button {:id            (element-id "choose-" (:id row)) :type "button"
                                    :data-on:click (get choices (:id row))}
                           [:span (:name row)] [:small (str (:article row) " · " (:location row))]])
                        (:matches interpretation)))
     :error [:div#evidence-parse.error {:role "status"} (:message interpretation)]
     nil)])


(defn smart-entry
  {:malli/schema [:=> [:cat :map] shared/Hiccup]}
  [{:keys [source on-add-row]}]
  [:section.smart-entry {:aria-label "Smart input"}
   [:stock-entry#stock-entry {:data-ignore-morph "" :data-effect source :data-on:row-add on-add-row}]
   [:div#smart-result]])


(defn adjustment-review
  {:malli/schema [:=> [:cat [:map
                             [:proposal schema/Proposal]]]
                  shared/Hiccup]}
  [{:keys [proposal value bind on-accept on-discard disabled?]}]
  (let [{:keys [row before after]} proposal
        delta (- after before)]
    [:tr.inline-diff {:id (element-id "review-" (:id row))}
     [:td {:colspan 5}
      [:div.diff-title [:strong "Inventory adjustment"] [:span.quiet "Manual edit · awaiting acceptance"]]
      [:div.diff-caption (:name row) " · " (:location row)]
      [:table.diff-table {:aria-label "Adjustment preview"}
       [:tbody
        [:tr.removed [:td "Accepted quantity"] [:td.numeric (str before " " (:unit row))]]
        [:tr.added [:td "Proposed quantity"] [:td.numeric (str after " " (:unit row))]]]]
      [:div.diff-caption (str "Change: " (when (pos? delta) "+") delta " " (:unit row)
                              ". Accepting records an adjustment and recalculates stock.")]
      [:div.action-row
       [:input.reason-input
        {:id          "adjustment-reason"           :aria-label "Adjustment reason"
         :placeholder "Reason for this adjustment…" :maxlength  500                 :value value :data-bind bind}]
       [:button.primary-action {:type "button" :data-on:click on-accept :disabled disabled?} "Accept adjustment"]
       [:button {:type "button" :data-on:click on-discard} "Discard"]]]]))


(defn accepted-quantity
  {:malli/schema [:=> [:cat [:map
                             [:row schema/Row]]]
                  shared/Hiccup]}
  [{:keys [row on-click disabled?]}]
  (let [{:keys [id name quantity]} row]
    [:button.number-button
     {:id            (element-id "accepted-quantity-" id) :type "button" :aria-label (str "Edit quantity for " name)
      :data-on:click on-click :disabled disabled?}
     quantity]))


(defn evidence
  {:malli/schema [:=> [:cat [:map
                             [:row schema/Row]
                             [:latest [:maybe schema/HistoryEntry]]]]
                  shared/Hiccup]}
  [{:keys [row latest on-click]}]
  [:button.source-link {:id (element-id "evidence-" (:id row)) :type "button" :data-on:click on-click}
   (if latest "Manual edit" "Opening declaration")])


(defn quantity-editor
  {:malli/schema [:=> [:cat [:map
                             [:row schema/Row]
                             [:editing [:maybe schema/Editing]]]]
                  shared/Hiccup]}
  [{:keys [row editing value bind init on-keydown on-preview]}]
  (let [{:keys [id name]} row]
    [:div.cell-actions {:id (element-id "editor-" id)}
     (when (= id (get-in editing [:row :id]))
       [:input.quantity-input
        {:id        (element-id "quantity-" id) :aria-label (str "Quantity for " name)
         :type      "text" :inputmode "numeric" :autocomplete "off"      :value (str value)
         :data-bind bind :data-init init :data-on:keydown     on-keydown}])
     (when (= id (get-in editing [:row :id]))
       [:button {:type "button" :aria-label (str "Preview quantity for " name) :data-on:click on-preview} "Preview"])]))


(defn stock-row
  {:malli/schema [:=> [:cat [:map
                             [:row schema/Row]
                             [:editing [:maybe schema/Editing]]
                             [:change [:maybe schema/Proposal]]
                             [:latest [:maybe schema/HistoryEntry]]]]
                  shared/Hiccup]}
  [{:keys [row change latest on-history on-source on-edit disabled?] :as props}]
  (let [{:keys [id name article location unit]} row
        proposal? (= id (:row-id change))]
    [:tr {:id (element-id "stock-" id) :class (when proposal? "has-proposal")}
     [:td [:button.goods-button
           {:type "button" :aria-label (str "History for " name) :data-on:click on-history} name]
      [:span.subline unit]]
     [:td.numeric
      (accepted-quantity {:row row :on-click on-edit :disabled? disabled?})
      (quantity-editor props)]
     [:td location]
     [:td article]
     [:td (evidence {:row row :latest latest :on-click (or on-source on-history)})]]))


(defn item-history
  {:malli/schema [:=> [:cat [:map
                             [:row schema/Row]
                             [:history [:vector schema/HistoryEntry]]]]
                  shared/Hiccup]}
  [{:keys [row history on-click]}]
  [:section.item-history {:aria-label (str "History for " (:name row))}
   [:div.history-heading [:strong (str (:name row) " · history")]
    [:button {:type "button" :data-on:click on-click} "Close"]]
   (into [:ul.history-list]
         (concat
           (map (fn [entry]
                  [:li {:id (element-id "history-" (validation/encode! :uuid (:id entry) (mt/string-transformer)))}
                   [:span (str (:before entry) " → " (:after entry) " " (:unit row) " · Inventory adjustment")]
                   [:span.subline (str (:reason entry) " · " (:recorded-at entry))]])
                history)
           [[:li "Opening declaration" [:span.subline "Local seed data · origin and cost unknown"]]]))])


(defn history-panel
  {:malli/schema [:=> [:cat [:map
                             [:row [:maybe schema/Row]]
                             [:history [:vector schema/HistoryEntry]]]]
                  shared/Hiccup]}
  [{:keys [row] :as props}]
  [:div#history-panel (when row (item-history props))])


(defn empty-stock-row
  {:malli/schema [:=> [:cat]
                  shared/Hiccup]}
  []
  [:tr#stock-empty [:td.no-rows {:colspan 5} "No matching goods."]])


(defn stock-grid
  {:malli/schema [:=> [:cat [:map
                             [:model schema/UIModel]]]
                  shared/Hiccup]}
  [{:keys [model] :as props}]
  (let [{:keys [rows editing proposal evidence]} model]
    (into [:tbody#stock-rows]
          (if (seq rows)
            (mapcat (fn [row]
                      (cond-> [(stock-row (assoc (get-in props [:rows (:id row)] {})
                                                 :row row :editing editing :change proposal
                                                 :latest (get evidence (:id row))))]
                        (= (:id row) (:row-id proposal))
                        (conj (adjustment-review (assoc (:review props) :proposal proposal)))))
                    rows)
            [(empty-stock-row)]))))


(defn footer
  {:malli/schema [:=> [:cat :map]
                  shared/Hiccup]}
  [{:keys [notice error?]}]
  [:footer.app-footer#stock-footer {:data-show "!$_uiSubmitted"}
   [:span#evidence-notice {:aria-live "polite" :class (when error? "error")}
    notice]])


(defn stock-review
  {:malli/schema [:=> [:cat [:map [:proposal [:maybe schema/Proposal]]]] shared/Hiccup]}
  [{:keys [proposal review]}]
  (cond-> [:div#stock-review]
    proposal (conj {:data-show "!$_uiSubmitted"})
    true (conj (when proposal
                 [:table.work-sheet {:aria-label "Inventory adjustment review"}
                  [:tbody (adjustment-review (assoc review :proposal proposal))]]))))


(defn sheet-icon
  {:malli/schema [:=> [:cat [:map [:name :keyword]]] shared/Hiccup]}
  [{:keys [name]}]
  [:svg {:viewBox        "0 0 24 24" :fill            "none"  :stroke      "currentColor" :stroke-width 1.6
         :stroke-linecap "round"     :stroke-linejoin "round" :aria-hidden true}
   (case name
     :modified [:path {:d "m16 3 5 5-12 12-6 1 1-6L16 3Z m-2 2 5 5"}]
     :deleted [:path {:d "M5 12h14"}]
     :added [:path {:d "M5 12h14 M12 5v14"}]
     :revert [:path {:d "m9 4-5 5 5 5 M4 9h10a5.5 5.5 0 0 1 0 11h-3"}]
     :preview [:g [:path {:d "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z"}]
               [:circle {:cx 12 :cy 12 :r 3}]]
     :submit [:path {:d "M12 21V3 m-7 7 7-7 7 7"}])])


(defn stock-draft-preview
  {:malli/schema [:=> [:cat [:map [:cells [:vector :map]]]] shared/Hiccup]}
  [{:keys [cells on-submit]}]
  [:section#stock-draft-preview.sheet-preview {:data-show "$_draftPreview" :style "display:none" :aria-label "Change preview"}
   [:table.sheet-diff-table
    [:thead [:tr [:th "Goods"] [:th "Field"] [:th "Before"] [:th "After"]]]
    (into [:tbody]
          (map (fn [{:keys [row-id name field before after deleted? added?]}]
                 [:tr {:id (element-id "draft-cell-" (str row-id "/" field)) :class (cond deleted? "is-deleted" added? "is-added")}
                  [:td name] [:td field]
                  [:td.sheet-diff-before (if (= "" before) "—" before)]
                  [:td.sheet-diff-after (if (= "" after) "—" after)]]))
          cells)]
   [:div.sheet-preview-actions
    [:span.quiet "Changes last until you reload."]
    [:button.sheet-submit-preview {:type               "button" :data-on:click on-submit
                                   :data-attr:disabled "$_gridEditing || ($_changedRows === 0 && $_deletedRows.length === 0 && $_addedRows.length === 0)"}
     "Submit changes"]]])


(defn stock-sheet
  {:malli/schema [:=> [:cat :map] shared/Hiccup]}
  [{:keys [source on-preview on-editing on-draft on-select-rows on-delete-rows on-add-row on-reset-drafts on-preview-drafts on-submit on-error]}]
  [:section.stock-sheet {:aria-label "Stock spreadsheet"}
   [:div.sheet-toolbar
    [:div.sheet-change-status {:aria-live "polite"}
     [:span.quiet {:data-show "$_changedRows === 0 && $_deletedRows.length === 0 && $_addedRows.length === 0"} "No changes"]
     [:span.sheet-count.is-modified {:data-show            "$_changedRows > 0 || $_deletedRows.length > 0 || $_addedRows.length > 0"  :role "img"
                                     :data-attr:title      "$_changedRows + ($_changedRows === 1 ? ' row changed' : ' rows changed')"
                                     :data-attr:aria-label "$_changedRows + ($_changedRows === 1 ? ' row changed' : ' rows changed')"}
      (sheet-icon {:name :modified}) [:span {:data-text "$_changedRows" :aria-hidden true}]]
     [:span.sheet-count.is-deleted {:data-show            "$_changedRows > 0 || $_deletedRows.length > 0 || $_addedRows.length > 0"                :role "img"
                                    :data-attr:title      "$_deletedRows.length + ($_deletedRows.length === 1 ? ' row deleted' : ' rows deleted')"
                                    :data-attr:aria-label "$_deletedRows.length + ($_deletedRows.length === 1 ? ' row deleted' : ' rows deleted')"}
      (sheet-icon {:name :deleted}) [:span {:data-text "$_deletedRows.length" :aria-hidden true}]]
     [:span.sheet-count.is-added {:data-show            "$_changedRows > 0 || $_deletedRows.length > 0 || $_addedRows.length > 0"        :role "img"
                                  :data-attr:title      "$_addedRows.length + ($_addedRows.length === 1 ? ' row added' : ' rows added')"
                                  :data-attr:aria-label "$_addedRows.length + ($_addedRows.length === 1 ? ' row added' : ' rows added')"}
      (sheet-icon {:name :added}) [:span {:data-text "$_addedRows.length" :aria-hidden true}]]
     [:span.sheet-preview-note {:data-show "$_gridNotice !== ''" :data-text "$_gridNotice"}]]
    [:div.sheet-actions
     [:button.sheet-add-row {:type "button" :data-on:click on-add-row :data-attr:disabled "$_gridEditing || ($_quantityPending && !$_uiSubmitted)"}
      (sheet-icon {:name :added}) "Add row"]
     [:button.sheet-icon-button {:type          "button"        :title              "Revert all changes" :aria-label "Revert all changes"
                                 :data-on:click on-reset-drafts :data-attr:disabled "$_changedRows === 0 && $_deletedRows.length === 0 && $_addedRows.length === 0"}
      (sheet-icon {:name :revert})]
     [:button.sheet-icon-button {:type                 "button"          :title                  "Preview changes" :aria-label "Preview changes"
                                 :data-on:click        on-preview-drafts :data-attr:disabled     "$_changedRows === 0 && $_deletedRows.length === 0 && $_addedRows.length === 0"
                                 :data-class:is-active "$_draftPreview"  :data-attr:aria-pressed "$_draftPreview"}
      (sheet-icon {:name :preview})]
     [:button.sheet-icon-button.is-submit {:type               "button" :title "Submit changes (UI preview)" :aria-label "Submit changes"
                                           :data-on:click      on-submit
                                           :data-attr:disabled "$_gridEditing || ($_changedRows === 0 && $_deletedRows.length === 0 && $_addedRows.length === 0)"}
      (sheet-icon {:name :submit})]]]
   (stock-draft-preview {:cells [] :on-submit on-submit})
   [:div.sheet-grid-host
    [:revo-grid#stock-revogrid
     {:aria-label               "RevoGrid stock spreadsheet" :data-ignore-morph ""
      :data-stock-grid          ""
      :data-effect              source :data-on:quantity-preview                on-preview     :data-on:grid-editing on-editing
      :data-on:cells-draft      on-draft :data-on:rows-select                   on-select-rows :data-on:rows-delete  on-delete-rows
      :data-on:stock-edit-error on-error}]]])
