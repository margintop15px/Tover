(ns tover.operations.core-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [tover.operations.core :as operations]
    [tover.shared.rdf :as rdf]
    [tover.stock.store :as store])
  (:import
    [java.nio.file Files]))


(def ^:private scope
  {:workspace-id #uuid "00000000-0000-0000-0000-000000000001"})


(deftest accepted-history-includes-openings-and-exact-signed-adjustments
  (let [path (.toFile (Files/createTempDirectory "tover-operations-" (make-array java.nio.file.attribute.FileAttribute 0)))
        repo (rdf/native-store path)
        row  {:id       "large"          :name "Large stock" :article "LARGE" :unit "pcs" :location "Shelf"
              :quantity 9007199254740993}]
    (try
      (is (= [] (operations/query (assoc scope :repo repo))))
      (store/declare-opening! repo scope row)
      (let [receipt    (store/accept-adjustment! repo scope {:row-id "large" :after 1 :reason "Recount"})
            before     (store/snapshot repo scope)
            result     (operations/query (assoc scope :repo repo))
            adjustment (first (filter #(= "Manual adjustment" (:type %)) result))
            opening    (first (filter #(= "Opening balance" (:type %)) result))]
        (is (= 2 (count result)))
        (is (= (str (:id receipt)) (:id adjustment)))
        (is (= ["9007199254740993" "1" "-9007199254740992" "Recount"]
               (mapv adjustment [:before :after :delta :reason])))
        (is (= ["0" "9007199254740993" "+9007199254740993"]
               (mapv opening [:before :after :delta])))
        (is (= (select-keys row [:name :article :unit :location])
               (select-keys opening [:name :article :unit :location])))
        (is (= "large" (:rowId opening) (:rowId adjustment)))
        (is (seq (:recordedAt opening)))
        (is (= result (operations/query (assoc scope :repo repo))))
        (is (= before (store/snapshot repo scope))))
      (finally
        (rdf/close! repo)
        (doseq [file (reverse (file-seq path))] (io/delete-file file))))))


(deftest operations-use-the-required-workspace-scope
  (let [path  (.toFile (Files/createTempDirectory "tover-operations-scope-" (make-array java.nio.file.attribute.FileAttribute 0)))
        repo  (rdf/native-store path)
        other {:workspace-id #uuid "00000000-0000-0000-0000-000000000003"}
        row   {:id "same" :name "First goods" :article "FIRST" :unit "pcs" :location "Shelf" :quantity 10}]
    (try
      (store/declare-opening! repo scope row)
      (store/declare-opening! repo other (assoc row :name "Other goods" :quantity 100))
      (store/accept-adjustment! repo scope {:row-id "same" :after 9 :reason "First count"})
      (is (= #{"First goods"} (set (map :name (operations/query (assoc scope :repo repo))))))
      (let [entries (operations/query (assoc other :repo repo))]
        (is (= 1 (count entries)))
        (is (= ["Other goods" "100"] ((juxt :name :after) (first entries)))))
      (is (thrown? clojure.lang.ExceptionInfo (operations/query {:repo repo})))
      (finally
        (rdf/close! repo)
        (doseq [file (reverse (file-seq path))] (io/delete-file file))))))
