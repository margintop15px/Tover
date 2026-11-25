(ns tover.marketplace.sync.ozon
  "Board chapter 41 · Fetch the next source batch: the read-only Ozon client and one traversal per sync domain.

  `request!` is the only way to Ozon. It refuses any path outside `allowlist`, keeps request starts for one
  Client-Id at least 25 ms apart, tries a request at most 4 times (timeouts, 429 and 5xx) with
  0.5 / 1 / 2 s plus up to 0.25 s jitter, honours Retry-After and Item-Retry-After up to 30 s in place and
  turns a longer wait into `:throttled`. A 401 is `:credentials-rejected`, a 403 `:unavailable`.

  `step` reads one page or detail batch of a domain from an opaque traversal state and returns sanitized
  observations (only the facts named on the board; no buyer data, selling prices or raw payloads), the next state and
  whether the domain is finished. Failures are ex-info with `:type` one of `:credentials-rejected`
  `:unavailable` `:throttled` `:retries-exhausted` `:repeated-cursor` `:incomplete-page` `:unexpected-response`.

  Response shapes follow docs/ozon-source-contracts.md. They are unverified against a live account, so readers
  accept the documented root or `result` wrapper and treat a missing required field as an incomplete page."
  (:require
    [charred.api :as json]
    [clojure.string :as str]
    [tover.marketplace.sync.observations :as observations])
  (:import
    [java.io IOException]
    [java.net.http HttpTimeoutException]
    [java.time Duration Instant ZonedDateTime]
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
   [:client-id :string] [:api-key :string]])


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


(def ^:private spacing
  25)


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
    (let [{:keys [status headers] :as response}
          (try (post path body {"Client-Id" client-id "Api-Key" api-key})
               (catch HttpTimeoutException _ {:status 0})
               (catch IOException _ {:status 0}))]
      (cond
        (<= 200 status 299) {:body (parse (:body response)) :tries attempt}
        (= 401 status) (fail! :credentials-rejected)
        (= 403 status) (fail! :unavailable)
        (retryable? status)
        (let [wait (retry-after headers (clock))]
          (cond
            (and wait (> wait in-place-limit))
            (fail! :throttled {:resume-not-before (str (Instant/ofEpochMilli (+ (clock) wait)))})
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


(defn handed-over?
  "Whether the posting was handed over: its status is after handover or, when cancelled, Ozon says so: by flag for
  FBS, by the handover time for FBO, which states no flag."
  {:malli/schema [:=> [:cat observations/OperationKind :map] :boolean]}
  [kind posting]
  (let [status (text (get posting "status"))]
    (cond (observations/after-handover? status) true
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
  {:malli/schema [:=> [:cat :map] :boolean]}
  [row]
  (let [status (get-in row ["compensation_status" "status"])]
    (boolean (some #(stated-id (get status %)) ["id" "sys_name" "display_name"]))))


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
  "A supply line with the quantities of its SKU's acceptance `acts`, each summed over them when all state it. The
  line quantity is the approved sum, only when every act's fact and approved quantities agree."
  {:malli/schema [:=> [:cat :map [:sequential :map]] :map]}
  [line acts]
  (let [sums   (into {} (for [[field k] act-quantities
                              :let      [stated (map #(decimal (get % field)) acts)]
                              :when     (and (seq stated) (every? some? stated))]
                          [k (text (reduce + stated))]))
        ids    (distinct (keep #(text (get % "act_id")) acts))
        agree? (fn [act] (some-> (decimal (get act "fact_quantity")) (== (or (decimal (get act "approved_quantity")) -1M))))]
    (cond-> (merge (dissoc line :quantity) sums)
      (seq ids) (assoc :act-id (str/join "," ids))
      (and (seq acts) (every? agree? acts)) (assoc :quantity (:approved-quantity sums)))))


(defn supply
  "One supply of a `/v3/supply-order/get` order with its bundle items and the items of its acts
  (`/v1/supply-order/act/product/get`). Only ACCEPTANCE acts state accepted quantities; the receipt time is not
  stated. A sum goes to the one line of its SKU; a SKU the bundle does not list adds a line."
  {:malli/schema [:=> [:cat :map :map [:sequential :map] [:sequential :map]] :map]}
  [order {:strs [supply_id state storage_warehouse]} bundle-items act-items]
  (let [from     (named-warehouse "supply-origin" (get-in order ["order_tags" "seller_warehouse_id"]) nil)
        to       (named-warehouse "fbo" (get storage_warehouse "warehouse_id") (get storage_warehouse "name"))
        sku      #(stated-id (get % "sku"))
        acts     (filter #(= "ACCEPTANCE" (some-> (text (get % "act_type")) str/upper-case)) act-items)
        by-sku   (dissoc (group-by sku acts) nil)
        listed   (set (keep sku bundle-items))
        extra    (concat (map #(hash-map "sku" %) (distinct (remove listed (keep sku acts))))
                         (filter (comp nil? sku) acts))
        rows     (mapv (fn [item line] (accepted line (if (:duplicate line) [] (get by-sku (sku item) []))))
                       (concat bundle-items extra)
                       (lines (concat bundle-items extra) to))
        order-id (text (or (get order "order_id") (get order "id")))]
    (operation (cond-> {:operation-kind "supply" :source-id (required (stated-id supply_id))
                        :status         (or (text state) (text (get order "state")) "unknown")
                        :lines          rows}
                 order-id (assoc :refetch-key order-id)
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
  "What a step may know besides its state: the dated window, ids to refetch and lookups over observed listings."
  [:map
   [:window {:optional true} [:map [:from :string] [:to :string]]]
   [:refetch-ids {:optional true} [:sequential :string]]
   [:skus {:optional true} [:fn fn?]]
   [:categories {:optional true} [:fn fn?]]
   [:sku->product {:optional true} [:fn fn?]]
   [:sku->products {:optional true} [:fn fn?]]])


(def Step
  [:map [:observations [:vector :map]] [:state :map] [:finished? :boolean]])


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


(defmethod step "attributes" [_ env _ {:keys [last-id]}]
  (let [{:keys [body]} (request! env "/v4/product/info/attributes"
                                 {:filter {:visibility "ALL"} :limit 1000 :last_id (or last-id "")})
        rows           (required (items body ["result" "items"]))
        next           (next-cursor! last-id (text (get body "last_id")))]
    (page (mapv attributes rows) {:last-id next} (or (empty? rows) (nil? next)))))


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
  `list` returns {:ids :next :more?} for an offset or cursor; `detail` maps ids to observations."
  {:malli/schema [:=> [:cat :map :map :int [:fn fn?] [:fn fn?]] Step]}
  [{:keys [refetch-ids]} state n list detail]
  (let [state (if (:started? state) state {:started? true :pending (vec refetch-ids)})]
    (details state n detail
             (fn [{:keys [listed? at] :as state}]
               (if listed?
                 (page [] state true)
                 (let [{:keys [ids next more?]} (list at)]
                   (page [] (assoc state :pending (vec ids) :at next :listed? (not more?)) false)))))))


(defn- window
  {:malli/schema [:=> [:cat :map] [:map [:from :string] [:to :string]]]}
  [context]
  (or (:window context) (fail! :incomplete-page)))


(def ^:private posting-details
  {:analytics_data true :financial_data true})


(defmethod step "fbs-postings" [_ env context state]
  (let [{:keys [from to]} (window context)]
    (dated context state 50
           (fn [offset]
             (let [offset         (or offset 0)
                   {:keys [body]} (request! env "/v4/posting/fbs/list"
                                            {:dir "ASC" :filter {:since from :to to} :limit 1000 :offset offset})
                   rows           (required (items body ["postings"]))]
               {:ids   (keep #(text (get % "posting_number")) rows)              :next (+ offset (count rows))
                :more? (boolean (and (seq rows) (get (result body) "has_next")))}))
           (fn [numbers]
             (for [n numbers]
               (fbs-posting (result (:body (request! env "/v3/posting/fbs/get" {:posting_number n :with posting-details})))))))))


(defmethod step "fbo-postings" [_ env context state]
  (let [{:keys [from to]} (window context)]
    (dated context state 50
           (fn [offset]
             (let [offset         (or offset 0)
                   {:keys [body]} (request! env "/v3/posting/fbo/list"
                                            {:dir "ASC" :filter {:since from :to to} :limit 1000 :offset offset})
                   rows           (required (items body ["postings"]))]
               {:ids   (keep #(text (get % "posting_number")) rows) :next (+ offset (count rows))
                :more? (boolean (and (seq rows) (or (get (result body) "has_next") (= 1000 (count rows)))))}))
           (fn [numbers]
             (with-named-fbo
               (for [n numbers]
                 (fbo-posting (result (:body (request! env "/v2/posting/fbo/get"
                                                       {:posting_number n :with posting-details}))))))))))


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
    (dated context state 50
           (fn [last-id]
             (let [{:keys [body]} (request! env "/v2/returns/rfbs/list"
                                            {:filter {:created_at {:from from :to to}} :limit 500 :last_id (or last-id 0)})
                   rows           (required (items body ["returns"]))
                   next           (some-> (last rows) (get "return_id") text)]
               (next-cursor! (some-> last-id str) next)
               {:ids (keep #(text (get % "return_id")) rows) :next next :more? (boolean (seq rows))}))
           (fn [ids]
             (for [id ids]
               (rfbs-return (required (get (:body (request! env "/v2/returns/rfbs/get" {:return_id id})) "returns"))))))))


(defn- bundle-items
  {:malli/schema [:=> [:cat :map [:maybe :string]] [:vector :map]]}
  [env bundle-id]
  (if-not bundle-id
    []
    (loop [last-id "" seen #{} acc []]
      (let [{:keys [body]}             (request! env "/v1/supply-order/bundle" {:bundle_ids [bundle-id] :limit 100 :last_id last-id})
            {:strs [has_next last_id]} (result body)
            acc (into acc (required (items body ["items"])))
            next (further last-id {"has_next" has_next "cursor" last_id})]
        (cond (nil? next) acc
              ;; The whole bundle is read in one step, so a cycle would never end.
              (seen next) (fail! :repeated-cursor)
              :else (recur next (conj seen next) acc))))))


(def ^:private accepted-states
  "Supply order states after storage acceptance began, when an acceptance act can exist.
  ponytail: from the Ozon state list; a live check may show acts earlier."
  #{"ACCEPTANCE_AT_STORAGE_WAREHOUSE" "REPORTS_CONFIRMATION_AWAITING" "REPORT_REJECTED" "COMPLETED"})


(defn- act-items
  "Items of every act of an accepted supply; none before acceptance."
  {:malli/schema [:=> [:cat Env :map :map] [:vector :map]]}
  [env order supply]
  (let [state (or (text (get supply "state")) (text (get order "state")))]
    (if (contains? accepted-states (some-> state str/upper-case))
      (vec (required (items (:body (request! env "/v1/supply-order/act/product/get" {:supply_id (required (text (get supply "supply_id")))}))
                            ["items" "products"])))
      [])))


(defmethod step "supplies" [_ env context state]
  ;; ponytail: supply lists carry no date filter we rely on; every order is listed and the window only
  ;; bounds coverage. Act request and field names follow the contract doc; confirm with a live check.
  (dated context state 50
         (fn [last-id]
           (let [{:keys [body]} (request! env "/v3/supply-order/list" {:filter {} :limit 100 :last_id (or last-id "")})
                 ids            (keep text (required (items body ["order_ids"])))
                 next           (next-cursor! last-id (text (get (result body) "last_id")))]
             {:ids ids :next next :more? (boolean (and (seq ids) next))}))
         (fn [ids]
           (let [{:keys [body]} (request! env "/v3/supply-order/get" {:order_ids ids})
                 orders         (required (items body ["orders"]))
                 found          (set (keep #(text (or (get % "order_id") (get % "id"))) orders))]
             (when-not (every? found ids) (fail! :incomplete-page))
             (with-named-fbo
               (for [order orders
                     each  (required (get order "supplies"))]
                 (supply order each (bundle-items env (text (get each "bundle_id"))) (act-items env order each))))))))


(defmethod step "removals" [_ env context {:keys [phase last-id]}]
  (let [{:keys [from to]} (window context)
        phase             (or phase "from-stock")
        {:keys [body]}    (request! env (str "/v1/removal/" phase "/list")
                                    {:date_from from :date_to to :limit 500 :last_id (or last-id "")})
        rows              (required (items body ["returns_summary_report_rows"]))
        next              (next-cursor! last-id (text (get body "last_id")))
        more?             (and (seq rows) next)]
    (page (mapv #(removal % phase) rows)
          (cond more? {:phase phase :last-id next} (= "from-stock" phase) {:phase "from-supply"} :else {})
          (and (not more?) (= "from-supply" phase)))))


(def dated-domains
  #{"fbs-postings" "fbo-postings" "returns" "rfbs-returns" "supplies" "removals"})


(def first-run-days
  (Duration/ofDays 30))


(def overlap
  (Duration/ofDays 1))
