(ns tover.catalogues.web
  (:require
    [bel.core :refer [defaction defpage defview]]
    [charred.api :as charred]
    [tover.catalogues.query :as query]
    [tover.catalogues.schema :as schema]
    [tover.catalogues.store :as store]
    [tover.catalogues.ui :as ui]
    [tover.inventory.layouts :as layouts]
    [tover.inventory.read-model :as read-model]
    [tover.inventory.store :as inventory]
    [tover.inventory.windows :as windows]
    [tover.shared.i18n :as i18n]
    [tover.shared.rdf :as rdf]
    [tover.stock.web :as stock])
  (:import
    [clojure.lang ExceptionInfo]))


(defn query-page
  {:malli/schema [:=> [:cat :map :map] :map]}
  [context input]
  (let [kind    (or (:kind input) (get-in context [:request :path-params :kind]) "goods")
        kind    (name kind)
        input   (assoc input :kind kind)
        layouts (layouts/load-all context)
        model   (inventory/read-snapshot (:repo context) context
                                         (fn [conn _]
                                           (let [generation (store/revision conn context)]
                                             (windows/initial! context
                                                               {:queryKey    (pr-str (dissoc input :requestId :start :count :anchorId :pendingRows)) :generation generation
                                                                :first-model (ui/grid (keyword kind) (query/page conn context input))
                                                                :read        (fn [range-input]
                                                                               (inventory/read-snapshot (:repo context) context
                                                                                                        (fn [conn _]
                                                                                                          (when (= generation (store/revision conn context))
                                                                                                            (ui/grid (keyword kind) (query/page conn context (merge input range-input)))))))}))))
        model   (assoc (layouts/apply-layout model (get layouts (:id model))) :layoutScope (str (:user-id context)))]
    (assoc input :kind (keyword kind) :model model :layouts layouts)))


(defn save-record!
  {:malli/schema [:=> [:cat :map schema/Save] :map]}
  [{:keys [repo] :as context} input]
  (inventory/read-snapshot repo context (fn [_ _] nil))
  (locking repo
    (rdf/with-transaction [conn {:repo repo :level :serializable}]
      (let [before (store/revision conn context)
            row    (store/save! conn context input (str (:user-id context)))]
        (when-not (= before (store/revision conn context))
          (read-model/refresh-catalogue! conn context (read-model/read-state conn context) (keyword (:kind input)) row))
        row))))


(defview table
  {:route-data {:tover/access :workspace} :schema schema/Query
   :query      (fn [context input] {:context context :input input})}
  [{:keys [context input]}]
  {:type :signals :data {:_catalogueResponseJson (charred/write-json-str {:requestId (:requestId input) :kind (:kind input) :model (:model (query-page context input))})}})


(defview window
  {:route-data {:tover/access :workspace}
   :schema     [:map [:queryId :string] [:requestId :string] [:start :int] [:count :int]]
   :query      (fn [context input] {:context context :input input})}
  [{:keys [context input]}]
  (let [model  (windows/fetch! context input)
        layout (when model (get (layouts/load-all context) (:id model)))]
    {:type :signals :data {:_catalogueWindowJson (charred/write-json-str (merge (select-keys input [:queryId :requestId])
                                                                                {:status (if model "ok" "expired")
                                                                                 :model  (when model (assoc (layouts/apply-layout model layout) :layoutScope (str (:user-id context))))}))}}))


(defview choices
  {:route-data {:tover/access :workspace}
   :schema     [:map [:kind schema/Kind] [:text [:string {:max 500}]] [:requestId :string]]
   :query      (fn [context input] {:context context :input input})}
  [{:keys [context] {:keys [kind text requestId]} :input}]
  {:type :signals :data {:_catalogueChoicesJson (charred/write-json-str
                                                  {:requestId requestId :status "ok"
                                                   :options   (inventory/read-snapshot (:repo context) context
                                                                                       (fn [conn _] (query/choices conn context (keyword kind) text)))})}})


(defaction save
  {:route-data {:tover/access :workspace} :schema schema/Save}
  [context input]
  (try
    (let [row (save-record! context input)]
      (stock/publish! context [:tover.stock.web/changed])
      {:type :signals :data {:_catalogueSaveJson (charred/write-json-str {:commandId (:commandId input) :kind (:kind input) :status "ok" :row row
                                                                          :gridRow   (inventory/read-snapshot (:repo context) context
                                                                                                              (fn [conn _] (first (:rows (ui/grid (keyword (:kind input)) {:rows [(store/display conn context (keyword (:kind input)) row)]})))))})}})
    (catch ExceptionInfo error
      {:type :signals :data {:_catalogueSaveJson (charred/write-json-str {:commandId (:commandId input)         :kind    (:kind input)              :status "failed"
                                                                          :message   (i18n/error-message error) :current (:current (ex-data error))})}})))


(defpage index
  {:route      "/catalogues"              :title "Catalogues · Tover" :parent :tover.shared.web.core/shell
   :route-data {:tover/access :workspace} :query query-page}
  [props]
  {:type :elements :html (ui/page props)})


(defpage page
  {:route      "/catalogues/:kind"        :title "Catalogues · Tover" :parent :tover.shared.web.core/shell
   :route-data {:tover/access :workspace} :query query-page}
  [props]
  {:type :elements :html (ui/page props)})
