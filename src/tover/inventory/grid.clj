(ns tover.inventory.grid
  (:require
    [clojure.string :as str]
    [tover.shared.i18n :as i18n]))


(def operation-types
  [[:purchase "Purchase"] [:return "Return"] [:transfer "Transfer"] [:production "Production"]
   [:defect "Defect"] [:sale "Sale"] [:write-off "Write-off"] [:payment "Payment"]])


(defn label
  {:malli/schema [:=> [:cat :any] :string]}
  [value]
  (i18n/t (or (some #(when (= value (first %)) (second %)) operation-types)
              (some-> value name (str/replace "-" " ") str/capitalize) "")))


(def columns
  [{:key "select" :label "" :width 36}
   {:key "type" :label "Operation" :width 180}
   {:key "occurred-at" :label "Date" :width 155}
   {:key "party" :label "Party" :width 150}
   {:key "direction" :label "Direction" :width 95}
   {:key "amount" :label "Amount" :width 95}
   {:key "currency" :label "Currency" :width 75}
   {:key "original-operation-id" :label "Original operation" :width 170}
   {:key "from-location" :label "From" :width 135}
   {:key "to-location" :label "To" :width 135}
   {:key "name" :label "Goods" :width 230}
   {:key "quantity" :label "Quantity" :width 90}
   {:key "unit" :label "Unit" :width 75}
   {:key "location" :label "Location" :width 145}
   {:key "article" :label "Article" :width 135}
   {:key "barcode" :label "Barcode" :width 145}
   {:key "reason" :label "Reason" :width 190}
   {:key "actions" :label "Actions" :width 96}])


(def line-keys
  #{"name" "quantity" "unit" "location" "article" "barcode"})


(defn effective-entry
  {:malli/schema [:=> [:cat :map :map] :map]}
  [draft model]
  (case (get-in draft [:entry :kind])
    :revision (get-in draft [:entry :replacement])
    :cancellation (or (some #(when (= (:id %) (get-in draft [:entry :target-id])) %) (or (:operation-catalog model) (:operations model))) {})
    (:entry draft)))


(defn path-string
  {:malli/schema [:=> [:cat [:sequential :any]] :string]}
  [path]
  (str/join "/" (map #(if (keyword? %) (name %) (str %)) path)))


(defn target
  {:malli/schema [:=> [:cat :map [:vector :any]] :map]}
  [draft path]
  {:id   (:id draft) :revision (:revision draft)
   :path (path-string (into (if (= :revision (get-in draft [:entry :kind])) [:replacement] []) path))})


(defn command
  {:malli/schema [:=> [:cat :map :string :string [:? [:vector :any]]] :map]}
  [draft name title & [path]]
  (cond-> {:type "draft-command" :id (:id draft) :revision (:revision draft) :command name :label (i18n/t title)}
    path (assoc :path (path-string path))))


(defn editable-cell
  {:malli/schema [:=> [:cat :map [:vector :any] :any :string] :map]}
  [draft path value type]
  {:value (str value) :target (target draft path) :editor {:type type}})


(defn section-rows
  {:malli/schema [:=> [:cat :map :map :map] [:vector :map]]}
  [draft entry _model]
  (let [kind        (get-in draft [:entry :kind])
        type        (:type entry)
        correction? (and (= :correction kind) (:stock-row-id entry))
        section     (fn [path]
                      (let [lines (map-indexed (fn [index line] {:line line :path (conj path index) :add-line? (zero? index)}) (get-in entry path))]
                        (if (seq lines) (vec lines) [{:path path :add-line? true}])))]
    (cond
      (= :cancellation kind) [{:summary (i18n/t "Cancel {type}" {:type (label type)})}]
      (= :payment type) [{}]
      correction? [{:line (last (:lines entry)) :path [:lines (max 0 (dec (count (:lines entry))))] :correction? true}]
      (= :production type) (vec (concat (map #(assoc % :production-role :input) (section [:inputs]))
                                        (map #(assoc % :production-role :output) (section [:outputs]))))
      :else (section [:lines]))))


(defn draft-columns
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [{:keys [drafts] :as model}]
  (let [entries (map #(effective-entry % model) drafts)
        types   (set (map :type entries))
        used    (cond-> #{"select" "type" "occurred-at" "reason" "actions"}
                  (some #(not= :payment (:type %)) entries) (into ["name" "quantity" "unit" "article" "barcode"])
                  (some #(not (#{:payment :transfer} (:type %))) entries) (conj "location")
                  (some types [:purchase :sale :return :payment]) (conj "party")
                  (some types [:return :payment]) (into ["direction" "original-operation-id"])
                  (some types [:purchase :sale :payment]) (into ["amount" "currency"])
                  (types :transfer) (into ["from-location" "to-location"]))]
    (i18n/translate-labels (filterv #(used (:key %)) columns))))


(defn shared-cell
  {:malli/schema [:=> [:cat :map :map :string :map] :map]}
  [draft entry key model]
  (let [kind        (get-in draft [:entry :kind])
        type        (:type entry)
        value       (get entry (keyword key))
        applicable? (case key
                      "party" (#{:purchase :sale :return :payment} type)
                      "direction" (#{:return :payment} type)
                      ("amount" "currency") (#{:purchase :sale :payment} type)
                      "original-operation-id" (#{:return :payment} type)
                      ("from-location" "to-location") (= :transfer type)
                      true)
        cell        (when applicable? (editable-cell draft [(keyword key)] value (case key "occurred-at" "date" "amount" "decimal" "text")))
        result      (case key
                      "select" {:value false :selection {:id (:id draft)}}
                      "reason" (assoc (editable-cell draft [:reason] (get-in draft [:entry :reason]) "text")
                                      :target {:id (:id draft) :revision (:revision draft) :path "reason"})
                      "type" (if (and (= :operation (:kind entry)) (not= :cancellation kind))
                               (assoc (editable-cell draft [:type] (some-> type name) "select")
                                      :display (str (when (= :revision kind) (i18n/t "Correction · ")) (label type))
                                      :editor {:type "select" :options (mapv (fn [[id title]] {:value (name id) :label (i18n/t title)}) operation-types)})
                               {:value (str (when (#{:revision :cancellation} kind) (str (if (= :revision kind) (i18n/t "Correction") (label kind)) " · "))
                                            (label (if (= :operation (:kind entry)) type (:kind entry))))})
                      "direction" (if applicable? (assoc cell :value (some-> value name) :display (label value)
                                                         :editor {:type "select" :options [{:value "incoming" :label (i18n/t "Incoming")} {:value "outgoing" :label (i18n/t "Outgoing")}]}) {})
                      "original-operation-id" (if applicable?
                                                (let [options (into [{:value "" :label (i18n/t "None")}]
                                                                    (for [operation (or (:operation-catalog model) (:operations model)) :when (#{:purchase :sale} (:type operation))]
                                                                      {:value (:id operation) :label (str (label (:type operation)) " · " (:party operation))}))]
                                                  (assoc cell :display (or (some #(when (= (:value %) value) (:label %)) options) (i18n/t "None"))
                                                         :editor {:type "operation-reference" :options options})) {})
                      (if (= :cancellation kind)
                        (cond-> {:value (str value)} (= key "occurred-at") (assoc :date true))
                        (or cell {})))]
    (if (and (= :cancellation kind) (not (#{"select" "actions" "reason"} key)))
      (dissoc result :target :editor)
      result)))


(defn line-cell
  {:malli/schema [:=> [:cat :map :map :map :string :map] :map]}
  [draft entry {:keys [line path correction?]} key model]
  (let [field      (keyword key)
        field-path (cond
                     (and correction? (= key "quantity")) [:stock-count]
                     (and correction? (= key "location")) [:stock-location]
                     :else (conj (vec path) field))
        value      (cond
                     (and correction? (= key "quantity")) (:stock-count entry)
                     (and correction? (= key "location")) (:stock-location entry)
                     :else (get line field))
        cell       (editable-cell draft field-path value (case key "quantity" "quantity" "text"))]
    (cond
      (not line) {}
      (and (= key "location") (= :transfer (:type entry))) {}
      (= key "name") (assoc cell :itemTarget (target draft (conj path :item-id)) :itemValue (:item-id line)
                            :editor {:type "goods" :options (mapv (fn [[id item]]
                                                                    {:value id :label (str (:name item) (when-not (str/blank? (:article item)) (str " · " (:article item))))})
                                                                  (sort-by #(get-in % [1 :name]) (:items model)))})
      (and (= key "unit") (not= value (i18n/unit-label value))) (assoc cell :display (i18n/unit-label value))
      :else cell)))


(defn row-actions
  {:malli/schema [:=> [:cat :map :map :int] :map]}
  [draft {:keys [line path add-line? correction? production-role]} index]
  (let [editable? (not= :cancellation (get-in draft [:entry :kind]))]
    {:value   ""
     :actions (vec (concat
                     (when (and editable? (not correction?) add-line?)
                       [(assoc (command draft "add-line" (i18n/t "Add {type}" {:type (i18n/t (if production-role (name production-role) "item"))})
                                        (if line (pop path) path)) :icon "+")])
                     (when (and editable? line (not correction?))
                       [(assoc (command draft "remove-line" (i18n/t "Remove {type}" {:type (i18n/t (if production-role (name production-role) "item"))}) path) :icon "−")])
                     (when (zero? index)
                       [(assoc (command draft "discard" (i18n/t "Discard operation")) :icon "×")])))}))


(defn drafts
  "Project shared drafts into display rows, merged cells and stable server edit targets."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [drafts] :as model}]
  (let [columns (i18n/translate-labels (or (:columns model) (draft-columns model)))]
    (reduce
      (fn [result draft]
        (let [entry  (effective-entry draft model)
              rows   (section-rows draft entry model)
              rows   (if (seq rows) rows [{}])
              start  (count (:rows result))
              cells  (mapv (fn [index row]
                             {:id        (str (:id draft) "/" index) :groupId (:id draft)
                              :className "inventory-pending-row"
                              :cells     (mapv (fn [{:keys [key]}]
                                                 (let [cell (cond
                                                              (line-keys key) (line-cell draft entry row key model)
                                                              (= key "actions") (row-actions draft row index)
                                                              (zero? index) (or (shared-cell draft entry key model) {})
                                                              :else {})]
                                                   (cond-> cell
                                                     (and (:production-role row) (or (line-keys key) (= "actions" key)))
                                                     (assoc :className (str "inventory-production-" (name (:production-role row)))
                                                            :title (if (= :input (:production-role row)) (i18n/t "Consumed input") (i18n/t "Created output")))))) columns)})
                           (range) rows)
              merges (when (> (count rows) 1)
                       (keep-indexed (fn [col column]
                                       (when-not (or (line-keys (:key column)) (= "actions" (:key column)))
                                         {:row start :col col :rowspan (count rows) :colspan 1})) columns))]
          (-> result (update :rows into cells) (update :merges into merges))))
      {:id "shared-draft" :columns columns :rows [] :merges [] :editable true :rowHeaders false} drafts)))
