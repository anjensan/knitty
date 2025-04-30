(ns knitty.test-util
  (:require
   [clojure.java.io :as io]
   [clojure.math :as math]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [clojure.test :as t]
   [criterium.core :as cc]
   [knitty.core :refer [defyarn]]
   [knitty.deferred :as kd]
   [manifold.debug :as md-debug]
   [manifold.deferred :as md]
   [manifold.executor :as ex])
  (:import
   [java.time LocalDateTime]
   [java.time.format DateTimeFormatter]))


(defmethod t/report :begin-test-var [m]
  (t/with-test-out
    (println "-"
             (-> m :var meta :name)
             (or (some-> t/*testing-contexts* seq vec) ""))))

(defmethod t/report :end-test-var [_m]
  )

(defonce -override-report-error
  (let [f (get-method t/report :error)]
    (defmethod t/report :error [m]
      (f m)
      (t/with-test-out
        (println)))))


(def bench-results-dir ".bench-results")

(defn mfut [x y]
  (if (zero? (mod x y)) (md/future x) x))


(defn compile-yarn-graph*
  [ns prefix ids deps-fn emit-body-fn fork?]
  (let [n (create-ns ns)]
    (binding [*ns* n]
      (mapv var-get
            (for [i ids]
              (eval
               (let [nsym #(if (vector? %)
                             (let [[t i] %]
                               (with-meta
                                 (symbol (str prefix i))
                                 {t true}))
                             (symbol (str prefix %)))
                     node-xxxx (cond-> (nsym i)
                                 (fork? i) (vary-meta assoc :fork true))
                     deps (map nsym (deps-fn i))]
                 `(defyarn ~node-xxxx
                    ~(zipmap deps (map (fn [s] (keyword (name ns) (name s))) deps))
                    ~(apply emit-body-fn i deps)))))))))


(defmacro build-yarns-graph
  [& {:keys [prefix ids deps emit-body fork?]
      :or {prefix "node"
           emit-body (fn [i & _] i)
           fork? `(constantly false)}}]
  (let [g (ns-name *ns*)]
    `(compile-yarn-graph* '~g (name ~prefix) ~ids ~deps ~emit-body ~fork?)))


(defmacro nodes-range
  ([prefix & range-args]
   (mapv #(keyword (name (ns-name *ns*)) (str (name prefix) %))
         (apply range range-args))))


(defn tracing-enabled-fixture
  [tracing-enabled]
  (fn [t]
    (binding [knitty.core/*tracing* (boolean tracing-enabled)]
      (t))))


(def smoke-benchmarks-opts
  (merge
   cc/*default-quick-bench-opts*
   {:max-gc-attempts 2
    :samples 5
    :target-execution-time (* cc/s-to-ns 0.1)
    :warmup-jit-period (* cc/s-to-ns 0.05)
    :bootstrap-size 100}))

(def benchmark-opts
  (cond
    (some? (System/getenv "knitty_qb_smoke")) smoke-benchmarks-opts
    (some? (System/getenv "knitty_qb")) cc/*default-quick-bench-opts*
    :else cc/*default-benchmark-opts*))


(def ^:dynamic *bench-results*
  (atom []))


(defn current-test-id []
  (vec
   (concat
    (reverse (map #(:name (meta %)) t/*testing-vars*))
    (reverse t/*testing-contexts*))))


(defn fmt-time-value
  [mean]
  (let [[factor unit] (cc/scale-time mean)]
    (cc/format-value mean factor unit)))


(defn fmt-ratio-diff [r]
  (cond
    (< r 1) (format "-%.2f%%" (* 100 (- 1 r)))
    (> r 1) (format "+%.2f%%" (* 100 (- r 1)))))


(defn track-results [results]
  (swap! *bench-results* conj
         (-> results
             (assoc :test-id (current-test-id))
             (dissoc :runtime-details :os-details :options)))
  (print "\t ⟶ ")
  (print "time" (fmt-time-value (first (:mean results))))
  (let [[v] (:variance results), m (math/sqrt v)]
    (print " ±" (fmt-time-value m)))
  (println))


(defn tests-run-id []
  (or (System/getenv "knitty_tid")
      (.format (LocalDateTime/now) (DateTimeFormatter/ofPattern "YYMMdd-HH:mm:ss"))))


(defn report-benchmark-results []
  (let [testid (tests-run-id)
        rs @*bench-results*
        res-file (io/file (str bench-results-dir "/" testid ".edn"))]

    (io/make-parents res-file)
    (spit res-file
          (pr-str {:id testid
                   :when (java.util.Date.)
                   :details {:os (cc/os-details)
                             :runtime (cc/runtime-details)
                             :options benchmark-opts}
                   :results rs}))

    (let [oldr (->>
                (file-seq (io/file bench-results-dir))
                (remove #(.isDirectory ^java.io.File %))
                (remove #(= % res-file))
                (map (comp read-string slurp))
                (sort-by #(let [^java.util.Date w (:when %)] (.getTime w))))
          oids (map :id oldr)
          oldm (into {}
                     (for [ors oldr
                           r (:results ors)]
                       [[(:id ors) (:test-id r)] r]))
          idlen (reduce max 10 (map (comp count str :test-id) rs))
          idf (str "%-" idlen "s")
          ]
      (println "\nbench results:")
      (pp/print-table
       (for [r rs]
         (let [k (:test-id r)
               v (-> r :mean first)]
           (into
            {:id (format idf (str/join "/" k))
             :time (fmt-time-value v)}
            (for [oid oids
                  :let [or (get oldm [oid k])]]
              [oid (some-> or :mean first (/ v) fmt-ratio-diff)])))))

      (println))))

(def report-benchmark-hook-installed (atom false))

(defn- add-report-benchmark-results-hook []
  (when (compare-and-set! report-benchmark-hook-installed false true)
    (.. (Runtime/getRuntime) (addShutdownHook (Thread. #'report-benchmark-results)))))

(defn report-benchmark-fixture
  []
  (fn [t]
    (add-report-benchmark-results-hook)
    (t)))

(defmacro bench
  ([id expr]
   `(t/testing ~id
      (print (format "  %-32s" (str/join " " (reverse t/*testing-contexts*))))
      (flush)
      (track-results (cc/benchmark (do ~expr nil) benchmark-opts))))
  ([id expr1 & exprs]
   `(t/testing ~id
      (print (format "  %-32s" (str/join " " (reverse t/*testing-contexts*))))
      (flush)
      (track-results (cc/benchmark-round-robin ~(mapv #(list `do %) (list* expr1 exprs))
                                               benchmark-opts)))))

(defmacro do-defs
  "eval forms one by one - allows to intermix defs"
  [& body]
  (list*
   `do
   (for [b body]
     `(binding [*ns* ~*ns*]
        (eval '~b)))))


(defmacro slow-future [delay & body]
  `(kd/future
     (Thread/sleep (long ~delay))
     ~@body))


(defn reset-registry-fixture
  []
  (fn [t]
    (let [r knitty.core/*registry*]
      (try
        (t)
        (finally
          (alter-var-root #'knitty.core/*registry* (constantly r)))))))


(defn disable-manifold-leak-detection-fixture
  []
  (fn [t]
    (let [e md-debug/*dropped-error-logging-enabled?*]
      (md-debug/disable-dropped-error-logging!)
      (try (t)
           (finally
             (.bindRoot #'md-debug/*dropped-error-logging-enabled?* e))))))


(defmacro with-defer [& body]
  (when (contains? &env '_-defer-callbacks)
    (throw (ex-info "nested (with-defer ..)" {})))
  (let [dc '_-defer-callbacks]
    `(let [~dc (java.util.ArrayList.)]
       (try
         ~@body
         (finally
           (loop []
             (when-not (.isEmpty ~dc)
               (java.util.Collections/shuffle ~dc)
               (let [^Object/1 xxs# (.toArray ~dc)]
                 (.clear ~dc)
                 (areduce xxs# i# _ret# nil ((aget xxs# i#)))
                 (recur)))))))))


(defmacro defer! [callback]
  (when-not (contains? &env '_-defer-callbacks)
    (throw (ex-info "defer! is called outside of (with-defer ..)" {})))
  `(.add ~'_-defer-callbacks (fn [] ~callback)))


(def always-false false)

(defmacro ninl [x]
  `(if always-false
     (for [x# (range 10)] (for [x# (range x#)] (for [x# (range x#)] (for [x# (range x#)] (for [x# (range x#)] x#)))))
     ~x))

(defn ninl-inc
  ([]
   (ninl 0))
  ([^long x]
   (ninl (unchecked-inc x))))

(defmacro eval-template [emit-body vss-expr]
  (let [emit-body (eval emit-body)
        vss-expr (eval vss-expr)]
    (list*
     `do
     (map #(apply emit-body %) vss-expr))))

(defmacro with-md-executor [& body]
  `(let [~'__knitty__test_util__md_executor (ex/execute-pool)]
     ~@body))

(defmacro md-future
  "Equivalent to 'manifold.deferred/future', but use didicated executor"
  [& body]
  `(md/future-with ~'__knitty__test_util__md_executor ~@body))

(defmacro kt-future
  "Equivalent to 'manifold.deferred/future', but use didicated executor"
  [& body]
  `(kd/future-with ~'__knitty__test_util__md_executor ~@body))


(defn dotimes-prn* [n body-fn]
  (let [s (quot n 100)
        t (System/currentTimeMillis)]
    (dotimes [x n]
      (when (zero? (mod x s))
        (print ".")
        (flush))
      (body-fn n))
    (let [t1 (System/currentTimeMillis)]
      (println " done in" (- t1 t) "ms"))))


(defmacro dotimes-prn [xn & body]
  (let [[x n] (cond
                (and (vector? xn) (== 2 (count xn))) xn
                (and (vector? xn) (== 1 (count xn))) ['_ (first xn)]
                :else ['_ xn])]
    `(dotimes-prn* ~n (fn [~x] ~@body))))
