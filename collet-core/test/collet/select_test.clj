(ns collet.select-test
  (:require
   [clojure.test :refer :all]
   [collet.select :as sut]
   [collet.test-fixtures :as tf]))


(use-fixtures :once (tf/instrument! 'collet.select))


(deftest selection-dsl-test
  (testing "select nested data"
    (let [actual (sut/select [:a :b :c]
                             {:a {:b {:c 1 :d 2}}})]
      (is (= 1 actual)))

    (let [actual (sut/select ["a" "b" "c"]
                             {"a" {"b" {"c" 1 "d" 2}}})]
      (is (= 1 actual)))

    (let [actual (sut/select [:a :b 1]
                             {:a {:b [1 2 3]}})]
      (is (= 2 actual)))

    (let [actual (sut/select [:a :b 1]
                             {:a {:b '(1 2 3)}})]
      (is (= 2 actual)))

    (let [actual (sut/select [:a :b :c :e]
                             {:a {:b {:c 1 :d 2}}})]
      (is (nil? actual)))

    (let [actual (sut/select [:a :b :c :e] nil)]
      (is (nil? actual)))

    (let [actual (sut/select [:a :b] {:a {:b {:c 1 :d 2}}})]
      (is (= {:c 1 :d 2} actual)))

    (let [actual (sut/select [] {:a {:b {:c 1 :d 2}}})]
      (is (nil? actual))))

  (testing "Select multiple values (akka join)"
    (let [actual (sut/select [:a :b {:c-val :c :d-val :d}]
                             {:a {:b {:c 1 :d 2}}})]
      (is (= {:c-val 1 :d-val 2} actual)))

    (let [actual (sut/select [:a {:c-val [:b :c]
                                  :e-val [:d :e]}]
                             {:a {:b {:c 1 :f 3}
                                  :d {:e 2 :f 4}}})]
      (is (= {:c-val 1 :e-val 2} actual))))

  (testing "Select values from collections"
    (let [actual (sut/select [:a [:$/cat :b]]
                             {:a 23})]
      (is (nil? actual)))

    (let [actual (sut/select [:a [:$/cat :b]]
                             {:a {:b 1}})]
      (is (nil? actual)))

    (let [actual (sut/select [:a [:$/cat :b]]
                             {:a [{:b 1}
                                  {:b 2}
                                  {:b 3}]})]
      (is (= [1 2 3] actual)))

    (let [actual (sut/select [:a [:$/cat :b :c]]
                             {:a [{:b {:c 1}}
                                  {:b {:c 2}}
                                  {:b {:c 3}}]})]
      (is (= [1 2 3] actual)))

    (testing "Select from collection with join"
      (let [actual (sut/select [:a [:$/cat :b {:c-val :c
                                               :d-val :d
                                               :e-val :e}]]
                               {:a [{:b {:c 1 :e 4}}
                                    {:b {:c 2}}
                                    {:b {:c 3 :d 5}}]})]
        (is (= [{:c-val 1 :d-val nil :e-val 4}
                {:c-val 2 :d-val nil :e-val nil}
                {:c-val 3 :d-val 5 :e-val nil}]
               actual)))))

  (testing "Select values with conditions"
    (let [actual (sut/select [:a [:$/cond [:< :b 3]]]
                             {:a {:b 5}})]
      (is (nil? actual)))

    (let [actual (sut/select [:a [:$/cond [:< :b 3]]]
                             {:a {:b 2 :c 5 :d [1 2 3]}})]
      (is (= {:b 2 :c 5 :d [1 2 3]}
             actual)))

    (let [actual (sut/select [:a [:$/cond [:< :b 3]] :b]
                             {:a {:b 2 :c 5 :d [1 2 3]}})]
      (is (= 2 actual)))

    (let [actual (sut/select [:a [:$/cat :b [:$/cond [:< :c 3]] :c]]
                             {:a [{:b {:c 1}}
                                  {:b {:c 2}}
                                  {:b {:c 3}}
                                  {:b {:c 4}}]})]
      (is (= [1 2] actual))))

  (testing "Select values with operations"
    (let [actual (sut/select [:a [:$/op :first]]
                             {:a [1 2 3]})]
      (is (= 1 actual)))

    (let [actual (sut/select [:a [:$/op :first] :b]
                             {:a [{:b 1} {:b 2}]})]
      (is (= 1 actual)))

    (let [actual (sut/select [:a [:$/op :no-op] :b]
                             {:a [{:b 1} {:b 2}]})]
      (is (nil? actual)))))