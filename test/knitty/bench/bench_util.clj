(ns knitty.bench.bench-util
  (:require
   [clojure.java.io :as io]
   [clojure.java.process :as proc]
   [clojure.math :as math]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [clojure.test :as t]
   [criterium.core :as cc]
   [knitty.deferred :as kd]
   [manifold.deferred :as md]
   [manifold.executor :as ex])
  (:import
   [java.time LocalDateTime]
   [java.time.format DateTimeFormatter]))


(def bench-results-dir ".bench-results")


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

(def benchmark-use-subprocess
  (not (some? (System/getenv "knitty_qb_smoke"))))


(def ^:dynamic *bench-results*
  (atom []))

(def ^:dynamic *capture-bench-fns*
  nil)


(defn current-test-id []
  (vec
   (concat
    (reverse (map #(-> % meta :name name) t/*testing-vars*))
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


(defn- save-benchmark-results []
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
                   :results rs}))))


(defn- print-benchmark-results []
  (let [testid (tests-run-id)
        rs @*bench-results*
        oldr (->>
              (file-seq (io/file bench-results-dir))
              (remove #(.isDirectory ^java.io.File %))
              (map (comp read-string slurp))
              (remove #(= testid (:id %)))
              (sort-by #(let [^java.util.Date w (:when %)] (.getTime w))))
        oids (map :id oldr)
        oldm (into {}
                   (for [ors oldr
                         r (:results ors)]
                     [[(:id ors) (:test-id r)] r]))
        idlen (reduce max 10 (map (comp count str :test-id) rs))
        idf (str "%-" idlen "s")]
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

    (println)))


(defn report-benchmark-results []
  (save-benchmark-results)
  (print-benchmark-results))


(defn print-benchmark-results-matrix [classify-test-id all-classes]
  (let [results @*bench-results*
        order (into {} (map vector (map :test-id results) (range)))
        rks (group-by (comp first classify-test-id :test-id) results)
        g (->>
           (for [[rk rs] rks]
             (into
              {:id (str/join "/" rk)
               ::order (get order (-> rs first :test-id))}
              (for [r rs
                    :let [t (-> r :test-id classify-test-id second)]
                    :when t]
                [t (-> r :mean first)])))
           (filter #(-> % keys count (> 2))))
        idlen (reduce max 10 (map (comp count :id) g))
        basecol (first all-classes)
        idf (str "%-" idlen "s")
        ]
    (pp/print-table
     (cons :id all-classes)
     (->> g
          (map #(assoc % :id (format idf (:id %))))
          (map (fn [row]
                 (let [base (get row basecol)]
                   (into row
                         (for [c all-classes
                               :let [v (get row c)]
                               :when (some? v)]
                           [c
                            (str (fmt-time-value v)
                                 " ("
                                 (format "%+4.0f%%" (-> v (/ base) (* 100) (- 100)))
                                 ")"
                                 )])))))
          (sort-by ::order)))))


(def report-benchmark-hook-installed (atom false))

(defn- add-report-benchmark-results-hook []
  (when (compare-and-set! report-benchmark-hook-installed false true)
    (.. (Runtime/getRuntime) (addShutdownHook (Thread. #'report-benchmark-results)))))


(defn report-benchmark-fixture
  []
  (fn [t]
    (add-report-benchmark-results-hook)
    (t)))


(defn eval-in-separate-jvm [ns-name code]

  (if benchmark-use-subprocess
    (let [i (str (java.io.File/createTempFile "knitty-tests/run/" ".clj"))
          t (str (java.io.File/createTempFile "knitty-tests/ret/" ".edn"))
          cp (System/getProperty "java.class.path")
          e (pr-str `(do
                       (require '~ns-name)
                       (in-ns '~ns-name)
                       (let [v# ~code]
                         (spit ~t (pr-str v#)))))]
      (spit i e)
      (->
       (proc/start
        {:in :inherit, :out :inherit, :err :inherit}
        "java" "-cp" cp "clojure.main" "-i" i)
       (proc/exit-ref)
       (deref))
      (read-string (slurp t)))
    (eval `(do (in-ns '~ns-name)
               ~code))))


(defn bench* [id expr-fn]
  (t/testing id
    (if-let [c *capture-bench-fns*]
      (swap! c conj expr-fn)
      (do
        (print (format "  %-32s" (str/join " " (reverse t/*testing-contexts*))))
        (flush)
        (track-results (cc/benchmark (expr-fn) benchmark-opts))))))


(defmacro bench [id expr]
  `(bench* ~id (fn [] ~expr nil)))


(defn warmup-benches [bs]
  (when (seq bs)
    (let [bs (vec bs)]
      (cc/benchmark ((rand-nth bs)) benchmark-opts))))


(defn run-bench-suite [ns-name benchs]
  (swap!
   *bench-results*
   into
   (eval-in-separate-jvm
    ns-name
    `(binding [*bench-results* (atom [])
               t/*testing-vars* (list ~@t/*testing-vars*)
               t/*testing-contexts* (list ~@t/*testing-contexts*)]
       (let [a# (atom [])
             f# (fn [] ~@benchs)]
         (binding [*capture-bench-fns* a#] (f#))
         (warmup-benches @a#)
         (f#)
         @*bench-results*)))))


(defmacro bench-suite [& benchs]
  `(run-bench-suite '~(ns-name *ns*) '~benchs))


;; helpers

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

(defmacro apply-replicate-arg [f n & args]
  (let [f (if (seq? f) f [f])]
    `(~@f ~@(take n (cycle args)))))
