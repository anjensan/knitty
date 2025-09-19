# Advanced features

### Weak dependencies (`^:maybe`)

Sometimes you want to consume a dependency only if something else triggers it, without starting it yourself. Use the `^:maybe` binding to obtain a deferred that may remain unrealised.

```clojure
(defyarn heavy-value {}
  (md/future (reduce + (range 1000000))))

(defyarn use-heavy-when-available
  {^:maybe h heavy-value}
  (-> h
      (md/chain (fn [hv]
                  (println "heavy value is" hv)
                  :called))
      (md/timeout! 100 :not-ready)))

@(yank {} [heavy-value use-heavy-when-available])
;; => #:user{:use-heavy-when-available :called, :heavy-value 499999500000}

@(yank {} [heavy-value])
;; => #:user{:heavy-value 499999500000}

@(yank {} [use-heavy-when-available])  ;; await takes 100ms!
;; => #:user{:use-heavy-when-available :not-ready}
```

Notes:
- `^:maybe` returns a raw deferred; it may remain unresolved forever.
- Combine it with `md/timeout!` (or similar) if you need to handle the "never" case gracefully.
- This flag is mostly useful for caches and instrumentation, not for routine control flow.

### Dynamic dependencies (`^:case`)

Use `^:case` to bind a single-argument function that routes to one of several yarns based on an input value. Mapped values must be `case`-compatible constants.

```clojure
(defyarn value-a {} 10)
(defyarn value-b {} 20)

(defyarn some-value
  {^:case pick {0 value-a, 1 value-b}}
  ;; `pick` is a fn returning a deferred of the selected yarn
  ;; then deferred will be awaited by knitty internally
  (pick (rand-int 2)))

@(yank {} [some-value])
;; => #:user{:value-a 10, :some-value 10} or #:user{:value-b 20, :some-value 20}
```

You can also provide a set/sequence (`#{::a ::b ...}`), which is treated as an identity mapping.

Example with sets:

```clojure
(defyarn choose-set {^:case pick #{value-a value-b}}
  (if (zero? (rand-int 2))
    (pick value-a)
    (pick value-b)))
```

### Multiyarns & routing

Polymorphic yarns use `defyarn-multi` and `defyarn-method` to dispatch implementations based on a route yarn. This supports hierarchies and default methods.

The value of the route yarn selects the appropriate implementation. Each implementation is itself a yarn, so it can declare further dependencies; only the dependencies of the chosen method are computed.


```clojure
(require '[knitty.core :refer [defyarn defyarn-multi defyarn-method yank]])

(defyarn route)
(defyarn-multi poly route :default :unknown)

(defyarn do-calc-two {}
  (println "calculate B ...")
  (md/future (+ 1 1)))

(defyarn-method poly :a       {} 1)
(defyarn-method poly :b       {x do-calc-two} x)
(defyarn-method poly :unknown {} 0)

@(yank {route :a} [poly]) ;; => #:user{:route :a, :poly 1}
@(yank {route :b} [poly]) ;; => #:user{:route :b, :do-calc-two 2, :poly 2}
@(yank {route :w} [poly]) ;; => #:user{:route :w, :poly 0}
```

### Abstract yarns

You can declare a yarn without implementation, then later link it to an actual implementation.

```clojure
(require '[knitty.core :as k])

(k/declare-yarn abstract)
(defyarn dowork {x abstract} (inc x))

@(yank {} [dowork])
;; => error: declared-only Yarn :user/abstract

(defyarn impl {} 10)
(k/link-yarn! abstract impl)   ;; redefine `abstract` as symlink to `impl'
@(yank {} [dowork])            ;; => #:user{:impl 10, :abstract 10, :dowork 11}
```

This can be useful to connect yarns defined in different namespaces without creating a hard dependency between those namespaces. Here is an example where namespace `user.a` uses an yarn from `user.b`, but does not require `user.b` at load time.

```clojure
(ns user.a (:require [knitty.core :as k]))
(k/declare-yarn value-used-by-a)
(k/defyarn work {a value-used-by-a} (inc a))

(ns user.b (:require [knitty.core :as k]))
(k/defyarn produce-value-for-a {} 10)

(ns user
  (:require [knitty.core :as k] 
            [user.a :as a]
            [user.b :as b]))

(k/link-yarn! a/value-used-by-a b/produce-value-for-a)

@(k/yank {} [a/work])
;; => {:user.b/produce-value-for-a 10, :user.a/value-used-by-a 10, :user.a/work 11}

```

## TODO
- Execution model (DFS)
- Integration with the ForkJoin executor
- Preloading inputs
- Capturing thread-local bindings
- Working with the yarn registry in detail
