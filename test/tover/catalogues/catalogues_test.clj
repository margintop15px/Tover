(ns tover.catalogues.catalogues-test
  (:require
    [bel.core :as bel]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [tover.catalogues.query :as query]
    [tover.catalogues.store :as catalogue]
    [tover.catalogues.web :as web]
    [tover.inventory.item-selection :as item-selection]
    [tover.inventory.read-query :as read-query]
    [tover.inventory.store :as inventory]
    [tover.inventory.store-test :as fixture]
    [tover.inventory.windows :as windows]
    [tover.inventory.workbench :as workbench]
    [tover.shared.i18n :as i18n]
    [tover.shared.rdf :as rdf]
    [tover.stock.web :as stock])
  (:import
    [java.nio.file Files]))


(defn context
  {:malli/schema [:=> [:cat rdf/Repository] :map]}
  [repo]
  (assoc fixture/scope :repo repo :user-id #uuid "00000000-0000-0000-0000-000000000011"))


(defn patch!
  {:malli/schema [:=> [:cat :map :keyword :string :int :keyword :string] :map]}
  [ctx kind id revision field value]
  (web/save-record! ctx {:kind (name kind) :id id :revision revision :path (name field) :value value :commandId (str (random-uuid))}))


(defn read-row
  {:malli/schema [:=> [:cat :map :keyword :string] [:maybe :map]]}
  [ctx kind id]
  (inventory/read-snapshot (:repo ctx) ctx (fn [conn _] (catalogue/record conn ctx kind id))))


(defn submit!
  {:malli/schema [:=> [:cat :map :map] :map]}
  [ctx entry]
  (let [repo     (:repo ctx)
        draft    (inventory/save-entry! repo ctx {:entry entry :actor "alice"})
        snapshot (inventory/snapshot repo ctx)
        request  {:selected                    [(select-keys draft [:id :revision])]
                  :expected-ledger-revision    (:ledger-revision snapshot)
                  :expected-catalogue-revision (:catalogue-revision snapshot)
                  :actor "alice"
                  :command-id                  (str (random-uuid))}]
    (inventory/submit! repo ctx request)))


(deftest typed-relationships-create-and-link-atomically
  (fixture/with-store
    (fn [repo _]
      (let [ctx      (context repo)
            _        (patch! ctx :goods "g" 0 :name "Goods")
            input    {:kind "goods" :id "g" :revision 1 :path "category-id" :value "" :referenceName "Clothes" :commandId "typed"}
            saved    (web/save-record! ctx input)
            category (:category-id saved)]
        (is (= "Clothes" (:name (read-row ctx :categories category))))
        (is (= saved (web/save-record! ctx input)))
        (is (= :idempotency-conflict (:type (fixture/error-data #(web/save-record! ctx (assoc input :referenceName "Other"))))))
        (is (= :stale-catalogue (:type (fixture/error-data #(web/save-record! ctx (assoc input :referenceName "Orphan" :commandId "stale"))))))
        (is (empty? (inventory/read-snapshot repo ctx (fn [conn _] (catalogue/exact-records conn ctx :categories "Orphan")))))
        (is (= category (:category-id (web/save-record! ctx (assoc input :revision 2 :commandId "reuse")))))
        (let [store     (:store-id (web/save-record! ctx {:kind "goods" :id "g" :revision 3 :path "store-id" :value "" :referenceName "Online" :commandId "store"}))
              warehouse (:warehouse-id (web/save-record! ctx {:kind "stores" :id store :revision 1 :path "warehouse-id" :value "" :referenceName "Main" :commandId "warehouse"}))]
          (is (= "Online" (:name (read-row ctx :stores store))))
          (is (= "Main" (:location-key (read-row ctx :warehouses warehouse))))
          (patch! ctx :warehouses warehouse 1 :name "North")
          (is (= warehouse (:warehouse-id (web/save-record! ctx {:kind "stores" :id store :revision 2 :path "warehouse-id" :value "" :referenceName "Main" :commandId "alias"})))))
        (patch! ctx :categories "duplicate" 0 :name "Clothes")
        (is (= :validation (:type (fixture/error-data #(web/save-record! ctx (assoc input :revision 4 :commandId "ambiguous"))))))
        (is (= "" (:category-id (patch! ctx :goods "g" 4 :category-id ""))))
        (doseq [bad [(assoc input :revision 5 :referenceName " ")
                     (assoc input :revision 5 :value category)
                     (assoc input :revision 5 :path "name")]]
          (is (= :validation (:type (fixture/error-data #(web/save-record! ctx (assoc bad :commandId (str (random-uuid)))))))))
        (let [other (assoc ctx :workspace-id (:workspace-id fixture/other-scope))]
          (patch! other :goods "g" 0 :name "Other goods")
          (let [foreign (web/save-record! other (assoc input :commandId "foreign"))]
            (is (not= category (:category-id foreign)))
            (is (= "Clothes" (:name (read-row other :categories (:category-id foreign)))))))
        (is (empty? (:rows (inventory/snapshot repo ctx))))))))


(deftest table-names-reuse-catalogue-identities-and-keep-new-identities-private-until-submit
  (fixture/with-store
    (fn [repo _]
      (let [ctx      (context repo)
            _        (patch! ctx :goods "known" 0 :name "Known")
            _        (patch! ctx :goods "known" 1 :article "ART")
            entry    (assoc (stock/new-entry :opening nil) :lines [(assoc (stock/line) :quantity 2 :location "Entered")])
            selected (stock/table-goods ctx entry [:lines 0] "Known" false)
            fresh    (stock/table-goods ctx selected [:lines 0] "New goods" false)
            fresh-id (get-in fresh [:lines 0 :item-id])]
        (is (= "known" (get-in selected [:lines 0 :item-id])))
        (is (= "ART" (get-in selected [:lines 0 :article])))
        (is (= [2 "Entered"] ((juxt :quantity :location) (get-in selected [:lines 0]))))
        (is (not= "known" fresh-id))
        (is (= fresh-id (get-in (stock/table-goods ctx fresh [:lines 0] "New goods edited" false) [:lines 0 :item-id])))
        (patch! ctx :goods "same-name" 0 :name "Known")
        (is (= :validation (:type (fixture/error-data #(stock/table-goods ctx entry [:lines 0] "Known" false)))))
        (is (= "known" (get-in (stock/table-goods ctx entry [:lines 0] "known" true) [:lines 0 :item-id])))
        (is (= :validation (:type (fixture/error-data #(stock/table-goods (assoc ctx :workspace-id (:workspace-id fixture/other-scope)) entry [:lines 0] "known" true)))))
        (let [purchase (assoc fresh :kind :operation :type :purchase :party "New supplier")
              draft    (stock/save! ctx nil nil purchase)
              request  (fixture/request repo [draft])]
          (is (nil? (read-row ctx :goods fresh-id)))
          (is (:valid? (inventory/preview repo ctx request)))
          (is (nil? (read-row ctx :goods fresh-id)))
          (is (every? empty? (inventory/read-snapshot repo ctx (fn [conn _] [(catalogue/exact-records conn ctx :warehouses "Entered")
                                                                             (catalogue/exact-records conn ctx :suppliers "New supplier")]))))
          (inventory/submit! repo ctx (assoc request :actor "alice" :command-id "submit-table"))
          (is (= "New goods" (:name (read-row ctx :goods fresh-id))))
          (is (every? seq (inventory/read-snapshot repo ctx (fn [conn _] [(catalogue/exact-records conn ctx :warehouses "Entered")
                                                                          (catalogue/exact-records conn ctx :suppliers "New supplier")]))))
          (is (not-any? :catalogue-new? (tree-seq coll? seq (:history (inventory/snapshot repo ctx))))))
        (let [collision (stock/table-goods ctx entry [:lines 0] "Arrived later" false)
              draft     (stock/save! ctx nil nil collision)]
          (patch! ctx :goods "later" 0 :name "Arrived later")
          (is (false? (:valid? (inventory/preview repo ctx (fixture/request repo [draft])))))
          (is (= :validation (:type (fixture/error-data #(inventory/submit! repo ctx (assoc (fixture/request repo [draft]) :actor "alice" :command-id "collision"))))))
          (inventory/discard-entry! repo ctx {:id (:id draft) :expected-revision (:revision draft)})
          (is (nil? (read-row ctx :goods (get-in collision [:lines 0 :item-id])))))))))


(deftest table-edit-paths-resolve-names-without-reassigning-accepted-stock
  (fixture/with-store
    (fn [repo _]
      (let [ctx    (context repo)
            _      (submit! ctx (fixture/opening 3))
            draft  (stock/save! ctx nil nil (stock/new-entry :opening nil))
            edited (stock/save-field* ctx {:id (:id draft) :revision (:revision draft) :path "lines/0/name" :value "a"})]
        (is (= "a" (get-in edited [:entry :lines 0 :item-id])))
        (is (= "Main" (get-in edited [:entry :lines 0 :location])))
        (let [snapshot   (inventory/snapshot repo ctx)
              row        (first (:rows snapshot))
              correction (stock/save-stock-correction! ctx {:rowId (:id row) :field "name" :value "Renamed" :ledgerRevision (:ledger-revision snapshot)})]
          (is (= "a" (get-in correction [:entry :lines 0 :item-id])))
          (is (= :validation (:type (fixture/error-data #(stock/save-field* ctx {:id (:id correction) :revision (:revision correction) :path "lines/0/item-id" :value "wrong"}))))))
        (is (= "a" (:name (read-row ctx :goods "a"))))))))


(deftest concurrent-typed-links-reuse-one-created-record
  (fixture/with-store
    (fn [repo _]
      (let [ctx    (context repo)
            _      (doseq [id ["first" "second"]] (patch! ctx :goods id 0 :name id))
            writes (mapv (fn [id]
                           (future (web/save-record! ctx {:kind          "goods"      :id        id :revision 1 :path "category-id" :value ""
                                                          :referenceName "Concurrent" :commandId id}))) ["first" "second"])
            rows   (mapv deref writes)]
        (is (apply = (map :category-id rows)))
        (is (= 1 (count (inventory/read-snapshot repo ctx (fn [conn _] (catalogue/exact-records conn ctx :categories "Concurrent"))))))))))


(deftest catalogue-validation-relationships-conflicts-and-retries
  (fixture/with-store
    (fn [repo _]
      (let [ctx     (context repo)
            request {:kind "goods" :id "g" :revision 0 :path "name" :value "Socks" :commandId "create"}
            created (web/save-record! ctx request)]
        (is (= created (web/save-record! ctx request)))
        (is (= "pcs" (:unit created)))
        (is (empty? (:rows (inventory/snapshot repo ctx))))
        (is (= #{"g"} (inventory/read-snapshot repo ctx (fn [conn _] (set (keys (read-query/suggest-items conn ctx "Socks" 30)))))))
        (doseq [[kind id label] [[:warehouses "w" "Main"] [:suppliers "s" "Supplier"] [:categories "c" "Clothes"] [:stores "shop" "Online"]]]
          (is (= label (:name (patch! ctx kind id 0 :name label)))))
        (is (= "w" (:warehouse-id (patch! ctx :stores "shop" 1 :warehouse-id "w"))))
        (is (= "c" (:category-id (patch! ctx :goods "g" 1 :category-id "c"))))
        (is (= "shop" (:store-id (patch! ctx :goods "g" 2 :store-id "shop"))))
        (is (= "Main" (get-in (inventory/snapshot repo ctx) [:items "g" :default-location])))
        (is (= :stale-catalogue (:type (fixture/error-data #(patch! ctx :goods "g" 1 :name "Old edit")))))
        (doseq [action [#(patch! ctx :goods "g" 3 :name "  ")
                        #(patch! ctx :goods "g" 3 :category-id "missing")
                        #(patch! ctx :warehouses "w" 1 :purpose "unknown")
                        #(patch! ctx :categories "new" 0 :description "bad")]]
          (is (= :validation (:type (fixture/error-data action)))))
        (is (= "" (:unit (patch! ctx :goods "g" 3 :unit ""))))
        (is (= "storage" (:purpose (patch! ctx :warehouses "w" 1 :purpose "storage"))))
        (is (= "Address" (:address (patch! ctx :suppliers "s" 1 :address "Address"))))
        (is (= "Clothing" (:name (patch! ctx :categories "c" 1 :name "Clothing"))))
        (let [other (assoc ctx :workspace-id (:workspace-id fixture/other-scope))]
          (is (nil? (read-row other :goods "g")))
          (patch! other :goods "g" 0 :name "Other workspace")
          (is (= :validation (:type (fixture/error-data #(patch! other :goods "g" 1 :store-id "shop")))))
          (is (= "Socks" (:name (read-row ctx :goods "g")))))))))


(deftest catalogue-renames-preserve-identity-history-and-rebuilds
  (fixture/with-store
    (fn [repo _]
      (let [ctx (context repo)]
        (submit! ctx (assoc (fixture/opening 7) :kind :operation :type :purchase :party "Supplier"))
        (let [before    (inventory/snapshot repo ctx)
              warehouse (inventory/read-snapshot repo ctx (fn [conn _] (catalogue/find-alias conn ctx :warehouses "Main")))
              supplier  (inventory/read-snapshot repo ctx (fn [conn _] (catalogue/find-alias conn ctx :suppliers "Supplier")))]
          (patch! ctx :goods "a" 1 :name "Renamed goods")
          (patch! ctx :warehouses (:id warehouse) 1 :name "North")
          (patch! ctx :suppliers (:id supplier) 1 :name "New supplier")
          (let [after (inventory/snapshot repo ctx)
                row   (first (:rows after))]
            (is (= (:history before) (:history after)))
            (is (= (:ledger-revision before) (:ledger-revision after)))
            (is (= (select-keys (first (:rows before)) [:id :location :quantity :item-id])
                   (select-keys row [:id :location :quantity :item-id])))
            (is (= "North" (:location-label row)))
            (is (= "Renamed goods" (:name row)))
            (inventory/rebuild! repo ctx)
            (is (= (:rows after) (:rows (inventory/snapshot repo ctx)))))
          (is (= :validation (:type (fixture/error-data #(patch! ctx :warehouses "another" 0 :name "Main")))))
          (submit! ctx {:type  :purchase :kind :operation :party "New supplier" :occurred-at "2026-02-01"
                        :lines [{:item-id "a" :name "Old document name" :quantity 2 :location "North"}]})
          (let [after (inventory/snapshot repo ctx)]
            (is (= 1 (count (:rows after))))
            (is (= 9 (:quantity (first (:rows after)))))
            (is (= "Renamed goods" (get-in after [:items "a" :name]))))
          (let [draft (inventory/save-entry! repo ctx {:entry {:type  :purchase :kind :operation :party "New supplier" :occurred-at "2026-03-01"
                                                               :lines [{:item-id "a" :quantity 1 :location "Future name"}]} :actor "alice"})]
            (patch! ctx :warehouses (:id warehouse) 2 :name "Future name")
            (fixture/submit! repo [draft])
            (is (= [["Main" 10]] (mapv (juxt :location :quantity) (:rows (inventory/snapshot repo ctx))))))
          (inventory/read-snapshot repo ctx
                                   (fn [conn _]
                                     (is (= ["Future name"] (read-query/values conn ctx :location "F" 30)))
                                     (is (= ["New supplier"] (read-query/values conn ctx :party "N" 30)))
                                     (is (= ["a"] (keys (read-query/suggest-items conn ctx "Renamed" 30)))))))))))


(deftest metadata-edits-invalidate-reviews-and-do-not-leak-private-compositions
  (fixture/with-store
    (fn [repo _]
      (let [ctx (context repo)]
        (submit! ctx (fixture/opening 7))
        (let [draft    (inventory/save-entry! repo ctx {:entry (assoc (fixture/opening 8) :kind :correction :reason "Count") :actor "alice"})
              snapshot (inventory/snapshot repo ctx)
              request  {:selected                    [(select-keys draft [:id :revision])] :expected-ledger-revision (:ledger-revision snapshot)
                        :expected-catalogue-revision (:catalogue-revision snapshot)}]
          (is (:valid? (inventory/preview repo ctx request)))
          (patch! ctx :goods "a" 1 :article "ARTICLE")
          (is (= :stale-catalogue (:type (fixture/error-data #(inventory/submit! repo ctx (assoc request :actor "alice" :command-id "stale"))))))
          (let [fresh (assoc request :expected-catalogue-revision (:catalogue-revision (inventory/snapshot repo ctx)))]
            (inventory/submit! repo ctx (assoc fresh :actor "alice" :command-id "fresh")))
          (is (= "ARTICLE" (get-in (inventory/snapshot repo ctx) [:items "a" :article]))))
        (inventory/save-composition! repo ctx {:entry {:type :purchase :lines [{:item-id "private" :name "Secret" :location "Private"}]} :actor (str (:user-id ctx))})
        (is (nil? (read-row ctx :goods "private")))
        (let [draft    (inventory/save-entry! repo ctx {:entry {:kind  :correction :occurred-at "2026-03-01" :reason "Name"
                                                                :lines [(assoc (fixture/line "a" 8) :name "Corrected")]}   :actor "alice"})
              snapshot (inventory/snapshot repo ctx)]
          (patch! ctx :goods "a" (:revision (read-row ctx :goods "a")) :name "Another editor")
          (let [draft   (inventory/save-entry! repo ctx {:id    (:id draft) :expected-revision (:revision draft) :actor "alice"
                                                         :entry (assoc-in (:entry draft) [:lines 0 :barcode] "Later edit")})
                request {:selected                    [(select-keys draft [:id :revision])]
                         :expected-ledger-revision    (:ledger-revision snapshot)
                         :expected-catalogue-revision (:catalogue-revision (inventory/snapshot repo ctx))}
                error   (fixture/error-data (fn [] (inventory/preview repo ctx request)))]
            (is (= :stale-catalogue (:type error)))))))))


(deftest catalogue-query-is-bounded-and-filters-reference-labels
  (fixture/with-store
    (fn [repo _]
      (let [ctx (context repo)]
        (patch! ctx :categories "c" 0 :name "Clothes")
        (rdf/with-transaction [conn {:repo repo :level :serializable}]
          (doseq [index (range 225)]
            (catalogue/register! conn ctx :goods {:id (str index) :name (format "Goods %03d" index) :category-id "c"} "fixture")))
        (let [model   (:model (web/query-page ctx {:kind "goods"}))
              request {:queryId (get-in model [:viewport :queryId]) :requestId "next" :start 200 :count 200}]
          (is (= 200 (count (:rows model))))
          (is (= 25 (count (:rows (windows/fetch! ctx request)))))
          (is (= #{"0"} (set (map #(get-in % [:target :id]) (:editStates (windows/fetch! ctx (assoc request :pendingRows ["0"])))))))
          (is (nil? (windows/fetch! (assoc ctx :user-id (random-uuid)) request)))
          (is (nil? (windows/fetch! (assoc ctx :workspace-id (:workspace-id fixture/other-scope)) request)))
          (patch! ctx :goods "0" 1 :article "Current")
          (is (nil? (windows/fetch! ctx request))))
        (inventory/read-snapshot repo ctx
                                 (fn [conn _]
                                   (let [first-page  (query/page conn ctx {:kind "goods"})
                                         second-page (query/page conn ctx {:kind "goods" :start 200})
                                         filtered    (query/page conn ctx {:kind "goods" :filters {"category-id" [{:id "f" :type "eq" :value "clothes"}]}})]
                                     (is (= 200 (count (:rows first-page)) (count (:rows filtered))))
                                     (is (= 25 (count (:rows second-page))))
                                     (is (= 225 (get-in first-page [:viewport :totalRows])))
                                     (is (= "Goods 200" (:name (first (:rows second-page)))))
                                     (is (= 30 (count (query/choices conn ctx :goods "Goods"))))
                                     (is (= 200 (get-in (query/page conn ctx {:kind "goods" :anchorId "200"}) [:viewport :start]))))))))))


(deftest filters-use-shared-and-or-semantics-and-translated-purpose
  (fixture/with-store
    (fn [repo _]
      (let [ctx (context repo)]
        (patch! ctx :warehouses "a" 0 :name "Alpha")
        (patch! ctx :warehouses "b" 0 :name "Beta")
        (patch! ctx :warehouses "a" 1 :purpose "storage")
        (inventory/read-snapshot repo ctx
                                 (fn [conn _]
                                   (is (= 2 (get-in (query/page conn ctx {:kind "warehouses" :filters {"name" [{:id "a" :type "eq" :value "Alpha" :relation "or"}
                                                                                                               {:id "b" :type "eq" :value "Beta"}]}}) [:viewport :totalRows])))
                                   (binding [i18n/*locale* "ru"]
                                     (is (= ["a"] (mapv :id (:rows (query/page conn ctx {:kind "warehouses" :filters {"purpose" [{:id "p" :type "eq" :value (i18n/t "Storage")}]}}))))))))))))


(deftest item-default-warehouse-never-overwrites-a-location-or-transfer
  (let [snapshot {:items {"a" {:name "Goods" :default-location "Default"}} :rows []}
        entry    {:type :purchase :lines [{:item-id "new" :location "" :quantity 5}]}]
    (is (= "Default" (get-in (item-selection/select-item snapshot entry [:lines 0] "a") [:lines 0 :location])))
    (is (= "Entered" (get-in (item-selection/select-item snapshot (assoc-in entry [:lines 0 :location] "Entered") [:lines 0] "a") [:lines 0 :location])))
    (is (= "" (get-in (item-selection/select-item snapshot (assoc entry :type :transfer) [:lines 0] "a") [:lines 0 :location])))
    (is (= "Stock location" (get-in (item-selection/select-item (assoc snapshot :rows [{:item-id "a" :location "Stock location" :quantity 3}]) entry [:lines 0] "a") [:lines 0 :location])))))


(deftest catalogue-endpoints-require-current-workspace-membership
  (doseq [declaration [#'web/index #'web/page #'web/table #'web/window #'web/choices #'web/save]]
    (is (= :workspace (get-in (meta declaration) [:bel/route-data :tover/access]))))
  (is (some? (bel/get-action :tover.catalogues.web/save))))


(deftest stock-location-corrections-display-the-destination-after-a-warehouse-rename
  (fixture/with-store
    (fn [repo _]
      (let [ctx (context repo)]
        (submit! ctx (fixture/opening 7))
        (let [warehouse (inventory/read-snapshot repo ctx (fn [conn _] (catalogue/find-alias conn ctx :warehouses "Main")))
              row       (first (:rows (inventory/snapshot repo ctx)))]
          (patch! ctx :warehouses (:id warehouse) 1 :name "North")
          (stock/save-stock-correction! ctx {:rowId (:id row) :field "location" :value "Elsewhere" :ledgerRevision 1})
          (is (= "Elsewhere" (get-in (workbench/query-stock ctx {}) [:model :rows 0 :location])))
          (is (nil? (get-in (workbench/query-stock ctx {}) [:model :rows 0 :location-label])))
          (fixture/submit! repo (:drafts (inventory/snapshot repo ctx)))
          (is (= 7 (:quantity (some #(when (= "Elsewhere" (:location %)) %) (:rows (inventory/snapshot repo ctx)))))))))))


(deftest legacy-bootstrap-is-repeatable-and-a-restored-backup-retains-catalogues
  (fixture/with-store
    (fn [repo path]
      (let [ctx (context repo)]
        (submit! ctx (assoc (fixture/opening 7) :kind :operation :type :purchase :party "Supplier"))
        (let [before     (inventory/snapshot repo ctx)
              read-graph (rdf/iri (str "urn:tover:workspace:" (:workspace-id ctx) ":inventory-read-v1"))]
          ;; Simulate an older installation in this disposable test store only.
          (rdf/with-transaction [conn {:repo repo :level :serializable}]
            (rdf/remove! conn (catalogue/graph ctx) nil nil nil)
            (rdf/remove! conn read-graph (rdf/iri (str read-graph ":state")) (rdf/iri "urn:tover:inventory-read-v1:version") nil)
            (rdf/add! conn read-graph (rdf/iri (str read-graph ":state")) (rdf/iri "urn:tover:inventory-read-v1:version") 7))
          (is (= "a" (get-in (web/query-page ctx {:kind "goods"}) [:model :rows 0 :id])))
          (let [bootstrapped (inventory/snapshot repo ctx)]
            (web/query-page ctx {:kind "warehouses"})
            (web/query-page ctx {:kind "suppliers"})
            (is (= bootstrapped (inventory/snapshot repo ctx)))
            (is (= (:history before) (:history bootstrapped)))
            (is (= (:rows before) (:rows bootstrapped))))
          (let [request  {:kind "goods" :id "a" :revision 1 :path "name" :value "Restored goods" :commandId "restore-edit"}
                saved    (web/save-record! ctx request)
                snapshot (inventory/snapshot repo ctx)
                target   (.toFile (Files/createTempDirectory "tover-catalogue-restore-" (make-array java.nio.file.attribute.FileAttribute 0)))]
            (rdf/close! repo)
            (try
              (doseq [source (file-seq path)
                      :let   [destination (io/file target (str (.relativize (.toPath path) (.toPath source))))]]
                (if (.isDirectory source) (.mkdirs destination) (io/copy source destination)))
              (let [restored         (rdf/native-store target)
                    restored-context (assoc ctx :repo restored)]
                (try
                  (is (= snapshot (inventory/snapshot restored restored-context)))
                  (is (= saved (web/save-record! restored-context request)))
                  (is (= snapshot (inventory/rebuild! restored restored-context)))
                  (finally (rdf/close! restored))))
              (finally
                (doseq [file (reverse (file-seq target))] (io/delete-file file))))))))))
