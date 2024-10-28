# COLLET

Collet is a powerful Clojure library designed to simplify the creation and
execution of data processing pipelines (ETL or ELT).
It provides a declarative approach to defining task sequences and their dependencies,
making it easier to manage complex data workflows.

![Collet](collet.png)

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

As mentioned before, when executing a Collet pipeline you can provide a configuration map,
which can be used to store sensitive data or any other configuration values.

It could be a regular Clojure map, e.g.

```clojure
{:db-user "my-user"
 :db-pass "my-pass"}
```

Another way to provide this configuration is to store that map into EDN file.
In this case Collet has a special reader for values - `#env`.
You can use this tag to refer environment variables available during execution time.

Example:

```clojure
{:post-id           #uuid "f47ac10b-58cc-4372-a567-0e02b2c3d479"
 ;; refers to POSTGRES_JDBC_URL environment variable
 :postgres-jdbc-url #env "POSTGRES_JDBC_URL"
 ;; refers to REPORT_PATH environment variable, casts it to string and sets default value to ./reports/comments_sentiment_analysis.csv
 :report-path       #env ["REPORT_PATH" Str :or "./reports/comments_sentiment_analysis.csv"]
 :gc-access-token   #env "GC_ACCESS_TOKEN"
 :s3-bucket         #env "S3_BUCKET"} }
```

### Pipeline specification

Here's a complete example of a real world pipeline specification.
Don't worry if you don't understand everything at once, we will explain it step by step.

```clojure
{:name  :comments-sentiment-analysis
 ;; include postgres jdbc driver as a runtime dependency
 :deps  {:coordinates [[org.postgresql/postgresql "42.7.3"]]
         :requires    [[collet.actions.jdbc-pg]]}
 ;; define the pipeline tasks
 ;; first task will fetch all comments for the specific post from the postgres database
 :tasks [{:name    :post-comments
          :actions [{:name      :comments
                     :type      :jdbc
                     :selectors {post-id           [:config :post-id]
                                 postgres-jdbc-url [:config :postgres-jdbc-url]}
                     :params    {:connection {:jdbcUrl postgres-jdbc-url}
                                 :query      {:select [:id :text]
                                              :from   :comments
                                              :where  [:= :post-id post-id]}}}]}

         ;; second task will analyze the sentiment of each comment using Google Cloud NLP API
         {:name     :comments-sentiment
          :inputs   [:post-comments]
          :actions  [{:name      :comment
                      :type      :slicer
                      :selectors {post-comments [:inputs :post-comments]}
                      :params    {:sequence post-comments}}

                     {:name      :sentiment
                      :type      :http
                      :selectors {comment-text    [:state :comment :current :text]
                                  gc-access-token [:config :gc-access-token]}
                      :params    {:url          "https://language.googleapis.com/v2/documents:analyzeSentiment"
                                  :method       :post
                                  :oauth-token  gc-access-token
                                  :content-type :json
                                  :as           :json
                                  :body         {:encodingType "UTF8"
                                                 :document     {:type    "PLAIN_TEXT"
                                                                :content comment-text}}
                                  :return       [:documentSentiment]}}]
          ;; returned data will be a map with keys :comment-id, :magnitude and :score
          :iterator {:data [{:comment-id [:state :comment :current :id]
                             :magnitude  [:state :sentiment :documentSentiment :magnitude]
                             :score      [:state :sentiment :documentSentiment :score]}]
                     ;; we will iterate over all comments one by one
                     :next [:not-nil? [:state :comment :next]]}}

         ;; third task will store the sentiment analysis report to the S3 bucket
         {:name    :report
          :inputs  [:comments-sentiment]
          :actions [{:name      :store-report
                     :type      :s3
                     :selectors {sentiments  [:inputs :comments-sentiment]
                                 s3-bucket   [:config :s3-bucket]
                                 report-path [:config :report-path]}
                     :params    {:aws-creds   {:aws-region "eu-west-1"}
                                 :input       sentiments
                                 :format      :csv
                                 :bucket      s3-bucket
                                 :file-name   report-path
                                 :csv-header? true}}]}]}
```

The basic structure of that can be represented as follows:

```clojure
{:name  :my-pipeline
 :deps  {...}
 :tasks [...]}
```

- `:name` (required): A keyword representing the pipeline name.
- `:tasks` (required): A vector of task maps.
- `:deps` (optional): A map for loading runtime dependencies (from maven or clojars).

Task is a logical unit of work that can be executed. Tasks can depend on other tasks, forming a Directed Acyclic Graph.
Task can be executed multiple times if it requires iteration over the data. Every task iteration will contribute
to the resulting data.

Each task map can contain the following keys:

- `:name` (required): A keyword that represents the name of the task
- `:actions` (required): A vector of maps, where each map represents an action
- `:setup` (optional): Similar to `:actions`, but runs only once before the main actions
- `:inputs` (optional): A vector of keywords that represents the input data for the task. Keywords should refer to other
  tasks names
- `:skip-on-error` (optional): a boolean value that represents whether the task should be skipped if an error occurs.
  Otherwise, the pipeline will stop on the first error.
- `:keep-state` (optional): a boolean value that represents whether the task should keep the state after the execution.
  Otherwise, the state will be cleaned up after the task execution. Useful for debugging purposes.
- `:keep-latest` (optional): a boolean value that represents whether the task should keep only the latest state after
  the
  execution. Useful for tasks that iterate over the same data multiple times (on a mutable objects).
- `:retry` (optional): A map that represents the retry policy. This map can contain the following keys:
    - `:max-retries` - how many times the task should be retried
    - `:backoff-ms` - a vector `[initial-delay-ms max-delay-ms multiplier]` to control the delay between each retry, the
      delay for nth retry will be `(min (* initial-delay-ms (expt 2 (- n 1))) max-delay-ms)`
- `:iterator` (optional): A map that represents the iteration of actions. If iterator is skipped, the actions will be
  executed only once. This map can contain the following keys:
    - `:data` - what part of the data should be treated as an task output
    - `:next` - responds to the question: should the iteration continue?

The most interesting part here is the  `:iterator` key. It allows you to iterate over the data and execute the actions
multiple times. The `:data` key is a path to the specific part of the task state that should be treated as an output.
The value of the `:data` key should be a path vector (think of it as a vector for `get-in` Clojure function).
It might look like this:

```clojure
;; Here we're drilling down in the task state
{:data [:state :action-name :nested-key :more-nested-key]}
```

This path vector support some additional elements.

- You can use a map syntax to select multiple keys at the same time:

```clojure
{:data [:state :user {:name :first-name :age :user-age}]}
;; This will select this map {:first-name "John" :user-age 30} from the state
```

- `:$/cat` function to iterate over a collection

```clojure
{:data [:state :users [:$/cat :first-name]]}
;; This will select all first names from the users collection
```

- `:$/cond` function to select element that match the criteria

```clojure
{:data [:state :users [:$/cat :first-name [:cond [:> :age 18]]]]}
;; This will select all first names from the users collection where age is greater than 18
```

If `:data` key is omitted, data returned from the last executed task will be treated as an output.

The `:next` key is responsible for the continuation of the iteration.
Setting it to true will mean an infinite loop, false - means no iteration at all.
Also, you can provide a path vector as for `:data` (but special syntax not supported here) to point to the specific
part of the state. If value under this path is `nil` the iteration will stop.

```clojure
{:next [:state :action-name :nested-key]}
```

For more complex use cases you can provide a condition vector. Condition vector is a vector of shape
`[:function-name :value-path :arguments]`.
Available functions are:`:and`, `:or`, `:pos?`, `:neg?`, `:zero?`, `:>`,
`:>=`, `:<`, `:<=`, `:=`, `:always-true`, `:true?`, `:false?`, `:contains`, `:absent`, `:regex`, `:nil?`, `:not-nil?`,
`:not=`, `:empty?`, `:not-empty?`.
Value path it's a well known path vector. Arguments are optional and should be concrete values.

```clojure
{:next [:and
        [:< [:state :users-count] batch-size]
        [:not-nil? [:state :next-token]]]}
```

### Actions library

Collet has a set of predefined actions, you can think of them as building blocks (functions) for your pipeline tasks.
Action is defined by it's `type`. Type keyword refers to the specific function that will be executed.
List of predefined actions: `:http`, `:oauth2`, `:odata`, `:counter`, `:slicer`, `:jdbc`, `:file`, `:s3`, `:queue`,
`:fold`, `:enrich`

```clojure
{:name      :paginated-http-request
 :type      :http
 :selectors {page [:state :pager :next-page]}
 :params    {:url          "https://some-api.com/v1"
             :query-params {:page page}
             :as           :json
             :return       [:body]}}
```

Apart from the predefined actions, you can define your own custom actions or refer to the Clojure core functions or
external libraries.

```clojure
{:name :my-custom-action
 :type :custom
 ;; you have to provide an implementation of the action
 :fn   (fn []
         (+1 2))}
```

```clojure
{:name      :query-string
 :type      :clj/format ;; refers to the clojure.core/format function
 :selectors {city [:config :city]}
 ;; params will be passed with apply function
 :params    ["area:%s AND type:City" city]}
```

Basic structure of the action map is:

- `:name` (required): A keyword that represents the name of the action
- `:type` (required): A keyword that represents the type of the action
- `:fn` (optional): If you want to define a custom action you can provide a regular Clojure function
- `:params` (optional): Represents the parameters (function arguments) for the action
- `:selectors` (optional): You can bind any value in the pipeline state to the specific symbol and refer to it in the
  params
- `:return` (optional): You can drill down to the specific part of the action output data and use it as a result

The `:selectors` key is a map of symbols (yes, only symbols should be used) and paths to the specific part of the state.
After that you can refer to these symbols in the `:params` key. They will be replaced with the actual values during the
action execution.
`:params` key could be a map (if only one argument is expected) or vector of positional arguments. If ommited, action
will be called without arguments.
`:return` is a path vector (with special syntax supported)

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