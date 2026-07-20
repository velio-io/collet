# Issue #43 deps.edn and modular actions implementation plan

> **For Codex:** Execute this checkpoint by checkpoint, keeping each checkpoint green
> before continuing and using the repository verification commands as the final gate.

**Goal:** Replace the legacy build with Clojure CLI, `tools.build`, and Babashka;
publish optional actions as isolated artifacts while preserving public runtime and
distribution contracts.

**Architecture:** A central EDN graph defines module identity, version, dependency,
namespace, test, entrypoint, and output contracts. Thin module builds delegate to
shared `tools.build` functions. Babashka performs topological test/build/install,
artifact verification, isolated-consumer checks, and guarded releases.

**Toolchain:** Clojure 1.12, Clojure CLI, tools.build 0.10.14, Babashka,
deps-deploy 0.2.5, JDK 21, Testcontainers 1.21.4, Docker.

## Checkpoints

1. Establish deterministic compatibility tests with local HTTP fixtures,
   self-managed temporary directories, and current Testcontainers test overrides.
2. Add the root workspace, central graph, shared build support, and independent core
   test/JAR/POM/install behavior.
3. Split ten action artifacts, create the all-actions aggregate, move tests/resources,
   preserve exact dependencies/exclusions, and verify isolated classpaths.
4. Migrate application, CLI archive, and Docker builds while preserving entrypoints,
   filenames, modes, runtime options, and startup tests.
5. Complete all `bb` commands, release guards, CI, artifact/consumer verification,
   and documentation; remove legacy build paths only after replacements pass.

## Final gate

Run `bb test:unit`, `bb test:integration`, `bb verify`, the Docker image startup
check, and a repository scan proving no supported build/CI/Docker/release path invokes
the removed toolchain. Do not deploy, push images, create remote tags, or create
GitHub releases as part of implementation verification.
