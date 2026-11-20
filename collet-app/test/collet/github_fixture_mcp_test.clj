(ns collet.github-fixture-mcp-test
  (:require [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [collet.mcp-contract-test :refer [connection]]
            [collet.workbench-test :refer [delete-tree! eventually temp-dir]]
            [collet.workbench.http :as http])
  (:import [java.nio.file Files LinkOption Path]))


(def ^:private config {:org-name "fixture-org" :gh-token "synthetic-github-token"})


(def ^:private repo-url "https://api.github.com/orgs/fixture-org/repos")


(def ^:private repo-query {:per_page 100})


(def ^:private pulls-query {:per_page 100 :state "closed" :sort "created" :direction "asc"})


(defn- route [url query & responses]
  {:request   {:method :get :url url :query-params query}
   :responses (vec responses)})


(defn- page [rows & [next-url]]
  (cond-> {:status 200 :json rows}
    next-url (assoc :headers {"Link" (str "<" next-url ">; rel=\"next\"")})))


(defn- pull [id user created closed merged]
  {:id id :user {:login user} :created_at created :closed_at closed :merged_at merged})


(def ^:private reports
  {"total-prs-by-member.csv"   {:header ["user-id" "pulls-count"] :rows [["ada" "3"] ["lin" "1"]]}
   "monthly-prs-by-member.csv" {:header ["user-id" "pulls-count" "year-month"]
                                :rows   [["ada" "2" "2026-02"] ["ada" "1" "2026-01"] ["lin" "1" "2026-02"]]}
   "merge-ratio-by-member.csv" {:header ["user-id" "merge-ratio"] :rows [["ada" "67"] ["lin" "0"]]}
   "average-time-to-merge.csv" {:header ["user-id" "average-ttm"] :rows [["ada" "30"]]}})


(def ^:private populated
  (let [alpha      (str "https://api.github.com/repos/fixture-org/alpha/pulls")
        beta       (str "https://api.github.com/repos/fixture-org/beta/pulls")
        empty-repo (str "https://api.github.com/repos/fixture-org/empty/pulls")
        february   "2026-02-01T00:00:00Z"
        merged     (pull 101 "ada" "2026-01-31T23:59:00Z" february february)
        same-time  (pull 102 "ada" february february february)
        closed     (pull 103 "ada" "2026-01-05T00:00:00Z" "2026-01-06T00:00:00Z" nil)
        lin        (pull 104 "lin" february "2026-02-02T00:00:00Z" nil)]
    {:config     config
     :allow      #{:report-fields :pull-request-rows :report}
     :temp-files #{:write-report}
     :http       {:list-repositories         [(route repo-url
                                                     repo-query
                                                     {:status 503 :headers {"Retry-After" "0"}}
                                                     (page [{:name "alpha"}] (str repo-url "?page=2")))
                                              (route (str repo-url "?page=2") repo-query (page [{:name "empty"} {:name "beta"}]))]
                  :list-closed-pull-requests [(route alpha pulls-query (page [merged same-time] (str alpha "?page=2")))
                                              (route (str alpha "?page=2") pulls-query (page [closed]))
                                              (route empty-repo pulls-query (page []))
                                              (route beta pulls-query (page [merged lin]))]}
     :expect     {:rows {:repositories        [{:name "alpha"} {:name "empty"} {:name "beta"}]
                         :total-prs-by-member [{:user-id "ada" :pulls-count 3} {:user-id "lin" :pulls-count 1}]}
                  :csv  reports}}))


(defn- call-tool [rpc tool args]
  (let [reply (rpc "tools/call" {:name (str "collet_" tool) :arguments args})]
    (is (nil? (:error reply)) (pr-str reply))
    (get-in reply [:result :structuredContent])))


(defn- finished [call id]
  (let [result (atom nil)]
    (is (eventually (fn []
                      (reset! result (call "get_execution" {:id id :waitMs 1000}))
                      (#{"completed" "failed" "stopped"} (:status @result)))))
    @result))


(defn- save-case [call id name fixture]
  (select-keys (call "save_fixture" {:id id :name name :edn (pr-str fixture)}) [:fixtureId :fixtureRevision]))


(defn- assert-files [details expected]
  (let [dir (Path/of ^String (:outputDirectory details) (make-array String 0))]
    (is (.startsWith dir (Path/of (System/getProperty "java.io.tmpdir") (make-array String 0))))
    (is (= (set (keys expected)) (set (map :name (:files details)))))
    (doseq [{:keys [name path bytes]} (:files details)]
      (let [file (Path/of ^String path (make-array String 0))
            {:keys [header rows]} (get expected name)]
        (is (= dir (.getParent file)))
        (is (Files/isRegularFile file (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))
        (is (= bytes (Files/size file)))
        (with-open [reader (io/reader (.toFile file))]
          (let [[actual-header & actual-rows] (doall (json/read-csv reader))]
            (is (= header actual-header))
            (is (= (frequencies rows) (frequencies actual-rows)))))))))


(deftest exact-github-version-runs-through-mcp
  (let [path   (temp-dir)
        app    (http/start! {:port 0 :data-dir (str path)})
        rpc    (connection (:port app))
        call   (partial call-tool rpc)
        source (slurp (io/resource "portability/github-developer-productivity-v1.edn"))]
    (try
      (let [draft         (call "create_draft" {:edn source})
            id            (:id draft)
            instance      (:instanceId draft)
            valid         (call "validate" {:kind "pipeline" :edn source})
            empty-reports (update-vals reports #(assoc % :rows []))
            empty-fixture (-> populated
                              (assoc :http {:list-repositories [(route repo-url repo-query (page []))]}
                                     :expect {:csv empty-reports}))
            malformed     (-> empty-fixture
                              (assoc :http {:list-repositories [(route repo-url repo-query {:status 200 :body "["})]}
                                     :expect {:status :failed :error-code "invalid-json"}))
            missing-route (assoc malformed
                            :http {:list-repositories [(route (str repo-url "?irrelevant") {} (page []))]}
                            :expect {:status :failed :error-code "fixture-unmatched-http"})
            context       (call "inspect_context" {:id id :task-name "repositories"})
            bad-allow     (call "save_fixture" {:id id :name "Wrong allow shape" :edn "{:allow [:report]}"})
            configured    (call "set_configuration" {:id id :configurationRevision 0 :configEdn (pr-str config)})
            saved         (call "save_draft" {:id id :revision 0})
            base          {:id id :scope "pipeline-fixture" :pipeline "github-developer-productivity" :version (:savedVersion saved)}
            cases         (mapv #(merge base (save-case call id %1 %2))
                                ["Populated GitHub" "Empty organization" "Malformed GitHub JSON" "Unmatched request"]
                                [populated empty-fixture malformed missing-route])
            command       {:instanceId instance :idempotencyKey "github-full-fixtures" :cases cases}
            suite         (call "run_fixture_suite" command)]
        (is (:valid valid))
        (is (true? (:configurationProvided context)))
        (is (false? (:configurationReady context)))
        (is (= #{["config" "org-name"] ["config" "gh-token"]}
               (set (map :path (:configurationDiagnostics context)))))
        (is (:configurationReady configured))
        (is (not (str/includes? (pr-str configured) (:gh-token config))))
        (is (= [":allow"] (get-in bad-allow [:error :path])))
        (is (str/includes? (get-in bad-allow [:error :hint]) "#{:report-fields}"))
        (is (= 1 (:savedVersion saved)))
        (is (= source (:edn (call "get_pipeline" {:name "github-developer-productivity" :version 1}))))
        (is (= (:id suite) (:id (call "run_fixture_suite" command))))
        (is (= "fixture-conflict"
               (get-in (call "run_fixture_suite"
                             (-> command
                                 (assoc :idempotencyKey "stale-fixture")
                                 (assoc-in [:cases 0 :fixtureRevision] 1)))
                       [:error :code])))
        (is (= "idempotency-conflict" (get-in (call "run_fixture_suite" (update command :cases pop)) [:error :code])))
        (let [done (finished call (:id suite))
              [full empty-run invalid unmatched] (:cases done)]
          (is (= "completed" (:status done)) (pr-str done))
          (is (= 4 (:passedCount done)))
          (doseq [[case expected] [[full reports] [empty-run empty-reports]]]
            (let [details (call "get_execution" {:id (:executionId case) :view "result"})]
              (is (= 1 (:version details)))
              (is (= 6 (get-in details [:taskCounts :completed])))
              (is (every? :passed (:assertions details)) (pr-str details))
              (assert-files details expected)))
          (is (= "invalid-json" (get-in (call "get_execution" {:id (:executionId invalid)}) [:error :code])))
          (is (= "fixture-unmatched-http" (get-in (call "get_execution" {:id (:executionId unmatched)}) [:error :code])))
          (is (empty? (:runs (call "list_runs" {})))))
        (let [single (assoc (first cases)
                       :instanceId instance
                       :idempotencyKey "github-exact-single")
              job    (call "execute" single)]
          (is (= (:id job) (:id (call "execute" single))))
          (is (= "completed" (:status (finished call (:id job)))))
          (is (= 5 (get-in (call "get_execution" {:id (:id job) :view "result" :task-name "pull-requests"}) [:sample :shown])))))
      (finally ((:stop app)) (delete-tree! path)))))


(deftest fixture-diagnostics-and-scope-boundaries
  (let
    [path (temp-dir)
     app (http/start! {:port 0 :data-dir (str path)})
     call (partial call-tool (connection (:port app)))
     source
     "{:name :fixture-edges :tasks
                 [{:name :rows :state-format :flatten :iterator {:next false}
                   :arrow-columns [[:id \"id\" :int64]]
                   :actions [{:name :pages :type :clj/identity :params [nil] :return [:body]}]}
                  {:name :write :actions [{:name :save :type :collet.actions.file/sink
                                           :params {:input \"synthetic\" :format :text :file-name \"../outside.csv\"}}]}]}"]
    (try
      (let [{:keys [id instanceId]} (call "create_draft" {:edn source})
            case-for                (fn [task fixture]
                                      (merge {:id id :scope "task" :revision 0 :task-name task}
                                             (save-case call id task fixture)))
            nested                  (case-for "rows"
                                              {:mocks  {:pages {:value {:collet.fixture/body-dataset-batches [[{:id 1}]]}}}
                                               :expect {:status :failed :error-code "collet.error/arrow-invalid-value"}})
            traversal               (case-for "write"
                                              {:temp-files #{:save}
                                               :expect     {:status :failed :error-code "fixture-file-path"}})
            command                 {:instanceId instanceId :idempotencyKey "edges" :cases [nested traversal]}
            job                     (call "run_fixture_suite" command)
            done                    (finished call (:id job))
            [arrow-result write-result] (mapv #(call "get_execution" {:id (:executionId %) :view "result"}) (:cases done))
            error                   (:error arrow-result)]
        (is (= 2 (:passedCount done)) (pr-str [done arrow-result write-result]))
        (is (= "row map" (:expected error)))
        (is (= "dataset batch" (:observed error)))
        (is (= ["rows" "pages"] [(:task error) (:action error)]))
        (is (= [0 0] (:path error)))
        (is (str/includes? (:hint error) "collet.stream/rows"))
        (is (empty? (:files write-result)))
        (is (= "draft-conflict"
               (get-in (call "run_fixture_suite"
                             (-> command
                                 (assoc :idempotencyKey "stale-source")
                                 (assoc-in [:cases 0 :revision] 1)))
                       [:error :code]))))
      (finally ((:stop app)) (delete-tree! path)))))


(deftest fixture-suite-snapshots-before-running
  (let
    [path (temp-dir)
     app (http/start! {:port 0 :data-dir (str path)})
     call (partial call-tool (connection (:port app)))
     source
     "{:name :snapshot
                 :tasks [{:name :slow :actions [{:name :wait :type :custom :fn collet.mcp-contract-test/slow-value}]}
                         {:name :rows :actions [{:name :data :type :clj/identity :params [[]]}]}]}"]
    (try
      (let [{:keys [id instanceId]} (call "create_draft" {:edn source})
            base                    {:id id :revision 0 :scope "task"}
            slow                    (merge base {:task-name "slow"} (save-case call id "Slow" {:allow #{:wait}}))
            content                 {:mocks  {:data {:value [{:id 1} {:id 1}]}}
                                     :expect {:rows {:rows [{:id 1} {:id 1}]}}}
            rows                    (merge base {:task-name "rows"} (save-case call id "Rows" content))
            command                 {:instanceId instanceId :idempotencyKey "snapshot" :cases [slow rows]}
            job                     (call "run_fixture_suite" command)
            updated                 (call "save_fixture"
                                          (merge (select-keys rows [:id :fixtureId :fixtureRevision])
                                                 {:name "Rows" :edn (pr-str (assoc-in content [:expect :rows :rows] [{:id 1}]))}))
            done                    (finished call (:id job))
            result                  (call "get_execution" {:id (get-in done [:cases 1 :executionId]) :view "result"})]
        (is (= 1 (:fixtureRevision updated)))
        (is (= 2 (:passedCount done)))
        (is (= 0 (:fixtureRevision result)))
        (is (false? (:fresh result)))
        (let [job      (call "run_fixture_suite"
                             (assoc command
                               :idempotencyKey "mismatch"
                               :cases [(assoc rows :fixtureRevision 1)]))
              mismatch (finished call (:id job))]
          (is (= "failed" (:status mismatch)))
          (is (= ["completed" false] ((juxt :status :passed) (first (:cases mismatch)))))))
      (finally ((:stop app)) (delete-tree! path)))))
