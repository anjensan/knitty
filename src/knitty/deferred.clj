(import 'knitty.javaimpl.KnittyLoader)
(KnittyLoader/touch)

(ns knitty.deferred
  (:refer-clojure :exclude [future future-call run! while reduce loop])
  (:require [clojure.core :as c]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md])
  (:import [java.util.concurrent Executor]
           [knitty.javaimpl KAwaiter KDeferred]
           [manifold.deferred IDeferred IMutableDeferred]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

;; ==

(defn create
  "Create a new unrealized deferred."
  {:inline
   (fn
     ([] `(KDeferred/create))
     ([token] `(KDeferred/create ~token)))
   :inline-arities #{1 2}}
  (^KDeferred []
   (KDeferred/create))
  (^KDeferred [token]
   (KDeferred/create token)))

(definline wrap-err
  "A realized deferred with error."
  [e]
  `(KDeferred/wrapErr ~e))

(definline wrap-val
  "A realized deferred with value."
  [v]
  `(KDeferred/wrapVal ~v))

(definline wrap
  "Coerce type `x` from IDeferred to Knitty deferred or returns realized deferred with value `x`."
  [x]
  `(KDeferred/wrap ~x))

(defn wrap*
  "Corece `x` into an instance of Knitty deferred.  Converts non-deferred futures with `manifold.deferred/->deferred`."
  ^KDeferred [x]
  (let [y (manifold.deferred/->deferred x nil)]
    (cond
      (nil? y)
      (wrap-val x)

      (realized? y)
      (let [v (md/success-value y y)]
        (if (identical? v y)
          (wrap-err (md/error-value y y))
          (wrap-val v)))

      :else
      (.bind (KDeferred/wrap y) wrap* wrap* nil))))

(defmacro do-wrap
  "Run `body` and returns value as deferred, catch and wrap any exceptions."
  [& body]
  `(try
     (wrap (do ~@body))
     (catch Throwable e# (wrap-err e#))))

(defn future-call
  "Equivalent to `clojure.core/future-call`, but returns a Knitty deferred."
  [f]
  (let [frame (clojure.lang.Var/cloneThreadBindingFrame)
        d (create)]
    (.execute
     clojure.lang.Agent/soloExecutor
     (fn executor-task []
       (clojure.lang.Var/resetThreadBindingFrame frame)
       (try
         (when-not (.realized d)
           (.chain d (f) nil))
         (catch Throwable e (.error d e nil)))))
    d))

(defmacro future
  "Equivalent to `clojure.core/future`, but returns a Knitty deferred."
  [& body]
  `(future-call (^{:once true} fn* [] ~@body)))

;; ==

(defn claim!
  "Attempts to claim the deferred for future updates."
  ([^IMutableDeferred d] (.claim d))
  ([^KDeferred d token] (.claim d token)))

(definline kd-get
  "Unwrap realized Knitty deferred by returning value or throwing wrapped exception."
  [kd]
  `(let [^KDeferred x# ~kd] (.get x#)))

(definline kd-chain-from
  "Conveys value (or error) from deferred `d` to Knitty deferred `kd`."
  [kd d token]
  `(let [^KDeferred x# ~kd] (.chain x# ~d ~token)))

(defmacro success!
  "Puts a deferred into a realized state with provided value `x`."
  ([d x] `(let* [^IMutableDeferred d# ~d] (.success d# ~x)))
  ([d x token] `(let* [^IMutableDeferred d# ~d] (.success d# ~x ~token))))

(defmacro error!
  "Puts a deferred into a realized state with provided error `x`."
  ([d x] `(let* [^IMutableDeferred d# ~d] (.error d# ~x)))
  ([d x token] `(let* [^IMutableDeferred d# ~d] (.error d# ~x ~token))))

(definline deferred?
  "Returns true if the object is an instance of a deferred."
  [x]
  `(instance? IDeferred ~x))

(definline unwrap1
  "Unwraps deferred (once). Returns realized value or the deferred itself."
  [x]
  `(let [x# ~x]
     (if (instance? IDeferred x#)
       (.successValue ^IDeferred x# x#)
       x#)))

;; ==

(defn on
  "Registers callback fns to run when deferred is realized.
   When only one callback is provided it shold be 0-arg fn.
   When both callbacks provided - they must accept 1 argument (value or error)."
  ([x on-any]
   (.onRealized (wrap x)
                (fn val [x] (if (deferred? x) (on x on-any) (on-any)))
                (fn err [_] (on-any))))
  ([x on-ok on-err]
   (.onRealized (wrap x)
                (fn val [x] (if (deferred? x) (on x on-ok on-err) (on-ok x)))
                (fn err [e] (on-err e)))))

(defmacro ^:private bind-inline
  ([d val-fn] `(.bind (wrap ~d) ~val-fn nil nil))
  ([d val-fn err-fn] `(.bind (wrap ~d) ~val-fn ~err-fn nil))
  ([d val-fn err-fn token] `(.bind (wrap ~d) ~val-fn ~err-fn ~token)))

(defn bind
  "Bind 1-arg callbacks fn to deferred. Returns new deferred with amended value.
   Fn `val-fn` takes single arugment with unwrapped value and may return a new value,
   another deferred or throw an exception. Optional `err-fn` accepts single argument with error.
   "
  {:inline (fn [d & args] (apply #'bind-inline nil nil d args))
   :inline-arities #{2 3 4}}
  ([d val-fn] (bind-inline d val-fn))
  ([d val-fn err-fn] (bind-inline d val-fn err-fn))
  ([d val-fn err-fn token] (bind-inline d val-fn err-fn token)))

(defn bind-ex
  "Similar to `bind`, but run all callbacks on specified `executor`."
  ([d ^Executor executor val-fn]
   (.bind (wrap d) val-fn nil nil executor))
  ([d ^Executor executor val-fn err-fn]
   (.bind (wrap d) val-fn err-fn nil executor))
  ([d ^Executor executor val-fn err-fn token]
   (.bind (wrap d) val-fn err-fn token executor)))

(defn onto
  "Returns a deferred whose callbacks will be run on `executor`."
  ([d ^Executor executor]
   (if (nil? executor)
     d
     (let [dd (create)]
       (on d
           (fn on-val [x] (.execute executor #(success! dd x)))
           (fn on-err [e] (.execute executor #(error! dd e))))
       dd))))


(defn- map-subset? [a-map b-map]
  (every? (fn [[k :as e]] (= e (find b-map k))) a-map))

(defn- build-err-predicate [ee]
  (cond
    (fn? ee) ee
    (and (class? ee) (.isAssignableFrom Throwable ee)) (fn check-ex-instance [e] (instance? ee e))
    (map? ee) (fn check-ex-data [e] (some-> e ex-data (->> (map-subset? ee))))
    :else (throw (ex-info "expected exception class, predicate fn or ex-data submap" {::ex ee}))
    ))

(defn bind-err
  "Bind 1-arg callback fn to deferred. Callback called when deferred is realized with an error.
   Use `throw` to re-throw error or return a new value.
   Optinal argument `exc` may specify which kind of errors we want to handle.
   It may be 1-arg predicate fn, subclass of java.lang.Throwable or map.
   In the last case only instances of `IExceptionInfo` are handled where their `ex-data` is a subset of the `exc` map.

       (-> d
         (bind-err java.lang.IOException       #(handle-instances-of-ioexception %))
         (bind-err #(re-find #\"text\" (str %))  #(hande-exceptions-with-matched-text %))
         (bind-err {:type :my-error}           #(hande-exinfo-with-matched-type %))
         (bind-err                             #(handle-any-error %)))
   "
  ([mv f] (bind mv identity f))
  ([mv exc f]
   (let [ep (build-err-predicate exc)]
     (bind-err mv (fn on-err [e] (if (ep e) (f e) (wrap-err e)))))))

(defn bind-fin
  "Bind 0-arg function to be executed when deferred is realized (either with value or error).
   Callback result is ignored, any thrown execiptions are re-wrapped.
   "
  ([d f0]
   (bind
    d
    (fn on-val [x] (bind (f0) (fn ret-val [_] x)))
    (fn on-err [e] (bind (f0) (fn ret-err [_] (wrap-err e)))))))

(defmacro bind->
  "Combination of `bind` and threading macro `->`.
   Similar to `manifold.deferred/chain`, but does not allow dynamic applying with seq of arguments.
   Use `reduce` if number of binded callbacks is known only at runtime."
  [expr & forms]
  (list*
   `->
   `(do-wrap ~expr)
   (map (comp (partial list `bind)
              #(if (seq? %) `(fn [x#] (-> x# ~%)) %))
        forms)))


(defn revoke
  "Returns new deferred connected to the provided.
   When resulted deferred is realized but original is not - calls cancellation callback.

       (let [f (java.core/future (Thread/sleep 1000) 1))
             x (-> (wrap* f)
                   (bind inc)
                   (revoke #(do
                              (println :operation-is-cancelled)
                              (clojure.core/future-cancel))))]
         (success! x 1)
         (assert (clojure.core/future-cancelled? f)))

   Note that call to `revoke` should be last in a chain, because revokation is not propagated by binding fns.

       (let [f (java.core/future (Thread/sleep 1000) 1))
             x (-> (wrap* f)
                   (revoke #(do
                              (println :operation-is-cancelled)
                              (clojure.core/future-cancel)))
                   (bind inc))]
         (success! x 1)
         ;; revokation was not pass through `(bind inc)` binding.
         (assert (not (clojure.core/future-cancelled? f))))

  "
  {:inline (fn
             ([d c] `(KDeferred/revoke ~d ~c nil))
             ([d c e] `(KDeferred/revoke ~d ~c ~e)))
   :inline-arities #{2 3}}
  ([d cancel-fn] (KDeferred/revoke d cancel-fn nil))
  ([d cancel-fn err-handler] (KDeferred/revoke d cancel-fn err-handler)))

(def ^:private revoke-to-error
  (knitty.javaimpl.RevokeException. "deferred is revoked by revoke-to"))

(defn revoke-to
  "Like `revoke`, but resolves `rd` with an exception instead of calling generic callback.
   Use it to bypass revokation to original deferred:

       (let [x (call-some-function)]
         (-> x
            (bind #(process-x1 %))
            (bind #(procces-x2 %))
            (bind-err #(handle-error %))
            (revoke-to x)  ;; bypass cancelation to `x`
            ))
  "
  ([^IDeferred d ^IDeferred rd]
   (revoke d #(error! rd revoke-to-error)))
  ([^IDeferred d ^IDeferred rd & rds]
   (revoke d #(do (error! rd revoke-to-error)
                  (doseq [x rds] (error! x revoke-to-error))))))

(defn connect
  "Conveys the realized value of `d-from` into `d-dest`."
  ([d-from d-dest]
   (connect d-from d-dest nil))
  ([d-from d-dest token]
   (on (wrap d-from)
       (fn on-val [x] (success! d-dest x token))
       (fn on-err [e] (error! d-dest e token)))))

(defn- join0 [x d t]
  (on x
      (fn on-val [x]
        (if (deferred? x)
          (join0 x d t)
          (success! d x t)))
      (fn on-err [e]
        (if (deferred? e)
          (join0 e d t)
          (error! d e t)))))

(defn join
  "Coerce 'deferred with deferred value' to deferred with plain value.

       @(join (kd/future (kd/future (kd/future 1))))  ;; => 1
   "
  [d]
  (cond
    (not (deferred? d)) (wrap d)
    (and (realized? d) (not (deferred? (unwrap1 d)))) d
    :else (let [d0 (create d)]
            (join0 d d0 d)
            d0)))

;; ==

(defmacro letm
  "Monadic let. Unwraps deferreds during binding, returning result as deferred.
   Unlike `manifold.deferred/let-flow` all steps are resolved in sequential order,
   so use `zip` if parallel execution is required.
   Steps list can contain :let and :when special forms.

    (letm [[x y] (zip (kd/future (rand) (kd/future (rand)))
           :when (< x y)  ;; whole letm returns nil when condition is not met
           :let [d x]     ;; bind without unwrapping
           z (kd/future (* x y))]
      (vector x y z @d))
   "
  [binds & body]
  {:pre [(vector? binds)
         (even? (count binds))]}
  (if (seq binds)
    (let [[x y & rest-binds] binds
          rest-binds (vec rest-binds)]
      (cond
        (= :when x) `(if ~y (letm ~rest-binds ~@body) (wrap-val nil))
        (= :let x)  `(let ~y (letm ~rest-binds ~@body))
        :else       `(bind ~y (fn [~x] (letm ~rest-binds ~@body)))))
    `(wrap (do ~@body))))

;; ==

(defn- iterator ^java.util.Iterator [xs]
  (let [^java.lang.Iterable c (if (instance? java.lang.Iterable xs) xs (seq xs))]
    (.iterator c)))

(defmacro kd-await!
  "Internal macros used by yarns - prefer not to use it.
   Schedlue 0/1-arg function `ls` to execute after all deferreds are realized
   with values or at least one deferred is realized with an error.
   All deferreds *must* be instances of Knitty deferred (use `wrap` or `wrap*` for coercion).
   "
  ([ls]
   `(~ls))
  ([ls x1]
   `(let [ls# ~ls] (when (KAwaiter/await1 ls# ~x1) (ls#))))
  ([ls x1 & xs]
   (let [xs (list* x1 xs)
         xsp (partition-all 4 xs)]
     `(let [ls# ~ls]
        (when (.await (doto (KAwaiter/start ls#)
                        ~@(map (fn [x] `(.with ~@x)) xsp)))
          (ls#))))))

(defmacro await! [ls & ds]
    `(kd-await! ~ls ~@(map #(do `(wrap ~%)) ds)))

(definline await!*
  "Like `await!` but accept iterable collection of deferreds."
  [ls ds]
  `(let [ls# ~ls]
     (when (KAwaiter/awaitIter ls# (iterator ~ds))
       (ls#))))

(let [ls #(do)
      x1 (wrap 1)
      x2 (wrap 1)
      x3 (wrap 1)
      x4 (wrap 1)]
  (kd-await! ls x1 x2 x3 x4))

;; ==

(defmacro call-after-all'
  [ds f]
  `(let [d# (create)]
     (await!*
      (fn ~'on-await-iter
        ([] (success! d# ~f))
        ([e#] (error! d# e#)))
      ~ds)
     d#))

(defn zip*
  "Similar to `(apply zip vs)`, returns a seq instead of vector."
  ([vs] (call-after-all'
         vs
         (let [it (iterator vs)]
           (iterator-seq
            (reify java.util.Iterator
              (hasNext [_] (.hasNext it))
              (next [_] (unwrap1 (.next it))))))))
  ([a vs] (zip* (list* a vs)))
  ([a b vs] (zip* (list* a b vs)))
  ([a b c vs] (zip* (list* a b c vs)))
  ([a b c d vs] (zip* (list* a b c d vs)))
  ([a b c d e & vs] (zip* (concat [a b c d e] vs))))

(defmacro ^:private zip-inline [& xs]
  `(let [~@(mapcat identity (for [x xs] [x `(wrap ~x)]))]
     (let [res# (create)]
       (kd-await!
        (fn ~(symbol (str "on-await-" (count xs)))
          ([] (success! res# ~(vec (for [x xs] `(kd-get ~x)))))
          ([e#] (error! res# e#)))
        ~@xs)
       res#)))

(defmacro ^:private iter-full-reduce
  [[_fn [a x] & f] val coll]
  `(c/let [^java.lang.Iterable coll# ~coll
           ^java.util.Iterator iter# (.iterator coll#)]
     (c/loop [ret# ~val]
       (if (.hasNext iter#)
         (recur (c/let [~a ret#, ~x (.next iter#)] ~@f))
         ret#))))

(defn zip
  "Takes several values and returns a deferred that will yield vector of realized values."
  ([] (wrap-val []))
  ([a] (bind a (fn on-await-1 [x] [x])))
  ([a b] (zip-inline a b))
  ([a b c] (zip-inline a b c))
  ([a b c d] (zip-inline a b c d))
  ([a b c d e] (zip-inline a b c d e))
  ([a b c d e f] (zip-inline a b c d e f))
  ([a b c d e f g] (zip-inline a b c d e f g))
  ([a b c d e f g h] (zip-inline a b c d e f g h))
  ([a b c d e f g h i] (zip-inline a b c d e f g h i))
  ([a b c d e f g h i j] (zip-inline a b c d e f g h i j))
  ([a b c d e f g h i j k] (zip-inline a b c d e f g h i j k))
  ([a b c d e f g h i j k l] (zip-inline a b c d e f g h i j k l))
  ([a b c d e f g h i j k l m] (zip-inline a b c d e f g h i j k l m))
  ([a b c d e f g h i j k l m n] (zip-inline a b c d e f g h i j k l m n))
  ([a b c d e f g h i j k l m n o] (zip-inline a b c d e f g h i j k l m n o))
  ([a b c d e f g h i j k l m n o p] (zip-inline a b c d e f g h i j k l m n o p))
  ([a b c d e f g h i j k l m n o p & z]
   (bind
    (zip-inline a b c d e f g h i j k l m n o p)
    (fn on-await-x [xg]
      (let [z (iter-full-reduce
               (fn [^java.util.ArrayList a x] (doto a (.add (wrap x))))
               (java.util.ArrayList. (count z))
               z)]
        (call-after-all'
         z
         (persistent!
          (iter-full-reduce
           (fn [a x] (conj! a (kd-get x)))
           (transient xg)
           z))))
        ))))


(def ^:private ^java.util.Random alt-rnd
  (java.util.Random.))

(defn- alt-in
  ([^KDeferred res a b]
   (if (== 0 (.nextInt alt-rnd 2))
     (do (.chain res a nil) (when-not (.realized res) (.chain res b nil)))
     (do (.chain res b nil) (when-not (.realized res) (.chain res a nil)))))
  ([^KDeferred res a b c]
   (case (.nextInt alt-rnd 3)
     0 (do (.chain res a nil) (when-not (.realized res) (alt-in res b c)))
     1 (do (.chain res b nil) (when-not (.realized res) (alt-in res a c)))
     2 (do (.chain res c nil) (when-not (.realized res) (alt-in res a b)))))
  ([^KDeferred res a b c d]
   (case (.nextInt alt-rnd 4)
     0 (do (.chain res a nil) (when-not (.realized res) (alt-in res b c d)))
     1 (do (.chain res b nil) (when-not (.realized res) (alt-in res a c d)))
     2 (do (.chain res c nil) (when-not (.realized res) (alt-in res a b d)))
     3 (do (.chain res d nil) (when-not (.realized res) (alt-in res a b c))))))

(defn alt
  "Takes several values, some of which may be a deferred, and returns a
  deferred that will yield the value which was realized first."
  ([a]
   (wrap a))
  ([a b]
   (doto (create) (alt-in a b)))
  ([a b c]
   (doto (create) (alt-in a b c)))
  ([a b c d]
   (doto (create) (alt-in a b c d)))
  ([a b c d & vs]
   (let [^KDeferred res (create)]
     (c/reduce
      (fn [_ x] (.chain res x nil) (when (.realized res) (reduced nil)))
      (doto (java.util.ArrayList. (+ (count vs) 4))
        (.addAll ^clojure.lang.PersistentList vs)
        (.add a)
        (.add b)
        (.add c)
        (.add d)
        (java.util.Collections/shuffle)))
     res)))

;; ==

(defmacro impl-iterate-while*
  [[_ [fx] & f]
   [_ [px] & p]
   x]
  `(let [d# (create)
         ef# (fn ~'on-err [e#] (error! d# e#))]
    (on
     ~x
     (fn iter-step# [x#]
       (when-not (.realized d#)
         (cond

           (deferred? x#)
           (let [y# (KDeferred/wrapDeferred x#)]
             (when-not (.listen0 y# iter-step# ef#)
               (recur (try (.get y#) (catch Throwable t# (ef# t#))))))

           (let [~px x#] ~@p)
           (let [y# (unwrap1 (try (let [~fx x#] ~@f) (catch Throwable t# (wrap-err t#))))]
             (if (deferred? y#)
               (let [y# (KDeferred/wrapDeferred y#)]
                 (when-not (.listen0 y# iter-step# ef#)
                   (recur (try (.get y#) (catch Throwable t# (ef# t#))))))
               (recur y#)))

           :else
           (success! d# x#))))
     ef#)
    d#))


(defn iterate-while
  "Iteratively run 1-arg function `f` with initial value `x`.
   After each call check result of calling `p` and stop loop when result if falsy.
   Function `f` may return deferreds, initial value `x` also may be deferred.
   Predicate `p` should always return synchonous values howerver.
   This is low-level routine, prefer `reduce` `while` or `loop`."
  [f p x]
  (impl-iterate-while* (fn [x] (f x)) (fn [x] (p x)) x))


(deftype DRecur [args])

(defmacro recur
  "A special recur that can be used with `knitty.deferred/loop`."
  [& args]
  `(bind (zip ~@args) #(DRecur. %)))

(defmacro loop
  "A version of Clojure's loop which allows for asynchronous loops, via `manifold.deferred/recur`.
  `loop` will always return a deferred value, even if the body is synchronous.  Note that `loop`
   does **not** coerce values to deferreds, actual Manifold deferreds must be used.

   (loop [i 1e6]
     (chain (future i)
       #(if (p/zero? %)
          %
          (recur (p/dec %)))))"

  [bindings & body]
  (let [bs (partition 2 bindings)
        syms (map first bs)
        init (map second bs)]
    `(impl-iterate-while*
      (fn [r#] (let [[~@syms] (.-args ^DRecur r#)] ~@body))
      (fn [r#] (instance? DRecur r#))
      (knitty.deferred/recur ~@init))))

(defmacro while
  "Deferred-aware version of `clojure.core/while`.
   Both test and body experssion may result into deferred.
   Returns deferred realized to `nil`."
  ([body]
   `(impl-iterate-while*
     (fn [x#] (when x# ~body))
     identity
     true))
  ([pred & body]
   `(impl-iterate-while*
     (fn [x#]
       (when x#
         (bind ~pred
               (fn ~'while-body [c#] (when c# (bind (do ~@body)
                                                    (constantly true)))))))
     (fn [x#] x#)
     true)))

(defn chain*
  "Composes functions over the value `x`, returning a deferred containing the result."
  [x fs]
  (let [it (iterator fs)]
    (impl-iterate-while*
     (fn [a] (bind a (.next it)))
     (fn [_] (.hasNext it))
     x)))

(defn reduce
  "Deferred-aware version of `clojure.core/reduce`.
   Step function `f` may return deferred values, `xs` may be sequence of deferreds."
  [f initd xs]
  (bind
   (let [it (iterator xs)]
     (impl-iterate-while*
      (fn [a] (bind (.next it) #(f a %)))
      (fn [a] (and (not (reduced? a)) (.hasNext it)))
      initd))
   unreduced))

(defn run!
  "Sequentially apply `f` to sequence of deferreds `xs` for side effects.
   Fn `f` may return deferreds."
  [f xs]
  (let [it (iterator xs)]
    (impl-iterate-while*
     (fn [_] (bind (.next it) f))
     (fn [_] (.hasNext it))
     nil)))

;; ==

(defn semaphore
  "*experimental*"
  [n]
  (let [s (atom [n []])]
    (letfn [(conj-fds
              [[^long n' fds'] f d]
              (if (pos? n')
                [(dec n') fds']
                [n' (conj fds' [f d])]))

            (pop-fds
              [[^long n' fds']]
              (if (empty? fds')
                [(unchecked-inc n') fds']
                [n' (pop fds')]))

            (maybe-release
              []
              (let [[[_ fds0]] (swap-vals! s pop-fds)]
                (when-let [[f d] (peek fds0)]
                  (kd-chain-from d (do-wrap (f)) nil))))

            (semaphore-fn [f]
              (let [d (create)
                    [[^long n0] [^long n1]] (swap-vals! s conj-fds f d)]
                (on d maybe-release)
                (when-not (== n0 n1)
                  (kd-chain-from d (do-wrap (f)) nil))
                d))]
      semaphore-fn)))

;; ==

(KDeferred/setExceptionLogFn
 (fn log-ex
   [error? e msg]
   (if error?
     (log/error e msg)
     (log/warn e msg))))

(defmethod print-method KDeferred [y ^java.io.Writer w]
  (.write w "#knitty/D[")
  (let [error (md/error-value y ::none)
        value (md/success-value y ::none)]

    (cond
      (not (identical? error ::none))
      (do
        (.write w ":err ")
        (print-method (class error) w)
        (.write w " ")
        (print-method (ex-message error) w))

      (not (identical? value ::none))
      (print-method value w)

      :else
      (.write w "…")))
  (.write w "]"))
