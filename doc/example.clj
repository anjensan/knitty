(require
 '[knitty.core
   :as kt
   :refer [defyarn                       ;; main macro to define computation nodes
           defyarn-method defyarn-multi  ;; polimorphic nodes (via clojure multimethods)
           declare-yarn                  ;; predeclare computation node
           yarn                          ;; create dynamic computation node (for mocks)
           link-yarn!                    ;; symlink nodes (use in conjunction with declare-yarn)
           yank                          ;; run computation graph & return all nodes
           yank1                         ;; run computation graph & return only one node
           ]])

;; knitty comes with custom (tho fully compatible) implementation of `manifold.deferred.IMutableDeferred`
;; you can freely intermix any functions from `knitty.deferred` and `manifold.deferred`
(require
 '[knitty.deferred :as kd]
 '[manifold.deferred :as md])


;; == BASIC

;; defin/se computation node `::zero`
(defyarn zero    ;; define "yarn" - single slot/value
  {}             ;; no dependencies on other nodes
  (do            ;; expression (maybe unpure)
    ;(println "compute ::zero")
    0))

;; defyarn do `(def zero ::zero)` for convinience
(type zero)     ;; => clojure.lang.Keyword
(= zero ::zero) ;; => true

;; compute `zero`
(yank {} [zero])  ;; => #knitty/D[#:user{:zero 0}]
@(yank {} [zero]) ;; => #:user{:zero 0}

;; define computation node `::one`
(defyarn one
  {z zero}   ;; assign value of `::zero` yarn to local `z`
  (inc z))   ;; still any expression here

;; compute `::one`
@(yank  {}          [one])  ;; => #:user{:zero 0, :one 1}
@(yank  {zero 0.0}  [one])  ;; => #:user{:zero 0.0, :one 1.0}
@(yank1 {zero 0.0}  one)    ;; => 1.0



;; == ASYNC

;; define async node with side-effects
(defyarn one-slow
  {z zero}
  (kd/future              ;; expression may result deferred value
    (Thread/sleep (long (rand-int 20)))
    (inc z)))

;; combine two nodes (one sync & other is async)
(defyarn two
  {x one
   y one-slow}            ;; knitty await and auto-deref deferreds
  (+ x y))                ;; both x and y are numbers (awaited)

;; combine nodes, use "raw deferreds"
(defyarn three
  {^:defer xd one-slow    ;; use raw 'deferred'
   ^:defer yd one         ;; use wrapped 'deferred' value
   z one}                 ;; use sync value

  (assert (instance? manifold.deferred.IDeferred xd))
  (assert (instance? manifold.deferred.IDeferred yd))
  (assert (instance? java.lang.Number z))

  (md/chain               ;; all functions from md/* works
   (md/zip xd yd)         ;; including md/timeout!, md/let-flow, md/go-off, md/alt ...
   (fn [[x y]] (+ x y z))))



;; == CONDITIONAL-YARNS

;; condidonally depend on node, execute it only when needed and get deferred
(defyarn two-or-one
  {^:lazy a two}     ;; bind 0-arg function, returning a deferred
  (assert (ifn? a))
  (when (zero? (rand-int 2))
    (assert (kd/deferred? (a)))   ;; return deferred
    (assert (identical? (a) @a))  ;; works similar do `delay`
    @a))

;; multiple conditional dependencies may be grouped into a single function
;; same effect may be achieved by injecting multiple ^:lazy nodes
(defyarn arand3
  {^:case f {0 zero   ;; instead of node mapping "value -> node" must be specified
             1 one    ;; values must be a constants, supported by `case` macro
             2 two}}
  (assert (fn? f))
  (let [x (rand-int 3)]
    (assert (kd/deferred? (f x)))  ;; callback returns deferred
    (assert (identical? (f x) (f x)))
    (f x)))

@(yank {} [arand3])  ;; => #:user{:zero 0, :arand3 0} or #:user{:zero 0, :one 1, :arand3 1} or ...

;; sometimes we want to use a node, but not trigger its computation
;; such value may become availabe if any other node triggered its comtutation
(defyarn maybe-two
  {^:maybe x one}
  (assert (kd/deferred? x))  ;; bind raw deferred, may not be realized yet (or never will be)!
  (md/timeout! (kd/bind x inc) 10 :timeout))

@(yank {} [maybe-two])     ;; => #:user{:maybe-two :timeout}
@(yank {} [one maybe-two]) ;; => #:user{:maybe-two 2, :zero 0, :one 1}



;; == MULTI-YARNS

;; define "route" node for polymorphic node `::four`
(defyarn four-stategy {} ::unknown)

;; define polymorphic node `::four`
(defyarn-multi
  four           ;; yarn name
  four-stategy   ;; select implementation based on this value
  )

;; define implementations for `::four` (it uses `defmethod` underhood)
(defyarn-method four :default {} 4)
(defyarn-method four :2*2 {x two} (kd/future (* x x)))
(defyarn-method four :3+1 {x one, y three} (+ x y))

;; compute graph with polymorphic node
@(yank {} [four])                   ;; => #:user{:four-stategy ::unknown, :four 4}
@(yank {four-stategy :2*2} [four])  ;; => #:user{:four-stategy :2*2, :one-slow 1, :zero 0, :one 1, :two 2, :four 4}
@(yank {four-stategy :3+1} [four])  ;; => #:user{:four-stategy :3+1, :one-slow 1, :three 3, :zero 0, :one 1, :four 4}



;; == ABSTRACT-YARNS

;; define "abstract" yarn without an implementation
(declare-yarn five)

;; attempt to compute node withon an implementation
(md/error-value (yank {} [five]) nil)  ;; => clojure.lang.ExceptionInfo "declared-only yarn :user/five"

;; but we can declare other nodes which depent on `::five`
(defyarn ten
  {x five       ;; but we can depend on it
   y two}
  (* x y))

;; declare node which will be used as source for `::five`
(defyarn five-impl
  {x one
   y four}
  (+ x y))

;; redefine node `::five` as symlink to `::five-impl`
(link-yarn! five five-impl)

;; not we can execute graph
@(yank {} [five])  ;; => #:user{:one-slow 1, :two 2, ...}



;; == TRACING

;; modeles render traces as text to console
(require '[knitty.tracetxt])

;; pass {:tracing true} as options to yank
(knitty.tracetxt/print-trace
 @(yank {} [ten] {:tracing true}))

;; or dynamically bind knitty.core/*tracing*
(knitty.tracetxt/print-trace
 @(binding [kt/*tracing* true]
    (yank {} [ten])))

;; or globally enable tracing
(knitty.core/enable-tracing!)
(knitty.tracetxt/print-trace @(yank {} [ten]))

;; visualize traces with "graphviz"
;; recommended to install python tool "xdot" (via 'pip')
(require '[knitty.traceviz])

;; await yank result & display trace
(knitty.traceviz/view-trace
 @(yank {zero 0.001
         four-stategy :2*2}
        [ten]))

;; view all traces of multiple executions at once
(knitty.traceviz/view-trace
 @(md/chain
   {}
   #(yank % [two])
   #(yank % [five])
   #(assoc % two 222222)
   #(yank % [ten])))

;; get raw format (as clojure data)
(knitty.traceviz/render-trace @(yank {} [ten]), :format :raw)

;; or graphviz dot (as string)
(knitty.traceviz/render-trace @(yank {} [ten]), :format :dot)



;; == DYNAMIC-YARNS / MOCKS

;; re-yank value from result-map (reuse all existing nodes, compute only missing)
(let [x @(yank {zero 0.0} [ten])]
  @(yank1 x five))  ;; pick just one yarn, intended for using in REPL

;; dynamically create node (may caupture locals), use it only in REPL
(let [captured 2]
  @(yank1 {} (yarn ::eight {f four} (* f captured))))

;; provide value for some yarns (aka mock)
@(yank {two 200, five 500} [ten])  ;; => #:user{:two 200, :five 500, :ten 100000}

;; load testing helpers
(require '[knitty.testing :as ktt])

;; dynamically redefine some yarns (may capture mutable refs)
(let [y2-called (atom false)
      y5-called (atom false)]
  (ktt/with-yarns [(yarn ::two {} (reset! y2-called true) 2)
                   (yarn ::five {} (reset! y5-called true) 5)]
    (do
      @(yank {} [ten])  ;; => #:user{:five 5, :two 2, :ten 10}
      (assert (and @y2-called @y5-called)))))

;; disable all yarns which keys does not match predicate
(ktt/with-yarns-only (ktt/from-this-ns)
  @(yank {} [ten]))

;; disable all yarns which keys does not belong to specified namespaces
(ktt/with-yarns-only (ktt/from-ns #"us.r")
  @(yank {} [ten]))

;; calling disabled yarn returns an exception
(try
  (ktt/with-yarns-only (every-pred (ktt/from-this-ns) (complement #{five}))
    @(yank {} [ten]))
  (catch clojure.lang.ExceptionInfo e e))  ;; => #error { :cause "yarn :user/five is disabled" ... }

;; yank, await and postprocess results
(ktt/yank-explain! {} [ten])

;; yank & check
(let [r (ktt/yank-explain! {} [ten])]
  (assert (:ok? r))                                          ;; yank was succeeded
  (assert (empty? (:leaked r)))                              ;; no leaked (aka unawaited) deferreds
  (assert (empty? (:failed r)))                              ;; no nodes with failure (exception)
  (assert (contains? (-> r :use ten) two))                   ;; node ::two directly used by ::ten
  (assert (not (contains? (-> r :depends* five) one-slow)))  ;; node ::five has no dependency (direct or indirect) to ::one-slow
  )


;; == TIMING

;; calculate histograms of timings (duration, start-at etc)
(require '[knitty.tstats])

;: define 0/1-arity function (with all defaults)
(def stats-collector
  (knitty.tstats/timings-collector))

;; use 1-arity version to capture timings from yank-result
@(stats-collector (yank {} [ten])) ;; => #:user{:two 2, :zero 0, ...}

;; run more times...
(dotimes [_ 1000]
  (stats-collector (yank {} [ten])))

;; use 0-arity version to dump results
;; returns seq of maps with keys [:yarn :event :min :max :mean :stdv :pcts :total-count :total-sum]
;; supported events are #{:yank-at :call-at :done-at :deps-time :time}
(stats-collector)  ;; => ({...}, ...)

;; by default (stats-collector) returns stats for last minute only
;; underhood it splits results in chunk of "window-chunk" size (in ms)
;; and aggregate several last chunks to compute results value
;; :total-sum & :total-count are accumulative (only grows)
(def stats-collector2
  (knitty.tstats/timings-collector
   {:yarns  #{::ten ::one-slow}  ;; predicate - what yarns to track
    :events #{:yank-at :time}    ;; predicate - what types of events to track
    :window 20000                ;; how long (in ms) to keep results, use 'nil' to keep forever
    :window-chunk 1000           ;; size (in ms) of a single chunk
    :precision 3                 ;; "precision" is bypassed to org.HdrHistogram
    :percentiles [50 95 99.9]    ;; what percentile to compute
    }))

;; rerun & dump timings
(do
  (dotimes [_ 10000] (stats-collector2 (yank {} [ten])))
  (stats-collector2))  ;; => ({...}, ...)



;; == EXECUTION

;; by default `yank` executes code in dedicated FJP-pool
(defyarn capture-thread {}
  (.getName (Thread/currentThread)))

@(yank {} [capture-thread])  ;; => #:user{:capture-thread "knitty-fjp-1"}

;; add ^:fork meta to compute node in another thread
(defyarn ^:fork capture-thread2
  {_ capture-thread}
  (Thread/sleep 100)
  (.getName (Thread/currentThread)))

@(yank {} [capture-thread2])  ;; => #:user{:capture-thread "knitty-fjp-1", :capture-thread2 "knitty-fjp-2"}

;; run code directly (withot an executor)
@(yank {} [capture-thread] {:executor nil})  ;; => #:user{:capture-thread "nREPL-session-4e118cd3-326f-4f2c-a2d4-3dfadb6aa1c7"}

;; globally disable knitty FJP
(kt/set-executor! nil)
@(yank {} [capture-thread])  ;; => #:user{:capture-thread "nREPL-session-4e118cd3-326f-4f2c-a2d4-3dfadb6aa1c7"}
