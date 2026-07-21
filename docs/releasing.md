# Releasing Collet

Collet packages are versioned independently by Kmono 4.12.3. Versions do not live
in source files and are never rewritten during publication. Kmono reads package
tags and conventional commits, and it converts internal `:local/root` dependencies
to exact Maven versions when the root build asks it to generate POMs.

Tags use `<coordinate>@<version>`, for example:

```text
io.velio/collet-core@0.2.8
io.velio/collet-action-http@0.2.9
io.velio/collet-actions@0.2.11
io.velio/collet-app@0.3.0
io.velio/collet-cli@0.2.10
```

The versions in this example intentionally differ. Historical `v0.2.x` tags are
retained, but Kmono ignores them for package versioning. If no package tags exist,
the first modular plan bootstraps all 14 packages at `0.2.8`.

Maven publication, the CLI GitHub release, and Docker publication are separate
operations. `bb release` deploys publishable packages to Clojars and creates the
package tags. It builds and tags the non-Maven CLI distribution but never creates a
GitHub release or pushes an image.

## Prerequisites

- JDK 21 or newer, Clojure CLI, and Babashka.
- Docker for the complete test and artifact-verification gates.
- Kmono/Kmono CLI 4.12.3, `tools.build` 0.10.14, and `deps-deploy` 0.2.5 are pinned
  by the root `deps.edn`; do not install separate project versions.
- `main` checked out with a clean worktree and exactly synchronized with
  `origin/main`.
- `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` when the selected plan contains Maven
  packages. Use a Clojars deploy token as the password. A tag-only CLI plan does not
  require Clojars credentials.

Only run release commands from the repository root. CI plans and verifies releases
but never deploys.

## How the build is divided

Kmono owns workspace discovery, the dependency graph, package order, conversion of
internal `:local/root` dependencies to Maven coordinates, package-tag version lookup,
conventional-commit version increments, and dependent patch bumps. Collet does not
wrap or reimplement those algorithms.

The root build contains only repository-specific work that Kmono does not provide:

- `collet.build` creates library JARs, application/CLI uberjars, and the CLI archive
  with `tools.build` while preserving their public filenames and layout.
- `collet.release` performs the release preflight, invokes Kmono's version plan,
  deploys Maven artifacts with `deps-deploy`, and pushes package tags.
- `collet.verify` checks the small public artifact contract: coordinates, required
  namespaces, filenames, entrypoints, archive layout and modes, and absence of
  snapshot or local-root data in published POMs.

Babashka tasks are shell-level entrypoints only. They contain no package graph,
version, or release policy.

## Version and change policy

The highest release-producing conventional commit for a package determines its next
version:

| Commit | Version change |
|---|---|
| `fix: ...` | Patch |
| `feat: ...` | Minor |
| `type!: ...` or a `BREAKING CHANGE:` footer | Major |

Only conventional commit messages determine whether a release is produced.
Documentation, tests, CI, and development-only commits use
non-releasing conventional types such as `docs:`, `test:`, `ci:`, or `chore:` and do
not publish. Runtime changes must use `fix:`, `feat:`, or a breaking-change marker;
using `chore:` for a runtime change intentionally produces no release.

When a package changes version, its workspace dependents receive patch releases
transitively so their generated POMs can pin the new exact dependency. Any action
release therefore gives `io.velio/collet-actions` a patch release as well.

There is no source version file and no `bb version` command. There are also no
release commits or follow-up development-version commits.

## Plan and package selection

Planning is read-only: it reads local tags and commits, prints package, current and
next versions, reason, package tag, and whether the result is a Maven publication or
tag-only distribution, and does not require credentials.

```shell
# Every changed package
bb release:plan

# Changed packages in the selected package and its required dependency chain
bb release:plan collet-action-http
```

`bb release` with no package and `bb release:all` both release every changed
package:

```shell
bb release
bb release:all
```

`bb release <module>` selects release candidates only from that package and its
required dependencies, in Kmono dependency order. It does not add dependents
automatically; use `bb release:plan` without a module when the complete independent
package plan is required:

```shell
bb release collet-action-http
```

Use directory-style module names such as `collet-core`, `collet-action-http`,
`collet-actions`, `collet-app`, or `collet-cli`.

## Local release workflow

After reviewing `bb release:plan`, a maintainer runs the matching release command.
It performs this guarded sequence:

1. Fetches package tags, requires a clean `main`, and requires local `HEAD` to equal
   `origin/main`.
2. Requires Clojars credentials only when at least one selected package is
   publishable.
3. Runs `bb test` and `bb verify` before publishing.
4. Builds every selected artifact once with the exact planned package-version map.
5. Deploys publishable Maven artifacts through `deps-deploy` in Kmono topological
   order. The CLI archive is built but remains tag-only.
6. Creates the selected package tags at the release revision after every deployment
   succeeds.
7. Atomically pushes those tags.

Generated and embedded POMs contain exact internal Maven versions, SCM package tag,
license metadata, and build identity.

## Failure recovery

The release command is deliberately fail-fast. Maven publication cannot be rolled
back, so recovery after a partial publication is manual.

- A failure before deployment changes no remote state; fix the cause and rerun.
- A deployment failure can leave earlier Maven coordinates published because Maven
  publication is irreversible. Record the printed plan, inspect Clojars, and finish
  or reconcile the remaining coordinates manually before creating package tags.
- A tag-push failure occurs after successful deployment. Inspect the local tags and
  retry the atomic push after fixing the Git remote problem.
- Never overwrite or reuse an existing Maven version for different source.

This recovery is intentionally manual: partial releases are rare, and keeping a
custom recovery engine would make the normal release path harder to understand and
maintain.

For a partial Maven publication, use the failed run's already-built target JAR and
POM when they are still present. If they are not, rerun the same `bb release
[module]` command to recreate the exact planned artifacts; expect deployment to stop
when it reaches an existing coordinate after the build. Compare the printed plan with
Clojars, then deploy only the missing target JAR/POM coordinates in Kmono order:

```shell
clojure -X:release :installer :remote :sign-releases? false \
  :artifact '"/absolute/path/package.jar"' \
  :pom-file '"/absolute/path/pom.xml"'
```

After every selected Maven coordinate exists, create every exact planned package tag
at the release revision and push all of them atomically. Copy every selected tag from
the printed plan into this command; do not omit tag-only packages:

```shell
release_revision=$(git rev-parse HEAD)
planned_tags=(
  'io.velio/collet-core@0.2.9'
  'io.velio/collet-cli@0.2.9'
)
for planned_tag in "${planned_tags[@]}"; do
  git tag "$planned_tag" "$release_revision"
done
git push --atomic origin "${planned_tags[@]}"
```

Do not create or push any package tag while a selected Maven coordinate is missing.

## GitHub and CLI distribution

Create the CLI GitHub release later from a separate, clean, detached worktree of its
own package tag. The verifier requires that detached checkout and checks the pod
JAR, archive copy, Maven/build identity, version, and Git revision before upload:

```shell
tag='io.velio/collet-cli@0.2.8'
cli_worktree=$(mktemp -d)
git worktree add --detach "$cli_worktree" "$tag"
cd "$cli_worktree"

bb build collet-cli
bb release:verify-cli "$tag"

gh release create "$tag" \
  collet-cli/target/collet-cli.tar.gz
```

This preserves `collet-cli/target/collet.pod.jar`,
`collet-cli/target/collet-cli.tar.gz`, the `collet-cli/` archive root, and executable
`collet.bb` and `gum`. Return to the original checkout before removing the temporary
worktree with `git worktree remove "$cli_worktree"`.

## Docker publication

Build the image later from a separate detached `io.velio/collet-app@<version>`
worktree. Because app and core versions are independent, derive the app version from
the tag and ask the root build for the exact Kmono-resolved core version at that tagged
commit. Pass both values and the tag commit as build inputs, verify the local image,
and only then upload it:

```shell
tag='io.velio/collet-app@0.2.8'
app_version=${tag##*@}
docker_worktree=$(mktemp -d)
git worktree add --detach "$docker_worktree" "$tag"
cd "$docker_worktree"
core_version=$(clojure -T:build package-version :module :collet-core)
revision=$(git rev-parse "$tag^{}")
registry=registry.example.com/your-team
image="$registry/collet:$app_version"

docker build -f collet-app/Dockerfile \
  --build-arg COLLET_CORE_VERSION="$core_version" \
  --build-arg COLLET_VERSION="$app_version" \
  --build-arg COLLET_REVISION="$revision" \
  -t "$image" .
bb release:verify-image "$tag" "$image"
docker push "$image"
```

The verifier checks OCI version/revision labels, the application JAR's embedded
identity, and that its embedded POM declares exactly one direct
`io.velio/collet-core` dependency at the Kmono-resolved version. Return to the
original checkout before removing the temporary worktree with
`git worktree remove "$docker_worktree"`.

See [`collet-app/deploy.md`](../collet-app/deploy.md) for runtime and
multi-architecture deployment details.
