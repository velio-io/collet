(ns collet.actions.fold-test
  (:require
   [clojure.test :refer :all]
   [collet.test-fixtures :as tf]
   [collet.actions.fold :as sut]))


(use-fixtures :once (tf/instrument! 'collet.actions.fold))


(deftest conjoin-test
  (is (= (sut/conjoin [1 2 3] nil 4)
         [1 2 3 4]))

  (is (= (sut/conjoin [1 2 3] [1] 4)
         [1 4 3]))

  (is (= (sut/conjoin {:a [1 2 3]} [:a 1] 4)
         {:a [1 4 3]}))

  (is (= (sut/conjoin {:a [{:b [1 2]}
                           {:b [3 4]}]}
                  [:a 1 :b]
                  5)
         {:a [{:b [1 2]} {:b [3 4 5]}]}))

  (is (= (sut/conjoin [] nil 123)
         [123]))

  (is (= (sut/conjoin [123] nil 321)
         [123 321]))

  (is (= (sut/conjoin {:a 123} nil {:b 321})
         {:a 123 :b 321}))

  (is (= (sut/conjoin {:a 123} [:b] {:c 321})
         {:a 123, :b {:c 321}}))

  (is (= (sut/conjoin {:a 123} [:b 10] 321)
         {:a 123, :b '(321)})))


(deftest basic-fold-test
  (testing "adding item into resulting collection"
    (is (= (sut/fold {:item 123} nil)
           [123]))
    (is (= (sut/fold {:item 321} [123])
           [123 321]))
    (is (= (sut/fold {:item 321} [123])
           [123 321])))

  (testing "merging data before adding"
    (is (= (sut/fold {:item {:a 123} :with {:b 321}} nil)
           [{:a 123 :b 321}]))

    (is (= (sut/fold {:item {:a 123} :in [:b] :with {:c 321}} nil)
           [{:a 123, :b {:c 321}}]))

    (is (= (sut/fold {:item {:a 123} :in [:b 10] :with 321} nil)
           [{:a 123, :b '(321)}])))

  (testing "accumulating value"
    (let [first-iteration  (sut/fold {:item {:a 123} :with {:b 321}} nil)
          second-iteration (sut/fold {:item {:a 456} :with {:b 654 :c 987}}
                                     first-iteration)]
      (is (= [{:a 123 :b 321}]
             first-iteration))
      (is (= [{:a 123 :b 321}
              {:a 456 :b 654 :c 987}]
             second-iteration)))))
