(ns tover.marketplace.operations.review
  "Board chapter 45 slice 5 · Read imported operations needing review. Every downloaded operation that is not
  accepted, with its one next step (`steps/classify`), grouped by the decision that unblocks it, most operations
  first. Plain data for the Operations page, which shows it in the shared draft's table.

  Each connection's facts are loaded once per read and every operation is classified against them: never a load per
  operation. The same classification answers the waiting counts of the matching and cutover views and the
  connection's counts by next step."
  (:require
    [bel.core :refer [defreadmodel]]
    [bel.runtime :as runtime]
    [clojure.string :as str]
    [com.brunobonacci.mulog :as mu]
    [tover.catalogues.records :as records]
    [tover.inventory.acceptance.model :as acceptance]
    [tover.marketplace.connections.model :as connections]
    [tover.marketplace.cutover.model :as cutover]
    [tover.marketplace.operations.model :as operations]
    [tover.marketplace.operations.proposals :as proposals]
    [tover.marketplace.operations.steps :as steps]
    [tover.marketplace.products.model :as products]
    [tover.marketplace.provenance :as provenance]
    [tover.marketplace.sync.model :as sync]
    [tover.marketplace.sync.observations :as observations]
    [tover.marketplace.warehouses.model :as warehouses]))


(def hidden
  "Finished next steps, shown only when the next step is filtered."
  #{"history" "set aside" "no effect"})


(def ^:private settled
  "Next steps that keep nothing waiting: finished, or about to be accepted without a person."
  (conj hidden "automatic"))


(def Line
  [:map [:label :string] [:stock-item-id {:optional true} :string] [:warehouse-id {:optional true} :string]
   [:quantity {:optional true} :int]])


(def Row
  "One operation of `Imported Operations Needing Review`: what Ozon states (kind, status, time, lines; never
  buyer data), its next step and group, its entry once proposed, missing facts and findings."
  [:map
   [:source-operation-id :string] [:connection-id :string] [:connection-label :string] [:kind :string]
   [:entry-kind {:optional true} :string] [:status {:optional true} :string] [:occurred-at {:optional true} :string]
   [:revision :int] [:next-step steps/NextStep] [:group steps/Group]
   [:entry-id {:optional true} :string] [:entry-revision {:optional true} :int]
   [:missing [:vector :string]] [:findings [:vector :string]]
   [:suggested-correction {:optional true} steps/Correction]
   [:listing-state {:optional true} products/ListingState]
   [:lines [:vector Line]]])


(def Group
  [:map [:decision steps/NextStep] [:id :string] [:label :string] [:count :int] [:connection-id :string]
   [:listing-state {:optional true} products/ListingState] [:operations [:vector Row]]])


(defn- json
  {:malli/schema [:=> [:cat :any] :any]}
  [value]
  (if (string? value) (observations/read-json value) value))


(defn- line-label
  "The listing's name, else the identifier Ozon states for the line."
  {:malli/schema [:=> [:cat :map [:maybe [:sequential :map]] :map] :string]}
  [names stated {:keys [external-product-id line-key]}]
  (or (some-> (get names external-product-id) str/trim not-empty)
      (some->> stated (some #(when (= line-key (:line-key %)) %)) :identifiers first :value str)
      external-product-id
      line-key))


(defn operation-row
  "The row of one classified operation."
  {:malli/schema [:=> [:cat :map :map :map steps/Classification] Row]}
  [facts deferrals {:keys [source-operation-id connection-id kind status occurred-at revision] :as operation}
   {:keys [next-step group entry-id missing findings suggested-correction]}]
  (let [names  (into {} (map (juxt :external-product-id :name)) (:products facts))
        stated (vec (json (:lines operation)))
        entry  (when entry-id (some #(when (= entry-id (:entry-id %)) %) (:entries facts)))
        listed (when (#{"match product" "confirm match again"} next-step)
                 (products/listing-state (get-in facts [:mappings (:id group)]) (get deferrals (:id group))))]
    (cond-> {:source-operation-id source-operation-id :connection-id connection-id
             :connection-label    (sync/connector-label (or (:connection facts) {:account-label connection-id}))
             :kind                kind :revision revision :next-step next-step :group group :missing missing :findings findings
             :lines               (mapv (fn [line]
                                          (cond-> {:label (line-label names stated line)}
                                            (:stock-item-id line) (assoc :stock-item-id (:stock-item-id line))
                                            (:warehouse-id line) (assoc :warehouse-id (:warehouse-id line))
                                            (:quantity line) (assoc :quantity (:quantity line))))
                                        (proposals/resolve-lines facts operation))}
      (proposals/entry-kind operation) (assoc :entry-kind (proposals/entry-kind operation))
      status (assoc :status status)
      occurred-at (assoc :occurred-at (str occurred-at))
      entry (assoc :entry-id entry-id :entry-revision (:revision entry))
      suggested-correction (assoc :suggested-correction suggested-correction)
      listed (assoc :listing-state listed))))


(defn classified
  "Every operation of the connection that is not accepted, with its classification, in source operation order;
  with `entries-only?` only those with a pending entry."
  {:malli/schema [:=> [:cat steps/Facts :boolean] [:vector [:tuple :map steps/Classification]]]}
  [facts entries-only?]
  (let [pending (set (map :entry-id (:entries facts)))]
    (vec (for [[id operation] (sort-by key (:operations facts))
               :when          (or (not entries-only?) (contains? pending (provenance/import-entry-id id)))
               :let           [classification (steps/classify facts operation)]
               :when          classification]
           [operation classification]))))


(defn grouped
  "Rows by the decision that unblocks them, most operations first; within a group by occurrence."
  {:malli/schema [:=> [:cat [:sequential Row]] [:vector Group]]}
  [rows]
  (->> (group-by (juxt (comp :decision :group) (comp :id :group)) rows)
       vals
       (map (fn [operations]
              (let [{:keys [group connection-id listing-state]} (first operations)]
                (cond-> (assoc (select-keys group [:decision :id :label])
                               :count (count operations) :connection-id connection-id
                               :operations (vec (sort-by (juxt (comp nil? :occurred-at) :occurred-at :source-operation-id) operations)))
                  listing-state (assoc :listing-state listing-state)))))
       (sort-by (juxt (comp - :count) :label :id))
       vec))


(defmethod runtime/coeffect ::facts
  ;; One facts load per connection of the workspace (or the one asked for).
  [_ context {:keys [connection-id]}]
  (let [ids (if connection-id [connection-id] (map :connection-id (runtime/coeffect ::connections/all context {})))]
    (vec (for [id ids :let [facts (operations/load-facts context id)] :when (:connection facts)] facts))))


(def Input
  "`entries-only?` classifies only operations whose entry is pending: what the unfiltered page marks."
  [:map [:connection-id {:optional true} :string] [:entries-only? {:optional true} :boolean]])


(defreadmodel imported-operations-needing-review
  {:input  Input
   :output [:map [:groups [:vector Group]]]
   :policy ::records/workspace-member
   :cofx   [[:facts ::facts (fn [_ input] (select-keys input [:connection-id]))]
            [:deferrals ::products/deferrals (constantly {})]
            [:clock ::acceptance/now (constantly {})]]}
  [{:keys [facts deferrals clock]} {:keys [entries-only?]}]
  {:groups (grouped (for [f     facts
                          :let  [f (assoc f :now clock)]
                          [o c] (classified f (boolean entries-only?))]
                      (operation-row f deferrals o c)))})


;; Waiting counts


(defn- stated-warehouses
  {:malli/schema [:=> [:cat :map] [:vector :string]]}
  [operation]
  (vec (distinct (filter some? (concat [(:from-warehouse operation) (:to-warehouse operation)]
                                       (map :warehouse (json (:lines operation))))))))


(defn waiting
  "What the connection's operations keep in Review: by listing, by external warehouse, by stock item and
  warehouse, and every operation by next step. An operation waits unless it is finished or automatic."
  {:malli/schema [:=> [:cat steps/Facts] [:map [:products :map] [:warehouses :map] [:scopes :map] [:next-steps :map]]]}
  [facts]
  (let [found   (classified facts false)
        waiting (for [[operation {:keys [next-step]}] found :when (not (settled next-step))]
                  [operation (proposals/resolve-lines facts operation)])]
    {:products   (frequencies (mapcat (fn [[_ lines]] (distinct (keep :external-product-id lines))) waiting))
     :warehouses (frequencies (mapcat (fn [[operation _]] (stated-warehouses operation)) waiting))
     :scopes     (frequencies (mapcat (fn [[_ lines]] (distinct (keep #(when (and (:stock-item-id %) (:warehouse-id %))
                                                                         [(:stock-item-id %) (:warehouse-id %)])
                                                                      lines)))
                                      waiting))
     :next-steps (frequencies (map (comp :next-step second) found))}))


(defn- waiting-of
  "`waiting` of one connection; counts that cannot be read are nil (shown as unknown, never zero): the counts
  guide, they must not take a page down."
  {:malli/schema [:=> [:cat :map :string :keyword] [:maybe :map]]}
  [context connection-id part]
  (try (get (waiting (assoc (operations/load-facts context connection-id) :now (runtime/coeffect ::acceptance/now context {}))) part {})
       (catch InterruptedException error (throw error))
       (catch Exception error
         (mu/log ::waiting-skipped :connection-id connection-id :error-type (or (:type (ex-data error)) (.getName (class error))))
         nil)))


(defmethod products/waiting-operations :imported-operations
  [context connection-id]
  (waiting-of context connection-id :products))


(defmethod warehouses/waiting-operations :imported-operations
  [context connection-id]
  (waiting-of context connection-id :warehouses))


(defmethod cutover/waiting-operations :imported-operations
  [context connection-id]
  (waiting-of context connection-id :scopes))


(defmethod connections/count-part :next-steps
  [_ context connection-id]
  (waiting-of context connection-id :next-steps))
