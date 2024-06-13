(ns collet.core-test
  (:require
   [clojure.test :refer :all]
   [malli.instrument :as mi]
   [malli.dev.pretty :as mp]
   [collet.core :as sut]))


(mi/collect! {:ns 'collet.core})
(mi/instrument!)


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
          action      (sut/compile-action action-spec)]
      (is (= (action {:config {} :state {}})
             {:a 1 :b 2 :e 5}))))

  (testing "Custom action are allowed"
    (let [action-spec {:type   :custom
                       :name   :format-string
                       :params ["My name is %s"]
                       :fn     (fn [format-str]
                                 (format format-str "John"))}
          action      (sut/compile-action action-spec)]
      (is (= (action {:config {} :state {}})
             "My name is John"))))

  (testing "Unknown action type raises an error"
    (let [action-spec {:type :random
                       :name :random-test}]
      (is (thrown? Exception (sut/compile-action action-spec)))))

  (testing "Params compilation"
    (let [compiled-params (sut/compile-action-params
                           {:type      :params-test
                            :name      :params-test
                            :params    '[param1 {:p2 param2} [1 2 state1]]
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
          action      (sut/compile-action action-spec)]
      (is (= (action {:config {:param1 "value1"
                               :param2 "value2"}
                      :state  {:some-action {:state1 "state-value"}}})
             "param1: value1, param2: value2, state1: state-value")))))



(comment
 ;; TODO wrap this code in the fixture to pretty print malli errors
 (def mp-report
   (mp/reporter))

 (try
   (catch Exception e
     (let [{:keys [type data]} (ex-data e)]
       (mp-report type data)))))