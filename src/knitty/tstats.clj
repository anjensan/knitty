(ns knitty.tstats
  (:require [knitty.trace :as trace]
            [knitty.deferred :as kd])
  (:import [org.HdrHistogram
            ConcurrentHistogram
            Histogram
            Recorder]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* true)


(defrecord Stats
  [^long total-count
   ^long total-sum
   ^long count
   ^long mean
   ^long stdv
   ^long min
   ^long max
   ^{:doc "seq of pairs"} pcts])


(defn- simple-stats-tracker
  [& {:keys [precision percentiles]}]
  (let [r (Recorder. (int precision))
        total-count (atom 0)
        total-sum (atom 0)]
     (fn
       ([]
        (let [h (.getIntervalHistogram r)]
          (->Stats
           @total-count
           @total-sum
           (.getTotalCount h)
           (Math/round (.getMean h))
           (Math/round (.getStdDeviation h))
           (.getMinValue h)
           (.getMaxValue h)
           (doall
            (for [p percentiles]
              [p (.getValueAtPercentile h p)])))))
       ([x]
        (swap! total-count inc)
        (swap! total-sum + x)
        (.recordValue r (long x))
        x))))


(definline ^:private now-ms []
  `(System/currentTimeMillis))


(defn- windowed-stats-tracker
  [& {:keys [window window-chunk precision percentiles]}]
  (let [;;
        window (long window)
        window-chunk (long window-chunk)

        total-count (atom 0)
        total-sum (atom 0)
        r (Recorder. (int precision))
        hss (atom clojure.lang.PersistentQueue/EMPTY)
        lc (atom (quot (now-ms) window))

        cleanup!
        (fn []
          (let [mint (- (now-ms) window)]
            (while (some-> @hss ffirst long (< mint))
              (swap! hss
                     (fn [[[t _h] :as hs]]
                       (if (< t mint) (pop hs) hs))))))
        flush!
        (fn [force]
          (let [t (now-ms)
                c (quot t window-chunk)]
            (when (or force (not= c @lc))
              (let [[c' _] (swap-vals! lc (constantly c))]
                (when (or force (not= c c'))
                  (let [h (.getIntervalHistogram r)]
                    (when-not (zero? (.getTotalCount h))
                      (swap! hss conj [t h])))))
              (cleanup!))))]

    (fn

      ([]
       (flush! true)
       (let [hs @hss
             t (Histogram. (int precision))]
         (doseq [[_c ^Histogram h] hs]
           (.add t h))
         (->Stats
          @total-count
          @total-sum
          (.getTotalCount t)
          (Math/round (.getMean t))
          (Math/round (.getStdDeviation t))
          (.getMinValue t)
          (.getMaxValue t)
          (doall
           (for [p percentiles]
             [p (.getValueAtPercentile t p)])))))

      ([x]
       (swap! total-count inc)
       (swap! total-sum + x)
       (.recordValue r (long x))
       (flush! false)
       x)
      ;;
      )))


(defn- grouped-stats-tracker [create-tracker]
  (let [ss (atom {})]
    (fn
      ([]
       (into {} (map (fn [[k v]] [k (v)])) @ss))
      ([m]
       (run!
        (fn [[k v]]
          (if-let [s (@ss k)]
            (s v)
            (let [s (create-tracker)]
              (swap! ss assoc k s)
              ((@ss k) v))))
        m))
      )))


(defn- safe-minus [a b]
  (when (and (some? a) (some? b))
    (let [^long a a
          ^long b b]
      (when (and (not (zero? a)) (not (zero? b)))
        (- a b)))))


(defn yarn-timings
  ([yank-result]
   (yarn-timings yank-result (constantly true)))
  ([yank-result yarns]
   (yarn-timings yank-result yarns (constantly true)))
  ([yank-result yarns events]
   (when-let [ts (trace/find-traces yank-result)]
     (let [att (- 0 (long (reduce min (map :base-at ts))))
           h (java.util.HashMap.)]
       (doseq [t ts, log (:tracelog t)]
         (let [y (:yarn log)]
           (when (yarns y)
             (let [e (:event log)]
               (when-let [i (case e
                              ::trace/trace-start 0
                              ::trace/trace-call 1
                              ::trace/trace-finish 2
                              nil)]
                 (let [y (:yarn log)
                       v (long (:value log))
                       ^longs c (.get h y)]
                   (if c
                     (aset c i (+ v att))
                     (let [c (long-array 3)]
                       (.put h y c)
                       (aset c i (+ v att))))))))))
       (let [emit-yank (events :yank-at)
             emit-call (events :call-at)
             emit-done (events :done-at)
             emit-deps-time (events :deps-time)
             emit-time (events :time)]
         (eduction
          (mapcat (fn [kv]
                    (let [k (key kv)
                          ^longs v (val kv)
                          yank (aget v 0)
                          call (aget v 1)
                          done (aget v 2)]
                      [(when emit-yank [[k :yank-at] yank])
                       (when emit-call [[k :call-at] call])
                       (when emit-done [[k :done-at] done])
                       (when emit-deps-time [[k :deps-time] (safe-minus call yank)])
                       (when emit-time [[k :time] (safe-minus done call)])
                       ;;
                       ])))
          (filter #(some-> % (nth 1) (> 0)))
          (.entrySet h)))))))


(defn- fast-memoize1 [f]
  (let [h (java.util.concurrent.ConcurrentHashMap.)
        g (reify java.util.function.Function
            (apply [_ x] (f x)))]
    (fn [x] (.computeIfAbsent h x g))))


(defn timings-collector
  [& {:keys [yarns
             events
             window
             window-chunk
             precision
             percentiles
             ]
      :or {yarns  nil ;; all
           events #{:yank-at :call-at :done-at :deps-time :time}
           percentiles [50 90 95 99]
           precision 3
           window 60000
           }}]
  (let [yarns (if (some? yarns)
                (fast-memoize1 yarns)
                (constantly true))
        tracker (grouped-stats-tracker
                 (if (or (nil? window) (zero? window))
                   (partial simple-stats-tracker
                            {:precision precision
                             :percentiles percentiles})
                   (partial windowed-stats-tracker
                            {:precision precision
                             :window window
                             :window-chunk (or window-chunk (long (* 0.125 window)))
                             :percentiles percentiles})))]
     (fn
       ([]
        (->> (tracker)
             (group-by ffirst)
             (mapcat (fn [[y row]]
                       (map (fn [[[_ e] s]]
                              (into {:event e, :yarn y} s))
                            row)))))
       ([yank-result]
        (let [f (fn [x]
                  (when-some [s (trace/find-traces x)]
                    (tracker (yarn-timings s yarns events))))]
          (kd/listen! yank-result f f))
        yank-result))))

