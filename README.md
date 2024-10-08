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

#### Build the image

```shell
# on Linux
docker build -t collet .

# on MacOS
docker build --platform=linux/amd64 -t collet .
```

#### Run container

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

#### Logging and monitoring options

You can provide these environment variables to enable logging and monitoring:

- `CONSOLE_PUBLISHER=false` - set to `true` to enable console publisher (prints logs to the console)
- `CONSOLE_PUBLISHER_PRETTY=true` - logs by default are printed in the "pretty" format, if you want to print them
  without formatting set this variable to false
- `FILE_PUBLISHER=false` - set to `true` to enable file publisher (prints logs to the file in the JSON format. One line
  in the file is one log entry)
- `FILE_PUBLISHER_FILENAME=tmp/collet-*.log` - set the filename for the file publisher (if you include `*` it will be
  replaced with the current date and log files will be rotated on dayly basis)
- `ELASTICSEARCH_PUBLISHER=false` - set to `true` to enable Elasticsearch publisher
- `ELASTICSEARCH_PUBLISHER_URL=http://localhost:9200` - set the URL for the Elasticsearch publisher
- `ZIPKIN_PUBLISHER=false` - set to `true` to enable Zipkin publisher
- `ZIPKIN_PUBLISHER_URL=http://localhost:9411` - set the URL for the Zipkin publisher
