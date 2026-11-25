(ns tover.marketplace.sync-ozon-test
  "Board chapter 41 · Fetch the next source batch and the translation half of Record source observations,
  against a fake Ozon. No live provider calls."
  (:require
    [charred.api :as json]
    [clojure.test :refer [are deftest is]]
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
  (doseq [status [400 404 408 422 425]]
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
    (is (= :forbidden (:type (thrown #(ozon/request! env "/v1/product/import/prices" {})))))
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
  (is (= {:kind       "attribute-definition" :category-id "200" :type-id "300" :attribute-id       "85"  :name "Brand"
          :value-type "unknown"              :multiple    false :complex false :separates-variants false}
         (ozon/attribute-definition "200" "300" {"id" 85 "name" "Brand"})))
  (is (= "dictionary" (:value-type (ozon/attribute-definition "200" "300" {"id" 1 "type" "String" "dictionary_id" 7})))))


(deftest aspect-attributes-separate-variants
  (is (= [true false false]
         (map #(:separates-variants (ozon/attribute-definition "200" "300" (merge {"id" 85} %)))
              [{"is_aspect" true} {"is_aspect" false} {}]))))


(defn cost
  "The cost of a provider price as it arrives over HTTP."
  {:malli/schema [:=> [:cat :any] [:maybe :map]]}
  [price]
  (ozon/cost (parse (if (string? price) price (json/write-json-str price)))))


(deftest cost-is-kept-only-when-entered
  (is (= {:cost "350" :cost-currency "RUB"} (cost {:net_price 350 :currency_code "RUB"})))
  (is (nil? (cost {:net_price 0 :currency_code "RUB"})))
  (doseq [entered ["0.0" "0.00" "null" "\"\"" "\"0\"" "-1"]]
    (is (nil? (cost (str "{\"net_price\":" entered ",\"currency_code\":\"RUB\"}"))) entered))
  (is (nil? (cost {:currency_code "RUB"}))))


(deftest cost-text-is-exact
  (doseq [entered ["350.10" "0.1" "12345678901234567890.123456789" "350.00"]]
    (is (= entered (:cost (cost (str "{\"net_price\":" entered ",\"currency_code\":\"RUB\"}"))))))
  (is (= "350" (:cost (cost "{\"net_price\":3.5E2}"))) "no exponent")
  (is (= :incomplete-page (:type (thrown #(cost {:net_price "many"}))))))


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
  (is (false? (:complete (ozon/supply {"order_id" 5} {"supply_id" 51 "state" "COMPLETED"} [{"sku" 100 "quantity" 5}] []))))
  (is (false? (:complete (ozon/removal {"sku" "100" "quantity_for_return" 4 "return_id" 7} "from-stock")))))


(defn p
  "An FBS posting in `status` with its handover dates."
  {:malli/schema [:=> [:cat :string [:* :any]] :map]}
  [status & {:keys [fact delivering after-ship]}]
  {"posting_number"     "P-1" "status"         status
   "fact_delivery_date" fact "delivering_date" delivering
   "cancellation"       {"cancelled_after_ship" after-ship}
   "delivery_method"    {"warehouse_id" 1}
   "products"           [{"sku" 100 "quantity" 2}]})


(deftest posting-times
  (are [posting at] (= at (:occurred-at (ozon/fbs-posting posting)))
    (p "delivering" :fact "2026-11-26T10:00:00Z" :delivering "2026-11-26T09:00:00Z") "2026-11-26T10:00:00Z"
    (p "delivering" :delivering "2026-11-26T09:00:00Z") "2026-11-26T09:00:00Z"
    (p "delivering" :fact "" :delivering "2026-11-26T09:00:00Z") "2026-11-26T09:00:00Z"
    (p "awaiting_deliver" :fact "2026-11-26T10:00:00Z") nil
    (p "cancelled" :fact "2026-11-26T10:00:00Z") nil
    (p "cancelled" :fact "2026-11-26T10:00:00Z" :after-ship true) "2026-11-26T10:00:00Z"
    (p "cancelled" :after-ship true) nil)
  (is (= ["actual time"] (:missing (ozon/fbs-posting (p "cancelled" :after-ship true)))) "the flag alone proves no time"))


(deftest handed-over-statuses
  (doseq [kind ["fbs-posting" "fbo-posting"]]
    (doseq [status ["delivering" "driver_pickup" "delivered" "client_arbitration" "arbitration" "not_accepted" "sent_by_seller"]]
      (is (true? (ozon/handed-over? kind {"status" status}))))
    (doseq [status ["awaiting_packaging" "awaiting_deliver" "acceptance_in_progress" "cancelled" "unknown" nil]]
      (is (false? (ozon/handed-over? kind {"status" status}))))))


(defn f
  "An FBO posting in `status` of warehouse 77."
  {:malli/schema [:=> [:cat :string [:maybe :string]] :map]}
  [status fact]
  {"posting_number"  "F-1"                  "status"         status              "fact_delivery_date" fact
   "delivering_date" "2026-11-26T09:00:00Z" "analytics_data" {"warehouse_id" 77} "products"           [{"sku" 100 "quantity" 1}]})


(deftest fbo-posting-complete-after-handover
  (is (= [true nil "2026-11-26T10:00:00Z" {:type "fbo" :id "77"}]
         ((juxt :complete :missing :occurred-at :from-warehouse) (ozon/fbo-posting (f "delivering" "2026-11-26T10:00:00Z")))))
  (is (= ["actual time"] (:missing (ozon/fbo-posting (f "delivering" nil)))) "delivering_date never stands in")
  (is (= ["actual time"] (:missing (ozon/fbo-posting (f "awaiting_deliver" "2026-11-26T10:00:00Z")))))
  (is (= ["actual time" "line quantity" "warehouse"]
         (:missing (ozon/fbo-posting (assoc (f "delivering" nil) "products" [] "analytics_data" nil))))))


(deftest cancelled-fbo-posting-with-fact-date-is-handed-over
  (is (= [true "2026-11-26T10:00:00Z" true]
         ((juxt :complete :occurred-at :terminal) (ozon/fbo-posting (f "cancelled" "2026-11-26T10:00:00Z")))))
  (doseq [fact [nil "" " "]]
    (is (false? (ozon/handed-over? "fbo-posting" (assoc (f "cancelled" fact) "cancellation" {"cancelled_after_ship" true}))))
    (is (= ["actual time"] (:missing (ozon/fbo-posting (f "cancelled" fact))))))
  (is (true? (ozon/handed-over? "fbs-posting" (p "cancelled" :after-ship true))))
  (is (false? (ozon/handed-over? "fbs-posting" (p "cancelled" :fact "2026-11-26T10:00:00Z")))))


(deftest financial-price-wins
  (let [prices  #(mapv (juxt :price :price-currency) (:lines %))
        posting (assoc (p "delivered")
                       "products" [{"sku" 100 "quantity" 1 "price" "900.10" "currency_code" "RUB"}
                                   {"sku" 200 "quantity" 1 "price" "50" "currency_code" "RUB"}
                                   {"sku" 300 "quantity" 1 "price" "" "currency_code" "RUB"}
                                   {"sku" 400 "quantity" 1 "price" "40" "currency_code" "RUB"}]
                       "financial_data" {"products" [{"product_id" 400 "price" "" "currency_code" "CNY"}
                                                     {"product_id" 100 "price" 850.50M "currency_code" "CNY"}]})
        twice   (assoc posting
                       "products" [{"sku" 100 "quantity" 1} {"sku" 100 "quantity" 2}]
                       "financial_data" {"products" [{"product_id" 100 "price" 10 "currency_code" "RUB"}
                                                     {"product_id" 100 "price" 20 "currency_code" "RUB"}]})
        ret     {"id"      1 "schema" "Fbo"
                 "product" {"sku" 100 "quantity" 1 "price" {"price" 99.90M "currency_code" "RUB"}
                            "price_without_commission" {"price" 70 "currency_code" "RUB"}}}]
    (is (= [["850.50" "CNY"] ["50" "RUB"] [nil nil] ["40" "RUB"]]
           (prices (ozon/fbs-posting posting)) (prices (ozon/fbo-posting posting))))
    (is (= [["10" "RUB"] ["20" "RUB"]] (prices (ozon/fbs-posting twice))) "repeated lines keep their own price")
    (is (= [["99.90" "RUB"]] (prices (ozon/fbo-fbs-return ret))))))


(deftest details-request-analytics-and-financial-data
  (doseq [[domain path] [["fbs-postings" "/v3/posting/fbs/get"] ["fbo-postings" "/v2/posting/fbo/get"]]]
    (let [{:keys [env calls]} (env [(ok {:result {:posting_number "1"}})])]
      (ozon/step domain env {:window {:from "2026-12-09T06:00:00Z" :to "2026-12-10T06:00:00Z"}} {:started? true :pending ["1"]})
      (is (= [{:path path :body {:posting_number "1" :with {:analytics_data true :financial_data true}}}]
             (mapv #(select-keys % [:path :body]) @calls))))))


(defn act
  "An acceptance act of one item, as `/v1/supply-order/act/product/get` states it."
  {:malli/schema [:=> [:cat :any :any :any :any] :map]}
  [id sku fact approved]
  {"act_id" id "type" "ACCEPTANCE" "unidentified_quantity" 0
   "items"  [{"sku_info"          {"sku" sku "offer_id" (str "A-" sku) "name" "Tee"}
              "declared_quantity" approved "fact_quantity" fact
              "approved_quantity" approved "fact_amount"   1    "approved_amount" 1}]})


(deftest approved-quantity-summed-over-acceptance-acts
  (let [supply (partial ozon/supply {"order_id" 5} {"supply_id" 51 "state" "COMPLETED" "storage_warehouse" {"warehouse_id" 2}})
        bundle [{"sku" 100 "quantity" 11}]]
    (let [s (supply bundle (into [(act 77 100 4 4) (act 78 100 6 6)]
                                 (map #(assoc (act 79 100 9 9) "type" %))
                                 ["DEFECT" "SURPLUS" "SHORTCOMING" "UNSPECIFIED" nil]))]
      (is (= [{:quantity "10" :fact-quantity "10" :approved-quantity "10" :act-id "77,78"}]
             (mapv #(select-keys % [:quantity :fact-quantity :approved-quantity :act-id]) (:lines s)))
          "acts of another or no stated type are no acceptance")
      (is (= ["actual time"] (:missing s)) "the receipt time is never stated"))
    (let [s (supply bundle [(act 77 100 9 10)])]
      (is (= {:fact-quantity "9" :approved-quantity "10"}
             (select-keys (first (:lines s)) [:quantity :fact-quantity :approved-quantity])) "no quantity is chosen")
      (is (some #{"accepted quantity"} (:missing s))))
    (doseq [acts [[(act 77 100 5 4) (act 78 100 5 6)] [(act 77 100 nil 4)] [(act 77 100 4 nil)] [(act 77 100 nil nil)]]]
      (is (some #{"accepted quantity"} (:missing (supply bundle acts))) "acts that differ do not even out"))
    (is (some #{"accepted quantity"} (:missing (supply bundle []))))))


(deftest supply-lines-are-matched-by-sku-once
  (let [supply (partial ozon/supply {"order_id" 5} {"supply_id" 51 "state" "COMPLETED" "storage_warehouse" {"warehouse_id" 2}})
        facts  #(mapv (juxt (fn [l] (mapv :value (:identifiers l))) :quantity) (:lines %))]
    (doseq [second-line [{"sku" 100 "quantity" 6} {"sku" 100 "offer_id" "other" "quantity" 6}]]
      (let [s (supply [{"sku" 100 "offer_id" "A" "quantity" 5} second-line] [(act 77 100 11 11)])]
        (is (= [nil nil] (mapv :quantity (:lines s))) "a sum is never given to two lines")
        (is (some #{"accepted quantity"} (:missing s)))))
    (let [s (supply [{"sku" 100 "offer_id" "A" "quantity" 5}] [(act 77 100 4 4) (act 77 200 1 1) (act 78 200 2 2)])]
      (is (= [[["100" "A"] "4"] [["200" "A-200"] "3"]] (facts s)) "an accepted SKU the bundle does not list is one line")
      (is (every? (comp nil? :price) (:lines s))))
    (is (= [[["100" "A"] nil] [["200" "A-200"] nil]] (facts (supply [{"sku" 100 "offer_id" "A" "quantity" 5}] [(act 77 200 1 2)])))
        "without an agreeing act an act-only line has no quantity")
    (is (= [nil nil] (mapv :quantity (:lines (supply [{"quantity" 1}] [(act 77 nil 1 1)])))) "a line without SKU is never matched")))


(defn traverse
  "Every step of `domain` from `state` until it finishes, meets a setback or `limit` steps passed. Each state goes
  through text, as the stored position does."
  {:malli/schema [:=> [:cat :string :map :map :map :int] [:vector :map]]}
  [domain env context state limit]
  (loop [state state acc []]
    (let [s (ozon/step domain env context (read-string (pr-str state)))]
      (if (or (:finished? s) (:setback s) (<= limit (inc (count acc))))
        (conj acc s)
        (recur (:state s) (conj acc s))))))


(defn observe-supplies
  "The detail batches of order 5, its bundles and acts answered by `answer`; with the calls made."
  {:malli/schema [:=> [:cat [:sequential :map] [:fn fn?]] [:map [:calls :any] [:observations :any]]]}
  [supplies answer]
  (let [{:keys [env calls]} (env (fn [path body]
                                   (ok (if (= "/v3/supply-order/get" path)
                                         {:orders [{:order_id 5 :state "IN_TRANSIT" :order_tags {:seller_warehouse_id 1} :supplies supplies}]}
                                         (answer path body)))))
        steps               (traverse "supplies" env {} {:started? true :pending ["5"] :listed? true} 30)]
    (when-let [setback (some :setback steps)]
      (throw (ex-info "setback" setback)))
    {:observations (vec (mapcat :observations steps))
     :calls        @calls}))


(deftest one-operation-per-supply
  (let [{:keys [observations calls]}
        (observe-supplies [{:supply_id 51 :bundle_id "a" :state "COMPLETED" :storage_warehouse {:warehouse_id 2 :name "Two"}}
                           {:supply_id 52 :bundle_id "b" :storage_warehouse {:warehouse_id 3}}
                           {:supply_id 53 :bundle_id "c"}]
                          (fn [path body]
                            (if (= "/v1/supply-order/bundle" path)
                              {:items [{:sku ({"a" 100 "b" 200 "c" 300} (first (:bundle_ids body))) :quantity 5}] :has_next false}
                              {:supply_id (:supply_id body) :supply_acts [(act 9 100 5 5)] :skus_defects []})))
        ops (filterv #(= "operation" (:kind %)) observations)]
    (is (= [["51" "COMPLETED" true "2" "100" "5"] ["52" "IN_TRANSIT" false "3" "200" nil] ["53" "IN_TRANSIT" false nil "300" nil]]
           (mapv (juxt :source-id :status :terminal (comp :id :to-warehouse)
                       #(get-in % [:lines 0 :identifiers 0 :value]) #(get-in % [:lines 0 :quantity]))
                 ops)))
    (is (= [["actual time"] ["actual time" "line quantity" "accepted quantity"]
            ["actual time" "line quantity" "warehouse" "accepted quantity"]]
           (mapv :missing ops)) "a supply without storage warehouse lacks the warehouse, though its origin is stated")
    (is (= ["5/51" "5/52" "5/53"] (mapv :refetch-key ops)) "supplies are read again by their order")
    (is (= [{:supply_id 51}] (mapv :body (filter #(= "/v1/supply-order/act/product/get" (:path %)) calls)))
        "acts of the accepted supply only")
    (is (= [["fbo" "2" "Two"] ["fbo" "3" nil]]
           (mapv (juxt :warehouse-type :source-id :name) (filter #(= "warehouse" (:kind %)) observations))))))


(deftest acts-stated-or-the-page-is-incomplete
  (let [read (fn [answer]
               (observe-supplies [{:supply_id 51 :bundle_id "a" :state "COMPLETED"}]
                                 (fn [path _] (if (= "/v1/supply-order/bundle" path) {:items [{:sku 100 :quantity 5}] :has_next false} answer))))]
    (doseq [answer [{:supply_id 51} {:supply_acts nil} {:items [{:sku 100}]} {:supply_acts [{:act_id 1 :type "ACCEPTANCE"}]}]]
      (is (= :incomplete-page (:type (thrown #(read answer))))))
    (is (some #{"accepted quantity"} (:missing (first (:observations (read {:supply_acts []}))))) "no acts")))


(deftest order-without-supply-facts
  (let [read (fn [state order]
               (let [{:keys [env]} (env (fn [path _]
                                          (ok (if (= "/v3/supply-order/get" path) {:orders [(merge {:order_id 5 :state state} order)]}
                                                  {:items [] :has_next false}))))]
                 (:observations (ozon/step "supplies" env {} {:started? true :pending ["5"]}))))]
    (doseq [order [{} {:supplies nil} {:supplies [{:supply_id ""}]} {:supplies [{:supply_id 0}]} {:supplies [{}]}]]
      (doseq [state ["DATA_FILLING" "CANCELLED"]]
        (is (= [] (read state order))))
      (doseq [state ["READY_TO_SUPPLY" "IN_TRANSIT" "COMPLETED" nil]]
        (is (= :incomplete-page (:type (thrown #(read state order)))))))
    (is (= ["51"] (map :source-id (read "CANCELLED" {:supplies [{:supply_id ""} {:supply_id 51}]}))) "what is stated is read")
    (is (= [] (read "IN_TRANSIT" {:supplies []})) "an empty list is stated")))


(defn posting-lists
  "List pages by cursor, as both posting lists answer; a detail states its number."
  {:malli/schema [:=> [:cat :map] :map]}
  [pages]
  (env (fn [path body]
         (ok (if (contains? #{"/v4/posting/fbs/list" "/v3/posting/fbo/list"} path)
               (pages (:cursor body))
               {:result {:posting_number (:posting_number body) :status "delivering"}})))))


(deftest posting-lists-page-by-cursor
  (doseq [[domain path] [["fbs-postings" "/v4/posting/fbs/list"] ["fbo-postings" "/v3/posting/fbo/list"]]]
    (let [{:keys [env calls]} (posting-lists {""  {:has_next true :cursor "x" :postings [{:posting_number "P-1"}]}
                                              "x" {:has_next true :cursor "y" :postings []}
                                              "y" {:has_next false :postings [{:posting_number "P-2"}]}})
          context             {:window {:from "2026-12-09T06:00:00Z" :to "2026-12-10T06:00:00Z"}}
          ;; Each state goes through text, as the stored position does.
          steps               (loop [state {} acc []]
                                (let [s (ozon/step domain env context (read-string (pr-str state)))]
                                  (if (or (:finished? s) (< 10 (count acc))) (conj acc s) (recur (:state s) (conj acc s)))))
          listed              (filter #(= path (:path %)) @calls)]
      (is (= ["P-1" "P-2"] (map :source-id (mapcat :observations steps))))
      (is (:finished? (last steps)))
      (is (= (for [cursor ["" "x" "y"]]
               {:filter {:since "2026-12-09T06:00:00Z" :to "2026-12-10T06:00:00Z"} :limit 100 :cursor cursor :sort_dir "ASC"})
             (map :body listed))))
    (doseq [[type page] {:repeated-cursor {:has_next true :cursor "x" :postings []}
                         :incomplete-page {:has_next true :postings []}}]
      (is (= type (:type (thrown #(ozon/step domain (:env (posting-lists {"x" page}))
                                             {:window {:from "2026-12-09T06:00:00Z" :to "2026-12-10T06:00:00Z"}}
                                             {:started? true :pending [] :at "x"}))))))))


(defn supply-list
  "Order ids newest first, `size` a page, continuing after `last_id`; `before` runs before each list call."
  {:malli/schema [:=> [:cat :any :int [:fn fn?]] :map]}
  [orders size before]
  (env (fn [path body]
         (ok (if (= "/v3/supply-order/list" path)
               (do (before body)
                   (let [after (if (= "" (:last_id body)) @orders (rest (drop-while #(not= (str %) (:last_id body)) @orders)))
                         ids   (take size after)]
                     {:order_ids ids :last_id (if (seq ids) (str (last ids)) (:last_id body))}))
               {:orders (for [id (:order_ids body)] {:order_id id :state "DATA_FILLING" :supplies []})})))))


(defn order-pages
  "Orders by id, as [state, time of the last state change, number of supplies]. `limit` answers a call with a
  failure, or with nil to let it succeed."
  {:malli/schema [:=> [:cat [:sequential [:tuple :int :any :any :int]] [:fn fn?]] :map]}
  [orders limit]
  (let [by-id (into {} (map (juxt first identity)) orders)]
    (env (fn [path body]
           (or (limit path body)
               (ok (case path
                     "/v3/supply-order/list" {:order_ids (if (= "" (:last_id body)) (map first orders) []) :last_id "end"}
                     "/v3/supply-order/get" {:orders (for [id   (map parse-long (:order_ids body))
                                                           :let [[_ state updated n] (by-id id)]]
                                                       {:order_id id :state state :state_updated_date updated
                                                        :supplies (for [i (range n)] {:supply_id (+ (* 100 id) i) :bundle_id (str id "-" i)})})}
                     "/v1/supply-order/bundle" {:items [{:sku 100 :quantity 1}] :has_next false}
                     {:supply_acts []})))))))


(defn paths
  "How many of the calls went to `path`."
  {:malli/schema [:=> [:cat :any :string] :int]}
  [calls path]
  (count (filter #(= path (:path %)) @calls)))


(def act-path
  "/v1/supply-order/act/product/get")


(defn metered
  "Every step of the supplies domain with the calls it made."
  {:malli/schema [:=> [:cat :map :any :map] [:vector :map]]}
  [env calls state]
  (loop [state state acc []]
    (reset! calls [])
    (let [s   (ozon/step "supplies" env {} (read-string (pr-str state)))
          acc (conj acc (assoc s :calls (count @calls) :acts (paths calls act-path)))]
      (if (or (:finished? s) (< 300 (count acc))) acc (recur (:state s) acc)))))


(deftest one-act-call-per-step
  (let [{:keys [env calls]} (order-pages (for [id (range 50 0 -1)] [id "COMPLETED" "2026-01-01T00:00:00Z" 2]) (constantly nil))
        steps               (metered env calls {})
        ids                 (map :source-id (mapcat :observations steps))]
    (is (= [100 100] [(count ids) (count (distinct ids))]) "each supply once")
    (is (= 1 (apply max (map :acts steps))) "no high request rate")
    (is (>= 10 (apply max (map :calls steps))))))


(deftest supply-page-keeps-what-was-read-before-a-429
  (let [left                (atom 2)
        {:keys [env calls]} (order-pages [[3 "IN_TRANSIT" nil 1] [2 "COMPLETED" nil 1] [1 "COMPLETED" nil 1]]
                                         (fn [path _]
                                           (when (and (= act-path path) (pos? (first (swap-vals! left dec))))
                                             {:status 429 :headers {"retry-after" "1200"}})))
        [one]               (traverse "supplies" env {} {:started? true :pending ["3" "2" "1"] :listed? true} 1)]
    (is (= ["300"] (map :source-id (:observations one))))
    (is (= :throttled (get-in one [:setback :type])))
    (is (= ["2" "1"] (:pending (:state one))))
    (is (= :throttled (:type (thrown #(ozon/step "supplies" env {} (:state one))))) "nothing new was read: only the wait")
    (reset! calls [])
    (is (= ["200" "100"] (map :source-id (mapcat :observations (traverse "supplies" env {} (:state one) 10)))))
    (is (= 2 (paths calls act-path)))))


(deftest long-bundle-is-read-over-steps
  (let [{:keys [env calls]} (env (fn [path body]
                                   (ok (if (= "/v3/supply-order/get" path)
                                         {:orders [{:order_id 5 :state "IN_TRANSIT" :supplies [{:supply_id 51 :bundle_id "a"}]}]}
                                         (let [n (if (= "" (:last_id body)) 0 (parse-long (:last_id body)))]
                                           {:items [{:sku (inc n) :quantity 1 :name "Tee"}] :last_id (str (inc n)) :has_next (< n 24)})))))
        steps               (metered env calls {:started? true :pending ["5"] :listed? true})]
    (is (>= 10 (apply max (map :calls steps))))
    (is (= [(map str (range 1 26))] (map (fn [o] (map #(get-in % [:identifiers 0 :value]) (:lines o))) (mapcat :observations steps))))))


(deftest detail-calls-of-one-step-are-bounded
  (doseq [[domain path] [["fbs-postings" "/v3/posting/fbs/get"] ["fbo-postings" "/v2/posting/fbo/get"]]
          :let          [context {:window {:from "2026-12-09T06:00:00Z" :to "2026-12-10T06:00:00Z"}}
                         posting (fn [body] (ok {:result {:posting_number (:posting_number body) :status "delivering"}}))]]
    (let [pending             (mapv str (range 25))
          {:keys [env calls]} (env (fn [_ body] (posting body)))
          s                   (ozon/step domain env context {:started? true :pending pending})]
      (is (= 10 (paths calls path)))
      (is (= (subvec pending 10) (:pending (:state s)))))
    (let [slow                (atom 1)
          {:keys [env calls]} (env (fn [_ body]
                                     (if (and (= "1" (:posting_number body)) (pos? (first (swap-vals! slow dec))))
                                       {:status 503}
                                       (posting body))))
          s                   (ozon/step domain env context {:started? true :pending ["0" "1" "2"]})]
      (is (= [["0" "1"] 3] [(map :source-id (filter #(= "operation" (:kind %)) (:observations s))) (count @calls)])
          "a request that had to be repeated ends the step"))))


(deftest only-new-changed-and-open-orders-are-detailed
  (let [{:keys [env calls]} (order-pages [[9 "COMPLETED" "2026-12-10T05:00:00Z" 1] [8 "COMPLETED" "2026-12-01T00:00:00Z" 1]
                                          [7 "COMPLETED" "2026-11-30T00:00:00Z" 1] [6 "COMPLETED" "2026-11-29T00:00:00Z" 1]]
                                         (constantly nil))
        steps               (traverse "supplies" env {:settled-before "2026-12-09T00:00:00Z"      :refetch-ids ["7/700"]
                                                      :known-orders   (constantly #{"9" "8" "7"})}
                                      {} 30)]
    (is (= ["900" "700" "600"] (map :source-id (mapcat :observations steps))) "8 is known, unchanged and terminal")
    (is (= ["" "end"] (map (comp :last_id :body) (filter #(= "/v3/supply-order/list" (:path %)) @calls))) "every order is listed")
    (is (:finished? (last steps)))))


(deftest open-order-gone-from-the-list-is-not-asked-for
  (let [{:keys [env calls]} (order-pages [] (constantly nil))]
    (is (:finished? (last (traverse "supplies" env {:refetch-ids ["7/700"]} {} 10))))
    (is (= 0 (paths calls "/v3/supply-order/get")))))


(deftest cancelled-order-closes-its-known-supplies
  (let [facts  {:operation-kind "supply" :source-id "51" :status "IN_TRANSIT" :terminal false :complete false :lines [] :refetch-key "5/51"}
        asked  (atom [])
        closed (fn [state n]
                 (let [{:keys [env]} (order-pages [[5 state nil n]] (constantly nil))]
                   (filter #(= "51" (:source-id %))
                           (mapcat :observations
                                   (traverse "supplies" env {:refetch-ids ["5/51" "6/61"] :stored-supplies (fn [ids] (swap! asked conj ids) [facts])}
                                             {:started? true :pending ["5"] :listed? true} 5)))))]
    (is (= [(assoc facts :kind "operation" :status "CANCELLED" :terminal true)] (closed "CANCELLED" 0)))
    (is (= [["5/51"]] @asked) "its own open supplies only")
    (is (= [] (closed "DATA_FILLING" 0)))))


(deftest open-removals-are-read-again-from-their-first-coverage
  (let [{:keys [env calls]} (env (fn [_ _] (ok {:returns_summary_report_rows [] :last_id ""})))
        context             {:window {:from "2026-12-09T06:00:00Z" :to "2026-12-10T06:00:00Z"} :reread-from "2026-10-20T06:00:00Z"}
        dates               (fn [context]
                              (reset! calls [])
                              (traverse "removals" env context {} 20)
                              (for [source ["from-stock" "from-supply"]]
                                (map (comp (juxt :date_from :date_to) :body)
                                     (filter #(= (str "/v1/removal/" source "/list") (:path %)) @calls))))
        ranges              [["2026-10-20T06:00:00Z" "2026-11-19T06:00:00Z"] ["2026-11-19T06:00:00Z" "2026-12-10T06:00:00Z"]]]
    (is (= [ranges ranges] (dates context)) "no longer range than the first run asks for")
    (is (= [["2026-12-09T06:00:00Z" "2026-12-10T06:00:00Z"]] (first (dates (dissoc context :reread-from)))))))


(defn listed-orders
  "Every order id whose details one run of the supplies domain reads."
  {:malli/schema [:=> [:cat :map :any] :any]}
  [env calls]
  (reset! calls [])
  (loop [state {} n 0]
    (let [s (ozon/step "supplies" env {} state)]
      (when-not (or (:finished? s) (< 20 n)) (recur (:state s) (inc n)))))
  (mapcat (comp :order_ids :body) (filter #(= "/v3/supply-order/get" (:path %)) @calls)))


(deftest supplies-are-listed-newest-first
  (let [orders              (atom [5 4 3 2 1])
        {:keys [env calls]} (supply-list orders 2 (fn [body] (when (and (= "4" (:last_id body)) (not= 6 (first @orders))) (swap! orders #(into [6] %)))))]
    (is (= ["5" "4" "3" "2" "1"] (listed-orders env calls)) "an order inserted at the head moves no page: none skipped or read twice")
    (is (= [{:filter   {:states ["DATA_FILLING" "READY_TO_SUPPLY" "ACCEPTED_AT_SUPPLY_WAREHOUSE" "IN_TRANSIT"
                                 "ACCEPTANCE_AT_STORAGE_WAREHOUSE" "REPORTS_CONFIRMATION_AWAITING" "REPORT_REJECTED" "COMPLETED"
                                 "REJECTED_AT_SUPPLY_WAREHOUSE" "CANCELLED" "OVERDUE"]}
             :limit    100
             :sort_by  "ORDER_STATE_UPDATED_AT"
             :sort_dir "DESC"
             :last_id  ""}]
           (take 1 (map :body @calls))))
    (is (= ["6" "5" "4" "3" "2" "1"] (listed-orders env calls)) "the next run reads the new order"))
  (let [{:keys [env]} (env [(ok {:order_ids [5] :last_id "x"})])]
    (is (= :repeated-cursor (:type (thrown #(ozon/step "supplies" env {} {:started? true :pending [] :at "x"})))))))


(deftest rfbs-list-continues-from-an-integer
  (let [{:keys [env calls]} (env [(ok {:returns []})])]
    (ozon/step "rfbs-returns" env {:window {:from "2026-12-09T06:00:00Z" :to "2026-12-10T06:00:00Z"}} {:started? true :pending [] :at "9223372036854775807"})
    (is (= 9223372036854775807 (:last_id (:body (first @calls)))))))


(deftest bundle-pages
  (let [read (fn [pages]
               (let [left   (atom 10)
                     answer (fn [_ body]
                              (when (neg? (swap! left dec)) (throw (ex-info "endless" {:type :endless})))
                              (pages (:last_id body)))]
                 (->> (observe-supplies [{:supply_id 51 :bundle_id "a"}] answer)
                      :observations first :lines (map #(get-in % [:identifiers 0 :value])))))
        page (fn [sku more? last-id] {:items [{:sku sku :quantity 1}] :has_next more? :last_id last-id})]
    (is (= ["1" "2"] (read {"" (page 1 true "x") "x" (page 2 false "x")})))
    (is (= ["1"] (read {"" (page 1 false "x")})) "has_next decides")
    (is (= :repeated-cursor (:type (thrown #(read {"" (page 1 true "x") "x" (page 2 true "x")})))))
    (is (= :incomplete-page (:type (thrown #(read {"" (page 1 true "")})))))
    (is (= :repeated-cursor (:type (thrown #(read {"" (page 1 true "x") "x" (page 2 true "y") "y" (page 3 true "x")}))))
        "a cycle is never followed")))


(deftest removals-given-out-or-disposed
  (let [row   {"return_id" 7 "box_id" 8 "sku" 100 "quantity_for_return" 4 "given_out_date" "" "utilization_date" " "}
        facts (juxt :occurred-at :disposed #(get-in % [:lines 0 :quantity]) :missing :terminal)]
    (is (= [nil false nil ["actual time" "line quantity" "warehouse"] false] (facts (ozon/removal row "from-stock"))) "blank is absent")
    (is (= ["2026-11-26T10:00:00Z" false "4" ["warehouse"] true]
           (facts (ozon/removal (assoc row "given_out_date" "2026-11-26T10:00:00Z") "from-stock"))))
    (is (= ["2026-11-27T10:00:00Z" true "4" ["warehouse"] true]
           (facts (ozon/removal (assoc row "utilization_date" "2026-11-27T10:00:00Z") "from-supply"))))))


(def window
  {:window {:from "2026-12-09T06:00:00Z" :to "2026-12-10T06:00:00Z"}})


(defn observe-detail
  "Observations of one detail batch of `domain` whose every answer is `body`."
  {:malli/schema [:=> [:cat :string :map] [:vector :map]]}
  [domain body]
  (:observations (ozon/step domain (:env (env (fn [_ _] (ok body)))) window {:started? true :pending ["1"]})))


(defn warehouses
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector :any]]}
  [observations]
  (mapv (juxt :warehouse-type :source-id :name) (filter #(= "warehouse" (:kind %)) observations)))


(defn observe-fbo-posting
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [posting]
  (observe-detail "fbo-postings" {:result (merge {:posting_number "F-1" :status "delivering" :products [{:sku 100 :quantity 1}]}
                                                 posting)}))


(deftest fbo-posting-names-a-new-warehouse
  (let [observed (observe-fbo-posting {:analytics_data {:warehouse_id 77 :warehouse_name "Hub"}})]
    (is (some #(= ["warehouse" "fbo" "77"] ((juxt :kind :warehouse-type :source-id) %)) observed))
    (is (= {:type "fbo" :id "77" :name "Hub"} (:from-warehouse (first observed))))
    (is (= [["fbo" "9223372036854775807" nil]]
           (warehouses (observe-fbo-posting {:analytics_data {:warehouse_id 9223372036854775807}})))
        "int64 kept exact")))


(deftest absent-ids-are-no-warehouse
  (doseq [id [nil "" " " 0 "0"]]
    (is (nil? (ozon/warehouse-observation "fbo" id "Hub")))
    (let [observed (observe-fbo-posting {:analytics_data {:warehouse_id id}})]
      (is (= [] (warehouses observed)))
      (is (nil? (:from-warehouse (first observed))))
      (is (some #{"warehouse"} (:missing (first observed)))))
    (doseq [[state row] [[{} {:warehouse_id id :name "W"}]
                         [{:phase :supply-origin} {:seller_warehouse_id id :seller_warehouse_name "W"}]]]
      (is (= :incomplete-page (:type (thrown #(ozon/step "warehouses" (:env (env [(ok {:warehouses [row]})])) {} state))))
          "a list row without an id fails the page, so the pass never finishes"))))


(deftest stock-names-its-warehouse
  (let [context {:skus (constantly ["100"]) :sku->product (constantly {"100" "9001"})}
        step    #(:observations (ozon/step "stock-analytics" (:env (env [(ok {:items %})])) context {}))
        row     {:sku 100 :warehouse_id 2 :warehouse_name "FBO 2" :available_stock_count 6}]
    (is (= [{:kind      "stock" :source "warehouse-analytics"          :product-source-id "9001" :part "100"
             :warehouse {:type "fbo" :id "2" :name "FBO 2"} :present 6}
            {:kind "warehouse" :warehouse-type "fbo" :source-id "2" :name "FBO 2"}]
           (step [row])))
    (is (= [["fbo" "2" "FBO 2"]] (warehouses (step [row row]))) "once per batch")
    (is (= [["fbo" "2" "FBO 2"]] (warehouses (step [(assoc row :sku 555)]))) "also when the listing is not resolved")
    (is (= [] (step [(assoc row :warehouse_id 0)])) "a figure that names no warehouse is no per-warehouse figure")))


(deftest supply-names-its-warehouses
  (let [[operation & more] (observe-detail "supplies"
                                           {:orders [{:order_id          "1" :state "DATA_FILLING"
                                                      :dropoff_warehouse {:warehouse_id 5 :name "Drop-off"}
                                                      :order_tags        {:seller_warehouse_id 1}
                                                      :supplies          [{:supply_id 51 :storage_warehouse {:warehouse_id 2 :name "FBO 2"}}]}]
                                            :items  []})]
    (is (= [{:type "supply-origin" :id "1"} {:type "fbo" :id "2" :name "FBO 2"}]
           ((juxt :from-warehouse :to-warehouse) operation)))
    (is (= [["fbo" "2" "FBO 2"]] (warehouses more)) "supply origins come from the seller's list; a drop-off point is no storage")))


(deftest seller-lists-decide-the-type
  (let [{:keys [env]} (env [(ok {:warehouses [{:warehouse_id 1 :name "A" :is_rfbs false} {:warehouse_id 3 :name "B" :is_rfbs true}] :has_next false})
                            (ok {:warehouses [{:seller_warehouse_id 1 :seller_warehouse_name "A" :is_active true :is_pickup false}]})])
        listed        (ozon/step "warehouses" env {} {})
        origins       (ozon/step "warehouses" env {} (:state listed))]
    (is (= [["fbs" "1" "A"] ["rfbs" "3" "B"]] (warehouses (:observations listed))))
    (is (= [["supply-origin" "1" "A"]] (warehouses (:observations origins))))))


(defn observe-return
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [row]
  (let [row (merge {:id       9223372036854775807 :posting_number "FBO-1"
                    :visual   {:status {:sys_name "ReceivedBySeller"}}
                    :logistic {:final_moment "2026-07-27T10:00:00Z"}
                    :product  {:sku 987 :offer_id "OFFER-1" :quantity 1}}
                   row)]
    (:observations (ozon/step "returns" (:env (env [(ok {:returns [row] :has_next false})])) window {}))))


(defn names-no-place
  "The return is the only observation, names no warehouse and waits for the receiving one."
  {:malli/schema [:=> [:cat [:vector :map]] :any]}
  [observed]
  (let [[r & more] observed]
    (is (nil? more))
    (is (false? (:complete r)))
    (is (= ["receiving warehouse"] (:missing r)))
    (is (= [nil nil [nil]] ((juxt :from-warehouse :to-warehouse #(mapv :warehouse (:lines %))) r)))))


(deftest fbo-return-received-by-ozon
  (let [[r :as observed] (observe-return {:schema "Fbo" :visual {:status {:sys_name "ReturnedToOzon"}} :place {:id 88} :target_place {:id 89}})]
    (names-no-place observed)
    (is (= ["fbo-posting:FBO-1" "9223372036854775807" "fbo" false "2026-07-27T10:00:00Z" "ReturnedToOzon" true]
           ((juxt :related-operation :source-id :return-schema :compensated :occurred-at :status :terminal) r)))))


(deftest fbo-return-received-by-seller
  (let [[r :as observed] (observe-return {:schema "Fbo"})]
    (names-no-place observed)
    (is (= ["fbo" "ReceivedBySeller" "2026-07-27T10:00:00Z"] ((juxt :return-schema :status :occurred-at) r)))))


(deftest fbo-return-lacks-receiving-warehouse
  (names-no-place (observe-return {:schema "FBO" :place {:id 88 :name "Hub"}})))


(deftest fbs-return-lacks-receiving-warehouse
  (let [[r :as observed] (observe-return {:schema "FBS" :visual {:status {:sys_name "SomeStatus"}} :place {:id 9}})]
    (names-no-place observed)
    (is (= ["fbs" "fbs-posting:FBO-1" "2026-07-27T10:00:00Z"] ((juxt :return-schema :related-operation :occurred-at) r)))))


(deftest no-final-moment-no-receipt
  (doseq [moment [nil ""]
          row    [{} {:type "Cancellation"} {:compensation_status {:status {:id 1 :sys_name "Sent"}}}]]
    (is (= ["actual time" "receiving warehouse"]
           (:missing (first (observe-return (merge {:schema "FBO" :logistic {:final_moment moment :return_date "2026-07-26T10:00:00Z"}} row))))))))


(deftest compensation-is-recorded-beside-the-final-moment
  (are [status compensated] (= [compensated "2026-07-27T10:00:00Z"]
                               ((juxt :compensated :occurred-at) (first (observe-return {:schema "FBO" :compensation_status status}))))
    {:status {:id 1 :display_name "Sent" :sys_name "Sent"}} true
    {:status {:id 2 :display_name "Received" :sys_name "Received"}} true
    {:status {:id 3 :display_name "Canceled" :sys_name "Canceled"}} false
    {:status {:id 4 :display_name "Reversed" :sys_name "DecompensationSent"}} false
    {:status {:id 0 :display_name "" :sys_name ""}} false
    nil false))


(deftest return-of-another-schema
  (is (= [] (observe-return {:schema "FBP"})) "FBP operations are not read")
  (is (= [nil nil] ((juxt :return-schema :related-operation) (first (observe-return {:schema "New"})))) "never guessed"))


(deftest fbs-return-at-a-pickup-point
  (names-no-place (observe-return {:schema "FBS" :place {:id 9}})))


(deftest pickup-point-is-not-a-warehouse
  (is (= ["operation"] (map :kind (observe-return {:schema "FBS" :place {:id 9}})))))


(deftest rfbs-return-warehouse-is-not-proven
  (let [observed (observe-detail "rfbs-returns" {:returns {:return_id "R1" :warehouse_id 4 :product {:sku 100}}})]
    (is (= [] (warehouses observed)))
    (is (= [nil ["actual time" "line quantity" "receiving warehouse"]] ((juxt :to-warehouse :missing) (first observed))))))


(deftest stocks-missing-is-incomplete-empty-is-zero
  (is (= [] (ozon/stock-totals {"product_id" 1 "stocks" []})))
  (is (= :incomplete-page (:type (thrown #(ozon/stock-totals {"product_id" 1}))))))


;; Traversal


(deftest prices-page-by-cursor
  (let [{:keys [env calls]} (env [(ok {:cursor "c1" :total 2 :items [{:product_id 9223372036854775807 :offer_id "A"
                                                                      :price      {:net_price 350.5 :currency_code "RUB" :price 900}}
                                                                     {:product_id 9002 :price {:net_price 0 :currency_code "RUB"}}]})
                                  (ok {:cursor "c1" :items []})])
        s1                  (ozon/step "prices" env {} {})
        s2                  (ozon/step "prices" env {} (:state s1))]
    (is (= [{:kind "product-cost" :source-id "9223372036854775807" :cost "350.5" :cost-currency "RUB"}
            {:kind "product-cost" :source-id "9002"}]
           (:observations s1))
        "a listing without a cost is stated too, so a withdrawn cost clears")
    (is (= [false [] true] [(:finished? s1) (:observations s2) (:finished? s2)]) "an empty page ends the list")
    (is (= [{:filter {:visibility "ALL"} :limit 1000 :cursor ""} {:filter {:visibility "ALL"} :limit 1000 :cursor "c1"}]
           (map :body @calls)))))


(def one-listing
  "SKUs 1 and 2 of listing 1 are asked for; SKU 3 is listing 2's and is not."
  {:skus (constantly ["1" "2"]) :sku->products (constantly {"1" #{"1"} "2" #{"1"} "3" #{"2"}})})


(deftest repeated-page-cursor-fails
  (doseq [[domain state body] [["prices" {:cursor "c1"} {:cursor "c1" :items [{:product_id 1 :price {}}]}]
                               ["stock-totals" {:cursor "c1"} {:cursor "c1" :items [{:product_id 1 :stocks []}]}]
                               ["stock-by-warehouse" {:phase :fbo :pending ["1"] :cursor "c1"}
                                {:cursor "c1" :has_next true :products [{:product_id 1 :sku 1 :warehouse_id 2 :present 1}]}]]]
    (is (= :repeated-cursor (:type (thrown #(ozon/step domain (:env (env [(ok body)])) one-listing state)))) domain)))


(deftest unreadable-price-and-stock-rows-are-incomplete
  (doseq [[domain body] [["prices" {}] ["prices" {:items [{:product_id 1}]}] ["prices" {:items [{:price {}}]}]
                         ["stock-by-warehouse" {:has_next false}]
                         ["stock-by-warehouse" {:has_next true :products []}]
                         ["stock-by-warehouse" {:cursor "x" :products []}]
                         ["stock-by-warehouse" {:has_next false :products [{:product_id 1 :sku 1 :warehouse_id 2}]}]
                         ["stock-by-warehouse" {:has_next false :products [{:product_id 1 :sku 1 :warehouse_id 0 :present 1}]}]]]
    (is (= :incomplete-page (:type (thrown #(ozon/step domain (:env (env [(ok body)])) one-listing {})))) (pr-str body))))


(defn warehouse-rows
  "What one FBO and one FBS page with these rows give: the figures, or the failure."
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector :any]]}
  [rows]
  (vec (for [phase [:fbo :fbs]
             :let  [read #(ozon/step "stock-by-warehouse" (:env (env [(ok {:has_next false :products rows})])) one-listing
                                     {:phase phase :pending ["1" "2"]})]]
         (or (:type (thrown read))
             (mapv (juxt :product-source-id :part :present :reserved) (filter #(= "stock" (:kind %)) (:observations (read))))))))


(def stock-row
  {:sku 1 :product_id 1 :warehouse_id 2 :present 5 :reserved 1})


(deftest stock-row-without-sku
  (doseq [sku [nil "" 0 "0"]]
    (is (= [:incomplete-page :incomplete-page] (warehouse-rows [(assoc stock-row :sku sku)])))))


(deftest stock-row-of-a-sku-not-asked-for
  (is (= [:incomplete-page :incomplete-page] (warehouse-rows [{:sku 3 :product_id 2 :warehouse_id 2 :present 5}]))))


(deftest stock-row-of-an-unresolved-sku
  (is (= :incomplete-page
         (:type (thrown #(ozon/step "stock-by-warehouse" (:env (env [(ok {:has_next false :products [stock-row]})]))
                                    (assoc one-listing :sku->products (constantly {})) {}))))))


(deftest stock-row-of-another-listing
  (is (= [:incomplete-page :incomplete-page] (warehouse-rows [(assoc stock-row :product_id 2)])))
  (is (= [[["1" "1" 5 1]] [["1" "1" 5 1]]] (warehouse-rows [(dissoc stock-row :product_id)])) "the SKU names the listing"))


(deftest sku-of-two-listings
  (let [context {:skus (constantly ["1" "2"]) :sku->products (constantly {"1" #{"1" "2"} "2" #{"1"}})}
        read    (fn [rows] (->> (ozon/step "stock-by-warehouse" (:env (env [(ok {:has_next false :products rows})])) context {})
                                :observations
                                (filter #(= "stock" (:kind %)))
                                (mapv (juxt :product-source-id :part :present))))]
    (is (= [["1" "1" 5] ["2" "1" 6]] (read [stock-row (assoc stock-row :product_id 2 :present 6)]))
        "each row under its own stated product; they do not contradict")
    (is (= [["2" "1" 5]] (read [(assoc stock-row :product_id 2)])))
    (is (= :incomplete-page (:type (thrown #(read [(dissoc stock-row :product_id)])))) "ambiguous")
    (is (= :incomplete-page (:type (thrown #(read [(assoc stock-row :product_id 3)])))) "none of the candidates")
    (is (= [["1" "2" 5]] (read [(-> stock-row (assoc :sku 2) (dissoc :product_id))])) "one candidate resolves")))


(deftest contradicting-stock-rows-in-one-page
  (doseq [other [{:present 6} {:reserved 2} {:reserved nil}]]
    (is (= [:incomplete-page :incomplete-page] (warehouse-rows [stock-row (merge stock-row other)]))))
  (is (= [["1" "1" 5 1]] (distinct (first (warehouse-rows [stock-row stock-row])))) "an identical repeat is no contradiction")
  (is (= [["1" "1" 5 1] ["1" "2" 6 1] ["1" "1" 7 1]]
         (first (warehouse-rows [stock-row (assoc stock-row :sku 2 :present 6) (assoc stock-row :warehouse_id 3 :present 7)])))
      "another SKU or warehouse is another row"))


(deftest fourth-product-is-not-the-stock-cursor
  (let [{:keys [env calls]} (env [(ok {:cursor "c1" :total 5 :items (for [id [1 2 3 4]] {:product_id id :stocks []})})
                                  (ok {:cursor "" :items [{:product_id 5 :stocks [{:type "fbp" :sku 7 :present 2 :reserved 1}]}]})])
        s1                  (ozon/step "stock-totals" env {} {})
        s2                  (ozon/step "stock-totals" env {} (:state s1))]
    (is (= ["" "c1"] (map (comp :cursor :body) @calls)))
    (is (not-any? #(contains? (:body %) :last_id) @calls))
    (is (= [false true] (map :finished? [s1 s2])))
    (is (= [["fbp" 2 1]] (map (juxt :fulfilment-type :present :reserved) (:observations s2)))))
  (is (:finished? (ozon/step "stock-totals" (:env (env [(ok {:items [{:product_id 1 :stocks []}] :last_id "x"})])) {} {}))
      "last_id is no cursor"))


(deftest stock-by-warehouse-reads-fbo-then-fbs
  (let [skus  (mapv str (range 1 1002))
        {:keys [env calls]}
        (env (fn [path {:keys [cursor sku skus]}]
               (ok (cond
                     (= "/v2/product/info/stocks-by-warehouse/fbs" path)
                     (if (= 1 (count sku))
                       {:has_next false :cursor "ignored" :products []}
                       {:has_next false :cursor "" :products [{:product_id   9001 :sku            1   :present 5 :reserved 2 :free_stock 3
                                                               :warehouse_id 3    :warehouse_name "W"}]})
                     (= 1 (count skus)) {:has_next false :products []}
                     (= "" cursor) {:has_next true :cursor "c1" :products [{:product_id 9001 :sku 1 :present 10 :reserved 3 :warehouse_id 77}]}
                     :else {:has_next false :cursor "c1" :products []}))))
        steps (loop [state {} acc []]
                (let [s (ozon/step "stock-by-warehouse" env {:skus (constantly skus) :sku->products (constantly {"1" #{"9001"}})} state)]
                  (if (:finished? s) (conj acc s) (recur (:state s) (conj acc s)))))]
    (is (= [["/v1/product/info/stocks-by-warehouse/fbo" "" 1000] ["/v1/product/info/stocks-by-warehouse/fbo" "c1" 1000]
            ["/v1/product/info/stocks-by-warehouse/fbo" "" 1]
            ["/v2/product/info/stocks-by-warehouse/fbs" "" 1000] ["/v2/product/info/stocks-by-warehouse/fbs" "" 1]]
           (map (fn [{:keys [path body]}] [path (:cursor body) (count (or (:skus body) (:sku body)))]) @calls)))
    (is (= [#{:skus :cursor :limit} #{:sku :cursor :limit}] (distinct (map (comp set keys :body) @calls))))
    (is (every? #(every? string? (or (:skus (:body %)) (:sku (:body %)))) @calls))
    (is (= [{:kind      "stock"                :source  "warehouse-stock" :product-source-id "9001" :part "1"
             :warehouse {:type "fbo" :id "77"} :present 10                :reserved          3}
            {:kind "warehouse" :warehouse-type "fbo" :source-id "77" :name nil}
            {:kind      "stock" :source "warehouse-stock"          :product-source-id "9001" :part "1"
             :warehouse {:type "fbs" :id "3" :name "W"} :present 5 :reserved          2}]
           (mapcat :observations steps))))
  (let [{:keys [env calls]} (env [])]
    (is (:finished? (ozon/step "stock-by-warehouse" env {:skus (constantly [])} {})))
    (is (empty? @calls) "no product, nothing to ask")))


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
      (is (= {:visual_status_change_moment {:time_from "2026-12-09T06:00:00Z" :time_to "2026-12-10T06:00:00Z"}}
             (get-in (last @calls) [:body :filter])) "then the window")
      (is (:finished? listed)))))


(deftest non-terminal-posting-outside-the-window-is-refetched
  (let [{:keys [env calls]} (env (fn [path body]
                                   (case path
                                     "/v3/posting/fbs/get" (ok {:result {:posting_number (:posting_number body) :status "delivering"}})
                                     "/v4/posting/fbs/list" (ok {:postings [] :has_next false}))))
        context             {:window {:from "2026-12-09T06:00:00Z" :to "2026-12-10T06:00:00Z"} :refetch-ids ["123-0001-1"]}
        first-step          (ozon/step "fbs-postings" env context {})]
    (is (= ["123-0001-1"] (map :source-id (:observations first-step))))
    (is (= "/v3/posting/fbs/get" (:path (first @calls))))
    (let [listed (ozon/step "fbs-postings" env context (:state first-step))]
      (is (= {:since "2026-12-09T06:00:00Z" :to "2026-12-10T06:00:00Z"} (get-in (last @calls) [:body :filter])))
      (is (:finished? (ozon/step "fbs-postings" env context (:state listed)))))))
