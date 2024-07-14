(ns collet.actions.jdbc-test
  (:require
   [clojure.test :refer :all]
   [next.jdbc :as jdbc]
   [clj-test-containers.core :as tc]
   [collet.core :as collet]
   [collet.actions.jdbc :as sut])
  (:import
   [clojure.lang LazySeq]))


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
  (let [pg   (pg-container)
        port (get (:mapped-ports pg) 5432)]
    (with-open [conn (jdbc/get-connection
                      {:dbtype   "postgresql"
                       :host     "localhost"
                       :port     port
                       :dbname   "test"
                       :user     "test"
                       :password "password"})]
      (populate-table conn)

      (testing "make query with HoneySQL syntax"
        (let [result (sut/make-query {:connection conn
                                      :query      {:select   [:user-name :age]
                                                   :from     :users
                                                   :order-by [:age]}})]
          (is (instance? LazySeq result))
          (is (= 3 (count result)))
          (is (= {:users/user_name "Alice" :users/age 30}
                 (first result)))))

      (testing "make query with plain SQL string"
        (let [result (sut/make-query {:connection conn
                                      :query      ["SELECT * FROM users ORDER BY age"]})]
          (is (instance? LazySeq result))
          (is (= 3 (count result)))
          (is (= {:users/id 1 :users/user_name "Alice" :users/age 30}
                 (first result)))))

      (testing "use parameters in SQL string"
        (let [result (sut/make-query {:connection conn
                                      :query      ["SELECT * FROM users WHERE user_name = ?" "Bob"]})]
          (is (= 1 (count result)))
          (is (= {:users/id 2 :users/user_name "Bob" :users/age 40}
                 (first result))))))

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