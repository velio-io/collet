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
the shared graph, build support, core, and application modules:

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

After the coordinated Maven release creates its one `v<version>` tag, authenticate
to the target registry and create a separate, clean, detached worktree of that tag.
Derive both image identity values from the tag checkout:

```shell
tag=v0.2.8
version=${tag#v}
docker_worktree=$(mktemp -d)
git worktree add --detach "$docker_worktree" "$tag"
cd "$docker_worktree"
revision=$(git rev-parse "$tag^{}")
image="velioio/collet:$version"

# Build one local platform first so its labels and embedded JAR can be checked.
docker buildx build \
  -f collet-app/Dockerfile \
  --build-arg COLLET_VERSION="$version" \
  --build-arg COLLET_REVISION="$revision" \
  --tag "$image" \
  --platform linux/amd64 \
  --load .
bb release:verify-image "$tag" "$image"

# Only publish the multi-architecture image after local verification succeeds.
docker buildx build \
  -f collet-app/Dockerfile \
  --build-arg COLLET_VERSION="$version" \
  --build-arg COLLET_REVISION="$revision" \
  --tag "$image" \
  --tag velioio/collet:latest \
  --platform linux/arm64,linux/amd64 \
  --push .
```

Repository release tasks publish Maven artifacts only. Docker pushes remain an
explicit deployment operation. Return to the original checkout before running
`git worktree remove "$docker_worktree"`. A Docker push failure does not change
Maven publication, GitHub release creation, or the coordinated version state.
