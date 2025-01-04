### Local build

```shell
cp -r ~/.m2/repository/io/velio velio

docker build -t collet .

docker run \
    -e PIPELINE_SPEC="/data/pipeline.edn" \
    -e PIPELINE_CONFIG="/data/config.edn" \
    -p 8080:8080 \
    -m 500m \
    collet
```

### Production build

Create a builder for multi-arch builds

```shell
docker buildx create --name collet-builder
docker buildx use collet-builder
```

Verify that the builder is created

```shell
docker buildx inspect --bootstrap
```

Login to Docker Hub.

Build and push the image

```shell
docker buildx build --tag velioio/collet:0.1.0 --tag velioio/collet:latest --platform linux/arm64,linux/amd64 --push .
```