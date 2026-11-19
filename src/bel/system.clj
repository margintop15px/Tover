(ns bel.system
  "Integrant keys and lifecycle for Bel applications. An application declares its keys in a config map;
  `start!` loads their namespaces and initializes them, `stop!` halts every key even when one fails."
  (:require
    [integrant.core :as ig])
  (:import
    [clojure.lang ExceptionInfo]))


(defn- resolve-symbol
  {:malli/schema [:=> [:cat :any] :any]}
  [value]
  (if (symbol? value) @(requiring-resolve value) value))


(defn system
  "A Bel system map. `:authority :impl` may be a symbol naming a constructor fn, which is called with no arguments."
  {:malli/schema [:=> [:cat [:map [:stores :map]]] :map]}
  [options]
  (cond-> options
    (get-in options [:authority :impl])
    (update-in [:authority :impl] #(let [impl (resolve-symbol %)] (if (fn? impl) (impl) impl)))))


(defmethod ig/init-key :bel/system [_ options]
  (system options))


(defn- halt-all!
  "Halt every key in reverse dependency order; return the first failure with later ones suppressed."
  {:malli/schema [:=> [:cat :map] [:maybe [:fn #(instance? Throwable %)]]]}
  [system]
  (let [failure (volatile! nil)]
    (ig/reverse-run! system (keys system)
                     (fn [k v]
                       (try (ig/halt-key! k v)
                            (catch Throwable cause
                              (let [error (ex-info (str "Error halting " k) {:reason ::halt :key k} cause)]
                                (if-let [first-failure @failure]
                                  (.addSuppressed ^Throwable first-failure error)
                                  (vreset! failure error)))))))
    @failure))


(defn stop!
  {:malli/schema [:=> [:cat :map] :nil]}
  [system]
  (some-> (halt-all! system) throw)
  nil)


(defn start!
  "Initialize `config`; a failed start halts what it already started before rethrowing."
  {:malli/schema [:=> [:cat [:map-of :any :any]] :map]}
  [config]
  (ig/load-namespaces config)
  (try
    (ig/init config)
    (catch ExceptionInfo error
      (when-let [cleanup (some-> (:system (ex-data error)) halt-all!)]
        (.addSuppressed error cleanup))
      (throw error))))
