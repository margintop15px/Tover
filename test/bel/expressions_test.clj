(ns bel.expressions-test
  (:require
    [bel.expressions :as expressions :refer [->expr]]
    [clojure.test :refer [deftest is testing]]))


(deftest unquoted-control-forms-use-clojure-semantics
  ;; Compile during test execution so a regression cannot prevent test loading.
  (testing "when inside ~ executes on the server"
    (is (= "$query = \"yes\""
           (eval '(bel.expressions/->expr
                    (set! $query ~(when true "yes")))))))
  (testing "if inside ~ retains its chosen Clojure branch"
    (is (= "$query = \"no\""
           (eval '(bel.expressions/->expr
                    (set! $query ~(if false "yes" "no"))))))))


(deftest browser-control-forms-still-compile-as-datastar-expressions
  (let [expression (->expr (when $ready (set! $query "yes")))]
    (is (re-find #"\(\$ready\) \?" expression))
    (is (re-find #"\$query = \"yes\"" expression))
    (is (not (re-find #"squint_core" expression)))))


(deftest runtime-values-still-escape-as-javascript-literals
  (let [value "a\"b\n/ok"]
    (is (= "$query = \"a\\\"b\\n/ok\""
           (->expr (set! $query ~value))))))


(deftest preprocessing-leaves-unquoted-clojure-untouched
  (let [forms '((set! $query
                      ~[(when true "yes")
                        (not= 1 2)
                        '("`literal`")
                        '($query "_row")
                        #uuid "00000000-0000-0000-0000-000000000001"]))]
    (is (= forms (expressions/pre-process forms)))))


(deftest dynamic-signal-names-preserve-host-control-forms
  (is (= "$query_row = \"yes\""
         (eval '(bel.expressions/->expr
                  (set! ($query ~(when true "_row")) "yes"))))))
