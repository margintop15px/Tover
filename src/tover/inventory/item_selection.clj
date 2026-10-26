(ns tover.inventory.item-selection
  (:require
    [clojure.string :as str]))


(defn select-item
  "Copy known item fields without replacing the line's entered quantity or location."
  {:malli/schema [:=> [:cat :map :map [:vector :any] :string] :map]}
  [snapshot entry line-path item-id]
  (let [item (get-in snapshot [:items item-id])]
    (when-not item
      (throw (ex-info "This item is no longer available. Choose an available item." {:type :validation})))
    (let [operation (if (= :replacement (first line-path)) (:replacement entry) entry)
          line      (get-in entry line-path)
          locations (->> (:rows snapshot)
                         (filter #(and (= item-id (:item-id %)) (number? (:quantity %)) (pos? (:quantity %))))
                         (keep :location) (remove str/blank?) distinct vec)
          metadata  (into {:item-id item-id} (map (fn [key] [key (or (get item key) "")]) [:name :article :barcode :unit]))
          metadata  (cond-> metadata
                      (and (not= :transfer (:type operation)) (str/blank? (:location line)) (= 1 (count locations)))
                      (assoc :location (first locations)))]
      (update-in entry line-path merge metadata))))
