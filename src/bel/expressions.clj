(ns bel.expressions
  (:refer-clojure
   :exclude [compile selector])
  (:require
   [backtick :refer [template]]
   [charred.api :as charred]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [squint.compiler])
  (:import
   [clojure.lang IObj]
   [java.util UUID]))


;; ---------------------------------------------------------------------------
;; Extension point: custom form expansions inside ->expr
;; ---------------------------------------------------------------------------


(defmulti expand-form
  "Extension point for custom form expansions inside ->expr.
   Dispatches on the first symbol of a list form.
   Also handles @sym syntax: (@action ...) dispatches as (symbol \"@action\")."
  (fn [form]
    (when (sequential? form)
      (let [head (first form)]
        (cond
          (symbol? head) head
          (and (sequential? head)
               (= 'clojure.core/deref (first head))
               (symbol? (second head)))
          (symbol (str "@" (name (second head)))))))))


(defmethod expand-form :default [form] form)


(defn- postwalk-expr
  "Walk DSL forms without entering unquoted host Clojure."
  [f form]
  (if (and (seq? form) (= 'clojure.core/unquote (first form)))
    form
    (walk/walk (partial postwalk-expr f) f form)))


(defn process-custom-expanders [form]
  (postwalk-expr expand-form form))


;; ---------------------------------------------------------------------------
;; Original implementation
;; ---------------------------------------------------------------------------


(defn bool-expr [e]
  (cond
    (boolean? e) e
    (instance? IObj e) (vary-meta e assoc :tag 'boolean)
    :else e))


(defn expr-js-template [_ _ & args]
  (concat (list 'js* (first args))))


(defn expr-println
  ([_ _ & exprs]
   (let [js (str/join "," (repeat (count exprs) "(~{})"))]
     (concat (list 'js* (str "console.log(" js ")")) exprs))))


(defn expr-do
  ([_ _]
   nil)
  ([_ _ & exprs]
   (let [js (str/join ", " (repeat (count exprs) "(~{})"))]
     (concat (list 'js* js) exprs))))


(defn expr-if
  [_ _ test & body]
  (list 'if (bool-expr test) (first body) (second body)))


(defn expr-not
  [_ _ x]
  (let [js "(!(~{}))"]
    (bool-expr
     (concat (list 'js* js) (list x)))))


(defn expr-when
  [_ _ test & body]
  (list 'if (bool-expr test) (cons 'expr/do body)))


(defn expr-when-not
  [_ _ test & body]
  (list 'if-not (bool-expr test) (cons 'expr/do body)))


(defn expr-or
  ([_ _]
   nil)
  ([_ _ x]
   x)
  ([_ _ x & next]
   (let [js (str/join " || " (repeat (inc (count next)) "(~{})"))]
     (bool-expr
      (concat (list 'js* js) (cons x next))))))


(defn expr-raw
  ([_ _]
   (list 'js* ""))
  ([_ _ x]
   (let [js (str/join (repeat (count x) "~{}"))]
     (concat (list 'js* js) x))))


(defn expr-and
  ([_ _]
   true)
  ([_ _ x]
   x)
  ([_ _ x & next]
   (let [js (str/join " && " (repeat (inc (count next)) "(~{})"))]
     (bool-expr
      (concat (list 'js* js) (cons x next))))))


(defn replace-truth
  "Post-process compiled JavaScript to convert squint_core.truth_(.*) to !!$1"
  [js-string]
  (str/replace js-string #"squint_core\.truth_\((.*)\)" "!!($1)"))


(defn replace-deref
  "Post-processes compiled JavaScript to convert squint_core.deref(fn) to @fn"
  [js-string]
  (-> js-string
      (str/replace #"squint_core\.deref\(squint_core.(get)\)\s*\(" "@$1(")
      (str/replace
       #"squint_core\.deref\(([a-zA-Z_$][a-zA-Z0-9_$]*)\)\s*\("
       "@$1(")))


(defn unescape-forward-slashes
  "Keep generated JS string literals readable.

   JSON permits escaping `/` as `\\/`, but Datastar expressions do not need it
   and existing callers/tests expect normal URL and qualified-keyword strings."
  [js-string]
  (str/replace js-string "\\/" "/"))


(defn collect-kebab-signals
  "Collects all kebab-case signal names starting with $ from a form"
  [form]
  (into #{}
        (comp
         (filter symbol?) ;; Keep symbols
         (filter #(and (str/starts-with? (name %) "$") ;; ...starting with $
                       (str/includes? (name %) "-"))) ;; ...containing dashes
         (map name))
        (tree-seq coll? seq form)))


(defn restore-signals-casing
  "Post-processes compiled js to preserve the original casing of kebab cased signals

  Squint converts symbols like foo-bar into foo_bar, this function reverses that."
  [js-string form]
  (let [kebab-symbols (collect-kebab-signals form)
        replacements (for [sym kebab-symbols
                           :let [kebab-case sym
                                 snake-case (str/replace kebab-case #"-" "_")]]
                       [snake-case kebab-case])]

    (reduce (fn [result [from to]]
              (str/replace result from to))
            js-string
            replacements)))


;; This is how we override squint's special forms (which aren't extensible)
;; We walk the forms before compiling, and replace the special forms with
;; our own macros that compile to the JS that we want.
;; Mainly we want to avoid special forms that rely on the squint core lib
(def macro-replacements
  {'and      'expr/and
   'or       'expr/or
   'if       'expr/if
   'not      'expr/not
   'println  'expr/println
   'when     'expr/when
   'expr/raw 'expr/raw})


(def compiler-macro-options
  {'expr {'and         expr-and
          'or          expr-or
          'when        expr-when
          'when-not    expr-when-not
          'do          expr-do
          'if          expr-if
          'not         expr-not
          'println     expr-println
          'js-template expr-js-template
          'raw         expr-raw}})


(defn js* [form]
  (->
   (squint.compiler/compile-string (str form)
                                   {:elide-imports true
                                    :elide-exports true
                                    :top-level     false
                                    :context       :expr
                                    :macros        compiler-macro-options})
   (replace-deref)
   (replace-truth)
   (restore-signals-casing form)
   (str/replace #"\n" " ")
   (unescape-forward-slashes)
   (str/trim)))


(defn compile [forms]
  (str/join "; "
            (map js*
                 (if (sequential? forms)
                   forms
                   (list forms)))))


(defn process-string-concat
  "This function converts forms whose head is a symbol starting with $ into string concatenation forms"
  [form]
  (postwalk-expr
   (fn [node]
     (if (and (sequential? node)
              (symbol? (first node))
              (.startsWith (name (first node)) "$"))
       (let [signal-ref (name (first node))
             args (rest node)
             processed-args (map (fn [arg]
                                   (if (and (sequential? arg)
                                            (= 'clojure.core/unquote (first arg)))
                                     (second arg)
                                     arg))
                                 args)]
         (list 'clojure.core/unquote
               (list 'clojure.core/symbol
                     (cons 'str (cons signal-ref processed-args)))))
       node))
   form))


(defn process-not-equals
  "Transforms (not= x y ...) into (not (= x y ...)) to avoid squint_core dependency"
  [form]
  (postwalk-expr
   (fn [node]
     (if (and (list? node)
              (= 'not= (first node)))
       (list 'not (cons '= (rest node)))
       node))
   form))


(defn process-macros [form]
  (postwalk-expr
   (fn [node]
     (if (and (list? node)
              (contains? macro-replacements (first node)))
       (cons (get macro-replacements (first node)) (rest node))
       node))
   form))


(defn process-interpolation
  "Walks the forms and replaces (\"`foo`\") with (expr/js-template \"foo\")"
  [form]
  (postwalk-expr
   (fn [node]
     (if (and (list? node)
              (= 1 (count node))
              (string? (first node))
              (= \` (ffirst node)))
       (cons 'expr/js-template node)
       node))
   form))


(defn process-literals
  "Normalizes host literals that Squint does not serialize correctly.

   Reader UUIDs otherwise stringify as bare tokens, producing invalid JS."
  [form]
  (postwalk-expr
   (fn [node]
     (if (instance? UUID node)
       (str node)
       node))
   form))


(defn pre-process [forms]
  (-> forms
      process-custom-expanders
      process-literals
      process-not-equals
      process-interpolation
      process-string-concat
      process-macros))


;; ---------------------------------------------------------------------------
;; Compile-time optimization helpers
;; ---------------------------------------------------------------------------


(defn has-unquotes?
  "Walk form tree, return true if any (clojure.core/unquote ...) node exists."
  [form]
  (boolean
   (some #(and (seq? %) (= 'clojure.core/unquote (first %)))
         (tree-seq coll? seq form))))


(defn extract-unquotes
  "Replace every (clojure.core/unquote expr) with a unique placeholder string.
   Returns [new-form bindings], where bindings is a vector of
   [placeholder-string original-expr] pairs.

   Normalizes Cons/LazySeq nodes back to PersistentList so (str form) stays
   valid Clojure source for Squint."
  [form]
  (let [counter (atom 0)
        bindings (atom [])]
    [(walk/postwalk
      (fn [node]
        (cond
          (and (seq? node) (= 'clojure.core/unquote (first node)))
          (let [placeholder (str "__DS_" (swap! counter inc) "__")]
            (swap! bindings conj [placeholder (second node)])
            placeholder)

          (and (seq? node) (not (list? node)))
          (apply list node)

          :else
          node))
      form)
     @bindings]))


(defn clj->js
  "Convert a Clojure value to a JavaScript literal for runtime interpolation."
  [v]
  (cond
    (symbol? v) (name v)
    (nil? v) "null"
    (true? v) "true"
    (false? v) "false"
    (and (number? v)
         (not (ratio? v))
         (not (and (float? v)
                   (or (NaN? v) (infinite? v)))))
    (str v)
    (instance? UUID v) (-> v str charred/write-json-str unescape-forward-slashes)
    :else (unescape-forward-slashes (charred/write-json-str v))))


(defn- emit-dynamic
  "Compile a template at macro expansion and emit runtime placeholder replacement."
  [processed-form]
  (let [[template-form bindings] (extract-unquotes processed-form)
        compiled-template (compile template-form)]
    (reduce
     (fn [expr [placeholder original-expr]]
       (let [quoted-ph (str "\"" placeholder "\"")]
         (if (str/includes? compiled-template quoted-ph)
           `(str/replace ~expr ~quoted-ph (clj->js ~original-expr))
           `(str/replace ~expr ~placeholder (str ~original-expr)))))
     compiled-template
     bindings)))


;; ---------------------------------------------------------------------------
;; Public macro
;; ---------------------------------------------------------------------------


(defmacro ->expr
  "Compiles a clojure form into a datastar expression.

   Static expressions with no ~unquotes compile entirely at macro expansion.
   Dynamic expressions compile a template at macro expansion and substitute
   runtime values with cheap string replacement.

   Use ^:runtime metadata on the first form to force the old runtime compiler
   path as an escape hatch."
  [& forms]
  (let [runtime-forced? (and (seq forms)
                             (-> forms first meta :runtime))
        processed-form (pre-process forms)]
    (cond
      runtime-forced?
      `(compile (process-literals (template ~processed-form)))

      (not (has-unquotes? processed-form))
      (compile processed-form)

      :else
      (emit-dynamic processed-form))))
