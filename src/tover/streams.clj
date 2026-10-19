(ns tover.streams
  (:require
    [bel.core :refer [defstream]]))


(defstream stock
  [_ctx _event]
  {:type   :signals
   :schema [:map [:stockChanged :boolean]]
   :data   {:stockChanged true}})
