# Application deployment

The application requires JDK 21 or newer. Build its preserved uberjar filename from
the repository root:

```shell
bb build collet-app
java -jar collet-app/target/collet.jar \
  -s collet-app/configs/sample-pipeline.edn \
  -c '{}'
```

The application library JAR can be installed independently with
`bb install collet-app`; the executable artifact remains
`collet-app/target/collet.jar` with main namespace `collet.main`.

## Local Docker image

The Docker build context must be the repository root because the builder consumes
the Kmono workspace, root build implementation, core, and application packages:

```shell
docker build -f collet-app/Dockerfile -t collet .

docker run --rm \
  -e PIPELINE_SPEC='{:name :example :tasks []}' \
  -e PIPELINE_CONFIG='{}' \
  -p 8080:8080 \
  -m 500m \
  collet
```

The builder uses Clojure CLI with JDK 21. The runtime image preserves the existing
JVM options, JMX agent on port 8080, `/tini` entrypoint, environment variables, and
startup command.

## Multi-architecture image

Create and bootstrap a Docker Buildx builder once:

```shell
docker buildx create --name collet-builder
docker buildx use collet-builder
docker buildx inspect --bootstrap
```

After `bb release` creates an application package tag, authenticate to the target
registry and create a separate, clean, detached worktree of that exact tag. App and
core versions are independent: derive the app version from the tag and set the core
version to the exact `io.velio/collet-core` version in the released app POM or
reviewed release plan. Both versions and the tag revision are required build inputs:

```shell
tag='io.velio/collet-app@0.2.8'
app_version=${tag##*@}
core_version='0.2.8' # exact io.velio/collet-core version used by this app
docker_worktree=$(mktemp -d)
git worktree add --detach "$docker_worktree" "$tag"
cd "$docker_worktree"
revision=$(git rev-parse "$tag^{}")
image="velioio/collet:$app_version"

# Build one local platform first so its labels and embedded JAR can be checked.
docker buildx build \
  -f collet-app/Dockerfile \
  --build-arg COLLET_CORE_VERSION="$core_version" \
  --build-arg COLLET_VERSION="$app_version" \
  --build-arg COLLET_REVISION="$revision" \
  --tag "$image" \
  --platform linux/amd64 \
  --load .
bb release:verify-image "$tag" "$image"

# Only publish the multi-architecture image after local verification succeeds.
docker buildx build \
  -f collet-app/Dockerfile \
  --build-arg COLLET_CORE_VERSION="$core_version" \
  --build-arg COLLET_VERSION="$app_version" \
  --build-arg COLLET_REVISION="$revision" \
  --tag "$image" \
  --tag velioio/collet:latest \
  --platform linux/arm64,linux/amd64 \
  --push .
```

Repository release tasks never push Docker images. Docker publication remains an
explicit operation from the app package tag. Return to the original checkout before
running `git worktree remove "$docker_worktree"`. A Docker push failure does not
change Maven publication, the CLI GitHub release, or any package version tag.
