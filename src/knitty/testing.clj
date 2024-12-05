(ns knitty.testing
  (:require
   [clojure.set :as set]
   [knitty.core :as kt]
   [knitty.deferred :as kd]
   [knitty.impl :as impl]
   [knitty.trace :as t])
  (:import [clojure.lang AFn]
           [knitty.javaimpl KwMapper YarnProvider]))


(deftype MockRegistry [mock-yarn-fn real-registry]

  YarnProvider
  (yarn [_ kkw] (or (mock-yarn-fn kkw) (.yarn ^YarnProvider real-registry kkw)))
  (ycache [_] (make-array AFn (.maxIndex (KwMapper/getInstance))))

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


(defn mock-registry [registry yarn-key->mock-yarn]
  (MockRegistry. yarn-key->mock-yarn registry))


(defn with-yarns* [yarns body-fn]
  (binding [kt/*registry* (into kt/*registry* yarns)]
    (body-fn)))


(defmacro with-yarns
  "Run body with additional yarns attached to the registry.
   This function known to be slow, intended only for testing/mocking!"
  [yarns & body]
  `(with-yarns* ~yarns (fn [] ~@body)))


(defn with-yarns-only* [predicate body-fn]
  (let [r kt/*registry*
        mf (fn [k] (when-not (predicate k) (impl/fail-always-yarn k (str "yarn " k " is disabled"))))
        r' (mock-registry r mf)]
    (binding [kt/*registry* r']
      (body-fn))))


(defn- coerce-to-re-nspred [ns]
  (condp instance? ns
    java.lang.String #(= % (name ns))
    clojure.lang.Symbol #(= % (name ns))
    java.util.regex.Pattern #(re-matches ns %)
    clojure.lang.Namespace #(= % (name (ns-name ns)))
    (throw (ex-info "unable to build namespace predicate" {::ns ns}))
  ))


(defn from-ns
  "Build keyword predicate mathing to any namespace from `nss`.
   Namespaces may be specified as strings, symbols or regex patterns."
  [& nss]
  (let [p (apply some-fn (mapv coerce-to-re-nspred nss))]
    (fn [k] (p (name (namespace k))))))


(defmacro from-this-ns
  "Build keyword predicate mathing to keywords only from `*ns*`."
  []
  `(from-ns *ns*))


(defmacro with-yarns-only
  "Run body with some yarns deactivated.
   Predicate is called with yarn-id (namespaced keyword) for all available yarns in the registry.
   If predicate returns false yarn is replaced with 'fail always' stub.
   This function known to be slow, intended only for testing/mocking!

   Disable all yarns except from current namespace:

       (with-yarns-only (from-this-ns)
         @(yank {...} [...]))
   "
  [predicate & body]
  `(with-yarns-only* ~predicate (fn [] ~@body)))


(defn- edges->graph [es]
  (update-vals (group-by first es)
               (partial into #{} (map second))))

(defn- topo-order [g]
  (let [ns (keys g)
        g' (assoc g ::root ns)
        visited (atom #{})
        result (atom ())]
    (letfn [(dfs! [n]
             (when-not (@visited n)
               (swap! visited conj n)
               (doseq [n' (g' n)]
                 (dfs! n'))
               (swap! result conj n)))]
      (dfs! ::root)
      (rest @result) ;; skip ::root
      )))

(defn- expand-transitives [g]
  (let [ns (reverse (topo-order g))]
    (reduce
     (fn [t n]
       (assoc t n
              (into (set (g n))
                    (mapcat t)
                    (g n))))
     {}
     ns)))

(defn explain-yank-result [yank-result]
  (let [ts (t/find-traces yank-result)]
    (when-not ts
      (throw (ex-info "yank result does not have any traces attched" {:knitty/yank-result yank-result})))
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
  "Run `knitty.core/yank` with tracing enabled, await (synchonously) result
   and return nn extra information for debugging and testing.

   Result is dictionary with:
   - :ok?      boolean, true when `yank` resulted with no error
   - :result   yank* result or thrown exception
   - :traces   list of yank traces
   - :yanked   set of yanked nodes
   - :failed   set of failed nodes (with an exception)
   - :called   set of computed nodes
   - :leaked   set of leaked nodes (started but not completed yet)
   - :depend   graph of direct dependencies (adjacency list, includes unused lazy deps)
   - :depend*  graph of indirect (all) dependencies
   - :use      graph of direct node usages (adjacency list, does not include unused lazy deps)
   - :use*     graph of indirect node usages
   "
  ([inputs yarns]
   (yank-explain! inputs yarns nil))
  ([inputs yarns opts]
   @(kd/bind
     (kt/yank* inputs yarns (assoc opts :tracing true))
     explain-yank-result
     explain-yank-result)))
