(ns knitty.test-util
  (:require
   [clojure.test :as t]
   [knitty.core]
   [knitty.deferred :as kd]
   [manifold.debug :as md-debug]))


(defmethod t/report :begin-test-var [m]
  (t/with-test-out
    (println "-"
             (-> m :var meta :name)
             (or (some-> t/*testing-contexts* seq vec) ""))))

(defmethod t/report :end-test-var [_m])


(defn tracing-enabled-fixture
  [tracing-enabled]
  (fn [t]
    (binding [knitty.core/*tracing* (boolean tracing-enabled)]
      (t))))


(defmacro do-defs
  "eval forms one by one - allows to intermix defs"
  [& body]
  (list*
   `do
   (for [b body]
     `(binding [*ns* ~*ns*]
        (eval '~b)))))


(defmacro slow-future [delay & body]
  `(kd/future
     (Thread/sleep (long ~delay))
     ~@body))


(defn reset-registry-fixture
  []
  (fn [t]
    (let [r knitty.core/*registry*]
      (try
        (t)
        (finally
          (alter-var-root #'knitty.core/*registry* (constantly r)))))))


(defn disable-manifold-leak-detection-fixture
  []
  (fn [t]
    (let [e md-debug/*dropped-error-logging-enabled?*]
      (md-debug/disable-dropped-error-logging!)
      (try (t)
           (finally
             (.bindRoot #'md-debug/*dropped-error-logging-enabled?* e))))))


(defn dotimes-prn* [n body-fn]
  (let [s (quot n 100)
        t (System/currentTimeMillis)]
    (dotimes [x n]
      (when (zero? (mod x s))
        (print ".")
        (flush))
      (body-fn n))
    (let [t1 (System/currentTimeMillis)]
      (println " done in" (- t1 t) "ms"))))


(defmacro dotimes-prn [xn & body]
  (let [[x n] (cond
                (and (vector? xn) (== 2 (count xn))) xn
                (and (vector? xn) (== 1 (count xn))) ['_ (first xn)]
                :else ['_ xn])]
    `(dotimes-prn* ~n (fn [~x] ~@body))))


