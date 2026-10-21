(ns bel.fixture
  (:require
    [bel.core :refer [defaction defpage defview]]))


(defn query-document
  {:malli/schema [:=> [:cat :map :map] [:map [:message :string]]]}
  [ctx input]
  {:message (str (:greeting ctx) (:query input))})


(defpage document
  {:route    "/"
   :document (fn [_props & children] [:html (into [:body] children)])
   :schema   [:map [:query {:optional true} :string]]
   :query    query-document}
  [{:keys [message]}]
  {:type :elements :html [:main [:h1 message]]})


(defview row
  {:schema [:map [:quantity :int]]}
  [{:keys [quantity]}]
  {:type :elements :html [:div#row quantity]})


(defaction increment
  {:schema [:map [:quantity :int]]}
  [_ctx {:keys [quantity]}]
  [{:type :view :id ::row :input {:quantity (inc quantity)}}
   {:type :signals :schema [:map [:revision :int]] :data {:revision quantity}}])
