(ns collet.core-test
  (:require
   [clojure.test :refer :all]
   [collet.test-fixtures :as tf]
   [malli.core :as m]
   [collet.core :as sut]))


(use-fixtures :once (tf/instrument! 'collet.core))


(deftest action-params-test
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

    (testing "Params could be a map as well"
      (let [compiled-params (sut/compile-action-params
                             {:params    '{:p1 param1
                                           :p2 param2
                                           :p3 [1 2 state1]}
                              :selectors '{param1 [:config :param1]
                                           param2 [:config :param2]
                                           state1 [:state :some-action :state1]}}
                             {:config {:param1 "value1"
                                       :param2 "value2"}
                              :state  {:some-action {:state1 "state-value"}}})]
        (is (= compiled-params
               {:p1 "value1"
                :p2 "value2"
                :p3 [1 2 "state-value"]}))))))


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
      (is (= actual "My name is John")))

    (let [action-spec {:type   :custom
                       :name   :format-string
                       :params {:template "My name is %s"
                                :value    "John"}
                       :fn     (fn [{:keys [template value]}]
                                 (format template value))}
          action      (sut/compile-action action-spec)
          actual      (-> (action {:config {} :state {}})
                          (get-in [:state :format-string]))]
      (is (= actual "My name is John"))))

  (testing "Unknown action type raises an error"
    (let [action-spec {:type :random
                       :name :random-test}]
      (is (thrown? Exception (sut/compile-action action-spec)))))

  (testing "Action with selectors"
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
    (let [counter-action (sut/compile-action {:type :counter :name :counter-test})]
      (is (fn? counter-action)))))


(deftest conditional-action-execution
  (testing "when condition specified, action executed only in case of match"
    (let [action-spec {:type      :custom
                       :name      :condition-test
                       :when      [:> [:config :b] 0]
                       :selectors '{a [:config :a]
                                    b [:config :b]}
                       :params    '[a b]
                       :fn        (fn [a b]
                                    (/ a b))}
          action      (sut/compile-action action-spec)
          match       (-> (action {:config {:a 20 :b 5}
                                   :state  {}})
                          (get-in [:state :condition-test]))
          no-match    (action {:config {:a 20 :b 0}
                               :state  {:other :data}})]
      (is (= 4 match)
          "action executed")

      (is (= :data (get-in no-match [:state :other]))
          "other data is not affected")

      (is (nil? (get-in no-match [:state :condition-test]))
          "action not executed"))))


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
          actual    (first result)]
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
          actual    (first result)]
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


(deftest handle-task-errors-test
  (testing "Tasks failed on error"
    (let [task-spec {:name    :throwing-task
                     :actions [{:type :custom
                                :name :bad-action
                                :fn   (fn []
                                        (throw (ex-info "Bad action" {})))}]}
          task      (sut/compile-task task-spec)]
      (is (thrown? Exception (first (task {:config {} :state {}}))))))

  (testing "Tasks retried on failure"
    (let [runs-count (atom 0)
          task-spec  {:name    :throwing-task
                      :retry   {:max-retries 3}
                      :actions [{:type :custom
                                 :name :bad-action
                                 :fn   (fn []
                                         (swap! runs-count inc)
                                         (throw (ex-info "Bad action" {})))}]}
          task       (sut/compile-task task-spec)]
      (is (thrown? Exception (seq (task {:config {} :state {}}))))
      ;; function will be called 4 times: 1 initial run + 3 retries
      (is (= @runs-count 4))))

  (testing "Tasks continued to execute after failure"
    (let [runs-count (atom 0)
          task-spec  {:name          :throwing-task
                      :skip-on-error true
                      :actions       [{:type :custom
                                       :name :bad-action
                                       :fn   (fn []
                                               (swap! runs-count inc)
                                               (if (= @runs-count 3)
                                                 (throw (ex-info "Bad action" {}))
                                                 @runs-count))}]
                      :iterator      {:data [:state :bad-action]}}
          task       (sut/compile-task task-spec)]
      (is (= (->> (task {:config {} :state {}})
                  (take 5))
             ;; we will see a number 2 two times
             ;; because when exception is thrown the previous iteration values is used on next run
             (list 1 2 2 4 5))))))


(deftest pipeline-test
  (testing "Basic pipeline"
    (let [pipeline-spec {:name  :test-pipeline
                         :tasks [{:name    :task1
                                  :actions [{:type :custom
                                             :name :action1
                                             :fn   (constantly 1)}]}
                                 {:name       :task2
                                  :inputs     [:task1]
                                  :keep-state true
                                  :actions    [{:type      :custom
                                                :name      :action2
                                                :selectors '{val1 [:inputs :task1]}
                                                :params    '[val1]
                                                :fn        (fn [v1]
                                                             ;; task result is a sequable/reducible
                                                             (-> (last v1)
                                                                 (+ 2)))}]}]}
          pipeline      (sut/compile-pipeline pipeline-spec)]
      (is (m/validate sut/pipeline? pipeline))
      (is (= :pending (sut/pipe-status pipeline)))

      @(pipeline {})

      (is (= :done (sut/pipe-status pipeline)))
      (is (= '(3) (-> pipeline :task2)))))

  (testing "Pipeline lifecycle"
    (let [pipeline-spec {:name  :test-pipeline
                         :tasks [{:name    :task1
                                  :actions [{:type :custom
                                             :name :action1
                                             :fn   (fn []
                                                     (Thread/sleep 2000)
                                                     (println "Task 1")
                                                     1)}]}
                                 {:name       :task2
                                  :inputs     [:task1]
                                  :keep-state true
                                  :actions    [{:type      :custom
                                                :name      :action2
                                                :selectors '{val1 [:inputs :task1 [:$/op :first]]}
                                                :params    '[val1]
                                                :fn        (fn [v1]
                                                             (Thread/sleep 2000)
                                                             (println "Task 2")
                                                             (+ v1 2))}]}]}]
      (let [pipeline-1 (sut/compile-pipeline pipeline-spec)]
        @(sut/start pipeline-1 {})
        (is (= :done (sut/pipe-status pipeline-1)))
        (is (= '(3) (-> pipeline-1 :task2))))

      (let [pipeline-2 (sut/compile-pipeline pipeline-spec)]
        (sut/start pipeline-2 {})
        (Thread/sleep 2100)
        (sut/stop pipeline-2)
        (is (= :stopped (sut/pipe-status pipeline-2)))
        (is (= '(1) (-> pipeline-2 :task1)))
        (is (= nil (-> pipeline-2 :task2)))

        (testing "Stopped pipeline can't be started again"
          (sut/start pipeline-2 {})
          (is (= :stopped (sut/pipe-status pipeline-2)))
          (sut/resume pipeline-2 {})
          (is (= :stopped (sut/pipe-status pipeline-2)))
          (is (= nil (-> pipeline-2 :task2)))))

      (let [pipeline-3 (sut/compile-pipeline pipeline-spec)]
        (sut/start pipeline-3 {})
        (Thread/sleep 2100)
        (sut/pause pipeline-3)
        (is (= :paused (sut/pipe-status pipeline-3)))
        (is (= '(1) (-> pipeline-3 :task1)))
        (is (= nil (-> pipeline-3 :task2)))
        (sut/resume pipeline-3 {})
        (Thread/sleep 2100)
        (is (= '(3) (-> pipeline-3 :task2))))))

  (testing "Pipeline with throwing task"
    (let [pipeline-spec {:name  :test-pipeline
                         :tasks [{:name    :throwing-task
                                  :actions [{:type :custom
                                             :name :bad-action
                                             :fn   (fn []
                                                     (throw (ex-info "Bad action" {})))}]}]}
          pipeline      (sut/compile-pipeline pipeline-spec)]
      (try @(pipeline {})
           (catch Exception e))
      (is (re-find #"Pipeline error: Bad action" (:message (sut/pipe-error pipeline))))))

  (testing "Invalid pipeline spec error"
    (let [pipeline-spec {:name   "invalid type"
                         :taskas :missing-tasks-key}]
      (is (thrown? Exception (sut/compile-pipeline pipeline-spec))))))


(deftest pipeline-with-iterator-test
  (let [pipe-spec {:name  :test-pipeline
                   :tasks [{:name       :counting-task
                            :keep-state true
                            :actions    [{:type      :custom
                                          :name      :count-action
                                          :selectors '{count [:state :count-action]}
                                          :params    '[count]
                                          :fn        (fn [c]
                                                       (inc (or c 0)))}]
                            :iterator   {:data [:state :count-action]
                                         :next [:< [:state :count-action] 10]}}]}
        pipeline  (sut/compile-pipeline pipe-spec)]
    @(pipeline {})
    (is (= (list 1 2 3 4 5)
           (take 5 (:counting-task pipeline))))))


(deftest complex-pipeline-test
  (testing "Pipeline with multiple roots
            task1 -> task11
            task2 -> task21
                  -> task22
            task3 -> task22 -> task4
                  -> task31 -> task4"
    (let [results       (atom {})
          pipeline-spec {:name  :test-pipeline
                         :tasks [;; root tasks
                                 {:name    :task1
                                  :actions [{:type :custom
                                             :name :action1
                                             :fn   (constantly 1)}]}
                                 {:name    :task2
                                  :actions [{:type :custom
                                             :name :action2
                                             :fn   (constantly 2)}]}
                                 {:name    :task3
                                  :actions [{:type :custom
                                             :name :action3
                                             :fn   (constantly 3)}]}
                                 ;; dependent tasks
                                 {:name    :task11
                                  :inputs  [:task1]
                                  :actions [{:type      :custom
                                             :name      :action11
                                             :selectors '{t1 [:inputs :task1]}
                                             :params    '[t1]
                                             :fn        (fn [v]
                                                          (let [value (+ 1 (last v))]
                                                            (swap! results assoc :task11 value)
                                                            value))}]}
                                 {:name    :task21
                                  :inputs  [:task2]
                                  :actions [{:type      :custom
                                             :name      :action21
                                             :selectors '{t2 [:inputs :task2]}
                                             :params    '[t2]
                                             :fn        (fn [v]
                                                          (let [value (+ 2 (last v))]
                                                            (swap! results assoc :task21 value)
                                                            value))}]}
                                 {:name    :task22
                                  :inputs  [:task2 :task3]
                                  :actions [{:type      :custom
                                             :name      :action22
                                             :selectors '{t2 [:inputs :task2]
                                                          t3 [:inputs :task3]}
                                             :params    '[t2 t3]
                                             :fn        (fn [t2 t3]
                                                          (let [value (+ (last t2) (last t3))]
                                                            (swap! results assoc :task22 value)
                                                            value))}]}
                                 {:name    :task31
                                  :inputs  [:task3]
                                  :actions [{:type      :custom
                                             :name      :action31
                                             :selectors '{t3 [:inputs :task3]}
                                             :params    '[t3]
                                             :fn        (fn [v]
                                                          (let [value (+ 4 (last v))]
                                                            (swap! results assoc :task31 value)
                                                            value))}]}
                                 {:name    :task4
                                  :inputs  [:task22 :task31]
                                  :actions [{:type      :custom
                                             :name      :action4
                                             :selectors '{t22 [:inputs :task22]
                                                          t31 [:inputs :task31]}
                                             :params    '[t22 t31]
                                             :fn        (fn [t22 t31]
                                                          (let [value (+ (last t22) (last t31))]
                                                            (swap! results assoc :task4 value)
                                                            value))}]}]}
          pipeline      (sut/compile-pipeline pipeline-spec)]
      @(pipeline {})
      ;; results atom should contain the values produced of each task
      (is (= (-> @results :task11) 2))
      (is (= (-> @results :task21) 4))
      (is (= (-> @results :task22) 5))
      (is (= (-> @results :task31) 7))
      (is (= (-> @results :task4) 12)))))