(ns tover.marketplace.sync-ozon-test
  "Board chapter 41 · Fetch the next source batch and the translation half of Record source observations,
  against a fake Ozon. No live provider calls."
  (:require
    [charred.api :as json]
    [clojure.test :refer [deftest is]]
    [tover.marketplace.sync.ozon :as ozon]))


(defn env
  "A client over `responses` (a vector of {:status :body :headers} or a fn of [path body]); records calls and
  sleeps. The fake clock starts at `start` epoch milliseconds."
  {:malli/schema [:function [:=> [:cat :any] :map] [:=> [:cat :any :int] :map]]}
  ([responses] (env responses 1000000))
  ([responses start]
   (let [queue  (atom responses)
         calls  (atom [])
         sleeps (atom [])
         clock  (atom start)]
     {:calls calls :sleeps sleeps
      :env   {:post      (fn [path body headers]
                           (swap! calls conj {:path path :body body :headers headers :at @clock})
                           (let [r (if (fn? @queue) (@queue path body) (let [[r & more] @queue] (reset! queue (vec more)) r))]
                             (if (instance? Exception r) (throw r) (update r :body #(if (string? %) % (json/write-json-str %))))))
              :sleep     (fn [ms] (swap! sleeps conj ms) (swap! clock + ms))
              :clock     #(deref clock)
              :jitter    (constantly 100)
              :pacer     (atom {})
              :client-id "123" :api-key "key"}})))


(defn ok
  {:malli/schema [:=> [:cat :any] :map]}
  [body]
  {:status 200 :body body})


(defn thrown
  {:malli/schema [:=> [:cat [:fn fn?]] [:maybe :map]]}
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))


;; Client: retries, pacing, allowlist


(deftest throttled-briefly
  (let [{:keys [env calls sleeps]} (env [{:status 429 :headers {"retry-after" "10"}} (ok {:result {:items [] :last_id ""}})])
        result (ozon/request! env "/v3/product/list" {:last_id ""})]
    (is (= 2 (:tries result)))
    (is (= [10000] @sleeps) "waits 10 s in place")
    (is (= [{:last_id ""} {:last_id ""}] (map :body @calls)) "the same page is read again")))


(deftest server-error-then-success
  (let [{:keys [env sleeps]} (env [{:status 502} {:status 502} (ok {:returns [] :has_next false})])]
    (is (= 3 (:tries (ozon/request! env "/v1/returns/list" {}))) "recorded after the third try")
    (is (= [600 1100] @sleeps) "0.5 s and 1 s plus jitter")))


(deftest four-failed-tries
  (let [{:keys [env calls sleeps]} (env (vec (repeat 4 {:status 503})))]
    (is (= :retries-exhausted (:type (thrown #(ozon/request! env "/v1/returns/list" {})))))
    (is (= 4 (count @calls)))
    (is (= [600 1100 2100] @sleeps))))


(deftest timeouts-are-retried
  (let [{:keys [env]} (env [(java.net.http.HttpTimeoutException. "slow") (ok {})])]
    (is (= 2 (:tries (ozon/request! env "/v1/returns/list" {}))))))


(deftest unexpected-client-error-is-not-retried
  (doseq [status [400 404 408 422]]
    (let [{:keys [env calls sleeps]} (env [{:status status} (ok {})])
          error (thrown #(ozon/request! env "/v1/returns/list" {}))]
      (is (= :unexpected-response (:type error)))
      (is (= status (:status error)))
      (is (= 1 (count @calls)) "one try only")
      (is (empty? @sleeps)))))


(deftest long-retry-delay-becomes-resume-not-before
  (let [{:keys [env sleeps]} (env [{:status 429 :headers {"retry-after" "1200"}}])
        error                (thrown #(ozon/request! env "/v1/returns/list" {}))]
    (is (= :throttled (:type error)))
    (is (= (str (java.time.Instant/ofEpochMilli (+ 1000000 1200000))) (:resume-not-before error)) "20 minutes ahead")
    (is (empty? @sleeps) "the worker moves on instead of waiting")))


(deftest item-retry-after-is-minutes
  (is (= 60000 (ozon/retry-after {"item-retry-after" "1"} 0)))
  (is (= 5000 (ozon/retry-after {"retry-after" "Thu, 01 Jan 1970 00:00:05 GMT"} 0))))


(deftest credentials-and-roles
  (is (= :credentials-rejected (:type (thrown #(ozon/request! (:env (env [{:status 401}])) "/v3/product/list" {})))))
  (is (= :unavailable (:type (thrown #(ozon/request! (:env (env [{:status 403}])) "/v1/analytics/stocks" {}))))))


(deftest only-allowlisted-reads
  (let [{:keys [env calls]} (env [(ok {})])]
    (is (= :forbidden (:type (thrown #(ozon/request! env "/v1/product/import" {})))))
    (is (= :forbidden (:type (thrown #(ozon/request! env "/v5/product/info/prices" {})))) "no prices")
    (is (empty? @calls))))


(deftest request-starts-25-ms-apart
  (let [{:keys [env calls]} (env [(ok {}) (ok {}) (ok {})])]
    (dotimes [_ 3] (ozon/request! env "/v1/returns/list" {}))
    (is (= [1000000 1000025 1000050] (map :at @calls)))
    (is (= {"Client-Id" "123" "Api-Key" "key"} (:headers (first @calls))))))


;; Translation


(defn parse
  {:malli/schema [:=> [:cat :string] :map]}
  [text]
  (json/read-json text :bigdec true))


(deftest large-id-kept-exact
  (let [item (parse "{\"id\":9223372036854775807,\"offer_id\":\"00042\",\"sku\":100,\"barcodes\":[\"0000000012345\",\"OZN999\"],\"sources\":[{\"sku\":100},{\"sku\":99}]}")]
    (is (= {:kind        "product" :source-id "9223372036854775807" :name nil :category nil :archived false :provider-updated-at nil
            :identifiers [{:type "product-id" :value "9223372036854775807"} {:type "article" :value "00042"}
                          {:type "sku" :value "100" :role "primary"} {:type "sku" :value "100" :role "source"}
                          {:type "sku" :value "99" :role "historical"} {:type "barcode" :value "0000000012345"}
                          {:type "barcode" :value "OZN999"}]}
           (ozon/product item))
        "leading zeros kept; no SKU derived from the OZN barcode")))


(deftest sku-zero-and-blank-barcode
  (is (= [{:type "product-id" :value "9002"} {:type "article" :value "NO-BARCODE"}]
         (:identifiers (ozon/product {"id" 9002 "offer_id" "NO-BARCODE" "sku" 0 "barcodes" [""] "sources" []})))))


(deftest attributes-keep-groups-and-order
  (is (= [{:attribute-id "4295" :values [{:value "56"} {:value "58"}]}
          {:attribute-id "700" :group-id "complex" :group-index 0 :values [{:value "Cotton" :dictionary-id "8"}]}
          {:attribute-id "700" :group-id "complex" :group-index 1 :values [{:value "Wool" :dictionary-id "9"}]}]
         (:attributes (ozon/attributes {"id"                 101
                                        "attributes"         [{"id" 4295 "values" [{"value" "56"} {"value" "58"}]}]
                                        "complex_attributes" [{"attributes" [{"id" 700 "values" [{"dictionary_value_id" 8 "value" "Cotton"}]}]}
                                                              {"attributes" [{"id" 700 "values" [{"dictionary_value_id" 9 "value" "Wool"}]}]}]})))))


(deftest attribute-definition-type-is-declared
  (is (= {:kind       "attribute-definition" :category-id "200" :type-id "300" :attribute-id "85" :name "Brand"
          :value-type "unknown"              :multiple    false :complex false}
         (ozon/attribute-definition "200" "300" {"id" 85 "name" "Brand"})))
  (is (= "dictionary" (:value-type (ozon/attribute-definition "200" "300" {"id" 1 "type" "String" "dictionary_id" 7})))))


(deftest posting-without-actual-handover-time
  (let [posting (ozon/fbs-posting {"posting_number"  "123-0001-1" "status" "delivering"       "shipment_date" "2026-12-01T10:00:00Z"
                                   "delivering_date" nil "delivery_method" {"warehouse_id" 1}
                                   "products"        [{"sku" 100 "quantity" 2} {"sku" 101 "quantity" 1}]})]
    (is (false? (:complete posting)))
    (is (= ["actual time"] (:missing posting)))
    (is (= 2 (count (:lines posting))) "every line")
    (is (= "2026-12-01T10:00:00Z" (:planned-at posting)))
    (is (nil? (:occurred-at posting)) "planned shipment is never the occurred time")
    (is (false? (:terminal posting)))))


(deftest posting-lines-are-never-collapsed
  (let [posting (ozon/fbs-posting {"posting_number"  "1" "status" "delivered" "delivering_date" "2026-12-01T10:00:00Z"
                                   "delivery_method" {"warehouse_id" 1}
                                   "products"        [{"sku" 100 "quantity" 2} {"sku" 100 "quantity" 3}]})]
    (is (:complete posting))
    (is (:terminal posting))
    (is (= [["1" "2" true] ["2" "3" true]] (map (juxt :line-key :quantity :duplicate) (:lines posting))))))


(deftest unconfirmed-facts-stay-incomplete
  (is (false? (:complete (ozon/fbo-posting {"posting_number" "1" "status" "delivered" "analytics_data" {"warehouse_id" 2}
                                            "products"       [{"sku" 100 "quantity" 1}]}))))
  (is (false? (:complete (ozon/rfbs-return {"return_id" "R1" "state" {"state" "ArrivedAtReturnPlace"} "product" {"sku" "123"}}))))
  (is (false? (:complete (ozon/supply {"order_id" 5 "state" "COMPLETED"} [{"sku" 100 "quantity" 5}]))))
  (is (false? (:complete (ozon/removal {"sku" "100" "quantity_for_return" 4 "return_id" 7} "from-stock")))))


(deftest supply-act-quantities-kept-apart
  (let [order    {"order_id" 5 "state" "COMPLETED" "drop_off_warehouse" {"warehouse_id" 2}}
        bundle   [{"sku" 100 "quantity" 5} {"sku" 200 "quantity" 3}]
        agreeing (ozon/supply order bundle [{"act_id" 77 "sku" 100 "declared_quantity" 5 "fact_quantity" 5 "approved_quantity" 5}
                                            {"act_id" 77 "sku" 200 "declared_quantity" 3 "fact_quantity" 3 "approved_quantity" 3}])
        differ   (ozon/supply order bundle [{"act_id" 77 "sku" 100 "declared_quantity" 5 "fact_quantity" 4 "approved_quantity" 5}
                                            {"act_id" 77 "sku" 200 "declared_quantity" 3 "fact_quantity" 3 "approved_quantity" 3}])]
    (is (= [{:declared-quantity "5" :fact-quantity "4" :approved-quantity "5" :act-id "77"}]
           (map #(not-empty (select-keys % [:declared-quantity :fact-quantity :approved-quantity :act-id :quantity]))
                (take 1 (:lines differ))))
        "the three quantities stay apart and no line quantity is chosen")
    (is (some #{"accepted quantity"} (:missing differ)) "disagreeing act keeps the supply incomplete")
    (is (= ["5" "3"] (map :quantity (:lines agreeing))) "agreeing act gives the line quantity")
    (is (= ["actual time"] (:missing agreeing)) "still incomplete: the actual time is unconfirmed")
    (is (some #{"accepted quantity"} (:missing (ozon/supply order bundle))) "no act, no accepted quantity")))


(deftest supply-acts-read-per-supply-once-accepted
  (let [{:keys [env calls]} (env (fn [path body]
                                   (ok (case path
                                         "/v3/supply-order/list" {:order_ids [5 6] :last_id ""}
                                         "/v3/supply-order/get" {:orders [{:order_id 5 :state "COMPLETED" :supplies [{:supply_id 51 :bundle_id "b"}]}
                                                                          {:order_id 6 :state "DATA_FILLING" :supplies [{:supply_id 61}]}]}
                                         "/v1/supply-order/bundle" {:items [{:sku 100 :quantity 5}] :has_next false}
                                         "/v1/supply-order/act/product/get" {:items [{:sku           100               :act_id            9 :declared_quantity 5
                                                                                      :fact_quantity 5                 :approved_quantity 5
                                                                                      :supply        (:supply_id body)}]}))))
        context             {:window {:from "2026-12-09T06:00:00Z" :to "2026-12-10T06:00:00Z"}}
        listed              (ozon/step "supplies" env context {})
        detailed            (ozon/step "supplies" env context (:state listed))]
    (is (= [{:supply_id "51"}] (map :body (filter #(= "/v1/supply-order/act/product/get" (:path %)) @calls)))
        "only the accepted order's supply")
    (is (= [["5" ["5"]] ["6" []]]
           (map (juxt :source-id #(mapv :quantity (:lines %))) (:observations detailed))))))


(deftest received-return-is-complete
  (let [r (ozon/fbo-fbs-return {"id"       9223372036854775807 "posting_number"                  "FBO-1"   "schema" "FBO"
                                "visual"   {"status" {"sys_name" "ReceivedBySeller"}}
                                "logistic" {"final_moment" "2026-07-27T10:00:00Z"}       "place" {"id" 98}
                                "product"  {"sku" 987 "offer_id" "OFFER-1" "quantity" 1}})]
    (is (:complete r))
    (is (= "fbo-posting:FBO-1" (:related-operation r)))
    (is (= "9223372036854775807" (:source-id r)))))


(deftest stocks-missing-is-incomplete-empty-is-zero
  (is (= [] (ozon/stock-totals {"product_id" 1 "stocks" []})))
  (is (= :incomplete-page (:type (thrown #(ozon/stock-totals {"product_id" 1}))))))


;; Traversal


(deftest repeated-cursor
  (let [{:keys [env]} (env [(ok {:result {:items [{:product_id 1}] :last_id "abc"}})])]
    (is (= :repeated-cursor (:type (thrown #(ozon/step "products" env {} {:last-id "abc"})))))))


(deftest products-list-then-details
  (let [{:keys [env calls]} (env [(ok {:result {:items [{:product_id 9001}] :last_id "abc"}})
                                  (ok {:items [{:id 9001 :offer_id "A-1" :sku 100}]})
                                  (ok {:result {:items [] :last_id ""}})])
        s1                  (ozon/step "products" env {} {})
        s2                  (ozon/step "products" env {} (:state s1))
        s3                  (ozon/step "products" env {} (:state s2))
        s4                  (ozon/step "products" env {} (:state s3))]
    (is (= [[] false] ((juxt :observations :finished?) s1)))
    (is (= ["9001"] (map :source-id (:observations s2))))
    (is (= [[] false] ((juxt :observations :finished?) s3)))
    (is (:finished? s4))
    (is (= {:product_id ["9001"]} (:body (second @calls))) "one identifier family per detail request")))


(deftest missing-detail-is-incomplete
  (let [{:keys [env]} (env [(ok {:items []})])]
    (is (= :incomplete-page (:type (thrown #(ozon/step "products" env {} {:pending ["9001"]})))))))


(deftest non-terminal-return-is-refetched-until-terminal
  (let [{:keys [env calls]} (env (fn [_ body]
                                   (ok (if (get-in body [:filter :posting_numbers])
                                         {:returns  [{:id      7 :posting_number "P-1" :schema "FBS" :visual {:status {:sys_name "MovingToSeller"}}
                                                      :product {:sku 100 :quantity 1}}]
                                          :has_next false}
                                         {:returns [] :has_next false}))))
        context             {:window {:from "2026-12-09T06:00:00Z" :to "2026-12-10T06:00:00Z"} :refetch-ids ["P-1"]}
        refetched           (ozon/step "returns" env context {})]
    (is (= {:posting_numbers ["P-1"]} (get-in (first @calls) [:body :filter])) "read again by posting number")
    (is (= [["7" "P-1" false]] (map (juxt :source-id :refetch-key :terminal) (:observations refetched))))
    (is (not (:finished? refetched)))
    (let [listed (ozon/step "returns" env context (:state refetched))]
      (is (= {:logistic_return_date {:time_from "2026-12-09T06:00:00Z" :time_to "2026-12-10T06:00:00Z"}}
             (get-in (last @calls) [:body :filter])) "then the window")
      (is (:finished? listed)))))


(deftest non-terminal-posting-outside-the-window-is-refetched
  (let [{:keys [env calls]} (env (fn [path body]
                                   (case path
                                     "/v3/posting/fbs/get" (ok {:result {:posting_number (:posting_number body) :status "delivering"}})
                                     "/v4/posting/fbs/list" (ok {:result {:postings [] :has_next false}}))))
        context             {:window {:from "2026-12-09T06:00:00Z" :to "2026-12-10T06:00:00Z"} :refetch-ids ["123-0001-1"]}
        first-step          (ozon/step "fbs-postings" env context {})]
    (is (= ["123-0001-1"] (map :source-id (:observations first-step))))
    (is (= "/v3/posting/fbs/get" (:path (first @calls))))
    (let [listed (ozon/step "fbs-postings" env context (:state first-step))]
      (is (= {:since "2026-12-09T06:00:00Z" :to "2026-12-10T06:00:00Z"} (get-in (last @calls) [:body :filter])))
      (is (:finished? (ozon/step "fbs-postings" env context (:state listed)))))))
