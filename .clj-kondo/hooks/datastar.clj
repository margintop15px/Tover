(ns hooks.datastar
  (:require [clj-kondo.hooks-api :as api]))

(defn ->expr [{:keys [node]}]
  (letfn [(unquotes [node]
            (if (#{:unquote :unquote-splicing} (api/tag node))
              (:children node)
              (mapcat unquotes (:children node))))]
    {:node (with-meta
             (api/list-node
               [(api/token-node 'clojure.core/str)
                (api/vector-node (mapcat unquotes (rest (:children node))))])
             (meta node))}))
