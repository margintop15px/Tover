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
    [tover.inventory.stock-items.model :as stock-items]
    [tover.inventory.valuation :as valuation])
  (:import
    [java.time OffsetDateTime]
    [java.time.format DateTimeParseException]
    [java.time.temporal ChronoUnit]))


(def kinds
  "Opening (chapter 14), Correction (the chapter 15 count) and the operations of chapters 18–25."
  [:enum "opening" "correction" "purchase" "sale" "customer-return" "supplier-return" "transfer" "production" "defect"
   "write-off" "payment"])


(def operation-rules
  "Per operation kind (board `Save Shared Inventory Entry` § Kind …): the line price field, whether lines may propose
  new stock items (`:outputs`: production outputs only), the party (`:either`: a supplier or a store), and whether a
  reason is required."
  {"purchase"        {:price :unit-cost :new-items true :party :supplier}
   "sale"            {:price :unit-price :party :store}
   "customer-return" {:price :unit-price :party :store}
   "supplier-return" {:price :unit-price :party :supplier}
   "transfer"        {}
   "production"      {:new-items :outputs}
   "defect"          {:reason? true}
   "write-off"       {:reason? true}
   "payment"         {:party :either}})


(def Entry
  [:map
   [:entry-id Id]
   [:kind kinds]
   [:occurred-at {:optional true} validation/Instant]
   [:reason {:optional true} :string]
   ;; Purchase: an existing supplier, or the name of one registered on acceptance. Sale: the store (sales channel).
   [:supplier-id {:optional true :bel/ref :tover.catalogues.suppliers.model/suppliers} Id]
   [:supplier-name {:optional true} :string]
   [:store-id {:optional true :bel/ref :tover.catalogues.stores.model/stores} Id]
   ;; Transfer: one source and one destination for every line.
   [:from-warehouse-id {:optional true :bel/ref :tover.catalogues.warehouses.model/warehouses} Id]
   [:to-warehouse-id {:optional true :bel/ref :tover.catalogues.warehouses.model/warehouses} Id]
   ;; Payment: money received (in) or paid (out), an exact decimal above 0; the counterparty is the supplier or store.
   [:direction {:optional true} [:enum "in" "out"]]
   [:amount {:optional true} :string]
   [:status [:enum "pending" "accepted" "discarded"]]
   [:revision [:int {:min 1}]]
   [:saved-by :uuid]])


(def line-fields
  "A line names an existing stock item, or a new one by product id or product name plus its variant fields."
  [[:stock-item-id {:optional true :bel/ref :tover.inventory.stock-items.model/stock-items} Id]
   [:product-id {:optional true :bel/ref :tover.catalogues.products.model/products} Id]
   [:product-name {:optional true} :string]
   ;; A new product's category and a new stock item's store, registered with them.
   [:category-id {:optional true :bel/ref :tover.catalogues.categories.model/categories} Id]
   [:store-id {:optional true :bel/ref :tover.catalogues.stores.model/stores} Id]
   [:variant-label {:optional true} :string]
   [:article {:optional true} :string]
   [:unit {:optional true} :string]
   [:warehouse-id {:optional true :bel/ref :tover.catalogues.warehouses.model/warehouses} Id]
   [:warehouse-name {:optional true} :string]
   [:quantity {:optional true} :int]
   [:unit-cost {:optional true} :string]
   [:unit-price {:optional true} :string]
   ;; Production only.
   [:side {:optional true} [:enum "input" "output"]]])


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


(def change-fields
  "One proposed stock item change of a Correction. Absent means unchanged; an empty string clears variant label or article."
  [[:stock-item-id {:bel/ref :tover.inventory.stock-items.model/stock-items} Id]
   [:variant-label {:optional true} :string]
   [:article {:optional true} :string]
   [:unit {:optional true} :string]
   ;; An empty string clears the store.
   [:store-id {:optional true} :string]
   [:product-id {:optional true :bel/ref :tover.catalogues.products.model/products} Id]
   [:archive {:optional true} :boolean]
   [:restore {:optional true} :boolean]])


(def EntryChange
  "Id is `entry-id/stock-item-id`."
  (into [:map [:entry-change-id Id] [:entry-id {:bel/ref ::entries} Id]] change-fields))


(def EntryChangeBarcode
  "Id is `entry-change-id|op|barcode`."
  [:map [:entry-change-barcode-id Id] [:entry-change-id {:bel/ref ::entry-changes} Id] [:op [:enum "add" "remove"]] [:barcode Id]])


(def Change
  "A change as commands take it and read models return it."
  (into [:map [:barcodes-added {:optional true} [:vector Id]] [:barcodes-removed {:optional true} [:vector Id]]] change-fields))


(def ^:private entry-fields
  [:kind :occurred-at :reason :supplier-id :supplier-name :store-id :from-warehouse-id :to-warehouse-id :direction :amount
   :status :revision :saved-by])


(def ^:private line-keys
  (into [:entry-id :line-id :position] (map first) line-fields))


(defevent shared-inventory-entry-saved {:schema Entry})
(defevent inventory-draft-discarded {:schema [:map [:entry-id Id] [:status [:enum "discarded"]] [:revision [:int {:min 1}]]]})
(defevent shared-inventory-entry-accepted {:schema [:map [:entry-id Id] [:status [:enum "accepted"]] [:revision [:int {:min 1}]]]})
(defevent inventory-entry-line-saved {:schema EntryLine})
(defevent inventory-entry-line-removed {:schema [:map [:entry-line-id Id]]})
(defevent inventory-entry-line-barcode-added {:schema EntryLineBarcode})
(defevent inventory-entry-line-barcode-removed {:schema [:map [:entry-line-barcode-id Id]]})
(defevent inventory-entry-change-saved {:schema EntryChange})
(defevent inventory-entry-change-removed {:schema [:map [:entry-change-id Id]]})
(defevent inventory-entry-change-barcode-added {:schema EntryChangeBarcode})
(defevent inventory-entry-change-barcode-removed {:schema [:map [:entry-change-barcode-id Id]]})


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


(defmodel entry-changes
  {:id     :entry-change-id
   :store  :catalogue
   :schema EntryChange
   :policy ::records/workspace-member
   :events {::inventory-entry-change-saved   {:set (into [:entry-id] (map first) change-fields)}
            ::inventory-entry-change-removed {:delete true}}})


(defmodel entry-change-barcodes
  {:id     :entry-change-barcode-id
   :store  :catalogue
   :schema EntryChangeBarcode
   :policy ::records/workspace-member
   :events {::inventory-entry-change-barcode-added   {:create [:entry-change-id :op :barcode]}
            ::inventory-entry-change-barcode-removed {:delete true}}})


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


(defn- change-barcodes
  "A change's barcodes as `[op barcode]` pairs."
  {:malli/schema [:=> [:cat [:maybe :map]] [:set [:tuple :string :string]]]}
  [change]
  (into (set (map #(vector "add" %) (:barcodes-added change))) (map #(vector "remove" %)) (:barcodes-removed change)))


(defn change-events
  "Events that turn the stored changes `old` into `new`, one change per stock item."
  {:malli/schema [:=> [:cat :string [:sequential Change] [:sequential Change]] [:vector :map]]}
  [entry-id old new]
  (let [before  (into {} (map (juxt :stock-item-id identity)) old)
        kept    (set (map :stock-item-id new))
        id-of   #(str entry-id "/" %)
        code-id (fn [change-id [op barcode]] (str change-id "|" op "|" barcode))
        dropped (fn [change-id pairs]
                  (mapv #(hash-map :type ::inventory-entry-change-barcode-removed :data {:entry-change-barcode-id (code-id change-id %)})
                        (sort pairs)))]
    (-> []
        (into (mapcat (fn [{:keys [stock-item-id] :as change}]
                        (let [id (id-of stock-item-id)]
                          (conj (dropped id (change-barcodes change)) {:type ::inventory-entry-change-removed :data {:entry-change-id id}}))))
              (remove (comp kept :stock-item-id) old))
        (into (mapcat (fn [{:keys [stock-item-id] :as change}]
                        (let [id       (id-of stock-item-id)
                              previous (change-barcodes (get before stock-item-id))
                              current  (change-barcodes change)]
                          (concat [{:type ::inventory-entry-change-saved
                                    :data (assoc (dissoc change :barcodes-added :barcodes-removed) :entry-change-id id :entry-id entry-id)}]
                                  (dropped id (remove current previous))
                                  (for [[op barcode :as pair] (sort (remove previous current))]
                                    {:type ::inventory-entry-change-barcode-added
                                     :data {:entry-change-barcode-id (code-id id pair) :entry-change-id id :op op :barcode barcode}})))))
              new))))


(defn assemble-changes
  "Stored change and barcode rows as `Change`s ordered by stock item."
  {:malli/schema [:=> [:cat [:sequential :map] [:sequential :map]] [:vector Change]]}
  [changes barcodes]
  (let [by-change (group-by :entry-change-id barcodes)]
    (mapv (fn [{:keys [entry-change-id] :as change}]
            (let [codes (fn [op] (vec (sort (keep #(when (= op (:op %)) (:barcode %)) (get by-change entry-change-id)))))]
              (cond-> (dissoc change :entry-change-id :entry-id)
                (seq (codes "add")) (assoc :barcodes-added (codes "add"))
                (seq (codes "remove")) (assoc :barcodes-removed (codes "remove")))))
          (sort-by :stock-item-id changes))))


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
  {:malli/schema [:function [:=> [:cat :string] decimal?] [:=> [:cat :keyword :string] decimal?]]}
  ([text] (parse-amount :unit-cost text))
  ([field text]
   (let [normalized (str/replace (str/trim text) "," ".")]
     (cond
       (re-matches #"-\d+(\.\d+)?" normalized) (invalid! field "Enter an amount of 0 or more." {:attempted text})
       (re-matches #"\d+(\.\d{1,4})?" normalized) (bigdec normalized)
       :else (invalid! field "Enter an amount with at most 4 decimal places." {:attempted text})))))


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
         [:quantity {:optional true} [:maybe [:or :int :string]]] [:unit-cost {:optional true} [:maybe :string]]
         [:unit-price {:optional true} [:maybe :string]]]
        (comp (remove (comp #{:quantity :unit-cost :unit-price} first))
              (map (fn [[key props schema]] [key (dissoc props :bel/ref) [:maybe schema]])))
        line-fields))


(def DraftChange
  "A change as typed; barcode lists may hold blanks and \"0\", dropped at save."
  (into [:map [:barcodes-added {:optional true} [:maybe [:vector :string]]]
         [:barcodes-removed {:optional true} [:maybe [:vector :string]]]]
        (map (fn [[key props schema]] [key (dissoc props :bel/ref) (if (= key :stock-item-id) schema [:maybe schema])]))
        change-fields))


(def SaveEntry
  [:map
   [:workspace-id :uuid]
   [:entry-id Id]
   [:expected-revision {:optional true} [:maybe [:int {:min 1}]]]
   [:kind kinds]
   [:occurred-at {:optional true} [:maybe :string]]
   [:reason {:optional true} [:maybe [:string {:max 1000}]]]
   [:supplier-id {:optional true} [:maybe Id]]
   [:supplier-name {:optional true} [:maybe [:string {:max 500}]]]
   [:store-id {:optional true} [:maybe Id]]
   [:from-warehouse-id {:optional true} [:maybe Id]]
   [:to-warehouse-id {:optional true} [:maybe Id]]
   [:direction {:optional true} [:maybe [:enum "in" "out"]]]
   [:amount {:optional true} [:maybe :string]]
   [:lines {:optional true} [:vector DraftLine]]
   [:changes {:optional true} [:vector DraftChange]]])


(def ^:private reference-fields
  {:stock-item-id :stock-items :product-id :products :warehouse-id :warehouses :category-id :categories :store-id :stores})


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
    (when (and (:category-id line) (not (:product-name line)))
      (invalid! :category-id "Only a new product takes a category here." {:line-id line-id}))
    (when (and (:store-id line) (:stock-item-id line))
      (invalid! :store-id "Only a new stock item takes a store here." {:line-id line-id}))
    (when-let [unit (:unit line)]
      (when-not (or (= unit (:unit previous)) (stock-items/unit? unit))
        (invalid! :unit "Choose a unit from the list." {:line-id line-id :attempted unit})))
    (try
      (cond-> line
        (:quantity line) (update :quantity parse-quantity)
        (:unit-cost line) (update :unit-cost (comp str parse-amount))
        (:unit-price line) (update :unit-price #(str (parse-amount :unit-price %)))
        (empty? (:barcodes line)) (dissoc :barcodes))
      (catch clojure.lang.ExceptionInfo error
        (throw (ex-info (ex-message error) (assoc (ex-data error) :line-id line-id)))))))


(def ^:private new-item-fields
  [:product-id :product-name :category-id :store-id :variant-label :article :unit :barcodes])


(defn- check-correction-line!
  "A Correction counts existing stock items and carries no unit cost."
  {:malli/schema [:=> [:cat Line] :nil]}
  [{:keys [line-id] :as line}]
  (when (some line new-item-fields)
    (invalid! :stock-item-id "A correction counts existing stock items only." {:line-id line-id}))
  (when (:unit-cost line)
    (invalid! :unit-cost "A correction has no unit cost." {:line-id line-id :attempted (:unit-cost line)})))


(defn- check-operation-line!
  "Operation lines move 1 or more units. Only a purchase line, or a production output, may propose a new stock item,
  and only a purchase a new warehouse. A line carries its kind's price field only; a transfer line takes the entry's
  warehouses; a defect starts from a Regular item; only production lines have a side."
  {:malli/schema [:=> [:cat :map :string Line] :nil]}
  [references kind {:keys [line-id quantity side] :as line}]
  (let [{:keys [price new-items]} (operation-rules kind)
        fail! (fn [field message attempted] (invalid! field message {:line-id line-id :attempted attempted}))]
    (when (and quantity (< quantity 1))
      (fail! :quantity "Enter a whole number of 1 or more." (str quantity)))
    (when (and (some line new-item-fields) (not (or (true? new-items) (and (= :outputs new-items) (= "output" side)))))
      (fail! :stock-item-id "Choose an existing stock item." nil))
    (when (and (:warehouse-name line) (not= "purchase" kind))
      (fail! :warehouse-id "Choose an existing warehouse." (:warehouse-name line)))
    (when (and (= "transfer" kind) (:warehouse-id line))
      (fail! :warehouse-id "A transfer line takes the transfer's warehouses." (:warehouse-id line)))
    (doseq [field [:unit-cost :unit-price] :when (and (get line field) (not= field price))]
      (fail! field "This operation has no such price." (get line field)))
    (when (and side (not= "production" kind))
      (fail! :side "Only production lines are inputs or outputs." side))
    (when (and (= "defect" kind) (= "defective" (some #(when (= (:stock-item-id line) (:id %)) (:condition %)) (:stock-items references))))
      (fail! :stock-item-id "Choose a regular stock item." (:stock-item-id line)))))


(defn- check-references!
  "Supplier, store, warehouse pair and payment fields only where the kind has them. A new choice must be an active
  record; a saved one is kept even if archived."
  {:malli/schema [:=> [:cat :map [:maybe :map] :map] :nil]}
  [references entry {:keys [kind] :as input}]
  (let [party     (:party (operation-rules kind))
        given     #(blank->nil (get input %))
        supplier? (#{:supplier :either} party)
        store?    (#{:store :either} party)]
    (when (and (given :supplier-id) (not supplier?))
      (invalid! :supplier-id "This operation has no supplier." {}))
    (when (and (given :supplier-name) (not= "purchase" kind))
      (invalid! :supplier-id "Choose an existing supplier." {:attempted (given :supplier-name)}))
    (when (and (given :store-id) (not store?))
      (invalid! :store-id "This operation has no store." {}))
    (when (and (= :either party) (given :supplier-id) (given :store-id))
      (invalid! :supplier-id "Choose one counterparty." {}))
    (when (and (some given [:from-warehouse-id :to-warehouse-id]) (not= "transfer" kind))
      (invalid! :from-warehouse-id "Only a transfer has a source and a destination." {}))
    (when (and (given :from-warehouse-id) (= (given :from-warehouse-id) (given :to-warehouse-id)))
      (invalid! :to-warehouse-id "Choose a different destination warehouse." {:attempted (given :to-warehouse-id)}))
    (when (and (some given [:direction :amount]) (not= "payment" kind))
      (invalid! :amount "Only a payment has an amount." {}))
    (when (and (= "payment" kind) (seq (:lines input)))
      (invalid! :lines "A payment has no lines." {}))
    (doseq [[field records] [[:supplier-id :suppliers] [:store-id :stores] [:from-warehouse-id :warehouses] [:to-warehouse-id :warehouses]]
            :let            [id (given field)]
            :when           (and id (not= id (get entry field)))]
      (try (records/check-related! id (get references records) :validation)
           (catch clojure.lang.ExceptionInfo _
             (invalid! field "Choose an active record of this workspace." {:attempted id}))))))


(defn- parse-payment-amount
  "Board kind Payment: an exact amount above 0."
  {:malli/schema [:=> [:cat :string] :string]}
  [text]
  (let [amount (parse-amount :amount text)]
    (when-not (pos? amount)
      (invalid! :amount "Enter an amount above 0." {:attempted text}))
    (str amount)))


(defn- clean-barcodes
  {:malli/schema [:=> [:cat [:maybe [:sequential [:maybe :string]]]] [:vector :string]]}
  [values]
  (into [] (comp (keep #(some-> % str/trim)) (remove #{"" "0"}) (distinct)) values))


(defn- parse-change
  "A typed change as stored. The item may be archived (restore); a move target must be active."
  {:malli/schema [:=> [:cat :map [:maybe :map] :map] Change]}
  [references previous {:keys [stock-item-id] :as change}]
  (let [fail!  (fn [field message attempted] (invalid! field message {:stock-item-id stock-item-id :attempted attempted}))
        text   (fn [value] (some-> value str/trim))
        change (cond-> (into {} (remove (comp nil? val)) change)
                 (:variant-label change) (update :variant-label text)
                 (:article change) (update :article text)
                 true (update :unit blank->nil)
                 true (update :product-id blank->nil)
                 true (update :barcodes-added clean-barcodes)
                 true (update :barcodes-removed clean-barcodes))
        change (into {} (remove (fn [[_ v]] (or (nil? v) (false? v) (and (coll? v) (empty? v))))) change)]
    (when-not (some #(= stock-item-id (:id %)) (:stock-items references))
      (fail! :stock-item-id "Choose a stock item of this workspace." stock-item-id))
    (when-let [product-id (:product-id change)]
      (when (not= product-id (:product-id previous))
        (try (records/check-related! product-id (:products references) :validation)
             (catch clojure.lang.ExceptionInfo _ (fail! :product-id "Choose an active record of this workspace." product-id)))))
    (when-let [unit (:unit change)]
      (when-not (or (= unit (:unit previous)) (stock-items/unit? unit))
        (fail! :unit "Choose a unit from the list." unit)))
    (when-let [store-id (not-empty (:store-id change))]
      (when (not= store-id (:store-id previous))
        (try (records/check-related! store-id (:stores references) :validation)
             (catch clojure.lang.ExceptionInfo _ (fail! :store-id "Choose an active record of this workspace." store-id)))))
    (when (and (:archive change) (:restore change))
      (fail! :archive "Archive or restore, not both." true))
    change))


(defn check-workspace!
  "Reject input whose workspace differs from the request scope; never retarget."
  {:malli/schema [:=> [:cat :map :map] :nil]}
  [scope input]
  (when-not (= (:workspace-id input) (:workspace-id scope))
    (records/fail! :not-permitted "This workspace is no longer selected." {})))


(defn- current-draft!
  "The pending entry at `revision`, or a rejection carrying the current saved values."
  {:malli/schema [:=> [:cat [:maybe :map] [:sequential Line] [:sequential Change] [:maybe :int]] [:maybe :map]]}
  [entry lines changes revision]
  (when (and entry (not= "pending" (:status entry)))
    (records/fail! :entry-not-pending "This entry is already accepted or discarded." {:current entry}))
  (when-not (= revision (:revision entry))
    (records/fail! :stale-draft "This entry changed. Review the latest values."
                   {:current (some-> entry (assoc :lines (vec lines)) (cond-> (seq changes) (assoc :changes (vec changes))))}))
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


(defmethod runtime/coeffect ::changes
  [_ context {:keys [entry-id]}]
  (let [changes (filterv #(= entry-id (:entry-id %)) (records/all-records context ::entry-changes :entry-change-id))
        ids     (set (map :entry-change-id changes))]
    (assemble-changes changes (filterv (comp ids :entry-change-id)
                                       (records/all-records context ::entry-change-barcodes :entry-change-barcode-id)))))


(defn- choices
  {:malli/schema [:=> [:cat [:sequential :map] :keyword] [:vector :map]]}
  [rows id-key]
  (mapv #(cond-> {:id (get % id-key) :status (:status %)} (:condition %) (assoc :condition (:condition %))) rows))


(defmethod runtime/coeffect ::references
  [_ context _]
  {:warehouses  (choices (records/all-records context :tover.catalogues.warehouses.model/warehouses :warehouse-id) :warehouse-id)
   :products    (choices (records/all-records context :tover.catalogues.products.model/products :product-id) :product-id)
   :stock-items (choices (records/all-records context :tover.inventory.stock-items.model/stock-items :stock-item-id) :stock-item-id)
   :suppliers   (choices (records/all-records context :tover.catalogues.suppliers.model/suppliers :supplier-id) :supplier-id)
   :stores      (choices (records/all-records context :tover.catalogues.stores.model/stores :store-id) :store-id)
   :categories  (choices (records/all-records context :tover.catalogues.categories.model/categories :category-id) :category-id)})


(def ^:private draft-facts
  [[:entry ::entries entry-key] [:lines ::lines entry-key] [:changes ::changes entry-key]])


(defcommand save-shared-inventory-entry
  {:input SaveEntry :policy ::records/workspace-member
   :cofx  (conj draft-facts [:references ::references (constantly {})])
   :emits #{::shared-inventory-entry-saved ::inventory-entry-line-saved ::inventory-entry-line-removed
            ::inventory-entry-line-barcode-added ::inventory-entry-line-barcode-removed ::inventory-entry-change-saved
            ::inventory-entry-change-removed ::inventory-entry-change-barcode-added ::inventory-entry-change-barcode-removed}}
  [{:keys [scope actor entry lines changes references]} {:keys [entry-id expected-revision kind occurred-at reason] :as input}]
  (check-workspace! scope input)
  (current-draft! entry lines (or changes []) expected-revision)
  (when (and (seq (:changes input)) (not= "correction" kind))
    (invalid! :changes "Only a correction changes stock items." {}))
  (check-references! references entry input)
  (let [before   (into {} (map (juxt :line-id identity)) lines)
        parsed   (mapv #(parse-line references (get before (:line-id %)) %) (:lines input))
        _        (cond
                   (= "correction" kind) (run! check-correction-line! parsed)
                   (operation-rules kind) (run! #(check-operation-line! references kind %) parsed)
                   :else (run! #(doseq [field [:unit-price :side] :when (get % field)]
                                  (invalid! field "This operation has no such field." {:line-id (:line-id %)}))
                               parsed))
        prior    (into {} (map (juxt :stock-item-id identity)) changes)
        proposed (mapv #(parse-change references (get prior (:stock-item-id %)) %) (:changes input))
        instant  (some-> (blank->nil occurred-at) parse-instant)
        saved    (cond-> {:entry-id entry-id :kind kind :status "pending" :revision (inc (or expected-revision 0)) :saved-by actor}
                   instant (assoc :occurred-at instant)
                   (blank->nil reason) (assoc :reason (str/trim reason))
                   (blank->nil (:supplier-id input)) (assoc :supplier-id (:supplier-id input))
                   (and (not (blank->nil (:supplier-id input))) (blank->nil (:supplier-name input)))
                   (assoc :supplier-name (str/trim (:supplier-name input)))
                   (blank->nil (:store-id input)) (assoc :store-id (:store-id input))
                   (blank->nil (:from-warehouse-id input)) (assoc :from-warehouse-id (:from-warehouse-id input))
                   (blank->nil (:to-warehouse-id input)) (assoc :to-warehouse-id (:to-warehouse-id input))
                   (:direction input) (assoc :direction (:direction input))
                   (blank->nil (:amount input)) (assoc :amount (parse-payment-amount (:amount input))))]
    {:events (-> [{:type ::shared-inventory-entry-saved :data saved}]
                 (into (line-events entry-id lines parsed))
                 (into (change-events entry-id (or changes []) proposed)))
     :fx     (records/publish scope)}))


(def DiscardEntry
  [:map [:workspace-id :uuid] [:entry-id Id] [:revision [:int {:min 1}]]])


(defcommand discard-inventory-draft
  {:input DiscardEntry :policy ::records/workspace-member :cofx draft-facts
   :emits #{::inventory-draft-discarded ::inventory-entry-line-removed ::inventory-entry-line-barcode-removed
            ::inventory-entry-change-removed ::inventory-entry-change-barcode-removed}}
  [{:keys [scope entry lines changes]} {:keys [entry-id revision] :as input}]
  (check-workspace! scope input)
  (when-not entry
    (records/fail! :not-found "This entry no longer exists." {}))
  (current-draft! entry lines (or changes []) revision)
  {:events (-> [{:type ::inventory-draft-discarded :data {:entry-id entry-id :status "discarded" :revision (inc revision)}}]
               (into (line-events entry-id lines []))
               (into (change-events entry-id (or changes []) [])))
   :fx     (records/publish scope)})


(defn blockers
  "What keeps a pending entry from review: missing facts and archived references, per line and field. Units enter
  stock only at a cost, so an opening or purchase line needs its unit cost. An entry
  with only stock item changes needs no occurrence time; a transfer's lines take its warehouses; a payment has no lines."
  {:malli/schema [:=> [:cat :map [:sequential Line] :map] [:vector :map]]}
  [{:keys [kind] :as entry} lines status]
  (let [{:keys [party reason?] :as rules} (operation-rules kind)
        entry-blocker (fn [field code] {:line-id nil :field field :code code})
        archived? (fn [index id] (and id (not= "active" (get-in status [index id]))))]
    (into (cond-> (if (or (:occurred-at entry) (and (empty? lines) (seq (:changes entry))))
                    []
                    [(entry-blocker :occurred-at :occurred-at-missing)])
            (and (= :supplier party) (not (some entry [:supplier-id :supplier-name])))
            (conj (entry-blocker :supplier-id :supplier-missing))
            (and rules (not= "payment" kind) (empty? lines))
            (conj (entry-blocker :lines :lines-missing))
            (and reason? (str/blank? (:reason entry)))
            (conj (entry-blocker :reason :reason-missing))
            (and (= "transfer" kind) (not (:from-warehouse-id entry)))
            (conj (entry-blocker :from-warehouse-id :warehouse-missing))
            (and (= "transfer" kind) (not (:to-warehouse-id entry)))
            (conj (entry-blocker :to-warehouse-id :warehouse-missing))
            (and (= "production" kind) (seq lines) (not-any? #(= "input" (:side %)) lines))
            (conj (entry-blocker :lines :inputs-missing))
            (and (= "production" kind) (seq lines) (not-any? #(= "output" (:side %)) lines))
            (conj (entry-blocker :lines :outputs-missing))
            (and (= "payment" kind) (not (:direction entry)))
            (conj (entry-blocker :direction :direction-missing))
            (and (= "payment" kind) (not (some entry [:supplier-id :store-id])))
            (conj (entry-blocker :supplier-id :counterparty-missing))
            (and (= "payment" kind) (not (:amount entry)))
            (conj (entry-blocker :amount :amount-missing))
            (archived? :suppliers (:supplier-id entry)) (conj (entry-blocker :supplier-id :archived-reference))
            (archived? :stores (:store-id entry)) (conj (entry-blocker :store-id :archived-reference))
            (archived? :warehouses (:from-warehouse-id entry)) (conj (entry-blocker :from-warehouse-id :archived-reference))
            (archived? :warehouses (:to-warehouse-id entry)) (conj (entry-blocker :to-warehouse-id :archived-reference)))
          (mapcat (fn [{:keys [line-id] :as line}]
                    (concat
                      (when-not (:quantity line) [{:line-id line-id :field :quantity :code :quantity-missing}])
                      (when-not (or (= "transfer" kind) (some line [:warehouse-id :warehouse-name]))
                        [{:line-id line-id :field :warehouse-id :code :warehouse-missing}])
                      (when-not (some line [:stock-item-id :product-id :product-name]) [{:line-id line-id :field :stock-item-id :code :item-missing}])
                      (when (and (= "production" kind) (not (:side line))) [{:line-id line-id :field :side :code :side-missing}])
                      (when (and (#{"opening" "purchase"} kind) (not (:unit-cost line)))
                        [{:line-id line-id :field :unit-cost :code :unit-cost-missing}])
                      (for [[field index] reference-fields
                            :when         (archived? index (get line field))]
                        {:line-id line-id :field field :code :archived-reference}))))
          lines)))


(defmethod runtime/coeffect ::pending
  [_ context _]
  (let [entries  (filterv #(= "pending" (:status %)) (records/all-records context ::entries :entry-id))
        by-entry (group-by :entry-id (records/all-records context ::entry-lines :entry-line-id))
        barcodes (group-by :entry-line-id (records/all-records context ::entry-line-barcodes :entry-line-barcode-id))
        changes  (group-by :entry-id (records/all-records context ::entry-changes :entry-change-id))
        codes    (group-by :entry-change-id (records/all-records context ::entry-change-barcodes :entry-change-barcode-id))]
    (mapv (fn [{:keys [entry-id] :as entry}]
            (let [rows    (get by-entry entry-id [])
                  changed (get changes entry-id)]
              (cond-> (assoc entry :lines (assemble rows (mapcat (comp barcodes :entry-line-id) rows)))
                (seq changed) (assoc :changes (assemble-changes changed (mapcat (comp codes :entry-change-id) changed))))))
          entries)))


(defmethod runtime/coeffect ::catalogue
  [_ context _]
  {:products    (records/all-records context :tover.catalogues.products.model/products :product-id)
   :warehouses  (records/all-records context :tover.catalogues.warehouses.model/warehouses :warehouse-id)
   :stock-items (records/all-records context :tover.inventory.stock-items.model/stock-items :stock-item-id)
   :barcodes    (records/all-records context :tover.inventory.stock-items.model/stock-item-barcodes :barcode-id)
   :suppliers   (records/all-records context :tover.catalogues.suppliers.model/suppliers :supplier-id)
   :stores      (records/all-records context :tover.catalogues.stores.model/stores :store-id)
   :categories  (records/all-records context :tover.catalogues.categories.model/categories :category-id)})


(defn status-index
  "Catalogue status by kind and id, as `blockers` takes it."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [warehouses products stock-items suppliers stores]}]
  {:warehouses  (into {} (map (juxt :warehouse-id :status)) warehouses)
   :products    (into {} (map (juxt :product-id :status)) products)
   :stock-items (into {} (map (juxt :stock-item-id :status)) stock-items)
   :suppliers   (into {} (map (juxt :supplier-id :status)) suppliers)
   :stores      (into {} (map (juxt :store-id :status)) stores)})


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
