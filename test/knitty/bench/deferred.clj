(ns knitty.bench.deferred
  (:require
   [clojure.template :as tmpl]
   [clojure.test :as t :refer [deftest is testing]]
   [knitty.bench.bench-util :as bu :refer [apply-replicate-arg bench
                                           bench-suite defer! ninl-inc
                                           with-defer]]
   [knitty.deferred :as kd]
   [knitty.test-util :as tu]
   [manifold.deferred :as md])
  (:import
   [java.util.concurrent ForkJoinPool]))


(set! *warn-on-reflection* true)
(set! *unchecked-math* true)


(declare print-bench-matrix)

(t/use-fixtures :once
  (t/join-fixtures
   [(tu/tracing-enabled-fixture false)
    (bu/report-benchmark-fixture)
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
  (bu/print-benchmark-results-matrix
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
         [t d]
         (testing t
           (bench :chain-x1  @(with-defer (apply-replicate-arg (md/chain' (d 0)) 1 ninl-inc)))
           (bench :chain-x2  @(with-defer (apply-replicate-arg (md/chain' (d 0)) 2 ninl-inc)))
           (bench :chain-x3  @(with-defer (apply-replicate-arg (md/chain' (d 0)) 3 ninl-inc)))
           (bench :chain-x5  @(with-defer (apply-replicate-arg (md/chain' (d 0)) 5 ninl-inc)))
           (bench :chain-x10 @(with-defer (apply-replicate-arg (md/chain' (d 0)) 10 ninl-inc)))
           )
         :val dd
         :fut ff)))

       (testing :knitty
         (bench-suite
          (tmpl/do-template
           [t d]
           (testing t
             (bench :chain-x1  @(with-defer (apply-replicate-arg (kd/bind-> (d 0)) 1 ninl-inc)))
             (bench :chain-x2  @(with-defer (apply-replicate-arg (kd/bind-> (d 0)) 2 ninl-inc)))
             (bench :chain-x3  @(with-defer (apply-replicate-arg (kd/bind-> (d 0)) 3 ninl-inc)))
             (bench :chain-x5  @(with-defer (apply-replicate-arg (kd/bind-> (d 0)) 5 ninl-inc)))
             (bench :chain-x10 @(with-defer (apply-replicate-arg (kd/bind-> (d 0)) 10 ninl-inc)))
             )
            :val dd
            :fut ff)))
   ))


(deftest ^:benchmark benchmark-chain-in
  (do-mode-futures

   (testing :manifold
     (bench-suite
      (tmpl/do-template
       [t d]
       (testing t
         (bench :chain-in-x1  @(with-defer (apply-replicate-arg (-> (d 0)) 1  (md/chain' #(d (inc %))))))
         (bench :chain-in-x2  @(with-defer (apply-replicate-arg (-> (d 0)) 2  (md/chain' #(d (inc %))))))
         (bench :chain-in-x3  @(with-defer (apply-replicate-arg (-> (d 0)) 3  (md/chain' #(d (inc %))))))
         (bench :chain-in-x5  @(with-defer (apply-replicate-arg (-> (d 0)) 5  (md/chain' #(d (inc %))))))
         (bench :chain-in-x10 @(with-defer (apply-replicate-arg (-> (d 0)) 10 (md/chain' #(d (inc %)))))))
       :val dd
       :fut ff)))

   (testing :knitty
     (bench-suite
      (tmpl/do-template
       [t d]
        #_{:clj-kondo/ignore [:invalid-arity]}
       (testing t
         (bench :chain-in-x1  @(with-defer (apply-replicate-arg (-> (d 0)) 1  (kd/bind #(d (inc %))))))
         (bench :chain-in-x2  @(with-defer (apply-replicate-arg (-> (d 0)) 2  (kd/bind #(d (inc %))))))
         (bench :chain-in-x3  @(with-defer (apply-replicate-arg (-> (d 0)) 3  (kd/bind #(d (inc %))))))
         (bench :chain-in-x5  @(with-defer (apply-replicate-arg (-> (d 0)) 5  (kd/bind #(d (inc %))))))
         (bench :chain-in-x10 @(with-defer (apply-replicate-arg (-> (d 0)) 10 (kd/bind #(d (inc %)))))))
      :val dd
      :fut ff)))))


(deftest ^:benchmark benchmark-chain-err
  (do-mode-futures

   #_{:clj-kondo/ignore [:invalid-arity]}
   (tmpl/do-template
    [t1 t2 d bind bind-err]
    (testing t1
      (bench-suite
       (testing t2
         (let [e (ArithmeticException. "err")]
           (bench :chain-err1 @(with-defer
                                 (-> d
                                     (bind (fn [_] (throw e)))
                                     (bind-err (fn [_] :ok)))))
           (bench :chain-err5 @(with-defer
                                 (-> d
                                     (bind (fn [_] (throw e)))
                                     (bind-err ClassCastException    (fn [_] :no))
                                     (bind-err NullPointerException  (fn [_] :no))
                                     (bind-err IllegalStateException (fn [_] :no))
                                     (bind-err ArithmeticException   (fn [_] :ok))
                                     (bind-err (fn [_] :no)))))))))

    :manifold :val (dd 0) md/chain' md/catch'
    :manifold :fut (ff 0) md/chain' md/catch'
    :knitty   :val (dd 0) kd/bind   kd/bind-err
    :knitty   :fut (ff 0) kd/bind   kd/bind-err)))


(deftest ^:benchmark benchmark-let

  (do-mode-futures
   (testing :manifold
     (bench-suite
      (bu/with-md-executor
        (bench :let-x3v
               @(md/let-flow' [x1 (dd 0)
                               x2 (ninl-inc x1)
                               x3 (dd 0)]
                              (+ x1 x2 x3)))
        (bench :let-x5f
               @(md/let-flow' [x1 (dd 0)
                               x2 (bu/md-future (ninl-inc x1))
                               x3 (bu/md-future (ninl-inc x2))
                               x4 (bu/md-future (ninl-inc x3))
                               x5 (ninl-inc x4)]
                              x5)))))

   (testing :knitty
     (bench-suite
      (bu/with-md-executor
        (bench :let-x3v
               @(kd/let-bind [x1 (dd 0)
                              x2 (ninl-inc x1)
                              x3 (dd 0)]
                             (+ x1 x2 x3)))
        (bench :let-x5f
               @(kd/let-bind [x1 (dd 0)
                              x2 (bu/md-future (ninl-inc x1))
                              x3 (bu/md-future (ninl-inc x2))
                              x4 (bu/md-future (ninl-inc x3))
                              x5 (ninl-inc x4)]
                             x5))))))
  ;;
  )


(deftest ^:benchmark benchmark-zip
  (do-mode-futures

   (testing :manifold
     (bench-suite
      (bench :zip-2v  @(apply-replicate-arg md/zip' 2 (dd 0)))
      (bench :zip-5v  @(apply-replicate-arg md/zip' 5 (dd 0)))
      (bench :zip-10v @(apply-replicate-arg md/zip' 10 (dd 0)))
      (bench :zip-25v @(apply-replicate-arg md/zip' 25 (dd 0)))
      (bench :zip-2f  @(with-defer (apply-replicate-arg md/zip' 2 (ff 0))))
      (bench :zip-5f  @(with-defer (apply-replicate-arg md/zip' 5 (ff 0))))
      (bench :zip-10f @(with-defer (apply-replicate-arg md/zip' 10 (ff 0))))
      (bench :zip-25f @(with-defer (apply-replicate-arg md/zip' 25 (ff 0))))
      ))

   (testing :knitty
     (bench-suite
      (bench :zip-2v  @(apply-replicate-arg kd/zip 2 (dd 0)))
      (bench :zip-5v  @(apply-replicate-arg kd/zip 5 (dd 0)))
      (bench :zip-10v @(apply-replicate-arg kd/zip 10 (dd 0)))
      (bench :zip-25v @(apply-replicate-arg kd/zip 25 (dd 0)))
      (bench :zip-2f  @(with-defer (apply-replicate-arg kd/zip 2 (ff 0))))
      (bench :zip-5f  @(with-defer (apply-replicate-arg kd/zip 5 (ff 0))))
      (bench :zip-10f @(with-defer (apply-replicate-arg kd/zip 10 (ff 0))))
      (bench :zip-25f @(with-defer (apply-replicate-arg kd/zip 25 (ff 0))))
      )))
  ;;
  )


(deftest ^:benchmark benchmark-zip-list

  (do-mode-futures
   (testing :manifold
     (bench-suite
      (bench :zip-50v  (doall @(apply md/zip' (repeat 50 (dd 0)))))
      (bench :zip-500v (doall @(apply md/zip' (repeat 500 (dd 0)))))
      (bench :zip-50f  (doall @(with-defer (apply md/zip' (doall (repeatedly 50 #(ff 0)))))))
      (bench :zip-500f (doall @(with-defer (apply md/zip' (doall (repeatedly 500 #(ff 0)))))))))

   (testing :knitty
     (bench-suite
      (bench :zip-50v  (doall @(kd/zip* (repeat 50 (dd 0)))))
      (bench :zip-500v (doall @(kd/zip* (repeat 500 (dd 0)))))
      (bench :zip-50f  (doall @(with-defer (kd/zip* (doall (repeatedly 50 #(ff 0)))))))
      (bench :zip-500f (doall @(with-defer (kd/zip* (doall (repeatedly 500 #(ff 0))))))))))
  ;;
  )


(deftest ^:benchmark benchmark-alt

  (do-mode-futures
   (testing :manifold
     (bench-suite
      (bench :alt-2v  @(apply-replicate-arg md/alt' 2 (dd 0)))
      (bench :alt-3v  @(apply-replicate-arg md/alt' 3 (dd 0)))
      (bench :alt-10v @(apply-replicate-arg md/alt' 10 (dd 0)))
      (bench :alt-2f  @(with-defer (apply-replicate-arg md/alt' 2 (ff 0))))
      (bench :alt-3f  @(with-defer (apply-replicate-arg md/alt' 3 (ff 0))))
      (bench :alt-10f @(with-defer (apply-replicate-arg md/alt' 10 (ff 0))))
      ))

   (testing :knitty
     (bench-suite
      (bench :alt-2v  @(apply-replicate-arg kd/alt 2 (dd 0)))
      (bench :alt-3v  @(apply-replicate-arg kd/alt 3 (dd 0)))
      (bench :alt-10v @(apply-replicate-arg kd/alt 10 (dd 0)))
      (bench :alt-2f  @(with-defer (apply-replicate-arg kd/alt 2 (ff 0))))
      (bench :alt-3f  @(with-defer (apply-replicate-arg kd/alt 3 (ff 0))))
      (bench :alt-10f @(with-defer (apply-replicate-arg kd/alt 10 (ff 0))))
      )))
  ;;
  )


(deftest ^:benchmark benchmark-loop

  (do-mode-futures
   (testing :manifold
     (bench-suite
      (tmpl/do-template
       [t d]
       (testing t
         (bench :loop100
                @(bu/with-defer
                   (md/loop [x (dd 0)]
                     (md/chain'
                      x
                      (fn [x]
                        (if (< x 1000)
                          (md/recur (dd (ninl-inc x)))
                          x)))))))
       :val dd
       :fut ff)))

   (testing :knitty
     (bench-suite
      (tmpl/do-template
       [t d]
       (testing t
         (bench :loop100
                @(bu/with-defer
                   (kd/loop [x (ff 0)]
                     (if (< x 1000)
                       (kd/recur (ff (ninl-inc x)))
                       x)))))
       :val dd
       :fut ff)))
   ;;
   ))


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




(deftest ^:benchmark bench-deferred-await
  (tmpl/do-template
   [t fut]
   (testing t
     (bench-suite
      (bu/with-md-executor

        (bench :await-1-sync
               @(fut 0))

        (bench :await-100
               (let [fs (mapv #(fut %) (range 100))]
                 (doseq [f fs] @f)))

        (bench :await-100-fjp
               (let [p (ForkJoinPool/commonPool)
                     fs (mapv #(fut %) (range 100))
                     ^Runnable r (fn [] (doseq [f fs] @f))]
                 (.get (.submit p r nil)))))))

   :manifold bu/md-future
   :knitty   bu/kt-future))


(comment
  (clojure.test/test-ns *ns*))
