(ns bel.handler-test
  (:require
    [bel.core :as core]
    [bel.core-test :as core-test]
    [bel.fixture]
    [bel.handler :as handler]
    [bel.validation :as validation]
    [charred.api :as charred]
    [clojure.test :refer [deftest is use-fixtures]]
    [starfederation.datastar.clojure.adapter.http-kit2 :as hk]
    [starfederation.datastar.clojure.api :as datastar]
    [starfederation.datastar.clojure.protocols :as protocols]))


(use-fixtures :each core-test/isolated-registry)


(deftest typed-wire-codec
  (let [schema [:map [:query :string] [:quantity :int] [:busy :boolean]]
        value {:query "" :quantity Long/MAX_VALUE :busy false}
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


(deftest handlers-dispatch-only-declared-ids
  (core/init! ['bel.fixture])
  (let [response ((handler/page-handler {:greeting "Hello"} :bel.fixture/document)
                  {:request-method :get :query-params {}})]
    (is (= 200 (:status response)))
    (is (= "text/html; charset=utf-8" (get-in response [:headers "content-type"])))
    (is (re-find #"^<!DOCTYPE html>\n<html>" (:body response)))
    (is (re-find #"<h1>Hello</h1>" (:body response)))
    (is (= 404 (:status ((handler/view-handler {}) {:path-params {:id "missing/view"}}))))
    (is (= 404 (:status ((handler/action-handler {}) {:path-params {:id "missing/action"}}))))
    (is (= [{:type :view :id :bel.fixture/row :input {:quantity 43}}
            {:type :signals :schema [:map [:revision :int]] :data {:revision 42}}]
           (handler/execute-action {} :bel.fixture/increment {:quantity "42"})))))


(deftest fragments-have-no-wrapper
  (doseq [siblings [[[:div#a "A"] [:div#b "B"]]
                    (list [:div#a "A"] [:div#b "B"])
                    [[[:div#a "A"]] nil [] [[:div#b "B"]]]]]
    (is (= "<div id=\"a\">A</div><div id=\"b\">B</div>"
           (handler/render-html siblings)))))


(deftest mixed-table-roots-use-separate-ordered-patches
  (let [patches (atom [])
        sse (reify protocols/SSEGenerator
              (get-lock [_] (throw (AssertionError. "Per-request SSE must not acquire a lock")))
              (sse-gen? [_] true))
        roots [[:div#smart-result "Search"]
               [:tbody#stock-rows [:tr [:td "Stock"]]]
               nil []
               [:div#stock-context "Acknowledged"]]
        definition {:bel/schema [:map]
                    :bel/output core/Result
                    :bel/query  core/input-query
                    :bel/fn     (constantly {:type :elements :html roots})}]
    (with-redefs [core/get-view (fn [id] (when (= :fixture/mixed id) definition))
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
        sse (reify protocols/SSEGenerator
              (send-event! [_ type lines options]
                (swap! events conj {:type type :lines lines :options options})
                true)
              (close-sse! [_] (swap! closed inc))
              (sse-gen? [_] true))]
    (with-redefs [hk/->sse-response (fn [_ options]
                                      (swap! opened inc)
                                      ((get options hk/on-open) sse)
                                      {:status 200})]
      (is (= 200 (:status ((handler/action-handler {})
                           {:path-params    {:id "bel.fixture/increment"}
                            :request-method :post
                            :body           "{\"quantity\":\"42\"}"}))))
      (is (= 1 @closed @opened))
      (is (= ["datastar-patch-elements" "datastar-patch-signals"]
             (mapv :type @events)))
      (reset! events [])
      (let [response ((handler/action-handler {})
                      {:path-params    {:id "bel.fixture/increment"}
                       :request-method :post
                       :body           "{\"quantity\":\"invalid\"}"})]
        (is (= 422 (:status response)))
        (is (= 1 @opened @closed))
        (is (empty? @events))
        (is (not (re-find #"notice|error" (:body response))))))))


(deftest instruction-views-preserve-individual-patch-options
  (let [patches (atom [])
        sse (reify protocols/SSEGenerator
              (sse-gen? [_] true))
        definition {:bel/schema [:map]
                    :bel/output core/Result
                    :bel/query  core/input-query
                    :bel/fn     (fn [_]
                                  [{:type :elements :html "" :selector "#old" :patch-mode :remove}
                                   {:type :elements :html [:tr#new [:td "New"]] :selector "#rows" :patch-mode :append}
                                   {:type :elements :html [:button#accepted "12"]}])}]
    (with-redefs [core/get-view (constantly definition)
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
  (let [events (atom [])
        closed (atom 0)
        opened (atom 0)
        sse (reify protocols/SSEGenerator
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
        current (atom definition)
        request {:path-params {:id "fixture/refresh"} :request-method :post :body "{}"}]
    (with-redefs [core/get-view (fn [_] @current)
                  hk/->sse-response (fn [_ options]
                                      (swap! opened inc)
                                      ((get options hk/on-open) sse)
                                      {:status 200})]
      (is (= 200 (:status ((handler/view-handler {}) request))))
      (is (= ["datastar-patch-elements" "datastar-patch-signals"]
             (mapv :type @events)))
      (reset! events [])
      (reset! current (assoc definition :bel/query (fn [& _] (throw (ex-info "Stock query failed" {})))))
      (is (= 500 (:status ((handler/view-handler {}) request))))
      (is (empty? @events))
      (is (= 1 @opened @closed))
      (reset! current definition)
      (with-redefs [datastar/patch-elements! (fn [& _] false)]
        (is (= 200 (:status ((handler/view-handler {}) request))))
        (is (empty? @events)))
      (is (= 2 @closed))
      (with-redefs [datastar/patch-signals! (fn [& _] (throw (java.io.IOException. "Patch failed")))]
        (is (= 200 (:status ((handler/view-handler {}) request))))
        (is (= ["datastar-patch-elements"] (mapv :type @events))))
      (is (= 3 @closed)))))


(deftest all-nested-instructions-are-prepared-before-sse-opens
  (let [opened (atom 0)
        view {:bel/schema [:map]
              :bel/output core/Result
              :bel/query  core/input-query
              :bel/fn     (fn [_]
                            [{:type :elements :html [:div#valid "Valid"]}
                             {:type :view :id :fixture/nested}])}
        nested (assoc view :bel/fn (fn [_]
                                     {:type :signals              :schema [:map [:quantity :int]]
                                      :data {:quantity "invalid"}}))]
    (with-redefs [core/get-view (fn [id] (if (= :fixture/nested id) nested view))
                  hk/->sse-response (fn [& _] (swap! opened inc) {:status 200})]
      (let [response ((handler/view-handler {})
                      {:path-params {:id "fixture/root"} :request-method :post :body "{}"})]
        (is (= 422 (:status response)))
        (is (zero? @opened))
        (is (= "text/plain; charset=utf-8" (get-in response [:headers "content-type"])))))))


(deftest client-cannot-override-fragment-targets
  (core/init! ['bel.fixture])
  (let [prepared (atom nil)]
    (with-redefs [hk/->sse-response (fn [_ options]
                                      ((get options hk/on-open) nil)
                                      {:status 200})
                  handler/send-prepared! (fn [_ result] (reset! prepared result))
                  datastar/close-sse! (fn [_])]
      (is (= 200 (:status ((handler/view-handler {})
                           {:path-params    {:id "bel.fixture/row"}
                            :request-method :post
                            :body           "{\"quantity\":\"42\",\"_selector\":\"body\"}"}))))
      (is (= [{:type :elements :html "<div id=\"row\">42</div>" :options {}}] @prepared)))))


(deftest request-input-is-decoded-once-for-pages-views-and-actions
  (let [decodes (atom 0)
        input-schema [:map
                      [:quantity [:int {:decode/string (fn [value]
                                                         (swap! decodes inc)
                                                         (Long/parseLong (str value)))}]]]
        view {:bel/schema input-schema     :bel/output core/Result
              :bel/query  core/input-query :bel/fn     (fn [{:keys [quantity]}]
                                                        {:type :elements :html [:div#quantity quantity]})}
        action {:bel/schema input-schema :bel/output core/Result
                :bel/fn     (fn [_ctx {:keys [quantity]}]
                              {:type :signals :schema [:map [:quantity :int]] :data {:quantity quantity}})}
        request {:request-method :post :path-params {:id "fixture/quantity"} :body "{\"quantity\":\"42\"}"}]
    (with-redefs [core/get-page (constantly view)
                  core/get-view (constantly view)
                  core/get-action (constantly action)
                  hk/->sse-response (fn [& _] {:status 200})]
      (doseq [handle [(handler/page-handler {} :fixture/quantity)
                      (handler/view-handler {})
                      (handler/action-handler {})]]
        (reset! decodes 0)
        (is (= 200 (:status (handle request))))
        (is (= 1 @decodes))))))


(deftest nested-view-targeting-inherits-defaults-and-leaves-signals-alone
  (let [view {:bel/schema [:map] :bel/output core/Result :bel/query core/input-query
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


(deftest pages-render-result-elements-and-reject-bare-hiccup-or-signals
  (let [definition {:bel/schema [:map] :bel/output core/Result
                    :bel/query  (fn [_ctx _input] "Hello")
                    :bel/fn     (fn [message]
                                  [{:type :elements :html [:h1 message]}
                                   {:type :elements :html [:p "Body"]}])}
        current (atom definition)
        request {:request-method :get :query-params {}}]
    (with-redefs [core/get-page (fn [_] @current)]
      (is (= "<!DOCTYPE html>\n<h1>Hello</h1><p>Body</p>"
             (:body ((handler/page-handler {} :fixture/document) request))))
      (doseq [result [[:h1 "Bare"] {:type :signals :data {:changed true}}]]
        (reset! current (assoc definition :bel/fn (constantly result)))
        (is (= 422 (:status ((handler/page-handler {} :fixture/document) request))))))))


(deftest views-and-actions-check-the-declared-output
  (let [output [:map [:type [:= :elements]] [:html :string]]
        view {:bel/schema [:map] :bel/output output :bel/query core/input-query
              :bel/fn     (fn [_] {:type :elements :html [:div "Invalid output"]})}
        action (assoc view :bel/fn (fn [_ctx _input] {:type :elements :html [:div "Invalid output"]}))]
    (with-redefs [core/get-view (constantly view)
                  core/get-action (constantly action)]
      (is (thrown? clojure.lang.ExceptionInfo (handler/render-view {} :fixture/view {})))
      (is (thrown? clojure.lang.ExceptionInfo (handler/execute-action {} :fixture/action {}))))))
