(ns bel.discovery-fixture.excluded
  (:require
    [bel.core :refer [defpage]]))


(defpage excluded
  {:schema [:map]}
  [_data]
  {:type :elements :html [:html [:body "Excluded"]]})
