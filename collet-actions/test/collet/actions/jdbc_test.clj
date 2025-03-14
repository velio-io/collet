(ns collet.actions.jdbc-test
  (:require
   [clojure.test :refer :all]
   [next.jdbc :as jdbc]
   [clj-test-containers.core :as tc]
   [collet.test-fixtures :as tf]
   [collet.core :as collet]
   [collet.deps :as collet.deps]
   [collet.utils :as utils]
   [collet.actions.jdbc :as sut]
   [tech.v3.dataset :as ds])
  (:import
   [clojure.lang LazySeq]
   [java.time LocalDate LocalDateTime LocalTime Duration]))


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

    (testing "execute statement test"
      (let [result (sut/execute-statement
                    {:connection connection-map
                     :statement  ["INSERT INTO users (user_name, age) VALUES (?, ?)" "David" 60]})]
        (is (= 1 (-> result first :next.jdbc/update-count)))

        (let [result (sut/make-query {:connection connection-map
                                      :query      {:select   [:*]
                                                   :from     :users
                                                   :order-by [:age]}})]
          (is (= 4 (count result)))
          (is (= {:users/id 4 :users/user_name "David" :users/age 60}
                 (last result))))

        (sut/execute-statement
         {:connection connection-map
          :statement  {:insert-into :users
                       :columns     [:user_name :age]
                       :values      [["Eve" 70]
                                     ["Frank" 80]]}})

        (let [result (sut/make-query {:connection connection-map
                                      :query      {:select   [:*]
                                                   :from     :users
                                                   :order-by [:age]}})]
          (is (= 6 (count result)))
          (is (= {:users/id 6 :users/user_name "Frank" :users/age 80}
                 (last result))))

        (sut/execute-statement
         {:connection connection-map
          :statement  {:insert-into :users
                       :columns     [:user_name :age]
                       :values      (ds/->dataset
                                     [{:user_name "Grace" :age 90}
                                      {:user_name "Helen" :age 100}])}})

        (let [result (sut/make-query {:connection connection-map
                                      :query      {:select   [:*]
                                                   :from     :users
                                                   :order-by [:age]}})]
          (is (= 8 (count result)))
          (is (= {:users/id 8 :users/user_name "Helen" :users/age 100}
                 (last result))))))

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
                       (utils/eval-ctx)
                       {:name      :query-action
                        :type      :collet.actions.jdbc/query
                        :selectors {'connection [:config :connection]}
                        :params    {:connection 'connection
                                    :query      {:select   [:*]
                                                 :from     :users
                                                 :order-by [:age]}}
                        :return    [[:$/cat :users/user_name]]})
              context (action {:config {:connection connection-map}
                               :state  {}})
              result  (-> context :state :query-action)]
          (is (= 3 (count result)))
          (is (= '("Alice" "Bob" "Charlie") result))))

      (testing "pass parameters to query action"
        (let [action  (collet/compile-action
                       (utils/eval-ctx)
                       {:name      :query-action
                        :type      :collet.actions.jdbc/query
                        :selectors {'connection [:config :connection]
                                    'name       [:config :name]}
                        :params    {:connection    'connection
                                    :query         {:select [:*]
                                                    :from   :users
                                                    :where  [:= :user-name 'name]}
                                    :prefix-table? false}
                        :return    [[:$/op :first]]})
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


(defn add-more-rows [conn]
  (dotimes [_i 20]
    (jdbc/execute! conn ["INSERT INTO data_types (bool_col, int_col, float_col, text_col, date_col, time_col, timestamp_col, json_col, jsonb_col, interval_col, mood_col)
                          VALUES (true, 42, 3.14, 'text', '2024-04-22', '21:01:03', '2024-04-22 21:01:03', '{\"a\": 1}', '{\"b\": 2}', '1 day 2 hours 3 minutes 4 seconds', 'happy')"])))


(deftest postgres-various-data-types-query-test
  (let [pg             (pg-container)
        port           (get (:mapped-ports pg) 5432)
        connection-map {:dbtype   "postgresql"
                        :host     "localhost"
                        :port     port
                        :dbname   "test"
                        :user     "test"
                        :password "password"}]
    (with-open [conn (jdbc/get-connection connection-map)]
      (populate-pg-data-types conn))

    (testing "query various data types"
      (let [pipeline (collet/compile-pipeline
                      {:name  :data-types
                       :deps  {:coordinates '[[org.postgresql/postgresql "42.7.3"]]
                               :requires    '[[collet.actions.jdbc-pg :as jdbc-pg]]}
                       :tasks [{:name       :query
                                :keep-state true
                                :actions    [{:name      :query-action
                                              :type      :collet.actions.jdbc/query
                                              :selectors {'connection [:config :connection]}
                                              :params    {:connection 'connection
                                                          :query      {:select [:*]
                                                                       :from   :data-types}}}]}]})
            _        @(pipeline {:connection connection-map})
            result   (:query pipeline)]
        (is (= (list #:data_types{:bool_col      true
                                  :date_col      (LocalDate/parse "2024-04-22")
                                  :float_col     3.14
                                  :id            1
                                  :int_col       42
                                  :interval_col  (Duration/parse "PT26H3M4S")
                                  :json_col      {:a 1}
                                  :jsonb_col     {:b 2}
                                  :mood_col      "happy"
                                  :text_col      "text"
                                  :time_col      (LocalTime/parse "21:01:03")
                                  :timestamp_col (LocalDateTime/parse "2024-04-22T21:01:03")}
                     #:data_types{:bool_col      false
                                  :date_col      (LocalDate/parse "2024-04-23")
                                  :float_col     3.15
                                  :id            2
                                  :int_col       43
                                  :interval_col  (Duration/parse "PT51H4M5S")
                                  :json_col      {:c 3}
                                  :jsonb_col     {:d 4}
                                  :mood_col      "sad"
                                  :text_col      "text2"
                                  :time_col      (LocalTime/parse "21:01:04")
                                  :timestamp_col (LocalDateTime/parse "2024-04-23T21:01:04")})
               result)))

      (let [pipeline (collet/compile-pipeline
                      {:name  :data-types
                       :deps  {:coordinates '[[org.postgresql/postgresql "42.7.3"]]
                               :requires    '[[collet.actions.jdbc-pg :as jdbc-pg]
                                              [next.jdbc.types :as types]]}
                       :tasks [{:name       :query
                                :keep-state true
                                :actions    [{:name      :query-action
                                              :type      :collet.actions.jdbc/query
                                              :selectors '{connection [:config :connection]
                                                           mood       [:config :mood]}
                                              :params    '{:connection connection
                                                           :query      {:select [:*]
                                                                        :from   :data-types
                                                                        :where  [:= :mood_col (types/as-other mood)]}}}]}]})
            _        @(pipeline {:connection connection-map
                                 :mood       "sad"})
            result   (:query pipeline)]
        (is (= 1 (count result)))
        (is (= "sad" (-> result first :data_types/mood_col)))))

    (testing "preserve data types"
      (let [pipeline (collet/compile-pipeline
                      {:name  :data-types
                       :deps  {:coordinates '[[org.postgresql/postgresql "42.7.3"]]
                               :requires    '[[collet.actions.jdbc-pg :as jdbc-pg]]}
                       :tasks [{:name       :query
                                :keep-state true
                                :actions    [{:name      :query-action
                                              :type      :collet.actions.jdbc/query
                                              :selectors {'connection [:config :connection]}
                                              :params    {:connection    'connection
                                                          :prefix-table? false
                                                          :query         {:select [:*]
                                                                          :from   :data-types}}}]}]})
            _        @(pipeline {:connection connection-map})
            result   (:query pipeline)]
        (are [key expected] (= expected (-> result first key))
          :bool_col true
          :mood_col "happy"
          :interval_col (Duration/parse "PT26H3M4S")
          :json_col {:a 1}
          :jsonb_col {:b 2}
          :date_col (LocalDate/parse "2024-04-22")
          :time_col (LocalTime/parse "21:01:03")
          :timestamp_col (LocalDateTime/parse "2024-04-22T21:01:03"))

        (are [key expected] (= expected (-> result second key))
          :bool_col false
          :mood_col "sad"
          :interval_col (Duration/parse "PT51H4M5S")
          :json_col {:c 3}
          :jsonb_col {:d 4}
          :date_col (LocalDate/parse "2024-04-23")
          :time_col (LocalTime/parse "21:01:04")
          :timestamp_col (LocalDateTime/parse "2024-04-23T21:01:04"))))

    ;; add 20 more records to the table
    (with-open [conn (jdbc/get-connection connection-map)]
      (add-more-rows conn))

    (testing "writing results into arrow"
      (let [pipeline (collet/compile-pipeline
                      {:name  :arrow-results
                       :deps  {:coordinates '[[org.postgresql/postgresql "42.7.3"]]
                               :requires    '[[collet.actions.jdbc-pg :as jdbc-pg]]}
                       :tasks [{:name       :query
                                :keep-state true
                                :actions    [{:name      :query-action
                                              :type      :collet.actions.jdbc/query
                                              :selectors '{connection [:config :connection]}
                                              :params    '{:connection    connection
                                                           :fetch-size    10
                                                           :prefix-table? false
                                                           :query         {:select [:id :bool_col
                                                                                    :mood_col :interval_col
                                                                                    :date_col :time_col :timestamp_col]
                                                                           :from   :data-types}}}]}]})
            _        @(pipeline {:connection connection-map})
            result   (:query pipeline)
            columns  (-> result meta :arrow-columns)]
        (is (= 3 (count result)))
        (is (utils/ds-seq? result))
        (is (= [10 10 2] (map ds/row-count result)))
        (is (= {:id            1
                :bool_col      true
                :mood_col      "happy"
                :interval_col  (Duration/parse "PT26H3M4S")
                :date_col      (LocalDate/parse "2024-04-22")
                :time_col      (LocalTime/parse "21:01:03")
                :timestamp_col (LocalDateTime/parse "2024-04-22T21:01:03")}
               (-> (first result)
                   (ds/row-at 0)
                   (collet.arrow/prep-record columns))))))

    (testing "writing results into json"
      (let [pipeline (collet/compile-pipeline
                      {:name  :arrow-results
                       :deps  {:coordinates '[[org.postgresql/postgresql "42.7.3"]]
                               :requires    '[[collet.actions.jdbc-pg :as jdbc-pg]]}
                       :tasks [{:name       :query
                                :keep-state true
                                :actions    [{:name      :query-action
                                              :type      :collet.actions.jdbc/query
                                              :selectors '{connection [:config :connection]}
                                              :params    '{:connection    connection
                                                           :fetch-size    10
                                                           :prefix-table? false
                                                           :query         {:select [:id :bool_col
                                                                                    ;; json columns couldn't be converted to arrow
                                                                                    :json_col :jsonb_col
                                                                                    :mood_col :interval_col
                                                                                    :date_col :time_col :timestamp_col]
                                                                           :from   :data-types}}}]}]})
            _        @(pipeline {:connection connection-map})
            result   (:query pipeline)]
        (is (instance? LazySeq result))
        (is (= 22 (count result)))
        (is (= {:id            1
                :bool_col      true
                :mood_col      "happy"
                :interval_col  "PT26H3M4S"
                :json_col      {:a 1}
                :jsonb_col     {:b 2}
                :date_col      "2024-04-22"
                :time_col      "21:01:03"
                :timestamp_col "2024-04-22T21:01:03"}
               (first result)))))

    (testing "writing results into json with preserving data types"
      (let [pipeline (collet/compile-pipeline
                      {:name  :arrow-results
                       :deps  {:coordinates '[[org.postgresql/postgresql "42.7.3"]]
                               :requires    '[[collet.actions.jdbc-pg :as jdbc-pg]]}
                       :tasks [{:name       :query
                                :keep-state true
                                :actions    [{:name      :query-action
                                              :type      :collet.actions.jdbc/query
                                              :selectors '{connection [:config :connection]}
                                              :params    '{:connection      connection
                                                           :fetch-size      10
                                                           :prefix-table?   false
                                                           :preserve-types? true
                                                           :query           {:select [:id :bool_col
                                                                                      ;; json columns couldn't be converted to arrow
                                                                                      :json_col :jsonb_col
                                                                                      :mood_col :interval_col
                                                                                      :date_col :time_col :timestamp_col]
                                                                             :from   :data-types}}}]}]})
            _        @(pipeline {:connection connection-map})
            result   (:query pipeline)]
        (is (instance? LazySeq result))
        (is (= 22 (count result)))
        (is (= {:id            1
                :bool_col      true
                :mood_col      "happy"
                :json_col      {:a 1}
                :jsonb_col     {:b 2}
                :interval_col  (Duration/parse "PT26H3M4S")
                :date_col      (LocalDate/parse "2024-04-22")
                :time_col      (LocalTime/parse "21:01:03")
                :timestamp_col (LocalDateTime/parse "2024-04-22T21:01:03")}
               (first result)))))

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
                       :tasks [{:name       :query
                                :keep-state true
                                :actions    [{:name      :query-action
                                              :type      :collet.actions.jdbc/query
                                              :selectors {'connection [:config :connection]}
                                              :params    {:connection 'connection
                                                          :query      {:select   [:*]
                                                                       :from     :employees
                                                                       :order-by [:id]}}
                                              :return    [[:$/cat :employees/user_name]]}]}]})
            _        (with-open [conn (jdbc/get-connection connection-map)]
                       (populate-mysql-table conn))
            _        @(pipeline {:connection connection-map})
            result   (:query pipeline)]
        (is (= 5 (count result)))
        (is (= '("Alice" "Bob" "Charlie" "David" "Eve") result))))

    (tc/stop! mysql)))


(defn create-tables [conn]
  (jdbc/execute! conn ["CREATE TABLE Users (
      user_id INT AUTO_INCREMENT PRIMARY KEY,
      username VARCHAR(50) NOT NULL,
      email VARCHAR(100),
      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      );"])
  (jdbc/execute! conn ["CREATE TABLE Products (
      product_id INT AUTO_INCREMENT PRIMARY KEY,
      product_name VARCHAR(100) NOT NULL,
      price DECIMAL(10,2),
      description TEXT
      );"])
  (jdbc/execute! conn ["CREATE TABLE Orders (
      order_id INT AUTO_INCREMENT PRIMARY KEY,
      user_id INT,
      order_date DATE,
      total_amount DECIMAL(10,2)
      );"])
  (jdbc/execute! conn ["CREATE TABLE OrderItems (
      order_item_id INT AUTO_INCREMENT PRIMARY KEY,
      order_id INT,
      product_id INT,
      quantity INT,
      price DECIMAL(10,2)
      );"]))


(defn populate-orders-data [conn]
  (jdbc/execute! conn ["INSERT INTO Users (username, email) VALUES ('Alice', 'alice@gmail.com')"])
  (jdbc/execute! conn ["INSERT INTO Users (username, email) VALUES ('Bob', 'bob@gmail.com')"])
  (jdbc/execute! conn ["INSERT INTO Users (username, email) VALUES ('Rocky', 'balboa@gmail.com')"])
  (jdbc/execute! conn ["INSERT INTO Products (product_name, price, description) VALUES ('Laptop', 1000.00, 'A laptop')"])
  (jdbc/execute! conn ["INSERT INTO Products (product_name, price, description) VALUES ('Mouse', 20.00, 'A mouse')"])
  (jdbc/execute! conn ["INSERT INTO Products (product_name, price, description) VALUES ('Keyboard', 30.00, 'A keyboard')"])
  (jdbc/execute! conn ["INSERT INTO Products (product_name, price, description) VALUES ('Monitor', 200.00, 'A monitor')"])
  (jdbc/execute! conn ["INSERT INTO Orders (user_id, order_date, total_amount) VALUES (1, '2024-04-22', 1050.00)"])
  (jdbc/execute! conn ["INSERT INTO Orders (user_id, order_date, total_amount) VALUES (2, '2024-04-23', 20.00)"])
  (jdbc/execute! conn ["INSERT INTO OrderItems (order_id, product_id, quantity, price) VALUES (1, 1, 1, 1000.00)"])
  (jdbc/execute! conn ["INSERT INTO OrderItems (order_id, product_id, quantity, price) VALUES (1, 2, 1, 20.00)"])
  (jdbc/execute! conn ["INSERT INTO OrderItems (order_id, product_id, quantity, price) VALUES (1, 3, 1, 30.00)"])
  (jdbc/execute! conn ["INSERT INTO OrderItems (order_id, product_id, quantity, price) VALUES (2, 2, 1, 20.00)"]))


(deftest join-tables-query-test
  (let [mysql          (mysql-container)
        port           (get (:mapped-ports mysql) 3306)
        connection-map {:dbtype   "mysql"
                        :host     "localhost"
                        :port     port
                        :dbname   "test"
                        :user     "test-user"
                        :password "test-pass"}]

    (testing "query with join tables"
      (let [pipeline (collet/compile-pipeline
                      {:name      :products-bought-by-users
                       :deps      {:coordinates '[[com.mysql/mysql-connector-j "9.0.0"]]}
                       :use-arrow false
                       :tasks     [{:name       :query
                                    :keep-state true
                                    :actions    [{:name      :query-action
                                                  :type      :collet.actions.jdbc/query
                                                  :selectors {'connection [:config :connection]}
                                                  :params    {:connection 'connection
                                                              :query      {:select   [:u/username
                                                                                      :p/product_name
                                                                                      [[:sum :oi/quantity] :total-quantity]
                                                                                      [[:sum [:* :oi/price :oi/quantity]] :total-amount]]
                                                                           :from     [[:Users :u]]
                                                                           :join     [[:Orders :o] [:= :u.user_id :o.user_id]
                                                                                      [:OrderItems :oi] [:= :o.order_id :oi.order_id]
                                                                                      [:Products :p] [:= :oi.product_id :p.product_id]]
                                                                           :group-by [:u.username :p/product_name]
                                                                           :order-by [:u.username :p.product_name]}
                                                              :options    {:dialect :mysql
                                                                           :quoted  false}}}]}]})
            _        (with-open [conn (jdbc/get-connection connection-map)]
                       (create-tables conn)
                       (populate-orders-data conn))
            _        @(pipeline {:connection connection-map})
            result   (:query pipeline)]
        (is (instance? LazySeq result))
        (is (= 4 (count result)))
        (is (= '({:users/username "Alice" :products/product_name "Keyboard" :total_quantity 1M :total_amount 30.00M}
                 {:users/username "Alice" :products/product_name "Laptop" :total_quantity 1M :total_amount 1000.00M}
                 {:users/username "Alice" :products/product_name "Mouse" :total_quantity 1M :total_amount 20.00M}
                 {:users/username "Bob" :products/product_name "Mouse" :total_quantity 1M :total_amount 20.00M})
               result))))

    (tc/stop! mysql)))
