(ns tover.inventory.actors
  (:require
    [bel-postgres.jdbc :as db]
    [bel.runtime :as runtime]
    [charred.api :as json]
    [clojure.string :as str]
    [com.brunobonacci.mulog :as mu]
    [tover.shared.i18n :as i18n]))


(def actor-fields
  {:actor :actor-label :created-by :created-by-label :proposed-by :proposed-by-label})


(defn labels
  "Display labels of workspace members, read from the directory store as the signed-in user."
  {:malli/schema [:=> [:cat :map [:sequential :map]] [:map-of :string :string]]}
  [{:keys [user-id workspace-id] :as context} records]
  (let [ids (vec (distinct (keep #(some-> % str parse-uuid) (mapcat #(keep % (keys actor-fields)) records))))]
    (if (and user-id workspace-id (get-in context [:bel/system :stores :directory]) (seq ids))
      (try
        (runtime/with-store context :directory :read-committed
          (fn [{:keys [tx]}]
            (db/execute-one! tx {:select [[[:set_config "request.jwt.claim.sub" [:cast user-id :text] true]]]})
            (json/read-json (str (:labels (db/execute-one! tx {:select [[[:app_private.bel_actor_labels workspace-id
                                                                          [:cast [:array ids] [:raw "uuid[]"]]]
                                                                         :labels]]})))
                            :key-fn str)))
        (catch Exception error
          (mu/log ::labels-unavailable :error-type (.getName (class error)))
          {}))
      {})))


(defmulti principal-label
  "Label of a non-user actor `<kind>:<id>`, such as `connector:<connection id>`; nil when unknown.
  The owning domain implements its kind, so this namespace depends on none of them."
  (fn [_context kind _id] kind))


(defmethod principal-label :default [_ _ _] nil)


(defn- principal
  {:malli/schema [:=> [:cat :map :any] [:maybe :string]]}
  [context id]
  (let [[kind id] (str/split (str id) #":" 2)]
    (when id
      (try (principal-label context kind id)
           (catch Exception error
             (mu/log ::principal-label-unavailable :kind kind :error-type (.getName (class error)))
             nil)))))


(defn enrich
  "Resolve presentation labels without changing immutable actor identifiers."
  {:malli/schema [:=> [:cat :map [:sequential :map]] [:vector :map]]}
  [context records]
  (let [names (labels context records)]
    (mapv (fn [record]
            (reduce-kv (fn [record field label-field]
                         (if-let [id (get record field)]
                           (assoc record label-field (or (get names (str id)) (principal context id)
                                                         (i18n/t "Unavailable user")))
                           record)) record actor-fields)) records)))
