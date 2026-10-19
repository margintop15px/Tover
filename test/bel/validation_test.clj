(ns bel.validation-test
  (:require
    [bel.validation :as validation]
    [clojure.test :refer [deftest is]])
  (:import
    [clojure.lang ExceptionInfo]))


(deftest valid-data-is-returned-unchanged
  (let [data {:quantity 42}]
    (is (identical? data (validation/check! [:map [:quantity :int]] data)))))


(deftest invalid-value-reports-structured-validation-error
  (let [data {:quantity "oops"}
        exception (try (validation/check! [:map [:quantity :int]] data)
                       (catch ExceptionInfo exception exception))]
    (is (instance? ExceptionInfo exception))
    (is (= "quantity: should be an integer" (ex-message exception)))
    (is (= :validation (:type (ex-data exception))))
    (is (= {:quantity ["should be an integer"]} (:errors (ex-data exception))))
    (is (= data (get-in (ex-data exception) [:explanation :value])))))


(deftest invalid-numeric-key-reports-structured-validation-error
  (let [data {1 "x"}
        exception (try (validation/check! [:map-of :keyword :any] data)
                       (catch ExceptionInfo exception exception))]
    (is (instance? ExceptionInfo exception))
    (is (= "1: should be a keyword" (ex-message exception)))
    (is (= :validation (:type (ex-data exception))))
    (is (= {1 ["should be a keyword"]} (:errors (ex-data exception))))
    (is (= data (get-in (ex-data exception) [:explanation :value])))))
