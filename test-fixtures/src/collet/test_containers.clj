(ns collet.test-containers
  (:require
   [clj-test-containers.core :as tc]
   [next.jdbc :as jdbc]))

(defn postgres []
  (-> (tc/create {:image-name    "postgres:16.3"
                  :exposed-ports [5432]
                  :env-vars      {"POSTGRES_PASSWORD" "password"
                                  "POSTGRES_USER"     "test"
                                  "POSTGRES_DB"       "test"}})
      (tc/start!)))

(defn mysql []
  (-> (tc/create {:image-name    "mysql:9.0.0"
                  :exposed-ports [3306]
                  :env-vars      {"MYSQL_ROOT_PASSWORD" "rootpass"
                                  "MYSQL_DATABASE"      "test"
                                  "MYSQL_USER"          "test-user"
                                  "MYSQL_PASSWORD"      "test-pass"}})
      (tc/start!)))

(defn localstack []
  (-> (tc/create {:image-name    "localstack/localstack:4.14.0"
                  :exposed-ports [4566 4510]
                  :wait-for      {:wait-strategy :http
                                  :path          "/_localstack/health"
                                  :port          4566
                                  :method        "GET"}
                  :env-vars      {"AWS_ACCESS_KEY_ID"     "test"
                                  "AWS_SECRET_ACCESS_KEY" "test"
                                  "AWS_DEFAULT_REGION"    "eu-west-1"}})
      (tc/start!)))

(defn populate-users! [connection]
  (jdbc/execute! connection ["CREATE TABLE users (id SERIAL PRIMARY KEY, user_name TEXT, age INT)"])
  (jdbc/execute! connection ["INSERT INTO users (user_name, age) VALUES ('Alice', 30)"])
  (jdbc/execute! connection ["INSERT INTO users (user_name, age) VALUES ('Bob', 40)"])
  (jdbc/execute! connection ["INSERT INTO users (user_name, age) VALUES ('Charlie', 50)"]))

(defn populate-employees! [connection]
  (jdbc/execute! connection ["CREATE TABLE employees (id SERIAL PRIMARY KEY, user_name TEXT, position TEXT)"])
  (jdbc/execute! connection ["INSERT INTO employees (user_name, position) VALUES ('Alice', 'Manager')"])
  (jdbc/execute! connection ["INSERT INTO employees (user_name, position) VALUES ('Bob', 'Developer')"])
  (jdbc/execute! connection ["INSERT INTO employees (user_name, position) VALUES ('Charlie', 'Designer')"])
  (jdbc/execute! connection ["INSERT INTO employees (user_name, position) VALUES ('David', 'Manager')"])
  (jdbc/execute! connection ["INSERT INTO employees (user_name, position) VALUES ('Eve', 'Developer')"]))
