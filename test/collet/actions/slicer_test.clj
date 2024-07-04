(ns collet.actions.slicer-test
  (:require
   [clojure.test :refer :all]
   [collet.actions.slicer :as sut]
   [collet.test-fixtures :as tf])
  (:import
   [clojure.lang LazySeq]))


(use-fixtures :once (tf/instrument! 'collet.actions.slicer))


(deftest flatten-sequence-test
  (let [actual (sut/flatten-sequence
                {:flatten-by {:c [:b [:cat :c]]}
                 :keep-keys  {:a [:a]}}
                [{:a 1 :b [{:c 20}
                           {:c 21}
                           {:c 22}]}
                 {:a 3 :b [{:c 23}
                           {:c 24}
                           {:c 25}]}])]
    (is (instance? LazySeq actual))
    (is (= (map :c actual)
           (range 20 26)))
    (is (= (map :a actual)
           '(1 1 1 3 3 3)))))


(deftest group-sequence-test
  (let [actual (sut/group-sequence
                [:a]
                [{:a 1 :b 2}
                 {:a 1 :b 3}
                 {:a 2 :b 4}
                 {:a 2 :b 5}
                 {:a 2 :b 6}])]
    (is (instance? LazySeq actual))
    (is (= (map :a (first actual))
           '(1 1)))
    (is (= (map :a (second actual))
           '(2 2 2)))))


(deftest join-sequence-test
  (let [actual (sut/join-sequence
                {:sequence [{:c 1 :d 2}
                            {:c 2 :d 3}
                            {:c 3 :d 4}]
                 :on       {:source [:a]
                            :target [:c]}}
                [{:a 1 :b 2}
                 {:a 2 :b 3}
                 {:a 3 :b 4}])]
    (is (instance? LazySeq actual))
    (is (= (first actual)
           [{:a 1 :b 2} {:c 1 :d 2}]))
    (is (= actual
           '([{:a 1 :b 2} {:c 1 :d 2}]
             [{:a 2 :b 3} {:c 2 :d 3}]
             [{:a 3 :b 4} {:c 3 :d 4}])))))
