(ns collet.core-test
  (:require
   [clojure.test :refer :all]
   [collet.test-fixtures :as tf]
   [collet.core :as sut]))


(use-fixtures :once (tf/instrument! 'collet.core))


(deftest compile-action-test
  (testing "Compiles an action spec into a function"
    (let [action-spec {:type :clj/select-keys
                       :name :test}
          action      (sut/compile-action action-spec)]
      (is (fn? action))))

  (testing "Action type prefixed with 'clj' is resolved as a Clojure core function"
    (let [action-spec {:type   :clj/select-keys
                       :name   :keys-selector
                       :params [{:a 1 :b 2 :c 3 :d 4 :e 5}
                                [:a :b :e]]}
          action      (sut/compile-action action-spec)
          actual      (-> (action {:config {} :state {}})
                          (get-in [:state :keys-selector]))]
      (is (= actual {:a 1 :b 2 :e 5}))))

  (testing "Custom action are allowed"
    (let [action-spec {:type   :custom
                       :name   :format-string
                       :params ["My name is %s"]
                       :fn     (fn [format-str]
                                 (format format-str "John"))}
          action      (sut/compile-action action-spec)
          actual      (-> (action {:config {} :state {}})
                          (get-in [:state :format-string]))]
      (is (= actual "My name is John"))))

  (testing "Unknown action type raises an error"
    (let [action-spec {:type :random
                       :name :random-test}]
      (is (thrown? Exception (sut/compile-action action-spec)))))

  (testing "Params compilation"
    (let [compiled-params (sut/compile-action-params
                           {:params    '[param1 {:p2 param2} [1 2 state1]]
                            :selectors '{param1 [:config :param1]
                                         param2 [:config :param2]
                                         state1 [:state :some-action :state1]}}
                           {:config {:param1 "value1"
                                     :param2 "value2"}
                            :state  {:some-action {:state1 "state-value"}}})]
      (is (= compiled-params
             ["value1" {:p2 "value2"} [1 2 "state-value"]])))

    (let [action-spec {:type      :custom
                       :name      :params-test
                       :selectors '{param1 [:config :param1]
                                    param2 [:config :param2]
                                    state1 [:state :some-action :state1]}
                       :params    '[param1 {:p2 param2} [1 2 state1]]
                       :fn        (fn [p1 {:keys [p2]} [_ _ s1]]
                                    (format "param1: %s, param2: %s, state1: %s" p1 p2 s1))}
          action      (sut/compile-action action-spec)
          actual      (-> (action {:config {:param1 "value1" :param2 "value2"}
                                   :state  {:some-action {:state1 "state-value"}}})
                          (get-in [:state :params-test]))]
      (is (= actual "param1: value1, param2: value2, state1: state-value"))))

  (testing "Predefined actions"
    (let [http-action  (sut/compile-action {:type :http :name :http-test})
          jdbc-action  (sut/compile-action {:type :jdbc :name :jdbc-test})
          odata-action (sut/compile-action {:type :odata :name :odata-test})]
      (is (fn? http-action))
      (is (fn? jdbc-action))
      (is (fn? odata-action)))))


(deftest compile-and-run-task
  (testing "Compiles and runs a task"
    (let [task-spec {:name    :test-task
                     :actions [{:type   :clj/select-keys
                                :name   :keys-selector
                                :params [{:a 1 :b 2 :c 3 :d 4 :e 5}
                                         [:a :b :e]]}
                               {:type      :custom
                                :name      :format-string
                                :selectors '{a [:state :keys-selector :a]
                                             b [:state :keys-selector :b]
                                             e [:state :keys-selector :e]}
                                :params    '[a b e]
                                :fn        (fn [a b e]
                                             (format "Params extracted a: %s, b: %s, e: %s"
                                                     a b e))}]}
          task      (sut/compile-task task-spec)
          result    (task {:config {} :state {}})
          actual    (-> result
                        first
                        (get-in [:state :format-string]))]
      (is (= actual "Params extracted a: 1, b: 2, e: 5"))
      (is (nil? (second result))
          "shouldn't continue executing tasks without iterator set")))

  (testing "Task with setup actions"
    (let [task-spec {:name    :test-task
                     :setup   [{:type   :clj/select-keys
                                :name   :keys-selector
                                :params [{:a 1 :b 2 :c 3 :d 4 :e 5}
                                         [:a :b :e]]}]
                     :actions [{:type      :custom
                                :name      :format-string
                                :selectors '{a [:state :keys-selector :a]
                                             b [:state :keys-selector :b]
                                             e [:state :keys-selector :e]}
                                :params    '[a b e]
                                :fn        (fn [a b e]
                                             (format "Params extracted a: %s, b: %s, e: %s"
                                                     a b e))}]}
          task      (sut/compile-task task-spec)
          result    (task {:config {} :state {}})
          actual    (-> result
                        first
                        (get-in [:state :format-string]))]
      (is (= actual "Params extracted a: 1, b: 2, e: 5"))))

  (testing "Task with iterator"
    (let [counter   (atom 0)
          task-spec {:name     :test-task
                     :actions  [{:type :custom
                                 :name :count-action
                                 :fn   (fn []
                                         {:count (swap! counter inc)})}]
                     :iterator {:data [:state :count-action :count]}}
          task      (sut/compile-task task-spec)
          result    (task {:config {} :state {}})]
      ;; result becomes a sequence of what :data iterator property returns
      (is (= (take 10 result) (range 1 11)))
      (is (= (first result) 11)))))
