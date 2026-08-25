# Issue #49 implementation plan: pull-based work-unit claiming

**Issue:** [#49 — Introduce pull-based work-unit claiming with leases and fencing](https://github.com/velio-io/collet/issues/49)
**Prepared against:** `main` at `b082c50` (after #44 and #48)
**Owning package:** `collet-core`
**Implementation style:** contract tests first; one focused, breaking core change

## Conclusion

**The issue needs alignment before implementation starts.** Its architectural
intent is sound and matches `COLLET_NEXT.md`, but the current codebase still has
an in-memory, per-run scheduler and several required distributed-execution
contracts are absent or conflict with prior semantics. Implementing only
`WorkUnit`/`Attempt` entities would not satisfy the acceptance criteria safely.

The mandatory pre-code decisions are:

1. define an authoritative Datalevin clock and prove the required transaction
   primitives for both embedded and client/server connections;
2. define how a replacement worker obtains a run's configuration without
   persisting configuration or secrets;
3. reconcile worker crash recovery with current `Context.close`/`stop` and
   `:keep-state` ownership semantics;
4. explicitly preserve global `:max-parallelism` as well as local worker
   capacity;
5. add a minimal task capability-requirement syntax and a client/server
   topology; and
6. add a schema-version/migration path before introducing durable entities.

Until (1) and (2) are resolved, a claim/reclaim implementation can look correct
in an embedded test while being unable to safely execute an expired claim in a
replacement process.

## Current-state assessment

### What is already in place

* #44 supplies immutable compiled plans, `PipelineRun`/`TaskRun` data in
  Datalevin, direct dependency references in `:task/inputs`, and a public
  `collet.store/Store` protocol.
* #48 supplies immutable run-owned Parquet/EDN artifacts, direct
  `[:artifact/id ...]` task outputs, staging/manifest validation, atomic task
  completion, and terminal artifact cleanup.
* `collet.core/start` creates all task runs atomically enough for the current
  model, compiles executable task functions in the caller process, and retains
  configuration, futures, callbacks, results, and errors only in `Context`.
* The persisted task-input graph is sufficient to derive direct downstream
  readiness. No second graph, task ordinal, partition entity, cache lookup, or
  lineage-edge entity is needed.
* `collet-core` already depends on Datalevin 1.0.0 and has isolated Store,
  artifact, and runtime tests.

### What must be replaced or extended

| Current implementation | #49 requirement | Required change |
| --- | --- | --- |
| `run-scheduler!` polls one run and calls `schedule-ready-tasks!`; it is execution authority. | Workers pull and atomically claim durable work. | Remove the scheduler from the execution path; add a worker loop that claims from the Store. |
| `TaskRun` moves `:waiting → :running → terminal` through unrestricted `update-task!`/`complete-task!`. | Every execution transition is fenced by a current lease. | Keep task runs as aggregates, but make claim, renewal, completion, failure, readiness, and terminalization Store commands that verify lease authority. |
| `Context` contains the only compiled task functions and the submitted config. | A replacement worker can reclaim expired work. | Add a non-secret execution-resolution boundary; a second process must rebuild the selected task and resolve its configuration independently. |
| All timestamps use `System/currentTimeMillis` in the caller. | Lease decisions use the Datalevin-side clock. | Obtain transaction time inside the Datalevin authority; never accept worker time in a claim/renew/terminal command. |
| `:max-parallelism` is calculated from in-memory scheduler reads for one run. | Pull workers must not oversubscribe a run or their local executors. | Enforce per-run active leases transactionally and separately limit each worker's submitted futures. |
| `close` calls `stop` on active runs and cleanup only knows local futures. | Killing a worker must permit reclaim; files cannot be deleted while readers may remain. | Separate worker shutdown from durable run cancellation; gate cleanup on durable lease quiescence. |
| The core dependency is `datalevin-embedded`; `datalevin/store` accepts a directory only. | Acceptance requires embedded **and client/server** atomic-claim evidence. | Make the Store accept an already-created Datalevin connection/client (or an explicit connection factory) and add the supported client/server dependency and integration fixture. |
| No Store schema-version entity exists, despite #44's intended compatibility rule. | New WorkUnit/Attempt schema must safely open existing #44/#48 databases. | Add a version entity, an idempotent `1 → 2` schema/data migration, and a newer-version rejection test. |

### Semantics that must remain true

* Each run computes its own work. There is no cache, reuse, predecessor
  carry-forward, `:reused` outcome, or partition/record-level schedule.
* A work unit maps one-to-one to one existing task run. A task's internal
  `:parallel` work remains internal task behavior, not durable partition work.
* Artifacts remain run-owned and are staged before a fenced durable completion;
  Datalevin stores metadata and references, never payloads.
* The existing `:skip-on-error` behavior remains: a skippable failing task is
  `:failed`, descendants are `:skipped`, and the run may finish `:done`; a
  non-skippable failure fails the run. In-action `:retry` remains unchanged;
  durable retry/backoff/deadline policy remains #51.
* Pipeline plans remain immutable, and functions/dependencies are reconstructed
  by a worker rather than persisted as closures.

## Required issue clarifications and recommended resolutions

These are not optional polish items; they determine whether #49 is correct.
The issue description should be amended (or linked decisions recorded) before
runtime code is changed.

| Gap or conflict | Why it matters | Recommended #49 decision |
| --- | --- | --- |
| **Authoritative time API is unspecified.** | A local `System/currentTimeMillis` lets a skewed worker extend/complete a lease incorrectly. The present Store uses that clock everywhere. | First prove the supported Datalevin 1.0 embedded and server transaction-time API. Encapsulate it in the Datalevin Store only. If it cannot be read and used in the same write transaction, split this into a prerequisite; do not substitute a client clock. |
| **No configuration recovery contract.** | #44 intentionally does not persist config/secrets. A replacement process therefore cannot execute an expired claim that needs config. | Persist only an opaque, validated `:run/execution-ref`; require a worker-local resolver `(fn [execution-ref claim] config)` for recoverable/shared runs. The raw config passed to `start` remains process-local and is never stored. Preserve legacy local runs with an ephemeral resolver, but state that they are not recoverable after the submitting process dies. Prove raw secrets are absent from Datalevin and claim responses. If this API is deferred, narrow #49 to one process and remove the client/server/killed-worker acceptance criteria. |
| **Current close means stop; worker death must mean reclaim.** | `collet.core/close` currently cancels active futures and marks their runs stopped. A worker shutdown cannot do that in a shared pull model. | Distinguish *worker shutdown* (stop renewing/cancel only local futures; do not mutate the run) from explicit `stop run` (durably revoke run work). Retain a compatibility option only if needed, but make shared worker shutdown non-cancelling. |
| **Cancellation is both needed and deferred to #51.** | Existing `stop`, non-skippable failure, and cleanup already need to prevent a current lease from publishing. #51 says cancellation semantics are not yet defined. | #49 implements only minimal terminal closure: `:stopped` and non-skippable `:failed` atomically prohibit new claims and invalidate active publication authority. #51 owns user-facing cancellation policy, deadlines, retry-wait, and retry budgets. State this boundary in both issues. |
| **`:keep-state` has one Context owner today.** | A shared run can complete in a worker other than the submitter; there is no durable owner Context whose close releases retained artifacts. | For #49, retain `:keep-state` only for the submitting/local Context and document that it is not a shared-worker retention feature. Add an explicit durable retention/lease owner only in a later issue if required. Never let an arbitrary worker extend artifact retention indefinitely. |
| **Capability requirements have no syntax or validation.** | The issue requires capability matching but no task field says what is required. | Add task `:requirements` as a set of small, EDN-safe descriptors. Support a keyword and a non-empty vector whose members are keywords/strings; canonicalize and reject duplicates, empty vectors, functions, maps, and arbitrary objects. An empty/missing set matches every worker; a worker is eligible when requirements are a subset of its declared capabilities. No registry, affinity, score, GPU accounting, or priority queue. |
| **Client/server topology is not implemented.** | `datalevin-embedded` plus a directory-only Store cannot prove independent workers against a shared authority. | Add supported Datalevin client/server wiring and a test fixture. #49's shared test may use a shared mounted local artifact root; S3/object-store transport remains #52. Do not claim arbitrary independent local files are shared. |
| **Per-run parallelism is not mentioned in the claim algorithm.** | Preserving README's `:max-parallelism` requires a global transaction-level check; local capacity alone can allow two workers to over-claim one run. | Copy the compiled run's maximum parallelism into immutable run execution metadata, or derive it transactionally from the exact plan. A claim succeeds only when active unexpired leases for that run are below the limit. |
| **Artifact ownership lacks attempt/fence evidence.** | A stale worker can leave a post-rename orphan; #53 must later distinguish same-work-unit evidence without attaching it. | Include `:artifact/work-unit-id`, `:artifact/attempt-id`, and `:artifact/fence` in the staged manifest and durable metadata for #49 writes. Validate them against the claim in `complete-work!`. They are provenance/evidence, not cache keys or a reuse mechanism. |
| **Cleanup assumes local futures.** | A stale or revoked remote worker may still be reading an input artifact after its run is terminal. | Retract/delete ordinary artifacts only after all work units are terminal and all previously leased attempts are quiescent/expired according to authoritative time. A physical delete failure remains an invisible orphan for #53. |
| **Empty pipelines are unspecified.** | A run with no tasks has no work to claim and would otherwise never finish. | Atomically create it as `:done` (with a finish time) or have one transactionally idempotent terminalization path do so immediately; test it explicitly. |

## Target model

### Entity ownership

```text
PipelineDefinition ──< PipelineRun ──< TaskRun ──1── WorkUnit ──< Attempt
                                      │                    │
                                      └── direct inputs     └── publishes Artifact
```

`TaskRun` remains the aggregate/API-compatible lifecycle entity. `WorkUnit` is
the independently claimable item. There is exactly one stable WorkUnit per
TaskRun for this issue. `Attempt` is immutable evidence of one accepted lease;
reclaim creates a new Attempt and never overwrites the previous one.

### States and aggregate mapping

| WorkUnit state | Meaning | TaskRun state |
| --- | --- | --- |
| `:blocked` | At least one direct input is not successfully completed. | `:waiting` |
| `:ready` | Eligible for a worker that satisfies requirements. | `:waiting` |
| `:leased` | One current, unexpired Attempt owns execution authority. | `:running` |
| `:succeeded` | A current fence completed atomically with its task/artifact. | `:completed` |
| `:failed` | A current fence recorded a task failure. | `:failed` |
| `:cancelled` | Run closure or upstream skip prevents execution. | `:interrupted` for an in-flight/stop cancellation; `:skipped` for upstream skip |

A lease expiry is not a separate WorkUnit terminal state. On a successful
reclaim transaction, the old attempt becomes `:expired`, its active lease is
replaced, the WorkUnit remains `:leased`, and the new attempt receives a larger
fence. An expired unit is merely eligible to be claimed again. A completion,
renewal, or failure after expiry is rejected even if no other worker has yet
claimed it.

Attempt states are `:leased`, `:succeeded`, `:failed`, `:expired`, and
`:cancelled`. A rejected stale command does **not** rewrite the old Attempt; it
returns a classified authority error for the caller/telemetry layer.

### Durable fields and invariants

Add indexed/schema fields equivalent to the following; exact Datalevin schema
syntax is determined by the time/transaction spike.

| Entity | Required fields | Invariants |
| --- | --- | --- |
| `PipelineRun` | existing identity/status/timestamps; immutable max parallelism; optional opaque execution reference | The run references one exact pipeline revision. Raw config, secrets, closures, executor handles, callbacks, futures, task output values, and exception objects are absent. |
| `WorkUnit` | UUID; unique TaskRun reference; status; canonical requirements; current-attempt ref; active owner/token/fence/expiry summary; latest issued fence and attempt number; timestamps | Exactly one per TaskRun. The active summary exists only while leased. Fence and attempt number only increase. |
| `Attempt` | UUID; WorkUnit ref; number; owner id; random lease token; fence; start/renew/expiry/finish timestamps; status/outcome; sanitized error | A new entity per accepted claim. `owner + token + fence` is required for renewal, completion, and failure. Expired/terminal attempts are immutable evidence. |
| `Artifact` | existing #48 fields plus WorkUnit UUID, Attempt UUID, and fence | Its run/task/work-unit/attempt/fence must match the completing claim. The manifest has the same bounded evidence. |

Use UUIDs for run, task, work-unit, attempt, owner, token, and artifact
identity. Fence, attempt number, durations, and timestamps are positive longs.
Tokens are unguessable random values; they are not logged in full or exposed in
exception data.

### Claim and fence rules

A Store-level claim command receives only validated worker identity,
capabilities, requested lease duration, and a bounded candidate limit. It must
perform this as **one short Datalevin write-authority transaction**:

1. select a ready or expired candidate and prefilter by canonical requirement
   subset;
2. read transaction-authoritative time;
3. re-read candidate state, direct successful inputs, run status, matching
   requirements, and active-lease count for the run;
4. reject candidates whose run is not `:running`, whose inputs are not all
   completed, whose capacity is exhausted, or whose active lease is still
   valid;
5. if reclaiming, finalize only the prior active Attempt as `:expired`;
6. increment the WorkUnit's issued attempt number and fence;
7. create a fresh Attempt/token, write the current lease summary, and change
   the aggregate TaskRun to `:running`; and
8. return only the claimed unit, lease authority, direct inputs/output refs,
   exact pipeline/task execution description, and opaque execution reference.

Datalog remains the ready-work queue. It is acceptable initially to query a
bounded set of `:ready`/expired units and recheck small requirement sets in the
transaction; no ordering, fairness, or external queue is promised. A later
rebuildable KV projection needs measured justification and is out of scope.

`renew-work!`, `fail-work!`, and `complete-work!` all use the same
Datalevin-side time and require the current WorkUnit/Attempt/owner/token/fence,
an unexpired lease, and an active run. They reject with a small
`:collet.error/lease-lost` exception when any condition is false. The error may
identify a safe reason such as `:expired`, `:fence-mismatch`,
`:not-current`, or `:run-terminal`, but must not expose the valid token.

`complete-work!` additionally validates the physically staged #48 artifact and
then atomically:

1. attaches the artifact and direct task-output ref;
2. finalizes the Attempt and WorkUnit as successful/computed;
3. completes the aggregate TaskRun;
4. moves direct blocked dependants to ready only when every input TaskRun is
   completed successfully; and
5. derives terminal run status when every WorkUnit is terminal.

No process-local result is visible to a dependant before this transaction. A
worker that loses authority after staging leaves a bounded orphan; it must not
attach its output and #53 owns later adoption/cleanup.

### Worker lifecycle

Introduce a worker owned by a Context, with a stable generated or supplied
worker UUID, declared capabilities, an explicit `:max-concurrency`, lease
length, renewal interval, and poll interval. The default local Context starts
one compatible local worker so existing `start` usage remains executable.

The loop is deliberately small:

1. calculate `max-concurrency - active-futures`;
2. claim no more than that many units, one atomic claim at a time;
3. submit each accepted claim immediately to the Context executor and track it
   before execution begins;
4. renew on an independent bounded timer while the future is active;
5. rebuild the selected task from the immutable plan, resolve config locally,
   read only direct artifact inputs, execute existing actions, stage an output,
   then fenced-complete or fenced-fail; and
6. stop renewing and remove the local future before polling again.

A renewal rejection sets local lease loss, best-effort interrupts the task, and
prevents publication/complete. An action may ignore interruption; fencing is
still the correctness mechanism. Workers never queue extra claimed work behind
their executor and never hold a claim merely waiting for a local slot.

A process can execute work it did not submit. The Run handle therefore polls
durable terminal state rather than relying only on a local scheduler promise.
Callbacks are local notifications emitted by the worker which commits the
transition; they are not a distributed callback guarantee.

## Test-first implementation sequence

Each phase begins by adding the listed failing tests, then adds the smallest
production change to make them pass. Do not replace this with an end-only,
sleep-heavy integration suite.

### Phase 0 — prove Datalevin authority and topology

**Tests first** (`collet-core/test/collet/store/datalevin_test.clj` plus a new
client/server integration namespace):

* an embedded transaction can select, validate, and conditionally mutate one
  candidate atomically under concurrent callers;
* the same Store commands work through two independent client/server
  connections against the write authority;
* transaction time, lease expiry, and renewal use authority time rather than a
  caller-supplied/skewed timestamp;
* the supported transaction mechanism can reject stale compare-and-set/
  ensure conditions without partially creating an Attempt; and
* opening a #44/#48 fixture database upgrades once, reopening does not mutate
  it again, and a newer schema version fails before mutation.

**Then implement:** a small private Datalevin transaction helper layer, a
connection/client construction seam in `collet.store.datalevin`, the version
entity/migration, and the client/server test fixture/dependencies. Confirm the
actual Datalevin 1.0 API from the worktree nREPL before choosing any server-side
function, `:db/ensure`, CAS, timeout, or transaction-time form. Do not use an
unverified client-side substitute.

**Exit criterion:** both embedded and server tests demonstrate one authoritative
write path; otherwise split topology/time work into a prerequisite and amend
#49 before proceeding.

### Phase 1 — define and persist WorkUnit/Attempt data

**Tests first:**

* run creation creates exactly one unique WorkUnit for every TaskRun, with
  roots `:ready`, dependants `:blocked`, immutable task/run links, and no
  stored config/payload/runtime objects;
* repeated reads and close/reopen preserve work-unit IDs, requirements, and
  zero attempts;
* fan-in/fan-out and disconnected roots have the correct initial states;
* an empty pipeline becomes terminal without a claim;
* valid/invalid requirements normalize deterministically; an empty requirement
  set matches every worker and a missing capability yields no claim; and
* schema inspection includes only the intended durable attributes and rejects
  invalid types/unknown attributes.

**Then implement:** extend `Store` with creation/read operations for work units
and attempts, extend the Datalevin schema/mappers/pull patterns, create work
units in the existing `create-run!` transaction, and add task requirement
validation/normalization to `collet.core` compilation. Keep TaskRun status
shapes compatible with current public reads.

### Phase 2 — claim, renew, expiry, and fence Store commands

**Tests first:**

* two concurrent claimers obtain exactly one non-nil authority for one unit;
  winner data has a new UUID token/attempt and fence `1`;
* a second unit can be claimed independently, while the same run never exceeds
  its persisted `:max-parallelism` across two workers;
* a matching worker may renew only its current, unexpired owner/token/fence;
  wrong owner, wrong token, wrong fence, terminal run, and post-expiry renewal
  are rejected without mutation;
* a completed/failed unit cannot be claimed again;
* after expiry, reclaim creates a new Attempt, marks the former one expired,
  increments both attempt number and fence, and preserves every historical
  field after reopen;
* late renewal, fail, and completion by the old fence are rejected both before
  and after the next claimant succeeds; and
* expiry is rejected even when no second worker has claimed yet.

Avoid assertion timing based solely on `Thread/sleep`: use a short controlled
lease only where the proven Store clock allows it, bounded wait helpers, and
transaction evidence. Race tests should run repeatedly and use barriers so both
claimers contend for the same ready entity.

**Then implement:** `claim-work!`, `renew-work!`, `fail-work!`,
`complete-work!` and attempt/work-unit query methods on `Store`, with all
state-transition logic inside `collet.store.datalevin`. Deprecate execution use
of generic `update-task!` and `complete-task!`; wrappers/fault-injection test
stores must implement the new protocol methods. Keep each Store method
transactional and bounded—never query IDs and then pull entities one at a time.

### Phase 3 — fenced completion, dependency release, and terminal closure

**Tests first:**

* valid completion atomically persists only a matching staged artifact,
  `:task/outcome :computed`, successful Attempt/WorkUnit, completed TaskRun,
  and direct downstream readiness;
* a stale completion leaves the newer lease, task output, artifact metadata,
  and dependant state unchanged; its physical output remains unattached;
* completion after expiry is rejected before reclaim; a successful retry of the
  current fence releases dependants exactly once;
* a fan-in dependant is not ready until all direct inputs completed, and an
  input that completed without required output produces the existing bounded
  missing-output error rather than a claimable dependant;
* a valid failure retains sanitized Attempt/Task error evidence but no output;
  `:skip-on-error` marks transitive descendants skipped/cancelled and prevents
  their claims; non-skippable failure makes remaining work non-claimable and
  the run fails;
* terminal status is derived once under concurrent final transitions; normal,
  failed, stopped, empty, and already-terminal paths are idempotent; and
* terminal cleanup cannot retract/delete an artifact while a valid or recently
  revoked lease may still read it. Store-release failure preserves metadata;
  filesystem failure leaves only the invisible orphan already assigned to #53.

**Then implement:** replace #48's unfenced `complete-task!` execution path with
`complete-work!`; derive dependency readiness from `:task/inputs` in the same
transaction; add the minimal terminal closure operation for current `stop` and
non-skippable failure; and make `finalize-run!` lease-quiescence-aware. Preserve
artifact manifest validation and extend it with claim evidence. Do not add an
orphan scanner, adoption, retry delay, retry budget, cache, or reconciliation
loop.

### Phase 4 — replace the scheduler with a capacity-bounded worker

**Tests first** (`collet-core/test/collet/runtime_test.clj`, preferably with a
new focused `worker_test.clj` for concurrency):

* starting a normal local pipeline starts no per-run scheduler but its default
  worker claims and executes all ready work through Store commands;
* a worker with capacity `N` never has more than `N` accepted local futures,
  including when the executor is unbounded/virtual-thread based;
* two workers share ready work without duplicate task execution; local capacity
  and global pipeline `:max-parallelism` both hold;
* renewals keep a gated long-running task claimable only by its owner; stopping
  renewal leads to reclaim and blocks the old worker's publication;
* worker execution obtains only direct durable input refs, honors the existing
  task/action/iterator/parallel behavior, and still publishes #48 artifacts;
* a fresh Context/worker can rebuild a stored pipeline and complete a claim
  using an opaque execution reference resolver; raw config and a sentinel
  secret are absent from raw Datalevin data, Store responses, attempt records,
  and logs used by the test;
* closing a worker Context with a gated active task stops local activity but
  does not stop the durable run; another worker completes it after lease
  expiry; and
* `@run`, `pipe-status`, `pipe-error`, pause/resume, explicit stop, callbacks,
  `:keep-state`, and artifact cleanup retain their documented local behavior or
  have an explicit compatibility test documenting the agreed change.

**Then implement:** introduce the worker record/loop, renewal scheduling,
execution resolver, durable Run-handle wait, and Context lifecycle changes.
Delete `run-scheduler!`, `schedule-ready-tasks!`, and `start-ready-task!` only
after their behavior is represented by the worker tests. Keep existing
`execute-action` and standalone `execute-task` non-durable.

### Phase 5 — recovery and client/server acceptance

**Tests first** (mark external-service tests `^:integration`; keep embedded
transaction tests unit-fast):

* worker A claims a gated unit through the client/server Store, ceases
  renewing/is terminated without stopping the run, and worker B reclaims after
  authoritative expiry with a fresh attempt and fence;
* A subsequently tries renew, fail, and complete, each is rejected, and B's
  artifact/output is the only visible one;
* separate worker Contexts connected to the same Datalevin authority and a
  shared mounted artifact root complete consecutive tasks; worker B consumes
  A's committed direct artifact rather than any process-local value;
* close/reopen of Datalevin preserves active/expired Attempt history and leaves
  expired work reclaimable; and
* client/server race tests repeatedly produce exactly one valid lease and do
  not exceed worker or run capacity.

**Then implement:** only test-fixture/deployment wiring discovered in Phase 0.
S3 transport, an object-store cache, HA deployment tooling, worker registry,
autoscaling, priority/fairness, and remote control APIs are explicitly not
added here.

## Edge-case matrix

The final suite must cover this matrix in addition to happy paths.

| Area | Required cases |
| --- | --- |
| Claim selection | no work; only blocked work; paused/failed/stopped run; roots plus dependants; capabilities missing/extra; empty requirements; multiple runs; run already at its parallelism limit; local worker full; candidate becomes terminal between query and transaction. |
| Lease authority | first claim; same-worker renewal; owner/token/fence mismatch; expiry without reclaim; reclaim after expiry; several reclaims; renewal at boundary; stale failure; stale completion; run terminal during work; transaction failure. |
| Attempt history | unique IDs/tokens; strictly increasing fences/numbers; prior attempt becomes expired rather than rewritten; terminal attempts persist after close/reopen and cleanup; no attempt for an unsuccessful race or unclaimable unit. |
| Dependencies | root readiness; chain; fan-in; fan-out; independent roots; direct-only readiness; skippable failure; non-skippable failure; missing/removed output; exactly-once release of each dependant. |
| Artifacts | scalar and dataset completion; no artifact for transient terminal result; staged artifact from stale fence rejected; mismatched task/run/work-unit/attempt/fence metadata rejected; pre-rename failure; post-rename stale orphan; transaction failure; cleanup while readers/leases exist; retained local result; delete failure. |
| Worker lifecycle | capacity 1/N; global run cap across workers; executor rejection; action throws; action ignores interruption; renewal failure; worker process loss; pause while leased; resume; explicit stop; Context close; deref a run completed remotely. |
| Persistence/security | embedded reopen; server reconnect; schema migration/newer rejection; no config/secret/closure/future/exception/payload persisted; bounded/sanitized error data; no lease token in logs/errors; no cross-run artifact/result reuse. |

## Files and package impact

| Path | Planned responsibility |
| --- | --- |
| `collet-core/src/collet/store.clj` | Extend the public Store command/read contract for WorkUnit/Attempt claims, renewals, fenced terminal transitions, and inspection. This is a deliberate custom-Store breaking change. |
| `collet-core/src/collet/store/datalevin.clj` | Schema version/migration, entity mappers, bounded set-based reads, authoritative-time transaction helpers, and all atomic coordination transitions. |
| `collet-core/src/collet/core.clj` | Task requirements validation, run creation with work units/execution ref, worker loop, resolver, lifecycle/Run-handle changes, and removal of scheduler authority. |
| `collet-core/src/collet/artifact.clj` | Validate/write bounded attempt/fence ownership in #49 artifact manifests and metadata without changing payload format or retention scope. |
| `collet-core/test/collet/store/datalevin_test.clj` | Schema, migration, entity, claim, renewal, fence, completion, readiness, and reopen contracts. |
| `collet-core/test/collet/runtime_test.clj` and new focused worker tests | Local worker behavior, capacity, action compatibility, lifecycle, recovery, and fault injection. |
| `collet-core/test/...` client/server integration fixture | Independent client contexts, shared artifact root, expiry/reclaim, and race acceptance tests. |
| `collet-core/deps.edn` | Add only the Datalevin client/server/test dependency proven in Phase 0; update artifact contract only if a new public namespace is introduced. |
| `collet-app/src/collet/main.clj`, `collet-cli/src/pod/collet/core.clj`, and their tests | Preserve default single-process run behavior and verify normal shutdown does not accidentally cancel shared work. |
| `README.md`, `docs/development.md`, `docs/repl-workflow.md` only if command lifecycle changes, `COLLET_NEXT.md`, `collet-app/deploy.md`, and relevant CLI docs | Document pull execution, local/shared topology, capability/config resolver boundaries, lease behavior, failure/cleanup semantics, and no-reuse guarantees. |

Do not create a new action module, put runtime coordination in `collet-actions`,
or add an S3 backend in this issue.

## Validation and release checklist

1. Start/attach the active-worktree nREPL **before editing Clojure**; inspect
   Datalevin APIs there and use `(user/reload)` after production changes.
2. For each changed Clojure/EDN file, run `bb fmt:fix --file <path>` and then
   `bb fmt:check --file <path>`.
3. Run focused Store and worker tests repeatedly, including deterministic race
   tests, before broadening the change.
4. Run `bb test:module collet-core`, then `bb test:unit`.
5. Run the client/server/shared-artifact integration coverage and then
   `bb test:integration`.
6. Because this changes a public core protocol, dependencies, durable schema,
   and artifact metadata, finish with `bb verify`, `bb release:plan`, Markdown
   link/path checks, `git diff --check`, and a full diff review.
7. Use a release-producing breaking commit/PR title such as
   `feat!: add fenced pull-based work-unit execution`; expect Kmono to include
   affected core dependants. Do **not** run `bb release` without explicit
   publication approval.

## Explicit non-goals

This plan does not add partition/record work units, task materialization reuse,
cache lookup, snapshots, delayed retry/backoff/deadlines, durable retry budgets,
S3 transport, orphan adoption/reconciliation, worker registration, priority or
fair scheduling, affinity/placement scoring, GPU accounting, autoscaling, HA
operations, external queues, control APIs, UI, telemetry semantics, or
exactly-once external effects. Those remain assigned to #51–#55 or future
work after this fenced one-work-unit-per-task foundation is proven.
