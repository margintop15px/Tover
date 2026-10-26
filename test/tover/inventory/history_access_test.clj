(ns tover.inventory.history-access-test
  (:require
    [bel.core :as bel]
    [bel.handler :as handler]
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [tover.auth.core :as auth]
    [tover.auth.security :as security]
    [tover.auth.sessions :as sessions]
    [tover.inventory.ledger :as ledger]
    [tover.inventory.model :as model]
    [tover.inventory.store :as store]
    [tover.stock.web :as web]
    [tover.support :as support]))


(defn- line
  {:malli/schema [:=> [:cat :string :int] :map]}
  [item quantity]
  {:item-id item :name item :unit "pcs" :location "Shelf" :quantity quantity})


(defn- save!
  {:malli/schema [:=> [:cat :map :map] :map]}
  [{:keys [repo user-id] :as context} entry]
  (store/save-entry! repo context {:entry entry :actor (str user-id)}))


(defn- submit!
  {:malli/schema [:=> [:cat :map [:vector :map]] :map]}
  [{:keys [repo user-id] :as context} drafts]
  (store/submit! repo context {:actor                    (str user-id) :command-id (str (random-uuid))
                               :selected                 (mapv #(select-keys % [:id :revision]) drafts)
                               :expected-ledger-revision (:ledger-revision (store/snapshot repo context))}))


(defn- html
  {:malli/schema [:=> [:cat :any] :string]}
  [result]
  (handler/render-html (mapv :html (filter #(= :elements (:type %)) (if (map? result) [result] result)))))


(defn- with-context
  {:malli/schema [:=> [:cat [:=> [:cat :map] :any]] :any]}
  [run]
  (support/with-application
    (fn [{:keys [auth session-store users] :as services}]
      (let [tokens (auth/sign-in! auth (first users))
            key    (sessions/establish! session-store nil tokens)]
        (run (merge services (security/authenticate services key) {:request {:session/key key}}))))))


(defn- open-operation
  {:malli/schema [:=> [:cat :map :map] :any]}
  [context input]
  (web/open-history-operation context (merge {:rowId (ledger/row-id (:itemId input) "Shelf") :requestId "history-test"} input)))


(deftest history-detail-requires-the-item-operation-and-revision-together
  (with-context
    (fn [context]
      (let [a         (save! context {:kind :opening :occurred-at "2026-01-01" :reason "Own opening" :lines [(line "A" 5)]})
            unrelated (save! context {:kind :opening :occurred-at "2026-01-01" :reason "Private unrelated details" :lines [(line "C" 9)]})]
        (submit! context [a unrelated])
        (let [valid (html (open-operation context {:itemId "A" :operationId (:id a) :revision 1}))]
          (is (str/includes? valid "id=\"history-operation-details\""))
          (is (str/includes? valid "owner@example.test"))
          (is (not (str/includes? valid (str (:user-id context))))))
        (doseq [input [{:itemId "A" :operationId (:id unrelated) :revision 1}
                       {:itemId "C" :operationId (:id a) :revision 1}
                       {:itemId "A" :operationId (:id a) :revision 2}
                       {:itemId "A" :operationId "missing" :revision 1}
                       {:itemId "unknown" :operationId (:id a) :revision 1}]]
          (let [denied (html (open-operation context input))]
            (is (str/includes? denied "role=\"alert\""))
            (is (not (str/includes? denied "id=\"history-operation-details\"")))
            (is (not (str/includes? denied "Private unrelated details")))))))))


(deftest history-detail-stays-workspace-scoped-even-with-known-identifiers
  (with-context
    (fn [{:keys [repo other-workspace-id] :as context}]
      (let [a     (save! context {:kind :opening :occurred-at "2026-01-01" :reason "Original workspace secret" :lines [(line "A" 5)]})
            other (assoc context :workspace-id other-workspace-id)]
        (submit! context [a])
        (submit! other [(save! other {:kind :opening :occurred-at "2026-01-01" :lines [(line "A" 8)]})])
        (let [denied (html (open-operation other {:itemId "A" :operationId (:id a) :revision 1}))]
          (is (str/includes? denied "role=\"alert\""))
          (is (not (str/includes? denied "Original workspace secret")))
          (is (not (str/includes? denied "id=\"history-operation-details\""))))
        (is (= 5 (:quantity (first (:rows (store/snapshot repo context))))))
        (is (= 8 (:quantity (first (:rows (store/snapshot repo other))))))))))


(deftest automatic-ancestry-opens-relevant-parents-but-excludes-future-parent-records
  (with-context
    (fn [context]
      (let [opening    (save! context {:kind :opening :occurred-at "2026-01-01" :lines [(line "A" 10)]})
            production (save! context {:kind   :operation     :type    :production    :occurred-at "2026-01-02"
                                       :inputs [(line "A" 4)] :outputs [(line "B" 2)]})
            future     (save! context {:kind        :operation   :type  :purchase        :party "Later supplier"
                                       :occurred-at "2026-01-03" :lines [(line "A" 100)]})]
        (submit! context [opening production future])
        (doseq [id [(:id opening) (:id production)]]
          (is (str/includes? (html (open-operation context {:itemId "B" :operationId id :revision 1}))
                             "id=\"history-operation-details\"")))
        (let [denied (html (open-operation context {:itemId "B" :operationId (:id future) :revision 1}))]
          (is (str/includes? denied "role=\"alert\""))
          (is (not (str/includes? denied "Later supplier"))))))))


(deftest historical-revisions-and-cancellations-remain-accessible-for-their-item
  (with-context
    (fn [context]
      (let [opening (save! context {:kind :opening :occurred-at "2026-01-01" :reason "Original count" :lines [(line "A" 5)]})]
        (submit! context [opening])
        (submit! context [(save! context {:kind        :revision :target-id (:id opening) :reason "Correct count"
                                          :replacement {:kind :opening :occurred-at "2026-01-01" :lines [(line "A" 7)]}})])
        (doseq [revision [1 2]]
          (is (str/includes? (html (open-operation context {:itemId "A" :operationId (:id opening) :revision revision}))
                             "id=\"history-operation-details\"")))
        (let [original (html (open-operation context {:itemId "A" :operationId (:id opening) :revision 1}))
              revised  (html (open-operation context {:itemId "A" :operationId (:id opening) :revision 2}))]
          (is (str/includes? original "Original count"))
          (is (str/includes? original ">5 pcs</td>"))
          (is (not (str/includes? original "Correct count")))
          (is (str/includes? revised "Correct count"))
          (is (str/includes? revised ">7 pcs</td>"))
          (is (not (str/includes? revised "Original count"))))
        (submit! context [(save! context {:kind :cancellation :target-id (:id opening) :reason "Cancelled count"})])
        (is (str/includes? (html (open-operation context {:itemId "A" :operationId (:id opening) :revision 3}))
                           "Cancelled count"))
        (doseq [revision [1 2 3]]
          (is (str/includes? (html (open-operation context {:itemId "A" :operationId (:id opening) :revision revision}))
                             "id=\"history-operation-details\"")))))))


(deftest history-detail-actions-declare-workspace-access
  (bel/init! {:scan-ns ['tover]})
  (doseq [action [:tover.stock.web/open-history-operation :tover.stock.web/close-history-operation]]
    (is (= :workspace (get-in (bel/get-action action) [:bel/route-data :tover/access])))))


(deftest history-and-lineage-actions-never-load-the-workspace-history
  (with-context
    (fn [context]
      (let [opening    (save! context {:kind :opening :occurred-at "2026-01-01" :lines [(line "A" 10)]})
            production (save! context {:kind   :operation     :type    :production    :occurred-at "2026-01-02"
                                       :inputs [(line "A" 4)] :outputs [(line "B" 2)]})
            unrelated  (save! context {:kind  :opening       :occurred-at "2026-01-03" :reason "Unrelated secret"
                                       :lines [(line "X" 5)]})]
        (submit! context [opening production unrelated])
        (with-redefs-fn {#'store/snapshot            (fn [& _] (throw (ex-info "Full snapshot forbidden" {})))
                         #'store/state               (fn [& _] (throw (ex-info "Canonical state forbidden" {})))
                         #'model/read-history        (fn [& _] (throw (ex-info "Full history forbidden" {})))
                         #'model/read-entity-history (fn [& _] (throw (ex-info "All operation revisions forbidden" {})))
                         #'ledger/replay             (fn [& _] (throw (ex-info "Replay forbidden" {})))}
          (fn []
            (let [input   {:itemId "B" :rowId (ledger/row-id "B" "Shelf") :requestId "scoped-history"}
                  history (web/history-snapshot context "B" (:rowId input))]
              (is (= ["B"] (mapv :item-id (:records history))))
              (is (= #{"A" "B"} (set (keys (:items history)))))
              (is (str/includes? (html (web/open-history context input)) "item-history-grid"))
              (is (str/includes? (html (web/open-history context (assoc input :mode "lineage"))) "lineage-parent-strip"))
              (is (str/includes? (html (open-operation context {:itemId "B" :operationId (:id opening) :revision 1}))
                                 "owner@example.test"))
              (let [denied (html (open-operation context {:itemId "B" :operationId (:id unrelated) :revision 1}))]
                (is (str/includes? denied "role=\"alert\""))
                (is (not (str/includes? denied "Unrelated secret"))))
              (is (str/includes? (html (web/open-history context (assoc input :rowId (ledger/row-id "B" "Other"))))
                                 "role=\"alert\"")))))))))
