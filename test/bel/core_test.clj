(ns bel.core-test
  (:require
    [bel.core :as core]
    [bel.expressions :refer [->expr]]
    [bel.fixture :as fixture]
    [bel.handler :as handler]
    [clojure.java.classpath :as classpath]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [clojure.tools.namespace.find :as ns-find]
    [malli.core :as m])
  (:import [java.io File]))


(defn isolated-registry
  {:malli/schema [:=> [:cat [:fn fn?]] :any]}
  [run-test]
  (with-redefs [core/registry (atom {:pages {} :views {} :actions {} :streams {}})]
    (run-test)))


(use-fixtures :each isolated-registry)


(deftest metadata-registry-is-owned-by-bel
  (core/init! ['bel.fixture])
  (is (= #{:bel.fixture/document} (set (keys (:pages @core/registry)))))
  (is (= #{:bel.fixture/row} (set (keys (:views @core/registry)))))
  (is (= #{:bel.fixture/increment} (set (keys (:actions @core/registry)))))
  (is (= :bel/page (:bel/kind (meta #'fixture/document))))
  (is (= :bel/view (:bel/kind (meta #'fixture/row))))
  (is (= :bel/action (:bel/kind (meta #'fixture/increment))))
  (is (= #'fixture/row (get-in @core/registry [:views :bel.fixture/row])))
  (is (= {:quantity 43} ((:bel/query (core/get-view :bel.fixture/row)) {} {:quantity 43})))
  (is (nil? (:bel/query (core/get-action :bel.fixture/increment))))
  (is (= :=> (m/type (m/schema (:malli/schema (meta #'fixture/row))))))
  (is (= :=> (m/type (m/schema (:malli/schema (meta #'fixture/increment))))))
  (is (= "<div id=\"row\">43</div>"
         (handler/render-html (:html (handler/render-view {} :bel.fixture/row {:quantity "43"}))))))


(deftest registry-uses-required-namespaces-and-current-declaration-kind
  (let [original-metadata (meta #'fixture/row)
        public-vars       ns-publics
        scans             (atom 0)
        namespaces        (atom [])]
    (try
      (core/attach-definition! #'fixture/row :bel/page :bel.fixture/row {:schema [:map]})
      (is (nil? (:bel/view (meta #'fixture/row))))
      (with-redefs [core/require-namespaces! (constantly ['bel.fixture])
                    clojure.core/all-ns      (fn [] (swap! scans inc) [])
                    clojure.core/ns-publics  (fn [namespace]
                                               (swap! namespaces conj namespace)
                                               (public-vars namespace))]
        (core/init! ['bel.fixture]))
      (is (zero? @scans))
      (is (= ['bel.fixture] @namespaces))
      (is (= #{:bel.fixture/document :bel.fixture/row} (set (keys (:pages @core/registry)))))
      (is (empty? (:views @core/registry)))
      (is (= :bel/page (:bel/kind (meta (get-in @core/registry [:pages :bel.fixture/row])))))
      (is (= #{:bel.fixture/increment} (set (keys (:actions @core/registry)))))
      (finally
        (reset-meta! #'fixture/row original-metadata)))))


(deftest discovers-unrequired-nested-namespaces
  (let [namespace-symbol 'bel.discovery-fixture.nested
        required         (atom [])
        require!         require]
    (with-redefs [clojure.core/require (fn [& args]
                                         (swap! required into args)
                                         (apply require! args))]
      (core/init! {:scan-ns         ['bel.discovery-fixture]
                   :scan-exclude-ns ['bel.discovery-fixture.excluded]}))
    (is (= [namespace-symbol] (filterv symbol? @required)))
    (is (not-any? #{:reload} @required))
    (is (= #{:bel.discovery-fixture.nested/discovered} (set (keys (:views @core/registry)))))
    (is (empty? (:pages @core/registry)))
    (is (= {:type :elements :html [:div "Discovered"]}
           ((:bel/fn (core/get-view :bel.discovery-fixture.nested/discovered)) {})))))


(deftest discovery-respects-prefix-boundaries-and-exclusions
  (with-redefs [ns-find/find-namespaces
                (fn [_classpath]
                  ['tover
                   'tover.stock.web
                   'tover.admin.views
                   'tover.admin.private
                   'tover-other.views
                   'tover.app-test
                   'tover.app-test.fixture
                   'tover.dev.seed
                   'tover.test.fixtures])]
    (is (= ['tover 'tover.stock.web]
           (core/find-namespaces 'tover ['tover.admin])))))


(deftest rebuilding-removes-old-declarations
  (core/init! ['bel.fixture])
  (let [namespace-symbol 'bel.discovery-fixture.temporary
        namespace        (create-ns namespace-symbol)
        definition-var   (intern namespace 'temporary (fn [_] [:div "Temporary"]))]
    (try
      (core/attach-definition! definition-var :bel/view :bel.discovery-fixture.temporary/temporary {:schema [:map]})
      (core/build-registry! [namespace-symbol])
      (is (some? (core/get-view :bel.discovery-fixture.temporary/temporary)))
      (is (nil? (core/get-page :bel.fixture/document)))
      (ns-unmap namespace 'temporary)
      (core/build-registry! [namespace-symbol])
      (is (= {:pages {} :views {} :actions {} :streams {}} @core/registry))
      (is (nil? (core/get-view :bel.discovery-fixture.temporary/temporary)))
      (finally
        (remove-ns namespace-symbol)))))


(deftest discovery-includes-system-paths-beside-added-loader-paths
  (let [test-path   (File. "test")
        source-path (File. "src")
        scanned     (atom [])]
    (with-redefs [classpath/classpath        (fn [] [test-path])
                  classpath/system-classpath (fn [] [source-path test-path])
                  ns-find/find-namespaces    (fn [paths]
                                               (reset! scanned (vec paths))
                                               ['tover.stock.web])]
      (is (= ['tover.stock.web] (core/find-namespaces 'tover)))
      (is (= [test-path source-path] @scanned)))))


(deftest discovery-keeps-classpath-order
  (with-redefs [ns-find/find-namespaces (fn [_] ['tover.z 'tover.a 'tover.z 'other.views])]
    (is (= ['tover.z 'tover.a] (core/find-namespaces 'tover))))
  (let [required (atom [])]
    (with-redefs [core/find-namespaces (fn [prefix _] [prefix])
                  clojure.core/require (fn [namespace] (swap! required conj namespace))]
      (is (= ['tover.z 'tover.a] (core/require-namespaces! ['tover.z 'tover.a])))
      (is (= ['tover.z 'tover.a] @required)))))


(deftest lookups-read-current-schema-and-query-metadata
  (core/init! ['bel.fixture])
  (let [original-metadata (meta #'fixture/row)
        renderer          @#'fixture/row
        schema            [:map [:next-quantity :int]]
        query             (fn [_ input] {:quantity (inc (:next-quantity input))})]
    (try
      (core/attach-definition! #'fixture/row :bel/view :bel.fixture/row
                               {:schema schema :query query})
      (is (= schema (:bel/schema (core/get-view :bel.fixture/row))))
      (is (identical? query (:bel/query (core/get-view :bel.fixture/row))))
      (core/init! ['bel.fixture])
      (is (identical? renderer @#'fixture/row))
      (is (identical? query (:bel/query (core/get-view :bel.fixture/row))))
      (is (= "<div id=\"row\">44</div>"
             (handler/render-html (:html (handler/render-view {} :bel.fixture/row {:next-quantity "43"})))))
      (finally
        (reset-meta! #'fixture/row original-metadata)))))


(deftest expressions-use-explicit-live-payload
  (testing "The literal action map retains browser signal references"
    (let [expression (->expr (@action :tover.stock.web/preview {:quantity $quantity}))]
      (is (re-find #"@post\(" expression))
      (is (re-find #"/action/tover.stock.web/preview" expression))
      (is (re-find #"quantity.*\$quantity" expression))
      (is (not (re-find #"requestCancellation|retry" expression)))))
  (testing "Runtime ids and params interpolate at Clojure rendering time"
    (let [id         :tover.stock.web/stock
          params     {:query "blue"}
          expression (->expr (@view ~id ~params))]
      (is (re-find #"/view/tover.stock.web/stock" expression))
      (is (re-find #"blue" expression))
      (is (not (re-find #"requestCancellation|retry" expression)))
      (is (not (re-find #"_selector" expression)))
      (is (not (re-find #"_patch_mode" expression)))))
  (is (re-find #"payload.*\{\s*\}" (->expr (@action :tover.stock.web/discard)))))


(deftest expressions-accept-per-call-request-options
  (testing "Literal options retain live browser values"
    (let [expression (->expr (@action :tover.stock.web/preview {:quantity $quantity}
                                      {:requestCancellation "disabled" :retry "never" :retryMaxCount 0}))]
      (is (re-find #"requestCancellation.*disabled" expression))
      (is (re-find #"retry.*never" expression))
      (is (re-find #"retryMaxCount.*0" expression))
      (is (re-find #"quantity.*\$quantity" expression))))
  (testing "Server-provided options do not serialize the live payload"
    (let [options    {:retry "always" :retryMaxCount 2 :headers {"X-Example" "yes"}}
          expression (->expr (@view :tover.stock.web/stock {:query $query} ~options))]
      (is (re-find #"retry.*always" expression))
      (is (re-find #"retryMaxCount.*2" expression))
      (is (re-find #"X-Example.*yes" expression))
      (is (re-find #"query.*\$query" expression))
      (is (not (re-find #"\"\$query\"" expression)))))
  (testing "Views also accept literal options and actions accept server options"
    (let [options {:requestCancellation "cleanup"}]
      (is (re-find #"requestCancellation.*cleanup" (->expr (@view :tover.stock.web/stock {} {:requestCancellation "cleanup"}))))
      (is (re-find #"requestCancellation.*cleanup" (->expr (@action :tover.stock.web/discard {} ~options)))))))


(deftest stream-declarations-share-discovery-and-instrumentation
  (let [namespace-symbol 'bel.discovery-fixture.stream
        namespace        (create-ns namespace-symbol)]
    (try
      (binding [*ns* namespace]
        (refer 'clojure.core)
        (eval '(bel.core/defstream messages
                 {:schema [:map [:type [:enum :connected :changed]]]}
                 [_ctx {:keys [type]}]
                 {:type :signals :data {:lastEvent type}})))
      (core/build-registry! [namespace-symbol])
      (let [id         :bel.discovery-fixture.stream/messages
            definition (core/get-stream id)
            metadata   (meta (:bel/fn definition))]
        (is (= #{id} (set (keys (:streams @core/registry)))))
        (is (= :bel/stream (:bel/kind metadata)))
        (is (nil? (:bel/query definition)))
        (is (= id (:bel/id definition)))
        (is (nil? (:bel/topic-schema definition)))
        (is (= [:=> [:cat :map (:bel/schema definition)] core/Result]
               (:malli/schema metadata)))
        (is (= {:type :signals :data {:lastEvent :connected}}
               ((:bel/fn definition) {} {:type :connected}))))
      (finally (remove-ns namespace-symbol)))))


(deftest declarations-accept-optional-options
  (let [namespace-symbol 'bel.discovery-fixture.optional
        namespace        (create-ns namespace-symbol)]
    (try
      (binding [*ns* namespace]
        (refer 'clojure.core)
        (eval '(bel.core/defpage document [_data] {:type :elements :html [:html]}))
        (eval '(bel.core/defview row "A row" [_data] {:type :elements :html [:div]}))
        (eval '(bel.core/defaction edit [_ctx _input] []))
        (eval '(bel.core/defstream events "A stream" [_ctx payload] {:type :signals :data payload})))
      (core/build-registry! [namespace-symbol])
      (doseq [[section name kind request?] [[:pages "document" :bel/page false]
                                            [:views "row" :bel/view false]
                                            [:actions "edit" :bel/action true]
                                            [:streams "events" :bel/stream true]]]
        (let [id       (keyword (str namespace-symbol) name)
              metadata (meta (get-in @core/registry [section id]))
              output   (case kind :bel/page core/PageResult :bel/action core/ActionResult core/Result)]
          (is (= kind (:bel/kind metadata)))
          (is (= id (:bel/id metadata)))
          (is (= [:map] (:bel/schema metadata)))
          (is (= output (:bel/output metadata)))
          (is (= [:=> (if request? [:cat :map [:map]] [:cat :any])
                  output]
                 (:malli/schema metadata)))))
      (is (= {:type :signals :data {:connected true}}
             ((:bel/fn (core/get-stream :bel.discovery-fixture.optional/events)) {} {:connected true})))
      (finally (remove-ns namespace-symbol)))))


(deftest declaration-output-contracts
  (let [original-metadata (meta #'fixture/row)]
    (try
      (alter-meta! #'fixture/row assoc
                   :bel/data-schema :map :bel/result-schema :any :bel/topic-schema :keyword)
      (core/attach-definition! #'fixture/row :bel/view :bel.fixture/row {})
      (is (= :bel.fixture/row (:bel/id (meta #'fixture/row))))
      (is (nil? (:bel/view (meta #'fixture/row))))
      (is (nil? (:bel/instructions? (meta #'fixture/row))))
      (is (nil? (:bel/data-schema (meta #'fixture/row))))
      (is (nil? (:bel/result-schema (meta #'fixture/row))))
      (is (nil? (:bel/topic-schema (meta #'fixture/row))))
      (is (= [:=> [:cat :any] core/Result] (:malli/schema (meta #'fixture/row))))
      (let [output [:map [:type [:= :signals]] [:data [:map [:ok :boolean]]]]]
        (core/attach-definition! #'fixture/row :bel/view :bel.fixture/row {:output output})
        (is (= output (:bel/output (meta #'fixture/row))))
        (is (= [:=> [:cat :any] output] (:malli/schema (meta #'fixture/row)))))
      (finally (reset-meta! #'fixture/row original-metadata)))))


(deftest instructions-dispatch-on-type-and-validate-patch-modes
  (is (= :multi (m/type (m/schema core/Instruction))))
  (doseq [instruction [{:type :view :id :tover.stock.web/stock}
                       {:type :elements :html [:div]}
                       {:type :signals :data {:ready true}}
                       {:type :elements :html [:div] :extra "Allowed"}]]
    (is (m/validate core/Instruction instruction)))
  (doseq [mode [:outer :inner :replace :prepend :append :before :after :remove]]
    (is (m/validate core/Instruction {:type :view :id :tover.stock.web/stock :patch-mode mode}))
    (is (m/validate core/Instruction {:type :elements :html [:div] :patch-mode mode})))
  (doseq [instruction [{:type :unknown :html [:div]}
                       {:type :view :html [:div]}
                       {:type :signals :data "Invalid"}
                       {:type :elements :html [:div] :patch-mode :upsert}]]
    (is (not (m/validate core/Instruction instruction))))
  (is (m/validate core/Result []))
  (is (m/validate core/Result [{:type :elements :html [:div]} {:type :signals :data {}}]))
  (doseq [raw [nil "HTML" [:div "Raw Hiccup"]]]
    (is (not (m/validate core/Result raw)))))


(deftest action-results-allow-ring-responses-without-weakening-instructions
  (let [response {:status    303 :headers     {"location" "/"} :body    ""
                  :session   nil :session/key nil              :cookies {}
                  :extension {:trace-id "request-1"}}]
    (is (m/validate core/ActionResult response))
    (is (not (m/validate core/Result response))))
  (doseq [result [{:type :elements :html [:div] :extension {:trace-id "request-1"}}
                  [{:type :signals :data {} :extension true}]
                  {:status 200 :type nil :extension true}
                  {:status 200 :type false :extension true}]]
    (is (m/validate core/ActionResult result)))
  (doseq [result [{:status "200"}
                  {:status 200 :type :unknown}
                  {:status 303 :type :page :id "invalid"}
                  [{:status 200}]
                  [{:type :page :id :fixture/home} {:type :signals :data {}}]]]
    (is (not (m/validate core/ActionResult result)))))


(deftest page-hierarchy-is-validated-before-registration
  (let [namespace-symbol 'bel.hierarchy-fixture
        namespace        (create-ns namespace-symbol)]
    (try
      (binding [*ns* namespace]
        (refer 'clojure.core)
        (eval '(bel.core/defpage shell [props & children]
                 {:type :elements :html (into [:main props] children)}))
        (eval '(bel.core/defpage child {:parent :bel.hierarchy-fixture/shell} [_props]
                 {:type :elements :html [:section "Child"]})))
      (core/build-registry! [namespace-symbol])
      (is (= [:bel.hierarchy-fixture/shell :bel.hierarchy-fixture/child]
             (mapv :bel/id (core/page-chain :bel.hierarchy-fixture/child))))
      (is (= [:=> [:cat :any [:* :any]] core/PageResult]
             (:malli/schema (core/get-page :bel.hierarchy-fixture/shell))))
      (let [shell      (ns-resolve namespace 'shell)
            child      (ns-resolve namespace 'child)
            registered @core/registry]
        (doseq [[definition-var options message]
                [[child {:parent :bel.hierarchy-fixture/missing} #"Undeclared parent"]
                 [child {:parent :shell} #"qualified ID"]]]
          (let [original (meta definition-var)]
            (try
              (core/attach-definition! definition-var :bel/page (:bel/id original) options)
              (is (thrown-with-msg? clojure.lang.ExceptionInfo message
                                    (core/build-registry! [namespace-symbol])))
              (is (= registered @core/registry))
              (finally (reset-meta! definition-var original)))))
        (let [original (meta shell)]
          (try
            (alter-meta! shell assoc :bel/children? false)
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"accept children"
                                  (core/build-registry! [namespace-symbol])))
            (reset-meta! shell original)
            (core/attach-definition! shell :bel/page :bel.hierarchy-fixture/shell
                                     {:parent :bel.hierarchy-fixture/shell})
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cyclic page hierarchy"
                                  (core/build-registry! [namespace-symbol])))
            (finally (reset-meta! shell original))))
        (core/attach-definition! child :bel/view :bel.hierarchy-fixture/child {})
        (is (not (contains? (meta child) :bel/parent)))
        (is (not (contains? (meta child) :bel/children?))))
      (finally (remove-ns namespace-symbol)))))


(deftest page-routing-metadata-is-replaced-on-redeclaration
  (let [definition-var #'fixture/document
        original       (meta definition-var)
        document       (fn [_props & children] (into [:html] children))]
    (try
      (core/attach-definition! definition-var :bel/page :bel.fixture/document
                               {:route      "/team/:id"        :title "Team" :document document
                                :route-data {:access :manager}})
      (core/build-registry! ['bel.fixture])
      (is (= "/team/:id" (:bel/route (core/get-page :bel.fixture/document))))
      (is (= "Team" (:bel/title (core/get-page :bel.fixture/document))))
      (is (identical? document (:bel/document (core/get-page :bel.fixture/document))))
      (is (= {:access :manager} (:bel/route-data (core/get-page :bel.fixture/document))))
      (core/attach-definition! definition-var :bel/page :bel.fixture/document {})
      (doseq [key [:bel/route :bel/title :bel/document :bel/route-data]]
        (is (nil? (key (core/get-page :bel.fixture/document)))))
      (core/attach-definition! definition-var :bel/action :bel.fixture/document {})
      (doseq [key [:bel/route :bel/title :bel/document :bel/parent :bel/children?]]
        (is (not (contains? (meta definition-var) key))))
      (doseq [options [{:route "team"} {:route 42} {:title 42} {:document 42} {:route-data []}]]
        (core/attach-definition! definition-var :bel/page :bel.fixture/document options)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid page route metadata"
                              (core/build-registry! ['bel.fixture]))))
      (finally (reset-meta! definition-var original)))))
