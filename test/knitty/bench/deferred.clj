(ns knitty.bench.deferred
  (:require
   [clojure.template :as tmpl]
   [clojure.test :as t :refer [deftest testing]]
   [knitty.deferred :as kd]
   [knitty.test-util :as tu :refer [bench bench-suite defer! ninl-inc
                                    with-defer]]
   [manifold.deferred :as md]))


(set! *warn-on-reflection* true)
(set! *unchecked-math* true)


(declare print-bench-matrix)

(t/use-fixtures :once
  (t/join-fixtures
   [(tu/tracing-enabled-fixture false)
    (tu/report-benchmark-fixture)
    (tu/disable-manifold-leak-detection-fixture)
    (fn [t] (t) (print-bench-matrix))]))


(defn infer-kt-vs-md-category [test-id]
  (let [s (set test-id)
        kt? (s :kt)
        md? (s :md)
        knitty? (s :knitty)
        manifold? (s :manifold)]
    [(vec (remove #{:kt :md :knitty :manifold} test-id))
     (cond
       (and knitty? md?) :knitty-md
       (and manifold? kt?) :manifold-kt
       knitty? :knitty
       manifold? :manifold)]))


(defn print-bench-matrix []
  (tu/print-benchmark-results-matrix
   infer-kt-vs-md-category
   [:knitty :manifold :knitty-md :manifold-kt]))



(declare dd)
(declare ff)

(defmacro manifold-dd [x]
  `(md/success-deferred ~x nil))

(defmacro knitty-dd [x]
  `(kd/wrap-val ~x))

(defmacro manifold-ff [x]
  `(doto (md/deferred nil)
     (as-> d# (defer! (md/success! d# ~x)))))

(defmacro knitty-ff [x]
  `(doto (kd/create)
     (as-> d# (defer! (kd/success! d# ~x)))))

(defmacro do-mode-futures [& body]
  `(tmpl/do-template
    [t# ~'dd ~'ff]
    (testing t# ~@body)
    :kt knitty-dd knitty-ff
    :md manifold-dd manifold-ff))



;; ===

(deftest ^:benchmark benchmark-chain
   (do-mode-futures

     (testing :manifold
       (bench-suite
        (tmpl/do-template
         [t create-d]
         (testing t
           (bench :chain-x1
                  @(with-defer (md/chain' create-d ninl-inc)))
           (bench :chain-x2
                  @(with-defer (md/chain' create-d ninl-inc ninl-inc)))
           (bench :chain-x3
                  @(with-defer (md/chain' create-d ninl-inc ninl-inc ninl-inc)))
           (bench :chain-x5
                  @(with-defer (md/chain' create-d ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc)))
           (bench :chain-x10
                  @(with-defer (md/chain' create-d ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc)))
           )
         :val (dd 0)
         :fun (ff 0))))

       (testing :knitty
         (bench-suite
          (tmpl/do-template
           [t create-d]
           (testing t
             (bench :chain-x1
                    @(with-defer (kd/bind-> create-d ninl-inc)))
             (bench :chain-x2
                    @(with-defer (kd/bind-> create-d ninl-inc ninl-inc)))
             (bench :chain-x3
                    @(with-defer (kd/bind-> create-d ninl-inc ninl-inc ninl-inc)))
             (bench :chain-x5
                    @(with-defer (kd/bind-> create-d ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc)))
             (bench :chain-x10
                    @(with-defer (kd/bind-> create-d ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc))))
            :val (dd 0)
            :fun (ff 0))))
   ))


(deftest ^:benchmark benchmark-let

  (do-mode-futures
   (testing :manifold
     (bench-suite
      (tu/with-md-executor
        (bench :let-x3v
               @(md/let-flow' [x1 (dd 0)
                               x2 (ninl-inc x1)
                               x3 (dd 0)]
                              (+ x1 x2 x3)))
        (bench :let-x5f
               @(md/let-flow' [x1 (dd 0)
                               x2 (tu/md-future (ninl-inc x1))
                               x3 (tu/md-future (ninl-inc x2))
                               x4 (tu/md-future (ninl-inc x3))
                               x5 (ninl-inc x4)]
                              x5)))))

   (testing :knitty
     (bench-suite
      (tu/with-md-executor
        (bench :let-x3v
               @(kd/let-bind [x1 (dd 0)
                              x2 (ninl-inc x1)
                              x3 (dd 0)]
                             (+ x1 x2 x3)))
        (bench :let-x5f
               @(kd/let-bind [x1 (dd 0)
                              x2 (tu/md-future (ninl-inc x1))
                              x3 (tu/md-future (ninl-inc x2))
                              x4 (tu/md-future (ninl-inc x3))
                              x5 (ninl-inc x4)]
                             x5))))))
  ;;
  )


(deftest ^:benchmark benchmark-zip
  (do-mode-futures

   (testing :manifold
     (bench-suite
      (bench :zip-1v @(md/zip' (dd 0)))
      (bench :zip-2v @(md/zip' (dd 0) (dd 1)))
      (bench :zip-5v @(md/zip' (dd 0) (dd 1) (dd 2) (dd 3) (dd 4)))
      (bench :zip-1f @(with-defer (md/zip' (ff 0))))
      (bench :zip-2f @(with-defer (md/zip' (ff 0) (ff 1))))
      (bench :zip-5f @(with-defer (md/zip' (ff 0) (ff 1) (ff 2) (ff 3) (ff 4))))))

   (testing :knitty
     (bench-suite
      (bench :zip-1v @(kd/zip (dd 0)))
      (bench :zip-2v @(kd/zip (dd 0) (dd 1)))
      (bench :zip-5v @(kd/zip (dd 0) (dd 1) (dd 2) (dd 3) (dd 4)))
      (bench :zip-1f @(with-defer (kd/zip (ff 0))))
      (bench :zip-2f @(with-defer (kd/zip (ff 0) (ff 1))))
      (bench :zip-5f @(with-defer (kd/zip (ff 0) (ff 1) (ff 2) (ff 3) (ff 4)))))))
  ;;
  )


(deftest ^:benchmark benchmark-zip-list

  (do-mode-futures
   (testing :manifold
     (bench-suite
      (bench :zip-50v  (doall @(apply md/zip' (repeat 50 (dd 0)))))
      (bench :zip-50f  (doall @(with-defer (apply md/zip' (doall (repeatedly 50 #(ff 0)))))))
      (bench :zip-200v (doall @(apply md/zip' (repeat 200 (dd 0)))))
      (bench :zip-200f (doall @(with-defer (apply md/zip' (doall (repeatedly 200 #(ff 0)))))))
      ))

   (testing :knitty
     (bench-suite
      (bench :zip-50v  (doall @(kd/zip* (repeat 50 (dd 0)))))
      (bench :zip-50f  (doall @(with-defer (kd/zip* (doall (repeatedly 50 #(ff 0)))))))
      (bench :zip-200v (doall @(kd/zip* (repeat 200 (dd 0)))))
      (bench :zip-200f (doall @(with-defer (kd/zip* (doall (repeatedly 200 #(ff 0)))))))
      )))
  ;;
  )


(deftest ^:benchmark benchmark-alt

  (do-mode-futures
   (testing :manifold
     (bench-suite
      (bench :alt-2v @(md/alt' (dd 0) (dd 1)))
      (bench :alt-2f @(with-defer (md/alt' (ff 0) (ff 0))))
      (bench :alt-3v @(md/alt' (dd 0) (dd 1) (dd 2)))
      (bench :alt-3f @(with-defer (md/alt' (ff 0) (ff 1) (ff 2))))
      (bench :alt-9v @(md/alt' (dd 0) (dd 1) (dd 2) (dd 3) (dd 4) (dd 5) (dd 6) (dd 7) (dd 8)))
      (bench :alt-9f @(with-defer (md/alt' (ff 0) (ff 1) (ff 2) (ff 3) (ff 4) (ff 5) (ff 6) (ff 7) (ff 8)))))))

   (testing :knitty
     (bench-suite
      (bench :alt-2v @(kd/alt (dd 0) (dd 1)))
      (bench :alt-2f @(with-defer (kd/alt (ff 0) (ff 0))))
      (bench :alt-3v @(kd/alt (dd 0) (dd 1) (dd 2)))
      (bench :alt-3f @(with-defer (kd/alt (ff 0) (ff 1) (ff 2))))
      (bench :alt-9v @(kd/alt (dd 0) (dd 1) (dd 2) (dd 3) (dd 4) (dd 5) (dd 6) (dd 7) (dd 8)))
      (bench :alt-9f @(with-defer (kd/alt (ff 0) (ff 1) (ff 2) (ff 3) (ff 4) (ff 5) (ff 6) (ff 7) (ff 8))))))
  ;;
  )


(deftest ^:benchmark benchmark-loop

  (do-mode-futures
   (testing :manifold
     (bench-suite
      (bench :loop100v
             @(tu/with-defer
                (md/loop [x (ff 0)]
                  (md/chain'
                   x
                   (fn [x]
                     (if (< x 1000)
                       (md/recur (ff (ninl-inc x)))
                       x))))))
      (tu/with-md-executor
        (bench :loop100f
               @(md/loop [x (tu/md-future 0)]
                  (md/chain'
                   x
                   (fn [x]
                     (if (< x 100)
                       (md/recur (tu/md-future (ninl-inc x)))
                       x))))))))

   (testing :knitty
     (bench-suite
      (bench :loop100
             @(tu/with-defer
                (kd/loop [x (ff 0)]
                  (if (< x 1000)
                    (kd/recur (ff (ninl-inc x)))
                    x))))
      (tu/with-md-executor
        (bench :loop100
               @(kd/loop [x (tu/kt-future 0)]
                  (if (< x 100)
                    (kd/recur (tu/kt-future (ninl-inc x)))
                    x))))
      )))
  ;;
  )


(deftest ^:benchmark bench-deferred
  (tmpl/do-template
   [t create-d]
   (testing t
     (bench-suite
      (let [ls (md/listener (fn [_] nil))]
        (bench :create
               create-d)
        (bench :listener
               (let [d create-d]
                 (md/add-listener! d ls)
                 (md/success! d 1)))
        (bench :add-listener-3
               (let [d create-d]
                 (md/add-listener! d ls)
                 (md/add-listener! d ls)
                 (md/add-listener! d ls)
                 (md/success! d 1)))
        (bench :add-listener-10
               (let [d create-d]
                 (dotimes [_ 10]
                   (md/add-listener! d ls))
                 (md/success! d 1)))
        (bench :add-listener-33
               (let [d create-d]
                 (dotimes [_ 33]
                   (md/add-listener! d ls))
                 (md/success! d 1)))
        (bench :suc-add-listener
               (let [d create-d]
                 (md/success! d 1)
                 (md/add-listener! d ls)))
        (bench :success-get
               (let [d create-d]
                 (md/success! d 1)
                 (md/success-value d 2)))
        (bench :success-deref
               (let [d create-d]
                 (md/success! d 1)
                 @d))
        (bench :success-kdpeel
               (let [d create-d]
                 (md/success! d 1)
                 (kd/peel d))))))

   :manifold (md/deferred nil)
   :knitty (kd/create)))


(comment
  (clojure.test/test-ns *ns*))
