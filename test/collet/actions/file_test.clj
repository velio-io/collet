(ns collet.actions.file-test
  (:require
   [clojure.test :refer :all]
   [clojure.data.csv :as csv]
   [clojure.java.io :as io]
   [collet.test-fixtures :as tf]
   [collet.utils :as utils]
   [next.jdbc :as jdbc]
   [cheshire.core :as json]
   [clj-test-containers.core :as tc]
   [collet.actions.jdbc-test :as jdbc-test]
   [collet.core :as collet]
   [collet.actions.file :as sut]))


(use-fixtures :once (tf/instrument! 'collet.actions.file))


(deftest write-into-file-test
  (testing "writing JSON file"
    (let [input     [{:a 1 :b 2} {:a 3 :b 4}]
          file-name "./tmp/file-test.json"
          options   {:input     input
                     :format    :json
                     :file-name file-name
                     :override? true}]
      (sut/write-into-file options)

      (is (.exists (io/file file-name)))

      (with-open [rdr (io/reader file-name)]
        (is (= input
               (->> (line-seq rdr)
                    (mapv #(json/parse-string % true))))))

      (testing "appending new rows"
        (let [new-input [{:a 5 :b 6} {:a 7 :b 8}]
              options   (assoc options :input new-input :override? false)]
          (sut/write-into-file options)

          (with-open [rdr (io/reader file-name)]
            (is (= (concat input new-input)
                   (->> (line-seq rdr)
                        (mapv #(json/parse-string % true))))))))

      (io/delete-file (io/file file-name))))

  (testing "writing CSV file"
    (testing "exporting a collection of maps"
      (let [input     [{:a 1 :b 2} {:a 3 :b 4}]
            file-name "./tmp/file-test.csv"
            options   {:input       input
                       :format      :csv
                       :file-name   file-name
                       :csv-header? true}]
        (is (not (.exists (io/file file-name))))

        (sut/write-into-file options)

        (is (.exists (io/file file-name)))

        (with-open [rdr (io/reader file-name)]
          (is (= [["a" "b"] ["1" "2"] ["3" "4"]]
                 (doall (csv/read-csv rdr)))))

        (testing "appending new rows"
          (let [new-input [{:a 5 :b 6} {:a 7 :b 8}]
                options   (assoc options :input new-input :csv-header? false)]
            (sut/write-into-file options)

            (with-open [rdr (io/reader file-name)]
              (is (= [["a" "b"] ["1" "2"] ["3" "4"] ["5" "6"] ["7" "8"]]
                     (doall (csv/read-csv rdr)))))))

        (io/delete-file (io/file file-name))))

    (testing "exporting a collection of sequential items"
      (let [input     [["a" "b"] [1 2] [3 4]]
            file-name "./tmp/file-test.csv"
            options   {:input       input
                       :format      :csv
                       :file-name   file-name
                       :csv-header? true}]
        (sut/write-into-file options)

        (is (.exists (io/file file-name)))

        (with-open [rdr (io/reader file-name)]
          (is (= [["a" "b"] ["1" "2"] ["3" "4"]]
                 (doall (csv/read-csv rdr)))))

        (io/delete-file (io/file file-name))))))


(deftest pipeline-file-action
  (let [pg             (jdbc-test/pg-container)
        port           (get (:mapped-ports pg) 5432)
        connection-map {:dbtype   "postgresql"
                        :host     "localhost"
                        :port     port
                        :dbname   "test"
                        :user     "test"
                        :password "password"}]
    (with-open [conn (jdbc/get-connection connection-map)]
      (jdbc-test/populate-table conn))

    (let [pipeline (collet/compile-pipeline
                    {:name  :file-sink-test
                     :deps  {:coordinates '[[org.postgresql/postgresql "42.7.3"]]
                             :requires    '[[collet.actions.jdbc-pg :as jdbc-pg]]}
                     :tasks [{:name    :query
                              :actions [{:name      :query-action
                                         :type      :jdbc
                                         :selectors {'connection [:config :connection]}
                                         :params    {:connection 'connection
                                                     :query      {:select [:*]
                                                                  :from   :users}}}
                                        {:name      :sink-action
                                         :type      :file
                                         :selectors {'input [:state :query-action]}
                                         :params    {:input       'input
                                                     :format      :csv
                                                     :file-name   "./tmp/file-sink-test.csv"
                                                     :csv-header? true}}]}]})]

      @(pipeline {:connection connection-map})

      (with-open [rdr (io/reader "./tmp/file-sink-test.csv")]
        (is (= [["id" "user_name" "age"]
                ["1" "Alice" "30"]
                ["2" "Bob" "40"]
                ["3" "Charlie" "50"]]
               (doall (csv/read-csv rdr)))))

      (tc/stop! pg)
      (io/delete-file (io/file "./tmp/file-sink-test.csv")))))


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
        s3-client      (utils/make-client :s3 aws-creds)]
    (utils/invoke! s3-client :CreateBucket
                   {:Bucket                    "test-bucket"
                    :CreateBucketConfiguration {:LocationConstraint "eu-west-1"}})

    (sut/upload-file
     {:aws-creds   aws-creds
      :bucket      "test-bucket"
      :format      :csv
      :file-name   "test/test-file.csv"
      :input       [{:a 1 :b 2} {:a 3 :b 4} {:a 5 :b 6}]
      :csv-header? true})

    (let [file (utils/invoke! s3-client :GetObject
                              {:Bucket "test-bucket"
                               :Key    "test/test-file.csv"})]
      (is (= [["a" "b"] ["1" "2"] ["3" "4"] ["5" "6"]]
             (csv/read-csv (io/reader (:Body file))))))

    (tc/stop! container)))


(defn generate-large-file
  [file-path size-in-mb]
  (let [chunk-size  (* 1024 1024) ;; 1MB in bytes
        repetitions (/ size-in-mb 1)] ;; Number of 1MB chunks needed
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
        s3-client      (utils/make-client :s3 aws-creds)]
    (utils/invoke! s3-client :CreateBucket
                   {:Bucket                    "test-multipart-bucket"
                    :CreateBucketConfiguration {:LocationConstraint "eu-west-1"}})

    (generate-large-file "./tmp/large-test-file.bin" 1024)

    (with-open [is (io/input-stream "./tmp/large-test-file.bin")]
      (sut/multipart-upload
       s3-client
       "test-multipart-bucket"
       "large-test-file.bin"
       is))

    (let [file            (utils/invoke! s3-client :GetObject
                                         {:Bucket "test-multipart-bucket"
                                          :Key    "large-test-file.bin"})
          one-gb-in-bytes 1073741824]
      (is (= one-gb-in-bytes (:ContentLength file))))

    (io/delete-file "./tmp/large-test-file.bin")
    (tc/stop! container)))


(deftest pipeline-s3-action
  (let [container      (localstack-container)
        container-port (get-in container [:mapped-ports 4566])
        s3-client      (utils/make-client :s3
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
                                             :type      :s3
                                             :selectors {'creds [:config :aws-creds]}
                                             :params    {:aws-creds   'creds
                                                         :input       [{:a 1 :b 2} {:a 3 :b 4} {:a 5 :b 6}]
                                                         :format      :csv
                                                         :bucket      "pipe-test-bucket"
                                                         :file-name   "pipe-test-file.csv"
                                                         :csv-header? true}}]}]})]
    (utils/invoke! s3-client :CreateBucket
                   {:Bucket                    "pipe-test-bucket"
                    :CreateBucketConfiguration {:LocationConstraint "eu-west-1"}})

    @(pipeline {:aws-creds {:aws-region        "eu-west-1"
                            :aws-key           "test"
                            :aws-secret        "test"
                            :endpoint-override {:protocol :http
                                                :hostname "localhost"
                                                :port     container-port}}})

    (let [file (utils/invoke! s3-client :GetObject
                              {:Bucket "pipe-test-bucket"
                               :Key    "pipe-test-file.csv"})]
      (is (= [["a" "b"] ["1" "2"] ["3" "4"] ["5" "6"]]
             (csv/read-csv (io/reader (:Body file))))))

    (tc/stop! container)))
