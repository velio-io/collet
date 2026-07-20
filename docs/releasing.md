# Releasing modules

Release tooling is implemented with Babashka and `deps-deploy` 0.2.5. It publishes
Maven artifacts only. It does not push commits or tags, create GitHub releases, or
build/push Docker images.

## Prerequisites

- JDK 21 or newer, Clojure CLI, and Babashka.
- Docker when the selected module has integration tests.
- Clojars credentials in `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` (a Clojars deploy
  token is used as the password).
- A clean Git worktree.

## Preparing versions

`build/modules.edn` is the version authority. Update versions intentionally before
publication:

1. Replace the target module's `-SNAPSHOT` version with the exact release version.
2. Update its own base `deps.edn` if it pins changed internal coordinates.
3. Update every dependent module's central graph version and base Maven coordinate
   when that dependency will be released.
4. For `collet-actions`, pin every action module to an exact non-snapshot version.
5. Commit the version changes and confirm the worktree is clean.

Release tasks never edit a version, dependency, source file, or documentation file.

## Commands

Release one artifact:

```shell
bb release collet-action-http
```

Release all publishable Maven artifacts in graph order:

```shell
bb release:all
```

The CLI is a distribution, not a Maven publication, and is excluded from
`release:all`.

For each module, the release task:

1. Requires a clean worktree and a new tag name.
2. Rejects snapshot versions in the module's complete internal dependency closure.
3. Confirms base `deps.edn` pins the graph's exact internal versions.
4. Runs the module's full tests in an isolated JVM.
5. Builds and installs its library JAR and verifies POM/JAR/dependency-tree contracts.
6. Deploys the explicit JAR and POM through `deps-deploy`.
7. Creates the local tag `<artifact>-v<version>` only after deployment succeeds.

A single-module release builds against a fresh Maven repository, so every internal
non-snapshot coordinate must already resolve from the configured remote repositories;
a same-version artifact installed only in the developer's local cache cannot satisfy
the gate.

`release:all` uses a fresh staging repository and prepares modules in graph order. It
completes version/tag preflight, tests, builds, and artifact verification for the
entire release set before the first remote deployment. An external repository or
network failure can still interrupt the later deploy phase, but a deterministic
failure in a later module cannot cause earlier modules to be published first.

Inspect and push the resulting tags explicitly after all intended publications have
been confirmed. Build and publish the Docker image using the separate steps in
[`collet-app/deploy.md`](../collet-app/deploy.md), and attach the preserved CLI archive
to a GitHub release only as a separate deliberate operation.
