# Module migration and compatibility

Issue #43 replaces the single optional-actions classpath with isolated Maven
artifacts. Public namespaces, action keywords, configuration keys, application
entrypoints, and distribution filenames remain compatible.

## Module graph

Each coordinate below has an independent version derived from its Kmono package
tags. There is no version in source and versions need not match. With no package
tags, the first modular release plans every package at `0.2.8`; historical
`v0.2.x` tags remain in Git but are not package-version tags.
All rows except the CLI distribution are Maven coordinates published to Clojars.

| Coordinate | Preserved namespaces | Direct internal dependencies |
|---|---|---|
| `io.velio/collet-core` | Existing core namespaces, including `collet.core` | None |
| `io.velio/collet-action-http` | `collet.actions.http` | core |
| `io.velio/collet-action-file` | `collet.actions.file` | core, HTTP |
| `io.velio/collet-action-odata` | `collet.actions.odata` | core, HTTP |
| `io.velio/collet-action-jdbc` | `collet.actions.jdbc`, `collet.actions.jdbc-pg` | core |
| `io.velio/collet-action-s3` | `collet.actions.s3` | core, file |
| `io.velio/collet-action-queue` | `collet.actions.queue` | core |
| `io.velio/collet-action-jslt` | `collet.actions.jslt` | core |
| `io.velio/collet-action-llm` | `collet.actions.llm` | core |
| `io.velio/collet-action-vega` | `collet.actions.vega` | core, file |
| `io.velio/collet-action-lucene` | `collet.actions.lucene` | core |
| `io.velio/collet-actions` | All eleven action namespaces above | All ten action artifacts |
| `io.velio/collet-app` | Application API and `collet.main` | core |
| `io.velio/collet-cli` (distribution, not Maven) | `pod.collet.core` | app |

Kmono resolves this graph from the packages' `deps.edn` files and topologically
orders builds, tests, verification, and publication. Each package's
`:collet/artifact` metadata owns its namespaces, publishability, kind, main
namespace, and exceptional output filenames.

## Consumer migration

An existing consumer can retain its pipeline specs and aggregate dependency:

```clojure
{:deps {io.velio/collet-actions {:mvn/version "ACTIONS_VERSION"}}}
```

The aggregate is a deliberately small JAR/POM. Its exact dependencies make all
legacy namespaces available transitively. To reduce the classpath, replace only the
coordinate and retain the same namespace and action types:

```clojure
{:deps {io.velio/collet-action-http {:mvn/version "HTTP_VERSION"}}}
```

```clojure
(require '[collet.actions.http])
;; :collet.actions.http/request remains unchanged
```

Use multiple action coordinates only when a pipeline uses multiple families. Direct
internal edges are already transitive; for example S3 brings file, which brings HTTP.
Those artifacts may all have different versions; each generated POM records the
exact compatible internal versions. Consumers choose only the published version of
each coordinate they declare directly.

JDBC includes the PostgreSQL driver because `collet.actions.jdbc-pg` imports its
classes. The MySQL driver remains test-only and consumers add their chosen driver.

## Source dependencies and generated Maven dependencies

Source checkouts use top-level `:local/root` dependencies between packages. Kmono
converts each local root into that package's exact Maven coordinate and resolved
version only when generating a publishable POM; local paths never appear in
published metadata. External consumers continue to use `:mvn/version`. There are no
internal pins or source versions to update manually. `bb verify` reads generated POMs
directly and checks exact internal Maven coordinates and resolved versions, together
with published JAR namespaces and executable contracts.

For example, the source dependency for file is a local root to HTTP and core. Its
generated POM contains `io.velio/collet-action-http` and
`io.velio/collet-core`, each at the exact Kmono-resolved package-tag version. The
aggregate POM likewise lists all ten action coordinates at their exact resolved
versions. This conversion is owned by Kmono, not a Collet dependency-rewrite layer.

## Vega and Darkstar

The former unpublished `lib/darkstar.jar` is not part of the build. The Vega module
packages the required existing source/resources directly and includes upstream MIT
license attribution and provenance under `META-INF`. Graal versions remain pinned at
25.1.3. Vega tests compare rendered output with the preserved golden result.

## Application and CLI contracts

- Application library and executable builds are independent; the uberjar remains
  `collet-app/target/collet.jar` with `collet.main`.
- The pod uberjar remains `collet-cli/target/collet.pod.jar` with
  `pod.collet.core`.
- The CLI archive remains `collet-cli/target/collet-cli.tar.gz`, rooted at
  `collet-cli/`, with executable `collet.bb` and `gum` files.
- Docker uses a Clojure CLI/JDK 25 builder and Java 25 runtime image, retaining the
  existing JVM options, JMX setup, entrypoint, and environment contract. Java 25 is
  the new minimum supported version, which is a compatibility break from previous
  releases.

## Version policy

Tags use Kmono's `<coordinate>@<version>` format, for example
`io.velio/collet-action-http@0.2.8`. `fix:` commits produce patch releases, `feat:`
commits produce minor releases, and `!` or a `BREAKING CHANGE:` footer produces a
major release. Documentation, tests, CI, and development-only changes are ignored.
A meaningful package change with no release-producing commit makes planning fail;
fix the commit or squash-merge PR title rather than adding a version.

Internal dependency changes give affected dependents a patch release transitively.
In particular, any action release also patches `io.velio/collet-actions`; a core
release patches every affected action, the aggregate, app, and CLI through the graph.
Use `bb release:plan` to inspect all candidates. Kmono automatically includes
dependents whose exact internal dependency version must change. Maven publication is
automated by `bb release`, while the CLI GitHub release and Docker push remain later,
explicit operations from their own package tags.
