(ns collet.actions.stats-test
  (:require
   [clojure.test :refer :all]
   [collet.actions.stats :as sut]))


(deftest test-calc-stats
  (let [data    [{:a 1 :b 2} {:a 3 :b 4} {:a 5 :b 6}]
        metrics {:sum-a       [:sum :a]
                 :min-b       [:min :b]
                 :max-a       [:max :a]
                 :mean-b      [:mean :b]
                 :median-a    [:median :a]
                 :quartiles-b [:quartiles :b]}
        result  (sut/calc-stats {:sequence data
                                 :metrics  metrics})]
    (is (= 9.0 (:sum-a result)))
    (is (= 2.0 (:min-b result)))
    (is (= 5.0 (:max-a result)))
    (is (= 4.0 (:mean-b result)))
    (is (= 3.0 (:median-a result)))
    (is (= [2.0 2.0 4.0 6.0 6.0]
           (:quartiles-b result)))))