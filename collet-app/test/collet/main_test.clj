(ns collet.main-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.test :refer :all]
   [clojure.tools.cli :as tools.cli]
   [clojure.java.shell :refer [sh]]
   [clj-test-containers.core :as tc]
   [collet.utils :as utils]
   [collet.main :as sut]))


(defn localstack-container []
  (-> (tc/create {:image-name    "localstack/localstack"
                  :exposed-ports [4566 4510]
                  :wait-for      {:wait-strategy :http
                                  :path          "/_localstack/health"
                                  :port          4566
                                  :method        "GET"}
                  :env-vars      {"AWS_ACCESS_KEY_ID"     "test-user"
                                  "AWS_SECRET_ACCESS_KEY" "test-pass"
                                  "AWS_DEFAULT_REGION"    "eu-west-1"}})
      (tc/start!)))


(deftest parse-options-test
  (testing "options parsed correctly"
    (let [{:keys [errors options]} (tools.cli/parse-opts '("-s" "configs/pipeline-test-config.edn") sut/cli-options)]
      (is (nil? errors))
      (is (= {} (:pipeline-config options)))
      (is (= :test-pipeline (-> options :pipeline-spec :name))))

    (let [{:keys [errors options]} (tools.cli/parse-opts '("-s" "{:name :raw-pipe-name :pwd #env \"PWD\"}" "-c" "{:foo :bar}") sut/cli-options)]
      (is (nil? errors))
      (is (= {:foo :bar} (:pipeline-config options)))
      (is (= :raw-pipe-name (-> options :pipeline-spec :name)))
      (is (string/ends-with? (-> options :pipeline-spec :pwd) "/collet-app")))

    (let [{:keys [errors options]} (tools.cli/parse-opts '("-s" "configs/pipeline-test-config.edn" "-c" "{}") sut/cli-options)]
      (is (nil? errors))
      (is (= {} (:pipeline-config options)))
      (is (= :test-pipeline (-> options :pipeline-spec :name)))))

  (testing "spec option is required"
    (let [{:keys [errors]} (tools.cli/parse-opts '() sut/cli-options)]
      (is (not (nil? errors)))))

  (testing "file should exist and raw options should be valid"
    (let [{:keys [errors]} (tools.cli/parse-opts '("-s" "configs/pipeline-test-config.edn" "-c" "[]") sut/cli-options)]
      (is (not (nil? errors)))
      (is (string/includes? (first errors) "Must provide a map for the pipeline config")))

    (let [{:keys [errors]} (tools.cli/parse-opts '("-s" "tmp/non-existing-file.edn") sut/cli-options)]
      (is (not (nil? errors)))
      (is (string/includes? (first errors) "File does not exist"))))

  (testing "file upload from S3"
    (let [container      (localstack-container)
          container-port (get-in container [:mapped-ports 4566])
          aws-creds      {:aws-region        "eu-west-1"
                          :aws-key           "test-user"
                          :aws-secret        "test-pass"
                          :endpoint-override {:protocol :http
                                              :hostname "localhost"
                                              :port     container-port}}
          s3-client      (utils/make-client :s3 aws-creds)]
      (utils/invoke! s3-client :CreateBucket
                     {:Bucket                    "test-bucket"
                      :CreateBucketConfiguration {:LocationConstraint "eu-west-1"}})

      (with-open [file-stream (io/input-stream "configs/pipeline-test-config.edn")]
        (utils/invoke! s3-client :PutObject
                       {:Bucket "test-bucket"
                        :Key    "test-pipeline-config.edn"
                        :Body   file-stream}))

      (with-redefs [utils/make-client (fn [& _] s3-client)]
        (let [{:keys [errors options]} (tools.cli/parse-opts '("-s" "s3://test-user:test-pass@test-bucket/test-pipeline-config.edn?region=eu-west-1") sut/cli-options)]
          (is (nil? errors))
          (is (= :test-pipeline (-> options :pipeline-spec :name)))))

      (tc/stop! container))))


(deftest config-string-parse-test
  (testing "config values can refer to env variables"
    (let [config (sut/read-config-string "{:pwd #env \"PWD\" :port #env [\"NOT_SET_VAR_PORT\" Int :or 8080]}")]
      (is (= 8080 (:port config)))
      (is (string/ends-with? (:pwd config) "/collet-app")))))


(deftest pipeline-execution-test
  (let [{:keys [exit out]}
        (sh "/usr/local/bin/lein" "run" "-s" "configs/sample-pipeline.edn" "-c" "{}")]
    (is (zero? exit))
    (is (string/includes? out "Pipeline completed."))))
