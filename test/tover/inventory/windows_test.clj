(ns tover.inventory.windows-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [tover.inventory.windows :as windows]))


(def context
  {:repo         (Object.)
   :user-id      #uuid "00000000-0000-0000-0000-000000000011"
   :workspace-id #uuid "00000000-0000-0000-0000-000000000001"})


(use-fixtures :each
  (fn [f]
    (binding [windows/*cache* (atom {:queries {} :reads {}})
              windows/*clock* (constantly 0)]
      (f))))


(defn- stock
  {:malli/schema [:=> [:cat :int] :map]}
  [total]
  {:id          "stock" :columns       [{:key "name"} {:key "quantity"}] :merges []
   :layoutScope "account" :savedWidths {"name" 400} :columnOrder                 ["quantity" "name"]
   :rows        (mapv (fn [index]
                        (let [id (str "row-" index)]
                          {:id    id :groupId id
                           :cells [{:value (str "Item " index) :target {:id id :rowId id :path "name" :revision 1}}
                                   {:value index :target {:id id :rowId id :path "quantity" :revision 1}}]})) (range total))})


(defn- operations
  {:malli/schema [:=> [:cat :int] :map]}
  [total]
  (reduce (fn [model index]
            (let [start (count (:rows model))
                  size  (+ 2 (mod index 4))
                  id    (str "operation-" index)]
              (-> model
                  (update :rows into (mapv (fn [line]
                                             {:id    (str id "/" line) :groupId id
                                              :cells [{:value (when (zero? line) "Production")}
                                                      {:value (str "Item " line)}]}) (range size)))
                  (update :merges conj {:row start :col 0 :rowspan size :colspan 1}))))
          {:id "operations" :columns [{:key "type"} {:key "name"}] :rows [] :merges []} (range total)))


(defn- open-query
  {:malli/schema [:=> [:cat :map [:? :map]] :map]}
  [model & [options]]
  (windows/initial! context (merge {:queryKey {:view (:id model)} :generation 1 :build (constantly model)} options)))


(defn- token
  {:malli/schema [:=> [:cat :map] :string]}
  [model]
  (get-in model [:viewport :queryId]))


(defn- error-data
  {:malli/schema [:=> [:cat [:=> [:cat] :any]] [:maybe :map]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (ex-data error))))


(deftest ten-thousand-stock-rows-have-bounded-windows-and-absolute-positions
  (let [model        (stock 10000)
        builds       (atom 0)
        query        {:queryKey {:view "stock" :sort "name"}   :generation [12 [["draft" 3]]]
                      :build    #(do (swap! builds inc) model)}
        first-window (windows/initial! context query)
        query-id     (token first-window)]
    (is (= [0 200 10000 10000] ((juxt :start :end :totalRows :totalGroups) (:viewport first-window))))
    (is (= (select-keys model [:columns :layoutScope :savedWidths :columnOrder])
           (select-keys first-window [:columns :layoutScope :savedWidths :columnOrder])))
    (doseq [start [0 200 4999 9800 9999 20000 4500 199]]
      (let [window              (windows/fetch! context {:queryId query-id :start start :count 10000})
            {:keys [start end]} (:viewport window)]
        (is (<= (count (:rows window)) windows/window-size))
        (is (= (mapv :id (subvec (:rows model) start end)) (mapv :id (:rows window))))
        (is (= (vec (range start end)) (mapv :rowIndex (:rows window))))
        (is (= (vec (range start end)) (mapv :groupIndex (:rows window))))))
    (is (= query-id (token (windows/initial! context query))))
    (is (= 1 @builds))))


(deftest ten-thousand-multi-line-operations-are-never-split
  (let [model    (operations 10000)
        initial  (open-query model)
        query-id (token initial)
        groups   (group-by :groupId (:rows model))]
    (is (= 35000 (get-in initial [:viewport :totalRows])))
    (is (= 10000 (get-in initial [:viewport :totalGroups])))
    (doseq [requested [1 197 201 16481 34998 100000]]
      (let [{:keys [rows merges viewport]} (windows/fetch! context {:queryId query-id :start requested})
            grouped (partition-by :groupId rows)]
        (is (<= 1 (count rows) 204))
        (is (= (:start viewport) (:rowIndex (first rows))))
        (doseq [group grouped]
          (is (= (mapv :id (get groups (:groupId (first group)))) (mapv :id group))))
        (is (= (count grouped) (count merges)))
        (doseq [{:keys [row rowspan]} merges]
          (is (= 1 (count (set (map :groupId (subvec rows row (+ row rowspan))))))))
        (is (= (:groupStart viewport) (:groupIndex (first rows))))))))


(deftest oversized-operation-and-empty-dataset-have-valid-geometry
  (let [model  {:id     "operations" :columns [{:key "type"}] :details {"large" {:secret true}}
                :rows   (mapv (fn [i] {:id (str "large/" i) :groupId "large" :cells [{:value "Production"}]}) (range 501))
                :merges [{:row 0 :col 0 :rowspan 501 :colspan 1}]}
        window (open-query model {:start 300})]
    (is (= 501 (count (:rows window))))
    (is (= [{:row 0 :col 0 :rowspan 501 :colspan 1}] (:merges window)))
    (is (= [0 501 0] ((juxt :start :end :groupStart) (:viewport window))))
    (is (not (contains? window :details)))
    (is (every? #(not (contains? (:model %) :details)) (vals (:queries @windows/*cache*)))))
  (let [window (open-query (stock 0))]
    (is (= [] (:rows window)))
    (is (= [0 0 0 0] ((juxt :start :end :totalRows :totalGroups) (:viewport window))))))


(deftest anchor-resolution-follows-stable-row-or-operation-identities
  (let [model    (operations 100)
        initial  (open-query model)
        query-id (token initial)
        expected (first (keep-indexed #(when (= "operation-70" (:groupId %2)) %1) (:rows model)))]
    (doseq [anchor ["operation-70" "operation-70/1"]]
      (let [window (windows/fetch! context {:queryId query-id :start 0 :anchorId anchor})]
        (is (= expected (get-in window [:viewport :start])))
        (is (= 70 (get-in window [:viewport :groupStart])))
        (is (true? (get-in window [:viewport :anchorFound])))))
    (let [window (windows/fetch! context {:queryId query-id :start 200 :anchorId "removed-operation"})]
      (is (false? (get-in window [:viewport :anchorFound])))
      (is (<= (get-in window [:viewport :start]) 200 (dec (get-in window [:viewport :end])))))))


(deftest inline-details-distinguish-offscreen-rows-from-filtered-out-rows
  (let [initial (open-query (stock 1000) {:detailRowId "row-900"})
        present (windows/fetch! context {:queryId (token initial) :start 200 :detailRowId "row-900"})
        absent  (windows/fetch! context {:queryId (token initial) :start 200 :detailRowId "filtered-out"})]
    (is (true? (get-in initial [:viewport :detailFound])))
    (is (true? (get-in present [:viewport :detailFound])))
    (is (false? (get-in absent [:viewport :detailFound])))
    (is (not-any? #(= "row-900" (:id %)) (:rows present)))))


(deftest only-requested-offscreen-edit-acknowledgments-leave-the-read-index
  (let [model        (assoc (stock 1000) :editStates [{:target {:id "filtered" :rowId "filtered" :path "quantity" :revision 2} :value 9}])
        first-window (open-query model)
        window       (windows/fetch! context {:queryId (token first-window) :start 0 :pendingRows ["row-900" "filtered" "gone" "filtered"]})]
    (is (= [] (:editStates first-window)))
    (is (= #{"row-900" "filtered"} (set (map #(get-in % [:target :rowId]) (:editStates window)))))
    (is (= 3 (count (:editStates window))))
    (is (= ["gone"] (:missingRows window)))
    (is (every? #(= #{:target :value} (set (keys %))) (:editStates window)))))


(deftest server-ordering-and-filtering-apply-before-window-boundaries
  (let [source   (stock 1000)
        filtered (assoc source :rows (vec (reverse (filter #(even? (get-in % [:cells 1 :value])) (:rows source)))))
        result   (open-query filtered {:queryKey {:view "stock" :filters {:quantity :even} :direction "desc"} :start 200})]
    (is (= 500 (get-in result [:viewport :totalRows])))
    (is (= "row-598" (:id (first (:rows result)))))
    (is (= "row-200" (:id (last (:rows result)))))))


(deftest query-and-read-snapshot-caches-are-scoped-and-generation-aware
  (let [other-user      (assoc context :user-id (random-uuid))
        other-workspace (assoc context :workspace-id (random-uuid))
        other-repo      (assoc context :repo (Object.))
        builds          (atom 0)
        query           {:queryKey {:view "stock"} :generation [3 [["draft" 4]]]
                         :build    #(do (swap! builds inc) (stock 300))}
        initial         (windows/initial! context query)]
    (doseq [outsider [other-user other-workspace other-repo]]
      (is (nil? (windows/fetch! outsider {:queryId (token initial) :start 0})))
      (is (false? (windows/contains-group? outsider (token initial) "row-0"))))
    (is (windows/contains-group? context (token initial) "row-250"))
    (is (false? (windows/contains-group? context (token initial) "not-filtered-in")))
    (is (= (token initial) (token (windows/initial! context query))))
    (is (not= (token initial) (token (windows/initial! other-user query))))
    (is (not= (token initial) (token (windows/initial! context (assoc query :generation [3 [["draft" 5]]])))))
    (is (not= (token initial) (token (windows/initial! context (assoc query :generation [4 []])))))
    (is (= 4 @builds))
    (is (nil? (windows/fetch! context {:queryId (token initial) :generation :stale})))
    (is (nil? (windows/fetch! context {:queryId (token initial)}))))
  (let [builds (atom 0)
        build  #(do (swap! builds inc) {:snapshot @builds})]
    (is (= {:snapshot 1} (windows/read-index! context 1 build)))
    (is (= {:snapshot 1} (windows/read-index! context 1 build)))
    (is (= {:snapshot 2} (windows/read-index! context 2 build)))
    (is (= {:snapshot 3} (windows/read-index! (assoc context :user-id (random-uuid)) 1 build)))
    (is (= 3 @builds))))


(deftest workspace-invalidation-expires-every-members-query-and-read-snapshot
  (let [member  (assoc context :user-id (random-uuid))
        foreign (assoc context :workspace-id (random-uuid))
        query   {:queryKey :stock :generation 1 :build #(stock 10)}
        own     (windows/initial! context query)
        shared  (windows/initial! member query)
        other   (windows/initial! foreign query)]
    (doseq [owner [context member foreign]] (windows/read-index! owner 1 (constantly {:value 1})))
    (windows/invalidate! context)
    (is (nil? (windows/fetch! context {:queryId (token own)})))
    (is (nil? (windows/fetch! member {:queryId (token shared)})))
    (is (some? (windows/fetch! foreign {:queryId (token other)})))
    (is (= 1 (count (:reads @windows/*cache*))))))


(deftest idle-expiry-and-lru-keep-both-caches-bounded
  (let [clock (atom 0)]
    (binding [windows/*clock* #(deref clock)]
      (let [first-window (open-query (stock 10))]
        (reset! clock 299999)
        (is (some? (windows/fetch! context {:queryId (token first-window)})))
        (reset! clock 599998)
        (is (some? (windows/fetch! context {:queryId (token first-window)})))
        (reset! clock 899998)
        (is (nil? (windows/fetch! context {:queryId (token first-window)}))))
      (reset! clock 1000000)
      (let [oldest (open-query (stock 10))]
        (doseq [generation (range 1 34)]
          (swap! clock inc)
          (open-query (stock 10) {:generation generation})
          (windows/read-index! context generation (constantly {:value generation})))
        (is (= 32 (count (:queries @windows/*cache*))))
        (is (= 32 (count (:reads @windows/*cache*))))
        (is (nil? (windows/fetch! context {:queryId (token oldest)}))))
      (swap! clock + 300000)
      (is (nil? (windows/fetch! context {:queryId "unknown"})))
      (is (= {} (:queries @windows/*cache*)))
      (windows/read-index! context :fresh (constantly {:fresh true}))
      (is (= 1 (count (:reads @windows/*cache*)))))))


(deftest simultaneous-openings-build-once-and-same-clock-insertions-retain-their-token
  (let [builds  (atom 0)
        gate    (promise)
        query   {:queryKey :same :generation 1 :build #(do (swap! builds inc) (stock 10))}
        readers (mapv (fn [_] (future @gate (windows/initial! context query))) (range 8))]
    (deliver gate true)
    (let [results (mapv #(deref % 10000 ::timeout) readers)]
      (is (every? map? results))
      (is (= 1 (count (set (map token results)))))
      (is (= 1 @builds))))
  (doseq [index (range 70)]
    (let [window (open-query (stock 10) {:queryKey index})]
      (is (some? (windows/fetch! context {:queryId (token window)})))))
  (is (= 32 (count (:queries @windows/*cache*)))))


(deftest untrusted-ranges-and-invalid-grid-merges-are-rejected
  (doseq [input [{:start -1} {:start 1.5} {:count 0} {:count -1} {:anchorId 3}
                 {:pendingRows nil} {:pendingRows (vec (repeat 101 "row-0"))} {:pendingRows [4]}]]
    (is (= :invalid-input (:type (error-data #(open-query (stock 10) input))))) (pr-str input))
  (doseq [owner [(dissoc context :repo) (dissoc context :user-id) (assoc context :user-id "forged") (dissoc context :workspace-id)]]
    (is (= :forbidden (:type (error-data #(windows/initial! owner {:queryKey :stock :generation 1 :build (fn [] (stock 10))}))))))
  (testing "A bad builder cannot create windows that divide one merge across groups"
    (doseq [model [(assoc (stock 3) :merges [{:row 0 :col 0 :rowspan 2 :colspan 1}])
                   (assoc (stock 3) :merges [{:row 2 :col 0 :rowspan 2 :colspan 1}])
                   (assoc (stock 3) :merges [{:row -1 :col 0 :rowspan 1 :colspan 1}])
                   (update (stock 3) :rows #(assoc-in % [2 :groupId] "row-0"))]]
      (is (= :invalid-grid (:type (error-data #(open-query model))))))))
