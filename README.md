# COLLET

With collet project you can:
- Create tasks to execute actions
- Combine tasks into a pipelines
- Execute pipelines to move data around

### Development

- start REPL as usual
- spin up containers for monitoring pipelines execution with command
  `docker-compose rm -f && docker-compose up` (includes elasticsearch, kibana, jaeger, prometheus, grafana)
- navigate to the `dev/src/dev.clj` namespace
- execute `(start-publishers)` to enable logging and tracing
- you can run all tests in the project with `(test)` command

Graphana is available at `http://localhost:3000` with default credentials `admin/grafana`
Jaeger is available at `http://localhost:16686`
Kibana is available at `http://localhost:9000`

### Run with Docker
Build the image
```shell
# on Linux
docker build -t collet .

# on MacOS
docker build --platform=linux/amd64 -t collet .
```
Running the image. You need to provide a pipeline spec and optionally a pipeline config map to the entry point. 
It could be a string with raw Clojure map or a path to the EDN file with a pipeline spec or S3 path to the file.
You can expose the 8080 port to access the JMX metrics or change the port with JMX_PORT env variable.
```shell
# with raw Clojure map
docker run -p 8080:8080 -e PIPELINE_SPEC="{:name :my-pipeline ...}" -e PIPELINE_CONFIG="{:my-secret #env SECRET_VALUE}" collet

# with local file (mount the volume with the pipeline spec)
docker run -p 8080:8080 -v ./test/collet:/app/data -e PIPELINE_SPEC="/app/data/sample-pipeline.edn" collet

# with S3 file
# Also, you can pass a presigned URL to the PIPELINE_SPEC to access file in the S3 bucket
docker run -p 8080:8080  -e PIPELINE_SPEC="s3://test-user:test-pass@test-bucket/test-pipeline-config.edn?region=eu-west-1" collet
```

### TODO
- documentation
- add action parameters to logs
  - obfuscate PII data

- tmd for reading datasets
- parallel tasks execution
- evaluate the use of nippy/arrow data formats for intermittent data storage
- evaluate the use of DuckDB for querying databases and files
