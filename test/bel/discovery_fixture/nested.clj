(ns bel.discovery-fixture.nested
  (:require
    [bel.core :refer [defview]]))


(defview discovered
  {:schema [:map]}
  [_data]
  {:type :elements :html [:div "Discovered"]})
