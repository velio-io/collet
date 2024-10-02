(ns collet.actions.fold-test
  (:require
   [clojure.test :refer :all]
   [collet.actions.fold :as sut]))


(deftest basic-fold-test
  (testing "replace operation"
    (is (= {:a 3, :b 2}
           (sut/fold {:value {:a 1 :b 2}
                      :op    :replace
                      :in    [:a]
                      :with  3}
                     nil)))

    (is (= {:a 1, :b [2 3 5]}
           (sut/fold {:value {:a 1 :b [2 3 4]}
                      :op    :replace
                      :in    [:b 2]
                      :with  5}
                     nil)))

    (is (= {:a 1, :b [{:c 2} {:c 5} {:c 4}]}
           (sut/fold {:value {:a 1 :b [{:c 2} {:c 3} {:c 4}]}
                      :op    :replace
                      :in    [:b 1 :c]
                      :with  5}
                     nil))))

  (testing "merge operation"
    (is (= {:a 1, :b {:c 5, :d 3}}
           (sut/fold {:value {:a 1 :b {:c 2 :d 3}}
                      :op    :merge
                      :in    [:b]
                      :with  {:c 5}}
                     nil)))

    (is (= {:a 1, :b [{:c 2, :d 3} {:c 5, :d 4}]}
           (sut/fold {:value {:a 1 :b [{:c 2 :d 3} {:c 3 :d 4}]}
                      :op    :merge
                      :in    [:b 1]
                      :with  {:c 5}}
                     nil))))

  (testing "conj operation"
    (is (= {:a 1, :b [2 3 5]}
           (sut/fold {:value {:a 1 :b [2 3]}
                      :op    :conj
                      :in    [:b]
                      :with  5}
                     nil)))

    (is (= {:a 1, :b [{:c 2} {:c 3} {:c 4} {:c 5}]}
           (sut/fold {:value {:a 1 :b [{:c 2} {:c 3} {:c 4}]}
                      :op    :conj
                      :in    [:b]
                      :with  {:c 5}}
                     nil))))

  (testing "accumulating value"
    (let [first-iteration  (sut/fold {:value {:a 1 :b 2}
                                      :op    :replace
                                      :in    [:a]
                                      :with  2}
                                     nil)
          second-iteration (sut/fold {:value {:a 1 :b 2}
                                      :op    :replace
                                      :in    [:a]
                                      :with  3}
                                     first-iteration)]
      (is (= {:a 2, :b 2} first-iteration))
      (is (= {:a 3, :b 2} second-iteration)))))

