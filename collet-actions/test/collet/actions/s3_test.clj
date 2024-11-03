(ns collet.actions.s3-test
  (:require
   [clojure.test :refer :all]
   [clojure.java.io :as io]
   [charred.api :as charred]
   [clj-test-containers.core :as tc]
   [collet.test-fixtures :as tf]
   [collet.core :as collet]
   [collet.actions.s3 :as sut]))


(use-fixtures :once (tf/instrument! 'collet.actions.s3))


(defn localstack-container []
  (-> (tc/create {:image-name    "localstack/localstack"
                  :exposed-ports [4566 4510]
                  :wait-for      {:wait-strategy :http
                                  :path          "/_localstack/health"
                                  :port          4566
                                  :method        "GET"}
                  :env-vars      {"AWS_ACCESS_KEY_ID"     "test"
                                  "AWS_SECRET_ACCESS_KEY" "test"
                                  "AWS_DEFAULT_REGION"    "eu-west-1"}})
      (tc/start!)))


(deftest s3-upload-test
  (let [container      (localstack-container)
        container-port (get-in container [:mapped-ports 4566])
        aws-creds      {:aws-region        "eu-west-1"
                        :aws-key           "test"
                        :aws-secret        "test"
                        :endpoint-override {:protocol :http
                                            :hostname "localhost"
                                            :port     container-port}}
        s3-client      (sut/make-client :s3 aws-creds)]
    (sut/invoke! s3-client :CreateBucket
      {:Bucket                    "test-bucket"
       :CreateBucketConfiguration {:LocationConstraint "eu-west-1"}})

    (sut/upload-file
     {:aws-creds   aws-creds
      :bucket      "test-bucket"
      :format      :csv
      :file-name   "test/test-file.csv"
      :input       [{:a 1 :b 2} {:a 3 :b 4} {:a 5 :b 6}]
      :csv-header? true})

    (let [file (sut/invoke! s3-client :GetObject
                 {:Bucket "test-bucket"
                  :Key    "test/test-file.csv"})]
      (is (= [["a" "b"] ["1" "2"] ["3" "4"] ["5" "6"]]
             (charred/read-csv (io/reader (:Body file))))))

    (tc/stop! container)))


(defn generate-large-file
  [file-path size-in-mb]
  (let [chunk-size  (* 1024 1024) ;; 1MB in bytes
        repetitions (/ size-in-mb 1)] ;; Number of 1MB chunks needed
    (io/make-parents file-path)
    (with-open [writer (io/output-stream file-path)]
      (dotimes [_ repetitions]
        (.write writer (byte-array chunk-size))))))


(deftest multipart-upload-test
  (let [container      (localstack-container)
        container-port (get-in container [:mapped-ports 4566])
        aws-creds      {:aws-region        "eu-west-1"
                        :aws-key           "test"
                        :aws-secret        "test"
                        :endpoint-override {:protocol :http
                                            :hostname "localhost"
                                            :port     container-port}}
        s3-client      (sut/make-client :s3 aws-creds)]
    (sut/invoke! s3-client :CreateBucket
      {:Bucket                    "test-multipart-bucket"
       :CreateBucketConfiguration {:LocationConstraint "eu-west-1"}})

    (generate-large-file "./tmp/large-test-file.bin" 1024)

    (with-open [is (io/input-stream "./tmp/large-test-file.bin")]
      (sut/multipart-upload
       s3-client
       "test-multipart-bucket"
       "large-test-file.bin"
       is))

    (let [file            (sut/invoke! s3-client :GetObject
                            {:Bucket "test-multipart-bucket"
                             :Key    "large-test-file.bin"})
          one-gb-in-bytes 1073741824]
      (is (= one-gb-in-bytes (:ContentLength file))))

    (io/delete-file "./tmp/large-test-file.bin")
    (tc/stop! container)))


(deftest pipeline-s3-action
  (let [container      (localstack-container)
        container-port (get-in container [:mapped-ports 4566])
        s3-client      (sut/make-client :s3
                                        {:aws-region        "eu-west-1"
                                         :aws-key           "test"
                                         :aws-secret        "test"
                                         :endpoint-override {:protocol :http
                                                             :hostname "localhost"
                                                             :port     container-port}})
        pipeline       (collet/compile-pipeline
                        {:name  :s3-sink-test
                         :tasks [{:name    :s3-test-task
                                  :actions [{:name      :s3-action
                                             :type      :collet.actions.s3/sink
                                             :selectors {'creds [:config :aws-creds]}
                                             :params    {:aws-creds   'creds
                                                         :input       [{:a 1 :b 2} {:a 3 :b 4} {:a 5 :b 6}]
                                                         :format      :csv
                                                         :bucket      "pipe-test-bucket"
                                                         :file-name   "pipe-test-file.csv"
                                                         :csv-header? true}}]}]})]
    (sut/invoke! s3-client :CreateBucket
      {:Bucket                    "pipe-test-bucket"
       :CreateBucketConfiguration {:LocationConstraint "eu-west-1"}})

    @(pipeline {:aws-creds {:aws-region        "eu-west-1"
                            :aws-key           "test"
                            :aws-secret        "test"
                            :endpoint-override {:protocol :http
                                                :hostname "localhost"
                                                :port     container-port}}})

    (let [file (sut/invoke! s3-client :GetObject
                 {:Bucket "pipe-test-bucket"
                  :Key    "pipe-test-file.csv"})]
      (is (= [["a" "b"] ["1" "2"] ["3" "4"] ["5" "6"]]
             (charred/read-csv (io/reader (:Body file))))))

    (tc/stop! container)))