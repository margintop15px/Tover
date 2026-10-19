(ns instrumentation
  (:require
    [clojure.string :as str]
    [malli.core :as m]
    [malli.dev :as dev]
    [malli.dev.pretty :as pretty]
    [malli.instrument :as instrument]))


(defn function-vars
  "Application function vars, including private helpers."
  {:malli/schema [:=> [:cat] [:vector [:fn var?]]]}
  []
  (vec (for [namespace (all-ns)
             :when (or (= 'user (ns-name namespace))
                       (str/starts-with? (str (ns-name namespace)) "tover.")
                       (contains? #{'bel.core 'bel.handler 'bel.realtime 'bel.session 'bel.validation} (ns-name namespace)))
             [_ var] (ns-interns namespace)
             :when (and (bound? var) (fn? @var) (not (:macro (meta var))) (not (:test (meta var))))]
         var)))


(defn prune-function-schemas!
  "Preserve live schemas when tools.namespace unloads or deletes function Vars."
  {:malli/schema [:=> [:cat] :nil]}
  []
  (let [schemas (or (m/function-schemas) {})
        live (into {}
                   (keep (fn [[namespace entries]]
                           (when-let [loaded (find-ns namespace)]
                             [namespace (select-keys entries (keys (ns-interns loaded)))])))
                   schemas)]
    (when (not= schemas live)
      ;; Malli's dev stop resolves every registered namespace before applying filters.
      (m/-deregister-function-schemas! :clj)
      (with-out-str (dev/stop!))
      (doseq [[namespace entries] live
              [name definition] entries]
        (m/-register-function-schema! namespace name (:schema definition)
                                      (dissoc definition :schema :ns :name)))))
  nil)


(defn start!
  {:malli/schema [:=> [:cat] :int]}
  []
  (prune-function-schemas!)
  (let [vars (function-vars)
        missing (filterv #(not (:malli/schema (meta %))) vars)
        options {:filters [(instrument/-filter-var (set vars))] :report (pretty/thrower)}]
    (when (seq missing)
      (throw (ex-info "Application functions are missing Malli schemas." {:vars missing})))
    (dev/start! options)
    ;; collect! scans public vars; use Malli's collector for private helpers too.
    (doseq [var vars] (instrument/-collect! var))
    (count (instrument/instrument! options))))


(defn stop!
  {:malli/schema [:=> [:cat] :nil]}
  []
  (prune-function-schemas!)
  (dev/stop!)
  nil)
