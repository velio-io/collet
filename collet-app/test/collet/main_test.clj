(ns collet.main-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.test :refer :all]
   [clojure.tools.cli :as tools.cli]
   [clojure.java.shell :refer [sh]]
   [clj-test-containers.core :as tc]
   [collet.aws :as aws]
   [collet.main :as sut])
  (:import
   [java.util.regex Pattern]))


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

    (let [{:keys [errors options]} (tools.cli/parse-opts '("-s" "{:name :raw-pipe-name}" "-c" "{:foo :bar :pwd #env \"PWD\"}") sut/cli-options)]
      (is (nil? errors))
      (is (= :bar (-> options :pipeline-config :foo)))
      (is (string/includes? (-> options :pipeline-config :pwd) "collet"))
      (is (= :raw-pipe-name (-> options :pipeline-spec :name))))

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

  (testing "spec supports include tag"
    (let [{:keys [errors options]} (tools.cli/parse-opts '("-s" "{:name :parent-pipe :include-config #include \"configs/pipeline-test-config.edn\"}") sut/cli-options)]
      (is (nil? errors))
      (is (= :parent-pipe (-> options :pipeline-spec :name)))
      (is (= :test-pipeline (-> options :pipeline-spec :include-config :name)))))

  (testing "spec supports include tag with overrides"
    (let [{:keys [errors options]} (tools.cli/parse-opts '("-s" "configs/pipeline-with-includes.edn") sut/cli-options)
          inc-actions (->> (-> options :pipeline-spec :tasks)
                           (map (comp first :actions)))]
      (is (nil? errors))
      (is (every? #(and (= (:name %) :gh-request)
                        (= (:type %) :collet.actions.http/request)
                        (= (get-in % [:selectors 'gh-token]) [:config :gh-token]))
                  inc-actions)
          "all included actions has the common properties")

      (is (= ["https://api.github.com/orgs/%s/repos" 'org-name]
             (-> inc-actions first :params :url)))
      (is (= ["https://api.github.com/repos/%s/%s/pulls" 'org-name 'repo]
             (-> inc-actions second :params :url)))

      (is (= {:state "closed" :per_page 100}
             (-> inc-actions second :params :query-params
                 (select-keys [:state :per_page]))))

      (is (instance? Pattern (-> inc-actions second :params :query-params :rx))
          "regex is parsed correctly")))

  (testing "parsing regex in edn"
    (let [{:keys [errors options]} (tools.cli/parse-opts '("-s" "{:name :parent-pipe :regex #rgx \"foo\"}") sut/cli-options)]
      (is (nil? errors))
      (is (instance? Pattern (-> options :pipeline-spec :regex)))))

  (testing "file upload from S3"
    (let [container      (localstack-container)
          container-port (get-in container [:mapped-ports 4566])
          aws-creds      {:aws-region        "eu-west-1"
                          :aws-key           "test-user"
                          :aws-secret        "test-pass"
                          :endpoint-override {:protocol :http
                                              :hostname "localhost"
                                              :port     container-port}}
          s3-client      (aws/make-client :s3 aws-creds)]
      (aws/invoke! s3-client :CreateBucket
                   {:Bucket                    "test-bucket"
                    :CreateBucketConfiguration {:LocationConstraint "eu-west-1"}})

      (with-open [file-stream (io/input-stream "configs/pipeline-test-config.edn")]
        (aws/invoke! s3-client :PutObject
                     {:Bucket "test-bucket"
                      :Key    "test-pipeline-config.edn"
                      :Body   file-stream}))

      (with-redefs [aws/make-client (fn [& _] s3-client)]
        (let [{:keys [errors options]} (tools.cli/parse-opts '("-s" "s3://test-user:test-pass@test-bucket/test-pipeline-config.edn?region=eu-west-1") sut/cli-options)]
          (is (nil? errors))
          (is (= :test-pipeline (-> options :pipeline-spec :name)))))

      (tc/stop! container))))


(deftest config-string-parse-test
  (testing "config values can refer to env variables"
    (let [config (sut/read-config-string :config "{:pwd #env \"PWD\" :port #env [\"NOT_SET_VAR_PORT\" Int :or 8080]}")]
      (is (= 8080 (:port config)))
      (is (string/includes? (:pwd config) "collet")))))


(deftest pipeline-execution-test
  (let [{:keys [exit out]}
        (sh "/usr/local/bin/lein" "run" "-s" "configs/sample-pipeline.edn" "-c" "{}")]
    (is (zero? exit))
    (is (string/includes? out "Pipeline completed."))))
