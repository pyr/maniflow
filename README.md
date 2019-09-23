maniflow: utilities on top of manifold
======================================

[![Build Status](https://secure.travis-ci.org/pyr/maniflow.png)](http://travis-ci.org/pyr/maniflow)

```clojure
[spootnik/maniflow "0.1.5"]
```

## Lifecycle management

The `manifold.lifecycle` namespace provides a lightweight take on a
mechanism similar to [interceptors](http://pedestal.io/reference/interceptors)
or [sieppari](https://github.com/metosin/sieppari). A key difference with
interceptors is that individual handlers have no access to the step chain
and thus cannot interact with it. Guards and early termination are still
provided.

A lifecycle is nothing more than a collection of handlers through
which a value is passed.

In essence:

```clojure
@(run 0 [(step inc) (step inc) (step inc)])
```

Is thus directly equivalent to:

```clojure
(-> 0 inc inc inc)
```

### Lifecycle steps

As shown above, each step in a lifecycle is a handler to run on a value.
Steps can be provided in several forms, all coerced to a map.

```clojure
{:id    :doubler
 :enter (fn [input] (* input 2))
 :guard (fn [context] ...)}
```

#### Function steps

When a step is a plain function, as in `(run 0 [inc])`, the
resulting map will be of the following shape:

```clojure
{:id    :step12345
 :enter inc}
```

If the function is provided as a var, the qualified name of the var
is used as the id, so for `(run 0 [#'inc])` we would have instead:

```clojure
{:id    :inc
 :enter inc}
```

#### Accessing parts of the input

Often times, the payload being threaded between lifecycle steps will
be a map. As with interceptors, it might be useful to hold on to
information accumulated during the chain processing. To help with
this, maniflow provides three parameters for steps:

- `:in`: specifies a path that will be extracted from the payload and
   fed as input to the handler
- `:out`: specifies where in the payload to assoc the result of the
  handler
- `:lens`: when present, supersedes the previous two parameters and
  acts as though both `:in` and `:out` where provided

```clojure
{:id    :determine-deserialize
 :enter (partial = :post)
 :in    [:request :request-method]
 :out   [::need-deserialize?])
```

#### Step guards

Based on the current state of processing, it might be useful to guard
execution of a step against a predicate, keeping with the last example:

```clojure
{:id    :deserialize
 :enter deserialize
 :lens  [:request :body]
 :guard ::need-deserialize?}
```

#### Discarding results

Sometimes, 

```clojure
{:id       :debug
 :enter    prn
 :discard? true}
```

#### Building steps with `step`

The `manifold.lifecycle/step` function is provided to build steps
easily, the above can thus be rewritten:

```clojure
(enter-step :determine-deserialize (partial = :post) :in [:request :request-method] :out ::need-deserialize?)
(enter-step :debug prn :discard? true)
(enter-step :deserialize deserialize :guard ::need-deserialize?)
(enter-step :handler run-handler)
```

### Global options

When running a lifecycle, an options map can be passed in to further
modify the behavior:

```clojure
{:augment    (fn [context step] ...)
 :initialize (fn [value] ...)
 :executor   ...}
```

- `augment`: A function of context and current step called for each step
  in the lifecycle.
- `initialize`: A function of init value to prepare the payload.
- `executor`: An executor to defer execution on.


### Error handling

There are intentionally no facilities to interact with the sequence of
steps in this library. Exceptions thrown will break the sequence of
steps, users of the library are encouraged to use
`manifold.deferred/catch` to handle errors raised during execution.

All errors contain the underlying thrown exception as a cause and
contain the last known context in their `ex-data`

```clojure
(-> (d/run 0 [(step inc) (step #(d/future (/ % 0))) (step inc)])
    (d/catch (fn [e]
	            (type (.getCause e))   ;; ArithmeticException
				(:context (ex-data e)) ;; last context)))
```

### Timing wrapper

Assuming a map payload, timing can be recorded for each step
with the `initialize` and `augment` implementations provided
in `manifold.lifecycle.timing`:

```clojure

@(run
  {:x 0}
  [(step :inc inc :lens :x) (step :inc inc :lens :x)]
  timing/milliseconds)

{:timing
 {:created-at 1569080525132,
  :updated-at 1569080525133,
  :index 2,
  :output [{:id :inc, :timing 0} {:id :inc, :timing 1}]},
 :x 2}
```
