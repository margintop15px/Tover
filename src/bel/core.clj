(ns bel.core
  (:require
    [bel.expressions :as expressions]
    [bel.validation :as validation]
    [clojure.java.classpath :as classpath]
    [clojure.string :as str]
    [clojure.tools.namespace.find :as ns-find]
    [malli.core :as m]))


(defonce registry
  (atom {:pages {} :views {} :actions {} :streams {} :events {} :commands {} :models {} :read-models {} :processors {}}))


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


(def ActionResponse
  [:multi {:dispatch #(if (and (map? %) (not (:type %))) :response :instructions)}
   [:response RingResponse]
   [:instructions Result]])


(def CommandRequest
  [:map
   [:type [:= :command]]
   [:id :qualified-keyword]
   [:command-id [:string {:min 1 :max 100}]]
   [:input :map]
   [:then ActionResponse]
   ;; A view receives the rejection as `:error`; a page receives only its type name as `:problem`,
   ;; because page input becomes the URL.
   [:catch {:optional true}
    [:map
     [:type [:enum :view :page]]
     [:id :qualified-keyword]
     [:input {:optional true} :map]
     [:path-params {:optional true} :map]]]])


(def ActionResult
  [:or CommandRequest ActionResponse])


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
  (when (contains? opts :command)
    (throw (ex-info "Actions return command requests" {:type :validation :id id})))
  (doseq [key [:read-model] :when (contains? opts key)]
    (let [allowed #{:read-model :schema :output :query :selector :patch-mode :route-data :signal}]
      (when (seq (remove allowed (keys opts)))
        (throw (ex-info "Unsupported bound declaration option" {:type :validation :id id :options (keys opts)}))))
    (when-not (and (= kind :bel/view)
                   (qualified-keyword? (get opts key)))
      (throw (ex-info "Invalid declaration binding" {:type :validation :id id :option key}))))
  (when (contains? opts :signal)
    (when-not (and (= kind :bel/view) (simple-keyword? (:signal opts)))
      (throw (ex-info "A snapshot signal belongs to a view" {:type :validation :id id}))))
  (let [input-schema (or (:schema opts) [:map])
        page?        (= :bel/page kind)
        children?    (and page? (spread-children? (:arglists (meta definition-var))))
        output       (or (:output opts)
                         (case kind
                           :bel/page PageResult
                           :bel/action ActionResult
                           (if (:signal opts) :map Result)))
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
                             :bel/read-model (:read-model opts)
                             :bel/signal (:signal opts)
                             :bel/schema input-schema
                             :bel/output output
                             :bel/selector (:selector opts)
                             :bel/patch-mode (:patch-mode opts)
                             :bel/route-data (:route-data opts)
                             :bel/resnapshot-on-open? (boolean (:resnapshot-on-open? opts))
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
  "Declare a pure renderer. :signal views return a snapshot map; other views return Result."
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


(defn attach-operation!
  "Validate the small business DSL at declaration time."
  {:malli/schema [:=> [:cat [:fn var?] :keyword :qualified-keyword :map] [:fn var?]]}
  [v kind id opts]
  (let [allowed  (case kind
                   :bel/event #{:schema}
                   :bel/command #{:input :policy :emits :cofx :scope}
                   :bel/read-model #{:input :output :policy :cofx :scope})
        required (disj allowed :cofx :scope)
        bindings (:cofx opts [])]
    (when (or (seq (remove allowed (keys opts)))
              (seq (remove #(contains? opts %) required)))
      (throw (ex-info "Unsupported or missing declaration option" {:type :validation :id id :options (keys opts)})))

    (when-not (= kind :bel/event)
      (validation/check! :qualified-keyword (:policy opts))
      (when (contains? opts :scope)
        (validation/check! [:enum :user :session :workspace] (:scope opts)))
      (when-not (and (vector? bindings)
                     (every? #(and (vector? %) (<= 2 (count %) 3)
                                   (keyword? (first %)) (qualified-keyword? (second %))
                                   (or (= 2 (count %)) (fn? (nth % 2)))) bindings)
                     (= (count bindings) (count (distinct (map first bindings))))
                     (not-any? #{:scope :actor :command-id :now} (map first bindings)))
        (throw (ex-info "Invalid coeffect bindings" {:type :validation :id id}))))

    (when (= kind :bel/command)
      (validation/check! [:set :qualified-keyword] (:emits opts)))

    (doseq [key [:input :output :schema] :when (contains? opts key)]
      (m/schema (get opts key)))
    (alter-meta! v assoc :bel/kind kind :bel/id id :bel/options opts)
    (when-not (= kind :bel/event)
      (alter-meta! v assoc :malli/schema
                   [:=> [:cat :map (:input opts)]
                    (if (= kind :bel/command) :map (:output opts))]))
    v))


(defmacro defevent
  [name opts]
  `(do
     (def ~name ~opts)
     (attach-operation! (var ~name) :bel/event ~(keyword (str (ns-name *ns*)) (str name)) ~opts)))


(defn attach-model!
  "Declare scalar entity fields, references and event-to-field mappings."
  {:malli/schema [:=> [:cat [:fn var?] :qualified-keyword :map] [:fn var?]]}
  [v id {:keys [schema policy events store] id-key :id :as opts}]
  (when (seq (remove #{:id :store :schema :policy :events} (keys opts)))
    (throw (ex-info "Unsupported model option" {:type :validation :id id})))

  (validation/check! :keyword store)
  (validation/check! :qualified-keyword policy)

  (let [schema (m/schema schema)
        fields (when (= :map (m/type schema))
                 (into {} (map (fn [[key props schema]] [key (assoc props :schema schema)])) (m/children schema)))]
    (when-not (and (seq fields) (contains? fields id-key)
                   (contains? #{:string :uuid} (m/type (get-in fields [id-key :schema])))
                   (not (get-in fields [id-key :optional])))
      (throw (ex-info "Models need a map schema and a required string or UUID identity" {:type :validation :id id})))

    (doseq [[key {:keys [schema bel/ref]}] fields]
      (when-not (and (keyword? key)
                     (or (contains? #{:string :uuid :time/instant :int :boolean :double} (m/type schema))
                         ;; A closed set of strings is still a string value to every adapter.
                         (and (= :enum (m/type schema)) (every? string? (m/children schema))))
                     (or (nil? ref) (and (qualified-keyword? ref) (contains? #{:string :uuid} (m/type schema)) (not= key id-key))))
        (throw (ex-info "Model fields must be scalar; references must be string or UUID identities" {:type :validation :field key}))))

    (validation/check! [:map-of :qualified-keyword [:or [:map [:set [:vector {:min 1} :keyword]]]
                                                    [:map [:create [:vector {:min 1} :keyword]]]
                                                    [:map [:delete [:= true]]]]]
                       (or events {}))

    (doseq [[event mapping] events]
      (let [keys (or (:set mapping) (:create mapping) [])]
        (when-not (and (contains? #{#{:set} #{:create} #{:delete}} (set (clojure.core/keys mapping)))
                       (= (count keys) (count (distinct keys)))
                       (not-any? #{id-key} keys) (every? fields keys))
          (throw (ex-info "Mappings replace declared fields and preserve identity" {:type :validation :event event})))))

    (let [options (assoc opts :model-id id :fields fields :events (or events {}))]
      (alter-meta! v assoc
                   :bel/kind :bel/model
                   :bel/id id
                   :bel/options options)))
  v)


(defmacro defmodel
  [name opts]
  `(do
     (def ~name ~opts)
     (attach-model! (var ~name) ~(keyword (str (ns-name *ns*)) (str name)) ~opts)))


(defmacro defcommand
  [name opts bindings & body]
  `(do
     (defn ~name ~bindings ~@body)
     (attach-operation! (var ~name) :bel/command ~(keyword (str (ns-name *ns*)) (str name)) ~opts)))


(def CommandRef
  [:map
   [:type [:= :command]]
   [:id :qualified-keyword]
   [:input :map]])


(def EffectDescriptor
  [:map
   [:id :qualified-keyword]
   [:input :map]
   [:then CommandRef]
   [:catch CommandRef]])


(def ProcessorRequest
  [:map
   [:type [:= :command]]
   [:id :qualified-keyword]
   [:scope [:map-of [:enum :workspace-id :user-id :session-id] :uuid]]
   [:actor :uuid]
   [:input :map]
   ;; (error) -> the request to run instead of a failed one, or nil to leave it to the next round.
   [:catch {:optional true} fn?]])


(defn attach-processor!
  "Declare a todo-list automation: each item of the `:todo` coeffect becomes command requests."
  {:malli/schema [:=> [:cat [:fn var?] :qualified-keyword :map] [:fn var?]]}
  [v id opts]
  (when (or (seq (remove #{:todo} (keys opts))) (not (contains? opts :todo)))
    (throw (ex-info "Unsupported or missing processor option" {:type :validation :id id :options (keys opts)})))
  (validation/check! :qualified-keyword (:todo opts))
  (alter-meta! v assoc :bel/kind :bel/processor :bel/id id :bel/options opts)
  v)


(defmacro defprocessor
  [name opts bindings & body]
  `(do
     (defn ~name ~bindings ~@body)
     (attach-processor! (var ~name) ~(keyword (str (ns-name *ns*)) (str name)) ~opts)))


(defmacro defreadmodel
  [name opts bindings & body]
  `(do
     (defn ~name ~bindings ~@body)
     (attach-operation! (var ~name) :bel/read-model ~(keyword (str (ns-name *ns*)) (str name)) ~opts)))


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
              (filter #(contains? #{:bel/page :bel/view :bel/action :bel/stream
                                    :bel/event :bel/command :bel/model :bel/read-model :bel/processor}
                                  (:bel/kind (meta %)))))
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
                                                           :bel/stream :streams
                                                           :bel/event :events
                                                           :bel/command :commands
                                                           :bel/model :models
                                                           :bel/read-model :read-models
                                                           :bel/processor :processors)]
                               (assoc-in result [section id] definition-var)))
                           {:pages {} :views {} :actions {} :streams {} :events {} :commands {} :models {} :read-models {} :processors {}}
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
    (doseq [[id v] (:models discovered)
            :let   [{:keys [fields events] id-key :id} (:bel/options (meta v))]]
      (doseq [ref (keep :bel/ref (vals fields))]
        (when-not (contains? (:models discovered) ref)
          (throw (ex-info "Undeclared referenced model" {:type :configuration :model id :reference ref}))))

      (doseq [[event mapping] events]
        (let [event-var (get-in discovered [:events event])]
          (when-not event-var
            (throw (ex-info "Undeclared mapped event" {:type :configuration :model id :event event})))
          (let [event-fields (set (map first (m/entries (m/deref (:schema (:bel/options (meta event-var)))))))
                missing      (seq (remove event-fields (cons id-key (or (:set mapping) (:create mapping)))))]
            (when missing
              (throw (ex-info "Mapped events must declare the identity and mapped fields"
                              {:type :configuration :model id :event event :fields (vec missing)})))))))
    (reset! registry discovered)))


(defn operations
  "Capture functions and metadata once for a command or composed read."
  {:malli/schema [:=> [:cat] :map]}
  []
  (let [definitions @registry
        ops         {:events {} :commands {} :models {} :read-models {} :processors {}}]
    (->> (keys ops)
         (mapcat (fn [section]
                   (for [[id v] (get definitions section)]
                     {section {id (assoc (:bel/options (meta v)) :handler @v)}})))
         (apply merge-deep ops))))


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


(defn get-model
  "A model's declared options (`:model-id`, `:fields`, `:schema`…), as stores receive them."
  {:malli/schema [:=> [:cat :qualified-keyword] [:map [:model-id :qualified-keyword] [:fields :map]]]}
  [id]
  (or (:bel/options (definition :models id))
      (throw (ex-info "Unknown model" {:type :configuration :model id}))))


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
