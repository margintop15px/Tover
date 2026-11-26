(ns tover.marketplace.candidates-test
  "Board chapter 42 slice 1 · candidate rules. Pure: rows in, candidates out."
  (:require
    [clojure.test :refer [deftest is]]
    [tover.marketplace.products.candidates :as candidates :refer [candidates identity-basis]]))


(defn product
  {:malli/schema [:=> [:cat :string :string] :map]}
  [id name]
  {:product-id id :name name :status "active" :revision 1})


(defn item
  {:malli/schema [:=> [:cat :string :string [:* :any]] :map]}
  [id product-id & {:as more}]
  (merge {:stock-item-id id :product-id product-id :condition "regular" :status "active" :revision 1} more))


(defn barcode
  {:malli/schema [:=> [:cat :string :string] :map]}
  [item-id code]
  {:stock-item-id item-id :barcode code})


(defn listing
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [barcodes articles name attributes]}]
  {:name        name
   :identifiers (vec (concat (map (fn [v] {:type "barcode" :value v :active true}) barcodes)
                             (map (fn [v] {:type "article" :value v :active true}) articles)))
   :attributes  (or attributes [])})


(def catalogue
  {:products    [(product "mug" "Mug") (product "plate" "Plate")]
   :stock-items [(item "mug-1" "mug" :article "MUG-RED" :variant-label "red") (item "plate-1" "plate" :article "PL-1")]
   :barcodes    [(barcode "mug-1" "4600000000017") (barcode "mug-1" "mug-code") (barcode "plate-1" "plate-code")]})


(def shirts
  {:products    [(product "tee" "T-shirt")]
   :stock-items [(item "tee-m" "tee" :variant-label "M") (item "tee-l" "tee" :variant-label "L")]
   :barcodes    [(barcode "tee-m" "1") (barcode "tee-l" "1")]})


(defn counts
  {:malli/schema [:=> [:cat :map] :map]}
  [result]
  {:candidates-preselected (count (filter :preselected? (:candidates result))) :conflicts (count (:conflicts result))})


(deftest one-item-owns-the-barcode
  (let [result (:candidates (candidates catalogue (listing {:barcodes ["4600000000017"]})))]
    (is (= [["mug-1" "barcode-owner" true]] (map (juxt :stock-item-id :rule :preselected?) result)))
    (is (= {:barcode "4600000000017" :product-id "mug"} (:evidence (first result))))
    (is (= 1 (:item-revision (first result))))))


(deftest barcode-shared-by-sizes
  (is (= [["tee-l" false] ["tee-m" false]]
         (map (juxt :stock-item-id :preselected?) (:candidates (candidates shirts (listing {:barcodes ["1"]})))))))


(deftest barcode-shared-by-sizes-the-listing-names-one
  (is (= [["tee-l" false] ["tee-m" true]]
         (map (juxt :stock-item-id :preselected?)
              (:candidates (candidates shirts (listing {:barcodes   ["1"]
                                                        :attributes [{:attribute-id "4295" :values [{:value "M"}]}]})))))))


(deftest two-owners
  (let [result (candidates catalogue (listing {:barcodes ["mug-code" "plate-code"]}))]
    (is (= 0 (:candidates-preselected (counts result))))
    (is (= [{:barcode "mug-code" :product-ids ["mug"]} {:barcode "plate-code" :product-ids ["plate"]}] (:conflicts result)))
    (is (= #{"mug-1" "plate-1"} (set (map :stock-item-id (:candidates result)))))))


(deftest barcode-and-article-disagree
  (let [result (candidates catalogue (listing {:barcodes ["mug-code"] :articles ["pl-1"]}))]
    (is (= {:candidates-preselected 0 :conflicts 0} (counts result)))
    (is (= #{"mug-1" "plate-1"} (set (map :stock-item-id (:candidates result)))))))


(deftest article-matches-case-insensitively
  (is (= [["plate-1" "article-match" true]]
         (map (juxt :stock-item-id :rule :preselected?)
              (:candidates (candidates catalogue (listing {:articles ["  pl-1 "]})))))))


(deftest barcode-outranks-article-for-the-same-item
  (is (= [["mug-1" "barcode-owner" true]]
         (map (juxt :stock-item-id :rule :preselected?)
              (:candidates (candidates catalogue (listing {:barcodes ["mug-code"] :articles ["mug-red"]})))))))


(deftest name-equal-is-never-preselected
  (is (= [["mug-1" "name-equal" false]]
         (map (juxt :stock-item-id :rule :preselected?)
              (:candidates (candidates catalogue (listing {:name "  Mug   RED "}))))))
  (is (= [["mug-1" "name-equal" false]]
         (map (juxt :stock-item-id :rule :preselected?)
              (:candidates (candidates catalogue (listing {:name "mug"})))))))


(deftest no-approximate-name-search
  (is (= {:candidates [] :conflicts []} (candidates catalogue (listing {:name "Mugs"})))))


(deftest archived-and-defective-are-no-candidates
  (let [catalogue (assoc catalogue
                         :stock-items [(item "mug-1" "mug") (item "mug-2" "mug" :status "archived")
                                       (item "mug-3" "mug" :condition "defective" :regular-item-id "mug-1")]
                         :barcodes [(barcode "mug-1" "m") (barcode "mug-2" "m") (barcode "mug-3" "m")])]
    (is (= [["mug-1" true]]
           (map (juxt :stock-item-id :preselected?) (:candidates (candidates catalogue (listing {:barcodes ["m"]}))))))))


(deftest zero-and-blank-barcodes-are-ignored
  (is (= {:candidates [] :conflicts []} (candidates catalogue (listing {:barcodes ["0" "" " "]})))))


(deftest unit-and-label-pick-one-of-several-items
  (let [catalogue {:products    [(product "tee" "T-shirt")]
                   :stock-items [(item "tee-m" "tee" :variant-label "M") (item "tee-l" "tee" :variant-label "L" :unit "kg")]
                   :barcodes    [(barcode "tee-m" "1") (barcode "tee-l" "1")]}
        sized     (fn [size] (listing {:barcodes ["1"] :attributes [{:attribute-id "4295" :values [{:value size}]}]}))]
    (is (= {:candidates-preselected 1 :conflicts 0} (counts (candidates catalogue (sized "M")))))
    (is (= {:candidates-preselected 0 :conflicts 0} (counts (candidates catalogue (sized "L")))))))


(deftest barcode-owners-skip-shared-and-archived
  (is (= {"4600000000017" "mug" "mug-code" "mug" "plate-code" "plate"}
         (candidates/barcode-owners (:barcodes catalogue) (:stock-items catalogue))))
  (is (= {} (candidates/barcode-owners [(barcode "a" "x") (barcode "b" "x")] [(item "a" "p1") (item "b" "p2")])))
  (is (= {} (candidates/barcode-owners [(barcode "a" "x")] [(item "a" "p1" :status "archived")]))))


(deftest identity-basis-keeps-variant-attributes
  (let [attributes  [{:attribute-id "1" :values [{:value "M"}]} {:attribute-id "2" :values [{:value "cotton"}]}
                     {:attribute-id "3" :values [{:value "red"}]} {:attribute-id "4" :values [{:value "x"}]}]
        definitions [{:attribute-id "1" :name "Размер" :separates-variants false}
                     {:attribute-id "2" :name "Материал" :separates-variants false}
                     {:attribute-id "3" :name "Цвет товара" :separates-variants false}
                     {:attribute-id "4" :name "Модель" :separates-variants true}]
        basis       (identity-basis {:attributes attributes :unit-and-pack "2"} definitions {:unit "pcs" :variant-label "M"})]
    (is (= {:attributes {"1" ["M"] "3" ["red"] "4" ["x"]} :pack "2" :unit "pcs" :variant-label "M"} basis))
    (is (= {"1" ["M"] "2" ["cotton"] "3" ["red"] "4" ["x"]}
           (:attributes (identity-basis {:attributes attributes} [] nil))))))
