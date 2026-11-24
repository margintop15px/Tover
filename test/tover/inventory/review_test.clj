(ns tover.inventory.review-test
  "Board chapters 14, 17 and 32a · Review the selected openings: effects, registrations, problems and fingerprint."
  (:require
    [clojure.test :refer [deftest is testing]]
    [tover.inventory.acceptance.model :as acceptance])
  (:import
    [java.time Instant]))


(defn- day
  {:malli/schema [:=> [:cat :int] :any]}
  [n]
  (Instant/parse (format "2026-11-%02dT00:00:00Z" n)))


(def catalogue
  {:products    [{:product-id "tee" :name "T-shirt" :status "active" :revision 1}
                 {:product-id "mug" :name "Mug" :status "active" :revision 1}]
   :stock-items [{:stock-item-id "tee-m" :product-id "tee" :variant-label "M" :article "T-100" :unit "pcs" :condition "regular" :status "active"
                  :revision      1}]
   :barcodes    [{:barcode-id "tee-m|4600000000017" :stock-item-id "tee-m" :barcode "4600000000017"}]
   :warehouses  [{:warehouse-id "main" :name "Main" :status "active" :revision 1}
                 {:warehouse-id "old" :name "Old" :status "archived" :revision 2}]})


(defn- priced
  "Units enter stock only at a cost: opening and purchase lines default to one. An explicit nil unit cost leaves it out."
  {:malli/schema [:=> [:cat :string :map] :map]}
  [kind line]
  (cond-> line
    (and (#{"opening" "purchase"} kind) (not (contains? line :unit-cost))) (assoc :unit-cost "10.00")
    (contains? line :unit-cost) (->> (into {} (remove (comp nil? val))))))


(defn- entry
  {:malli/schema [:=> [:cat :string [:* :map]] :map]}
  [id & lines]
  {:entry-id id :kind "opening" :occurred-at (day 19) :status "pending" :revision 1
   :lines    (vec (map-indexed #(priced "opening" (merge {:line-id (str id "-" %1) :warehouse-id "main" :quantity 5 :unit "pcs"} %2)) lines))})


(defn- facts
  {:malli/schema [:=> [:cat [:sequential :map] :map] :map]}
  [entries more]
  (merge catalogue {:now (day 20) :currency "RUB" :entries entries :history {:records [] :lines []}} more))


(defn- review
  {:malli/schema [:=> [:cat :map [:* :string]] :map]}
  [facts & ids]
  (acceptance/review facts {:selection (mapv #(hash-map :entry-id % :revision 1) ids) :order (vec ids)}))


(defn- problems
  {:malli/schema [:=> [:cat :map] [:set :any]]}
  [review]
  (set (map :code (:problems review))))


(def accepted-opening
  {:records [{:record-revision-id "r#1" :record-id "r"   :revision    1       :acceptance-id "a" :kind "opening" :occurred-at (day 1)
              :sequence           0     :cancelled false :recorded-at (day 1)}]
   :lines   [{:record-line-id  "r#1/0" :record-revision-id "r#1" :position 0 :stock-item-id "tee-m" :warehouse-id "main"
              :quantity-change 3}]})


;; 14 · Review the opening declaration


(deftest preview-shows-before-and-after
  (let [result (review (facts [(entry "e" {:stock-item-id "tee-m" :unit-cost "100.00"})] {}) "e")]
    (is (:valid? result))
    (is (= [{:stock-item-id "tee-m" :warehouse-id "main" :quantity-before 0 :quantity-after 5 :value-before 0M :value-after 500M}]
           (mapv #(update-vals % (fn [v] (if (decimal? v) (.stripTrailingZeros ^BigDecimal v) v))) (:effects result))))))


(deftest second-opening-found
  (is (= #{:second-opening} (problems (review (facts [(entry "e" {:stock-item-id "tee-m"})] {:history accepted-opening}) "e"))))
  (is (= #{:second-opening} (problems (review (facts [(entry "a" {:stock-item-id "tee-m"}) (entry "b" {:stock-item-id "tee-m"})] {}) "a" "b")))
      "two selected openings for one pair"))


(deftest late-opening-after-other-records
  (let [history (-> accepted-opening
                    (assoc-in [:records 0 :kind] "purchase")
                    (assoc-in [:records 0 :occurred-at] (day 5)))]
    (is (= #{:opening-not-first} (problems (review (facts [(assoc (entry "e" {:stock-item-id "tee-m"}) :occurred-at (day 7))] {:history history}) "e"))))))


(deftest changed-draft-invalidates-review
  (is (= #{:entry-changed} (problems (review (facts [(assoc (entry "e" {:stock-item-id "tee-m"}) :revision 2)] {}) "e"))))
  (is (= #{:entry-changed} (problems (review (facts [] {}) "gone")))))


(deftest future-occurrence-blocks
  (is (= #{:future-occurrence} (problems (review (facts [(assoc (entry "e" {:stock-item-id "tee-m"}) :occurred-at (day 21))] {}) "e")))))


;; 32a / 17 · Select complete entries; review registrations


(deftest only-complete-entries-are-reviewable
  (is (= #{:entry-incomplete} (problems (review (facts [(entry "e" {:stock-item-id "tee-m" :quantity nil})] {}) "e"))))
  (is (= #{:entry-incomplete} (problems (review (facts [(entry "e" {:stock-item-id "tee-m" :warehouse-id nil})] {}) "e")))))


(deftest unselected-entries-have-no-effect
  (let [result (review (facts [(entry "a" {:stock-item-id "tee-m"}) (entry "b" {:product-id "mug" :variant-label "blue"})] {}) "a")]
    (is (= ["tee-m"] (map :stock-item-id (:effects result))))
    (is (= [] (:registrations result)))))


(deftest review-lists-registrations
  (let [result (review (facts [(entry "e" {:product-id "tee" :variant-label "XL" :quantity 4 :barcodes ["4600000000031"]})] {}) "e")]
    (is (:valid? result))
    (is (= [{:type :stock-item :id "item:e-0" :label "T-shirt / XL"} {:type :barcode :id "item:e-0|4600000000031" :label "4600000000031"}]
           (:registrations result)))
    (is (= [["item:e-0" "main" 0 4]] (map (juxt :stock-item-id :warehouse-id :quantity-before :quantity-after) (:effects result))))))


(deftest new-product-and-warehouse-register-once
  (let [result (review (facts [(entry "e" {:product-name "Cup" :variant-label "Big" :warehouse-id nil :warehouse-name "Back"}
                                      {:product-name " cup" :variant-label "Small" :warehouse-id nil :warehouse-name "back "})]
                              {})
                       "e")]
    (is (= [[:warehouse "warehouse:e-0" "Back"] [:product "product:e-0" "Cup"]
            [:stock-item "item:e-0" "Cup / Big"] [:stock-item "item:e-1" "Cup / Small"]]
           (map (juxt :type :id :label) (:registrations result)))))
  (testing "a name of an existing product refers to it"
    (is (= [[:stock-item "item:e-0" "T-shirt / L"]]
           (map (juxt :type :id :label) (:registrations (review (facts [(entry "e" {:product-name "t-shirt" :variant-label "L"})] {}) "e")))))))


(deftest article-conflict-names-the-owner
  (let [result (review (facts [(entry "e" {:product-id "mug" :variant-label "blue" :article " t-100 "})] {}) "e")]
    (is (= [{:entry-id "e" :line-id "e-0" :code :uniqueness-conflict :owner-product "T-shirt"}] (:problems result)))
    (is (false? (:valid? result)))))


(deftest barcode-owned-by-another-product-conflicts
  (is (= #{:uniqueness-conflict} (problems (review (facts [(entry "e" {:product-id "mug" :variant-label "blue" :barcodes ["4600000000017"]})] {}) "e"))))
  (is (= #{} (problems (review (facts [(entry "e" {:product-id "tee" :variant-label "L" :barcodes ["4600000000017"]})] {}) "e")))))


(deftest duplicate-variant-blocks
  (let [archived {:stock-items (conj (:stock-items catalogue) {:stock-item-id "tee-s" :product-id "tee" :variant-label "S" :status "archived"
                                                               :revision      2})}]
    (is (= #{:duplicate-variant} (problems (review (facts [(entry "e" {:product-id "tee" :variant-label "s"})] archived) "e")))
        "an archived item keeps its variant")))


(deftest a-new-item-opens-in-several-warehouses
  (testing "two selected openings of one new item share it and register it once"
    (let [result (review (facts [(entry "a" {:product-name "Shirt"}) (entry "b" {:product-name "shirt " :warehouse-id nil :warehouse-name "Back"})] {})
                         "a" "b")]
      (is (:valid? result) (pr-str (:problems result)))
      (is (= [[:warehouse "warehouse:b-0"] [:product "product:a-0"] [:stock-item "item:a-0"]]
             (map (juxt :type :id) (:registrations result))))
      (is (= [["item:a-0" "main"] ["item:a-0" "warehouse:b-0"]] (map (juxt :stock-item-id :warehouse-id) (:effects result))))))
  (testing "after the first acceptance the second entry resolves to the accepted item"
    (let [result (review (facts [(entry "b" {:product-id "tee" :variant-label " m"})] {}) "b")]
      (is (:valid? result))
      (is (= [] (:registrations result)))
      (is (= ["tee-m"] (map :stock-item-id (:effects result)))))))


(deftest unknown-unit-blocks-a-new-item
  (let [codes (fn [line] (problems (review (facts [(entry "e" line)] {}) "e")))]
    (is (contains? (codes {:product-id "tee" :variant-label "L" :unit "crate"}) :unknown-unit))
    (is (not (contains? (codes {:product-id "tee" :variant-label "L" :unit "pack"}) :unknown-unit)))
    (is (not (contains? (codes {:stock-item-id "tee-m" :unit "crate"}) :unknown-unit)) "registered items keep theirs")))


(deftest archived-reference-blocks
  (is (contains? (problems (review (facts [(entry "e" {:stock-item-id "tee-m" :warehouse-id "old"})] {}) "e")) :archived-reference)))


;; Fingerprint


(deftest fingerprint-tracks-what-the-review-saw
  (let [base  (facts [(entry "e" {:product-id "tee" :variant-label "XL" :barcodes ["4600000000031"]})] {})
        print #(:fingerprint (review % "e"))]
    (is (= (print base) (print (assoc base :now (day 25)))) "time alone does not outdate a review")
    (is (not= (print base) (print (assoc-in base [:products 0] {:product-id "tee" :name "Tee" :status "active" :revision 2}))))
    (is (not= (print base) (print (update base :barcodes conj {:barcode-id "x" :stock-item-id "mug-1" :barcode "4600000000031"}))))
    (is (not= (print base) (print (assoc base :currency "EUR"))))
    (let [on-pair (facts [(entry "e" {:stock-item-id "tee-m" :warehouse-id "main"})] {})]
      (is (not= (print on-pair) (print (assoc on-pair :history (assoc-in accepted-opening [:records 0 :kind] "purchase")))) "accepted lines on the affected pair"))))


;; 15 · Review the correction and later effects


(defn- correction
  "A Correction entry: `counts` are `[warehouse counted]` pairs for tee-m, `changes` stock item changes."
  {:malli/schema [:=> [:cat :string [:sequential :any] [:sequential :map]] :map]}
  [id counts changes]
  (cond-> {:entry-id id :kind "correction" :occurred-at (day 19) :status "pending" :revision 1
           :lines    (vec (map-indexed (fn [i [warehouse counted]]
                                         {:line-id (str id "-" i) :stock-item-id "tee-m" :warehouse-id warehouse :quantity counted})
                                       counts))}
    (seq changes) (assoc :changes (vec changes))))


(def opened-at-100
  (assoc-in accepted-opening [:lines 0 :unit-cost] "100.00"))


(def opened-5
  (assoc-in opened-at-100 [:lines 0 :quantity-change] 5))


(deftest count-down-with-value
  (let [result (review (facts [(correction "c" [["main" 3]] [])] {:history opened-5}) "c")]
    (is (:valid? result))
    (is (= [["tee-m" "main" 5 3 500M 300M]]
           (map (juxt :stock-item-id :warehouse-id :quantity-before :quantity-after
                      #(.stripTrailingZeros ^BigDecimal (:value-before %)) #(.stripTrailingZeros ^BigDecimal (:value-after %)))
                (:effects result))))))


(deftest backdated-count-breaks-a-later-sale
  (let [history (-> opened-5
                    (update :records conj {:record-revision-id "s#1"    :record-id "s" :revision  1     :acceptance-id "a"      :kind "sale"
                                           :occurred-at        (day 10) :sequence  0   :cancelled false :recorded-at   (day 10)})
                    (update :lines conj {:record-line-id "s#1/0" :record-revision-id "s#1" :position 0 :stock-item-id "tee-m"
                                         :warehouse-id   "main"  :quantity-change    -5}))]
    (is (= #{:negative-balance} (problems (review (facts [(assoc (correction "c" [["main" 3]] []) :occurred-at (day 5))] {:history history}) "c"))))))


(deftest count-needs-history
  (is (= #{:count-without-history} (problems (review (facts [(correction "c" [["main" 3]] [])] {}) "c"))))
  (is (:valid? (review (facts [(entry "o" {:stock-item-id "tee-m"}) (assoc (correction "c" [["main" 3]] []) :occurred-at (day 19))] {}) "o" "c"))
      "an opening in the same selection gives the pair history"))


(deftest same-field-changed-twice
  (let [result (review (facts [(correction "a" [] [{:stock-item-id "tee-m" :article "T-1"}])
                               (correction "b" [] [{:stock-item-id "tee-m" :article "T-2" :unit "kg"}])]
                              {})
                       "a" "b")]
    (is (= #{:field-conflict} (problems result)))
    (is (= #{["a" :article] ["b" :article]} (set (map (juxt :entry-id :field) (:problems result)))))))


(deftest archive-blocker-shown
  (let [result (review (facts [(correction "c" [] [{:stock-item-id "tee-m" :archive true}])] {:history opened-5}) "c")]
    (is (= [[:archive-blocked "main"]] (map (juxt :code :warehouse-id) (:problems result))))))


(deftest archive-after-counting-to-zero
  (is (:valid? (review (facts [(correction "c" [["main" 0]] [{:stock-item-id "tee-m" :archive true}])] {:history opened-5}) "c"))))


(deftest archive-blocked-by-another-draft
  (let [result (review (facts [(correction "c" [] [{:stock-item-id "tee-m" :archive true}]) (entry "other" {:stock-item-id "tee-m"})] {})
                       "c")]
    (is (= [[:archive-blocked "other"]] (map (juxt :code :other-entry-id) (:problems result))))))


(deftest restore-needs-an-active-product
  (let [archived (-> catalogue (assoc-in [:products 0 :status] "archived") (assoc-in [:stock-items 0 :status] "archived"))]
    (is (= #{:restore-needs-product}
           (problems (review (facts [(correction "c" [] [{:stock-item-id "tee-m" :restore true}])] archived) "c"))))))


(deftest unit-locked-by-history
  (is (= #{:unit-locked} (problems (review (facts [(correction "c" [] [{:stock-item-id "tee-m" :unit "kg"}])] {:history opened-5}) "c"))))
  (is (:valid? (review (facts [(correction "c" [] [{:stock-item-id "tee-m" :unit "kg"}])] {}) "c")) "unlocked without history"))


(deftest barcode-owned-by-another-product
  (let [catalogue (update catalogue :stock-items conj {:stock-item-id "mug-blue" :product-id "mug" :variant-label "blue" :status "active" :revision 1})
        result    (review (facts [(correction "c" [] [{:stock-item-id "mug-blue" :barcodes-added ["4600000000017"]}])] catalogue) "c")]
    (is (= [[:uniqueness-conflict "T-shirt"]] (map (juxt :code :owner-product) (:problems result))))))


(deftest move-with-a-shared-article
  (let [catalogue (update catalogue :stock-items conj {:stock-item-id "tee-l"  :product-id "tee" :variant-label "L" :article "T-100"
                                                       :status        "active" :revision   1})]
    (is (= [[:move-conflict "T-100"]]
           (map (juxt :code :value) (:problems (review (facts [(correction "c" [] [{:stock-item-id "tee-m" :product-id "mug"}])] catalogue) "c")))))
    (is (:valid? (review (facts [(correction "c" [] [{:stock-item-id "tee-m" :product-id "mug" :article "M-1"}])] catalogue) "c"))
        "changing the article in the same entry lets the move through")))


(deftest move-to-the-current-parent-or-a-taken-variant
  (let [catalogue (update catalogue :stock-items conj {:stock-item-id "mug-m" :product-id "mug" :variant-label "M" :status "active" :revision 1})]
    (is (= #{:move-conflict} (problems (review (facts [(correction "c" [] [{:stock-item-id "tee-m" :product-id "tee"}])] {}) "c"))))
    (is (= #{:move-conflict} (problems (review (facts [(correction "c" [] [{:stock-item-id "tee-m" :product-id "mug"}])] catalogue) "c"))))
    (is (= #{:move-conflict} (problems (review (facts [(correction "c" [] [{:stock-item-id "tee-m" :product-id "mug"}])]
                                                      (assoc-in catalogue [:products 1 :status] "archived")) "c")))
        "an archived target")
    (is (:valid? (review (facts [(correction "c" [] [{:stock-item-id "tee-m" :product-id "mug" :variant-label "M2"}])] catalogue) "c")))))


(deftest renamed-variant-must-stay-unique
  (let [catalogue (update catalogue :stock-items conj {:stock-item-id "tee-l" :product-id "tee" :variant-label "L" :status "active" :revision 1})]
    (is (= #{:duplicate-variant} (problems (review (facts [(correction "c" [] [{:stock-item-id "tee-m" :variant-label " l"}])] catalogue) "c"))))))


(deftest a-count-needs-the-item-unit
  (let [unitless (assoc-in catalogue [:stock-items 0 :unit] nil)]
    (is (= #{:unit-required} (problems (review (facts [(correction "c" [["main" 3]] [])] (assoc unitless :history opened-5)) "c"))))
    (is (= #{:unit-required} (problems (review (facts [(entry "o" {:stock-item-id "tee-m"})] unitless) "o")))
        "an opening needs it too")
    (is (:valid? (review (facts [(correction "u" [] [{:stock-item-id "tee-m" :unit "pcs"}]) (entry "o" {:stock-item-id "tee-m"})] unitless) "u" "o"))
        "a unit set in the same selection counts")))


(deftest tie-needs-a-choice
  (let [history (assoc-in opened-5 [:records 0 :kind] "opening")
        tied    [(correction "a" [["main" 3]] []) (correction "b" [["main" 4]] [])]
        result  (review (facts tied {:history history}) "a" "b")]
    (is (= #{:order-required} (problems result)))
    (is (= #{["a" ["a" "b"]] ["b" ["a" "b"]]} (set (map (juxt :entry-id :tied) (:problems result)))))
    (let [chosen (acceptance/review (facts tied {:history history}) {:selection [{:entry-id "b" :revision 1} {:entry-id "a" :revision 1}]
                                                                     :order     ["b" "a"] :ordered? true})]
      (is (:valid? chosen))
      (is (= [["tee-m" "main" 5 3]] (map (juxt :stock-item-id :warehouse-id :quantity-before :quantity-after) (:effects chosen)))
          "the member's order decides: b counts 4, then a counts 3"))
    (is (:valid? (review (facts [(correction "a" [["main" 3]] []) (assoc (correction "b" [["main" 4]] []) :occurred-at (day 18))] {:history history}) "a" "b"))
        "different seconds need no choice")))


(deftest first-unit-set-with-a-count
  (let [unitless (assoc-in catalogue [:stock-items 0 :unit] nil)]
    (is (:valid? (review (facts [(correction "c" [["main" 3]] [{:stock-item-id "tee-m" :unit "kg"}])] (assoc unitless :history opened-5)) "c"))
        "an item with history but no unit takes its first unit with the count")
    (is (= #{:unit-locked} (problems (review (facts [(correction "c" [] [{:stock-item-id "tee-m" :unit "kg"}])] {:history opened-5}) "c")))
        "a set unit stays locked")))


(deftest the-chosen-order-is-part-of-what-the-review-saw
  (let [f     (facts [(correction "a" [["main" 3]] []) (correction "b" [["main" 4]] [])] {:history opened-5})
        input {:selection [{:entry-id "a" :revision 1} {:entry-id "b" :revision 1}] :order ["a" "b"]}]
    (is (not= (:fingerprint (acceptance/review f input)) (:fingerprint (acceptance/review f (assoc input :ordered? true)))))))


(deftest an-opening-comes-first-at-the-same-second
  (let [result (acceptance/review (facts [(correction "c" [["main" 3]] []) (entry "o" {:stock-item-id "tee-m"})] {})
                                  {:selection [{:entry-id "c" :revision 1} {:entry-id "o" :revision 1}] :order ["c" "o"]})]
    (is (:valid? result) (pr-str (:problems result)))
    (is (= [["tee-m" "main" 0 3]] (map (juxt :stock-item-id :warehouse-id :quantity-before :quantity-after) (:effects result)))
        "opening 5, then the count sets 3, whatever order was sent")))


;; 18 · Review the purchase · 19 · Review the sale


(def suppliers
  {:suppliers [{:supplier-id "tex" :name "Textiles LLC" :status "active" :revision 1}
               {:supplier-id "gone" :name "Gone Ltd" :status "archived" :revision 2}]
   :stores    [{:store-id "web" :name "Web shop" :default-warehouse-id "main" :status "active" :revision 1}
               {:store-id "closed" :name "Closed" :status "archived" :revision 2}]})


(defn- operation
  {:malli/schema [:=> [:cat :string :string :map [:* :map]] :map]}
  [kind id fields & lines]
  (-> (apply entry id lines)
      (assoc :kind kind)
      (update :lines #(mapv (fn [line given] (priced kind (merge (dissoc line :unit-cost) (select-keys given [:unit-cost])))) % lines))
      (merge fields)))


(defn- values
  {:malli/schema [:=> [:cat :map] [:sequential :any]]}
  [result]
  (map (juxt :quantity-after #(.stripTrailingZeros ^BigDecimal (:value-after %))) (:effects result)))


(deftest purchase-values-by-moving-average
  (let [purchase #(operation "purchase" "p" {:supplier-id "tex"} (merge {:stock-item-id "tee-m"} %))]
    (is (= [[10 1500M]] (values (review (facts [(purchase {:unit-cost "200.00"})] (assoc suppliers :history opened-5)) "p")))
        "5 at 100.00 plus 5 at 200.00")
    (is (= #{:entry-incomplete} (problems (review (facts [(purchase {:unit-cost nil})] (assoc suppliers :history opened-5)) "p")))
        "units enter stock only at a cost")))


(deftest purchase-needs-a-supplier-and-a-line
  (is (= #{:supplier-missing} (problems (review (facts [(operation "purchase" "p" {} {:stock-item-id "tee-m"})] suppliers) "p"))))
  (is (= [:supplier-id] (map :field (:problems (review (facts [(operation "purchase" "p" {} {:stock-item-id "tee-m"})] suppliers) "p")))))
  (is (= #{:lines-missing} (problems (review (facts [(operation "purchase" "p" {:supplier-id "tex"})] suppliers) "p")))))


(deftest purchase-in-the-future-or-before-the-opening
  (is (= #{:future-occurrence}
         (problems (review (facts [(operation "purchase" "p" {:supplier-id "tex" :occurred-at (day 21)} {:stock-item-id "tee-m"})] suppliers) "p"))))
  (let [history (assoc-in opened-5 [:records 0 :occurred-at] (day 5))]
    (is (= #{:opening-not-first}
           (problems (review (facts [(operation "purchase" "p" {:supplier-id "tex" :occurred-at (day 3)} {:stock-item-id "tee-m"})]
                                    (assoc suppliers :history history))
                             "p"))))))


(deftest archived-warehouse-supplier-or-store-blocks
  (is (= #{:archived-reference}
         (problems (review (facts [(operation "purchase" "p" {:supplier-id "tex"} {:stock-item-id "tee-m" :warehouse-id "old"})] suppliers) "p"))))
  (is (= #{:archived-reference} (problems (review (facts [(operation "purchase" "p" {:supplier-id "gone"} {:stock-item-id "tee-m"})] suppliers) "p"))))
  (is (= #{:archived-reference}
         (problems (review (facts [(operation "sale" "s" {:store-id "closed"} {:stock-item-id "tee-m" :quantity 1})] (assoc suppliers :history opened-5)) "s")))))


(deftest new-supplier-registered-once
  (let [result (review (facts [(operation "purchase" "a" {:supplier-name "Cotton Co"} {:stock-item-id "tee-m"})
                               (assoc (operation "purchase" "b" {:supplier-name " cotton  co"} {:stock-item-id "tee-m"}) :occurred-at (day 18))]
                              suppliers)
                       "a" "b")]
    (is (:valid? result) (pr-str (:problems result)))
    (is (= [[:supplier "supplier:a" "Cotton Co"]] (map (juxt :type :id :label) (:registrations result))))
    (is (= #{"supplier:a"} (set (map :supplier-id (get-in result [:records :records])))))))


(deftest supplier-name-taken
  (let [result (review (facts [(operation "purchase" "p" {:supplier-name "textiles llc"} {:stock-item-id "tee-m"})] suppliers) "p")]
    (is (= #{:supplier-name-taken} (problems result)))
    (is (= "Textiles LLC" (:owner-supplier (first (:problems result)))))))


(deftest sale-values-at-the-average
  (let [sale (operation "sale" "s" {:store-id "web"} {:stock-item-id "tee-m" :quantity 3 :unit-price "150.00"})
        f    (facts [sale] (assoc suppliers :history (assoc-in opened-at-100 [:lines 0 :quantity-change] 10)))]
    (is (= [[7 700M]] (values (review f "s"))) "10 at 100.00, 3 sold")
    (is (= [[-3 "150.00" "web"]]
           (let [{:keys [records lines]} (:records (acceptance/review f {:selection [{:entry-id "s" :revision 1}] :order ["s"]}))]
             (map (juxt :quantity-change :unit-price (constantly (:store-id (first records)))) lines))))))


(deftest sale-shortage-names-the-pair
  (let [result (review (facts [(operation "sale" "s" {} {:stock-item-id "tee-m" :quantity 6})] (assoc suppliers :history opened-5)) "s")]
    (is (= #{:negative-balance} (problems result)))
    (is (= ["tee-m" "main" (day 19)] ((juxt :stock-item-id :warehouse-id :occurred-at) (first (:problems result)))))))


(deftest backdated-sale-breaks-a-later-one
  (let [history (-> opened-5
                    (update :records conj {:record-revision-id "s#1"    :record-id "s" :revision  1     :acceptance-id "a"      :kind "sale"
                                           :occurred-at        (day 10) :sequence  0   :cancelled false :recorded-at   (day 10)})
                    (update :lines conj {:record-line-id "s#1/0" :record-revision-id "s#1" :position 0 :stock-item-id "tee-m"
                                         :warehouse-id   "main"  :quantity-change    -4}))]
    (is (= #{:negative-balance}
           (problems (review (facts [(operation "sale" "n" {:occurred-at (day 5)} {:stock-item-id "tee-m" :quantity 2})] (assoc suppliers :history history)) "n"))))))


(deftest purchase-covers-a-pending-sale
  (let [result (review (facts [(operation "purchase" "p" {:supplier-id "tex" :occurred-at (day 18)} {:stock-item-id "tee-m" :unit-cost "100.00"})
                               (operation "sale" "s" {} {:stock-item-id "tee-m" :quantity 3})]
                              (assoc suppliers :history (assoc-in opened-5 [:lines 0 :quantity-change] 0)))
                       "p" "s")]
    (is (:valid? result) (pr-str (:problems result)))
    (is (= [[2 200M]] (values result)))))


(deftest sales-at-the-same-second-need-an-order
  (is (= #{:order-required}
         (problems (review (facts [(operation "sale" "a" {} {:stock-item-id "tee-m" :quantity 1})
                                   (operation "sale" "b" {} {:stock-item-id "tee-m" :quantity 1})]
                                  (assoc suppliers :history opened-5))
                           "a" "b")))))


(deftest accepted-operations-newest-first-with-totals
  (let [record (fn [id kind at] {:record-revision-id (str id "#1") :record-id id    :revision    1        :acceptance-id "a" :kind kind :occurred-at (day at)
                                 :sequence           0             :cancelled false :recorded-at (day at)})
        line   (fn [id pos q & [k v]] (cond-> {:record-line-id (str id "#1/" pos) :record-revision-id (str id "#1") :position        pos
                                               :stock-item-id  "tee-m"            :warehouse-id       "main"        :quantity-change q}
                                        k (assoc k v)))
        ops    (acceptance/operations {:records [(record "o" "opening" 1) (record "p" "purchase" 2) (record "s" "sale" 3)]
                                       :lines   [(line "p" 0 5 :unit-cost "200.00") (line "p" 1 2) (line "s" 0 -3 :unit-price "150.00")]})]
    (is (= [["s" 450M false] ["p" 1000M true]]
           (map (juxt :record-id #(.stripTrailingZeros ^BigDecimal (:total %)) :partial?) ops)))))


(deftest fingerprint-sees-the-store
  (let [f   (facts [(operation "sale" "s" {:store-id "web"} {:stock-item-id "tee-m" :quantity 1})] (assoc suppliers :history opened-5))
        sel {:selection [{:entry-id "s" :revision 1}] :order ["s"]}]
    (is (not= (:fingerprint (acceptance/review f sel))
              (:fingerprint (acceptance/review (assoc-in f [:stores 0 :name] "Shop") sel))))))


;; 20–25 · Review returns, transfers, production, defects, write-offs and payments


(def more
  (-> (merge catalogue suppliers)
      (assoc :history opened-5)
      (update :warehouses conj {:warehouse-id "back" :name "Back" :status "active" :revision 1})
      (update :stock-items conj {:stock-item-id "mug-1" :product-id "mug" :variant-label "" :unit "pcs" :condition "regular" :status "active" :revision 1})))


(defn- cells
  {:malli/schema [:=> [:cat :map] [:sequential :any]]}
  [result]
  (map (juxt :stock-item-id :warehouse-id :quantity-after #(some-> ^BigDecimal (:value-after %) .stripTrailingZeros)) (:effects result)))


(deftest returns-move-at-the-average
  (is (= [[7 700M]] (values (review (facts [(operation "customer-return" "r" {:store-id "web"} {:stock-item-id "tee-m" :quantity 2 :unit-price "150.00"})] more) "r")))
      "a customer return enters at the average, whatever price it carries")
  (is (= [[3 300M]] (values (review (facts [(operation "supplier-return" "r" {:supplier-id "tex"} {:stock-item-id "tee-m" :quantity 2})] more) "r"))))
  (is (= #{:supplier-missing} (problems (review (facts [(operation "supplier-return" "r" {} {:stock-item-id "tee-m" :quantity 2})] more) "r"))))
  (is (= #{:negative-balance} (problems (review (facts [(operation "supplier-return" "r" {:supplier-id "tex"} {:stock-item-id "tee-m" :quantity 6})] more) "r")))))


(def at-150
  "10 at 100.00 and 10 at 200.00 in Main."
  (-> opened-at-100
      (assoc-in [:lines 0 :quantity-change] 10)
      (update :records conj {:record-revision-id "p#1" :record-id "p"   :revision    1       :acceptance-id "a" :kind "purchase" :occurred-at (day 2)
                             :sequence           0     :cancelled false :recorded-at (day 2)})
      (update :lines conj {:record-line-id  "p#1/0" :record-revision-id "p#1"    :position 0 :stock-item-id "tee-m" :warehouse-id "main"
                           :quantity-change 10      :unit-cost          "200.00"})))


(deftest transfer-carries-the-cost
  (let [transfer #(operation "transfer" "t" {:from-warehouse-id "main" :to-warehouse-id "back"} {:stock-item-id "tee-m" :warehouse-id nil :quantity %})]
    (is (= [["tee-m" "back" 5 750M] ["tee-m" "main" 15 2250M]]
           (sort (cells (review (facts [(transfer 5)] (assoc more :history at-150)) "t")))))
    (is (= #{:negative-balance} (problems (review (facts [(transfer 21)] (assoc more :history at-150)) "t"))))
    (is (= [[:entry-incomplete :to-warehouse-id]]
           (map (juxt :code :field) (:problems (review (facts [(assoc (transfer 5) :to-warehouse-id nil)] more) "t")))))))


(deftest production-spreads-the-input-cost
  (let [history (assoc-in opened-at-100 [:lines 0] {:record-line-id "r#1/0" :record-revision-id "r#1" :position  0       :stock-item-id "tee-m"
                                                    :warehouse-id   "main"  :quantity-change    12    :unit-cost "10.00"})
        made    #(operation "production" "m" {} {:stock-item-id "tee-m" :quantity 12 :side "input"} {:stock-item-id "mug-1" :quantity % :side "output"})]
    (is (= [["mug-1" "main" 1 120M] ["tee-m" "main" 0 0M]] (sort (cells (review (facts [(made 1)] (assoc more :history history)) "m")))))
    (is (= [["mug-1" "main" 2 120M] ["tee-m" "main" 0 0M]] (sort (cells (review (facts [(made 2)] (assoc more :history history)) "m"))))
        "two units share 120.00")
    (is (= #{:outputs-missing} (problems (review (facts [(operation "production" "m" {} {:stock-item-id "tee-m" :quantity 1 :side "input"})] more) "m"))))
    (is (= #{:inputs-missing} (problems (review (facts [(operation "production" "m" {} {:stock-item-id "mug-1" :quantity 1 :side "output"})] more) "m"))))
    (is (= #{:negative-balance} (problems (review (facts [(made 1)] more) "m"))) "5 in stock, 12 needed")))


(deftest defect-moves-to-the-counterpart
  (let [defect #(operation "defect" % {:reason "torn"} {:stock-item-id "tee-m" :quantity 2})
        result (review (facts [(defect "d") (assoc (defect "e") :occurred-at (day 18))] more) "e" "d")]
    (is (:valid? result) (pr-str (:problems result)))
    (is (= [["tee-m" "main" 1 100M] ["tee-m~defect" "main" 4 400M]] (sort (cells result))) "cost follows the units")
    (is (= [[:stock-item "tee-m~defect" "T-shirt / M · defect"]] (map (juxt :type :id :label) (:registrations result))) "one counterpart for two defects")
    (is (= #{:reason-missing} (problems (review (facts [(dissoc (defect "d") :reason)] more) "d"))))))


(deftest write-off-at-the-average
  (let [write-off #(operation "write-off" "w" {:reason "lost"} {:stock-item-id "tee-m" :quantity %})]
    (is (= [[3 300M]] (values (review (facts [(write-off 2)] more) "w"))))
    (is (= #{:negative-balance} (problems (review (facts [(write-off 6)] more) "w"))))
    (is (= #{:reason-missing} (problems (review (facts [(dissoc (write-off 2) :reason)] more) "w"))))))


(deftest payment-is-a-record-without-lines
  (let [payment (assoc (operation "payment" "pay" {:direction "out" :supplier-id "tex" :amount "1500.00"}) :lines [])
        result  (review (facts [payment] more) "pay")]
    (is (:valid? result) (pr-str (:problems result)))
    (is (= [["payment" "out" "tex" "1500.00"]] (map (juxt :kind :direction :supplier-id :amount) (get-in result [:records :records]))))
    (is (= [] (get-in result [:records :lines])))
    (is (= #{:direction-missing :counterparty-missing :amount-missing}
           (problems (review (facts [(assoc payment :direction nil :supplier-id nil :amount nil)] more) "pay"))))))


(deftest backdated-transfer-or-write-off-breaks-a-later-sale
  (let [history (-> opened-5
                    (update :records conj {:record-revision-id "s#1"    :record-id "s" :revision  1     :acceptance-id "a"      :kind "sale"
                                           :occurred-at        (day 10) :sequence  0   :cancelled false :recorded-at   (day 10)})
                    (update :lines conj {:record-line-id "s#1/0" :record-revision-id "s#1" :position 0 :stock-item-id "tee-m"
                                         :warehouse-id   "main"  :quantity-change    -4}))
        f       #(facts [%] (assoc more :history history))]
    (is (= #{:negative-balance}
           (problems (review (f (operation "transfer" "t" {:occurred-at (day 5) :from-warehouse-id "main" :to-warehouse-id "back"} {:stock-item-id "tee-m" :warehouse-id nil :quantity 2})) "t"))))
    (is (= #{:negative-balance}
           (problems (review (f (operation "write-off" "w" {:occurred-at (day 5) :reason "lost"} {:stock-item-id "tee-m" :quantity 2})) "w"))))))


(deftest archived-transfer-warehouse-blocks
  (is (= #{:archived-reference}
         (problems (review (facts [(operation "transfer" "t" {:from-warehouse-id "main" :to-warehouse-id "old"} {:stock-item-id "tee-m" :warehouse-id nil :quantity 1})] more) "t")))))
