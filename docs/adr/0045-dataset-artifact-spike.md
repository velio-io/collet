# ADR 0045: Reopen the Lance decision with aligned runtimes

Status: provisional. The earlier Parquet acceptance is withdrawn. No production
artifact contract or publishable dependency is introduced by this spike.

## Provisional decision

Keep the durable artifact format unresolved until the corrected native Ubuntu
Gate 1 and one-GiB Gate 2 run completes. Lance and Parquet are both still
candidates. JDBC Arrow 19 remains the default core interchange regardless of
the durable-format result. DuckTape may only be an optional tech.ml.dataset
view and never the artifact contract.

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

FFM is therefore a separately approval-gated fallback, not a way to reinterpret
the translated-container crash. Finish the native Ubuntu gate first. Only an
authoritative native failure plausibly isolated to JNI should trigger a narrow
FFM probe limited to open, version, row count, and projected scan before any
write or object-store surface is considered.

## Corrected Gate 1 evidence

The aligned macOS ARM64 / JDK 25.0.1 functional run passes:

- full nested value fidelity through DuckDB Parquet, DuckDB Lance, and JDBC
  Arrow;
- latest Lance version 2 with 17 rows and explicitly pinned version 1 with 16
  rows after a fresh JVM restart;
- DuckDB JDBC, the DuckDB Lance extension, Lance Java 9.0.1, and Arrow 18.3 in
  one aligned helper JVM;
- one uncommitted child-created fragment excluded after the child force-halts
  with exit 86 before cleanup or commit, with the prior committed version and
  17-row count retained;
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

The complete 5,818-byte Clojure report is retained in `output.log` with SHA-256
`5a6c63503a00f6bdd4f154f55550d1ad1fd9210d071d1b7effd64151898c5feb`.
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
while addresses, PIDs, and extracted-library names are run-specific. This is
not evidence that native Ubuntu fails. The manual workflow is the authority
for that hard gate.

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
| Parquet + DuckDB | Main spike JVM plus manifest-driven fixed-array restoration; replacement artifact for column evolution | survives corrected macOS gate with one physical-schema gap |
| Lance | DuckDB native extension plus an isolated Lance Java/Arrow 18.3 helper | survives corrected macOS gate; native Ubuntu pending |
| Arrow IPC | Arrow 19 JDBC streaming | selected core interchange, not a durable table contract |
| DuckTape | Pinned Git snapshot and matching native DuckDB library | optional TMD adapter only |

## Gate 2 smoke and pending measurement

A 16-row x 4-float smoke validates the measurement machinery only; it is not
performance evidence. Parquet, Lance, and Arrow IPC all completed write, cold
read, and applicable operations. Lance add preserved all 11,007 existing bytes,
added 7,302 bytes, and rewrote 0 bytes. Lance replace preserved 18,296 bytes,
added 9,592 bytes, and rewrote a 13-byte latest-version hint. The comparable
Parquet artifacts were 3,425 source bytes, 3,680 add bytes, and 3,682 replace
bytes. Every file was hashed before and after.

The required decision still needs native Linux AMD64 with a 256 MiB DuckDB
limit, at least one GiB logical input, one warm-up, and three measured runs.
The runner's OS page cache is not flushed. If native Gate 1 fails, Lance is
rejected without running its expensive benchmark. If evidence is mixed, choose
Parquet. Select Lance only if every hard gate passes and measured versioning and
column-evolution benefit justifies its native/helper operational surface. The
manual workflow defaults `run_benchmark` to false; Gate 2 requires a second,
explicitly approved dispatch after reviewing the uploaded Gate 1 evidence.

## Follow-ups

- [tech.ml.dataset#389](https://github.com/techascent/tech.ml.dataset/issues/389): recommend DuckTape only as an optional object-column view. Its fixed-size
  array gap and omitted top-level nil keys prevent it from defining Collet's
  artifact contract.
- **#46:** implement the supported nested Arrow boundary without assuming the
  final durable format and without JSON coercion.
- **#47:** keep task-local DuckDB SQL capable of scanning either surviving
  artifact format until the native decision lands.
- **#48:** keep artifact identity, manifests, publication, recovery, and
  object-store behavior format-neutral until this ADR becomes accepted.
