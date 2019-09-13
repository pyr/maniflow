(ns manifold.lifecycle
  "
  Convention based lifecycle management of asynchronous chains.
  This implements a lightweight take on the ideas behind interceptors
  and similar libraries.

  Notable differences are:

  - Manifold is the sole provided execution model for chains
  - No chain manipulation can occur
  - Guards and finalizer for steps are optional
  - Exceptions during lifecycle steps stop the execution
  "
  (:require [spootnik.clock     :as clock]
            [clojure.spec.alpha :as s]
            [manifold.deferred  :as d]))

(defn step
  "Convenience function to build a step map.
   Requires and ID and handler, and can be fed
   additional options"
  [id handler & {:keys [context? guard]}]
  (cond->
      {::id       id
       ::handler  handler
       ::context? (or context? false)}
    (some? guard)
    (assoc ::guard guard)))

(defn ^:no-doc initialize-context
  "Create a context which will be threaded between executions"
  [{::keys [clock] :or {clock clock/wall-clock}} init]
  (let [timestamp (clock/epoch clock)]
    {::created-at timestamp
     ::updated-at timestamp
     ::input      init
     ::index      0
     ::output     []
     ::clock      clock}))

(defn ^:no-doc augment-context
  "Called after a step to record timing"
  [{::keys [clock index] :as context} id last-update]
  (let [timestamp (clock/epoch clock)
        timing    (- timestamp last-update)]
    (-> context
        (update ::index inc)
        (assoc  ::updated-at timestamp)
        (update ::output conj {::id id ::timing timing}))))

(defn ^:no-doc rethrow-exception-fn
  "When chains fail, augment the thrown exception with the current
   context state"
  [context]
  (fn [e]
    (let [data  (ex-data e)
          extra {:type    :error/fault
                    ::context context}]
      (throw (ex-info (.getMessage e) (merge extra data) e)))))

(defn ^:no-doc prepare-step
  "Coerce input to a step"
  [input]
  (cond
    (map? input)
    input

    (var? input)
    (let [{:keys [ns name]} (meta input)]
      (step (keyword (str ns) (str name)) (var-get input)))

    (keyword? input)
    (step input input)

    (instance? clojure.lang.IFn input)
    (step (keyword (gensym "step")) input)

    :else (throw (ex-info "invalid step definition"
                          {:error/type :error/invalid}))))

(defn ^:no-doc wrap-step-fn
  "Wrap each lifecycle step. This yields a function which will run a
   step's handler. If a guard is present, the execution of the function
   will be dependent on the guard's success, if a finalizer is present,
   it will be ran.

   Any exception caught will be rethrown with the current context
   state attached."
  [{::keys [clock]}]
  (bound-fn [{::keys [id handler guard context?] :as step}]
    {:pre [(s/valid? ::step step)]}
    (bound-fn [{::keys [input] :as context}]
      (let [context (augment-context context id (clock/epoch clock))
            input   (if context? context input)
            rethrow (rethrow-exception-fn context)]
        (try
          (if (or (nil? guard) (guard context))
            (d/catch
                (cond-> (handler input)
                  (not context?)
                  (d/chain #(assoc context ::input %)))
                rethrow)
            input)
          (catch Exception e
            (rethrow e)))))))

(defn ^:no-doc prepare-steps
  "Prepares chain function for each given step.
   By default, the input is extracted out of a chain at
   its end, `:manifold.lifecyle/context?` set to true
   in opts to `run` will ensure this is not the case"
  [{::keys [context?]} steps]
  (cond-> (mapv prepare-step steps)
    (not context?)
    (conj (step ::input ::input :context? true))))

(defn run
  "
  Run a series of potentially asynchronous steps in sequence
  on an initial value (`init`0, threading each step's result to
  the next step as input.

  Steps are maps or the following keys:

      [:manifold.lifecycle/id
       :manifold.lifecycle/handler
       :manifold.lifecycle/show-context?
       :manifold.lifecycle/guard]

  - `id` is the unique ID for a step
  - `handler` is the function of the previous result
  - `context?` determines whether the handler is fed
    the context map or the plain input. When false, the output
    is considered to be the result, not the context.
  - `guard` is an optional predicate of the current context and previous
    preventing execution of the step when yielding false

  Steps can also be provided as functions or vars, in which case it is
  assumed that `context?` is false for the step, and ID is inferred
  or generated.


  In the three-arity version, an extra options maps can
  be provided, with the following keys:

      [:manifold.lifecycle/clock
       :manifold.lifecycle/context?]

  - `clock` is an optional implementation of `spootnik.clock/Clock`,
    defaulting to the system's wall clock (`spootnik.clock/wall-clock`)
  - `context?` toggles extraction of the result out of the context
    map, defaults to `false`
   "
  ([init steps]
   (run init steps {}))
  ([init steps opts]
   (let [context (initialize-context opts init)]
     (apply d/chain context
            (->> (prepare-steps opts steps)
                 (map (wrap-step-fn context)))))))

;; Specs
;; =====

(s/def ::id keyword?)
(s/def ::handler #(instance? clojure.lang.IFn %))
(s/def ::guard #(instance? clojure.lang.IFn %))
(s/def ::description string?)
(s/def ::context? boolean?)
(s/def ::step (s/keys :req [::id ::handler ::context?]
                      :opt [::guard ::description]))

(s/def ::clock ::clock/clock)
(s/def ::opts (s/keys :opt [::clock ::context?]))
