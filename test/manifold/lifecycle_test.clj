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

  (is (= {:x 1} @(run {:x 0} [(step :inc inc :lens :x)])))

  (is (= {:x 0} @(run {:x 0} [(step :inc inc :lens :x :guard (constantly false))])))

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

(deftest out-value-test

  (is (= 2 @(run {:x 0}
              {:out [:x]}
              [(step :inc inc :lens :x)
               (step :inc inc :lens :x)]))))


(deftest build-stages-test

  (is (=
       (build-stages {:stop-on :stop! :augment 'augment}
                     [{:id :format :enter 'deserialize :leave 'serialize :error :eformat}
                      {:id :normalize :enter 'normalize-in :leave 'normalize-out}
                      {:id :validate :enter 'validate :error :evalidate}
                      {:id :handler :enter 'runcmd :error :ehandler}])
       '[{:id :format,
          :error (:eformat),
          :stage :enter,
          :handler deserialize,
          :augment augment,
          :stop-on :stop!}
         {:id :normalize,
          :stage :enter,
          :handler normalize-in,
          :error (:eformat),
          :augment augment,
          :stop-on :stop!}
         {:id :validate,
          :error (:evalidate :eformat),
          :stage :enter,
          :handler validate,
          :augment augment,
          :stop-on :stop!}
         {:id :handler,
          :error (:ehandler :evalidate :eformat),
          :stage :enter,
          :handler runcmd,
          :augment augment,
          :stop-on :stop!}
         {:id :normalize,
          :stage :leave,
          :handler normalize-out,
          :error (:eformat),
          :augment augment,
          :stop-on :stop!}
         {:id :format,
          :error (:eformat),
          :stage :leave,
          :handler serialize,
          :augment augment,
          :stop-on :stop!}]))
  (is (=
       (build-stages {:stop-on :stop! :augment 'augment}
                     [{:id :request-id :enter 'add-request}
                      {:id :route :enter 'route}
                      {:id :format :enter 'deserialize :leave 'serialize}
                      {:id :normalize :enter 'normalize-in :leave 'normalize-out}
                      {:id :validate :enter 'validate}
                      {:id :handler :enter 'runcmd}])
       '[{:id :request-id,
          :stage :enter,
          :handler add-request,
          :error []
          :stop-on :stop!,
          :augment augment}
         {:id :route,
          :stage :enter,
          :handler route,
          :error []
          :stop-on :stop!,
          :augment augment}
         {:id :format,
          :stage :enter,
          :handler deserialize,
          :error []
          :stop-on :stop!,
          :augment augment}
         {:id :normalize,
          :stage :enter,
          :handler normalize-in,
          :error []
          :stop-on :stop!,
          :augment augment}
         {:id :validate,
          :stage :enter,
          :handler validate,
          :stop-on :stop!,
          :error []
          :augment augment}
         {:id :handler,
          :stage :enter,
          :handler runcmd,
          :error []
          :stop-on :stop!,
          :augment augment}
         {:id :normalize,
          :stage :leave,
          :error []
          :handler normalize-out,
          :stop-on :stop!,
          :augment augment}
         {:id :format,
          :stage :leave,
          :handler serialize,
          :error []
          :stop-on :stop!,
          :augment augment}])))


(defn fail!
  [& _]
  (throw (ex-info "blah" {})))

(deftest stage-error-handling

  (testing "basic-error-handling"
    (let [error-handler (constantly 1000)]
      (is (= [{:id :a :stage :enter :handler fail! :error [error-handler] :stop-on :stop! :augment nil}
              {:id :a :stage :leave :handler inc :error [error-handler] :stop-on :stop! :augment nil} ]
             (build-stages {:stop-on :stop!} [(step :a [fail! inc] :error error-handler)]))))

    (is (= @(run 0 [(step :a [fail! inc] :error (constantly 1000))]) 1001))
    (is (= @(run 0 [(step :a [inc fail!] :error (constantly 1000))]) 1000))

    )
  )
