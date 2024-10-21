(ns knitty.bench1
  (:require [clojure.test :as t :refer [deftest testing]]
            [knitty.core :refer [yank yank1]]
            [knitty.test-util :refer :all]
            [manifold.deferred :as md]))


(set! *warn-on-reflection* true)
(set! *unchecked-math* true)

(t/use-fixtures :once
  (t/join-fixtures
   [(tracing-enabled-fixture false)
    (report-benchmark-fixture)]))


(defn pyank [ids]
  #(yank % ids))


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
           @(md/chain'
             (reduce #(md/chain' %1 (pyank [%2])) {} ps)
             (pyank [(first nodes) (last nodes)])))))


(deftest ^:benchmark sync-futures-50
  (build-yarns-graph
   :ids (range 50)
   :prefix :node
   :deps linear-sync-deps
   :emit-body (fn [i & xs] `(mfut (reduce unchecked-add ~i [~@xs]) 3)))
  (run-benchs (nodes-range :node 50)))


(deftest ^:benchmark sync-futures-50-exp
  (build-yarns-graph
   :ids (range 50)
   :prefix :node
   :deps exp-sync-deps
   :emit-body (fn [i & xs] `(mfut (reduce unchecked-add ~i [~@xs]) 3)))
  (run-benchs (nodes-range :node 50)))


(deftest ^:benchmark sync-futures-200
  (build-yarns-graph
   :ids (range 200)
   :prefix :node
   :deps linear-sync-deps
   :emit-body (fn [i & xs] `(mfut (reduce unchecked-add ~i [~@xs]) 20)))
  (run-benchs (nodes-range :node 200)))


(deftest ^:benchmark g100-by-deptype
  (doseq [[nf f] [[:syn `do]
                  [:fut `md/future]]]
    (testing nf
      (doseq [tt [:sync :defer :lazy]]
        (build-yarns-graph
         :ids (range 100)
         :prefix :node
         :deps #(map vector (repeat tt) (exp-sync-deps %))
         :emit-body (fn [i & xs]
                      `(~f
                        (reduce
                         (fn [a# ~'x]
                           (kd/bind
                            a#
                            (fn [aa#]
                              (kd/bind
                               ~(if (= :lazy tt) `(deref ~'x) 'x)
                               (fn [xx#] (unchecked-add aa# xx#))))))
                         ~i
                         [~@xs]))))
        (bench tt (::node99 @(yank {} [::node99])))))))


(deftest ^:benchmark sync-nofutures-200
  (build-yarns-graph
   :ids (range 200)
   :prefix :node
   :deps linear-sync-deps
   :emit-body (fn [i & xs] `(reduce unchecked-add ~i [~@xs])))
  (run-benchs (nodes-range :node 200)))


(deftest ^:stress check-big-graph

  (build-yarns-graph
   :ids (range 1000)
   :deps (sample-sync-deps 2)
   :fork? (constantly true)
   :emit-body (fn [i & xs] `(mfut
                             (do
                               (reduce unchecked-add ~i [~@xs]))
                             10)))

  (dotimes-prn [_ 10000]
    (binding [knitty.core/*tracing* (rand-nth [false true])]
      @(yank {} (nodes-range :node 0 1000)
             #_(random-sample 0.01 (nodes-range :node 0 500))))))

