(ns tover.inventory.entries.model
  "Board chapters 16, 14 and 32a · Shared pending inventory entries. Each draft line and each barcode typed for a
  new stock item is its own entity, so SHACL and SPARQL see them; removed lines are deleted."
  (:require
    [bel.core :refer [defcommand defevent defmodel defreadmodel]]
    [bel.runtime :as runtime]
    [bel.validation :as validation]
    [clojure.string :as str]
    [tover.catalogues.records :as records :refer [Id]]
    [tover.inventory.findings :as findings]
    [tover.inventory.valuation :as valuation])
  (:import
    [java.time OffsetDateTime]
    [java.time.format DateTimeParseException]
    [java.time.temporal ChronoUnit]))


(def Entry
  [:map
   [:entry-id Id]
   [:kind [:enum "opening"]]
   [:occurred-at {:optional true} validation/Instant]
   [:reason {:optional true} :string]
   [:status [:enum "pending" "accepted" "discarded"]]
   [:revision [:int {:min 1}]]
   [:saved-by :uuid]])


(def line-fields
  "A line names an existing stock item, or a new one by product id or product name plus its variant fields."
  [[:stock-item-id {:optional true :bel/ref :tover.inventory.stock-items.model/stock-items} Id]
   [:product-id {:optional true :bel/ref :tover.catalogues.products.model/products} Id]
   [:product-name {:optional true} :string]
   [:variant-label {:optional true} :string]
   [:article {:optional true} :string]
   [:unit {:optional true} :string]
   [:warehouse-id {:optional true :bel/ref :tover.catalogues.warehouses.model/warehouses} Id]
   [:warehouse-name {:optional true} :string]
   [:quantity {:optional true} :int]
   [:unit-cost {:optional true} :string]])


(def EntryLine
  "Id is `entry-id/line-id`."
  (into [:map [:entry-line-id Id] [:entry-id {:bel/ref ::entries} Id] [:line-id Id] [:position [:int {:min 0}]]]
        line-fields))


(def EntryLineBarcode
  "Id is `entry-line-id|barcode`."
  [:map [:entry-line-barcode-id Id] [:entry-line-id {:bel/ref ::entry-lines} Id] [:barcode Id]])


(def Line
  "A draft line as commands take it and read models return it; order is the vector order."
  (into [:map [:line-id Id] [:barcodes {:optional true} [:vector Id]]] line-fields))


(def ^:private entry-fields
  [:kind :occurred-at :reason :status :revision :saved-by])


(def ^:private line-keys
  (into [:entry-id :line-id :position] (map first) line-fields))


(defevent shared-inventory-entry-saved {:schema Entry})
(defevent inventory-draft-discarded {:schema [:map [:entry-id Id] [:status [:enum "discarded"]] [:revision [:int {:min 1}]]]})
(defevent shared-inventory-entry-accepted {:schema [:map [:entry-id Id] [:status [:enum "accepted"]] [:revision [:int {:min 1}]]]})
(defevent inventory-entry-line-saved {:schema EntryLine})
(defevent inventory-entry-line-removed {:schema [:map [:entry-line-id Id]]})
(defevent inventory-entry-line-barcode-added {:schema EntryLineBarcode})
(defevent inventory-entry-line-barcode-removed {:schema [:map [:entry-line-barcode-id Id]]})


(defmodel entries
  {:id     :entry-id
   :store  :catalogue
   :schema Entry
   :policy ::records/workspace-member
   ;; `apply-model!` writes a missing entity, so the first save needs no separate create.
   :events {::shared-inventory-entry-saved    {:set entry-fields}
            ::inventory-draft-discarded       {:set [:status :revision]}
            ::shared-inventory-entry-accepted {:set [:status :revision]}}})


(defmodel entry-lines
  {:id     :entry-line-id
   :store  :catalogue
   :schema EntryLine
   :policy ::records/workspace-member
   :events {::inventory-entry-line-saved   {:set line-keys}
            ::inventory-entry-line-removed {:delete true}}})


(defmodel entry-line-barcodes
  {:id     :entry-line-barcode-id
   :store  :catalogue
   :schema EntryLineBarcode
   :policy ::records/workspace-member
   :events {::inventory-entry-line-barcode-added   {:create [:entry-line-id :barcode]}
            ::inventory-entry-line-barcode-removed {:delete true}}})


(defn entry-line-id
  {:malli/schema [:=> [:cat :string :string] :string]}
  [entry-id line-id]
  (str entry-id "/" line-id))


(defn- barcode-id
  {:malli/schema [:=> [:cat :string :string] :string]}
  [entry-line-id barcode]
  (str entry-line-id "|" barcode))


(defn- removed
  {:malli/schema [:=> [:cat :string [:sequential :string]] [:vector :map]]}
  [entry-line-id barcodes]
  (mapv (fn [barcode] {:type ::inventory-entry-line-barcode-removed :data {:entry-line-barcode-id (barcode-id entry-line-id barcode)}})
        barcodes))


(defn line-events
  "Events that turn the stored lines `old` into `new`: every new line is saved at its vector position; lines and
  barcodes no longer present are removed."
  {:malli/schema [:=> [:cat :string [:sequential Line] [:sequential Line]] [:vector :map]]}
  [entry-id old new]
  (let [before (into {} (map (juxt :line-id identity)) old)
        kept   (set (map :line-id new))]
    (-> []
        (into (mapcat (fn [{:keys [line-id barcodes]}]
                        (let [id (entry-line-id entry-id line-id)]
                          (conj (removed id (or barcodes [])) {:type ::inventory-entry-line-removed :data {:entry-line-id id}}))))
              (remove (comp kept :line-id) old))
        (into (mapcat (fn [position {:keys [line-id barcodes] :as line}]
                        (let [id       (entry-line-id entry-id line-id)
                              previous (set (:barcodes (get before line-id)))
                              current  (set barcodes)]
                          (concat [{:type ::inventory-entry-line-saved
                                    :data (assoc (dissoc line :barcodes) :entry-line-id id :entry-id entry-id :position position)}]
                                  (removed id (sort (remove current previous)))
                                  (for [barcode (sort (remove previous current))]
                                    {:type ::inventory-entry-line-barcode-added
                                     :data {:entry-line-barcode-id (barcode-id id barcode) :entry-line-id id :barcode barcode}}))))
                      (range) new)))))


(defn assemble
  "Stored line and barcode rows of one entry as `Line`s in position order."
  {:malli/schema [:=> [:cat [:sequential :map] [:sequential :map]] [:vector Line]]}
  [lines barcodes]
  (let [by-line (group-by :entry-line-id barcodes)]
    (mapv (fn [{:keys [entry-line-id] :as line}]
            (let [codes (sort (map :barcode (get by-line entry-line-id)))]
              (cond-> (dissoc line :entry-line-id :entry-id :position)
                (seq codes) (assoc :barcodes (vec codes)))))
          (sort-by :position lines))))


;; Save, discard and read


(defn- invalid!
  {:malli/schema [:=> [:cat :keyword :string :map] :nil]}
  [field message data]
  (records/fail! :validation message (assoc data :field field)))


(defn- blank->nil
  {:malli/schema [:=> [:cat :any] :any]}
  [value]
  (if (and (string? value) (str/blank? value)) nil value))


(defn parse-amount
  "A nonnegative decimal with at most 4 fraction digits; `,` and `.` both separate decimals."
  {:malli/schema [:=> [:cat :string] decimal?]}
  [text]
  (let [normalized (str/replace (str/trim text) "," ".")]
    (cond
      (re-matches #"-\d+(\.\d+)?" normalized) (invalid! :unit-cost "Enter an amount of 0 or more." {:attempted text})
      (re-matches #"\d+(\.\d{1,4})?" normalized) (bigdec normalized)
      :else (invalid! :unit-cost "Enter an amount with at most 4 decimal places." {:attempted text}))))


(defn parse-instant
  "An ISO instant with an offset, as the same UTC instant truncated to whole seconds."
  {:malli/schema [:=> [:cat :string] validation/Instant]}
  [text]
  (try (.truncatedTo (.toInstant (OffsetDateTime/parse (str/trim text))) ChronoUnit/SECONDS)
       (catch DateTimeParseException _
         (invalid! :occurred-at "Enter a valid date and time." {:attempted text}))))


(defn- parse-quantity
  {:malli/schema [:=> [:cat :any] :int]}
  [value]
  (let [text (str/trim (str value))]
    (if (re-matches #"\d{1,9}" text)
      (parse-long text)
      (invalid! :quantity "Enter a whole number of 0 or more." {:attempted text}))))


(def DraftLine
  "A line as typed: quantity and unit cost arrive as cell text and are checked at save."
  (into [:map [:line-id Id] [:barcodes {:optional true} [:maybe [:vector Id]]]
         [:quantity {:optional true} [:maybe [:or :int :string]]] [:unit-cost {:optional true} [:maybe :string]]]
        (comp (remove (comp #{:quantity :unit-cost} first))
              (map (fn [[key props schema]] [key (dissoc props :bel/ref) [:maybe schema]])))
        line-fields))


(def SaveEntry
  [:map
   [:workspace-id :uuid]
   [:entry-id Id]
   [:expected-revision {:optional true} [:maybe [:int {:min 1}]]]
   [:kind [:enum "opening"]]
   [:occurred-at {:optional true} [:maybe :string]]
   [:reason {:optional true} [:maybe [:string {:max 1000}]]]
   [:lines {:optional true} [:vector DraftLine]]])


(def ^:private reference-fields
  {:stock-item-id :stock-items :product-id :products :warehouse-id :warehouses})


(defn- parse-line
  "A typed line as stored. New selections must name active records; a saved reference is kept even if archived."
  {:malli/schema [:=> [:cat :map [:maybe :map] :map] Line]}
  [references previous {:keys [line-id] :as line}]
  (let [line (into {} (keep (fn [[k v]] (when-some [v (blank->nil v)] [k v]))) line)]
    (doseq [[field kind] reference-fields
            :let         [id (get line field)]
            :when        (and id (not= id (get previous field)))]
      (try (records/check-related! id (get references kind) :validation)
           (catch clojure.lang.ExceptionInfo _
             (invalid! field "Choose an active record of this workspace." {:line-id line-id :attempted id}))))
    (try
      (cond-> line
        (:quantity line) (update :quantity parse-quantity)
        (:unit-cost line) (update :unit-cost (comp str parse-amount))
        (empty? (:barcodes line)) (dissoc :barcodes))
      (catch clojure.lang.ExceptionInfo error
        (throw (ex-info (ex-message error) (assoc (ex-data error) :line-id line-id)))))))


(defn- check-workspace!
  {:malli/schema [:=> [:cat :map :map] :nil]}
  [scope input]
  (when-not (= (:workspace-id input) (:workspace-id scope))
    (records/fail! :not-permitted "This workspace is no longer selected." {})))


(defn- current-draft!
  "The pending entry at `revision`, or a rejection carrying the current saved values."
  {:malli/schema [:=> [:cat [:maybe :map] [:sequential Line] [:maybe :int]] [:maybe :map]]}
  [entry lines revision]
  (when (and entry (not= "pending" (:status entry)))
    (records/fail! :entry-not-pending "This entry is already accepted or discarded." {:current entry}))
  (when-not (= revision (:revision entry))
    (records/fail! :stale-draft "This entry changed. Review the latest values." {:current (some-> entry (assoc :lines (vec lines)))}))
  entry)


(defn- entry-key
  {:malli/schema [:=> [:cat :map :map] :map]}
  [_ input]
  (select-keys input [:entry-id]))


(defmethod runtime/coeffect ::lines
  [_ context {:keys [entry-id]}]
  (let [lines (filterv #(= entry-id (:entry-id %)) (records/all-records context ::entry-lines :entry-line-id))
        ids   (set (map :entry-line-id lines))]
    (assemble lines (filterv (comp ids :entry-line-id) (records/all-records context ::entry-line-barcodes :entry-line-barcode-id)))))


(defn- choices
  {:malli/schema [:=> [:cat [:sequential :map] :keyword] [:vector :map]]}
  [rows id-key]
  (mapv #(hash-map :id (get % id-key) :status (:status %)) rows))


(defmethod runtime/coeffect ::references
  [_ context _]
  {:warehouses  (choices (records/all-records context :tover.catalogues.warehouses.model/warehouses :warehouse-id) :warehouse-id)
   :products    (choices (records/all-records context :tover.catalogues.products.model/products :product-id) :product-id)
   :stock-items (choices (records/all-records context :tover.inventory.stock-items.model/stock-items :stock-item-id) :stock-item-id)})


(def ^:private draft-facts
  [[:entry ::entries entry-key] [:lines ::lines entry-key]])


(defcommand save-shared-inventory-entry
  {:input SaveEntry :policy ::records/workspace-member
   :cofx  (conj draft-facts [:references ::references (constantly {})])
   :emits #{::shared-inventory-entry-saved ::inventory-entry-line-saved ::inventory-entry-line-removed
            ::inventory-entry-line-barcode-added ::inventory-entry-line-barcode-removed}}
  [{:keys [scope actor entry lines references]} {:keys [entry-id expected-revision kind occurred-at reason] :as input}]
  (check-workspace! scope input)
  (current-draft! entry lines expected-revision)
  (let [before  (into {} (map (juxt :line-id identity)) lines)
        parsed  (mapv #(parse-line references (get before (:line-id %)) %) (:lines input))
        instant (some-> (blank->nil occurred-at) parse-instant)
        saved   (cond-> {:entry-id entry-id :kind kind :status "pending" :revision (inc (or expected-revision 0)) :saved-by actor}
                  instant (assoc :occurred-at instant)
                  (blank->nil reason) (assoc :reason (str/trim reason)))]
    {:events (into [{:type ::shared-inventory-entry-saved :data saved}] (line-events entry-id lines parsed))
     :fx     (records/publish scope)}))


(def DiscardEntry
  [:map [:workspace-id :uuid] [:entry-id Id] [:revision [:int {:min 1}]]])


(defcommand discard-inventory-draft
  {:input DiscardEntry :policy ::records/workspace-member :cofx draft-facts
   :emits #{::inventory-draft-discarded ::inventory-entry-line-removed ::inventory-entry-line-barcode-removed}}
  [{:keys [scope entry lines]} {:keys [entry-id revision] :as input}]
  (check-workspace! scope input)
  (when-not entry
    (records/fail! :not-found "This entry no longer exists." {}))
  (current-draft! entry lines revision)
  {:events (into [{:type ::inventory-draft-discarded :data {:entry-id entry-id :status "discarded" :revision (inc revision)}}]
                 (line-events entry-id lines []))
   :fx     (records/publish scope)})


(defn blockers
  "What keeps a pending entry from review: missing facts and archived references, per line and field."
  {:malli/schema [:=> [:cat :map [:sequential Line] :map] [:vector :map]]}
  [entry lines status]
  (into (if (:occurred-at entry) [] [{:line-id nil :field :occurred-at :code :occurred-at-missing}])
        (mapcat (fn [{:keys [line-id] :as line}]
                  (concat
                    (when-not (:quantity line) [{:line-id line-id :field :quantity :code :quantity-missing}])
                    (when-not (some line [:warehouse-id :warehouse-name]) [{:line-id line-id :field :warehouse-id :code :warehouse-missing}])
                    (when-not (some line [:stock-item-id :product-id :product-name]) [{:line-id line-id :field :stock-item-id :code :item-missing}])
                    (for [[field kind] reference-fields
                          :let         [id (get line field)]
                          :when        (and id (not= "active" (get-in status [kind id])))]
                      {:line-id line-id :field field :code :archived-reference}))))
        lines))


(defmethod runtime/coeffect ::pending
  [_ context _]
  (let [entries  (filterv #(= "pending" (:status %)) (records/all-records context ::entries :entry-id))
        by-entry (group-by :entry-id (records/all-records context ::entry-lines :entry-line-id))
        barcodes (group-by :entry-line-id (records/all-records context ::entry-line-barcodes :entry-line-barcode-id))]
    (mapv (fn [{:keys [entry-id] :as entry}]
            (let [rows (get by-entry entry-id)]
              (assoc entry :lines (assemble rows (mapcat (comp barcodes :entry-line-id) rows)))))
          entries)))


(defmethod runtime/coeffect ::catalogue
  [_ context _]
  {:products    (records/all-records context :tover.catalogues.products.model/products :product-id)
   :warehouses  (records/all-records context :tover.catalogues.warehouses.model/warehouses :warehouse-id)
   :stock-items (records/all-records context :tover.inventory.stock-items.model/stock-items :stock-item-id)
   :barcodes    (records/all-records context :tover.inventory.stock-items.model/stock-item-barcodes :barcode-id)})


(defn status-index
  "Catalogue status by kind and id, as `blockers` takes it."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [warehouses products stock-items]}]
  {:warehouses  (into {} (map (juxt :warehouse-id :status)) warehouses)
   :products    (into {} (map (juxt :product-id :status)) products)
   :stock-items (into {} (map (juxt :stock-item-id :status)) stock-items)})


;; Pending entries with blockers, and draft findings over all of them (nil when the rules are unavailable).
(defreadmodel pending-entries
  {:input  [:map]
   :output [:map [:entries [:vector :map]] [:findings [:maybe [:vector findings/Finding]]]]
   :policy ::records/workspace-member
   :cofx   [[:pending ::pending (constantly {})] [:catalogue ::catalogue (constantly {})]
            [:history :tover.inventory.acceptance.model/history (constantly {})]]}
  [{:keys [pending catalogue history]} _]
  (let [status  (status-index catalogue)
        entries (->> pending
                     (sort-by (juxt (comp nil? :occurred-at) :occurred-at :entry-id))
                     (mapv #(assoc % :blockers (blockers % (:lines %) status))))]
    {:entries  entries
     :findings (findings/derive-within-limit
                 (assoc catalogue :entries entries
                        :balances (:balances (valuation/replay (:records history) (:lines history)))))}))
