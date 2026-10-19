(ns tover.input-test
  (:require [clojure.test :refer [deftest is testing]]
            [tover.input :as input]))


(def rows
  [{:id       "mug-blue"       :article  "MUG-01" :name "Blue mug" :unit "pcs"
    :location "Main warehouse" :quantity 20}
   {:id       "mug-red" :article  "MUG-02" :name "Red mug" :unit "pcs"
    :location "Shop"    :quantity 12}
   {:id       "bowl"           :article  "BOWL-01" :name "Bowl" :unit "pcs"
    :location "Main warehouse" :quantity 4}])


(deftest ^{:malli/schema [:=> [:cat] :any]} search-stock
  (is (= {:type :search :query "" :rows rows} (input/interpret rows "  ")))
  (is (= rows (:rows (input/interpret rows nil))))
  (is (= [(first rows) (last rows)] (:rows (input/interpret rows "MAIN"))))
  (is (= [(second rows)] (:rows (input/interpret rows "mug-02"))))
  (is (= [] (:rows (input/interpret rows "missing")))))


(deftest ^{:malli/schema [:=> [:cat] :any]} exact-command-takes-precedence
  (doseq [text ["set MUG-01 to 15" "SET Blue mug TO 15" "set mug-blue to 15"]]
    (is (= {:type :adjustment :row (first rows) :after 15}
           (input/interpret rows text))))
  (is (= :adjustment (:type (input/interpret
                              (conj rows (assoc (first rows) :id "other" :article "X" :name "Blue mug large"))
                              "set Blue mug to 0")))))


(deftest ^{:malli/schema [:=> [:cat] :any]} ambiguous-command-requires-a-choice
  (is (= {:type :choose :matches (vec (take 2 rows)) :after 7}
         (input/interpret rows "set mug to 7")))
  (is (= :choose (:type (input/interpret
                          (conj rows (assoc (first rows) :id "duplicate"))
                          "set MUG-01 to 7")))))


(deftest ^{:malli/schema [:=> [:cat] :any]} invalid-command-never-becomes-a-search
  (doseq [text ["set" "set mug" "set to 3" "set   to 3" "set mug to" "set mug to 1 2"
                "set mug to -1" "set mug to 1.5" "set mug to 9223372036854775808"
                "set missing to 3"]]
    (testing text
      (is (= :error (:type (input/interpret rows text)))))))


(deftest ^{:malli/schema [:=> [:cat] :any]} quantity-boundaries
  (doseq [[quantity expected] [["0" 0] ["0002" 2] ["9223372036854775807" Long/MAX_VALUE]]]
    (is (= expected (input/quantity quantity)))
    (is (= expected (:after (input/interpret rows (str "set bowl to " quantity)))))))


(deftest ^{:malli/schema [:=> [:cat] :any]} quantity-validation
  (doseq [text [nil 15 "" "-1" "+1" "1.5" "1e3" "1a" "a1" "1\n" "1 2" "9223372036854775808"]]
    (testing (pr-str text)
      (is (= :validation
             (try
               (input/quantity text)
               nil
               (catch clojure.lang.ExceptionInfo error
                 (:type (ex-data error)))))))))


(deftest ^{:malli/schema [:=> [:cat] :any]} input-boundaries
  (is (= :error (:type (input/interpret rows (apply str (repeat 201 "a"))))))
  (is (= :search (:type (input/interpret rows (apply str (repeat 200 "a"))))))
  (is (= :error (:type (input/interpret rows 15)))))
