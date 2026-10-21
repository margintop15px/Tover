(ns bel.handler-test
  (:require
    [bel.core :as core]
    [bel.core-test :as core-test]
    [bel.fixture]
    [bel.handler :as handler]
    [bel.nav :as nav]
    [bel.routing-fixture]
    [bel.validation :as validation]
    [charred.api :as charred]
    [clojure.test :refer [deftest is use-fixtures]]
    [reitit.core :as reitit]
    [reitit.ring :as ring]
    [ring.middleware.keyword-params :refer [wrap-keyword-params]]
    [ring.middleware.params :refer [wrap-params]]
    [starfederation.datastar.clojure.adapter.http-kit2 :as hk]
    [starfederation.datastar.clojure.api :as datastar]
    [starfederation.datastar.clojure.protocols :as protocols]))


(use-fixtures :each core-test/isolated-registry)


(defn- matched-handler
  [handle kind id]
  (fn [request]
    (let [id (or id (some-> (get-in request [:path-params :id]) keyword))]
      (handle (assoc-in request [:reitit.core/match :result (:request-method request) :data kind] id)))))


(defn- page-handler
  [context id]
  (matched-handler (handler/page-handler context) :bel/page id))


(defn- view-handler
  [context]
  (matched-handler (handler/view-handler context) :bel/view nil))


(defn- action-handler
  [context]
  (matched-handler (handler/action-handler context) :bel/action nil))


(deftest typed-wire-codec
  (let [schema  [:map [:query :string] [:quantity :int] [:busy :boolean]]
        value   {:query "" :quantity Long/MAX_VALUE :busy false}
        encoded (validation/encode-data schema value)]
    (is (= {:query "" :quantity "9223372036854775807" :busy false} encoded))
    (is (= value (validation/decode-input schema encoded)))
    (is (= value (validation/decode-input schema
                                          (charred/read-json (validation/signal-json schema value) :key-fn keyword)))))
  (is (= :validation
         (try (validation/decode-input [:map [:quantity :int]] {:quantity "oops"})
              (catch Exception exception (:type (ex-data exception)))))))


(deftest payload-parsed-and-decoded
  (let [request {:request-method :post
                 :body           (java.io.ByteArrayInputStream. (.getBytes "{\"quantity\":\"42\",\"query\":\"\"}" "UTF-8"))}
        signals (handler/parse-signals request)]
    (is (= {:quantity "42" :query ""} signals))
    (is (= {:quantity 42 :query ""}
           (validation/decode-input [:map [:quantity :int] [:query :string]] signals))))
  (is (= :validation
         (try (handler/parse-signals {:request-method :post :body "[1,2]"})
              (catch Exception exception (:type (ex-data exception)))))))


(deftest native-form-payload-uses-ring-keyword-params
  (let [handle (-> (fn [request] {:request request :signals (handler/parse-signals request)})
                   wrap-keyword-params
                   wrap-params)
        {:keys [request signals]}
        (handle {:request-method :post
                 :headers        {"content-type" "application/x-www-form-urlencoded"}
                 :query-string   "quantity=99&filter=stock"
                 :body           (java.io.ByteArrayInputStream. (.getBytes "quantity=42&query=tea" "UTF-8"))})]
    (is (= {:quantity "99" :query "tea" :filter "stock"} signals))
    (is (identical? (:params request) signals))
    (is (= {"quantity" "42" "query" "tea"} (:form-params request)))
    (is (= {"quantity" "99" "filter" "stock"} (:query-params request)))
    (is (= {:quantity "42"}
           (:signals (handle {:request-method :post
                              :headers        {"content-type" "application/json"}
                              :body           (java.io.ByteArrayInputStream. (.getBytes "{\"quantity\":\"42\"}" "UTF-8"))}))))))


(deftest handlers-dispatch-only-declared-ids
  (core/init! ['bel.fixture])
  (let [response ((page-handler {:greeting "Hello"} :bel.fixture/document)
                  {:request-method :get :query-params {}})]
    (is (= 200 (:status response)))
    (is (= "text/html; charset=utf-8" (get-in response [:headers "content-type"])))
    (is (re-find #"^<!DOCTYPE html>\n<html>" (:body response)))
    (is (re-find #"<h1>Hello</h1>" (:body response)))
    (is (= 404 (:status ((view-handler {}) {:path-params {:id "missing/view"}}))))
    (is (= 404 (:status ((action-handler {}) {:path-params {:id "missing/action"}}))))
    (is (= [{:type :view :id :bel.fixture/row :input {:quantity 43}}
            {:type :signals :schema [:map [:revision :int]] :data {:revision 42}}]
           (handler/execute-action {} :bel.fixture/increment {:quantity "42"})))))


(deftest fragments-have-no-wrapper
  (doseq [siblings [[[:div#a "A"] [:div#b "B"]]
                    (list [:div#a "A"] [:div#b "B"])
                    [[[:div#a "A"]] nil [] [[:div#b "B"]]]]]
    (is (= "<div id=\"a\">A</div><div id=\"b\">B</div>"
           (handler/render-html siblings)))))


(deftest handlers-merge-current-request-context
  (let [contexts (atom [])
        context  {:service :shared-service :scope :base-scope :request :stale-request}
        result   {:type :elements :html [:div "Scoped"]}
        view     {:bel/schema [:map] :bel/output core/Result
                  :bel/query  (fn [context _input] (swap! contexts conj context))
                  :bel/fn     (constantly result)}
        action   {:bel/schema [:map] :bel/output core/Result
                  :bel/fn     (fn [context _input]
                                (swap! contexts conj context)
                                result)}
        request  {:request-method :post :path-params {:id "fixture/scoped"} :body "{}"}
        requests [(assoc request :bel/context {:scope :first-scope :request :ignored})
                  (assoc request :bel/context {:scope :second-scope})
                  request]]
    (with-redefs [core/get-page     (constantly view)
                  core/get-view     (constantly view)
                  core/get-action   (constantly action)
                  hk/->sse-response (fn [& _] {:status 200})]
      (doseq [handle [(page-handler context :fixture/scoped)
                      (view-handler context)
                      (action-handler context)]]
        (reset! contexts [])
        (doseq [request requests]
          (is (= 200 (:status (handle request)))))
        (is (= [{:service :shared-service :scope :first-scope :request (first requests)}
                {:service :shared-service :scope :second-scope :request (second requests)}
                {:service :shared-service :scope :base-scope :request request}]
               (mapv #(update % :request dissoc :reitit.core/match) @contexts)))))))


(deftest mixed-table-roots-use-separate-ordered-patches
  (let [patches    (atom [])
        sse        (reify protocols/SSEGenerator
                     (get-lock [_] (throw (AssertionError. "Per-request SSE must not acquire a lock")))
                     (sse-gen? [_] true))
        roots      [[:div#smart-result "Search"]
                    [:tbody#stock-rows [:tr [:td "Stock"]]]
                    nil []
                    [:div#stock-context "Acknowledged"]]
        definition {:bel/schema [:map]
                    :bel/output core/Result
                    :bel/query  core/input-query
                    :bel/fn     (constantly {:type :elements :html roots})}]
    (with-redefs [core/get-view            (fn [id] (when (= :fixture/mixed id) definition))
                  datastar/patch-elements! (fn [_ html options]
                                             (swap! patches conj {:html html :options options})
                                             true)]
      (handler/send-result! {} sse {:type :view :id :fixture/mixed :input {}})
      (is (= ["<div id=\"smart-result\">Search</div>"
              "<tbody id=\"stock-rows\"><tr><td>Stock</td></tr></tbody>"
              "<div id=\"stock-context\">Acknowledged</div>"]
             (mapv :html @patches)))
      (reset! patches [])
      (handler/send-result! {} sse
                            [{:type :elements :html [nil "" []]}
                             {:type :elements :html "" :selector "#review" :patch-mode :remove}])
      (is (= [{:html "" :options {:d*.elements/selector "#review" :d*.elements/patch-mode "remove"}}]
             @patches)))))


(deftest short-sse-closes-after-success-and-rejects-invalid-input-before-opening
  (core/init! ['bel.fixture])
  (let [events (atom [])
        closed (atom 0)
        opened (atom 0)
        sse    (reify protocols/SSEGenerator
                 (send-event! [_ type lines options]
                   (swap! events conj {:type type :lines lines :options options})
                   true)
                 (close-sse! [_] (swap! closed inc))
                 (sse-gen? [_] true))]
    (with-redefs [hk/->sse-response (fn [_ options]
                                      (swap! opened inc)
                                      ((get options hk/on-open) sse)
                                      {:status 200})]
      (is (= 200 (:status ((action-handler {})
                           {:path-params    {:id "bel.fixture/increment"}
                            :request-method :post
                            :body           "{\"quantity\":\"42\"}"}))))
      (is (= 1 @closed @opened))
      (is (= ["datastar-patch-elements" "datastar-patch-signals"]
             (mapv :type @events)))
      (reset! events [])
      (let [response ((action-handler {})
                      {:path-params    {:id "bel.fixture/increment"}
                       :request-method :post
                       :body           "{\"quantity\":\"invalid\"}"})]
        (is (= 422 (:status response)))
        (is (= 1 @opened @closed))
        (is (empty? @events))
        (is (not (re-find #"notice|error" (:body response))))))))


(deftest instruction-views-preserve-individual-patch-options
  (let [patches    (atom [])
        sse        (reify protocols/SSEGenerator
                     (sse-gen? [_] true))
        definition {:bel/schema [:map]
                    :bel/output core/Result
                    :bel/query  core/input-query
                    :bel/fn     (fn [_]
                                  [{:type :elements :html "" :selector "#old" :patch-mode :remove}
                                   {:type :elements :html [:tr#new [:td "New"]] :selector "#rows" :patch-mode :append}
                                   {:type :elements :html [:button#accepted "12"]}])}]
    (with-redefs [core/get-view            (constantly definition)
                  datastar/patch-elements! (fn [_ html options]
                                             (swap! patches conj {:html html :options options})
                                             true)]
      (handler/send-result! {} sse {:type :view :id :fixture/instructions})
      (is (= [{:html "" :options {:d*.elements/selector "#old" :d*.elements/patch-mode "remove"}}
              {:html    "<tr id=\"new\"><td>New</td></tr>"
               :options {:d*.elements/selector "#rows" :d*.elements/patch-mode "append"}}
              {:html "<button id=\"accepted\">12</button>" :options {}}]
             @patches)))
    (with-redefs [core/get-view (constantly (assoc definition :bel/fn (constantly {:type :unknown})))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (handler/render-view {} :fixture/instructions {}))))))


(deftest instruction-view-closes-after-sending-patches
  (let [events     (atom [])
        closed     (atom 0)
        opened     (atom 0)
        sse        (reify protocols/SSEGenerator
                     (send-event! [_ type lines _options]
                       (swap! events conj {:type type :lines lines})
                       true)
                     (close-sse! [_] (swap! closed inc))
                     (sse-gen? [_] true))
        definition {:bel/schema [:map]
                    :bel/output core/Result
                    :bel/query  core/input-query
                    :bel/fn     (fn [_]
                                  [{:type :elements :html [:button#accepted "12"]}
                                   {:type :signals          :schema [:map [:domainValue :int]]
                                    :data {:domainValue 12}}])}
        current    (atom definition)
        request    {:path-params {:id "fixture/refresh"} :request-method :post :body "{}"}]
    (with-redefs [core/get-view     (fn [_] @current)
                  hk/->sse-response (fn [_ options]
                                      (swap! opened inc)
                                      ((get options hk/on-open) sse)
                                      {:status 200})]
      (is (= 200 (:status ((view-handler {}) request))))
      (is (= ["datastar-patch-elements" "datastar-patch-signals"]
             (mapv :type @events)))
      (reset! events [])
      (reset! current (assoc definition :bel/query (fn [& _] (throw (ex-info "Stock query failed" {})))))
      (is (= 500 (:status ((view-handler {}) request))))
      (is (empty? @events))
      (is (= 1 @opened @closed))
      (reset! current definition)
      (with-redefs [datastar/patch-elements! (fn [& _] false)]
        (is (= 200 (:status ((view-handler {}) request))))
        (is (empty? @events)))
      (is (= 2 @closed))
      (with-redefs [datastar/patch-signals! (fn [& _] (throw (java.io.IOException. "Patch failed")))]
        (is (= 200 (:status ((view-handler {}) request))))
        (is (= ["datastar-patch-elements"] (mapv :type @events))))
      (is (= 3 @closed)))))


(deftest all-nested-instructions-are-prepared-before-sse-opens
  (let [opened (atom 0)
        view   {:bel/schema [:map]
                :bel/output core/Result
                :bel/query  core/input-query
                :bel/fn     (fn [_]
                              [{:type :elements :html [:div#valid "Valid"]}
                               {:type :view :id :fixture/nested}])}
        nested (assoc view :bel/fn (fn [_]
                                     {:type :signals              :schema [:map [:quantity :int]]
                                      :data {:quantity "invalid"}}))]
    (with-redefs [core/get-view     (fn [id] (if (= :fixture/nested id) nested view))
                  hk/->sse-response (fn [& _] (swap! opened inc) {:status 200})]
      (let [response ((view-handler {})
                      {:path-params {:id "fixture/root"} :request-method :post :body "{}"})]
        (is (= 422 (:status response)))
        (is (zero? @opened))
        (is (= "text/plain; charset=utf-8" (get-in response [:headers "content-type"])))))))


(deftest client-cannot-override-fragment-targets
  (core/init! ['bel.fixture])
  (let [prepared (atom nil)]
    (with-redefs [hk/->sse-response      (fn [_ options]
                                           ((get options hk/on-open) nil)
                                           {:status 200})
                  handler/send-prepared! (fn [_ result] (reset! prepared result))
                  datastar/close-sse!    (fn [_])]
      (is (= 200 (:status ((view-handler {})
                           {:path-params    {:id "bel.fixture/row"}
                            :request-method :post
                            :body           "{\"quantity\":\"42\",\"_selector\":\"body\"}"}))))
      (is (= [{:type :elements :html "<div id=\"row\">42</div>" :options {}}] @prepared)))))


(deftest request-input-is-decoded-once-for-pages-views-and-actions
  (let [decodes      (atom 0)
        input-schema [:map
                      [:quantity [:int {:decode/string (fn [value]
                                                         (swap! decodes inc)
                                                         (Long/parseLong (str value)))}]]]
        view         {:bel/schema input-schema     :bel/output core/Result
                      :bel/query  core/input-query :bel/fn     (fn [{:keys [quantity]}]
                                                                {:type :elements :html [:div#quantity quantity]})}
        action       {:bel/schema input-schema :bel/output core/Result
                      :bel/fn     (fn [_ctx {:keys [quantity]}]
                                    {:type :signals :schema [:map [:quantity :int]] :data {:quantity quantity}})}
        request      {:request-method :post :path-params {:id "fixture/quantity"} :body "{\"quantity\":\"42\"}"}]
    (with-redefs [core/get-page     (constantly view)
                  core/get-view     (constantly view)
                  core/get-action   (constantly action)
                  hk/->sse-response (fn [& _] {:status 200})]
      (doseq [handle [(page-handler {} :fixture/quantity)
                      (view-handler {})
                      (action-handler {})]]
        (reset! decodes 0)
        (is (= 200 (:status (handle request))))
        (is (= 1 @decodes))))))


(deftest nested-view-targeting-inherits-defaults-and-leaves-signals-alone
  (let [view   {:bel/schema [:map] :bel/output core/Result :bel/query core/input-query
                :bel/fn     (fn [_]
                              [{:type :view :id :fixture/nested :selector "#nested"}
                               {:type :signals :data {:changed true}}])}
        nested (assoc view :bel/fn
                      (fn [_]
                        [{:type :elements :html ""}
                         {:type :elements :html [:span "New"] :selector "#explicit" :patch-mode :append}]))]
    (with-redefs [core/get-view (fn [id] (if (= :fixture/nested id) nested view))]
      (is (= [{:type :elements :html "" :options {:d*.elements/selector "#nested" :d*.elements/patch-mode "remove"}}
              {:type    :elements :html "<span>New</span>"
               :options {:d*.elements/selector "#explicit" :d*.elements/patch-mode "append"}}
              {:type :signals :json "{\"changed\":true}"}]
             (handler/prepare-result {} {:type     :view       :id         :fixture/root
                                         :selector "#fallback" :patch-mode :remove}))))))


(deftest pages-render-one-root-and-reject-other-results
  (let [definition {:bel/schema [:map] :bel/output core/Result
                    :bel/query  (fn [_ctx _input] "Hello")
                    :bel/fn     (fn [message]
                                  {:type :elements :html [:main [:h1 message] [:p "Body"]]})}
        current    (atom definition)
        request    {:request-method :get :query-params {}}]
    (with-redefs [core/get-page (fn [_] @current)]
      (is (re-find #"<main><h1>Hello</h1><p>Body</p></main>"
                   (:body ((page-handler {} :fixture/document) request))))
      (doseq [result [[:h1 "Bare"]
                      {:type :signals :data {:changed true}}
                      [{:type :elements :html [:h1 "First"]} {:type :elements :html [:p "Second"]}]
                      {:type :elements :html [[:h1 "First"] [:p "Second"]]}]]
        (reset! current (assoc definition :bel/fn (constantly result)))
        (is (= 422 (:status ((page-handler {} :fixture/document) request))))))))


(deftest views-and-actions-check-the-declared-output
  (let [output [:map [:type [:= :elements]] [:html :string]]
        view   {:bel/schema [:map] :bel/output output :bel/query core/input-query
                :bel/fn     (fn [_] {:type :elements :html [:div "Invalid output"]})}
        action (assoc view :bel/fn (fn [_ctx _input] {:type :elements :html [:div "Invalid output"]}))]
    (with-redefs [core/get-view   (constantly view)
                  core/get-action (constantly action)]
      (is (thrown? clojure.lang.ExceptionInfo (handler/render-view {} :fixture/view {})))
      (is (thrown? clojure.lang.ExceptionInfo (handler/execute-action {} :fixture/action {}))))))


(deftest hierarchy-composes-before-one-negotiated-page-response
  (let [events      (atom [])
        root        {:bel/schema   [:map] :bel/output core/PageResult :bel/children? true
                     :bel/document (fn [{:keys [title]} & children]
                                     [:html [:head [:title title]] (into [:body] children)])
                     :bel/query    (fn [_ input] (swap! events conj [:query :root input]) {:heading "Shared"})
                     :bel/fn       (fn [{:keys [heading]} & children]
                                     (swap! events conj [:render :root])
                                     {:type :elements :html (into [:main#shell [:h1 heading]] children)})}
        leaf        {:bel/schema [:map [:view :keyword]] :bel/output core/PageResult :bel/parent :fixture/root
                     :bel/title  "Stock"
                     :bel/query  (fn [context input]
                                   (swap! events conj [:query :leaf input])
                                   {:message (:message context)})
                     :bel/fn     (fn [{:keys [message]}]
                                   (swap! events conj [:render :leaf])
                                   {:type :elements :html [:section#inventory message]})}
        definitions (atom {:fixture/root root :fixture/leaf leaf})
        handle      (page-handler {:message "Inventory"} :fixture/leaf)
        request     {:uri          "/" :query-string "search=blue&datastar=%7B%7D&sort=name"
                     :query-params {"view" "stock" "datastar" "{}"} :request-method :get}
        prepared    (atom nil)
        opened      (atom 0)]
    (with-redefs [core/get-page          #(get @definitions %)
                  hk/->sse-response      (fn [_ options]
                                           (swap! opened inc)
                                           ((get options hk/on-open) nil)
                                           {:status 200})
                  handler/send-prepared! (fn [_ result] (reset! prepared result))
                  datastar/close-sse!    (fn [_])]
      (let [response (handle request)
            html     (:body response)]
        (is (= 200 (:status response)))
        (is (re-find #"^<!DOCTYPE html>\n<html>" html))
        (is (re-find #"<title>Stock</title>" html))
        (is (re-find #"data-bel-url=\"/\?search=blue&amp;sort=name\"" html))
        (is (re-find #"data-bel-title=\"Stock\"" html))
        (is (re-find #"data-bel-page=\"fixture/leaf\"" html))
        (is (re-find #"<main id=\"shell\"><h1>Shared</h1><section id=\"inventory\">Inventory</section></main>" html))
        (is (= [[:query :root {:view "stock"}]
                [:query :leaf {:view :stock}]
                [:render :leaf] [:render :root]] @events)))
      (reset! events [])
      (is (= 200 (:status (handle (assoc request :headers {"datastar-request" "true"})))))
      (is (= 1 @opened))
      (is (= 1 (count @prepared)))
      (is (= :elements (:type (first @prepared))))
      (is (re-find #"^<div id=\"bel-page\"" (:html (first @prepared))))
      (is (not (re-find #"<html|DOCTYPE" (:html (first @prepared)))))
      (swap! definitions assoc-in [:fixture/leaf :bel/query]
             (fn [& _] (throw (ex-info "Leaf failed" {}))))
      (reset! prepared nil)
      (is (= 500 (:status (handle (assoc request :headers {"datastar-request" "true"})))))
      (is (= 1 @opened))
      (is (nil? @prepared)))))


(deftest navigation-links-retain-native-link-semantics
  (let [attrs (nav/link "/team?search=blue" {:class "nav-link" :target "_blank"})]
    (is (= "/team?search=blue" (:href attrs)))
    (is (= "nav-link" (:class attrs)))
    (is (= "_blank" (:target attrs)))
    (is (string? (:data-on:click attrs)))))


(deftest page-policy-errors-propagate-before-rendering-or-opening-sse
  (let [events  (atom [])
        opened  (atom 0)
        failure (atom nil)
        root    {:bel/schema [:map] :bel/output core/PageResult :bel/children? true
                 :bel/query  (fn [_ _] (swap! events conj :root-query) {})
                 :bel/fn     (fn [_ & children]
                               (swap! events conj :root-render)
                               {:type :elements :html (into [:main] children)})}
        leaf    {:bel/schema [:map] :bel/output core/PageResult :bel/parent :fixture/root
                 :bel/query  (fn [_ _] (swap! events conj :leaf-query) (throw @failure))
                 :bel/fn     (fn [_]
                               (swap! events conj :leaf-render)
                               {:type :elements :html [:section]})}
        pages   {:fixture/root root :fixture/leaf leaf}]
    (with-redefs [core/get-page     pages
                  hk/->sse-response (fn [& _] (swap! opened inc) {:status 200})]
      (doseq [handle  [(page-handler {} :fixture/leaf)]
              headers [{} {"datastar-request" "true"}]
              status  [401 403 503]]
        (let [error (ex-info "Provider policy error" {:status status :code "PROVIDER_ERROR"})]
          (reset! failure error)
          (reset! events [])
          (is (identical? error
                          (try (handle {:uri "/team" :request-method :get :headers headers})
                               (catch clojure.lang.ExceptionInfo exception exception))))
          (is (= [:root-query :leaf-query] @events))
          (is (zero? @opened)))))))


(deftest generated-routes-coalesce-methods-and-use-reitit-matches
  (core/build-registry! ['bel.routing-fixture])
  (let [requests (atom [])
        routes   (handler/routes {:requests requests}
                                 [["/team" {:post {:handler (constantly {:status 201})}}]])
        router   (ring/router routes)
        handle   (ring/ring-handler router)]
    (is (= 1 (count (filter #(= "/team" (first %)) routes))))
    (is (= 201 (:status (handle {:uri "/team" :request-method :post}))))
    (is (= :bel.routing-fixture/team
           (get-in (reitit/match-by-path router "/team") [:result :get :data :bel/page])))
    (is (= "/team" (reitit/match->path (reitit/match-by-name router :bel.routing-fixture/team))))
    (is (nil? (reitit/match-by-name router :bel.routing-fixture/shell)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Undeclared view"
                          (handler/render-view {} :bel.routing-fixture/team {})))
    (is (= "<div id=\"row\">Row</div>"
           (:html (first (handler/prepare-result {} {:type :view :id :bel.routing-fixture/row})))))
    (let [response (handle {:uri          "/items/42"             :request-method :get
                            :query-string "q=blue&id=999"
                            :query-params {"q" "blue" "id" "999"}})]
      (is (= 200 (:status response)))
      (is (re-find #"<title>Item</title>" (:body response)))
      (is (re-find #"<section id=\"item\">42:blue</section>" (:body response)))
      (is (= {:id "42"} (:path-params (last @requests)))))
    (is (re-find #"<section id=\"file\">a/b.txt</section>"
                 (:body (handle {:uri "/files/a/b.txt" :request-method :get}))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Duplicate route"
                          (handler/routes {} [["/team" {:get identity}]])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"conflicting route paths"
                          (ring/router (handler/routes {} [["/items/{name}" {:get identity}]]))))))


(deftest page-effects-are-terminal-and-use-named-destinations
  (core/build-registry! ['bel.routing-fixture])
  (let [router      (ring/router (handler/routes {} []))
        request     {:request-method :post :reitit.core/router router}
        destination {:type        :page    :id    :bel.routing-fixture/item
                     :path-params {:id 42} :input {:q "blue socks"}}]
    (is (= {:status 303 :headers {"location" "/items/42?q=blue+socks"} :body ""}
           (handler/page-response request destination)))
    (doseq [instruction [{:type :page :id :bel.routing-fixture/shell}
                         {:type :page :id :bel.routing-fixture/navigate}
                         {:type :page :id :bel.routing-fixture/item}]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (handler/page-response request instruction))))
    (doseq [result [[destination]
                    [{:type :elements :html [:div]} destination]
                    [destination {:type :signals :data {}}]]]
      (is (thrown? clojure.lang.ExceptionInfo (validation/check! core/Result result))))
    (with-redefs [core/get-view
                  (constantly {:bel/schema [:map]           :bel/output core/Result
                               :bel/query  core/input-query :bel/fn     (constantly destination)})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Page instructions require"
                            (handler/prepare-result {} {:type :view :id :fixture/nested}))))))


(deftest action-page-effects-reauthorize-with-original-context-and-clear-source-fields
  (core/build-registry! ['bel.routing-fixture])
  (let [requests (atom [])
        checked  (atom [])
        opened   (atom 0)
        prepared (atom nil)
        policy   {:compile
                  (fn [{:keys [required-role]} _]
                    (when required-role
                      (fn [handler]
                        (fn [request]
                          (swap! checked conj request)
                          (when-not (= required-role (get-in request [:session :role]))
                            (throw (ex-info "Denied" {:status 403})))
                          (when-not (= "current" (get-in request [:headers "x-context"]))
                            (throw (ex-info "Stale page" {:status 409})))
                          (handler request)))))}
        router   (ring/router (handler/routes {:requests requests} []) {:data {:middleware [wrap-keyword-params policy]}})
        handle   (ring/ring-handler router)
        request  {:uri            (core/action-url :bel.routing-fixture/navigate)
                  :request-method :post
                  :session        {:role :manager}
                  :headers        {"datastar-request" "true" "x-context" "current"}
                  :body           "unused source body"
                  :form-params    {"q" "blue" "password" "secret"}
                  :params         {"q" "blue" "password" "secret"}}]
    (with-redefs [hk/->sse-response      (fn [_ options]
                                           (swap! opened inc)
                                           ((get options hk/on-open) nil)
                                           {:status 200})
                  handler/send-prepared! (fn [_ result] (reset! prepared result))
                  datastar/close-sse!    (fn [_])]
      (is (= 200 (:status (handle request))))
      (is (= 1 @opened))
      (is (= :get (:request-method (last @checked))))
      (is (= {} (:form-params (last @checked))))
      (is (= {"q" "blue"} (:query-params (last @checked))))
      (is (= {:q "blue"} (:params (last @checked))))
      (is (nil? (:body (last @checked))))
      (is (= (:session request) (:session (last @checked))))
      (is (= (:headers request) (:headers (last @checked))))
      (is (re-find #"data-bel-navigation=\"true\"" (:html (first @prepared))))
      (is (re-find #"data-bel-reset-form=\"true\"" (:html (first @prepared))))
      (is (re-find #"data-bel-title=\"Team\"" (:html (first @prepared))))
      (doseq [[change status] [[#(assoc-in % [:session :role] :member) 403]
                               [#(assoc-in % [:headers "x-context"] "stale") 409]]]
        (let [before @opened]
          (is (= status (try (handle (change request))
                             (catch clojure.lang.ExceptionInfo error (:status (ex-data error))))))
          (is (= before @opened)))))))


(deftest native-action-responses-pass-through-unchanged
  (core/build-registry! ['bel.routing-fixture])
  (let [result {:status      303 :headers {"location" "/auth/login"}                :body ""
                :session     (with-meta {:user-id "user"} {:recreate true})
                :session/key nil :cookies {"theme" {:value "dark" :http-only true}}
                :extension   {:trace-id "request-1"}}
        handle (ring/ring-handler (ring/router (handler/routes {:action-result result} [])))]
    (is (identical? result (handle {:uri            (core/action-url :bel.routing-fixture/navigate)
                                    :request-method :post :body "{}"})))))


(deftest action-session-and-cookies-are-applied-to-the-final-negotiated-response
  (core/build-registry! ['bel.routing-fixture])
  (let [requests         (atom [])
        original-session {:role :manager}
        cookies          {"theme" {:value "dark" :http-only true}}
        sse-response     (with-meta {:status 200 :headers {"content-type" "text/event-stream"}} {:adapter true})]
    (doseq [[session session-key]   [[nil nil] [{} "rotated"] [(with-meta {:user-id "new-user"} {:recreate true}) nil]]
            [instruction datastar?] [[{:type :page :id :bel.routing-fixture/team} false]
                                     [{:type :page :id :bel.routing-fixture/team} true]
                                     [{:type :page :id :bel.routing-fixture/team :reload? true} true]
                                     [{:type :elements :html [:div "Done"]} true]]]
      (let [result  (assoc instruction :session session :session/key session-key :cookies cookies
                           :status 999 :headers {"location" "/wrong" "content-type" "wrong"})
            handle  (ring/ring-handler
                      (ring/router (handler/routes {:requests requests :action-result result} [])))
            request (cond-> {:uri            (core/action-url :bel.routing-fixture/navigate)
                             :request-method :post :body "{}" :session original-session}
                      datastar? (assoc :headers {"datastar-request" "true"}))]
        (with-redefs [hk/->sse-response (fn [& _] sse-response)]
          (let [response (handle request)]
            (is (= (if datastar? 200 303) (:status response)))
            (is (contains? response :session))
            (is (identical? session (:session response)))
            (is (= (meta session) (meta (:session response))))
            (is (contains? response :session/key))
            (is (= session-key (:session/key response)))
            (is (= cookies (:cookies response)))
            (if datastar?
              (do (is (= (:headers sse-response) (:headers response)))
                  (is (= (meta sse-response) (meta response))))
              (is (= {"location" "/team"} (:headers response))))))
        (when (and datastar? (= :page (:type instruction)) (not (:reload? instruction)))
          (is (= original-session (:session (last @requests)))))))))


(deftest malformed-action-results-cannot-bypass-validation-as-ring-responses
  (core/build-registry! ['bel.routing-fixture])
  (let [opened (atom 0)]
    (with-redefs [hk/->sse-response (fn [& _] (swap! opened inc) {:status 200})]
      (doseq [result [{:type :page :id "invalid" :status 200}
                      {:type :unknown :status 200}
                      [{:status 200}]
                      [{:type :page :id :bel.routing-fixture/home} {:type :signals :data {}}]]]
        (let [handle (ring/ring-handler
                       (ring/router (handler/routes {:action-result result} [])))]
          (is (= 422 (:status (handle {:uri            (core/action-url :bel.routing-fixture/navigate)
                                       :request-method :post :body "{}"}))))))
      (is (zero? @opened)))))


(deftest custom-action-output-still-requires-a-valid-ring-response
  (with-redefs [core/get-action (constantly {:bel/schema [:map] :bel/output :map :bel/fn (constantly {})})]
    (is (= 422 (:status ((action-handler {})
                         {:path-params {:id "fixture/custom"} :request-method :post :body "{}"}))))))


(deftest form-errors-retain-only-explicit-fields-and-do-not-mask-rendering-failure
  (core/build-registry! ['bel.routing-fixture])
  (let [requests (atom [])
        router   (ring/router (handler/routes {:requests requests} []) {:data {:middleware [wrap-keyword-params]}})
        request  {:request-method :post :reitit.core/router router
                  :form-params    {"q" "blue" "password" "secret"}
                  :params         {"q" "blue" "password" "secret"}
                  :error          {:code "DELIVERY_UNKNOWN"}}
        response (handler/error-page-response request :bel.routing-fixture/team {"q" "blue"})]
    (is (= 200 (:status response)))
    (is (= {"q" "blue"} (:form-params (last @requests))))
    (is (= {:q "blue"} (:params (last @requests))))
    (is (= {:code "DELIVERY_UNKNOWN"} (:error (last @requests))))
    (is (re-find #"<section id=\"team\">blue</section>" (:body response)))
    (is (not (re-find #"data-bel-reset-form" (:body response))))
    (let [router (ring/router (handler/routes {:page-failure (ex-info "Query failed" {})} []))]
      (is (= 500 (:status (handler/error-page-response
                            (assoc request :reitit.core/router router)
                            :bel.routing-fixture/team {})))))))


(deftest structured-action-errors-escape-before-opening-sse
  (core/build-registry! ['bel.routing-fixture])
  (let [error  (ex-info "Cooldown" {:status 429 :code "COOLDOWN"})
        router (ring/router (handler/routes {:action-failure error} []))
        handle (ring/ring-handler router)
        opened (atom 0)]
    (with-redefs [hk/->sse-response (fn [& _] (swap! opened inc) {:status 200})]
      (is (identical? error
                      (try (handle {:uri            (core/action-url :bel.routing-fixture/navigate)
                                    :request-method :post :form-params {"q" "blue"} :params {:q "blue"}})
                           (catch clojure.lang.ExceptionInfo actual actual))))
      (is (zero? @opened)))))


(deftest reload-page-effects-redirect-and-close-the-short-stream
  (core/build-registry! ['bel.routing-fixture])
  (let [router      (ring/router (handler/routes {} []))
        request     {:request-method :post :reitit.core/router router}
        instruction {:type :page :id :bel.routing-fixture/home :reload? true}
        events      (atom [])]
    (is (= {:status 303 :headers {"location" "/"} :body ""}
           (handler/page-response request instruction)))
    (with-redefs [hk/->sse-response   (fn [_ options]
                                        ((get options hk/on-open) nil)
                                        {:status 200})
                  datastar/redirect!  (fn [_ url] (swap! events conj [:redirect url]))
                  datastar/close-sse! (fn [_] (swap! events conj :close))]
      (is (= 200 (:status (handler/page-response
                            (assoc request :headers {"datastar-request" "true"})
                            instruction))))
      (is (= [[:redirect "/"] :close] @events)))))


(deftest configured-form-errors-distinguish-input-validation-from-action-output
  (core/build-registry! ['bel.routing-fixture])
  (let [router  (fn [context]
                  (ring/ring-handler
                    (ring/router
                      (mapv (fn [[path data]]
                              [path (cond-> data
                                      (= :bel.routing-fixture/navigate (:name data))
                                      (assoc-in [:post :bel/error-page] :bel.routing-fixture/team))])
                            (handler/routes context [])))))
        handle  (router {})
        request {:uri (core/action-url :bel.routing-fixture/navigate) :request-method :post}]
    (doseq [body ["{\"q\":42}" "{"]]
      (let [error (try (handle (assoc request :body body))
                       (catch clojure.lang.ExceptionInfo error error))]
        (is (= :validation (:type (ex-data error))))
        (is (true? (:bel/input-error? (ex-data error))))))
    (doseq [failure [(ex-info "Unexpected provider failure" {}) (IllegalStateException. "Unexpected failure")]]
      (let [handle (router {:action-failure failure})]
        (is (identical? failure
                        (try (handle (assoc request :body "{}"))
                             (catch Exception error error))))))
    (let [handle (router {:action-result {:type :page :id "invalid-output"}})]
      (is (= 422 (:status (handle (assoc request :body "{}"))))))))
