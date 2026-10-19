(ns tover.rdf-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [tover.rdf :as rdf])
  (:import [java.nio.file Files]
           [org.eclipse.rdf4j.model.impl SimpleValueFactory]
           [org.eclipse.rdf4j.model.vocabulary XSD]))


(defn- with-repository
  {:malli/schema [:=> [:cat [:=> [:cat rdf/Repository :any] :any]] :any]}
  [f]
  (let [path (.toFile (Files/createTempDirectory "tover-rdf-test-" (make-array java.nio.file.attribute.FileAttribute 0)))
        repo (rdf/native-store path)]
    (try (f repo path)
         (finally
           (rdf/close! repo)
           (doseq [file (reverse (file-seq path))] (io/delete-file file))))))


(def ^:private graph (rdf/iri "urn:test:graph"))


(def ^:private predicate (rdf/iri "urn:test:value"))


(def ^:private values-query
  '{:prefixes {:e "<urn:test:>"}
    :select   [?subject ?value]
    :where    [[:graph :e/graph [[?subject :e/value ?value]]]]
    :order-by [?subject]})


(deftest typed-values-preserve-rdf-identity-and-numeric-precision
  (doseq [data ["hello" true false 0 Long/MIN_VALUE Long/MAX_VALUE
                9007199254740993N 9007199254740993123123N
                -9007199254740993123123N 1.234567890123456789M 1.25 (float 1.25)]]
    (testing (str data)
      (is (= data (rdf/value (rdf/literal data))))))
  (is (instance? Long (rdf/value (rdf/literal 9007199254740993N))))
  (is (instance? clojure.lang.BigInt (rdf/value (rdf/literal 9007199254740993123123N))))
  (let [identifier (rdf/iri "urn:test:thing")
        factory (SimpleValueFactory/getInstance)
        language-literal (.createLiteral factory "bonjour" "fr")
        date-literal (.createLiteral factory "2026-10-09" XSD/DATE)]
    (is (identical? identifier (rdf/value identifier)))
    (is (= "urn:test:thing" (rdf/value (rdf/literal "urn:test:thing"))))
    (is (identical? language-literal (rdf/value language-literal)))
    (is (identical? date-literal (rdf/value date-literal)))))


(deftest generic-named-graphs-select-bindings-and-persistence
  (with-repository
    (fn [repo path]
      (let [subject (rdf/iri "urn:test:one")
            identifier (rdf/iri "urn:test:thing")]
        (is (= :committed
               (rdf/with-transaction [conn {:repo repo :level :serializable}]
                 (rdf/add! conn graph subject predicate identifier)
                 (rdf/add! conn graph (rdf/iri "urn:test:two") predicate "urn:test:thing")
                 (rdf/add! conn nil subject predicate "default graph")
                 :committed)))
        (rdf/with-transaction [conn {:repo repo :level :snapshot}]
          (is (= identifier (rdf/value-at conn graph subject predicate)))
          (is (= "default graph" (rdf/value-at conn nil subject predicate)))
          (is (= [{:subject subject :value identifier}]
                 (rdf/select conn values-query {'?subject subject})))
          (is (= [{:subject (rdf/iri "urn:test:two") :value "urn:test:thing"}]
                 (rdf/select conn values-query {:value "urn:test:thing"})))
          (is (= 2 (count (rdf/select conn values-query)))))
        (rdf/close! repo)
        (let [reopened (rdf/native-store path)]
          (try
            (rdf/with-transaction [conn {:repo reopened :level :snapshot}]
              (is (= 2 (count (rdf/select conn values-query))))
              (is (= "default graph" (rdf/value-at conn nil subject predicate))))
            (finally (rdf/close! reopened))))))))


(deftest failed-transactions-rollback-and-release-the-connection
  (with-repository
    (fn [repo _]
      (let [subject (rdf/iri "urn:test:one")
            connection (volatile! nil)]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo #"rollback this write"
              (rdf/with-transaction [conn {:repo repo :level :serializable}]
                (vreset! connection conn)
                (rdf/add! conn graph subject predicate 12)
                (throw (ex-info "rollback this write" {:type :test})))))
        (is (not (.isOpen @connection)))
        (rdf/with-transaction [conn {:repo repo :level :snapshot}]
          (is (nil? (rdf/value-at conn graph subject predicate)))
          (is (= [] (rdf/select conn values-query))))
        (rdf/with-transaction [conn {:repo repo :level :serializable}]
          (rdf/add! conn graph subject predicate 13))
        (is (thrown? Exception
                     (rdf/with-transaction [conn {:repo repo :level :serializable}]
                       (rdf/remove! conn graph subject predicate nil)
                       (throw (ex-info "rollback removal" {})))))
        (rdf/with-transaction [conn {:repo repo :level :snapshot}]
          (is (= 13 (rdf/value-at conn graph subject predicate))))
        (rdf/with-transaction [conn {:repo repo :level :serializable}]
          (rdf/remove! conn graph nil predicate nil))
        (rdf/with-transaction [conn {:repo repo :level :snapshot}]
          (is (= [] (rdf/select conn values-query))))))))


(deftest transaction-options-are-evaluated-once
  (with-repository
    (fn [repo _]
      (let [evaluations (atom 0)
            options {:repo repo :level :serializable}
            connection (volatile! nil)]
        (is (= :committed
               (rdf/with-transaction [conn (do (swap! evaluations inc) options)]
                 (vreset! connection conn)
                 :committed)))
        (is (= 1 @evaluations))
        (is (not (.isOpen @connection)))))))


(deftest invalid-generic-input-is-rejected
  (is (thrown? clojure.lang.ExceptionInfo (rdf/iri "relative/path")))
  (is (thrown? clojure.lang.ExceptionInfo (rdf/literal nil)))
  (is (thrown? clojure.lang.ExceptionInfo (rdf/literal {:value 1})))
  (with-repository
    (fn [repo _]
      (doseq [options [{:repo repo :level :unsupported}
                       {:repo repo}
                       {:repo nil :level :snapshot}]]
        (is (thrown? clojure.lang.ExceptionInfo
                     (rdf/with-transaction [conn options] nil))))
      (rdf/with-transaction [conn {:repo repo :level :snapshot}]
        (is (thrown? clojure.lang.ExceptionInfo
                     (rdf/select conn values-query {:subject nil})))))))
