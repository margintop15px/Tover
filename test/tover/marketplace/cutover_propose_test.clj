(ns tover.marketplace.cutover-propose-test
  "Board chapter 44 · Propose the cutover balances (slice 2), Review the cutover and resulting corrections (slice 3)
  and Accept the reviewed cutover (slice 4): the proposal decided over given facts, then propose, review and accept
  through a store."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.runtime :as runtime]
    [charred.api :as charred]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [tover.catalogues.categories.model]
    [tover.catalogues.products.model]
    [tover.catalogues.stores.model]
    [tover.catalogues.suppliers.model]
    [tover.catalogues.warehouses.model]
    [tover.inventory.acceptance.model :as acceptance]
    [tover.inventory.entries.model :as entries]
    [tover.inventory.stock-items.model]
    [tover.marketplace.connections.model :as connections]
    [tover.marketplace.cutover.model :as cutover]
    [tover.marketplace.products-test :as listings]
    [tover.marketplace.provenance :as provenance]
    [tover.marketplace.sync.model :as sync]
    [tover.support :as support]
    [tover.workspaces.currency.model])
  (:import
    [java.time Instant]))


(defn- register
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (bel/build-registry! ['tover.catalogues.products.model 'tover.catalogues.stores.model 'tover.catalogues.warehouses.model
                        'tover.catalogues.suppliers.model 'tover.catalogues.categories.model
                        'tover.inventory.stock-items.model 'tover.inventory.entries.model 'tover.inventory.acceptance.model
                        'tover.marketplace.connections.model 'tover.marketplace.sync.model 'tover.marketplace.products.model
                        'tover.marketplace.warehouses.model 'tover.marketplace.provenance 'tover.marketplace.cutover.model
                        'tover.workspaces.currency.model])
  (f))


(use-fixtures :each bel-test/isolated-registry register listings/unit-authority)


(def c "c1")
(def snapshot "2026-11-20T10:00:00Z")
(def earliest "2026-11-01T00:00:00Z")
(def hub "c1:fbo:1")
(def manager #uuid "00000000-0000-0000-0000-0000000000a1")


(defn- day
  {:malli/schema [:=> [:cat :int] :any]}
  [n]
  (Instant/parse (format "2026-11-%02dT00:00:00Z" n)))


(defn- figure
  {:malli/schema [:=> [:cat :string :string :int] :map]}
  [listing warehouse present]
  {:run-id "r1" :external-product-id listing :external-warehouse-id warehouse :fulfilment-type "fbo" :present present})


(defn- product-mapping
  {:malli/schema [:=> [:cat :string [:* :any]] :map]}
  [item & {:keys [connection-id revision] :or {connection-id c revision 1}}]
  {:connection-id connection-id :state "confirmed" :mapping-revision revision :stock-item-id item})


(def positions
  (into {} (map (fn [d] [d {:domain d :first-covered-at earliest}])) cutover/operation-domains))


(def opened-blue
  "Blue has balance 10 in main: an opening of day 1."
  {:records [{:record-revision-id "o#1"   :record-id "o" :revision 1 :kind "opening" :occurred-at (day 1) :sequence 0 :cancelled false
              :recorded-at        (day 1)}]
   :lines   [{:record-revision-id "o#1" :position 0 :stock-item-id "blue" :warehouse-id "main" :quantity-change 10 :unit-cost "100.00"}]})


(def base
  "Red (listing p1) has no history; blue (listing p2) has balance 10. Both are in the hub, mapped to main."
  {:currency           "RUB"
   :passes             [{:run-id "r1" :observed-at snapshot :asked #{"c1:p1" "c1:p2"}}]
   :figures            [(figure "c1:p1" hub 120) (figure "c1:p2" hub 8)]
   :listings           [{:external-product-id "c1:p1" :cost "350" :cost-currency "RUB"} {:external-product-id "c1:p2"}]
   :product-mappings   {"c1:p1" (product-mapping "red") "c1:p2" (product-mapping "blue")}
   :warehouse-mappings {hub {:connection-id c :warehouse-id "main" :warehouse-type "fbo" :state "confirmed" :mapping-revision 1}}
   :history            opened-blue
   :provenance         {:cutover-entries {} :baselines {}}
   :positions          positions
   :readable           (set cutover/operation-domains)})


(defn- facts
  {:malli/schema [:=> [:cat [:* :any]] :map]}
  [& {:keys [cutover manager? proposals now] :or {cutover base manager? true proposals [] now (day 26)}}]
  {:scope     support/scope        :actor manager :now now
   :facts     cutover
   :viewer    {:manager? manager?}
   :proposals proposals})


(defn- scope
  {:malli/schema [:=> [:cat :string :int [:* :any]] :map]}
  [item quantity & {:as extra}]
  (merge {:stock-item-id             item :warehouse-id "main"
          :product-mapping-revisions {(if (= "red" item) "c1:p1" "c1:p2") 1} :warehouse-mapping-revisions {hub 1}
          :quantity                  quantity}
         extra))


(defn- propose
  {:malli/schema [:=> [:cat :map [:sequential :map] [:* :any]] :map]}
  [f scopes & {:keys [instant] :or {instant snapshot}}]
  (runtime/decide ::cutover/propose-marketplace-cutover f {:connection-id c :instant instant :scopes (vec scopes)}))


(defn- failure
  {:malli/schema [:=> [:cat [:fn fn?]] [:maybe :map]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (assoc (ex-data e) :message (ex-message e)))))


(defn- of-type
  {:malli/schema [:=> [:cat :map :keyword] [:vector :map]]}
  [result type]
  (filterv #(= type (:type %)) (:events result)))


;; Propose the cutover balances


(deftest opening-and-correction
  (testing "Given item A without history and item B with balance 10, when the manager proposes A 120 at cost 350 and B 8"
    (let [result (propose (facts) [(scope "red" 120 :unit-cost "350") (scope "blue" 8)])
          saved  (of-type result ::entries/shared-inventory-entry-saved)
          lines  (of-type result ::entries/inventory-entry-line-saved)
          rows   (of-type result ::provenance/marketplace-cutover-proposed)
          red    "cutover:c1|red|main#1"
          blue   "cutover:c1|blue|main#1"]
      (testing "one opening and one correction are pending"
        (is (= [[red "opening" "pending" 1] [blue "correction" "pending" 1]]
               (mapv (comp (juxt :entry-id :kind :status :revision) :data) saved)))
        (is (= [(Instant/parse snapshot) (Instant/parse snapshot)] (mapv (comp :occurred-at :data) saved)))
        (is (= [[red "red" "main" 120 "350"] [blue "blue" "main" 8 nil]]
               (mapv (comp (juxt :entry-id :stock-item-id :warehouse-id :quantity :unit-cost) :data) lines))))
      (testing "the observation is frozen with each entry as evidence"
        (is (= [[red c "red" "main" snapshot "Ozon" (str manager)] [blue c "blue" "main" snapshot nil (str manager)]]
               (mapv (comp (juxt :entry-id :connection-id :stock-item-id :warehouse-id :instant :cost-source :proposed-by) :data) rows)))
        (is (= [{:run-id "r1" :observed-quantity 120 :observed-at snapshot}
                {:run-id "r1" :observed-quantity 8 :observed-at snapshot :balance 10}]
               (mapv #(charred/read-json (get-in % [:data :observation]) :key-fn keyword) rows))))
      (testing "and stock is unchanged: nothing is accepted"
        (is (= #{::entries/shared-inventory-entry-saved ::entries/inventory-entry-line-saved ::provenance/marketplace-cutover-proposed}
               (set (map :type (:events result)))))))))


(deftest cost-missing
  (testing "Given an opening scope without a cost, nothing is proposed and the row is marked"
    (let [error (failure #(propose (facts) [(scope "red" 120) (scope "blue" 8)]))]
      (is (= :cost-required (:type error)))
      (is (= [["red" "main" :cost-required]] (mapv (juxt :stock-item-id :warehouse-id :code) (:rows error)))))
    (testing "a correction needs no cost"
      (is (= 1 (count (of-type (propose (facts) [(scope "blue" 8)]) ::entries/shared-inventory-entry-saved)))))))


(deftest several-batches
  (let [first-batch (propose (facts) [(scope "red" 120 :unit-cost "350")])
        row         (:data (first (of-type first-batch ::provenance/marketplace-cutover-proposed)))
        pending     (assoc-in base [:provenance :cutover-entries (:entry-id row)] row)]
    (testing "a later batch proposes the scopes left"
      (is (= ["cutover:c1|blue|main#1"]
             (mapv (comp :entry-id :data) (of-type (propose (facts :cutover pending) [(scope "blue" 8)]) ::entries/shared-inventory-entry-saved)))))
    (testing "after its entry is discarded a scope is proposed again under the next number"
      (is (= ["cutover:c1|red|main#2"]
             (mapv (comp :entry-id :data)
                   (of-type (propose (facts :proposals ["cutover:c1|red|main#1"]) [(scope "red" 110 :unit-cost "350")])
                            ::entries/shared-inventory-entry-saved)))))))


(deftest instant-before-first-coverage-names-the-earliest
  (let [error (failure #(propose (facts) [(scope "blue" 8)] :instant "2026-10-31T00:00:00Z"))]
    (is (= :instant-out-of-range (:type error)))
    (is (= earliest (:earliest error)))
    (is (re-find #"2026-11-01" (:message error))))
  (testing "without coverage of every readable operation domain nothing can be proposed"
    (is (= :instant-out-of-range
           (:type (failure #(propose (facts :cutover (assoc base :positions (dissoc positions "returns"))) [(scope "blue" 8)])))))))


(deftest future-instant
  (is (= :instant-out-of-range (:type (failure #(propose (facts :now (day 20)) [(scope "blue" 8)] :instant "2026-11-21T00:00:00Z")))))
  (is (= :validation (:type (failure #(propose (facts) [(scope "blue" 8)] :instant "tomorrow"))))))


(deftest comma-cost
  (testing "comma decimals parse like an opening's cost"
    (is (= ["350.5"] (mapv (comp :unit-cost :data)
                           (of-type (propose (facts) [(scope "red" 120 :unit-cost "350,5")]) ::entries/inventory-entry-line-saved)))))
  (testing "more than 4 places is refused on the row"
    (let [error (failure #(propose (facts) [(scope "red" 120 :unit-cost "350,12345")]))]
      (is (= :validation (:type error)))
      (is (= [["red" :invalid-cost]] (mapv (juxt :stock-item-id :code) (:rows error))))))
  (testing "a typed cost other than the suggestion keeps no cost source"
    (is (= [nil] (mapv (comp :cost-source :data)
                       (of-type (propose (facts) [(scope "red" 120 :unit-cost "300")]) ::provenance/marketplace-cutover-proposed))))))


(deftest member-may-accept-but-not-propose
  (is (= :not-a-manager (:type (failure #(propose (facts :manager? false) [(scope "blue" 8)])))))
  (testing "acceptance asks only for an active member (accepted by a member in the store test below)"
    (is (= :tover.catalogues.records/workspace-member
           (:policy (get-in (bel/operations) [:commands ::acceptance/accept-reviewed-inventory-changes]))))))


(deftest scope-already-pending-or-baselined
  (let [row      {:entry-id    "cutover:c1|blue|main#1" :connection-id c        :stock-item-id "blue" :warehouse-id "main" :instant snapshot
                  :proposed-by (str manager)            :proposed-at   snapshot}
        baseline {:baseline-id  (provenance/baseline-id c "blue" "main") :connection-id c :stock-item-id "blue"
                  :warehouse-id "main" :instant snapshot :record-id "o" :established-at snapshot}]
    (testing "a pending cutover entry is named"
      (let [error (failure #(propose (facts :cutover (assoc-in base [:provenance :cutover-entries (:entry-id row)] row)) [(scope "blue" 8)]))]
        (is (= :scope-not-ready (:type error)))
        (is (= [["blue" "pending" "cutover:c1|blue|main#1"]] (mapv (juxt :stock-item-id :readiness :pending-entry) (:rows error))))))
    (testing "a baseline is named"
      (is (= [["blue" "has baseline"]]
             (mapv (juxt :stock-item-id :readiness)
                   (:rows (failure #(propose (facts :cutover (assoc-in base [:provenance :baselines (:baseline-id baseline)] baseline))
                                             [(scope "blue" 8)])))))))
    (testing "the missing match is named"
      (is (= [["red" "needs product match"]]
             (mapv (juxt :stock-item-id :readiness)
                   (:rows (failure #(propose (facts :cutover (update base :product-mappings dissoc "c1:p1")) [(scope "red" 120 :unit-cost "1")])))))))
    (testing "all scopes are proposed together or none"
      (is (= :scope-not-ready
             (:type (failure #(propose (facts :cutover (assoc-in base [:provenance :cutover-entries (:entry-id row)] row))
                                       [(scope "red" 120 :unit-cost "350") (scope "blue" 8)]))))))))


(deftest mapping-changed-since-review
  (is (= [["blue" :stale]]
         (mapv (juxt :stock-item-id :code) (:rows (failure #(propose (facts) [(scope "blue" 8 :product-mapping-revisions {"c1:p2" 2})]))))))
  (is (= :stale (:type (failure #(propose (facts) [(scope "blue" 8 :warehouse-mapping-revisions {})])))))
  (is (= :stale (:type (failure #(propose (facts) [(scope "blue" 8 :observation "r0")]))))))


(deftest second-connection-same-scope
  (testing "Two connections share a warehouse: one opening only, the second is refused"
    (let [other {:entry-id    "cutover:c2|red|main#1" :connection-id "c2"     :stock-item-id "red" :warehouse-id "main" :instant snapshot
                 :proposed-by (str manager)           :proposed-at   snapshot}
          error (failure #(propose (facts :cutover (assoc-in base [:provenance :cutover-entries (:entry-id other)] other))
                                   [(scope "red" 120 :unit-cost "350")]))]
      (is (= :scope-not-ready (:type error)))
      (is (= [["red" "pending" "cutover:c2|red|main#1"]] (mapv (juxt :stock-item-id :readiness :pending-entry) (:rows error)))))))


;; Review the cutover and resulting corrections


(deftest review-shows-observed-proposed-and-difference
  (let [rows    {"cutover:c1|blue|main#1" {:entry-id    "cutover:c1|blue|main#1" :connection-id c :stock-item-id "blue" :warehouse-id "main"
                                           :instant     snapshot                 :observation   "{\"run-id\":\"r1\",\"observed-quantity\":8,\"observed-at\":\"2026-11-20T10:00:00Z\",\"balance\":10}"
                                           :proposed-by (str manager)            :proposed-at   snapshot}}
        pending [{:entry-id "cutover:c1|blue|main#1" :kind "correction" :lines [{:line-id "line" :stock-item-id "blue" :warehouse-id "main" :quantity 7}]}
                 {:entry-id "other" :kind "opening" :lines [{:line-id "l" :stock-item-id "red" :warehouse-id "main" :quantity 1}]}]]
    (is (= [{:entry-id          "cutover:c1|blue|main#1" :line-id "line" :stock-item-id "blue" :warehouse-id "main" :connection-id c
             :observed-quantity 8 :proposed-quantity              7      :balance       10     :difference   -3}]
           (provenance/review-rows rows pending)))))


;; Through the store: propose, review, accept


(defn- seed!
  {:malli/schema [:=> [:cat :map :keyword :map] :nil]}
  [ctx type data]
  (listings/seed! ctx type data))


(defn- context
  {:malli/schema [:=> [:cat :any :keyword] :map]}
  [repo role]
  (runtime/prepare-context {:bel/system  (support/bel-system {:repo repo})               :repo repo :workspace-id listings/workspace
                            :user-id     (if (= :member role) listings/member manager)
                            :memberships [{:workspace-id listings/workspace :role role}]}))


(defn seed-cutover!
  "Connection `cid` (seeded unless it exists): every operation domain first covered at `earliest`; its finished pass r1
  found red 120 and blue 8 at the hub; both are mapped, the hub to main."
  {:malli/schema [:=> [:cat :map :string :boolean] :nil]}
  [ctx cid connect?]
  (let [wh (str cid ":fbo:1")]
    (seed! ctx :tover.catalogues.warehouses.model/warehouse-registered {:warehouse-id "main" :name "Main" :status "active" :revision 1})
    (seed! ctx :tover.catalogues.products.model/product-registered {:product-id "mug" :name "Mug" :status "active" :revision 1})
    (doseq [item ["red" "blue"]]
      (seed! ctx :tover.inventory.stock-items.model/stock-item-registered
             {:stock-item-id item :product-id "mug" :variant-label item :unit "pcs" :condition "regular" :status "active" :revision 1}))
    (when connect?
      (seed! ctx ::connections/marketplace-account-connected
             {:connection-id    cid :workspace-id           (str listings/workspace) :provider     "ozon" :provider-account-id "123"
              :secret-ref       "secret" :account-currency  "RUB"
              :readable-domains (apply str (interpose " " (into ["products" "stock-by-warehouse"] cutover/operation-domains)))
              :sync-state       "active" :credentials-state "valid"                  :connected-by "m"    :connected-at        earliest}))
    (seed! ctx ::sync/marketplace-sync-started
           {:run-id             "r0" :connection-id cid      :trigger   "member" :triggered-by "m"
            :domains            (apply str (interpose " " cutover/operation-domains))
            :starting-positions "{}" :started-at    earliest :run-state "open"})
    (doseq [domain cutover/operation-domains]
      (seed! ctx ::sync/source-batch-recorded
             {:position-id (str cid "/" domain) :connection-id cid  :domain         domain :run-id           "r0" :cursor           "" :batch-key   ""
              :refetch-ids "[]"                 :finished      true :found-listings 0      :found-warehouses 0    :found-operations 0  :recorded-at earliest})
      (seed! ctx ::sync/source-domain-traversed
             {:run-id      "r0"                 :connection-id    cid    :domain           domain   :covered-until earliest           :traversed-at earliest
              :position-id (str cid "/" domain) :finished-domains domain :first-covered-at earliest :pass-id       (str "r0/" domain)}))
    (seed! ctx ::sync/marketplace-sync-started
           {:run-id             "r1" :connection-id cid      :trigger   "member" :triggered-by "m" :domains "stock-by-warehouse"
            :starting-positions "{}" :started-at    snapshot :run-state "open"})
    (seed! ctx ::sync/source-batch-recorded
           {:position-id (str cid "/stock-by-warehouse") :connection-id cid :domain "stock-by-warehouse" :run-id "r1" :cursor ""
            :batch-key   "" :refetch-ids "[]" :finished true :found-listings 2 :found-warehouses 0 :found-operations 0
            :recorded-at snapshot})
    (seed! ctx ::sync/source-domain-traversed
           {:run-id      "r1" :connection-id cid :domain "stock-by-warehouse" :covered-until snapshot :traversed-at snapshot
            :position-id (str cid "/stock-by-warehouse") :finished-domains "stock-by-warehouse" :first-covered-at snapshot
            :pass-id     "r1/stock-by-warehouse"         :asked            "[\"p1\",\"p2\"]"})
    (doseq [[p n item] [["p1" 120 "red"] ["p2" 8 "blue"]]
            :let       [listing (str cid ":" p)]]
      (seed! ctx ::sync/source-stock-observed
             {:stock-id            (str listing "|warehouse-stock|" wh "|r1") :connection-id cid :run-id "r1"
              :external-product-id listing :external-warehouse-id wh :source "warehouse-stock"                :fulfilment-type "fbo"
              :present             n :parts "[]" :observed-at snapshot})
      (seed! ctx :tover.marketplace.products.model/external-product-mapped
             {:external-product-id listing :mapping-revision-id (str listing "#1") :connection-id cid         :mapping-revision 1
              :changed-by          "m"     :changed-at          snapshot           :change        "mapped"    :stock-item-id    item :listing-revision 1
              :item-revision       1       :identity-basis      "{}"               :state         "confirmed"}))
    (seed! ctx :tover.marketplace.warehouses.model/external-warehouse-mapped
           {:external-warehouse-id wh :mapping-revision-id (str wh "#1") :connection-id cid      :warehouse-type "fbo"
            :mapping-revision      1  :changed-by          "m"           :changed-at    snapshot :change         "mapped" :warehouse-id "main" :state "confirmed"})))


(deftest propose-review-and-accept-through-the-store
  (support/with-store
    (fn [repo _]
      (let [admin  (context repo :admin)
            member (context repo :member)
            run!   (fn [ctx command id input] (runtime/execute! ctx command {:command-id id :input input}))
            cid    listings/c
            wh     (str cid ":fbo:1")]
        (seed-cutover! admin cid true)
        (run! admin ::entries/save-shared-inventory-entry "blue-opening"
              {:workspace-id listings/workspace :entry-id "o" :kind "opening" :occurred-at "2026-11-02T00:00:00Z"
               :lines        [{:line-id "l" :stock-item-id "blue" :warehouse-id "main" :quantity "10" :unit-cost "100"}]})
        (let [selection {:selection [{:entry-id "o" :revision 1}] :order ["o"]}
              review    (runtime/read-model admin ::acceptance/inventory-review selection)]
          (run! admin ::acceptance/accept-reviewed-inventory-changes "acc-0"
                (merge selection {:workspace-id listings/workspace :acceptance-id "acc-0" :fingerprint (:fingerprint review)})))
        (let [read   (runtime/read-model admin ::cutover/cutover-readiness-and-observed-balances {:connection-id cid})
              scopes (into {} (map (juxt :stock-item-id identity)) (:scopes read))
              input  {:connection-id cid :instant (:instant read)
                      :scopes        [{:stock-item-id               "red" :warehouse-id "main" :quantity "120" :unit-cost "350"
                                       :product-mapping-revisions   (get-in scopes ["red" :mapping-revisions :products])
                                       :warehouse-mapping-revisions (get-in scopes ["red" :mapping-revisions :warehouses])
                                       :observation                 "r1"}
                                      {:stock-item-id               "blue" :warehouse-id "main" :quantity "8"
                                       :product-mapping-revisions   (get-in scopes ["blue" :mapping-revisions :products])
                                       :warehouse-mapping-revisions {wh 1}}]}]
          (is (= [["blue" "ready" "correction" 10] ["red" "ready" "opening" nil]]
                 (mapv (juxt :stock-item-id :readiness :result :balance) (:scopes read))))
          (testing "a member may not propose"
            (is (= :not-a-manager (:type (failure #(run! member ::cutover/propose-marketplace-cutover "p0" input))))))
          (is (= :accepted (:status (run! admin ::cutover/propose-marketplace-cutover "p1" input))))
          (testing "a retry of the same attempt has no further effects"
            (is (= :accepted (:status (run! admin ::cutover/propose-marketplace-cutover "p1" input)))))
          (let [pending (:entries (runtime/read-model member ::entries/pending-entries {}))
                ids     (mapv :entry-id pending)]
            (is (= #{(str "cutover:" cid "|red|main#1") (str "cutover:" cid "|blue|main#1")} (set ids)))
            (is (= [["opening" 120] ["correction" 8]]
                   (mapv (juxt :kind (comp :quantity first :lines)) (sort-by :kind #(compare %2 %1) pending))))
            (testing "the scopes are pending, and stock is unchanged"
              (is (= ["pending" "pending"] (mapv :readiness (:scopes (runtime/read-model admin ::cutover/cutover-readiness-and-observed-balances {:connection-id cid})))))
              (is (= 2 (connections/count-part :scopes-without-baseline admin cid))))
            (testing "the review shows observed, proposed and the difference against Tover's balance"
              (is (= #{["red" 120 120 nil 120] ["blue" 8 8 10 -2]}
                     (set (map (juxt :stock-item-id :observed-quantity :proposed-quantity :balance :difference)
                               (:rows (runtime/read-model member ::provenance/cutover-review {})))))))
            (testing "a member accepts; each accepted scope gets its baseline in the same commit"
              (let [selection {:selection (mapv #(hash-map :entry-id (:entry-id %) :revision (:revision %)) pending) :order ids}
                    review    (runtime/read-model member ::acceptance/inventory-review selection)]
                (is (:valid? review) (pr-str (:problems review)))
                (is (= :accepted (:status (run! member ::acceptance/accept-reviewed-inventory-changes "acc-1"
                                                (merge selection {:workspace-id listings/workspace    :acceptance-id "acc-1"
                                                                  :fingerprint  (:fingerprint review)})))))
                (let [coverage (runtime/read-model admin ::cutover/baseline-coverage-by-stock-item-and-warehouse {:connection-id cid})]
                  (is (= [["blue" "main" snapshot] ["red" "main" snapshot]] (mapv (juxt :stock-item-id :warehouse-id :instant) (:baselines coverage))))
                  (is (= [] (:without-baseline coverage))))
                (is (= 2 (connections/count-part :baselines admin cid)))
                (testing "a baselined scope is not proposed again"
                  (is (= :scope-not-ready (:type (failure #(run! admin ::cutover/propose-marketplace-cutover "p2" input))))))))))))))
