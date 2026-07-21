# Releasing Collet

Collet uses Kmono for package discovery, dependency ordering, change detection, and
independent semantic versions. Kaven publishes the JAR and POM already produced by
`tools.build`. Collet keeps only the repository-specific safety checks and artifact
layouts around those tools.

Versions do not live in source files. Tags use Kmono's
`<coordinate>@<version>` format:

```text
io.velio/collet-core@0.2.8
io.velio/collet-action-http@0.2.9
io.velio/collet-actions@0.2.11
io.velio/collet-app@0.3.0
io.velio/collet-cli@0.2.10
```

Different packages can have different versions. Historical `v0.2.x` tags remain in
Git but are not package tags. If no package tags exist, the first modular release
bootstraps all 14 packages at `0.2.8`.

## What each tool owns

- Kmono reads the workspace from `deps.edn`, resolves its graph and package tags,
  applies conventional-commit version changes, bumps affected dependents, converts
  internal `:local/root` dependencies to exact Maven versions, and runs builds in
  dependency order.
- `tools.build` copies sources/resources and creates the POM, library JAR,
  application uberjar, CLI pod JAR, and CLI archive.
- Kaven reads Maven repository credentials from `~/.m2/settings.xml` and deploys the
  exact generated JAR and POM to Clojars.
- Babashka provides short root commands. It contains no graph or version logic.
- `collet.release` only checks Git state, runs the quality gates, invokes the build
  and Kaven, creates Kmono tags, and atomically pushes them.

This boundary is intentional: Collet does not maintain its own version file,
dependency graph, publication client, release transaction, or recovery journal.

## Prerequisites

- JDK 21 or newer, Clojure CLI, and Babashka.
- Docker for `bb test` and the integration suite.
- A clean local `main` exactly synchronized with `origin/main`.
- Clojars credentials in Maven's standard `~/.m2/settings.xml` file.

Use a Clojars deploy token as the password:

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <servers>
    <server>
      <id>clojars</id>
      <username>YOUR_CLOJARS_USERNAME</username>
      <password>YOUR_CLOJARS_DEPLOY_TOKEN</password>
    </server>
  </servers>
</settings>
```

Kmono 4.12.3, Kaven 1.0.0, and `tools.build` 0.10.14 are pinned in the root
`deps.edn`; maintainers do not install project-specific copies. CI builds and
verifies artifacts but never publishes them.

## Version policy

The highest release-producing conventional commit for a package determines its next
version:

| Commit | Change |
|---|---|
| `fix: ...` | Patch |
| `feat: ...` | Minor |
| `type!: ...` or `BREAKING CHANGE:` | Major |

Package Markdown, tests, test resources, sample configs, and development files are
ignored. A remaining package change with no release-producing conventional commit
makes planning fail with guidance. A runtime change therefore needs `fix:`, `feat:`,
or a breaking marker in its commit message or squash-merge title.

When a package version changes, Kmono gives affected dependents a transitive patch
release so their generated POMs can reference the new exact version. For example,
changing an action also releases `io.velio/collet-actions`; changing core can release
the actions, aggregate, app, and CLI through the graph.

There is no manual bump command, release commit, or follow-up snapshot commit.

## Planning

From the repository root:

```shell
bb release:plan
```

The command prints only the packages Kmono will release, with the next version, tag,
and whether the package is published to Maven or only tagged. Planning is read-only:
it does not build, publish, or create tags.

Releases are deliberately workspace-wide. There is no module-scoped release command
and no separate `release:all`; both were redundant with Kmono's changed-package and
dependent selection.

## Publishing

After reviewing the plan, run:

```shell
bb release
```

The command performs this sequence:

1. Fetches tags and requires a clean, synchronized `main`.
2. Resolves and prints the Kmono release candidates.
3. Rejects any candidate tag that already exists.
4. Runs `bb test` and `bb verify`.
5. Rechecks that the release revision did not move.
6. Builds the selected packages in Kmono dependency order. Generated POMs contain
   exact Maven versions for internal dependencies, never `:local/root`.
7. Fetches and rejects candidate tags again, then uses Kaven to deploy every
   publishable JAR/POM to Clojars in dependency order.
   `io.velio/collet-cli` is built but is tag-only.
8. Creates all candidate package tags at the captured revision and pushes them to
   `origin` atomically.

`bb release` does not create a GitHub release and does not build or push a Docker
image. Those are explicit later operations from the CLI and app package tags.

## Failure recovery

Maven coordinates are immutable, so the release command is fail-fast and recovery
after publication begins is intentionally manual.

- Before deployment: no remote release state changed. Fix the error and rerun.
- During deployment: earlier coordinates may already exist on Clojars. Keep the
  printed plan and release revision, inspect Clojars, and publish only the missing
  JAR/POM pairs in Kmono order with Kaven. Do not reuse an existing coordinate for
  different source.
- After deployment but before the tag push: the local package tags identify the
  published source. Fix Git access and retry the exact atomic tag push.

Do not create or push the planned tags while any planned Maven coordinate is missing.
This rare manual path is the tradeoff for not maintaining a custom remote-probing
transaction engine or temporary Maven repository.

The failed release leaves the planned artifacts in each package's `target/`
directory. For each coordinate confirmed missing on Clojars, substitute its exact
paths in this Kaven invocation:

```shell
clojure -Sdeps '{:deps {com.kepler16/kaven {:mvn/version "1.0.0"}}}' \
  -M -e "(require '[k16.kaven.deploy :as d])
          (d/deploy {:jar-path \"/absolute/path/package.jar\"
                     :pom-path \"/absolute/path/classes/META-INF/maven/group/artifact/pom.xml\"
                     :repository {:id \"clojars\"
                                  :url \"https://repo.clojars.org/\"}})"
```

After every planned Maven coordinate exists, create every planned tag at the original
release revision and push the complete set atomically:

```shell
release_revision='FULL_GIT_REVISION_FROM_THE_FAILED_RUN'
planned_tags=(
  'io.velio/collet-core@0.2.9'
  'io.velio/collet-cli@0.2.9'
)
for tag in "${planned_tags[@]}"; do
  git tag "$tag" "$release_revision"
done
git push --atomic origin "${planned_tags[@]}"
```

If tag creation already completed and only the push failed, do not recreate the
tags; run only the final atomic push after verifying their revisions.

## CLI GitHub release

The CLI package is tag-only because its public distribution is a tar archive rather
than a Maven artifact. Build it from a clean detached worktree of its tag and upload
the preserved archive:

```shell
tag='io.velio/collet-cli@0.2.8'
cli_worktree=$(mktemp -d)
git worktree add --detach "$cli_worktree" "$tag"
cd "$cli_worktree"

bb build collet-cli
tar -tzf collet-cli/target/collet-cli.tar.gz
gh release create "$tag" collet-cli/target/collet-cli.tar.gz
```

The archive remains rooted at `collet-cli/` and contains `bb.edn`, executable
`collet.bb`, `collet.pod.jar`, and executable `gum`. Return to the original checkout
before removing the worktree with `git worktree remove "$cli_worktree"`.

## Docker publication

Build an image later from a detached `io.velio/collet-app@<version>` worktree. App
and core versions are independent, so pass both resolved versions plus the tag
revision to Docker:

```shell
tag='io.velio/collet-app@0.2.8'
app_version=${tag##*@}
app_worktree=$(mktemp -d)
git worktree add --detach "$app_worktree" "$tag"
cd "$app_worktree"

core_tag=$(git tag --merged HEAD --list 'io.velio/collet-core@*' \
  --sort=-creatordate | head -1)
core_version=${core_tag##*@}
revision=$(git rev-parse "$tag^{}")
image="velioio/collet:$app_version"

docker build -f collet-app/Dockerfile \
  --build-arg COLLET_CORE_VERSION="$core_version" \
  --build-arg COLLET_VERSION="$app_version" \
  --build-arg COLLET_REVISION="$revision" \
  -t "$image" .
docker run --rm \
  -e PIPELINE_SPEC='{:name :release-check :tasks []}' \
  -e PIPELINE_CONFIG='{}' \
  "$image"
docker push "$image"
```

See [`collet-app/deploy.md`](../collet-app/deploy.md) for Buildx and runtime details.
