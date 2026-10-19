(ns tover.operations-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [tover.operations :as operations]
    [tover.rdf :as rdf]
    [tover.store :as store])
  (:import
    [java.nio.file Files]))


(deftest accepted-history-includes-openings-and-exact-signed-adjustments
  (let [path (.toFile (Files/createTempDirectory "tover-operations-" (make-array java.nio.file.attribute.FileAttribute 0)))
        repo (rdf/native-store path)
        row {:id       "large"          :name "Large stock" :article "LARGE" :unit "pcs" :location "Shelf"
             :quantity 9007199254740993}]
    (try
      (is (= [] (operations/query {:repo repo})))
      (store/declare-opening! repo row)
      (let [receipt (store/accept-adjustment! repo {:row-id "large" :after 1 :reason "Recount"})
            before (store/snapshot repo)
            result (operations/query {:repo repo})
            adjustment (first (filter #(= "Manual adjustment" (:type %)) result))
            opening (first (filter #(= "Opening balance" (:type %)) result))]
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
        (is (= result (operations/query {:repo repo})))
        (is (= before (store/snapshot repo))))
      (finally
        (rdf/close! repo)
        (doseq [file (reverse (file-seq path))] (io/delete-file file))))))
