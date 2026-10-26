(ns tover.inventory.semantics-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [tover.inventory.ledger :as ledger]
    [tover.inventory.model :as model]
    [tover.inventory.semantics :as semantics]
    [tover.inventory.store :as store]
    [tover.shared.rdf :as rdf])
  (:import
    [java.nio.file Files]
    [org.eclipse.rdf4j.model.vocabulary RDF]))


(def graph
  (rdf/iri "urn:tover:workspace:semantic-test:inventory-v1:history"))


(def workspace
  (rdf/iri "urn:tover:workspace:semantic-test"))


(defn with-store
  {:malli/schema [:=> [:cat [:=> [:cat rdf/Repository] :any]] :any]}
  [f]
  (let [path (.toFile (Files/createTempDirectory "tover-semantic-test-" (make-array java.nio.file.attribute.FileAttribute 0)))
        repo (rdf/native-store path)]
    (try (f repo)
         (finally
           (rdf/close! repo)
           (doseq [file (reverse (file-seq path))] (io/delete-file file))))))


(defn record
  {:malli/schema [:=> [:cat :map] :map]}
  [entry]
  {:id          "test-record" :entity-id      "test-operation" :revision   1     :sequence    1          :business-order 1
   :recorded-at "2026-10-21T12:00:00Z" :actor "alice"          :cancelled? false :change-kind :operation
   :entry       (merge {:kind :operation :occurred-at "2026-10-21T11:00:00Z"} entry)})


(deftest declarative-policy-covers-all-business-types-and-manual-edits
  (is (= [:add :directional :transfer :production :remove :remove :remove :none]
         (mapv #(semantics/stock-effect :operation %)
               [:purchase :return :transfer :production :defect :sale :write-off :payment])))
  (is (= :add (semantics/stock-effect :opening nil)))
  (is (= :set (semantics/stock-effect :correction nil))))


(deftest malformed-rdf-cannot-bypass-shacl-through-direct-transaction-writes
  (with-store
    (fn [repo]
      (with-open [conn (.getConnection ^org.eclipse.rdf4j.repository.Repository repo)]
        (.begin conn org.eclipse.rdf4j.common.transaction.IsolationLevels/SERIALIZABLE)
        (model/write-record! conn graph workspace
                             (record {:type    :production
                                      :inputs  [{:id "input" :item-id "a" :quantity 1 :location "Main"}]
                                      :outputs []}))
        (let [error (try (semantics/validate! conn graph) nil (catch clojure.lang.ExceptionInfo error error))]
          (is (= :validation (:type (ex-data error)))))
        (is (thrown? org.eclipse.rdf4j.repository.RepositoryException (.commit conn)))
        (when (.isActive conn) (.rollback conn)))
      (rdf/with-transaction [conn {:repo repo :level :snapshot}]
        (is (empty? (model/read-history conn graph)))))))


(deftest valid-production-has-queryable-scoped-lines-and-validation-returns-structured-errors
  (with-store
    (fn [repo]
      (rdf/with-transaction [conn {:repo repo :level :serializable}]
        (let [entry (ledger/normalize-entry
                      {:kind    :operation :type :production :occurred-at "2026-10-21"
                       :inputs  [{:id "in" :item-id "a" :quantity 1 :location "Main"}]
                       :outputs [{:id "out" :item-id "b" :quantity 1 :location "Main"}]})]
          (model/write-record! conn graph workspace (record entry))
          (is (nil? (semantics/validate! conn graph)))))
      (with-open [conn (.getConnection ^org.eclipse.rdf4j.repository.Repository repo)]
        (.begin conn org.eclipse.rdf4j.common.transaction.IsolationLevels/SERIALIZABLE)
        (let [item      (-> (model/read-history conn graph) first :entry :inputs first :item-id)
              item-node (first (rdf/select conn
                                           {:prefixes {:t "<urn:tover:inventory-v1:>"}
                                            :select   '[?item]
                                            :where    '[[:graph ?graph [[?item :t/itemId ?id]]]]}
                                           {'?graph graph '?id item}))]
          (rdf/add! conn graph (:item item-node) RDF/TYPE (model/term :Operation))
          (let [error (try (semantics/validate! conn graph) nil (catch clojure.lang.ExceptionInfo error error))]
            (is (= semantics/version (:semantic-version (ex-data error))))
            (is (seq (:errors (ex-data error))))
            (is (every? :message (:errors (ex-data error))))))
        (.rollback conn)))))


(deftest cross-workspace-relationships-are-rejected-by-the-graph-model
  (with-store
    (fn [repo]
      (with-open [conn (.getConnection ^org.eclipse.rdf4j.repository.Repository repo)]
        (.begin conn org.eclipse.rdf4j.common.transaction.IsolationLevels/SERIALIZABLE)
        (let [entry   (rdf/iri "urn:test:entry")
              foreign (rdf/iri "urn:test:foreign-line")]
          (rdf/add! conn graph entry (model/term :workspace) workspace)
          (rdf/add! conn graph entry (model/term :line) foreign)
          (rdf/add! conn graph foreign (model/term :workspace) (rdf/iri "urn:tover:workspace:other"))
          (let [error (try (semantics/validate! conn graph) nil (catch clojure.lang.ExceptionInfo error error))]
            (is (some #(= "Inventory relationships must stay inside their workspace." (:message %))
                      (:errors (ex-data error))))))
        (.rollback conn)))))


(deftest future-facts-remain-durable-drafts-without-affecting-accepted-stock
  (with-store
    (fn [repo]
      (let [scope   {:workspace-id #uuid "00000000-0000-0000-0000-000000000009"}
            line    {:item-id "a" :quantity 10 :location "Main"}
            entries [{:kind :opening :occurred-at "2099-01-01" :lines [line]}
                     {:kind  :operation :type :purchase :party "Supplier" :occurred-at "2099-01-01"
                      :lines [line]}]]
        (doseq [entry entries]
          (let [draft   (store/save-entry! repo scope {:entry entry :actor "alice"})
                before  (store/snapshot repo scope)
                request {:selected [(select-keys draft [:id :revision])] :expected-ledger-revision 0}
                preview (store/preview repo scope request)]
            (is (false? (:valid? preview)))
            (is (re-find #"future facts" (pr-str (:errors preview))))
            (is (thrown? clojure.lang.ExceptionInfo
                         (store/submit! repo scope (assoc request :actor "alice" :command-id (:id draft)))))
            (is (= before (store/snapshot repo scope)))
            (is (empty? (:rows before)))))))))
