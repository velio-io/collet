(ns collet.main-test
  (:require
   [clj-test-containers.core :as tc]
   [clojure.java.io :as io]
   [clojure.java.shell :refer [sh]]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [clojure.tools.cli :as tools.cli]
   [collet.aws :as aws]
   [collet.core :as collet]
   [collet.main :as sut]
   [collet.test-containers :as containers])
  (:import
    [java.nio.file FileVisitOption Files LinkOption Path]
    [java.nio.file.attribute FileAttribute]
    [java.util.regex Pattern]))


(defn- delete-tree!
  [^Path path]
  (when (Files/exists path (make-array LinkOption 0))
    (with-open [paths (Files/walk path (make-array FileVisitOption 0))]
      (doseq [entry (sort-by str
                             #(compare %2 %1)
                             (iterator-seq (.iterator paths)))]
        (Files/deleteIfExists ^Path entry)))))


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
          inc-actions              (->> (-> options :pipeline-spec :tasks)
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
             (-> inc-actions
                 second
                 :params
                 :query-params
                 (select-keys [:state :per_page]))))

      (is (instance? Pattern (-> inc-actions second :params :query-params :rx))
          "regex is parsed correctly")))

  (testing "parsing regex in edn"
    (let [{:keys [errors options]} (tools.cli/parse-opts '("-s" "{:name :parent-pipe :regex #rgx \"foo\"}") sut/cli-options)]
      (is (nil? errors))
      (is (instance? Pattern (-> options :pipeline-spec :regex))))))


(deftest ^:integration s3-config-test
  (let [container      (containers/localstack)
        container-port (get-in container [:mapped-ports 4566])
        aws-creds      {:aws-region        "eu-west-1"
                        :aws-key           "test-user"
                        :aws-secret        "test-pass"
                        :endpoint-override {:protocol :http
                                            :hostname "localhost"
                                            :port     container-port}}
        s3-client      (aws/make-client :s3 aws-creds)]
    (aws/invoke! s3-client
     :CreateBucket
     {:Bucket "test-bucket"
      :CreateBucketConfiguration {:LocationConstraint "eu-west-1"}})

    (with-open [file-stream (io/input-stream "configs/pipeline-test-config.edn")]
      (aws/invoke! s3-client
       :PutObject
       {:Bucket "test-bucket"
        :Key    "test-pipeline-config.edn"
        :Body   file-stream}))

    (with-redefs [aws/make-client (fn [& _] s3-client)]
      (let [{:keys [errors options]} (tools.cli/parse-opts '("-s" "s3://test-user:test-pass@test-bucket/test-pipeline-config.edn?region=eu-west-1") sut/cli-options)]
        (is (nil? errors))
        (is (= :test-pipeline (-> options :pipeline-spec :name)))))

    (tc/stop! container)))


(deftest config-string-parse-test
  (testing "config values can refer to env variables"
    (let [config (sut/read-config-string :config "{:pwd #env \"PWD\" :port #env [\"NOT_SET_VAR_PORT\" Int :or 8080]}")]
      (is (= 8080 (:port config)))
      (is (string/includes? (:pwd config) "collet")))))


(deftest runtime-context-uses-the-configured-data-directory
  (testing "the default is relative to the process working directory"
    (binding [sut/*env* {}]
      (with-redefs [collet/context identity]
        (is (= {:data-dir "./.collet"}
               (sut/create-runtime-context))))))

  (testing "COLLET_DATA_DIR overrides the default"
    (let [data-dir (Files/createTempDirectory
                    "collet-app-data-"
                    (make-array FileAttribute 0))]
      (try
        (binding [sut/*env* {"COLLET_DATA_DIR" (str data-dir)}]
          (let [ctx (sut/create-runtime-context)]
            (try
              (is (= (str (.resolve data-dir "db")) (get-in ctx [:store :dir])))
              (is (= (str (.resolve data-dir "artifacts"))
                     (str (:artifact-dir ctx))))
              (finally
               (collet/close ctx)))))
        (finally
         (delete-tree! data-dir))))))


(deftest ^:integration pipeline-execution-test
  (let [data-dir (str (java.nio.file.Files/createTempDirectory
                       "collet-app-test-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        {:keys [exit out]}
        (sh "java"
            "--add-opens=java.base/java.nio=ALL-UNNAMED"
            "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
            "--enable-native-access=ALL-UNNAMED"
            "-jar" "target/collet.jar"
            "-s" "configs/sample-pipeline.edn"
            "-c" "{}"
            :env (assoc (into {} (System/getenv))
                   "COLLET_DATA_DIR" data-dir))]
    (is (zero? exit))
    (is (string/includes? out "Pipeline completed."))))
