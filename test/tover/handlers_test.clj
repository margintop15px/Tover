(ns tover.handlers-test
  (:require
    [bel.core :as bel]
    [bel.handler :as handler]
    [bel.realtime :as realtime]
    [charred.api :as charred]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [tover.actions :as actions]
    [tover.operations :as operations]
    [tover.pages :as pages]
    [tover.rdf :as rdf]
    [tover.store :as store]
    [tover.streams :as streams]
    [tover.views :as views])
  (:import [java.nio.file Files]))


(def row
  {:id "test" :name "Test goods" :article "TEST" :unit "pcs" :location "Shelf" :quantity 12})


(defn with-context
  {:malli/schema [:=> [:cat [:=> [:cat actions/Context] :any]] :any]}
  [run]
  (let [path (.toFile (Files/createTempDirectory "tover-handlers-" (make-array java.nio.file.attribute.FileAttribute 0)))
        repo (rdf/native-store path)
        stream (realtime/start!)]
    (try
      (store/declare-opening! repo row)
      (run {:repo repo :realtime stream})
      (finally
        (realtime/stop! stream)
        (rdf/close! repo)
        (doseq [file (reverse (file-seq path))] (io/delete-file file))))))


(defn html
  {:malli/schema [:=> [:cat bel/Result] :string]}
  [result]
  (handler/render-html
    (into [] (keep #(when (= :elements (:type %)) (:html %))
                   (if (map? result) [result] result)))))


(deftest quantity-preview-discard-and-accept
  (with-context
    (fn [{:keys [repo] :as context}]
      (let [before (store/snapshot repo)
            preview (actions/preview-quantity context {:rowId "test" :quantity "8" :query ""})]
        (is (= {:reason "" :_quantityPending true :_quantityRow "test" :_quantityValue "8"}
               (:data (first preview))))
        (is (re-find #"id=\"review-test\"" (html preview)))
        (is (re-find #"12 pcs" (html preview)))
        (is (re-find #"8 pcs" (html preview)))
        (is (re-find #"Review this adjustment before accepting" (html preview)))
        (is (not (re-find #"class=\"error\"" (html preview))))
        (is (= before (store/snapshot repo)))
        (let [discarded (actions/discard context {:query ""})]
          (is (re-find #"<div id=\"stock-review\"></div>" (html discarded)))
          (is (some #(= {:reason "" :_quantityPending false :_gridNotice ""} (:data %)) discarded)))
        (is (= before (store/snapshot repo)))
        (let [accepted (actions/accept context {:rowId "test" :quantity "8" :reason "Shelf count" :query ""})
              snapshot (store/snapshot repo)]
          (is (= 8 (:quantity (first (:rows snapshot)))))
          (is (= "Shelf count" (:reason (first (:history snapshot)))))
          (is (= 1 (count (:history snapshot))))
          (is (re-find #"Accepted 12 → 8" (html accepted)))
          (is (re-find #"<div id=\"stock-review\"></div>" (html accepted)))
          (is (some #(= {:reason "" :_quantityPending false :_gridNotice ""} (:data %)) accepted))
          (is (not (re-find #"id=\"history-panel\"" (html accepted)))))))))


(deftest invalid-input-leaves-stock-unchanged
  (with-context
    (fn [{:keys [repo] :as context}]
      (let [before (store/snapshot repo)]
        (doseq [result [(actions/preview-quantity context {:rowId "test" :quantity "-1" :query ""})
                        (actions/preview-quantity context {:rowId "missing" :quantity "8" :query ""})]]
          (is (re-find #"class=\"error\"" (html result)))
          (is (re-find #"<div id=\"stock-review\"></div>" (html result)))
          (is (= {:_quantityPending false} (:data (last result))))
          (is (= before (store/snapshot repo))))
        (let [result (actions/accept context {:rowId "test" :quantity "8" :reason "" :query ""})]
          (is (re-find #"class=\"error\"" (html result)))
          (is (not (re-find #"id=\"stock-review\"" (html result))))
          (is (not-any? #(= :signals (:type %)) (if (map? result) [result] result))
              "Invalid acceptance preserves the pending change and entered reason")
          (is (= before (store/snapshot repo))))))))


(deftest search-updates-accepted-stock
  (with-context
    (fn [context]
      (let [model (views/query-stock context {:query "TEST"})
            stock (views/stock model)]
        (is (= [row] (:rows model)))
        (is (= "TEST" (:value (views/smart-props (:interpretation model)))))
        (is (= {:stockChanged false
                :_stockRows   [(assoc row :quantity "12" :evidence "Opening declaration")]
                :_operations  (operations/query context)}
               (:data (last stock)))))
      (is (= [] (:_stockRows (:data (last (views/stock (views/query-stock context {:query "missing"}))))))))))


(deftest background-refresh-preserves-editor-review-and-reason
  (with-context
    (fn [{:keys [repo] :as context}]
      (store/accept-adjustment! repo {:row-id "test" :after Long/MAX_VALUE :reason "External count"})
      (let [model (assoc (views/query-stock context {:query ""})
                         :editing {:row row}
                         :proposal {:row row :row-id "test" :before 12 :after 8}
                         :history {:row row :history []})
            refresh (views/stock model)
            patch (:data (last refresh))
            local {:_gridEditing true :_quantityPending true :_gridNotice "Keep this notice" :reason "Still counting"}]
        (is (= #{:stockChanged :_stockRows :_operations} (set (keys patch))))
        (is (= (str Long/MAX_VALUE) (get-in patch [:_stockRows 0 :quantity])))
        (is (= "Manual edit" (get-in patch [:_stockRows 0 :evidence])))
        (is (= local (select-keys (merge local patch) (keys local))))
        (is (= [[:div#smart-result nil]] (mapv :html (filter #(= :elements (:type %)) refresh))))))))


(deftest deleted-row-preview-replaces-cell-edits-without-changing-stock
  (with-context
    (fn [{:keys [repo] :as context}]
      (let [before (store/snapshot repo)
            preview (views/query-draft-preview context
                                               {:cells       [{:rowId "test" :field "name" :value "Edited goods"}]
                                                :deletedRows ["test"]})]
        (is (= [{:row-id "test"           :name  "Test goods" :field "Row" :deleted? true
                 :before "12 pcs · Shelf" :after "Deleted"}]
               preview))
        (is (= before (store/snapshot repo)))))))


(deftest draft-preview-uses-submitted-baseline-without-changing-stock
  (with-context
    (fn [{:keys [repo] :as context}]
      (let [before (store/snapshot repo)
            preview (views/query-draft-preview context
                                               {:baseline [{:rowId "test" :field "unit" :value "boxes"}]
                                                :cells    [{:rowId "test" :field "unit" :value "bags"}]})]
        (is (= [{:row-id "test" :name "Test goods" :field "Unit" :before "boxes" :after "bags"}]
               preview))
        (is (= before (store/snapshot repo)))))))


(deftest new-row-preview-and-later-local-edit-leave-stock-unchanged
  (with-context
    (fn [{:keys [repo] :as context}]
      (let [before (store/snapshot repo)
            added {:id       "draft-new"  :name    "New goods" :quantity "3"            :unit "boxes"
                   :location "Back shelf" :article "NEW-1"     :evidence "Manual entry"}]
        (is (= [{:row-id "draft-new" :name  "New goods"                    :field "Row" :added? true
                 :before ""          :after "3 boxes · Back shelf · NEW-1"}]
               (views/query-draft-preview context {:cells [] :addedRows [added]})))
        (is (= [{:row-id "draft-new" :name "New goods" :field "Quantity" :before "3" :after "4"}]
               (views/query-draft-preview context
                                          {:localRows [added] :cells [{:rowId "draft-new" :field "quantity" :value "4"}]})))
        (is (= before (store/snapshot repo)))))))


(deftest smart-input-previews-without-changing-stock
  (with-context
    (fn [{:keys [repo] :as context}]
      (let [before (store/snapshot repo)
            preview (actions/preview-smart context {:query "set TEST to 8"})]
        (is (re-find #"id=\"review-test\"" (html preview)))
        (is (= {:reason "" :_quantityPending true :_quantityRow "test" :_quantityValue "8"}
               (:data (first preview))))
        (is (= before (store/snapshot repo)))))))


(deftest history-opens-and-closes
  (with-context
    (fn [context]
      (actions/accept context {:rowId "test" :quantity "8" :reason "Shelf count" :query ""})
      (is (re-find #"Shelf count" (html (actions/history context {:rowId "test"}))))
      (is (re-find #"Opening declaration" (html (actions/history context {:rowId "test"}))))
      (is (= "<div id=\"history-panel\"></div>" (html (actions/close-history context {})))))))


(deftest declaration-http-input-is-small-and-validated
  (with-context
    (fn [context]
      (bel/init! {:scan-ns ['tover]})
      (is (re-find #"review-test" (html (handler/execute-action context :tover.actions/preview-quantity
                                                                {:rowId "test" :quantity "8" :query ""}))))
      (let [accepted (handler/execute-action context :tover.actions/accept
                                             {:rowId "test" :query "" :quantity "8" :reason "Shelf count" :actor "unexpected"})]
        (is (re-find #"Accepted 12 → 8" (html accepted)))
        (is (not (contains? (first (:history (store/snapshot (:repo context)))) :actor))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (handler/execute-action context :tover.actions/preview-quantity {:quantity "8" :query ""}))))))


(deftest page-has-local-signals-and-direct-events
  (with-context
    (fn [context]
      (let [document (:html (pages/stock (pages/query-stock context {})))
            body (last document)
            body-attrs (if (map? (second body)) (second body) {})
            workbench (first (filter vector? (rest body)))
            workbench-attrs (second workbench)
            attributes (keep #(when (and (vector? %) (map? (second %))) (second %))
                             (tree-seq sequential? seq document))
            page (handler/render-html document)]
        (is (not-any? #(contains? body-attrs %) [:data-signals :data-effect]))
        (is (re-find #"/_bel/realtime/stream" (:data-init body-attrs)))
        (is (= :div#inventory-evidence-workbench (first workbench)))
        (is (= {:stockChanged       false :_gridEditing       false :_quantityPending false :_gridNotice  ""
                :_view              "stock" :_operations      (operations/query context) :_localOperations []
                :_exampleOperations [] :_demoRows             [] :_showOperationExamples                   false
                :_cellDrafts        {}
                :_addedRows         [] :_submittedAddedRows   [] :_previewAddedRows [] :_previewLocalRows []
                :_changedRows       0 :_draftPreview          false :_previewCells []
                :_quantityRow       "" :_quantityValue        ""
                :_selectedRows      [] :_deletedRows          []
                :_submittedCells    {} :_submittedDeletedRows [] :_uiSubmitted false :_previewBaseline    []
                :_stockRows         [(assoc row :quantity "12" :evidence "Opening declaration")]}
               (charred/read-json (:data-signals workbench-attrs) :key-fn keyword)))
        (is (= 1 (count (filter :data-signals attributes))))
        (is (not (re-find #"Demo data|data-examples|data-on:examples-toggle" page)))
        (is (nil? (:data-init workbench-attrs)))
        (is (string? (:data-effect workbench-attrs)))
        (is (some #(and (= "" (:data-ignore-morph %))
                        (string? (:data-effect %))
                        (string? (:data-on:row-add %))) attributes))
        (is (re-find #"/_bel/realtime/stream" page))
        (let [submit (first (filter #(= "Submit changes" (:aria-label %)) attributes))
              event (:data-on:click submit)]
          (is (string? event))
          (is (re-find #"\$_submittedCells\s*=" event))
          (is (not (re-find #"Submitted · UI preview" event)))
          (is (not (re-find #"@post|/action/" event))))
        (is (some #(and (string? (:data-on:quantity-preview %))
                        (re-find #"evt\.detail\.rowId" (:data-on:quantity-preview %))
                        (re-find #"evt\.detail\.quantity" (:data-on:quantity-preview %))) attributes))
        (is (re-find #"/view/tover.views/stock" page))
        (is (not (re-find #"window\.|belRequests|toverStock|belRequestComplete" page)))))))


(deftest page-examples-are-explicit-and-do-not-change-stored-stock
  (with-context
    (fn [{:keys [repo] :as context}]
      (let [before (store/snapshot repo)
            examples {:rows    [(assoc row :id "demo-row" :quantity "5")]
                      :entries [{:id "demo-operation" :kind "opening" :example true}]}
            {:keys [stock-props] :as data} (pages/query-stock (assoc context :operation-examples examples) {})
            signals (charred/read-json (:signals stock-props) :key-fn keyword)
            page (html (pages/stock data))]
        (is (= (:rows examples) (:_demoRows signals)))
        (is (= (:entries examples) (:_exampleOperations signals)))
        (is (true? (:_showOperationExamples signals)))
        (is (re-find #"Demo data" page))
        (is (re-find #"data-examples=\"\"" page))
        (is (re-find #"data-on:examples-toggle" page))
        (is (= before (store/snapshot repo)))))))


(deftest stock-stream-requests-a-simple-refresh
  (is (= {:stockChanged true} (:data (streams/stock {} {})))))
