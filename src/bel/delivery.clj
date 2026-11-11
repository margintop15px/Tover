(ns bel.delivery
  "Deliver committed source records without rerunning business decisions."
  (:require
    [bel.journal :as journal]
    [com.brunobonacci.mulog :as mu]
    [integrant.core :as ig])
  (:import
    [java.lang AutoCloseable]
    [java.util.concurrent Executors TimeUnit]))


(defmulti pending-records (fn [context] (get-in context [:bel/runtime :adapter])))
(defmulti read-record (fn [context _record] (get-in context [:bel/runtime :adapter])))
(defmulti acknowledge! (fn [context _delivery] (get-in context [:bel/runtime :adapter])))


(defn pending
  "Read batches for inspection. The worker isolates decoding failures per source record."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [context]
  (mapv #(read-record context %) (pending-records context)))


(defn deliver-one!
  {:malli/schema [:=> [:cat :map :map] :nil]}
  [context delivery]
  (journal/deliver! context delivery)
  (acknowledge! context delivery))


(defn deliver-pending!
  {:malli/schema [:=> [:cat :map] :map]}
  [context]
  (reduce (fn [counts record]
            (try
              (deliver-one! context (read-record context record))
              (update counts :delivered inc)
              (catch Exception error
                (mu/log ::delivery-failed :source (:source record)
                        :error-type (or (:type (ex-data error)) (.getName (class error))))
                (update counts :failed inc))))
          {:delivered 0 :failed 0} (pending-records context)))


(defn start!
  {:malli/schema [:=> [:cat :map] :any]}
  [context]
  (let [executor (Executors/newSingleThreadScheduledExecutor)]
    (.scheduleWithFixedDelay executor
                             ^Runnable (fn []
                                         (try (deliver-pending! context)
                                              (catch Exception error
                                                (mu/log ::delivery-scan-failed :error-type (.getName (class error))))))
                             0 1 TimeUnit/SECONDS)
    (reify AutoCloseable
      (close [_]
        (.shutdownNow executor)
        (when-not (.awaitTermination executor 30 TimeUnit/SECONDS)
          (throw (ex-info "Journal delivery has not stopped; keep its resources open" {:type :timeout})))))))


(defmethod ig/init-key ::worker
  [_ context]
  (start! context))


(defmethod ig/halt-key! ::worker
  [_ worker]
  (.close ^AutoCloseable worker))
