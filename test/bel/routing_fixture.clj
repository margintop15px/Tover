(ns bel.routing-fixture
  (:require
    [bel.core :refer [defaction defpage defview]]))


(defpage shell
  {:document (fn [{:keys [title]} & children]
               [:html [:head [:title title]] (into [:body] children)])}
  [_props & children]
  {:type :elements :html (into [:main#shell] children)})


(defn query-page
  [context input]
  (when-let [requests (:requests context)]
    (swap! requests conj (:request context)))
  (when-let [failure (:page-failure context)]
    (throw failure))
  input)


(defpage home
  {:route  "/"
   :title  "Home"
   :parent ::shell
   :query  query-page}
  [_props]
  {:type :elements :html [:section#home "Home"]})


(defpage team
  {:route      "/team"
   :title      "Team"
   :parent     ::shell
   :route-data {:required-role :manager}
   :query      query-page}
  [{:keys [q]}]
  {:type :elements :html [:section#team q]})


(defpage item
  {:route  "/items/:id"
   :title  "Item"
   :parent ::shell
   :schema [:map [:id :int] [:q {:optional true} :string]]
   :query  query-page}
  [{:keys [id q]}]
  {:type :elements :html [:section#item (str id ":" q)]})


(defpage file
  {:route  "/files/{*path}"
   :title  "File"
   :parent ::shell
   :schema [:map [:path :string]]}
  [{:keys [path]}]
  {:type :elements :html [:section#file path]})


(defaction navigate
  {:schema [:map [:q {:optional true} :string]]}
  [context input]
  (when-let [failure (:action-failure context)]
    (throw failure))
  (or (:action-result context) {:type :page :id ::team :input (select-keys input [:q])}))


(defview row
  [_props]
  {:type :elements :html [:div#row "Row"]})
