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
            [manifold.deferred  :as d]
            [manifold.executor  :as pool]))

(defn- ^:no-doc as-vector
  [x]
  (cond-> x (not (sequential? x)) vector))

(defn step*
  "Convenience function to build a step map.
   Requires and ID and handler, and can be fed
   additional options"
  [id handlers {:keys [in out lens guard discard?]}]
  (let [[enter leave] (as-vector handlers)]
    (cond-> {:id id}
      (some? enter)    (assoc :enter enter)
      (some? leave)    (assoc :leave leave)
      (some? in)       (assoc :in (as-vector in))
      (some? out)      (assoc :out (as-vector out))
      (some? lens)     (assoc :lens (as-vector lens))
      (some? guard)    (assoc :guard guard)
      (some? discard?) (assoc :discard? discard?))))

(defn step
  "Convenience function to build a step map.
   Requires and ID and handler, and can be fed
   additional options"
  ;;  [id enter leave & {:as params}]
  ([enter]
   (step (keyword (gensym "handler")) [enter]))
  ([id handlers & {:as params}]
   (step* id handlers params)))

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

(defn ^:no-doc assoc-result-fn
  "When applicable, yield a function of a result which
   sets the output in the expected position, as per the
   step definition"
  [{:keys [lens out discard?]} context]
  (cond
    discard?     (constantly context)
    (some? lens) (partial assoc-in context lens)
    (some? out)  (partial assoc-in context out)
    :else        identity))

(defn ^:no-doc extract-input
  "Fetches the expected part of a threaded context"
  [{:keys [lens in]} context]
  (cond
    (some? lens) (get-in context lens)
    (some? in)   (get-in context in)
    :else        context))

(defn ^:no-doc run-step
  "Run a single step in a lifecycle. Takes care of honoring `augment`,
   `stop-on`, `guard`, and position (`in`, `out`, or `lens`) specifications."
  [{:keys [handler guard in out lens discard? augment stop-on] :as step} context]
  (let [context (cond-> context (some? augment) (augment step))
        rethrow (rethrow-exception-fn context step)]
    (try
      (if (or (nil? guard) (guard context))
        (-> (d/chain (handler (extract-input step context))
                     (assoc-result-fn step context)
                     (juxt identity stop-on))
            (d/catch rethrow))
        [context])
      (catch Exception e (rethrow e)))))

(defn- validate-args!
  "Throw early on invalid configuration"
  [opts steps]
  (when-not (s/valid? ::args [opts steps])
    (let [msg (s/explain-str ::args [opts steps])]
      (throw (ex-info msg {:type :error/incorrect :message msg})))))

(defn prepare-stage
  "Prepare a single stage, discard steps which do not have a handler
   for a specific stage."
  [stage opts steps]
  (let [clean-stages #(apply dissoc % (:stages opts [:enter :leave]))
        extra        {:augment (:augment opts)
                      :stop-on (or (:stop-on opts)
                                   (:terminate-when opts)
                                   (constantly false))}]
    (vec (for [step  steps
               :let  [handler (get step stage)]
               :when (some? handler)]
           (-> step
               (assoc :stage stage :handler handler)
               (dissoc stage)
               (clean-stages)
               (merge extra))))))

(defn ^:no-doc build-stages
  "Given a list of steps, prepare a flat vector of actions
   to take in sequence. If options do not specify a stage,
   assume `:enter` and `:leave`"
  [{:keys [stages] :as opts} steps]
  (loop [[stage & stages] (or stages [:enter :leave])
         steps            steps
         prepared         []]
    (if (nil? stage)
      (vec prepared)
      (recur stages
             (reverse steps)
             (concat prepared (prepare-stage stage opts steps))))))

(defprotocol ^:no-doc Restarter
  "A protocol to allow restarts"
  (restart-step [this step]  "Run an asynchronous operation")
  (restart      [this value] "Success callback"))

(defrecord DeferredRestarter [executor steps out result]
  Restarter
  (restart-step [this value]
    (let [[step & steps] steps]
      (d/on-realized (d/chain (d/future-with executor (run-step step value)))
                     (partial restart (assoc this :steps steps))
                     (partial d/error! result))))
  (restart [this [value stop?]]
    (let [step (first steps)
          exit (cond-> value (some? out) (get-in out))]
      (cond
        (nil? step) (d/success! result exit)
        stop?       (d/success! result exit)
        :else       (restart-step this value)))))

(defn ^:no-doc make-restarter
  "Build a restarter"
  [opts steps]
  (DeferredRestarter.
    (or (:executor opts) (pool/execute-pool))
    (build-stages opts steps)
    (:out opts)
    (d/deferred)))

(defn run
  "
  Run a series of potentially asynchronous steps in sequence
  on an initial value (`init`0, threading each step's result to
  the next step as input.

  Steps are maps or the following keys:

      [:id :enter :leave :in :out :discard? :guard]

  - `id` is the unique ID for a step
  - `enter` is the function of the previous result in the enter stage
  - `leave` is the function of the previous result in the leave stage
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

      [:augment :executor :initialize :stages :stop-on]

  - `augment` is a function called on the context for each step,
    expected to yield an updated context. This can useful to
    perform timing tasks
  - `executor` a manifold executor to execute deferreds on
  - `initialize` is a function called on the context before
    running the lifecycle.
  - `out` a path in the context to retrieve as the final
     value out of the lifecycle
  - `stages` a list of stages to run through, defaults to
    `:enter` and `:leave`. Every second stage is run in reverse
    order.
  - `stop-on` predicate which determines whether an early stop is
    mandated, for compatibility with interceptors `terminate-when`
    is synonymous."
  ([init steps]
   (run init {} steps))
  ([init opts steps]
   (validate-args! opts steps)
   (let [initialize (or (:initialize opts) identity)
         restarter  (make-restarter opts steps)]
     (restart-step restarter (initialize init))
     (:result restarter))))

;; Specs
;; =====

(s/def ::id         keyword?)
(s/def ::handler    (partial instance? clojure.lang.IFn))
(s/def ::enter      (partial instance? clojure.lang.IFn))
(s/def ::leave      (partial instance? clojure.lang.IFn))
(s/def ::guard      (partial instance? clojure.lang.IFn))
(s/def ::initialize (partial instance? clojure.lang.IFn))
(s/def ::augment    (partial instance? clojure.lang.IFn))
(s/def ::stop-on    (partial instance? clojure.lang.IFn))
(s/def ::stages     (s/coll-of keyword?))
(s/def ::in         vector?)
(s/def ::out        vector?)
(s/def ::lens       vector?)
(s/def ::step       (s/keys :req-un [::id]
                            :opt-un [::enter ::leave ::in ::out ::lens
                                     ::guard ::discard?]))
(s/def ::steps      (s/coll-of ::step))
(s/def ::opts       (s/keys :opt-un [::stages ::stop-on ::initialize
                                     ::augment ::executor ::out]))
(s/def ::args       (s/cat :opts ::opts :steps ::steps))
