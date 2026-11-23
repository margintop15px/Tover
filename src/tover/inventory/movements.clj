(ns tover.inventory.movements
  "Board `Inventory Record Accepted` § Other operations: every operation kind as signed lines per stock item and
  warehouse. Findings, the stock table's pending effects and acceptance all read operations through `expand`."
  (:require
    [clojure.string :as str]))


(def ^:private sign
  {"opening" 1 "purchase" 1 "customer-return" 1 "sale" -1 "supplier-return" -1 "write-off" -1})


(defn defect-label
  "Variant label of a Defective counterpart."
  {:malli/schema [:=> [:cat [:maybe :string]] :string]}
  [label]
  (if (str/blank? label) "defect" (str (str/trim label) " · defect")))


(defn counterpart
  "The Defective item of `regular-id`: an existing one by `:regular-item-id`, else the one a defect registers, id
  `<regular-id>~defect`, with the Regular item's product, unit and article."
  {:malli/schema [:=> [:cat [:sequential :map] [:maybe :string]] [:maybe :map]]}
  [stock-items regular-id]
  (when regular-id
    (or (some #(when (= regular-id (:regular-item-id %)) {:stock-item-id (:stock-item-id %)}) stock-items)
        (when-let [regular (some #(when (= regular-id (:stock-item-id %)) %) stock-items)]
          (into {:stock-item-id (str regular-id "~defect")              :new-item? true        :new-counterpart? true
                 :variant-label (defect-label (:variant-label regular)) :condition "defective" :regular-item-id  regular-id}
                (remove (comp nil? val))
                (select-keys regular [:product-id :unit :article]))))))


(defn expand
  "An entry's lines as signed lines, each with `:change` (nil without a quantity) in position order.

  - Transfer: per line, the leaving half at the source, then the entering half at the destination.
  - Defect: per line, the leaving half on the Regular item, then the entering half on `(counterpart item-id)`.
  - Production: inputs leave, then outputs enter.
  - Correction: lines unchanged; a count's change is derived on replay. Payment: no lines.

  An entering half has `:carry-from`, the position of the half whose cost it takes."
  {:malli/schema [:=> [:cat :map [:=> [:cat [:maybe :string]] [:maybe :map]]] [:vector :map]]}
  [{:keys [kind lines from-warehouse-id to-warehouse-id]} counterpart-of]
  (let [out   #(some-> (:quantity %) -)
        pairs (fn [leave enter]
                (into [] (comp (map-indexed (fn [i l] [(assoc (leave l) :change (out l))
                                                       (assoc (enter l) :change (:quantity l) :carry-from (* 2 i))]))
                               cat)
                      lines))]
    (case kind
      "transfer" (pairs #(assoc % :warehouse-id from-warehouse-id) #(assoc % :warehouse-id to-warehouse-id))
      "defect" (pairs identity #(merge (dissoc % :stock-item-id) (counterpart-of (:stock-item-id %))))
      "production" (let [{inputs "input" outputs "output"} (group-by :side lines)]
                     (into (mapv #(assoc % :change (out %)) inputs) (map #(assoc % :change (:quantity %))) outputs))
      "correction" (vec lines)
      "payment" []
      (mapv #(assoc % :change (some-> (:quantity %) (* (sign kind 1)))) lines))))


(defn lines-of
  "Lines with their entry id; an entering half is marked `:entering?`."
  {:malli/schema [:=> [:cat [:sequential :map] [:sequential :map]] [:vector :map]]}
  [stock-items entries]
  (vec (for [{:keys [entry-id] :as entry} entries
             line (expand entry (partial counterpart stock-items))]
         (cond-> (assoc line :entry-id entry-id :kind (:kind entry))
           (:carry-from line) (assoc :entering? true)))))
