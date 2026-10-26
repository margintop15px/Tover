(ns tover.inventory.filters-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [tover.inventory.filters :as filters]))


(defn condition
  {:malli/schema [:=> [:cat :string :string :string] :map]}
  [id operator value]
  {:id id :type operator :value value :relation "and"})


(deftest canonical-conditions-are-validated-without-losing-order-or-repeats
  (let [conditions [{:id 12 :type "begins" :value "a" :relation "or"}
                    {:id "13" :type "begins" :value "b" :relation "and"}]]
    (is (= {"name" [(assoc (first conditions) :id "12") (second conditions)]}
           (filters/check! :stock {:name conditions :unit []})))
    (is (= {} (filters/check! :stock nil)))
    (doseq [invalid [{"unknown" conditions}
                     {"name" [{:id "x" :type "javascript" :value "a"}]}
                     {"name" [{:id "x" :type "eq" :relation "xor"}]}
                     {"name" [(condition "same" "eq" "a") (condition "same" "eq" "b")]}
                     {"name" [(condition "long" "contains" (apply str (repeat 2001 "x")))]}]]
      (is (thrown? clojure.lang.ExceptionInfo (filters/check! :stock invalid))))))


(deftest all-string-operators-are-case-insensitive-literal-patterns
  (let [row {:name "Notebook [A].*" :quantity 12 :location ""}]
    (doseq [[operator value expected] [["begins" "note" true] ["begins" "book" false]
                                       ["contains" "[A].*" true] ["contains" "^Note" false]
                                       ["notContains" "xyz" true] ["eq" "NOTEBOOK [a].*" true]
                                       ["notEq" "Notebook" true]]]
      (is (= expected (filters/matches? :stock row {"name" [(condition "1" operator value)]} {}))))
    (is (filters/matches? :stock row {"location" [{:id "blank" :type "empty"}]} {}))
    (is (filters/matches? :stock row {"quantity" [(condition "count" "eq" "12")]} {}))
    (is (not (filters/matches? :stock row {"location" [{:id "present" :type "notEmpty"}]} {}))))
  (is (filters/matches? :stock {} {"article" [{:id "missing" :type "empty"}]} {})))


(deftest ordered-and-groups-join-with-or-and-columns-join-with-and
  (let [patterns [(condition "a" "begins" "a")
                  (assoc (condition "b" "contains" "b") :relation "or")
                  (condition "c" "eq" "c")]
        model    {"name" patterns "location" [(condition "where" "eq" "Main")]}]
    (is (filters/matches? :stock {:name "Abacus" :location "main"} model {}))
    (is (filters/matches? :stock {:name "C" :location "main"} model {}))
    (is (not (filters/matches? :stock {:name "Another" :location "main"} model {})))
    (is (not (filters/matches? :stock {:name "Abacus" :location "Elsewhere"} model {}))))
  (testing "OR alternatives may themselves contain multiple AND conditions"
    (let [patterns [(assoc (condition "a" "eq" "a") :relation "or")
                    (condition "b" "begins" "b")
                    (condition "c" "contains" "c")]]
      (is (filters/matches? :stock {:name "a"} {"name" patterns} {}))
      (is (filters/matches? :stock {:name "bc"} {"name" patterns} {}))
      (is (not (filters/matches? :stock {:name "b"} {"name" patterns} {}))))))


(deftest operations-goods-filter-traverses-own-operation-lines-only
  (let [operation {:type   :production :inputs [{:item-id "paper"}] :outputs [{:name "Notebook"}]
                   :detail {:name "Unsupported detail attribution"}}
        catalog   {"paper" {:name "Paper"}}]
    (is (filters/matches? :operations operation {"name" [(condition "in" "begins" "pa")]} catalog))
    (is (filters/matches? :operations operation {"name" [(condition "out" "begins" "note")]} catalog))
    (is (not (filters/matches? :operations operation {"name" [(condition "detail" "begins" "Unsupported")]} catalog)))
    (is (not (filters/matches? :operations operation {"name" [(condition "negative" "notContains" "paper")]} catalog)))
    (is (filters/matches? :operations operation {"type" [(condition "kind" "eq" "Production")]} catalog))))


(deftest smart-search-defaults-to-goods-and-preserves-complex-filters
  (let [initial (filters/smart-add :stock {} nil "Note" false)
        simple  (filters/smart-add :stock initial "name" "Paper" false)
        complex {"name"     [(condition "eq" "eq" "Notebook") (condition "not" "notContains" "Old")]
                 "location" [(condition "where" "begins" "Main")]}
        error   (try (filters/smart-add :stock complex "name" "Paper" false)
                     (catch clojure.lang.ExceptionInfo error (ex-data error)))]
    (is (= "Note" (get-in initial ["name" 0 :value])))
    (is (= "begins" (get-in initial ["name" 0 :type])))
    (is (= (get-in initial ["name" 0 :id]) (get-in simple ["name" 0 :id])))
    (is (= :filter-replacement (:kind error)))
    (is (= "name" (:column error)))
    (is (= (get complex "name") (:current error)))
    (is (= (get complex "location") (get (filters/smart-add :stock complex "name" "Paper" true) "location")))
    (is (= 1 (count (get (filters/smart-add :stock complex "name" "Paper" true) "name"))))
    (is (= 7 (count (filters/columns :operations))))))
