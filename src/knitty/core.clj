(ns knitty.core
  (:require [clojure.spec.alpha :as s]
            [knitty.deferred :as kd]
            [knitty.impl :as impl]
            [knitty.trace :as trace]))


(def ^:dynamic *registry*
  (impl/create-registry))

(def ^:dynamic *tracing*
  false)

(defn enable-tracing!
  "Globally enables Knitty tracing."
  ([]
   (enable-tracing! true))
  ([enable]
   (alter-var-root #'*tracing* (constantly (boolean enable)))))


(defn set-executor!
  "Globally set knitty executor, returns old value."
  [executor]
  (let [a (atom nil)]
    (alter-var-root #'kd/*executor* (fn [x] (reset! a x) executor))
    @a))


(defn register-yarn
  "Registers a Yarn into the global registry. Does nothing if the Yarn
   is already registered and the `no-override` flag is true."
  ([yarn]
   (register-yarn yarn false))
  ([yarn no-override]
   (let [k (impl/yarn-key yarn)]
     (when-not (qualified-keyword? k)
       (throw (ex-info "Yarn must be a qualified keyword" {::yarn k, ::class (type k)})))
     (if no-override
       (alter-var-root #'*registry* #(if (contains? % k) % (assoc % k yarn)))
       (alter-var-root #'*registry* assoc k yarn)))))


(defn- valid-bind-type? [bsym]
  (let [{:keys [defer lazy case]} (meta bsym)
        n (count (filter identity [defer lazy case]))]
    (<= n 1)))


(s/def ::yarn-ref
  (s/or :ident symbol?
        :keyword qualified-keyword?))

(s/def ::yarn-binding-case-map
  (s/or
   :map (s/map-of any? ::yarn-ref)
   :seq (s/coll-of ::yarn-ref)))

(s/def ::yarn-bindind-var
  (s/and symbol? valid-bind-type?))

(s/def ::yarn-binding
  (s/map-of
   ::yarn-bindind-var
   (s/or :yarn-ref ::yarn-ref
         :case-map ::yarn-binding-case-map)))

(s/def ::bind-and-body
  (s/cat :bind ::yarn-binding :body (s/+ any?)))

(s/def ::yarn
  (s/cat
   :name qualified-keyword?
   :bind-and-body ::bind-and-body))

(s/def ::defyarn
  (s/cat
   :name symbol?
   :doc (s/? string?)
   :bind-and-body (s/? ::bind-and-body)))

(s/def ::defyarn-multi
  (s/cat
   :name symbol?
   :doc (s/? string?)
   :dispatch-yarn ::yarn-ref
   :multi-options (s/* any?)))

(s/def ::defyarn-method
  (s/cat
   :name ::yarn-ref
   :dispatch-value any?
   :bind-and-body ::bind-and-body))


(defmacro declare-yarn
  "Defines an abstract Yarn without an implementation.
   Useful for making forward declarations."
  [nm]
  {:pre [(ident? nm)]}
  (let [spec (:spec (meta nm))
        k (keyword (or (namespace nm)
                       (-> *ns* ns-name name))
                   (name nm))]
    `(do (register-yarn (impl/fail-always-yarn ~k ~(str "declared-only Yarn " k)) true)
         ~(when spec `(spec/def ~k ~spec))
         ~(when (simple-ident? nm) `(def ~nm ~k))
         ~k)))


(defn bind-yarn
  "Redeclares a Yarn as a symlink to the `yarn-target`."
  [yarn yarn-target]
  {:pre [(qualified-keyword? yarn)
         (qualified-keyword? yarn-target)]}
  (register-yarn (eval (impl/gen-yarn-ref yarn yarn-target))))


(defn- resolve-yarn-sym
  [env k]
  (let [v (resolve env k)]
    (when-not v
      (throw (ex-info "unable to resolve yarn binding variable" {::binding-var k})))
    (let [k @v]
      (when-not (qualified-keyword? k)
        (throw (ex-info "yarn binding must be a qualified keyword" {::binding k})))
      k)))

(defn- parse-yarn-ref [env [x y]]
  (case x
    :ident (resolve-yarn-sym env y)
    :keyword y))

(defn- parse-case-params-map
  [env vv]
  (let [[vv-t vv-val] vv]
    (case vv-t
      :map
      (into {} (for [[x y] vv-val]
                 [x (parse-yarn-ref env y)]))
      :seq
      (into {} (map (comp (juxt identity identity)

                          (partial parse-yarn-ref env))) vv-val))))
(defn- parse-bind-vec [env bind]
  (->> bind
       (mapcat (fn [[k [vt vv]]]
                 [k
                  (case vt
                    :yarn-ref (parse-yarn-ref env vv)
                    :case-map (parse-case-params-map env vv))]))
       (apply array-map)))


(defn- emit-yarn
  [env k cf mt]
  (let [{{:keys [bind body]} :bind-and-body} cf
        mt (merge mt (meta bind))
        bind (parse-bind-vec env bind)]
    (impl/gen-yarn k bind `(do ~@body) mt)))


(defn- conform-and-check [spec value]
  (let [x (s/conform spec value)]
    (when (s/invalid? x)
      (throw (Exception. (str (s/explain-str spec value)))))
    x))


(defmacro yarn
  "Returns a Yarn object (without registering it into the global registry).
   May capture variables from the outer scope."
  [k & exprs]
  (if (empty? exprs)
    (impl/gen-yarn-input k)
    (let [bd (cons k exprs)
          cf (conform-and-check ::yarn bd)]
      (emit-yarn &env k cf nil))))


(defn- pick-yarn-meta [obj ex-meta doc]
  (let [m (merge ex-meta (meta obj))
        doc (or doc (:doc m))
        m (if doc (assoc m :doc doc) m)]
    [(with-meta obj m)
     m]))


(defmacro defyarn
  "Defines a Yarn, which is a computation node in Knitty. The Yarn is identified by a qualified keyword
   generated using the current namespace and the provided name. This macro allows you to define Yarns
   with optional dependencies and computation logic.

   Examples:

   ```clojure
   ;; declare ::yarn-1 without a body
   (defyarn yarn-1)

   ;; declare ::yarn-2 with a docstring
   (defyarn yarn-2 \"documentation\")

   ;; define ::yarn-3 without any inputs
   (defyarn yarn-3 {} (rand-int 10))

   ;; define ::yarn-4 with inputs
   (defyarn yarn-4 {x yarn-3} (str \"Random is \" x))
   ```
  "
  {:arglists '([name docstring?]
               [name docstring? [dependencies*] & body])}
  [name & doc-binds-body]
  (let [bd (cons name doc-binds-body)
        cf (conform-and-check ::defyarn bd)
        k (keyword (-> *ns* ns-name clojure.core/name) (clojure.core/name name))
        {doc :doc, {:keys [bind body]} :bind-and-body} cf
        [nm m] (pick-yarn-meta name (meta bind) doc)
        spec (:spec m)
        y (if (empty? body)
            (impl/gen-yarn-input k)
            (emit-yarn &env k cf m))]
    (list
     `do
     (when spec `(s/def ~k ~spec))
     `(register-yarn ~y)
     `(def ~nm ~k))))


(defmacro defyarn-multi
  "Defines a new multiyarn.  Dispatching is routed by the value of `dispatch-yarn'
   using same mechanics as `defmulti` macro.  Optional parameters are `:hierarchy` and `:default`."
  {:arglists '([name docstring? dispatch-yarn & multi-options])}
  ([name & doc-dispatch-options]
   (let [bd (cons name doc-dispatch-options)
         cf (conform-and-check ::defyarn-multi bd)
         {:keys [dispatch-yarn name doc multi-options]} cf
         k (keyword (-> *ns* ns-name clojure.core/name)
                    (clojure.core/name name))
         dy (parse-yarn-ref &env dispatch-yarn)
         my (impl/gen-yarn-multi k dy (apply array-map multi-options))
         [name m] (pick-yarn-meta name {} doc)
         spec (:spec m)]
     (list
      `do
      `(when-let [f# (get *registry* ~k)] (println ">>>>" (f#)))
      (when spec `(s/def ~k ~spec))
      `(register-yarn ~my)
      `(def ~name ~k)))))


(defmacro defyarn-method
  "Creates and installs a new method of multiyarn associated with dispatch-value."
  {:arglists '([multiyarn-name dispatch-value bindings-vec & body])}
  [multiyarn-name dispatch-value bindings-vec & body]
  (let [z (list* multiyarn-name dispatch-value bindings-vec body)
        cf (conform-and-check ::defyarn-method z)
        {:keys [name dispatch-value], {:keys [bind body]} :bind-and-body} cf
        bind (parse-bind-vec &env bind)
        k (parse-yarn-ref &env name)
        y (gensym)]
    `(let [~y (yarn ~k ~bind ~@body)
           x# ~(impl/gen-reg-yarn-method k y dispatch-value `*registry*)]
       (register-yarn (get *registry* ~k) false)  ;; reregister to trigger cycle-check
       x#)))


(defmacro yarn-prefer-method
  "Causes the multiyarn to prefer matches of `dispatch-val-x` over `dispatch-val-y`."
  [yarn-ref dispatch-val-x dispatch-val-y]
  (let [cf (conform-and-check ::yarn-ref yarn-ref)
        y (parse-yarn-ref &env cf)]
    `(prefer-method (impl/yarn-multifn (get *registry* ~y)) ~dispatch-val-x ~dispatch-val-y)))


(defmacro ^:private pick-opt [opts key default]
  `(if (contains? ~opts ~key) (~key ~opts) ~default))

(defn yank*
  "Computes missing nodes. Always returns deferred resolved into YankResult.
   YankResult implements ILookup, Seqable, IObj, IKVReduce and IReduceInit.

   Optinans are:
    - `:executor` a instance of `java.util.concurrent.Executor` which is used to run code;
    - `:preload`  preload all values from input map;
    - `:bindings` flag, indicating that thread-local bindings should be captured and installed for yarns;
    - `:tracing`  flag, do we need to capture tracing (introduce some perfomance penalties);
    - `:registry` a knitty registry with avalable yarns, usefull for mocking code.
  "
  ([inputs yarns]
   (yank* inputs yarns nil))
  ([inputs yarns opts]
   (let [registry (pick-opt opts :registry *registry*)
         executor (pick-opt opts :executor kd/*executor*)
         preload  (pick-opt opts :preload false)
         bindings (pick-opt opts :bindings true)
         tracing  (trace/if-tracing (pick-opt opts :tracing *tracing*))
         tracer (trace/if-tracing (when tracing (trace/create-tracer inputs yarns)))
         bframe (when bindings (clojure.lang.Var/cloneThreadBindingFrame))
         ctx (knitty.javaimpl.YankCtx/create inputs registry executor tracer (boolean preload) bframe)
         r (.yank ctx yarns)]
     (trace/if-tracing
      (if tracer
        (let [r' (kd/bind
                  r
                  (fn [x]
                    (vary-meta x update :knitty/trace conj (trace/capture-trace! tracer)))
                  (fn [e]
                    (throw
                     (ex-info
                      (ex-message e)
                      (assoc (ex-data e) :knitty/trace (conj (:knitty/trace (meta inputs))
                                                             (trace/capture-trace! tracer)))
                      (ex-cause e)))))]
          (kd/revoke-to r' r))
        r)
      r))))


(defn yr->map
  "Transforms result of `yank*` into persistent map."
  [yr]
  (cond
    (instance? knitty.javaimpl.YankResult yr) (.toAssociative ^knitty.javaimpl.YankResult yr)
    (map? yr) yr
    :else (throw (ex-info "invalid yank-result" {:knitty/invalid-result yr}))))


(defmacro yank
  "Computes and adds missing nodes into 'inputs' map. Always returns deferred."
  ([inputs yarns]
   `(yank ~inputs ~yarns nil))
  ([inputs yarns & {:as opts}]
   `(let [r# (yank* ~inputs ~yarns ~opts)]
      (-> r#
          (kd/bind yr->map)
          (kd/revoke-to r#)))))


(defmacro yank1
  "Computes and returns a single node.
   Intended to be used in REPL sessions where there is needed to pick just a single yarn value.
   Tracing is disabled, use wrapped `yank*` if you need it.

   Logically similar to:

       (md/chain (yank inputs [::yarn-key]) ::yarn-key)
   "
  ([inputs yarns]
   `(yank1 ~inputs ~yarns nil))
  ([inputs yarn & {:as opts}]
   (let [y (gensym)
         k (if (keyword? yarn) y `(if (keyword? ~y) ~y (impl/yarn-key ~y)))
         opts (assoc opts :tracing false)]
     `(let [~y ~yarn
            k# ~k
            r# (yank* ~inputs [~y] ~opts)]
        (-> r#
            (kd/bind k#)
            (kd/revoke-to r#))))))


(defn yank-error?
  "Checks if an exception was rethrown by a `yank` function."
  [ex]
  (:knitty/yank-error? (ex-data ex) false))

