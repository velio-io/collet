# COLLET

Collet is a powerful Clojure library designed to simplify the development and
execution of data processing pipelines (ETL or ELT).
It provides a declarative approach to defining task sequences and their dependencies,
making it easier to manage complex data workflows.

![Collet](collet.png)

- [Quick start](#quick-start)
- [Collet CLI tool](#collet-cli-tool)
- [Pipeline Configuration](#pipeline-configuration)
- [Pipeline specification](#pipeline-specification)
- [Tasks](#tasks)
- [Actions](#actions)
- [Using Collet with Docker](#using-collet-with-docker)
- [Collet as a library](#collet-as-a-library)

## Quick start

Pull the latest Collet image from DockerHub:

```shell
docker pull velioio/collet:latest
```

Create a pipeline spec file `demo-pipeline.edn` with the following content:

```clojure
{:name  :demo-pipeline
 :tasks [{:name    :print-hello-world
          :actions [{:name   :print
                     :type   :clj/println
                     :params ["Hello, world!"]}]}]}
```

Run the pipeline using the following command:

```shell
docker run \
  -v "$(pwd)"/demo-pipeline.edn:/config/demo-pipeline.edn \ 
  -e PIPELINE_SPEC="/config/demo-pipeline.edn" \
  velioio/collet
```

Now you should see the output `Hello, world!` in the console.
Looks a little bit overcomplicated for such a simple task, right?
Stay tuned, it will make more sense as we dive deeper into the Collet features.

### Collet CLI tool

Docker environment is good for running pipelines in production, but for development purposes you can use the Collet CLI
tool.
It provides a more interactive way to build and run pipelines, in the step by step manner.
Check the instructions [here](./collet-cli/README.md).

### Pipeline Configuration

To run a Collet pipeline, you need to provide two things: a pipeline specification and, optionally, a pipeline
configuration. Let's first discuss the pipeline configuration. Pipeline configuration can be used to store sensitive
data or dynamic values. The closest analogy is a template and the parameters that fill in the template placeholders. The
pipeline specification is the template, and the pipeline configuration contains the values to insert into the template.

It could be a regular Clojure map, e.g.

```clojure
{:db-user "my-user"
 :db-pass "my-pass"}
```

If you're using Collet Docker image or Collet app jar directly, you can provide pipeline configuration as EDN file.
In this case Collet has a special reader - `#env` for reading environment variables.

```clojure
{:post-id           #uuid "f47ac10b-58cc-4372-a567-0e02b2c3d479"
 ;; refers to POSTGRES_JDBC_URL environment variable
 :postgres-jdbc-url #env "POSTGRES_JDBC_URL"
 ;; refers to REPORT_PATH environment variable, casts it to string and sets default value to ./reports/comments_sentiment_analysis.csv
 :report-path       #env ["REPORT_PATH" Str :or "./reports/comments_sentiment_analysis.csv"]
 :gc-access-token   #env "GC_ACCESS_TOKEN"
 :s3-bucket         #env "S3_BUCKET"}
```

### Pipeline specification

Here's a complete example of a real world pipeline specification.
Don't worry if you don't understand everything at once, we will explain it step by step.

```clojure
{:name  :comments-sentiment-analysis
 ;; include postgres jdbc driver as a runtime dependency
 :deps  {:coordinates [[org.postgresql/postgresql "42.7.3"]
                       ;; also you can include a library with some prebuilt actions
                       [io.velio/collet-actions "0.2.5"]]
         ;; you'll need to require namespaces with actions we're going to use
         :requires    [[collet.actions.jdbc-pg]]} ;; postgres specific bindings
 ;; define the pipeline tasks
 ;; first task will fetch all comments for the specific post from the postgres database
 :tasks [{:name    :post-comments
          :actions [{:name      :comments
                     :type      :collet.actions.jdbc/query
                     ;; with selectors map you can pick values from the pipeline state
                     :selectors {post-id           [:config :post-id]
                                 postgres-jdbc-url [:config :postgres-jdbc-url]}
                     ;; then you can reference them in the params map
                     :params    {:connection {:jdbcUrl postgres-jdbc-url}
                                 :query      {:select [:id :text]
                                              :from   :comments
                                              :where  [:= :post-id post-id]}}}]}

         ;; second task will analyze the sentiment of each comment using Google Cloud NLP API
         {:name     :comments-sentiment
          :inputs   [:post-comments] ;; this task will depend on the previous one
          :retry    {:max-retries 3} ;; if some of the actions will throw an error we will retry that task again
          :actions  [{:name      :for-each-comment
                      :type      :mapper
                      :selectors {post-comments [:inputs :post-comments]}
                      :params    {:sequence post-comments}}

                     {:name      :sentiment-request
                      :type      :collet.actions.http/request
                      :selectors {comment-text    [:$mapper/item :text]
                                  gc-access-token [:config :gc-access-token]}
                      :params    {:url          "https://language.googleapis.com/v2/documents:analyzeSentiment"
                                  :method       :post
                                  :rate         5 ;; rate limit requests to 5 per second
                                  :oauth-token  gc-access-token
                                  :content-type :json ;; send as json
                                  :body         {:encodingType "UTF8"
                                                 :document     {:type    "PLAIN_TEXT"
                                                                :content comment-text}}
                                  :as           :json} ;; read response as json
                      :return    [:documentSentiment]}]
          ;; we will iterate over all comments one by one until we processed all of them
          :iterator {:next [:true? [:$mapper/has-next-item]]}
          ;; returned data will be a map with keys :comment-id, :magnitude and :score
          :return   [{:comment-id [:$mapper/item :id]
                      :magnitude  [:state :sentiment-request :magnitude]
                      :score      [:state :sentiment-request :score]}]}

         ;; third task will store the sentiment analysis report to the S3 bucket
         {:name    :report
          :inputs  [:comments-sentiment]
          :actions [{:name      :store-report
                     :type      :collet.actions.s3/sink
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

If you will run the pipeline listed above it will do the following things:

- Validate the pipeline spec on correctness
- Fetch required dependencies from Maven or Clojars
- Query the Postgres database and return data from `comments` table
- Map over each comment and make an HTTP request to Google API
- Store the data from all requests into S3 bucket as a CSV file

More of it you can run multiple pipelines in parallel for different posts or even for different databases.
You just need to run it with different environment variables.

Now you can see some of the benefits of using Collet, it will take care of:

- dependencies management
- error handling and retries
- rate limiting
- connection and interactions with external services, such as databases or APIs
- large datasets processing (data converted into Apache Arrow format on the fly)

Let's dive in into some pipeline implementation details.
The basic structure of pipeline spec can be represented as follows:

```clojure
{:name  :my-pipeline
 :deps  {...}
 :tasks [...]}
```

- `:name` (required): A keyword representing the pipeline name (something meaningful to distinct logs and results from
  other ones).
- `:tasks` (required): A vector of task.
- `:deps` (optional): A map with the coordinates of the pipeline dependencies (from maven or clojars). Check the deps
  format [here](./docs/deps.md).

One way to think of a pipeline is as a data structure that evolves over time.
When you initialize a pipeline, it has an internal state shaped like `{:state {} :config {}}`.
The `state` key is an empty map, and the `config` key is a map with the configuration values provided at startup.
When the pipeline is running, every task will contribute to the `state` map. The `state` map will contain data returned
from tasks. If a task is executed multiple times, the `state` map will contain all the iteration data as a sequence of
discrete results (can be changed with the `:state-format` option).
Tasks can also refer to each other's data using the `:inputs` key (fulfilled for each task individually).

Collet is designed to work with large datasets, so keeping all task data in memory is not a good idea.
By default, data returned from tasks will be offloaded to Arrow files. These files will be memory-mapped (not loaded
into the JVM heap) and represented as [TMD datasets](https://github.com/techascent/tech.ml.dataset?tab=readme-ov-file)
or dataset sequences when data is required for processing.

**Note!** Currently, not all types of data can be offloaded to Arrow. It works for collections with simple types like
strings, numbers, dates, etc., and for lists with simple values.
Nested data structures like maps aren't supported yet but will be in future releases. Collections that can't be
converted to Arrow will be kept in memory.

You can disable this feature by setting the `:use-arrow` key to false in the pipeline specification.

In a nutshell, a task is a logical unit of work that can be executed. Tasks can depend on other tasks, forming a
Directed Acyclic Graph.
A task can be executed multiple times based on the `:iterator` property. Every task iteration will contribute to the
resulting pipeline state.

![collet dataflow](collet-dataflow.png)

### Tasks

Each task map can contain the following keys:

- `:name` (required): A keyword that represents the name of the task
- `:actions` (required): A vector of maps, where each map represents an action
- `:setup` (optional): Similar to `:actions`, but runs only once before the main actions
- `:inputs` (optional): A vector of keywords that represents the input data for the task. Keywords should refer to other
  tasks names
- `:skip-on-error` (optional): a boolean value that represents whether the task should be skipped if an error occurs.
  Otherwise, the pipeline will stop on the first error.
- `:state-format` (optional): a keyword that represents how task data will be added to the pipeline state. Available
  options are `:latest` `:flatten`. In case of `:latest` value, pipeline state will contain only the last task iteration
  value. In case of `:flatten` value, pipeline state will contain all task iterations values as a flattened sequence.
- `:retry` (optional): A map that represents the retry policy. This map can contain the following keys:
    - `:max-retries` - how many times the task should be retried
    - `:backoff-ms` - a vector `[initial-delay-ms max-delay-ms multiplier]` to control the delay between each retry, the
      delay for nth retry will be `(min (* initial-delay-ms (expt 2 (- n 1))) max-delay-ms)`
- `:keep-state` (optional): a boolean value that represents whether the pipeline should keep the task state after the
  execution. Otherwise, the state will be cleaned up after the task execution if no other tasks referring to this data.
  Useful for debugging purposes.
- `:iterator` (optional): A map that represents the iteration of actions. If iterator is skipped, the actions will be
  executed only once. With that key you can iterate over the data and execute actions
  multiple times sequentially. This map can contain the following keys:
    - `:next` - responds to the question: should the iteration continue?
- `:parallel` (optional): a map that describes how to run your task multiple times (in parallel). `:iterator` and
  `:parallel` keys are mutually exclusive, you can pick only one of them. Inside the `:parallel` map you can specify the
  maximum number of parallel threads by using `:threads` key. Also, you have to provide a sequence of values, each of
  these values will be used to spin up a thread. That sequence can be either a `:range` with `:start`, `:end` and
  `:step` properties or you can provide a path to some collection in the pipeline state - `:items`. In the task itself
  you can refer to that value with the `:$parallel/item` keyword.
- `:return` (optional): specifies what part of the data should be treated as an task output. You can point it to the
  specific part of the task state that should be treated as an output.

The value of the `:return` key should be a "path vector" (think of it as a vector for `get-in` Clojure function).
It might look like this:

```clojure
;; Here we're drilling down in the task state
{:return [:state :action-name :nested-key :more-nested-key]}
```

This path vector supports some additional elements like "map syntax", `:$/cat`, `:$/cond` and `:$/op` functions.
See the details [here](./docs/select-syntax.md).

If `:return` key is omitted, data returned from the last executed task will be treated as a task output.

The `:next` key in the `:iterator` map is responsible for the continuation of the iteration.
Setting it to true will mean an infinite loop, false - means no iteration at all.
You can provide a "conditional vector" to check if iteration must go on based on the current state.
Also, you can provide a "path vector" to point to the specific part of the state (in the same way as for `:return`, but
special syntax is not supported here). If value under this path is `nil` the iteration will stop.

```clojure
;; here we're using a path vector
{:next [:state :action-name :nested-key]}
```

For more complex use cases you can provide a `condition vector`, which looks like this:

```clojure
;; conditional vector
{:next [:and
        [:< [:state :users-count] batch-size]
        [:not-nil? [:state :next-token]]]}
```

For more details on condition syntax see [here](./docs/condition-syntax.md).

Here is an example of running task in parallel:

```clojure
{:name     :parallel-task
 :actions  [{:name   :print-item
             :type   :clj/println
             :params [:$parallel/item]}]
 :parallel {:threads 2
            :items   [:state :some-collection]}}
```

Using the range property:

```clojure
{:name     :parallel-task
 :actions  [{:name   :print-item
             :type   :clj/println
             :params [:$parallel/item]}]
 :parallel {:threads 2
            :range   {:start 10 :end 100 :step 5}}}
```

One more feature you can use with Collet app is `#include` tag in the pipeline specification.
It allows you to split the pipeline into multiple files and include them in the main pipeline spec.
This way you can create reusable tasks or actions and inject them into different pipelines.
Include path could be a local file (absolute or relative to the main spec file path), HTTP URL or S3 key.

```clojure
;; ./tasks/common-task.edn
{:name    :task-2
 :actions [...]}

;; ./my-pipeline.edn
{:name  :my-pipeline
 :tasks [{:name    :task-1
          :actions [...]}

         ;; here the task-2 will be injected from the separate file
         #include "./tasks/common-task.edn"

         {:name    :task-3
          :actions [...]}]}
```

If you need to override some parameters in the included block, you can use a vector with two elements.
First element should be a path to the file and second is a map with the parameters you want to override.

```clojure
{:actions [{:name :first-action
            ...}

           #include ["path/to/my-action.edn"
                     {:params {:some-key "some-value"}}]

           {:name :third-action
            ...}]}
```

By default, you can't use regular expressions in EDN files.
If you need one, you can use `#rgx` tag for parsing regular expressions.
Notice that you have to double escape special characters.

```clojure
{:some-key #rgx "foo"}

;; double escaping
{:name   :find-pattern
 :type   :clj/re-find
 :params [#rgx "foo\\d+" "foo123"]}
```

### Actions

Collet has a set of predefined actions, you can think of them as building blocks (functions) for your pipeline tasks.
Action is defined by its `type`. Type keyword refers to the specific function that will be executed.
List of predefined actions: `:counter`, `:slicer`, `:mapper`, `:fold`, `:enrich`, `:switch`

Here's an example of the `:counter` action:

```clojure
{:name   :events-count
 :type   :counter
 :params {:start 0
          :end   150
          :step  10}}
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
- `:when` (optional): A "condition vector" that represents whether the action should be executed
- `:keep-state` (optional): A boolean value that represents whether the action should keep the state from the previous
  execution if it was skipped due to `:when` condition. false by default.
- `:fn` (optional): If you want to define a custom action you can provide a regular Clojure function
- `:params` (optional): Represents the parameters (function arguments) for the action
- `:selectors` (optional): You can bind any value in the pipeline state to the specific symbol and refer to it in the
  params
- `:return` (optional): You can drill down to the specific part of the action output data and use it as a result

The `:selectors` key is a map of symbols (yes, only symbols should be used) and paths to the specific part of the state.
After that you can refer to these symbols in the `:params` key. They will be replaced with the actual values during the
action execution.
`:params` key could be a map (if only one argument is expected) or vector of positional arguments. If omitted, action
will be called without arguments.
`:return` is a "path vector" (with special syntax supported)

Also, Collet provides a separate package for more complex actions - `[io.velio/collet-actions "0.2.5"]`
This library contains such actions as `:collet.actions.http/request`, `:collet.actions.http/oauth2`,
`:collet.actions.odata/request`, `:collet.actions.jdbc/query`, `:collet.actions.s3/sink`, `:collet.actions.file/sink`,
`:collet.actions.queue/enqueue`, `:collet.actions.jslt/apply`, `:collet.actions.llm/openai`

Check the [actions documentation](./docs/actions.md) for more details.

For complete pipeline example check
the [github-productivity-pipeline.edn](examples/github-developer-productivity/github-productivity-pipeline.edn)

### Using Collet with Docker

Pull the latest Collet image from DockerHub:

```shell
docker pull velioio/collet:latest
```

Or if you want to build Collet image from the source code, clone this repository:

```shell
git clone git@github.com:velio-io/collet.git && cd collet
```

Then build the image with the following command:

```shell
cd collet-app

docker build -t velioio/collet .
```

To run Collet, you need to provide a pipeline specification and optionally a pipeline config map.
These can be provided as environment variables in three ways:

1. As a raw Clojure map

```shell
docker run \
  -p 8080:8080 \
  -e PIPELINE_SPEC="{:name :my-pipeline ...}" \
  -e PIPELINE_CONFIG="{:my-secret #env SECRET_VALUE}" \
  velioio/collet
```

2. Local file (mount the volume with the pipeline spec)

```shell
docker run \
  -p 8080:8080 \
  -v ./test/collet:/app/data \
  -e PIPELINE_SPEC="/app/data/sample-pipeline.edn" \
  -e PIPELINE_CONFIG="/app/data/sample-config.edn" \
  velioio/collet
```

3. S3 file:

```shell
docker run \
  -p 8080:8080  \
  -e PIPELINE_SPEC="s3://test-user:test-pass@test-bucket/test-pipeline-spec.edn?region=eu-west-1" \
  -e PIPELINE_CONFIG="s3://test-user:test-pass@test-bucket/test-pipeline-config.edn?region=eu-west-1" \
  velioio/collet
```

### Options for Docker container

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

### Collet as a library

Collet core library heavily relies on latest Java features, such as virtual threads.
So you'll need JDK 21 or higher to run it.
Add the following dependency to your project:

For Leiningen:

```clojure
[io.velio/collet-core "0.2.5"]
```

For deps.edn:

```clojure
io.velio/collet-core {:mvn/version "0.2.5"}
```

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

## License

Copyright &copy; 2024 Velio

This project is licensed under the [Apache License 2.0][license].

[license]: https://choosealicense.com/licenses/apache-2.0e