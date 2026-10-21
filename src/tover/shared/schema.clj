(ns tover.shared.schema
  (:require [clojure.string :as str]))


(def Scope
  [:map [:workspace-id :uuid]])


(def Text
  [:and [:string {:min 1}]
   [:fn {:error/message "must not be blank"} (complement str/blank?)]])


(def Hiccup
  [:and [:vector {:min 1} :any] [:fn #(keyword? (first %))]])


(def Headers
  [:map-of :string [:or :string [:sequential :string]]])


(def Request
  [:map [:request-method {:optional true} :keyword] [:headers {:optional true} Headers]])


(def Response
  [:map [:status [:int {:min 100 :max 599}]]
   [:headers {:optional true} Headers] [:body {:optional true} :any]])


(def Handler
  [:=> [:cat Request] [:maybe Response]])
