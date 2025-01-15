(ns collet.actions.file-test
  (:require
   [clojure.test :refer :all]
   [clojure.java.io :as io]
   [clj-test-containers.core :as tc]
   [charred.api :as charred]
   [next.jdbc :as jdbc]
   [collet.test-fixtures :as tf]
   [collet.actions.jdbc-test :as jdbc-test]
   [collet.core :as collet]
   [collet.actions.file :as sut]
   [tech.v3.dataset :as ds]))


(use-fixtures :once (tf/instrument! 'collet.actions.file))


(deftest write-into-file-test
  (testing "writing JSON file"
    (let [input     [{:a 1 :b 2} {:a 3 :b 4}]
          file-name "./tmp/file-test.json"
          options   {:input     input
                     :format    :json
                     :file-name file-name}]
      (sut/write-into-file options)

      (is (.exists (io/file file-name)))

      (is (= input
             (-> (slurp file-name)
                 (charred/read-json :key-fn keyword))))

      (testing "overriding existing file"
        (let [new-input [{:a 5 :b 6} {:a 7 :b 8}]
              options   (assoc options :input new-input)]
          (sut/write-into-file options)

          (is (= new-input
                 (-> (slurp file-name)
                     (charred/read-json :key-fn keyword))))))

      (testing "writing a dataset sequence"
        (let [dataset-seq (seq [(ds/->dataset [{:a 1 :b 2} {:a 3 :b 4}])
                                (ds/->dataset [{:a 5 :b 6} {:a 7 :b 8}])])
              options     {:input     dataset-seq
                           :format    :json
                           :file-name file-name}]
          (sut/write-into-file options)

          (is (= [{:a 1 :b 2} {:a 3 :b 4} {:a 5 :b 6} {:a 7 :b 8}]
                 (-> (slurp file-name)
                     (charred/read-json :key-fn keyword))))))

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
                 (doall (charred/read-csv rdr)))))

        (testing "overriding existing file"
          (let [new-input [{:a 5 :b 6} {:a 7 :b 8}]
                options   (assoc options :input new-input)]
            (sut/write-into-file options)

            (with-open [rdr (io/reader file-name)]
              (is (= [["a" "b"] ["5" "6"] ["7" "8"]]
                     (doall (charred/read-csv rdr)))))))

        (testing "writing a dataset sequence"
          (let [dataset-seq (seq [(ds/->dataset [{:a 1 :b 2} {:a 3 :b 4}])
                                  (ds/->dataset [{:a 5 :b 6} {:a 7 :b 8}])])
                options     {:input       dataset-seq
                             :format      :csv
                             :file-name   file-name
                             :csv-header? true}]
            (sut/write-into-file options)

            (with-open [rdr (io/reader file-name)]
              (is (= [["a" "b"] ["1" "2"] ["3" "4"] ["5" "6"] ["7" "8"]]
                     (doall (charred/read-csv rdr)))))))

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
                                         :type      :collet.actions.jdbc/query
                                         :selectors {'connection [:config :connection]}
                                         :params    {:connection 'connection
                                                     :query      {:select [:*]
                                                                  :from   :users}}}
                                        {:name      :sink-action
                                         :type      :collet.actions.file/sink
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
               (doall (charred/read-csv rdr)))))

      (tc/stop! pg)
      (io/delete-file (io/file "./tmp/file-sink-test.csv")))))
