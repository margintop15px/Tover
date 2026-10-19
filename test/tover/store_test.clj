(ns tover.store-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [tover.rdf :as rdf]
            [tover.store :as store])
  (:import [java.nio.file Files]))


(def ^:private opening-rows
  [{:id "notebook" :name "Notebook A5" :article "NB-A5" :unit "pcs" :location "Main shelf" :quantity 24}
   {:id "tea" :name "Green tea, 100 g" :article "TEA-100" :unit "packs" :location "Main shelf" :quantity 18}
   {:id "cable" :name "USB-C cable, 1 m" :article "USB-C-1" :unit "pcs" :location "Storage box" :quantity 12}])


(defn- error-type
  {:malli/schema [:=> [:cat [:=> [:cat] :any]] [:maybe :keyword]]}
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo error
         (let [type (:type (ex-data error))]
           (if (= :malli.core/invalid-input type) :validation type)))))


(defn- with-store
  {:malli/schema [:=> [:cat [:=> [:cat rdf/Repository :any] :any]] :any]}
  [f]
  (let [path (.toFile (Files/createTempDirectory "tover-store-test-" (make-array java.nio.file.attribute.FileAttribute 0)))
        repo (rdf/native-store path)]
    (try (f repo path)
         (finally
           (rdf/close! repo)
           (doseq [file (reverse (file-seq path))] (io/delete-file file))))))


(defn- declare-fixtures!
  {:malli/schema [:=> [:cat rdf/Repository] :nil]}
  [repo]
  (doseq [row opening-rows] (store/declare-opening! repo row)))


(deftest fresh-store-is-empty-and-openings-are-explicit-and-immutable
  (with-store
    (fn [repo _]
      (is (= {:rows [] :history []} (store/snapshot repo)))
      (is (= {:rows [] :evidence {}} (store/stock-grid repo)))
      (let [row (assoc (first opening-rows) :id "generic row/α +1")]
        (is (= row (store/declare-opening! repo row)))
        (is (= row (store/declare-opening! repo row)))
        (is (= {:rows [row] :history []} (store/snapshot repo)))
        (doseq [changed [(update row :quantity inc) (assoc row :name "Different name")]]
          (is (= :opening-conflict (error-type #(store/declare-opening! repo changed)))))
        (store/accept-adjustment! repo {:row-id (:id row) :after 25 :reason "Counted again"})
        (let [saved (store/snapshot repo)]
          (is (= row (store/declare-opening! repo row)))
          (is (= saved (store/snapshot repo)))
          (is (= 25 (:quantity (first (:rows saved))))))))))


(deftest adjustments-record-current-before-and-persist
  (with-store
    (fn [repo path]
      (declare-fixtures! repo)
      (let [first-receipt (store/accept-adjustment! repo {:row-id "notebook" :after 25 :reason "Counted one extra item"})
            second-receipt (store/accept-adjustment! repo {:row-id "notebook" :after 0 :reason "Corrected count to zero"})
            saved (store/snapshot repo)
            entry (first (:history saved))]
        (is (uuid? (:id first-receipt)))
        (is (= {:row-id "notebook" :before 24 :after 25 :delta 1} (dissoc first-receipt :id)))
        (is (= {:row-id "notebook" :before 25 :after 0 :delta -25} (dissoc second-receipt :id)))
        (is (= 0 (get-in (store/stock-row repo "notebook") [:row :quantity])))
        (is (= [(:id second-receipt) (:id first-receipt)] (mapv :id (:history saved))))
        (is (= "Corrected count to zero" (:reason entry)))
        (is (= (:recorded-at entry) (:effective-at entry)))
        (is (not (contains? entry :actor)))
        (rdf/close! repo)
        (let [reopened (rdf/native-store path)]
          (try
            (is (= saved (store/snapshot reopened)))
            (is (= (:id second-receipt) (get-in (store/stock-grid reopened) [:evidence "notebook" :id])))
            (finally (rdf/close! reopened))))))))


(deftest invalid-edits-do-not-change-stock
  (with-store
    (fn [repo _]
      (declare-fixtures! repo)
      (let [initial (store/snapshot repo)
            edit {:row-id "notebook" :after 25 :reason "Counted one extra item"}]
        (doseq [[field value] [[:after -1] [:after 1.5] [:after nil]
                               [:after (inc (bigint Long/MAX_VALUE))]
                               [:row-id ""] [:reason "  "] [:reason nil] [:actor ""]]]
          (testing (str field "=" value)
            (is (= :validation (error-type #(store/accept-adjustment! repo (assoc edit field value)))))
            (is (= initial (store/snapshot repo)))))
        (is (= :validation (error-type #(store/accept-adjustment! repo (dissoc edit :reason)))))
        (doseq [row-id ["missing" (apply str (repeat 201 "x"))]]
          (is (= :unknown-row (error-type #(store/accept-adjustment! repo (assoc edit :row-id row-id))))))
        (is (= :validation (error-type #(store/accept-adjustment! repo (assoc edit :after 24)))))
        (is (= initial (store/snapshot repo)))))))


(deftest failed-history-write-rolls-back
  (with-store
    (fn [repo _]
      (declare-fixtures! repo)
      (let [initial (store/snapshot repo)
            add! rdf/add!
            writes (atom 0)]
        (with-redefs [rdf/add! (fn [& args]
                                 (when (= 3 (swap! writes inc))
                                   (throw (ex-info "History write failed" {})))
                                 (apply add! args))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"History write failed"
                                (store/accept-adjustment! repo {:row-id "notebook" :after 25 :reason "Physical count"}))))
        (is (= initial (store/snapshot repo)))))))


(deftest edits-retain-reason-and-optional-actor
  (with-store
    (fn [repo _]
      (declare-fixtures! repo)
      (let [edit {:row-id "notebook"                   :after 25
                  :reason (apply str (repeat 501 "x"))
                  :actor  (apply str (repeat 201 "x"))}
            accepted (store/accept-adjustment! repo edit)
            entry (first (:history (store/snapshot repo)))]
        (is (= accepted (select-keys entry [:id :row-id :before :after :delta])))
        (is (= (:reason edit) (:reason entry)))
        (is (= (:actor edit) (:actor entry)))))))


(deftest proposals-are-plain-data
  (let [row (first opening-rows)]
    (is (= {:row-id "notebook" :row row :before 24 :after 26}
           (store/proposal row 26)))
    (is (= :validation (error-type #(store/proposal row 24))))
    (is (= :validation (error-type #(store/proposal row -1))))))


(deftest derived-quantities-retain-integer-precision
  (with-store
    (fn [repo _]
      (store/declare-opening! repo (assoc (first opening-rows) :quantity 9007199254740993))
      (doseq [quantity [Long/MAX_VALUE 0 Long/MAX_VALUE 0]]
        (let [accepted (store/accept-adjustment! repo {:row-id "notebook" :after quantity :reason "Physical count"})]
          (is (= quantity (:after accepted)))
          (is (= quantity (:quantity (first (:rows (store/snapshot repo))))))
          (is (= quantity (get-in (store/stock-row repo "notebook") [:row :quantity])))
          (is (= quantity (:quantity (first (:rows (store/stock-grid repo)))))))))))


(deftest grid-and-history-return-the-current-row-and-latest-evidence
  (with-store
    (fn [repo _]
      (declare-fixtures! repo)
      (let [first-receipt (store/accept-adjustment! repo {:row-id "tea" :after 20 :reason "Counted tea"})
            other-receipt (store/accept-adjustment! repo {:row-id "notebook" :after 22 :reason "Counted notebooks"})
            latest (store/accept-adjustment! repo {:row-id "tea" :after 21 :reason "Counted again"})
            tea (assoc (second opening-rows) :quantity 21)
            grid (store/stock-grid repo)
            history (store/row-history repo "tea")]
        (is (= (:rows (store/snapshot repo)) (:rows grid)))
        (is (= {:row tea} (store/stock-row repo "tea")))
        (is (= {:row nil} (store/stock-row repo "missing")))
        (is (= {:row nil :history []} (store/row-history repo "missing")))
        (is (= {"tea" (:id latest) "notebook" (:id other-receipt)} (update-vals (:evidence grid) :id)))
        (is (= [(:id latest) (:id first-receipt)] (mapv :id (:history history))))
        (is (= tea (:row history)))))))


(deftest old-extra-command-and-revision-triples-are-ignored
  (with-store
    (fn [repo _]
      (declare-fixtures! repo)
      (let [receipt (store/accept-adjustment! repo {:row-id "notebook" :after 25 :reason "Counted again"})
            saved (store/snapshot repo)
            term (fn [suffix] (rdf/iri (str "urn:tover:stock-v0:" suffix)))
            effect (term (str "edit:" (:id receipt)))]
        (rdf/with-transaction [conn {:repo repo :level :serializable}]
          (rdf/add! conn (term "commands") (term "workspace") (term "revision") 1)
          (rdf/add! conn (term "history") effect (term "revision") 1)
          (rdf/add! conn (term "history") effect (term "command") (term "command:old")))
        (is (= saved (store/snapshot repo)))
        (is (= (:id receipt) (get-in (store/stock-grid repo) [:evidence "notebook" :id])))))))
