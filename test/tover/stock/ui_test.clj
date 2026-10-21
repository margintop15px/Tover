(ns tover.stock.ui-test
  (:require
    [clojure.test :refer [deftest is]]
    [tover.inventory.workbench :as workbench]
    [tover.shared.schema :as schema]
    [tover.stock.ui :as ui]))


(def rows
  [{:id "notebook" :name "Notebook A5" :article "NB-A5" :unit "pcs" :location "Main shelf" :quantity 24}
   {:id "tea" :name "Green tea, 100 g" :article "TEA-100" :unit "packs" :location "Main shelf" :quantity 18}])


(def model
  {:rows    rows :evidence {}  :history        {:row nil :history []}
   :editing nil  :proposal nil :interpretation {:type :search :query "" :rows rows}})


(def props
  {:smart {} :sheet {} :rows {} :review {}})


(defn- nodes
  {:malli/schema [:=> [:cat schema/Hiccup] [:sequential :any]]}
  [hiccup]
  (tree-seq sequential? seq hiccup))


(defn- attributes
  {:malli/schema [:=> [:cat schema/Hiccup] [:sequential :map]]}
  [hiccup]
  (keep #(when (and (vector? %) (map? (second %))) (second %)) (nodes hiccup)))


(deftest rendering-is-pure-and-keeps-accepted-and-proposed-stock-distinct
  (let [calls   (atom 0)
        action  #(swap! calls inc)
        signals "{\"stockChanged\":false}"
        row     (first rows)
        change  {:row row :row-id (:id row) :before 24 :after 18}
        model   (assoc model :proposal change)
        events  (assoc props :signals signals :init action :effect action
                       :review {:on-accept action :on-discard action :bind "reason" :value "Shelf count"})
        page    (workbench/stock-page (assoc events :model model))]
    (is (= page (workbench/stock-page (assoc events :model model))))
    (is (zero? @calls) "Rendering passes event values through without executing them")
    (is (= {:data-signals__ifmissing signals :data-init action :data-effect action}
           (select-keys (second page) [:data-signals__ifmissing :data-init :data-effect])))
    (is (= 1 (count (filter #(and (vector? %) (= :tr.inline-diff (first %))) (nodes page)))))
    (is (some #{[:td.numeric "24 pcs"]} (nodes page)))
    (is (some #{[:td.numeric "18 pcs"]} (nodes page)))
    (is (some #{[:div.diff-caption "Notebook A5" " · " "Main shelf"]} (nodes page)))
    (is (some #(= action (:data-on:click %)) (attributes page)))
    (is (some #(and (= "adjustment-reason" (:id %)) (= "reason" (:data-bind %))
                    (= "Shelf count" (:value %))) (attributes page)))
    (is (not-any? #(and (string? %) (re-find #"[Rr]evision" %)) (nodes page)))))


(deftest spreadsheet-remains-accessible-with-empty-stock
  (let [page  (workbench/stock-page (assoc props :model (assoc model :rows []
                                                               :interpretation {:type :search :query "missing" :rows []})))
        attrs (attributes page)]
    (is (some #(= "RevoGrid stock spreadsheet" (:aria-label %)) attrs))
    (is (some #(= "polite" (:aria-live %)) attrs))
    (is (= [:div#stock-review nil] (ui/stock-review {:proposal nil})))))


(deftest stock-and-operations-navigation-remains-pure
  (let [calls  (atom 0)
        source #(swap! calls inc)
        page   (workbench/stock-page (assoc props :model model
                                            :view :operations
                                            :operations {:source source :examples? true :on-examples-toggle "toggle-examples"}))
        attrs  (attributes page)]
    (is (zero? @calls))
    (is (some #(and (vector? %) (= :section#stock-view (first %)) (true? (:hidden (second %)))) (nodes page)))
    (is (some #(and (vector? %) (= :section#operations-view (first %)) (false? (:hidden (second %)))) (nodes page)))
    (doseq [tag [:section#stock-view :section#operations-view]]
      (is (= 1 (count (filter #(and (vector? %) (= tag (first %))) (nodes page))))))
    (is (some #(and (vector? %) (= :stock-operations#operations-history (first %))
                    (= {:data-ignore-morph "" :data-effect source :data-examples "" :data-on:examples-toggle "toggle-examples"} (second %)))
              (nodes page)))))


(deftest history-displays-only-the-selected-goods
  (let [entries [{:id           #uuid "2b1b0cd3-ad61-4695-9b72-a098a737fbb7" :row-id "notebook" :before 24 :after 18 :delta -6
                  :reason       "Shelf count" :actor "previous actor" :recorded-at "2026-10-09T12:00:00Z"
                  :effective-at "2026-10-09T12:00:00Z"}
                 {:id           #uuid "a1073e12-ddbc-4cb5-bd7c-8eadf6d34a8f" :row-id "tea" :before 18 :after 10 :delta -8
                  :reason       "Tea count" :recorded-at "2026-10-09T11:00:00Z"
                  :effective-at "2026-10-09T11:00:00Z"}]
        history (ui/item-history {:row (first rows) :history (filterv #(= "notebook" (:row-id %)) entries) :on-click "close"})]
    (is (= ["history-2b1b0cd3-ad61-4695-9b72-a098a737fbb7"] (vec (keep :id (attributes history)))))
    (is (some #(= "close" (:data-on:click %)) (attributes history)))
    (is (some #{"Shelf count · 2026-10-09T12:00:00Z"} (nodes history)))
    (is (some #{[:li "Opening declaration" [:span.subline "Local seed data · origin and cost unknown"]]} (nodes history)))))


(deftest smart-input-accepts-ordinary-event-properties
  (let [calls  (atom 0)
        action #(swap! calls inc)
        page   (ui/smart-entry {:source action :on-add-row action})
        attrs  (attributes page)]
    (is (zero? @calls))
    (is (some #(and (= "" (:data-ignore-morph %)) (= action (:data-effect %))
                    (= action (:data-on:row-add %))) attrs))
    (is (some #(and (vector? %) (= :stock-entry#stock-entry (first %))) (nodes page)))
    (is (some #{[:div#smart-result]} (nodes page)))))


(deftest spreadsheet-events-pass-through-without-executing
  (let [calls  (atom 0)
        action #(swap! calls inc)
        sheet  (ui/stock-sheet {:source            action                :on-preview      action              :on-editing action
                                :on-draft          action                :on-reset-drafts "reset-cell-drafts"
                                :on-select-rows    action                :on-delete-rows  action
                                :on-add-row        "add-row"
                                :on-preview-drafts "preview-cell-drafts" :on-submit       "submit-changes"
                                :on-error          action})
        attrs  (attributes sheet)]
    (is (zero? @calls))
    (is (some #(and (= "" (:data-ignore-morph %))
                    (every? (fn [key] (= action (get % key)))
                            [:data-effect :data-on:quantity-preview :data-on:grid-editing
                             :data-on:cells-draft :data-on:rows-select :data-on:rows-delete :data-on:stock-edit-error])) attrs))
    (doseq [handler ["add-row" "reset-cell-drafts" "preview-cell-drafts" "submit-changes"]]
      (is (some #(= handler (:data-on:click %)) attrs)))))


(deftest toolbar-stays-visible-and-draft-preview-shows-before-and-after
  (let [sheet   (ui/stock-sheet {})
        toolbar (first (filter #(and (vector? %) (= :div.sheet-toolbar (first %))) (nodes sheet)))
        cells   [{:row-id "notebook" :name "Notebook A5" :field "name" :before "Notebook A5" :after "Notebook B5"}]
        preview (ui/stock-draft-preview {:cells cells :on-submit "submit-changes"})]
    (is (= :div.sheet-toolbar (first toolbar)))
    (is (nil? (get-in toolbar [1 :data-show])))
    (is (= preview (ui/stock-draft-preview {:cells cells :on-submit "submit-changes"})))
    (is (some #{[:td.sheet-diff-before "Notebook A5"]} (nodes preview)))
    (is (some #{[:td.sheet-diff-after "Notebook B5"]} (nodes preview)))
    (is (some #{"Submit changes"} (nodes preview)))
    (is (some #(= "submit-changes" (:data-on:click %)) (attributes preview)))))


(deftest generic-row-identifiers-are-safe-in-every-fragment
  (let [row      (assoc (first rows) :id "new row/α +'[]")
        editing  {:row row}
        proposed {:row row :row-id (:id row) :before 24 :after 18}
        encoded  "new+row%2F%CE%B1+%2B%27%5B%5D"]
    (is (= "stock-notebook" (ui/element-id "stock-" "notebook")))
    (doseq [prefix ["stock-" "accepted-quantity-" "editor-" "quantity-" "evidence-" "review-"]]
      (is (= (str prefix encoded) (ui/element-id prefix (:id row)))))
    (is (= (str "stock-" encoded) (get-in (ui/stock-row {:row row :editing editing :change proposed :latest nil}) [1 :id])))
    (is (= (str "review-" encoded) (get-in (ui/adjustment-review {:proposal proposed}) [1 :id])))))


(deftest fragments-have-stable-hosts-and-footer-uses-server-values
  (let [page (workbench/stock-page (assoc props :model model :init "open-stream" :notice "Saved" :error? true))]
    (is (= "open-stream" (get-in page [1 :data-init])))
    (is (some #{[:span#evidence-notice {:aria-live "polite" :class "error"} "Saved"]} (nodes page)))
    (doseq [tag [:div#smart-result :revo-grid#stock-revogrid :div#stock-review :div#history-panel :footer.app-footer#stock-footer]]
      (is (= 1 (count (filter #(and (vector? %) (= tag (first %))) (nodes page)))))))
  (is (= [:div#history-panel nil] (ui/history-panel {:row nil :history []}))))


