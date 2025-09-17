(ns knitty.testing
  "This namespace provides utilities for testing and mocking the Knitty system."
  (:require
   [clojure.set :as set]
   [knitty.core :as kt]
   [knitty.deferred :as kd]
   [knitty.impl :as impl]
   [knitty.trace :as t])
  (:import [clojure.lang AFn]
           [knitty.javaimpl YarnProvider]))

(deftype MockRegistry 
         [^clojure.lang.IFn mock-yarn-fn 
          ^YarnProvider real-registry]

  YarnProvider
  (yarn [_ kkw] (or (mock-yarn-fn kkw) (.yarn real-registry kkw)))
  (ycache [_] (make-array AFn (alength (.ycache real-registry))))

  clojure.lang.Seqable
  (seq [_] (map
            (fn [[k v]] (or (mock-yarn-fn k) v))
            (seq real-registry)))

  clojure.lang.IPersistentCollection
  (count [_] (count real-registry))

  (cons [_ x] (MockRegistry. mock-yarn-fn (conj real-registry x)))
  (equiv [_ o] (and (instance? MockRegistry o)
                    (= mock-yarn-fn (.-mock-yarn-fn ^MockRegistry o))
                    (= real-registry (.-real-registry ^MockRegistry o))))
  (empty [_] (MockRegistry. mock-yarn-fn (empty real-registry)))

  clojure.lang.ILookup
  (valAt [_ k] (when (contains? real-registry k)
                 (or (mock-yarn-fn k) (get real-registry k))))
  (valAt [_ k d] (if (contains? real-registry k)
                   (or (mock-yarn-fn k) (get real-registry k d))
                   d))

  clojure.lang.Associative
  (containsKey [_ k] (contains? real-registry k))
  (entryAt [_ k] (when-let [[k :as kv] (find real-registry k)]
                   (if-let [y' (mock-yarn-fn k)]
                     [k y'] kv)))

  (assoc [_ k v] (MockRegistry. mock-yarn-fn (assoc real-registry k v)))
  )


(defn mock-registry
  "Creates a mock registry based on the given real registry and a mapping function.
  The mock registry will use `yarn-key->mock-yarn` to override yarns when present; otherwise, it
  delegates to the real registry.
  "
  [registry yarn-key->mock-yarn]
  (MockRegistry. yarn-key->mock-yarn registry))


(defn with-yarns*
  "Executes the provided body function with additional yarns merged into the global registry."
  [yarns body-fn]
  (binding [kt/*registry* (into kt/*registry* yarns)]
    (body-fn)))


(defmacro with-yarns
  "Runs the given body with additional yarns attached to the registry.
   This macro is intended for testing or mocking scenarios and is known to be slow."
  [yarns & body]
  `(with-yarns* ~yarns (fn [] ~@body)))


(defn with-yarns-only*
  "Runs the provided body function with yarns filtered by a predicate.

  For each yarn in the registry, if the predicate returns false then that yarn is disabled by
  replacing it with a 'fail always' stub."
  [predicate body-fn]
  (let [r kt/*registry*
        mf (fn [k] (when-not (predicate k)
                     (impl/fail-always-yarn k (str "yarn " k " is disabled"))))
        r' (mock-registry r mf)]
    (binding [kt/*registry* r']
      (body-fn))))


(defn- coerce-to-re-nspred
  [ns]
  (condp instance? ns
    java.lang.String #(= % (name ns))
    clojure.lang.Symbol #(= % (name ns))
    java.util.regex.Pattern #(re-matches ns %)
    clojure.lang.Namespace #(= % (name (ns-name ns)))
    (throw (ex-info "unable to build namespace predicate" {::ns ns}))))


(defn from-ns
  "Builds a keyword predicate that matches any namespace from the given specifications.
   Namespaces may be specified as strings, symbols, or regex patterns."
  [& nss]
  (let [p (apply some-fn (mapv coerce-to-re-nspred nss))]
    (fn [k] (p (name (namespace k))))))


(defmacro from-this-ns
  "Builds a keyword predicate that matches keywords only from the current namespace (*ns*)."
  []
  `(from-ns *ns*))


(defmacro with-yarns-only
  "Runs the body with some yarns deactivated based on a predicate.

  The predicate is called with each yarn-id (a namespaced keyword) in the registry. If it returns false,
  the yarn is replaced with a 'fail always' stub.

  For example, to disable all yarns except those from the current namespace:

      (with-yarns-only (from-this-ns)
        (do-something))

  This macro is intended for testing or mocking and is known to be slow."
  [predicate & body]
  `(with-yarns-only* ~predicate (fn [] ~@body)))


(defn- edges->graph [es]
  (update-vals (group-by first es)
               (partial into #{} (map second))))

(defn- topo-order [g]
  (let [ns (keys g)
        g' (assoc g ::root ns)
        visited (atom #{})
        result (atom [])]
    (letfn [(dfs! [n]
              (when-not (@visited n)
                (swap! visited conj n)
                (doseq [n' (g' n)]
                  (dfs! n'))
                (swap! result conj n)))]
      (dfs! ::root)
      (rest @result))))  ;; skip ::root

(defn- expand-transitives [g]
  (let [ns (topo-order g)]
    (reduce
     (fn [t n]
       (assoc t n
              (into (set (g n))
                    (mapcat t)
                    (g n))))
     {}
     ns)))


(defn explain-yank-result
  "Analyzes a yank-result by extracting trace information and computing dependency graphs.

   Returns a map containing keys:
   - :ok?     - Boolean indicating whether the yank succeeded.
   - :result  - The yank result or thrown exception.
   - :traces  - A sequence of trace records.
   - :yanked  - A set of yanked node identifiers.
   - :failed  - A set of node identifiers that failed.
   - :called  - A set of computed node identifiers.
   - :leaked  - A set of node identifiers that started but did not finish.
   - :use     - Graph (adjacency list) of direct node usages.
   - :use*    - Graph of transitive node usages.
   - :depend  - Graph of direct dependencies.
   - :depend* - Graph of transitive dependencies.

  Throws an exception if no traces are attached to the yank-result."
  [yank-result]
  (let [ts (t/find-traces yank-result)]
    (when-not ts
      (throw (ex-info "yank result does not have any traces attached" {:knitty/yank-result yank-result})))
    (let [tls (mapcat :tracelog ts)
          ev (fn [e] #(= (:event %) e))
          ybe (fn [e] (into #{} (comp (filter (ev e)) (map :yarn)) tls))
          started (ybe ::t/trace-start)
          finished (ybe ::t/trace-finish)
          failed (ybe ::t/trace-error)
          uses (->> tls
                    (filter (ev ::t/trace-dep))
                    (map (juxt :yarn (comp first :value)))
                    (edges->graph))
          deps (-> (concat
                    (->> tls
                         (filter (ev ::t/trace-route-by))
                         (map #(vector (:yarn %) (:value %))))
                    (->> tls
                         (filter (ev ::t/trace-all-deps))
                         (mapcat (fn [t] (let [y (:yarn t)]
                                           (for [d (:value t)] [y (first d)]))))))
                   (edges->graph))]
      {:ok?     (not (kt/yank-error? yank-result))
       :result  (or (some-> yank-result ex-data :knitty/result) yank-result)
       :traces  (seq ts)
       :yanked  (into #{} (mapcat :yarns) ts)
       :failed  failed
       :called  started
       :leaked  (set/difference started finished)
       :use     uses
       :use*    (expand-transitives uses)
       :depend  deps
       :depend* (expand-transitives deps)})))


(defn yank-explain!
  "Runs `kt/yank` with tracing enabled, waits synchronously for the result, and returns
   additional diagnostic information for debugging and testing.
   See `explain-yank-result` for information about the returned value."
  ([inputs yarns]
   (yank-explain! inputs yarns nil))
  ([inputs yarns opts]
   @(kd/bind
     (kt/yank* inputs yarns (assoc opts :tracing true))
     explain-yank-result
     explain-yank-result)))
