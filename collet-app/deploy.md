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