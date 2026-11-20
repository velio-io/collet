(ns collet.workbench.mcp
  (:require
   [clojure.string :as str]
   [collet.workbench :as wb]
   [collet.workbench.api :as api]
   [collet.workbench.authoring :as authoring]
   [collet.workbench.contracts :as contracts]
   [collet.workbench.data :as data]
   [collet.workbench.fixtures :as fixtures]
   [collet.workbench.inspection :as inspection]
   [collet.workbench.knowledge :as knowledge]
   [collet.workbench.wire :as wire]
   [org.clojars.roklenarcic.mcp-server.core :as core]
   [org.clojars.roklenarcic.mcp-server.json.charred :as serde]
   [org.clojars.roklenarcic.mcp-server.resources :as resources]
   [org.clojars.roklenarcic.mcp-server.server :as server]
   [org.clojars.roklenarcic.mcp-server.server.http :as http])
  (:import
   [java.io Closeable]))


(def ^:private mutations
  #{"create_draft" "update_draft" "restore_draft" "delete_draft" "delete_pipeline_version" "delete_pipeline" "apply_draft" "save_draft"
    "set_configuration" "save_fixture" "execute" "run_fixture_suite" "control_run"})


(defn- tool [name description handler]
  (let [read-only? (not (contains? mutations name))]
    (server/tool
     (str "collet_" name)
     description
     (contracts/input-schema name)
     (fn [_ args]
       (try
         (contracts/check! name args)
         (let [value   (handler args)
               source? (contains? #{"get_draft" "get_pipeline" "get_fixture"} name)
               budget  (if (= "fixture-suite" (:scope value))
                         16384
                         (if (or (contains? mutations name)
                                 (and (#{"get_execution" "get_run"} name) (= "summary" (get args :view "summary"))))
                           4096
                           (or (:maxBytes args) 16384)))
               value   (if source? value (wire/fit value budget))]
           (core/tool-result (core/text-content (wire/text value)) value))
         (catch Exception e
           (let [value (wire/error e)]
             (core/tool-result (core/text-content (wire/text value)) value :_meta {"collet/tool-error" true})))))
     :output-schema (contracts/output-schema name)
     :annotations {:readOnlyHint    read-only?
                   :destructiveHint (boolean (#{"execute" "apply_draft" "save_draft" "delete_draft" "delete_pipeline_version" "delete_pipeline" "restore_draft" "control_run"} name))
                   :idempotentHint  (or read-only? (contains? #{"execute" "run_fixture_suite"} name))
                   :openWorldHint   (boolean (#{"execute" "apply_draft" "save_draft"} name))})))


(defn- tools [app]
  [(tool "get_overview" "Start here: instance ID, working example, shared drafts, authoring loop and limits." (fn [_] (api/overview app)))
   (tool "read_documentation" "Search with query, list guides, or read an id's outline and a selected section. Start with getting-started. Use cursor to continue." knowledge/documentation)
   (tool "list_actions"
         "Search here before writing custom code. Prefer catalogue actions, then clj/*, then inline domain logic. Availability refers only to this process; declare external dependencies for deployment."
         knowledge/list-actions)
   (tool
    "describe_action"
    "Exact action fields, constraints, example and pinned dependencyEdn. Core actions are bundled. Declare optional package coordinates even when classpath-present. Null version/recipe requires workbench:prepare and restart; never guess versions. Supports clj/* and durable custom EDN forms."
    #(update (knowledge/describe (:type %)) :type str))
   (tool "validate" "Passive DSL check: pipeline, task, action, selector, condition or deps. Never evaluates code, reads action files or installs dependencies." knowledge/check)
   (tool "list_pipelines" "Page saved pipeline version summaries, newest first." #(api/pipelines app %))
   (tool "get_pipeline" "Read the source of an exact immutable saved version." #(api/pipeline app (:name %) (:version %)))
   (tool "delete_pipeline_version"
         "Permanently delete one saved version and its associated run/task history and internal outputs. Drafts and external files survive. Stop affected runs first."
         #(api/delete-pipeline! app (:name %) {:version (:version %)}))
   (tool
    "delete_pipeline"
    "Permanently delete every saved version and associated run/task history and internal outputs for this name. Supply the latest listed version to reject concurrent saves. Drafts and external files survive. Stop affected runs first."
    #(api/delete-pipeline! app (:name %) {:latest-version (:latestVersion %)}))
   (tool "list_drafts" "Page shared durable drafts in creation order, newest first; includes application instance ID." #(api/drafts app %))
   (tool "get_draft" "Read current or historical source. Select summary, task, action or diff for focused reads; configuration values are excluded." #(authoring/read-draft app (:id %) %))
   (tool "create_draft"
         "Create a shared draft from EDN, including incomplete source; optional configuration stays in memory. Return revision and browser link."
         #(authoring/create! app
                             (assoc %
                               :author "agent"
                               :response :summary)))
   (tool "update_draft"
         "Replace source OR perform one structural edit against an expected revision. add-task requires taskEdn; add-action requires actionEdn. Never executes."
         #(authoring/update! app
                             (:id %)
                             (assoc %
                               :author "agent"
                               :response :summary)))
   (tool "get_draft_history" "Page revision metadata, verified change summaries and client descriptions. Read source/diff separately with get_draft." #(api/history app (:id %) %))
   (tool "restore_draft"
         "Restore earlier source as a new revision against the current revision; retain intervening history."
         #(authoring/restore! app
                              (:id %)
                              (assoc %
                                :author "agent"
                                :response :summary)))
   (tool "delete_draft" "Delete a draft, its history and scratch context. Saved versions and runs survive." #(authoring/delete! app (:id %) (:revision %)))
   (tool "apply_draft" "Explicit preparation for clients that need it. May load dependencies and evaluate preparation forms. Run prepares automatically." #(authoring/prepare! app (:id %) (assoc % :response :summary)))
   (tool "save_draft"
         "Prepare and save an exact source revision as an immutable version; do not run actions. Returns the saved version and configuration reference."
         #(authoring/save! app (:id %) (assoc % :response :summary)))
   (tool "set_configuration" "Replace transient configuration using its expected configurationRevision. Values are never returned." #(authoring/configuration! app (:id %) (assoc % :response :summary)))
   (tool "list_fixtures" "List named synthetic fixture cases for a draft." #(fixtures/list-cases app (:id %)))
   (tool "get_fixture" "Read and export a synthetic fixture case as EDN." #(fixtures/get-case app (:id %) (:fixtureId %)))
   (tool "save_fixture" "Import or update synthetic fixture EDN. Updating requires fixtureId and fixtureRevision. Use synthetic data only." #(fixtures/put-case! app (:id %) %))
   (tool "inspect_context"
         "Inspect observed fields, input representations, configuration readiness and missing prerequisites. Never runs upstream tasks or consumes live lazy inputs."
         #(api/inspect-context app (:id %) %))
   (tool
    "execute"
    "Start execution. Use pipeline-fixture to test an exact saved version with a fixture owned by the supplied draft ID. Task, action and prefix fixtures also use exact source and fixture revisions. Live authoring uses configurationRevision. Always supply instanceId and idempotencyKey."
    #(authoring/execute! app (:id %) %))
   (tool "run_fixture_suite"
         "Run up to 20 revision-pinned fixture cases in order, including saved-version pipeline fixtures. Poll with collet_get_execution to read assertion outcomes and child execution IDs."
         #(authoring/run-fixture-suite! app %))
   (tool "get_execution"
         "Read status by default. Select result, events or metrics; filter by task/action. waitMs waits up to 10000 ms for completion/change after afterSequence; cancellation never stops the run."
         #(authoring/get-execution app (:id %) %))
   (tool "list_runs" "Page durable normal run summaries, newest first." #(api/runs app %))
   (tool "get_run" "Read durable run status/version/failure. Select result, events or metrics for detail; samples/events may expire, lifecycle summaries persist." #(inspection/read-run app (:id %) %))
   (tool "control_run" "Explicitly pause, resume or stop an owned normal run. Disconnecting never stops execution." #(do (wb/control! app (:id %) (:command %)) (inspection/read-run app (:id %) {})))])


(defn- resource-entries []
  (concat (map (fn [{:keys [id title]}] [(str "collet://docs/" id) title "text/markdown"]) (:docs (knowledge/index)))
          (map #(vector (str "collet://actions/" (data/label (:type %))) (:purpose %) "application/edn") (knowledge/actions))
          (map #(vector (str "collet://schemas/" (name %)) (str (name %) " authoring schema") "application/edn") (keys (knowledge/schemas)))))


(defn- dispatch []
  ;; mcp-server 0.3.61 separates ToolErrorResponse from structured ToolResult.
  ;; Adapt only this server's explicit errors, preserving both wire fields.
  (update (server/make-dispatch)
          "tools/call"
          (fn [handler]
            (fn [session request params]
              (let [result (handler session request params)]
                (if (get-in result [:_meta "collet/tool-error"])
                  (-> result (assoc :isError true) (dissoc :_meta))
                  result))))))


(defn create [app]
  (let
    [session
     (server/make-session
      (server/server-info
       "Collet Workbench"
       (:version (knowledge/build-info))
       "Start with collet_get_overview. Prefer catalogue actions, then clj/* core functions; use inline EDN function forms only for domain logic. Core is bundled. Declare pinned optional :coordinates even when classpath-present; :requires loads namespaces and :imports exposes Java classes. EDN forms and qualified function symbols are durable; live function objects and closures are rejected. Edit shared drafts with expected revisions, Check passively, inspect inputs, then Run. Use saved fixtures for synthetic action, prefix or task runs. Live runs perform real effects. Read status first; request details only when needed. A Workbench or saved run does not prove clean-process portability. Source/history persist; configuration and detailed diagnostics end on restart. Repeat a lost start with the same instanceId, arguments and idempotencyKey.")
      (serde/serde {})
      {}
      (dispatch))
     sessions (http/memory-sessions-store)]
    (doseq [t (tools app)]
      (server/add-tool session t))

    (server/set-resources-handler session
                                  (reify
                                   resources/Resources
                                   (supports-list-changed? [_] false)
                                   (supports-subscriptions? [_] false)
                                   (list-resources [_ _ _]
                                     {:resources (mapv (fn [[uri title mime]] (core/resource-desc uri title title mime nil)) (resource-entries))})
                                   (get-resource [_ _ uri]
                                     (when-let [[_ title mime] (some #(when (or (= (first (str/split uri #"\?" 2)) (first %))
                                                                                (and (str/starts-with? uri "collet://docs/") (str/starts-with? uri (str (first %) "/"))))
                                                                        %)
                                                                     (resource-entries))]
                                       (assoc (core/resource-desc uri title title mime nil)
                                         :handler (fn [_ _] (core/resource (knowledge/resource uri) mime uri)))))
                                   (subscribe [this _ _] this)
                                   (unsubscribe [this _ _] this)
                                   (subscribed? [_ _ _] false)))

    {:handler (http/ring-handler session sessions {})
     :close   (fn []
                (doseq [s (http/all-sessions sessions)]
                  (doseq [stream (concat [@(:os s)] (vals @(:post-streams s)))]
                    (when stream (try (.close ^Closeable stream) (catch Exception _ nil))))))}))
