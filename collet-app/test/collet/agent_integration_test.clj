(ns collet.agent-integration-test
  (:require [charred.api :as json]
            [clj-test-containers.core :as tc]
            [clojure.test :refer [deftest is]]
            [collet.actions.http]
            [collet.actions.jdbc]
            [collet.authoring-test :as client]
            [collet.test-containers :as containers]
            [collet.workbench-test :refer [delete-tree! eventually request! temp-dir]]
            [collet.workbench.http :as http]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set]
            [ring.adapter.jetty :as jetty]))


(def orders
  [{:day "2026-01-01" :currency "eur" :amount 5 :status "paid"}
   {:day "2026-01-01" :currency "EUR" :amount 10 :status "paid"}
   {:day "2026-01-01" :currency "usd" :amount 7 :status "paid"}
   {:day "2026-01-01" :currency "eur" :amount 99 :status "cancelled"}])


(def pipeline
  '{:name :agent-orders
    :deps {:requires [[collet.actions.http] [collet.actions.jdbc] [clojure.string]]}
    :tasks
    [{:name       :source
      :keep-state true
      :actions    [{:name      :fetch
                    :type      :collet.actions.http/request
                    :selectors {url [:config :orders-url]}
                    :params    {:url url :as :json}
                    :return    [:body]}]}
     {:name       :normalize
      :inputs     [:source]
      :keep-state true
      :actions    [{:name      :paid
                    :type      :slicer
                    :selectors {rows [:inputs :source]}
                    :params    {:sequence rows
                                :apply    [[:filter {:by :status :predicate #{"paid"}}]
                                           [:map {:with (fn [row] (update row :currency clojure.string/upper-case))}]]}}]}
     {:name       :totals
      :inputs     [:normalize]
      :keep-state true
      :actions    [{:name      :sum
                    :type      :slicer
                    :selectors {rows [:inputs :normalize]}
                    :params    {:sequence rows :apply [[:fold {:by [:day :currency] :columns {:total [:sum :amount]}}]]}}]}
     {:name     :write
      :inputs   [:totals]
      :parallel {:items [:inputs :totals]}
      :actions  [{:name      :insert
                  :type      :collet.actions.jdbc/execute
                  :selectors {db       [:config :database]
                              day      [:$parallel/item :day]
                              currency [:$parallel/item :currency]
                              total    [:$parallel/item :total]}
                  :params    {:connection db
                              :statement  ["INSERT INTO daily_totals(day, currency, total) VALUES (?, ?, ?) ON CONFLICT (day,currency) DO UPDATE SET total=EXCLUDED.total"
                                           day currency total]}}]}]})


(deftest ^:integration agents-build-inspect-correct-handoff-and-run-orders-to-postgres
  (let [pg         (containers/postgres)
        database   {:dbtype   "postgresql"
                    :host     "localhost"
                    :port     (get (:mapped-ports pg) 5432)
                    :dbname   "test"
                    :user     "test"
                    :password "password"}
        requests   (atom 0)
        source     (jetty/run-jetty (fn [_]
                                      (swap! requests inc)
                                      {:status 200 :headers {"Content-Type" "application/json"} :body (json/write-json-str orders)})
                                    {:port 0 :host "127.0.0.1" :join? false})
        source-url (str "http://127.0.0.1:" (.getLocalPort (first (.getConnectors source))) "/orders")
        path       (temp-dir)
        app        (http/start! {:port 0 :data-dir (str path)})
        port       (:port app)]
    (try
      (jdbc/execute! database ["CREATE TABLE daily_totals(day text, currency text, total bigint, PRIMARY KEY(day,currency))"])
      (let [init    (#'client/rpc!
                     port
                     nil
                     {:jsonrpc "2.0"
                      :id      1
                      :method  "initialize"
                      :params  {:protocolVersion "2025-11-25"
                                :capabilities    {}
                                :clientInfo      {:name "orders-agent" :version "1"}}})
            session (.orElse (.firstValue (.headers init) "Mcp-Session-Id") nil)
            _ (#'client/rpc! port session {:jsonrpc "2.0" :method "notifications/initialized"})
            call    (fn [tool args]
                      (let [response (#'client/rpc-body
                                      (#'client/rpc!
                                       port
                                       session
                                       {:jsonrpc "2.0"
                                        :id      2
                                        :method  "tools/call"
                                        :params  {:name (str "collet_" tool) :arguments args}}))]
                        (when (or (:error response) (get-in response [:result :isError]))
                          (throw (ex-info "MCP command failed" response)))
                        (get-in response [:result :structuredContent])))
            draft   (call "create_draft"
                          {:edn       (pr-str (update pipeline :tasks #(subvec % 0 1)))
                           :configEdn (pr-str {:orders-url source-url :database database})})
            id      (:id draft)
            execute (fn [revision task]
                      (let [job (call "execute"
                                      {:id                    id
                                       :revision              revision
                                       :configurationRevision 0
                                       :instanceId            (:instanceId draft)
                                       :idempotencyKey        (str (random-uuid))
                                       :scope                 "task"
                                       :task-name             task})]
                        (is (eventually #(#{"completed" "failed"} (:status (call "get_execution" {:id (:id job)})))))
                        (let [done (call "get_execution" {:id (:id job) :view "result" :task-name task})]
                          (is (= "completed" (:status done)) (pr-str done))
                          done)))]
        (is (:valid (call "validate" {:kind "pipeline" :edn (pr-str pipeline)})))
        (is (= 0 @requests) "Catalogue and validation do not fetch data")
        (is (= 4 (get-in (execute 0 "source") [:sample :shown])))
        (is (= 1 @requests))
        (is (false? (:valid (call "validate" {:kind "action" :edn "{:name :bad :type :collet.actions.jdbc/execute :params {:connection 42}}"}))))
        (call "update_draft" {:id id :revision 0 :edn (pr-str pipeline)})
        (execute 1 "normalize")
        (let [inputs (call "inspect_context" {:id id :task-name "totals" :index 0})]
          (is (:fresh inputs))
          (is (seq (:fields inputs)))
          (is (not (.contains (json/write-json-str inputs) "password"))))
        (execute 1 "totals")
        (let [human (request! port
                              "POST"
                              (str "/api/sessions/" id "/source")
                              {:revision 1 :edn (str "; checked by a human\n" (pr-str pipeline))}
                              true)]
          (is (= 200 (.statusCode human)))
          (is (= 2 (:revision (call "get_draft" {:id id})))))
        (let [saved        (call "save_draft" {:id id :revision 2})
              count-before (jdbc/execute-one! database ["SELECT count(*) AS n FROM daily_totals"])]
          (is (= 0 (:n count-before)) "Saving does not execute the sink")
          (let [command {:configuration  {:source "draft" :id id :revision 0}
                         :pipeline       "agent-orders"
                         :version        (:savedVersion saved)
                         :scope          "pipeline"
                         :instanceId     (:instanceId draft)
                         :idempotencyKey "saved-orders-once"}
                job     (call "execute" command)]
            (is (= (:id job) (:id (call "execute" command))))
            (is (eventually #(#{"completed" "failed"} (:status (call "get_execution" {:id (:id job)})))))
            (let [done (call "get_execution" {:id (:id job)})]
              (is (= "completed" (:status done)) (pr-str done))
              (is (= (:savedVersion saved) (get-in (call "get_run" {:id (:runId done)}) [:version]))))))
        (is (= [{:currency "EUR" :total 15} {:currency "USD" :total 7}]
               (jdbc/execute! database
                              ["SELECT currency,total FROM daily_totals ORDER BY currency"]
                              {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps})))
        (is (= 2 @requests) "One authoring fetch and one fresh normal run"))
      (finally ((:stop app)) (.stop source) (tc/stop! pg) (delete-tree! path)))))
