(ns knitty.bench.yank
  (:require
   [clojure.test :as t :refer [deftest testing]]
   [knitty.core :refer [yank yank1 yank*]]
   [knitty.deferred :as kd]
   [knitty.test-util :as tu :refer [bench build-yarns-graph dotimes-prn
                                    nodes-range]]))


(set! *warn-on-reflection* true)
(set! *unchecked-math* true)

(t/use-fixtures :once
  (t/join-fixtures
   [(tu/tracing-enabled-fixture false)
    (tu/report-benchmark-fixture)]))


(defn linear-sync-deps [c]
  (take-while
   nat-int?
   (nnext (reductions (fn [a x] (- a x)) c (range)))))


(defn exp-sync-deps [c]
  (map #(- c %)
       (butlast
        (take-while
         nat-int?
         (iterate #(min (dec %) (int (* % 0.71))) c)))))


(defn sample-sync-deps [avg-deps]
  (fn [c]
    (let [x (/ avg-deps (inc c))]
      (filter (fn [_] (> x (rand)))
              (range 0 c)))))


(defn- run-benchs [nodes]
  (let [ps (map #(nth nodes %) (range 0 (count nodes) 20))
        ls (last nodes)]

    (bench :yank-last
           @(yank {} [ls]))
    (bench :yank-last1
           @(yank1 {} ls))
    (bench :yank-all
           @(yank {} nodes))
    (bench :seq-yank
           @(kd/bind->
             (reduce #(kd/bind-> %1 (yank* [%2])) {} ps)
             (yank [(first nodes) (last nodes)])))))


(deftest ^:benchmark sync-futures-50
  (tu/bench-suite
   (build-yarns-graph
    :ids (range 50)
    :prefix :node
    :deps linear-sync-deps
    :emit-body (fn [i & xs] `(tu/mfut (reduce unchecked-add ~i [~@xs]) 3)))
   (run-benchs (nodes-range :node 50))))


(deftest ^:benchmark sync-futures-50-exp
  (tu/bench-suite
   (build-yarns-graph
    :ids (range 50)
    :prefix :node
    :deps exp-sync-deps
    :emit-body (fn [i & xs] `(tu/mfut (reduce unchecked-add ~i [~@xs]) 3)))
   (run-benchs (nodes-range :node 50))))


(deftest ^:benchmark sync-futures-200
  (tu/bench-suite
   (build-yarns-graph
    :ids (range 200)
    :prefix :node
    :deps linear-sync-deps
    :emit-body (fn [i & xs] `(tu/mfut (reduce unchecked-add ~i [~@xs]) 20)))
   (run-benchs (nodes-range :node 200))))


(deftest ^:benchmark g100-by-deptype
  (tu/bench-suite
   (doseq [[nf f] [[:syn `do]
                   [:fut `kd/future]]]
     (testing nf
       (doseq [tt [:sync :defer :lazy]]
         (build-yarns-graph
          :ids (range 100)
          :prefix :node
          :deps #(map vector (repeat tt) (exp-sync-deps %))
          :emit-body (fn [i & xs]
                       `(~f
                         (reduce
                          (fn [a# x#]
                            (kd/bind
                             (do a#)
                             (fn [aa#]
                               (kd/bind
                                (-> x# ~@(when (= :lazy tt) `[deref]))
                                (fn [xx#] (unchecked-add aa# xx#))))))
                          ~i
                          [~@xs]))))
         (bench tt (::node99 @(yank {} [::node99]))))))))


(deftest ^:benchmark sync-nofutures-200
  (tu/bench-suite
   (build-yarns-graph
    :ids (range 200)
    :prefix :node
    :deps linear-sync-deps
    :emit-body (fn [i & xs] `(reduce unchecked-add ~i [~@xs])))
   (run-benchs (nodes-range :node 200))))


(deftest ^:stress check-big-graph
  (tu/bench-suite
   (build-yarns-graph
    :ids (range 1000)
    :deps (sample-sync-deps 2)
    :fork? (constantly true)
    :emit-body (fn [i & xs] `(tu/mfut
                              (do
                                (reduce unchecked-add ~i [~@xs]))
                              10)))
   (dotimes-prn 100000
                (binding [knitty.core/*tracing* (rand-nth [false true])]
                  @(yank {} (random-sample 0.01 (nodes-range :node 0 500)))))))

