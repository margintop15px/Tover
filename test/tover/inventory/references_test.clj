(ns tover.inventory.references-test
  "Board chapter 32a · Read active catalogue suggestions and resolve draft identities."
  (:require
    [clojure.test :refer [deftest is testing]]
    [tover.inventory.references :as references]))


(def facts
  {:products    [{:product-id "tee" :name "T-shirt" :status "active"}
                 {:product-id "mug" :name "Mug" :status "active" :default-warehouse-id "main"}
                 {:product-id "screws" :name "Screws" :status "active"}
                 {:product-id "old" :name "T-shirt old" :status "archived"}]
   :stock-items [{:stock-item-id "tee-m" :product-id "tee" :variant-label "M" :article "T-100" :unit "pcs" :status "active"}
                 {:stock-item-id "tee-l" :product-id "tee" :variant-label "L" :status "active"}
                 {:stock-item-id "screw" :product-id "screws" :status "active"}
                 {:stock-item-id "old-1" :product-id "old" :status "archived"}]
   :barcodes    [{:stock-item-id "tee-m" :barcode "4600000000017"}]
   :warehouses  [{:warehouse-id "main" :name "Main" :status "active"}]})


(deftest two-kinds-of-suggestion
  (is (= [["stock-item" "tee-m" "T-shirt / M · T-100"] ["stock-item" "tee-l" "T-shirt / L"] ["product" "tee" "T-shirt"]]
         (map (juxt :kind :id :label) (references/suggest "T-sh" facts))))
  (is (= {:stock-item-id "tee-m"           :product-id "tee" :product-name "T-shirt" :variant-label "M" :article "T-100"
          :barcodes      ["4600000000017"] :unit       "pcs"}
         (:fill (first (references/suggest "4600000000017" facts))))
      "a stock-item suggestion fills every known field; barcode search finds it"))


(deftest product-without-items-is-suggested
  (is (= [{:kind "product" :id "mug" :label "Mug" :fill {:product-id "mug" :product-name "Mug" :warehouse-id "main"}}]
         (references/suggest "mug" facts))))


(deftest archived-records-are-never-suggested
  (is (not-any? #(= "old" (:id %)) (references/suggest "old" facts)))
  (is (<= (count (references/suggest "" (assoc facts :products (repeat 30 {:product-id "p" :name "P" :status "active"})))) 20)))


(deftest exact-barcode-resolves
  (is (= {:stock-item-id "tee-m"} (references/resolve " 4600000000017 " facts))))


(deftest product-with-one-item-resolves
  (is (= {:stock-item-id "screw"} (references/resolve "screws" facts))))


(deftest several-items-need-a-choice
  (is (= {:choose ["tee-l" "tee-m"] :product-id "tee"} (references/resolve "T-shirt" facts)))
  (testing "a barcode shared by items of one product is a choice too"
    (is (= {:choose ["tee-l" "tee-m"] :product-id "tee"}
           (references/resolve "4600000000017" (update facts :barcodes conj {:stock-item-id "tee-l" :barcode "4600000000017"}))))))


(deftest anything-else-is-new-product-intent
  (is (= {:new-product "Cup"} (references/resolve " Cup " facts)))
  (is (= {:choose [] :product-id "mug"} (references/resolve "Mug" facts)) "a product without items asks for a new item")
  (is (nil? (references/resolve "  " facts))))
