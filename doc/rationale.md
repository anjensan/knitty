# Rationale

## Iteration 0

Originally, Knitty was not about asynchronicity. Its target was to make it easier to navigate through tens (and hundreds) of "ingested features".
Features might be anything (some data from a request, from a DB), may depend on each other, and their calculation is spread across multiple namespaces (just because there are a lot of them).
Each feature has its unique name, and dependencies may be complex and change over time.
There are several use cases where the set of features we are interested in is different. So Knitty was trying to solve dependency hell, make it easier to remove unused parts,
and protect from mistakes (like trying to read some value from a map that is not yet added to it).

The idea was to provide dependencies between values, link their IDs with docstrings and actual code responsible for producing their values.
Add clojure specs to the mix and place them together in a structured way, where it is easy to find everything just by an ID (which you might see in logs, message queues, databases, etc).

## Iteration 1

Later, the focus shifted to integration with `Manifold`—features might need asynchronous computation.
Surprisingly, it also became a useful way to combine asynchronous subprograms in a more manageable way.
It started to resemble the [Dagger Producers](https://dagger.dev/dev-guide/producers.html) framework, but adapted to the dynamic nature of Clojure.
Because Clojure's power comes from the REPL, Knitty tries to keep that: the execution graph is dynamic, open for extension by default,
and there is no need for an explicit "compile graph" stage, etc.

## Thoughts

Here is a bullet list of current thoughts and ideas that actually drive Knitty's design:

When you see a pure data (in logs, message queue, document database) - it should be easy to recognize what code was responsible to produce that data.
  - Natural way: use fully qualified keywords as IDs, where the namespace of the keyword matches the namespace of the code.

Values are computed only when they are actually needed, nothing computes twice.
  - Memoization of values and computing values "on-demand"; laziness is natural for Clojure.

Mutation is allowed only for short period of time, when data is fully produced - it must be immutable.
  - A graph executes with a "mutable context" (uses mutable structures under the hood); after completion, it must be just a persistent collection.

Should be a way to "extend" existing workflow without breaking it.
  - Any computation node may be reused, but there should also be a way to hide some of them (as ^:private).

Async is supported, but not enforced (or actually enforced, but to a minimum possible way that is just required to have a single api).
  - As the whole graph may be asynchronous, it is OK to return a deferred from the "entry point".

Overhead should be small, especially for "fully synchonous graphs".
  - There should be a separate branch for the synchronous variant; ideally, it should be inlinable by a JIT.

Development workflow should be REPL-friendly.
  - It should be easy to redefine any node in a graph from the REPL, ideally without any "graph full recompilation".

Debugging and analyzing of existing graph should be easy:
  - There must be a way to view/analyze what is actually happening with a graph at runtime (traces/logs).

Support from editor/IDE (navigate to code used to produce value, highligh errors and warnings).
  - DSL vs Macros: DSLs are easy to create, but harder to check/validate in IDEs.

Thread safety, "cheap" asynchronoucity.
  - Developers should not need to deal with concurrency directly when it is not needed.

## Limitations

- Knitty targets only the JVM (no ClojureScript support).

- The graph *must* be acyclic. That is strictly enforced at runtime.

- Any deferred *should not* be awaited directly (with a blocking call to `deref`).
  This may work in some cases (where you know what you are doing), but usually it might introduce deadlocks.

- Knitty is intended to work with a single global graph, where only parts of it are executed on demand.
  Multiple independent graphs *are* supported, but the API is not friendly enough for such use cases.

- It uses macros (not a DSL), so to get the best user experience you might need an editor with integration with `clj-kondo`.

- Line-by-line debugging is not supported due to how Knitty is implemented.
  This might be solved in the future, but for now you need to move code outside yarns and debug it explicitly.

- Knitty generates a lot of code. So bootstrapping and JIT time of application may be slightly affected.

## Example

Here is an example of theoretical work with Knitty. Assume we are writing a web application and need to render a home page for a specific user. We already have functions with our business logic:

```clojure
;; testable & composable, so no direct dependencies between them
(defn parse-params [req] ::params)
(defn extract-user-id [params] 123)
(defn fetch-user-by-id [user-id] {:name "User", :id user-id})
(defn compute-data-for-user-home-page [user] {:name (:name user)})
(defn render-data-for-user-home-page [home-page-data] (pr-str home-page-data))
```

Lets wire them with a traditional approach:

```clojure
(defn generate-home-page [req]
  (let [params         (parse-params req)
        user-id        (extract-user-id params)
        user           (fetch-user-by-id user-id)
        home-page-data (compute-data-for-user-home-page user)
        home-page      (render-data-for-user-home-page home-page-data)]
    {:params params
     :user-id user-id
     :user user
     :home-page-data home-page-data
     :home-page home-page}))
```

The function returns not a single value, but a map of all "steps" — easier to debug/test.
Order of execution is explicit and enforced by the Clojure compiler (you can't make a mistake by incorrectly reordering them).
Everything is evaluated only once; looks good.

Lets try rewrite this with Knitty:
```clojure
(require '[knitty.core :refer [defyarn yank]])

(defyarn req)
(defyarn params         {r req}               (parse-params r))
(defyarn user-id        {p params}            (extract-user-id p))
(defyarn user           {ui user-id}          (fetch-user-by-id ui))
(defyarn home-page-data {ui user-id}          (compute-data-for-user-home-page ui))
(defyarn home-page      {d home-page-data}    (render-data-for-user-home-page d))

(defn generate-home-page [r]
  @(yank {req r} [home-page]))
```

It's harder to make a typo in resulting map keys. But overall, honestly, it does not look any better yet.

And now lets imagine we need an API, so we require another way to render home-data. Assume we have
```clojure
(defn render-home-page-for-api [user home-page-data api-format] 
  (str api-format "|" home-page-data))
(defn extract-required-api-format [params] 
  (get params :api :api-v1))
```

New code could be:
```clojure
(defn generate-home-page-for-api [req]
  (let [params            (parse-params req)
        user-id           (extract-user-id params)
        user              (fetch-user-by-id user-id)
        home-page-data    (compute-data-for-user-home-page user)
        api-format        (extract-required-api-format params)
        home-page-for-api (render-home-page-for-api user home-page-data api-format)]
    {:params params
     :user-id user-id
     :user user
     :home-page-data home-page-data
     :home-page-for-api home-page-for-api}))
```

There's a lot of duplication between `generate-home-page` and `generate-home-page-for-api`.
There are ways to deal with it. For example, we can refactor out a new function that will produce just a map with `:home-page-data`.
Let's see how it looks with Knitty:

```clojure
(defyarn api-format        {p params}                                 (extract-required-api-format p))
(defyarn home-page-for-api {u user, d home-page-data, af api-format}  (render-home-page-for-api u d af))

(defn generate-home-page-for-api [r]
  @(yank {req r} [home-page-for-api]))
```

Now, we realize that for rendering we also need to read the user session from another storage.

```clojure
(defn fetch-user-session-by-id [user-id] 
  {:cookie "ABC"})
(defn compute-data-for-user-home-page [user session] 
  {:data "Hello", :name (:name user), :c (:cookie session)})
```

With the traditional approach, we would need to modify both `generate-home-page` and `generate-home-page-for-api`.
Let's see what we need to do with Knitty:

```clojure
(defyarn user-session   {ui user-id}             (fetch-user-session-by-id ui))
(defyarn home-page-data {u user, s user-session} (compute-data-for-user-home-page u s))
```

That's all. Also, it may be just reloaded via REPL — no need to touch code in other namespaces.

Now, finally, let's add another change. We realized that fetching the user and session takes too long,
so let's update our code so they are loaded in parallel. If our functions with business logic
return deferreds (our system is asynchronous), no updates are needed. If not, let's wrap calls into
deferreds (note: we need to use futures from Manifold or Knitty, not native ones):

```clojure
(defyarn user-session   {ui user-id}             (kt/future (fetch-user-session-by-id ui)))
(defyarn home-page-data {u user, s user-session} (kt/future (compute-data-for-user-home-page u s)))
```

And lets check computation flow:
```clojure
(require '[knitty.traceviz :as ktviz])

(ktviz/view-trace
  (binding [knitty.core/*tracing* true]
    (generate-home-page-for-api {})))
```

Result:
![](img/rationale_example_trace.svg)


## Alternatives

Here are some alternative libraries with a similar approach.
They don't provide integration with asynchronous code, and focus more on actual graph execution rather than "simplifying to match data and code".
Graphs are explicit and must be explicitly compiled (which is sometimes good, sometimes not).
They may definitely fit better for some users, but unfortunately, did not match all requirements for the project Knitty was born out of.

- [plumatic/plumbing](https://github.com/plumatic/plumbing#graph-the-functional-swiss-army-knife)
- [troy-west/wire](https://github.com/troy-west/wire)
- [bortexz/graphcom](https://github.com/bortexz/graphcom)

For pure java you *can try* framework from Google:
- [Dagger Producers](https://dagger.dev/dev-guide/producers.html).

