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
   :stock-items [{:stock-item-id "tee-m" :product-id "tee" :variant-label "M" :article "T-100" :condition "regular" :status "active"
                  :revision      1}]
   :barcodes    [{:barcode-id "tee-m|4600000000017" :stock-item-id "tee-m" :barcode "4600000000017"}]
   :warehouses  [{:warehouse-id "main" :name "Main" :status "active" :revision 1}
                 {:warehouse-id "old" :name "Old" :status "archived" :revision 2}]})


(defn- entry
  {:malli/schema [:=> [:cat :string [:* :map]] :map]}
  [id & lines]
  {:entry-id id :kind "opening" :occurred-at (day 19) :status "pending" :revision 1
   :lines    (vec (map-indexed #(merge {:line-id (str id "-" %1) :warehouse-id "main" :quantity 5} %2) lines))})


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
    (is (:valid? (review (facts [(correction "c" [] [{:stock-item-id "tee-m" :product-id "mug" :variant-label "M2"}])] catalogue) "c")))))


(deftest renamed-variant-must-stay-unique
  (let [catalogue (update catalogue :stock-items conj {:stock-item-id "tee-l" :product-id "tee" :variant-label "L" :status "active" :revision 1})]
    (is (= #{:duplicate-variant} (problems (review (facts [(correction "c" [] [{:stock-item-id "tee-m" :variant-label " l"}])] catalogue) "c"))))))
