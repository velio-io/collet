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

The workspace pins Kmono and its CLI to 4.12.3, Kaven to 1.0.0, and
`tools.build` to 0.10.14.

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
| `bb verify` | Builds artifacts and checks their public POM coordinates/internal dependency versions, namespace entries, executable entrypoints, CLI archive layout/modes, and Vega golden output. |
| `bb release:plan` | Prints the read-only independent-version plan for every changed package. |
| `bb release` | Tests, verifies, builds, publishes, and tags every changed package. |

Package selectors are directory names such as `collet-core`,
`collet-action-http`, `collet-actions`, `collet-app`, and `collet-cli`. Package-local
`clojure -M:test` remains useful while developing one module; the table above is the
supported repository orchestration contract.

## Build outputs

Library packages produce `target/<artifact>-<version>.jar` with Clojure sources,
runtime resources, `LICENSE`, Maven metadata, and a publishable POM.
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

## Branch names

Create branches from an up-to-date `main` using this form:

```text
<kind>/<issue-number>-<short-description>
```

Use lowercase kebab-case and keep the description short enough to scan in GitHub.
Use one of these kinds:

| Kind | Use for | Example |
|---|---|---|
| `feature` | New user-visible behavior | `feature/43-modular-action-artifacts` |
| `fix` | Bug fixes | `fix/118-odata-pagination` |
| `docs` | Documentation-only changes | `docs/122-release-recovery` |
| `test` | Test-only changes | `test/96-http-retry` |
| `refactor` | Internal restructuring with no behavior change | `refactor/81-action-registration` |
| `ci` | CI and automation changes | `ci/77-jdk-21-cache` |
| `chore` | Maintenance that does not fit another kind | `chore/update-testcontainers` |

Include the GitHub issue number when the work has one. For small untracked
maintenance, omit only the number, as in `chore/update-testcontainers`. Tool-managed
branches may use the tool's required prefix while keeping the same issue-and-summary
shape, for example `codex/43-deps-modules`.

For example, start issue #43 and publish its branch with:

```bash
git switch main
git pull --ff-only origin main
git switch -c feature/43-modular-action-artifacts
git push -u origin feature/43-modular-action-artifacts
```

Keep one logical change per branch and open its pull request against `main`. Do not
reuse a merged branch for later work; update `main` and create a new branch instead.
The branch kind is for navigation only: Kmono determines release versions from
conventional commits or the squash-merge pull-request title. For example, a feature
branch intended to produce a minor release should use a title such as
`feat: add modular action artifacts`.

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
   `bb release:plan`.

Package Markdown, tests, test resources, sample configs, and development files do
not publish. A meaningful package change without a release-producing commit makes
release planning fail with guidance; use the correct conventional commit or
squash-merge PR title rather than assigning a version manually. Root build and CI
files are development-only inputs and do not select package releases. If a root
metadata or packaging change is intended to alter a published artifact, include its
owning package metadata in the same release-producing commit.

See [module migration](./module-migration.md) for artifact boundaries and
[releasing](./releasing.md) for independent versions, publication, and recovery.
