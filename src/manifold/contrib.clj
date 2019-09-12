(ns manifold.contrib
  (:require [manifold.deferred :refer [chain'
                                       chain'-
                                       chain
                                       success-deferred
                                       error-deferred
                                       deferred
                                       ->deferred
                                       deferred?
                                       success-error-unrealized
                                       on-realized
                                       success!
                                       error!]]))

(defn zipseq'
  "Like `zipseq`, but only unwraps Manifold deferreds."
  [vals]
  (let [cnt (count vals)
        ^objects ary (object-array cnt)
        counter (java.util.concurrent.atomic.AtomicInteger. cnt)]
    (clojure.core/loop [d nil, idx 0, s vals]
      (if (empty? s)
        ;; no further results, decrement the counter one last time
        ;; and return the result if everything else has been realized
        (if (zero? (.get counter))
          (success-deferred (or (seq ary) (list)))
          d)
        (let [x (first s)
              rst (rest s)
              idx' (unchecked-inc idx)]
          (if (deferred? x)
            (success-error-unrealized x
              val (do
                    (aset ary idx val)
                    (.decrementAndGet counter)
                    (recur d idx' rst))
              err (error-deferred err)
              (let [d (or d (deferred))]
                (on-realized (chain' x)
                  (fn [val]
                    (aset ary idx val)
                    (when (zero? (.decrementAndGet counter))
                      (success! d (seq ary))))
                  (fn [err]
                    (error! d err)))
                (recur d idx' rst)))
            ;; not deferred - set, decrement, and recur
            (do
              (aset ary idx x)
              (.decrementAndGet counter)
              (recur d idx' rst))))))))

(defn zipseq
  "Takes a collection of values, some of which may be deferrable, and returns a deferred that will yield a list
   of realized values.
        @(zipseq [1 2 3]) => [1 2 3]
        @(zipseq [(future 1) 2 3]) => [1 2 3]
  "
  [vals]
  (zipseq' (map #(or (->deferred % nil) %) vals)))

(defn- error-match-fn
  "Predicate which checks for a match on an error based on
   the `error-pattern` argument to `catch'`, upon successful
   matching, yields the appropriate value to the error handler.

   patterns can be:

   - nil, matching everything and yielding the Exception
   - a keyword, which will be compared against the `:type` key
     in the error's `ex-data`. The `ex-data` will be chained
     to the handler in that case
   - a function which must observe the same behavior
   - a class which will check for instances of that class"
  [pattern]
  (cond
    (nil? pattern)
    identity

    (keyword? pattern)
    (fn [err]
      (let [data (ex-data err)]
        (when (= (:type data) pattern)
          data)))

    (fn? pattern)
    pattern

    :else
    (fn [err]
      (when (instance? pattern err)
        err))))

(defn catch'
  "Like `catch`, but does not coerce deferrable values."
  ([x error-handler]
     (catch' x nil error-handler))
  ([x error-pattern error-handler]
     (let [x (chain' x)
           catch? (error-match-fn error-pattern)]
       (if-not (deferred? x)

         ;; not a deferred value, skip over it
         x

         (success-error-unrealized x
           val x

           err (try
                 (if-let [caught (catch? err)]
                   (chain' (error-handler caught))
                   (error-deferred err))
                 (catch Throwable e
                   (error-deferred e)))

           (let [d' (deferred)]

             (on-realized x
               #(success! d' %)
               #(try
                  (if-let [caught (catch? %)]
                    (chain'- d' (error-handler caught))
                    (chain'- d' (error-deferred %)))
                  (catch Throwable e
                    (error! d' e))))

             d'))))))

(defn catch
  "An equivalent of the catch clause, which takes an `error-handler` function that will be invoked
   with the exception, and whose return value will be yielded as a successful outcome.  If an
   `error-class` is specified, only exceptions of that type will be caught.  If not, all exceptions
   will be caught.

       (-> d
         (chain f g h)
         (catch :invalid    #(str \"caught Exception info with data: \" %)
         (catch IOException #(str \"oh no, IO: \" (.getMessage %)))
         (catch             #(str \"something unexpected: \" (.getMessage %))))

    "
  ([x error-handler]
    (catch x nil error-handler))
  ([x error-class error-handler]
     (if-let [d (->deferred x nil)]
       (-> d
         chain
         (catch' error-class error-handler)
         chain)
       x)))
