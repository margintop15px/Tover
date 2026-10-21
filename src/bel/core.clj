(ns bel.core
  (:require
    [bel.expressions :as expressions]
    [clojure.java.classpath :as classpath]
    [clojure.string :as str]
    [clojure.tools.namespace.find :as ns-find]))


(defonce registry
  (atom {:pages {} :views {} :actions {} :streams {}}))


(def Scan
  [:or
   [:sequential :symbol]
   [:map
    [:scan-ns [:sequential :symbol]]
    [:scan-exclude-ns {:optional true} [:sequential :symbol]]]])


(def PatchMode
  [:enum :outer :inner :replace :prepend :append :before :after :remove])


(def Instruction
  [:multi {:dispatch :type}
   [:view [:map
           [:type [:= :view]]
           [:id :qualified-keyword]
           [:input {:optional true} :map]
           [:selector {:optional true} :string]
           [:patch-mode {:optional true} PatchMode]]]
   [:elements [:map
               [:type [:= :elements]]
               [:html :any]
               [:selector {:optional true} :string]
               [:patch-mode {:optional true} PatchMode]]]
   [:signals [:map
              [:type [:= :signals]]
              [:data :map]
              [:schema {:optional true} :any]]]
   [:page [:map
           [:type [:= :page]]
           [:id :qualified-keyword]
           [:input {:optional true} :map]
           [:path-params {:optional true} :map]
           [:reload? {:optional true} :boolean]]]])


(def Result
  [:and
   [:or Instruction [:vector Instruction]]
   [:fn {:error/message "Page instructions must be returned alone"}
    #(not
       (and (vector? %)
            (some (fn [instruction] (= :page (:type instruction))) %)))]])


(def RingResponse
  [:map
   [:status :int]
   [:headers {:optional true} :map]
   [:body {:optional true} :any]
   [:session {:optional true} [:maybe :map]]
   [:session/key {:optional true} [:maybe :string]]
   [:cookies {:optional true} :map]])


(def ActionResult
  [:multi {:dispatch #(if (and (map? %) (not (:type %))) :response :instructions)}
   [:response RingResponse]
   [:instructions Result]])


(def PageResult
  [:map
   [:type [:= :elements]]
   [:html :any]])


(defn input-query
  {:malli/schema [:=> [:cat :map :map] :map]}
  [_context input]
  input)


(defn spread-children?
  {:malli/schema [:=> [:cat [:maybe [:sequential [:sequential :any]]]] :boolean]}
  [arglists]
  (boolean (some #(some #{'&} %) arglists)))


(defn attach-definition!
  {:malli/schema
   [:=> [:cat [:fn var?] :keyword :qualified-keyword :map]
    [:fn var?]]}
  [definition-var kind id opts]
  (let [input-schema (or (:schema opts) [:map])
        page?        (= :bel/page kind)
        children?    (and page? (spread-children? (:arglists (meta definition-var))))
        output       (or (:output opts)
                         (case kind
                           :bel/page PageResult
                           :bel/action ActionResult
                           Result))
        request?     (contains? #{:bel/action :bel/stream} kind)]
    (alter-meta! definition-var
                 #(-> %
                      (dissoc :bel/page :bel/view :bel/action :bel/stream
                              :bel/route :bel/input :bel/instructions?
                              :bel/topic-schema :bel/data-schema :bel/result-schema
                              :bel/parent :bel/children? :bel/title :bel/document)
                      (assoc :bel/kind kind
                             :bel/id id
                             :bel/query (or (:query opts) (when-not request? input-query))
                             :bel/schema input-schema
                             :bel/output output
                             :bel/selector (:selector opts)
                             :bel/patch-mode (:patch-mode opts)
                             :bel/route-data (:route-data opts)
                             :malli/schema [:=> (cond
                                                  request? [:cat :map input-schema]
                                                  children? [:cat :any [:* :any]]
                                                  :else [:cat :any]) output])
                      (cond-> page? (assoc :bel/parent (:parent opts)
                                           :bel/children? children?
                                           :bel/route (:route opts)
                                           :bel/title (:title opts)
                                           :bel/document (:document opts)))))
    definition-var))


(defn declaration-form
  {:malli/schema
   [:=> [:cat :keyword :symbol [:sequential :any] :symbol]
    :any]}
  [kind name args namespace-symbol]
  (let [[docstring args]  (if (string? (first args))
                            [(first args) (rest args)]
                            [nil args])
        ;; optional opts map is the first argument if present, otherwise an empty map
        [opts args]       (if (map? (first args))
                            [(first args) (rest args)]
                            [{} args])
        [bindings & body] args
        id                (keyword (str namespace-symbol) (str name))]
    `(do
       (defn ~name
         ~@(when docstring [docstring])
         ~bindings
         ~@body)
       (attach-definition! (var ~name) ~kind ~id ~opts))))


(defmacro defpage
  "Declare a GET page at :route; optional :parent names a page with a real child slot."
  [name & args]
  (declaration-form :bel/page name args (ns-name *ns*)))


(defmacro defview
  "Declare a pure renderer returning Result. :query receives [ctx input]; the body receives [data]."
  [name & args]
  (declaration-form :bel/view name args (ns-name *ns*)))


(defmacro defaction
  "Declare an action receiving [ctx input] and returning instructions or a Ring response."
  [name & args]
  (declaration-form :bel/action name args (ns-name *ns*)))


(defmacro defstream
  "Declare an event interpreter receiving [ctx payload]. Its qualified declaration ID is its topic."
  [name & args]
  (declaration-form :bel/stream name args (ns-name *ns*)))


(defn scan-config
  {:malli/schema
   [:=> [:cat Scan] :map]}
  [scan]
  (if (map? scan)
    {:scan-ns         (vec (:scan-ns scan))
     :scan-exclude-ns (vec (:scan-exclude-ns scan))}
    {:scan-ns         (vec scan)
     :scan-exclude-ns []}))


(defn- ns-under?
  {:malli/schema
   [:=> [:cat :symbol :symbol] :boolean]}
  [namespace-symbol prefix]
  (let [namespace-name (str namespace-symbol)
        prefix-name    (str prefix)]
    (or (= namespace-name prefix-name)
        (str/starts-with? namespace-name (str prefix-name ".")))))


(defn- excluded-ns?
  {:malli/schema
   [:=> [:cat :symbol [:sequential :symbol]] :boolean]}
  [namespace-symbol exclusions]
  (boolean
    (or (re-find #"(?:^|\.)(?:dev|test)(?:\.|$)|-test(?:\.|$)" (str namespace-symbol))
        (some #(ns-under? namespace-symbol %) exclusions))))


(defn find-namespaces
  "Find application namespaces under a prefix in classpath directories and JARs."
  {:malli/schema
   [:function
    [:=> [:cat :symbol] [:vector :symbol]]
    [:=> [:cat :symbol [:sequential :symbol]] [:vector :symbol]]]}
  ([prefix]
   (find-namespaces prefix []))

  ([prefix exclusions]
   ;; A test loader can add URLs without exposing the JVM's application classpath.
   (let [paths (distinct (concat (classpath/classpath) (classpath/system-classpath)))]
     (into []
           (comp (filter #(ns-under? % prefix))
                 (remove #(excluded-ns? % exclusions))
                 (distinct))
           (ns-find/find-namespaces paths)))))


(defn require-namespaces!
  "Require namespaces found by the configured prefixes. Requiring has no registration side effects."
  {:malli/schema
   [:=> [:cat Scan] [:vector :symbol]]}
  [scan]
  (let [{:keys [scan-ns scan-exclude-ns]} (scan-config scan)
        namespaces (into [] (mapcat #(find-namespaces % scan-exclude-ns)) scan-ns)]
    (doseq [namespace-symbol namespaces]
      (require namespace-symbol))
    namespaces))


(defn discover-vars
  "Collect declaration Vars from the loaded application namespaces."
  {:malli/schema
   [:=> [:cat [:sequential :symbol]] [:vector [:fn var?]]]}
  [namespaces]
  (into []
        (comp (mapcat #(vals (ns-publics %)))
              (filter #(contains? #{:bel/page :bel/view :bel/action :bel/stream} (:bel/kind (meta %)))))
        namespaces))


(defn- page-chain*
  {:malli/schema
   [:=> [:cat [:fn ifn?] :qualified-keyword]
    [:vector :map]]}
  [lookup id]
  (loop [id    id
         seen  #{}
         pages ()]
    (when (contains? seen id)
      (throw (ex-info "Cyclic page hierarchy" {:type :validation :id id})))

    (let [{:bel/keys [parent] :as page} (lookup id)]
      (when-not page
        (throw (ex-info "Undeclared parent page" {:type :validation :id id})))

      (when (and (seq pages) (not (:bel/children? page)))
        (throw (ex-info "Parent page must accept children" {:type :validation :id id})))

      (if parent
        (do
          (when-not (qualified-keyword? parent)
            (throw (ex-info "Parent page must have a qualified ID" {:type :validation :id id :parent parent})))
          (recur parent (conj seen id) (conj pages page)))
        (vec (conj pages page))))))


(defn build-registry!
  "Replace Bel's registry with discovered Vars; removed declarations do not linger."
  {:malli/schema [:=> [:cat [:sequential :symbol]] :map]}
  [namespaces]
  (let [discovered (reduce (fn [result definition-var]
                             (let [{:bel/keys [id kind]} (meta definition-var)
                                   section               (case kind
                                                           :bel/page :pages
                                                           :bel/view :views
                                                           :bel/action :actions
                                                           :bel/stream :streams)]
                               (assoc-in result [section id] definition-var)))
                           {:pages {} :views {} :actions {} :streams {}}
                           (discover-vars namespaces))
        pages      (:pages discovered)]
    (doseq [[id definition-var] pages]
      (page-chain* #(some-> (get pages %) meta) id)
      (let [{:bel/keys [route title document route-data]} (meta definition-var)]
        (when-not (and (or (nil? route) (and (string? route) (str/starts-with? route "/")))
                       (or (nil? title) (string? title))
                       (or (nil? document) (ifn? document))
                       (or (nil? route-data) (map? route-data)))
          (throw (ex-info "Invalid page route metadata" {:type :validation :id id})))))
    (reset! registry discovered)))


(defn init!
  "Load declarations by namespace prefix and rebuild Bel's internal registry."
  {:malli/schema [:=> [:cat Scan] :map]}
  [scan]
  (-> (require-namespaces! scan)
      (build-registry!)))


(defn- definition
  {:malli/schema [:=> [:cat :keyword :qualified-keyword] [:maybe :map]]}
  [section id]
  (when-let [definition-var (get-in @registry [section id])]
    (assoc (meta definition-var) :bel/fn definition-var)))


(defn get-page
  "Read a declaration's current Var metadata, including REPL redefinitions."
  {:malli/schema [:=> [:cat :qualified-keyword] [:maybe :map]]}
  [id]
  (definition :pages id))


(defn page-chain
  "Read and validate the current page hierarchy, ordered from root to leaf."
  {:malli/schema [:=> [:cat :qualified-keyword] [:vector :map]]}
  [id]
  (page-chain* get-page id))


(defn get-view
  {:malli/schema [:=> [:cat :qualified-keyword] [:maybe :map]]}
  [id]
  (definition :views id))


(defn get-action
  {:malli/schema [:=> [:cat :qualified-keyword] [:maybe :map]]}
  [id]
  (definition :actions id))


(defn get-stream
  "Look up a stream by its qualified declaration ID."
  {:malli/schema [:=> [:cat :qualified-keyword] [:maybe :map]]}
  [topic]
  (definition :streams topic))


(defn action-url
  {:malli/schema [:=> [:cat :qualified-keyword] :string]}
  [id]
  (str "/action/" (namespace id) "/" (name id)))


(defn view-url
  {:malli/schema [:=> [:cat :qualified-keyword] :string]}
  [id]
  (str "/view/" (namespace id) "/" (name id)))


(defn- unquote-form?
  {:malli/schema [:=> [:cat :any] :boolean]}
  [value]
  (and (sequential? value) (= 'clojure.core/unquote (first value))))


(defn- unquote-value
  {:malli/schema [:=> [:cat [:sequential :any]] :any]}
  [value]
  (second value))


(defmethod expressions/expand-form (symbol "@action")
  [[_ action-id & [payload options]]]
  (let [id-expr      (if (unquote-form? action-id)
                       (list 'clojure.core/unquote `(action-url ~(unquote-value action-id)))
                       (action-url action-id))
        payload-form (or payload {})]
    (list '@post id-expr
          (if (unquote-form? options)
            (list 'js* "{...~{}, payload: ~{}}" options payload-form)
            (assoc (or options {}) :payload payload-form)))))


(defmethod expressions/expand-form (symbol "@view")
  [[_ view-id & [params options]]]
  (let [id-expr (if (unquote-form? view-id)
                  (list 'clojure.core/unquote `(view-url ~(unquote-value view-id)))
                  (view-url view-id))
        payload (or params {})]
    (list '@post id-expr
          (if (unquote-form? options)
            (list 'js* "{...~{}, payload: ~{}}" options payload)
            (assoc (or options {}) :payload payload)))))
