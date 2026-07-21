# Releasing Collet

Collet packages are versioned independently by Kmono 4.12.3. Versions do not live
in source files and are never rewritten during publication. Kmono reads package
tags and conventional commits, while the root build converts internal `:local/root`
dependencies to exact Maven versions in generated POMs.

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

## Version and change policy

The highest release-producing conventional commit for a package determines its next
version:

| Commit | Version change |
|---|---|
| `fix: ...` | Patch |
| `feat: ...` | Minor |
| `type!: ...` or a `BREAKING CHANGE:` footer | Major |

Documentation, tests, CI, and development-only paths are ignored and do not publish.
A meaningful runtime/package change with no release-producing commit makes
`bb release:plan` and `bb verify` fail. Fix the commit message—or the PR title that
will become the squash commit—instead of assigning or editing a version manually.

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

# Changed packages in the selected package's required dependency/dependent closure
bb release:plan collet-action-http
```

`bb release` with no package and `bb release:all` both release every changed
package:

```shell
bb release
bb release:all
```

`bb release <module>` starts with changed packages related to that module, then
selects the required changed dependency/dependent fixed-point closure. This keeps a
filtered release internally consistent without publishing unrelated changes:

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
3. Runs `bb test` and `bb verify` with Clojars credentials removed from child
   process environments.
4. Rechecks source identity, builds every selected artifact with the exact planned
   package-version map, and rechecks source identity again.
5. Inspects every selected Maven coordinate. A fresh release requires both POM and
   JAR to be absent; an existing coordinate is never overwritten.
6. Atomically creates the complete set of provisional local package tags at the
   release revision before the first deployment.
7. Deploys missing Maven artifacts through `deps-deploy` in Kmono topological order.
   The CLI archive is built but remains tag-only.
8. Atomically pushes all selected package tags only after every Maven deployment
   succeeds.

Generated and embedded POMs contain exact internal Maven versions, SCM package tag,
license metadata, and build identity. Publication checks verify POM coordinates,
SCM tag, and the JAR's embedded version/revision before an existing coordinate may
be skipped during recovery.

## Failure recovery

The complete set of provisional local package tags is the only resume state. There
is no transaction journal, auxiliary artifact state, version rewrite, or release
commit. If deployment stops after those tags are created, leave them intact and
rerun the same release command from the same clean revision. The rerun rebuilds the
tagged source and the same version plan, verifies matching remote POM/JAR identity,
skips exact matches, deploys absent coordinates, and finally pushes all tags.

Recovery is intentionally strict:

- A fresh release stops if any planned remote coordinate already exists.
- Only a complete set of local package tags pointing at `HEAD` qualifies as a
  resume; missing tags stop safely.
- A remote POM without its JAR, a JAR without its POM, mismatched coordinates, SCM
  tag, version, or embedded source revision stops safely.
- A local tag pointing to another revision is a collision and stops safely.
- A failed atomic tag push leaves the complete local tags available for the same
  verified rerun.

Maven publication is irreversible. Do not delete or move provisional tags to force
a new release, and do not try to overwrite a mismatched Clojars coordinate. Inspect
the reported package and reconcile it with the maintainers before rerunning.

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
