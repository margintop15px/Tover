(ns kaocha-hooks
  (:require
    [bel.core :as bel]
    [instrumentation :as instrumentation]
    [tover.app]))


(defn start-instrumentation
  {:malli/schema [:=> [:cat :map] :map]}
  [test-plan]
  (bel/init! {:scan-ns ['tover]})
  (instrumentation/start!)
  test-plan)


(defn stop-instrumentation
  {:malli/schema [:=> [:cat :map] :map]}
  [test-result]
  (instrumentation/stop!)
  test-result)
