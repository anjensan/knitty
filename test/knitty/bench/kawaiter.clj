(ns knitty.bench.kawaiter
  (:require
   [clojure.test :as t :refer [deftest]]
   [knitty.bench.bench-util :as bu :refer [apply-replicate-arg]]
   [knitty.deferred :as kd]
   [knitty.test-util :as tu]))


(set! *warn-on-reflection* true)
(set! *unchecked-math* true)

(t/use-fixtures :once
  (t/join-fixtures
   [(tu/tracing-enabled-fixture false)
    (bu/report-benchmark-fixture)]))

(defmacro dd []
  `(tu/ninl (kd/wrap 0)))

(defmacro ff []
  `(tu/ninl (doto (kd/create) (as-> d# (tu/defer! (kd/success! d# 0))))))

;; ===

(deftest ^:benchmark benchmark-kd-kawait-sync
  (bu/bench-suite
   (bu/eval-template
    (fn [a b] `(tu/bench ~a (apply-replicate-arg (kd/kd-await! tu/ninl-inc) ~b (dd))))
    (for [x (range 25)] [(keyword (str "kd-await-x" x)) x]))))

(deftest ^:benchmark benchmark-kd-kawait-async
  (bu/bench-suite
   (bu/eval-template
    (fn [a b] `(tu/bench ~a (tu/with-defer (apply-replicate-arg (kd/kd-await! tu/ninl-inc) ~b (ff)))))
    (for [x (range 25)] [(keyword (str "kd-await-x" x)) x]))))

(deftest ^:benchmark benchmark-kd-kawait-some-async
  (bu/bench-suite
   (bu/eval-template
    (fn [a b] `(tu/bench ~a (tu/with-defer (apply-replicate-arg (kd/kd-await! tu/ninl-inc) ~b (ff) (dd) (dd)))))
    (for [x (range 25)] [(keyword (str "kd-await-x" x)) x]))))


(comment
  (clojure.test/test-ns *ns*))
