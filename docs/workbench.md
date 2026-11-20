# Collet Workbench

Workbench is local pipeline authoring for humans and agents. The browser and MCP
clients share durable drafts. HTTP, browser SSE and MCP Streamable HTTP run on one
Ring/Jetty server. Collet, the application and embedded Datalevin run in one JVM.

## Development

Run `bb workbench:prepare` from the repository root before starting source
Workbench. It writes an ignored compatibility manifest under
`target/workbench-knowledge/` using the existing version resolver, without building
UI/JARs or installing dependencies. Regenerate and restart Workbench after package
versions change. Packaged resources take precedence over this development file.
Without a manifest MCP returns null dependency versions and recipes, with
preparation guidance.

Start the worktree nREPL as described in [REPL workflow](repl-workflow.md), then:

```clojure
(require '[collet.workbench.http :as workbench-http] :reload)
(def workbench (workbench-http/start! {:port 8081 :data-dir ".collet"}))
```

Run `npm ci` and `npm run dev` in `collet-app/ui`. Open http://127.0.0.1:5173.
Vite proxies `/api` to port 8081. For backend edits, call `((:stop workbench))`,
reload with `(user/reload)`, then recreate the server. Keep the nREPL JVM running.

## Packaged application

With JDK 25, Clojure CLI, Babashka and Node.js 22:

```sh
bb build collet-app
java --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  --enable-native-access=ALL-UNNAMED \
  -jar collet-app/target/collet.jar --ui
```

Open http://127.0.0.1:8081. `--port 8090` selects another port.
`COLLET_DATA_DIR` selects the database/artifact directory (default `./.collet`).
UI assets, DSL guides, schemas and all repository action descriptions are bundled
in the JAR. They work outside the checkout. Optional action implementations remain
separate dependencies. Existing headless `-s` / `-c` invocation is unchanged.

## Build, check and run

Start from a new pipeline, the orders example, a saved version or an exact run
version. Build opens the canvas and selecting a task opens its actions, links,
settings and results. An action opens a focused source editor beside Inputs and
Result. Add action beside results opens a searchable catalogue with examples,
parameter constraints, dependency availability and external effects.

Source and action editors edit the same EDN text. Autosave accepts incomplete and
invalid text, preserving comments, tagged values and unknown fields. Structural
edits regenerate formatted EDN; inspect their diff in History. Ambiguous ranges
redirect editing to Source. Invalid text retains the last representable graph.

Generated definitions and EDN result previews use zprint's Clojure community style,
with aligned map values, no map commas and an 88-column target. Use **Format** or
**⌘/Ctrl+Shift+F** in a definition, action or configuration editor to format existing
text. Comments, tagged literals and key order are preserved. Formatting is one
undoable edit; malformed syntax reports an error and leaves the text intact.

| Control | Meaning |
| --- | --- |
| Check | Passive DSL validation. Never executes, evaluates or installs dependencies. |
| Run action | Execute one action using valid earlier context; the first action executes setup. |
| Run to here | Execute setup and the selected action prefix. Upstream tasks never run implicitly. |
| Run task | Execute complete retry, setup, iterator, parallel and return semantics. |
| Save version | Compile and save an immutable pipeline version without running it. |
| Run version | Start a fresh normal run of an exact saved version. |
| Save & run | Save the selected source revision and run that exact version. |

Running prepares its selected source/configuration revisions automatically. Missing
prerequisites identify what must run first. Failed preparation preserves source and
previous results; it never silently runs older text. Preparation can load declared
dependencies and perform action preparation, including opening queues.

Runs execute real code. HTTP requests, SQL, queues and file sinks can change external
systems. The displayed 100-row sample does not limit source reads or writes.
HTTP actions return a response map; use `:return [:body]` for decoded records.

Earlier results remain visible as previous samples until preparation proves their
prefix unchanged. Changed suffixes and downstream contexts are invalidated. Normal
pipeline runs never reuse scratch results. Results and execution records identify
their producing source/configuration revisions.

Adding the first dependant or removing the last can change a task's output handoff.
When its retention requirement changes, that task and affected downstream results
become stale. Run the producer explicitly before using its output again. Adding
another dependant to an already retained output preserves it.

Actions skipped by `:when` do not require their configuration inputs. Conditions
are evaluated during execution, including conditions based on earlier action results.
Workbench passes the selected configuration unchanged; the orders example creates
its retry-attempt state in task setup, shared across retries and reset for each execution.

## Shared drafts and history

Draft IDs are stable across clients and application restart. Raw source and all
accepted source revisions live in Datalevin, separately from saved versions. Saving
unchanged pipeline content reuses its existing immutable version. Opening a run for
editing never changes that historical run.

Opening or previewing a saved pipeline uses a temporary session and creates no
durable draft. The first source or configuration edit creates one draft, retaining
its preview results and original source. Reopening an unchanged saved pipeline does
not add drafts. Existing drafts remain available until explicitly deleted.

In draft source, `:version` refers to an origin version. Saving unchanged origin
content reuses that version; edited content receives the next version from the
Store. Repeated edits and saves work even when the source still names an older
version. Saving never rewrites the raw draft text or its source history.

Source writes require an expected revision. Concurrent writes yield an explicit
conflict: local text stays available for comparison and deliberate reconciliation.
Agent changes refresh the current draft without changing selection or taking focus.
History shows author, summary and source comparisons. Restore creates a new revision
and preserves intervening history. Client attribution describes the local transport;
it is not an authenticated user identity.

Browser storage contains UI preferences and recovery copies of unacknowledged source.
The switcher verifies cached drafts with the server, including after reconnect.
Deleted drafts disappear from Working drafts. Any browser-held raw source remains
under **Recovery**; choose **Recover text** to open it in a temporary preview.
Reloading never recreates a deleted draft automatically. Configuration is excluded
from browser storage.

Configuration and its revision, compiled contexts, scratch values and action history
remain in application memory. After restart, supply configuration explicitly (an
empty map is valid) and prepare/run again. Closing a tab or MCP session never deletes
a draft. Closing scratch state releases its results. Explicit draft deletion removes
its source history and scratch state, preserving saved versions and runs.

Runs mode shows durable run/task history and the exact definition used. Pause/resume/
stop apply only to runs owned by this Context. Pause prevents scheduling new tasks;
already running tasks can finish. Retained outputs may become unavailable after
release or process shutdown. Logs and Metrics load only when selected. Detailed
logs retain at most 1,000 events or 1 MiB per execution, with a 16 MiB application
cap. Cursor gaps and dropped counts are explicit. Export retained logs as NDJSON
when needed. Run/task status, timings, retry counts and available output counts
persist. A Workbench μ/log publisher translates core traces into action logs and
metrics. Open Logs and Metrics panels refresh as events arrive. Delivery is
asynchronous and action totals may be incomplete under load. Workbench captures
bounded inputs and results while each action executes, so samples are available
even when the publisher falls behind. Samples and action metrics end on restart.
Action durations measure
invocations, so deferred lazy work can appear in task time instead. Inspect in
Portal uses the 64 most recent bounded action samples kept in memory.

Samples remain bounded to 100 rows, 30 columns, 50 map entries, six levels,
2,000 projected values, 32 KiB of strings and 50 KB of EDN. Truncation and deferred
sequences are labelled. Passive inspection never realizes a live lazy sequence.

## Delete unused work

Open **Manage** in the top bar or **Manage pipelines and drafts** in the switcher.
Filter the list, then delete a draft, one saved version, or an entire pipeline.
Cancel leaves everything unchanged. The confirmation names the selected item and
states which history will be removed.

Deleting a draft removes its source revisions and preview context. Saved versions
and runs survive. Deleting a saved version removes that definition, its run/task
history and internal output directories. Deleting a pipeline does this for every
saved version. Working drafts and files written to external destinations survive.
Deletion is permanent. Stop affected runs and wait for their workers first.

Draft deletion compares the source revision. Whole-pipeline deletion compares the
latest saved version; concurrent saves return a conflict instead of deleting new
work. Deleted version numbers stay reserved, so old links cannot point at different
code. A later save uses a new version number. No automatic age-based cleanup runs.

MCP exposes `collet_delete_draft` (`id`, `revision`),
`collet_delete_pipeline_version` (`name`, `version`), and
`collet_delete_pipeline` (`name`, `latestVersion`). Request deletion only for items
the user selected. Browser and MCP use the same deletion services.

## Agent connection

Open **Connect agents** and copy the terminal command for Codex or Claude Code.
Each command includes the running Workbench instance's Streamable HTTP endpoint.
Run the Claude Code command from the project folder where you use Claude Code.
Start a new agent session and use `/mcp` to check that `collet` is connected.
Use **Copy draft reference** to point the agent at your current draft, task, or
action. See the [agent guide](agent-authoring.md) for the authoring workflow.

MCP supports protocol versions `2025-06-18` and `2025-11-25`. Tools cover knowledge,
fragment validation, saved definitions, shared drafts/history, preparation,
configuration, bounded input inspection, asynchronous execution and run controls.
Every tool name starts with `collet_`. Resources use `collet://docs/`,
`collet://actions/` and `collet://schemas/`.

Routine agent responses exclude configuration values. The application executes
trusted developer code: actions can intentionally return any data they access.
Validation distinguishes structural errors, warnings and runtime checks that remain
deferred. Inspecting inputs never runs their upstream dependencies.

Execution starts require the current application instance ID and an idempotency key.
A repeated identical start returns the existing execution. Reusing the key for other
arguments fails. Keys belong to the current application instance; after restart,
inspect durable runs before explicitly starting again. Disconnecting or cancelling
an MCP request never implicitly stops a pipeline. Use explicit run controls.

The server binds to loopback and checks Host and Origin. Native clients may omit
Origin. This feature does not provide remote authentication or distributed workers.

## HTTP interface

POST requests require `X-Collet-Workbench: 1`; definitions travel as EDN strings.
Bodies are limited to 512 KiB and source strings to 262,144 characters. Source writes use `revision`;
configuration writes use `configurationRevision`.

Draft snapshots and input-context responses also expose `scratchRevision`. It is
`null` when scratch state is absent and changes when execution invalidates context,
finishes or fails, and when prepared state is edited or reset. It belongs to the
current scratch session, not durable source history. Cache Inputs using instance,
draft, source, configuration and scratch revisions; discard responses for an older
selection or revision. Source writes still use only the source `revision`.

| Endpoint | Purpose |
| --- | --- |
| `GET /api/health`, `/api/catalog` | Instance/connection information and shared catalogue |
| `GET /api/actions`, `/api/documentation/:id`, `/api/schemas` | Bundled authoring knowledge |
| `POST /api/validate` | Passive `{kind, edn, pipelineEdn?}` checks |
| `POST /api/format` | Format `{edn}` source without evaluation or saving |
| `POST /api/sessions` | Create a draft from optional `{edn, configEdn, origin}`; `preview: true` keeps it temporary until the first edit |
| `GET /api/sessions/:id` | Raw source and current scratch snapshot |
| `POST /api/sessions/:id/source` | Revision-checked text or structural edit |
| `POST /api/sessions/:id/configuration` | Replace transient configuration |
| `POST /api/sessions/:id/apply` | Explicit preparation |
| `GET /api/sessions/:id/history[/:revision]` | Change summaries or exact historical text |
| `POST /api/sessions/:id/restore` | `{revision, fromRevision}` creates a new source revision |
| `POST /api/sessions/:id/save` | Save `{revision}` as an immutable pipeline version |
| `GET /api/sessions/:id/inputs/:task/:index` | Bounded context and prerequisite notices |
| `POST /api/sessions/:id/execute`, `/api/executions` | Start execution and return its ID |
| `GET /api/executions/:id` | Preparation/execution progress and bounded results |
| `GET /api/runs/:id/diagnostics` | Focused `view=summary`, `result`, `events` or `metrics` |
| `GET /api/drafts`, `/api/pipelines` | Paged cleanup lists |
| `POST /api/pipelines/:name/delete` | Delete `{version}` or all versions with expected `{latestVersion}`, including associated runs |
| `POST /api/sessions/:id/reset`, `/close`, `/delete` | Reset results, close scratch state, explicitly delete draft |
| `GET /api/definitions/:name/:version` | Exact saved source |
| `GET /api/runs/:id`, `/api/runs/:id/outputs/:task` | Run, tasks and available outputs |
| `POST /api/runs/:id/pause`, `/resume`, `/stop` | Explicit owned-run controls |
| `GET /api/events` | SSE; reconnect requests authoritative snapshots |
| `POST /api/inspect` | Open the selected value in Portal |

Execution commands include `instanceId`, `idempotencyKey` and `scope`. Authoring
execution adds draft ID, source/configuration revisions, task and optional action
index. Pipeline execution adds pipeline name, saved version, and an explicit
`configuration` object: `{source: "draft", id, revision}` or `{source: "empty"}`.

Selection uses hash URLs. Back and Escape preserve text. Keyboard controls remain
Cmd/Ctrl+K (commands), Cmd/Ctrl+S (save version), Cmd/Ctrl+Enter (run selection),
Cmd/Ctrl+E (canvas/source) and Alt+Up/Down (previous/next action).

## Validation

Keep the worktree REPL attached. Use an isolated data directory for tests.

```sh
bb test:module collet-core
bb test:module collet-app
bb test:unit
bb test:integration
bb verify
bb release:plan
```

In `collet-app/ui`:

```sh
node --test tests/draft.test.mjs
npm run test:ui
npm run build
npm run test:sites
```

Set `WORKBENCH_URL` to a packaged server to test the bundled UI. Source-only app
checks use `clojure -M:test -e :integration` from `collet-app/`. Docker integration
coverage includes an agent building an HTTP-to-Postgres pipeline through MCP.
