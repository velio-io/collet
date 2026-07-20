# Module migration and compatibility

Issue #43 replaces the single optional-actions classpath with isolated Maven
artifacts. Public namespaces, action keywords, configuration keys, application
entrypoints, and distribution filenames remain compatible.

## Module graph

All coordinates below use the version declared for the module in
`build/modules.edn`. New action modules begin at `0.2.8-SNAPSHOT` in source.

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
| CLI distribution | `pod.collet.core` | app |

The graph is topologically ordered and is the authority for artifact names,
versions, internal edges, main namespaces, output filenames, and integration-test
selection.

## Consumer migration

An existing consumer can retain its pipeline specs and aggregate dependency:

```clojure
{:deps {io.velio/collet-actions {:mvn/version "VERSION"}}}
```

The aggregate is a deliberately small JAR/POM. Its exact dependencies make all
legacy namespaces available transitively. To reduce the classpath, replace only the
coordinate and retain the same namespace and action types:

```clojure
{:deps {io.velio/collet-action-http {:mvn/version "VERSION"}}}
```

```clojure
(require '[collet.actions.http])
;; :collet.actions.http/request remains unchanged
```

Use multiple action coordinates only when a pipeline uses multiple families. Direct
internal edges are already transitive; for example S3 brings file, which brings HTTP.
JDBC includes the PostgreSQL driver because `collet.actions.jdbc-pg` imports its
classes. The MySQL driver remains test-only and consumers add their chosen driver.

## Dependency isolation

Base module dependencies always use Maven coordinates. Development and tests
override internal coordinates with `:local/root`; those local paths never appear in
generated POMs. `bb verify` installs every library into a temporary Maven repository,
starts a minimal consumer process for each coordinate, requires its promised
namespaces, and checks forbidden optional dependency families in each dependency
tree.

The `io.velio/collet-actions` aggregate is the only artifact expected to expose all
optional families. HTTP-only consumers do not receive JDBC, AWS, Chronicle, OpenAI,
Graal, or Lucene dependencies.

## Vega and Darkstar

The former unpublished `lib/darkstar.jar` is not part of the build. The Vega module
packages the required existing source/resources directly and includes upstream MIT
license attribution and provenance under `META-INF`. Graal versions remain pinned at
24.2.2. Vega tests compare rendered output with the preserved golden result.

## Application and CLI contracts

- Application library and executable builds are independent; the uberjar remains
  `collet-app/target/collet.jar` with `collet.main`.
- The pod uberjar remains `collet-cli/target/collet.pod.jar` with
  `pod.collet.core`.
- The CLI archive remains `collet-cli/target/collet-cli.tar.gz`, rooted at
  `collet-cli/`, with executable `collet.bb` and `gum` files.
- Docker uses a Clojure CLI/JDK 21 builder and retains the existing runtime image,
  JVM options, JMX setup, entrypoint, and environment contract.

## Version policy

Versions are per module and are never rewritten during publication. Before release,
maintainers explicitly replace the target module's snapshot version and update every
dependent module's exact internal Maven version. Released tags are named
`<artifact>-v<version>`, such as `collet-action-http-v0.2.8`.

The aggregate pins exact released action versions. A module cannot be released while
it or any direct/transitive internal dependency is a snapshot.
