(ns collet.mcp-contract-test
  (:require [charred.api :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [collet.authoring-test :as client]
            [collet.runtime :as runtime]
            [collet.store :as store]
            [collet.workbench :as wb]
            [collet.workbench-test :refer [delete-tree! eventually temp-dir with-workbench]]
            [collet.workbench.api :as api]
            [collet.workbench.authoring :as authoring]
            [collet.workbench.contracts :as contracts]
            [collet.workbench.data :as data]
            [collet.workbench.diagnostics :as diagnostics]
            [collet.workbench.http :as http]
            [collet.workbench.inspection :as inspection]
            [collet.workbench.knowledge :as knowledge]
            [collet.workbench.wire :as wire]
            [malli.core :as m]))


(defn slow-value []
  (Thread/sleep 700)
  42)


(defn large-error []
  (throw (ex-info (apply str (repeat 2000 "詳細")) {})))


(defn connection [port]
  (let [init    (#'client/rpc!
                 port
                 nil
                 {:jsonrpc "2.0"
                  :id      1
                  :method  "initialize"
                  :params  {:protocolVersion "2025-11-25"
                            :capabilities    {}
                            :clientInfo      {:name "fresh-contract-client" :version "1"}}})
        session (.orElse (.firstValue (.headers init) "Mcp-Session-Id") nil)]
    (#'client/rpc! port session {:jsonrpc "2.0" :method "notifications/initialized"})
    (fn [method params]
      (#'client/rpc-body (#'client/rpc! port session {:jsonrpc "2.0" :id 2 :method method :params params})))))


(deftest runtime-errors-remain-actionable-through-mcp
  (let [path   (temp-dir)
        app    (http/start! {:port 0 :data-dir (str path)})
        rpc    (connection (:port app))
        call   (fn [name args] (:result (rpc "tools/call" {:name (str "collet_" name) :arguments args})))
        source "{:name :failed :tasks [{:name :one :actions [{:name :divide :type :clj// :params [1 0]}]}]}"
        draft  (:structuredContent (call "create_draft" {:edn source :configEdn "{:token \"private-config-value\"}"}))]
    (try
      (doseq [scope ["action" "prefix" "task"]]
        (let [command {:id                    (:id draft)
                       :revision              0
                       :configurationRevision 0
                       :instanceId            (:instance-id app)
                       :idempotencyKey        scope
                       :scope                 scope
                       :task-name             "one"}
              job     (:structuredContent (call "execute" (cond-> command (not= scope "task") (assoc :index 0))))]
          (is (eventually #(= "failed" (:status (authoring/get-execution app (:id job))))))
          (doseq [view ["summary" "result"]]
            (let [result (call "get_execution" {:id (:id job) :view view})
                  error  (get-in result [:structuredContent :error])
                  text   (get-in result [:content 0 :text])]
              (is (false? (:isError result)))
              (is (= "Divide by zero" (:message error)))
              (is (= "one" (:task error)))
              (is (= "divide" (:action error)))
              (is (= "Divide by zero" (last (:causes error))))
              (is (.contains text "Divide by zero"))
              (is (.contains text "Action: divide"))
              (is (not (.contains (json/write-json-str result) "private-config-value")))
              (is (<= (wire/bytes result) (if (= view "summary") 4096 16384)))))))
      (let [draft (:structuredContent (call "create_draft" {:edn "{:name :large-error :tasks [{:name :one :actions [{:name :fail :type :custom :fn collet.mcp-contract-test/large-error}]}]}"}))
            job   (:structuredContent (call "execute"
                                            {:id                    (:id draft)
                                             :revision              0
                                             :configurationRevision 0
                                             :instanceId            (:instance-id app)
                                             :idempotencyKey        "large-error"
                                             :scope                 "task"
                                             :task-name             "one"}))]
        (is (eventually #(= "failed" (:status (authoring/get-execution app (:id job))))))
        (let [result (call "get_execution" {:id (:id job)})]
          (is (= "failed" (get-in result [:structuredContent :status])))
          (is (= "execution-failed" (get-in result [:structuredContent :error :code])))
          (is (seq (get-in result [:structuredContent :error :causes])))
          (is (<= (wire/bytes result) 4096))))
      (finally ((:stop app)) (delete-tree! path)))))


(deftest every-tool-has-a-working-wire-contract
  (let [path   (temp-dir)
        app    (http/start! {:port 0 :data-dir (str path)})
        rpc    (connection (:port app))
        called (atom #{})
        call   (fn [name args]
                 (swap! called conj name)
                 (let [response (rpc "tools/call" {:name (str "collet_" name) :arguments args})
                       result   (:result response)
                       value    (:structuredContent result)]
                   (is (nil? (:error response)) (pr-str response))
                   (is (false? (:isError result)) (pr-str response))
                   (is (m/validate (get contracts/outputs name) value) (pr-str [name value]))
                   (when-not (#{"get_draft" "get_pipeline"} name)
                     (is (<= (wire/bytes result) 16384) name))
                   value))]
    (try
      (let [advertised (get-in (rpc "tools/list" {}) [:result :tools])
            overview   (call "get_overview" {})
            draft      (call "create_draft" (get-in overview [:example :createDraft]))
            id         (:id draft)
            instance   (:instanceId overview)
            command    {:scope                 "task"
                        :id                    id
                        :revision              0
                        :configurationRevision 0
                        :task-name             "hello"
                        :instanceId            instance
                        :idempotencyKey        "hello-once"}
            job        (call "execute" command)]
        (is (= (:id job) (:id (call "execute" command))))
        (is (eventually #(= "completed" (:status (call "get_execution" {:id (:id job) :waitMs 1000})))))
        (is (= 42 (get-in (call "get_execution" {:id (:id job) :view "result"}) [:sample :value])))
        (is (seq (:events (call "get_execution" {:id (:id job) :view "events"}))))
        (is (eventually #(= 1 (get-in (call "get_execution" {:id (:id job) :view "metrics"}) [:metrics :actions 0 :completed]))))
        (is (= (count contracts/inputs) (count advertised)))
        (is (every? :outputSchema advertised))
        (is (true? (get-in (some #(when (= "collet_execute" (:name %)) %) advertised) [:annotations :idempotentHint])))
        (let [fixture (call "save_fixture" {:id id :name "Synthetic" :edn "{:mocks {:value {:value 7}}}"})
              key     (select-keys fixture [:fixtureId :fixtureRevision])
              job     (call "execute"
                            (merge (dissoc command :configurationRevision)
                                   key
                                   {:idempotencyKey "fixture-once"}))]
          (is (= [fixture] (:fixtures (call "list_fixtures" {:id id}))))
          (is (string? (:edn (call "get_fixture" (assoc (dissoc key :fixtureRevision) :id id)))))
          (is (eventually #(= "completed" (:status (call "get_execution" {:id (:id job) :waitMs 1000})))))
          (let [result (call "get_execution" {:id (:id job) :view "result"})]
            (is (= "fixture" (:executionMode result)))
            (is (= ["value"] (:mockedActions result)))
            (is (= 7 (get-in result [:sample :value]))))
          (let [suite (call "run_fixture_suite"
                            {:instanceId     instance
                             :idempotencyKey "fixture-suite"
                             :cases          [(merge key {:id id :revision 0 :scope "task" :task-name "hello"})]})]
            (is (eventually #(= "completed" (:status (call "get_execution" {:id (:id suite) :waitMs 1000})))))))
        (call "list_drafts" {})
        (call "get_draft" {:id id})
        (call "read_documentation" {:id "pipeline"})
        (call "read_documentation" {:query "setup" :limit 3})
        (is (str/includes? (:text (call "read_documentation" {:id "generic-actions" :section "sparql"})) ":jsonld"))
        (is (str/includes? (:text (call "read_documentation" {:id "dash-images" :section "migration-to-the-generic-actions"})) ":jev :endpoint"))
        (call "list_actions" {:query "daily totals"})
        (call "describe_action" {:type "clj/slurp"})
        (doseq [type ["collet.actions.llm/request" "collet.actions.sparql/format"
                      "collet.actions.sparql/query" "collet.actions.sparql/update"]]
          (is (= "collet://docs/generic-actions" (:documentation (call "describe_action" {:type type})))))
        (call "validate" {:kind "action" :edn "{:name :x :type :clj/identity :params [1]}"})
        (let [updated (call "update_draft"
                            {:id       id
                             :revision 0
                             :op       "add-task"
                             :taskEdn  "{:name :slow :actions [{:name :wait :type :custom :fn collet.mcp-contract-test/slow-value}]}"
                             :summary  "Add a slow test task"})
              source  (:edn (call "get_draft" {:id id}))]
          (is (= 1 (:revision updated)))
          (is (.contains source ":slow"))
          (is (not= "Add a slow test task" (:summary updated))))
        (let [bad (rpc "tools/call" {:name "collet_update_draft" :arguments {:id id :revision 0 :edn "{}"}})]
          (is (true? (get-in bad [:result :isError])) (pr-str bad))
          (is (= 1 (get-in bad [:result :structuredContent :error :currentRevision])))
          (is (string? (get-in bad [:result :structuredContent :error :code]))))
        (let [bad (rpc "tools/call" {:name "collet_update_draft" :arguments {:id id :revision 1 :op "add-task"}})]
          (is (true? (get-in bad [:result :isError])) (pr-str bad))
          (is (= "invalid-arguments" (get-in bad [:result :structuredContent :error :code])))
          (is (= [["taskEdn"]] (mapv :path (get-in bad [:result :structuredContent :error :diagnostics])))))
        (call "get_draft_history" {:id id :limit 1})
        (call "restore_draft" {:id id :revision 1 :fromRevision 0})
        (call "restore_draft" {:id id :revision 2 :fromRevision 1})
        (call "set_configuration" {:id id :configurationRevision 0 :configEdn "{:label 1}"})
        (call "apply_draft" {:id id :revision 3 :configurationRevision 1})
        (call "inspect_context" {:id id :task-name "hello"})
        (let [saved   (call "save_draft" {:id id :revision 3})
              _ (call "get_pipeline" {:name "hello" :version (:savedVersion saved)})
              _ (call "list_pipelines" {})
              run-job (call "execute"
                            {:scope          "pipeline"
                             :pipeline       "hello"
                             :version        (:savedVersion saved)
                             :configuration  {:source "draft" :id id :revision 1}
                             :instanceId     instance
                             :idempotencyKey "saved-once"})
              run-id  (atom nil)]
          (is (eventually #(reset! run-id (:runId (call "get_execution" {:id (:id run-job)})))))
          (call "control_run" {:id @run-id :command "pause"})
          (call "control_run" {:id @run-id :command "resume"})
          (is (eventually #(= "completed" (:status (call "get_run" {:id @run-id})))))
          (call "get_run" {:id @run-id :view "metrics"})
          (call "list_runs" {}))
        (call "delete_pipeline_version" {:name "hello" :version 1})
        (let [saved (call "save_draft" {:id id :revision 3})]
          (call "delete_pipeline" {:name "hello" :latestVersion (:savedVersion saved)}))
        (call "delete_draft" {:id id :revision 3})
        (is (= (set (keys contracts/inputs)) @called)))
      (finally ((:stop app)) (delete-tree! path)))))


(deftest passive-errors-and-bounded-knowledge
  (is (seq (:sections (knowledge/documentation {:id "pipeline"}))))
  (is (not (.contains (:example (knowledge/describe "clj/slurp")) "identity")))
  (is (nil? (:example (knowledge/describe "clj/partition"))))
  (doseq [backoff ["10" "[10 10]" "[20 10]" "[0 10]" "[10 100 1]" "[10 100 ##Inf]"]]
    (is (false? (:valid (knowledge/check {:kind "task" :edn (str "{:name :x :retry {:backoff-ms " backoff "} :actions [{:name :v :type :clj/identity :params [1]}]} ")}))) backoff))
  (doseq [backoff ["[10 100]" "[10 100 2]" "[10 100 2.0]"]]
    (is (:valid (knowledge/check {:kind "task" :edn (str "{:name :x :retry {:backoff-ms " backoff "} :actions [{:name :v :type :clj/identity :params [1]}]} ")})) backoff))
  (let [validation (knowledge/check {:kind "action" :edn "{:name :x :type :slicer :selectors {rows [:inputs :source]} :params {:sequence rows :apply [[:fold {:by :currency :columns {:total [:summ :amount]}}]]}}"})]
    (is (false? (:valid validation)))
    (is (every? #(some #{:columns} (:path %)) (:errors validation))))
  (let [value {:sample {:value (vec (repeat 100 (apply str (repeat 1000 "x"))))}}]
    (is (<= (wire/bytes (wire/result (wire/fit value 4096))) 4096)))
  (doseq [name (keys contracts/inputs)]
    (is (= "object" (:type (contracts/input-schema name))))))


(deftest retained-events-are-bounded-and-cursors-report-gaps
  (let [app {:instance-id "test-instance" :closed? (atom false) :diagnostics (diagnostics/state) :diagnostic-signal (atom (promise))}]
    (dotimes [_ 1100]
      (diagnostics/observe! app {:execution-id "one" :task :task :action :action :event "action-completed" :durationMs 1.0}))
    (let [page (diagnostics/events app ["one"] {})]
      (is (= 20 (count (:events page))))
      (is (= 100 (:dropped page)))
      (is (:gap page))
      (is (:hasMore page))
      (is (= 1100 (get-in (diagnostics/metrics app ["one"] {}) [:actions 0 :completed])))
      (is (false? (:gap (diagnostics/events app ["one"] {:cursor (:nextCursor page)})))))
    (is (thrown-with-msg? Exception #"another application" (diagnostics/events app ["one"] {:cursor "old:0"})))
    (is (thrown-with-msg? Exception #"Invalid event cursor" (diagnostics/events app ["one"] {:cursor "test-instance:-1"})))
    (with-redefs [diagnostics/total-bytes 3000]
      (dotimes [i 100]
        (diagnostics/observe! app {:execution-id (str i) :event "action-start" :message (apply str (repeat 500 "x"))})))
    (is (<= (:bytes @(:diagnostics app)) 3000))))


(deftest small-event-pages-always-advance
  (let [app {:instance-id "small-page" :closed? (atom false) :diagnostics (diagnostics/state) :diagnostic-signal (atom (promise))}]
    (dotimes [_ 2]
      (diagnostics/observe! app {:execution-id "one" :event "action-failed" :level "error" :message (apply str (repeat 1000 "x"))}))
    (let [page      (diagnostics/events app ["one"] {:maxBytes 4096})
          next-page (diagnostics/events app ["one"] {:maxBytes 4096 :cursor (:nextCursor page)})]
      (is (= [1] (mapv :sequence (:events page))))
      (is (:hasMore page))
      (is (= [2] (mapv :sequence (:events next-page))))
      (is (false? (:hasMore next-page)))
      (is (<= (wire/bytes (wire/result (wire/fit page 4096))) 4096)))))


(deftest saved-run-completion-wakes-long-poll
  (with-workbench [app]
                  (let [draft      (authoring/create! app {:edn "{:name :wake-client :tasks [{:name :hello :actions [{:name :value :type :clj/identity :params [42]}]}]}"})
                        saved      (authoring/save! app (:id draft) {:revision 0 :response :summary})
                        finalizing (promise)
                        release    (promise)
                        inspected  (promise)
                        finalize   @#'runtime/finalize-terminal-run!
                        read-run   inspection/read-run]
                    (with-redefs [runtime/finalize-terminal-run! (fn [& args]
                                                                   (deliver finalizing true)
                                                                   @release
                                                                   (apply finalize args))]
                      (try
                        (let [job (authoring/execute! app
                                                      nil
                                                      {:scope          "pipeline"
                                                       :pipeline       "wake-client"
                                                       :version        (:savedVersion saved)
                                                       :configuration  {:source "empty"}
                                                       :instanceId     (:instance-id app)
                                                       :idempotencyKey "wake-once"})]
                          (is (= true (deref finalizing 5000 nil)))
                          (with-redefs [inspection/read-run (fn [& args]
                                                              (let [result (apply read-run args)]
                                                                (deliver inspected true)
                                                                result))]
                            (let [waiting (future (authoring/get-execution app (:id job) {:waitMs 5000 :afterSequence Long/MAX_VALUE}))]
                              (try
                                (is (= true (deref inspected 2000 nil)))
                                (deliver release true)
                                (is (= "completed" (:status (deref waiting 2000 {}))))
                                (finally (future-cancel waiting))))))
                        (finally (deliver release true)))))))


(def retry-attempts (atom 0))


(defn retry-rows []
  (if (= 1 (swap! retry-attempts inc)) (throw (ex-info "try again" {})) [{:id 1} {:id 2}]))


(deftest run-metrics-persist-without-detailed-events
  (reset! retry-attempts 0)
  (let [path (temp-dir)
        app  (wb/open! {:data-dir (str path)})]
    (try
      (let [d     (authoring/create!
                   app
                   {:edn "{:name :durable-metrics :tasks [{:name :rows :keep-state true :retry {:max-retries 1 :backoff-ms [1 5 2]} :actions [{:name :rows :type :custom :fn collet.mcp-contract-test/retry-rows}]}]}"})
            saved (authoring/save! app (:id d) {:revision 0})
            job   (authoring/execute! app
                                      nil
                                      {:scope          "pipeline"
                                       :pipeline       "durable-metrics"
                                       :version        (:savedVersion saved)
                                       :configuration  {:source "empty"}
                                       :instanceId     (:instance-id app)
                                       :idempotencyKey "metrics"})]
        (is (eventually #(= "completed" (:status (authoring/get-execution app (:id job) {:waitMs 1000})))))
        (let [run-id  (:runId (authoring/get-execution app (:id job)))
              metrics (:metrics (inspection/read-run app run-id {:view "metrics"}))]
          (is (= 1 (:retryCount metrics)))
          (is (eventually #(= 2 (get-in (inspection/read-run app run-id {:view "metrics"}) [:metrics :actions 0 :invocations]))))
          (is (= 2 (get-in metrics [:tasks 0 :records])))
          (is (pos? (get-in metrics [:tasks 0 :bytes])))
          (is (= 2 (get-in (inspection/read-run app run-id {:view "result" :task-name "rows"}) [:sample :shown])))
          (wb/close! app)
          (let [reopened (wb/open! {:data-dir (str path)})]
            (try
              (is (= (:tasks metrics) (get-in (inspection/read-run reopened run-id {:view "metrics"}) [:metrics :tasks])))
              (is (false? (:available (inspection/read-run reopened run-id {:view "events"}))))
              (is (= "completed" (:status (inspection/read-run reopened run-id {}))))
              (finally (wb/close! reopened))))))
      (finally (wb/close! app) (delete-tree! path)))))


(defn documentation-text [id section]
  (loop [cursor nil
         text   ""]
    (let [page (knowledge/documentation (cond-> {:id id :full true :maxBytes 32768}
                                          section (assoc :section section)
                                          cursor (assoc :cursor cursor)))
          text (str text (:text page))]
      (if-let [next-cursor (:nextCursor page)]
        (recur next-cursor text)
        text))))


(defn documentation-examples [id section]
  (vec
   (mapcat
    (fn [[_ text]]
      (with-open [reader (java.io.PushbackReader. (java.io.StringReader. text))]
        (let [eof (Object.)]
          (loop [forms []]
            (let [form (edn/read {:eof eof :readers {'rgx re-pattern}} reader)]
              (if (identical? eof form) forms (recur (conj forms form))))))))
    (re-seq #"```clojure\n([\s\S]*?)```" (documentation-text id section)))))


(deftest exposed-examples-follow-current-action-contracts
  (doseq [[id section] [["actions" nil] ["extensibility" nil] ["recipes" nil] ["integrations" nil]
                        ["dependencies" nil] ["pipeline" "pipeline-specification"] ["pipeline" "actions"]]
          form         (documentation-examples id section)
          :let         [kind (cond (:tasks form) "pipeline"
                                   (:actions form) "task"
                                   (:type form) "action")]
          :when        kind]
    (let [form   (cond-> form (not (:name form)) (assoc :name :example))
          result (knowledge/check {:kind kind :edn (pr-str form)})]
      (is (:valid result) (pr-str [id form result]))))
  (doseq [form (documentation-examples "conditions" nil)]
    (is (:valid (knowledge/check {:kind "condition" :edn (data/source form)})) (pr-str form)))
  (doseq [form  (documentation-examples "selectors" nil)
          :when (:return form)]
    (is (:valid (knowledge/check {:kind "selector" :edn (data/source (:return form))})) (pr-str form)))
  (doseq [action (knowledge/actions)]
    (is (:valid (knowledge/check {:kind "action" :edn (:example action)})) (str (:type action)))))


(deftest pinned-recipes-preserve-independent-versions-and-unresolved-is-explicit
  (let [catalogue-var (ns-resolve 'collet.workbench.knowledge 'catalogue)
        original      (deref (deref catalogue-var))
        manifest      {:build    {:version "1.2.3" :distribution "source"}
                       :packages {"io.velio/collet-action-http" {:version "2.3.4"}
                                  "io.velio/collet-action-s3"   {:version "3.4.5"}}}]
    (doseq [compatibility [manifest nil]]
      (with-redefs-fn {catalogue-var (delay (assoc original :compatibility compatibility))}
        (fn []
          (doseq [[type version] [["collet.actions.http/request" "2.3.4"] ["collet.actions.s3/sink" "3.4.5"]]]
            (let [action (knowledge/describe type)]
              (is (contains? action :version))
              (is (contains? action :dependencyEdn))
              (if compatibility
                (do
                  (is (= version (:version action)))
                  (is (= {:coordinates [[(symbol (:coordinate action)) version]]
                          :requires    [[(symbol (:namespace action))]]}
                         (edn/read-string (:dependencyEdn action))))
                  (is (not (str/includes? (:dependencyEdn action) "VERSION"))))
                (do
                  (is (nil? (:version action)))
                  (is (nil? (:dependencyEdn action)))
                  (is (str/includes? (:distribution action) "bb workbench:prepare")))))))))))


(deftest packaged-manifest-takes-precedence-over-prepared-source
  (let [path          (temp-dir)
        packaged      (.toFile (.resolve path "packaged.edn"))
        prepared      (.toFile (.resolve path "prepared.edn"))
        original-file io/file
        source-path   "target/workbench-knowledge/collet/knowledge/compatibility.edn"
        manifest      {:build    {:version "1.2.3" :distribution "source"}
                       :packages {"io.velio/collet-action-http" {:version "2.3.4"}}}]
    (try
      (spit prepared (pr-str manifest))
      (spit packaged (pr-str (assoc-in manifest [:build :distribution] "release")))
      (doseq [packaged? [true false]]
        (with-redefs [io/resource (fn [resource] (when (and packaged? (= resource "collet/knowledge/compatibility.edn")) (.toURL (.toURI packaged))))
                      io/file     (fn [& args] (if (= source-path (last args)) prepared (apply original-file args)))]
          (let [found (#'knowledge/compatibility)]
            (is (= (:packages manifest) (:packages found)))
            (is (= (if packaged? "release" "source") (get-in found [:build :distribution]))))))
      (finally (delete-tree! path)))))


(deftest mcp-guidance-and-s3-endpoint-schema-are-visible-to-agents
  (let [path (temp-dir)
        app  (http/start! {:port 0 :data-dir (str path)})]
    (try
      (let [init         (#'client/rpc-body
                          (#'client/rpc!
                           (:port app)
                           nil
                           {:jsonrpc "2.0"
                            :id      1
                            :method  "initialize"
                            :params  {:protocolVersion "2025-11-25"
                                      :capabilities    {}
                                      :clientInfo      {:name "portability-guide" :version "1"}}}))
            instructions (get-in init [:result :instructions])
            overview     (pr-str (api/overview app))
            guide        (documentation-text "getting-started" nil)
            description  (pr-str (knowledge/describe "collet.actions.s3/sink"))]
        (doseq [text [instructions overview guide description]]
          (is (str/includes? text "classpath-present"))
          (is (str/includes? text ":coordinates"))
          (is (str/includes? text ":requires"))
          (is (str/includes? text ":imports")))
        (doseq [text [instructions guide]]
          (is (str/includes? text "qualified"))
          (is (str/includes? text "closures")))
        (let [rpc    (connection (:port app))
              action (get-in (rpc "tools/call" {:name "collet_describe_action" :arguments {:type "collet.actions.s3/sink"}}) [:result :structuredContent])
              schema (pr-str (:params-schema action))
              source {:name   :upload
                      :type   :collet.actions.s3/sink
                      :params {:aws-creds {:aws-region        "auto"
                                           :aws-key           "key"
                                           :aws-secret        "secret"
                                           :endpoint-override {:protocol :https :hostname "account.r2.cloudflarestorage.com" :port 443}}
                               :bucket    "reports"
                               :file-name "records.json"
                               :input     [{:n 1}]
                               :format    :json}}]
          (is (str/includes? schema "endpoint-override"))
          (is (= ["map" ["bucket" "string"] ["key" "string"] ["etag" {:optional true} "string"]]
                 (:result-schema action)))
          (is (str/includes? (:notes action) "opaque"))
          (is (str/includes? (:notes (knowledge/describe "collet.actions.http/request")) ":basic-auth [username password]"))
          (is (:valid (knowledge/check {:kind "action" :edn (pr-str source)})))
          (doseq [[key value] [[:protocol :ftp] [:hostname 42] [:port "443"]]]
            (is (false? (:valid (knowledge/check {:kind "action" :edn (pr-str (assoc-in source [:params :aws-creds :endpoint-override key] value))})))))))
      (finally ((:stop app)) (delete-tree! path)))))


(deftest cleanup-purges-associated-execution-caches-but-preserves-draft-source
  (with-workbench [app]
                  (let [source  "; raw draft stays intact\n{:name :delete-caches :tasks [{:name :one :keep-state true :actions [{:name :value :type :clj/identity :params [42]}]}]}"
                        draft   (authoring/create! app {:edn source})
                        saved   (authoring/save! app (:id draft) {:revision 0})
                        command {:scope          "pipeline"
                                 :pipeline       "delete-caches"
                                 :version        (:savedVersion saved)
                                 :configuration  {:source "empty"}
                                 :instanceId     (:instance-id app)
                                 :idempotencyKey "delete-caches-once"}
                        job     (authoring/execute! app nil command)]
                    (is (eventually #(= "completed" (:status (authoring/get-execution app (:id job))))))
                    (let [finished (authoring/get-execution app (:id job))
                          id       (:runId finished)
                          uuid     (java.util.UUID/fromString id)]
                      (is (contains? @(:handles app) uuid))
                      (is (= {:deleted true :name "delete-caches" :versionsDeleted 1 :runsDeleted 1}
                             (api/delete-pipeline! app "delete-caches" {:version 1})))
                      (is (nil? (store/get-run (:store (:ctx app)) uuid)))
                      (is (not (contains? @(:handles app) uuid)))
                      (is (not (contains? @(:executions app) (:id job))))
                      (is (nil? (get-in @(:diagnostics app) [:groups id])))
                      (is (= 404 (try (authoring/get-execution app (:id job)) nil (catch Exception e (:status (ex-data e))))))
                      (is (= source (:edn (authoring/read-draft app (:id draft) {}))))
                      (is (= 404 (try (authoring/execute! app nil command) nil (catch Exception e (:status (ex-data e))))))))))


(deftest cleanup-waits-for-saved-run-preparation-to-settle
  (with-workbench [app]
                  (let [draft   (authoring/create! app {:edn "{:name :delete-preparing :tasks [{:name :one :actions [{:name :value :type :clj/identity :params [42]}]}]}"})
                        _ (authoring/save! app (:id draft) {:revision 0})
                        ready   (promise)
                        release (promise)
                        start   wb/start-saved-run!]
                    (try
                      (with-redefs [wb/start-saved-run! (fn [& args]
                                                          (let [run (apply start args)]
                                                            (deliver ready run)
                                                            @release
                                                            run))]
                        (let [job  (authoring/execute! app
                                                       nil
                                                       {:scope          "pipeline"
                                                        :pipeline       "delete-preparing"
                                                        :version        1
                                                        :configuration  {:source "empty"}
                                                        :instanceId     (:instance-id app)
                                                        :idempotencyKey "delete-preparing-once"})
                              run  (deref ready 5000 nil)
                              uuid (java.util.UUID/fromString (:id run))]
                          (is (eventually #(realized? (get-in @(:runs (:ctx app)) [uuid :completion]))))
                          (is (= 409 (try (api/delete-pipeline! app "delete-preparing" {:version 1}) nil (catch Exception e (:status (:error (wire/error e)))))))
                          (is (store/get-run (:store (:ctx app)) uuid))
                          (deliver release true)
                          (is (eventually #(realized? (:settled (get @(:executions app) (:id job))))))
                          (is (:deleted (api/delete-pipeline! app "delete-preparing" {:version 1})))
                          (is (not (contains? @(:executions app) (:id job))))))
                      (finally (deliver release true))))))
