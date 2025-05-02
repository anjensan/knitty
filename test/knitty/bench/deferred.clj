(ns knitty.bench.deferred
  (:require
   [clojure.test :as t :refer [deftest testing]]
   [knitty.deferred :as kd]
   [knitty.test-util :as tu :refer [bench ninl-inc with-defer defer!]]
   [manifold.deferred :as md]
   [clojure.template :as tmpl]
   ))

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

(declare dd)
(declare ff)

(defmacro do-mode-futures [& body]
  `(tmpl/do-template
    [t# ~'dd ~'ff]
    (testing t# ~@body)
    :kt knitty-dd knitty-ff
    :md manifold-dd manifold-ff))


;; ===

(deftest ^:benchmark benchmark-chain

  (tmpl/do-template
   [t1 t2 create-d]

   (testing t1
     (testing t2

       (testing :manifold

         (bench :chain-x1
                @(with-defer (md/chain' create-d ninl-inc)))
         (bench :chain-x2
                @(with-defer (md/chain' create-d ninl-inc ninl-inc)))
         (bench :chain-x3
                @(with-defer (md/chain' create-d ninl-inc ninl-inc ninl-inc)))
         (bench :chain-x5
                @(with-defer (md/chain' create-d ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc)))
         (bench :chain-x10
                @(with-defer (md/chain' create-d ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc))))

       (testing :knitty

         (bench :chain-x1
                @(with-defer (kd/bind-> create-d ninl-inc)))
         (bench :chain-x2
                @(with-defer (kd/bind-> create-d ninl-inc ninl-inc)))
         (bench :chain-x3
                @(with-defer (kd/bind-> create-d ninl-inc ninl-inc ninl-inc)))
         (bench :chain-x5
                @(with-defer (kd/bind-> create-d ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc)))
         (bench :chain-x10
                @(with-defer (kd/bind-> create-d ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc)))))
     )

   :sync  :sync       0
   :md    :realized   (manifold-dd 0)
   :md    :future     (manifold-ff 0)
   :kt    :realized   (knitty-dd 0)
   :kt    :future     (knitty-ff 0)
   ))


(deftest ^:benchmark benchmark-let

  (do-mode-futures
   (tu/with-md-executor
     (testing :manifold
       (bench :let-x3-sync
              @(md/let-flow' [x1 (dd 0)
                              x2 (ninl-inc x1)
                              x3 (dd 0)]
                             (+ x1 x2 x3)))
       (bench :let-x5
              @(md/let-flow' [x1 (dd 0)
                              x2 (tu/md-future (ninl-inc x1))
                              x3 (tu/md-future (ninl-inc x2))
                              x4 (tu/md-future (ninl-inc x3))
                              x5 (ninl-inc x4)]
                             x5))))

   (tu/with-md-executor
     (testing :knitty
       (bench :let-x3-sync
              @(kd/let-bind [x1 (dd 0)
                             x2 (ninl-inc x1)
                             x3 (dd 0)]
                            (+ x1 x2 x3)))
       (bench :let-x5
              @(kd/let-bind [x1 (dd 0)
                             x2 (tu/md-future (ninl-inc x1))
                             x3 (tu/md-future (ninl-inc x2))
                             x4 (tu/md-future (ninl-inc x3))
                             x5 (ninl-inc x4)]
                            x5)))))
  ;;
  )

(deftest ^:benchmark benchmark-zip-sync

  (do-mode-futures

   (testing :manifold
     (bench :zip-d1 @(md/zip' (dd 0)))
     (bench :zip-d2 @(md/zip' (dd 0) (dd 1)))
     (bench :zip-d5 @(md/zip' (dd 0) (dd 1) (dd 2) (dd 3) (dd 4)))
     (bench :zip-d10 @(md/zip' (dd 0) (dd 1) (dd 2) (dd 3) (dd 4) (dd 5) (dd 6) (dd 7) (dd 8) (dd 9)))
     (bench :zip-d20 @(md/zip' (dd 0) (dd 1) (dd 2) (dd 3) (dd 4) (dd 5) (dd 6) (dd 7) (dd 8) (dd 9)
                               (dd 0) (dd 1) (dd 2) (dd 3) (dd 4) (dd 5) (dd 6) (dd 7) (dd 8) (dd 9))))

   (testing :knitty
     (bench :zip-d1 @(kd/zip (dd 0)))
     (bench :zip-d2 @(kd/zip (dd 0) (dd 1)))
     (bench :zip-d5 @(kd/zip (dd 0) (dd 1) (dd 2) (dd 3) (dd 4)))
     (bench :zip-d10 @(kd/zip (dd 0) (dd 1) (dd 2) (dd 3) (dd 4) (dd 5) (dd 6) (dd 7) (dd 8) (dd 9)))
     (bench :zip-d20 @(kd/zip (dd 0) (dd 1) (dd 2) (dd 3) (dd 4) (dd 5) (dd 6) (dd 7) (dd 8) (dd 9)
                              (dd 0) (dd 1) (dd 2) (dd 3) (dd 4) (dd 5) (dd 6) (dd 7) (dd 8) (dd 9)))))
  )


(deftest ^:benchmark benchmark-zip-vals

  (testing :manifold
    (bench :zip-v1 @(md/zip' 0))
    (bench :zip-v2 @(md/zip' 0 0))
    (bench :zip-v5 @(md/zip' 0 1 2 3 4))
    (bench :zip-v10 @(md/zip' 0 1 2 3 4 5 6 7 8 9))
    (bench :zip-v20 @(md/zip' 0 1 2 3 4 5 6 7 8 9
                              0 1 2 3 4 5 6 7 8 9)))

  (testing :knitty
    (bench :zip-v1 @(kd/zip 0))
    (bench :zip-v2 @(kd/zip 0 0))
    (bench :zip-v5 @(kd/zip 0 1 2 3 4))
    (bench :zip-v10 @(kd/zip 0 1 2 3 4 5 6 7 8 9))
    (bench :zip-v20 @(kd/zip 0 1 2 3 4 5 6 7 8 9
                             0 1 2 3 4 5 6 7 8 9)))
  ;;
  )

(deftest ^:benchmark benchmark-zip-async

  (do-mode-futures
   (testing :manifold
     (bench :zip-f1 @(with-defer (md/zip' (ff 0))))
     (bench :zip-f2 @(with-defer (md/zip' (ff 0) (ff 1))))
     (bench :zip-f5 @(with-defer (md/zip' (ff 0) (ff 1) (ff 2) (ff 3) (ff 4)))))

   (testing :knitty
     (bench :zip-f1 @(with-defer (kd/zip (ff 0))))
     (bench :zip-f2 @(with-defer (kd/zip (ff 0) (ff 1))))
     (bench :zip-f5 @(with-defer (kd/zip (ff 0) (ff 1) (ff 2) (ff 3) (ff 4))))))
  ;;
  )

(deftest ^:benchmark benchmark-zip-list-sync

  (do-mode-futures
   (testing :manifold
     (bench :zip-50 (doall @(apply md/zip' (repeat 50 (dd 0)))))
     (bench :zip-200 (doall @(apply md/zip' (repeat 200 (dd 0))))))

   (testing :knitty
     (bench :zip-50 (doall @(kd/zip* (repeat 50 (dd 0)))))
     (bench :zip-200 (doall @(kd/zip* (repeat 200 (dd 0)))))))
  ;;
  )

(deftest ^:benchmark benchmark-zip-list-async

  (do-mode-futures
   (testing :manifold
     (bench :zip-50 (doall @(with-defer (apply md/zip' (doall (repeatedly 50 #(ff 0)))))))
     (bench :zip-200 (doall @(with-defer (apply md/zip' (doall (repeatedly 200 #(ff 0))))))))

   (testing :knitty
     (bench :zip-50 (doall @(with-defer (kd/zip* (doall (repeatedly 50 #(ff 0)))))))
     (bench :zip-200 (doall @(with-defer (kd/zip* (doall (repeatedly 200 #(ff 0)))))))))
  ;;
  )

(deftest ^:benchmark benchmark-alt-sync

  (do-mode-futures
   (testing :manifold
     (bench :alt-2 @(md/alt' (dd 0) (dd 1)))
     (bench :alt-3 @(md/alt' (dd 0) (dd 1) (dd 2)))
     (bench :alt-10 @(md/alt' (dd 0) (dd 1) (dd 2) (dd 3) (dd 4) (dd 5) (dd 6) (dd 7) (dd 8) (dd 9))))

   (testing :knitty
     (bench :alt-2 @(kd/alt (dd 0) (dd 1)))
     (bench :alt-3 @(kd/alt (dd 0) (dd 1) (dd 2)))
     (bench :alt-10 @(kd/alt (dd 0) (dd 1) (dd 2) (dd 3) (dd 4) (dd 5) (dd 6) (dd 7) (dd 8) (dd 9)))))
  ;;
  )

(deftest ^:benchmark benchmark-alt-async

  (do-mode-futures
   (testing :manifold
     (bench :alt-2 @(with-defer (md/alt' (ff 0) (ff 0))))
     (bench :alt-3 @(with-defer (md/alt' (ff 0) (ff 1) (ff 2))))
     (bench :alt-10 @(with-defer (md/alt' (ff 0) (ff 1) (ff 2) (ff 3) (ff 4) (ff 5) (ff 6) (ff 7) (ff 8) (ff 9)))))

   (testing :knitty
     (bench :alt-2 @(with-defer (kd/alt (ff 0) (ff 0))))
     (bench :alt-3 @(with-defer (kd/alt (ff 0) (ff 1) (ff 2))))
     (bench :alt-10 @(with-defer (kd/alt (ff 0) (ff 1) (ff 2) (ff 3) (ff 4) (ff 5) (ff 6) (ff 7) (ff 8) (ff 9))))))
  ;;
  )

(deftest ^:benchmark benchmark-loop

  (do-mode-futures
   (testing :manifold
     (bench :loop100
            @(tu/with-defer
               (md/loop [x (ff 0)]
                 (md/chain'
                  x
                  (fn [x]
                    (if (< x 1000)
                      (md/recur (ff (ninl-inc x)))
                      x)))))))

   (testing :knitty
     (bench :loop100
            @(tu/with-defer
               (kd/loop [x (ff 0)]
                 (if (< x 1000)
                   (kd/recur (ff (ninl-inc x)))
                   x)))))
   (testing :reduce
     (bench :loop100
            @(tu/with-defer
               (kd/reduce
                (fn [x _] (if (< x 1000) (ff (ninl-inc x)) (reduced x)))
                (ff 0)
                (range)))))
   (testing :iterate
     (bench :loop100
            @(tu/with-defer
               (kd/iterate-while
                (fn [x] (ff (ninl-inc x)))
                (fn [x] (< x 1000))
                (ff 0))))))
  ;;
  )

(deftest ^:benchmark benchmark-loop-fut

  (do-mode-futures
   (tu/with-md-executor
     (testing :manifold
       (bench :loop100
              @(md/loop [x (tu/md-future 0)]
                 (md/chain'
                  x
                  (fn [x]
                    (if (< x 100)
                      (md/recur (tu/md-future (ninl-inc x)))
                      x)))))))

   (testing :knitty
     (tu/with-md-executor
       (bench :loop100
              @(kd/loop [x (tu/kt-future 0)]
                 (if (< x 100)
                   (kd/recur (tu/kt-future (ninl-inc x)))
                   x)))))
   (tu/with-md-executor
     (testing :reduce
       (bench :loop100
              @(kd/reduce
                (fn [x _] (if (< x 100) (tu/kt-future (ninl-inc x)) (reduced x)))
                (tu/kt-future 0)
                (range)))))
   (tu/with-md-executor
     (testing :iterate
       (bench :loop100
              @(kd/iterate-while
                (fn [x] (tu/kt-future (ninl-inc x)))
                (fn [x] (< x 100))
                (tu/kt-future 0)))))))


(deftest ^:benchmark bench-deferred

  (let [ls (md/listener (fn [_] nil))]
    (tmpl/do-template [t create-d]

      (testing t
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
                 (kd/peel d)))
        )

    :manifold (md/deferred nil)
    :knitty (kd/create)
    )))

(comment
  (clojure.test/test-ns *ns*))
