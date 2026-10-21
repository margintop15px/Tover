(ns tover.shared.ui.forms
  (:require
    [bel.core :as bel]))


(defn csrf-field
  {:malli/schema [:=> [:cat :map] [:vector :any]]} [request]
  [:input {:type "hidden" :name "__anti-forgery-token" :value (force (:anti-forgery-token request))}])


(defn field
  {:malli/schema [:=> [:cat :map :string :string :string] [:vector :any]]} [request name label type]
  [:label label
   [:input (cond-> {:name name :type type :required true}
             (not= type "password") (assoc :value (get-in request [:params (keyword name)] "")))]])


(defn form
  {:malli/schema
   [:=> [:cat :map [:or :string :qualified-keyword [:map [:action :string]]]
         :string [:* :any]]
    [:vector :any]]}
  [request target label & fields]
  (let [action   (cond
                   (keyword? target) (bel/action-url target)
                   (map? target) (:action target)
                   :else target)
        enhance? (or (keyword? target) (map? target))]
    (into [:form (cond-> {:method "post" :action action}
                   enhance?
                   (assoc :data-on:submit (str "@post('" action "', {contentType: 'form', retry: 'never', retryMaxCount: 0, requestCancellation: 'cleanup'})")))
           (csrf-field request)
           [:input {:type "hidden" :name "_tover-context" :value (str (get-in request [:tover/identity :auth-version]))}]]
          (concat fields [[:button {:type "submit"} label]]))))
