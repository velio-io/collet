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
    (is (= (range 20 26)
           (map (comp :c :value) actual)))
    (is (= '(1 1 1 3 3 3)
           (map (comp :a :value) actual)))))


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


(deftest seq-iteration-test
  (let [state'  (sut/slice-sequence {:sequence [:a :b :c]} nil)
        state'' (sut/slice-sequence {:sequence [:a :b :c]} state')]
    (is (= :a (:current state')))
    (is (= 0 (:idx state')))
    (is (= :b (:next state')))

    (is (= :b (:current state'')))
    (is (= 1 (:idx state'')))
    (is (= :c (:next state''))))

  (let [state'  (sut/slice-sequence {:sequence [{:id 1} {:id 2} {:id 3}]} nil)
        state'' (sut/slice-sequence {:sequence [{:id 1} {:id 2} {:id 3}]} state')]
    (is (= {:id 1} (:current state')))
    (is (= 0 (:idx state')))
    (is (= {:id 2} (:next state')))

    (is (= {:id 2} (:current state'')))
    (is (= 1 (:idx state'')))
    (is (= {:id 3} (:next state'')))))
