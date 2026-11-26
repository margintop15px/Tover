(ns tover.marketplace.warehouses-test
  "Board chapter 43 · Match marketplace warehouses: the reads (slices 1, 3, 5) and the confirm and change
  commands (slices 2, 4) over given facts, then the models, coeffects and connection count through a store."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.runtime :as runtime]
    [clojure.test :refer [are deftest is testing use-fixtures]]
    [tover.catalogues.categories.model]
    [tover.catalogues.products.model]
    [tover.catalogues.stores.model]
    [tover.catalogues.suppliers.model]
    [tover.catalogues.warehouses.model]
    [tover.inventory.entries.model]
    [tover.inventory.stock-items.model]
    [tover.marketplace.connections.model :as connections]
    [tover.marketplace.products-test :as listings]
    [tover.marketplace.provenance]
    [tover.marketplace.sync.model :as sync]
    [tover.marketplace.sync.observations :as observations]
    [tover.marketplace.warehouses.model :as warehouses]
    [tover.support :as support]
    [tover.workspaces.currency.model])
  (:import
    [java.time Instant]))


(defn register
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (bel/build-registry! ['tover.catalogues.products.model 'tover.catalogues.stores.model 'tover.catalogues.warehouses.model
                        'tover.inventory.stock-items.model 'tover.marketplace.connections.model
                        'tover.marketplace.sync.model 'tover.marketplace.products.model 'tover.marketplace.warehouses.model
                        'tover.workspaces.currency.model 'tover.catalogues.suppliers.model 'tover.catalogues.categories.model
                        'tover.inventory.entries.model 'tover.marketplace.provenance])
  (f))


(use-fixtures :each bel-test/isolated-registry register listings/unit-authority)


(def c "c-1")
(def other-c "c-2")
(def member listings/member)
(def at listings/at)


(defn external
  "An external warehouse as the coeffects return it."
  {:malli/schema [:=> [:cat :string :string [:* :any]] :map]}
  [type source-id & {:keys [name connection-id revision active] :or {connection-id c revision 1 active true}}]
  (cond-> {:external-warehouse-id (observations/warehouse-id connection-id type source-id) :connection-id connection-id
           :source-id             source-id :warehouse-type type :revision revision :active active}
    name (assoc :name name)))


(defn warehouse
  {:malli/schema [:=> [:cat :string :string [:* :any]] :map]}
  [id name & {:keys [status] :or {status "active"}}]
  {:warehouse-id id :name name :status status :revision 1})


(defn mapping
  {:malli/schema [:=> [:cat :map :string] :map]}
  [{:keys [external-warehouse-id connection-id warehouse-type]} warehouse-id]
  {:external-warehouse-id external-warehouse-id :connection-id    connection-id :warehouse-type warehouse-type
   :warehouse-id          warehouse-id          :mapping-revision 1             :state          "confirmed"    :changed-by (str member) :changed-at at})


(def connection-rows
  [{:connection-id c :provider-account-id "111" :account-label "Main shop"}
   {:connection-id other-c :provider-account-id "222" :account-label "Shop LLC"}])


(def main (warehouse "main" "Main"))


(defn by-id
  {:malli/schema [:=> [:cat [:sequential :map]] :map]}
  [rows]
  (into {} (map (juxt :external-warehouse-id identity)) rows))


(defn read-handler
  {:malli/schema [:=> [:cat :keyword :map :map] :any]}
  [id facts input]
  ((:handler (get-in (bel/operations) [:read-models id])) (merge {:mappings {} :warehouses [main] :connections connection-rows :waiting {}} facts)
                                                          input))


(defn decide
  {:malli/schema [:=> [:cat :keyword :map :map] :map]}
  [command facts input]
  (runtime/decide command (merge {:scope support/scope :actor member :now (Instant/parse at) :mappings {} :named {} :warehouses [main]} facts)
                  input))


(defn confirm
  "Confirm Warehouse Mapping of `rows` with `externals` known."
  {:malli/schema [:=> [:cat [:sequential :map] [:sequential :map] [:* :any]] :map]}
  [externals rows & {:as facts}]
  (decide ::warehouses/confirm-warehouse-mapping (merge {:externals (by-id externals)} facts) {:rows rows}))


(defn row
  {:malli/schema [:=> [:cat :map [:* :any]] :map]}
  [external & {:as choice}]
  (merge {:external-warehouse-id (:external-warehouse-id external) :revision (:revision external)} choice))


(defn rejection
  {:malli/schema [:=> [:cat [:fn fn?]] [:maybe :keyword]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))


(defn offending
  "The rejection's rows as [external-warehouse-id code], or nil when accepted."
  {:malli/schema [:=> [:cat [:fn fn?]] [:maybe [:vector [:vector :any]]]]}
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         (mapv (juxt :external-warehouse-id :code) (:rows (ex-data e))))))


(def accepted-fx
  [[:bel/publish {:topic :tover.inventory.web/changed :scope-id (:workspace-id support/scope) :data {}}]
   [::sync/mark-dirty {:connection-id c}]])


;; Slice 1 · Unmatched External Warehouses


(def twelve
  (mapv #(external "fbo" (str (inc %)) :name (str "FBO " (inc %))) (range 12)))


(deftest many-ozon-warehouses
  (let [rows (:rows (read-handler ::warehouses/unmatched-external-warehouses {:externals twelve} {:connection-id c}))]
    (is (= 12 (count rows)))
    (is (every? #(= {:new-name (:name %)} (:suggestion %)) rows) "each row is prefilled with a new warehouse under the provider's name")
    (let [plan (confirm twelve (mapv #(row % :warehouse-name (get-in % [:suggestion :new-name])) rows))]
      (is (= (concat (repeat 12 :tover.catalogues.warehouses.model/warehouse-registered) (repeat 12 ::warehouses/external-warehouse-mapped))
             (map :type (:events plan)))
          "one confirm registers and maps all 12")
      (is (= {:warehouse-id "listing-warehouse:c-1:fbo:1" :name "FBO 1" :status "active" :revision 1}
             (:data (some #(when (= "FBO 1" (get-in % [:data :name])) %) (:events plan)))))
      (is (= {:external-warehouse-id "c-1:fbo:1"                   :mapping-revision-id "c-1:fbo:1#1" :connection-id c :warehouse-type "fbo"
              :change                "mapped" :mapping-revision 1 :changed-by (str member) :changed-at at
              :warehouse-id          "listing-warehouse:c-1:fbo:1" :state               "confirmed"}
             (:data (some #(when (= "c-1:fbo:1" (get-in % [:data :external-warehouse-id])) %)
                          (filter #(= ::warehouses/external-warehouse-mapped (:type %)) (:events plan))))))
      (is (= accepted-fx (:fx plan)) "operations waiting on the connection are evaluated again"))))


(deftest rows-sorted-by-waiting-operations
  (let [[a b] twelve
        rows  (:rows (read-handler ::warehouses/unmatched-external-warehouses
                                   {:externals [a b] :waiting {(:external-warehouse-id b) 3}} {:connection-id c}))]
    (is (= [[(:external-warehouse-id b) 3] [(:external-warehouse-id a) 0]] (mapv (juxt :external-warehouse-id :waiting-operations) rows)))))


(deftest blank-name-suggests-the-id
  (are [name] (= {:new-name "77"}
                 (:suggestion (first (:rows (read-handler ::warehouses/unmatched-external-warehouses
                                                          {:externals [(external "fbo" "77" :name name)]} {:connection-id c})))))
    nil "" "  "))


(deftest second-account-suggestion-adds-the-label
  (let [first-account  (external "fbo" "5" :name "Хоругвино")
        second-account (external "fbo" "9" :name "Хоругвино" :connection-id other-c)
        facts          {:externals  [second-account]
                        :mappings   {(:external-warehouse-id first-account) (mapping first-account "listing-warehouse:c-1:fbo:5")}
                        :warehouses [main (warehouse "listing-warehouse:c-1:fbo:5" "Хоругвино")]}]
    (is (= {:new-name "Хоругвино · Shop LLC"}
           (:suggestion (first (:rows (read-handler ::warehouses/unmatched-external-warehouses facts {:connection-id other-c}))))))))


(deftest name-equality-is-a-suggestion-only
  (let [seller (external "fbs" "1" :name " main ")
        fbo    (external "fbo" "2" :name "Main")
        rows   (by-id (:rows (read-handler ::warehouses/unmatched-external-warehouses {:externals [seller fbo]} {:connection-id c})))]
    (is (= {:warehouse-id "main"} (:suggestion (get rows (:external-warehouse-id seller)))) "a seller warehouse suggests the warehouse of its name")
    (is (= {:new-name "Main · Main shop"} (:suggestion (get rows (:external-warehouse-id fbo)))) "FBO defaults to new")
    (testing "a warehouse an FBO warehouse uses is not suggested to another"
      (let [hub (external "fbo" "3" :connection-id other-c)]
        (is (= {:new-name "main · Main shop"}
               (:suggestion (first (:rows (read-handler ::warehouses/unmatched-external-warehouses
                                                        {:externals [seller] :mappings {(:external-warehouse-id hub) (mapping hub "main")}}
                                                        {:connection-id c}))))))))))


(deftest inactive-warehouses-are-listed
  (let [row (first (:rows (read-handler ::warehouses/unmatched-external-warehouses
                                        {:externals [(external "fbs" "1" :name "Old" :active false)]} {:connection-id c})))]
    (is (= ["fbs" false] ((juxt :warehouse-type :active) row)))))


;; Slice 2 · Confirm Warehouse Mapping


(deftest name-taken
  (let [externals twelve
        rows      (mapv #(row % :warehouse-name (:name %)) externals)]
    (testing "a name an existing warehouse holds, archived included, trimmed and case-insensitive"
      (let [rows (assoc rows 3 (row (externals 3) :warehouse-name " archived HUB "))]
        (is (= [["c-1:fbo:4" :name-taken]]
               (offending #(confirm externals rows :warehouses [main (warehouse "hub" "Archived hub" :status "archived")]))))
        (is (= :name-taken (rejection #(confirm externals rows :warehouses [main (warehouse "hub" "Archived hub" :status "archived")]))))))
    (testing "a name another row of the batch takes: both rows are marked"
      (is (= [["c-1:fbo:2" :name-taken] ["c-1:fbo:3" :name-taken]]
             (offending #(confirm externals (assoc rows 2 (row (externals 2) :warehouse-name "fbo 2")))))))
    (testing "a blank name"
      (is (= [["c-1:fbo:1" :invalid-name]] (offending #(confirm externals (assoc rows 0 (row (externals 0) :warehouse-name " ")))))))))


(defn confirm-both
  "Two external warehouses of types `a` and `b`, both confirmed to `warehouse-id`: in one batch, then the second
  after the first is mapped from another connection."
  {:malli/schema [:=> [:cat :string :string :string] :nil]}
  [a b warehouse-id]
  (let [first-one (external a "1" :name "One")
        second    (external b "2" :name "Two")
        elsewhere (external a "1" :name "Elsewhere" :connection-id other-c)]
    (confirm [first-one second] [(row first-one :warehouse-id warehouse-id) (row second :warehouse-id warehouse-id)])
    (confirm [second] [(row second :warehouse-id warehouse-id)]
             :mappings {(:external-warehouse-id elsewhere) (mapping elsewhere warehouse-id)}
             :named {(:external-warehouse-id elsewhere) elsewhere})
    nil))


(deftest two-fbo-warehouses-to-one
  (let [one (external "fbo" "1" :name "Хоругвино")
        two (external "fbo" "2" :name "Тверь")]
    (is (= :already-used (rejection #(confirm [one two] [(row one :warehouse-id "main") (row two :warehouse-id "main")]))))
    (try (confirm [one two] [(row one :warehouse-id "main") (row two :warehouse-id "main")])
         (catch clojure.lang.ExceptionInfo e
           (is (= "Already used for another Ozon warehouse." (ex-message e)))
           (is (= [["c-1:fbo:1" :already-used "Тверь"] ["c-1:fbo:2" :already-used "Хоругвино"]]
                  (mapv (juxt :external-warehouse-id :code :other-warehouse) (:rows (ex-data e)))) "each row names the other")))))


(deftest fbo-warehouse-is-not-shared
  (are [a b] (= :already-used (rejection #(confirm-both a b "main")))
    "fbo" "fbo"
    "fbo" "fbs"
    "fbs" "fbo"))


(deftest seller-warehouses-may-share
  (is (nil? (rejection #(confirm-both "fbs" "supply-origin" "main"))))
  (is (nil? (rejection #(confirm-both "rfbs" "fbs" "main")))))


(deftest confirm-rejections
  (let [hub (external "fbs" "1" :name "Hub" :revision 2)]
    (are [code r facts] (= [[(:external-warehouse-id hub) code]] (offending #(apply confirm [hub] [r] (mapcat identity facts))))
      :stale (row hub :revision 1 :warehouse-id "main") {}
      :warehouse-not-available (row hub :warehouse-id "gone") {}
      :warehouse-not-available (row hub :warehouse-id "old") {:warehouses [(warehouse "old" "Old" :status "archived")]}
      :already-mapped (row hub :warehouse-id "main") {:mappings   {(:external-warehouse-id hub) (mapping hub "other")}
                                                      :warehouses [main (warehouse "other" "Other")]}
      :warehouse-exists (row hub :warehouse-name "Hub 2") {:warehouses [main (warehouse "listing-warehouse:c-1:fbs:1" "Hub")]})))


(deftest a-row-twice-or-confirmed-again
  (let [hub (external "fbs" "1" :name "Hub")]
    (is (= :already-mapped (rejection #(confirm [hub] [(row hub :warehouse-id "main") (row hub :warehouse-name "Hub")]))))
    (is (empty? (:events (confirm [hub] [(row hub :warehouse-id "main")] :mappings {(:external-warehouse-id hub) (mapping hub "main")})))
        "already confirmed to the same warehouse: nothing to record")
    (is (= 2 (get-in (confirm [hub] [(row hub :warehouse-id "main")]
                              :mappings {(:external-warehouse-id hub) (assoc (mapping hub "main") :state "removed")})
                     [:events 0 :data :mapping-revision]))
        "a removed mapping is mapped again with the next revision")))


(defn new-after
  "The suggestion for FBO warehouse Hub and the warehouse its confirm registers, given `earlier` warehouses."
  {:malli/schema [:=> [:cat [:sequential :map]] [:tuple :map :map]]}
  [earlier]
  (let [hub        (external "fbo" "1" :name "Hub")
        warehouses (into [main] earlier)
        suggestion (:suggestion (first (:rows (read-handler ::warehouses/unmatched-external-warehouses
                                                            {:externals [hub] :warehouses warehouses} {:connection-id c}))))
        plan       (confirm [hub] [(row hub :warehouse-name (:new-name suggestion))] :warehouses warehouses)]
    [suggestion (:data (first (:events plan)))]))


(deftest new-warehouse-after-the-earlier-one-is-archived
  (let [[suggestion registered] (new-after [(warehouse "listing-warehouse:c-1:fbo:1" "Hub" :status "archived")])]
    (is (= {:new-name "Hub · Main shop"} suggestion) "the archived warehouse is not suggested; it keeps its name")
    (is (= {:warehouse-id "listing-warehouse:c-1:fbo:1:2" :name "Hub · Main shop" :status "active" :revision 1} registered)
        "the new warehouse takes the next free id")))


(deftest new-warehouse-after-the-earlier-one-is-deleted
  (let [[suggestion registered] (new-after [(warehouse "listing-warehouse:c-1:fbo:1" "Hub" :status "deleted")])]
    (is (= {:new-name "Hub"} suggestion) "a deleted warehouse holds no name")
    (is (= "listing-warehouse:c-1:fbo:1:2" (:warehouse-id registered))))
  (testing "every earlier one archived or deleted"
    (is (= "listing-warehouse:c-1:fbo:1:3"
           (:warehouse-id (second (new-after [(warehouse "listing-warehouse:c-1:fbo:1" "Hub" :status "deleted")
                                              (warehouse "listing-warehouse:c-1:fbo:1:2" "Hub 2" :status "archived")]))))))
  (testing "an active later one is suggested and stays the only one"
    (let [hub     (external "fbo" "1" :name "Hub")
          earlier [main (warehouse "listing-warehouse:c-1:fbo:1" "Hub" :status "deleted") (warehouse "listing-warehouse:c-1:fbo:1:2" "Hub 2")]]
      (is (= {:warehouse-id "listing-warehouse:c-1:fbo:1:2"}
             (:suggestion (first (:rows (read-handler ::warehouses/unmatched-external-warehouses
                                                      {:externals [hub] :warehouses earlier} {:connection-id c}))))))
      (is (= [["c-1:fbo:1" :warehouse-exists]]
             (offending #(confirm [hub] [(row hub :warehouse-name "Hub 3")] :warehouses earlier)))))))


;; Slice 4 · Change Warehouse Mapping


(defn change
  {:malli/schema [:=> [:cat :map :map [:* :any]] :map]}
  [external input & {:as facts}]
  (decide ::warehouses/change-warehouse-mapping
          (merge {:externals  (by-id [external])                 :mappings {(:external-warehouse-id external) (mapping external "main")}
                  :warehouses [main (warehouse "spare" "Spare")]}
                 facts)
          (merge {:external-warehouse-id (:external-warehouse-id external) :mapping-revision 1} input)))


(deftest change-points-at-another-warehouse-or-removes
  (let [hub (external "fbo" "1" :name "Hub")]
    (is (= {:external-warehouse-id "c-1:fbo:1" :mapping-revision-id "c-1:fbo:1#2" :connection-id c            :warehouse-type "fbo"
            :change                "changed"   :mapping-revision    2             :changed-by    (str member) :changed-at     at
            :previous-warehouse-id "main"      :warehouse-id        "spare"       :state         "confirmed"}
           (get-in (change hub {:warehouse-id "spare"}) [:events 0 :data])))
    (is (= {:previous-warehouse-id "main" :state "removed"}
           (select-keys (get-in (change hub {}) [:events 0 :data]) [:previous-warehouse-id :warehouse-id :state]))
        "a blank warehouse removes the mapping")
    (is (= accepted-fx (:fx (change hub {}))))
    (is (empty? (:events (change hub {:warehouse-id "main"}))) "the same warehouse changes nothing")))


(deftest change-rejections
  (let [hub   (external "fbo" "1" :name "Hub")
        other (external "fbs" "7" :name "Seller")]
    (is (= :stale (rejection #(change hub {:mapping-revision 2 :warehouse-id "spare"}))))
    (is (= :stale (rejection #(change hub {:warehouse-id "spare"} :mappings {}))))
    (is (= :warehouse-not-available (rejection #(change hub {:warehouse-id "gone"}))))
    (is (= [["c-1:fbo:1" :already-used]]
           (offending #(change hub {:warehouse-id "spare"}
                               :mappings {(:external-warehouse-id hub) (mapping hub "main") (:external-warehouse-id other) (mapping other "spare")}
                               :named {(:external-warehouse-id other) other})))
        "a new target obeys Confirm Warehouse Mapping")))


;; Slices 3 and 5 · Warehouse Mappings


(deftest every-warehouse-with-its-mapping
  (let [hub    (external "fbo" "1" :name "Hub")
        seller (external "fbs" "2" :name "Seller")
        rows   (:rows (read-handler ::warehouses/current-warehouse-mappings
                                    {:externals [hub seller] :mappings {(:external-warehouse-id hub) (mapping hub "main")}}
                                    {:connection-id c}))]
    (is (= [["c-1:fbs:2" "unmatched"] ["c-1:fbo:1" "confirmed"]] (mapv (juxt :external-warehouse-id :state) rows)))
    (is (= {:warehouse        {:warehouse-id "main" :name "Main" :archived? false}
            :mapping-revision 1
            :confirmed-by     (str member)
            :confirmed-at     (Instant/parse at)}
           (select-keys (second rows) [:warehouse :mapping-revision :confirmed-by :confirmed-at])))))


;; Through a store


(defn observed
  {:malli/schema [:=> [:cat :string :string [:maybe :string] [:* :any]] :map]}
  [type source-id name & {:keys [connection-id] :or {connection-id c}}]
  (let [id (observations/warehouse-id connection-id type source-id)]
    (cond-> {:external-warehouse-id id :entity-id id :connection-id connection-id :source-id source-id :revision 1
             :payload-hash          (apply str (repeat 64 "a"))            :adapter-version "test" :observed-at at :last-observed-at at
             :source-revision-id    (str id "#1") :warehouse-type type :active true
             :facts                 (observations/write-json {:name name})}
      name (assoc :name name))))


(deftest confirm-and-change-through-the-store
  (support/with-store
    (fn [repo _]
      (let [ctx     (listings/context repo)
            execute #(runtime/execute! ctx %1 {:command-id %2 :input %3})
            read    #(:rows (runtime/read-model ctx %1 {:connection-id %2}))
            hub     (observations/warehouse-id c "fbo" "1")
            seller  (observations/warehouse-id c "fbs" "2")]
        (listings/seed! ctx :tover.catalogues.warehouses.model/warehouse-registered (warehouse "main" "Main"))
        (listings/seed! ctx ::sync/external-warehouse-observed (observed "fbo" "1" "Hub"))
        (listings/seed! ctx ::sync/external-warehouse-observed (observed "fbs" "2" nil))
        (listings/seed! ctx ::sync/external-warehouse-observed (observed "fbo" "3" "Elsewhere" :connection-id other-c))
        (is (= 2 (connections/count-part :unmatched-warehouses ctx c)))
        (is (= [[seller {:new-name "2"}] [hub {:new-name "Hub"}]]
               (mapv (juxt :external-warehouse-id :suggestion) (read ::warehouses/unmatched-external-warehouses c))))

        (testing "one confirm registers the new warehouse and maps both"
          (execute ::warehouses/confirm-warehouse-mapping "confirm-1"
                   {:rows [{:external-warehouse-id hub :revision 1 :warehouse-name "Hub"}
                           {:external-warehouse-id seller :revision 1 :warehouse-id "main"}]})
          (is (= 0 (connections/count-part :unmatched-warehouses ctx c)))
          (is (= [] (read ::warehouses/unmatched-external-warehouses c)))
          (is (= {:warehouse-id "listing-warehouse:c-1:fbo:1" :name "Hub" :status "active" :revision 1}
                 (select-keys (some #(when (= "Hub" (:name %)) %) (runtime/coeffect :tover.catalogues.warehouses.model/all ctx {}))
                              [:warehouse-id :name :status :revision])))
          (is (= [[hub "listing-warehouse:c-1:fbo:1"] [seller "main"]]
                 (sort (mapv (juxt :external-warehouse-id (comp :warehouse-id :warehouse)) (read ::warehouses/current-warehouse-mappings c))))))

        (testing "the FBO rule holds across connections"
          (is (= :already-used
                 (rejection #(execute ::warehouses/confirm-warehouse-mapping "confirm-2"
                                      {:rows [{:external-warehouse-id (observations/warehouse-id other-c "fbo" "3") :revision 1
                                               :warehouse-id          "listing-warehouse:c-1:fbo:1"}]})))))

        (testing "removing returns the warehouse to unmatched; confirming again maps it in place"
          (execute ::warehouses/change-warehouse-mapping "change-1" {:external-warehouse-id seller :mapping-revision 1})
          (is (= [seller] (mapv :external-warehouse-id (read ::warehouses/unmatched-external-warehouses c))))
          (is (= 1 (connections/count-part :unmatched-warehouses ctx c)))
          (execute ::warehouses/confirm-warehouse-mapping "confirm-3" {:rows [{:external-warehouse-id seller :revision 1 :warehouse-id "main"}]})
          (is (= [3 "main"] ((juxt :mapping-revision (comp :warehouse-id :warehouse))
                             (some #(when (= seller (:external-warehouse-id %)) %) (read ::warehouses/current-warehouse-mappings c)))))
          (is (= [["mapped" nil "main"] ["changed" "main" nil] ["mapped" nil "main"]]
                 (mapv (juxt :change :previous-warehouse-id :warehouse-id)
                       (sort-by :mapping-revision (vals (sync/load-rows ctx ::warehouses/warehouse-mapping-revisions
                                                                        (map #(warehouses/revision-id seller %) [1 2 3]))))))))

        (testing "the connection status counts unmatched warehouses"
          (is (= 1 (get (runtime/coeffect ::connections/counts ctx {:connection-id other-c}) :unmatched-warehouses))))))))
