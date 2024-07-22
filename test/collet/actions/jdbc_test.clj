(ns collet.actions.jdbc-test
  (:require
   [clojure.test :refer :all]
   [collet.test-fixtures :as tf]
   [next.jdbc :as jdbc]
   [clj-test-containers.core :as tc]
   [collet.core :as collet]
   [collet.deps :as collet.deps]
   [collet.actions.jdbc :as sut])
  (:import
   [clojure.lang LazySeq]))


(use-fixtures :once (tf/instrument! 'collet.actions.jdbc))


(defn pg-container []
  (-> (tc/create {:image-name    "postgres:16.3"
                  :exposed-ports [5432]
                  :env-vars      {"POSTGRES_PASSWORD" "password"
                                  "POSTGRES_USER"     "test"
                                  "POSTGRES_DB"       "test"}})
      (tc/start!)))


(defn populate-table [conn]
  (jdbc/execute! conn ["CREATE TABLE users (id SERIAL PRIMARY KEY, user_name TEXT, age INT)"])
  (jdbc/execute! conn ["INSERT INTO users (user_name, age) VALUES ('Alice', 30)"])
  (jdbc/execute! conn ["INSERT INTO users (user_name, age) VALUES ('Bob', 40)"])
  (jdbc/execute! conn ["INSERT INTO users (user_name, age) VALUES ('Charlie', 50)"]))


(deftest jdbc-make-query-test
  (let [pg             (pg-container)
        port           (get (:mapped-ports pg) 5432)
        connection-map {:dbtype   "postgresql"
                        :host     "localhost"
                        :port     port
                        :dbname   "test"
                        :user     "test"
                        :password "password"}]
    (collet.deps/add-dependencies
     {:coordinates '[[org.postgresql/postgresql "42.7.3"]]})

    (with-open [conn (jdbc/get-connection connection-map)]
      (populate-table conn))

    (testing "make query with HoneySQL syntax"
      (let [result (sut/make-query {:connection connection-map
                                    :query      {:select   [:user-name :age]
                                                 :from     :users
                                                 :order-by [:age]}})]
        (is (instance? LazySeq result))
        (is (= 3 (count result)))
        (is (= {:users/user_name "Alice" :users/age 30}
               (first result)))))

    (testing "make query with plain SQL string"
      (let [result (sut/make-query {:connection connection-map
                                    :query      ["SELECT * FROM users ORDER BY age"]})]
        (is (instance? LazySeq result))
        (is (= 3 (count result)))
        (is (= {:users/id 1 :users/user_name "Alice" :users/age 30}
               (first result)))))

    (testing "use parameters in SQL string"
      (let [result (sut/make-query {:connection connection-map
                                    :query      ["SELECT * FROM users WHERE user_name = ?" "Bob"]})]
        (is (= 1 (count result)))
        (is (= {:users/id 2 :users/user_name "Bob" :users/age 40}
               (first result)))))

    (tc/stop! pg)))


(deftest jdbc-execute-action-test
  (let [pg             (pg-container)
        port           (get (:mapped-ports pg) 5432)
        connection-map {:dbtype   "postgresql"
                        :host     "localhost"
                        :port     port
                        :dbname   "test"
                        :user     "test"
                        :password "password"}]
    (collet.deps/add-dependencies
     {:coordinates '[[org.postgresql/postgresql "42.7.3"]]})

    (with-open [conn (jdbc/get-connection connection-map)]
      (populate-table conn)

      (testing "compile and execute action"
        (let [action  (collet/compile-action
                       {:name      :query-action
                        :type      :jdbc
                        :selectors {'connection [:config :connection]}
                        :params    {:connection 'connection
                                    :query      {:select   [:*]
                                                 :from     :users
                                                 :order-by [:age]}}
                        :return    [[:cat :users/user_name]]})
              context (action {:config {:connection connection-map}
                               :state  {}})
              result  (-> context :state :query-action)]
          (is (instance? LazySeq result))
          (is (= 3 (count result)))
          (is (= '("Alice" "Bob" "Charlie") result))))

      (testing "pass parameters to query action"
        (let [action  (collet/compile-action
                       {:name      :query-action
                        :type      :jdbc
                        :selectors {'connection [:config :connection]
                                    'name       [:config :name]}
                        :params    {:connection    'connection
                                    :query         {:select [:*]
                                                    :from   :users
                                                    :where  [:= :user-name 'name]}
                                    :prefix-table? false}
                        :return    [[:op :first]]})
              context (action {:config {:connection connection-map
                                        :name       "Bob"}
                               :state  {}})
              result  (-> context :state :query-action)]
          (is (map? result))
          (is (= {:id 2 :user_name "Bob" :age 40} result)))))

    (tc/stop! pg)))


(defn populate-pg-data-types [conn]
  ;; create enum type
  (jdbc/execute! conn ["CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy')"])
  (jdbc/execute! conn ["CREATE TABLE data_types (id SERIAL PRIMARY KEY,
                                                 bool_col BOOLEAN,
                                                 int_col INT,
                                                 float_col FLOAT,
                                                 text_col TEXT,
                                                 date_col DATE,
                                                 time_col TIME,
                                                 timestamp_col TIMESTAMP,
                                                 json_col JSON,
                                                 jsonb_col JSONB,
                                                 interval_col INTERVAL,
                                                 mood_col mood)"])
  (jdbc/execute! conn ["INSERT INTO data_types (bool_col, int_col, float_col, text_col, date_col, time_col, timestamp_col, json_col, jsonb_col, interval_col, mood_col)
                        VALUES (true, 42, 3.14, 'text', '2024-04-22', '21:01:03', '2024-04-22 21:01:03', '{\"a\": 1}', '{\"b\": 2}', '1 day 2 hours 3 minutes 4 seconds', 'happy')"])
  (jdbc/execute! conn ["INSERT INTO data_types (bool_col, int_col, float_col, text_col, date_col, time_col, timestamp_col, json_col, jsonb_col, interval_col, mood_col)
                        VALUES (false, 43, 3.15, 'text2', '2024-04-23', '21:01:04', '2024-04-23 21:01:04', '{\"c\": 3}', '{\"d\": 4}', '2 days 3 hours 4 minutes 5 seconds', 'sad')"]))


(deftest postgres-various-data-types-query-test
  (let [pg             (pg-container)
        port           (get (:mapped-ports pg) 5432)
        connection-map {:dbtype   "postgresql"
                        :host     "localhost"
                        :port     port
                        :dbname   "test"
                        :user     "test"
                        :password "password"}]
    (testing "query various data types"
      (let [pipeline (collet/compile-pipeline
                      {:name  :data-types
                       :deps  {:coordinates '[[org.postgresql/postgresql "42.7.3"]]
                               :requires    '[[collet.actions.jdbc-pg :as jdbc-pg]]}
                       :tasks [{:name    :query
                                :actions [{:name      :query-action
                                           :type      :jdbc
                                           :selectors {'connection [:config :connection]}
                                           :params    {:connection 'connection
                                                       :query      {:select [:*]
                                                                    :from   :data-types}}}]}]})
            _        (with-open [conn (jdbc/get-connection connection-map)]
                       (populate-pg-data-types conn))
            context  (pipeline {:connection connection-map})
            result   (-> context :query first)]
        (is (= '(#:data_types{:bool_col      true
                              :date_col      "2024-04-22"
                              :float_col     3.14
                              :id            1
                              :int_col       42
                              :interval_col  "PT26H3M4S"
                              :json_col      {:a 1}
                              :jsonb_col     {:b 2}
                              :text_col      "text"
                              :time_col      "1970-01-01T20:01:03Z"
                              :timestamp_col "2024-04-22T21:01:03"
                              :mood_col      "happy"}
                 #:data_types{:bool_col      false
                              :date_col      "2024-04-23"
                              :float_col     3.15
                              :id            2
                              :int_col       43
                              :interval_col  "PT51H4M5S"
                              :json_col      {:c 3}
                              :jsonb_col     {:d 4}
                              :text_col      "text2"
                              :time_col      "1970-01-01T20:01:04Z"
                              :timestamp_col "2024-04-23T21:01:04"
                              :mood_col      "sad"})
               result)))

      (let [pipeline (collet/compile-pipeline
                      {:name  :data-types
                       :deps  {:coordinates '[[org.postgresql/postgresql "42.7.3"]]
                               :requires    '[[collet.actions.jdbc-pg :as jdbc-pg]
                                              [next.jdbc.types :as types]]}
                       :tasks [{:name    :query
                                :actions [{:name      :query-action
                                           :type      :jdbc
                                           :selectors '{connection [:config :connection]
                                                        mood       [:config :mood]}
                                           :params    '{:connection connection
                                                        :query      {:select [:*]
                                                                     :from   :data-types
                                                                     :where  [:= :mood_col (types/as-other mood)]}}}]}]})
            context  (pipeline {:connection connection-map
                                :mood       "sad"})
            result   (-> context :query first)]
        (is (= 1 (count result)))
        (is (= "sad" (-> result first :data_types/mood_col)))))

    (tc/stop! pg)))


(defn mysql-container []
  (-> (tc/create {:image-name    "mysql:9.0.0"
                  :exposed-ports [3306]
                  :env-vars      {"MYSQL_ROOT_PASSWORD" "rootpass"
                                  "MYSQL_DATABASE"      "test"
                                  "MYSQL_USER"          "test-user"
                                  "MYSQL_PASSWORD"      "test-pass"}})
      (tc/start!)))


(defn populate-mysql-table [conn]
  (jdbc/execute! conn ["CREATE TABLE employees (id SERIAL PRIMARY KEY, user_name TEXT, position TEXT)"])
  (jdbc/execute! conn ["INSERT INTO employees (user_name, position) VALUES ('Alice', 'Manager')"])
  (jdbc/execute! conn ["INSERT INTO employees (user_name, position) VALUES ('Bob', 'Developer')"])
  (jdbc/execute! conn ["INSERT INTO employees (user_name, position) VALUES ('Charlie', 'Designer')"])
  (jdbc/execute! conn ["INSERT INTO employees (user_name, position) VALUES ('David', 'Manager')"])
  (jdbc/execute! conn ["INSERT INTO employees (user_name, position) VALUES ('Eve', 'Developer')"]))


(deftest execute-pipeline-with-jdbc-action
  (let [mysql          (mysql-container)
        port           (get (:mapped-ports mysql) 3306)
        connection-map {:dbtype   "mysql"
                        :host     "localhost"
                        :port     port
                        :dbname   "test"
                        :user     "test-user"
                        :password "test-pass"}]

    (testing "execute pipeline with jdbc action"
      (let [pipeline (collet/compile-pipeline
                      {:name  :employees
                       :deps  {:coordinates '[[com.mysql/mysql-connector-j "9.0.0"]]}
                       :tasks [{:name    :query
                                :actions [{:name      :query-action
                                           :type      :jdbc
                                           :selectors {'connection [:config :connection]}
                                           :params    {:connection 'connection
                                                       :query      {:select   [:*]
                                                                    :from     :employees
                                                                    :order-by [:id]}}
                                           :return    [[:cat :employees/user_name]]}]}]})
            _        (with-open [conn (jdbc/get-connection connection-map)]
                       (populate-mysql-table conn))
            context  (pipeline {:connection connection-map})
            result   (-> context :query first)]
        (is (instance? LazySeq result))
        (is (= 5 (count result)))
        (is (= '("Alice" "Bob" "Charlie" "David" "Eve") result))))

    (tc/stop! mysql)))
