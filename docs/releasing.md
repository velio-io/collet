# Releasing Collet

Collet uses one coordinated version for every module, application artifact, and CLI
distribution. The version is declared once in `build/modules.edn`; internal Maven
coordinates in module `deps.edn` files are synchronized by repository tooling and
must never be edited individually.

Maven publication, GitHub/CLI distribution, and Docker publication are separate
operations. This keeps credentials and failure recovery isolated while allowing all
outputs to share the same version and Git tag.

## Prerequisites

- JDK 21 or newer, Clojure CLI, and Babashka.
- Docker for the complete integration and artifact verification gates.
- Clojars credentials in `CLOJARS_USERNAME` and `CLOJARS_PASSWORD`; use a Clojars
  deploy token as the password.
- The `main` branch checked out with a clean worktree and no commits ahead of or
  behind `origin/main`.

## Coordinated versions

Use `bb version` when changing the development target without publishing:

```shell
bb version 0.3.0-SNAPSHOT
```

The command validates the requested version, updates the shared graph version and
every internal Maven pin as one operation, and rejects any stale or unexpected
internal coordinate. It does not commit, tag, publish, or push.

## Maven release

Release the current snapshot with the default patch progression:

```shell
bb release
```

The optional level controls the next development snapshot:

```shell
bb release :patch
bb release :minor
bb release :major
```

For a current version of `0.2.8-SNAPSHOT`, every command releases `0.2.8`. The next
development version is selected as follows:

| Level | Next version |
|---|---|
| `:patch` | `0.2.9-SNAPSHOT` |
| `:minor` | `0.3.0-SNAPSHOT` |
| `:major` | `1.0.0-SNAPSHOT` |

The release command performs the following workflow:

1. Confirms the branch, worktree, remote synchronization, credentials, versions,
   internal dependency pins, and absence of the release tag.
2. Changes every module and internal Maven pin to the release version and commits
   `Release <version>`.
3. Runs the complete unit, Docker integration, build, isolated-consumer, and
   artifact verification gates.
4. Publishes all Maven artifacts to Clojars in dependency order.
5. Creates one `v<version>` tag pointing to the release commit.
6. Changes every module and internal Maven pin to the next snapshot and commits
   `Begin <version>-SNAPSHOT`.
7. Atomically pushes `main` and the release tag.

The CLI distribution is not a Maven publication. It is built and verified during
the release gate but is not uploaded by `bb release`.

## Failure recovery

Nothing is published or pushed when a deterministic validation step fails. The
local release commit remains available so the problem can be fixed and the release
commit amended before retrying.

Publication is irreversible and occurs one Maven coordinate at a time. If Clojars
or the network fails partway through, the command stops before creating a tag,
bumping the next snapshot, or pushing. It reports the coordinates that completed
and the coordinate that failed so the release can be reconciled before continuing.

If only the final atomic push fails, both commits and the release tag remain local;
retry the reported atomic Git push without republishing artifacts.

## GitHub and CLI distribution

After the Maven release succeeds, create the GitHub release explicitly from the
same tag and attach the preserved CLI archive:

```shell
bb build collet-cli

gh release create v0.2.8 \
  collet-cli/target/collet-cli.tar.gz
```

## Docker publication

Build and publish the application image separately using the same release version:

```shell
docker build -f collet-app/Dockerfile -t <registry>/collet:0.2.8 .
docker push <registry>/collet:0.2.8
```

See [`collet-app/deploy.md`](../collet-app/deploy.md) for runtime configuration and
deployment details.
