(ns knitty.bench.deferred
  (:require
   [clojure.template :as tmpl]
   [clojure.test :as t :refer [deftest testing]]
   [knitty.bench.bench-util :as bu :refer [apply-replicate-arg bench
                                           bench-suite defer! ninl-inc
                                           with-defer]]
   [knitty.deferred :as kd]
   [knitty.test-util :as tu]
   [manifold.deferred :as md]))


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
         )
       :value dd
       :defer ff)))

   #_{:clj-kondo/ignore [:invalid-arity]}
   (testing :knitty
     (bench-suite
      (tmpl/do-template
       [t d]
       (testing t
         (bench :chain-x1  @(with-defer (apply-replicate-arg (-> (d 0)) 1  (kd/bind ninl-inc))))
         (bench :chain-x2  @(with-defer (apply-replicate-arg (-> (d 0)) 2  (kd/bind ninl-inc))))
         (bench :chain-x3  @(with-defer (apply-replicate-arg (-> (d 0)) 3  (kd/bind ninl-inc))))
         )
       :value dd
       :defer ff)))))


(deftest ^:benchmark benchmark-chain-in
  (do-mode-futures

   (testing :manifold
     (bench-suite
      (tmpl/do-template
       [t d]
       (testing t
         (bench :chain-in-x1  @(with-defer (let [f #(d (ninl-inc %))] (apply-replicate-arg (-> (d 0)) 1 (md/chain' f)))))
         (bench :chain-in-x2  @(with-defer (let [f #(d (ninl-inc %))] (apply-replicate-arg (-> (d 0)) 2 (md/chain' f)))))
         (bench :chain-in-x3  @(with-defer (let [f #(d (ninl-inc %))] (apply-replicate-arg (-> (d 0)) 3 (md/chain' f)))))
         )
       :value dd
       :defer ff)))

   #_{:clj-kondo/ignore [:invalid-arity]}
   (testing :knitty
     (bench-suite
      (tmpl/do-template
       [t d]
       (testing t
         (bench :chain-in-x1  @(with-defer (let [f #(d (ninl-inc %))] (apply-replicate-arg (-> (d 0)) 1 (kd/bind f)))))
         (bench :chain-in-x2  @(with-defer (let [f #(d (ninl-inc %))] (apply-replicate-arg (-> (d 0)) 2 (kd/bind f)))))
         (bench :chain-in-x3  @(with-defer (let [f #(d (ninl-inc %))] (apply-replicate-arg (-> (d 0)) 3 (kd/bind f)))))
         )
       :value dd
       :defer ff)))))


(deftest ^:benchmark benchmark-chain-err
  (do-mode-futures

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

    :manifold  :value  (dd 0)  md/chain'  md/catch'
    :manifold  :defer  (ff 0)  md/chain'  md/catch'
    :knitty    :value  (dd 0)  kd/bind    kd/bind-err
    :knitty    :defer  (ff 0)  kd/bind    kd/bind-err)))


(deftest ^:benchmark benchmark-zip
  (do-mode-futures

   (testing :manifold
     (bench-suite
      (testing :value
        (bench :zip-2  @(apply-replicate-arg md/zip' 2 (dd 0)))
        (bench :zip-5  @(apply-replicate-arg md/zip' 5 (dd 0)))
        (bench :zip-10 @(apply-replicate-arg md/zip' 10 (dd 0)))
        )
      (testing :defer
        (bench :zip-2  @(with-defer (apply-replicate-arg md/zip' 2 (ff 0))))
        (bench :zip-5  @(with-defer (apply-replicate-arg md/zip' 5 (ff 0))))
        (bench :zip-10 @(with-defer (apply-replicate-arg md/zip' 10 (ff 0))))
        )
      ))

   (testing :knitty
     (bench-suite
      (testing :value
        (bench :zip-2  @(apply-replicate-arg kd/zip 2 (dd 0)))
        (bench :zip-5  @(apply-replicate-arg kd/zip 5 (dd 0)))
        (bench :zip-10 @(apply-replicate-arg kd/zip 10 (dd 0)))
        )
      (testing :defer
        (bench :zip-2  @(with-defer (apply-replicate-arg kd/zip 2 (ff 0))))
        (bench :zip-5  @(with-defer (apply-replicate-arg kd/zip 5 (ff 0))))
        (bench :zip-10 @(with-defer (apply-replicate-arg kd/zip 10 (ff 0))))
        )
      )))
  ;;
  )


(deftest ^:benchmark benchmark-zip-list

  (do-mode-futures
   (testing :manifold
     (bench-suite
      (testing :value
        (bench :zip-50  (doall @(apply md/zip' (repeat 50 (dd 0)))))
        (bench :zip-500 (doall @(apply md/zip' (repeat 500 (dd 0))))))
      (testing :defer
        (bench :zip-50  (doall @(with-defer (apply md/zip' (doall (repeatedly 50 #(ff 0)))))))
        (bench :zip-500 (doall @(with-defer (apply md/zip' (doall (repeatedly 500 #(ff 0))))))))))

   (testing :knitty
     (bench-suite
      (testing :value
        (bench :zip-50  (doall @(kd/zip* (repeat 50 (dd 0)))))
        (bench :zip-500 (doall @(kd/zip* (repeat 500 (dd 0))))))
      (testing :defer
        (bench :zip-50  (doall @(with-defer (kd/zip* (doall (repeatedly 50 #(ff 0)))))))
        (bench :zip-500 (doall @(with-defer (kd/zip* (doall (repeatedly 500 #(ff 0)))))))))))
  ;;
  )


(deftest ^:benchmark benchmark-alt

  (do-mode-futures
   (testing :manifold
     (bench-suite
      (testing :value
        (bench :alt-2  @(apply-replicate-arg md/alt' 2 (dd 0)))
        (bench :alt-3  @(apply-replicate-arg md/alt' 3 (dd 0)))
        (bench :alt-10 @(apply-replicate-arg md/alt' 10 (dd 0))))
      (testing :defer
        (bench :alt-2  @(with-defer (apply-replicate-arg md/alt' 2 (ff 0))))
        (bench :alt-3  @(with-defer (apply-replicate-arg md/alt' 3 (ff 0))))
        (bench :alt-10 @(with-defer (apply-replicate-arg md/alt' 10 (ff 0)))))
      ))

   (testing :knitty
     (bench-suite
      (testing :value
        (bench :alt-2  @(apply-replicate-arg kd/alt 2 (dd 0)))
        (bench :alt-3  @(apply-replicate-arg kd/alt 3 (dd 0)))
        (bench :alt-10 @(apply-replicate-arg kd/alt 10 (dd 0))))
      (testing :defer
        (bench :alt-2  @(with-defer (apply-replicate-arg kd/alt 2 (ff 0))))
        (bench :alt-3  @(with-defer (apply-replicate-arg kd/alt 3 (ff 0))))
        (bench :alt-10 @(with-defer (apply-replicate-arg kd/alt 10 (ff 0)))))
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
                   (md/loop [x (d 0)]
                     (md/chain'
                      x
                      (fn [x]
                        (if (< x 1000)
                          (md/recur (d (ninl-inc x)))
                          x)))))))
       :value dd
       :defer ff)))

   (testing :knitty
     (bench-suite
      (tmpl/do-template
       [t d]
       (testing t
         (bench :loop100
                @(bu/with-defer
                   (kd/loop [x (d 0)]
                     (if (< x 1000)
                       (kd/recur (d (ninl-inc x)))
                       x)))))
       :value dd
       :defer ff)))
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
