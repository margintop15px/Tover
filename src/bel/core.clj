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
              [:schema {:optional true} :any]]]])


(def Result
  [:or Instruction [:vector Instruction]])


(defn input-query
  {:malli/schema [:=> [:cat :map :map] :map]}
  [_context input]
  input)


(defn attach-definition!
  {:malli/schema
   [:=> [:cat [:fn var?] :keyword :qualified-keyword :map]
    [:fn var?]]}
  [definition-var kind id opts]
  (let [input-schema (or (:schema opts) [:map])
        output (or (:output opts) Result)
        request? (contains? #{:bel/action :bel/stream} kind)]
    (alter-meta! definition-var
                 #(-> %
                      (dissoc :bel/page :bel/view :bel/action :bel/stream
                              :bel/route :bel/input :bel/instructions?
                              :bel/topic-schema :bel/data-schema :bel/result-schema)
                      (assoc :bel/kind kind
                             :bel/id id
                             :bel/query (or (:query opts) (when-not request? input-query))
                             :bel/schema input-schema
                             :bel/output output
                             :bel/selector (:selector opts)
                             :bel/patch-mode (:patch-mode opts)
                             :malli/schema [:=> (if request? [:cat :map input-schema] [:cat :any]) output])))
    definition-var))


(defn declaration-form
  {:malli/schema
   [:=> [:cat :keyword :symbol [:sequential :any] :symbol]
    :any]}
  [kind name args namespace-symbol]
  (let [[docstring args] (if (string? (first args))
                           [(first args) (rest args)]
                           [nil args])
        ;; optional opts map is the first argument if present, otherwise an empty map
        [opts args] (if (map? (first args))
                      [(first args) (rest args)]
                      [{} args])
        [bindings & body] args
        id (keyword (str namespace-symbol) (str name))]
    `(do
       (defn ~name
         ~@(when docstring [docstring])
         ~bindings
         ~@body)
       (attach-definition! (var ~name) ~kind ~id ~opts))))


(defmacro defpage
  "Declare a document renderer returning Result. :query receives [ctx input]; the body receives [data]."
  [name & args]
  (declaration-form :bel/page name args (ns-name *ns*)))


(defmacro defview
  "Declare a pure renderer returning Result. :query receives [ctx input]; the body receives [data]."
  [name & args]
  (declaration-form :bel/view name args (ns-name *ns*)))


(defmacro defaction
  "Declare an action receiving [ctx input] and returning one or several instructions."
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
        prefix-name (str prefix)]
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


(defn build-registry!
  "Replace Bel's registry with discovered Vars; removed declarations do not linger."
  {:malli/schema [:=> [:cat [:sequential :symbol]] :map]}
  [namespaces]
  (reset! registry
          (reduce (fn [result definition-var]
                    (let [{:bel/keys [id kind]} (meta definition-var)
                          section (case kind
                                    :bel/page :pages
                                    :bel/view :views
                                    :bel/action :actions
                                    :bel/stream :streams)]
                      (assoc-in result [section id] definition-var)))
                  {:pages {} :views {} :actions {} :streams {}}
                  (discover-vars namespaces))))


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
  (let [id-expr (if (unquote-form? action-id)
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
