# Testing

Utilities for testing live in the `knitty.testing` namespace.

```clojure
(require
  '[knitty.core :as kt] 
  '[knitty.testing :as ktest])
```

## Dynamic anonymous yarns

You can create ad-hoc yarns without automatically registering them in the global registry.

```clojure
(kt/defyarn input1 {} 1)

(def y (kt/yarn ::my-yarn {x input1} (inc x)))
(assert (fn? y)) ;; implementation detail: yarns are functions

@(kt/yank {} [y])

;; but `::my-yarn` is not in the registry, so that will fail
;; (kt/defyarn fail {x ::my-yarn} x)

(kt/register-yarn y)

;; and now it works
(kt/defyarn nofail {x ::my-yarn} x)

@(kt/yank {} [nofail])
;; => #:user{:input1 1, :my-yarn 2, :nofail 2}
```

Ad-hoc yarns may capture local bindings:

```clojure
(let [x 10]
 (kt/register-yarn (kt/yarn ::ten {} x)))
@(kt/yank {} [::ten])

```

## Mocking

There is a low-level helper that creates a mock registry of yarns. When Knitty needs to invoke a yarn, it consults the global registry `kt/*registry*`. By binding a mocked registry to `kt/*registry*` (for example with `binding`) you can intercept yarn lookups and substitute mocks.

```clojure

(kt/defyarn my-yarn {} 1)
(def my-yarn-mock (kt/yarn ::my-yarn {} 1000))

(binding [kt/*registry* (ktest/mock-registry
                         kt/*registry*
                         (fn [k]
                           (when (= k ::my-yarn)
                             my-yarn-mock)))]
  @(kt/yank {} [my-yarn]))
```

There are helper macros `with-yarns` and `with-yarns-only`. `with-yarns` lets you overwrite existing yarns with provided mocks. `with-yarns-only` replaces all yarns except the specified ones with "always fail" stubs, which is useful to limit the subset of yarns that can be invoked during a test.

```clojure
(ktest/with-yarns [my-yarn-mock]
  @(kt/yank {} [my-yarn]))
;; => #:user{:my-yarn 1000}
```

Mocks may have side effects:

```clojure
(let [c (atom 0)]
  (ktest/with-yarns [(kt/yarn ::my-yarn {} (swap! c inc) 1000)]
    (assert (== 0 @c))
    @(kt/yank {} [my-yarn])  ;; => #:user{:my-yarn 1000}
    (assert (== 1 @c))))
```

Macro `with-yarns-only` accepts a predicate function that is called with a yarn-id (keyword).
```clojure

(kt/register-yarn (kt/yarn :test.one/y {} 1))
(kt/register-yarn (kt/yarn :test.two/y {} 2))

(ktest/with-yarns-only (ktest/from-ns 'test.two)
  @(kt/yank {} [:test.two/y]))

;; but this will fail
(ktest/with-yarns-only (ktest/from-ns 'test.two)
  @(kt/yank {} [:test.one/y])) ;; => error
```

Function `from-ns` allows you to specify namespaces via ther names (symbols) or regular expressions (matched against a namespace name).

## Tracing

Knitty can log all actions (yarn requested, computation started, etc.) as an immutable tracelog. Tracing is disabled by default because it introduces some overhead.

```clojure

(kt/defyarn n1 {} 1)
(kt/defyarn n2 {x n1} (inc x))

;; enable globally (useful for dev)
(knitty.core/enable-tracing!)

;; or per call to `yank`
(def y @(kt/yank {} [n2] {:tracing true}))

y
;; => #:knitty.testing{:n1 1, :n2 2}

(meta y)
;; => {:knitty/trace
;;      ({:at #inst "2025-09-17T14:39:56.847-00:00",
;;        :base-at 152992595641770,
;;        :done-at 152992595870736,
;;        :result nil,
;;        :yarns [:user/n2],
;;        :tracelog
;;        ({:yarn :user/n2, :event :knitty.trace/trace-finish, :value 152992595835399}
;;         ...
;;         ),
;;        :yankid 5,
;;        :input {}})}
```

Tracing is attached to the resulting map as metadata. It is usually consumed via helper tooling rather than inspected directly.

Tracelog may be printed to console:

```clojure
(require 'knitty.tracetxt)
(knitty.tracetxt/print-trace y)
;; prints:
; at 2025-09-17 16:39:56.847 yank [:user/n2]
; yanked :user/n2 
; yanked :user/n1 by :user/n2
; exec :user/n1
; done :user/n1 
; exec :user/n2
; done :user/n2 
```

You can also render it as a Graphviz graph:

```clojure
(require 'knitty.traceviz)

;; convert trace to execution graph
(knitty.traceviz/render-trace y {:format :raw})
;; => map with graph (nodes, edges, info)
;; {:type :knitty/parsed-trace,
;;  :nodes
;;  ([:user/n2 {...}]
;;   [:user/n1 {...}]),
;;  :links
;;  ([[:user/n2 :user/n1] {...}])}

;; render graph with graphiz (dot)
(knitty.traceviz/render-trace y {:format :dot})
;; => "digraph {\n\ngraph[dpi=120, rankdir=TB, ranks ..."
```

Or open the rendered graph in an external GUI tool `xdot`, or your browser as an SVG:
```clojure
(knitty.traceviz/view-trace y)
```

Tracing records node lifecycle, dependencies, timing, and thread data. You can enable tracing per-call, globally, or via dynamic binding.

Traces are stored in `:knitty/trace` metadata/ex-data and can be retrieved with `knitty.trace/find-traces`.

You can visualize traces as text, SVG, or Graphviz dot files. See the next section for more details.

## Capture timings

Traces contain nanosecond timestamps for when each node started, called its body, and finished. You can aggregate that information with `knitty.tstats` to build histograms or sliding-window statistics.

```clojure
(require 'knitty.tstats)

;; stats-agg collector with default settings
(def stats-agg (knitty.tstats/timings-collector))

;; run with tracing enabled and feed results to collector
(binding [kt/*tracing* true]
  (dotimes [_ 1000]
    (let [y @(kt/yank {} [n2] {:tracing true})]
      (stats-agg y)))) ;; feed timestamps into the collector

;; snapshot of aggregated stats
(stats-agg)
;; =>
;;{[:user/n1 :done-at]
;; {:total-count 1,
;;  :total-sum 383751,
;;  :count 1,
;;  :mean 383872,
;;  :stdv 0,
;;  :min 383744,
;;  :max 383999,
;;  :pcts ([50 383999] [90 383999] [95 383999] [99 383999])},
;; [:knitty.testing/n2 :time]
;; {:total-count 1,
;;  :total-sum 21571,
;;  :count 1,
;;  :mean 21576,
;;  :stdv 0,
;;  :min 21568,
;;  :max 21583,
;;  :pcts ([50 21583] [90 21583] [95 21583] [99 21583])},
;; ... }

;; mean execution time for ::n2 in nanoseconds
(let [snapshot (stats-agg)]
  (-> snapshot (get [:user/n2 :time]) :mean))
;; => 21576
```

The stats collector is a stateful function. Internally it aggregates samples into small windows (3 seconds by default) and keeps the most recent minute of data. Calling the function with no arguments merges all retained windows into a single report. You can disable windowing or change its parameters via optional arguments; see `knitty.tstats/timings-collector` for details.
