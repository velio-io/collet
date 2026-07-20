# Releasing Collet

Collet uses one coordinated version for every module, application artifact, and CLI
distribution. The version is declared once in `build/modules.edn`; internal Maven
coordinates in module `deps.edn` files are synchronized by repository tooling and
must never be edited individually.

Maven publication, GitHub/CLI distribution, and Docker publication are separate
operations. This keeps credentials and failure recovery isolated while allowing all
outputs to share the same version and Git tag.

Generated POMs are the Maven metadata shipped with each library. They are produced
from the module base dependencies so external consumers resolve the exact released
internal coordinates; contributors never edit generated POMs or internal pins by
hand.

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
every internal Maven pin as one operation, and rejects stale or unexpected internal
pins. Module-local `:version` entries are rejected by `bb verify`'s repository
consistency check. `bb version` does not commit, tag, publish, or push.

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
development version is selected exactly as follows:

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
4. Rechecks that the worktree is clean, `HEAD` is still the release commit, the
   graph and pins still have the release version, and every frozen JAR/POM has the
   captured Maven coordinates and source identity.
5. Publishes all Maven artifacts to Clojars in dependency order.
6. Creates one `v<version>` tag pointing to the release commit.
7. Changes every module and internal Maven pin to the next snapshot and commits
   `Begin <version>-SNAPSHOT`.
8. Atomically pushes `main` and the release tag.

The CLI distribution is not a Maven publication. It is built and verified during
the release gate but is not uploaded by `bb release`.

## Failure recovery

Nothing is published or pushed when preflight fails. If setting the release version,
committing, testing, verification, staging, or the final source check fails before
the first Maven deployment, `bb release` rolls back only its version paths and
release commit. It preserves unrelated worktree changes. Recovery refuses to move
`HEAD` when it no longer identifies the release-owned commit, so an unexpected Git
change is retained for manual inspection instead of being reset.

The command records recovery state in `target/.collet/release-state.edn` and freezes
the exact deployable JAR/POM pairs under `target/.collet/release-artifacts/`. Once
Maven deployment starts, do not delete or edit those files. Rerun the exact same
command (`bb release`, `bb release :minor`, or `bb release :major`) to resume. The
command skips coordinates recorded as complete, checks an in-flight coordinate on
Clojars, recognizes an exact JAR/POM hash match as complete, and deploys only when
both remote files are absent. Tag, next-snapshot commit, and atomic-push failures
are resumed from their recorded phase without redeploying completed coordinates.

Remote publication is irreversible. A partial coordinate, a remote hash mismatch,
or an unavailable remote status stops automatic recovery. Inspect `:completed`,
`:in-flight`, and the frozen `:jar-sha256`/`:pom-sha256` values in the state file;
compare both remote files with the frozen artifacts, and reconcile the Clojars
coordinate before rerunning. Do not delete the state file to force a fresh release
or attempt to overwrite an existing mismatched coordinate. If a published artifact
cannot be reconciled exactly, stop and choose a new release version with the
maintainers. Successful completion removes the recovery state and frozen copies.

## GitHub and CLI distribution

After the Maven release succeeds, build the CLI archive from a separate, clean,
detached worktree of the coordinated tag. Verify its embedded version, Git revision,
and Maven metadata before uploading it. This is not performed by `bb release`:

```shell
tag=v0.2.8
cli_worktree=$(mktemp -d)
git worktree add --detach "$cli_worktree" "$tag"
cd "$cli_worktree"

bb build collet-cli
bb release:verify-cli "$tag"

gh release create "$tag" \
  collet-cli/target/collet-cli.tar.gz
```

Return to the original checkout before removing the temporary worktree with
`git worktree remove "$cli_worktree"`.

## Docker publication

Use a new clean detached tag worktree for the application image as well. Pass the
tag-derived version and exact tag commit into the build, then verify both OCI labels
and the embedded application JAR identity before pushing. `bb release` never builds
or pushes Docker images:

```shell
tag=v0.2.8
version=${tag#v}
docker_worktree=$(mktemp -d)
git worktree add --detach "$docker_worktree" "$tag"
cd "$docker_worktree"
revision=$(git rev-parse "$tag^{}")
registry=registry.example.com/your-team
image="$registry/collet:$version"

docker build -f collet-app/Dockerfile \
  --build-arg COLLET_VERSION="$version" \
  --build-arg COLLET_REVISION="$revision" \
  -t "$image" .
bb release:verify-image "$tag" "$image"
docker push "$image"
```

Return to the original checkout before removing this temporary worktree with
`git worktree remove "$docker_worktree"`.

See [`collet-app/deploy.md`](../collet-app/deploy.md) for runtime configuration and
deployment details.
