(ns collet.workbench.contracts
  "One argument contract for MCP advertisement and server-side validation."
  (:require [malli.core :as m]
            [malli.json-schema :as json-schema]))


(def text [:string {:max 262144}])


(def identifier [:string {:min 1 :max 200}])


(def revision [:int {:min 0}])


(def page-fields
  {:limit    [:int {:min 1 :max 100}]
   :cursor   [:string {:max 200}]
   :maxBytes [:int {:min 4096 :max 65536}]})


(def draft-fields {:id identifier :revision revision :configurationRevision revision})


(def detail-fields
  (merge page-fields
         {:view          [:enum "summary" "result" "events" "metrics"]
          :task-name     identifier
          :action-name   identifier
          :level         [:enum "debug" "info" "warn" "error"]
          :samplePathEdn [:string {:max 2000}]}))


(defn object [fields required]
  (into [:map {:closed true}]
        (map (fn [[k schema]] (if (contains? (set required) k) [k schema] [k {:optional true} schema])))
        fields))


(def edit-fields
  (merge draft-fields
         {:summary   [:string {:max 500}]
          :task      identifier
          :source    identifier
          :target    identifier
          :index     revision
          :direction [:enum "up" "down"]
          :actionEdn text
          :taskEdn   text}))


(def edit-operations
  {"add-task"       [:taskEdn]
   "replace-task"   [:task :taskEdn]
   "remove-task"    [:task]
   "add-action"     [:task :actionEdn]
   "replace-action" [:task :index :actionEdn]
   "remove-action"  [:task :index]
   "move-action"    [:task :index :direction]
   "connect"        [:source :target]
   "disconnect"     [:source :target]})


(def edit-schema
  (into [:or (object {:id identifier :revision revision :edn text :summary [:string {:max 500}]} [:id :revision :edn])]
        (map (fn [[op required]]
               (object (assoc (select-keys edit-fields
                                           (concat [:id :revision :summary]
                                                   required
                                                   (when (= op "add-action") [:index])))
                         :op [:= op])
                       (concat [:id :revision :op] required))))
        edit-operations))


(def configuration
  [:or (object {:source [:= "empty"]} [:source])
   (object {:source [:= "draft"] :id identifier :revision revision} [:source :id :revision])])


(def execution-fields
  (merge draft-fields
         {:instanceId      identifier
          :idempotencyKey  identifier
          :task-name       identifier
          :index           revision
          :fixtureId       identifier
          :fixtureRevision revision}))


(def pipeline-fixture-fields
  {:scope           [:= "pipeline-fixture"]
   :id              identifier
   :pipeline        identifier
   :version         [:int {:min 1}]
   :fixtureId       identifier
   :fixtureRevision revision})


(def ^:private fixture-case-schema
  (into [:or (object pipeline-fixture-fields (keys pipeline-fixture-fields))]
        (for [scope ["task" "action" "prefix"]
              :let  [required (cond-> [:id :revision :scope :task-name :fixtureId :fixtureRevision]
                                (not= scope "task") (conj :index))]]
          (object (assoc (select-keys execution-fields required) :scope [:= scope]) required))))


(def execute-schema
  (into [:or
         (object (assoc pipeline-fixture-fields
                   :instanceId identifier
                   :idempotencyKey identifier)
                 (concat (keys pipeline-fixture-fields) [:instanceId :idempotencyKey]))
         (object {:instanceId     identifier
                  :idempotencyKey identifier
                  :scope          [:= "pipeline"]
                  :pipeline       identifier
                  :version        [:int {:min 1}]
                  :configuration  configuration}
                 [:instanceId :idempotencyKey :scope :pipeline :version :configuration])]
        (map (fn [scope]
               (object (assoc (if (= scope "task") (dissoc execution-fields :index) execution-fields) :scope [:= scope])
                       (cond-> [:instanceId :idempotencyKey :id :revision :scope :task-name]
                         (not= scope "task") (conj :index)))))
        ["task" "action" "prefix"]))


(def inputs
  {"get_overview"            (object {} [])
   "read_documentation"      (object (merge page-fields {:id identifier :section identifier :query [:string {:max 1000}] :full :boolean}) [])
   "list_actions"            (object (merge page-fields
                                            {:query        [:string {:max 1000}]
                                             :availability [:enum "classpath-present" "dependency-required"]
                                             :effects      [:string {:max 100}]})
                                     [])
   "describe_action"         (object {:type identifier} [:type])
   "validate"                (object {:kind        [:enum "pipeline" "task" "action" "selector" "condition" "deps"]
                                      :edn         text
                                      :pipelineEdn text}
                                     [:edn])
   "list_pipelines"          (object page-fields [])
   "get_pipeline"            (object {:name identifier :version [:int {:min 1}]} [:name :version])
   "delete_pipeline_version" (object {:name identifier :version [:int {:min 1}]} [:name :version])
   "delete_pipeline"         (object {:name identifier :latestVersion [:int {:min 1}]} [:name :latestVersion])
   "list_drafts"             (object page-fields [])
   "get_draft"               (object {:id           identifier
                                      :revision     revision
                                      :view         [:enum "source" "summary" "task" "action" "diff"]
                                      :task-name    identifier
                                      :index        revision
                                      :fromRevision revision}
                                     [:id])
   "create_draft"            (object {:edn text :configEdn text} [:edn])
   "update_draft"            edit-schema
   "get_draft_history"       (object (merge {:id identifier} page-fields) [:id])
   "restore_draft"           (object {:id identifier :revision revision :fromRevision revision} [:id :revision :fromRevision])
   "delete_draft"            (object {:id identifier :revision revision} [:id :revision])
   "apply_draft"             (object draft-fields [:id :revision :configurationRevision])
   "save_draft"              (object {:id identifier :revision revision} [:id :revision])
   "set_configuration"       (object {:id identifier :configurationRevision revision :configEdn text} [:id :configurationRevision :configEdn])
   "list_fixtures"           (object {:id identifier} [:id])
   "get_fixture"             (object {:id identifier :fixtureId identifier} [:id :fixtureId])
   "save_fixture"            (object {:id              identifier
                                      :fixtureId       identifier
                                      :fixtureRevision revision
                                      :name            identifier
                                      :edn             text}
                                     [:id :name :edn])
   "inspect_context"         (object (merge page-fields {:id identifier :task-name identifier :index revision :samplePathEdn [:string {:max 2000}]}) [:id :task-name])
   "execute"                 execute-schema
   "run_fixture_suite"       (object {:instanceId     identifier
                                      :idempotencyKey identifier
                                      :cases          [:vector {:min 1 :max 20} fixture-case-schema]}
                                     [:instanceId :idempotencyKey :cases])
   "get_execution"           (object (merge {:id identifier :waitMs [:int {:min 0 :max 10000}] :afterSequence revision} detail-fields) [:id])
   "list_runs"               (object page-fields [])
   "get_run"                 (object (merge {:id identifier} detail-fields) [:id])
   "control_run"             (object {:id identifier :command [:enum "pause" "resume" "stop"]} [:id :command])})


(def error-schema
  [:map
   [:error
    [:map [:code :string] [:message :string]
     [:status {:optional true} :int] [:path {:optional true} [:vector :any]]
     [:hint {:optional true} :string] [:currentRevision {:optional true} revision]
     [:diagnostics {:optional true} [:vector :map]]]]])


(def receipt-schema
  [:map [:id :string] [:revision revision] [:sourceRevision revision]
   [:configurationRevision revision] [:configurationReady :boolean] [:configurationProvided :boolean]
   [:configurationDiagnostics [:vector :map]] [:instanceId :string] [:url :string]
   [:summary {:optional true} [:maybe :string]]])


(def status-schema
  [:map [:id :string] [:status :string] [:error {:optional true} [:maybe :map]]
   [:scope {:optional true} :string] [:runId {:optional true} [:maybe :string]]
   [:sourceRevision {:optional true} [:maybe revision]] [:configurationRevision {:optional true} [:maybe revision]]
   [:executionMode {:optional true} [:enum "fixture" "live"]]
   [:fixtureId {:optional true} [:maybe :string]] [:fixtureRevision {:optional true} [:maybe revision]]
   [:mockedActions {:optional true} [:vector :string]] [:fresh {:optional true} :boolean]
   [:assertionsPassed {:optional true} :boolean] [:assertions {:optional true} [:vector :map]]
   [:cases {:optional true} [:vector :map]] [:caseCount {:optional true} :int] [:passedCount {:optional true} :int]
   [:outputDirectory {:optional true} :string] [:files {:optional true} [:vector :map]]
   [:taskCounts {:optional true} :map]
   [:sequence {:optional true} revision] [:elapsedMs {:optional true} [:maybe number?]]
   [:sample {:optional true} [:maybe :map]] [:events {:optional true} [:vector :map]]
   [:metrics {:optional true} :map]])


(def outputs
  {"get_overview"            [:map [:instanceId :string] [:version :string] [:workflow [:vector :string]] [:example :map] [:drafts [:vector :map]]]
   "read_documentation"      [:map [:documents {:optional true} [:vector :map]] [:sections {:optional true} [:vector :map]]
                              [:matches {:optional true} [:vector :map]] [:text {:optional true} :string]]
   "list_actions"            [:map [:actions [:vector :map]] [:families [:vector :map]]]
   "describe_action"         [:map [:type :string] [:purpose :string] [:effects :string] [:availability :string]]
   "validate"                [:map [:valid :boolean] [:errors [:vector :map]] [:warnings [:vector :map]] [:deferred [:vector :map]]]
   "list_pipelines"          [:map [:pipelines [:vector :map]]]
   "get_pipeline"            [:map [:name :string] [:version :int] [:edn :string]]
   "list_drafts"             [:map [:instanceId :string] [:drafts [:vector :map]]]
   "get_draft"               receipt-schema
   "create_draft"            receipt-schema
   "update_draft"            receipt-schema
   "get_draft_history"       [:map [:revisions [:vector :map]]]
   "restore_draft"           receipt-schema
   "delete_draft"            [:map [:deleted :boolean]]
   "delete_pipeline_version" [:map [:deleted :boolean] [:name :string] [:versionsDeleted :int] [:runsDeleted :int]]
   "delete_pipeline"         [:map [:deleted :boolean] [:name :string] [:versionsDeleted :int] [:runsDeleted :int]]
   "apply_draft"             receipt-schema
   "save_draft"              receipt-schema
   "set_configuration"       receipt-schema
   "list_fixtures"           [:map [:fixtures [:vector :map]]]
   "get_fixture"             [:map [:fixtureId :string] [:fixtureRevision revision] [:name :string] [:edn :string]]
   "save_fixture"            [:map [:fixtureId :string] [:fixtureRevision revision] [:name :string]]
   "inspect_context"         [:map [:id :string] [:sourceRevision revision] [:configurationRevision revision] [:fresh :boolean]
                              [:fields [:vector :map]] [:notices [:vector :string]] [:configuration [:vector :map]]
                              [:configurationProvided :boolean] [:configurationReady :boolean] [:configurationDiagnostics [:vector :map]]]
   "execute"                 status-schema
   "run_fixture_suite"       status-schema
   "get_execution"           status-schema
   "get_run"                 status-schema
   "list_runs"               [:map [:runs [:vector :map]]]
   "control_run"             status-schema})


(defn input-schema [name]
  (assoc (json-schema/transform (get inputs name)) :type "object"))


(defn output-schema [name]
  (assoc (json-schema/transform [:or error-schema (get outputs name)]) :type "object"))


(def ^:private compiled-inputs (update-vals inputs m/schema))


(def ^:private compiled-variants
  (into {}
        (for [[name discriminator] {"update_draft" :op "execute" :scope}]
          [name
           (into {}
                 (keep (fn [schema]
                         (when-let [tag (some #(when (= discriminator (first %)) (second (last %))) (drop 2 schema))]
                           [tag (m/schema schema)])))
                 (rest (get inputs name)))])))


(defn check! [name args]
  (when-let [failure (m/explain (or (get-in compiled-variants [name (or (:op args) (:scope args))])
                                    (get compiled-inputs name))
                                args)]
    (let [errors (mapv (fn [{:keys [in type]}]
                         {:code    "invalid-argument"
                          :path    in
                          :message (case type
                                     :malli.core/missing-key "Required field is missing."
                                     :malli.core/extra-key "Unknown field for this operation."
                                     "Invalid value for this field.")})
                       (take 8 (:errors failure)))]
      (throw
       (ex-info
        "Arguments do not match this tool's contract. Use its required fields and operation-specific arguments."
        {:status 400
         :code "invalid-arguments"
         :errors errors
         :hint
         (case name
           "execute"
           "Authoring: draft ID, revisions, task and index for action/prefix. Saved run: pipeline, version and configuration. Pipeline fixture: pipeline, version, fixture-owning draft ID, fixtureId and fixtureRevision. All starts require instanceId and idempotencyKey."
           "update_draft" "Provide edn OR an op with its required fragment and target. add-task requires taskEdn; add-action requires task and actionEdn."
           "Read this tool's inputSchema; unknown fields are rejected.")})))))
