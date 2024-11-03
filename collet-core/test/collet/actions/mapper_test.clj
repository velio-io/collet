(ns collet.actions.mapper-test
  (:require
   [clojure.test :refer :all]
   [tech.v3.dataset :as ds]
   [collet.test-fixtures :as tf]
   [collet.actions.mapper :as sut]))


(use-fixtures :once (tf/instrument! 'collet.actions.mapper))


(deftest basic-mapper-test
  (testing "map over a simple sequence"
    (let [params {:sequence [1 2 3]}
          result (sut/map-sequence params nil)]
      (is (= {:current 1
              :idx     0
              :next    true
              :dataset [1 2 3]}
             result))
      (let [result (sut/map-sequence {:sequence [:whatever]} result)]
        (is (= {:current 2
                :idx     1
                :next    true
                :dataset [1 2 3]}
               result))
        (let [result (sut/map-sequence {:sequence [:whatever]} result)]
          (is (= {:current 3
                  :idx     2
                  :next    false
                  :dataset [1 2 3]}
                 result))))))

  (testing "map over a dataset"
    (let [params {:sequence [{:a 1 :b 2} {:a 3 :b 4} {:a 5 :b 6}]}
          result (sut/map-sequence params nil)]
      (is (= {:a 1 :b 2}
             (:current result)))
      (is (ds/dataset? (:dataset result))))))