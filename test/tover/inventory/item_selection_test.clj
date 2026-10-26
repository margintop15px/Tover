(ns tover.inventory.item-selection-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [tover.inventory.item-selection :as selection]))


(def snapshot
  {:items {"known" {:name "Notebook" :article "NB-1" :barcode "123" :unit "pcs"}
           "plain" {:name "Plain" :unit "box"}}
   :rows  [{:item-id "known" :quantity 3 :location "Main"}
           {:item-id "known" :quantity 0 :location "Empty"}
           {:item-id "known" :quantity -1 :location "Unavailable"}]})


(deftest known-item-prefill-preserves-quantity-and-explicit-location
  (let [entry  {:kind :operation :type :sale :lines [{:quantity 7 :location ""}]}
        filled (selection/select-item snapshot entry [:lines 0] "known")]
    (is (= {:item-id "known" :name "Notebook" :article "NB-1" :barcode "123" :unit "pcs" :quantity 7 :location "Main"}
           (get-in filled [:lines 0])))
    (is (= "Entered" (get-in (selection/select-item snapshot (assoc-in entry [:lines 0 :location] "Entered") [:lines 0] "known") [:lines 0 :location])))))


(deftest inference-uses-distinct-positive-accepted-locations-only
  (let [entry {:type :sale :lines [{:quantity "" :location ""}]}
        infer (fn [rows] (get-in (selection/select-item (assoc snapshot :rows rows) entry [:lines 0] "known") [:lines 0 :location]))]
    (is (= "" (infer [])))
    (is (= "Main" (infer [{:item-id "known" :quantity 2 :location "Main"} {:item-id "known" :quantity 3 :location "Main"}])))
    (is (= "" (infer [{:item-id "known" :quantity 2 :location "Main"} {:item-id "known" :quantity 3 :location "Other"}])))
    (is (= "" (infer [{:item-id "known" :quantity 0 :location "Main"} {:item-id "plain" :quantity 3 :location "Other"}])))))


(deftest production-revisions-and-arrivals-use-their-own-line-path
  (doseq [path [[:inputs 0] [:outputs 1] [:receipts 0 :lines 0] [:replacement :outputs 0]]]
    (testing (str path)
      (let [entry    (assoc-in {:kind :operation :type :production :replacement {:type :production}} path {:quantity 4})
            selected (selection/select-item snapshot entry path "known")]
        (is (= "known" (get-in selected (conj path :item-id))))
        (is (= 4 (get-in selected (conj path :quantity))))
        (is (= "Main" (get-in selected (conj path :location))))))))


(deftest transfer-location-choice-stays-explicit-and-absent-metadata-is-cleared
  (let [transfer {:type :transfer :from-location "" :to-location "" :lines [{:quantity 3 :location ""}]}
        selected (selection/select-item snapshot transfer [:lines 0] "known")
        revised  (selection/select-item snapshot {:kind :revision :replacement transfer} [:replacement :lines 0] "known")
        plain    (selection/select-item snapshot {:lines [{:article "OLD" :barcode "OLD" :quantity 3 :location "Chosen"}]} [:lines 0] "plain")]
    (is (= transfer (-> selected (update-in [:lines 0] #(select-keys % [:quantity :location])))))
    (is (= "" (get-in revised [:replacement :lines 0 :location])))
    (is (= "" (get-in plain [:lines 0 :article])))
    (is (= "" (get-in plain [:lines 0 :barcode])))
    (is (= "Chosen" (get-in plain [:lines 0 :location])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Choose an available item"
                          (selection/select-item snapshot transfer [:lines 0] "foreign")))))
