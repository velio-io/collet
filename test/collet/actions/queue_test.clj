(ns collet.actions.queue-test
  (:require
   [clojure.test :refer :all]
   [collet.core :as collet]
   [collet.test-fixtures :as tf]
   [cues.queue :as q]
   [collet.actions.queue :as sut]))


(use-fixtures :once (tf/instrument! 'collet.actions.queue))


(deftest write-into-queue-test
  (testing "writing into a queue"
    (let [input    [{:a 1 :b 2} {:a 3 :b 4}]
          queue    (q/queue :write-test {:queue-path "./tmp/queues"
                                         :roll-cycle :fast-daily})
          appender (q/appender queue)
          tailer   (q/tailer queue)]

      (sut/write-into-queue
       {:input         input
        :queue-name    :write-test
        ::sut/appender appender})

      (is (= (first input) (q/read tailer)))
      (is (= (second input) (q/read tailer)))
      (is (nil? (q/read tailer)))

      (q/delete-queue! queue true))))


(deftest pipeline-queue-action
  (testing "pipeline queue action. writing multiple messages"
    (let [pipeline (collet/compile-pipeline
                    {:name  :queue-sink-test
                     :tasks [{:name    :write-messages
                              :actions [{:name   :queue-action
                                         :type   :queue
                                         :params {:input      [{:a 1 :b 2} {:a 3 :b 4} {:a 5 :b 6}]
                                                  :queue-name :pipeline-queue-test}}]}]})]

      @(pipeline {})

      (let [queue  (q/queue :pipeline-queue-test {:queue-path "tmp/queues" :roll-cycle :fast-daily})
            tailer (q/tailer queue)]

        (is (= {:a 1 :b 2} (q/read tailer)))
        (is (= {:a 3 :b 4} (q/read tailer)))
        (is (= {:a 5 :b 6} (q/read tailer)))
        (is (nil? (q/read tailer)))

        (q/delete-queue! queue true))))

  (testing "pipeline queue action. writing a single message"
    (let [pipeline (collet/compile-pipeline
                    {:name  :queue-sink-test
                     :tasks [{:name    :write-messages
                              :actions [{:name   :queue-action
                                         :type   :queue
                                         :params {:input      {:a 1 :b 2}
                                                  :queue-name :pipeline-queue-test}}]}]})]

      @(pipeline {})

      (let [queue  (q/queue :pipeline-queue-test {:queue-path "tmp/queues" :roll-cycle :fast-daily})
            tailer (q/tailer queue)]

        (is (= {:a 1 :b 2} (q/read tailer)))
        (is (nil? (q/read tailer)))

        (q/delete-queue! queue true)))))