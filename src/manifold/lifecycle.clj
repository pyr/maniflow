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
  (:require [clojure.spec.alpha :as s]
            [manifold.deferred  :as d]))

(defn step*
  "Convenience function to build a step map.
   Requires and ID and handler, and can be fed
   additional options"
  [id handler {:keys [in out lens guard discard?]}]
  (let [as-vector #(cond-> % (keyword? %) vector)]
    (cond-> {:id id :handler handler}
      (some? in)       (assoc :in (as-vector in))
      (some? out)      (assoc :out (as-vector out))
      (some? lens)     (assoc :lens (as-vector lens))
      (some? guard)    (assoc :guard guard)
      (some? discard?) (assoc :discard? discard?))))

(defn step
  "Convenience function to build a step map.
   Requires and ID and handler, and can be fed
   additional options"
  ([id handler & {:as params}]
   (step* id handler params))
  ([handler]
   {:id (keyword (gensym "handler")) :handler handler}))

(defn ^:no-doc rethrow-exception-fn
  "When chains fail, augment the thrown exception with the current
   context state"
  [context step]
  (fn thrower [e]
    (throw (ex-info (.getMessage e)
                    {:type    (or (:type (ex-data e)) :error/fault)
                     :context context
                     :step    step}
                    e))))

(defn ^:no-doc set-out-fn
  "When applicable, yield a function of a result which
   sets the output in the expected position, as per the
   step definition"
  [{:keys [lens out discard?]} context]
  (cond
    discard?     (constantly context)
    (some? lens) (partial assoc-in context lens)
    (some? out)  (partial assoc-in context out)))

(defn ^:no-doc extract-input
  "Fetches the expected part of a threaded context"
  [{:keys [lens in]} context]
  (cond
    (some? lens) (get-in context lens)
    (some? in)   (get-in context in)
    :else        context))

(defn ^:no-doc wrap-step
  "Wrap each lifecycle step. This yields a function which will run a
   step's handler. If a guard is present, the execution of the function
   will be dependent on the guard's success, if a finalizer is present,
   it will be ran.

   Any exception caught will be rethrown with the current context
   state attached."
  [augment {:keys [id handler guard in out lens discard?] :as step}]
  (fn [context]
    (let [context (cond-> context (some? augment) (augment step))
          input   (extract-input step context)
          set-out (set-out-fn step context)
          rethrow (rethrow-exception-fn context step)]
      (try
        (if (or (nil? guard) (guard context))
          (cond-> (handler input)
            (some? set-out) (d/chain set-out)
            true            (d/catch rethrow))
          context)
        (catch Exception e (rethrow e))))))

(defn- validate-args!
  [steps opts]
  (when-not (s/valid? ::args [steps opts])
    (let [msg (s/explain-str ::args [steps opts])]
      (throw (ex-info msg {:type :error/incorrect :message msg})))))

(defn run
  "
  Run a series of potentially asynchronous steps in sequence
  on an initial value (`init`0, threading each step's result to
  the next step as input.

  Steps are maps or the following keys:

      [:id :handler :in :out :discard? :guard]

  - `id` is the unique ID for a step
  - `handler` is the function of the previous result
  - `in` when present determines which path in the context will
     be fed to the handler. The handler is considered a function
     of the contex
  - `out` when present determines which path in the context to
     associate the result to, otherwise replaces the full context
  - `discard?` when present, runs the handler but pass the context
     untouched to the next step
  - `guard` is an optional predicate of the current context and previous
    preventing execution of the step when yielding false

  In the three-arity version, an extra options maps can
  be provided, with the following keys:

      [:augment :executor]

  - `augment` is a function called on the context for each step,
    expected to yield an updated context. This can useful to
    perform timing tasks
  - `executor` a manifold executor to execute deferreds on"
  ([init steps]
   (run init steps {}))
  ([init steps {:keys [initialize augment executor] :as opts}]
   (validate-args! steps opts)
   (let [init (cond-> init (some? initialize) initialize)]
     (cond-> (apply d/chain init (map (partial wrap-step augment) steps))
       (some? executor) (d/onto executor)))))

;; Specs
;; =====

(def ^:private callable? #(instance? clojure.lang.IFn %))

(s/def ::id         keyword?)
(s/def ::handler    callable?)
(s/def ::guard      callable?)
(s/def ::initialize callable?)
(s/def ::augment    callable?)
(s/def ::in         vector?)
(s/def ::out        vector?)
(s/def ::lens       vector?)
(s/def ::step       (s/keys :req-un [::id ::handler]
                            :opt-un [::in ::out ::lens ::guard ::discard?]))
(s/def ::steps      (s/coll-of ::step))
(s/def ::opts       (s/keys :opt-un [::initialize ::augment ::executor]))
(s/def ::args       (s/cat :steps ::steps :opts ::opts))
