(ns tover.inventory.grid-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [tover.inventory.grid :as grid]
    [tover.shared.i18n :as i18n]))


(def purchase
  {:kind  :operation :type :purchase :party "Supplier" :amount "12.34"
   :lines [{:name "A" :quantity 2} {:name "B" :quantity 4}]})


(def production
  {:kind    :operation :type :production
   :inputs  [{:name "A" :quantity 2} {:name "B" :quantity 3}]
   :outputs [{:name "C" :quantity 4} {:name "D" :quantity 1}]})


(defn draft
  {:malli/schema [:=> [:cat :map] :map]}
  [entry]
  {:id "draft-a" :revision 7 :entry entry})


(defn at-path
  {:malli/schema [:=> [:cat :map :string] [:sequential :map]]}
  [model path]
  (filter #(= path (get-in % [:target :path])) (mapcat :cells (:rows model))))


(deftest merged-operation-fields-have-one-stable-edit-target
  (let [model (grid/drafts {:items {} :drafts [(draft purchase)]})]
    (is (= 1 (count (at-path model "party"))))
    (is (= {:id "draft-a" :revision 7 :path "party"} (:target (first (at-path model "party")))))
    (is (= ["2" "4"] (mapv #(-> (at-path model %) first :value) ["lines/0/quantity" "lines/1/quantity"])))
    (is (= 2 (count (:rows model))))
    (is (some #(> (:rowspan %) 1) (:merges model)))
    (is (not-any? #(> (:colspan %) 1) (:merges model)))
    (is (not-any? #(re-find #"Ordered items|All items arrive|Arrivals" (str (:value %))) (mapcat :cells (:rows model))))))


(deftest production-retains-distinct-line-paths
  (let [model (grid/drafts {:drafts [(draft production)]})]
    (doseq [path ["inputs/0/quantity" "inputs/1/quantity" "outputs/0/quantity" "outputs/1/quantity"]]
      (is (= 1 (count (at-path model path)))))
    (is (every? #(= "draft-a" (:groupId %)) (:rows model)))))


(deftest production-previews-use-colored-lines-without-heading-rows
  (let [model          (grid/drafts {:drafts [(draft production)]})
        column-index   (zipmap (map :key (:columns model)) (range))
        action-column  (column-index "actions")
        actions        (mapcat #(get-in % [:cells action-column :actions]) (:rows model))
        shared-columns (remove #(or (grid/line-keys %) (= "actions" %)) (keys column-index))]
    (is (= 4 (count (:rows model))))
    (is (not-any? #(#{"Inputs consumed" "Outputs created"} (:value %)) (mapcat :cells (:rows model))))
    (doseq [[path class-name title] [["inputs/0/name" "inventory-production-input" "Consumed input"]
                                     ["inputs/1/quantity" "inventory-production-input" "Consumed input"]
                                     ["outputs/0/name" "inventory-production-output" "Created output"]
                                     ["outputs/1/quantity" "inventory-production-output" "Created output"]]]
      (let [cell (first (at-path model path))]
        (is (= class-name (:className cell)))
        (is (= title (:title cell)))))
    (is (= ["inputs" "outputs"] (mapv :path (filter #(= "add-line" (:command %)) actions))))
    (is (= ["inputs/0" "inputs/1" "outputs/0" "outputs/1"]
           (mapv :path (filter #(= "remove-line" (:command %)) actions))))
    (is (= ["add-line" "remove-line" "discard"] (mapv :command (get-in model [:rows 0 :cells action-column :actions]))))
    (is (= ["add-line" "remove-line"] (mapv :command (get-in model [:rows 2 :cells action-column :actions]))))
    (is (every? #(= {:row 0 :col (column-index %) :rowspan 4 :colspan 1}
                    (first (filter (fn [merge] (= (column-index %) (:col merge))) (:merges model)))) shared-columns))
    (is (every? #(nil? (:className (get-in model [:rows 0 :cells (column-index %)]))) shared-columns))))


(deftest empty-production-groups-retain-their-add-controls
  (let [model   (grid/drafts {:drafts [(draft (assoc production :inputs [] :outputs []))]})
        actions (mapv #(get-in % [:cells (dec (count (:columns model))) :actions]) (:rows model))]
    (is (= 2 (count (:rows model))))
    (is (= [["add-line" "discard"] ["add-line"]] (mapv #(mapv :command %) actions)))
    (is (= ["inputs" "outputs"] (mapv #(-> % first :path) actions)))
    (is (not-any? #(> (:colspan %) 1) (:merges model)))))


(deftest revision-reason-and-stock-counts-use-the-correct-server-fields
  (let [revised    {:kind :revision :reason "Fix order" :replacement purchase}
        corrected  {:kind  :correction :stock-row-id "row" :stock-count 9 :stock-location "Main"
                    :lines [{:name "A" :quantity 0 :location "Old"} {:name "A" :quantity 9 :location "Main"}]}
        revision   (grid/drafts {:drafts [(draft revised)]})
        correction (grid/drafts {:drafts [(draft corrected)]})]
    (is (= 1 (count (at-path revision "replacement/party"))))
    (is (= "Fix order" (:value (first (at-path revision "reason")))))
    (is (= "Correction · Purchase" (get-in revision [:rows 0 :cells 1 :display])))
    (is (= "9" (:value (first (at-path correction "stock-count")))))
    (is (= 1 (count (at-path correction "lines/1/name"))))
    (is (empty? (at-path correction "lines/0/quantity")))))


(deftest merge-rectangles-never-overlap
  (doseq [[type _] grid/operation-types]
    (testing (name type)
      (let [entry   (assoc production :type type :lines (:lines purchase))
            model   (grid/drafts {:drafts [(draft entry) (assoc (draft entry) :id "draft-b")]})
            covered (mapcat (fn [{:keys [row col rowspan colspan]}]
                              (for [y (range row (+ row rowspan)) x (range col (+ col colspan))] [x y])) (:merges model))]
        (is (= (count covered) (count (set covered))))
        (is (every? #(= (count (:columns model)) (count (:cells %))) (:rows model)))))))


(deftest actions-share-one-narrow-final-column-without-merged-row-actions
  (doseq [[type _] grid/operation-types]
    (testing (name type)
      (let [entry         (assoc production :type type :lines (:lines purchase))
            model         (grid/drafts {:drafts [(draft entry)]})
            column        (dec (count (:columns model)))
            actions       (mapcat #(get-in % [:cells column :actions]) (:rows model))
            first-actions (get-in model [:rows 0 :cells column :actions])]
        (is (= {:key "actions" :label "Actions" :width 96} (last (:columns model))))
        (is (not-any? #(= "line-actions" (:key %)) (:columns model)))
        (is (not-any? #(= "Items" (:value %)) (mapcat :cells (:rows model))))
        (is (= 1 (count (filter #(= "discard" (:command %)) actions))))
        (is (= "discard" (:command (last first-actions))))
        (is (not-any? #(= column (:col %)) (:merges model)))
        (is (every? #(#{"+" "−" "×"} (:icon %)) actions))
        (when-not (= :payment type)
          (is (some #(= "add-line" (:command %)) actions))
          (is (some #(= "remove-line" (:command %)) actions)))))))


(deftest purchases-have-simple-item-lines-and-actions-in-both-languages
  (doseq [locale ["en" "ru"]]
    (binding [i18n/*locale* locale]
      (let [model         (grid/drafts {:drafts [(draft purchase)]})
            columns       (mapv :key (:columns model))
            action-column (.indexOf columns "actions")
            first-actions (get-in model [:rows 0 :cells action-column :actions])
            other-actions (get-in model [:rows 1 :cells action-column :actions])]
        (is (= 2 (count (:rows model))))
        (is (= "A" (:value (first (at-path model "lines/0/name")))))
        (is (= "B" (:value (first (at-path model "lines/1/name")))))
        (is (not-any? #{"line-kind" "stock-effect" "line-date"} columns))
        (is (nil? (:purchaseHelp model)))
        (is (= ["add-line" "remove-line" "discard"] (mapv :command first-actions)))
        (is (= ["+" "−" "×"] (mapv :icon first-actions)))
        (is (= [(i18n/t "Add {type}" {:type (i18n/t "item")})
                (i18n/t "Remove {type}" {:type (i18n/t "item")})
                (i18n/t "Discard operation")] (mapv :label first-actions)))
        (is (= ["remove-line"] (mapv :command other-actions)))
        (is (not-any? :className (mapcat :cells (:rows model))))
        (is (= ["12.34"] (mapv :value (at-path model "amount"))))))))
