(ns manifold.lifecycle-test
  (:require [clojure.test              :refer :all]
            [manifold.lifecycle        :refer :all]
            [manifold.lifecycle.timing :as timing]
            [manifold.deferred         :as d]))

(deftest minimal-lifecycle
  (is (= 1 @(run 0 [(step inc)]))))

(deftest simple-lifecycles
  (is (= 3 @(run 0 [(step inc) (step inc) (step inc)])))

  (is (= 4 @(run 0 [(step inc) (step inc) (step (partial * 2))])))

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
         @(run {:x 0} [(step :inc inc :lens :x :guard (constantly true))
                       (step :inc inc :lens :x :guard (constantly true))
                       (step :inc inc :lens :x :guard (constantly true))])))

  (is (= 3
         @(run {:x 0} [(step :inc inc :lens :x :guard (constantly true))
                       (step :inc inc :lens :x :guard (constantly true))
                       (step :inc inc :in :x :guard (constantly true))])))

  (is (= {:x 0}
         @(run {:x 0} [(step :inc inc :lens :x :guard (constantly true) :discard? true)
                       (step :inc inc :lens :x :guard (constantly true) :discard? true)
                       (step :inc inc :in :x :guard (constantly true) :discard? true)])))

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
             (step :content-type #(assoc-in % [:headers :content-type] "text/plain")
                   :in :response)])))

  (is (= {:x 0}
         @(run {:x 0} [(step :inc inc :lens :x :guard (constantly false))
                       (step :inc inc :lens :x :guard (constantly false))
                       (step :inc inc :lens :x :guard (constantly false))]))))

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
              [(step :inc inc :lens :x)
               (step :inc inc :lens :x)]
              (timing/make clock))
           {:x      2
            :timing {:created-at 0
                     :updated-at 2
                     :index      2
                     :output     [{:id :inc :timing 1}
                                  {:id :inc :timing 1}]}}))))
