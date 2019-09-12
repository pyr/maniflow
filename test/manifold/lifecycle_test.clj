(ns manifold.lifecycle-test
  (:require [clojure.test       :refer :all]
            [manifold.lifecycle :as lc :refer :all]
            [manifold.deferred  :as d]))

(deftest prepare-steps-output
  (is (= [{::lc/id            :a
           ::lc/handler       :a
           ::lc/show-context? false}
          {::lc/id            :b
           ::lc/handler       :b
           ::lc/show-context? false}
          {::lc/id            ::lc/result
           ::lc/handler       ::lc/result
           ::lc/show-context? true}]
         (prepare-steps {} [:a :b]))))

(deftest simple-lifecycles
  (is (= 3 @(run 0 [inc inc inc])))

  (is (= 4 @(run 0 [#'inc #'inc (partial * 2)])))

  (is (= 0 @(run {:a 0} [:a])))

  (is (= {:a 1}
         @(run {} [#(assoc % :a 0)
                   #(d/future (update % :a inc))]))))
