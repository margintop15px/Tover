(ns tover.marketplace.provenance-test
  "Board chapters 44 and 14 · Cutover entries and baselines as inventory reads them: a baseline per accepted scope,
  fixed instants, items and warehouses, and discarding a pending cutover entry."
  (:require
    [bel.core :as bel :refer [defcommand defreadmodel]]
    [bel.core-test :as bel-test]
    [bel.runtime :as runtime]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [tover.catalogues.records :as records]
    [tover.inventory.acceptance.model :as acceptance]
    [tover.inventory.entries.model :as entries]
    [tover.inventory.valuation :as valuation]
    [tover.marketplace.provenance :as provenance]
    [tover.marketplace.sync.model :as sync]
    [tover.support :as support])
  (:import
    [java.time Instant]))


(use-fixtures :each bel-test/isolated-registry
  (fn [f]
    (bel/build-registry! ['tover.shared.web.core 'tover.catalogues.warehouses.model 'tover.catalogues.suppliers.model
                          'tover.catalogues.stores.model 'tover.catalogues.categories.model
                          'tover.catalogues.products.model 'tover.inventory.stock-items.model
                          'tover.inventory.entries.model 'tover.inventory.acceptance.model
                          'tover.workspaces.currency.model 'tover.marketplace.provenance 'tover.marketplace.provenance-test])
    (f)))


(def workspace (:workspace-id support/scope))
(def member #uuid "00000000-0000-0000-0000-000000000011")


(defn- day
  {:malli/schema [:=> [:cat :int] :any]}
  [n]
  (Instant/parse (format "2026-11-%02dT00:00:00Z" n)))


(def catalogue
  {:products    [{:product-id "mug" :name "Mug" :status "active" :revision 1}]
   :stock-items [{:stock-item-id "red" :product-id "mug" :variant-label "red" :unit "pcs" :condition "regular" :status "active" :revision 1}
                 {:stock-item-id "blue" :product-id "mug" :variant-label "blue" :unit "pcs" :condition "regular" :status "active" :revision 1}]
   :barcodes    []
   :warehouses  [{:warehouse-id "main" :name "Main" :status "active" :revision 1} {:warehouse-id "back" :name "Back" :status "active" :revision 1}]
   :suppliers   []
   :stores      [{:store-id "web" :name "Web" :status "active" :revision 1}]
   :categories  []})


(def references
  {:warehouses  [{:id "main" :status "active"} {:id "back" :status "active"}]
   :products    [{:id "mug" :status "active"}]
   :stock-items [{:id "red" :status "active" :condition "regular"} {:id "blue" :status "active" :condition "regular"}]
   :suppliers   []
   :stores      [{:id "web" :status "active"}]})


(def cutover-id "cutover:c1|red|main#1")


(def cutover-row
  {:entry-id    cutover-id :connection-id "c1"         :stock-item-id "red"                  :warehouse-id "main" :instant "2026-11-10T00:00:00Z"
   :cost-source "Ozon"     :proposed-by   (str member) :proposed-at   "2026-11-19T00:00:00Z"})


(defn- baseline-of
  {:malli/schema [:=> [:cat :string :string] :map]}
  [record-id instant]
  {:baseline-id    "c1|red|main"          :connection-id "c1" :stock-item-id "red" :warehouse-id "main" :instant instant :record-id record-id
   :established-at "2026-11-19T00:00:00Z"})


(defn- record
  "One accepted revision on day `d` with `[item warehouse change & {...}]` lines; a correction line counts."
  {:malli/schema [:=> [:cat :string :int :string :int [:* :any]] :map]}
  [id revision kind d & lines]
  (let [rid (acceptance/record-revision-id id revision)]
    {:record {:record-revision-id rid :record-id id    :revision    revision :acceptance-id (str "a-" rid) :kind kind :occurred-at (day d)
              :sequence           0   :cancelled false :recorded-at (day d)  :actor         member}
     :lines  (vec (map-indexed (fn [i [item warehouse n more]]
                                 (merge {:record-line-id (acceptance/record-line-id rid i) :record-revision-id rid :position i
                                         :stock-item-id  item :warehouse-id warehouse}
                                        (if (= "correction" kind) {:counted-quantity n} {:quantity-change n})
                                        more))
                               lines))}))


(defn- history
  {:malli/schema [:=> [:cat [:* :map]] :map]}
  [& records]
  {:records (mapv :record records) :lines (vec (mapcat :lines records))})


(def opening (record "o" 1 "opening" 1 ["red" "main" 120 {:unit-cost "350.00"}]))


(def pending-cutover
  {:entry-id cutover-id :kind "opening" :occurred-at (day 10) :status "pending" :revision 1 :saved-by member
   :lines    [{:line-id "l" :stock-item-id "red" :warehouse-id "main" :quantity 120 :unit-cost "350.00"}]})


(defn- facts
  {:malli/schema [:=> [:cat :map [:maybe :map] [:* :map]] :map]}
  [history provenance & entries]
  {:scope   {:workspace-id workspace} :actor        member :now        (day 20)   :command-id "c" :entries (vec entries) :catalogue catalogue
   :history history                   :currency-row nil    :provenance provenance})


(defn- selected
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [entries]}]
  {:selection (mapv #(select-keys % [:entry-id :revision]) entries) :order (mapv :entry-id entries)})


(defn- review
  {:malli/schema [:=> [:cat :map] :map]}
  [facts]
  (acceptance/review (assoc (acceptance/review-input facts) :now (:now facts)) (selected facts)))


(defn- accept
  {:malli/schema [:=> [:cat :map] :map]}
  [facts]
  (runtime/decide ::acceptance/accept-reviewed-inventory-changes facts
                  (merge (selected facts) {:workspace-id workspace :acceptance-id "acc" :fingerprint (:fingerprint (review facts))})))


(defn- of-type
  {:malli/schema [:=> [:cat :map :keyword] [:vector :map]]}
  [plan type]
  (into [] (comp (filter #(= type (:type %))) (map :data)) (:events plan)))


(defn- failure
  {:malli/schema [:=> [:cat fn?] [:maybe :map]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (assoc (ex-data error) :message (ex-message error)))))


(defn- message
  {:malli/schema [:=> [:cat fn?] [:maybe :string]]}
  [f]
  (:message (failure f)))


(defn- save
  "Save `input` with the facts in `more`; by default the accepted opening `o` is the scope's baseline."
  {:malli/schema [:function [:=> [:cat :map] :map] [:=> [:cat :map :map] :map]]}
  ([input] (save {} input))
  ([more input]
   (runtime/decide ::entries/save-shared-inventory-entry
                   (merge {:scope      {:workspace-id workspace} :actor member :now (day 20) :entry nil :lines [] :changes [] :references references
                           :pending    [] :history (history opening)
                           :provenance {:cutover-entries {} :baselines {"c1|red|main" (baseline-of "o" "2026-11-01T00:00:00Z")}}}
                          more)
                   (merge {:workspace-id workspace :entry-id "x" :kind "opening" :amends "o#1" :occurred-at "2026-11-01T00:00:00Z"
                           :lines        [{:line-id "o#1-0" :stock-item-id "red" :warehouse-id "main" :quantity "120" :unit-cost "350.00"}]}
                          (cond-> input (contains? input :occurred-at) (update :occurred-at #(some-> % str)))))))


(deftest counts-from-the-cutover
  (let [f    (facts {:records [] :lines []} {:cutover-entries {cutover-id cutover-row} :baselines {}} pending-cutover)
        plan (accept f)]
    (is (= [{:baseline-id "c1|red|main" :connection-id  "c1"                   :stock-item-id "red" :warehouse-id "main" :instant "2026-11-10T00:00:00Z"
             :record-id   cutover-id    :established-at "2026-11-20T00:00:00Z"}]
           (of-type plan ::provenance/cutover-baseline-established))
        "the accepted opening is the scope's baseline, in the same commit")
    (is (= [cutover-id] (map :record-id (of-type plan ::acceptance/inventory-record-accepted))))
    (is (some #{[::sync/mark-dirty {:connection-id "c1"}]} (:fx plan)) "the scope's operations are evaluated again")
    (testing "a sale the next day counts from it"
      (let [h (-> (history (record "s" 1 "sale" 11 ["red" "main" -3 {:unit-price "500.00"}]))
                  (update :records into (map #(assoc % :recorded-at (day 20)) (of-type plan ::acceptance/inventory-record-accepted)))
                  (update :lines into (of-type plan ::acceptance/inventory-record-line-accepted)))]
        (is (= 117 (get-in (valuation/replay (:records h) (:lines h)) [:balances ["red" "main"] :quantity])))))))


(deftest moving-the-date
  (is (= "Cutover date is fixed" (message #(save {:amends "o#1" :occurred-at (day 2)}))))
  (testing "nor its warehouse or stock item"
    (is (= [:validation :warehouse-id "Cutover date is fixed"]
           ((juxt :type :field :message) (failure #(save {:lines [{:line-id "o#1-0" :stock-item-id "red" :warehouse-id "back" :quantity "120" :unit-cost "350.00"}]})))))
    (is (= [:validation :stock-item-id "Cutover date is fixed"]
           ((juxt :type :field :message) (failure #(save {:lines [{:line-id "o#1-0" :stock-item-id "blue" :warehouse-id "main" :quantity "120" :unit-cost "350.00"}]}))))))
  (testing "nothing is accepted"
    (let [moved (merge (:facts (entries/latest-revision (history opening) "o#1"))
                       {:entry-id "x" :amends "o#1" :status "pending" :revision 1 :occurred-at (day 2)})
          f     (facts (history opening) {:cutover-entries {} :baselines {"c1|red|main" (baseline-of "o" "2026-11-01T00:00:00Z")}} moved)]
      (is (= [:cutover-date-fixed] (map :code (:problems (review f)))))
      (is (= [:cutover-date-fixed "Cutover date is fixed"] ((juxt :type :message) (failure #(accept f))))))))


(deftest pending-cutover-entry-keeps-its-scope
  (let [more  {:entry      pending-cutover :lines (:lines pending-cutover) :history {:records [] :lines []}
               :provenance {:cutover-entries {cutover-id cutover-row} :baselines {}}}
        input (fn [line & {:as entry}]
                (merge {:entry-id cutover-id :amends nil :expected-revision 1 :occurred-at "2026-11-10T00:00:00Z"
                        :lines    [(merge {:line-id "l" :stock-item-id "red" :warehouse-id "main" :quantity "120" :unit-cost "350.00"} line)]}
                       entry))
        field (fn [i] (let [{:keys [field message]} (failure #(save more i))] [field message]))]
    (is (= [:stock-item-id "Cutover date is fixed"] (field (input {:stock-item-id "blue"}))))
    (is (= [:warehouse-id "Cutover date is fixed"] (field (input {:warehouse-id "back"}))))
    (is (= [:occurred-at "Cutover date is fixed"] (field (input {} :occurred-at "2026-11-11T00:00:00Z"))))
    (is (= "pending" (get-in (save more (input {} :occurred-at "2026-11-10T03:00:00+03:00")) [:events 0 :data :status]))
        "an equal instant in another offset is the same date")
    (testing "quantity and cost are saved"
      (is (= [[110 "300.00"]]
             (map (juxt :quantity :unit-cost) (of-type (save more (input {:quantity "110" :unit-cost "300.00"})) ::entries/inventory-entry-line-saved)))))))


(deftest wrong-count
  (let [p     {:cutover-entries {} :baselines {"c1|red|main" (baseline-of "o" "2026-11-01T00:00:00Z")}}
        h     (history opening (record "s" 1 "sale" 5 ["red" "main" -3 {:unit-price "500.00"}]))
        _     (is (= ["pending" "o#1"] ((juxt :status :amends) (get-in (save {:lines [{:line-id "o#1-0" :stock-item-id "red" :warehouse-id "main" :quantity "110" :unit-cost "350.00"}]}) [:events 0 :data]))))
        amend (-> (:facts (entries/latest-revision h "o#1"))
                  (assoc-in [:lines 0 :quantity] 110)
                  (merge {:entry-id "x" :amends "o#1" :status "pending" :revision 1}))
        plan  (accept (facts h p amend))]
    (is (= [["o#2" (day 1)]] (map (juxt :record-revision-id :occurred-at) (of-type plan ::acceptance/operation-amended)))
        "the instant is unchanged")
    (is (= [] (of-type plan ::provenance/cutover-baseline-established)) "the baseline stays as it was")
    (testing "the timeline is revalidated"
      (let [low (assoc-in amend [:lines 0 :quantity] 2)]
        (is (= :negative-balance (:type (failure #(accept (facts h p low))))))))))


(deftest baseline-correction-is-not-cancelled
  (let [correction (record "k" 1 "correction" 3 ["red" "main" 8])
        more       {:history    (history opening correction)
                    :provenance {:cutover-entries {} :baselines {"c1|red|main" (baseline-of "k" "2026-11-03T00:00:00Z")}}}]
    (is (= [:validation :cancel "A cutover baseline cannot be cancelled."]
           ((juxt :type :field :message) (failure #(save more {:kind "correction" :amends "k#1" :cancel true :occurred-at nil :lines []})))))
    (is (= [:validation :cancel "A cutover baseline cannot be cancelled."]
           ((juxt :type :field :message) (failure #(save {:amends "o#1" :cancel true :occurred-at nil :lines []}))))
        "nor an opening baseline")))


(deftest a-scope-has-one-baseline
  (let [p {:cutover-entries {cutover-id cutover-row} :baselines {"c1|red|main" (baseline-of "earlier" "2026-11-01T00:00:00Z")}}]
    (is (= [:baseline-exists] (map :code (:problems (review (facts {:records [] :lines []} p pending-cutover))))))))


(deftest acceptance-without-marketplace-facts-is-unchanged
  (let [entry {:entry-id "e" :kind "opening" :occurred-at (day 1) :status "pending" :revision 1
               :lines    [{:line-id "l" :stock-item-id "red" :warehouse-id "main" :quantity 5 :unit-cost "100.00"}]}
        plain (dissoc (facts {:records [] :lines []} nil entry) :provenance)
        none  (facts {:records [] :lines []} {:cutover-entries {} :baselines {}} entry)]
    (is (= (map (juxt :type :data) (:events (accept plain))) (map (juxt :type :data) (:events (accept none)))))
    (is (= (records/publish {:workspace-id workspace}) (:fx (accept plain))))
    (is (= (map (juxt :type :data) (:events (accept plain)))
           (map (juxt :type :data) (:events (acceptance/plan plain (merge (selected plain) {:workspace-id workspace :acceptance-id "acc" :fingerprint (:fingerprint (review plain))})
                                                             {:actor member}))))
        "the command is its plan")))


(deftest pure-rules
  (let [p {:cutover-entries {} :baselines {"c1|red|main" (baseline-of "o" "2026-11-01T00:00:00Z")}}]
    (is (= "o" (:record-id (provenance/baseline p "c1" "red" "main"))))
    (is (nil? (provenance/baseline p "c2" "red" "main")) "a baseline belongs to one connection")
    (is (nil? (provenance/baseline nil "c1" "red" "main")))
    (is (= [[::sync/mark-dirty {:connection-id "a"}] [::sync/mark-dirty {:connection-id "b"}]] (provenance/dirty-fx ["b" "a" "b" nil])))))


;; Through the store: a stand-in for the Task 4 proposal, which saves the entry and its cutover row together.


(defcommand propose-for-test
  {:input  [:map [:row provenance/CutoverEntry] [:entry entries/Entry] [:line entries/EntryLine]]
   :policy ::records/workspace-member
   :emits  #{::provenance/marketplace-cutover-proposed ::entries/shared-inventory-entry-saved ::entries/inventory-entry-line-saved}}
  [_ {:keys [row entry line]}]
  {:events [{:type ::provenance/marketplace-cutover-proposed :data row}
            {:type ::entries/shared-inventory-entry-saved :data entry}
            {:type ::entries/inventory-entry-line-saved :data line}]})


(defreadmodel facts-for-test
  {:input [:map] :output :map :policy ::records/workspace-member :cofx [[:provenance ::provenance/facts (constantly {})]]}
  [{:keys [provenance]} _]
  provenance)


(deftest discarding-frees-the-scope
  (with-redefs [runtime/with-authority (fn [context _touched _isolation read]
                                         (read (assoc context :principal (select-keys context [:user-id :memberships]))))]
    (support/with-store
      (fn [repo _]
        (let [ctx   (runtime/prepare-context {:bel/system  (support/bel-system {:repo repo})         :repo repo :workspace-id workspace :user-id member
                                              :memberships [{:workspace-id workspace :role :member}]})
              run!  (fn [command id input] (runtime/execute! ctx command {:command-id id :input input}))
              saved (dissoc pending-cutover :lines)]
          (run! ::propose-for-test "p1" {:row  cutover-row :entry saved
                                         :line {:entry-line-id (entries/entry-line-id cutover-id "l") :entry-id cutover-id :line-id "l" :position 0
                                                :stock-item-id "red" :warehouse-id "main" :quantity 120 :unit-cost "350.00"}})
          (is (= {cutover-id cutover-row} (:cutover-entries (runtime/read-model ctx ::facts-for-test {}))))
          (run! ::entries/discard-inventory-draft "d1" {:workspace-id workspace :entry-id cutover-id :revision 1})
          (is (= {:cutover-entries {} :baselines {}} (runtime/read-model ctx ::facts-for-test {})) "the scope is free again"))))))


(deftest two-cutover-entries-of-one-scope-are-refused
  (let [row      (fn [n] (assoc cutover-row :entry-id (str "cutover:c1|red|main#" n)))
        entry    (fn [n] {:entry-id (str "cutover:c1|red|main#" n) :kind "correction" :occurred-at (day 10) :status "pending" :revision 1
                          :saved-by member :lines [{:line-id "l" :stock-item-id "red" :warehouse-id "main" :quantity 8}]})
        p        {:cutover-entries {(:entry-id (row 1)) (row 1) (:entry-id (row 2)) (row 2)} :baselines {}}
        f        (facts (history (record "o" 1 "opening" 1 ["red" "main" 5 {:unit-cost "100.00"}])) p (entry 1) (entry 2))
        input    (assoc (selected f) :ordered? true)
        reviewed (acceptance/review (assoc (acceptance/review-input f) :now (:now f)) input)]
    (is (= #{[(:entry-id (row 1)) :baseline-exists] [(:entry-id (row 2)) :baseline-exists]}
           (set (map (juxt :entry-id :code) (:problems reviewed)))))
    (is (= :baseline-exists
           (:type (failure #(runtime/decide ::acceptance/accept-reviewed-inventory-changes f
                                            (merge input {:workspace-id workspace :acceptance-id "acc" :fingerprint (:fingerprint reviewed)}))))))))


(deftest cancelling-a-baseline-is-refused-at-acceptance
  (let [f (facts (history opening) {:cutover-entries {} :baselines {"c1|red|main" (baseline-of "o" "2026-11-01T00:00:00Z")}}
                 {:entry-id "x" :kind "opening" :amends "o#1" :cancel true :status "pending" :revision 1 :lines []})]
    (is (= [:baseline-cancelled] (map :code (:problems (review f)))))
    (is (= [:baseline-cancelled "A cutover baseline cannot be cancelled."] ((juxt :type :message) (failure #(accept f)))))))
