(ns collet.actions-compatibility-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [collet.action :as action]
   [collet.core :as collet]
   [collet.actions.file]
   [collet.actions.http]
   [collet.actions.jdbc]
   [collet.actions.jdbc-pg]
   [collet.actions.jslt]
   [collet.actions.llm]
   [collet.actions.lucene]
   [collet.actions.odata]
   [collet.actions.queue]
   [collet.actions.s3]
   [collet.actions.vega]))

(def legacy-action-types
  [:collet.actions.http/request
   :collet.actions.http/oauth2
   :collet.actions.file/sink
   :collet.actions.odata/request
   :collet.actions.jdbc/query
   :collet.actions.jdbc/execute
   :collet.actions.s3/sink
   :collet.actions.queue/enqueue
   :collet.actions.jslt/apply
   :collet.actions.llm/openai
   :collet.actions.vega/sink
   :collet.actions.lucene/index
   :collet.actions.lucene/query])

(deftest legacy-action-registration-test
  (testing "all legacy action keywords still dispatch"
    (doseq [action-type legacy-action-types]
      (is (fn? (action/action-fn {:type action-type}))
          (str action-type " is registered")))))

(deftest unchanged-pipeline-spec-test
  (let [spec     {:name  :aggregate-compatibility
                  :tasks [{:name       :transform
                           :keep-state true
                           :actions    [{:name   :apply-template
                                         :type   :collet.actions.jslt/apply
                                         :params {:input    "{\"answer\": 42}"
                                                  :template "{\"value\": .answer}"}}]}]}
        pipeline (collet/compile-pipeline spec)]
    @(pipeline {})
    (is (= {:value 42} (:transform pipeline)))))
