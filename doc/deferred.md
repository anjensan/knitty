# Knitty deferreds

Knitty ships with a high-performance implementation of Manifold-style deferreds. They implement the same public protocols as `manifold.deferred` so they interoperate with the rest of the Manifold ecosystem, but are optimised for Knitty's execution engine and tracing.

Key differences compared to stock Manifold deferreds:

- Implemented in Java with lock-free CAS structures rather than synchronized queues.
- Callbacks form a *trident stack*, executing in reverse registration order (similar to `CompletableFuture`).
- Error-leak detection is always enabled (Manifold samples errors by default).
- Executors are associated with individual callbacks instead of being stored on each deferred.
- Fast path for synchronous values (no unnecessary boxing or scheduler hand-offs).
- A single unwrap step is performed when chaining (`bind` mirrors Manifold's `'` variants).

Most of the time you do not need to construct deferreds manually — Knitty runtime does that for you — but the helpers in `knitty.deferred` are handy when integrating external asynchronous APIs or writing custom utilities.

```clojure
(ns user
  (:require [knitty.deferred :as kd]
            [manifold.deferred :as md]))
```

## Creating deferreds

```clojure
(def empty-d     (kd/create))                ;; unrealised placeholder
(def success-d   (kd/wrap 42))               ;; realised value, accepts IDeferred or plain values
(def error-d     (kd/wrap-err (Exception.))) ;; realised with an error
(def coerce-mf   (kd/wrap (md/future 1)))    ;; convert a manifold future
(def coerce-core (kd/wrap* (future 1)))      ;; accepts futures/promises via md/->deferred
```

`kd/success!` and `kd/error!` mirror the Manifold helpers:

```clojure
(def pending (kd/create))
(kd/success! pending :done)
@pending          ;; => :done

(def other (kd/create))
(kd/listen! other #(println (kd/peel other)))
(kd/error! other (IllegalStateException. "nope"))
;; prints IllegalStateException
```

Use `kd/peel` when you expect a deferred to be realised and want to obtain the value (with an optional default):

```clojure
(kd/peel (kd/wrap 5))          ;; => 5
(kd/peel (kd/create) :timeout) ;; => :timeout
```

## Binding and chaining

The core combinator is `kd/bind`. It behaves like `manifold.deferred/chain'`: it does not coerce return values and expects you to return an `IDeferred` when chaining asynchronously.

```clojure
(-> (kd/wrap 1)
    (kd/bind (fn [x] (kd/wrap (inc x))))
    (kd/bind (fn [x] (kd/wrap (* x 3)))))
;; => deferred that realises to 6
```

Convenience forms:

- `kd/bind-err` — catch errors, optionally filtering by class or predicate on `ExceptionInfo` data.
- `kd/bind-fnl` — finally handler; unlike Manifold it awaits the result of the callback.
- `kd/bind->` — macro for piping multiple callbacks (`->` semantics with automatic wrapping).
- `kd/bind-onto` — run a callback on a specific executor.
- `kd/bind=>` — threaded variant for building pipelines.

All of these defer to the same lock-free core and follow the `'` (no coercion) semantics from Manifold.

## Cancellation and `kd/revoke-to`

Manifold's short-circuiting behaviour can be surprising when combined with cancellation. Knitty keeps `kd/bind` simple — if the downstream deferred is cancelled or completed externally, upstream callbacks do **not** receive a cancellation signal automatically.

Use `kd/revoke-to` to propagate cancellations/timeouts back to the original deferred:

```clojure
(let [src (md/future (Thread/sleep 100) :ok)
      derived (-> src
                  (kd/bind #(println "value:" %))
                  (kd/revoke-to src))]
  (md/success! derived ::cancelled))
;; prints nothing; completion is routed back to `src`
```

You can combine it with `kd/bind-err` (or Manifold's `md/catch`) to ensure both success and failure paths are covered.

When bridging non-Manifold APIs, supply a custom cancellation callback via `kd/revoke`:

```clojure
(let [fut (future 
             (Thread/sleep 1000) 
             (println "Work...") 
             1)
      wrapped (-> (kd/wrap* fut)
                  (kd/bind inc)
                  (kd/revoke #(do (future-cancel fut))))]
  (kd/error! wrapped ::stop)
  (assert (future-cancelled? fut)))
```

## Structured flows

Knitty mirrors Manifold's structured helpers and adds sequential variants that avoid building a flow graph:

- `kd/let-bind` — sequential analogue to `md/let-flow` with `:let` and `:when` clauses.
- `kd/while`, `kd/reduce`, `kd/run!` — convenience macros that provide looping semantics.
- Faster alternatives for `md/zip` and `md/alt` that honour the same contracts but avoid repeated coercions.

Refer to the docstrings in `knitty.deferred` for detailed argument lists and behaviour differences.

## Executors

`knitty.deferred/*executor*` (used by `yank` and `kd/future`) defaults to a tuned `ForkJoinPool` (async mode, named threads, error logging). You can replace it globally via `knitty.core/set-executor!` or temporarily by passing `:executor` when calling `yank*`. Use `knitty.deferred/build-fork-join-pool` if you need a customised pool (naming, saturation checks, etc.).

Scheduled tasks use `*sched-executor*`, a `ScheduledThreadPoolExecutor` that preserves dynamic bindings while executing timers.
