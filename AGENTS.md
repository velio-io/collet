# Repository Guidelines

## Project Snapshot

Collet is a modular Clojure workflow engine for declarative ETL and ELT
pipelines. The repository is a Clojure CLI workspace resolved by Kmono: each
runtime library or executable has its own `deps.edn`, tests, artifact contract,
and independent release tag.

This is not a single-classpath monolith. Keep implementation, dependencies,
tests, resources, and artifact metadata in the package that owns them.

## Start Here

- Read `README.md` for the pipeline and action model.
- Read `docs/development.md` before changing packages, dependencies, builds, or
  releases.
- Read `docs/repl-workflow.md` before editing Clojure.
- Read only the task-relevant action, syntax, migration, or release docs.
- Treat this file as the routing layer, not the complete manual.

## Repository Map

| Area | Paths |
| --- | --- |
| Pipeline engine | `collet-core/src/`, `collet-core/test/` |
| Individual actions | `collet-action-*/src/`, `collet-action-*/test/` |
| Compatibility aggregate | `collet-actions/` |
| JVM application | `collet-app/` |
| Babashka pod and CLI | `collet-cli/` |
| Shared test fixtures | `test-fixtures/` |
| Build and release tooling | `build/src/`, `build/test/`, `bb.edn` |
| Examples and docs | `examples/`, `docs/`, `README.md` |
| Agent tooling | `dev/user.clj`, `scripts/agent/` |

## Golden Commands

Run repository-level commands from the repository root.

| Task | Command |
| --- | --- |
| Start the worktree nREPL | `clojure -M:dev:nrepl` |
| Discover nREPL ports | `clj-nrepl-eval --discover-ports` |
| Discover ports with the fallback client | `bb scripts/agent/nrepl-eval.bb --discover` |
| Reload changed project code | `clj-nrepl-eval -p <port> "(user/reload)"` |
| Repair Clojure delimiters | `clj-paren-repair path/to/file.clj` |
| Inspect the package graph | `bb kmono query` |
| Test one package | `bb test:module <module>` |
| Run non-integration tests | `bb test:unit` |
| Run Docker integration tests | `bb test:integration` |
| Run every test | `bb test` |
| Build packages | `bb build [module]` |
| Verify artifact contracts | `bb verify` |
| Inspect the release plan | `bb release:plan` |

Package selectors are directory names such as `collet-core`,
`collet-action-http`, `collet-actions`, `collet-app`, and `collet-cli`.

## Operating Rules

- Preserve user-owned changes. Inspect a dirty file before editing it, and do
  not revert, overwrite, or clean unrelated work.
- Do not run destructive file or Git operations unless the user explicitly
  requested the exact operation.
- Keep one logical change per branch or worktree. Use the active worktree for
  every command so REPL state, source paths, and Git state agree.
- Keep runtime changes and package-specific tests/resources in the owning
  package. Put reusable unpublished test helpers in `test-fixtures`.
- Internal source dependencies stay as top-level `:local/root` entries. Do not
  replace them with Maven versions or edit generated POMs.
- Root build, CI, documentation, agent, and development files do not select a
  package release. If a packaging change alters an artifact, change the owning
  package metadata in the same release-producing commit.
- Never run `bb release` unless the user explicitly requests publication.

## Clojure and REPL Rules

- Clojure work is REPL-first. Before editing any Clojure source, test, fixture,
  or build namespace, attach to or start an nREPL from the active worktree.
- Discover servers with `clj-nrepl-eval --discover-ports`. If the global client
  is unavailable, run
  `bb scripts/agent/nrepl-eval.bb --discover`.
- If no suitable worktree REPL is running, start
  `clojure -M:dev:nrepl` in a long-running terminal, then rediscover its port.
- If a sandbox denies a loopback socket with `Operation not permitted`, rerun
  the exact start or eval command through the environment's approval mechanism.
  The repository fallback client cannot bypass OS or sandbox permissions.
- Apply runtime and shared-fixture edits with
  `clj-nrepl-eval -p <port> "(user/reload)"`. The fallback form is:

  ```shell
  bb scripts/agent/nrepl-eval.bb \
    --port <port> \
    --code "(user/reload)"
  ```

- The root reload scope is every existing `collet-*/src` directory plus
  `test-fixtures/src`. Package tests and `build/src` remain authoritative
  through their isolated CLI test commands.
- Never use `:reload-all` or `tools.namespace/refresh-all`. They can re-evaluate
  library protocols and orphan live instances.
- Recreate pipelines, records, deftypes, protocol implementations, publishers,
  and other stateful values after reload. Restart the JVM if a value is bound
  to a stale class or protocol identity.
- Keep evals small, deterministic, and bounded. Do not run destructive file,
  Git, publication, or network actions through nREPL without explicit
  authorization.
- If startup, rediscovery, the fallback client, and required sandbox approval
  all fail, report the REPL as a blocker instead of switching silently to a
  CLI-only Clojure workflow.

## REPL-First Code Discovery

Use nREPL to inspect dependency and library APIs. Do not scrape source from
`~/.m2`, Git dependency caches, JARs, or generated POMs when the namespace is
available on the development classpath.

```clojure
(require '[some.library :as lib] :reload)
(dir some.library)
(doc lib/function)
(source lib/function)
(ns-publics 'some.library)
```

Repository source files may be read normally. Use `:reload` for manual
namespace investigation so an earlier require does not hide recent edits.

## Parenthesis Repair

Do not manually repair unbalanced Clojure delimiters.

```shell
clj-paren-repair path/to/file.clj
```

Run the command from the repository root. File mode repairs delimiters and
formats with cljfmt using this repository's `.cljfmt.edn`. It accepts multiple
files when one edit broke more than one form.

Use the tool only after a reader or delimiter failure, not as the routine
formatter. Inspect the resulting diff, confirm the intended form structure,
then rerun `(user/reload)` and the affected tests. If the command is missing or
cannot repair the file, report the blocker rather than guessing.

## Module Boundaries

- `collet-core` owns the pipeline engine and built-in core actions.
- Each `collet-action-*` package owns one optional integration. Add a direct
  `:local/root` dependency only when that action genuinely uses another
  workspace package.
- `collet-actions` is the compatibility aggregate. It re-exports action
  namespaces through dependencies and should not accumulate new runtime
  implementation.
- `collet-app` owns the JVM entrypoint, runtime configuration, metrics, and
  deployable JAR/image inputs.
- `collet-cli` owns the Babashka pod protocol and CLI archive.
- `test-fixtures` is unpublished and reusable only by tests and development
  tooling.
- `build` owns package discovery, dependency ordering, artifact verification,
  version planning, publication orchestration, and tags.

When a package boundary, namespace, entrypoint, or exceptional output changes,
update that package's `:collet/artifact` map and its verification coverage.

## Validation Rules

- Documentation-only changes: check referenced paths, commands, Markdown links,
  and `git diff --check`.
- Runtime Clojure changes: reload through nREPL, run
  `bb test:module <module>`, then `bb test:unit`.
- Test or build-tool Clojure changes: keep the required worktree REPL attached,
  then run the owning isolated test command. The root reload path does not add
  all package tests or build dependencies to one JVM.
- Docker-, database-, LocalStack-, app-startup-, CLI-startup-, or image-related
  changes: run `bb test:integration` in addition to unit tests.
- Dependency, artifact, namespace, executable, or packaging changes: finish
  with `bb verify` and `bb release:plan`.
- Agent/REPL tooling changes: test non-interactive `:dev` loading, start a real
  nREPL, evaluate through both clients, exercise documented error paths, and
  verify parenthesis repair with a temporary malformed file.
- Before reporting completion, review the full diff and rerun every command
  that supports the final claims.

## Release Rules

- Kmono derives independent versions from landed conventional commits and
  package tags, not branch names or a source version file.
- `fix:` produces a patch, `feat:` a minor, and `type!:` or a
  `BREAKING CHANGE:` footer a major version.
- Documentation, tests, test resources, configs, and development files are
  ignored for package selection. A runtime change without a release-producing
  landed commit makes release planning fail.
- `bb release:plan` is read-only and should be run before merge or publication.
- `bb release` tests, verifies, builds, publishes Maven artifacts, and tags
  packages. It is approval-gated and does not publish the CLI archive or Docker
  image.

## Task Router

| Task | Read first |
| --- | --- |
| REPL or agent Clojure workflow | `docs/repl-workflow.md` |
| Package/dependency change | `docs/development.md` |
| Action behavior | `docs/actions.md` |
| Selection DSL | `docs/select-syntax.md` |
| Conditions | `docs/condition-syntax.md` |
| Dependency loading | `docs/deps.md` |
| Modular artifact compatibility | `docs/module-migration.md` |
| Versioning or publication | `docs/releasing.md` |
| Deployment image | `collet-app/deploy.md` |
| Future architecture | `COLLET_NEXT.md` |

## Reference Docs

- `docs/repl-workflow.md` - mandatory agent REPL lifecycle and recovery.
- `docs/development.md` - workspace commands, package changes, branches, and
  Kmono version behavior.
- `docs/module-migration.md` - module boundaries and compatibility.
- `docs/releasing.md` - release planning, publication, and recovery.
- `README.md` - pipeline, action, application, and module overview.
