(ns collet.pipeline-plan-test
  (:require
   [clojure.edn :as edn]
   [clojure.test :refer [deftest is testing]]
   [collet.core :as collet])
  (:import [clojure.lang ExceptionInfo]))


(defn durable-action
  [value]
  (inc value))


(def minimal-pipeline
  {:name  :orders
   :tasks [{:name    :fetch
            :actions [{:name :fetch
                       :type :custom
                       :fn   'collet.pipeline-plan-test/durable-action}]}]})


(deftest compile-pipeline-produces-durable-data
  (testing "version defaults to one and the plan round-trips as EDN"
    (let [pipeline (collet/compile-pipeline minimal-pipeline)]
      (is (= 1 (:version pipeline)))
      (is (= pipeline (edn/read-string (pr-str pipeline))))
      (is (map? pipeline))))

  (testing "Vars normalize to fully-qualified symbols"
    (let [pipeline (collet/compile-pipeline
                    (assoc-in minimal-pipeline
                     [:tasks 0 :actions 0 :fn]
                     #'durable-action))]
      (is (= 'collet.pipeline-plan-test/durable-action
             (get-in pipeline [:tasks 0 :actions 0 :fn])))))

  (testing "regular expressions normalize to EDN data"
    (let [pipeline (collet/compile-pipeline
                    (assoc-in minimal-pipeline
                     [:tasks 0 :actions 0 :params]
                     {:pattern #"(?i)orders"}))]
      (is (= {:collet.runtime/type :regex
              :pattern             "(?i)orders"
              :flags               2}
             (get-in pipeline [:tasks 0 :actions 0 :params :pattern])))
      (is (= pipeline (edn/read-string (pr-str pipeline))))))

  (testing "quoted function forms and qualified symbols are accepted"
    (is (map? (collet/compile-pipeline minimal-pipeline)))
    (is (map? (collet/compile-pipeline
               (assoc-in minimal-pipeline
                [:tasks 0 :actions 0 :fn]
                '(fn [value] (inc value)))))))

  (testing "external actions are expanded in setup and main actions"
    (let [pipeline (collet/compile-pipeline
                    {:name  :external-actions
                     :tasks [{:name    :external
                              :setup   [{:name   :setup
                                         :type   :test.collet/counter-action.edn
                                         :params [0]}]
                              :actions [{:name   :main
                                         :type   :test.collet/counter-action.edn
                                         :params [1]}]}]})]
      (is (= :clj/inc (get-in pipeline [:tasks 0 :setup 0 :type])))
      (is (= :my-external-action
             (get-in pipeline [:tasks 0 :setup 0 :name])))
      (is (= :clj/inc (get-in pipeline [:tasks 0 :actions 0 :type])))))

  (testing "anonymous function objects are rejected"
    (let [error (try
                  (collet/compile-pipeline
                   (assoc-in minimal-pipeline
                    [:tasks 0 :actions 0 :fn]
                    (fn [value] (inc value))))
                  nil
                  (catch ExceptionInfo error
                    error))]
      (is (= :collet.error/non-durable-value
             (:collet.error/type (ex-data error))))
      (is (= [:tasks 0 :actions 0 :fn]
             (:path (ex-data error))))))

  (testing "other unsupported runtime objects are rejected"
    (is (= :collet.error/non-durable-value
           (try
             (collet/compile-pipeline
              (assoc-in minimal-pipeline
               [:tasks 0 :actions 0 :params]
               {:value (Object.)}))
             nil
             (catch ExceptionInfo error
               (:collet.error/type (ex-data error))))))))


(deftest compile-pipeline-validates-revisions-and-dependencies
  (testing "version must be a positive integer"
    (doseq [version [0 -1 1.5]]
      (is (thrown? ExceptionInfo
                   (collet/compile-pipeline
                    (assoc minimal-pipeline :version version))))))

  (testing "max parallelism must be positive"
    (doseq [parallelism [0 -1]]
      (is (thrown? ExceptionInfo
                   (collet/compile-pipeline
                    (assoc minimal-pipeline
                      :max-parallelism parallelism))))))

  (testing "task names are unique"
    (let [duplicate (-> minimal-pipeline
                        (update :tasks conj (first (:tasks minimal-pipeline))))]
      (is (= :duplicate-task-name
             (try
               (collet/compile-pipeline duplicate)
               nil
               (catch ExceptionInfo error
                 (:problem (ex-data error))))))))

  (testing "inputs name existing tasks"
    (is (= :missing-input
           (try
             (collet/compile-pipeline
              (assoc-in minimal-pipeline [:tasks 0 :inputs] [:missing]))
             nil
             (catch ExceptionInfo error
               (:problem (ex-data error)))))))

  (testing "cycles are rejected by the dependency graph"
    (let [cyclic {:name  :cycle
                  :tasks [{:name    :a
                           :inputs  [:b]
                           :actions [{:name :a :type :custom :fn 'clojure.core/identity}]}
                          {:name    :b
                           :inputs  [:a]
                           :actions [{:name :b :type :custom :fn 'clojure.core/identity}]}]}]
      (is (= :cycle
             (try
               (collet/compile-pipeline cyclic)
               nil
               (catch ExceptionInfo error
                 (:problem (ex-data error)))))))))
