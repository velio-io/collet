(ns collet.actions.file-test
  (:require
   [clojure.test :refer :all]
   [clojure.data.csv :as csv]
   [clojure.java.io :as io]
   [collet.test-fixtures :as tf]
   [next.jdbc :as jdbc]
   [cheshire.core :as json]
   [clj-test-containers.core :as tc]
   [collet.actions.jdbc-test :as jdbc-test]
   [collet.core :as collet]
   [collet.actions.file :as sut]))


(use-fixtures :once (tf/instrument! 'collet.actions.file))


(deftest write-into-file-test
  (testing "writing JSON file"
    (let [input    [{:a 1 :b 2} {:a 3 :b 4}]
          filename "./tmp/file-test.json"
          options  {:input     input
                    :format    :json
                    :filename  filename
                    :override? true}]
      (sut/write-into-file options)

      (is (.exists (io/file filename)))

      (with-open [rdr (io/reader filename)]
        (is (= input
               (->> (line-seq rdr)
                    (mapv #(json/parse-string % true))))))

      (testing "appending new rows"
        (let [new-input [{:a 5 :b 6} {:a 7 :b 8}]
              options   (assoc options :input new-input :override? false)]
          (sut/write-into-file options)

          (with-open [rdr (io/reader filename)]
            (is (= (concat input new-input)
                   (->> (line-seq rdr)
                        (mapv #(json/parse-string % true))))))))

      (io/delete-file (io/file filename))))

  (testing "writing CSV file"
    (testing "exporting a collection of maps"
      (let [input    [{:a 1 :b 2} {:a 3 :b 4}]
            filename "./tmp/file-test.csv"
            options  {:input       input
                      :format      :csv
                      :filename    filename
                      :csv-header? true}]
        (is (not (.exists (io/file filename))))

        (sut/write-into-file options)

        (is (.exists (io/file filename)))

        (with-open [rdr (io/reader filename)]
          (is (= [["a" "b"] ["1" "2"] ["3" "4"]]
                 (doall (csv/read-csv rdr)))))

        (testing "appending new rows"
          (let [new-input [{:a 5 :b 6} {:a 7 :b 8}]
                options   (assoc options :input new-input :csv-header? false)]
            (sut/write-into-file options)

            (with-open [rdr (io/reader filename)]
              (is (= [["a" "b"] ["1" "2"] ["3" "4"] ["5" "6"] ["7" "8"]]
                     (doall (csv/read-csv rdr)))))))

        (io/delete-file (io/file filename))))

    (testing "exporting a collection of sequential items"
      (let [input    [["a" "b"] [1 2] [3 4]]
            filename "./tmp/file-test.csv"
            options  {:input       input
                      :format      :csv
                      :filename    filename
                      :csv-header? true}]
        (sut/write-into-file options)

        (is (.exists (io/file filename)))

        (with-open [rdr (io/reader filename)]
          (is (= [["a" "b"] ["1" "2"] ["3" "4"]]
                 (doall (csv/read-csv rdr)))))

        (io/delete-file (io/file filename))))))


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
                                                     :filename    "./tmp/file-sink-test.csv"
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