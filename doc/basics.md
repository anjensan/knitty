# Basics

Knitty is a dependency-aware computation engine for Clojure. You describe individual computations (called *yarns*), declare their dependencies, and ask Knitty to "yank" the values you need. Knitty builds a DAG, memoises intermediate results, resolves asynchronous values, and can attach detailed execution traces along the way.

There are two basic building blocks in Knitty: macro `defyarn` and function `yank`.

```clojure
(ns user
  (:require [knitty.core :refer [defyarn yank yank* yank1]]
            [manifold.deferred :as md]))
```

## Defining yarns

`defyarn` registers a computation under a qualified keyword that is derived from the current namespace and the provided symbol. The macro also defines a var with that keyword so you can refer to the yarn symbolically.

```clojure
(defyarn input "A simple input yarn with no dependencies.")

(keyword? input)
;; => true

input
;; => :user/input
```

Dependencies are described with an ordered map whose keys are local binding symbols and whose values are yarn references (keywords or vars that deref to keywords). Knitty ensures the requested dependencies exist, yanks them if needed, and binds the results.

```clojure
(defyarn total
  {x input}
  (reduce + x))

(defyarn length
  {x input}
  (count x))

(defyarn average
  {t total
   c length}
   (/ t c))
```

You can forward-declare placeholders using `declare-yarn`.
Then define then later `link-yarn!` them to a real implementation. 
See [doc/advanced.md](advanced.md) for details.

## Pulling values with `yank`

Function `yank` takes a map of known values and a collection of yarns to realise. It always returns a `manifold.deferred/IDeferred` that resolves to an immutable map containing the original inputs plus any computed yarns.

```clojure
@(yank {input [1 2 3]} [total])
;; => #:user{:input [1 2 3], :total 6}

@(yank {input [1 2 3]} [average])
;; => #:user{:input [1 2 3], :length 3, :total 6, :average 2}
```

When you need lower-level control, use `yank*`. It returns a `knitty.javaimpl.YankResult` which implements associative/sequential interfaces but preserves internal memoisation metadata. `yank1` is a convenience for REPL work: it yanks a single yarn and binds to that value directly.

```clojure
(def yr @(yank* {input [1 2 3]} [total]))
(get yr total)
;; => 6

@(yank1 {input [1 2 3]} total)
;; => 6
```

## Dependency binding modes

Bindings accept metadata flags that control how Knitty obtains the dependency:

| Flag      | Meaning                                                                                     |
|-----------|---------------------------------------------------------------------------------------------|
| *(none)*  | Wait for the dependency, unwrap the value, and memoize it for downstream nodes.             |
| `^:defer` | Do not await the dependency; bind the raw deferred.                                         |
| `^:lazy`  | Bind a `IDeref`/function that will trigger the dependency only on first deref/invoke.       |
| `^:maybe` | Bind a deferred that *might never* realize unless some other yarn forces it.                |
| `^:case`  | Bind a single-argument routing function that selects a yarn from a map/set of options.      |
| `^:fork`  | Run the dependency computation on a fresh ForkJoin task. Can be combined with `:defer`.     |

Per-yarn metadata can also include `:fork` (run the body in a fork/join task), `:spec` (auto-register a spec for the yarn keyword).
When yarn is marked with

## Asynchronous results

Yarns can return any value, including `manifold` deferreds. By default Knitty automatically awaits and unwraps deferred results.

```clojure
(defyarn async-value
  {}
  (md/future
    (Thread/sleep 5)
    40))

(defyarn consume-sync
  {x async-value}
  (+ x 2))

@(yank {} [consume-sync])
;; => #:user{:async-value 40, :consume-sync 42}
```

Add `^:defer` to bind the deferred itself and handle completion manually.

```clojure
(defyarn consume-deferred
  {^:defer dx async-value}
  (md/chain dx inc))

@(yank {} [consume-deferred])
;; => #:user{:async-value 40, :consume-deferred 41}
```

If a yarn returns something that is not already a manifold deferred (e.g. a core future or promise) you can coerce it via `md/->deferred` or `knitty.deferred/wrap*`.

```clojure
(defyarn future-backed
  {}
  (md/->deferred (future 5)))

@(yank {} [future-backed])
;; => #:user{:future-backed 5}
```

## Lazy dependencies

`^:lazy` binds a delay-like wrapper that starts the dependency only when first dereferenced or invoked. This is ideal for optional or expensive values.

```clojure
(defyarn expensive
  {}
  (println "computing expensive thing")
  (+ 100 (rand-int 10)))

(defyarn maybe-use
  {^:lazy x expensive}
  (if (< (rand) 0.5)
    -1
    (x) ;; note - `(x)` returns deferred!
    ))

@(yank {} [maybe-use])
;; Prints "computing..." about half of the time.
```

## Handling errors

Errors thrown inside a yarn become errors on the resulting deferred. You can combine `^:defer` with the manifold error helpers to handle failures explicitly.

```clojure
(defyarn fail {}
  (throw (ex-info "boom" {:reason :demo})))

(defyarn recover
  {^:defer d fail}
  (md/catch d (fn [e]
                {:failed? true
                 :message (ex-message e)})))

(doseq [[k v] @(yank {} [recover])]
  (prn k v))
; :user/fail #knitty/D[:err clojure.lang.ExceptionInfo "boom"]
; :user/recover {:failed? true, :message "boom"}
```

For more complex flows you can rely on `md/alt`, `md/zip`, or the helpers in `knitty.deferred`.
