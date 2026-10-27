(ns tover.inventory.smart
  (:require
    [clojure.string :as str]
    [tover.inventory.completion :as completion]
    [tover.inventory.filters :as column-filters]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]))


(def operation-types
  [[:purchase "Purchase"] [:return "Return"] [:transfer "Transfer"] [:production "Production"]
   [:defect "Defect"] [:sale "Sale"] [:write-off "Write-off"] [:payment "Payment"]])


(defn entry-fields
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [entry]
  (let [prefix  (if (= :revision (:kind entry)) "replacement/" "")
        entry   (if (= :revision (:kind entry)) (:replacement entry) entry)
        type    (:type entry)
        common  [{:key "occurred-at" :label (i18n/t "Occurred at") :description (i18n/t "Local date and time") :date true}
                 {:key "reason" :label (i18n/t "Reason") :description (i18n/t "Reason for this record")}]
        headers (concat
                  (when (#{:purchase :sale :return :payment} type) [{:key "party" :label (i18n/t "Party")}])
                  (when (#{:return :payment} type) [{:key "direction" :label (i18n/t "Direction")} {:key "original-operation-id" :label (i18n/t "Original operation")}])
                  (when (#{:purchase :sale :payment} type) [{:key "amount" :label (i18n/t "Amount")} {:key "currency" :label (i18n/t "Currency")}])
                  (when (= :transfer type) [{:key "from-location" :label (i18n/t "From location")} {:key "to-location" :label (i18n/t "To location")}])
                  common)
        lines   (for [group       (if (= :production type) [:inputs :outputs] [:lines])
                      [index _]   (map-indexed vector (get entry group))
                      [key label] [["name" (i18n/t "Goods")] ["quantity" (i18n/t "Quantity")] ["location" (i18n/t "Location")] ["article" (i18n/t "Article")] ["barcode" (i18n/t "Barcode")] ["unit" (i18n/t "Unit")]]]
                  {:key   (str (name group) "/" index "/" key)
                   :label (str (when (= :production type) (if (= :inputs group) (str (i18n/t "Input {field}" {:field ""})) (str (i18n/t "Output {field}" {:field ""})))) label
                               (when (pos? index) (str " " (inc index))))})]
    (mapv #(update % :key (partial str prefix)) (concat headers lines))))


(defn path
  {:malli/schema [:=> [:cat :string] [:vector :any]]}
  [key]
  (mapv #(if (re-matches #"\d+" %) (Long/parseLong %) (keyword %)) (str/split key #"/")))


(defn field-values
  {:malli/schema [:=> [:cat :map :map] [:vector :map]]}
  [snapshot field]
  (or (get-in snapshot [:field-options (:key field)])
      (let [leaf   (last (path (:key field)))
            nodes  (filter map? (tree-seq coll? seq (select-keys snapshot [:rows :operations :drafts])))
            values (case leaf
                     :direction ["incoming" "outgoing"]
                     :currency (concat ["EUR" "USD" "GBP" "RUB"] (keep :currency nodes))
                     :name (map :name (vals (:items snapshot)))
                     (keep #(get % leaf) nodes))]
        (if (= :name leaf)
          (mapv (fn [[id item]]
                  {:kind        "value" :key (:key field) :value (:name item) :itemId id :label (:name item)
                   :description (or (not-empty (:article item)) (i18n/t "Existing item"))})
                (sort-by #(get-in % [1 :name]) (:items snapshot)))
          (->> values (remove nil?) (map #(if (keyword? %) (name %) (str %))) (remove str/blank?) distinct sort
               (mapv (fn [value] {:kind        "value" :key (:key field) :value (if (= :type leaf) (i18n/t (str/capitalize value)) value)
                                  :label       (case leaf :unit (i18n/unit-label value)
                                                     (:direction :type) (i18n/t (str/capitalize value)) value)
                                  :description (i18n/t "Existing value")})))))))

(defn populated?
  {:malli/schema [:=> [:cat :any] :boolean]}
  [value]
  (and (some? value) (not (str/blank? (str value)))))


(defn composition
  {:malli/schema [:=> [:cat :map [:maybe :string]] [:maybe :map]]}
  [snapshot id]
  (some #(when (= id (:id %)) %) (:compositions snapshot)))


(defn resumed-state
  {:malli/schema [:=> [:cat :map] :map]}
  [draft]
  {:mode "add" :id (:id draft) :revision (:revision draft)})


(defn model
  {:malli/schema [:=> [:cat :map :map :string :map :int] :map]}
  [snapshot state text filters request]
  (let [state        (merge {:mode "filter" :id nil :revision nil :field nil :replacing nil :showErrors false :conflict nil} state)
        filters      (column-filters/check! (or (:view state) (:view snapshot) "stock") filters)
        draft        (composition snapshot (:id state))
        unavailable? (and (#{"add" "unavailable"} (:mode state)) (nil? draft))
        state        (if unavailable? (assoc state :mode "unavailable") state)
        fields       (if (= "add" (:mode state)) (entry-fields (:entry draft))
                         (column-filters/columns (or (:view state) (:view snapshot) "stock")))
        field        (some #(when (= (:field state) (:key %)) %) fields)
        term         (str/lower-case (str/trim text))
        matching?    #(or (str/blank? term) (str/includes? (str/lower-case (str (:label %) " " (:key %) " " (:description %))) term))
        available    (if draft (remove #(populated? (get-in (:entry draft) (path (:key %)))) fields) fields)
        values       (when field (filter matching? (field-values snapshot field)))
        replacement? (and (not draft) (column-filters/complex? filters (or (:key field) "name")))
        typed        (when (and field (not (str/blank? text)) (not-any? #(= text (:value %)) values))
                       [{:kind        "value" :key (:key field) :value text
                         :label       (if (= :unit (last (path (:key field)))) (i18n/unit-label text) text)
                         :description (i18n/t "Use this value")}])
        check        (when draft (completion/check (:entry draft) (set (keys (:items snapshot)))))
        done?        (boolean (:valid? check))
        options      (cond
                       unavailable? [{:kind "restart" :label (i18n/t "Start again") :description (i18n/t "This unfinished operation is no longer available")}]
                       (= "types" (:mode state))
                       (for [[type label] operation-types :when (or (#{"add" "ass"} term) (str/blank? term) (str/includes? (str/lower-case (i18n/t label)) term))]
                         {:kind "type" :type (name type) :label (i18n/t label) :description (i18n/t "Add operation")})
                       (= "resumes" (:mode state))
                       (for [item (sort-by :saved-at #(compare %2 %1) (:compositions snapshot))]
                         {:kind        "resume"              :id      (:id item)       :label (or (some #(when (= (first %) (get-in item [:entry :type])) (i18n/t (second %))) operation-types) (i18n/t "Operation"))
                          :description (i18n/t "Last saved") :savedAt (:saved-at item)})
                       field (map #(cond-> % replacement? (assoc :kind "replace-filter" :description (i18n/t "Replace this column’s filter with begins…"))) (concat values typed))
                       (and (= "filter" (:mode state)) (#{"add" "ass"} term))
                       (for [[type label] operation-types] {:kind "type" :type (name type) :label (i18n/t label) :description (i18n/t "Add operation")})
                       (and (= "filter" (:mode state)) (str/blank? term))
                       [{:kind "add" :label (i18n/t "Add operation") :description (i18n/t "Choose an operation type")}]
                       :else
                       (concat
                         (when (and (= "add" (:mode state)) done?)
                           [{:kind "done" :label (i18n/t "Done") :description (i18n/t "Add complete operation to pending changes · ⌘/Ctrl Enter")}])
                         (when (and (= "filter" (:mode state)) (or (str/blank? term) (str/starts-with? "add" term) (= "ass" term)))
                           [{:kind "add" :label (i18n/t "Add operation") :description (i18n/t "Choose an operation type")}])
                         (for [item available :when (matching? item)] (assoc item :kind "field"))
                         (when (and (= "filter" (:mode state)) (not (str/blank? term)))
                           [{:kind        (if replacement? "replace-filter" "value") :key "name" :value text :label text
                             :description (if replacement? (i18n/t "Replace Goods filter with begins…") (i18n/t "Goods begins with"))}])
                         (when (and (= "filter" (:mode state)) (seq (:compositions snapshot)) (or (str/blank? term) (str/includes? (i18n/t "resume unfinished") term)))
                           [{:kind "resumes" :label (i18n/t "Resume unfinished…") :description (i18n/t "{count} saved operations" {:count (count (:compositions snapshot))})}])
                         (when (= "add" (:mode state))
                           (concat
                             (for [group (if (= :production (get-in draft [:entry :type])) ["inputs" "outputs"]
                                             (when-not (= :payment (get-in draft [:entry :type])) ["lines"]))]
                               {:kind "line" :key group :label (i18n/t "Add {type}" {:type (i18n/t (case group "inputs" "input" "outputs" "output" "item"))}) :description (i18n/t "Another item line")})
                             (for [group (if (= :production (get-in draft [:entry :type])) [:inputs :outputs] [:lines])
                                   index (range 1 (count (get-in draft [:entry group])))]
                               {:kind        "remove-line" :key (str (name group) "/" index)
                                :label       (str (i18n/t "Remove {type}" {:type (i18n/t (case group :inputs "input" :outputs "output" "item"))}) " " (inc index))
                                :description (i18n/t "Remove this extra line")})))))
        chips        (if draft
                       (into [{:key "" :label (i18n/t "Add") :value (or (some #(when (= (first %) (get-in draft [:entry :type])) (i18n/t (second %))) operation-types) (i18n/t "Operation")) :kind "composition"}]
                             (for [item fields :let [value (get-in (:entry draft) (path (:key item)))] :when (populated? value)]
                               {:key   (:key item) :label (:label item)
                                :value (case (last (path (:key item)))
                                         :unit (i18n/unit-label (str value))
                                         :direction (i18n/t (str/capitalize (name value)))
                                         (if (keyword? value) (name value) (str value)))
                                :kind  "edit"})) [])]
    {:closeMenu   false
     :conflict    nil :failed   false
     :state       state :text   text :filters filters :request request :field field :chips chips
     :canDone     done? :errors (if (:showErrors state) (:errors check) []) :unavailable unavailable?
     :options     (vec (if (= "resumes" (:mode state)) options (take 30 options)))
     :placeholder (cond field (i18n/t "Choose or enter {field}" {:field (:label field)})
                        (= "types" (:mode state)) (i18n/t "Choose an operation type…")
                        (= "add" (:mode state)) (i18n/t "Choose the next field…")
                        :else (i18n/t "Search or add… Goods by default"))}))


(defn initial-model
  {:malli/schema [:=> [:cat :map :map] [:maybe :map]]}
  [snapshot filters]
  (when-let [draft (last (sort-by :saved-at (:compositions snapshot)))]
    (model snapshot (resumed-state draft) "" filters 0)))


(defn entry
  {:malli/schema [:=> [:cat [:map [:view :keyword] [:init :any] [:on-suggest :any] [:on-complete :any]]] schema/Hiccup]}
  [{:keys [view init on-suggest on-complete]}]
  [:section.smart-entry {:aria-label (i18n/t "Smart input")}
   [:stock-entry
    {:id                    (str "stock-entry-" (name view)) :data-ignore-morph ""
     :data-effect           init
     :data-on:smart-suggest on-suggest :data-on:smart-complete                  on-complete}]])
