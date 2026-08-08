# ADR 0045: Reopen the Lance decision with aligned runtimes

Status: provisional pending review. No production artifact contract or
publishable dependency is introduced by this spike.

## Provisional decision

Recommend Parquet plus DuckDB for the durable artifact format. Lance passed
every compatibility and recovery hard gate and met every latency threshold,
but it failed the predetermined process-cold RSS and both rewrite-amplification
thresholds. The recommendation remains provisional until review; this spike
does not accept the format or start its production implementation.

JDBC Arrow 19 remains the default core interchange. DuckTape may only be an
optional tech.ml.dataset view and never the artifact contract.

The earlier Lance rejection was based on three invalid inferences:

1. Lance Java was forced onto Arrow 19 even though Lance declares Arrow 18.3.
   A crash on that mixed classpath is not Lance compatibility evidence.
2. A Linux AMD64 container translated by an Apple-Silicon host was called
   native Linux evidence. It is useful portability diagnostics, but cannot
   satisfy or fail the native Ubuntu gate.
3. DuckDB Lance `UPDATE` was treated as the only way to replace a computed
   column. The working spike path is Lance Java `addColumns` / `dropColumns`,
   followed by DuckDB Lance `ALTER COLUMN ... TYPE FLOAT[n]` to retain the
   fixed-size embedding contract.

## Runtime and version contract

| Process | Direct versions | Purpose |
| --- | --- | --- |
| Main spike JVM | Clojure 1.12.5, DuckDB JDBC 1.5.5.1 / DuckDB 1.5.5, Arrow 19.0.0, DuckTape Git `f0fc5f38f272560821e1f9c8e7fe1634761c0580` | DuckDB, JDBC Arrow, Parquet, Lance extension, Arrow IPC, and TMD comparison |
| Lance helper JVM | Lance Java 9.0.1, Arrow vector/memory/C-data/dataset 18.3.0, DuckDB JDBC 1.5.5.1 | Version inspection, deterministic orphan creation, and computed-column evolution |
| Forced diagnostic JVM | Lance Java 9.0.1 with Arrow 19.0.0 | Reproduce the invalid original override only |
| DuckDB extensions | Lance `2f167ea`, HTTPFS `827222f` | Pinned native DuckDB capabilities |
| Containers | Temurin 25 base pinned by digest; LocalStack 4.14.0 | Offline Linux image check and S3 smoke |

The main JVM asserts that Arrow 19 is loaded and `org.lance.Dataset` is absent.
The helper uses `:replace-deps`, asserts Arrow 18.3 at runtime, and records the
JAR code sources. This isolates incompatible Arrow families without adding a
new dependency to `collet-core` or `collet-app`.

The spike changes no publishable package path. `bb release:plan` is not empty
on the current `main`: merged #59 already produces the same 14-package 0.2.9
plan in a clean `HEAD` clone. The spike worktree and clean-`HEAD` plans are
identical, so this spike selects no additional package.

## Foreign Function and Memory API alternative

Java 25's finalized [Foreign Function and Memory API](https://openjdk.org/jeps/454)
could replace JNI only if the native library exposes a C-compatible ABI. At upstream Lance commit
`82c81cadb044f979cbdc95baaa9ba6c4d169df2f`, the published native Java surface
is the [`lance-jni` Rust `cdylib`](https://github.com/lance-format/lance/blob/82c81cadb044f979cbdc95baaa9ba6c4d169df2f/java/lance-jni/Cargo.toml);
a repository-tree audit found no public Lance C header or C API. Calling its
exported JNI symbols through FFM would still require `JNIEnv` and Java objects
and therefore would not bypass the Java binding.

Bulk Lance reads do not need a new binding in this spike. The DuckDB Lance
extension already provides a native Lance path behind SQL, and its results
return to the main JVM through JDBC Arrow 19. That is also the shortest path
toward TMD. An FFM binding would only replace helper-only control-plane or
mutation operations that DuckDB does not expose with the required semantics.

A real FFM alternative would require a Collet-owned Rust `cdylib` that wraps
Lance and exposes opaque dataset handles plus a deliberately small C API. The
read/write boundary should be the stable Arrow
[C Data](https://arrow.apache.org/docs/format/CDataInterface.html) and
[C Stream](https://arrow.apache.org/docs/format/CStreamInterface.html)
interfaces.
That could keep Lance's Rust Arrow dependency independent from Arrow Java 19,
but Collet would then own Rust builds, ABI versioning, panic/error conversion,
Tokio lifetime, native packaging, and object-store configuration on every
supported platform.

This also is not a direct tech.ml.dataset integration. TMD's current Arrow
adapter consumes Arrow IPC/file streams. An FFM `ArrowArrayStream` would need
either an IPC hop or a new lifetime-aware C Data-to-TMD adapter; nested and
fixed-size-list mappings remain issue #46 work. DuckTape remains the smaller
optional TMD path.

FFM is therefore deferred. Native Ubuntu passed the Lance Java and DuckDB Lance
paths, so there is no immediate justification for Collet to own a Rust C ABI.
Revisit a narrow FFM probe only for a future failure plausibly isolated to JNI
or a measured control-plane need that the supported bindings cannot satisfy.

## Gate 1 evidence

Native Ubuntu AMD64 on JDK 25.0.3 passes, and the macOS ARM64 functional run
also passes:

- full nested value fidelity through DuckDB Parquet, DuckDB Lance, and JDBC
  Arrow;
- latest Lance version 2 with 513 rows and explicitly pinned version 1 with 512
  rows after a fresh JVM restart;
- DuckDB JDBC, the DuckDB Lance extension, Lance Java 9.0.1, and Arrow 18.3 in
  one aligned helper JVM;
- one uncommitted child-created fragment excluded after the child force-halts
  with exit 86 before cleanup or commit, with the prior committed version and
  513-row count retained;
- existing Collet Arrow on its supported scalar/timestamp subset;
- DuckTape maps/vectors for nullable `STRUCT`, `MAP`, `LIST`, and `LIST<STRUCT>`
  object columns; fixed-size arrays remain explicitly unsupported there;
- LocalStack write and fresh-JVM projected/filtered reopen for Lance and
  Parquet. This makes no object-store performance claim.

The schema assertion also found a Parquet limitation hidden by the first spike:
DuckDB reads the physical Parquet embedding as `FLOAT[]`, not `FLOAT[4]`.
Values are unchanged. The Parquet integration now records that physical cell as
unsupported and restores the fixed width with
`embedding::FLOAT[n]`, using the explicit artifact schema. The integrated read
then matches the complete logical schema. Lance preserves the fixed width
without that adapter.

The forced Arrow 19 diagnostic fails before the helper reaches `-main`. Its
exact top-level error is:

```text
Execution error (UnsupportedOperationException) at io.netty.buffer.EmptyByteBuf/memoryAddress (EmptyByteBuf.java:961).
null
```

The native run's complete 7,301-byte Clojure report is retained in `output.log`
with SHA-256
`977cb7d4f11a5ae65593694605e240af0a995c2806989420beb4731bf6d4ac05`.
This diagnostic is non-qualifying because the runtime deliberately contradicts
Lance's declared Arrow dependency.

The aligned Linux AMD64 image, when translated on the Apple-Silicon host, still
terminates while entering `Dataset.openNative`. The last checkpoint is
`:allocator-opened`; `:dataset-opened` is absent. The exact HotSpot header is:

```text
#  SIGSEGV (0xb) at pc=0x343438312f736e6f, pid=342, tid=352
# Problematic frame:
# C  [liblance_jni17287533467677409887.so+0x75fe9f4]  _RINvNtCscI6d9CVNmLh_4core3ptr9drop_glueINtNtNtNtB4_4iter8adapters7flatten7FlattenINtCsdWqgeKPIYK7_4slab5DrainINtNtNtB4_4task4wake5WakerEEEECs6Ymvtjsbuwl_11lance_index+0x84
```

The report identifies `Host: VirtualApple`, Java 25.0.3, Lance 9.0.1, and Arrow
18.3.0. The complete 936,963-byte `hs_err_pid342.log` has SHA-256
`4ee1e2aaa777e9e434da306e54b7da90a646755b620b139b664004384ef87ac2`.
The full report, combined output, checkpoints, sizes, and hashes are retained
for this exact run. The checkpoint boundary repeats across translated runs,
while addresses, PIDs, and extracted-library names are run-specific. Native
Ubuntu subsequently passed, so this remains translated-host portability
diagnostics and is not a Lance blocker.

## Nested-type and packaging matrix

| Value shape | Parquet / DuckDB | Lance / DuckDB + aligned helper | JDBC Arrow IPC | Existing Collet Arrow | DuckTape TMD view |
| --- | --- | --- | --- | --- | --- |
| Scalars and timestamp | pass | pass | pass | pass | pass |
| Nullable address `STRUCT` | pass | pass | pass | unsupported | Clojure map |
| `MAP<VARCHAR,VARCHAR>` with null value | pass | pass | pass | unsupported | Clojure map |
| `VARCHAR[]` with null element | pass | pass | pass | unsupported | Clojure vector |
| `STRUCT[]` with null element/child | pass | pass | pass | unsupported | vector of maps/nil |
| Nullable fixed `FLOAT[n]` | values pass; raw width is lost and explicit-schema cast restores it | pass without restoration | pass | unsupported | explicitly unsupported |
| Add/replace derived fixed `FLOAT[n]` | replacement artifact | Lance Java compute plus DuckDB fixed-array cast passes | interchange only | unsupported | unsupported |
| Null parents | pass | pass | pass | scalar subset only | omitted top-level nil keys are recorded and restored only for comparison |

No profile value is serialized through JSON for fidelity checks. JSON is used
only for DuckDB profiling output. Unsupported cells are recorded rather than
coerced.

| Candidate | Packaging and operational surface | Current state |
| --- | --- | --- |
| Parquet + DuckDB | Main spike JVM plus manifest-driven fixed-array restoration; replacement artifact for column evolution | passes native gates and is provisionally recommended |
| Lance | DuckDB native extension plus an isolated Lance Java/Arrow 18.3 helper | passes native gates but fails the RSS and amplification decision thresholds |
| Arrow IPC | Arrow 19 JDBC streaming | selected core interchange, not a durable table contract |
| DuckTape | Pinned Git snapshot and matching native DuckDB library | optional TMD adapter only |

## Gate 2 measurement

[Native run 31258409578](https://github.com/velio-io/collet/actions/runs/31258409578)
used 1,048,576 rows x 256 floats, exactly one GiB of logical embedding input,
one warm-up, three measured executions, a 256 MiB DuckDB limit, JDBC streaming,
one DuckDB worker, and 4,096-row Parquet row groups. Every format and cleanup
case passed. The sort/join emitted 33,554,432 rows and spilled in every sample.
The runner's OS page cache was not flushed.

Median measured results:

| Metric | Parquet | Lance | Lance / Parquet | Rule |
| --- | ---: | ---: | ---: | --- |
| Write latency | 12,558.66 ms | 6,586.41 ms | 0.524x | <= 1.20x, pass |
| Full sequential latency | 3,761.14 ms | 2,636.81 ms | 0.701x | <= 1.20x, pass |
| Projected/filtered latency | 95.51 ms | 94.71 ms | 0.992x | <= 1.20x, pass |
| Sort/join latency | 5,856.63 ms | 6,264.31 ms | 1.070x | <= 1.20x, pass |
| Process-cold latency | 3,993.13 ms | 2,834.96 ms | 0.710x | <= 1.20x, pass |
| Process-cold peak RSS | 701,517,824 bytes | 1,452,331,008 bytes | 2.070x | <= 1.25x, fail |
| Add changed/replacement bytes | 18,445,106 | 2,131,793,911 | 115.575x | <= 0.75x, fail |
| Replace changed/replacement bytes | 18,787,184 | 2,131,796,225 | 113.471x | <= 0.75x, fail |

Lance's median initial artifact was 1,169,049,750 bytes; Parquet's was
15,295,724 bytes and Arrow IPC's was 1,282,662,386 bytes. The generated columns
are deliberately repetitive, so this is evidence for this workload rather than
a general compression claim. Lance's add/replace operations were faster than
the Parquet replacements, but the working add/drop/cast sequence added roughly
2.13 GB per change and its fresh helper process used twice Parquet's peak RSS.
Those failures are sufficient to select Parquet under the approved rule.

DuckDB 1.5.5 does not implement `EXPLAIN (ANALYZE, VERBOSE)`; its exact error is
`Not implemented Error: Unimplemented explain type: verbose`. The spike records
DuckDB's `all` fallback, containing logical, optimized, and physical plans.
Parquet profiling reports read bytes for full and sort reads, while the Lance
extension reports zero. These counters are retained as backend-visible evidence
and are not presented as a comparable physical-byte measurement.

Four earlier native runs were invalid instrumentation/workload iterations, not
format decisions: JDBC result materialization, unnecessary ordering, a
non-spillable wide sort, and Parquet's default row-group buffering during the
double-width rewrite. Their run IDs, source hashes, and exact error strings are
retained in `gate-2-linux-amd64.edn`. The final repair explicitly uses the
existing 4,096-row batch size for every benchmark Parquet write; the approved
rows, embedding width, memory limit, and repetition count were unchanged.

The sanitized evidence is
[`gate-1-linux-amd64.edn`](../../dev/spikes/issue-45/evidence/gate-1-linux-amd64.edn)
and
[`gate-2-linux-amd64.edn`](../../dev/spikes/issue-45/evidence/gate-2-linux-amd64.edn).
Raw workflow artifacts remain outside Git, and no generated dataset binary was
uploaded.

## Follow-ups

- [tech.ml.dataset#389](https://github.com/techascent/tech.ml.dataset/issues/389):
  keep Arrow IPC/JDBC Arrow as the integration boundary and offer DuckTape only
  as an optional object-column view. Its fixed-size-array gap and omitted
  top-level nil keys prevent it from defining Collet's artifact contract.
- **#46:** define the nested JDBC Arrow boundary, including null parents, null
  children, null list elements, maps, structs, lists, and fixed-size floats,
  without JSON coercion; document Parquet's manifest-driven `FLOAT[n]` cast.
- **#48:** implement format-neutral artifact and snapshot identities with a
  Parquet/DuckDB storage adapter, immutable publication, checksums, recovery,
  and object-store behavior. Do not copy Lance's internal version model into
  the public contract.
- **#47:** add task-local DuckDB SQL over the #48 Parquet artifacts with
  projection/predicate pushdown, configurable bounded memory and spill, and
  JDBC Arrow output.
