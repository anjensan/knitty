(ns knitty.bench.deferred
  (:require
   [clojure.test :as t :refer [deftest testing]]
   [knitty.deferred :as kd]
   [knitty.test-util :as tu :refer [bench ninl-inc with-defer defer!]]
   [manifold.deferred :as md]
   ))


(set! *warn-on-reflection* true)
(set! *unchecked-math* true)


(defn manifold-dd [x]
  (md/success-deferred x nil))

(defn manifold-ff [x]
  (let [d (md/deferred nil)]
    (defer! (md/success! d x))
    d))

(defn knitty-dd [x]
  (kd/wrap-val x))

(defn knitty-ff [x]
  (let [d (kd/create)]
    (defer! (kd/success! d x))
    d))


(declare ff)
(declare dd)

(defn with-modes-fixture []
  (fn [t]
    (testing :kt
      (with-redefs [ff knitty-ff
                    dd knitty-dd]
        (t)))
    (testing :md
      (with-redefs [ff manifold-ff
                    dd manifold-dd]
        (t)))))

(t/use-fixtures :once
  (t/join-fixtures
   [(tu/tracing-enabled-fixture false)
    (tu/report-benchmark-fixture)
    (tu/disable-manifold-leak-detection-fixture)]))

(t/use-fixtures :each
  (t/join-fixtures
   [(with-modes-fixture)]))


(defmacro d0 []
  `(dd 0))

(defmacro f0 []
  `(ff 0))


;; ===

(deftest ^:benchmark benchmark-chain

  (doseq [[t create-d] [[:sync #(do 0)]
                        [:realized #(d0)]
                        [:future #(f0)]]]
    (testing t

      (testing :manifold

        (bench :chain-x1
               @(with-defer (md/chain' (create-d) ninl-inc)))
        (bench :chain-x2
               @(with-defer (md/chain' (create-d) ninl-inc ninl-inc)))
        (bench :chain-x3
               @(with-defer (md/chain' (create-d) ninl-inc ninl-inc ninl-inc)))
        (bench :chain-x5
               @(with-defer (md/chain' (create-d) ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc)))
        (bench :chain-x10
               @(with-defer (md/chain' (create-d) ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc))))

      (testing :knitty

        (bench :bind-x1
               @(with-defer (kd/bind-> (create-d) ninl-inc)))
        (bench :bind-x2
               @(with-defer (kd/bind-> (create-d) ninl-inc ninl-inc)))
        (bench :bind-x3
               @(with-defer (kd/bind-> (create-d) ninl-inc ninl-inc ninl-inc)))
        (bench :bind-x5
               @(with-defer (kd/bind-> (create-d) ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc)))
        (bench :bind-x10
               @(with-defer (kd/bind-> (create-d) ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc ninl-inc))))
    ;;
    )))


(deftest ^:benchmark benchmark-let

  (tu/with-md-executor
    (testing :manifold
      (bench :let-x3-sync
             @(md/let-flow' [x1 (d0)
                             x2 (ninl-inc x1)
                             x3 (d0)]
                            (+ x1 x2 x3)))
      (bench :let-x5
             @(md/let-flow' [x1 (d0)
                             x2 (tu/md-future (ninl-inc x1))
                             x3 (tu/md-future (ninl-inc x2))
                             x4 (tu/md-future (ninl-inc x3))
                             x5 (ninl-inc x4)]
                            x5))))

  (tu/with-md-executor
    (testing :knitty
      (bench :let-x3-sync
             @(kd/let-bind [x1 (d0)
                            x2 (ninl-inc x1)
                            x3 (d0)]
                           (+ x1 x2 x3)))
      (bench :let-x5
             @(kd/let-bind [x1 (d0)
                            x2 (tu/md-future (ninl-inc x1))
                            x3 (tu/md-future (ninl-inc x2))
                            x4 (tu/md-future (ninl-inc x3))
                            x5 (ninl-inc x4)]
                       x5))))
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
  ;;
  )

(deftest ^:benchmark benchmark-zip-async

  (testing :manifold
    (bench :zip-f1 @(with-defer (md/zip' (f0))))
    (bench :zip-f2 @(with-defer (md/zip' (f0) (f0))))
    (bench :zip-f5 @(with-defer (md/zip' (f0) (f0) (f0) (f0) (f0)))))

  (testing :knitty
    (bench :zip-f1 @(with-defer (kd/zip (f0))))
    (bench :zip-f2 @(with-defer (kd/zip (f0) (f0))))
    (bench :zip-f5 @(with-defer (kd/zip (f0) (f0) (f0) (f0) (f0)))))
  ;;
  )

(deftest ^:benchmark benchmark-zip-list-sync

  (testing :manifold
    (bench :zip-50 (doall @(apply md/zip' (repeat 50 (d0)))))
    (bench :zip-200 (doall @(apply md/zip' (repeat 200 (d0))))))

  (testing :knitty
    (bench :zip-50 (doall @(kd/zip* (repeat 50 (d0)))))
    (bench :zip-200 (doall @(kd/zip* (repeat 200 (d0))))))
  ;;
  )

(deftest ^:benchmark benchmark-zip-list-async

  (testing :manifold
    (bench :zip-50 (doall @(with-defer (apply md/zip' (doall (repeatedly 50 #(f0)))))))
    (bench :zip-200 (doall @(with-defer (apply md/zip' (doall (repeatedly 200 #(f0))))))))

  (testing :knitty
    (bench :zip-50 (doall @(with-defer (kd/zip* (doall (repeatedly 50 #(f0)))))))
    (bench :zip-200 (doall @(with-defer (kd/zip* (doall (repeatedly 200 #(f0))))))))
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
    (bench :alt-2 @(with-defer (md/alt' (f0) (f0))))
    (bench :alt-3 @(with-defer (md/alt' (f0) (f0) (f0))))
    (bench :alt-10 @(with-defer (md/alt' (f0) (f0) (f0) (f0) (f0) (f0) (f0) (f0) (f0) (f0)))))

  (testing :knitty
    (bench :alt-2 @(with-defer (kd/alt (f0) (f0))))
    (bench :alt-3 @(with-defer (kd/alt (f0) (f0) (f0))))
    (bench :alt-10 @(with-defer (kd/alt (f0) (f0) (f0) (f0) (f0) (f0) (f0) (f0) (f0) (f0)))))
  ;;
  )

(deftest ^:benchmark benchmark-loop

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
    (testing :loop
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
          (testing :loop
            (bench :loop100
                   @(kd/loop [x (tu/md-future 0)]
                      (if (< x 100)
                        (kd/recur (tu/md-future (ninl-inc x)))
                        x)))))
      (tu/with-md-executor
        (testing :reduce
          (bench :loop100
                 @(kd/reduce
                   (fn [x _] (if (< x 100) (tu/md-future (ninl-inc x)) (reduced x)))
                   (tu/md-future 0)
                   (range)))))
      (tu/with-md-executor
        (testing :iterate
          (bench :loop100
                 @(kd/iterate-while
                   (fn [x] (tu/md-future (ninl-inc x)))
                   (fn [x] (< x 100))
                   (tu/md-future 0))))))
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
  (clojure.test/test-ns *ns*))
