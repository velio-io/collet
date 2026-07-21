# Development

Collet is a Clojure CLI workspace resolved by Kmono 4.12.3. Shared root-only
`tools.build` behavior lives under `build/`, reusable unpublished test fixtures
live in `test-fixtures/`, and each public or deployable package declares its
artifact contract in its own `deps.edn` under `:collet/artifact`.

## Prerequisites

- JDK 21 or newer. CI runs on JDK 21.
- Clojure CLI.
- Babashka.
- Docker for PostgreSQL, MySQL, LocalStack, application/CLI startup, and image
  integration tests.

The workspace pins Kmono and its CLI to 4.12.3, `tools.build` to 0.10.14, and
`deps-deploy` to 0.2.5. Artifact bases ignore user dependencies and use only the
repository's explicit Maven Central and Clojars configuration.

## Source and published dependencies

Internal dependencies in the root and package `deps.edn` files are top-level
`:local/root` entries. They make a checkout immediately editable and ensure tests
and uberjars use the source currently on disk. Kmono resolves the package graph and
replaces each internal local root with that package's exact Maven coordinate and
version only when a publishable POM is generated. A generated POM therefore never
contains `:local/root`, and an external consumer continues to use ordinary
`:mvn/version` dependencies.

Do not edit generated POMs or introduce internal Maven pins into source `deps.edn`
files. Versions are derived from package tags; there is no source version file,
manual version-bump command, or supported `bb version` task.

## Repository commands

Run build, workspace, verification, and release commands from the repository root:

| Command | Behavior |
|---|---|
| `bb kmono query` | Prints Kmono's resolved package graph. Additional Kmono query arguments may follow. |
| `bb test:unit` | Runs build-tool tests once and every package in a separate JVM, excluding `^:integration` tests; Docker is not required. |
| `bb test:integration` | Builds app/CLI outputs, then runs only integration tests in integration-bearing packages; Docker is required. |
| `bb test` | Runs the complete unit and integration suite; Docker is intentionally required. |
| `bb test:module <module>` | Runs one package's complete `:test` alias in its own JVM; Docker may be required. Cognitect test-runner options may follow the module. |
| `bb build [module]` | Builds one package and its dependencies, or every package, in dependency order. |
| `bb install [module]` | Installs one publishable package and its dependencies, or all publishable packages, into the local Maven repository. |
| `bb verify` | Builds and checks POMs, JARs, dependency isolation, isolated consumers, app/CLI outputs, archive modes, build identity, and legacy-build removal. |
| `bb release:plan [module]` | Prints a read-only independent-version release plan. |
| `bb release [module]` | Runs the guarded local release for all changed packages or a selected package closure. |
| `bb release:all` | Runs the guarded local release for every changed package. |
| `bb release:verify-cli io.velio/collet-cli@VERSION` | Verifies CLI outputs from a detached CLI package-tag checkout. |
| `bb release:verify-image io.velio/collet-app@VERSION IMAGE` | Verifies a local image against a detached app package-tag checkout. |

Package selectors are directory names such as `collet-core`,
`collet-action-http`, `collet-actions`, `collet-app`, and `collet-cli`. Package-local
`clojure -M:test` remains useful while developing one module; the table above is the
supported repository orchestration contract.

## Build outputs

Library packages produce `target/<artifact>-<version>.jar` with Clojure sources,
runtime resources, `LICENSE`, Maven metadata, build identity, and a publishable POM.
In addition:

- `bb build collet-app` preserves `collet-app/target/collet.jar` and main namespace
  `collet.main`.
- `bb build collet-cli` preserves `collet-cli/target/collet.pod.jar`, main namespace
  `pod.collet.core`, and `collet-cli/target/collet-cli.tar.gz`.
- `docker build -f collet-app/Dockerfile -t collet .` builds the runtime image from
  the repository-root context. Release images must also receive the exact app/core
  versions and revision described in the [deployment guide](../collet-app/deploy.md).

Build outputs and tests use package-local `target` and temporary directories. Tests
create and remove their own fixtures; no pre-existing `tmp` directory is required.

## Changing a package

Keep runtime implementation and package-specific tests/resources in the owning
directory. Put reusable, unpublished test fixtures in `test-fixtures`. When changing
dependencies or package boundaries:

1. Add or update the owning package's top-level dependency in `deps.edn`. Use
   `:local/root` for another workspace package and `:mvn/version` for an external
   library.
2. Update that package's `:collet/artifact` metadata when its namespaces, kind,
   publishability, main namespace, or exceptional output contract changes.
3. Use a conventional commit that describes a publishable change: `fix:` for a
   patch, `feat:` for a minor, or `!`/`BREAKING CHANGE:` for a major release.
4. Run `bb test:module <module>`, then `bb test:unit`.
5. Run Docker integrations when affected and finish with `bb verify` and
   `bb release:plan [module]`.

Documentation, tests, CI, and development-only changes do not publish. A meaningful
runtime/package change without a release-producing commit causes planning and
verification to fail; fix the commit message or the PR title that will become the
squash commit rather than assigning a version manually.

See [module migration](./module-migration.md) for artifact boundaries and
[releasing](./releasing.md) for independent versions, selection, publication, and
recovery.
