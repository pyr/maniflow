(ns manifold.lifecycle-test
  (:require [clojure.test              :refer :all]
            [manifold.lifecycle        :refer :all]
            [manifold.lifecycle.timing :as timing]
            [manifold.deferred         :as d]))

(deftest minimal-lifecycle
  (is (= 1 @(run 0 [(step inc)]))))

(deftest simple-lifecycles
  (is (= 3 @(run 0 [(step inc)
                    (step inc)
                    (step inc)])))

  (is (= 4 @(run 0 [(step inc)
                    (step inc)
                    (step (partial * 2))])))

  (is (= 0 @(run {:a 0} [(step :a)])))

  (is (= {:a 1}
         @(run {} [(step #(assoc % :a 0))
                   (step #(d/future (update % :a inc)))]))))

(deftest lifecycles-with-parmeters
  (is (= {:x 3}
         @(run {:x 0} [(step :inc inc :lens :x)
                       (step :inc inc :lens :x)
                       (step :inc inc :lens :x)])))

  (is (= {:x 3}
         @(run {:x 0} [(step :inc inc
                             :lens :x
                             :guard (constantly true))
                       (step :inc inc
                             :lens :x
                             :guard (constantly true))
                       (step :inc inc
                             :lens :x
                             :guard (constantly true))])))

  (is (= 3
         @(run {:x 0} [(step :inc inc
                             :lens :x
                             :guard (constantly true))
                       (step :inc inc
                             :lens :x
                             :guard (constantly true))
                       (step :inc inc
                             :in :x
                             :guard (constantly true))])))

  (is (= {:x 0}
         @(run {:x 0} [(step :inc inc
                             :lens :x
                             :guard (constantly true)
                                   :discard? true)
                       (step :inc inc
                             :lens :x
                             :guard (constantly true)
                             :discard? true)
                       (step :inc inc
                             :in :x
                             :guard (constantly true)
                             :discard? true)])))

  (is (= {:status  200
          :headers {:content-type "text/plain"}
          :body    "hello"}
         @(run {:request {:uri "/"}}
            [(step :handler (constantly {:body "hello"})
                   :in :request
                   :out :response)
             (step :status (constantly 200)
                   :in :response
                   :out [:response :status])
             (step :content-type #(assoc-in % [:headers :content-type]
                                            "text/plain")
                   :in :response)])))

  (is (= 1 @(run 0 {:stop-on odd?} [(step inc)
                                    (step inc)
                                    (step inc)
                                    (step inc)])))

  (is (= {:x 0}
         @(run {:x 0} [(step :inc inc
                             :lens :x
                             :guard (constantly false))
                       (step :inc inc
                             :lens :x
                             :guard (constantly false))
                       (step :inc inc
                             :lens :x
                             :guard (constantly false))]))))

(deftest errors-in-lifecycles
  (is (thrown? clojure.lang.ExceptionInfo
               @(run 0 [(step (partial / 0))])))

  (let [e (try @(run 0 [(step (partial / 0))])
               (catch Exception e e))]
    (is (= ArithmeticException (class (.getCause e))))))

(deftest timings-test
  (let [clock (let [state (atom 0)] (fn [] (first (swap-vals! state inc))))]
    (is (= @(run
              {:x 0}
              (timing/make clock)
              [(step :inc inc :lens :x)
               (step :inc inc :lens :x)])
           {:x      2
            :timing {:created-at 0
                     :updated-at 2
                     :index      2
                     :output     [{:id :inc :timing 1}
                                  {:id :inc :timing 1}]}}))))


(deftest build-stages-test
  (is (=
       (build-stages {:stages [:enter :leave] :stop-on :stop! :augment 'augment}
                     [{:id :request-id :enter 'add-request}
                      {:id :route :enter 'route}
                      {:id :format :enter 'deserialize :leave 'serialize}
                      {:id :normalize :enter 'normalize-in :leave 'normalize-out}
                      {:id :validate :enter 'validate}
                      {:id :handler :enter 'runcmd}])
       '[{:id :request-id,
          :stage :enter,
          :handler add-request,
          :stop-on :stop!,
          :augment augment}
         {:id :route,
          :stage :enter,
          :handler route,
          :stop-on :stop!,
          :augment augment}
         {:id :format,
          :stage :enter,
          :handler deserialize,
          :stop-on :stop!,
          :augment augment}
         {:id :normalize,
          :stage :enter,
          :handler normalize-in,
          :stop-on :stop!,
          :augment augment}
         {:id :validate,
          :stage :enter,
          :handler validate,
          :stop-on :stop!,
          :augment augment}
         {:id :handler,
          :stage :enter,
          :handler runcmd,
          :stop-on :stop!,
          :augment augment}
         {:id :normalize,
          :stage :leave,
          :handler normalize-out,
          :stop-on :stop!,
          :augment augment}
         {:id :format,
          :stage :leave,
          :handler serialize,
          :stop-on :stop!,
          :augment augment}])))
