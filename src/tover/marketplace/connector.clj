(ns tover.marketplace.connector
  "Marketplace credentials outside the Bel command: Ozon verification and the connection-secret vault.

  The API key is write-only. The connect and replace actions verify it here, seal it with AES-256-GCM under
  the connector key (`TOVER_CONNECTOR_KEY`, 32 bytes in base64, separate from `TOVER_SESSION_KEY`) and pass
  only the secret reference to the command. Command input is recorded with its receipt, so the key and its
  ciphertext never reach a command, event, receipt, read model, log or the browser. Sealed keys live in a
  per-workspace RDF graph that no Bel model maps.

  The connector is the `:marketplace` Bel resource: `{:verify (fn [{:keys [client-id api-key]}] verification)
  :repo repository :key bytes :post (fn [path body headers] response) :pacer atom}`. Tests pass their own
  `:verify` and `:post`; nothing here runs at namespace load."
  (:require
    [bel-rdf.adapter :as adapter]
    [buddy.core.codecs :as codecs]
    [buddy.core.crypto :as crypto]
    [buddy.core.mac :as mac]
    [buddy.core.nonce :as nonce]
    [charred.api :as json]
    [clojure.string :as str]
    [com.brunobonacci.mulog :as mu]
    [tover.shared.rdf :as rdf])
  (:import
    [java.io IOException]
    [java.net URI]
    [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers HttpTimeoutException]
    [java.time Duration Instant]
    [java.time.format DateTimeParseException]
    [java.util UUID]))


(def domains
  "Sync domains in board order (chapter 41, Fetch the next source batch)."
  ["warehouses" "products" "sku-aliases" "attributes" "attribute-definitions" "prices" "stock-totals"
   "stock-by-warehouse" "stock-analytics"
   "fbs-postings" "fbo-postings" "returns" "rfbs-returns" "supplies" "removals"])


(def Verification
  [:map
   [:verification-outcome [:enum "verified" "rejected" "unreachable"]]
   [:account-label {:optional true} :string]
   [:account-currency {:optional true} :string]
   [:readable-domains {:optional true} [:vector (into [:enum] domains)]]
   [:key-expires-at {:optional true} :string]])


(def Connector
  [:map [:verify [:fn fn?]] [:repo {:optional true} :any] [:key {:optional true} bytes?]
   [:post {:optional true} [:fn fn?]] [:pacer {:optional true} :any] [:last-look {:optional true} :any]])


;; Sealing


(defn connector-key
  "The 32-byte connector key from base64 text."
  {:malli/schema [:=> [:cat :string] bytes?]}
  [text]
  (let [key (try (codecs/b64->bytes text)
                 (catch Exception _ (throw (ex-info "Invalid connector key" {:type :configuration}))))]
    (when-not (= 32 (alength ^bytes key))
      (throw (ex-info "Connector key must contain 32 bytes" {:type :configuration})))
    key))


(defn seal
  {:malli/schema [:=> [:cat bytes? :string] :string]}
  [key plaintext]
  (let [iv (nonce/random-bytes (long 12))]
    (str (codecs/bytes->b64-str iv) "."
         (codecs/bytes->b64-str (crypto/encrypt (codecs/str->bytes plaintext) key iv {:alg :aes256-gcm})))))


(defn unseal
  {:malli/schema [:=> [:cat bytes? :string] :string]}
  [key sealed]
  (let [[iv encrypted] (str/split sealed #"\." 2)]
    (codecs/bytes->str (crypto/decrypt (codecs/b64->bytes encrypted) key (codecs/b64->bytes iv) {:alg :aes256-gcm}))))


;; Vault


(defn- graph
  {:malli/schema [:=> [:cat :uuid] rdf/IRI]}
  [workspace-id]
  (rdf/iri (str "urn:tover:workspace:" workspace-id ":marketplace-secrets")))


(defn- subject
  {:malli/schema [:=> [:cat :string] rdf/IRI]}
  [secret-ref]
  (rdf/iri (str "urn:tover:marketplace-secret:" secret-ref)))


(def ^:private sealed-key
  (rdf/iri "urn:tover:marketplace:sealed-key"))


(defn- configured!
  {:malli/schema [:=> [:cat [:maybe :map]] :map]}
  [{:keys [repo key] :as connector}]
  (when-not (and repo key)
    (throw (ex-info "Marketplace connections are not configured" {:type :configuration})))
  connector)


(defn secret-ref
  "The reference for `api-key` in attempt `command-id`: the same for a double-click, different for another key.
  Keyed with the connector key, so it tells nothing about the API key."
  {:malli/schema [:=> [:cat :map :string :string :string] :string]}
  [{:keys [key]} command-id client-id api-key]
  (str (UUID/nameUUIDFromBytes (mac/hash (pr-str [command-id client-id api-key]) {:key key :alg :hmac+sha256}))))


(def ^:private in-flight-millis
  "How long a sealed key waits for its command before another attempt may destroy it."
  120000)


(defn- now-millis
  {:malli/schema [:=> [:cat :map] :int]}
  [{:keys [clock]}]
  (if clock (clock) (System/currentTimeMillis)))


(defn- recent
  {:malli/schema [:=> [:cat [:maybe :map] :int] :map]}
  [sealed now]
  (into {} (filter (fn [[_ at]] (<= (- now at) in-flight-millis))) sealed))


(defn- in-flight!
  "Remember that a key is about to be sealed. Kept in this process only: after a crash nothing is in flight."
  {:malli/schema [:=> [:cat :map :string] :nil]}
  [{:keys [in-flight] :as connector} secret-ref]
  (when in-flight
    (let [now (now-millis connector)]
      (swap! in-flight #(assoc (recent % now) secret-ref now))))
  nil)


(defn store-secret!
  "Seal `api-key` under `secret-ref` (or a new reference) in the workspace's secret graph; returns the reference."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :map] :uuid :string] :string]
                  [:=> [:cat [:maybe :map] :uuid :string :string] :string]]}
  ([connector workspace-id api-key]
   (store-secret! connector workspace-id (str (random-uuid)) api-key))
  ([connector workspace-id secret-ref api-key]
   (let [{:keys [repo key]} (configured! connector)]
     ;; Before the commit that makes it visible, so no sweep sees the key without seeing it in flight.
     (in-flight! connector secret-ref)
     ;; A reference stands for one key, so sealing it twice only adds another ciphertext of that key.
     (rdf/with-transaction [conn {:repo repo :level :serializable}]
       (rdf/add! conn (graph workspace-id) (subject secret-ref) sealed-key (seal key api-key))))
   secret-ref))


(defn sealed?
  "Whether a reference has a key, read in the caller's transaction so that a concurrent destroy conflicts with it."
  {:malli/schema [:=> [:cat rdf/Connection :uuid :string] :boolean]}
  [conn workspace-id secret-ref]
  (some? (rdf/value-at conn (graph workspace-id) (subject secret-ref) sealed-key)))


(defn destroy-unreferenced!
  "Destroy the workspace's sealed keys that are neither in `referenced`, in flight, nor named by any stored
  record: their command was not accepted. Serializable with the command that would name one; when another commit
  wins, the next attempt destroys them."
  {:malli/schema [:=> [:cat [:maybe :map] :uuid [:set :string]] :nil]}
  [connector workspace-id referenced]
  (let [{:keys [repo in-flight]} (configured! connector)
        prefix                   (str (subject ""))]
    (try
      (adapter/transact repo :serializable
                        (fn [conn]
                          (let [secrets (mapv :secret (rdf/select conn '{:select [?secret] :where [[?secret ?p ?key]]}
                                                                  {:p sealed-key}))
                                ;; Read after the list: every listed key was registered before it was visible.
                                kept    (into referenced (keys (recent (some-> in-flight deref) (now-millis connector))))]
                            (doseq [secret (distinct secrets)
                                    :let   [secret-ref (subs (str secret) (count prefix))]
                                    :when  (and (sealed? conn workspace-id secret-ref)
                                                (not (contains? kept secret-ref))
                                                (empty? (rdf/select conn '{:select [?record] :where [[?record ?p ?ref]]}
                                                                    {:ref secret-ref})))]
                              (rdf/remove! conn (graph workspace-id) (subject secret-ref) nil nil)))))
      (catch clojure.lang.ExceptionInfo error
        (when-not (= :conflict (:type (ex-data error))) (throw error))
        (mu/log ::unreferenced-secrets-kept))))
  nil)


(defn destroy-secret!
  "Remove a sealed key. Idempotent."
  {:malli/schema [:=> [:cat [:maybe :map] :uuid :string] :nil]}
  [connector workspace-id secret-ref]
  (let [{:keys [repo]} (configured! connector)]
    (rdf/with-transaction [conn {:repo repo :level :serializable}]
      (rdf/remove! conn (graph workspace-id) (subject secret-ref) nil nil)))
  nil)


(defn api-key
  "The API key behind a reference, for the server-side fetcher only; nil when destroyed."
  {:malli/schema [:=> [:cat [:maybe :map] :uuid :string] [:maybe :string]]}
  [connector workspace-id secret-ref]
  (let [{:keys [repo key]} (configured! connector)]
    (rdf/with-transaction [conn {:repo repo :level :snapshot}]
      (some->> (rdf/value-at conn (graph workspace-id) (subject secret-ref) sealed-key) (unseal key)))))


;; Ozon


(def ^:private origin
  "https://api-seller.ozon.ru")


(def domain-calls
  "Every Ozon call of each domain (chapter 41, Domains table). A domain is readable when the key lists them all."
  {"warehouses"            #{"/v2/warehouse/list" "/v1/warehouse/fbo/seller/list"}
   "products"              #{"/v3/product/list" "/v3/product/info/list"}
   "sku-aliases"           #{"/v1/product/related-sku/get"}
   "attributes"            #{"/v4/product/info/attributes"}
   "attribute-definitions" #{"/v1/description-category/attribute"}
   "prices"                #{"/v5/product/info/prices"}
   "stock-totals"          #{"/v4/product/info/stocks"}
   "stock-by-warehouse"    #{"/v1/product/info/stocks-by-warehouse/fbo" "/v2/product/info/stocks-by-warehouse/fbs"}
   "stock-analytics"       #{"/v1/analytics/stocks"}
   "fbs-postings"          #{"/v4/posting/fbs/list" "/v3/posting/fbs/get"}
   "fbo-postings"          #{"/v3/posting/fbo/list" "/v2/posting/fbo/get"}
   "returns"               #{"/v1/returns/list"}
   "rfbs-returns"          #{"/v2/returns/rfbs/list" "/v2/returns/rfbs/get"}
   "supplies"              #{"/v3/supply-order/list" "/v3/supply-order/get" "/v1/supply-order/bundle"
                             "/v1/supply-order/act/product/get"}
   "removals"              #{"/v1/removal/from-stock/list" "/v1/removal/from-supply/list"}})


(def ^:private http-client
  (delay (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 10)) (.build))))


(defn http-post
  "POST JSON to Ozon; returns {:status :body :headers} with lower-case header names. Network failures and
  timeouts throw. Verification waits 15 s per request; the sync fetcher passes 30."
  {:malli/schema [:function
                  [:=> [:cat :string :map :map] [:map [:status :int] [:body :string]]]
                  [:=> [:cat :string :map :map :int] [:map [:status :int] [:body :string]]]]}
  ([path body headers] (http-post path body headers 15))
  ([path body headers timeout-seconds]
   (let [request  (-> (HttpRequest/newBuilder (URI/create (str origin path)))
                      (.timeout (Duration/ofSeconds timeout-seconds))
                      (.header "Content-Type" "application/json")
                      (as-> builder (reduce-kv (fn [b k v] (.header ^java.net.http.HttpRequest$Builder b k v)) builder headers))
                      (.POST (HttpRequest$BodyPublishers/ofString (json/write-json-str body)))
                      (.build))
         response (.send ^HttpClient @http-client request (HttpResponse$BodyHandlers/ofString))]
     {:status  (.statusCode response) :body (str (.body response))
      :headers (into {} (keep (fn [[k vs]] (when (seq vs) [(str/lower-case k) (first vs)])))
                     (.map (.headers response)))})))


(defn- classify
  "Any 4xx but 429 is a refusal; other answers and network failures mean the provider could not answer."
  {:malli/schema [:=> [:cat [:fn fn?]] [:map [:outcome :keyword] [:body {:optional true} :string]]]}
  [request]
  (try
    (let [{:keys [status body]} (request)]
      (cond
        (<= 200 status 299) {:outcome :ok :body body}
        (and (<= 400 status 499) (not= 429 status)) {:outcome :refused}
        :else {:outcome :unreachable}))
    (catch HttpTimeoutException _ {:outcome :unreachable})
    (catch IOException _ {:outcome :unreachable})))


(defn- answer
  "The JSON object a call answers, or its outcome when there is none."
  {:malli/schema [:=> [:cat [:fn fn?]] [:map [:outcome :keyword] [:data {:optional true} :map]]]}
  [request]
  (let [{:keys [outcome body]} (classify request)
        data                   (when (= :ok outcome) (try (json/read-json body) (catch Exception _ nil)))]
    (cond
      (not= :ok outcome) {:outcome outcome}
      (map? data) {:outcome :ok :data data}
      :else {:outcome :unreachable})))


(defn- header-text?
  "Printable ASCII: what a credential may hold to be sent as an HTTP header."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [text]
  (boolean (re-matches #"[\x20-\x7E]+" text)))


(defn- text
  {:malli/schema [:=> [:cat :any] [:maybe :string]]}
  [value]
  (when (string? value) (not-empty (str/trim value))))


(defn- expiry
  "A stated key expiry as ISO text; absent when there is none."
  {:malli/schema [:=> [:cat :any] [:maybe :string]]}
  [value]
  (some-> (text value) (as-> s (try (str (Instant/parse s)) (catch DateTimeParseException _ nil)))))


(defn- listed-methods
  {:malli/schema [:=> [:cat :any] [:set :any]]}
  [roles]
  (letfn [(rows [value] (when (sequential? value) value))]
    (into #{} (mapcat #(when (map? %) (rows (get % "methods")))) (rows roles))))


(defn ozon-verify
  "`/v1/seller/info`, then `/v1/roles`: Ozon authenticates the Client-Id and key together, so two answers prove the
  pair. `post` is `http-post` or a test fake. Only the returned facts leave this function; everything else in
  the answers (legal details, ratings, other methods) is dropped here."
  {:malli/schema [:=> [:cat [:fn fn?] [:map [:client-id :string] [:api-key :string]]] Verification]}
  [post {:keys [client-id api-key]}]
  (let [call     #(answer (fn [] (post % {} {"Client-Id" client-id "Api-Key" api-key})))
        failed   {:refused {:verification-outcome "rejected"} :unreachable {:verification-outcome "unreachable"}}
        info     (if (every? header-text? [client-id api-key]) (call "/v1/seller/info") {:outcome :refused})
        roles    (when (= :ok (:outcome info)) (call "/v1/roles"))
        company  (get-in info [:data "company"])
        label    (text (get company "name"))
        currency (text (get company "currency"))
        expires  (expiry (get-in roles [:data "expires_at"]))
        methods  (listed-methods (get-in roles [:data "roles"]))
        readable (filterv #(every? methods (domain-calls %)) domains)]
    (mu/log ::verified :info (:outcome info) :roles (:outcome roles) :readable (count readable))
    (or (failed (:outcome info))
        (failed (:outcome roles))
        (when-not currency (failed :unreachable))
        (cond-> {:verification-outcome "verified" :account-currency currency :readable-domains readable}
          label (assoc :account-label label)
          expires (assoc :key-expires-at expires)))))


(defn connector
  "Integrant: the `:marketplace` resource. Without a connector key connecting reports a configuration error."
  {:malli/schema [:=> [:cat [:map [:repo :any] [:connector-key {:optional true} [:maybe :string]]]] Connector]}
  [{:keys [repo connector-key]}]
  (cond-> {:verify    (partial ozon-verify http-post) :repo  repo
           ;; The sync fetcher's transport (30 s per try) and its per-Client-Id request pacing.
           :post      #(http-post %1 %2 %3 30)        :pacer (atom {}) :last-look (atom nil)
           ;; Keys sealed by this process whose command may still be running.
           :in-flight (atom {})}
    connector-key (assoc :key (tover.marketplace.connector/connector-key connector-key))))
