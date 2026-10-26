(ns tover.stock.web-test
  (:require
    [bel.core :as bel]
    [bel.handler :as handler]
    [bel.realtime :as realtime]
    [charred.api :as charred]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [tover.inventory.fixture :as historical]
    [tover.inventory.ledger :as ledger]
    [tover.inventory.store :as store]
    [tover.inventory.workbench :as workbench]
    [tover.shared.rdf :as rdf]
    [tover.stock.web :as web])
  (:import
    [java.nio.file Files]))


(def scope
  {:workspace-id #uuid "00000000-0000-0000-0000-000000000001"})


(defn with-context
  {:malli/schema [:=> [:cat [:=> [:cat :map] :any]] :any]}
  [run]
  (let [path   (.toFile (Files/createTempDirectory "tover-workbench-" (make-array java.nio.file.attribute.FileAttribute 0)))
        repo   (rdf/native-store path)
        stream (realtime/start!)]
    (try
      (run (merge scope {:repo repo :realtime stream :user-id #uuid "00000000-0000-0000-0000-000000000002" :role :owner}))
      (finally
        (realtime/stop! stream)
        (rdf/close! repo)
        (doseq [file (reverse (file-seq path))] (io/delete-file file))))))


(defn html
  {:malli/schema [:=> [:cat :any] :string]}
  [result]
  (handler/render-html (mapv :html (filter #(= :elements (:type %)) (if (map? result) [result] result)))))


(defn opening
  {:malli/schema [:=> [:cat :string :int] :map]}
  [name quantity]
  {:kind  :opening :occurred-at "2026-10-20T12:00:00Z" :reason "Existing stock"
   :lines [{:item-id name :name name :article name :barcode "" :unit "pcs" :location "Shelf" :quantity quantity}]})


(defn save-opening
  {:malli/schema [:=> [:cat :map :string :int] :map]}
  [context name quantity]
  (store/save-entry! (:repo context) context {:entry (opening name quantity) :actor (str (:user-id context))}))


(deftest column-layouts-follow-the-authenticated-user-without-changing-inventory
  (with-context
    (fn [context]
      (save-opening context "Layout goods" 4)
      (let [before (store/snapshot (:repo context) context)
            other  (assoc context :user-id #uuid "00000000-0000-0000-0000-000000000003")
            result (web/save-layout context {:gridId "stock" :widths {"name" 375}
                                             :order  ["article" "name" "location" "quantity" "unit" "barcode"]
                                             :userId (str (:user-id other))})
            data   (:data result)
            grid   (fn [actor]
                     (charred/read-json (get (workbench/view-snapshot (workbench/query-stock actor {})) :stockGridJson)
                                        :key-fn keyword))
            own    (grid context)]
        (is (= "Saved" (:_layoutStatus data)))
        (is (not (contains? data :_inventorySnapshot)))
        (is (not (contains? data :_review)))
        (is (= "article" (get-in own [:columns 0 :key])))
        (is (= 375 (get-in own [:savedWidths :name])))
        (is (= "name" (get-in (grid other) [:columns 0 :key])))
        (is (= 375 (get-in (grid (assoc context :workspace-id (random-uuid))) [:savedWidths :name])))
        (is (= before (store/snapshot (:repo context) context)))
        (is (= "Failed" (get-in (web/save-layout context {:gridId "stock" :order ["name" "name"]}) [:data :_layoutStatus])))
        (is (= "article" (get-in (grid context) [:columns 0 :key])))))))


(deftest shared-draft-field-save-survives-query-and-rejects-stale-edit
  (with-context
    (fn [context]
      (let [draft  (save-opening context "Goods" 4)
            input  {:id (:id draft) :revision 1 :path "lines/0/name" :value "Renamed goods" :view "stock"}
            result (web/save-field context input)]
        (is (= "Renamed goods" (get-in (store/snapshot (:repo context) context) [:drafts 0 :entry :lines 0 :name])))
        (is (some #(= "Saved" (get-in % [:data :saveStatus])) result))
        (is (re-find #"changed since" (html (web/save-field context (assoc input :value "Stale overwrite"))))))
      (is (= [] (:rows (store/snapshot (:repo context) context)))))))


(deftest selected-preview-submit-and-idempotent-retry-use-server-ledger
  (with-context
    (fn [context]
      (let [first   (save-opening context "First" 4)
            second  (save-opening context "Second" 9)
            preview (web/preview context {:selected [(:id first)] :view "stock" :query ""})
            review  (get-in preview [0 :data :_review])]
        (is (re-find #"Submit selected" (html preview)))
        (is (re-find #"First" (html preview)))
        (is (not (re-find #"Second" (html preview))))
        (web/submit context {:review review :view "stock"})
        (is (= [4] (mapv :quantity (:rows (store/snapshot (:repo context) context)))))
        (is (= [(:id second)] (mapv :id (:drafts (store/snapshot (:repo context) context)))))
        (is (re-find #"Submitted 1 entries" (html (web/submit context {:review review :view "stock"}))))))))


(deftest removing-the-last-draft-closes-its-review-without-a-validation-error
  (with-context
    (fn [context]
      (let [entry (save-opening context "Removed" 4)]
        (store/discard-entry! (:repo context) context {:id (:id entry) :expected-revision 1})
        (doseq [selection [[] [(:id entry)]]]
          (let [result (web/preview context {:selected selection :view "stock"})]
            (is (= {:_draftPreview false :_review nil :_reviewDirty false :_reviewPending false}
                   (get-in result [0 :data])))
            (is (not (re-find #"role=\"alert\"|Resolve these conflicts" (html result))))
            (is (= [:section#inventory-review {:data-ignore-morph ""}] (get-in result [1 :html])))))))))


(deftest new-purchase-lines-directly-increase-stock
  (with-context
    (fn [context]
      (is (not (contains? (web/new-entry nil :purchase) :receipts)))
      (let [lines    [{:item-id "First" :name "First" :quantity 5 :unit "pcs" :location "Shelf"}
                      {:item-id "Second" :name "Second" :quantity 3 :unit "pcs" :location "Shelf"}
                      {:item-id "Third" :name "Third" :quantity 2 :unit "pcs" :location "Other"}]
            purchase (store/save-entry! (:repo context) context
                                        {:actor "owner" :entry (assoc (web/new-entry nil :purchase)
                                                                      :party "Supplier" :amount "100.00" :currency "EUR" :lines lines)})
            input    {:selected [(select-keys purchase [:id :revision])] :expected-ledger-revision 0}
            preview  (store/preview (:repo context) context input)]
        (is (:valid? preview))
        (is (empty? (:before-rows preview)))
        (is (= {"First" 5 "Second" 3 "Third" 2} (into {} (map (juxt :item-id :quantity) (:rows preview)))))
        (store/submit! (:repo context) context (assoc input :command-id "direct-purchase" :actor "owner"))
        (let [saved (store/snapshot (:repo context) context)]
          (is (= {"First" 5 "Second" 3 "Third" 2} (into {} (map (juxt :item-id :quantity) (:rows saved)))))
          (is (not (contains? (first (:operations saved)) :receipts))))))))


(deftest editing-a-shared-purchase-draft-removes-legacy-receipts
  (with-context
    (fn [context]
      (doseq [command ["save-field" "add-line" "remove-line"]]
        (let [purchase (store/save-entry! (:repo context) context
                                          {:actor "owner" :entry (assoc (opening "Goods" 5) :kind :operation :type :purchase
                                                                        :party "Supplier" :receipts [])})
              input    {:id (:id purchase) :revision 1}]
          (if (= command "save-field")
            (web/save-field context (assoc input :path "reason" :value "Use direct purchase"))
            (web/draft-command context (assoc input :command command :path (if (= command "add-line") "lines" "lines/0"))))
          (let [saved (some #(when (= (:id purchase) (:id %)) %) (:drafts (store/snapshot (:repo context) context)))]
            (is (= 2 (:revision saved)))
            (is (not (contains? (:entry saved) :receipts)))))))))


(deftest correcting-a-legacy-purchase-previews-direct-stock-without-rewriting-history
  (with-context
    (fn [context]
      (let [purchase {:kind        :operation :type               :purchase :party    "Supplier"
                      :occurred-at "2026-10-20T12:00:00Z" :amount "100.00"  :currency "EUR"
                      :lines       [{:item-id "Goods" :name "Goods" :quantity 10 :unit "pcs" :location "Shelf"}]
                      :receipts    [{:occurred-at "2026-10-20T12:00:00Z"
                                     :lines       [{:item-id "Goods" :name "Goods" :quantity 3 :unit "pcs" :location "Shelf"}]}]}]
        (historical/accept-history! (:repo context) context [purchase])
        (let [before   (store/snapshot (:repo context) context)
              accepted (first (:operations before))
              records  (store/operation-records (:repo context) context (:id accepted))]
          (web/revise-entry context {:targetId (:id accepted) :kind "revision" :view "operations"})
          (let [draft (first (:drafts (store/snapshot (:repo context) context)))]
            (is (= :revision (get-in draft [:entry :kind])))
            (is (= (:id accepted) (get-in draft [:entry :target-id])))
            (is (not (contains? (get-in draft [:entry :replacement]) :receipts)))
            (is (= (:lines accepted) (get-in draft [:entry :replacement :lines])))
            (is (= [3] (mapv :quantity (:rows (store/snapshot (:repo context) context)))))
            (is (= records (store/operation-records (:repo context) context (:id accepted))))
            (web/save-field context {:id (:id draft) :revision 1 :path "reason" :value "Use direct purchase quantity"})
            (let [pending (first (:drafts (store/snapshot (:repo context) context)))
                  input   {:selected [(select-keys pending [:id :revision])] :expected-ledger-revision 1}
                  preview (store/preview (:repo context) context input)]
              (is (:valid? preview))
              (is (= [3] (mapv :quantity (:before-rows preview))))
              (is (= [10] (mapv :quantity (:rows preview))))
              (store/submit! (:repo context) context (assoc input :command-id "simplify-purchase" :actor "owner"))
              (let [saved   (store/snapshot (:repo context) context)
                    updated (first (:operations saved))]
                (is (= [10] (mapv :quantity (:rows saved))))
                (is (= (:id accepted) (:id updated)))
                (is (= 100.00M (:amount updated)))
                (is (not (contains? updated :receipts)))
                (is (= records (take (count records) (store/operation-records (:repo context) context (:id accepted)))))))))))))


(deftest stock-cell-edit-is-a-durable-correction-with-trusted-actor-and-scope
  (with-context
    (fn [context]
      (let [draft     (save-opening context "Goods" 4)
            selection {:selected [(select-keys draft [:id :revision])] :expected-ledger-revision 0 :command-id "opening" :actor "owner"}
            _         (store/submit! (:repo context) context selection)
            snapshot  (store/snapshot (:repo context) context)
            row       (first (:rows snapshot))]
        (web/edit-stock context {:rowId        (:id row)     :field "quantity" :value "7" :ledgerRevision 1 :view "stock"
                                 :workspace-id (random-uuid) :actor "forged"})
        (let [pending (first (:drafts (store/snapshot (:repo context) context)))]
          (is (= :correction (get-in pending [:entry :kind])))
          (is (= "Manual quantity correction" (get-in pending [:entry :reason])))
          (is (:valid? (store/preview (:repo context) context {:selected [(select-keys pending [:id :revision])] :expected-ledger-revision 1})))
          (is (= 7 (get-in pending [:entry :lines 0 :quantity])))
          (is (= (str (:user-id context)) (:actor pending))))
        (is (= [4] (mapv :quantity (:rows (store/snapshot (:repo context) context)))))
        (is (re-find #"Stock changed" (html (web/edit-stock context {:rowId (:id row) :field "quantity" :value "7" :ledgerRevision 0}))))))))


(deftest filtered-stock-still-acknowledges-saved-values-and-revisions
  (with-context
    (fn [context]
      (let [entry (save-opening context "OLD" 4)]
        (store/submit! (:repo context) context {:selected                 [(select-keys entry [:id :revision])]
                                                :expected-ledger-revision 0 :command-id "filtered-opening" :actor "owner"})
        (let [accepted (first (:rows (store/snapshot (:repo context) context)))
              input    {:filters {"article" [{:id "article" :type "begins" :value "OLD"}]}}
              before   (workbench/accepted-grid (workbench/query-stock context input))]
          (is (= 1 (count (:rows before))))
          (is (empty? (:editStates before)))
          (web/edit-stock context {:rowId (:id accepted) :field "article" :value "NEW" :ledgerRevision 1})
          (let [model   (workbench/accepted-grid (workbench/query-stock context (assoc input :ackRowId (:id accepted))))
                article (some #(when (= "article" (get-in % [:target :path])) %) (:editStates model))]
            (is (empty? (:rows model)))
            (is (= "NEW" (:value article)))
            (is (= (:id accepted) (get-in article [:target :rowId])))
            (is (= 1 (get-in article [:target :draftRevision])))
            (is (= 1 (get-in article [:target :ledgerRevision])))
            (is (every? #(= #{:target :value} (set (keys %))) (:editStates model)))
            (is (empty? (:editStates (workbench/accepted-grid
                                       (workbench/query-stock (assoc context :workspace-id (random-uuid)) (assoc input :ackRowId (:id accepted)))))))))))))


(deftest workbench-search-and-history-only-read-authorized-workspace
  (with-context
    (fn [context]
      (let [draft (save-opening context "Goods" 4)]
        (store/submit! (:repo context) context {:selected   [(select-keys draft [:id :revision])] :expected-ledger-revision 0
                                                :command-id "opening" :actor "owner"}))
      (is (= ["Goods"] (mapv :name (get-in (workbench/query-stock context {:filters {"name" [{:id "goods" :type "begins" :value "Goo"}]}}) [:model :rows]))))
      (is (empty? (get-in (workbench/query-stock context {:filters {"name" [{:id "goods" :type "begins" :value "absent"}]}}) [:model :rows])))
      (is (re-find #"data-item-id=\"Goods\"" (html (web/open-history context {:itemId "Goods" :rowId (ledger/row-id "Goods" "Shelf") :requestId "history-test"}))))
      (is (not (re-find #"Goods" (html (web/open-history (assoc context :workspace-id (random-uuid)) {:itemId "Goods" :rowId (ledger/row-id "Goods" "Shelf") :requestId "history-test"}))))))))


(deftest declarations-are-workspace-protected-and-use-one-authoritative-refresh
  (bel/init! {:scan-ns ['tover]})
  (doseq [id [:tover.stock.web/add-entry :tover.stock.web/save-field :tover.stock.web/draft-command
              :tover.stock.web/edit-stock :tover.stock.web/revise-entry :tover.stock.web/preview
              :tover.stock.web/submit :tover.stock.web/open-history]]
    (is (= :workspace (get-in (bel/get-action id) [:bel/route-data :tover/access]))))
  (is (string? (get-in (web/changed {} {}) [:data :inventoryChanged])))
  (is (not= (:data (web/changed {} {})) (:data (web/changed {} {}))))
  (is (:bel/resnapshot-on-open? (meta #'web/changed))))


(deftest history-requests-bind-the-item-row-and-response-token
  (with-context
    (fn [context]
      (let [draft (save-opening context "Goods" 4)]
        (store/submit! (:repo context) context {:selected                 [(select-keys draft [:id :revision])]
                                                :expected-ledger-revision 0 :command-id "history-row" :actor "owner"}))
      (let [input {:itemId "Goods" :rowId (ledger/row-id "Goods" "Shelf") :requestId "latest-request"}
            table (web/open-history context input)
            chart (web/open-history context (assoc input :mode "lineage"))]
        (is (= "#history-panel[data-request-id='latest-request']" (:selector table)))
        (is (re-find #"item-history-grid" (html table)))
        (is (not (re-find #"class=\"inventory-timeline\"" (html table))))
        (is (re-find #"inventory-timeline" (html chart)))
        (is (not (re-find #"item-history-grid" (html chart))))
        (is (re-find #"unavailable" (html (web/open-history context (assoc input :rowId "wrong@Shelf")))))
        (is (= (:selector table) (:selector (web/close-history context input))))))))


(deftest operation-type-switch-discards-incompatible-roles-and-can-submit-payment
  (with-context
    (fn [context]
      (let [draft (store/save-entry! (:repo context) context
                                     {:actor "owner" :entry {:kind        :operation                   :type     :purchase :party "Supplier"
                                                             :occurred-at "2026-10-20T12:00:00Z"       :reason   "Order"
                                                             :lines       [{:item-id "a" :quantity 1}] :receipts []}})]
        (web/save-field context {:id (:id draft) :revision 1 :path "type" :value "production"})
        (let [entry (get-in (store/snapshot (:repo context) context) [:drafts 0 :entry])]
          (is (= :production (:type entry)))
          (is (seq (:inputs entry)))
          (is (seq (:outputs entry)))
          (is (not (contains? entry :lines)))
          (is (not (contains? entry :receipts))))
        (web/save-field context {:id (:id draft) :revision 2 :path "type" :value "payment"})
        (let [entry (get-in (store/snapshot (:repo context) context) [:drafts 0 :entry])]
          (is (= :payment (:type entry)))
          (is (not-any? #(contains? entry %) [:lines :inputs :outputs :receipts])))
        (doseq [[revision path value] [[3 "party" "Supplier"] [4 "amount" "12.34"] [5 "currency" "EUR"]]]
          (web/save-field context {:id (:id draft) :revision revision :path path :value value}))
        (let [preview (web/preview context {:selected [(:id draft)]})
              review  (get-in preview [0 :data :_review])]
          (is review)
          (web/submit context {:review review})
          (is (= :payment (get-in (store/snapshot (:repo context) context) [:operations 0 :type]))))))))


(deftest inline-location-correction-keeps-source-anchor-and-destination-balance
  (with-context
    (fn [context]
      (let [a (store/save-entry! (:repo context) context {:actor "owner" :entry (opening "Goods" 5)})
            b (store/save-entry! (:repo context) context {:actor "owner" :entry (assoc-in (opening "Goods" 2) [:lines 0 :location] "Other")})]
        (store/submit! (:repo context) context {:actor                    "owner" :command-id "opening"
                                                :expected-ledger-revision 0       :selected   (mapv #(select-keys % [:id :revision]) [a b])}))
      (let [snapshot (store/snapshot (:repo context) context)
            source   (some #(when (= "Shelf" (:location %)) %) (:rows snapshot))
            request  {:rowId (:id source) :field "location" :value "Other" :ledgerRevision 1}]
        (web/edit-stock context request)
        (let [initial-draft (first (:drafts (store/snapshot (:repo context) context)))
              versions      {:draftId (:id initial-draft) :draftRevision 1}]
          (is (= [["Shelf" 0] ["Other" 7]] (mapv (juxt :location :quantity) (get-in initial-draft [:entry :lines]))))
          (is (re-find #"correction changed" (html (web/edit-stock context (assoc request :field "quantity" :value "9")))))
          (web/edit-stock context (merge request versions {:field "quantity" :value "3"}))
          (let [updated (first (:drafts (store/snapshot (:repo context) context)))]
            (is (= [["Shelf" 0] ["Other" 5]] (mapv (juxt :location :quantity) (get-in updated [:entry :lines]))))
            (is (= (:id initial-draft) (:id updated)))
            (web/edit-stock context (merge request {:draftId (:id updated) :draftRevision 2 :value "Shelf"}))
            (let [reverted (first (:drafts (store/snapshot (:repo context) context)))]
              (is (= [["Shelf" 3]] (mapv (juxt :location :quantity) (get-in reverted [:entry :lines]))))
              (is (= (:id initial-draft) (:id reverted))))))))))


(deftest clearing-optional-root-and-replacement-fields-preserves-valid-draft-maps
  (with-context
    (fn [context]
      (doseq [[kind prefix] [[:operation ""] [:revision "replacement/"]]]
        (let [operation {:kind      :operation :type  :payment   :occurred-at "2026-10-20T12:00:00Z" :reason   "Payment"
                         :direction :outgoing  :party "Supplier" :amount      "12.34"                :currency "EUR"     :original-operation-id "reference"}
              entry     (if (= :revision kind) {:kind :revision :target-id "target" :reason "Correct payment" :replacement operation} operation)
              draft     (store/save-entry! (:repo context) context {:actor "owner" :entry entry})]
          (doseq [[revision field] [[1 "amount"] [2 "currency"] [3 "original-operation-id"]]]
            (web/save-field context {:id (:id draft) :revision revision :path (str prefix field) :value ""}))
          (let [saved     (some #(when (= (:id draft) (:id %)) %) (:drafts (store/snapshot (:repo context) context)))
                operation (if (= :revision kind) (get-in saved [:entry :replacement]) (:entry saved))]
            (is (= 4 (:revision saved)))
            (is (not-any? #(contains? operation %) [:amount :currency :original-operation-id nil]))
            (is (= "Supplier" (:party operation)))
            (is (not (contains? (:entry saved) nil)))))))))


(deftest pending-item-catalog-allows-opening-and-production-to-submit-together
  (with-context
    (fn [context]
      (let [opening    (save-opening context "Bulk" 6)
            production (store/save-entry! (:repo context) context
                                          {:actor "owner" :entry {:kind        :operation :type :production :reason "Make kit"
                                                                  :occurred-at "2026-10-20T13:00:00Z"
                                                                  :inputs      [{:item-id "placeholder" :name "" :unit "pcs" :quantity 2 :location "Shelf"}]
                                                                  :outputs     [{:item-id "Kit" :name "Kit" :article "KIT" :unit "pcs" :quantity 1 :location "Shelf"}]}})
            props      (workbench/query-stock context {})]
        (is (= "Bulk" (get-in props [:model :items "Bulk" :name])))
        (is (= "Kit" (get-in props [:model :items "Kit" :name])))
        (is (= "Bulk" (get-in (workbench/item-catalog (:model (workbench/query-stock context {}))) ["Bulk" :name])))
        (web/save-field context {:id (:id production) :revision 1 :path "inputs/0/item-id" :value "Bulk"})
        (let [snapshot (store/snapshot (:repo context) context)
              updated  (some #(when (= (:id production) (:id %)) %) (:drafts snapshot))
              preview  (web/preview context {:selected [(:id opening) (:id production)]})
              review   (get-in preview [0 :data :_review])]
          (is (= "Bulk" (get-in updated [:entry :inputs 0 :name])))
          (is review)
          (web/submit context {:review review})
          (is (= {"Bulk" 4 "Kit" 1} (into {} (map (juxt :item-id :quantity)) (:rows (store/snapshot (:repo context) context)))))
          (is (empty? (:drafts (store/snapshot (:repo context) context)))))))))


(deftest new-stock-rows-edit-durable-opening-drafts-without-changing-accepted-inventory
  (with-context
    (fn [context]
      (web/add-entry context {:kind "opening" :view "stock"})
      (let [row      (first (get-in (workbench/query-stock context {}) [:model :rows]))
            draft-id (:draft-id row)]
        (is (= "sheet-row-added" (:_changeClass row)))
        (doseq [[revision field value] [[1 "name" "Inline opening"] [2 "quantity" "8"] [3 "location" "Shelf"]]]
          (web/edit-stock context {:rowId (:id row) :draftId draft-id :draftRevision  revision
                                   :field field     :value   value    :ledgerRevision 0        :view "stock"}))
        (let [saved (store/snapshot (:repo context) context)
              shown (first (get-in (workbench/query-stock context {}) [:model :rows]))]
          (is (empty? (:rows saved)))
          (is (= "Inline opening" (:name shown)))
          (is (= 8 (:quantity shown)))
          (is (= (:item-id row) (:item-id shown)))
          (is (= 4 (:draft-revision shown)))
          (is (re-find #"changed since" (html (web/edit-stock context {:rowId (:id row)  :draftId draft-id :draftRevision  1
                                                                       :field "quantity" :value   "99"     :ledgerRevision 0}))))
          (web/revert-drafts context {:selected [{:id draft-id :revision 4}] :view "stock"})
          (is (empty? (:drafts (store/snapshot (:repo context) context)))))))))


(deftest pending-cells-are-presented-with-accepted-before-values-and-server-header-order
  (with-context
    (fn [context]
      (let [draft (save-opening context "Goods" 4)]
        (store/submit! (:repo context) context {:selected                 [(select-keys draft [:id :revision])]
                                                :expected-ledger-revision 0 :command-id "display-opening" :actor "owner"}))
      (let [accepted (first (:rows (store/snapshot (:repo context) context)))]
        (web/edit-stock context {:rowId (:id accepted) :field "quantity" :value "7" :ledgerRevision 1})
        (let [row (first (get-in (workbench/query-stock context {:sort "quantity" :direction "desc"}) [:model :rows]))]
          (is (= 7 (:quantity row)))
          (is (= 4 (get-in row [:_accepted :quantity])))
          (is (= ["quantity"] (:_changedFields row)))
          (is (= "sheet-row-modified" (:_changeClass row)))
          (is (= 4 (:quantity (first (:rows (store/snapshot (:repo context) context)))))))))))


(deftest filter-responses-cannot-morph-another-view-or-an-older-request
  (with-context
    (fn [context]
      (let [result   (web/response context {:view "stock" :filterRequest 7 :filters {"name" [{:id "prefix" :type "begins" :value "G"}]}})
            snapshot (get-in result [0 :data :_inventorySnapshot])]
        (is (= 7 (:request snapshot)))
        (is (= "stock" (:view snapshot)))
        (is (string? (:filtersJson snapshot)))
        (is (= [] (:draftIds snapshot)))
        (doseq [message (rest result)]
          (is (re-find #"data-view='stock'" (:selector message)))
          (is (re-find #"data-filter-request='7'" (:selector message))))
        (is (not (re-find #"id=\"inventory-evidence-workbench\"" (html result))))))))


(deftest preview-errors-link-to-the-incomplete-entry-field
  (with-context
    (fn [context]
      (let [draft  (store/save-entry! (:repo context) context {:actor "owner" :entry (web/new-entry :operation :payment)})
            result (web/preview context {:selected [(:id draft)] :view "stock"})
            page   (html result)]
        (is (nil? (get-in result [0 :data :_review])))
        (is (re-find #"party is required" page))
        (is (re-find #"Edit field" page))
        (is (re-find (re-pattern (:id draft)) page))
        (is (re-find #"focusField\([^)]*party" page))))))


(deftest financial-facts-remain-in-the-single-review-without-stock-effects
  (with-context
    (fn [context]
      (let [draft (store/save-entry! (:repo context) context
                                     {:actor "owner" :entry {:kind      :operation :type  :payment   :occurred-at "2026-10-20T12:00:00Z"
                                                             :direction :outgoing  :party "Supplier" :amount      "19.45"                :currency "EUR"}})
            page  (html (web/preview context {:selected [(:id draft)] :view "operations"}))]
        (is (re-find #"No stock changes" page))
        (let [model (:model (workbench/query-stock context {:view "operations"}))
              cells (mapcat :cells (:rows (tover.inventory.grid/drafts model)))]
          (doseq [value ["19.45" "EUR" "Supplier" "outgoing"]]
            (is (some #(= value (:value %)) cells))))
        (is (not (re-find #"Edit reviewed entry|inventory-entry-facts" page)))
        (is (not (re-find #"<ul|<li" page)))))))
