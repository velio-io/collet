# Development

Collet is a Clojure CLI workspace. Shared metadata and `tools.build` behavior live
under `build/`, reusable unpublished test fixtures live in `test-fixtures/`, and
every public or deployable module owns a thin `build.clj` and `deps.edn`.

## Prerequisites

- JDK 21 or newer. CI runs on JDK 21.
- Clojure CLI.
- Babashka.
- Docker for PostgreSQL, MySQL, LocalStack, and application-image integration tests.

`tools.build` is pinned to 0.10.14 in every build alias. Tests use local-root
overrides for workspace modules, while the base dependency maps use Maven
coordinates so generated POMs are consumable outside the checkout.
Artifact bases ignore user dependencies and use only the module `deps.edn` plus the
repository's explicit Maven Central/Clojars configuration.

## Repository commands

Run commands from the repository root:

| Command | Behavior |
|---|---|
| `bb test:unit` | Runs every module in a separate JVM, excluding `^:integration` tests; Docker is not required. |
| `bb test:integration` | Runs only integration-bearing modules and builds app/CLI artifacts before startup tests; Docker is required. |
| `bb test` | Runs the complete unit and integration suite; Docker is intentionally required. |
| `bb test:module <module>` | Runs one module's complete suite in its own JVM. Test-runner options may follow the module. |
| `bb build [module]` | Builds one module or every module in dependency order. |
| `bb install [module]` | Installs one publishable module and its internal dependencies, or all publishable modules. |
| `bb verify` | Verifies POMs, JARs, dependency isolation, isolated Maven consumers, uberjars, the CLI archive, and legacy-build removal. |
| `bb version <version>` | Updates the coordinated version and every internal Maven pin without committing or publishing. |
| `bb release [:patch\|:minor\|:major]` | Runs the guarded coordinated Maven release and advances the next snapshot. |

Module names are the keys in `build/modules.edn`, for example `collet-core`,
`collet-action-http`, `collet-actions`, `collet-app`, and `collet-cli`.

## Build outputs

Library modules produce `target/<artifact>-<version>.jar` with Clojure sources,
runtime resources, `LICENSE`, Maven metadata, and a publishable POM. In addition:

- `bb build collet-app` preserves `collet-app/target/collet.jar` and main namespace
  `collet.main`.
- `bb build collet-cli` preserves `collet-cli/target/collet.pod.jar`, main namespace
  `pod.collet.core`, and `collet-cli/target/collet-cli.tar.gz`.
- `docker build -f collet-app/Dockerfile -t collet .` builds the runtime image from
  the repository-root context.

Build outputs and tests use module-local `target` and temporary directories. Tests
create and remove their own fixtures; no pre-existing `tmp` directory is required.

## Changing a module

Keep runtime implementation and module-specific tests/resources in the owning
directory. Put reusable, unpublished test fixtures in `test-fixtures`. When changing
dependencies or module boundaries:

1. Update the module's base Maven coordinate dependencies in its `deps.edn`.
2. Keep workspace-only `:local/root` entries in test overrides.
3. Update the central graph if an internal edge, namespace, version, or artifact
   contract changes.
4. Run `bb test:module <module>`, then `bb test:unit`.
5. Run Docker integrations when affected and finish with `bb verify`.

See [module migration](./module-migration.md) for artifact boundaries and
[releasing](./releasing.md) for version and publication policy.
