(ns tover.catalogues.ui
  (:require
    [bel.expressions :refer [->expr]]
    [bel.nav :as nav]
    [charred.api :as charred]
    [tover.catalogues.schema :as catalogue]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as shared]))


(defn grid
  {:malli/schema [:=> [:cat :keyword :map] :map]}
  [kind {:keys [rows viewport pending missingRows]}]
  (let [columns   (get catalogue/columns kind)
        row-model (fn [row]
                    {:id    (:id row) :groupId (:id row) :label (:name row)
                     :cells (mapv (fn [[field _]]
                                    (let [related (catalogue/references field)
                                          value   (get row field "")]
                                      (cond-> {:value  value
                                               :target {:type "catalogue-edit" :id   (:id row)   :rowId (:id row) :revision (:revision row)
                                                        :path (name field)     :kind (name kind)}
                                               :editor {:type "text"}}
                                        related (assoc :display (get-in row [:labels field] "")
                                                       :editor {:type    "catalogue-reference" :valueType "id"
                                                                :options (if (seq value) [{:value value :label (get-in row [:labels field])}] [])})
                                        related (assoc-in [:target :lookupKind] (name related))
                                        (= field :purpose) (assoc :display (i18n/t (case value "storage" "Storage" "sales" "Sales" "production" "Production" ""))
                                                                  :editor {:type    "select" :allowEmpty true
                                                                           :options (mapv (fn [[value label]] {:value value :label (i18n/t label)})
                                                                                          [["" "None"] ["storage" "Storage"] ["sales" "Sales"] ["production" "Production"]])})))) columns)})]
    {:id          (str "catalogue-" (name kind)) :editable true     :rowHeaders true :merges []
     :columns     (mapv (fn [[key label]] {:key     (name key) :label     (i18n/t label) :width (if (= key :name) 280 180)
                                           :sortKey (name key) :filterKey (name key)}) columns)
     :rows        (mapv (fn [index row] (assoc (row-model row) :rowIndex index :groupIndex index)) (range (:start viewport 0) (:end viewport (count rows))) rows)
     :editStates  (vec (mapcat (comp #(map (fn [cell] (select-keys cell [:value :target])) %) :cells row-model) pending))
     :missingRows (or missingRows []) :viewport            viewport}))


(defn page
  {:malli/schema [:=> [:cat :map] shared/Hiccup]}
  [{:keys [kind model query sort direction filters layouts]}]
  [:catalogue-workbench.catalogue-workbench
   {:id (str "catalogue-workbench-" (name kind))
    :data-kind               (name kind) :data-model (charred/write-json-str model)
    :data-on:catalogue-query
    (->expr (.catch (@view :tover.catalogues.web/table evt.detail {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0})
                    (fn [_] (.requestFailed el "read"))))
    :data-on:catalogue-window
    (->expr (.catch (@view :tover.catalogues.web/window evt.detail {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0})
                    (fn [_] (.requestFailed el "window"))))
    :data-on:catalogue-choices
    (->expr (.catch (@view :tover.catalogues.web/choices evt.detail {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0})
                    (fn [_] (.requestFailed el "choices"))))
    :data-on:catalogue-save
    (->expr (.catch (@action :tover.catalogues.web/save evt.detail {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0})
                    (fn [_] (.requestFailed el "save"))))
    :data-on:inventory-layout
    (->expr (set! $_layoutStatus "Saving")
      (.catch (@action :tover.stock.web/save-layout evt.detail {:requestCancellation "cleanup" :retry "never" :retryMaxCount 0})
              (fn [_] (set! $_layoutStatus "Failed"))))
    :data-query              (charred/write-json-str {:kind (name kind) :query (or query "") :sort (or sort "name") :direction (or direction "asc") :filters (or filters {})})
    :data-signals__ifmissing (charred/write-json-str {:_catalogueResponseJson "null" :_catalogueWindowJson                    "null"  :_catalogueChoicesJson "null" :_catalogueSaveJson "null"
                                                      :_columnLayoutsJson     (charred/write-json-str layouts) :_layoutStatus "Saved" :inventoryChanged      ""})
    :data-effect             "el.catalogueResponse=JSON.parse($_catalogueResponseJson); el.catalogueWindow=JSON.parse($_catalogueWindowJson); el.catalogueChoices=JSON.parse($_catalogueChoicesJson); el.catalogueSave=JSON.parse($_catalogueSaveJson); el.catalogueChanged=$inventoryChanged; el.catalogueLayouts=$_columnLayoutsJson; el.catalogueLayoutStatus=$_layoutStatus"}
   [:nav.catalogue-tabs {:aria-label (i18n/t "Catalogues")}
    (for [[key label] catalogue/kinds]
      [:a (nav/link (str "/catalogues/" (name key)) {:aria-current (when (= key kind) "page")}) (i18n/t label)])]
   [:div.catalogue-tools
    [:input.catalogue-search {:type "search" :aria-label (i18n/t "Search catalogue") :placeholder (i18n/t "Search by name…") :value (or query "")}]
    [:button.catalogue-reset {:type "button"} (i18n/t "Reset all")]
    [:span.catalogue-save-status.quiet {:role "status"} (i18n/t "Saved")]
    [:button.sheet-add-row.catalogue-add {:type "button" :aria-label (i18n/t "Add row")} "+ " (i18n/t "Add row")]]
   [:div.catalogue-notice {:role "status"}]
   [:inventory-grid.catalogue-new-row {:id (str "catalogue-new-row-" (name kind)) :hidden true :data-ignore-morph "" :aria-label (i18n/t "New catalogue record")}]
   [:inventory-grid.catalogue-grid {:id (str "catalogue-grid-" (name kind)) :data-ignore-morph "" :aria-label (i18n/t (second (some #(when (= kind (first %)) %) catalogue/kinds)))}]])
