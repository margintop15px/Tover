(ns tover.inventory.store-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [tover.inventory.derivation :as derivation]
    [tover.inventory.ledger :as ledger]
    [tover.inventory.schema :as schema]
    [tover.inventory.store :as store]
    [tover.shared.rdf :as rdf])
  (:import
    [java.nio.file Files]
    [java.util UUID]))


(def scope
  {:workspace-id #uuid "00000000-0000-0000-0000-000000000001"})


(def other-scope
  {:workspace-id #uuid "00000000-0000-0000-0000-000000000002"})


(defn with-store
  {:malli/schema [:=> [:cat [:=> [:cat rdf/Repository :any] :any]] :any]}
  [f]
  (let [path (.toFile (Files/createTempDirectory "tover-inventory-test-" (make-array java.nio.file.attribute.FileAttribute 0)))
        repo (rdf/native-store path)]
    (try (f repo path)
         (finally
           (rdf/close! repo)
           (doseq [file (reverse (file-seq path))] (io/delete-file file))))))


(defn line
  {:malli/schema [:=> [:cat :string :int] :map]}
  [item quantity]
  {:item-id item :name item :location "Main" :quantity quantity})


(defn opening
  {:malli/schema [:=> [:cat :int] :map]}
  [quantity]
  {:kind :opening :occurred-at "2026-01-01" :lines [(line "a" quantity)]})


(defn save!
  {:malli/schema [:=> [:cat rdf/Repository :map] :map]}
  [repo entry]
  (store/save-entry! repo scope {:entry entry :actor "alice"}))


(defn request
  {:malli/schema [:=> [:cat rdf/Repository [:vector :map]] schema/ReviewRequest]}
  [repo drafts]
  {:selected                 (mapv #(select-keys % [:id :revision]) drafts)
   :expected-ledger-revision (:ledger-revision (store/snapshot repo scope))})


(defn submit!
  {:malli/schema [:=> [:cat rdf/Repository [:vector :map]] :map]}
  [repo drafts]
  (store/submit! repo scope (assoc (request repo drafts) :actor "bob" :command-id (str (UUID/randomUUID)))))


(defn error-data
  {:malli/schema [:=> [:cat [:=> [:cat] :any]] [:maybe :map]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (ex-data error))))


(defn quantity
  {:malli/schema [:=> [:cat rdf/Repository :string] [:maybe :int]]}
  [repo item]
  (:quantity (first (filter #(= item (:item-id %)) (:rows (store/snapshot repo scope))))))


(deftest fresh-v1-store-is-empty-and-does-not-read-prototype-graphs
  (with-store
    (fn [repo _]
      (rdf/with-transaction [conn {:repo repo :level :serializable}]
        (rdf/add! conn (rdf/iri "urn:tover:workspace:00000000-0000-0000-0000-000000000001:stock-v0:history")
                  (rdf/iri "urn:test:prototype") (rdf/iri "urn:test:quantity") 999))
      (is (= {:ledger-revision 0 :items {} :rows [] :drafts [] :compositions [] :operations [] :history []}
             (store/snapshot repo scope))))))


(deftest incomplete-draft-autosaves-and-survives-reopen
  (with-store
    (fn [repo path]
      (let [draft (save! repo {:kind :operation :type :production :inputs []})]
        (is (= 1 (:revision draft)))
        (is (= [draft] (:drafts (store/snapshot repo scope))))
        (is (false? (:valid? (store/preview repo scope (request repo [draft])))))
        (rdf/close! repo)
        (let [reopened (rdf/native-store path)]
          (try
            (is (= [draft] (:drafts (store/snapshot reopened scope))))
            (finally (rdf/close! reopened))))))))


(deftest stale-edit-retains-current-value-and-does-not-overwrite
  (with-store
    (fn [repo _]
      (let [draft   (save! repo (opening 10))
            updated (store/save-entry! repo scope {:id (:id draft) :expected-revision 1 :entry (opening 12) :actor "bob"})
            error   (error-data #(store/save-entry! repo scope {:id    (:id draft)  :expected-revision 1
                                                                :entry (opening 20) :actor             "alice"}))]
        (is (= :stale-draft (:type error)))
        (is (= updated (:current error)))
        (is (= [updated] (:drafts (store/snapshot repo scope))))
        (is (= :stale-draft (:type (error-data #(store/discard-entry! repo scope {:id (:id draft) :expected-revision 1})))))
        (store/discard-entry! repo scope {:id (:id draft) :expected-revision 2})
        (is (empty? (:drafts (store/snapshot repo scope))))))))


(deftest selected-discard-is-atomic-and-preserves-accepted-state-and-unselected-drafts
  (with-store
    (fn [repo _]
      (let [accepted (save! repo (opening 10))
            command  (assoc (request repo [accepted]) :actor "bob" :command-id "before-discard")
            receipt  (store/submit! repo scope command)
            a        (save! repo {:kind :operation :type :sale})
            b        (save! repo {:kind :operation :type :production})
            pending  (save! repo {:kind :operation :type :payment})
            edited-b (store/save-entry! repo scope {:id    (:id b) :expected-revision (:revision b)
                                                    :entry {:kind :operation :type :production :reason "Keep this edit"}
                                                    :actor "alice"})
            before   (store/snapshot repo scope)
            stale    (mapv #(select-keys % [:id :revision]) [a b])]
        (is (= :stale-draft (:type (error-data #(store/discard-selected! repo scope stale)))))
        (is (= before (store/snapshot repo scope)))
        (is (= {:discarded-ids [(:id a) (:id b)]}
               (store/discard-selected! repo scope (mapv #(select-keys % [:id :revision]) [a edited-b]))))
        (let [after (store/snapshot repo scope)]
          (is (= [pending] (:drafts after)))
          (is (= (dissoc before :drafts) (dissoc after :drafts))))
        (is (= receipt (store/submit! repo scope command)))))))


(deftest selected-discard-rejects-invalid-and-cross-workspace-selections
  (with-store
    (fn [repo _]
      (let [draft    (save! repo (opening 1))
            selected [(select-keys draft [:id :revision])]
            before   (store/snapshot repo scope)]
        (doseq [selection [[] (vec (concat selected selected))
                           (mapv #(hash-map :id (str %) :revision 1) (range 501))]]
          (is (= :validation (:type (error-data #(store/discard-selected! repo scope selection)))))
          (is (= before (store/snapshot repo scope))))
        (is (= :stale-draft (:type (error-data #(store/discard-selected! repo other-scope selected)))))
        (is (empty? (:drafts (store/snapshot repo other-scope))))
        (is (= before (store/snapshot repo scope)))))))


(deftest selected-entries-submit-atomically-and-unselected-stay-pending
  (with-store
    (fn [repo _]
      (let [a       (save! repo (opening 10))
            b       (save! repo {:kind :operation :type :sale :party "Customer" :occurred-at "2026-01-02" :lines [(line "a" 3)]})
            pending (save! repo {:kind :operation :type :production})
            receipt (submit! repo [a b])
            saved   (store/snapshot repo scope)]
        (is (= 1 (:ledger-revision receipt)))
        (is (= [(:id a) (:id b)] (:submitted-ids receipt)))
        (is (= [pending] (:drafts saved)))
        (is (= 7 (quantity repo "a")))
        (is (= 2 (count (:history saved))))
        (is (= saved (store/rebuild! repo scope)))))))


(deftest invalid-selection-leaves-ledger-and-all-drafts-untouched
  (with-store
    (fn [repo _]
      (let [a      (save! repo (opening 2))
            b      (save! repo {:kind :operation :type :sale :party "Customer" :occurred-at "2026-01-02" :lines [(line "a" 3)]})
            before (store/snapshot repo scope)]
        (is (false? (:valid? (store/preview repo scope (request repo [a b])))))
        (is (= :validation (:type (error-data #(submit! repo [a b])))))
        (is (= before (store/snapshot repo scope)))))))


(deftest changed-draft-or-ledger-invalidates-reviewed-selection
  (with-store
    (fn [repo _]
      (let [a (save! repo (opening 2))
            b (save! repo (assoc (opening 1) :occurred-at "2026-01-02"))
            q (request repo [a])]
        (store/save-entry! repo scope {:id (:id a) :expected-revision 1 :entry (opening 3) :actor "bob"})
        (is (= :stale-draft (:type (error-data #(store/preview repo scope q)))))
        (submit! repo [b])
        (is (= :stale-ledger (:type (error-data #(store/preview repo scope q)))))))))


(deftest duplicate-submit-and-response-loss-return-the-existing-receipt-after-restart
  (with-store
    (fn [repo path]
      (let [draft   (save! repo (opening 10))
            q       (assoc (request repo [draft]) :actor "bob" :command-id "same-command")
            receipt (store/submit! repo scope q)]
        (is (= receipt (store/submit! repo scope q)))
        (is (= :idempotency-conflict (:type (error-data #(store/submit! repo scope (assoc q :selected []))))))
        (rdf/close! repo)
        (let [reopened (rdf/native-store path)]
          (try
            (is (= receipt (store/submit! reopened scope q)))
            (is (= 10 (quantity reopened "a")))
            (is (= 1 (count (:history (store/snapshot reopened scope)))))
            (finally (rdf/close! reopened))))))))


(deftest revisions-and-cancellations-retain-original-facts
  (with-store
    (fn [repo _]
      (let [original (save! repo (opening 10))]
        (submit! repo [original])
        (let [revise (save! repo {:kind :revision :target-id (:id original) :reason "Correct opening" :replacement (opening 12)})]
          (submit! repo [revise])
          (let [saved (store/snapshot repo scope)]
            (is (= 12 (quantity repo "a")))
            (is (= 2 (count (:history saved))))
            (is (= 10 (get-in saved [:history 0 :entry :lines 0 :quantity])))
            (is (= 2 (get-in saved [:operations 0 :revision])))
            (is (= "bob" (get-in saved [:history 1 :actor])))))
        (let [cancel (save! repo {:kind :cancellation :target-id (:id original) :reason "Entered twice"})]
          (submit! repo [cancel])
          (let [saved (store/snapshot repo scope)]
            (is (= 3 (count (:history saved))))
            (is (empty? (:operations saved)))
            (is (empty? (:rows saved)))
            (is (true? (get-in saved [:history 2 :cancelled?])))))))))


(deftest related-corrections-must-be-reviewed-together-to-repair-a-timeline
  (with-store
    (fn [repo _]
      (let [open (save! repo (opening 10))
            sale (save! repo {:kind :operation :type :sale :party "C" :occurred-at "2026-01-02" :lines [(line "a" 8)]})]
        (submit! repo [open sale])
        (let [rev-open (save! repo {:kind :revision :target-id (:id open) :reason "Correct count" :replacement (opening 5)})
              rev-sale (save! repo {:kind :revision :target-id (:id sale) :reason "Correct sale" :replacement {:kind :operation :type :sale :party "C" :occurred-at "2026-01-02" :lines [(line "a" 4)]}})]
          (is (false? (:valid? (store/preview repo scope (request repo [rev-open])))))
          (is (true? (:valid? (store/preview repo scope (request repo [rev-open rev-sale])))))
          (submit! repo [rev-open rev-sale])
          (is (= 1 (quantity repo "a"))))))))


(deftest cancelling-an-input-opening-cannot-orphan-production-descendants
  (with-store
    (fn [repo _]
      (let [open       (save! repo (opening 4))
            production (save! repo {:kind :operation :type :production :occurred-at "2026-01-02" :inputs [(line "a" 4)] :outputs [(line "b" 2)]})]
        (submit! repo [open production])
        (let [cancel  (save! repo {:kind :cancellation :target-id (:id open) :reason "Bad input"})
              before  (store/snapshot repo scope)
              preview (store/preview repo scope (request repo [cancel]))]
          (is (false? (:valid? preview)))
          (is (= (:id production) (get-in preview [:errors 0 :operation-id])))
          (is (= :validation (:type (error-data #(submit! repo [cancel])))))
          (is (= before (store/snapshot repo scope))))))))


(deftest workspace-graphs-isolate-drafts-receipts-stock-and-history
  (with-store
    (fn [repo _]
      (let [draft (save! repo (opening 5))]
        (is (empty? (:drafts (store/snapshot repo other-scope))))
        (is (= :stale-draft (:type (error-data #(store/preview repo other-scope (request repo [draft]))))))
        (submit! repo [draft])
        (is (empty? (:rows (store/snapshot repo other-scope))))
        (is (empty? (:history (store/snapshot repo other-scope))))
        (is (= :not-found
               (:type (error-data #(store/item-history repo other-scope "a" {:include-ancestors? true})))))))))


(deftest failed-commit-rolls-back-ledger-projection-draft-removal-and-receipt
  (with-store
    (fn [repo _]
      (let [draft  (save! repo (opening 10))
            before (store/snapshot repo scope)
            q      (assoc (request repo [draft]) :actor "alice" :command-id "rollback")
            add!   rdf/add!
            writes (atom 0)]
        (with-redefs [rdf/add! (fn [& args] (when (= 3 (swap! writes inc)) (throw (ex-info "Receipt write failed" {}))) (apply add! args))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Receipt write failed" (store/submit! repo scope q))))
        (is (= before (store/snapshot repo scope)))
        (is (= 1 (:ledger-revision (store/submit! repo scope q))))
        (is (= 10 (quantity repo "a")))))))

(deftest simultaneous-edits-have-one-winner-and-preserve-acknowledged-content
  (with-store
    (fn [repo _]
      (let [draft (save! repo (opening 5))
            start (promise)
            edit  (fn [quantity]
                    (future @start
                            (try (store/save-entry! repo scope {:id    (:id draft)        :expected-revision 1
                                                                :entry (opening quantity) :actor             "alice"})
                                 (catch clojure.lang.ExceptionInfo error (ex-data error)))))
            a     (edit 6) b (edit 7)]
        (deliver start true)
        (let [results [(deref a 5000 :timeout) (deref b 5000 :timeout)]
              winner  (first (filter :revision results))]
          (is (= 1 (count (filter :revision results))))
          (is (= 1 (count (filter #(= :stale-draft (:type %)) results))))
          (is (= [winner] (:drafts (store/snapshot repo scope)))))))))


(deftest simultaneous-submissions-recheck-the-reviewed-ledger
  (with-store
    (fn [repo _]
      (let [a      (save! repo (opening 5))
            b      (save! repo (assoc (opening 3) :occurred-at "2026-01-02"))
            qa     (assoc (request repo [a]) :actor "alice" :command-id "submit-a")
            qb     (assoc (request repo [b]) :actor "bob" :command-id "submit-b")
            start  (promise)
            submit (fn [q] (future @start (try (store/submit! repo scope q)
                                               (catch clojure.lang.ExceptionInfo error (ex-data error)))))
            fa     (submit qa) fb (submit qb)]
        (deliver start true)
        (let [results  [(deref fa 5000 :timeout) (deref fb 5000 :timeout)]
              snapshot (store/snapshot repo scope)]
          (is (= 1 (count (filter :command-id results))))
          (is (= 1 (count (filter #(= :stale-ledger (:type %)) results))))
          (is (= 1 (:ledger-revision snapshot)))
          (is (= 1 (count (:drafts snapshot))))
          (is (= 1 (count (:history snapshot)))))))))


(deftest semantic-rdf-is-the-only-accepted-history-authority
  (with-store
    (fn [repo _]
      (let [draft      (save! repo (opening 6))
            production (save! repo {:kind :operation :type :production :occurred-at "2026-01-02" :inputs [(line "a" 4)] :outputs [(line "b" 2)]})]
        (submit! repo [draft production])
        (rdf/with-transaction [conn {:repo repo :level :snapshot}]
          (let [rows (rdf/select conn
                                 {:prefixes {:t "<urn:tover:inventory-v1:>" :rdf "<http://www.w3.org/1999/02/22-rdf-syntax-ns#>"}
                                  :select   '[?itemId ?quantity ?occurredAt]
                                  :where    '[[:graph ?history [[?operation :rdf/type :t/Production] [?operation :t/input ?input] [?input :t/item ?item] [?item :t/itemId ?itemId] [?input :t/quantity ?quantity] [?operation :t/occurredAt ?occurredAt]]]]}
                                 {'?history (rdf/iri "urn:tover:workspace:00000000-0000-0000-0000-000000000001:inventory-v1:history")})]
            (is (= 1 (count rows)))
            (is (= "a" (:itemId (first rows))))
            (is (= 4 (:quantity (first rows)))))
          (is (empty? (rdf/select conn
                                  {:prefixes {:t "<urn:tover:inventory-v1:>"}
                                   :select   '[?payload]
                                   :where    '[[:graph ?history [[?record :t/payload ?payload]]]]}
                                  {'?history (rdf/iri "urn:tover:workspace:00000000-0000-0000-0000-000000000001:inventory-v1:history")}))))
        (is (= (store/snapshot repo scope) (store/rebuild! repo scope)))))))


(deftest simple-purchase-money-and-transfer-roundtrip
  (with-store
    (fn [repo _]
      (let [purchase (save! repo {:kind :operation :type :purchase :occurred-at "2026-01-01" :party "Supplier" :currency "EUR" :amount "20.10" :lines [(assoc (line "a" 10) :amount "20.10")]})
            transfer (save! repo {:kind :operation :type :transfer :occurred-at "2026-01-04" :from-location "Main" :to-location "Shop" :lines [{:item-id "a" :quantity 2}]})]
        (submit! repo [purchase transfer])
        (let [saved (store/snapshot repo scope)
              op    (first (:operations saved))]
          (is (= 20.10M (:amount op)))
          (is (= 20.10M (get-in op [:lines 0 :amount])))
          (is (not (contains? op :receipts)))
          (is (= #{["Main" 8] ["Shop" 2]} (set (map (juxt :location :quantity) (:rows saved)))))
          (is (= saved (store/rebuild! repo scope))))))))


(deftest semantic-preview-rolls-back-staged-records-and-pins-the-model-version
  (with-store
    (fn [repo _]
      (let [draft  (save! repo (opening 4))
            before (store/snapshot repo scope)]
        (is (true? (:valid? (store/preview repo scope (request repo [draft])))))
        (is (= before (store/snapshot repo scope)))
        (let [receipt (submit! repo [draft])
              saved   (store/snapshot repo scope)]
          (is (= "inventory-v1.0.0" (:semantic-version receipt)))
          (is (= "inventory-v1.0.0" (get-in saved [:history 0 :semantic-version]))))))))


(deftest semantic-projection-drift-blocks-submit-without-consuming-the-draft
  (with-store
    (fn [repo _]
      (let [draft     (save! repo (opening 4))
            before    (store/snapshot repo scope)
            validate! derivation/validate!]
        (with-redefs [derivation/validate! (fn [conn graph] (update-in (validate! conn graph) [:balances ["a" "Main"]] inc))]
          (is (= :projection-drift (:type (error-data #(submit! repo [draft]))))))
        (is (= before (store/snapshot repo scope)))
        (submit! repo [draft])
        (is (= 4 (quantity repo "a")))))))


(deftest declarative-timeline-validation-runs-before-independent-replay
  (with-store
    (fn [repo _]
      (let [open         (save! repo (opening 1))
            sale         (save! repo {:kind :operation :type :sale :party "C" :occurred-at "2026-01-02" :lines [(line "a" 2)]})
            replay-calls (atom 0)]
        (with-redefs [ledger/replay (fn [_] (swap! replay-calls inc) (throw (ex-info "Reference should not run" {})))]
          (let [error (error-data #(submit! repo [open sale]))]
            (is (= :validation (:type error)))
            (is (= (:id sale) (get-in error [:errors 0 :operation-id])))
            (is (zero? @replay-calls))))
        (is (empty? (:history (store/snapshot repo scope))))))))


(deftest ancestor-history-is-consistent-with-one-snapshot-and-stops-at-consumption
  (with-store
    (fn [repo _]
      (let [drafts (mapv #(save! repo %)
                         [(opening 10)
                          {:kind :operation :type :production :occurred-at "2026-01-02" :inputs [(line "a" 4)] :outputs [(line "b" 2)]}
                          {:kind :operation :type :production :occurred-at "2026-01-03" :inputs [(line "b" 1)] :outputs [(line "c" 1)]}
                          {:kind :operation :type :purchase :party "S" :occurred-at "2026-01-04" :lines [(line "a" 100) (line "b" 100)]}])]
        (submit! repo drafts)
        (let [history  (store/item-history repo scope "c" {:include-ancestors? true})
              timeline (:timeline-records history)]
          (is (= 1 (:ledger-revision history)))
          (is (= #{"a" "b" "c"} (set (keys (:items history)))))
          (is (string? (:snapshot-at history)))
          (is (= ["c"] (mapv :item-id (:records history))))
          (is (not-any? #(= :purchase (:type %)) timeline))
          (is (= #{"2026-01-02T00:00:00Z"} (set (map :timeline-until (filter #(= "a" (:item-id %)) timeline)))))
          (is (= #{"2026-01-03T00:00:00Z"} (set (map :timeline-until (filter #(= "b" (:item-id %)) timeline)))))
          (is (every? #(not (contains? % :timeline-until)) (filter #(= "c" (:item-id %)) timeline)))
          (is (every? #(= :unknown (:allocation %)) (:lineage history))))))))


(deftest later-co-inputs-are-not-ancestors-of-a-consumed-item
  (with-store
    (fn [repo _]
      (let [drafts (mapv #(save! repo %)
                         [{:kind :opening :occurred-at "2026-01-01" :lines [(line "a" 10) (line "x" 10)]}
                          {:kind :operation :type :production :occurred-at "2026-01-02" :inputs [(line "a" 4)] :outputs [(line "b" 2)]}
                          {:kind :operation :type :production :occurred-at "2026-01-03" :inputs [(line "b" 1) (line "x" 1)] :outputs [(line "c" 1)]}])]
        (submit! repo drafts)
        (let [history (store/item-history repo scope "b" {:include-ancestors? true})]
          (is (= #{"a" "b"} (set (map :item-id (:timeline-records history)))))
          (is (= 2 (count (:records history)))))))))


(deftest creator-editor-and-reviewer-provenance-remain-distinct
  (with-store
    (fn [repo _]
      (let [created (save! repo (opening 4))
            edited  (store/save-entry! repo scope {:id (:id created) :expected-revision 1 :entry (opening 5) :actor "bob"})]
        (is (= "alice" (:created-by edited)))
        (is (= "bob" (:actor edited)))
        (store/submit! repo scope (assoc (request repo [edited]) :actor "carol" :command-id "three-people"))
        (let [record (first (:history (store/snapshot repo scope)))]
          (is (= "alice" (:created-by record)))
          (is (= "bob" (:proposed-by record)))
          (is (= "carol" (:actor record))))))))


(deftest corrected-identity-survives-old-snapshots-backdating-and-rebuild
  (with-store
    (fn [repo _]
      (let [initial (save! repo (assoc-in (opening 10) [:lines 0 :article] "OLD"))
            sale    (save! repo {:kind :operation :type :sale :party "C" :occurred-at "2026-01-03" :lines [(assoc (line "a" 1) :article "OLD")]})]
        (submit! repo [initial sale])
        (let [correction (save! repo {:kind :correction :reason "Correct identity" :occurred-at "2026-01-02" :lines [(assoc (line "a" 8) :article "NEW")]})]
          (submit! repo [correction]))
        (let [old-snapshot (save! repo {:kind :operation :type :purchase :party "S" :occurred-at "2026-01-04" :lines [(assoc (line "a" 1) :article "OLD")]})]
          (submit! repo [old-snapshot]))
        (let [saved (store/snapshot repo scope)]
          (is (= "NEW" (get-in saved [:items "a" :article])))
          (is (= "NEW" (get-in saved [:rows 0 :article])))
          (is (= 8 (quantity repo "a")))
          (is (= "OLD" (get-in saved [:history 1 :entry :lines 0 :article])))
          (is (= saved (store/rebuild! repo scope))))))))


(deftest revision-of-an-earlier-correction-is-the-latest-metadata-decision
  (with-store
    (fn [repo _]
      (let [initial          (save! repo (opening 10))
            first-correction (save! repo {:kind :correction :reason "Count" :occurred-at "2026-01-03" :lines [(assoc (line "a" 10) :article "FIRST")]})]
        (submit! repo [initial first-correction])
        (submit! repo [(save! repo {:kind :correction :reason "Backdate" :occurred-at "2026-01-02" :lines [(assoc (line "a" 8) :article "SECOND")]})])
        (submit! repo [(save! repo {:kind :revision :target-id (:id first-correction) :reason "Fix article" :replacement {:kind :correction :reason "Count" :occurred-at "2026-01-03" :lines [(assoc (line "a" 10) :article "REVISED")]}})])
        (let [saved (store/snapshot repo scope)]
          (is (= "REVISED" (get-in saved [:items "a" :article])))
          (is (= 10 (quantity repo "a")))
          (is (= saved (store/rebuild! repo scope))))))))


(deftest cancellation-keeps-an-item-audit-without-restoring-effective-effects
  (with-store
    (fn [repo _]
      (let [initial (save! repo (assoc-in (opening 10) [:lines 0 :article] "A-1"))]
        (submit! repo [initial])
        (submit! repo [(save! repo {:kind :cancellation :target-id (:id initial) :reason "Wrong opening"})])
        (let [history (store/item-history repo scope "a" {:include-ancestors? true})]
          (is (= "a" (get-in history [:item :id])))
          (is (= "A-1" (get-in history [:item :article])))
          (is (= (:item history) (get-in history [:items "a"])))
          (is (= [:superseded :cancelled] (mapv :status (:audit-records history))))
          (is (= [:opening :cancellation] (mapv :change-kind (:audit-records history))))
          (is (every? :touches-item? (:audit-records history)))
          (is (= "Wrong opening" (get-in history [:audit-records 1 :reason])))
          (is (empty? (:records history)))
          (is (empty? (:timeline-records history)))
          (is (empty? (:lineage history)))
          (is (empty? (:rows (store/snapshot repo scope)))))))))


(deftest moving-an-operation-to-another-item-keeps-both-item-audits
  (with-store
    (fn [repo _]
      (let [initial (save! repo (assoc-in (opening 10) [:lines 0 :article] "A-1"))]
        (submit! repo [initial])
        (submit! repo [(save! repo {:kind :revision :target-id (:id initial) :reason "Wrong goods" :replacement {:kind :opening :occurred-at "2026-01-01" :lines [(assoc (line "b" 10) :article "B-1")]}})])
        (let [old     (store/item-history repo scope "a" {:include-ancestors? true})
              current (store/item-history repo scope "b" {:include-ancestors? true})]
          (is (= "A-1" (get-in old [:item :article])))
          (is (= [:superseded :effective] (mapv :status (:audit-records old))))
          (is (= [true false] (mapv :touches-item? (:audit-records old))))
          (is (= [false true] (mapv :touches-item? (:audit-records current))))
          (is (empty? (get-in old [:audit-records 1 :item-lines])))
          (is (= "Wrong goods" (get-in old [:audit-records 1 :reason])))
          (is (empty? (:records old)))
          (is (empty? (:timeline-records old)))
          (is (= 1 (count (:records current))))
          (is (= ["b"] (mapv :item-id (:rows (store/snapshot repo scope))))))))))


(def alice-scope
  (assoc scope :user-id #uuid "00000000-0000-0000-0000-000000000011"))


(def bob-scope
  (assoc scope :user-id #uuid "00000000-0000-0000-0000-000000000012"))


(defn compose!
  {:malli/schema [:=> [:cat rdf/Repository :map] schema/Draft]}
  [repo entry]
  (store/save-composition! repo alice-scope {:entry entry :actor "alice"}))


(defn complete-request
  {:malli/schema [:=> [:cat schema/Draft :string] schema/CompleteRequest]}
  [draft command-id]
  {:id (:id draft) :expected-revision (:revision draft) :actor "alice" :command-id command-id})


(deftest private-compositions-are-owned-by-authenticated-scope-and-excluded-from-shared-snapshots
  (with-store
    (fn [repo _]
      (let [draft (store/save-composition! repo alice-scope {:entry {:kind :operation :type :purchase} :actor "bob"})
            owner (store/snapshot repo alice-scope)]
        (is (= (str (:user-id alice-scope)) (:owner-id draft)))
        (is (= :composing (:phase draft)))
        (is (= [draft] (:compositions owner)))
        (is (empty? (:drafts owner)))
        (is (= (dissoc owner :compositions) (dissoc (store/snapshot repo bob-scope) :compositions)))
        (is (empty? (:compositions (store/snapshot repo bob-scope))))
        (is (empty? (:compositions (store/snapshot repo scope))))
        (is (empty? (:items owner)))
        (is (empty? (:rows owner)))
        (is (= :forbidden (:type (error-data #(store/save-composition! repo scope {:entry {} :actor "alice"})))))))))


(deftest shared-entry-points-cannot-read-mutate-or-promote-private-compositions
  (with-store
    (fn [repo _]
      (let [draft    (compose! repo (opening 3))
            selected [(select-keys draft [:id :revision])]
            request  {:selected selected :expected-ledger-revision 0}
            before   (store/snapshot repo alice-scope)]
        (doseq [caller [scope alice-scope bob-scope]
                action [#(store/save-entry! repo caller {:id (:id draft) :expected-revision 1 :entry (opening 9) :actor "bob"})
                        #(store/discard-entry! repo caller {:id (:id draft) :expected-revision 1})
                        #(store/discard-selected! repo caller selected)
                        #(store/preview repo caller request)
                        #(store/submit! repo caller (assoc request :actor "bob" :command-id "reject-private"))]]
          (let [error (error-data action)]
            (is (= :stale-draft (:type error)))
            (is (nil? (:current error)))))
        (is (= before (store/snapshot repo alice-scope)))))))


(deftest private-ownership-is-checked-before-returning-current-on-stale-edits
  (with-store
    (fn [repo _]
      (let [draft   (compose! repo (opening 3))
            request {:id (:id draft) :expected-revision 1 :entry (opening 5) :actor "alice"}
            updated (store/save-composition! repo alice-scope request)
            stale   (error-data #(store/save-composition! repo alice-scope request))]
        (is (= updated (:current stale)))
        (doseq [action [#(store/save-composition! repo bob-scope request)
                        #(store/discard-composition! repo bob-scope (select-keys request [:id :expected-revision]))
                        #(store/complete-composition! repo bob-scope (complete-request draft "private-done"))]]
          (let [error (error-data action)]
            (is (= :stale-draft (:type error)))
            (is (nil? (:current error)))))
        (is (= (error-data #(store/save-composition! repo bob-scope request))
               (error-data #(store/save-composition! repo (assoc bob-scope :workspace-id (:workspace-id other-scope)) request))))
        (is (= [updated] (:compositions (store/snapshot repo alice-scope))))))))


(deftest completing-an-entry-validates-all-fields-and-shares-without-accepting-ledger-history
  (with-store
    (fn [repo _]
      (let [draft   (compose! repo {:kind :operation :type :payment})
            request (complete-request draft "complete-payment")
            invalid (error-data #(store/complete-composition! repo alice-scope request))
            entry   {:kind      :operation :type   :payment :occurred-at "2026-01-01" :party "Supplier"
                     :direction :outgoing  :amount "7.50"   :currency    "EUR"}]
        (is (= :validation (:type invalid)))
        (is (= #{[:occurred-at] [:party] [:direction] [:amount] [:currency]}
               (set (map :path (:errors invalid)))))
        (is (= [draft] (:compositions (store/snapshot repo alice-scope))))
        (let [pending (store/complete-composition! repo alice-scope (assoc request :entry entry))
              saved   (store/snapshot repo bob-scope)]
          (is (= (:id draft) (:id pending)))
          (is (= 2 (:revision pending)))
          (is (= :pending (:phase pending)))
          (is (= (:owner-id draft) (:owner-id pending)))
          (is (= [pending] (:drafts saved)))
          (is (empty? (:compositions (store/snapshot repo alice-scope))))
          (is (zero? (:ledger-revision saved)))
          (is (empty? (:history saved)))
          (is (empty? (:rows saved)))
          (is (= pending (store/complete-composition! repo alice-scope request)))
          (is (= :idempotency-conflict
                 (:type (error-data #(store/complete-composition! repo alice-scope (assoc request :entry (assoc entry :amount "9.50")))))))
          (let [edited (store/save-entry! repo bob-scope {:id (:id pending) :expected-revision 2 :actor "bob" :entry (assoc entry :amount "8.50")})]
            (is (= (:owner-id draft) (:owner-id edited)))
            (is (= :pending (:phase edited)))
            (is (= pending (store/complete-composition! repo alice-scope request)))))))))


(deftest composition-autosave-and-completion-receipt-survive-restart-and-later-ledger-submission
  (with-store
    (fn [repo path]
      (let [draft   (compose! repo (opening 4))
            command (complete-request draft "response-lost")]
        (rdf/close! repo)
        (let [reopened (rdf/native-store path)]
          (try
            (is (= [draft] (:compositions (store/snapshot reopened alice-scope))))
            (let [pending (store/complete-composition! reopened alice-scope command)]
              (is (= pending (store/complete-composition! reopened alice-scope command)))
              (is (= :idempotency-conflict
                     (:type (error-data #(store/complete-composition! reopened alice-scope (assoc command :entry (opening 6)))))))
              (submit! reopened [pending])
              (is (= pending (store/complete-composition! reopened alice-scope command)))
              (is (= 4 (quantity reopened "a")))
              (is (= 1 (count (:history (store/snapshot reopened scope)))))
              (rdf/close! reopened)
              (let [again (rdf/native-store path)]
                (try
                  (is (= pending (store/complete-composition! again alice-scope command)))
                  (is (= 1 (count (:history (store/snapshot again scope)))))
                  (finally (rdf/close! again)))))
            (finally (rdf/close! reopened))))))))


(deftest duplicate-done-requests-promote-once-and-different-commands-observe-the-new-revision
  (with-store
    (fn [repo _]
      (let [draft     (compose! repo (opening 2))
            command   (complete-request draft "same-done")
            attempts  [(future (store/complete-composition! repo alice-scope command))
                       (future (store/complete-composition! repo alice-scope command))]
            completed (mapv deref attempts)]
        (is (apply = completed))
        (is (= 1 (count (:drafts (store/snapshot repo scope)))))
        (is (= :stale-draft (:type (error-data #(store/complete-composition! repo alice-scope (assoc command :command-id "another-done"))))))
        (is (nil? (:current (error-data #(store/complete-composition! repo alice-scope (assoc command :command-id "another-done"))))))))))


(deftest private-clear-preserves-shared-drafts-and-legacy-missing-phase-remains-pending
  (with-store
    (fn [repo _]
      (let [shared  (save! repo (opening 1))
            private (compose! repo {:kind :operation :type :sale})]
        (is (nil? (:phase shared)))
        (is (= [shared] (:drafts (store/snapshot repo alice-scope))))
        (is (= :stale-draft (:type (error-data #(store/save-composition! repo alice-scope {:id (:id shared) :expected-revision 1 :entry {} :actor "alice"})))))
        (is (= {:id (:id private) :discarded? true}
               (store/discard-composition! repo alice-scope {:id (:id private) :expected-revision 1})))
        (is (empty? (:compositions (store/snapshot repo alice-scope))))
        (is (= [shared] (:drafts (store/snapshot repo scope))))))))


(deftest item-history-refuses-unknown-pending-and-private-items
  (with-store
    (fn [repo _]
      (save! repo {:kind :opening :occurred-at "2026-01-01" :lines [(line "pending" 2)]})
      (compose! repo {:kind :opening :occurred-at "2026-01-01" :lines [(line "private" 2)]})
      (doseq [caller [scope alice-scope bob-scope]
              item   ["unknown" "pending" "private"]]
        (is (= {:type :not-found :item-id item}
               (error-data #(store/item-history repo caller item {}))))))))


(deftest legacy-shared-purchase-drafts-preview-direct-quantities-before-acceptance
  (doseq [receipts [[] [{:occurred-at "2026-01-02" :lines [(line "a" 2)]}]
                    [{:occurred-at "" :lines [{:item-id "a" :quantity ""}]}]]]
    (with-store
      (fn [repo _]
        (let [draft   (save! repo {:kind  :operation :type  :purchase :occurred-at                   "2026-01-01"
                                   :party "Supplier" :lines [(line "a" 10) (line "new" 3)] :receipts receipts})
              before  (store/snapshot repo scope)
              preview (store/preview repo scope (request repo [draft]))]
          (is (true? (:valid? preview)))
          (is (= {"a" 10 "new" 3} (into {} (map (juxt :item-id :quantity)) (:rows preview))))
          (is (= before (store/snapshot repo scope)))
          (submit! repo [draft])
          (let [accepted (store/snapshot repo scope)]
            (is (= {"a" 10 "new" 3} (into {} (map (juxt :item-id :quantity)) (:rows accepted))))
            (is (not (contains? (first (:operations accepted)) :receipts)))))))))


(deftest hidden-legacy-receipts-do-not-block-a-purchase-revision
  (with-store
    (fn [repo _]
      (let [purchase (save! repo {:kind  :operation :type  :purchase      :occurred-at "2026-01-01"
                                  :party "Supplier" :lines [(line "a" 5)]})]
        (submit! repo [purchase])
        (let [revision (save! repo {:kind        :revision :target-id (:id purchase) :reason "Correct purchase"
                                    :replacement {:kind     :operation :type  :purchase      :occurred-at "2026-01-01"
                                                  :party    "Supplier" :lines [(line "a" 7)]
                                                  :receipts [{:occurred-at "" :lines [{:quantity ""}]}]}})
              before   (store/snapshot repo scope)
              preview  (store/preview repo scope (request repo [revision]))]
          (is (true? (:valid? preview)))
          (is (= [7] (mapv :quantity (:rows preview))))
          (is (= [5] (mapv :quantity (:before-rows preview))))
          (is (= before (store/snapshot repo scope)))
          (submit! repo [revision])
          (is (= 7 (quantity repo "a")))
          (is (= 2 (count (:history (store/snapshot repo scope))))))))))
