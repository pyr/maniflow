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

(defn initialize-context
  "Create a context which will be threaded between executions"
  [{::keys [clock] :or {clock clock/wall-clock}}]
  (let [timestamp (clock/epoch clock)]
    {::created-at timestamp
     ::updated-at timestamp
     ::index      0
     ::steps      []
     ::clock      clock}))

(defn augment-context
  "Called after a step to record timing"
  [{::keys [clock] :as context} id last-update]
  (let [timestamp (clock/epoch clock)
        timing    (- timestamp last-update)]
    (-> context
        (update ::index inc)
        (assoc  ::updated-at timestamp)
        (update ::steps conj {::id id ::timing timing}))))

(defn rethrow-exception-fn
  "When chains fail, augment the thrown exception with the current
   context state"
  [context]
  (fn [e]
    (let [data  (ex-data e)
          extra {:type :error/fault ::cause e ::context context}]
      (throw (ex-info (.getMessage e) (merge extra (ex-data e)))))))

(defn var-step
  "Yield a valid step given a var"
  [v]
  {:pre (var? v)}
  (let [{:keys [ns name]} (meta v)]
    {::id      (keyword (str ns) (str name))
     ::handler (var-get v)}))

(defn prepare-step
  "Coerce input to a step"
  [input]
  (cond
    (vector? input) {::id (first input) ::handler (second input)}
    (var? input)    (var-step input)
    (map? input)    input
    (fn? input)     {::id (keyword (gensym "step")) ::handler input}
    :else           (throw (ex-info "invalid step definition"
                                    {:error/type :error/invalid}))))

(defn wrap-step-fn
  "Wrap each lifecycle step. This yields a function which will run a
   step's handler. If a guard is present, the execution of the function
   will be dependent on the guard's success, if a finalizer is present,
   it will be ran.

   Any exception caught will be rethrown with the current context
   state attached."
  [{::keys [clock]}]
  (bound-fn [{::keys [id handler guard finalizer] :as step}]
    {:pre [(s/valid? ::step step)]}
    (bound-fn [[context res]]
      (let [timestamp (clock/epoch clock)
            context   (augment-context context id timestamp)
            rethrow   (rethrow-exception-fn context)]
        (try
          (if (or (nil? guard) (guard context res))
            (-> (handler res)
                (d/chain (juxt (constantly context) identity))
                (cond-> (some? finalizer) (d/chain finalizer))
                (d/catch rethrow))
            res)
          (catch Exception e
            (rethrow e)))))))

(defn run
  "
  Run a series of potentially asynchronous steps in sequence
  on an initial value, threading each step's result to the next step.

  Steps are maps or the following keys:

      [:manifold.lifecycle/id keyword
       :manifold.lifecycle/handler
       :manifold.lifecycle/guard
       :manifold.lifecycle/finalizer]

  - `id` is the unique ID for a step
  - `handler` is the function of the previous result
  - `guard` is an optional predicate of the current context and previous
    result, preventing execution of the step when yielding false
  - `finalizer` is an option predicate of the context and result as a tuple
    which must yield - a potentially transformed - context and result tuple

  Accepts an options map of the following keys:

      [:manifold.lifecycle/clock
       :manifold.lifecycle/result-fn]

  - `clock` is an optional implementation of `spootnik.clock/Clock`,
    defaulting to the system's wall clock (`spootnik.clock/wall-clock`)
  - `result-fn` is an optional function of the context and result tuple
    to yield the final value out of the chain, defaulting to `second`
    to output the result
   "
  ([init steps]
   (run init steps {}))
  ([init steps opts]
   (let [context (initialize-context opts)
         result  (or (get opts ::result-fn) second)
         prepare (comp (wrap-step-fn context) prepare-step)]
     (apply d/chain [context init]
            (conj (mapv prepare steps) result)))))

;; Specs
;; =====

(s/def ::id keyword?)
(s/def ::handler fn?)
(s/def ::guard fn?)
(s/def ::finalizer fn?)
(s/def ::description string?)
(s/def ::step (s/keys :req [::id ::handler]
                      :opt [::guard ::finalizer ::description]))


(comment
  ;; some infered names, some explicit
  @(run 0 [#'inc #'inc [:multiply-by-two (partial * 2)]])

  ;; unnamed steps
  @(run {} [#(assoc % :a 0) #(d/future (update % :a inc))]
     {::result-fn identity})  (defn error-out
    [e]
    (let [{:keys [type] :as data} (ex-data e)]
      (when (or (nil? type) (= :error/fault type))
        (log-and-report-error (:manifold.lifecycle/context data)))
      (format-error e data)))

  (defn report-timings
    [[context result]]
    (d/future
      (doseq [{:manifold.lifecycle/keys [name timing]}
              (get context :manifold.lifecycle/steps)]
        (send-timing-data name timing)))
    (report-timing name timing))


  (fn [request]
    (-> (run request [#'find-route
                      #'deserialie
                      #'coerce-input
                      #'normalize
                      #'validate-in
                      #'dispatch-to-handler
                      #'coerce-output
                      #'serialize]
          {:manifold.lifecycle/result-fn report-timing})
        (d/catch error-out)
        (d/finally report-timing))))
