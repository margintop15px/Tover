(ns tover.stock.ui-test
  (:require
    [bel.handler :as handler]
    [clojure.test :refer [deftest is]]
    [tover.inventory.grid :as grid]
    [tover.inventory.tables :as tables]
    [tover.inventory.workbench :as workbench]))


(def model
  {:ledger-revision 0 :items {} :rows [] :operations [] :drafts []})


(defn render
  {:malli/schema [:=> [:cat :map] :string]}
  [props]
  (handler/render-html (workbench/stock-page (merge {:model model :view :stock :query ""} props))))


(deftest shared-input-and-real-server-actions-on-both-pages
  (doseq [view [:stock :operations]]
    (let [page (render {:view view})]
      (is (re-find #"stock-entry" page))
      (is (re-find #"/action/tover.inventory.smart-web/suggest" page))
      (is (re-find (if (= view :stock) #"/action/tover.stock.web/add-entry" #"beginAdd") page))
      (is (re-find #"No changes" page))
      (is (re-find #"Preview changes" page))
      (is (re-find #"Revert all changes" page))
      (is (not (re-find #"New operation type|Sort stock|Sort direction|No pending changes|Accepted quantities" page)))
      (is (not (re-find #"Demo data|_localOperations|_submittedCells|UI preview|Local seed" page)))))
  (is (re-find #"inventory-grid id=\"stock-grid\"" (render {})))
  (is (< (.indexOf (render {}) "id=\"inventory-review\"") (.indexOf (render {}) "id=\"stock-grid\"")))
  (is (not (re-find #"Production timeline|inventory-timeline|id=\"history-panel\"" (render {:view :operations})))))


(deftest operation-type-selects-fields-on-server-and-production-stays-one-draft
  (let [entry {:kind    :operation :type :production :occurred-at "2026-10-20T12:00:00Z" :reason ""
               :inputs  [{:item-id "a" :name "Input" :quantity 2 :location "Shelf"}]
               :outputs [{:item-id "b" :name "Output" :quantity 1 :location "Shelf"}]}
        page  (render {:model (assoc model :drafts [{:id "one" :revision 1 :entry entry}])})]
    (is (not (re-find #"Inputs consumed|Outputs created" page)))
    (is (re-find #"inventory-production-input" page))
    (is (re-find #"inventory-production-output" page))
    (is (= 1 (count (filter :selection (mapcat :cells (:rows (grid/drafts {:drafts [{:id "one" :revision 1 :entry entry}]})))))))
    (is (every? (set (map #(get-in % [:target :path]) (mapcat :cells (:rows (grid/drafts {:drafts [{:id "one" :revision 1 :entry entry}]})))))
                ["inputs/0/quantity" "outputs/0/quantity"]))
    (is (not (re-find #"Amount \(optional" page))))
  (let [page (render {:model (assoc model :drafts [{:id "payment" :revision 1 :entry {:kind :operation :type :payment}}])})]
    (is (re-find #"Currency" page))
    (is (re-find #"Direction" page))
    (is (not (re-find #"data-field=\"lines/0" page)))))


(deftest item-history-table-shows-own-records-and-gantt-uses-dated-lineage
  (let [history {:item          {:id "item" :name "Goods"}
                 :records       [{:operation-id "opening"              :operation-revision 1 :type  :opening :kind     :opening
                                  :occurred-at  "2026-10-20T12:00:00Z" :before             0 :after 8        :location "Shelf"}]
                 :audit-records [{:entity-id   "opening" :revision 1 :status :effective :actor "Owner"
                                  :recorded-at "2026-10-20T12:00:00Z"
                                  :entry       {:kind :opening :occurred-at "2026-10-20T12:00:00Z"}}]
                 :lineage       [{:operation-id "prod" :occurred-at                   "2026-10-20T13:00:00Z"
                                  :inputs       [{:item-id "a" :quantity 2}] :outputs [{:item-id "item" :quantity 1}]}]}
        page    (handler/render-html (workbench/history-panel (assoc history :row-id "item@Shelf" :request-id "history-test" :mode "lineage")))]
    (is (= "0 → 8 · Shelf" (get-in (tables/history history) [:rows 0 :cells 6 :value])))
    (is (re-find #"inventory-timeline" page))
    (is (not (re-find #"item-history-grid|lineage-production|lineage-legend" page)))
    (is (not (re-find #"Show this item only|Include parent items" page)))
    (is (not (re-find #"Local seed|receipt allocation|physical" page)))))


(deftest pending-catalog-recurses-through-production-receipts-and-replacements-with-accepted-precedence
  (let [snapshot {:items  {"known" {:id "known" :name "Accepted name" :article "A"}}
                  :drafts [{:entry {:kind :opening :lines [{:item-id "opening" :name "Opening item"}
                                                           {:item-id "known" :name "Pending old name"}]}}
                           {:entry {:kind :operation :type :purchase :receipts [{:lines [{:item-id "receipt" :name "Receipt item"}]}]}}
                           {:entry {:kind :revision :replacement {:type    :production
                                                                  :inputs  [{:item-id "input" :name "Input item"}]
                                                                  :outputs [{:item-id "output" :name "Output item"}]}}}]}
        catalog  (workbench/item-catalog snapshot)]
    (is (= #{"known" "opening" "receipt" "input" "output"} (set (keys catalog))))
    (is (= "Accepted name" (get-in catalog ["known" :name])))
    (is (= "A" (get-in catalog ["known" :article])))))


(deftest review-has-one-stock-diff-and-no-duplicated-item-summary
  (let [before  {:id "goods@Shelf" :item-id "goods" :name "Goods" :location "Shelf" :quantity 4}
        preview {:valid?      true     :selected [{:id "one" :revision 1}] :expected-ledger-revision 0
                 :before-rows [before] :rows     []}
        page    (handler/render-html (workbench/preview-panel preview :stock ""))
        result  (tables/resulting-stock preview)]
    (is (= 1 (count (:rows result))))
    (is (= {:before 4 :value 0} (get-in result [:rows 0 :cells 1])))
    (is (re-find #"inventory-grid id=\"resulting-stock-grid\"" page))
    (is (not (re-find #"inventory-entry-facts|sheet-diff-table|Change preview" page)))
    (is (not (re-find #"<ul|<li" page)))))


(deftest both-submit-controls-block-reviews-until-the-receipt-is-acknowledged
  (let [toolbar (workbench/change-toolbar model :stock)
        preview (workbench/preview-panel {:valid? true :rows [] :before-rows []} :stock "")
        buttons (for [tree  [toolbar preview]
                      node  (tree-seq coll? seq tree)
                      :when (and (vector? node) (#{:button.sheet-icon-button.is-submit :button.sheet-submit-preview} (first node)))]
                  (get-in node [1 :data-on:click]))]
    (is (= 2 (count buttons)))
    (doseq [action buttons]
      (is (re-find #"\$saveStatus\s*=\s*['\"]Saving['\"]" action))
      (is (< (.indexOf action "Saving") (.indexOf action "/action/tover.stock.web/submit"))))))


(deftest active-filter-strip-describes-columns-operators-and-connectors
  (let [page (workbench/filter-strip-html :stock {"name" [{:id "a" :type "begins" :value "Note" :relation "or"}
                                                          {:id "b" :type "eq" :value "Paper"}]})]
    (is (re-find #"Goods" page))
    (is (re-find #"begins with “Note”" page))
    (is (re-find #"equals “Paper”" page))
    (is (re-find #">OR<" page))
    (is (re-find #"Reset all" page))
    (is (re-find #"Remove Goods filter 2" page))))
