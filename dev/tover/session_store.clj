(ns tover.session-store
  (:require
    [tover.auth.sessions :as sessions]))


(defn memory-store
  {:malli/schema [:=> [:cat] :map]} []
  (let [rows (atom {})]
    (sessions/->Store
      (fn [id f]
        (locking rows
          (let [[row result] (f (get @rows id))]
            (swap! rows #(if row (assoc % id row) (dissoc % id)))
            result))))))
