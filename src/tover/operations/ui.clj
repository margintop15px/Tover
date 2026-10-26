(ns tover.operations.ui
  (:require
    [clojure.string :as str]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]))


(defn- title
  {:malli/schema [:=> [:cat :any] :string]}
  [value]
  (i18n/t (case value
            :opening (i18n/t "Opening stock")
            :correction (i18n/t "Data edit")
            :write-off (i18n/t "Write-off")
            (if value (str/capitalize (str/replace (name value) "-" " ")) (i18n/t "Operation")))))


(defn- entry-lines
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [entry]
  (vec (concat (if (and (= :purchase (:type entry)) (contains? entry :receipts))
                 (mapcat :lines (:receipts entry))
                 (:lines entry))
               (:inputs entry) (:outputs entry))))


(defn- line-table
  {:malli/schema [:=> [:cat :string [:sequential :map]] :map]}
  [heading lines]
  {:heading heading
   :columns [(i18n/t "Goods / article") (i18n/t "Quantity") (i18n/t "Location") (i18n/t "Amount")]
   :rows    (mapv (fn [line]
                    [(str (or (:name line) (:item-id line))
                          (when-not (str/blank? (:article line)) (str " · " (:article line))))
                     (str (:quantity line) " " (i18n/unit-label (or (:unit line) "pcs")))
                     (or (:location line) "—")
                     (if (:amount line) (str (:amount line)) "—")]) lines)})


(defn- sections
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [entry]
  (let [lines (if (and (= :purchase (:type entry)) (contains? entry :receipts))
                (mapcat :lines (:receipts entry))
                (:lines entry))]
    (vec (for [[heading entries] [[(i18n/t "Items") lines]
                                  [(i18n/t "Inputs consumed") (:inputs entry)] [(i18n/t "Outputs created") (:outputs entry)]]
               :when             (seq entries)]
           (line-table heading entries)))))


(defn- metadata
  {:malli/schema [:=> [:cat :map [:maybe :map]] [:vector :map]]}
  [entry record]
  (vec (for [[label value] [["Occurred at" (:occurred-at entry)]
                            ["Party" (:party entry)]
                            ["Direction" (some-> (:direction entry) title)]
                            ["From" (:from-location entry)]
                            ["To" (:to-location entry)]
                            ["Amount" (when (:amount entry) (str (:amount entry) " " (:currency entry)))]
                            ["Original operation" (:original-operation-id entry)]
                            ["Reason" (or (:reason record) (:reason entry))]
                            ["Recorded at" (or (:recorded-at record) (:recorded-at entry))]
                            ["Submitted by" (when (or (:actor record) (:actor entry)) (or (:actor-label record) (:actor-label entry) (i18n/t "Unavailable user")))]
                            ["Created by" (when (:created-by record) (or (:created-by-label record) (i18n/t "Unavailable user")))]
                            ["Edited by" (when (:proposed-by record) (or (:proposed-by-label record) (i18n/t "Unavailable user")))]]
             :when         (and (some? value) (not (str/blank? (str value))))]
         (cond-> {:key label :label (i18n/t label) :value (str value)}
           (#{"Occurred at" "Recorded at"} label) (assoc :date? true)))))


(defn- detail
  {:malli/schema [:=> [:cat :map [:vector :map]] :map]}
  [entry records]
  (let [latest         (last records)
        facts          (metadata entry latest)
        actor          (or (:actor latest) (:actor entry))
        created-by     (:created-by latest)
        proposed-by    (:proposed-by latest)
        actor-label    (or (:actor-label latest) (:actor-label entry) (i18n/t "Unavailable user"))
        creator-label  (or (:created-by-label latest) (i18n/t "Unavailable user"))
        editor-label   (or (:proposed-by-label latest) (i18n/t "Unavailable user"))
        different?     (fn [id label other-id other-label]
                         (if (and id other-id) (not= id other-id) (not= label other-label)))
        summary-labels (cond-> #{"Recorded at" "Submitted by" "Reason"}
                         (and created-by (different? created-by creator-label actor actor-label)) (conj "Created by")
                         (and proposed-by (different? proposed-by editor-label actor actor-label)
                              (different? proposed-by editor-label created-by creator-label)) (conj "Edited by"))]
    {:heading          (title (if (= :operation (:kind entry)) (:type entry) (:kind entry)))
     :sections         (sections entry)
     :metadata         facts
     :summary-metadata (filterv #(summary-labels (:key %)) facts)
     :cancelled        (boolean (:cancelled? latest))
     :revisions        (mapv (fn [record]
                               {:heading  (cond (:cancelled? record) (i18n/t "Cancelled")
                                                (> (:revision record) 1) (i18n/t "Correction")
                                                :else (i18n/t "Accepted"))
                                :date     (:recorded-at record)
                                :sections (sections (:entry record))
                                :metadata (metadata (:entry record) record)})
                             (reverse records))}))


(defn operation-title
  {:malli/schema [:=> [:cat :map] :string]}
  [entry]
  (str (title (if (= :operation (:kind entry)) (:type entry) (:kind entry)))
       (when (:cancelled? entry) (i18n/t " · cancelled"))))


(defn rows
  "Format accepted records on the server, including entities whose latest revision was cancelled."
  {:malli/schema [:=> [:cat [:sequential :map] [:sequential :map]] [:vector :map]]}
  [entries records]
  (let [grouped   (into {} (map (fn [[id history]] [id (vec (sort-by :revision history))]))
                        (group-by :entity-id records))
        visible   (set (map :id entries))
        cancelled (for [[id history] grouped
                        :let         [record (last history)]
                        :when        (and (:cancelled? record) (not (visible id)))]
                    (assoc (:entry record) :id id :revision (:revision record) :recorded-at (:recorded-at record)
                           :actor (:actor record) :cancelled? true))]
    (mapv (fn [entry]
            (let [lines      (entry-lines entry)
                  item-count (count (set (map :item-id lines)))
                  locations  (distinct (keep :location lines))
                  history    (get grouped (:id entry) [])]
              (assoc entry
                     :time (:occurred-at entry)
                     :operation (operation-title entry)
                     :summary (if (seq lines)
                                (str/join ", " (map #(str (or (:name %) (:item-id %)) " × " (:quantity %)) lines))
                                (str (some-> (:direction entry) title) " " (:amount entry) " " (:currency entry)))
                     :context (or (when (:from-location entry) (str (:from-location entry) " → " (:to-location entry)))
                                  (when (:party entry) (:party entry))
                                  (when (seq locations) (str/join " → " locations)) "—")
                     :items item-count :item-label (i18n/t "{count} items" {:count item-count})
                     :reason (or (:reason (last history)) (:reason entry) "—")
                     :detail (detail entry history))))
          (concat entries cancelled))))


(defn sheet
  {:malli/schema [:=> [:cat :map] schema/Hiccup]}
  [{:keys [init on-sort on-filter on-revise]}]
  [:section.inventory-operations {:aria-label (i18n/t "Accepted operations")}
   [:stock-operations#operations-history
    {:aria-label                (i18n/t "Operations history spreadsheet") :data-ignore-morph ""
     :data-effect               init
     :data-on:operations-sort   on-sort
     :data-on:operations-filter on-filter
     :data-on:operation-revise  on-revise}]])
