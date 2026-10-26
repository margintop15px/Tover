(ns tover.inventory.smart-web-test
  (:require
    [bel.core :as bel]
    [charred.api :as charred]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [tover.inventory.smart :as smart]
    [tover.inventory.smart-web :as web]
    [tover.inventory.store :as store]
    [tover.shared.rdf :as rdf]
    [tover.stock.web :as stock])
  (:import
    [java.nio.file Files]))


(defn with-context
  {:malli/schema [:=> [:cat [:=> [:cat :map] :any]] :any]}
  [run]
  (let [path (.toFile (Files/createTempDirectory "tover-smart-" (make-array java.nio.file.attribute.FileAttribute 0)))
        repo (rdf/native-store path)]
    (try (run {:repo repo :workspace-id (random-uuid) :user-id (random-uuid)})
         (finally (rdf/close! repo)
                  (doseq [file (reverse (file-seq path))] (io/delete-file file))))))


(defn input
  {:malli/schema [:=> [:cat :map :map] :map]}
  [state option]
  {:state state :option option :text "" :filters {} :view "stock" :request 1})


(defn response-model
  {:malli/schema [:=> [:cat [:vector :map]] :map]}
  [messages]
  (charred/read-json (get-in (last messages) [:data :_smartModelJson]) :key-fn keyword))


(defn compose
  {:malli/schema [:=> [:cat :map :string [:vector [:tuple :string :string]]] :map]}
  [context type values]
  (reduce (fn [result [key value]]
            (web/complete* context (input (:state result) {:kind "value" :key key :value value})))
          (web/complete* context (input {} {:kind "type" :type type})) values))


(deftest add-flow-autosaves-private-fields-without-shared-changes-or-stock
  (with-context
    (fn [context]
      (let [types    (web/complete* context {:state {} :text "ass"})
            created  (web/complete* context (input (:state types) {:kind "type" :type "production"}))
            state    (:state created)
            saved    (web/complete* context (input state {:kind "value" :key "inputs/0/name" :value "Bulk"}))
            snapshot (store/snapshot (:repo context) context)]
        (is (= "types" (get-in types [:state :mode])))
        (is (= "add" (:mode state)))
        (is (= 2 (get-in saved [:state :revision])))
        (is (= "Bulk" (get-in snapshot [:compositions 0 :entry :inputs 0 :name])))
        (is (empty? (:drafts snapshot)))
        (is (empty? (:rows snapshot)))
        (is (empty? (:history snapshot)))
        (is (empty? (:compositions (store/snapshot (:repo context) (assoc context :user-id (random-uuid))))))
        (is (empty? (:compositions (store/snapshot (:repo context) (assoc context :workspace-id (random-uuid))))))))))


(deftest private-completion-does-not-publish-shared-draft-events
  (with-context
    (fn [context]
      (let [published (atom [])]
        (with-redefs [stock/publish! (fn [_ topics] (swap! published conj topics))]
          (let [messages (web/complete context (input {} {:kind "type" :type "payment"}))]
            (is (empty? @published))
            (is (not-any? #(= :elements (:type %)) (remove #(= "#inventory-notice" (:selector %)) messages)))
            (is (= "Saved" (some #(get-in % [:data :saveStatus]) messages)))))))))


(deftest simple-filter-edit-and-complex-replacement-use-canonical-server-filters
  (with-context
    (fn [context]
      (let [field    (web/complete* context (input {} {:kind "field" :key "location"}))
            filtered (web/complete* context (input (:state field) {:kind "value" :key "location" :value "Main shelf"}))
            default  (web/complete* context (input {} {:kind "value" :value "Note"}))
            complex  {"name" [{:id "a" :type "notEq" :value "Excluded"}]}
            request  (assoc (input {} {:kind "value" :key "name" :value "Note"}) :filters complex)]
        (is (= "begins" (get-in filtered [:filters "location" 0 :type])))
        (is (= "Main shelf" (get-in filtered [:filters "location" 0 :value])))
        (is (= "Note" (get-in default [:filters "name" 0 :value])))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Replace" (web/complete* context request)))
        (is (= "begins" (get-in (web/complete* context (assoc-in request [:option :kind] "replace-filter")) [:filters "name" 0 :type])))
        (is (empty? (:drafts (store/snapshot (:repo context) context))))))))


(deftest stale-completion-retains-attempt-with-explicit-resolution
  (with-context
    (fn [context]
      (let [created  (web/complete* context (input {} {:kind "type" :type "purchase"}))
            state    (assoc (:state created) :field "party")
            _        (web/complete* context (input state {:kind "value" :key "party" :value "Current supplier"}))
            messages (web/complete context (input state {:kind "value" :key "party" :value "Attempted supplier"}))
            model    (response-model messages)]
        (is (:failed model))
        (is (= "Attempted supplier" (:text model)))
        (is (= "Current supplier" (get-in model [:conflict :saved])))
        (is (= ["use-saved" "apply-mine"] (mapv :kind (:options model))))
        (let [saved (web/complete* context (input (:state model) {:kind "use-saved"}))]
          (is (nil? (get-in saved [:state :field])))
          (is (= "Current supplier" (get-in (store/snapshot (:repo context) context) [:compositions 0 :entry :party]))))
        (web/complete* context (input (:state model) {:kind "apply-mine"}))
        (is (= "Attempted supplier" (get-in (store/snapshot (:repo context) context) [:compositions 0 :entry :party])))))))


(deftest fields-can-be-cleared-and-an-unfinished-operation-cancelled
  (with-context
    (fn [context]
      (let [created  (compose context "sale" [["party" "Customer"]])
            cleared  (web/complete* context (input (:state created) {:kind "remove" :key "party"}))
            snapshot (web/snapshot context)
            model    (smart/model snapshot (:state cleared) "" {} 1)]
        (is (= "" (get-in snapshot [:compositions 0 :entry :party])))
        (is (some #(= "party" (:key %)) (:options model)))
        (is (not (:canDone model)))
        (web/complete* context (input (:state cleared) {:kind "cancel"}))
        (is (empty? (:compositions (store/snapshot (:repo context) context))))))))


(deftest cancelling-type-selection-preserves-filters-and-other-unfinished-work
  (with-context
    (fn [context]
      (let [saved     (compose context "payment" [["party" "Other unfinished payment"]])
            filters   {"name" [{:id "goods" :type "begins" :value "Notebook"}]}
            request   (assoc (input {:mode "types"} {:kind "cancel"}) :filters filters :text "pur")
            cancelled (web/complete* context request)]
        (is (= {:mode "filter"} (:state cancelled)))
        (is (= filters (:filters cancelled)))
        (is (= "" (:text cancelled)))
        (is (:closeMenu cancelled))
        (is (= [(get-in saved [:state :id])] (mapv :id (:compositions (web/snapshot context)))))
        (is (nil? (get-in (response-model (web/complete context request)) [:state :id])))))))


(deftest cancellation-discards-only-the-current-private-composition
  (with-context
    (fn [context]
      (let [other     (compose context "payment" [["party" "Keep private"]])
            shared    (store/save-entry! (:repo context) context {:entry {:kind :operation :type :payment} :actor "owner"})
            current   (compose context "purchase" [["party" "Cancel private"]])
            cancelled (web/complete* context (input (:state current) {:kind "cancel"}))
            snapshot  (web/snapshot context)]
        (is (= "filter" (get-in cancelled [:state :mode])))
        (is (= [(get-in other [:state :id])] (mapv :id (:compositions snapshot))))
        (is (= [(:id shared)] (mapv :id (:drafts snapshot))))
        (is (empty? (:history snapshot)))
        (is (some #(and (= "party" (:key %)) (= "Keep private" (:value %))) (:chips (smart/initial-model snapshot {}))))))))


(deftest stale-cancellation-preserves-the-attempt-and-current-composition
  (with-context
    (fn [context]
      (let [created  (compose context "payment" [["party" "Original"]])
            _        (web/complete* context (input (:state created) {:kind "value" :key "party" :value "Saved later"}))
            messages (web/complete context (assoc (input (:state created) {:kind "cancel"}) :text "Unacknowledged input"))
            model    (response-model messages)]
        (is (:failed model))
        (is (= "Unacknowledged input" (:text model)))
        (is (= "Updated unfinished operation" (get-in model [:conflict :saved])))
        (is (not (re-find #"Revision [0-9]" (str model))))
        (is (= "Saved later" (get-in (web/snapshot context) [:compositions 0 :entry :party])))))))


(deftest cancelling-an-already-completed-composition-preserves-its-shared-entry
  (with-context
    (fn [context]
      (let [created   (compose context "payment" [["party" "Supplier"] ["amount" "12"] ["currency" "EUR"]])
            done      (web/complete* context (input (:state created) {:kind "done" :commandId "complete-before-cancel"}))
            request   (assoc (input (:state created) {:kind "cancel"}) :filters {"name" [{:id "goods" :type "begins" :value "Notebook"}]})
            cancelled (web/complete* context request)
            repeated  (web/complete* context request)
            snapshot  (web/snapshot context)]
        (is (= "filter" (get-in cancelled [:state :mode])))
        (is (= (:filters request) (:filters cancelled)))
        (is (= cancelled repeated))
        (is (= [(get-in done [:completed :id])] (mapv :id (:drafts snapshot))))
        (is (empty? (:compositions snapshot)))))))


(deftest incomplete-done-errors-lead-to-specific-fields
  (with-context
    (fn [context]
      (let [created  (compose context "payment" [])
            messages (web/complete context (input (:state created) {:kind "done" :commandId "incomplete"}))
            model    (response-model messages)]
        (is (:failed model))
        (is (some #(= ["party"] (:path %)) (:errors model)))
        (is (some #(= ["amount"] (:path %)) (:errors model)))
        (is (empty? (:drafts (store/snapshot (:repo context) context))))))))


(deftest done-applies-active-field-atomically-and-is-idempotent-after-response-loss
  (with-context
    (fn [context]
      (let [composed  (compose context "payment" [["party" "Supplier"] ["amount" "12.34"]])
            state     (assoc (:state composed) :field "currency")
            request   (assoc (input state {:kind "done" :commandId "smart-payment-done"}) :text "EUR")
            done      (web/complete* context request)
            retried   (web/complete* context request)
            draft     (:completed done)
            selection {:selected [(select-keys draft [:id :revision])] :expected-ledger-revision 0}]
        (is (= (:completed done) (:completed retried)))
        (is (= "filter" (get-in done [:state :mode])))
        (is (= "EUR" (get-in draft [:entry :currency])))
        (is (empty? (:compositions (store/snapshot (:repo context) context))))
        (is (= 1 (count (:drafts (store/snapshot (:repo context) context)))))
        (is (:valid? (store/preview (:repo context) context selection)))
        (store/submit! (:repo context) context (assoc selection :command-id "smart-payment-submit" :actor "owner"))
        (is (= :payment (get-in (store/snapshot (:repo context) context) [:operations 0 :type])))
        (is (empty? (:rows (store/snapshot (:repo context) context))))))))


(deftest selecting-an-existing-item-keeps-its-stable-id-and-metadata
  (with-context
    (fn [context]
      (let [item-entry {:kind  :opening :occurred-at "2026-10-20T12:00:00Z" :reason "Existing"
                        :lines [{:item-id "notebook" :name "Notebook" :article "NB-1" :barcode "123" :unit "pcs" :location "Main" :quantity 3}]}
            _          (store/save-entry! (:repo context) context {:entry item-entry :actor "owner"})
            created    (web/complete* context (input {} {:kind "type" :type "sale"}))
            updated    (web/complete* context (input (:state created) {:kind "value" :key "lines/0/name" :value "Notebook" :itemId "notebook"}))
            draft      (smart/composition (store/snapshot (:repo context) context) (get-in updated [:state :id]))]
        (is (= "notebook" (get-in draft [:entry :lines 0 :item-id])))
        (is (= "NB-1" (get-in draft [:entry :lines 0 :article])))
        (is (= "123" (get-in draft [:entry :lines 0 :barcode])))))))


(deftest selected-known-item-prefills-all-fields-in-one-durable-smart-save
  (with-context
    (fn [context]
      (let [opening  (store/save-entry! (:repo context) context
                                        {:actor "owner" :entry {:kind  :opening :occurred-at "2026-10-20T12:00:00Z" :reason "Existing"
                                                                :lines [{:item-id "notebook" :name "Notebook" :article "NB-1" :barcode "123" :unit "pcs" :location "Main" :quantity 3}]}})
            _        (store/submit! (:repo context) context {:actor    "owner" :command-id "prefill-opening" :expected-ledger-revision 0
                                                             :selected [(select-keys opening [:id :revision])]})
            created  (compose context "production" [["inputs/0/quantity" "2"]])
            selected (web/complete* context (input (:state created) {:kind "value" :key "inputs/0/name" :value "Notebook" :itemId "notebook"}))
            saved    (smart/composition (web/snapshot context) (get-in selected [:state :id]))
            model    (smart/model (web/snapshot context) (:state selected) "" {} 3)]
        (is (= (inc (get-in created [:state :revision])) (:revision saved)))
        (is (= {:item-id "notebook" :name "Notebook" :article "NB-1" :barcode "123" :unit "pcs" :quantity 2 :location "Main"}
               (get-in saved [:entry :inputs 0])))
        (is (every? (set (map :key (:chips model))) ["inputs/0/name" "inputs/0/article" "inputs/0/barcode" "inputs/0/unit" "inputs/0/location"]))
        (is (not-any? #(= "inputs/0/article" (:key %)) (:options model)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Choose an available item"
                              (web/complete* context (input (:state selected) {:kind "value" :key "outputs/0/name" :value "Foreign" :itemId "foreign"}))))))))


(deftest production-inputs-and-outputs-remain-one-private-entry
  (with-context
    (fn [context]
      (let [created (web/complete* context (input {} {:kind "type" :type "production"}))
            updated (web/complete* context (input (:state created) {:kind "line" :key "inputs"}))
            draft   (first (:compositions (store/snapshot (:repo context) context)))]
        (is (= (get-in created [:state :id]) (get-in updated [:state :id])))
        (is (= "inputs/1/name" (get-in updated [:state :field])))
        (is (= 2 (count (get-in draft [:entry :inputs]))))
        (is (= 1 (count (get-in draft [:entry :outputs]))))))))


(deftest smart-actions-require-current-workspace-membership
  (bel/init! {:scan-ns ['tover]})
  (doseq [id [:tover.inventory.smart-web/suggest :tover.inventory.smart-web/complete]]
    (is (= :workspace (get-in (bel/get-action id) [:bel/route-data :tover/access])))))


(deftest default-fields-can-be-cleared-and-edited-again
  (with-context
    (fn [context]
      (let [created  (compose context "payment" [])
            cleared  (web/complete* context (input (:state created) {:kind "remove" :key "direction"}))
            snapshot (web/snapshot context)
            model    (smart/model snapshot (:state cleared) "" {} 1)]
        (is (= "" (get-in snapshot [:compositions 0 :entry :direction])))
        (is (some #(= "direction" (:key %)) (:options model)))
        (is (not-any? #(= "direction" (:key %)) (:chips model)))
        (is (not (:canDone model)))))))


(deftest stale-done-has-resolution-even-with-no-active-field
  (with-context
    (fn [context]
      (let [complete (compose context "payment" [["party" "Supplier"] ["amount" "12.34"] ["currency" "EUR"]])
            _        (web/complete* context (input (:state complete) {:kind "value" :key "party" :value "Updated supplier"}))
            messages (web/complete context (input (:state complete) {:kind "done" :commandId "stale-done"}))
            model    (response-model messages)
            resolved (web/complete* context (input (:state model) {:kind "apply-mine"}))]
        (is (:failed model))
        (is (= ["use-saved" "apply-mine"] (mapv :kind (:options model))))
        (is (= "Updated supplier" (get-in resolved [:completed :entry :party])))
        (is (= 1 (count (:drafts (store/snapshot (:repo context) context)))))))))


(deftest clearing-goods-forgets-selection-and-unknown-new-goods-do-not-rename-an-existing-item
  (let [items   {"known" {:name "Notebook" :unit "pcs" :article "NB-1"}}
        entry   {:lines [{:item-id "known" :name "Notebook" :unit "pcs"}]}
        clear   (with-redefs [web/snapshot (fn [_] {:items items})]
                  (web/with-value {} entry "lines/0/name" "" {}))
        changed (with-redefs [web/snapshot (fn [_] {:items items})]
                  (web/with-value {} entry "lines/0/name" "Different goods" {}))]
    (is (not= "known" (get-in clear [:lines 0 :item-id])))
    (is (= "" (get-in clear [:lines 0 :name])))
    (is (not= "known" (get-in changed [:lines 0 :item-id])))
    (is (= "Different goods" (get-in changed [:lines 0 :name])))))


(deftest accidental-extra-production-line-can-be-removed-before-done
  (with-context
    (fn [context]
      (let [created  (compose context "production" [])
            added    (web/complete* context (input (:state created) {:kind "line" :key "inputs"}))
            snapshot (web/snapshot context)
            model    (smart/model snapshot (:state added) "" {} 1)
            removed  (web/complete* context (input (:state added) {:kind "remove-line" :key "inputs/1"}))
            draft    (first (:compositions (web/snapshot context)))]
        (is (some #(= "Remove input 2" (:label %)) (:options (smart/model snapshot (assoc (:state added) :field nil) "" {} 1))))
        (is (= 1 (count (get-in draft [:entry :inputs]))))
        (is (= 1 (count (get-in draft [:entry :outputs]))))
        (is (= (get-in created [:state :id]) (get-in removed [:state :id])))
        (is (= 3 (get-in removed [:state :revision])))
        (is (thrown? clojure.lang.ExceptionInfo (web/complete* context (input (:state removed) {:kind "remove-line" :key "inputs/0"}))))))))


(deftest incoming-json-column-keys-keep-explicit-complex-replacement-for-new-values
  (with-context
    (fn [context]
      (let [reply (web/model-response context {:state   {:field "name"} :view "stock" :text "Unknown goods" :request 1
                                               :filters {:name [{:id "one" :type "notEq" :value "Excluded"}
                                                                {:id "two" :type "contains" :value "Find" :relation "or"}]}})
            model (response-model [reply])]
        (is (= "replace-filter" (get-in model [:options 0 :kind])))
        (is (= "Unknown goods" (get-in model [:options 0 :value])))
        (is (= "Replace this column’s filter with begins…" (get-in model [:options 0 :description])))))))
