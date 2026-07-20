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

After authenticating to the target registry, build from the repository root:

```shell
docker buildx build \
  -f collet-app/Dockerfile \
  --tag velioio/collet:VERSION \
  --tag velioio/collet:latest \
  --platform linux/arm64,linux/amd64 \
  --push .
```

Repository release tasks publish Maven artifacts only. Docker pushes remain an
explicit deployment operation: after the coordinated Maven release creates its one
`v<version>` tag, run the Buildx command above yourself with that version. A Docker
push failure does not change Maven publication, GitHub release creation, or the
coordinated version state.
