(ns tover.inventory.findings-test
  "Board chapter 16 · Read draft findings: one test per example, firing on the reported case and silent on the correct one."
  (:require
    [clojure.test :refer [deftest is testing]]
    [tover.inventory.findings :as findings]))


(def catalogue
  {:products    [{:product-id "tee" :name "T-shirt" :status "active"} {:product-id "mug" :name "Mug" :status "active"}]
   :stock-items [{:stock-item-id "tee-m" :product-id "tee" :variant-label "M" :article "T-100" :unit "pcs" :status "active"}]
   :barcodes    [{:stock-item-id "tee-m" :barcode "4600000000017"}]
   :warehouses  [{:warehouse-id "main" :name "Main" :status "active"}]
   :balances    {}})


(defn- entry
  {:malli/schema [:=> [:cat :string :string [:* :map]] :map]}
  [id kind & lines]
  {:entry-id id :kind kind :lines (vec (map-indexed #(merge {:line-id (str id "-" %1)} %2) lines))})


(defn- run
  {:malli/schema [:=> [:cat :map [:* :map]] :any]}
  [facts & entries]
  (findings/derive (merge catalogue facts {:entries (vec entries)})))


(defn- codes
  {:malli/schema [:=> [:cat [:sequential :map]] [:set :any]]}
  [found]
  (set (map (juxt :line-id :code :level) found)))


(deftest new-warehouse-with-similar-name
  (let [found (run {} (entry "e" "opening" {:stock-item-id "tee-m" :warehouse-name "main " :quantity 1}))]
    (is (= #{["e-0" :new-reference :info] ["e-0" :similar-reference :warning]} (codes found)))
    (is (= {:id "main" :name "Main"} (:related (first (filter #(= :similar-reference (:code %)) found)))))
    (is (= #{:warehouse-name} (set (map :field found))))))


(deftest overdraw-by-one-entry
  (let [facts {:balances {["tee-m" "main"] {:quantity 10}}}]
    (is (= #{["s-0" :insufficient-stock :error]}
           (codes (run facts (entry "s" "sale" {:stock-item-id "tee-m" :warehouse-id "main" :quantity 12})))))
    (is (empty? (run facts (entry "s" "sale" {:stock-item-id "tee-m" :warehouse-id "main" :quantity 10}))))))


(deftest combined-overdraw-warns-every-contributor
  (is (= #{["a-0" :insufficient-stock :warning] ["b-0" :insufficient-stock :warning]}
         (codes (run {:balances {["tee-m" "main"] {:quantity 10}}}
                     (entry "a" "sale" {:stock-item-id "tee-m" :warehouse-id "main" :quantity 6})
                     (entry "b" "sale" {:stock-item-id "tee-m" :warehouse-id "main" :quantity 6}))))))


(deftest pending-opening-counts-as-incoming
  (is (empty? (run {}
                   (entry "o" "opening" {:stock-item-id "tee-m" :warehouse-id "main" :quantity 5})
                   (entry "s" "sale" {:stock-item-id "tee-m" :warehouse-id "main" :quantity 4})))))


(deftest barcode-shared-within-a-product-is-fine
  (is (not-any? #(= :barcode-conflict (:code %))
                (run {} (entry "e" "opening" {:product-id "tee" :variant-label "L" :barcodes ["4600000000017"] :warehouse-id "main" :quantity 1}))))
  (let [found (filter #(= :barcode-conflict (:code %))
                      (run {} (entry "e" "opening" {:product-id "mug" :variant-label "blue" :barcodes ["4600000000017"] :warehouse-id "main"
                                                    :quantity   1})))]
    (is (= [[:barcodes :error {:id "tee" :name "T-shirt"}]] (map (juxt :field :level :related) found))))
  (testing "a barcode claimed for a different product by another pending entry"
    (is (= #{["a-0" :barcode-conflict :error] ["b-0" :barcode-conflict :error]}
           (set (filter #(= :barcode-conflict (second %))
                        (codes (run {}
                                    (entry "a" "opening" {:product-id "tee" :variant-label "XL" :barcodes ["111"]})
                                    (entry "b" "opening" {:product-id "mug" :variant-label "red" :barcodes ["111"]})))))))))


(deftest article-conflict
  (let [found (filter #(= :article-conflict (:code %))
                      (run {} (entry "e" "opening" {:product-id "mug" :variant-label "blue" :article " t-100 "})))]
    (is (= [[:article :error {:id "tee" :name "T-shirt"}]] (map (juxt :field :level :related) found))))
  (is (not-any? #(= :article-conflict (:code %)) (run {} (entry "e" "opening" {:product-id "tee" :variant-label "L" :article "T-100"})))))


(deftest duplicate-variant
  (is (contains? (codes (run {} (entry "e" "opening" {:product-id "tee" :variant-label "m"}))) ["e-0" :duplicate-variant :error]))
  (is (contains? (codes (run {} (entry "a" "opening" {:product-name "Cup" :variant-label "Big"})
                             (entry "b" "opening" {:product-name " cup" :variant-label "big "})))
                 ["b-0" :duplicate-variant :error])
      "another pending entry's proposed label for the same proposed product")
  (is (not (contains? (codes (run {} (entry "e" "opening" {:product-id "tee" :variant-label "L"}))) ["e-0" :duplicate-variant :error]))))


(deftest new-product-and-item-will-be-created
  (is (= #{[:product-name :new-reference] [:variant-label :new-reference]}
         (set (map (juxt :field :code) (run {} (entry "e" "opening" {:product-name "Cup" :variant-label "Big"})))))))


(deftest incomplete-entry-evaluates-without-error
  (is (vector? (run {} (entry "e" "opening" {} {:product-name "  "} {:quantity 0 :warehouse-name ""})))))


(deftest rules-unavailable-show-no-findings
  (with-redefs [findings/derive (fn [_] (Thread/sleep 5000) [])]
    (is (nil? (findings/derive-within-limit {}))))
  (with-redefs [findings/derive (fn [_] (throw (ex-info "boom" {})))]
    (is (nil? (findings/derive-within-limit {})))))


;; 15 · Findings of corrections


(defn- correction
  {:malli/schema [:=> [:cat :string [:sequential :map] [:sequential :map]] :map]}
  [id counts changes]
  (cond-> (apply entry id "correction" (map #(merge {:stock-item-id "tee-m" :warehouse-id "main"} %) counts))
    (seq changes) (assoc :changes (vec changes))))


(defn- change-codes
  {:malli/schema [:=> [:cat [:sequential :map]] [:set :any]]}
  [found]
  (set (map (juxt :entry-id :stock-item-id :code :level) found)))


(def stocked {:balances {["tee-m" "main"] {:quantity 5}}})


(deftest count-direction-against-the-accepted-balance
  (is (= #{["c-0" :insufficient-stock :warning] ["s-0" :insufficient-stock :warning]}
         (codes (run stocked (correction "c" [{:quantity 3}] []) (entry "s" "sale" {:stock-item-id "tee-m" :warehouse-id "main" :quantity 4}))))
      "counting 3 of 5 takes 2 out")
  (is (empty? (run stocked (correction "c" [{:quantity 8}] []) (entry "s" "sale" {:stock-item-id "tee-m" :warehouse-id "main" :quantity 7})))
      "counting 8 of 5 brings 3 in"))


(deftest unit-locked
  (is (= #{["c" "tee-m" :unit-locked :error]} (change-codes (run stocked (correction "c" [] [{:stock-item-id "tee-m" :unit "kg"}])))))
  (is (empty? (run {} (correction "c" [] [{:stock-item-id "tee-m" :unit "kg"}])))))


(deftest archive-blocked
  (let [found (run stocked (correction "c" [] [{:stock-item-id "tee-m" :archive true}]))]
    (is (= #{["c" "tee-m" :archive-blocked :error]} (change-codes found)))
    (is (= {:warehouse-id "main"} (:related (first found)))))
  (is (empty? (run stocked (correction "c" [{:quantity 0}] [{:stock-item-id "tee-m" :archive true}]))) "counted to zero in the same entry")
  (is (= {:other-entry-id "o"}
         (:related (first (run {} (correction "c" [] [{:stock-item-id "tee-m" :archive true}])
                               (entry "o" "opening" {:stock-item-id "tee-m" :warehouse-id "main" :quantity 1})))))))


(deftest restore-needs-product
  (is (= #{["c" "tee-m" :restore-needs-product :error]}
         (change-codes (run {:products [{:product-id "tee" :name "T-shirt" :status "archived"}]}
                            (correction "c" [] [{:stock-item-id "tee-m" :restore true}]))))))


(deftest move-conflict
  (is (= #{["c" "tee-m" :move-conflict :error]} (change-codes (run {} (correction "c" [] [{:stock-item-id "tee-m" :product-id "tee"}])))))
  (is (empty? (run {} (correction "c" [] [{:stock-item-id "tee-m" :product-id "mug"}]))))
  (testing "to an archived product"
    (is (= #{["c" "tee-m" :move-conflict :error]}
           (change-codes (run {:products [{:product-id "tee" :name "T-shirt" :status "active"} {:product-id "mug" :name "Mug" :status "archived"}]}
                              (correction "c" [] [{:stock-item-id "tee-m" :product-id "mug"}]))))))
  (testing "an article or barcode an item staying in the product keeps names the value and that item"
    (let [staying {:stock-items (conj (:stock-items catalogue) {:stock-item-id "tee-l" :product-id "tee" :variant-label "L" :article "T-100" :status "active"})
                   :barcodes    (conj (:barcodes catalogue) {:stock-item-id "tee-l" :barcode "4600000000017"})}
          found   (run staying (correction "c" [] [{:stock-item-id "tee-m" :product-id "mug"}]))]
      (is (= #{["c" "tee-m" :move-conflict :error]} (change-codes found)))
      (is (= #{{:value "T-100" :other-item "tee-l"} {:value "4600000000017" :other-item "tee-l"}}
             (set (map #(select-keys (:related %) [:value :other-item]) found))))
      (is (empty? (run staying (correction "c" [] [{:stock-item-id "tee-m" :product-id "mug" :article "M-1" :barcodes-removed ["4600000000017"]}])))
          "changing both in the same entry lets the move through"))))


(deftest first-unit-on-an-item-with-history-is-not-locked
  (is (empty? (run (assoc stocked :stock-items [(dissoc (first (:stock-items catalogue)) :unit)])
                   (correction "c" [{:quantity 5}] [{:stock-item-id "tee-m" :unit "kg"}])))))


(deftest silent-when-the-change-is-fine
  (is (empty? (run {} (correction "c" [] [{:stock-item-id "tee-m" :restore true}]))) "restore with an active product")
  (is (empty? (run {} (correction "a" [] [{:stock-item-id "tee-m" :article "A"}]) (correction "b" [] [{:stock-item-id "tee-m" :unit "kg"}])))
      "two entries changing different fields"))


(deftest field-conflict
  (is (= #{["a" "tee-m" :field-conflict :warning] ["b" "tee-m" :field-conflict :warning]}
         (change-codes (run {} (correction "a" [] [{:stock-item-id "tee-m" :article "A"}])
                            (correction "b" [] [{:stock-item-id "tee-m" :article "B"}]))))))


(deftest edited-values-keep-ownership
  (let [facts {:stock-items (conj (:stock-items catalogue) {:stock-item-id "mug-1" :product-id "mug" :variant-label "1" :status "active"})}]
    (is (= #{["c" "mug-1" :barcode-conflict :error]}
           (change-codes (run facts (correction "c" [] [{:stock-item-id "mug-1" :barcodes-added ["4600000000017"]}])))))
    (is (= #{["c" "mug-1" :article-conflict :error]}
           (change-codes (run facts (correction "c" [] [{:stock-item-id "mug-1" :article "t-100"}])))))
    (is (= #{["c" "mug-1" :duplicate-variant :error]}
           (change-codes (run (update facts :stock-items conj {:stock-item-id "mug-2" :product-id "mug" :variant-label "2" :status "active"})
                              (correction "c" [] [{:stock-item-id "mug-1" :variant-label "2"}])))))))


(def two-mugs
  {:stock-items [{:stock-item-id "tee-m" :product-id "tee" :variant-label "M" :article "T-100" :status "active"}
                 {:stock-item-id "mug-1" :product-id "mug" :variant-label "1" :status "active"}
                 {:stock-item-id "mug-2" :product-id "mug" :variant-label "2" :status "active"}]})


(deftest moving-to-a-taken-variant-is-a-move-conflict
  (is (= #{["c" "tee-m" :move-conflict :error]}
         (change-codes (run (update two-mugs :stock-items conj {:stock-item-id "mug-m" :product-id "mug" :variant-label "m" :status "active"})
                            (correction "c" [] [{:stock-item-id "tee-m" :product-id "mug"}]))))))


(deftest field-conflict-on-barcodes-is-per-barcode
  (is (empty? (run {} (correction "a" [] [{:stock-item-id "tee-m" :barcodes-added ["4600000000024"]}])
                   (correction "b" [] [{:stock-item-id "tee-m" :barcodes-added ["4600000000031"]}])))
      "two different barcodes do not conflict")
  (let [found (run {} (correction "a" [] [{:stock-item-id "tee-m" :barcodes-added ["4600000000024"]}])
                   (correction "b" [] [{:stock-item-id "tee-m" :barcodes-removed ["4600000000024"]}]))]
    (is (= #{["a" "tee-m" :field-conflict :warning] ["b" "tee-m" :field-conflict :warning]} (change-codes found)))
    (is (= #{:barcodes} (set (map :field found))))
    (is (= #{{:value "4600000000024"}} (set (map :related found))))))


(deftest edited-values-checked-against-other-pending-entries
  (testing "variant label claimed by another pending change in the same product"
    (is (= #{["a" "mug-1" :duplicate-variant :error] ["b" "mug-2" :duplicate-variant :error]}
           (change-codes (run two-mugs (correction "a" [] [{:stock-item-id "mug-1" :variant-label "blue"}])
                              (correction "b" [] [{:stock-item-id "mug-2" :variant-label "Blue"}]))))))
  (testing "article claimed for another product by another pending change"
    (is (= #{["a" "mug-1" :article-conflict :error] ["b" "tee-m" :article-conflict :error]}
           (change-codes (run two-mugs (correction "a" [] [{:stock-item-id "mug-1" :article "X-1"}])
                              (correction "b" [] [{:stock-item-id "tee-m" :article "x-1"}])))))
    (is (empty? (run two-mugs (correction "a" [] [{:stock-item-id "mug-1" :article "X-1"}])
                     (correction "b" [] [{:stock-item-id "mug-2" :article "x-1"}])))
        "sharing within one product is fine"))
  (testing "barcode a change adds is claimed by a new item of another product"
    (is (= #{["a" "mug-1" :barcode-conflict :error]}
           (change-codes (filter :stock-item-id
                                 (run two-mugs (correction "a" [] [{:stock-item-id "mug-1" :barcodes-added ["4600000000048"]}])
                                      (entry "o" "opening" {:product-id "tee" :variant-label "L" :barcodes ["4600000000048"] :warehouse-id "main" :quantity 1}))))))
    (is (some #(= ["o-0" :barcode-conflict :error] %)
              (codes (run two-mugs (correction "a" [] [{:stock-item-id "mug-1" :barcodes-added ["4600000000048"]}])
                          (entry "o" "opening" {:product-id "tee" :variant-label "L" :barcodes ["4600000000048"] :warehouse-id "main" :quantity 1}))))
        "and the new item's line shows it too")))


;; 18 · Propose the purchase


(deftest new-supplier-with-a-similar-name
  (let [facts {:suppliers [{:supplier-id "tex" :name "Textiles LLC" :status "active"}]}
        found (run facts (assoc (entry "p" "purchase" {:stock-item-id "tee-m" :warehouse-id "main" :quantity 1}) :supplier-name "textiles  llc"))]
    (is (= #{[nil :new-reference :info] [nil :similar-reference :warning]} (codes found)))
    (is (= #{:supplier-name} (set (map :field found))))
    (is (= {:id "tex" :name "Textiles LLC"} (:related (first (filter #(= :similar-reference (:code %)) found))))))
  (is (= #{[nil :new-reference :info]}
         (codes (run {:suppliers []} (assoc (entry "p" "purchase" {:stock-item-id "tee-m" :warehouse-id "main" :quantity 1}) :supplier-name "Cotton Co"))))))


(deftest pending-purchase-covers-a-sale
  (is (empty? (run {}
                   (entry "p" "purchase" {:stock-item-id "tee-m" :warehouse-id "main" :quantity 5})
                   (entry "s" "sale" {:stock-item-id "tee-m" :warehouse-id "main" :quantity 3})))))
