(ns knitty.bench.vs-bench
  (:require
   [clojure.test :as t :refer [deftest testing]]
   [knitty.bench.bench-util :as bu :refer [bench bench-suite]]
   [knitty.core :as kt :refer [defyarn yank yank1 yarn]]
   [plumbing.core :as pc]
   [plumbing.graph :as pg]))


(t/use-fixtures :once
  (t/join-fixtures
   [(bu/report-benchmark-fixture)]))


;; plain Fn

(defn stats-fn
  [xs]
  (let [n  (count xs)
        m  (/ (pc/sum identity xs) n)
        m2 (/ (pc/sum #(* % %) xs) n)
        v  (- m2 (* m m))]
    {:n n   ; count
     :m m   ; mean
     :m2 m2 ; mean-square
     :v v   ; variance
     }))


;; Plumbing Graph

#_{:clj-kondo/ignore [:unresolved-symbol]}
(def stats-pg-g
  (pg/compile
   {:n  (pc/fnk [xs]   (count xs))
    :m  (pc/fnk [xs n] (/ (pc/sum identity xs) n))
    :m2 (pc/fnk [xs n] (/ (pc/sum #(* % %) xs) n))
    :v  (pc/fnk [m m2] (- m2 (* m m)))}))

(defn stats-pg [x]
  (stats-pg-g {:xs x}))


;; Memoized Map

(defmacro assoc-when-miss [ctx key dep-fns expr]
  {:pre [(symbol? ctx), (keyword? key)]}
  `(if (contains? ~ctx ~key)
     ~ctx
     (-> ~ctx
         ~@dep-fns
         (as-> ~ctx (assoc ~ctx ~key ~expr)))))

(defn stats-mm-n [ctx]
  (assoc-when-miss ctx ::n [] (count (::xs ctx))))

(defn stats-mm-m [ctx]
  (assoc-when-miss ctx ::m [stats-mm-n] (/ (pc/sum identity (::xs ctx)) (::n ctx))))

(defn stats-mm-m2 [ctx]
  (assoc-when-miss ctx ::m2 [stats-mm-n] (/ (pc/sum #(* % %) (::xs ctx)) (::n ctx))))

(defn stats-mm-v [ctx]
  (assoc-when-miss ctx ::v [stats-mm-m stats-mm-m2] (- (::m2 ctx) (let [m (::m ctx)] (* m m)))))

(defn stats-mm [x]
  (-> {::xs x}
      (stats-mm-m)
      (stats-mm-m2)
      (stats-mm-v)))


;; KniTty

(defyarn xs)

(defyarn n {xs xs}
  (count xs))

(defyarn m {xs xs, n n}
  (/ (pc/sum identity xs) n))

(defyarn m2 {xs xs, n n}
  (/ (pc/sum #(* % %) xs) n))

(defyarn v {m m, m2 m2}
  (- m2 (* m m)))

(defn stats-kt [x]
  @(yank {::xs x} [m m2 v]))

(defn stats-kt1 [x]
  @(yank1 {::xs x} (yarn ::stats {m m, m2 m2, v v} {:m m, :m2 m2, :v v})))


;; bench

(deftest ^:benchmark stats-simple
  (testing :simple
    (bench-suite
     (bench :list1 (stats-fn [1]))
     (bench :list5 (stats-fn [1 2 3 5 7])))))

(deftest ^:benchmark stats-naive
  (testing :naive
    (bench-suite
     (bench :list1 (stats-mm [1]))
     (bench :list5 (stats-mm [1 2 3 5 7])))))

(deftest ^:benchmark stats-pl-graph
  (testing :pl-graph
    (bench-suite
     (bench :list1 (stats-pg [1]))
     (bench :list5 (stats-pg [1 2 3 5 7])))))

(deftest ^:benchmark stats-knitty
  (testing :knitty
    (bench-suite
     (kt/set-executor! nil)
     (kt/enable-tracing! false)
     (bench :list1 (stats-kt [1]))
     (bench :list5 (stats-kt [1 2 3 5 7])))))

(deftest ^:benchmark stats-knitty1
  (testing :knitty1
    (bench-suite
     (kt/set-executor! nil)
     (kt/enable-tracing! false)
     (bench :list1 (stats-kt1 [1]))
     (bench :list5 (stats-kt1 [1 2 3 5 7])))))
