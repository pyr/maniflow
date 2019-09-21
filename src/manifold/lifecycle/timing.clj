(ns manifold.lifecycle.timing
  "Lifecycle wrapper for timing steps")

(defn initializer

  [clock]
  (fn [init]
    (let [timestamp (clock)]
      (-> {:timing {:created-at timestamp
                    :updated-at timestamp
                    :index      0
                    :output     []}}
          (merge init)))))

(defn augmenter
  [clock]
  (fn [context {:keys [id] :as step}]
    (let [timestamp (clock)
          timing    (- timestamp (get-in context [:timing :updated-at]))]
      (-> context
          (update-in [:timing :index] inc)
          (assoc-in  [:timing :updated-at] timestamp)
          (update-in [:timing :output] conj {:id id :timing timing})))))

(def wall-clock
  "Default clock for timing"
  #(System/currentTimeMillis))

(defn make
  [clock]
  {:initialize (initializer clock)
   :augment    (augmenter clock)})

(def milliseconds
  "A timing helper at millisecond resolution"
  (make wall-clock))
