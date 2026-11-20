(ns collet.authoring-test
  (:require [charred.api :as json]
            [clojure.test :refer [deftest is testing]]
            [collet.actions.http :as http-action]
            [collet.core :as collet]
            [collet.store :as store]
            [collet.workbench :as wb]
            [collet.workbench-test :as wtest :refer [delete-tree! eventually pipeline temp-dir with-workbench]]
            [collet.workbench.authoring :as a]
            [collet.workbench.data :as data]
            [collet.workbench.example :as example]
            [collet.workbench.fixtures :as fixtures]
            [collet.workbench.http :as http]
            [collet.workbench.knowledge :as k]
            [malli.core :as m])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]))


(deftest passive-catalogue-and-fragment-validation
  (with-redefs [collet/check-dependencies (fn [& _] (throw (ex-info "must not load" {})))
                collet/compile-pipeline   (fn [& _] (throw (ex-info "must not compile" {})))]
    (is (>= (count (k/actions)) 45))
    (doseq [descriptor (k/actions)]
      (is (some? (m/schema (:params-schema descriptor))) (str (:type descriptor)))
      (is (:valid (k/check {:kind "action" :edn (:example descriptor)})) (str (:type descriptor))))
    (is (false? (:valid (k/check {:kind "action" :edn "{:name :get :type :collet.actions.http/request :params {:url 42}}"}))))
    (is (false? (:valid (k/check {:kind "action" :edn "wrong"}))))
    (is (:valid (k/check {:kind "action" :edn "{:name :get :type :collet.actions.http/request :selectors {url [:config :url]} :params {:url url}}"})))
    (is (seq (:deferred (k/check {:kind "action" :edn "{:name :x :type :custom :fn (fn [] (spit \"never\" \"called\"))}"}))))
    (is (:valid (k/check {:kind "pipeline" :edn (pr-str pipeline)})))
    (is (false? (:valid (k/check {:kind "pipeline" :edn (pr-str (assoc-in pipeline [:tasks 0 :inputs] [:dependent]))}))))
    (is (:valid (k/check {:kind "selector" :edn "[:inputs :source 0 :amount]"})))
    (is (false? (:valid (k/check {:kind "pipeline" :edn "{:broken"}))))))


(deftest shared-raw-drafts-history-conflicts-and-restart
  (let [path  (temp-dir)
        app   (wb/open! {:data-dir (str path)})
        draft (a/create! app {:edn "{:unfinished" :configEdn "{:token \"private\"}"})
        id    (:id draft)]
    (try
      (is (= "{:unfinished" (get-in draft [:document :edn])))
      (let [next (a/update! app id {:revision 0 :edn (pr-str pipeline) :author "agent"})]
        (is (= 1 (:revision next)))
        (is (= "agent" (get-in next [:lastChange :draft.edit/author]))))
      (is (= 409 (try (a/update! app id {:revision 0 :edn "{:stale"}) (catch Exception e (:status (ex-data e))))))
      (is (= 2 (:revision (a/restore! app id {:revision 1 :fromRevision 0 :author "browser"}))))
      (is (= [2 1 0] (mapv :draft.edit/revision (:revisions (a/history app id 100)))))
      (is (not (contains? (a/view app id false) :configEdn)))
      (wb/close! app)
      (let [reopened (wb/open! {:data-dir (str path)})]
        (try
          (is (= "{:unfinished" (get-in (a/view reopened id) [:document :edn])))
          (is (false? (:configurationReady (a/view reopened id))))
          (is (empty? (:tasks (a/view reopened id))))
          (is (= 2 (:revision (a/view reopened id))))
          (finally (wb/close! reopened))))
      (finally (wb/close! app) (delete-tree! path)))))


(deftest lightweight-draft-reads-and-exact-saved-source
  (with-workbench [app]
                  (with-redefs [data/format-edn (fn [_] (throw (ex-info "Unexpected formatting" {})))]
                    (let [source   (str ";; Keep the original comment and whitespace\n" (pr-str pipeline) "\n\n")
                          overview {:overview? true}
                          draft    (a/create! app {:edn source :configEdn "{:token \"synthetic\"}" :view "overview"})
                          id       (:id draft)]
                      (is (= "source" (get-in draft [:document :tasks 0 :name])))
                      (is (not (contains? (:document draft) :edn)))
                      (is (not (contains? (get-in draft [:document :tasks 0 :actions 0]) :edn)))
                      (is (not (contains? draft :configEdn)))
                      (is (= {:revision 0 :edn source} (a/browser-text app id "source")))
                      (is (= {:token "synthetic"} (data/read-edn (:configEdn (a/browser-text app id "configuration")))))
                      (is (= 1 (:savedVersion (a/save! app id {:revision 0 :view "overview"}))))
                      (is (= source (:edn (wb/load-definition app "incremental" 1))))
                      (let [preview (a/create! app {:preview true :origin {:name "incremental" :version 1} :view "overview"})
                            id      (:id preview)
                            edited  (str source ";; Another comment\n")]
                        (is (= {:revision 0 :edn source} (a/browser-text app id "source")))
                        (is (= 1 (:savedVersion (a/save! app id {:revision 0}))))
                        (a/update! app id {:revision 0 :edn edited :view "overview"})
                        (is (= 2 (:savedVersion (a/save! app id {:revision 1}))))
                        (is (= 2 (:savedVersion (a/save! app id {:revision 1}))))
                        (is (= edited (:edn (wb/load-definition app "incremental" 2))))
                        (is (nil? (get-in (a/browser-view app id overview) [:document :edn]))))))))


(defn delayed-value [x]
  (Thread/sleep 200)
  x)


(deftest mutation-receipts-identify-the-accepted-source
  (with-workbench [app]
                  (doseq [response [nil :summary]]
                    (let [origin   {:name :base :version 1}
                          draft    (a/create! app {:edn "{:name :original :tasks []}" :origin origin})
                          id       (:id draft)
                          db       (get-in app [:ctx :store])
                          accepted "{:name :original :tasks []}\n;; client-a"
                          later    "{:name :client-b :tasks []}"
                          emit     wb/emit!
                          receipt  (with-redefs [wb/emit! (fn [target event]
                                                            (when (and (identical? app target) (= "client-a" (:author event)))
                                                              (store/update-draft! db (java.util.UUID/fromString id) 1 later "client-b" "Later edit"))
                                                            (emit target event))]
                                     (a/update! app id {:revision 0 :edn accepted :author "client-a" :response response}))]
                      (is (= 1 (:revision receipt) (:sourceRevision receipt)))
                      (if response
                        (do
                          (is (= "original" (:name receipt)))
                          (is (not= "Later edit" (:summary receipt))))
                        (do
                          (is (= origin (:origin receipt)))
                          (is (= accepted (get-in receipt [:document :edn])))
                          (is (= "client-a" (get-in receipt [:lastChange :draft.edit/author])))))
                      (is (= later (get-in (a/view app id) [:document :edn])))
                      (is (= 409
                             (try
                               (a/update! app id {:revision (:revision receipt) :edn "{:name :overwrite :tasks []}"})
                               (catch Exception e (:status (ex-data e))))))))))


(defn- run-authoring! [app id revision configuration-revision scope task index]
  (let [command {:instanceId            (:instance-id app)
                 :idempotencyKey        (str (random-uuid))
                 :revision              revision
                 :configurationRevision configuration-revision
                 :scope                 scope
                 :task-name             task}
        job     (a/execute! app id (cond-> command (some? index) (assoc :index index)))]
    (is (eventually #(#{"completed" "failed"} (:status (a/get-execution app (:id job))))))
    (a/get-execution app (:id job) {:view "result"})))


(deftest saved-previews-become-durable-only-after-editing
  (with-workbench [app]
                  (doseq [source [42 (apply str (repeat 262145 "x"))]]
                    (is (= 400
                           (try (a/create! app {:edn source :preview true})
                                (catch Exception e (:status (ex-data e)))))))
                  (let [original     (:id (a/create! app {:edn (pr-str pipeline)}))
                        _ (a/save! app original {:revision 0})
                        source       (:edn (wb/load-definition app "incremental" 1))
                        open-preview #(a/create! app
                                                 {:edn       source
                                                  :configEdn "{}"
                                                  :preview   true
                                                  :origin    {:name "incremental" :version 1}})
                        preview      (open-preview)
                        id           (:id preview)
                        other        (:id (open-preview))
                        ids          #(set (map :id (:drafts (a/list-drafts app 100))))]
                    (is (= #{original} (ids)))
                    (is (= "completed" (:status (run-authoring! app id 0 0 "prefix" "source" 0))))
                    (a/update! app id {:revision 0 :edn source})
                    (a/configuration! app id {:configurationRevision 0 :configEdn "{}"})
                    (is (= 1 (:savedVersion (a/save! app id {:revision 0}))))
                    (is (= #{original} (ids)))
                    (let [edited   (str ";; preserve this exact text\n" source)
                          accepted (a/update! app id {:revision 0 :edn edited})]
                      (is (= id (:id accepted)))
                      (is (= 1 (:revision accepted)))
                      (is (= edited (get-in accepted [:document :edn])))
                      (is (= {:name "incremental" :version 1} (:origin accepted)))
                      (is (= #{original id} (ids)))
                      (is (= source (:draft.edit/source (a/revision app id 0))))
                      (is (= edited (:draft.edit/source (a/revision app id 1))))
                      (is (= 409
                             (try (a/update! app id {:revision 0 :edn "{:old"})
                                  (catch Exception e (:status (ex-data e))))))
                      (is (= 0 (get-in (a/prepare! app id {:revision 1 :configurationRevision 0}) [:tasks "source" :through]))))
                    (a/close-scratch! app other)
                    (is (= 404 (try (a/view app other) (catch Exception e (:status (ex-data e))))))
                    (is (not (contains? @(:draft-runtime app) (java.util.UUID/fromString other))))
                    (a/close-scratch! app id)
                    (is (= 1 (:revision (a/view app id))))
                    (let [configured (:id (open-preview))]
                      (is (= 409
                             (try (a/configuration! app configured {:configurationRevision 99 :configEdn "{:changed true}"})
                                  (catch Exception e (:status (ex-data e))))))
                      (is (= #{original id} (ids)))
                      (a/configuration! app configured {:configurationRevision 0 :configEdn "{:changed true}"})
                      (is (= #{original id configured} (ids)))
                      (is (= source (:draft.edit/source (a/revision app configured 0))))))))


(deftest inspection-distinguishes-retained-prefixes-from-invalidated-samples
  (with-workbench [app]
                  (let [task     (assoc (first (:tasks pipeline)) :name :one)
                        spec     {:name :freshness :tasks [(assoc-in task [:actions 0] '{:name :first :type :clj/identity :params [7]})]}
                        id       (:id (a/create! app {:edn (pr-str spec)}))
                        appended (update-in spec [:tasks 0 :actions] conj '{:name :third :type :clj/identity :params [3]})
                        changed  (assoc-in appended [:tasks 0 :actions 0 :params] [8])]
                    (is (= "completed" (:status (run-authoring! app id 0 0 "prefix" "one" 0))))
                    (a/update! app id {:revision 0 :edn (pr-str appended)})
                    (a/prepare! app id {:revision 1 :configurationRevision 0})
                    (let [context (a/inspect-context app id {:task-name "one" :index 1})]
                      (is (:fresh context))
                      (is (= #{"[:state :base]" "[:state :first]"}
                             (set (map :selector (filter :executable (:fields context)))))))
                    (a/update! app id {:revision 1 :edn (pr-str changed)})
                    (a/prepare! app id {:revision 2 :configurationRevision 0})
                    (let [before  (wb/session-view app id)
                          context (a/inspect-context app id {:task-name "one" :index 1})]
                      (is (= "stale" (get-in before [:tasks "one" :status])))
                      (is (false? (:fresh context)))
                      (is (= {"base" 4 "first" 7} (get-in context [:preview :value "state"])))
                      (is (every? #(and (false? (:fresh %)) (false? (:executable %))) (:fields context)))
                      (is (empty? (get-in context [:preview :selectors])))
                      (is (some #(re-find #"setup.*stale" %) (:notices context)))
                      (is (= before (wb/session-view app id))))
                    (is (= "completed" (:status (run-authoring! app id 2 0 "prefix" "one" 0))))
                    (is (:fresh (a/inspect-context app id {:task-name "one" :index 1})))
                    (a/configuration! app id {:configurationRevision 0 :configEdn "{:changed true}"})
                    (a/prepare! app id {:revision 2 :configurationRevision 1})
                    (is (false? (:fresh (a/inspect-context app id {:task-name "one" :index 1})))))))


(deftest configuration-checks-follow-the-execution-scope
  (with-workbench [app]
                  (let [http '{:name      :http
                               :type      :collet.actions.http/request
                               :selectors {url [:config :future-url]}
                               :params    {:url url}}
                        spec {:name  :scope
                              :tasks [{:name :local :actions [{:name :value :type :clj/identity :params [42]}]}
                                      {:name :later :actions [http]}]}
                        id   (:id (a/create! app {:edn (pr-str spec)}))]
                    (is (= 42 (get-in (run-authoring! app id 0 0 "task" "local" nil) [:sample :value])))
                    (let [saved (a/save! app id {:revision 0})
                          job   (a/execute! app
                                            nil
                                            {:instanceId     (:instance-id app)
                                             :idempotencyKey "scope-pipeline"
                                             :scope          "pipeline"
                                             :pipeline       "scope"
                                             :version        (:savedVersion saved)
                                             :configuration  {:source "empty"}})]
                      (is (eventually #(= "failed" (:status (a/get-execution app (:id job))))))
                      (is (= "missing-configuration" (get-in (a/get-execution app (:id job)) [:error :code]))))
                    (a/update! app id {:revision 0 :edn (pr-str (update-in spec [:tasks 0 :actions] conj http))})
                    (doseq [scope ["action" "prefix"]]
                      (is (= 42 (get-in (run-authoring! app id 1 0 scope "local" 0) [:sample :value]))))
                    (doseq [[scope index] [["task" nil] ["action" 1] ["prefix" 1]]]
                      (let [result (run-authoring! app id 1 0 scope "local" index)]
                        (is (= "failed" (:status result)))
                        (is (= "missing-configuration" (get-in result [:error :code])))
                        (is (= [:tasks 0 :actions 1 :params :url] (get-in result [:error :diagnostics 0 :path]))))))))


(deftest execution-captures-revisions-and-deduplicates-starts
  (with-workbench [app]
                  (let [spec    '{:name  :snapshot
                                  :tasks [{:name       :one
                                           :keep-state true
                                           :actions    [{:name      :x
                                                         :type      :custom
                                                         :fn        collet.authoring-test/delayed-value
                                                         :selectors {x [:config :number]}
                                                         :params    [x]}]}]}
                        draft   (a/create! app {:edn (pr-str spec) :configEdn "{:number 7}"})
                        id      (:id draft)
                        command {:instanceId            (:instance-id app)
                                 :idempotencyKey        "once"
                                 :revision              0
                                 :configurationRevision 0
                                 :scope                 "task"
                                 :task-name             "one"}
                        job     (a/execute! app id command)]
                    (is (= (:id job) (:id (a/execute! app id command))))
                    (is (.endsWith (:url job) (str "/#draft=" id "&view=canvas&task=one")))
                    (a/update! app id {:revision 0 :edn "{:unfinished"})
                    (a/configuration! app id {:configurationRevision 0 :configEdn "{:number 99}"})
                    (is (eventually #(#{"completed" "failed"} (:status (a/get-execution app (:id job))))))
                    (let [done (a/get-execution app (:id job) {:view "result"})]
                      (is (= "completed" (:status done)) (pr-str done))
                      (is (= 7 (get-in done [:sample :value])))
                      (is (= 0 (:sourceRevision done)))
                      (is (false? (:currentResults (a/view app id)))))
                    (is (= 409
                           (try (a/execute! app
                                            id
                                            (assoc command
                                              :scope "action"
                                              :index 0))
                                (catch Exception e (:status (ex-data e))))))
                    (is (= 409
                           (try (a/execute! app
                                            id
                                            (assoc command
                                              :instanceId "old"
                                              :idempotencyKey "new"))
                                (catch Exception e (:status (ex-data e)))))))))


(defn- rpc! [port session message]
  (let [builder (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port "/mcp")))
                    (.header "Content-Type" "application/json")
                    (.header "Accept" "application/json, text/event-stream")
                    (.POST (HttpRequest$BodyPublishers/ofString (json/write-json-str message))))]
    (when session
      (.header builder "Mcp-Session-Id" session)
      (.header builder "MCP-Protocol-Version" "2025-11-25"))
    (.send (HttpClient/newHttpClient) (.build builder) (HttpResponse$BodyHandlers/ofString))))


(defn- rpc-body [response]
  (let [body (.body response)]
    (json/read-json (or (second (re-find #"(?m)^data: (.+)$" body)) body) :key-fn keyword)))


(deftest streamable-mcp-lifecycle-tools-and-resources
  (let [path (temp-dir)
        app  (http/start! {:port 0 :data-dir (str path)})
        port (:port app)]
    (try
      (let [init    (rpc! port
                          nil
                          {:jsonrpc "2.0"
                           :id      1
                           :method  "initialize"
                           :params  {:protocolVersion "2025-11-25"
                                     :capabilities    {}
                                     :clientInfo      {:name "acceptance-client" :version "1"}}})
            session (.orElse (.firstValue (.headers init) "Mcp-Session-Id") nil)
            call    (fn [method params]
                      (rpc-body (rpc! port session {:jsonrpc "2.0" :id 2 :method method :params params})))]
        (is (= 200 (.statusCode init)))
        (is (= "2025-11-25" (get-in (rpc-body init) [:result :protocolVersion])))
        (rpc! port session {:jsonrpc "2.0" :method "notifications/initialized"})
        (is (some #(= "collet_inspect_context" (:name %)) (get-in (call "tools/list" {}) [:result :tools])))
        (is (seq (get-in (call "resources/read" {:uri "collet://docs/getting-started"}) [:result :contents])))
        (let [created (call "tools/call" {:name "collet_create_draft" :arguments {:edn (pr-str pipeline) :configEdn "{:token \"workbench-private-config-value\"}"}})
              draft   (get-in created [:result :structuredContent])]
          (is (string? (:id draft)) (pr-str created))
          (is (not (contains? draft :configEdn)))
          (is (= 0 (:revision draft)))
          (let [job (get-in (call "tools/call"
                                  {:name      "collet_execute"
                                   :arguments {:id                    (:id draft)
                                               :revision              0
                                               :configurationRevision 0
                                               :instanceId            (:instanceId draft)
                                               :idempotencyKey        "privacy-check"
                                               :scope                 "action"
                                               :task-name             "source"
                                               :index                 0}})
                            [:result :structuredContent])]
            (is (.endsWith (:url job) (str "/#draft=" (:id draft) "&view=action&task=source&action=first")))
            (is (eventually #(#{"completed" "failed"} (:status (a/get-execution app (:id job))))))
            (is (not (.contains (json/write-json-str (call "tools/call" {:name "collet_get_execution" :arguments {:id (:id job)}})) "workbench-private-config-value")))
            (is (not (.contains (json/write-json-str (call "tools/call" {:name "collet_get_draft" :arguments {:id (:id draft)}})) "workbench-private-config-value"))))
          (let [conflict (call "tools/call" {:name "collet_update_draft" :arguments {:id (:id draft) :revision 99 :edn "{}"}})]
            (is (true? (get-in conflict [:result :isError]))))
          (let [invalid (call "tools/call" {:name "collet_validate" :arguments {:edn "{:broken"}})]
            (is (false? (get-in invalid [:result :structuredContent :valid]))))))
      (finally ((:stop app)) (delete-tree! path)))))


(defn- run-saved! [app id task]
  (let [draft (a/view app id)
        saved (a/save! app id {:revision (:revision draft)})
        job   (a/execute! app
                          nil
                          {:instanceId     (:instance-id app)
                           :idempotencyKey (str (random-uuid))
                           :scope          "pipeline"
                           :pipeline       (get-in saved [:savedPipeline :name])
                           :version        (:savedVersion saved)
                           :configuration  {:source "draft" :id id :revision (:configurationRevision draft)}})]
    (is (eventually #(#{"completed" "failed"} (:status (a/get-execution app (:id job))))))
    (let [summary (a/get-execution app (:id job))]
      (if (:runId summary)
        (a/get-execution app (:id job) {:view "result" :task-name task})
        summary))))


(deftest repeated-and-historical-draft-saves-preserve-source-and-versions
  (with-workbench [app]
                  (let [spec     {:name :save-roundtrip :tasks [{:name :one :actions [{:name :value :type :clj/identity :params [1]}]}]}
                        initial  (:id (a/create! app {:edn (pr-str spec)}))
                        _ (a/save! app initial {:revision 0})
                        original (store/load-pipeline (get-in app [:ctx :store]) :save-roundtrip 1)
                        source   (:edn (wb/load-definition app "save-roundtrip" 1))
                        id       (:id (a/create! app {:edn source}))]
                    (doseq [n [2 3]]
                      (let [text (str "; retain this comment\n" (pr-str (assoc-in (data/read-edn source) [:tasks 0 :actions 0 :params] [n])))]
                        (a/update! app id {:revision (- n 2) :edn text})
                        (is (= n (:savedVersion (a/save! app id {:revision (dec n)}))))
                        (is (= n (:savedVersion (a/save! app id {:revision (dec n)}))))
                        (is (= text (get-in (a/view app id) [:document :edn])))
                        (is (= text (:draft.edit/source (a/revision app id (dec n)))))))
                    (is (= 409 (try (a/save! app id {:revision 0}) (catch Exception e (:status (ex-data e))))))
                    (let [historical (:id (a/create! app {:edn source :origin {:name "save-roundtrip" :version 1}}))]
                      (is (= 1 (:savedVersion (a/save! app historical {:revision 0}))))
                      (a/update! app historical {:revision 0 :edn (pr-str (assoc-in (data/read-edn source) [:tasks 0 :actions 0 :params] [4]))})
                      (is (= 4 (:savedVersion (a/save! app historical {:revision 1}))))
                      (is (= source (:draft.edit/source (a/revision app historical 0)))))
                    (is (= original (store/load-pipeline (get-in app [:ctx :store]) :save-roundtrip 1)))
                    (let [db      (get-in app [:ctx :store])
                          legacy  (store/save-pipeline! db {} (collet/compile-pipeline (assoc spec :name :legacy-save)))
                          session (wb/create-session! app {:edn (:edn (wb/load-definition app "legacy-save" 1)) :configEdn "{}"})]
                      (is (= 1 (get-in (wb/save! app (:id session)) [:document :version])))
                      (is (= legacy (store/load-pipeline db :legacy-save 1)))))))


(deftest scratch-output-matches-compiled-task-formatting
  (with-workbench [app]
                  (let [task '{:name     :collect
                               :setup    [{:name :seed :type :clj/identity :params [0]}]
                               :actions  [{:name :seed :type :clj/inc :selectors {n [:state :seed]} :params [n]}
                                          {:name :collected :type :fold :selectors {n [:state :seed]} :params {:item n}}]
                               :iterator {:next [:< [:state :seed] 3]}}]
                    (doseq [[case-name selected scope expected]
                            [[:inferred task "task" [1 2 3]]
                             [:explicit (assoc task :state-format :flatten) "task" [1 1 2 1 2 3]]
                             [:prefix (dissoc task :iterator) "prefix" 1]]]
                      (testing (name case-name)
                        (let [id      (:id (a/create! app {:edn (pr-str {:name case-name :tasks [(assoc selected :keep-state true)]})}))
                              scratch (run-authoring! app id 0 0 scope "collect" 1)
                              saved   (run-saved! app id "collect")]
                          (is (= "completed" (:status scratch) (:status saved)))
                          (is (= expected (get-in scratch [:sample :value]) (get-in saved [:sample :value])))))))))


(deftest dependency-edits-invalidate-only-changed-retention
  (with-workbench [app]
                  (let [left            {:name :left :actions [{:name :rows :type :clj/identity :params [[{:id 1} {:id 2}]]}]}
                        right           (assoc left :name :right)
                        consumer        '{:name :consumer :inputs [:left] :actions [{:name :count :type :clj/count :selectors {rows [:inputs :left]} :params [rows]}]}
                        second-consumer (assoc consumer :name :second-consumer)
                        id              (:id (a/create! app {:edn (pr-str {:name :retention :tasks [left right]})}))
                        apply-tasks!    (fn [tasks]
                                          (let [revision (:revision (a/view app id))]
                                            (a/update! app id {:revision revision :edn (pr-str {:name :retention :tasks tasks})})
                                            (a/prepare! app id {:revision (inc revision) :configurationRevision 0})))
                        run!            (fn [task] (run-authoring! app id (:revision (a/view app id)) 0 "task" task nil))
                        output          #(get-in @(get @(:sessions app) (java.util.UUID/fromString id)) [:tasks :left :output-handle])]
                    (run! "left")
                    (run! "right")
                    (let [view (apply-tasks! [left right consumer])]
                      (is (= "stale" (get-in view [:tasks "left" :status])))
                      (is (= "value" (get-in view [:tasks "left" :preview :representation])))
                      (is (= "completed" (get-in view [:tasks "right" :status])))
                      (is (= "failed" (:status (run! "consumer"))))
                      (is (= "stale" (get-in (a/view app id) [:tasks "left" :status]))))
                    (run! "left")
                    (is (= 1 (get-in (run! "consumer") [:sample :value])))
                    (let [handle (output)]
                      (apply-tasks! [left right consumer second-consumer])
                      (is (identical? handle (output)))
                      (is (= "completed" (get-in (a/view app id) [:tasks "left" :status])))
                      (apply-tasks! [left right consumer])
                      (is (identical? handle (output))))
                    (let [files   (filterv #(.isFile %) (file-seq (.toFile (:scratch-dir app))))
                          rewired (-> consumer (assoc :inputs [:right]) (assoc-in [:actions 0 :selectors] '{rows [:inputs :right]}))
                          view    (apply-tasks! [left right rewired])]
                      (is (seq files))
                      (is (every? #(not (.exists %)) files))
                      (is (nil? (output)))
                      (doseq [task ["left" "right" "consumer"]]
                        (is (= "stale" (get-in view [:tasks task :status]))))
                      (is (= "failed" (:status (run! "consumer"))))
                      (run! "right")
                      (is (= 1 (get-in (run! "consumer") [:sample :value]))))
                    (let [view (apply-tasks! [left right])]
                      (is (= "stale" (get-in view [:tasks "right" :status])))))))


(deftest configuration-preflight-defers-conditional-actions
  (with-workbench [app]
                  (let [calls (atom [])
                        spec  '{:name  :conditional
                                :tasks [{:name    :fetch
                                         :actions [{:name :enabled :type :clj/identity :selectors {enabled [:config :enabled]} :params [enabled]}
                                                   {:name      :request
                                                    :type      :collet.actions.http/request
                                                    :when      [:true? [:state :enabled]]
                                                    :selectors {url [:config :url]}
                                                    :params    {:url url}}]}]}
                        id    (:id (a/create! app {:edn (pr-str spec) :configEdn "{:enabled false}"}))]
                    (with-redefs [http-action/http-request (fn [request] (swap! calls conj (:url request)) {:status 200 :body "ok"})]
                      (is (= "completed" (:status (run-authoring! app id 0 0 "task" "fetch" nil))))
                      (is (= "completed" (:status (run-saved! app id "fetch"))))
                      (is (empty? @calls))
                      (a/configuration! app id {:configurationRevision 0 :configEdn "{:enabled true :url \"https://example.test\"}"})
                      (is (= "completed" (:status (run-authoring! app id 0 1 "prefix" "fetch" 1))))
                      (is (= "completed" (:status (run-saved! app id "fetch"))))
                      (is (= ["https://example.test" "https://example.test"] @calls))))))


(deftest execution-preserves-exact-configuration
  (with-workbench [app]
                  (doseq [config [{} {:demo-id "expected-value" :nested {:enabled true}}]]
                    (let [id (:id (a/create! app
                                             {:edn       "{:name :config-exact :tasks [{:name :read :actions [{:name :config :type :clj/identity :selectors {c [:config]} :params [c]}]}]}"
                                              :configEdn (pr-str config)}))]
                      (doseq [job [(run-authoring! app id 0 0 "action" "read" 0) (run-saved! app id "read")]]
                        (is (= "completed" (:status job)))
                        (is (= (data/safe-value config) (get-in job [:sample :value]))))
                      (is (= config (data/read-edn (:configEdn (a/view app id)))))))))


(deftest portable-example-retries-have-execution-local-attempts
  (with-workbench [app]
                  (let [id (:id (a/create! app {}))]
                    (is (not (.contains (pr-str example/pipeline) "collet.workbench.example/")))
                    (doseq [task ["load-orders" "normalize"]]
                      (is (= "completed" (:status (run-authoring! app id 0 0 "task" task nil)))))
                    (dotimes [_ 2]
                      (let [job (run-authoring! app id 0 0 "task" "enrich-customers" nil)]
                        (is (= "completed" (:status job)))
                        (is (eventually #(= 1 (get-in (a/get-execution app (:id job) {:view "metrics"}) [:metrics :retryCount]))))))
                    (dotimes [_ 2]
                      (let [job (run-saved! app id "report")]
                        (is (= "completed" (:status job)))
                        (is (= 24 (get-in job [:sample :value "orders"])))
                        (is (= 1 (get-in (a/get-execution app (:id job) {:view "metrics"}) [:metrics :retryCount])))))
                    (is (= example/config (data/read-edn (:configEdn (a/view app id)))))
                    (a/configuration! app id {:configurationRevision 0 :configEdn "{:failure-mode :always}"})
                    (let [job (run-saved! app id "enrich-customers")]
                      (is (= "failed" (:status job)))
                      (is (= 2 (get-in (a/get-execution app (:id job) {:view "metrics"}) [:metrics :retryCount]))))
                    (a/configuration! app id {:configurationRevision 1 :configEdn "{:failure-mode :never}"})
                    (let [job (run-saved! app id "report")]
                      (is (= "completed" (:status job)))
                      (is (= 0 (get-in (a/get-execution app (:id job) {:view "metrics"}) [:metrics :retryCount])))))))


(deftest scratch-revisions-track-execution-and-reset-without-source-edits
  (with-workbench [app]
                  (let [id       (:id (a/create! app {:edn "{:name :scratch-revisions :tasks [{:name :one :actions [{:name :value :type :custom :fn collet.workbench-test/gated-value}]}]}"}))
                        revision #(-> (a/view app id) :scratchRevision)
                        gate     (promise)]
                    (reset! wtest/task-release gate)
                    (is (nil? (revision)))
                    (a/prepare! app id {:revision 0 :configurationRevision 0})
                    (let [prepared (revision)
                          job      (a/execute! app
                                               id
                                               {:instanceId            (:instance-id app)
                                                :idempotencyKey        "scratch-revision"
                                                :revision              0
                                                :configurationRevision 0
                                                :scope                 "task"
                                                :task-name             "one"})]
                      (try
                        (is (eventually #(= "running" (get-in (a/view app id) [:tasks "one" :status]))))
                        (is (< prepared (revision)))
                        (let [running (revision)]
                          (deliver gate 7)
                          (is (eventually #(= "completed" (:status (a/get-execution app (:id job))))))
                          (is (< running (revision))))
                        (finally (deliver gate 7))))
                    (let [completed (revision)]
                      (is (= completed (:scratchRevision (a/inspect-context app id {:task-name "one"}))))
                      (wb/reset-session! app id)
                      (is (< completed (revision))))
                    (is (= 0 (:revision (a/view app id))))
                    (a/update! app id {:revision 0 :edn "{:name :scratch-revisions :tasks [{:name :one :actions [{:name :value :type :clj/inc :params [nil]}]}]}"})
                    (a/prepare! app id {:revision 1 :configurationRevision 0})
                    (let [prepared (revision)]
                      (is (= "failed" (:status (run-authoring! app id 1 0 "task" "one" nil))))
                      (is (< prepared (revision))))
                    (a/close-scratch! app id)
                    (is (nil? (revision))))))


(deftest workbench-execution-applies-config-defaults
  (with-workbench [app]
                  (let [spec  '{:name        :with-defaults
                                :config-spec [:map [:endpoint [:string {:default "https://fixture.invalid"}]]]
                                :tasks       [{:name       :one
                                               :keep-state true
                                               :actions    [{:name      :value
                                                             :type      :clj/identity
                                                             :selectors {url [:config :endpoint]}
                                                             :params    [url]}]}]}
                        draft (a/create! app {:edn (pr-str spec) :configEdn "{:secret \"synthetic-config-secret\"}"})
                        id    (:id draft)]
                    (doseq [scope ["action" "prefix" "task"]]
                      (let [result (run-authoring! app id 0 0 scope "one" 0)]
                        (is (= "completed" (:status result)))
                        (is (= "https://fixture.invalid" (get-in result [:sample :value])))
                        (is (not (contains? (get-in (a/view app id false) [:tasks "one" :actions "value" :inputs :value]) "config")))))
                    (is (not (contains? (wb/selected-value app {:session-id id :task-name "one" :action-name "value" :view "Inputs"}) :config))))))


(deftest fixture-runs-a-selected-iterator-action-and-stays-separate
  (let [path    (temp-dir)
        app     (wb/open! {:data-dir (str path)})
        spec    '{:name        :fixture-iterator
                  :config-spec [:map [:secret :string]]
                  :tasks       [{:name     :rows
                                 :iterator {:next [:true? [:$mapper/has-next-item]]}
                                 :actions  [{:name :each :type :mapper :params {:sequence [{:id 1} {:id 2}]}}
                                            {:name      :request
                                             :type      :collet.actions.http/request
                                             :selectors {item [:$mapper/item :id]}
                                             :when      [:= [:$mapper/item :id] 2]
                                             :params    {:url ["http://must-not-run/%s" item]}
                                             :return    [:body :id]}]}]}
        draft   (a/create! app {:edn (pr-str spec)})
        id      (:id draft)
        fixture (fixtures/put-case! app
                                    id
                                    {:name "Second row"
                                     :edn  "{:state {:each {:current {:id 2}}}
                                                    :mocks {:request {:responses [{:body {:id 42}}]}}}"})
        command {:scope          "action"
                 :task-name      "rows"
                 :index          1
                 :idempotencyKey "single-row"
                 :instanceId     (:instance-id app)
                 :revision       (:revision draft)}]
    (try
      (doseq [scope ["action" "prefix" "task"]]
        (let [live (a/execute! app
                               id
                               (assoc command
                                 :scope scope
                                 :idempotencyKey scope
                                 :configurationRevision 0))]
          (is (= "invalid-configuration"
                 (get-in (a/get-execution app (:id live) {:waitMs 5000}) [:error :code])))))
      (let [job    (a/execute! app
                               id
                               (merge (assoc command :idempotencyKey "fixture-row")
                                      (select-keys fixture [:fixtureId :fixtureRevision])))
            result (a/get-execution app (:id job) {:view "result" :waitMs 5000})]
        (is (= "completed" (:status result)))
        (is (= 42 (get-in result [:sample :value])))
        (is (= ["request"] (:mockedActions result)))
        (is (:fresh result))
        (let [unmocked (fixtures/put-case! app id (assoc fixture :edn "{:state {:each {:current {:id 2}}} :allow #{:request}}"))
              blocked  (a/execute! app
                                   id
                                   (merge (assoc command :idempotencyKey "fixture-unmocked")
                                          (select-keys unmocked [:fixtureId :fixtureRevision])))]
          (is (= "fixture-mock-required"
                 (get-in (a/get-execution app (:id blocked) {:waitMs 5000}) [:error :code]))))
        (is (false? (:fresh (a/get-execution app (:id job)))))
        (is (nil? (:scratchRevision (a/view app id)))))
      (finally (wb/close! app) (delete-tree! path)))))
