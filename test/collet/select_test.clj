(ns collet.select-test
  (:require
   [clojure.test :refer :all]
   [collet.select :as sut]
   [collet.test-fixtures :as tf])
  (:import
   [clojure.lang LazySeq]))


(use-fixtures :once (tf/instrument! 'collet.select))


(deftest selection-dsl-test
  (testing "Selection vector extracts nested data
            similarly to Clojure's get-in function but returns a named results"
    (let [actual (sut/select [:a :b :c]
                             {:a {:b {:c 1 :d 2}}})]
      (is (= actual 1)))

    (let [actual (sut/select [:a :b :c :e]
                             {:a {:b {:c 1 :d 2}}})]
      (is (nil? actual)))

    (let [actual (sut/select [:a :b :c :e] nil)]
      (is (nil? actual)))

    (let [actual (sut/select [:a :b] {:a {:b {:c 1 :d 2}}})]
      (is (= actual {:c 1 :d 2})))

    (let [actual (sut/select [] {:a {:b {:c 1 :d 2}}})]
      (is (nil? actual))))

  (testing "Select multiple values (akka join)"
    (let [actual (sut/select [:a :b {:c-val :c :d-val :d}]
                             {:a {:b {:c 1 :d 2}}})]
      (is (= actual {:c-val 1 :d-val 2})))

    (let [actual (sut/select [:a {:c-val [:b :c]
                                  :e-val [:d :e]}]
                             {:a {:b {:c 1 :f 3}
                                  :d {:e 2 :f 4}}})]
      (is (= actual {:c-val 1 :e-val 2}))))

  (testing "Select values from collections"
    (let [actual (sut/select [:a [:cat :b]]
                             {:a 23})]
      (is (nil? actual)))

    (let [actual (sut/select [:a [:cat :b]]
                             {:a {:b 1}})]
      (is (nil? actual)))

    (let [actual (sut/select [:a [:cat :b]]
                             {:a [{:b 1}
                                  {:b 2}
                                  {:b 3}]})]
      (is (instance? LazySeq actual))
      (is (= actual '(1 2 3))))

    (let [actual (sut/select [:a [:cat :b :c]]
                             {:a [{:b {:c 1}}
                                  {:b {:c 2}}
                                  {:b {:c 3}}]})]
      (is (instance? LazySeq actual))
      (is (= actual '(1 2 3))))

    (testing "Select from collection with join"
      (let [actual (sut/select [:a [:cat :b {:c-val :c
                                             :d-val :d
                                             :e-val :e}]]
                               {:a [{:b {:c 1 :e 4}}
                                    {:b {:c 2}}
                                    {:b {:c 3 :d 5}}]})]
        (is (= actual '({:c-val 1 :d-val nil :e-val 4}
                        {:c-val 2 :d-val nil :e-val nil}
                        {:c-val 3 :d-val 5 :e-val nil}))))))

  (testing "Select values with conditions"
    (let [actual (sut/select [:a [:cond [:< :b 3]]]
                             {:a {:b 5}})]
      (is (nil? actual)))

    (let [actual (sut/select [:a [:cond [:< :b 3]]]
                             {:a {:b 2 :c 5 :d [1 2 3]}})]
      (is (= actual {:b 2 :c 5 :d [1 2 3]})))

    (let [actual (sut/select [:a [:cond [:< :b 3]] :b]
                             {:a {:b 2 :c 5 :d [1 2 3]}})]
      (is (= actual 2)))

    (let [actual (sut/select [:a [:cat :b [:cond [:< :c 3]] :c]]
                             {:a [{:b {:c 1}}
                                  {:b {:c 2}}
                                  {:b {:c 3}}
                                  {:b {:c 4}}]})]
      (is (= actual '(1 2)))))

  (testing "Select values with operations"
    (let [actual (sut/select [:a [:op :first]]
                             {:a [1 2 3]})]
      (is (= actual 1)))

    (let [actual (sut/select [:a [:op :first] :b]
                             {:a [{:b 1} {:b 2}]})]
      (is (= actual 1)))

    (let [actual (sut/select [:a [:op :no-op] :b]
                             {:a [{:b 1} {:b 2}]})]
      (is (nil? actual)))))