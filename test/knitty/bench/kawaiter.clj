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
  `(bu/ninl (kd/wrap 0)))

(defmacro ff []
  `(bu/ninl (doto (kd/create) (as-> d# (bu/defer! (kd/success! d# 0))))))

;; ===

(deftest ^:benchmark benchmark-kd-kawait-sync
  (bu/bench-suite
   (bu/eval-template
    (fn [a b] `(bu/bench ~a (apply-replicate-arg (kd/kd-await! tu/ninl-inc) ~b (dd))))
    (for [x (range 25)] [(keyword (str "kd-await-x" x)) x]))))

(deftest ^:benchmark benchmark-kd-kawait-async
  (bu/bench-suite
   (bu/eval-template
    (fn [a b] `(bu/bench ~a (bu/with-defer (apply-replicate-arg (kd/kd-await! bu/ninl-inc) ~b (ff)))))
    (for [x (range 25)] [(keyword (str "kd-await-x" x)) x]))))

(deftest ^:benchmark benchmark-kd-kawait-some-async
  (bu/bench-suite
   (bu/eval-template
    (fn [a b] `(bu/bench ~a (bu/with-defer (apply-replicate-arg (kd/kd-await! bu/ninl-inc) ~b (ff) (dd) (dd)))))
    (for [x (range 25)] [(keyword (str "kd-await-x" x)) x]))))


(comment
  (clojure.test/test-ns *ns*))
