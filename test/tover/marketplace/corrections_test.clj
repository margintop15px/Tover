(ns tover.marketplace.corrections-test
  "Board chapter 45 slices 7 and 8 · Notice a changed or cancelled source operation, and Propose the source
  correction: a new revision of an accepted operation becomes one pending amendment or cancellation of the accepted
  record, compared with the source revision that was accepted and resolved with the mappings it used. Nothing is
  accepted automatically."
  (:require
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.runtime :as runtime]
    [bel.store :as store]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [tover.catalogues.records :as records]
    [tover.inventory.acceptance.model :as acceptance]
    [tover.inventory.entries.model :as entries]
    [tover.marketplace.guard-test :as guard-test]
    [tover.marketplace.operations-test :as operations-test]
    [tover.marketplace.operations.corrections :as corrections]
    [tover.marketplace.products.model :as products]
    [tover.marketplace.provenance :as provenance]
    [tover.marketplace.sync.model :as sync]
    [tover.marketplace.sync.observations :as observations]
    [tover.support :as support]))


(use-fixtures :each bel-test/isolated-registry
  (fn [f]
    (bel/build-registry! ['tover.catalogues.products.model 'tover.catalogues.stores.model 'tover.catalogues.warehouses.model
                          'tover.catalogues.suppliers.model 'tover.catalogues.categories.model
                          'tover.inventory.stock-items.model 'tover.inventory.entries.model 'tover.inventory.acceptance.model
                          'tover.marketplace.connections.model 'tover.marketplace.sync.model 'tover.marketplace.products.model
                          'tover.marketplace.products.guard 'tover.marketplace.warehouses.model 'tover.marketplace.provenance
                          'tover.marketplace.operations.model 'tover.marketplace.operations.corrections
                          'tover.workspaces.currency.model])
    (f))
  guard-test/unit-authority)


(def workspace (:workspace-id support/scope))
(def member #uuid "00000000-0000-0000-0000-0000000000b1")
(def c "00000000-0000-0000-0000-00000000c045")
(def connector (sync/connector-actor c))
(def sale (str c ":fbs-posting:P1"))
(def record (provenance/import-entry-id sale))


(defn- correction-id
  {:malli/schema [:=> [:cat :string [:* :any]] :string]}
  [source-operation-id & {:keys [record-revision] :or {record-revision 1}}]
  (provenance/correction-entry-id source-operation-id record-revision))


(defn- execute!
  {:malli/schema [:=> [:cat :map :qualified-keyword :string :map] :any]}
  [ctx id command-id input]
  (runtime/execute! ctx id {:command-id command-id :input input}))


(defn- failure
  {:malli/schema [:=> [:cat fn?] [:maybe :keyword]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))


(defn- rows-of
  {:malli/schema [:=> [:cat :map :qualified-keyword :keyword] [:map-of :string :map]]}
  [ctx model id-key]
  (into {} (map (juxt id-key identity)) (records/all-records ctx model id-key)))


(defn- posting
  "The FBS posting P1 as the adapter translates it: `quantity` red mugs (listing 100) at 500 RUB from the seller
  warehouse, handed over on the 10th."
  {:malli/schema [:=> [:cat [:* :any]] :map]}
  [& {:as fields}]
  (let [{:keys [quantity price] :or {quantity "3" price "500"}} fields]
    (into {} (remove (comp nil? val))
          (merge {:kind        "operation" :operation-kind            "fbs-posting"         :source-id "P1" :status "delivered" :terminal true
                  :occurred-at "2026-11-10T09:00:00Z" :from-warehouse {:type "fbs" :id "3"} :complete  true
                  :lines       [{:line-key "1"   :identifiers    [{:type "sku" :value "100"}] :quantity quantity
                                 :price    price :price-currency "RUB"}]}
                 (dissoc fields :quantity :price)))))


(defn- return-of
  "An FBS return R2 of P1: one red mug received by Ozon on the 12th."
  {:malli/schema [:=> [:cat [:* :any]] :map]}
  [& {:as fields}]
  (merge {:kind     "operation" :operation-kind "return"               :source-id         "R2"             :return-schema "fbs" :status "ReturnedToOzon"
          :terminal true :occurred-at           "2026-11-12T09:00:00Z" :related-operation "fbs-posting:P1" :complete      true
          :lines    [{:line-key "1" :identifiers [{:type "sku" :value "100"}] :quantity "1"}]}
         fields))


(defn- setup!
  "Plan 4's connected workspace (`operations-test/setup!`), with operations observed through the adapter's own
  `observe`, so each revision is stored as sync stores it, and Source Correction Intake run as the processor runs."
  {:malli/schema [:=> [:cat :any] :map]}
  [repo]
  (let [{:keys [ctx system marketplace] :as setup} (operations-test/setup! repo)
        observed (atom 0)]
    (assoc setup
           :observe! (fn [observation]
                       (let [rows     (merge (rows-of ctx ::sync/external-warehouses :external-warehouse-id)
                                             (rows-of ctx ::sync/source-operations :source-operation-id))
                             at       (str (java.time.Instant/parse "2026-11-20T10:00:00Z"))
                             [events] (observations/observe rows observation c (str "run-" (swap! observed inc)) at "test")]
                         (store/with-store (assoc ctx :bel/definitions (bel/operations)) :catalogue :serializable
                           (fn [tx] (doseq [event events] (runtime/apply-event! tx event))))
                         (count events)))
           :corrections! (fn []
                           ;; As `runtime/run-processors!` runs Source Correction Intake, with a full sweep each time.
                           (reset! (:swept marketplace) nil)
                           (let [found (runtime/coeffect ::corrections/corrections-to-propose {:bel/system system} {})]
                             (doseq [request (map corrections/source-correction-intake found)]
                               (runtime/execute! (merge (:scope request) {:bel/system system           :bel/service ::corrections/source-correction-intake
                                                                          :user-id    (:actor request)})
                                                 (:id request)
                                                 {:command-id (runtime/processor-command-id ::corrections/source-correction-intake request)
                                                  :input      (:input request)}))
                             (count found)))
           :propose! (fn [command-id input]
                       (execute! {:workspace-id workspace :bel/system system :bel/service ::corrections/source-correction-intake
                                  :user-id      connector}
                                 ::corrections/propose-source-correction command-id input))
           :entry (fn [id] (get (rows-of ctx ::entries/entries :entry-id) id))
           :pending (fn [id] (some #(when (= id (:entry-id %)) %) (runtime/coeffect ::entries/pending ctx {})))
           :correction (fn [record-id] (get (rows-of ctx ::provenance/source-corrections :record-id) record-id))
           :corrections (fn [] (filterv (comp provenance/correction-entry-id? :entry-id) (vals (rows-of ctx ::entries/entries :entry-id))))
           :accepted (fn [id] (get (rows-of ctx ::provenance/source-acceptances :source-operation-id) id)))))


(defn- accept-all!
  {:malli/schema [:=> [:cat :map :string [:sequential [:tuple :string :int]]] :any]}
  [ctx acceptance-id selection]
  (let [input  {:selection (mapv (fn [[id revision]] {:entry-id id :revision revision}) selection) :order (mapv first selection)}
        review (runtime/read-model ctx ::acceptance/inventory-review input)]
    (is (:valid? review) (pr-str (:problems review)))
    (execute! ctx ::acceptance/accept-reviewed-inventory-changes acceptance-id
              (merge input {:workspace-id workspace :acceptance-id acceptance-id :fingerprint (:fingerprint review)}))))


(defn- save!
  {:malli/schema [:=> [:cat :map :string :int :map] :any]}
  [ctx entry-id revision fields]
  (execute! ctx ::entries/save-shared-inventory-entry (str "save-" entry-id "-" revision)
            (merge {:workspace-id workspace :entry-id entry-id :expected-revision revision :kind "sale"} fields)))


(defn- accepted-sale!
  "Given an accepted Sale of 3 red mugs: P1 observed, proposed and accepted automatically."
  {:malli/schema [:=> [:cat :map] :nil]}
  [{:keys [observe! intake! automatic! balance accepted]}]
  (observe! (posting))
  (intake!)
  (is (= 1 (automatic!)))
  (is (some? (accepted sale)))
  (is (= 7 (balance "red" "home")))
  nil)


(defn- quantities
  {:malli/schema [:=> [:cat [:maybe :map]] [:vector :any]]}
  [entry]
  (mapv :quantity (:lines entry)))


;; Board 45 slice 8 · examples


(deftest quantity-changed
  (support/with-store
    (fn [repo _]
      (let [{:keys [observe! corrections! pending correction balance] :as s} (setup! repo)]
        (testing "Given an accepted Sale of 3"
          (accepted-sale! s)
          (testing "and a new revision stating 2"
            (observe! (posting :quantity "2"))
            (is (= 1 (corrections!)))
            (testing "then one pending amendment to 2 exists"
              (let [entry (pending (correction-id sale))]
                (is (= ["sale" (acceptance/record-revision-id record 1) connector [2]]
                       ((juxt :kind :amends :saved-by quantities) entry)))
                (is (= [{:line-id (str sale "/1") :stock-item-id "red" :quantity 2 :warehouse-id "home"}]
                       (:lines entry))))
              (is (= [sale 2 (correction-id sale) "amendment" false]
                     ((juxt :source-operation-id :revision :entry-id :kind :source-conflict) (correction record)))))
            (testing "and stock is unchanged"
              (is (= 7 (balance "red" "home"))))
            (is (= 0 (corrections!)) "a repeat proposes nothing")))))))


(deftest newer-revision
  (support/with-store
    (fn [repo _]
      (let [{:keys [observe! corrections! pending correction corrections] :as s} (setup! repo)]
        (accepted-sale! s)
        (observe! (posting :quantity "2"))
        (corrections!)
        (testing "Given that amendment, unedited, when a revision states 1"
          (observe! (posting :quantity "1"))
          (is (= 1 (corrections!)))
          (testing "then the same proposal now states 1"
            (is (= [(correction-id sale)] (mapv :entry-id (corrections))))
            (is (= [2 connector [1]] ((juxt :revision :saved-by quantities) (pending (correction-id sale)))))
            (is (= [3 false] ((juxt :revision :source-conflict) (correction record))))))))))


(deftest edited-proposal-is-kept-with-a-conflict
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! corrections! pending correction] :as s} (setup! repo)
            id (correction-id sale)]
        (accepted-sale! s)
        (observe! (posting :quantity "2"))
        (corrections!)
        (testing "Given a member edited the pending amendment"
          (save! ctx id 1 {:amends (acceptance/record-revision-id record 1) :occurred-at "2026-11-10T09:00:00Z"
                           :lines  [{:line-id (str sale "/1") :stock-item-id "red" :warehouse-id "home" :quantity 2 :unit-price "450"}]})
          (testing "when a revision states 1"
            (observe! (posting :quantity "1"))
            (is (= 1 (corrections!)))
            (testing "then the member's entry is kept and a source conflict is recorded"
              (is (= [2 member [2]] ((juxt :revision :saved-by quantities) (pending id))))
              (is (= [3 true 1] ((juxt :revision :source-conflict :entry-revision) (correction record))))
              (is (= 0 (corrections!)) "recorded once"))))))))


(deftest members-own-edit-is-not-proposed-back
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! corrections! corrections balance entry-of]} (setup! repo)
            id (entry-of "P1")]
        (testing "Given a member accepted the imported Sale of 3 as 2"
          (observe! (posting))
          (intake!)
          (save! ctx id 1 {:occurred-at "2026-11-10T09:00:00Z"
                           :lines       [{:line-id (str sale "/1") :stock-item-id "red" :warehouse-id "home" :quantity 2}]})
          (accept-all! ctx "acc-edit" [[id 2]])
          (is (= 8 (balance "red" "home")))
          (testing "when a revision still states 3"
            (observe! (posting :provider-updated-at "2026-11-21T10:00:00Z"))
            (is (= 0 (corrections!)))
            (testing "then the member's 2 is not proposed back as 3"
              (is (empty? (corrections))))))))))


(deftest status-and-price-changes-propose-nothing
  (support/with-store
    (fn [repo _]
      (let [{:keys [observe! corrections! corrections balance] :as s} (setup! repo)]
        (accepted-sale! s)
        (testing "When only the status and the price change"
          (observe! (posting :status "customer_received" :price "450"))
          (is (= 0 (corrections!)))
          (testing "then nothing is proposed"
            (is (empty? (corrections)))
            (is (= 7 (balance "red" "home")))))))))


(deftest withdrawn-effect-is-a-cancellation
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! corrections! pending correction balance accepted]} (setup! repo)
            return (str c ":return:R2")
            id (provenance/import-entry-id return)]
        (testing "Given an accepted Customer return"
          (observe! (posting))
          (observe! (return-of))
          (intake!)
          (accept-all! ctx "acc-r" [[id 1]])
          (is (some? (accepted return)))
          (is (= 11 (balance "red" "home")))
          (testing "when Ozon cancels it"
            (observe! (return-of :status "Cancelled"))
            (is (= 1 (corrections!)))
            (testing "then a pending cancellation of the accepted record exists and stock is unchanged"
              (let [entry (pending (correction-id return))]
                (is (= ["customer-return" true (acceptance/record-revision-id id 1) connector []]
                       ((juxt :kind :cancel :amends :saved-by :lines) entry))))
              (is (= "cancellation" (:kind (correction id))))
              (is (= 11 (balance "red" "home"))))))))))


(deftest cancelled-after-handover-proposes-nothing
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! corrections! corrections] :as s} (setup! repo)]
        (accepted-sale! s)
        (testing "When the handed-over posting is cancelled and Ozon no longer states the handover time"
          (observe! (posting :status "cancelled" :occurred-at nil))
          (is (= "2026-11-10T09:00:00Z" (:occurred-at (get (rows-of ctx ::sync/source-operations :source-operation-id) sale)))
              "sync keeps the handover")
          (is (= 0 (corrections!)))
          (testing "then no cancellation is proposed"
            (is (empty? (corrections)))))))))


(deftest already-cancelled
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! corrections! propose! corrections balance] :as s} (setup! repo)]
        (accepted-sale! s)
        (testing "Given a member already cancelled the accepted Sale"
          (execute! ctx ::entries/save-shared-inventory-entry "m-cancel"
                    {:workspace-id workspace :entry-id "m-cancel" :kind "sale" :amends (acceptance/record-revision-id record 1)
                     :cancel       true      :reason   "Wrong"})
          (accept-all! ctx "acc-cancel" [["m-cancel" 1]])
          (is (= 10 (balance "red" "home")))
          (testing "when a revision states 2"
            (observe! (posting :quantity "2"))
            (is (= 0 (corrections!)))
            (testing "then the record gets no proposal"
              (is (empty? (corrections)))
              (is (= :already-cancelled (failure #(propose! "late" {:source-operation-id sale :revision 2})))))))))))


(deftest cancelled-by-hand-withdraws-the-pending-correction
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! corrections! propose! pending entry correction] :as s} (setup! repo)
            id (correction-id sale)]
        (accepted-sale! s)
        (testing "Given a member's cancellation of the accepted Sale is pending when a revision states 2"
          (execute! ctx ::entries/save-shared-inventory-entry "m-cancel"
                    {:workspace-id workspace :entry-id "m-cancel" :kind "sale" :amends (acceptance/record-revision-id record 1)
                     :cancel       true      :reason   "Wrong"})
          (observe! (posting :quantity "2"))
          (is (= 1 (corrections!)))
          (is (some? (pending id)) "the unedited amendment is proposed beside it")
          (testing "when the member accepts the cancellation"
            (accept-all! ctx "acc-cancel" [["m-cancel" 1]])
            (testing "then intake withdraws the amendment, which could never be accepted"
              (is (= 1 (corrections!)))
              (is (nil? (pending id)))
              (is (= "discarded" (:status (entry id))))
              (is (nil? (:kind (correction record))) "the row no longer names a pending correction")
              (is (= 0 (corrections!)) "once"))
            (testing "and a new proposal is still refused as already cancelled"
              (is (= :already-cancelled (failure #(propose! "late" {:source-operation-id sale :revision 2})))))))))))


(deftest discarded-is-not-repeated-for-the-same-revision
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! corrections! entry pending correction] :as s} (setup! repo)
            id (correction-id sale)]
        (accepted-sale! s)
        (observe! (posting :quantity "2"))
        (corrections!)
        (testing "Given a member discarded the amendment"
          (execute! ctx ::entries/discard-inventory-draft "discard" {:workspace-id workspace :entry-id id :revision 1})
          (testing "then the same revision proposes nothing again"
            (is (= 0 (corrections!)))
            (is (= "discarded" (:status (entry id)))))
          (testing "and the next revision is proposed"
            (observe! (posting :quantity "1"))
            (is (= 1 (corrections!)))
            (is (= [3 connector [1]] ((juxt :revision :saved-by quantities) (pending id))))
            (is (= 3 (:revision (correction record))))))))))


(deftest discarded-stays-discarded-through-a-status-only-revision
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! corrections! entry pending] :as s} (setup! repo)
            id (correction-id sale)]
        (accepted-sale! s)
        (observe! (posting :quantity "2"))
        (corrections!)
        (testing "Given a member discarded the amendment to 2"
          (execute! ctx ::entries/discard-inventory-draft "discard" {:workspace-id workspace :entry-id id :revision 1})
          (testing "when the next revision changes only the status"
            (observe! (posting :quantity "2" :status "customer_received"))
            (is (= 0 (corrections!)))
            (testing "then nothing is proposed again"
              (is (= "discarded" (:status (entry id))))))
          (testing "and a change of what is compared proposes again"
            (observe! (posting :quantity "1" :status "customer_received"))
            (is (= 1 (corrections!)))
            (is (= [1] (quantities (pending id))))))))))


(defn- two-lines
  "Lines of P1 as `[line-key sku quantity]`."
  {:malli/schema [:=> [:cat [:* :any]] [:vector :map]]}
  [& lines]
  (mapv (fn [[line-key sku quantity]] {:line-key line-key :identifiers [{:type "sku" :value sku}] :quantity quantity}) lines))


(deftest amendment-keeps-prices-by-line
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! corrections! pending entry-of]} (setup! repo)
            id (entry-of "P1")
            line #(str sale "/" %)]
        (testing "Given a Sale of 3 red at 500 and 1 blue at 300, accepted"
          (observe! (posting :lines (two-lines ["1" "100" "3"] ["2" "200" "1"])))
          (intake!)
          (save! ctx id 1 {:occurred-at "2026-11-10T09:00:00Z"
                           :lines       [{:line-id (line 1) :stock-item-id "red" :warehouse-id "home" :quantity 3 :unit-price "500"}
                                         {:line-id (line 2) :stock-item-id "blue" :warehouse-id "home" :quantity 1 :unit-price "300"}]})
          (accept-all! ctx "acc-prices" [[id 2]])
          (testing "when a revision adds a line first and reorders the others"
            (observe! (posting :lines (two-lines ["3" "100" "1"] ["2" "200" "1"] ["1" "100" "2"])))
            (is (= 1 (corrections!)))
            (testing "then each line keeps its own accepted price and the new line has none"
              (is (= [[(line 3) nil] [(line 2) "300"] [(line 1) "500"]]
                     (mapv (juxt :line-id :unit-price) (:lines (pending (correction-id sale)))))))))))))


(deftest unproposable-changes-are-named
  (let [sale-proposal {:entry {:kind "sale" :lines []} :missing [] :findings []}]
    (is (= :kind-changed
           (corrections/unproposable {:accepted-proposal sale-proposal
                                      :new-proposal      (assoc-in sale-proposal [:entry :kind] "transfer")})))
    (is (= :accepted-revision-missing (corrections/unproposable {:accepted-missing? true})))
    (is (nil? (corrections/unproposable {:accepted-proposal sale-proposal :new-proposal sale-proposal})))))


(deftest change-back-withdraws-the-unedited-proposal
  (support/with-store
    (fn [repo _]
      (let [{:keys [observe! corrections! entry pending correction] :as s} (setup! repo)
            id (correction-id sale)]
        (accepted-sale! s)
        (observe! (posting :quantity "2"))
        (corrections!)
        (testing "When the source states 3 again"
          (observe! (posting :quantity "3" :provider-updated-at "2026-11-21T10:00:00Z"))
          (is (= 1 (corrections!)))
          (testing "then the unedited amendment is withdrawn"
            (is (nil? (pending id)))
            (is (= "discarded" (:status (entry id))))
            (is (= [3 nil] ((juxt :revision :kind) (correction record))))
            (is (= 0 (corrections!)))))))))


;; Review focus and plan 4 carry-in


(defn- marked?
  "Whether Source Correction Intake's dirty set comes to hold the connection: effects run after commit."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [marketplace]
  (loop [tries 200]
    (cond (contains? (get @(:dirty marketplace) :source-correction-intake #{}) c) true
          (zero? tries) false
          :else (do (Thread/sleep 10) (recur (dec tries))))))


(deftest saving-a-correction-marks-its-connection
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx marketplace observe! corrections!] :as s} (setup! repo)
            id (correction-id sale)]
        (accepted-sale! s)
        (reset! (:dirty marketplace) {})
        (observe! (posting :quantity "2"))
        (corrections!)
        (is (marked? marketplace) "the connector's proposal marks it")
        (reset! (:dirty marketplace) {})
        (save! ctx id 1 {:amends (acceptance/record-revision-id record 1) :occurred-at "2026-11-10T09:00:00Z"
                         :lines  [{:line-id (str sale "/1") :stock-item-id "red" :warehouse-id "home" :quantity 1}]})
        (is (marked? marketplace) "a member's save marks it")))))


(deftest mapping-changed-after-acceptance
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! corrections! pending corrections] :as s} (setup! repo)]
        (accepted-sale! s)
        (testing "Given a member moved listing 100 from red to blue after acceptance"
          (execute! ctx ::products/change-product-mapping "remap"
                    {:external-product-id (str c ":100") :mapping-revision 1 :stock-item-id "blue" :item-revision 1})
          (testing "when a revision changes nothing compared"
            (observe! (posting :provider-updated-at "2026-11-21T10:00:00Z"))
            (is (= 0 (corrections!)))
            (testing "then there is no amendment"
              (is (empty? (corrections)))))
          (testing "when a revision states 2"
            (observe! (posting :quantity "2"))
            (is (= 1 (corrections!)))
            (testing "then the amendment keeps the item the accepted mapping resolved"
              (is (= [["red" 2]] (mapv (juxt :stock-item-id :quantity) (:lines (pending (correction-id sale)))))))))))))


(deftest conflict-accepted-compares-with-the-revision-it-reflects
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! intake! corrections! pending accepted entry-of]} (setup! repo)
            id (entry-of "P1")]
        (testing "Given a member edited the imported Sale of 3 and a newer revision stating 2 conflicts"
          (observe! (posting))
          (intake!)
          (save! ctx id 1 {:occurred-at "2026-11-10T09:00:00Z"
                           :lines       [{:line-id (str sale "/1") :stock-item-id "red" :warehouse-id "home" :quantity 3}]})
          (observe! (posting :quantity "2"))
          (intake!)
          (testing "when the member accepts their entry"
            (accept-all! ctx "acc-conflict" [[id 2]])
            (testing "then the acceptance records the revision the entry reflects"
              (is (= [2 1] ((juxt :revision :reflected-revision) (accepted sale)))))
            (testing "and the difference to revision 2 is proposed"
              (is (= 1 (corrections!)))
              (is (= [2] (quantities (pending (correction-id sale))))))))))))


(deftest accepted-amendment-is-the-new-comparison
  (support/with-store
    (fn [repo _]
      (let [{:keys [ctx observe! corrections! corrections balance correction] :as s} (setup! repo)
            id (correction-id sale)]
        (accepted-sale! s)
        (observe! (posting :quantity "2"))
        (corrections!)
        (testing "When a member accepts the amendment to 2"
          (accept-all! ctx "acc-amend" [[id 1]])
          (is (= 8 (balance "red" "home")))
          (is (= 2 (:accepted-revision (correction record))))
          (testing "then the same revision proposes nothing more"
            (is (= 0 (corrections!)))
            (is (= [id] (mapv :entry-id (corrections)))))
          (testing "and a later change amends the new record revision"
            (observe! (posting :quantity "1"))
            (is (= 1 (corrections!)))
            (is (= (acceptance/record-revision-id record 2) (:amends (some #(when (= (correction-id sale :record-revision 2) (:entry-id %)) %)
                                                                           (corrections)))))))))))


(deftest stale-and-not-permitted
  (support/with-store
    (fn [repo _]
      (let [{:keys [system observe! propose!] :as s} (setup! repo)]
        (accepted-sale! s)
        (observe! (posting :quantity "2"))
        (is (= :stale (failure #(propose! "old" {:source-operation-id sale :revision 1}))))
        (is (= :stale (failure #(propose! "record" {:source-operation-id sale :revision 2 :record-revision 2}))))
        (is (= :not-permitted
               (failure #(execute! {:workspace-id workspace :bel/system system :bel/service ::corrections/source-correction-intake
                                    :user-id      (sync/connector-actor "00000000-0000-0000-0000-00000000c999")}
                                   ::corrections/propose-source-correction "other" {:source-operation-id sale :revision 2}))))))))


(deftest never-accepts
  (is (not-any? #(contains? (:emits (:bel/options (meta %))) ::acceptance/inventory-changes-accepted)
                [#'corrections/propose-source-correction])))


;; Pure rules


(def ^:private accepted-proposal
  {:entry   {:kind  "sale" :occurred-at "2026-11-10T09:00:00Z"
             :lines [{:line-id "p/1" :stock-item-id "red" :quantity 3 :warehouse-id "home" :unit-price "500"}]}
   :missing [] :findings []})


(deftest difference-compares-lines-quantities-warehouses-and-time
  (is (nil? (corrections/difference accepted-proposal accepted-proposal)))
  (is (nil? (corrections/difference accepted-proposal (assoc-in accepted-proposal [:entry :lines 0 :unit-price] "450"))))
  (is (nil? (corrections/difference accepted-proposal (assoc accepted-proposal :missing ["line quantity"]))))
  (is (nil? (corrections/difference accepted-proposal (assoc-in accepted-proposal [:entry :occurred-at] "2026-11-10T09:00:00.000Z"))))
  (is (= "amendment" (:kind (corrections/difference accepted-proposal (assoc-in accepted-proposal [:entry :lines 0 :quantity] 2)))))
  (is (= "amendment" (:kind (corrections/difference accepted-proposal (assoc-in accepted-proposal [:entry :lines 0 :warehouse-id] "v")))))
  (is (= "amendment" (:kind (corrections/difference accepted-proposal (assoc-in accepted-proposal [:entry :occurred-at] "2026-11-11T09:00:00Z")))))
  (is (= {:kind "cancellation"} (corrections/difference accepted-proposal nil))))


(deftest old-acceptance-rows-fall-back-to-their-revision
  (is (= 3 (provenance/reflected-revision {:revision 3})))
  (is (= 2 (provenance/reflected-revision {:revision 3 :reflected-revision 2}))))


(deftest correction-entries-are-not-import-entries
  (let [id (provenance/correction-entry-id sale 1)]
    (is (= (str "correction:" sale "@1") id))
    (is (false? (provenance/import-entry-id? id)))
    (is (provenance/correction-entry-id? id))
    (is (= sale (provenance/correction-source id)))
    (is (nil? (provenance/save-problem {} nil {:entry-id id :amends (str record "#1") :cancel true})))))


(deftest revision-operation-reads-back-what-sync-stored
  (let [rows     {(str c ":fbs:3") {:external-warehouse-id (str c ":fbs:3") :warehouse-type "fbs" :active true}}
        [events] (observations/observe rows (posting) c "run-1" "2026-11-20T10:00:00Z" "test")
        row      (:data (some #(when (= ::sync/source-operation-observed (:type %)) %) events))]
    ;; The stored revision holds the same facts, revision and hash as the row the event wrote.
    (let [read-back (observations/revision-operation rows c row (select-keys row [:facts :revision :payload-hash :adapter-version
                                                                                  :observed-at :source-revision-id]))
          lines     (comp observations/read-json :lines)]
      (is (= (dissoc row :last-observed-at :lines) (dissoc read-back :lines)))
      (is (= (lines row) (lines read-back))))))
