(ns knitty.bench.kawaiter
  (:require
   [clojure.test :as t :refer [deftest]]
   [knitty.deferred :as kd]
   [knitty.test-util :as tu]))


(set! *warn-on-reflection* true)
(set! *unchecked-math* true)

(t/use-fixtures :once
  (t/join-fixtures
   [(tu/tracing-enabled-fixture false)
    (tu/report-benchmark-fixture)]))

(defmacro dd []
  `(tu/ninl (kd/wrap 0)))

(defmacro ff []
  `(tu/ninl (doto (kd/create) (as-> d# (tu/defer! (kd/success! d# 0))))))

(defmacro replicate-arg [f n & args]
  `(~@f ~@(take n (cycle args))))

;; ===

(deftest ^:benchmark benchmark-kd-kawait-sync
  (tu/bench-suite
   (tu/eval-template
    (fn [a b] `(tu/bench ~a (replicate-arg (kd/kd-await! tu/ninl-inc) ~b (dd))))
    (for [x (range 25)] [(keyword (str "kd-await-x" x)) x]))))

(deftest ^:benchmark benchmark-kd-kawait-async
  (tu/bench-suite
   (tu/eval-template
    (fn [a b] `(tu/bench ~a (tu/with-defer (replicate-arg (kd/kd-await! tu/ninl-inc) ~b (ff)))))
    (for [x (range 25)] [(keyword (str "kd-await-x" x)) x]))))

(deftest ^:benchmark benchmark-kd-kawait-some-async
  (tu/bench-suite
   (tu/eval-template
    (fn [a b] `(tu/bench ~a (tu/with-defer (replicate-arg (kd/kd-await! tu/ninl-inc) ~b (ff) (dd) (dd)))))
    (for [x (range 25)] [(keyword (str "kd-await-x" x)) x]))))


(comment
  (clojure.test/test-ns *ns*))
