# Knitty


Knitty is a library for declarative definitions of how data should be computed and what dependencies exist between different pieces.

Knitty assigns data computation functions to qualified keywords. Each such function explicitly declares all needed dependencies (also keywords). A user provides the initial data set as a map and requests what keys should be added — Knitty takes care of the rest: builds a dependency graph, checks for cycles, resolves deferred, memoizes all values, applies tracing and profiling.

## Usage

Add dependency:

[![Clojars Project](https://clojars.org/com.github.anjensan/knitty/latest-version.svg)](http://clojars.org/com.github.anjensan/knitty). 

Require namespace:

```clojure
(require '[knitty.core :refer [defyarn yank]])
```

Define a few "nodes of computation" with macro `defyarn`:

```clojure
(defyarn node-a)         ;; "input" node
(defyarn node-b {} 2)    ;; node with default value

(defyarn node-c  ;; yarn name
  {a node-a
   b node-b}     ;; dependencies
  (+ a b))       ;; value expression
```

Compute nodes by calling the function `yank`, passing values for input nodes as map:

```clojure
@(yank {} [])                              ;; nothing to compute,
;; => {}

@(yank {node-a 1} [node-c])                ;; compute node-c base on node-a 
;; => #:user{:node-a 1, :node-b 2, :node-c 3}

@(yank {node-a 10, node-b 20} [node-c])    ;; noop, as node-c is already computed
;; => #:user{:node-a 10, :node-b 20, :node-c 30}
```

## Deferreds

Knitty also integrates with [clj-commons/manifold](https://github.com/clj-commons/manifold):


```clojure
(require '[manifold.deferred :as md])

;; node may return an async value
(defyarn anode-x {c node-c}
  (md/future (* c 10)))

;; all dependencies are automatically resolved
(defyarn anode-y {x anode-x, c node-c}
  (+ x c))

;; but a raw async value may still be used
(defyarn anode-z {^:defer x anode-x}
  (md/chain' x dec))

(md/chain
  (yank {node-a 1, node-b 10} [anode-x, anode-y, anode-z])
  println)
;; #:user{:node-a 1, :node-b 10, :anode-z 109, :anode-y 121, :node-c 11, :anode-x 110}
;; => #<SuccessDeferred@6b4da3bf: nil>
```


Knitty can also track when and how nodes are computed.
This information may be used for visualizations:

```clojure
(require
  '[knitty.tracetxt :as ktt]
  '[knitty.traceviz :as ktv])

(def m 
  (binding [knitty.core/*tracing* true] ;; enable tracing
    @(yank 
      {node-a 1, node-b 10} 
      [anode-x, anode-y, anode-z])))

(class m)
;; => clojure.lang.PersistentArrayMap

;; print execution log to *out*
(ktt/print-trace m)

;; open trace as svg image in the web browser
(ktv/view-trace m)
```

![](doc/img/readme_trace_example1.svg)


More examples and details can be found in the documentation:

- [basics](doc/basics.md) — getting started, core concepts.
- [rationale](doc/rationale.md) — why the library was created.
- [advanced](doc/advanced.md) — multiyarns, optional dependencies, and registry tricks.
- [deferreds](doc/deferred.md) — Knitty's deferred implementation and helpers.
- [testing](doc/testing.md) — fixtures, mocking yarns, and working with traces.

## License

Distributed under the MIT License.
