(ns tover.inventory.stock.model
  "Board chapter 13 · The stock table, an item's accepted history and its production lineage, derived from accepted
  records only. Valuation rules are in `tover.inventory.valuation`."
  (:require
    [bel.core :refer [defreadmodel]]
    [bel.validation :as validation]
    [tover.catalogues.records :as records :refer [Id]]
    [tover.inventory.valuation :as valuation]
    [tover.workspaces.currency.model :as currency]))


(defn- cell
  {:malli/schema [:=> [:cat [:maybe valuation/Balance] :boolean] :map]}
  [balance pending?]
  {:quantity (:quantity balance 0) :value (if balance (valuation/value balance) 0M) :valued? (:valued? balance true) :pending? pending?})


(defn- total
  "Sum of cells or totals; `:valued` is full, partial or none by how many parts carry a value."
  {:malli/schema [:=> [:cat [:sequential :map]] :map]}
  [parts]
  (let [flags (map #(or (:valued %) (if (:valued? %) "full" "none")) parts)]
    {:quantity (reduce + 0 (map :quantity parts))
     :value    (reduce + 0M (keep :value parts))
     :valued   (cond (every? #{"full"} flags) "full" (every? #{"none"} flags) "none" :else "partial")}))


(defn table
  "Products with their stock items, one cell per warehouse. `hide-empty?` drops products without stock items."
  {:malli/schema [:=> [:cat :map :boolean] :map]}
  [{{:keys [products stock-items barcodes warehouses]} :catalogue :keys [history pending currency]} hide-empty?]
  (let [balances (:balances (valuation/replay (:records history) (:lines history)))
        locked   (set (map :stock-item-id (:lines history)))
        touched  (set (for [entry pending line (:lines entry)] [(:stock-item-id line) (:warehouse-id line)]))
        codes    (group-by :stock-item-id barcodes)
        columns  (->> warehouses
                      (filter #(or (= "active" (:status %)) (some (fn [[[_ w] b]] (and (= w (:warehouse-id %)) (pos? (:quantity b)))) balances)))
                      (sort-by (comp records/name-key :name)))
        by-prod  (group-by :product-id stock-items)
        row      (fn [{:keys [stock-item-id] :as i}]
                   (let [cells (into {} (for [{w :warehouse-id} columns]
                                          [w (cell (balances [stock-item-id w]) (contains? touched [stock-item-id w]))]))]
                     (merge (select-keys i [:stock-item-id :variant-label :article :unit])
                            {:barcodes  (mapv :barcode (codes stock-item-id)) :unit-locked? (contains? locked stock-item-id)
                             :archived? (= "archived" (:status i))            :cells        cells :total (total (vals cells))})))]
    {:currency   currency
     :warehouses (mapv #(hash-map :id (:warehouse-id %) :name (:name %)) columns)
     :products   (vec (for [p     (sort-by (comp records/name-key :name) (remove #(= "deleted" (:status %)) products))
                            :let  [items (mapv row (sort-by (comp str :variant-label) (by-prod (:product-id p))))]
                            :when (or (seq items) (not hide-empty?))]
                        {:product-id (:product-id p) :name (:name p) :archived? (= "archived" (:status p)) :items items
                         :total      (if (seq items) (total (map :total items)) {:quantity 0 :value 0M :valued "full"})}))}))


(defn- within?
  {:malli/schema [:=> [:cat :any [:maybe :any] [:maybe :any]] :boolean]}
  [at from to]
  (boolean (and (or (nil? from) (not (neg? (compare at from)))) (or (nil? to) (neg? (compare at to))))))


(defn history-rows
  "Rows of one stock item in `[from, to)`: effective lines in business order with the true running balance, then
  superseded revisions, which count for nothing."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [history]} {:keys [stock-item-id from to]}]
  (let [record    (into {} (map (juxt :record-revision-id identity)) (:records history))
        effective (set (map :record-revision-id (valuation/business-order (:records history))))
        row       (fn [line superseded?]
                    (let [r (record (:record-revision-id line))]
                      (merge (select-keys r [:record-id :revision :kind :occurred-at :sequence :actor :recorded-at :reason])
                             (select-keys line [:warehouse-id :quantity-change :unit-cost :balance-after])
                             {:superseded? superseded?})))
        mine?     #(and (= stock-item-id (:stock-item-id %)) (within? (:occurred-at (record (:record-revision-id %))) from to))]
    {:rows (vec (concat (for [step (:steps (valuation/replay (:records history) (:lines history))) :when (mine? step)] (row step false))
                        (for [line (:lines history) :when (and (mine? line) (not (effective (:record-revision-id line))))]
                          (row line true))))}))


(defn lineage
  "Production relationships of one stock item; none until chapter 22 accepts Production."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [_ _]
  ;; ponytail: no production exists yet; derive dated input/output relationships when chapter 22 lands.
  {:status "none"})


(def ^:private facts
  [[:catalogue :tover.inventory.entries.model/catalogue (constantly {})]
   [:history :tover.inventory.acceptance.model/history (constantly {})]
   [:pending :tover.inventory.entries.model/pending (constantly {})]
   currency/row-fact])


(defreadmodel stock-table
  {:input  [:map [:hide-empty? {:optional true} :boolean]]
   :output :map
   :policy ::records/workspace-member
   :cofx   facts}
  [{:keys [currency-row] :as facts} {:keys [hide-empty?] :or {hide-empty? true}}]
  (table (assoc facts :currency (currency/current currency-row)) hide-empty?))


(def Period
  [:map [:stock-item-id Id] [:from {:optional true} validation/Instant] [:to {:optional true} validation/Instant]])


(defreadmodel item-history
  {:input  Period
   :output :map
   :policy ::records/workspace-member
   :cofx   [[:history :tover.inventory.acceptance.model/history (constantly {})]]}
  [facts input]
  (history-rows facts input))


(defreadmodel item-lineage
  {:input Period :output :map :policy ::records/workspace-member :cofx []}
  [facts input]
  (lineage facts input))
