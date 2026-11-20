(ns tover.catalogues.ui
  "Catalogue grid model for the goods rename boundary. ponytail: delete with the rename slice."
  (:require
    [tover.catalogues.schema :as catalogue]
    [tover.shared.i18n :as i18n]))


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


