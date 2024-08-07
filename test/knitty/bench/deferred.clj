(ns knitty.bench.deferred
  (:require [clojure.test :as t :refer [deftest testing]]
            [knitty.test-util :refer :all]
            [knitty.deferred :as kd]
            [manifold.debug :as debug]
            [manifold.deferred :as md]
            [promesa.core :as pc])
  (:import [java.util.concurrent Executor Executors]))


(set! *warn-on-reflection* true)
(set! *unchecked-math* true)


(defn disable-manifold-leak-detection-fixture
  []
  (fn [t]
    (let [e debug/*dropped-error-logging-enabled?*]
      (debug/disable-dropped-error-logging!)
      (try (t)
           (finally
             (.bindRoot #'debug/*dropped-error-logging-enabled?* e))))))

(t/use-fixtures :once
  (t/join-fixtures
   [(tracing-enabled-fixture false)
    (report-benchmark-fixture)
    (disable-manifold-leak-detection-fixture)]))


(defmacro d0 []
  `(md/success-deferred 0 nil))

(defmacro d0-pc []
  `(pc/promise 0))


(def ^:dynamic *ff-callbacks* nil)

(defmacro with-defer [body]
  `(let [ff-callbacks# (java.util.ArrayList.)]
     (try
      (binding [*ff-callbacks* ff-callbacks#] ~body)
      (finally
        (run! #(%) ff-callbacks#)))))

(def ^:private ^java.util.Random dffr-rnd
  (java.util.Random.))

(defmacro defer! [callback]
  `(let [^java.util.ArrayList cs# *ff-callbacks*
         n# (.size cs#)]
     (.add cs# (fn [] ~callback))
     (java.util.Collections/swap cs# (.nextInt dffr-rnd (unchecked-inc-int n#)) n#)))


(defmacro ff0 []
  `(let [d# (md/deferred nil)]
     (defer! (md/success! d# 0))
     d#))

(defmacro ff0-pc []
  `(pc/promise
    (fn [resolve# _reject#]
      (defer! (resolve# 0)))))



(def always-false false)

(defn incx [^long x]
  (when always-false (for [x x] (for [x x] (for [x x] (for [x x] (for [x x] x))))))
  (unchecked-inc x))


(deftest ^:benchmark benchmark-chain

  (doseq [[t create-d] [[:sync #(do 0)]
                        [:realized #(d0)]
                        [:future #(ff0)]]]
    (testing t

      (testing :manifold

        (bench :chain-x1
               @(with-defer (md/chain' (create-d) incx)))
        (bench :chain-x2
               @(with-defer (md/chain' (create-d) incx incx)))
        (bench :chain-x5
               @(with-defer (md/chain' (create-d) incx incx incx incx incx)))
        (bench :chain-x10
               @(with-defer (md/chain' (create-d) incx incx incx incx incx incx incx incx incx incx))))

      (testing :knitty

        (bench :chain-x1
               @(with-defer (kd/bind-> (create-d) incx)))
        (bench :chain-x2
               @(with-defer (kd/bind-> (create-d) incx incx)))
        (bench :chain-x5
               @(with-defer (kd/bind-> (create-d) incx incx incx incx incx)))
        (bench :chain-x10
               @(with-defer (kd/bind-> (create-d) incx incx incx incx incx incx incx incx incx incx))))

      (testing :promesa

        (bench :chain-x1
               @(with-defer (pc/-> (create-d) incx)))
        (bench :chain-x2
               @(with-defer (pc/-> (create-d) incx incx)))
        (bench :chain-x5
               @(with-defer (pc/-> (create-d) incx incx incx incx incx)))
        (bench :chain-x10
               @(with-defer (pc/-> (create-d) incx incx incx incx incx incx incx incx incx incx)))))
    ;;
    ))


(deftest ^:benchmark benchmark-let

  (testing :manifold
    (bench :let-x3-sync
           @(md/let-flow' [x1 (d0)
                           x2 (incx x1)
                           x3 (d0)]
                          (+ x1 x2 x3)))
    (bench :let-x5
           @(md/let-flow' [x1 (d0)
                           x2 (incx x1)
                           x3 (+ x2)
                           x4 (incx x3)
                           x5 (incx x4)]
                          x5)))

  (testing :knitty
    (bench :let-x3-sync
           @(kd/letm [x1 (d0)
                      x2 (incx x1)
                      x3 (d0)]
                     (+ x1 x2 x3)))
    (bench :let-x5
           @(kd/letm [x1 (d0)
                      x2 (incx x1)
                      x3 (md/future x2)
                      x4 (incx x3)
                      x5 (incx x4)]
                     x5)))

  (testing :promesa
    (bench :let-x3-sync
           @(pc/let* [x1 (d0)
                      x2 (incx x1)
                      x3 (d0)]
                     (+ x1 x2 x3)))
    (bench :let-x5
           @(pc/let* [x1 (d0)
                      x2 (incx x1)
                      x3 (md/future x2)
                      x4 (incx x3)
                      x5 (incx x4)]
                     x5)))
  ;;
  )

(deftest ^:benchmark benchmark-zip-sync

  (testing :manifold
    (bench :zip-d1 @(md/zip' (d0)))
    (bench :zip-d2 @(md/zip' (d0) (d0)))
    (bench :zip-d5 @(md/zip' (d0) (d0) (d0) (d0) (d0)))
    (bench :zip-d10 @(md/zip' (d0) (d0) (d0) (d0) (d0) (d0) (d0) (d0) (d0) (d0)))
    (bench :zip-d20 @(md/zip' (d0) (d0) (d0) (d0) (d0) (d0) (d0) (d0) (d0) (d0)
                              (d0) (d0) (d0) (d0) (d0) (d0) (d0) (d0) (d0) (d0))))


  (testing :knitty
    (bench :zip-d1 @(kd/zip (d0)))
    (bench :zip-d2 @(kd/zip (d0) (d0)))
    (bench :zip-d5 @(kd/zip (d0) (d0) (d0) (d0) (d0)))
    (bench :zip-d10 @(kd/zip (d0) (d0) (d0) (d0) (d0) (d0) (d0) (d0) (d0) (d0)))
    (bench :zip-d20 @(kd/zip (d0) (d0) (d0) (d0) (d0) (d0) (d0) (d0) (d0) (d0)
                             (d0) (d0) (d0) (d0) (d0) (d0) (d0) (d0) (d0) (d0))))

  (testing :promesa
    (bench :zip-d1 @(pc/all [(d0-pc)]))
    (bench :zip-d2 @(pc/all [(d0-pc) (d0-pc)]))
    (bench :zip-d5 @(pc/all [(d0-pc) (d0-pc) (d0-pc) (d0-pc) (d0-pc)]))
    (bench :zip-d10 @(pc/all [(d0-pc) (d0-pc) (d0-pc) (d0-pc) (d0-pc) (d0-pc) (d0-pc) (d0-pc) (d0-pc) (d0-pc)]))
    (bench :zip-d20 @(pc/all [(d0-pc) (d0-pc) (d0-pc) (d0-pc) (d0-pc) (d0-pc) (d0-pc) (d0-pc) (d0-pc) (d0-pc)
                              (d0-pc) (d0-pc) (d0-pc) (d0-pc) (d0-pc) (d0-pc) (d0-pc) (d0-pc) (d0-pc) (d0-pc)])))
  ;;
  )


(deftest ^:benchmark benchmark-zip-vals

  (testing :manifold
    (bench :zip-v1 @(md/zip' 0))
    (bench :zip-v2 @(md/zip' 0 0))
    (bench :zip-v5 @(md/zip' 0 0 0 0 0))
    (bench :zip-v10 @(md/zip' 0 0 0 0 0 0 0 0 0 0))
    (bench :zip-v20 @(md/zip' 0 0 0 0 0 0 0 0 0 0
                              0 0 0 0 0 0 0 0 0 0)))


  (testing :knitty
    (bench :zip-v1 @(kd/zip 0))
    (bench :zip-v2 @(kd/zip 0 0))
    (bench :zip-v5 @(kd/zip 0 0 0 0 0))
    (bench :zip-v10 @(kd/zip 0 0 0 0 0 0 0 0 0 0))
    (bench :zip-v20 @(kd/zip 0 0 0 0 0 0 0 0 0 0
                             0 0 0 0 0 0 0 0 0 0)))

  (testing :promesa
    (bench :zip-v1 @(pc/all [0]))
    (bench :zip-v2 @(pc/all [0 0]))
    (bench :zip-v5 @(pc/all [0 0 0 0 0]))
    (bench :zip-v10 @(pc/all [0 0 0 0 0 0 0 0 0 0]))
    (bench :zip-v20 @(pc/all [0 0 0 0 0 0 0 0 0 0
                              0 0 0 0 0 0 0 0 0 0])))
  ;;
  )

(deftest ^:benchmark benchmark-zip-async

  (testing :manifold
    (bench :zip-f1 @(with-defer (md/zip' (ff0))))
    (bench :zip-f2 @(with-defer (md/zip' (ff0) (ff0))))
    (bench :zip-f5 @(with-defer (md/zip' (ff0) (ff0) (ff0) (ff0) (ff0)))))

  (testing :knitty
    (bench :zip-f1 @(with-defer (kd/zip (ff0))))
    (bench :zip-f2 @(with-defer (kd/zip (ff0) (ff0))))
    (bench :zip-f5 @(with-defer (kd/zip (ff0) (ff0) (ff0) (ff0) (ff0)))))

  (testing :promesa
    (bench :zip-f1 @(with-defer (pc/all [(ff0-pc)])))
    (bench :zip-f2 @(with-defer (pc/all [(ff0-pc) (ff0-pc)])))
    (bench :zip-f5 @(with-defer (pc/all [(ff0-pc) (ff0-pc) (ff0-pc) (ff0-pc) (ff0-pc)]))))
  ;;
  )

(deftest ^:benchmark benchmark-zip-list-sync

  (testing :manifold
    (bench :zip-50 (doall @(apply md/zip' (repeat 50 (d0)))))
    (bench :zip-200 (doall @(apply md/zip' (repeat 200 (d0))))))

  (testing :knitty
    (bench :zip-50 (doall @(kd/zip* (repeat 50 (d0)))))
    (bench :zip-200 (doall @(kd/zip* (repeat 200 (d0))))))

  (testing :knitty-kd
    (bench :zip-50 (doall @(kd/zip* (repeat 50 (kd/wrap 0)))))
    (bench :zip-200 (doall @(kd/zip* (repeat 200 (kd/wrap 0))))))

  (testing :promesa
    (bench :zip-50 (doall @(pc/all (repeat 50 (d0-pc)))))
    (bench :zip-200 (doall @(pc/all (repeat 200 (d0-pc))))))

  ;;
  )

(deftest ^:benchmark benchmark-zip-list-async

  (testing :manifold
    (bench :zip-50 (doall @(with-defer (apply md/zip' (vec (repeatedly 50 #(ff0)))))))
    (bench :zip-200 (doall @(with-defer (apply md/zip' (vec (repeatedly 200 #(ff0))))))))

  (testing :knitty
    (bench :zip-50 (doall @(with-defer (kd/zip* (vec (repeatedly 50 #(ff0)))))))
    (bench :zip-200 (doall @(with-defer (kd/zip* (vec (repeatedly 200 #(ff0))))))))

  (testing :promesa
    (bench :zip-50 (doall @(with-defer (pc/all (vec (repeatedly 50 #(ff0-pc)))))))
    (bench :zip-200 (doall @(with-defer (pc/all (vec (repeatedly 200 #(ff0-pc))))))))

  ;;
  )

(deftest ^:benchmark benchmark-alt-sync

  (testing :manifold
    (bench :alt-2 @(md/alt' (d0) (d0)))
    (bench :alt-3 @(md/alt' (d0) (d0) (d0)))
    (bench :alt-10 @(md/alt' (d0) (d0) (d0) (d0) (d0) (d0) (d0) (d0) (d0) (d0))))

  (testing :knitty
    (bench :alt-2 @(kd/alt (d0) (d0)))
    (bench :alt-3 @(kd/alt (d0) (d0) (d0)))
    (bench :alt-10 @(kd/alt (d0) (d0) (d0) (d0) (d0) (d0) (d0) (d0) (d0) (d0))))

  ;;
  )

(deftest ^:benchmark benchmark-alt-async

  (testing :manifold
    (bench :alt-2 @(with-defer (md/alt' (ff0) (ff0))))
    (bench :alt-3 @(with-defer (md/alt' (ff0) (ff0) (ff0))))
    (bench :alt-10 @(with-defer (md/alt' (ff0) (ff0) (ff0) (ff0) (ff0) (ff0) (ff0) (ff0) (ff0) (ff0)))))

  (testing :knitty
    (bench :alt-2 @(with-defer (kd/alt (ff0) (ff0))))
    (bench :alt-3 @(with-defer (kd/alt (ff0) (ff0) (ff0))))
    (bench :alt-10 @(with-defer (kd/alt (ff0) (ff0) (ff0) (ff0) (ff0) (ff0) (ff0) (ff0) (ff0) (ff0)))))

   ;;
  )

(deftest ^:benchmark benchmark-loop-fut

  (testing :manifold
    (bench :loop100
           @(md/loop [x (md/future 0)]
              (md/chain'
               x
               (fn [x]
                 (if (< x 100)
                   (md/recur (md/future (incx x)))
                   x))))))

  (testing :knitty-loop
    (bench :loop100
           @(kd/loop [x (md/future 0)]
              (if (< x 100)
                (kd/recur (md/future (incx x)))
                x))))

  (testing :knitty-reduce
    (bench :loop100
           @(kd/reduce
             (fn [x _] (if (< x 100) (md/future (incx x)) (reduced x)))
             (md/future 0)
             (range))))

  (testing :knitty-iterate
    (bench :loop100
           @(kd/iterate-while
             (fn [x] (md/future (incx x)))
             (fn [x] (< x 100))
             (md/future 0))))

  (testing :promesa
    (bench :loop100
           @(pc/loop [x (pc/future 0)]
              (if (< x 100)
                (pc/recur (pc/future (incx x)))
                x))))

  ;;
  )

(deftest ^:benchmark bench-deferred

  (let [ls (md/listener (fn [_] nil))]
    (doseq [[t create-d] [[:manifold #(md/deferred nil)]
                          [:knitty #(kd/create)]]]
      (testing t
        (bench :create
               (create-d))
        (bench :listener
               (let [d (create-d)]
                 (md/add-listener! d ls)
                 (md/success! d 1)))
        (bench :add-listener-3
               (let [d (create-d)]
                 (md/add-listener! d ls)
                 (md/add-listener! d ls)
                 (md/add-listener! d ls)
                 (md/success! d 1)))
        (bench :add-listener-10
               (let [d (create-d)]
                 (dotimes [_ 10]
                   (md/add-listener! d ls))
                 (md/success! d 1)))
        (bench :add-listener-33
               (let [d (create-d)]
                 (dotimes [_ 33]
                   (md/add-listener! d ls))
                 (md/success! d 1)))
        (bench :suc-add-listener
               (let [d (create-d)]
                 (md/success! d 1)
                 (md/add-listener! d ls)))
        (bench :success-get
               (let [d (create-d)]
                 (md/success! d 1)
                 (md/success-value d 2)))
        (bench :success-deref
               (let [d (create-d)]
                 (md/success! d 1)
                 @d))))))


(comment

  (require '[clj-async-profiler.core :as prof])

  (prof/serve-files 8888)

  (dotimes [_ 1000000]
    (doall @(kd/zip* (repeat 1000 (d0)))))

  (prof/profile
   (dotimes [_ 10000000]
     (doall @(kd/zip* (repeat 100 (d0))))))


  (+ 1 2))