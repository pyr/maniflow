maniflow: utilities on top of manifold
======================================

[![Build Status](https://secure.travis-ci.org/pyr/maniflow.png)](http://travis-ci.org/pyr/maniflow)

```clojure
[spootnik/maniflow "0.1.2"]
```

## Lifecycle management

The `manifold.lifecycle` namespace provides a lightweight take on a
mechanism similar to [interceptors](http://pedestal.io/reference/interceptors).

A lifecycle is nothing more than a collection of handlers through
which a value is passed.

In essence:

```clojure
@(run 0 [inc inc inc])
```

Is thus directly equivalent to:

```clojure
(-> 0 inc inc inc)
```

### Lifecycle steps

As shown above, each step in a lifecycle is a handler to run on a value.
Steps can be provided in several forms, all coerced to a map.

```clojure
{:manifold.lifecycle/id             :doubler
 :manifold.lifecycle/handler        (fn [result] (* result 2))
 :manifold.lifecycle/show-context?  false
 :manifold.lifecycle/guard          (fn [context] ...)}
```

#### Function steps

When a step is a plain function, as in `(run 0 [inc])`, the
resulting map will be of the following shape:

```clojure
{:manifold.lifecycle/id            :step12345
 :manifold.lifecycle/handler       inc
 :manifold.lifecycle/show-context? false}
```

If the function is provided as a var, the qualified name of the var
is used as the id, so for `(run 0 [#'inc])` we would have instead:

```clojure
{:manifold.lifecycle/id            :clojure.core/inc
 :manifold.lifecycle/handler       inc
 :manifold.lifecycle/show-context? false}
```

#### Accessing and modifying the context

There are cases where accessing the context directly could be useful,
in this case `:manifold.lifecycle/show-context?` should be set to true
in the step definition:

```clojure
(defn determine-deserialize
  [{:manifold.lifecycle/keys [result] :as context}]
  (cond-> context
    (= :post (:request-method result))
	(assoc context ::need-deserialize? true)))
	
{:manifold.lifecycle/id            :determine-deserialize
 :manifold.lifecycle/handler       determine-deserialize
 :manifold.lifecycle/show-context? true}
```

#### Step guards

Based on the current state of processing, it might be useful to guard
execution of a step against a predicate, keeping with the last example:

```clojure
{:manifold.lifecycle/id            :deserialize
 :manifold.lifecycle/handler       deserialize
 :manifold.lifecycle/show-context? false
 :manifold.lifecycle/guard         ::need-deserialize?}
```

### Global options

When running a lifecycle, an options map can be passed in to further
modify the behavior:

```clojure
{:manifold.lifecycle/clock       clock-implementation
 :manifold.lifecycle/raw-result? false}
```

The clock options is an implementations of `spootnik.clock.Clock` from
[commons](https://github.com/pyr/commons) used for timing steps in
the context map. `raw-result?` defaults to false and determines whether
the deferred that `run` yields the result or the context map.

### Error handling

There are intentionally no facilities to interact with the sequence of
steps in this library. Exceptions thrown will break the sequence of
steps, users of the library are encouraged to use
`manifold.deferred/catch` to handle errors raised during execution.

All errors contain the underlying thrown exception as a cause and
contain the last known context in their `ex-data`

```clojure
(-> (d/run 0 [inc #(d/future (/ % 0)) inc])
    (d/catch (fn [e]
	            (type (.getCause e)) ;; ArithmeticException
				(:manifold.lifecycle/context (ex-data e)) ;; last context)))
```
