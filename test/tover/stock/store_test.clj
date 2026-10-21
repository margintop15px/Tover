(ns tover.stock.store-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [tover.shared.rdf :as rdf]
            [tover.stock.store :as store])
  (:import [java.nio.file Files]))


(def ^:private scope
  {:workspace-id #uuid "00000000-0000-0000-0000-000000000001"})


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
  (doseq [row opening-rows] (store/declare-opening! repo scope row)))


(deftest fresh-store-is-empty-and-openings-are-explicit-and-immutable
  (with-store
    (fn [repo _]
      (is (= {:rows [] :history []} (store/snapshot repo scope)))
      (is (= {:rows [] :evidence {}} (store/stock-grid repo scope)))
      (let [row (assoc (first opening-rows) :id "generic row/α +1")]
        (is (= row (store/declare-opening! repo scope row)))
        (is (= row (store/declare-opening! repo scope row)))
        (is (= {:rows [row] :history []} (store/snapshot repo scope)))
        (doseq [changed [(update row :quantity inc) (assoc row :name "Different name")]]
          (is (= :opening-conflict (error-type #(store/declare-opening! repo scope changed)))))
        (store/accept-adjustment! repo scope {:row-id (:id row) :after 25 :reason "Counted again"})
        (let [saved (store/snapshot repo scope)]
          (is (= row (store/declare-opening! repo scope row)))
          (is (= saved (store/snapshot repo scope)))
          (is (= 25 (:quantity (first (:rows saved))))))))))


(deftest adjustments-record-current-before-and-persist
  (with-store
    (fn [repo path]
      (declare-fixtures! repo)
      (let [first-receipt  (store/accept-adjustment! repo scope {:row-id "notebook" :after 25 :reason "Counted one extra item"})
            second-receipt (store/accept-adjustment! repo scope {:row-id "notebook" :after 0 :reason "Corrected count to zero"})
            saved          (store/snapshot repo scope)
            entry          (first (:history saved))]
        (is (uuid? (:id first-receipt)))
        (is (= {:row-id "notebook" :before 24 :after 25 :delta 1} (dissoc first-receipt :id)))
        (is (= {:row-id "notebook" :before 25 :after 0 :delta -25} (dissoc second-receipt :id)))
        (is (= 0 (get-in (store/stock-row repo scope "notebook") [:row :quantity])))
        (is (= [(:id second-receipt) (:id first-receipt)] (mapv :id (:history saved))))
        (is (= "Corrected count to zero" (:reason entry)))
        (is (= (:recorded-at entry) (:effective-at entry)))
        (is (not (contains? entry :actor)))
        (rdf/close! repo)
        (let [reopened (rdf/native-store path)]
          (try
            (is (= saved (store/snapshot reopened scope)))
            (is (= (:id second-receipt) (get-in (store/stock-grid reopened scope) [:evidence "notebook" :id])))
            (finally (rdf/close! reopened))))))))


(deftest invalid-edits-do-not-change-stock
  (with-store
    (fn [repo _]
      (declare-fixtures! repo)
      (let [initial (store/snapshot repo scope)
            edit    {:row-id "notebook" :after 25 :reason "Counted one extra item"}]
        (doseq [[field value] [[:after -1] [:after 1.5] [:after nil]
                               [:after (inc (bigint Long/MAX_VALUE))]
                               [:row-id ""] [:reason "  "] [:reason nil] [:actor ""]]]
          (testing (str field "=" value)
            (is (= :validation (error-type #(store/accept-adjustment! repo scope (assoc edit field value)))))
            (is (= initial (store/snapshot repo scope)))))
        (is (= :validation (error-type #(store/accept-adjustment! repo scope (dissoc edit :reason)))))
        (doseq [row-id ["missing" (apply str (repeat 201 "x"))]]
          (is (= :unknown-row (error-type #(store/accept-adjustment! repo scope (assoc edit :row-id row-id))))))
        (is (= :validation (error-type #(store/accept-adjustment! repo scope (assoc edit :after 24)))))
        (is (= initial (store/snapshot repo scope)))))))


(deftest failed-history-write-rolls-back
  (with-store
    (fn [repo _]
      (declare-fixtures! repo)
      (let [initial (store/snapshot repo scope)
            add!    rdf/add!
            writes  (atom 0)]
        (with-redefs [rdf/add! (fn [& args]
                                 (when (= 3 (swap! writes inc))
                                   (throw (ex-info "History write failed" {})))
                                 (apply add! args))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"History write failed"
                                (store/accept-adjustment! repo scope {:row-id "notebook" :after 25 :reason "Physical count"}))))
        (is (= initial (store/snapshot repo scope)))))))


(deftest edits-retain-reason-and-optional-actor
  (with-store
    (fn [repo _]
      (declare-fixtures! repo)
      (let [edit     {:row-id "notebook"                   :after 25
                      :reason (apply str (repeat 501 "x"))
                      :actor  (apply str (repeat 201 "x"))}
            accepted (store/accept-adjustment! repo scope edit)
            entry    (first (:history (store/snapshot repo scope)))]
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
      (store/declare-opening! repo scope (assoc (first opening-rows) :quantity 9007199254740993))
      (doseq [quantity [Long/MAX_VALUE 0 Long/MAX_VALUE 0]]
        (let [accepted (store/accept-adjustment! repo scope {:row-id "notebook" :after quantity :reason "Physical count"})]
          (is (= quantity (:after accepted)))
          (is (= quantity (:quantity (first (:rows (store/snapshot repo scope))))))
          (is (= quantity (get-in (store/stock-row repo scope "notebook") [:row :quantity])))
          (is (= quantity (:quantity (first (:rows (store/stock-grid repo scope)))))))))))


(deftest grid-and-history-return-the-current-row-and-latest-evidence
  (with-store
    (fn [repo _]
      (declare-fixtures! repo)
      (let [first-receipt (store/accept-adjustment! repo scope {:row-id "tea" :after 20 :reason "Counted tea"})
            other-receipt (store/accept-adjustment! repo scope {:row-id "notebook" :after 22 :reason "Counted notebooks"})
            latest        (store/accept-adjustment! repo scope {:row-id "tea" :after 21 :reason "Counted again"})
            tea           (assoc (second opening-rows) :quantity 21)
            grid          (store/stock-grid repo scope)
            history       (store/row-history repo scope "tea")]
        (is (= (:rows (store/snapshot repo scope)) (:rows grid)))
        (is (= {:row tea} (store/stock-row repo scope "tea")))
        (is (= {:row nil} (store/stock-row repo scope "missing")))
        (is (= {:row nil :history []} (store/row-history repo scope "missing")))
        (is (= {"tea" (:id latest) "notebook" (:id other-receipt)} (update-vals (:evidence grid) :id)))
        (is (= [(:id latest) (:id first-receipt)] (mapv :id (:history history))))
        (is (= tea (:row history)))))))


(deftest old-extra-command-and-revision-triples-are-ignored
  (with-store
    (fn [repo _]
      (declare-fixtures! repo)
      (let [receipt (store/accept-adjustment! repo scope {:row-id "notebook" :after 25 :reason "Counted again"})
            saved   (store/snapshot repo scope)
            term    (fn [suffix] (rdf/iri (str "urn:tover:stock-v0:" suffix)))
            effect  (rdf/iri (str "urn:tover:workspace:" (:workspace-id scope) ":stock-v0:edit:" (:id receipt)))
            history (get (store/scope-bindings scope) '?history)]
        (rdf/with-transaction [conn {:repo repo :level :serializable}]
          (rdf/add! conn (term "commands") (term "workspace") (term "revision") 1)
          (rdf/add! conn history effect (term "revision") 1)
          (rdf/add! conn history effect (term "command") (term "command:old")))
        (is (= saved (store/snapshot repo scope)))
        (is (= (:id receipt) (get-in (store/stock-grid repo scope) [:evidence "notebook" :id])))))))


(deftest workspaces-isolate-identical-row-identifiers-and-evidence
  (with-store
    (fn [repo _]
      (let [other     {:workspace-id #uuid "00000000-0000-0000-0000-000000000003"}
            row       (first opening-rows)
            other-row (assoc row :name "Other workspace goods" :quantity 100)
            actor     "00000000-0000-0000-0000-000000000002"]
        (store/declare-opening! repo scope row)
        (store/declare-opening! repo other other-row)
        (let [receipt (store/accept-adjustment! repo scope {:row-id (:id row) :after 25 :reason "Count" :actor actor})]
          (is (= 25 (get-in (store/stock-row repo scope (:id row)) [:row :quantity])))
          (is (= {:row other-row} (store/stock-row repo other (:id row))))
          (is (= [other-row] (:rows (store/snapshot repo other))))
          (is (= {:rows [other-row] :evidence {}} (store/stock-grid repo other)))
          (is (= {:row other-row :history []} (store/row-history repo other (:id row))))
          (is (= [(:id receipt)] (mapv :id (:history (store/row-history repo scope (:id row))))))
          (is (= actor (get-in (store/stock-grid repo scope) [:evidence (:id row) :actor])))
          (store/accept-adjustment! repo other {:row-id (:id row) :after 99 :reason "Other count"})
          (is (= 25 (get-in (store/stock-row repo scope (:id row)) [:row :quantity])))
          (is (= 99 (get-in (store/stock-row repo other (:id row)) [:row :quantity]))))))))


(deftest anonymous-legacy-graphs-are-never-assigned-or-used-as-a-fallback
  (with-store
    (fn [repo _]
      (let [term    #(rdf/iri (str "urn:tover:stock-v0:" %))
            row     (first opening-rows)
            subject (term "row:notebook")]
        (rdf/with-transaction [conn {:repo repo :level :serializable}]
          (doseq [[key value] (dissoc row :quantity)]
            (rdf/add! conn (term "catalog") subject (term (name key)) value))
          (doseq [[key value] {:row subject :delta 24 :decision "accepted"}]
            (rdf/add! conn (term "history") (term "opening:notebook") (term (name key)) value)))
        (is (= {:rows [] :history []} (store/snapshot repo scope)))
        (is (= {:row nil} (store/stock-row repo scope "notebook")))
        (is (= {:rows [] :evidence {}} (store/stock-grid repo scope)))
        (is (= {:row nil :history []} (store/row-history repo scope "notebook")))
        (is (= :unknown-row (error-type #(store/accept-adjustment! repo scope {:row-id "notebook" :after 25 :reason "Count"}))))
        (rdf/with-transaction [conn {:repo repo :level :snapshot}]
          (is (= "Notebook A5" (rdf/value-at conn (term "catalog") subject (term "name")))))))))


(deftest inventory-access-requires-explicit-valid-workspace-scope
  (with-store
    (fn [repo _]
      (doseq [invalid [nil {} {:workspace-id "spoofed"}]
              access  [#(store/snapshot repo invalid)
                       #(store/stock-grid repo invalid)
                       #(store/stock-row repo invalid "notebook")
                       #(store/row-history repo invalid "notebook")
                       #(store/declare-opening! repo invalid (first opening-rows))
                       #(store/accept-adjustment! repo invalid {:row-id "notebook" :after 25 :reason "Count"})]]
        (is (= :validation (error-type access)))))))
