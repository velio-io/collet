(ns collet.actions.switch-test
  (:require
   [clojure.test :refer :all]
   [collet.test-fixtures :as tf]
   [collet.actions.switch :as sut]
   [collet.core :as collet]
   [collet.utils :as utils]))


(use-fixtures :once (tf/instrument! 'collet.actions.switch))


(deftest switch-functionality-test
  (testing "expanding switch conditions"
    (is (= [:= [:state :b] 1]
           (sut/expand-selectors
            {:state {:a 1 :b 1}}
            {'a [:state :a]}
            [:= [:state :b] 'a])))

    (is (= [:= [:state :b] 2]
           (sut/expand-selectors
            {:state {:a 1}}
            {'a [:state :a]}
            [:= [:state :b] 2]))))

  (testing "executing actions"
    (let [result (atom nil)]
      (sut/switch-action
       {:selectors {'a [:state :a]}
        :case      [{:condition [:= [:state :a] 2]
                     :actions   [(fn [ctx] (reset! result :first-condition) ctx)]}
                    {:condition [:= [:state :a] 1]
                     :actions   [(fn [ctx] (reset! result :second-condition) ctx)]}]}
       {:state {:a 1}})

      (is (= :second-condition @result)))))


(deftest switch-action-test
  (testing "nested actions from switch block taken into account"
    (is (= [['my-ns-1] ['my-ns-2]]
           (collet/get-actions-deps
            [{:name    :task-1
              :actions [{:name :action-1
                         :type :my-ns-1/action-1}
                        {:name :action-2
                         :type :switch
                         :case [{:condition []
                                 :actions   [{:name :action-3
                                              :type :my-ns-2/action-3}]}]}
                        {:name :action-1
                         :type :action-4}]}]))))

  (testing "expand on task works with nested actions"
    (is (= {'a2 [:state :action-2 :current]}
           (-> (collet/expand-on-actions
                {:name    :task-1
                 :actions [{:name :action-1
                            :type :switch
                            :case [{:condition []
                                    :actions   [{:name   :action-2
                                                 :type   :mapper
                                                 :params {:sequence [1 2 3]}}
                                                {:name      :action-3
                                                 :type      :qwerty
                                                 :selectors {'a2 [:$mapper/item]}
                                                 :params    ['a2]}]}]}]})
               (get-in [:actions 0 :case 0 :actions 1 :selectors])))))

  (testing "switch action compiles and executes successfully"
    (let [action-spec {:name      :action-1
                       :type      :switch
                       :selectors {'counter-1 [:state :counter-1]}
                       :case      [{:condition [:or [:nil? [:state :counter-1]]
                                                [:< [:state :counter-1] 1]]
                                    :actions   [{:name :counter-1
                                                 :type :counter}]}
                                   {:condition [:and [:= [:state :counter-1] 'counter-1]
                                                [:or [:nil? [:state :counter-2]]
                                                 [:< [:state :counter-2] 5]]]
                                    :actions   [{:name      :counter-2
                                                 :type      :counter
                                                 :selectors {'counter-1 [:state :counter-1]}
                                                 :params    {:start 'counter-1 :step 2}}]}
                                   {:condition :default
                                    :actions   [{:name :counter-3
                                                 :type :counter}]}]}
          action      (collet/compile-action (utils/eval-ctx) action-spec)
          [cycle-1 cycle-2 cycle-3 cycle-4 cycle-5 cycle-6]
          (rest (iterate action {:config {} :state {}}))]
      (is (= 0 (get-in cycle-1 [:state :counter-1])))
      (is (= 1 (get-in cycle-2 [:state :counter-1])))
      (is (= 1 (get-in cycle-3 [:state :counter-2])))
      (is (= 3 (get-in cycle-4 [:state :counter-2])))
      (is (= 5 (get-in cycle-5 [:state :counter-2])))
      (is (= 0 (get-in cycle-6 [:state :counter-3]))))))


(deftest switch-action-pipeline-test
  (let [pipeline-spec {:name  :switch-pipe
                       :tasks [{:name       :task-1
                                :keep-state true
                                :setup      [{:name   :value
                                              :type   :clj/identity
                                              :params [0]}]
                                :actions    [{:name      :value
                                              :type      :clj/inc
                                              :selectors {'value [:state :value]}
                                              :params    ['value]}
                                             {:name :switch-action
                                              :type :switch
                                              :case [{:condition [:< [:state :value] 5]
                                                      :actions   [{:name      :value
                                                                   :type      :clj/inc
                                                                   :selectors {'value [:state :value]}
                                                                   :params    ['value]}]}
                                                     {:condition [:< [:state :value] 10]
                                                      :actions   [{:name      :value
                                                                   :type      :clj/inc
                                                                   :selectors {'value [:state :value]}
                                                                   :params    ['value]}]}]}]
                                :iterator   {:next [:< [:state :value] 15]}
                                :return     [:state :value]}]}
        pipeline      (collet/compile-pipeline pipeline-spec)]
    @(pipeline {})
    (is (= [2 4 6 8 10 11 12 13 14 15]
           (:task-1 pipeline)))))