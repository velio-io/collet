# Issue #45 dataset-artifact spike

This is an isolated, development-only project. None of its dependencies or
native components enter a published Collet artifact or generated POM.
The current branch already has a pre-existing 0.2.9 release plan from merged
#59; comparison with a clean `HEAD` clone confirms this spike adds no package
to that plan.

Run it from the repository root:

```shell
bb spike:45 check
bb spike:45 s3-check
bb spike:45 docker-check
bb spike:45 benchmark [--rows N] [--embedding-width N] [--memory-limit 256MiB] [--repetitions N] [--output-dir path]
```

`prepare-native` is an image-build helper, not a user-facing artifact command.
Every public command writes machine-readable EDN and exits nonzero when a hard
check fails. Benchmark defaults are one warm-up and three measured runs over
1,048,576 rows x 256 `FLOAT` values: one GiB of logical embedding input.
Every candidate uses one DuckDB worker and JDBC streaming results so the fixed
256 MiB limit is available to the workload rather than parallel result buffers;
both settings are recorded in the evidence. The memory-pressure workload joins
each artifact row to 32 dimension rows and sorts the resulting 33,554,432
default rows by a deterministic numeric hash. This exceeds 256 MiB without
relying on DuckDB 1.5.5's non-spillable wide array or string sort records.
Parquet writes use the configured 4,096-row batch size as an explicit row-group
size so adding the second fixed-width embedding does not make the writer buffer
a default row group larger than the DuckDB limit; the setting is recorded in
the benchmark EDN.
After each benchmark format, the spike records inventories and hashes, closes
the DuckDB connection, and deletes generated Lance, Parquet, Arrow, evolution,
and spill data outside the timed operation. It repeats cleanup at the sample
boundary and treats either cleanup failure as a hard failure. The manual
workflow uploads only EDN, DuckDB profiling JSON, checkpoints, and logs; it
never uploads generated dataset binaries.

## Dependency isolation

The spike intentionally uses two JVM classpaths:

- The main process uses Collet's Arrow 19.0.0, DuckDB JDBC 1.5.5.1, the DuckDB
  v1.5.5 Lance extension, and pinned DuckTape source.
- Short-lived Lance Java helpers use Lance 9.0.1 with its declared Arrow
  18.3.0 family and DuckDB JDBC 1.5.5.1. `:replace-deps` prevents Arrow 19 from
  leaking into this helper.

The `:lance-java-forced-arrow19` alias exists only to reproduce the invalid
mixed classpath from the first spike. Its outcome is diagnostic and cannot
qualify or reject Lance.

Java 25 FFM is recorded in the ADR as a possible separate fallback. Lance does
not currently publish a general C ABI, so bypassing Lance Java would require a
Collet-owned Rust shim and is not silently folded into this spike. Normal
Lance scans already avoid Lance Java: DuckDB's native extension returns them
through the main process's JDBC Arrow 19 path.

DuckTape is locked to Git revision
`f0fc5f38f272560821e1f9c8e7fe1634761c0580`. DuckDB extension versions and
native file hashes are checked at runtime.

## Exact failure evidence

Each child JVM receives a unique evidence directory containing:

- `result.edn` when Clojure can return a result;
- force-synced lifecycle checkpoints in `checkpoints.edn`;
- complete combined stdout/stderr in `output.log`;
- the JVM's complete `hs_err_pid*.log` after a fatal native error.

The orphan-fragment child force-halts with exit 86 immediately after its
force-synced `:fragment-created` checkpoint. No result write or resource cleanup
is allowed before the parent reopens the last committed dataset version.

The parent EDN records the child command, exit code, peak sampled RSS, paths,
sizes, SHA-256 hashes, checkpoint values, and the beginning of any fatal JVM
report. Clojure startup reports are sent to stderr so load-time failures are
also retained in `output.log`.

`docker-check` builds the pinned Linux AMD64 image, preinstalls native inputs
while networking is available, and mounts the complete evidence directory
while the runtime check uses `--network none`. It records whether execution was
native Linux or a translated container; translated Apple-Silicon results are
never presented as native-Linux evidence.

## Current provisional result

Native Ubuntu AMD64 and macOS ARM64 pass aligned Lance latest/pinned reopen,
DuckDB/Lance coexistence, orphan-fragment recovery, nested fidelity, JDBC
Arrow, DuckTape nested object values, LocalStack reopen, and the offline image
check. Lance add/replace of a derived fixed-size embedding passes by combining
Lance Java's computed-column API with the DuckDB extension's fixed-array cast.
The same schema check makes Parquet's physical limitation explicit: DuckDB
reopens its embedding as `FLOAT[]`; the Parquet adapter restores `FLOAT[n]`
from the artifact schema and records the raw width loss as unsupported.

The one-GiB native benchmark provisionally recommends Parquet. Lance passed all
five latency limits but failed the process-cold RSS limit at 2.07x Parquet and
both amplification limits: its add and replace each changed about 2.13 GB,
versus 18.4 MB and 18.8 MB Parquet replacements. JDBC Arrow remains the core
interchange, and DuckTape remains an optional TMD view only. The ADR is still
pending review; no production format has been accepted or implemented.

The translated Apple-Silicon-hosted Linux image's `liblance_jni` crash remains
retained portability diagnostics. The passing native Ubuntu run proves that it
is not a current Lance hard blocker.

See [`evidence/`](evidence/) and
[`docs/adr/0045-dataset-artifact-spike.md`](../../../docs/adr/0045-dataset-artifact-spike.md).
