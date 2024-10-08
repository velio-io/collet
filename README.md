# COLLET

Collet is a powerful Clojure library designed to simplify the creation and
execution of data processing pipelines (ETL or ELT).
It provides a declarative approach to defining task sequences and their dependencies,
making it easier to manage complex data workflows.

- [Features](#features)
- [Installation](#installation)
- [Usage](#usage)
- [Configuration](#configuration)
    - [Pipeline configuration](#pipeline-specification)
    - [Actions library](#actions-library)
    - [General options](#general-options)
- [Development](#development)

## Features

- Declarative pipeline definition
- Support for both ETL and ELT workflows
- Usable as a standalone Docker container or as a Clojure library
- Robust logging and monitoring capabilities
- Flexible configuration options
- Predefined actions for various data sources
- Task dependency management (DAG support)

## Installation

### As a Docker container

Pull the latest Collet image from DockerHub:

```shell
docker pull velio-io/collet:latest
```

### As a library

Add the following dependency to your project:

For Leiningen:

```clojure
[com.github.velio-io/collet "0.1.0"]
```

For deps.edn:

```clojure
com.github.velio-io/collet {:mvn/version "0.1.0"}
```

## Usage

### As a Docker container

To run Collet, you need to provide a pipeline specification and optionally a pipeline config map.
These can be provided as environment variables in three ways:

1. As a raw Clojure map

```shell
docker run -p 8080:8080 -e PIPELINE_SPEC="{:name :my-pipeline ...}" -e PIPELINE_CONFIG="{:my-secret #env SECRET_VALUE}" collet
```

2. Local file (mount the volume with the pipeline spec)

```shell
docker run -p 8080:8080 -v ./test/collet:/app/data -e PIPELINE_SPEC="/app/data/sample-pipeline.edn" collet
```

3. S3 file:

```shell
docker run -p 8080:8080  -e PIPELINE_SPEC="s3://test-user:test-pass@test-bucket/test-pipeline-config.edn?region=eu-west-1" collet
```

If you want to build Collet image from source code, clone this repository:

```shell
git clone git@github.com:velio-io/collet.git && cd collet
```

Then build the image with the following command:

```shell
# on Linux
docker build -t collet .

# on MacOS
docker build --platform=linux/amd64 -t collet .
```

### As a library

After adding Collet to your project dependencies, you can use it as follows:

```clojure
(ns my-namespace
  ;; require the collet namespace
  (:require [collet.core :as collet]))

;; define your pipeline
(def my-pipeline-spec
  {:name  :my-pipeline
   :tasks [...]})

;; precompile your pipeline
(def my-pipeline
  (collet/compile-pipeline my-pipeline-spec))

;; now you can run it as a regular Clojure function
;; also, you can provide a configuration map as an argument
;; pipeline will run in the separate thread and wouldn't block the calling thread 
(my-pipeline {:some-key "some-value"})

;; if you to wait for the pipeline to finish you can dereference returned future
@(my-pipeline {:some-key "some-value"})

;; another way to run the pipeline is to use the pipeline protocol
(collet/start my-pipeline {:some-key "some-value"})

;; pipeline is a stateful object, so you can stop, pause and resume it at any time
(collet/pause my-pipeline)
(collet/resume my-pipeline {:another-key "another-value"})

;; after stopping the pipeline you can't resume it
(collet/stop my-pipeline)

;; to get the current status of the pipeline or the pipeline error
(collet/pipe-status my-pipeline)
(collet/pipe-error my-pipeline)
```

## Configuration

As mentioned before, you can provide a pipeline configuration map,
which can be used to store sensitive data or any other configuration values.
Collet has a special reader for values in the configuration map EDN file.
For example, you can use `#env` tag to read environment variables.

Example:

```clojure
{:my-secret #env "SECRET_VALUE" ;; refers to SECRET_VALUE environment variable
 :port      #env ["PORT" Bool :or 8080]} ;; refers to PORT environment variable, casts it to Bool and sets default value to 8080
```

### Pipeline specification

A pipeline specification consists of the following keys:

- `:name` (required): A keyword representing the pipeline name.
- `:tasks` (required): A vector of task maps.
- `:deps` (optional): A map for loading runtime dependencies (from maven or clojars).

Here is an example of the pipeline specification:

```clojure
{:name  :my-pipeline
 :deps  {:coordinates '[[org.postgresql/postgresql "42.7.3"]]
         :requires    '[[collet.actions.jdbc-pg :as jdbc-pg]]}
 :tasks [{:name    :task-1
          :actions [...]}
         {:name    :task-2
          :actions [...]}]}
```

Each task map can contain the following keys:

- `:name` (required): A keyword that represents the name of the task
- `:actions` (required): A vector of maps, where each map represents an action
- `:iterator` (optional): A map that represents the iteration of actions. This map can contain the following keys:
    - `:data` - what part of the data should be treated as an task output
    - `:next` - responds to the question: should the iteration continue?
- `:setup` (optional): Similar to `:actions`, but runs only once before the first iteration
- `:inputs` (optional): A vector of keywords that represents the input data for the task. Keywords should refer to other
  tasks names
- `:retry` (optional): A map that represents the retry policy. This map can contain the following keys:
    - `:max-retries` - how many times the task should be retried
    - `:backoff-ms` - how long to wait before the next retry
- `:skip-on-error` (optional): a boolean value that represents whether the task should be skipped if an error occurs

### Actions library

Collet has a set of predefined actions, you can think of them as building blocks (functions) for your pipeline.
Action is defined by it's `type`. Type keyword refers to the specific function that will be executed.
Apart from the predefined actions, you can define your custom actions or refer to the Clojure core functions or external
libraries.

Basic structure of the action map is:

- `:name` (required): A keyword that represents the name of the action
- `:type` (required): A keyword that represents the type of the action
- `:params` (optional): Represents the parameters (function arguments) for the action
- `:selectors` (optional): You can bind any value in the pipeline state to the specific symbol and refer to it in the
  params
- `:fn` (optional): If you want to define a custom action you can provide a regular Clojure function
- `:return` (optional): You can drill down to the specific part of the action output data and use it as a result

### General options

Collet uses the mulog library for logging and tracing. When running as a Docker container, 
you can configure various publishers using environment variables:

- `CONSOLE_PUBLISHER`: Enable console logging (default: false)
- `CONSOLE_PUBLISHER_PRETTY`: Use pretty formatting for console logs (default: true)
- `FILE_PUBLISHER`: Enable file logging (default: false)
- `FILE_PUBLISHER_FILENAME`: Set the filename for file logging (default: `tmp/collet-*.log`)
- `ELASTICSEARCH_PUBLISHER`: Enable Elasticsearch publishing (default: false)
- `ELASTICSEARCH_PUBLISHER_URL`: Set Elasticsearch URL (default: http://localhost:9200)
- `ZIPKIN_PUBLISHER`: Enable Zipkin publishing (default: false)
- `ZIPKIN_PUBLISHER_URL`: Set Zipkin URL (default: http://localhost:9411)

JMX metrics are exposed on port `8080` by default. 
You can change this using the `JMX_PORT` environment variable.

## Development

- start the REPL as usual
- spin up containers for monitoring pipelines execution with command
  `docker-compose rm -f && docker-compose up` (includes elasticsearch, kibana, jaeger, prometheus, grafana)
- navigate to the `dev/src/dev.clj` namespace
- execute `(start-publishers)` to enable logging and tracing
- you can run all tests in the project with `(test)` command

Graphana is available at `http://localhost:3000` with default credentials `admin/grafana`
Jaeger is available at `http://localhost:16686`
Kibana is available at `http://localhost:9000`