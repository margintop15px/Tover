(ns tover.inventory.findings-test
  "Board chapter 16 · Read draft findings: one test per example, firing on the reported case and silent on the correct one."
  (:require
    [clojure.test :refer [deftest is testing]]
    [tover.inventory.findings :as findings]))


(def catalogue
  {:products    [{:product-id "tee" :name "T-shirt" :status "active"} {:product-id "mug" :name "Mug" :status "active"}]
   :stock-items [{:stock-item-id "tee-m" :product-id "tee" :variant-label "M" :article "T-100" :status "active"}]
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
