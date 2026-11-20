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
    [buddy.core.codecs :as codecs]
    [buddy.core.crypto :as crypto]
    [buddy.core.nonce :as nonce]
    [charred.api :as json]
    [clojure.string :as str]
    [com.brunobonacci.mulog :as mu]
    [tover.shared.rdf :as rdf])
  (:import
    [java.io IOException]
    [java.net URI]
    [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers HttpTimeoutException]
    [java.time Duration]))


(def domains
  "Sync domains in board order (chapter 41, Fetch the next source batch); each has one read-only probe."
  ["warehouses" "products" "sku-aliases" "attributes" "attribute-definitions" "stock-totals" "stock-analytics"
   "fbs-postings" "fbo-postings" "returns" "rfbs-returns" "supplies" "removals"])


(def Verification
  [:map
   [:verification-outcome [:enum "verified" "rejected" "unreachable"]]
   [:provider-account-id {:optional true} :string]
   [:account-label {:optional true} :string]
   [:readable-domains {:optional true} [:vector (into [:enum] domains)]]])


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


(defn store-secret!
  "Seal `api-key` under a new reference in the workspace's secret graph; returns the reference."
  {:malli/schema [:=> [:cat [:maybe :map] :uuid :string] :string]}
  [connector workspace-id api-key]
  (let [{:keys [repo key]} (configured! connector)
        secret-ref         (str (random-uuid))]
    (rdf/with-transaction [conn {:repo repo :level :serializable}]
      (rdf/add! conn (graph workspace-id) (subject secret-ref) sealed-key (seal key api-key)))
    secret-ref))


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


(def probes
  "One read-only request per domain, the smallest each endpoint accepts."
  {"warehouses"            ["/v2/warehouse/list" {:limit 1}]
   "products"              ["/v3/product/list" {:filter {} :limit 1}]
   "sku-aliases"           ["/v1/product/related-sku/get" {:sku []}]
   "attributes"            ["/v4/product/info/attributes" {:filter {} :limit 1}]
   "attribute-definitions" ["/v1/description-category/attribute" {}]
   "stock-totals"          ["/v4/product/info/stocks" {:filter {} :limit 1}]
   "stock-analytics"       ["/v1/analytics/stocks" {:skus []}]
   "fbs-postings"          ["/v4/posting/fbs/list" {:limit 1}]
   "fbo-postings"          ["/v3/posting/fbo/list" {:limit 1}]
   "returns"               ["/v1/returns/list" {:limit 1}]
   "rfbs-returns"          ["/v2/returns/rfbs/list" {:limit 1}]
   "supplies"              ["/v3/supply-order/list" {:limit 1}]
   "removals"              ["/v1/removal/from-stock/list" {:limit 1}]})


(defn http-post
  "POST JSON to Ozon; returns {:status :body :headers} with lower-case header names. Network failures and
  timeouts throw. Verification waits 15 s per request; the sync fetcher passes 30."
  {:malli/schema [:function
                  [:=> [:cat :string :map :map] [:map [:status :int] [:body :string]]]
                  [:=> [:cat :string :map :map :int] [:map [:status :int] [:body :string]]]]}
  ([path body headers] (http-post path body headers 15))
  ([path body headers timeout-seconds]
   (let [client   (HttpClient/newHttpClient)
         request  (-> (HttpRequest/newBuilder (URI/create (str origin path)))
                      (.timeout (Duration/ofSeconds timeout-seconds))
                      (.header "Content-Type" "application/json")
                      (as-> builder (reduce-kv (fn [b k v] (.header ^java.net.http.HttpRequest$Builder b k v)) builder headers))
                      (.POST (HttpRequest$BodyPublishers/ofString (json/write-json-str body)))
                      (.build))
         response (.send client request (HttpResponse$BodyHandlers/ofString))]
     {:status  (.statusCode response) :body (str (.body response))
      :headers (into {} (keep (fn [[k vs]] (when (seq vs) [(str/lower-case k) (first vs)])))
                     (.map (.headers response)))})))


(defn- classify
  "401/403 and other client errors are a refusal; 429, 5xx and network failures mean the provider could not answer."
  {:malli/schema [:=> [:cat [:fn fn?]] [:map [:outcome :keyword] [:body {:optional true} :string]]]}
  [request]
  (try
    (let [{:keys [status body]} (request)]
      (cond
        (<= 200 status 299) {:outcome :ok :body body}
        (or (= 429 status) (<= 500 status)) {:outcome :unreachable}
        :else {:outcome :refused}))
    (catch HttpTimeoutException _ {:outcome :unreachable})
    (catch IOException _ {:outcome :unreachable})))


(defn ozon-verify
  "`/v1/seller/info`, then one probe per domain. `post` is `http-post` or a test fake.
  A refused probe makes that domain unreadable; a probe the provider could not answer makes the whole
  verification unreachable, so a domain is never wrongly reported as unavailable."
  {:malli/schema [:=> [:cat [:fn fn?] [:map [:client-id :string] [:api-key :string]]] Verification]}
  [post {:keys [client-id api-key]}]
  (let [headers {"Client-Id" client-id "Api-Key" api-key}
        info    (classify #(post "/v1/seller/info" {} headers))]
    (case (:outcome info)
      :refused {:verification-outcome "rejected"}
      :unreachable {:verification-outcome "unreachable"}
      (let [results (into {} (map (fn [[domain [path body]]] [domain (:outcome (classify #(post path body headers)))])) probes)
            label   (try (not-empty (str (get-in (json/read-json (:body info)) ["company" "name"])))
                         (catch Exception _ nil))]
        (mu/log ::verified :readable (count (filter #{:ok} (vals results))))
        (if (some #{:unreachable} (vals results))
          {:verification-outcome "unreachable"}
          (cond-> {:verification-outcome "verified"
                   ;; ponytail: Ozon authenticates the Client-Id header itself, so the account is that id;
                   ;; read a reported id from the body if a live check shows one.
                   :provider-account-id  client-id
                   :readable-domains     (filterv #(= :ok (results %)) domains)}
            label (assoc :account-label label)))))))


(defn connector
  "Integrant: the `:marketplace` resource. Without a connector key connecting reports a configuration error."
  {:malli/schema [:=> [:cat [:map [:repo :any] [:connector-key {:optional true} [:maybe :string]]]] Connector]}
  [{:keys [repo connector-key]}]
  (cond-> {:verify (partial ozon-verify http-post) :repo  repo
           ;; The sync fetcher's transport (30 s per try) and its per-Client-Id request pacing.
           :post   #(http-post %1 %2 %3 30)        :pacer (atom {}) :last-look (atom nil)}
    connector-key (assoc :key (tover.marketplace.connector/connector-key connector-key))))
