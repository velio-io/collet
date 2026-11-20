# Authoring with humans and agents

Call `collet_get_overview` first. Keep its `instanceId`. It describes the workflow,
provides a minimal example, and lists recent shared drafts. MCP sessions do not
own drafts; reconnecting does not delete work or stop execution.

## Action selection and portability

Prefer catalogue built-in actions, then `:clj/*` core functions. Reserve inline
EDN functions for domain logic those actions do not cover. Discover HTTP, S3 and
other integrations before writing custom I/O code.
Read `generic-actions` with `collet_read_documentation` for multi-provider LLM and
Flint SPARQL actions. The `dash-images` guide shows them in a complete pipeline.

Declare every external library with pinned Maven `:deps/:coordinates` from
`collet_describe_action`. `:requires` loads namespaces; `:imports` exposes Java
classes. Neither supplies missing libraries. `classpath-present` describes only
the current process. Successful Workbench or saved-run execution does not prove
that a pipeline will run in a clean standalone deployment.

EDN function forms and qualified function symbols are durable data. Live function
objects and closures cannot be saved; file-backed code is another option, not a
requirement for inline EDN functions. Qualified symbols require their namespace
and dependency in the deployment.

Source Workbench needs `bb workbench:prepare` before startup for pinned recipes.
Regenerate and restart after package-version changes. Without that manifest,
`version` and `dependencyEdn` are null; prepare it instead of guessing a version.

## Authoring workflow

1. Search `collet_list_actions` by purpose; describe a selected exact type.
2. Create a draft with `edn` and optional `configEdn`, or read an existing draft.
   Source and configuration revisions are separate. Configuration stays in memory.
3. Edit with the expected source `revision`. Incomplete EDN is preserved. Use full
   source replacement or a structural operation with its required EDN fragment.
4. Call `collet_validate`. Check never evaluates code, loads dependencies or runs
   actions. Read errors, warnings and deferred checks separately.
5. Inspect actual inputs with `collet_inspect_context`. Missing upstream tasks
   must be run explicitly. Previous samples are labelled and cannot supply current
   executable context. Representation labels distinguish rows from dataset batches.
6. Call `collet_execute` with `instanceId`, a unique `idempotencyKey`, draft `id`,
   `revision`, `configurationRevision`, `scope` and `task-name`. Use `action` with
   `index`, `prefix` to run to an index, or `task` for complete task semantics.
7. Inspect `collet_get_execution` by ID. The default is small status; request
   `view: result`, `events`, or `metrics` for detail. `waitMs` waits up to 10000 ms
   for completion or a change after `afterSequence`. Disconnect/cancellation does
   not stop the pipeline. Run control is explicit.
8. Save the exact draft revision with `collet_save_draft`. For an authorized live
   run, execute
   `scope: pipeline`, `pipeline: savedPipeline.name`, `version: savedVersion`, and an
   explicit `configuration`: `{"source":"draft","id":"DRAFT_ID","revision":0}`
   or `{"source":"empty"}`. Saved runs start fresh and never reuse scratch results.
9. Before reporting completion, read the [production checklist](pipeline-production-checklist.md)
   with `collet_read_documentation` and `id: production-checklist`. Report the evidence
   you collected and leave unavailable checks pending.

Retry a lost execution-start response with the same arguments and key. Changing
arguments requires a new key. After restart obtain a new instance ID and inspect
run history before explicitly starting again.

A conflict returns the current revision. Read source, compare, reconcile and
resubmit; never blindly overwrite it. `get_draft` accepts exact historical
revisions and `view: diff`. Restoring source creates a new revision.

Use list cursors and focused reads. Documentation supports search, outlines and
sections; resources and tools expose the same material. Configuration reads show
keys, types and availability, not values. Detailed events are bounded and transient;
action metrics can arrive after completion and can undercount under load. Normal run/task summaries and task retry counts persist. Run executes real external effects. A bounded
sample is a display limit, not a limit on data read, processed or written.

Lists and samples default to 20 items, with a maximum of 100. Status and mutation
receipts fit 4 KiB; ordinary detail fits 16 KiB (`maxBytes` can request up to
64 KiB). Explicit source reads are complete. Source strings accept at most 262,144
characters and HTTP bodies at most 512 KiB. Follow `nextCursor` to continue lists,
documentation sections or retained events.

## Explicit cleanup

Use `collet_delete_draft` with its current source revision to remove an unwanted
draft and its edit history. Saved versions and runs remain.

Use `collet_delete_pipeline_version` with `name` and `version` to remove one saved
definition and all associated run/task history and internal outputs. Use
`collet_delete_pipeline` with `name` and `latestVersion` to remove every saved
version and associated run history. List versions first; a concurrent save makes
the whole-pipeline command fail with a conflict. Stop affected runs and wait for
workers before deleting. Working drafts and external output files remain.

Deletion is permanent and requires an explicit user-selected target; do not infer
that an old draft or version is disposable. Deleted version numbers are never
reused. Existing drafts can be saved again as a new version.

## Structure a pipeline around data handoffs

Start with the stages that produce reusable results. Let one task list records
from an API, let later tasks consume those records through `:inputs`, and keep
independent branches separate so they can run in parallel. Use `:setup` for work
that runs once per task and `:when` for individual conditional actions.

Core actions are bundled, including mapper, fold, and slicer. Their empty
`dependencyEdn` is intentional. Merge pinned recipes for optional packages.
Search for HTTP pagination, managed authentication, polling, or file/image I/O
before writing wrappers. Use slicer partitioning to turn file rows into batches
for a bulk API call. Mapper visits one row at a time. Fold accumulates results
in memory. To emit rows independently from each iteration, use a task return
selector and `:state-format :flatten`. These choices also work when the input
arrives as Arrow dataset batches.

Put the configuration contract in the pipeline's optional `:config-spec`
Malli schema. A live playground action, prefix, or task uses the same complete
contract as a production run. Literal Malli `:default` values fill omitted
configuration fields before validation. A passive Check validates the schema form and
DSL without calling services. A fixture result tests behavior against synthetic
data. A live verification checks the real service and deployment.

## Run with fixtures

In the task or action toolbar, choose **Fixtures…**. Name the case and enter
or import EDN, then save it and select the scope to run. The same case is
available through `collet_list_fixtures`, `collet_get_fixture`, and
`collet_save_fixture`. To run it with `collet_execute`, supply `fixtureId`
and `fixtureRevision` alongside the draft revision and target. Use `action`,
`prefix`, or `task`. Fetch the result using `collet_get_execution`.

Use `scope: "pipeline-fixture"` to test an exact saved version with normal task
ordering and dataset handoffs. Pass `pipeline`, `version`, the fixture-owning
draft's `id`, `fixtureId`, `fixtureRevision`, `instanceId`, and
`idempotencyKey`. The HTTP and file packages used by real fixture adapters
must be on the Workbench classpath. A fixture run uses its own temporary Store
and leaves no normal run history.

Fixture EDN supports:

- `:config` and `:inputs` for synthetic configuration and upstream results.
  Input keys are task names. Supply the upstream task's output shape.
- `:state` for prior action results. For a single iterator action, seed
  mapper state under the mapper's action name, e.g. `{:each {:current {:id 1}}}`.
  `:bindings` overlays state and can provide `:$parallel/item`.
- `:mocks` keyed by action name. Use `{:value ...}` for a repeated result
  or `{:responses [... ...]}` to consume a sequence. Supply the result
  before the action's `:return` selector. To supply a response stream, use
  `:collet.fixture/body-base64` in the response map in place of `:body`.
  To mock a paginated HTTP response, use `:collet.fixture/body-dataset-batches`
  with a vector of batches, each containing a vector of row maps.
- `:allow` is a set such as `#{:normalize :calculate-report}` of reviewed
  custom actions and parameter expressions that
  should execute. Catalogue actions that contact a service or have filesystem
  effects require a mock. Reviewed image inspection can read a synthetic
  stream. Reviewed code executes with the JVM's privileges.

Use repeatable mock values in parallel tasks. Ordered response sequences are
for sequential action invocations.

Full pipeline fixtures use actual upstream outputs. Omit `:inputs`, `:state`,
and `:bindings`. Use `:http` for responses at the HTTP transport boundary.
The URL includes any query string already present on it, while `:query-params`
matches the separate request options. Each matching request consumes one
response. An unexpected request, exhausted response sequence, or unused
response on a successful run fails the fixture.

```clojure
{:config {:org-name "fixture-org" :gh-token "synthetic-token"}
 :allow #{:report-fields :pull-request-rows :report}
 :http {:list-repositories
        [{:request {:method :get
                    :url "https://api.github.com/orgs/fixture-org/repos"
                    :query-params {:per_page 100}}
          :responses [{:status 503 :headers {"Retry-After" "0"}}
                      {:status 200 :json []}]}]}
 :temp-files #{:write-report}
 :expect {:status :completed
          :rows {:repositories []}
          :csv {"total-prs-by-member.csv" {:header ["user-id" "pulls-count"]
                                          :rows []}}}}
```

`:temp-files` invokes the real file sink for CSV data and literal text. Collet
uses the action's filename and supplies a private temporary folder for the
execution. Use the result view to read `outputDirectory` and the file receipts.
The folder lives until Workbench shuts down normally. `:expect` defaults to
`:completed` and also supports `:failed` with an optional `:error-code`.
Row assertions compare up to 100 expected rows exactly, including duplicates.
CSV assertions also check header order and read files up to 1 MiB.

Use `collet_run_fixture_suite` with `instanceId`, `idempotencyKey`, and
`cases` to run up to 20 saved cases. Each case has the same target and revision
fields as a fixture execution, with the instance and key supplied once for the
suite. Poll the suite ID via `collet_get_execution` for the pass count and
child IDs, then inspect any child result. A fixture with the requested
failure status and error code passes.

Fixture runs use their own configuration. The result shows
the source and fixture revisions and the actions the runtime substituted.
Fixture EDN is durable and exportable. Enter synthetic values only.
