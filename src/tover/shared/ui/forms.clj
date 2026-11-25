(ns tover.shared.ui.forms
  (:require
    [bel.core :as bel]
    [tover.shared.i18n :as i18n]
    [tover.shared.schema :as schema]))


(defn csrf-field
  {:malli/schema [:=> [:cat :map] [:vector :any]]} [request]
  [:input {:type "hidden" :name "__anti-forgery-token" :value (force (:anti-forgery-token request))}])


(def PasswordFieldProps
  [:map [:label :string] [:autocomplete [:enum "current-password" "new-password"]]])


(defn password-field
  "A password input that starts hidden, with a control that shows it (identity.js). The control is outside the
  label, so it never becomes part of the field's name."
  {:malli/schema [:=> [:cat PasswordFieldProps] schema/Hiccup]}
  [{:keys [label autocomplete]}]
  [:div.password
   [:label {:for "password"} label]
   [:span.password-field
    [:input#password {:name "password" :type "password" :required true :autocomplete autocomplete}]
    [:button.password-toggle {:type "button" :aria-pressed "false" :aria-controls "password" :aria-label (i18n/t "Show password")}
     [:svg {:viewBox "0 0 24 24" :aria-hidden true}
      [:path {:d "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z"}]
      [:circle {:cx 12 :cy 12 :r 3}]
      [:path.password-shown {:d "M4 4l16 16"}]]]]])


(defn field
  {:malli/schema [:=> [:cat :map :string :string :string] [:vector :any]]} [request name label type]
  [:label label
   [:input {:name name :type type :required true :value (get-in request [:params (keyword name)] "")}]])


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
