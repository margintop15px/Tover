(ns tover.catalogues.rename-test
  (:require
    [bel-rdf.adapter :as source]
    [bel.core :as bel]
    [bel.core-test :as bel-test]
    [bel.delivery :as delivery]
    [bel.handler :as handler]
    [bel.realtime :as realtime]
    [bel.runtime :as runtime]
    [bel.store :as store]
    [charred.api :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [starfederation.datastar.clojure.adapter.http-kit2 :as hk]
    [starfederation.datastar.clojure.api :as datastar]
    [tover.catalogues.persistence :as persistence]
    [tover.catalogues.rename.model :as model]
    [tover.catalogues.rename.web :as web]
    [tover.catalogues.store :as catalogue]
    [tover.inventory.store-test :as fixture]
    [tover.shared.bel :as wiring]
    [tover.shared.rdf :as rdf])
  (:import
    [java.util.concurrent CountDownLatch TimeUnit]))


(defn unit-authority
  "These tests isolate RDF/DSL behavior. Real session/membership guards run in the store integration suite."
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (with-redefs [runtime/with-authority (fn [context _owning _isolation read] (read context))]
    (f)))


(use-fixtures :each bel-test/isolated-registry unit-authority)


(defn context
  {:malli/schema [:=> [:cat rdf/Repository] :map]}
  [repo]
  (runtime/prepare-context {:bel/system  (wiring/system {:repo repo}) :repo repo :workspace-id (:workspace-id fixture/scope)
                            :user-id     #uuid "00000000-0000-0000-0000-000000000011"
                            :memberships [{:workspace-id (:workspace-id fixture/scope) :role :member}]}))


(defn scoped
  {:malli/schema [:=> [:cat :map] :map]}
  [scope]
  {:scope (store/store-scope scope) :graphs persistence/graphs})


(def request
  {:command-id "C" :input {:id "G" :revision 4 :name "Nut"}})


(def cell
  {:kind "goods" :id "G" :revision 4 :path "name" :value "Nut" :commandId "C"})


(defn model-definition
  {:malli/schema [:=> [:cat :qualified-keyword] :map]}
  [id]
  (get-in (bel/operations) [:models id]))


(defn baseline!
  {:malli/schema [:=> [:cat rdf/Repository :map] :any]}
  [repo scope]
  (rdf/with-transaction [tx {:repo repo :level :serializable}]
    (source/write-model! (assoc (scoped scope) :tx tx) (model-definition ::model/goods)
                         {:id    "G"     :name       "Bolt"                 :revision 4 :article "" :barcode "" :unit "pcs"
                          :actor "alice" :updated-at "2026-11-10T00:00:00Z"})))


(defn with-feature
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [f]
  (bel/build-registry! ['tover.catalogues.rename.model 'tover.catalogues.rename.web])
  (fixture/with-store
    (fn [repo path]
      (baseline! repo fixture/scope)
      (f (context repo) path))))


(defn current
  {:malli/schema [:=> [:cat :map] :map]}
  [ctx]
  {:row (runtime/read-model ctx ::model/goods {:id "G"})})


(defn pending-events
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [context]
  (into [] (comp (filter #(= (:scope context) (get-in % [:request :scope]))) (mapcat :events))
        (delivery/pending (:bel/system context))))


(deftest alice-renames-bolt-to-nut-and-can-retry-the-same-intent
  (with-feature
    (fn [ctx _]
      (let [receipt (runtime/execute! ctx ::model/rename-good request)]
        (is (= {:command-id "C" :status :accepted} (select-keys receipt [:command-id :status])))
        (is (= 1 (count (:event-ids receipt))))
        (is (= ["G" "Nut" 5] ((juxt :id :name :revision) (:row (current ctx)))))
        (is (= receipt (runtime/execute! ctx ::model/rename-good request)))
        (is (= :idempotency-conflict
               (:type (fixture/error-data #(runtime/execute! ctx ::model/rename-good
                                                             (assoc-in request [:input :name] "Other"))))))
        (is (= :stale-catalogue
               (:type (fixture/error-data #(runtime/execute! ctx ::model/rename-good
                                                             (assoc request :command-id "stale"))))))))))


(deftest the-grid-bridge-refreshes-through-a-rendering-only-view
  (with-feature
    (fn [ctx _]
      (let [result   (handler/execute-action ctx ::web/save cell)
            prepared (handler/prepare-result ctx result)
            response (-> prepared first :json (json/read-json :key-fn keyword)
                         :_catalogueSaveJson (json/read-json :key-fn keyword))]
        (is (= ["C" "goods" "ok"] ((juxt :commandId :kind :status) response)))
        (is (= "Nut" (get-in response [:gridRow :cells 0 :value])))
        (is (= "Nut" (get-in response [:row :name])))
        (let [failed (-> (handler/prepare-result ctx (handler/execute-action ctx ::web/save (assoc cell :commandId "stale")))
                         first :json (json/read-json :key-fn keyword)
                         :_catalogueSaveJson (json/read-json :key-fn keyword))]
          (is (= ["stale" "failed"] ((juxt :commandId :status) failed)))
          (is (= "Nut" (get-in failed [:current :name]))))))))


(deftest action-returns-data-without-executing-a-command
  (with-redefs [runtime/execute! (fn [& _] (throw (Exception. "Action must be pure")))]
    (let [result (web/save {} cell)]
      (is (= {:type :command :id ::model/rename-good :command-id "C" :input (:input request)}
             (select-keys result [:type :id :command-id :input])))
      (is (= ::web/row (get-in result [:then :id])))
      (is (= ::web/failed (get-in result [:catch :id]))))))


(deftest retryable-recovery-errors-do-not-render-a-rejected-save
  (bel/build-registry! ['tover.catalogues.rename.model 'tover.catalogues.rename.web])
  (with-redefs [runtime/execute! (fn [& _]
                                   (throw (ex-info "Accepted receipt unavailable"
                                                   {:type :journal-unavailable :retryable? true :command-id "C"})))]
    (is (= {:type :journal-unavailable :retryable? true :command-id "C"}
           (fixture/error-data #(handler/execute-action {} ::web/save cell))))))


(deftest rollback-after-domain-and-delivery-writes
  (doseq [stage [#'runtime/apply-event! #'source/save-pending!]]
    (with-feature
      (fn [ctx _]
        (let [original @stage
              staged?  (atom false)]
          (with-redefs-fn {stage (fn [& args]
                                   (apply original args)
                                   (reset! staged? true)
                                   (throw (ex-info "Injected failure after write" {:type :injected})))}
            #(is (= :injected (:type (fixture/error-data (fn [] (runtime/execute! ctx ::model/rename-good request)))))))
          (is @staged?)
          (is (= ["Bolt" 4] ((juxt :name :revision) (:row (current ctx)))))
          (is (empty? (pending-events ctx)))
          ;; Reusing C must execute the decision: no acceptance survived the rollback.
          (is (= :accepted (:status (runtime/execute! ctx ::model/rename-good request)))))))))


(deftest model-uses-native-triples-and-preserves-unmapped-properties
  (with-feature
    (fn [ctx _]
      (let [original (:row (current ctx))
            model    (model-definition ::model/goods)
            vocab    (source/vocabulary model)
            graph    (persistence/graph (:scope ctx) :domain)
            subject  (source/subject (scoped (:scope ctx)) ::model/goods "G")]
        (rdf/with-transaction [tx {:repo (:repo ctx) :level :serializable}]
          (source/write-model! (assoc (scoped (:scope ctx)) :tx tx) model (assoc original :category-id "category/one +😀"))
          (rdf/add! tx graph subject (rdf/iri "urn:test:annotation") "Keep me")
          ;; A same-ID legacy record must stay outside this slice.
          (catalogue/save! tx (:scope ctx) {:kind "goods" :id "G" :revision 0 :path "name" :value "Legacy" :commandId "legacy"} "alice"))
        (runtime/execute! ctx ::model/rename-good request)
        (is (= (assoc (dissoc original :name :revision :actor :updated-at) :category-id "category/one +😀")
               (dissoc (:row (current ctx)) :name :revision :actor :updated-at)))
        (rdf/with-transaction [tx {:repo (:repo ctx) :level :snapshot}]
          (is (= "Nut" (rdf/value-at tx graph subject (get-in vocab [:fields :name]))))
          (is (= 5 (rdf/value-at tx graph subject (get-in vocab [:fields :revision]))))
          (is (= (source/subject (scoped (:scope ctx)) ::model/categories "category/one +😀")
                 (rdf/value-at tx graph subject (get-in vocab [:fields :category-id]))))
          (is (= "Keep me" (rdf/value-at tx graph subject (rdf/iri "urn:test:annotation"))))
          (is (= "Legacy" (rdf/value-at tx (catalogue/graph (:scope ctx)) (catalogue/subject (:scope ctx) :goods "G") (catalogue/term "name"))))
          (let [triples (rdf/select tx '{:select [?predicate ?value] :where [[:graph ?graph [[?row ?predicate ?value]]]]}
                                    {:graph graph :row subject})]
            (is (= (into #{(rdf/iri "http://www.w3.org/1999/02/22-rdf-syntax-ns#type") (rdf/iri "urn:test:annotation")}
                         (map (:fields vocab) (keys (:row (current ctx)))))
                   (set (map :predicate triples))))
            (is (not-any? #(= "payload" (last (str/split (str (:predicate %)) #":"))) triples))))))))


(deftest mapped-optional-fields-are-removed-when-absent
  (with-feature
    (fn [ctx _]
      (let [model       (model-definition ::model/goods)
            definitions (-> (bel/operations)
                            (update-in [:models ::model/goods :events ::model/good-renamed :set] conj :category-id)
                            (update-in [:events ::model/good-renamed :schema] conj [:category-id {:optional true} :string]))]
        (rdf/with-transaction [tx {:repo (:repo ctx) :level :serializable}]
          (source/write-model! (assoc (scoped (:scope ctx)) :tx tx) model (assoc (:row (current ctx)) :category-id "old")))
        (with-redefs [bel/operations (constantly definitions)]
          (runtime/execute! ctx ::model/rename-good request))
        (is (not (contains? (:row (current ctx)) :category-id)))))))


(deftest invalid-mapped-data-rolls-back-instead-of-accepting-a-partial-model
  (with-feature
    (fn [ctx _]
      (let [definitions (assoc-in (bel/operations) [:models ::model/goods :events ::model/good-renamed :set]
                                  [:name :revision :actor :updated-at :article])]
        (with-redefs [bel/operations (constantly definitions)]
          (is (= :validation (:type (fixture/error-data #(runtime/execute! ctx ::model/rename-good request)))))))
      (is (= "Bolt" (get-in (current ctx) [:row :name])))
      (is (empty? (pending-events ctx)))
      (is (= :accepted (:status (runtime/execute! ctx ::model/rename-good request)))))))


(deftest receipts-do-not-depend-on-printer-settings
  (with-feature
    (fn [ctx _]
      (let [receipt (binding [*print-length* 1 *print-level* 1]
                      (runtime/execute! ctx ::model/rename-good request))]
        (is (= receipt (runtime/execute! ctx ::model/rename-good request)))
        (let [delivery (first (delivery/pending (:bel/system ctx)))]
          (is (= receipt (get-in delivery [:result :receipt])))
          (is (= fixture/scope (get-in delivery [:request :scope]))))))))


(deftest authorization-and-workspaces-cover-reads-writes-and-retries
  (with-feature
    (fn [ctx _]
      (let [receipt (runtime/execute! ctx ::model/rename-good request)
            revoked (assoc ctx :memberships [])
            other   (runtime/prepare-context (assoc ctx :workspace-id (:workspace-id fixture/other-scope)
                                                    :memberships [{:workspace-id (:workspace-id fixture/other-scope)}]))]
        (doseq [operation [#(runtime/execute! revoked ::model/rename-good request) #(current revoked)]]
          (is (= :forbidden (:type (fixture/error-data operation)))))
        (is (nil? (:row (current other))))
        (is (= :not-found (:type (fixture/error-data #(runtime/execute! other ::model/rename-good request)))))
        (is (empty? (pending-events other)))
        (baseline! (:repo ctx) fixture/other-scope)
        (let [second-receipt (runtime/execute! other ::model/rename-good (assoc-in request [:input :name] "Other"))]
          (is (not= (:event-ids receipt) (:event-ids second-receipt)))
          (is (= "Other" (get-in (current other) [:row :name])))
          (is (= "Nut" (get-in (current ctx) [:row :name]))))
        (is (= :idempotency-conflict
               (:type (fixture/error-data #(runtime/execute! (runtime/prepare-context (assoc ctx :user-id (random-uuid)))
                                                             ::model/rename-good request)))))))))


(deftest native-bindings-preserve-hostile-text-without-changing-graphs
  (with-feature
    (fn [ctx _]
      (let [name       "Nut\" . } }; DROP ALL; # \n\\😀"
            invocation (assoc-in request [:input :name] name)]
        (runtime/execute! ctx ::model/rename-good invocation)
        (is (= name (get-in (current ctx) [:row :name])))
        (is (some #(= name (get-in % [:data :name])) (pending-events ctx)))
        (is (= :validation
               (:type (fixture/error-data
                        #(runtime/coeffect :rdf/query ctx
                                           {:query '{:select [?row] :where [[?row ?predicate ?value]]} :bindings {:graph (persistence/graph fixture/other-scope :domain)}})))))))))


(deftest publication-is-after-commit-and-cannot-block-or-reject-acceptance
  (with-feature
    (fn [ctx _]
      (let [started (promise) release (promise)]
        (with-redefs [realtime/publish! (fn [_ _ event]
                                          (deliver started {:event event :name (get-in (current ctx) [:row :name])})
                                          (deref release 10000 nil)
                                          (throw (ex-info "Notification failed" {})))]
          (let [work (future (handler/execute-action (assoc-in ctx [:bel/system :resources :realtime] :fixture) ::web/save cell))]
            (try
              (is (= {:event {:topic :tover.stock.web/changed :scope-id (:workspace-id fixture/scope) :data {}}
                      :name  "Nut"}
                     (deref started 10000 ::timeout)))
              (is (= :view (:type (deref work 1000 {}))))
              (is (= "Nut" (get-in (current ctx) [:row :name])))
              (finally (deliver release true) (future-cancel work))))))
      (let [receipt (runtime/execute! ctx ::model/rename-good request)]
        (with-redefs [model/rename-good    (fn [& _] (throw (Exception. "Decision must not rerun")))
                      runtime/apply-event! (fn [& _] (throw (Exception. "Mapping must not rerun")))]
          (is (seq (handler/prepare-result ctx (handler/execute-action ctx ::web/save cell))))
          (is (= receipt (runtime/execute! ctx ::model/rename-good request))))))))


(deftest rendering-failure-leaves-the-accepted-command-recoverable
  (with-feature
    (fn [ctx _]
      (with-redefs [web/row (fn [_] (throw (ex-info "Rendering failed" {:type :validation})))]
        (is (= :validation (:type (fixture/error-data
                                    #(handler/prepare-result ctx (handler/execute-action ctx ::web/save cell)))))))
      (is (= "Nut" (get-in (current ctx) [:row :name])))
      (is (seq (handler/prepare-result ctx (handler/execute-action ctx ::web/save cell))))
      (is (= 1 (count (distinct (map :id (pending-events ctx)))))))))


(deftest a-retry-acknowledges-the-original-command-and-renders-current-state
  (with-feature
    (fn [ctx _]
      (let [receipt (runtime/execute! ctx ::model/rename-good request)]
        (runtime/execute! ctx ::model/rename-good {:command-id "D" :input {:id "G" :revision 5 :name "Latest"}})
        (let [prepared (handler/prepare-result ctx (handler/execute-action ctx ::web/save cell))
              response (-> prepared first :json (json/read-json :key-fn keyword)
                           :_catalogueSaveJson (json/read-json :key-fn keyword))]
          (is (= "C" (:commandId response)))
          (is (= ["Latest" 6] ((juxt :name :revision) (:row response)))))
        (is (= receipt (runtime/execute! ctx ::model/rename-good request)))
        (is (= 2 (count (distinct (map :id (pending-events ctx))))))))))


(deftest ring-action-and-view-handlers-preserve-the-grid-wire-format
  (with-feature
    (fn [ctx _]
      (let [sent    (atom nil)
            request {:request-method    :post :body (json/write-json-str (assoc cell :revision "4"))
                     :reitit.core/match {:result {:post {:data {:bel/action ::web/save}}}}}]
        (with-redefs [hk/->sse-response      (fn [_ options] ((get options hk/on-open) nil) {:status 200})
                      handler/send-prepared! (fn [_ result] (reset! sent result))
                      datastar/close-sse!    (fn [_])]
          (is (= 200 (:status ((handler/action-handler ctx) request))))
          (let [message (-> @sent first :json (json/read-json :key-fn keyword)
                            :_catalogueSaveJson (json/read-json :key-fn keyword))]
            (is (= ["C" "ok" "Nut"] ((juxt :commandId :status #(get-in % [:row :name])) message))))
          (is (= 200 (:status ((handler/view-handler ctx)
                               {:request-method    :get :query-params {"datastar" (json/write-json-str {:id "G" :commandId "C"})}
                                :reitit.core/match {:result {:get {:data {:bel/view ::web/row}}}}}))))
          (is (= "signals" (:type (json/read-json (json/write-json-str (first @sent)) :key-fn keyword)))))))))


(defn concurrent-results
  {:malli/schema [:=> [:cat :map [:vector :map]] [:vector :any]]}
  [ctx requests]
  (let [ready  (CountDownLatch. (count requests))
        decide model/rename-good]
    ;; Both transactions must load their facts before either can stage writes.
    (with-redefs [model/rename-good
                  (fn [facts input]
                    (.countDown ready)
                    (when-not (.await ready 10 TimeUnit/SECONDS)
                      (throw (ex-info "Decisions did not overlap" {:type :test-timeout})))
                    (decide facts input))]
      (let [jobs (mapv (fn [request]
                         (future (try (runtime/execute! ctx ::model/rename-good request)
                                      (catch Exception error error)))) requests)]
        (try
          (mapv #(deref % 15000 ::timeout) jobs)
          (finally (doseq [job jobs] (future-cancel job))))))))


(deftest concurrent-retries-accept-once-and-stale-edits-do-not-overwrite
  (with-feature
    (fn [ctx _]
      (let [results (concurrent-results ctx [request request])
            receipt (runtime/execute! ctx ::model/rename-good request)]
        (is (some #{receipt} results))
        (is (not-any? #{::timeout} results))
        (is (= 5 (get-in (current ctx) [:row :revision])))
        (is (= 1 (count (distinct (map :id (pending-events ctx))))))
        (doseq [result results]
          (if (map? result)
            (is (= receipt result))
            (is (= {:type :conflict :retryable? true :command-id "C"} (ex-data result))))))))
  (with-feature
    (fn [ctx _]
      (let [second   (-> request (assoc :command-id "D") (assoc-in [:input :name] "Other"))
            results  (concurrent-results ctx [request second])
            accepted (filter map? results)]
        (is (= 1 (count accepted)))
        (is (= 5 (get-in (current ctx) [:row :revision])))
        (is (= 1 (count (distinct (map :id (pending-events ctx))))))
        (doseq [result results :when (instance? Exception result)]
          (is (contains? #{:conflict :stale-catalogue} (:type (ex-data result)))))
        (let [loser (if (= "C" (:command-id (first accepted))) second request)]
          (is (= :stale-catalogue (:type (fixture/error-data #(runtime/execute! ctx ::model/rename-good loser))))))))))


(deftest decisions-and-renderers-work-with-plain-data
  (let [row   {:id "G" :name "Bolt" :revision 4 :article "" :barcode "" :unit "pcs"}
        facts {:good row :actor "alice" :now "2026-11-10T00:00:00Z"}
        plan  (model/rename-good facts (:input request))]
    (is (= plan (model/rename-good facts (:input request))))
    (is (= #{:events :fx} (set (keys plan))))
    (is (= {:id "G" :name "Nut" :revision 5}
           (select-keys (get-in plan [:events 0 :data]) [:id :name :revision])))
    (let [rendered (web/row {:model (assoc row :name "Nut" :revision 5) :input {:commandId "C"}})]
      (is (= "Nut" (get-in rendered [:row :name])))
      (is (= "C" (:commandId rendered)))
      (is (not (contains? rendered :type))))))


(deftest composed-rdf-models-use-the-same-active-connection
  (with-feature
    (fn [ctx _]
      (let [read   source/load-model
            seen   (atom [])
            loaded (promise)
            resume (promise)]
        (rdf/with-transaction [tx {:repo (:repo ctx) :level :serializable}]
          (source/write-model! (assoc (scoped (:scope ctx)) :tx tx) (model-definition ::model/categories) {:id "category" :name "Before"})
          (source/write-model! (assoc (scoped (:scope ctx)) :tx tx) (model-definition ::model/goods)
                               (assoc (:row (current ctx)) :category-id "category")))
        (with-redefs [source/load-model
                      (fn [context model id]
                        (swap! seen conj (:tx context))
                        (is (.isActive ^org.eclipse.rdf4j.repository.RepositoryConnection (:tx context)))
                        (let [result (read context model id)]
                          (when (= 1 (count @seen))
                            (deliver loaded true)
                            (when (= ::timeout (deref resume 10000 ::timeout))
                              (throw (ex-info "Reader was not resumed" {:type :test-timeout}))))
                          result))]
          (let [reader (future (runtime/read-model ctx ::model/good-row {:id "G"}))]
            (try
              (is (= true (deref loaded 10000 ::timeout)))
              (rdf/with-transaction [tx {:repo (:repo ctx) :level :serializable}]
                (source/write-model! (assoc (scoped (:scope ctx)) :tx tx) (model-definition ::model/categories) {:id "category" :name "After"}))
              (deliver resume true)
              (let [row (deref reader 10000 {})]
                (is (= "Before" (get-in row [:labels :category-id])))
                (is (not (contains? row :commandId))))
              (is (= 2 (count @seen)))
              (is (identical? (first @seen) (second @seen)))
              (finally
                (deliver resume true)
                (future-cancel reader)))))
        (is (= "After" (get-in (runtime/read-model ctx ::model/good-row {:id "G"}) [:labels :category-id])))))))


(deftest uncommitted-domain-and-delivery-writes-are-not-visible
  (with-feature
    (fn [ctx _]
      (let [staged (promise)            release (promise)
            save   source/save-pending!]
        (with-redefs [source/save-pending! (fn [context delivery]
                                             (save context delivery)
                                             (deliver staged true)
                                             (when (= ::timeout (deref release 10000 ::timeout))
                                               (throw (ex-info "Release timed out" {}))))]
          (let [writer (future (runtime/execute! ctx ::model/rename-good request))]
            (try
              (is (= true (deref staged 10000 ::timeout)))
              (is (= "Bolt" (get-in (current ctx) [:row :name])))
              (is (empty? (pending-events ctx)))
              (deliver release true)
              (is (= :accepted (:status (deref writer 10000 {}))))
              (is (= "Nut" (get-in (current ctx) [:row :name])))
              (finally (deliver release true) (future-cancel writer)))))))))


(deftest pending-acceptance-survives-a-fresh-jvm
  (with-feature
    (fn [ctx path]
      (let [receipt (runtime/execute! ctx ::model/rename-good request)
            code    (pr-str
                      `(do
                         (require '~'tover.catalogues.rename-test)
                         (bel.core/build-registry! ['~'tover.catalogues.rename.model '~'tover.catalogues.rename.web])
                         (let [repo# (tover.shared.rdf/native-store ~(str path))]
                           (try
                             (let [ctx#     (tover.catalogues.rename-test/context repo#)
                                   receipt# (tover.catalogues.rename-test/unit-authority
                                              (fn [] (bel.runtime/execute! ctx# :tover.catalogues.rename.model/rename-good
                                                                           tover.catalogues.rename-test/request)))]
                               (assert (= ~receipt receipt#))
                               (assert (= "Nut" (get-in (tover.catalogues.rename-test/unit-authority
                                                          (fn [] (tover.catalogues.rename-test/current ctx#))) [:row :name])))
                               (println :durable))
                             (finally (tover.shared.rdf/close! repo#))))))]
        (rdf/close! (:repo ctx))
        (let [process (.start (ProcessBuilder. ^java.util.List
                                [(str (System/getProperty "java.home") "/bin/java")
                                 "--enable-native-access=ALL-UNNAMED" "-cp" (System/getProperty "java.class.path")
                                 "clojure.main" "-e" code]))
              output  (future (slurp (.getInputStream process)))
              errors  (future (slurp (.getErrorStream process)))]
          (try
            (is (.waitFor process 60 TimeUnit/SECONDS))
            (when-not (.isAlive process)
              (is (= 0 (.exitValue process)) (deref errors 1000 "stderr timed out"))
              (is (= :durable (edn/read-string (deref output 1000 "nil")))))
            (finally (.destroyForcibly process) (future-cancel output) (future-cancel errors))))))))


(deftest generated-vocabulary-is-stable-and-keeps-qualified-fields-distinct
  (let [model {:model-id ::model/goods :fields {:id {} :name {} :other/name {}}}
        vocab (source/vocabulary model)]
    (is (= "urn:bel:model:tover.catalogues.rename.model%2Fgoods" (str (:type vocab))))
    (is (= "urn:bel:model:tover.catalogues.rename.model%2Fgoods:field:name" (str (get-in vocab [:fields :name]))))
    (is (= 3 (count (set (vals (:fields vocab))))))
    (is (not= (source/subject (scoped fixture/scope) ::model/goods "G")
              (source/subject (scoped fixture/other-scope) ::model/goods "G")))))


(deftest native-model-reads-reject-ambiguous-values-and-cross-workspace-references
  (with-feature
    (fn [ctx _]
      (let [graph   (persistence/graph (:scope ctx) :domain)
            subject (source/subject (scoped (:scope ctx)) ::model/goods "G")
            fields  (:fields (source/vocabulary (model-definition ::model/goods)))]
        (rdf/with-transaction [tx {:repo (:repo ctx) :level :serializable}]
          (rdf/add! tx graph subject (:name fields) "Conflicting name"))
        (is (= :validation (:type (fixture/error-data #(current ctx)))))
        (is (= :validation (:type (fixture/error-data #(runtime/execute! ctx ::model/rename-good request)))))
        (is (empty? (pending-events ctx)))
        (baseline! (:repo ctx) (:scope ctx))
        (rdf/with-transaction [tx {:repo (:repo ctx) :level :serializable}]
          (rdf/add! tx graph subject (:category-id fields) (source/subject (scoped fixture/other-scope) ::model/categories "private")))
        (is (= :validation (:type (fixture/error-data #(runtime/read-model ctx ::model/good-row {:id "G"})))))))))


(deftest catalogue-graphs-refuse-non-workspace-scopes
  (is (= :configuration (:type (fixture/error-data #(persistence/graphs {:kind :user :id (:workspace-id fixture/scope)}))))))


(deftest additional-rdf-types-do-not-hide-or-replace-the-model
  (bel/build-registry! ['tover.catalogues.rename.model 'tover.catalogues.rename.web])
  (fixture/with-store
    (fn [repo _]
      (let [ctx        (context repo)
            graph      (persistence/graph fixture/scope :domain)
            subject    (source/subject (scoped fixture/scope) ::model/goods "G")
            type       (rdf/iri "http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
            extra-type (rdf/iri "urn:test:AdditionalType")]
        (rdf/with-transaction [tx {:repo repo :level :serializable}]
          (rdf/add! tx graph subject type extra-type))
        (is (nil? (:row (current ctx))))
        (baseline! repo fixture/scope)
        (is (= "Bolt" (get-in (current ctx) [:row :name])))
        (is (= :accepted (:status (runtime/execute! ctx ::model/rename-good request))))
        (is (= "Nut" (get-in (current ctx) [:row :name])))
        (rdf/with-transaction [tx {:repo repo :level :snapshot}]
          (is (= #{extra-type (:type (source/vocabulary (model-definition ::model/goods)))}
                 (set (map :type (rdf/select tx '{:select [?type] :where [[:graph ?graph [[?row ?predicate ?type]]]]}
                                             {:graph graph :row subject :predicate type}))))))))))
