(ns tover.shared.web.address
  "The visitor's network address for counting. An address the visitor supplies is never trusted: only the last
  entry of `X-Forwarded-For`, which our own proxy writes, and only where the deployment says there is one."
  (:require
    [clojure.string :as str])
  (:import
    [java.net Inet4Address InetAddress UnknownHostException]))


(defn- literal
  "The address of an IP literal; a name is never looked up."
  {:malli/schema [:=> [:cat [:maybe :string]] [:maybe [:fn #(instance? InetAddress %)]]]}
  [value]
  (when (and value (or (re-matches #"(?:\d{1,3}\.){3}\d{1,3}" value)
                       ;; With a colon Java parses a literal or fails; it never resolves.
                       (re-matches #"[0-9a-fA-F:.]*:[0-9a-fA-F:.]*" value)))
    (try (InetAddress/getByName value)
         (catch UnknownHostException _ nil))))


(defn- network
  "IPv4 exactly (Java already reads IPv4-mapped IPv6 as IPv4); IPv6 as its /64."
  {:malli/schema [:=> [:cat [:fn #(instance? InetAddress %)]] :string]}
  [^InetAddress address]
  (if (instance? Inet4Address address)
    (.getHostAddress address)
    (let [bytes (.getAddress address)]
      (str (.getHostAddress (InetAddress/getByAddress (byte-array (concat (take 8 bytes) (repeat 8 0))))) "/64"))))


(defn visitor
  {:malli/schema [:=> [:cat :map :any] :string]}
  [{:keys [headers remote-addr]} trusted-proxy?]
  (let [forwarded (when trusted-proxy?
                    (some-> (get headers "x-forwarded-for") (str/split #",") last str/trim literal))
        address   (or forwarded (literal remote-addr))]
    (if address (network address) "unknown")))
