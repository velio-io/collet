(ns collet.actions.mapper-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer :all]
   [collet.actions.mapper :as sut]
   [collet.arrow :as collet.arrow]
   [collet.core :as collet]
   [collet.stream :as stream]
   [collet.test-fixtures :as tf]
   [collet.utils :as utils]
   [tech.v3.dataset :as ds]))


(use-fixtures :once (tf/instrument! 'collet.actions.mapper))


(deftest basic-mapper-test
  (testing "map over a simple sequence"
    (let [params {:sequence [1 2 3]}
          result (sut/map-sequence params nil)]
      (is (= {:current 1
              :idx     0
              :next    true
              :dataset [1 2 3]}
             (select-keys result [:current :idx :next :dataset])))
      (let [result (sut/map-sequence {:sequence [:whatever]} result)]
        (is (= {:current 2
                :idx     1
                :next    true
                :dataset [1 2 3]}
               (select-keys result [:current :idx :next :dataset])))
        (let [result (sut/map-sequence {:sequence [:whatever]} result)]
          (is (= {:current 3
                  :idx     2
                  :next    false
                  :dataset [1 2 3]}
                 (select-keys result [:current :idx :next :dataset])))))))

  (testing "map over a dataset"
    (let [params {:sequence [{:a 1 :b 2} {:a 3 :b 4} {:a 5 :b 6}]}
          result (sut/map-sequence params nil)]
      (is (= {:a 1 :b 2}
             (:current result)))
      (is (ds/dataset? (:dataset result)))))

  (testing "map over a dataset sequence"
    (let [data-seq [[{:a 1 :b 2 :c "text"} {:a 3 :b 4 :c "text"} {:a 5 :b 6 :c "text"}]
                    [{:a 7 :b 8 :c "text"} {:a 9 :b 10 :c "text"} {:a 11 :b 12 :c "text"}]
                    [{:a 13 :b 14 :c "text"} {:a 15 :b 16 :c "text"} {:a 17 :b 18 :c "text"}
                     {:a 19 :b 20 :c "last item"}]]
          columns  (collet.arrow/get-columns (first data-seq))]
      (with-open [writer (collet.arrow/make-writer "tmp/mapper-arrow-test.arrow" columns)]
        (doseq [data data-seq]
          (collet.arrow/write writer data)))
      (let [params {:sequence (collet.arrow/read-dataset "tmp/mapper-arrow-test.arrow" columns)}
            result (sut/map-sequence params nil)]
        (is (nil? (:rows-count result)))
        (is (= {:a 1 :b 2 :c "text"} (:current result)))
        (is (:next result))

        (let [result (loop [result (sut/map-sequence {:sequence [:whatever]} result)]
                       (if (:next result)
                         (recur (sut/map-sequence {:sequence [:whatever]} result))
                         result))]
          (is (false? (:next result)))
          (is (= 9 (:idx result)))
          (is (= {:a 19 :b 20 :c "last item"} (:current result)))))
      (io/delete-file "tmp/mapper-arrow-test.arrow"))))


(deftest first-row-needs-no-total
  (let [opened      (atom [])
        source      (stream/view #(map (fn [n] (swap! opened conj n) (ds/->dataset (if (= n 1) [] [{:n n} {:n n}])))
                                       (list 0 1 2 3 4))
                                 {})
        first-state (sut/map-sequence {:sequence source} nil)]
    (is (= [0] @opened))
    (is (= {:n 0} (:current first-state)))
    (is (:next first-state))
    (is (= [0 0 2 2 3 3 4 4]
           (loop [s     first-state
                  items []]
             (let [items (conj items (get-in s [:current :n]))]
               (if (:next s) (recur (sut/map-sequence {:sequence []} s) items) items)))))
    (is (= [0 1 2 3 4] @opened))))


(deftest empty-mapper-stops-before-following-effects
  (let [seen (atom 0)
        run  (:task-fn (collet/compile-task
                        (utils/eval-ctx)
                        {:name     :empty
                         :iterator {:next [:true? [:$mapper/has-next-item]]}
                         :actions  [{:name :row :type :mapper :params {:sequence []}}
                                    {:name :effect :type :custom :fn (fn [] (swap! seen inc))}]}))]
    (is (empty? (seq (run {:state {} :config {}}))))
    (is (zero? @seen))))
