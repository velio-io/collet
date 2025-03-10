(ns collet.core-test
  (:require
   [clojure.test :refer :all]
   [collet.core :as collet]
   [collet.core :as sut]
   [collet.test-fixtures :as tf]
   [collet.utils :as utils]
   [malli.core :as m]
   [tech.v3.dataset :as ds])
  (:import
   [collet.core Pipeline]
   [java.io File]
   [java.time Duration LocalDate LocalDateTime ZoneOffset]))


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
                            :state  {:some-action {:state1 "state-value"}}}
                           (utils/eval-ctx))]
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
                              :state  {:some-action {:state1 "state-value"}}}
                             (utils/eval-ctx))]
        (is (= compiled-params
               {:p1 "value1"
                :p2 "value2"
                :p3 [1 2 "state-value"]}))))))


(deftest compile-action-test
  (testing "Compiles an action spec into a function"
    (let [action-spec {:type :clj/select-keys
                       :name :test}
          action      (sut/compile-action (utils/eval-ctx) action-spec)]
      (is (fn? action))))

  (testing "Action type prefixed with 'clj' is resolved as a Clojure core function"
    (let [action-spec {:type   :clj/select-keys
                       :name   :keys-selector
                       :params [{:a 1 :b 2 :c 3 :d 4 :e 5}
                                [:a :b :e]]}
          action      (sut/compile-action (utils/eval-ctx) action-spec)
          actual      (-> (action {:config {} :state {}})
                          (get-in [:state :keys-selector]))]
      (is (= actual {:a 1 :b 2 :e 5}))))

  (testing "Custom action are allowed"
    (let [action-spec {:type   :custom
                       :name   :format-string
                       :params ["My name is %s"]
                       :fn     (fn [format-str]
                                 (format format-str "John"))}
          action      (sut/compile-action (utils/eval-ctx) action-spec)
          actual      (-> (action {:config {} :state {}})
                          (get-in [:state :format-string]))]
      (is (= actual "My name is John")))

    (let [action-spec {:type   :custom
                       :name   :format-string
                       :params {:template "My name is %s"
                                :value    "John"}
                       :fn     (fn [{:keys [template value]}]
                                 (format template value))}
          action      (sut/compile-action (utils/eval-ctx) action-spec)
          actual      (-> (action {:config {} :state {}})
                          (get-in [:state :format-string]))]
      (is (= actual "My name is John"))))

  (testing "Unknown action type raises an error"
    (let [action-spec {:type :random
                       :name :random-test}]
      (is (thrown? Exception (sut/compile-action (utils/eval-ctx) action-spec)))))

  (testing "Action with selectors"
    (let [action-spec {:type      :custom
                       :name      :params-test
                       :selectors '{param1 [:config :param1]
                                    param2 [:config :param2]
                                    state1 [:state :some-action :state1]}
                       :params    '[param1 {:p2 param2} [1 2 state1]]
                       :fn        (fn [p1 {:keys [p2]} [_ _ s1]]
                                    (format "param1: %s, param2: %s, state1: %s" p1 p2 s1))}
          action      (sut/compile-action (utils/eval-ctx) action-spec)
          actual      (-> (action {:config {:param1 "value1" :param2 "value2"}
                                   :state  {:some-action {:state1 "state-value"}}})
                          (get-in [:state :params-test]))]
      (is (= actual "param1: value1, param2: value2, state1: state-value"))))

  (testing "Predefined actions"
    (let [counter-action (sut/compile-action (utils/eval-ctx) {:type :counter :name :counter-test})]
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
          action      (sut/compile-action (utils/eval-ctx) action-spec)
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
          {:keys [task-fn]} (sut/compile-task (utils/eval-ctx) task-spec)
          actual    (task-fn {:config {} :state {}})]
      (is (= actual "Params extracted a: 1, b: 2, e: 5"))))

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
          {:keys [task-fn]} (sut/compile-task (utils/eval-ctx) task-spec)
          actual    (task-fn {:config {} :state {}})]
      (is (= actual "Params extracted a: 1, b: 2, e: 5"))))

  (testing "Task with iterator"
    (let [counter   (atom 0)
          task-spec {:name     :test-task
                     :actions  [{:type :custom
                                 :name :count-action
                                 :fn   (fn []
                                         {:count (swap! counter inc)})}]
                     :iterator {:next true}
                     :return   [:state :count-action :count]}
          {:keys [task-fn]} (sut/compile-task (utils/eval-ctx) task-spec)
          result    (task-fn {:config {} :state {}})]
      ;; result becomes a sequence of what :data iterator property returns
      (is (= (take 10 result) (range 1 11)))
      (is (= (first result) 11))))

  (testing "Task with external actions"
    (let [task-spec {:name    :test-task
                     :actions [{:name   :count-action
                                :type   :test.collet/counter-action.edn
                                :params [0]}]
                     ;; name of the action is overridden by the external action
                     :return  [:state :my-external-action]}
          {:keys [task-fn]} (sut/compile-task (utils/eval-ctx) task-spec)
          result    (task-fn {:config {} :state {}})]
      (is (= 1 result)))))


(deftest handle-task-errors-test
  (testing "Tasks failed on error"
    (let [task-spec {:name    :throwing-task
                     :actions [{:type :custom
                                :name :bad-action
                                :fn   (fn []
                                        (throw (ex-info "Bad action" {})))}]}
          {:keys [task-fn]} (sut/compile-task (utils/eval-ctx) task-spec)]
      (is (thrown? Exception (task-fn {:config {} :state {}})))))

  (testing "Tasks retried on failure"
    (let [runs-count (atom 0)
          task-spec  {:name    :throwing-task
                      :retry   {:max-retries 3}
                      :actions [{:type :custom
                                 :name :bad-action
                                 :fn   (fn []
                                         (swap! runs-count inc)
                                         (throw (ex-info "Bad action" {})))}]}
          {:keys [task-fn]} (sut/compile-task (utils/eval-ctx) task-spec)]
      (is (thrown? Exception (task-fn {:config {} :state {}})))
      ;; function will be called 4 times: 1 initial run + 3 retries
      (is (= @runs-count 4)))))


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
                                                             (+ v1 2))}]}]}
          pipeline      (sut/compile-pipeline pipeline-spec)]
      (is (m/validate sut/pipeline? pipeline))
      (is (= :pending (sut/pipe-status pipeline)))

      @(pipeline {})

      (is (= :done (sut/pipe-status pipeline)))
      (is (= 3 (:task2 pipeline)))
      pipeline))

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
                                                :selectors '{val1 [:inputs :task1]}
                                                :params    '[val1]
                                                :fn        (fn [v1]
                                                             (Thread/sleep 2000)
                                                             (println "Task 2")
                                                             (+ v1 2))}]}]}]
      (let [pipeline-1 (sut/compile-pipeline pipeline-spec)]
        @(sut/start pipeline-1 {})
        (is (= :done (sut/pipe-status pipeline-1)))
        (is (= 3 (:task2 pipeline-1))))

      (let [pipeline-2 (sut/compile-pipeline pipeline-spec)]
        (sut/start pipeline-2 {})
        (Thread/sleep 2100)
        (sut/stop pipeline-2)
        (is (= :stopped (sut/pipe-status pipeline-2)))
        (is (= 1 (:task1 pipeline-2)))
        (is (= nil (:task2 pipeline-2)))

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
        (is (= 1 (:task1 pipeline-3)))
        (is (= nil (:task2 pipeline-3)))
        (sut/resume pipeline-3 {})
        (Thread/sleep 2100)
        (is (= 3 (:task2 pipeline-3))))))

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
      (is (thrown? Exception (sut/compile-pipeline pipeline-spec))))

    (let [pipeline-spec {:name  :sample-pipeline
                         :tasks [{:name     :sample-task
                                  :actions  [{:name :sample-action
                                              :type :clj/inc}]
                                  ;; you can't specify both :iterator and :parallel
                                  :iterator {:next true}
                                  :parallel {:range {:end 10}}}]}]
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
                            :iterator   {:next [:< [:state :count-action] 10]}}]}
        pipeline  (sut/compile-pipeline pipe-spec)]
    @(pipeline {})
    (is (= (list 1 2 3 4 5)
           (take 5 (:counting-task pipeline))))
    (is (= 10 (count (:counting-task pipeline))))))


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
                                                          (let [value (+ 1 v)]
                                                            (swap! results assoc :task11 value)
                                                            value))}]}
                                 {:name    :task21
                                  :inputs  [:task2]
                                  :actions [{:type      :custom
                                             :name      :action21
                                             :selectors '{t2 [:inputs :task2]}
                                             :params    '[t2]
                                             :fn        (fn [v]
                                                          (let [value (+ 2 v)]
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
                                                          (let [value (+ t2 t3)]
                                                            (swap! results assoc :task22 value)
                                                            value))}]}
                                 {:name    :task31
                                  :inputs  [:task3]
                                  :actions [{:type      :custom
                                             :name      :action31
                                             :selectors '{t3 [:inputs :task3]}
                                             :params    '[t3]
                                             :fn        (fn [v]
                                                          (let [value (+ 4 v)]
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
                                                          (let [value (+ t22 t31)]
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


(deftest skipping-tasks-test
  (testing "If :skip-on-error is set on the task, all dependent tasks are skipped"
    (let [pipeline-spec {:name  :test-pipeline
                         :tasks [;; root tasks
                                 {:name    :task1
                                  :actions [{:type :custom
                                             :name :action1
                                             :fn   (constantly 1)}]}
                                 {:name          :task2
                                  :skip-on-error true
                                  :actions       [{:type :custom
                                                   :name :action2
                                                   :fn   (fn []
                                                           (throw (ex-info "Bad action" {})))}]}
                                 {:name    :task3
                                  :actions [{:type :custom
                                             :name :action3
                                             :fn   (constantly 3)}]}
                                 ;; dependent tasks
                                 {:name       :task11
                                  :keep-state true
                                  :inputs     [:task1]
                                  :actions    [{:type      :custom
                                                :name      :action11
                                                :selectors '{t1 [:inputs :task1]}
                                                :params    '[t1]
                                                :fn        (fn [v]
                                                             (let [value (+ 1 v)]
                                                               value))}]}
                                 {:name    :task21
                                  :inputs  [:task2]
                                  :actions [{:type      :custom
                                             :name      :action21
                                             :selectors '{t2 [:inputs :task2]}
                                             :params    '[t2]
                                             :fn        (fn [v]
                                                          (let [value (+ 2 v)]
                                                            value))}]}
                                 {:name    :task22
                                  :inputs  [:task2 :task3]
                                  :actions [{:type      :custom
                                             :name      :action22
                                             :selectors '{t2 [:inputs :task2]
                                                          t3 [:inputs :task3]}
                                             :params    '[t2 t3]
                                             :fn        (fn [t2 t3]
                                                          (let [value (+ t2 t3)]
                                                            value))}]}
                                 {:name    :task31
                                  :inputs  [:task3]
                                  :actions [{:type      :custom
                                             :name      :action31
                                             :selectors '{t3 [:inputs :task3]}
                                             :params    '[t3]
                                             :fn        (fn [v]
                                                          (let [value (+ 4 v)]
                                                            value))}]}
                                 {:name    :task4
                                  :inputs  [:task22 :task31]
                                  :actions [{:type      :custom
                                             :name      :action4
                                             :selectors '{t22 [:inputs :task22]
                                                          t31 [:inputs :task31]}
                                             :params    '[t22 t31]
                                             :fn        (fn [t22 t31]
                                                          (let [value (+ t22 t31)]
                                                            value))}]}]}
          pipeline      (sut/compile-pipeline pipeline-spec)]
      @(pipeline {})
      (let [{:keys [task1 task2 task3
                    task11 task21 task22 task31
                    task4]}
            pipeline]
        (is (= task1 1))
        (is (= task3 3))
        (is (= task11 2))
        (is (= task31 7))

        (is (= task2 nil))
        (is (= (-> @(.-tasks pipeline) :task2 :status)
               :failed))
        (is (= task21 nil))
        (is (= (-> @(.-tasks pipeline) :task21 :status)
               :skipped))
        (is (= task22 nil))
        (is (= (-> @(.-tasks pipeline) :task22 :status)
               :skipped))
        (is (= task4 nil))
        (is (= (-> @(.-tasks pipeline) :task4 :status)
               :skipped))))))


(deftest max-parallel-tasks-test
  (testing "Number of parallel tasks is limited by :max-parallel-tasks"
    (let [pipeline-spec {:name            :test-pipeline
                         :max-parallelism 2
                         :tasks           [{:name    :task1
                                            :actions [{:type :custom
                                                       :name :action1
                                                       :fn   (fn [] (Thread/sleep 2000))}]}
                                           {:name    :task2
                                            :actions [{:type :custom
                                                       :name :action2
                                                       :fn   (fn [] (Thread/sleep 2000))}]}
                                           {:name    :task3
                                            :actions [{:type :custom
                                                       :name :action3
                                                       :fn   (fn [] (Thread/sleep 2000))}]}
                                           {:name    :task4
                                            :actions [{:type :custom
                                                       :name :action4
                                                       :fn   (fn [] (Thread/sleep 2000))}]}
                                           {:name    :task5
                                            :actions [{:type :custom
                                                       :name :action5
                                                       :fn   (fn [] (Thread/sleep 2000))}]}
                                           {:name    :task6
                                            :actions [{:type :custom
                                                       :name :action6
                                                       :fn   (fn [] (Thread/sleep 2000))}]}
                                           {:name    :task7
                                            :actions [{:type :custom
                                                       :name :action7
                                                       :fn   (fn [] (Thread/sleep 2000))}]}
                                           {:name    :task8
                                            :actions [{:type :custom
                                                       :name :action8
                                                       :fn   (fn [] (Thread/sleep 2000))}]}
                                           {:name    :task9
                                            :actions [{:type :custom
                                                       :name :action9
                                                       :fn   (fn [] (Thread/sleep 2000))}]}
                                           {:name    :task10
                                            :actions [{:type :custom
                                                       :name :action10
                                                       :fn   (fn [] (Thread/sleep 2000))}]}]}
          pipeline      (sut/compile-pipeline pipeline-spec)]
      (pipeline {})

      (Thread/sleep 1000)
      (is (= 2 @(.-running-count ^Pipeline pipeline)))
      (is (zero? (->> @(.-tasks pipeline)
                      (map (fn [[_ {:keys [status]}]] status))
                      (filter #(= :completed %))
                      (count))))
      (is (= 2 (->> @(.-tasks pipeline)
                    (map (fn [[_ {:keys [status]}]] status))
                    (filter #(= :running %))
                    (count))))

      (Thread/sleep 2000)
      (is (= 2 @(.-running-count ^Pipeline pipeline)))
      (is (= 2 (->> @(.-tasks pipeline)
                    (map (fn [[_ {:keys [status]}]] status))
                    (filter #(= :completed %))
                    (count))))
      (is (= 2 (->> @(.-tasks pipeline)
                    (map (fn [[_ {:keys [status]}]] status))
                    (filter #(= :running %))
                    (count))))

      (Thread/sleep 8000)
      (is (zero? @(.-running-count ^Pipeline pipeline)))
      (is (= 10 (->> @(.-tasks pipeline)
                     (map (fn [[_ {:keys [status]}]] status))
                     (filter #(= :completed %))
                     (count))))
      (is (zero? (->> @(.-tasks pipeline)
                      (map (fn [[_ {:keys [status]}]] status))
                      (filter #(= :running %))
                      (count)))))))


(deftest task-parallel-test
  (let [completed-tasks (atom [])
        pipe-spec       {:name  :parallel-test
                         :tasks [{:name       :sample-task
                                  :keep-state true
                                  :actions    [{:type      :custom
                                                :name      :sample-action
                                                :selectors {'item [:$parallel/item]}
                                                :params    ['item]
                                                :fn        (fn [j]
                                                             (Thread/sleep 1000)
                                                             (swap! completed-tasks conj j)
                                                             j)}]
                                  :parallel   {:range   {:end 10}
                                               :threads 5}}]}
        pipeline        (sut/compile-pipeline pipe-spec)]
    (pipeline {})
    (Thread/sleep 500)
    (is (= :running (collet/pipe-status pipeline)))
    (is (zero? (count @completed-tasks)))
    (Thread/sleep 1000)
    (is (= :running (collet/pipe-status pipeline)))
    (is (= 5 (count @completed-tasks)))
    (Thread/sleep 1000)
    (is (= :done (collet/pipe-status pipeline)))
    (is (= 10 (count @completed-tasks)))
    (is (= (range 10) (:sample-task pipeline))))

  (let [pipe-spec {:name  :parallel-test
                   :tasks [{:name    :prep-task
                            :actions [{:type   :clj/identity
                                       :name   :sample-items
                                       :params [[{:a 10 :b "abc"}
                                                 {:a 20 :b "def"}
                                                 {:a 30 :b "ghi"}]]}]}
                           {:name       :sample-task
                            :keep-state true
                            :inputs     [:prep-task]
                            :actions    [{:type      :custom
                                          :name      :sample-action
                                          :selectors {'item [:$parallel/item]}
                                          :params    ['item]
                                          :fn        (fn [{:keys [a]}]
                                                       (Thread/sleep 1000)
                                                       (inc a))}]
                            :parallel   {:items   [:inputs :prep-task]
                                         :threads 5}}]}
        pipeline  (sut/compile-pipeline pipe-spec)]
    (pipeline {})
    (Thread/sleep 2000)
    (is (= :done (collet/pipe-status pipeline)))
    (is (= 3 (count (:sample-task pipeline))))
    (is (= [11 21 31] (:sample-task pipeline)))
    (collet/pipe-error pipeline)))


(deftest pipeline-tasks-results-in-arrow
  (testing "task result stored in arrow file"
    (let [pipe-spec {:name  :test-arrow-pipeline
                     :tasks [{:name       :users-collection
                              :keep-state true
                              :actions    [{:type   :clj/identity
                                            :name   :users
                                            :params [[{:id 1 :name "John"}
                                                      {:id 2 :name "Jane"}
                                                      {:id 3 :name "Doe"}]]}]}]}
          pipeline  (sut/compile-pipeline pipe-spec)]
      @(pipeline {})
      (is (sut/arrow-task-result? (:users-collection pipeline)))
      (is (instance? File (.-file (:users-collection pipeline))))
      (is (= [{:id 1 :name "John"}
              {:id 2 :name "Jane"}
              {:id 3 :name "Doe"}]
             (-> (.-file (:users-collection pipeline))
                 (collet.arrow/read-dataset (.-columns (:users-collection pipeline)))
                 first
                 (ds/rows)
                 (as-> $
                       (map #(update % :name str) $)))))))

  (testing "disable arrow storage for the pipeline"
    (let [pipe-spec {:name      :test-arrow-pipeline
                     :use-arrow false
                     :tasks     [{:name       :users-collection
                                  :keep-state true
                                  :actions    [{:type   :clj/identity
                                                :name   :users
                                                :params [[{:id 1 :name "John"}
                                                          {:id 2 :name "Jane"}
                                                          {:id 3 :name "Doe"}]]}]}]}
          pipeline  (sut/compile-pipeline pipe-spec)]
      @(pipeline {})
      (is (not (sut/arrow-task-result? (:users-collection pipeline))))
      (is (= [{:id 1 :name "John"}
              {:id 2 :name "Jane"}
              {:id 3 :name "Doe"}]
             (:users-collection pipeline)))))

  (testing "complex data stored in arrow file and parsed correctly"
    (let [john-uuid (random-uuid)
          jane-uuid (random-uuid)
          pipe-spec {:name  :test-arrow-pipeline
                     :tasks [{:name       :users-collection
                              :keep-state true
                              :actions    [{:type   :clj/identity
                                            :name   :users
                                            :params [[{:id         1
                                                       :name       "John"
                                                       :male       true
                                                       :height     38.12
                                                       :created_at (LocalDate/of 2020 1 1)
                                                       :lifetime   (Duration/ofDays 9125)
                                                       :uuid       john-uuid
                                                       :dob        (LocalDateTime/of 2020 1 1 0 0)}
                                                      {:id         2
                                                       :name       "Jane"
                                                       :male       false
                                                       :height     45.12
                                                       :created_at (LocalDate/of 2019 1 1)
                                                       :lifetime   (Duration/ofDays 7125)
                                                       :uuid       jane-uuid
                                                       :dob        (LocalDateTime/of 2019 1 1 0 0)}]]}]}]}
          pipeline  (sut/compile-pipeline pipe-spec)]
      @(pipeline {})
      (let [{:keys [id name male height created_at lifetime uuid dob]}
            (-> (.-file (:users-collection pipeline))
                (collet.arrow/read-dataset (.-columns (:users-collection pipeline)))
                first
                (ds/rows)
                first)]
        (is (= 1 id))
        (is (= "John" (str name)))
        (is (true? male))
        (is (= 38.12 height))
        (is (= (LocalDate/of 2020 1 1) created_at))
        (is (= 788400000000000 lifetime))
        (is (= john-uuid (parse-uuid (str uuid))))
        (is (= (LocalDateTime/of 2020 1 1 0 0)
               (LocalDateTime/ofInstant dob ZoneOffset/UTC)))))))


(deftest external-deps-test
  (testing "Adding and using external dependencies"
    (let [pipeline-spec {:name  :parsing-pipeline
                         :deps  {:coordinates '[[org.clojure/data.xml "0.0.8"]]
                                 :requires    '[[clojure.data.xml :as xml]]
                                 :imports     '[java.io.StringReader]}
                         :tasks [{:name       :xml-doc
                                  :keep-state true
                                  :actions    [{:name   :parse-xml-string
                                                :type   :custom
                                                :params ["<root><child>data</child></root>"]
                                                :fn     '(fn [xml-str]
                                                           (xml/parse (java.io.StringReader. xml-str)))}]}]}
          pipeline      (sut/compile-pipeline pipeline-spec)]
      @(pipeline {})
      (is (= :root (:tag (:xml-doc pipeline))))
      (is (= :child (:tag (first (:content (:xml-doc pipeline))))))
      (is (= ["data"] (:content (first (:content (:xml-doc pipeline))))))))

  (testing "Catch custom function error"
    (let [pipeline-spec {:name  :parsing-pipeline
                         :deps  {:coordinates '[[org.clojure/data.xml "0.0.8"]]
                                 :requires    '[[clojure.data.xml :as xml]]}
                         :tasks [{:name       :xml-doc
                                  :keep-state true
                                  :actions    [{:name   :parse-xml-string
                                                :type   :custom
                                                :params ["<root><child>data</child></root>"]
                                                :fn     '(fn [xml-str]
                                                           (xml/parse xml-str))}]}]}
          pipeline      (sut/compile-pipeline pipeline-spec)]
      @(pipeline {})
      (is (instance? Exception (:exception (sut/pipe-error pipeline)))))))


(deftest execute-task-test
  (testing "execute-task respects the state format option"
    (let [context {:state {:gh-repos [{:name "repo1"}
                                      {:name "repo2"}
                                      {:name "repo3"}
                                      {:name "repo4"}
                                      {:name "repo5"}]
                           :gh-prs   {"repo1" [{:title "PR1" :state "open"}
                                               {:title "PR2" :state "closed"}]
                                      "repo2" [{:title "PR3" :state "open"}
                                               {:title "PR4" :state "closed"}]
                                      "repo3" [{:title "PR5" :state "open"}
                                               {:title "PR6" :state "closed"}]
                                      "repo4" [{:title "PR7" :state "open"}
                                               {:title "PR8" :state "closed"}]
                                      "repo5" [{:title "PR9" :state "open"}
                                               {:title "PR10" :state "closed"}]}}}]
      (let [task   {:name         :gh-prs
                    :inputs       [:gh-repos :gh-prs]
                    :parallel     {:items   [:inputs :gh-repos]
                                   :threads 2}
                    :actions      [{:name      :fetch-gh-prs
                                    :type      :custom
                                    :selectors {'repo-name [:$parallel/item :name]
                                                'all-prs   [:state :gh-prs]}
                                    :params    ['all-prs 'repo-name]
                                    :fn        (fn [prs repo-name]
                                                 (get prs repo-name))}]
                    :state-format :flatten}
            result (sut/execute-task task {} context)]
        (is (= 10 (count result)))
        (is (= ["PR1" "PR2" "PR3" "PR4" "PR5" "PR6" "PR7" "PR8" "PR9" "PR10"]
               (map :title result))))

      (testing "with iterator only first result is calculated"
        (let [task   {:name         :gh-prs
                      :inputs       [:gh-repos :gh-prs]
                      :actions      [{:name      :repo
                                      :type      :mapper
                                      :selectors {'repos [:inputs :gh-repos]}
                                      :params    {:sequence 'repos}}
                                     {:name      :fetch-gh-prs
                                      :type      :custom
                                      :selectors {'repo-name [:$mapper/item :name]
                                                  'all-prs   [:state :gh-prs]}
                                      :params    ['all-prs 'repo-name]
                                      :fn        (fn [prs repo-name]
                                                   (get prs repo-name))}]
                      :iterator     {:next [:true? [:$mapper/has-next-item]]}
                      :state-format :flatten}
              result (sut/execute-task task {} context)]
          (is (= 2 (count result)))
          (is (= ["PR1" "PR2"]
                 (map :title result))))))))

