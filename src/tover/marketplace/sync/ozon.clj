(ns tover.marketplace.sync.ozon
  "Board chapter 41 · Fetch the next source batch: the read-only Ozon client and one traversal per sync domain.

  `request!` is the only way to Ozon. It refuses any path outside `allowlist`, keeps request starts for one
  Client-Id at least 25 ms apart, tries a request at most 4 times (timeouts, 429 and 5xx) with
  0.5 / 1 / 2 s plus up to 0.25 s jitter, honours Retry-After and Item-Retry-After up to 30 s in place and
  turns a longer wait into `:throttled`. So does a 429 that outlasts the tries, for at least 60 s.
  A 401 is `:credentials-rejected`, a 403 `:unavailable`.

  `step` reads one page or detail batch of a domain from an opaque traversal state and returns sanitized
  observations (only the facts named on the board; no buyer data, selling prices or raw payloads), the next state and
  whether the domain is finished. A step makes at most `max-calls` provider calls and ends after one that had to be
  repeated, so it never holds its caller longer than one slow request; what it read before a failure is returned
  with the failure as `:setback`. Failures are ex-info with `:type` one of `:credentials-rejected`
  `:unavailable` `:throttled` `:retries-exhausted` `:repeated-cursor` `:incomplete-page` `:unexpected-response`.

  Response shapes follow docs/ozon-source-contracts.md. Readers accept the documented root or `result` wrapper
  and treat a missing required field as an incomplete page."
  (:require
    [charred.api :as json]
    [clojure.string :as str]
    [tover.marketplace.sync.observations :as observations])
  (:import
    [java.io IOException]
    [java.net.http HttpTimeoutException]
    [java.time Duration Instant LocalDate ZoneOffset ZonedDateTime]
    [java.time.format DateTimeFormatter]))


(def adapter-version
  "Translation version recorded with every observation. A changed reading ships as a new version, under which
  every operation is observed again once."
  "ozon-2")


(def allowlist
  "The read-only calls of the board's Domains table. Anything else is refused before it is sent."
  #{"/v2/warehouse/list" "/v1/warehouse/fbo/seller/list" "/v3/product/list" "/v3/product/info/list"
    "/v1/product/related-sku/get" "/v4/product/info/attributes" "/v1/description-category/attribute"
    "/v5/product/info/prices" "/v4/product/info/stocks" "/v1/product/info/stocks-by-warehouse/fbo"
    "/v2/product/info/stocks-by-warehouse/fbs" "/v1/analytics/stocks" "/v4/posting/fbs/list" "/v3/posting/fbs/get"
    "/v3/posting/fbo/list" "/v2/posting/fbo/get" "/v1/returns/list" "/v2/returns/rfbs/list" "/v2/returns/rfbs/get"
    "/v3/supply-order/list" "/v3/supply-order/get" "/v1/supply-order/bundle" "/v1/supply-order/act/product/get"
    "/v1/removal/from-stock/list" "/v1/removal/from-supply/list"})


(def Env
  "`post` is `connector/http-post` with a 30 s timeout or a test fake; `clock` returns epoch milliseconds."
  [:map [:post [:fn fn?]] [:sleep [:fn fn?]] [:clock [:fn fn?]] [:jitter [:fn fn?]] [:pacer :any]
   [:client-id :string] [:api-key :string]
   ;; The calls made through a `metered` client.
   [:calls {:optional true} :any]])


(def failures
  "What a step may meet at the provider. Any other exception is not one of these and is never a setback."
  #{:credentials-rejected :unavailable :throttled :retries-exhausted :repeated-cursor :incomplete-page :unexpected-response})


(defn- met
  "The ex-data of a provider failure met after progress; any other exception is thrown on."
  {:malli/schema [:=> [:cat :boolean [:fn #(instance? Throwable %)]] :map]}
  [progress? error]
  (if (and progress? (contains? failures (:type (ex-data error))))
    (ex-data error)
    (throw error)))


(defn fail!
  {:malli/schema [:function [:=> [:cat :keyword] :nil] [:=> [:cat :keyword :map] :nil]]}
  ([type] (fail! type {}))
  ([type data] (throw (ex-info (name type) (assoc data :type type)))))


;; Client


(def ^:private try-waits
  [500 1000 2000])


(def ^:private tries
  4)


(def ^:private in-place-limit
  "Longest provider-requested wait spent in place, in milliseconds."
  30000)


(def ^:private throttled-wait
  "Shortest wait after a 429 that outlasted its tries, in milliseconds."
  60000)


(def ^:private spacing
  25)


(def max-calls
  "Most provider calls of one step."
  10)


(defn- metered
  "The client counting its calls, for one step."
  {:malli/schema [:=> [:cat Env] Env]}
  [env]
  (assoc env :calls (atom {:made 0 :repeated? false})))


(defn- spent?
  "Whether the step has to end: its calls are made, or one had to be repeated."
  {:malli/schema [:=> [:cat Env] :boolean]}
  [{:keys [calls]}]
  (let [{:keys [made repeated?]} @calls]
    (or repeated? (<= max-calls made))))


(defn retry-after
  "Milliseconds the provider asks to wait: Retry-After as seconds or an HTTP date, else Item-Retry-After in minutes."
  {:malli/schema [:=> [:cat [:maybe :map] :int] [:maybe :int]]}
  [headers now]
  (let [after (get headers "retry-after")
        item  (get headers "item-retry-after")]
    (cond
      (and after (re-matches #"\s*\d+\s*" after)) (* 1000 (parse-long (str/trim after)))
      after (try (max 0 (- (.toEpochMilli (Instant/from (ZonedDateTime/parse after DateTimeFormatter/RFC_1123_DATE_TIME))) now))
                 (catch Exception _ nil))
      (and item (re-matches #"\s*\d+\s*" item)) (* 60000 (parse-long (str/trim item))))))


(defn- pace!
  "Reserve the next start for this Client-Id and wait for it."
  {:malli/schema [:=> [:cat Env] :nil]}
  [{:keys [pacer clock sleep client-id]}]
  (let [now   (clock)
        start (locking pacer
                (let [start (max now (+ spacing (get @pacer client-id (- now spacing))))]
                  (swap! pacer assoc client-id start)
                  start))]
    (when (> start now) (sleep (- start now))))
  nil)


(defn- parse
  {:malli/schema [:=> [:cat [:maybe :string]] :map]}
  [body]
  (let [value (try (json/read-json (or body "") :bigdec true) (catch Exception _ nil))]
    (if (map? value) value (fail! :incomplete-page))))


(defn- retryable?
  {:malli/schema [:=> [:cat :int] :boolean]}
  [status]
  (or (zero? status) (= 429 status) (<= 500 status 599)))


(defn request!
  "One read with pacing and bounded retries; the parsed body (lossless numbers) with the tries it took."
  {:malli/schema [:=> [:cat Env :string :map] [:map [:body :map] [:tries :int]]]}
  [{:keys [post sleep clock jitter client-id api-key] :as env} path body]
  (when-not (contains? allowlist path)
    (throw (ex-info "Not an allowed read-only call" {:type :forbidden :path path})))
  (loop [attempt 1]
    (pace! env)
    (some-> (:calls env) (swap! #(cond-> (update % :made inc) (< 1 attempt) (assoc :repeated? true))))
    (let [{:keys [status headers] :as response}
          (try (post path body {"Client-Id" client-id "Api-Key" api-key})
               (catch HttpTimeoutException _ {:status 0})
               (catch IOException _ {:status 0}))]
      (cond
        (<= 200 status 299) {:body (parse (:body response)) :tries attempt}
        (= 401 status) (fail! :credentials-rejected)
        (= 403 status) (fail! :unavailable)
        (retryable? status)
        (let [wait     (retry-after headers (clock))
              throttle (fn [wait] (fail! :throttled {:resume-not-before (str (Instant/ofEpochMilli (+ (clock) wait)))}))]
          (cond
            (and wait (> wait in-place-limit)) (throttle wait)
            (and (= 429 status) (>= attempt tries)) (throttle (max (or wait 0) throttled-wait))
            (>= attempt tries) (fail! :retries-exhausted)
            :else (do (sleep (or wait (+ (get try-waits (dec attempt)) (jitter 250))))
                      (recur (inc attempt)))))
        ;; Board decision: any other answer is not retried; the run fails and the position stays.
        :else (fail! :unexpected-response {:status status})))))


(defn env
  "A client environment for one connection from the `:marketplace` resource."
  {:malli/schema [:=> [:cat :map :string :string] Env]}
  [{:keys [post pacer]} client-id api-key]
  {:post   post :pacer (or pacer (atom {})) :client-id client-id :api-key api-key
   :sleep  #(Thread/sleep (long %)) :clock #(System/currentTimeMillis)
   :jitter #(rand-int (inc %))})


;; Normalizing provider values


(defn text
  "Provider scalars as exact text: int64 and decimals without rounding, blank as absent."
  {:malli/schema [:=> [:cat :any] [:maybe :string]]}
  [value]
  (let [s (cond (nil? value) nil
                (decimal? value) (.toPlainString ^BigDecimal value)
                (map? value) nil
                (sequential? value) nil
                :else (str value))]
    (when-not (str/blank? s) s)))


(defn- stated-id
  "An id as exact text; blank and 0 mean not available."
  {:malli/schema [:=> [:cat :any] [:maybe :string]]}
  [value]
  (let [s (text value)] (when-not (= "0" s) s)))


(defn- instant
  {:malli/schema [:=> [:cat :any] [:maybe :string]]}
  [value]
  (when-let [s (text value)]
    (try (str (Instant/parse s)) (catch Exception _ (try (str (.toInstant (ZonedDateTime/parse s))) (catch Exception _ nil))))))


(defn- whole
  {:malli/schema [:=> [:cat :any] [:maybe :int]]}
  [value]
  (cond (integer? value) (long value)
        (decimal? value) (try (.longValueExact ^BigDecimal value) (catch ArithmeticException _ nil))
        :else (some-> (text value) (#(re-matches #"-?\d+" %)) parse-long)))


(defn- result
  "The documented `result` wrapper or the root."
  {:malli/schema [:=> [:cat :map] :any]}
  [body]
  (get body "result" body))


(defn- items
  "The first present list among `keys` in the result or the root; nil when none is present."
  {:malli/schema [:=> [:cat :map [:sequential :string]] [:maybe [:sequential :any]]]}
  [body keys]
  (let [r (result body)]
    (or (when (sequential? r) r)
        (some #(let [v (or (when (map? r) (get r %)) (get body %))] (when (sequential? v) v)) keys))))


(defn- required
  {:malli/schema [:=> [:cat :any] :any]}
  [value]
  (if (nil? value) (fail! :incomplete-page) value))


(defn- identifier
  {:malli/schema [:function [:=> [:cat :string [:maybe :string]] [:maybe :map]]
                  [:=> [:cat :string [:maybe :string] :string] [:maybe :map]]]}
  ([type value] (when value {:type type :value value}))
  ([type value role] (when value {:type type :value value :role role})))


(defn- next-cursor!
  "A next cursor equal to the one just read is a failure, never completion."
  {:malli/schema [:=> [:cat [:maybe :string] [:maybe :string]] [:maybe :string]]}
  [current next]
  (when (and next current (= current next))
    (fail! :repeated-cursor))
  next)


;; Translation


(defn product
  "`/v3/product/info/list` item: identity, name, typed identifiers, category, archived flag."
  {:malli/schema [:=> [:cat :map] :map]}
  [item]
  (let [main (stated-id (get item "sku"))]
    {:kind                "product"
     :source-id           (required (text (get item "id")))
     :name                (text (get item "name"))
     :identifiers         (vec (keep identity
                                     (concat [(identifier "product-id" (text (get item "id")))
                                              (identifier "article" (text (get item "offer_id")))
                                              (identifier "sku" main "primary")]
                                             (for [source (get item "sources")
                                                   :let   [s (stated-id (get source "sku"))]]
                                       ;; A source SKU other than the current one was observed earlier.
                                               (identifier "sku" s (if (= s main) "source" "historical")))
                                             (for [barcode (concat (get item "barcodes") [(get item "barcode")])]
                                               (identifier "barcode" (text barcode))))))
     :category            (some->> [(text (get item "description_category_id")) (text (get item "type_id"))]
                                   (filter some?) seq (str/join "/"))
     :archived            (boolean (or (get item "is_archived") (get item "is_autoarchived") (get item "archived")))
     :provider-updated-at (instant (get item "updated_at"))}))


(defn attributes
  "`/v4/product/info/attributes` item: attributes and complex groups as ids and text, values in order."
  {:malli/schema [:=> [:cat :map] :map]}
  [item]
  (letfn [(values [attribute]
            (vec (for [v (get attribute "values")]
                   (cond-> {:value (or (text (get v "value")) "")}
                     (text (get v "dictionary_value_id")) (assoc :dictionary-id (text (get v "dictionary_value_id")))
                     (= "0" (text (get v "dictionary_value_id"))) (dissoc :dictionary-id)))))]
    {:kind       "product-attributes"
     :source-id  (required (text (get item "id")))
     :attributes (vec (concat
                        (for [a (get item "attributes")]
                          {:attribute-id (text (get a "id")) :values (values a)})
                        (for [[i group] (map-indexed vector (get item "complex_attributes"))
                              a         (get group "attributes")]
                          {:attribute-id (text (get a "id")) :group-id (or (text (get group "complex_id")) "complex")
                           :group-index  i                   :values   (values a)})))}))


(def ^:private value-types
  {"string" "string" "integer" "integer" "decimal" "decimal" "boolean" "boolean" "date" "date" "datetime" "date"})


(defn attribute-definition
  "`/v1/description-category/attribute` row. Value type is the declared one, never guessed from values."
  {:malli/schema [:=> [:cat :string :string :map] :map]}
  [category-id type-id row]
  (let [dictionary (text (get row "dictionary_id"))
        dictionary (when-not (= "0" dictionary) dictionary)]
    (cond-> {:kind               "attribute-definition" :category-id category-id :type-id type-id
             :attribute-id       (required (text (get row "id")))
             :name               (or (text (get row "name")) "")
             :value-type         (cond dictionary "dictionary"
                                       :else (get value-types (some-> (text (get row "type")) str/lower-case) "unknown"))
             :multiple           (boolean (get row "is_collection"))
             :complex            (boolean (or (get row "attribute_complex_id") (some-> (whole (get row "group_id")) pos?)))
             :separates-variants (true? (get row "is_aspect"))}
      dictionary (assoc :dictionary-id dictionary))))


(defn cost
  "The cost the seller entered at Ozon, as exact decimal text; nil when not entered (blank or 0)."
  {:malli/schema [:=> [:cat :map] [:maybe [:map [:cost :string] [:cost-currency {:optional true} :string]]]]}
  [price]
  (let [entered  (some-> (text (get price "net_price"))
                         (as-> s (try (bigdec s) (catch NumberFormatException _ (fail! :incomplete-page)))))
        currency (text (get price "currency_code"))]
    (when (and entered (pos? entered))
      (cond-> {:cost (text entered)} currency (assoc :cost-currency currency)))))


(defn product-cost
  "`/v5/product/info/prices` item: the entered cost only. Without one the observation says so."
  {:malli/schema [:=> [:cat :map] :map]}
  [item]
  (merge {:kind "product-cost" :source-id (required (stated-id (get item "product_id")))}
         (cost (required (get item "price")))))


(defn- decimal
  "An exact decimal; nil when blank or not one."
  {:malli/schema [:=> [:cat :any] [:maybe decimal?]]}
  [value]
  (when-let [s (text value)]
    (try (bigdec s) (catch NumberFormatException _ nil))))


(defn- price
  "The price stated by `m`, a scalar beside its currency or one map of both; only with the currency stated there."
  {:malli/schema [:=> [:cat [:maybe :map]] [:map [:price {:optional true} :string] [:price-currency {:optional true} :string]]]}
  [m]
  (let [stated   (get m "price")
        source   (if (map? stated) stated m)
        amount   (text (decimal (get source "price")))
        currency (text (get source "currency_code"))]
    (cond-> {}
      amount (assoc :price amount)
      (and amount currency) (assoc :price-currency currency))))


(defn- fulfilment
  {:malli/schema [:=> [:cat :any] [:maybe :string]]}
  [value]
  (#{"fbo" "fbs" "rfbs" "fbp"} (some-> (text value) str/lower-case)))


(defn warehouse-observation
  "One external warehouse; nil when the id is absent."
  {:malli/schema [:=> [:cat :string :any :any] [:maybe observations/Observation]]}
  [type id name]
  (when-let [id (stated-id id)]
    {:kind "warehouse" :warehouse-type type :source-id id :name (text name)}))


(defn- named-warehouse
  "The warehouse a fact names; nil when the id is absent."
  {:malli/schema [:=> [:cat :string :any :any] [:maybe observations/Warehouse]]}
  [type id name]
  (when-let [id (stated-id id)]
    (cond-> {:type type :id id} (text name) (assoc :name (text name)))))


(defn- with-fbo-warehouses
  "Observations followed by the FBO warehouses `named` by them, each once. No list states these."
  {:malli/schema [:=> [:cat [:sequential :map] [:sequential observations/Warehouse]] [:vector :map]]}
  [observed named]
  (into (vec observed)
        (comp (filter #(= "fbo" (:type %))) (map #(warehouse-observation "fbo" (:id %) (:name %))) (distinct))
        named))


(defn- with-named-fbo
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector :map]]}
  [operations]
  (with-fbo-warehouses operations (mapcat observations/warehouses operations)))


(defn- listed-warehouse
  "A row of one of the seller's warehouse lists; without an id the page is incomplete."
  {:malli/schema [:=> [:cat :string :any :any] :map]}
  [type id name]
  (required (warehouse-observation type id name)))


(defn- part
  "Which of a listing's rows in one place a figure comes from: its SKU, and what else tells such rows apart."
  {:malli/schema [:=> [:cat [:* :any]] :string]}
  [& values]
  (str/join "/" (keep text (flatten values))))


(defn stock-totals
  "`/v4/product/info/stocks` item. Missing `stocks` is incomplete; `stocks: []` states nothing to record."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [item]
  (let [stocks (required (get item "stocks"))]
    (vec (for [s     stocks
               :let  [type (fulfilment (get s "type"))]
               ;; ponytail: types outside fbo | fbs | rfbs | fbp (e.g. crossborder) are skipped until the board names them.
               :when type]
           (cond-> {:kind            "stock" :source "fulfilment-totals"              :product-source-id (required (text (get item "product_id")))
                    :fulfilment-type type :present   (or (whole (get s "present")) 0)
                    :part            (part (get s "sku") (get s "shipment_type") (get s "warehouse_ids"))}
             (whole (get s "reserved")) (assoc :reserved (whole (get s "reserved"))))))))


(defn- analytics-warehouse
  {:malli/schema [:=> [:cat :map] [:maybe observations/Warehouse]]}
  [row]
  (named-warehouse "fbo" (get row "warehouse_id") (get row "warehouse_name")))


(defn stock-analytics
  "`/v1/analytics/stocks` row, resolved to a listing by SKU within the connection; nil when unresolved or when
  it names no warehouse."
  {:malli/schema [:=> [:cat :map [:map-of :string :string]] [:maybe :map]]}
  [row sku->product]
  (let [product   (get sku->product (stated-id (get row "sku")))
        warehouse (analytics-warehouse row)]
    (when (and product warehouse)
      {:kind      "stock" :source "warehouse-analytics"  :product-source-id product
       :warehouse warehouse :part (part (get row "sku"))
       :present   (required (whole (get row "available_stock_count")))})))


(defn warehouse-stock
  "A stocks-by-warehouse row of the `type` call, \"fbo\" or \"fbs\", for an `asked` SKU (a set), resolved to its
  listing. The stated product picks among the listings the SKU is active on; without one the SKU has to name a
  single listing. `present` is the quantity, reserved units included. A row that cannot be attributed leaves the
  page incomplete: without it an asked listing would read as 0."
  {:malli/schema [:=> [:cat :string [:set :string] [:map-of :string [:set :string]] :map] :map]}
  [type asked sku->products row]
  (let [sku        (required (asked (stated-id (get row "sku"))))
        candidates (get sku->products sku #{})
        stated     (stated-id (get row "product_id"))
        product    (required (if stated (candidates stated) (when (= 1 (count candidates)) (first candidates))))
        reserved   (whole (get row "reserved"))]
    (cond-> {:kind      "stock" :source "warehouse-stock" :product-source-id product :part sku
             :warehouse (required (named-warehouse type (get row "warehouse_id") (get row "warehouse_name")))
             :present   (required (whole (get row "present")))}
      reserved (assoc :reserved reserved))))


(defn- agreeing
  "The figures of a page, unless it states one SKU of a listing in one warehouse twice with different quantities."
  {:malli/schema [:=> [:cat [:vector :map]] [:vector :map]]}
  [figures]
  (if (apply distinct? nil (map (juxt :product-source-id :part (comp :id :warehouse))
                                (distinct (map #(update % :warehouse dissoc :name) figures))))
    figures
    (fail! :incomplete-page)))


(def ^:private terminal-statuses
  #{"delivered" "cancelled" "not_accepted" "returned" "receivedbyseller" "utilized" "completed" "rejected"
    "cancelled_by_seller" "moneyreturned" "returnedtoozon"})


(defn- terminal?
  {:malli/schema [:=> [:cat [:maybe :string]] :boolean]}
  [status]
  (contains? terminal-statuses (some-> status str/lower-case)))


(defn- lines
  "Every product line, never collapsed; lines with the same identifiers are flagged."
  {:malli/schema [:=> [:cat [:maybe [:sequential :map]] [:maybe observations/Warehouse]] [:vector :map]]}
  [products warehouse]
  (let [rows   (vec (for [[i p] (map-indexed vector products)]
                      (cond-> {:line-key    (str (inc i))
                               :identifiers (vec (keep identity [(identifier "sku" (stated-id (get p "sku")))
                                                                 (identifier "article" (text (get p "offer_id")))
                                                                 (identifier "product-id" (text (get p "product_id")))]))}
                        (text (get p "quantity")) (assoc :quantity (text (get p "quantity")))
                        true (merge (price p))
                        warehouse (assoc :warehouse warehouse))))
        counts (frequencies (map :identifiers rows))]
    (mapv #(cond-> % (< 1 (counts (:identifiers %))) (assoc :duplicate true)) rows)))


(defn- operation
  "Common completeness: the actual time, every line with a quantity and the warehouse. A supply lacks it without
  the storage warehouse it goes to."
  {:malli/schema [:function [:=> [:cat :map] :map] [:=> [:cat :map [:vector :string]] :map]]}
  ([op] (operation op []))
  ([{:keys [operation-kind occurred-at lines from-warehouse to-warehouse] :as op} unproven]
   (let [placed? (if (= "supply" operation-kind)
                   to-warehouse
                   (or from-warehouse to-warehouse (and (seq lines) (every? :warehouse lines))))
         missing (vec (concat (when-not occurred-at ["actual time"])
                              (when (or (empty? lines) (some #(nil? (:quantity %)) lines)) ["line quantity"])
                              (when-not placed?
                                [(if (#{"return" "rfbs-return"} operation-kind) "receiving warehouse" "warehouse")])
                              unproven))]
     (cond-> (assoc op :kind "operation" :terminal (terminal? (:status op)) :complete (empty? missing))
       (seq missing) (assoc :missing missing)))))


(def ^:private after-handover
  #{"delivering" "driver_pickup" "delivered" "client_arbitration" "arbitration" "not_accepted" "sent_by_seller"})


(defn handed-over?
  "Whether the posting was handed over: its status is after handover or, when cancelled, Ozon says so: by flag for
  FBS, by the handover time for FBO, which states no flag."
  {:malli/schema [:=> [:cat observations/OperationKind :map] :boolean]}
  [kind posting]
  (let [status (text (get posting "status"))]
    (cond (contains? after-handover (some-> status str/lower-case)) true
          (not (observations/cancelled? status)) false
          (= "fbo-posting" kind) (some? (instant (get posting "fact_delivery_date")))
          :else (true? (get-in posting ["cancellation" "cancelled_after_ship"])))))


(defn- priced-products
  "The posting's products, each with the price and currency of its financial row (found by SKU, in order) when
  that states one."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [posting]
  (let [financial (group-by #(stated-id (get % "product_id")) (get-in posting ["financial_data" "products"]))]
    (first (reduce (fn [[products left] product]
                     (let [sku          (stated-id (get product "sku"))
                           [row & more] (when sku (left sku))]
                       [(conj products (cond-> product (:price (price row)) (merge {"price" nil "currency_code" nil} (select-keys row ["price" "currency_code"]))))
                        (assoc left sku more)]))
                   [[] financial] (get posting "products")))))


(defn- posting
  "A posting whose handover time is the first stated of `times`, and only after handover."
  {:malli/schema [:=> [:cat observations/OperationKind :map [:vector :string] [:maybe observations/Warehouse]] :map]}
  [kind source times warehouse]
  (let [at      (when (handed-over? kind source) (some #(instant (get source %)) times))
        planned (instant (get source "shipment_date"))]
    (operation (cond-> {:operation-kind kind :source-id (required (text (get source "posting_number")))
                        :status         (or (text (get source "status")) "unknown")
                        :lines          (lines (priced-products source) nil)}
                 at (assoc :occurred-at at)
                 planned (assoc :planned-at planned)
                 warehouse (assoc :from-warehouse warehouse)))))


(defn fbs-posting
  "`/v3/posting/fbs/get` result. `fact_delivery_date`, else `delivering_date`, is the handover time;
  `shipment_date` is only planned."
  {:malli/schema [:=> [:cat :map] :map]}
  [source]
  (posting "fbs-posting" source ["fact_delivery_date" "delivering_date"]
           (named-warehouse "fbs" (get-in source ["delivery_method" "warehouse_id"]) nil)))


(defn fbo-posting
  "`/v2/posting/fbo/get` result. `fact_delivery_date` is the handover time; the list does not state it."
  {:malli/schema [:=> [:cat :map] :map]}
  [source]
  (let [{:strs [warehouse_id warehouse_name]} (get source "analytics_data")]
    (posting "fbo-posting" source ["fact_delivery_date"] (named-warehouse "fbo" warehouse_id warehouse_name))))


(defn- compensated?
  "Whether a compensation is in force; a cancelled or reversed one is not."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [row]
  (contains? #{"Sent" "Received"} (get-in row ["compensation_status" "status" "sys_name"])))


(defn- return-schema
  {:malli/schema [:=> [:cat :map] [:maybe :string]]}
  [row]
  (some-> (text (get row "schema")) str/lower-case))


(defn fbo-fbs-return
  "`/v1/returns/list` row. `final_moment` is the receipt, by Ozon or the seller as the status says. The place
  (pickup point, returns centre or blank) is never a warehouse, so the receiving warehouse is always missing."
  {:malli/schema [:=> [:cat :map] :map]}
  [row]
  (let [moment  (instant (get-in row ["logistic" "final_moment"]))
        posting (text (get row "posting_number"))
        schema  (#{"fbo" "fbs"} (return-schema row))]
    (operation (cond-> {:operation-kind "return" :source-id (required (text (get row "id")))
                        :status         (or (text (get-in row ["visual" "status" "sys_name"])) "unknown")
                        :compensated    (compensated? row)
                        :lines          (lines [(get row "product")] nil)}
                 moment (assoc :occurred-at moment)
                 schema (assoc :return-schema schema)
                 posting (assoc :refetch-key posting)
                 (and schema posting) (assoc :related-operation (str schema "-posting:" posting))))))


(defn rfbs-return
  "`/v2/returns/rfbs/get` return: receipt time and quantity are not stated and its warehouse is not proven as
  receiving."
  {:malli/schema [:=> [:cat :map] :map]}
  [ret]
  (let [posting (text (get ret "posting_number"))]
    (operation (cond-> {:operation-kind "rfbs-return" :source-id (required (text (or (get ret "return_id") (get ret "id"))))
                        :status         (or (text (get-in ret ["state" "state"])) (text (get ret "state")) "unknown")
                        :lines          (mapv #(dissoc % :quantity) (lines [(get ret "product")] nil))}
                 posting (assoc :related-operation (str "fbs-posting:" posting))))))


(def ^:private act-quantities
  {"declared_quantity" :declared-quantity "fact_quantity" :fact-quantity "approved_quantity" :approved-quantity})


(defn- accepted
  "A supply line with the quantities of its SKU's rows in acceptance acts, `acts`, each summed over them when all state it. The
  line quantity is the approved sum, only when every act's fact and approved quantities agree."
  {:malli/schema [:=> [:cat :map [:sequential :map]] :map]}
  [line acts]
  (let [sums   (into {} (for [[field k] act-quantities
                              :let      [stated (map #(decimal (get % field)) acts)]
                              :when     (and (seq stated) (every? some? stated))]
                          [k (text (reduce + stated))]))
        ids    (distinct (keep #(text (get % "act_id")) acts))
        agree? (fn [act]
                 (let [fact     (decimal (get act "fact_quantity"))
                       approved (decimal (get act "approved_quantity"))]
                   (and fact approved (== fact approved))))]
    (cond-> (merge (dissoc line :quantity) sums)
      (seq ids) (assoc :act-id (str/join "," ids))
      (and (seq acts) (every? agree? acts)) (assoc :quantity (:approved-quantity sums)))))


(defn- acceptance-rows
  "Items of the ACCEPTANCE acts, each with its act's id. Other acts state no accepted quantity."
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector :map]]}
  [acts]
  (vec (for [act   acts
             :when (= "ACCEPTANCE" (get act "type"))
             item  (required (get act "items"))]
         (merge item (select-keys (get item "sku_info") ["sku" "offer_id"]) (select-keys act ["act_id"])))))


(defn supply
  "One supply of a `/v3/supply-order/get` order with its bundle items and its acts
  (`/v1/supply-order/act/product/get`). The receipt time is not stated. An approved sum goes to the one bundle line
  of its SKU, to none when several list it; a SKU the bundle does not list adds a line."
  {:malli/schema [:=> [:cat :map :map [:sequential :map] [:sequential :map]] :map]}
  [order {:strs [supply_id state storage_warehouse]} bundle-items acts]
  (let [from     (named-warehouse "supply-origin" (get-in order ["order_tags" "seller_warehouse_id"]) nil)
        to       (named-warehouse "fbo" (get storage_warehouse "warehouse_id") (get storage_warehouse "name"))
        sku      #(stated-id (get % "sku"))
        accepts  (acceptance-rows acts)
        by-sku   (dissoc (group-by sku accepts) nil)
        listed   (frequencies (keep sku bundle-items))
        extra    (map #(select-keys % ["sku" "offer_id"])
                      (concat (map #(first (by-sku %)) (distinct (remove listed (keep sku accepts))))
                              (filter (comp nil? sku) accepts)))
        stated   (concat bundle-items extra)
        rows     (mapv (fn [item line]
                         (accepted line (if (< 1 (listed (sku item) 0)) [] (get by-sku (sku item) []))))
                       stated
                       (lines stated to))
        order-id (text (or (get order "order_id") (get order "id")))]
    (operation (cond-> {:operation-kind "supply" :source-id (required (stated-id supply_id))
                        :status         (or (text state) (text (get order "state")) "unknown")
                        :lines          rows}
                 ;; Read again by its order; kept per supply, as supplies of one order are recorded apart.
                 order-id (assoc :refetch-key (str order-id "/" (text supply_id)))
                 from (assoc :from-warehouse from)
                 to (assoc :to-warehouse to))
               (if (and (seq rows) (every? :quantity rows)) [] ["accepted quantity"]))))


(defn removal
  "Removal report row: the requested quantity left only with a box that was given out or disposed. Warehouses are
  not stated as ids."
  {:malli/schema [:=> [:cat :map :string] :map]}
  [row source]
  (let [id       (str/join "/" (keep text [source (get row "return_id") (get row "box_id") (get row "sku")]))
        given    (text (get row "given_out_date"))
        disposed (when-not given (text (get row "utilization_date")))
        done     (or given disposed)
        at       (instant done)]
    (operation (cond-> {:operation-kind "removal" :source-id (if (str/blank? id) (fail! :incomplete-page) id)
                        :status         (if done "completed" (or (text (get row "return_state")) (text (get row "state")) "requested"))
                        :disposed       (some? disposed)
                        :lines          (lines [(cond-> {"sku" (get row "sku")} done (assoc "quantity" (get row "quantity_for_return")))] nil)}
                 at (assoc :occurred-at at)))))


;; Traversal: one page or detail batch per step


(def Context
  "What a step may know besides its state: the dated window, ids to refetch and lookups over observed records.
  `settled-before`: orders whose state last changed earlier were read by a finished pass. `reread-from`: where open
  removals are read again from."
  [:map
   [:settled-before {:optional true} :string]
   [:reread-from {:optional true} :string]
   ;; () -> the set of order ids of the stored supplies; (refetch ids) -> the last stated facts of those supplies.
   [:known-orders {:optional true} [:fn fn?]]
   [:stored-supplies {:optional true} [:fn fn?]]
   [:window {:optional true} [:map [:from :string] [:to :string]]]
   [:refetch-ids {:optional true} [:sequential :string]]
   [:skus {:optional true} [:fn fn?]]
   [:categories {:optional true} [:fn fn?]]
   [:sku->product {:optional true} [:fn fn?]]
   [:sku->products {:optional true} [:fn fn?]]])


(def Step
  "`setback`: the ex-data of a failure met after the observations were read; `state` continues at what failed.
  `pending-ids`: refetch ids of records listed beside the observed ones and not read yet."
  [:map [:observations [:vector :map]] [:state :map] [:finished? :boolean] [:setback {:optional true} :map]
   [:pending-ids {:optional true} [:vector :string]]])


(defn- page
  {:malli/schema [:=> [:cat [:vector :map] :map :boolean] Step]}
  [observations state finished?]
  {:observations observations :state state :finished? finished?})


(defn- details
  "Fetch pending ids `n` at a time with `fetch` (ids -> observations); then continue with `then`."
  {:malli/schema [:=> [:cat :map :int [:fn fn?] [:fn fn?]] Step]}
  [{:keys [pending] :as state} n fetch then]
  (if (seq pending)
    (let [[batch more] (split-at n pending)]
      (page (vec (fetch (vec batch))) (assoc state :pending (vec more)) false))
    (then state)))


(defn- walk
  "Read `items` one by one with `(read env item)` until the step is spent. A failure after the first item ends the
  walk; what was read before it is kept."
  {:malli/schema [:=> [:cat Env [:sequential :any] [:fn fn?]]
                  [:map [:observed [:vector :map]] [:left [:vector :any]] [:setback {:optional true} :map]]]}
  [env items read]
  (let [env (metered env)]
    (loop [left (vec items) observed []]
      (let [first? (= (count left) (count items))]
        (if (or (empty? left) (and (not first?) (spent? env)))
          {:observed observed :left left}
          (let [{:keys [found setback]} (try {:found (vec (read env (first left)))}
                                             (catch clojure.lang.ExceptionInfo error
                                               {:setback (met (not first?) error)}))]
            (if setback
              {:observed observed :left left :setback setback}
              (recur (subvec left 1) (into observed found)))))))))


(defmulti step
  "Read the next page or detail batch of `domain` from `state` ({} at the start of a run)."
  {:arglists '([domain env context state])}
  (fn [domain _ _ _] domain))


(defn- seller-warehouses
  {:malli/schema [:=> [:cat :map :map] Step]}
  [env {:keys [cursor phase] :as state}]
  (if (= :supply-origin phase)
    (let [{:keys [body]} (request! env "/v1/warehouse/fbo/seller/list" {})
          rows           (required (items body ["warehouses" "result"]))]
      (page (mapv #(listed-warehouse "supply-origin" (get % "seller_warehouse_id") (get % "seller_warehouse_name")) rows) state true))
    (let [{:keys [body]} (request! env "/v2/warehouse/list" (cond-> {:limit 200} cursor (assoc :cursor cursor)))
          rows           (required (items body ["warehouses"]))
          next           (next-cursor! cursor (text (get (result body) "cursor")))
          more?          (and (get (result body) "has_next") next (seq rows))]
      (page (mapv #(listed-warehouse (if (get % "is_rfbs") "rfbs" "fbs") (get % "warehouse_id") (get % "name")) rows)
            (if more? {:cursor next} {:phase :supply-origin}) false))))


(defmethod step "warehouses" [_ env _ state] (seller-warehouses env state))


(defn- by-last-id
  "A `last_id` list page: ids of the page go to `pending`; an empty page or blank cursor ends the list."
  {:malli/schema [:=> [:cat :map :string :map [:fn fn?] :map] Step]}
  [env path body ids {:keys [last-id] :as state}]
  (let [{response :body} (request! env path (assoc body :last_id (or last-id "")))
        rows             (required (items response ["items"]))
        next             (next-cursor! last-id (text (get (result response) "last_id")))]
    (page [] (assoc state :pending (vec (keep ids rows)) :last-id next :done? (or (empty? rows) (nil? next))) false)))


(defmethod step "products" [_ env _ state]
  (details state 1000
           (fn [ids]
             (let [{:keys [body]} (request! env "/v3/product/info/list" {:product_id ids})
                   rows           (required (items body ["items"]))
                   found          (set (keep #(text (get % "id")) rows))]
               (when-not (every? found ids) (fail! :incomplete-page))
               (map product rows)))
           (fn [state]
             (if (:done? state)
               (page [] state true)
               (by-last-id env "/v3/product/list" {:filter {:visibility "ALL"} :limit 1000}
                           #(text (get % "product_id")) state)))))


(defmethod step "sku-aliases" [_ env {:keys [skus]} state]
  (let [state (if (:started? state) state {:started? true :pending (vec (distinct (skus)))})]
    (details state 200
             (fn [batch]
               (let [{:keys [body]} (request! env "/v1/product/related-sku/get" {:sku batch})]
                 ;; Per-SKU errors stay unresolved; only returned relations are evidence.
                 (for [[product rows] (group-by #(text (get % "product_id")) (or (items body ["items"]) []))
                       :when          product]
                   {:kind    "product-aliases" :source-id product
                    :aliases (vec (keep #(identifier "sku" (stated-id (get % "sku"))
                                                     (if (text (get % "deleted_at")) "historical" "source"))
                                        rows))})))
             #(page [] % true))))


(def ^:private attribute-limit
  1000)


(defmethod step "attributes" [_ env _ {:keys [last-id seen] :or {seen 0}}]
  ;; The last page states a `last_id` too, and Ozon answers 404 when asked with it: `total` ends the list.
  (let [{:keys [body]} (request! env "/v4/product/info/attributes"
                                 {:filter {:visibility "ALL"} :limit attribute-limit :last_id (or last-id "")})
        rows           (required (items body ["result" "items"]))
        seen           (+ seen (count rows))
        total          (whole (get body "total"))
        next           (text (get body "last_id"))
        last?          (or (empty? rows) (nil? next) (if total (>= seen total) (< (count rows) attribute-limit)))]
    (page (mapv attributes rows)
          {:last-id (if last? next (next-cursor! last-id next)) :seen seen}
          last?)))


(defmethod step "attribute-definitions" [_ env {:keys [categories]} state]
  (let [state (if (:started? state) state {:started? true :pending (vec (distinct (categories)))})]
    (details state 1
             (fn [[[category-id type-id]]]
               (let [{:keys [body]} (request! env "/v1/description-category/attribute"
                                              {:description_category_id category-id :type_id type-id :language "DEFAULT"})]
                 (map #(attribute-definition category-id type-id %) (required (items body ["attributes"])))))
             #(page [] % true))))


(defn- by-cursor
  "A `cursor` list page of all products. There is no `has_next`: an empty page or a blank cursor ends the list."
  {:malli/schema [:=> [:cat Env :string [:fn fn?] :map] Step]}
  [env path translate {:keys [cursor]}]
  (let [{:keys [body]} (request! env path {:filter {:visibility "ALL"} :limit 1000 :cursor (or cursor "")})
        rows           (required (items body ["items"]))
        next           (when (seq rows) (next-cursor! cursor (text (get (result body) "cursor"))))]
    (page (vec (mapcat translate rows)) {:cursor next} (nil? next))))


(defmethod step "prices" [_ env _ state]
  (by-cursor env "/v5/product/info/prices" (comp vector product-cost) state))


(defmethod step "stock-totals" [_ env _ state]
  (by-cursor env "/v4/product/info/stocks" stock-totals state))


(def ^:private warehouse-stock-calls
  "The call of each phase, its name for the SKU list, the type of the warehouses it names and the phase after it."
  {:fbo {:path "/v1/product/info/stocks-by-warehouse/fbo" :sku-key :skus :type "fbo" :then :fbs}
   :fbs {:path "/v2/product/info/stocks-by-warehouse/fbs" :sku-key :sku :type "fbs"}})


(defn- further
  "The next cursor of a `has_next` page, nil on the last one. A page that does not say is the last one only
  without a cursor."
  {:malli/schema [:=> [:cat [:maybe :string] :map] [:maybe :string]]}
  [current {:strs [has_next cursor]}]
  (let [next (text cursor)]
    (when (or has_next (and (nil? has_next) next))
      (when (nil? has_next) (fail! :incomplete-page))
      (next-cursor! current (required next)))))


(defmethod step "stock-by-warehouse" [_ env {:keys [skus sku->products]} {:keys [phase pending cursor]}]
  (let [phase (or phase :fbo)
        pending (or pending (vec (distinct (skus))))
        {:keys [path sku-key type then]} (warehouse-stock-calls phase)
        [batch more] (split-at 1000 pending)]
    (if (empty? batch)
      (page [] {} true)
      (let [{:keys [body]} (request! env path {sku-key (vec batch) :limit 1000 :cursor (or cursor "")})
            asked          (set batch)
            lookup         (sku->products)
            rows           (agreeing (mapv #(warehouse-stock type asked lookup %) (required (items body ["products"]))))
            next           (further cursor (result body))]
        (page (with-fbo-warehouses rows (map :warehouse rows))
              (cond next {:phase phase :pending (vec pending) :cursor next}
                    (seq more) {:phase phase :pending (vec more)}
                    :else {:phase then})
              (not (or next (seq more) then)))))))


(defmethod step "stock-analytics" [_ env {:keys [skus sku->product]} state]
  (let [state (if (:started? state) state {:started? true :pending (vec (distinct (skus)))})]
    (details state 100
             (fn [batch]
               (let [{:keys [body]} (request! env "/v1/analytics/stocks" {:skus batch})
                     lookup         (sku->product)
                     rows           (required (items body ["items"]))]
                 (with-fbo-warehouses (keep #(stock-analytics % lookup) rows) (keep analytics-warehouse rows))))
             #(page [] % true))))


(defn- dated
  "Refetch known non-terminal records first, then list the window page by page and detail each page.
  `list` returns {:ids :next :more?} for a cursor, nil at the start; `(detail env id)` gives an id's observations."
  {:malli/schema [:=> [:cat Env :map :map [:fn fn?] [:fn fn?]] Step]}
  [env {:keys [refetch-ids]} state list detail]
  (let [{:keys [pending listed? at] :as state} (if (:started? state) state {:started? true :pending (vec refetch-ids)})]
    (cond
      (seq pending)
      (let [{:keys [observed left setback]} (walk env pending detail)]
        (cond-> (page observed (assoc state :pending left) false) setback (assoc :setback setback)))

      listed? (page [] state true)
      :else (let [{:keys [ids next more?]} (list at)]
              (page [] (assoc state :pending (vec ids) :at next :listed? (not more?)) false)))))


(defn- window
  {:malli/schema [:=> [:cat :map] [:map [:from :string] [:to :string]]]}
  [context]
  (or (:window context) (fail! :incomplete-page)))


(def ^:private posting-details
  {:analytics_data true :financial_data true})


(def ^:private posting-age
  "The longest `since` / `to` span the FBS list takes is about a year."
  (Duration/ofDays 364))


(defn- postings-page
  "One posting list page under `filter`, oldest first."
  {:malli/schema [:=> [:cat Env :string :map [:maybe :string]] [:map [:ids [:sequential :string]] [:next [:maybe :string]] [:more? :boolean]]]}
  [env path filter cursor]
  (let [{:keys [body]} (request! env path {:filter filter :limit 100 :cursor (or cursor "") :sort_dir "ASC"})
        rows           (required (get body "postings"))
        next           (further cursor body)]
    {:ids (keep #(text (get % "posting_number")) rows) :next next :more? (some? next)}))


(defmethod step "fbs-postings" [_ env context state]
  ;; Postings changed in the window. `since` / `to` are required and bound the creation: a posting created over 364
  ;; days before the window ends is not listed.
  (let [{:keys [from to]} (window context)
        filter            {:since (str (.minus (Instant/parse to) posting-age)) :to to :last_changed_status_date {:from from :to to}}]
    (dated env context state
           #(postings-page env "/v4/posting/fbs/list" filter %)
           (fn [env n]
             [(fbs-posting (result (:body (request! env "/v3/posting/fbs/get" {:posting_number n :with posting-details}))))]))))


(defmethod step "fbo-postings" [_ env context state]
  (let [{:keys [from to]} (window context)]
    (update (dated env context state
                   #(postings-page env "/v3/posting/fbo/list" {:since from :to to} %)
                   (fn [env n]
                     [(fbo-posting (result (:body (request! env "/v2/posting/fbo/get" {:posting_number n :with posting-details}))))]))
            :observations with-named-fbo)))


(defn- returns-page
  "One `/v1/returns/list` page under `filter`: the rows, the next cursor and whether more follow."
  {:malli/schema [:=> [:cat Env :map [:maybe :string]] [:map [:rows [:vector :map]] [:next [:maybe :string]] [:more? :any]]]}
  [env filter last-id]
  (let [{:keys [body]} (request! env "/v1/returns/list"
                                 {:filter filter :limit 500 :last_id (if last-id (BigInteger. ^String last-id) 0)})
        rows           (vec (required (items body ["returns"])))
        next           (some-> (last rows) (get "id") text)]
    (next-cursor! last-id next)
    {:rows rows :next next :more? (and (seq rows) (get body "has_next"))}))


(defn- returns
  "FBP operations are not read."
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector :map]]}
  [rows]
  (into [] (comp (remove #(= "fbp" (return-schema %))) (map fbo-fbs-return)) rows))


(defmethod step "returns" [_ env {:keys [refetch-ids] :as context} state]
  ;; The list has no detail call: known non-terminal returns are read again by posting number (their
  ;; refetch key), 50 postings per request, before the window is listed.
  ;; ponytail: `posting_numbers` filter per the Ozon reference; confirm with a live check.
  (let [{:keys [from to]}         (window context)
        state (if (seq state) state {:pending (vec refetch-ids)})
        {:keys [pending last-id]} state]
    (if (seq pending)
      (let [[batch more]              (split-at 50 pending)
            {:keys [rows next more?]} (returns-page env {:posting_numbers (vec batch)} last-id)]
        (page (returns rows)
              (if more? (assoc state :last-id next) (assoc state :pending (vec more) :last-id nil))
              false))
      (let [{:keys [rows next more?]} (returns-page env {:visual_status_change_moment {:time_from from :time_to to}} last-id)]
        (page (returns rows) (assoc state :last-id next) (not more?))))))


(defmethod step "rfbs-returns" [_ env context state]
  (let [{:keys [from to]} (window context)]
    (dated env context state
           (fn [last-id]
             (let [{:keys [body]} (request! env "/v2/returns/rfbs/list"
                                            {:filter {:created_at {:from from :to to}} :limit 500 :last_id (if last-id (BigInteger. ^String last-id) 0)})
                   rows           (required (items body ["returns"]))
                   next           (some-> (last rows) (get "return_id") text)]
               (next-cursor! last-id next)
               {:ids (keep #(text (get % "return_id")) rows) :next next :more? (boolean (seq rows))}))
           (fn [env id]
             [(rfbs-return (required (get (:body (request! env "/v2/returns/rfbs/get" {:return_id id})) "returns")))]))))


(defn- read-bundle
  "Read on in a supply's bundle, whose part read so far is `bundle` (nil at the start), until it ends or the step is
  spent; at least one page."
  {:malli/schema [:=> [:cat Env [:maybe :string] [:maybe :map]] :map]}
  [env bundle-id bundle]
  (loop [{:keys [seen last-id] :as bundle} (or bundle {:items [] :seen #{} :last-id "" :done? (nil? bundle-id)})]
    (if (:done? bundle)
      bundle
      (let [{:keys [body]}             (request! env "/v1/supply-order/bundle" {:bundle_ids [bundle-id] :limit 100 :last_id last-id})
            {:strs [has_next last_id]} (result body)
            ;; Only what a line is made of: the rest is never stored.
            rows (into (:items bundle) (map #(select-keys % ["sku" "offer_id" "product_id" "quantity" "price" "currency_code"]))
                       (required (items body ["items"])))
            next (further last-id {"has_next" has_next "cursor" last_id})
            bundle {:items rows :seen (cond-> seen next (conj next)) :last-id next :done? (nil? next)}]
        ;; A cycle would never end.
        (when (contains? seen next) (fail! :repeated-cursor))
        (if (spent? env) bundle (recur bundle))))))


(def ^:private supply-states
  ["DATA_FILLING" "READY_TO_SUPPLY" "ACCEPTED_AT_SUPPLY_WAREHOUSE" "IN_TRANSIT" "ACCEPTANCE_AT_STORAGE_WAREHOUSE"
   "REPORTS_CONFIRMATION_AWAITING" "REPORT_REJECTED" "COMPLETED" "REJECTED_AT_SUPPLY_WAREHOUSE" "CANCELLED" "OVERDUE"])


(def ^:private accepted-states
  "Supply order states after storage acceptance began, when an acceptance act can exist.
  ponytail: from the Ozon state list; a live check may show acts earlier."
  #{"ACCEPTANCE_AT_STORAGE_WAREHOUSE" "REPORTS_CONFIRMATION_AWAITING" "REPORT_REJECTED" "COMPLETED"})


(defn- stated-status
  "The status of a supply, else of its order."
  {:malli/schema [:=> [:cat :map :map] [:maybe :string]]}
  [order supply]
  (or (text (get supply "state")) (text (get order "state"))))


(defn- accepted?
  "Whether the supply is accepted, so that it has acts."
  {:malli/schema [:=> [:cat :map :map] :boolean]}
  [order supply]
  (contains? accepted-states (stated-status order supply)))


(defn- read-supply
  "Read on in one supply: its bundle, then the acts of an accepted one. Gives `:bundle` to continue from when the
  step is spent first, else the `:operation`. Each call reads something."
  {:malli/schema [:=> [:cat Env :map :map [:maybe :map]] :map]}
  [env order source bundle]
  (let [bundle-id (text (get source "bundle_id"))
        read?     (or (nil? bundle-id) (:done? bundle))
        bundle    (read-bundle env bundle-id bundle)
        acts?     (accepted? order source)]
    (if (or (not (:done? bundle)) (and acts? (not read?) (spent? env)))
      {:bundle bundle}
      (let [acts (if acts?
                   (required (get (:body (request! env "/v1/supply-order/act/product/get" {:supply_id (required (whole (get source "supply_id")))}))
                                  "supply_acts"))
                   [])]
        ;; Acts are limited to about one call a minute: one call of them ends the step.
        {:operation (supply order source (:items bundle) acts) :acts? acts?}))))


(defn- supplies
  "Supplies of an order. An order being filled in or cancelled may have none yet; any other order states them."
  {:malli/schema [:=> [:cat :map] [:sequential :map]]}
  [order]
  (let [stated (get order "supplies")
        usable (filter #(stated-id (get % "supply_id")) stated)]
    (if (or (contains? #{"DATA_FILLING" "CANCELLED"} (get order "state"))
            (and stated (= (count stated) (count usable))))
      usable
      (fail! :incomplete-page))))


(defn- order-id
  {:malli/schema [:=> [:cat :map] [:maybe :string]]}
  [order]
  (text (or (get order "order_id") (get order "id"))))


(defn- of-order
  "The open supplies of an order among the refetch ids."
  {:malli/schema [:=> [:cat :map :string] [:sequential :string]]}
  [{:keys [refetch-ids]} id]
  (filter #(= id (first (str/split % #"/"))) refetch-ids))


(defn- wanted
  "Predicate of the order ids to detail whatever their last change: unknown ones, and those with an open supply."
  {:malli/schema [:=> [:cat :map] [:fn fn?]]}
  [{:keys [known-orders] :as context}]
  (let [known (if known-orders (known-orders) #{})]
    #(boolean (or (not (contains? known %)) (seq (of-order context %))))))


(defn- settled?
  "Whether a finished pass read the order after its state last changed."
  {:malli/schema [:=> [:cat :map :map] :boolean]}
  [{:keys [settled-before]} order]
  (let [changed (instant (get order "state_updated_date"))]
    (boolean (and settled-before changed (.isBefore (Instant/parse changed) (Instant/parse settled-before))))))


(defn- closed-supplies
  "A cancelled order may state no supplies any more: its open ones, as last stated, are cancelled with it."
  {:malli/schema [:=> [:cat :map :map] [:sequential :map]]}
  [{:keys [stored-supplies] :as context} order]
  (let [open (when (= "CANCELLED" (text (get order "state"))) (of-order context (order-id order)))]
    (if (and (seq open) stored-supplies)
      (let [stated (set (map #(stated-id (get % "supply_id")) (get order "supplies")))]
        (for [facts (stored-supplies open)
              :when (not (stated (:source-id facts)))]
          (assoc facts :kind "operation" :status "CANCELLED" :terminal true)))
      [])))


(defn- supply-id
  {:malli/schema [:=> [:cat :map] [:maybe :string]]}
  [source]
  (stated-id (get source "supply_id")))


(defn- in-progress
  "The progress in an order; none when it belongs to another one."
  {:malli/schema [:=> [:cat :map [:maybe :string]] :map]}
  [{:keys [reading]} id]
  (if (and reading (= id (:order reading))) reading {:order id :done []}))


(defn- recorded
  "Until a first pass finished: the ids of an order's supplies recorded as terminal in the status now stated. A pass
  that failed is not read again."
  {:malli/schema [:=> [:cat :map :map] [:set :string]]}
  [{:keys [settled-before stored-supplies]} order]
  (let [stated (when (and stored-supplies (not settled-before))
                 (into {} (for [s     (supplies order)
                                :let  [status (stated-status order s)]
                                :when (terminal? status)]
                            [(supply-id s) status])))]
    (if (seq stated)
      (into #{} (comp (filter #(and (:terminal %) (= (:status %) (stated (:source-id %))))) (map :source-id))
            (stored-supplies (map #(str (order-id order) "/" %) (keys stated))))
      #{})))


(defn- unread
  "The supplies of an order that are not read yet, by the step's `:recorded` and the progress in the order."
  {:malli/schema [:=> [:cat :map :map :map] [:sequential :map]]}
  [{:keys [recorded]} {:keys [done]} order]
  (remove (comp (into (recorded order) done) supply-id) (supplies order)))


(defn- read-next
  "Read on in the first order of `now`: what was observed, the state after it and whether the step ends."
  {:malli/schema [:=> [:cat Env :map :map :map] :map]}
  [env context {:keys [pending] :as now} order]
  (let [progress (in-progress now (first pending))]
    (if-let [source (first (unread context progress order))]
      (let [part {:supply (supply-id source) :bundle-id (text (get source "bundle_id"))}
            {:keys [operation acts? bundle]}
            (read-supply env order source (when (= part (select-keys progress [:supply :bundle-id])) (:bundle progress)))]
        (if operation
          {:observed [operation] :stop? acts?
           :now      (assoc now :reading {:order (:order progress) :done (conj (:done progress) (:supply part))})}
          {:stop? true :now (assoc now :reading (merge progress part {:bundle bundle}))}))
      {:observed (closed-supplies context order)
       :now      (-> now (assoc :pending (vec (rest pending))) (dissoc :reading))})))


(defn- orders-page
  "The step's page. The unread supplies of an order in progress become open, so the next run reads them although
  the recorded ones make their order known."
  {:malli/schema [:=> [:cat :map [:vector :map] :map [:map-of :string :map]] Step]}
  [context observed {:keys [pending] :as now} orders]
  (let [id       (first pending)
        order    (get orders id)
        progress (in-progress now id)
        ids      (if (and order (seq (:done progress)))
                   (mapv #(str id "/" (supply-id %)) (unread context progress order))
                   [])]
    (cond-> (page (with-named-fbo observed) now false)
      (seq ids) (assoc :pending-ids ids))))


(defn- read-orders
  "Read the pending orders one supply after the other until the step is spent or an act was read. `reading` is the
  progress in the first order: its supplies `done`, and the part read of the `bundle` of one `supply`, which counts
  for that supply and bundle only. A failure after the first progress ends the step and keeps it."
  {:malli/schema [:=> [:cat Env :map :map [:map-of :string :map]] Step]}
  [env context state orders]
  (loop [now state, observed []]
    (let [order (get orders (first (:pending now)))]
      (if (or (nil? order) (and (not= now state) (spent? env)))
        (orders-page context observed now orders)
        (let [next (try (read-next env context now order)
                        (catch clojure.lang.ExceptionInfo error
                          {:setback (met (not= now state) error)}))
              seen (into observed (:observed next))]
          (cond
            (:setback next) (assoc (orders-page context observed now orders) :setback (:setback next))
            (:stop? next) (orders-page context seen (:now next) orders)
            :else (recur (:now next) seen)))))))


(defn- order-details
  "The next pending orders, up to 50 per call. From the first settled one on, only wanted or changed ones are read;
  an order in progress is read to its end."
  {:malli/schema [:=> [:cat Env :map :map] Step]}
  [env context {:keys [pending reading] :as state}]
  (let [env     (metered env)
        batch   (vec (take 50 pending))
        rows    (required (items (:body (request! env "/v3/supply-order/get" {:order_ids batch})) ["orders"]))
        orders  (into {} (map (juxt order-id identity)) rows)
        _       (when-not (every? #(contains? orders %) batch) (fail! :incomplete-page))
        settled (filterv #(settled? context (orders %)) batch)
        skip?   (if (seq settled) (let [wanted? (wanted context)] #(not (or (= % (:order reading)) (wanted? %)))) (constantly false))
        kept    (remove #(and (some #{%} settled) (skip? %)) batch)
        later   (cond->> (drop 50 pending) (seq settled) (remove skip?))
        next    (cond-> (assoc state :pending (into (vec kept) later)) (seq settled) (assoc :settled? true))]
    (if (empty? kept)
      (page [] next false)
      ;; Stored facts are looked up one order at a time, once a step.
      (read-orders env (assoc context :recorded (memoize #(recorded context %))) next orders))))


(defmethod step "supplies" [_ env context {:keys [pending listed? settled? at] :as state}]
  ;; The list has no date filter and states ids only: every run lists every order, the last changed first (Ozon
  ;; refuses oldest first with archive states). Orders are detailed until one settled before the last finished pass;
  ;; below it only unknown ones and those with an open supply are. An order created or changed while paging is
  ;; read by the next run: its change is later than this run's coverage.
  (cond
    (seq pending) (order-details env context state)
    listed? (page [] state true)
    :else
    (let [{:keys [body]} (request! env "/v3/supply-order/list"
                                   {:filter   {:states supply-states} :limit   100        :sort_by "ORDER_STATE_UPDATED_AT"
                                    :sort_dir "DESC"                  :last_id (or at "")})
          ids            (keep text (required (items body ["order_ids"])))
          ;; The empty last page may state the last id again.
          next           (when (seq ids) (next-cursor! at (text (get (result body) "last_id"))))]
      (page [] (assoc state :started? true :at next :listed? (nil? next)
                      :pending (if settled? (filterv (wanted context) ids) (vec ids)))
            false))))


(def first-run-days
  (Duration/ofDays 30))


(def overlap
  (Duration/ofDays 1))


(def ^:private removal-limit
  500)


(defn- removal-days
  "The whole days [from to] that contain a window, with a day more on both sides: which time zone Ozon's days are in
  is not stated, and a row read twice changes nothing."
  {:malli/schema [:=> [:cat :string :string] [:tuple :string :string]]}
  [from to]
  (let [day #(LocalDate/ofInstant (Instant/parse %) ZoneOffset/UTC)]
    [(str (.minusDays (day from) 1)) (str (.plusDays (day to) 1))]))


(defmethod step "removals" [_ env context {:keys [phase last-id from] :as state}]
  ;; There is no read by id: open removals are found again by listing from where they were first covered, in
  ;; ranges no longer than the first run asks for. Ozon takes days, and at most 3 months of them.
  (let [{window-from :from window-to :to} (window context)
        [start to] (removal-days (or (:reread-from context) window-from) window-to)
        start (or (:start state) start)
        from (or from start)
        phase (or phase "from-stock")
        until (str (.plusDays (LocalDate/parse from) (.toDays ^Duration first-run-days)))
        until (if (.isBefore (LocalDate/parse until) (LocalDate/parse to)) until to)
        {:keys [body]}                    (request! env (str "/v1/removal/" phase "/list")
                                                    {:date_from from :date_to until :limit removal-limit :last_id (or last-id "")})
        rows (required (items body ["returns_summary_report_rows"]))
        ;; The last page states a `last_id` too.
        next (when (<= removal-limit (count rows)) (next-cursor! last-id (text (get body "last_id"))))
        next-state (cond next {:phase phase :from from :last-id next}
                         (not= until to) {:phase phase :from until}
                         (= "from-stock" phase) {:phase "from-supply" :from start})]
    (page (mapv #(removal % phase) rows) (assoc (or next-state {}) :start start) (nil? next-state))))


(def dated-domains
  #{"fbs-postings" "fbo-postings" "returns" "rfbs-returns" "removals"})
