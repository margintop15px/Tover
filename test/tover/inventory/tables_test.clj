(ns tover.inventory.tables-test
  (:require
    [clojure.set :as set]
    [clojure.test :refer [deftest is testing]]
    [tover.inventory.grid :as grid]
    [tover.inventory.tables :as tables]))


(def stock-row
  {:id       "item@Main" :item-id "item" :name    "Goods" :quantity        10 :unit "pcs"
   :location "Main"      :article ""     :barcode ""      :ledger-revision 4})


(defn cells-by-column
  {:malli/schema [:=> [:cat :map :int] :map]}
  [model row]
  (zipmap (map :key (:columns model)) (get-in model [:rows row :cells])))


(deftest stock-edit-targets-preserve-identities-and-revisions
  (let [row   (assoc stock-row :draft-id "correction" :draft-revision 7
                     :article "Article" :_accepted stock-row :_changedFields ["article"]
                     :_changeClass "sheet-row-modified")
        model (tables/stock [row])
        cells (cells-by-column model 0)]
    (is (= [310 110 90 190 150 150] (mapv :width (:columns model))))
    (is (= {:type    "stock-edit" :id            "item@Main" :path           "quantity" :revision 7
            :rowId   "item@Main"  :field         "quantity"  :ledgerRevision 4
            :draftId "correction" :draftRevision 7}
           (get-in cells ["quantity" :target])))
    (is (= "quantity" (get-in cells ["quantity" :editor :type])))
    (is (= "" (get-in cells ["article" :before])))
    (is (not (contains? (get cells "quantity") :before)))
    (is (= "sheet-row-modified" (get-in model [:rows 0 :className])))
    (is (= {:type "item-history" :itemId "item" :rowId "item@Main" :label "History for Goods" :icon "›"}
           (get-in cells ["name" :action])))
    (is (nil? (get-in (cells-by-column (tables/stock [(assoc row :draft-kind "opening")]) 0) ["name" :action]))))
  (testing "Accepted stock omits absent optional draft tokens instead of sending null"
    (let [target (get-in (cells-by-column (tables/stock [stock-row]) 0) ["quantity" :target])]
      (is (= 4 (:revision target)))
      (is (= 4 (:ledgerRevision target)))
      (is (not-any? #(contains? target %) [:draftId :draftRevision :draftKind])))))


(def purchase
  {:kind        :operation :type               :purchase :id "purchase-a"                    :revision 2
   :operation   "Purchase" :party              "Supplier"                    :amount "12.34" :currency "EUR"
   :occurred-at "2026-10-22T08:00:00Z" :detail {:heading "Purchase details"}
   :lines       [{:name "A" :quantity 2 :unit "pcs" :location "Main"}
                 {:name "B" :quantity 4 :unit "pcs" :location "Main"}]})


(deftest accepted-operations-keep-separate-merged-groups-and-no-edit-controls
  (let [model         (tables/operations [purchase (assoc purchase :id "purchase-b")])
        groups        (partition-by :groupId (:rows model))
        shared-column (.indexOf (mapv :key (:columns model)) "party")
        type-column   (.indexOf (mapv :key (:columns model)) "type")
        group-ranges  (map (fn [group] (set (map :id group))) groups)]
    (is (false? (:editable model)))
    (is (= 2 (count groups)))
    (is (empty? (set/intersection (first group-ranges) (second group-ranges))))
    (is (= ["purchase-a" "purchase-b"] (mapv #(-> % first :groupId) groups)))
    (is (= 2 (count (filter #(= shared-column (:col %)) (:merges model)))))
    (is (not-any? #(> (:colspan %) 1) (:merges model)))
    (is (= [2 2] (mapv count groups)))
    (doseq [cell (mapcat :cells (:rows model))]
      (is (not-any? #(contains? cell %) [:target :editor :selection :itemTarget :itemValue]))
      (when (:action cell) (is (= "operation-details" (get-in cell [:action :type])))))
    (is (= "Purchase" (get-in model [:rows 0 :cells type-column :value])))
    (is (= 180 (:width (nth (:columns model) type-column))))
    (is (= "purchase-a" (get-in model [:rows 0 :cells type-column :action :id])))
    (is (= {:heading "Purchase details"} (get-in model [:details "purchase-a"])))
    (is (every? #(not (#{"select" "actions" "line-actions"} (:key %))) (:columns model))))
  (testing "Cancelled operations remain visible and clearly marked"
    (let [model (tables/operations [(assoc purchase :cancelled? true :operation "Purchase · cancelled")])]
      (is (= "inventory-cancelled-row" (get-in model [:rows 0 :className])))
      (is (.contains (get-in (cells-by-column model 0) ["type" :value]) "cancelled")))))


(deftest operation-merges-remain-in-bounds-after-controls-are-removed
  (let [production (assoc purchase :id "production" :type :production
                          :inputs (:lines purchase) :outputs [{:name "C" :quantity 3}])
        arrival    (assoc purchase :receipts [{:occurred-at "2026-10-23T08:00:00Z"
                                               :lines       [{:name "A" :quantity 1}]}])]
    (doseq [operation [production arrival]]
      (let [model   (tables/operations [operation])
            covered (mapcat (fn [{:keys [row col rowspan colspan]}]
                              (for [y (range row (+ row rowspan)) x (range col (+ col colspan))] [x y])) (:merges model))]
        (is (= (count covered) (count (set covered))))
        (is (every? (fn [[x y]] (and (< x (count (:columns model))) (< y (count (:rows model))))) covered))
        (is (every? #(= (count (:columns model)) (count (:cells %))) (:rows model)))))))


(deftest accepted-production-shows-colored-lines-without-heading-rows
  (let [production (assoc purchase :type :production :inputs (:lines purchase)
                          :outputs [{:name "C" :quantity 3} {:name "D" :quantity 1}])
        model      (tables/operations [production])
        rows       (mapv #(cells-by-column model %) (range 4))]
    (is (= 4 (count (:rows model))))
    (is (= ["A" "B" "C" "D"] (mapv #(get-in % ["name" :value]) rows)))
    (is (= ["inventory-production-input" "inventory-production-input"
            "inventory-production-output" "inventory-production-output"]
           (mapv #(get-in % ["name" :className]) rows)))
    (is (not-any? #(> (:colspan %) 1) (:merges model)))
    (is (every? #(= 4 (:rowspan %)) (:merges model)))))


(deftest historical-purchase-quantities-are-plain-items-with-one-commercial-amount
  (let [entry      (assoc purchase :receipts [{:occurred-at "2026-10-23T08:00:00Z"
                                               :lines       [(assoc (first (:lines purchase)) :quantity 1)]}
                                              {:occurred-at "2026-10-24T09:00:00Z"
                                               :lines       [(assoc (first (:lines purchase)) :quantity 1) (second (:lines purchase))]}])
        model      (tables/operations [entry])
        rows       (mapv #(cells-by-column model %) (range 3))
        amount-col (.indexOf (mapv :key (:columns model)) "amount")]
    (is (= 3 (count (:rows model))))
    (is (= ["A" "A" "B"] (mapv #(get-in % ["name" :value]) rows)))
    (is (= ["1" "1" "4"] (mapv #(get-in % ["quantity" :value]) rows)))
    (is (not-any? #(#{"line-kind" "stock-effect" "line-date"} (:key %)) (:columns model)))
    (is (not-any? :className (mapcat vals rows)))
    (is (= ["12.34" nil nil] (mapv #(get-in % ["amount" :value]) rows)))
    (is (= [{:row 0 :col amount-col :rowspan 3 :colspan 1}] (filterv #(= amount-col (:col %)) (:merges model))))))


(deftest saved-operation-columns-remain-simple
  (let [model (tables/operations [purchase] {:column-keys ["type" "name" "quantity"]})]
    (is (= ["type" "name" "quantity"] (mapv :key (:columns model))))))


(deftest every-accepted-operation-has-only-business-line-rows
  (doseq [[type _] grid/operation-types]
    (testing (name type)
      (let [entry    (assoc purchase :type type :inputs (:lines purchase)
                            :outputs [{:name "C" :quantity 3} {:name "D" :quantity 1}])
            model    (tables/operations [entry])
            expected (case type :payment 1 :production 4 2)]
        (is (= expected (count (:rows model))))
        (is (every? #(= expected (:rowspan %)) (:merges model)))
        (is (not-any? #(re-find #"Revision|Ordered items|Inputs consumed|Outputs created|All items arrive" (str (:value %)))
                      (mapcat :cells (:rows model))))))))


(deftest metadata-only-result-highlights-the-actual-changed-cell
  (let [model (tables/resulting-stock {:before-rows [stock-row]
                                       :rows        [(assoc stock-row :article "New article" :ledger-revision 5)]})
        cells (cells-by-column model 0)]
    (is (= 1 (count (:rows model))))
    (is (= {:value "New article" :before ""} (get cells "article")))
    (is (= {:value 10} (get cells "quantity")))
    (is (not-any? :sortKey (:columns model)))
    (is (= [] (:rows (tables/resulting-stock {:before-rows [stock-row]
                                              :rows        [(assoc stock-row :ledger-revision 5)]}))))))


(deftest removed-balances-and-new-stock-show-correct-values
  (let [removed (tables/resulting-stock {:before-rows [stock-row] :rows []})
        opening (tables/resulting-stock {:before-rows [] :rows [stock-row]})]
    (is (= {:value 0 :before 10} (get (cells-by-column removed 0) "quantity")))
    (is (= "Main" (get-in (cells-by-column removed 0) ["location" :value])))
    (is (= {:value 10 :before 0} (get (cells-by-column opening 0) "quantity")))
    (is (= {:value ""} (get (cells-by-column opening 0) "article")))))


(deftest history-joins-own-effective-quantities-without-exposing-identifiers
  (let [audit  (fn [revision status]
                 {:entity-id   "purchase"             :revision revision :status status :actor "86c451ff-6ea5-49f8-b04b-cb6cbbb79af0"
                  :actor-label "Alice"                :entry    {:kind :operation :type :purchase :occurred-at "2026-10-20T08:00:00Z"}
                  :recorded-at "2026-10-22T08:00:00Z"})
        effect (fn [revision after]
                 {:operation-id "purchase" :operation-revision revision :before 0 :after after :location "Main"})
        model  (tables/history {:audit-records    [(audit 1 :superseded) (audit 2 :effective) (audit 3 :cancelled)]
                                :records          [(effect 1 50) (effect 2 7) (effect 3 100)]
                                :timeline-records [(assoc (effect 2 99) :operation-id "parent")]})
        rows   (mapv #(cells-by-column model %) (range 3))]
    (is (= ["—" "0 → 7 · Main" "—"] (mapv #(get-in % ["quantity" :value]) rows)))
    (is (= ["Alice" "Alice" "Alice"] (mapv #(get-in % ["actor" :value]) rows)))
    (is (= ["Purchase" "Purchase" "Purchase"] (mapv #(get-in % ["record" :value]) rows)))
    (is (not (.contains (pr-str model) "86c451ff")))
    (is (every? #(true? (get-in % ["occurred-at" :date])) rows))
    (is (every? #(true? (get-in % ["recorded-at" :date])) rows))
    (is (= "Unavailable user"
           (get-in (cells-by-column (tables/history {:audit-records [(dissoc (audit 1 :effective) :actor-label)]}) 0) ["actor" :value])))
    (is (= [] (:rows (tables/history {:records [(effect 2 7)]}))))))


(deftest partial-arrival-effect-dates-remain-visible-in-one-history-cell
  (let [date    "2026-10-20T08:00:00Z"
        arrival "2026-10-22T10:30:00Z"
        model   (tables/history {:audit-records [{:entity-id "purchase" :revision 1 :status :effective
                                                  :entry     {:kind :operation :type :purchase :occurred-at date}}]
                                 :records       [{:operation-id "purchase" :operation-revision 1
                                                  :before       0          :after              2 :location "Main" :occurred-at date}
                                                 {:operation-id "purchase" :operation-revision 1
                                                  :before       2          :after              7 :location "Main" :occurred-at arrival}]})
        cell    (get (cells-by-column model 0) "quantity")]
    (is (= "0 → 2 · Main; 2 → 7 · Main" (:value cell)))
    (is (= [{:value "0 → 2 · Main"}
            {:value "2 → 7 · Main"}
            {:value arrival :date true}] (:lines cell)))
    (is (= [arrival] (mapv :value (filter :date (:lines cell)))))))
