(ns bel.delivery
  "Deliver committed source records without rerunning business decisions."
  (:require
    [bel.journal :as journal]
    [bel.runtime :as runtime]
    [bel.store :as store]
    [com.brunobonacci.mulog :as mu]
    [integrant.core :as ig])
  (:import
    [java.lang AutoCloseable]
    [java.util.concurrent Executors TimeUnit]))


(defn- outboxes
  {:malli/schema [:=> [:cat :map] [:sequential [:tuple :keyword :any]]]}
  [system]
  (filter #(satisfies? store/Outbox (val %)) (:stores system)))


(defn pending
  "Read batches for inspection. The worker isolates decoding failures per source record."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [system]
  (into [] (comp (mapcat #(store/-pending (val %))) (keep :delivery)) (outboxes system)))


(defn deliver-one!
  {:malli/schema [:=> [:cat :map :keyword :map] :nil]}
  [system key delivery]
  (journal/deliver! {:bel/system system} delivery)
  (store/-ack! (get-in system [:stores key]) delivery))


(defn deliver-pending!
  {:malli/schema [:=> [:cat :map] :map]}
  [system]
  (reduce (fn [counts [key outbox]]
            (reduce (fn [counts {:keys [source delivery error]}]
                      (try
                        (when error (throw error))
                        (deliver-one! system key delivery)
                        (update counts :delivered inc)
                        (catch Exception error
                          (mu/log ::delivery-failed :source source
                                  :error-type (or (:type (ex-data error)) (.getName (class error))))
                          (update counts :failed inc))))
                    counts (store/-pending outbox)))
          {:delivered 0 :failed 0} (outboxes system)))


(defn start!
  {:malli/schema [:=> [:cat :map] :any]}
  [system]
  (let [executor (Executors/newSingleThreadScheduledExecutor)]
    (.scheduleWithFixedDelay executor
                             ^Runnable (fn []
                                         (try (deliver-pending! system)
                                              (catch Exception error
                                                (mu/log ::delivery-scan-failed :error-type (.getName (class error)))))
                                         (try (runtime/run-processors! system)
                                              (catch Exception error
                                                (mu/log ::processor-scan-failed :error-type (.getName (class error))))))
                             0 1 TimeUnit/SECONDS)
    (reify AutoCloseable
      (close [_]
        (.shutdownNow executor)
        (when-not (.awaitTermination executor 30 TimeUnit/SECONDS)
          (throw (ex-info "Journal delivery has not stopped; keep its resources open" {:type :timeout})))))))


(defmethod ig/init-key ::worker
  [_ system]
  (start! system))


(defmethod ig/halt-key! ::worker
  [_ worker]
  (.close ^AutoCloseable worker))
