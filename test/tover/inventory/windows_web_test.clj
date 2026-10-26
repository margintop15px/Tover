(ns tover.inventory.windows-web-test
  (:require
    [charred.api :as json]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [tover.inventory.model :as model]
    [tover.inventory.read-model :as read-model]
    [tover.inventory.read-query :as read-query]
    [tover.inventory.store :as store]
    [tover.inventory.windows :as windows]
    [tover.inventory.workbench :as workbench]
    [tover.shared.rdf :as rdf]
    [tover.stock.web :as web])
  (:import
    [java.nio.file Files]))


(defn- with-context
  {:malli/schema [:=> [:cat [:=> [:cat :map] :any]] :any]}
  [run]
  (let [path    (.toFile (Files/createTempDirectory "tover-window-web-" (make-array java.nio.file.attribute.FileAttribute 0)))
        repo    (rdf/native-store path)
        context {:repo repo :workspace-id (random-uuid) :user-id (random-uuid)}]
    (binding [windows/*cache* (atom {:queries {} :reads {}})]
      (try (run context)
           (finally
             (rdf/close! repo)
             (doseq [file (reverse (file-seq path))] (io/delete-file file)))))))


(defn- decode
  {:malli/schema [:=> [:cat :map :keyword] :map]}
  [response key]
  (json/read-json (get-in response [:data key]) :key-fn keyword))


(defn- seed-stock!
  {:malli/schema [:=> [:cat :map :int] :nil]}
  [{:keys [repo] :as context} size]
  (let [rows (mapv (fn [index]
                     {:id   (str "stock-" index)        :item-id  (str "item-" index)
                      :name (format "Goods %05d" index) :quantity index               :unit "pcs" :location "Main"})
                   (range size))]
    (rdf/with-transaction [conn {:repo repo :level :serializable}]
      (read-model/stage-state! conn context {:ledger-revision 4 :drafts {} :projection {:items {} :rows rows}})))
  nil)


(defn- reject-full-read
  {:malli/schema [:=> [:cat [:* :any]] :any]}
  [& _]
  (throw (ex-info "Window reads must not load the canonical snapshot or full accepted history." {})))


(deftest accepted-snapshots-and-read-views-send-only-the-requested-window
  (with-context
    (fn [context]
      (seed-stock! context 1000)
      (with-redefs [store/snapshot reject-full-read model/read-history reject-full-read]
        (let [snapshot   (workbench/view-snapshot (workbench/query-stock context {}))
              initial    (json/read-json (:stockGridJson snapshot) :key-fn keyword)
              token      (get-in initial [:viewport :queryId])
              request    {:queryId token :view "stock" :requestId "middle" :start 600 :count 200}
              middle     (decode (web/grid-window {:context context :input request}) :_gridWindowJson)
              descriptor (get-in @windows/*cache* [:queries token])]
          (is (= 200 (count (:rows snapshot)) (count (:rows initial))))
          (is (= 1000 (get-in initial [:viewport :totalRows])))
          (is (= "null" (:operationsGridJson snapshot)))
          (is (empty? (:editStates initial)))
          (is (= "ok" (:status middle)))
          (is (= "stock-600" (get-in middle [:model :rows 0 :id])))
          (is (= 600 (get-in middle [:model :rows 0 :rowIndex])))
          (is (= 200 (count (get-in middle [:model :rows]))))
          (is (ifn? (:read descriptor)))
          (is (ifn? (:contains descriptor)))
          (is (not-any? #(contains? descriptor %) [:model :groups :anchors :row-groups])
              "A query token retains only a bounded descriptor, never a materialized inventory index")
          (is (empty? (:reads @windows/*cache*)))
          (doseq [other [(assoc context :user-id (random-uuid)) (assoc context :workspace-id (random-uuid))]]
            (is (= "expired" (:status (decode (web/grid-window {:context other :input request}) :_gridWindowJson)))))
          (is (= "expired" (:status (decode (web/grid-window {:context context :input (assoc request :view "operations")}) :_gridWindowJson))))
          (web/publish! context [])
          (is (= "expired" (:status (decode (web/grid-window {:context context :input request}) :_gridWindowJson)))))))))


(deftest filtering-and-sorting-apply-to-the-whole-query-before-windowing
  (with-context
    (fn [context]
      (seed-stock! context 1000)
      (with-redefs [store/snapshot reject-full-read model/read-history reject-full-read]
        (let [descending (workbench/accepted-grid (workbench/query-stock context {:sort "quantity" :direction "desc"}))
              filtered   (workbench/accepted-grid
                           (workbench/query-stock context {:filters {"name" [{:id "remote" :type "begins" :value "Goods 00999"}]}}))]
          (is (= 999 (get-in descending [:rows 0 :cells 1 :value])))
          (is (= 1 (get-in filtered [:viewport :totalRows])))
          (is (= "stock-999" (get-in filtered [:rows 0 :id]))))))))


(deftest filtered-out-stock-edits-have-an-explicit-bounded-acknowledgment
  (with-context
    (fn [context]
      (seed-stock! context 1000)
      (with-redefs [store/snapshot reject-full-read model/read-history reject-full-read]
        (let [props   (workbench/query-stock context {:filters  {"name" [{:id "keep" :type "begins" :value "Goods 000"}]}
                                                      :ackRowId "stock-999"})
              initial (workbench/accepted-grid props)
              cells   (:editStates initial)
              window  (decode (web/grid-window {:context context :input {:queryId     (get-in initial [:viewport :queryId])
                                                                         :requestId   "offscreen" :view          "stock"     :start 0
                                                                         :pendingRows ["stock-888"] :detailRowId "stock-999"}}) :_gridWindowJson)]
          (is (= 6 (count cells)))
          (is (= #{"stock-999"} (set (map #(get-in % [:target :rowId]) cells))))
          (is (= #{"stock-888"} (set (map #(get-in % [:target :rowId]) (get-in window [:model :editStates])))))
          (is (false? (get-in window [:model :viewport :detailFound])))
          (is (not-any? #(= "stock-999" (:id %)) (:rows initial))))))))


(deftest operation-details-are-fetched-separately-and-remain-workspace-authorized
  (with-context
    (fn [{:keys [repo user-id] :as context}]
      (let [entry {:kind      :operation :type  :payment   :occurred-at "2026-10-20T12:00:00Z"
                   :direction :outgoing  :party "Supplier" :amount      "19.45"                :currency "EUR" :reason "Invoice payment"}
            draft (store/save-entry! repo context {:id "payment" :entry entry :actor (str user-id)})]
        (store/submit! repo context {:selected                 [(select-keys draft [:id :revision])]
                                     :expected-ledger-revision 0 :actor (str user-id) :command-id (str (random-uuid))})
        (with-redefs [store/snapshot reject-full-read model/read-history reject-full-read]
          (let [snapshot (workbench/view-snapshot (workbench/query-stock context {:view "operations"}))
                grid     (json/read-json (:operationsGridJson snapshot) :key-fn keyword)
                request  {:queryId (get-in grid [:viewport :queryId]) :operationId "payment" :requestId "expand"}
                details  (decode (web/operation-details {:context context :input request}) :_operationDetailJson)]
            (is (nil? (:details grid)))
            (is (= [{:id "payment" :type :payment :operation "Payment"}] (:operationRows snapshot)))
            (is (= "ok" (:status details)))
            (is (= "Payment" (get-in details [:detail :heading])))
            (is (some #(= "Invoice payment" (:value %)) (get-in details [:detail :summary-metadata])))
            (is (nil? (get-in details [:detail :revisions])))
            (is (nil? (get-in details [:detail :sections])))
            (is (= "expired" (:status (decode (web/operation-details {:context (assoc context :workspace-id (random-uuid))
                                                                      :input   request}) :_operationDetailJson))))))))))


(deftest captured-query-props-cannot-reinstall-an-invalidated-generation
  (with-context
    (fn [{:keys [repo user-id] :as context}]
      (let [entry    {:kind  :opening :occurred-at "2026-10-20T12:00:00Z"
                      :lines [{:item-id "race-item" :name "Race item" :quantity 1 :unit "pcs" :location "Main"}]}
            draft    (store/save-entry! repo context {:entry entry :actor (str user-id)})
            captured (workbench/query-stock context {})]
        (store/save-entry! repo context {:id    (:id draft) :expected-revision 1 :actor (str user-id)
                                         :entry (assoc-in entry [:lines 0 :quantity] 2)})
        (web/publish! context [])
        (let [old-grid   (workbench/accepted-grid captured)
              old-token  (get-in old-grid [:viewport :queryId])
              fresh-grid (workbench/accepted-grid (workbench/query-stock context {}))]
          (is (nil? (windows/fetch! context {:queryId old-token}))
              "Rendering captured props after invalidation must reuse its expired token, never install a stale index")
          (is (not= old-token (get-in fresh-grid [:viewport :queryId])))
          (is (= 2 (get-in fresh-grid [:rows 0 :cells 1 :value]))))))))


(deftest snapshot-and-query-installation-serialize-with-a-concurrent-commit
  (with-context
    (fn [{:keys [repo user-id] :as context}]
      (let [entry          {:kind  :opening :occurred-at "2026-10-20T12:00:00Z"
                            :lines [{:item-id "concurrent-item" :name "Concurrent item" :quantity 1 :unit "pcs" :location "Main"}]}
            draft          (store/save-entry! repo context {:entry entry :actor (str user-id)})
            actual-page    read-query/page
            first-read?    (atom true)
            captured       (promise)
            release        (promise)
            writer-started (promise)]
        (with-redefs [read-query/page (fn [conn scope query]
                                        (let [page (actual-page conn scope query)]
                                          (when (compare-and-set! first-read? true false)
                                            (deliver captured {:repo-locked? (Thread/holdsLock repo)})
                                            (when (= ::timeout (deref release 10000 ::timeout))
                                              (throw (ex-info "Timed out waiting to resume the query fixture." {}))))
                                          page))]
          (let [reader (future (workbench/query-stock context {}))]
            (try
              (let [read-state (deref captured 10000 ::timeout)]
                (is (map? read-state))
                (is (true? (:repo-locked? read-state))
                    "The bounded RDF read must remain under the repository lock until its query token is installed")
                (let [writer (future
                               (deliver writer-started true)
                               (store/save-entry! repo context {:id    (:id draft) :expected-revision 1 :actor (str user-id)
                                                                :entry (assoc-in entry [:lines 0 :quantity] 3)})
                               (web/publish! context [])
                               :committed)]
                  (try
                    (is (= true (deref writer-started 10000 ::timeout)))
                    (deliver release true)
                    (let [props (deref reader 10000 ::timeout)]
                      (is (map? props))
                      (is (= :committed (deref writer 10000 ::timeout)))
                      (when (map? props)
                        (let [old-grid (workbench/accepted-grid props)]
                          (is (nil? (windows/fetch! context {:queryId (get-in old-grid [:viewport :queryId])}))))))
                    (finally
                      (deliver release true)
                      (future-cancel writer)))))
              (finally
                (deliver release true)
                (future-cancel reader)))))))))
