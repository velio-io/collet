### Pipeline Configuration

To run a Collet pipeline, you need to provide two things: a pipeline specification and, optionally, a pipeline
configuration. Let's first discuss the pipeline configuration. Pipeline configuration can be used to store sensitive
data or dynamic values. The closest analogy is a template and the parameters that fill in the template placeholders.
The pipeline specification is the template, and the pipeline configuration contains the values to insert into the
template.

Pipeline configuration could be a regular Clojure map, e.g.

```clojure
{:db-user "my-user"
 :db-pass "my-pass"}
```

If you're using Collet Docker image or Collet app jar directly, you can provide pipeline configuration as EDN file.
In this case Collet has a special reader - `#env` for reading environment variables. So your configuration file could
look like

```clojure
{:post-id           #uuid "f47ac10b-58cc-4372-a567-0e02b2c3d479"
 ;; refers to POSTGRES_JDBC_URL environment variable
 :postgres-jdbc-url #env "POSTGRES_JDBC_URL"
 ;; refers to REPORT_PATH environment variable, casts it to string and sets default value to ./reports/comments_sentiment_analysis.csv
 :report-path       #env ["REPORT_PATH" Str :or "./reports/comments_sentiment_analysis.csv"]
 :gc-access-token   #env "GC_ACCESS_TOKEN"
 :s3-bucket         #env "S3_BUCKET"}
```

`#env` tag can be a string with the name of the environment variable, or a vector with such elements:
`[ENV_NAME CAST_TO_TYPE :or DEFAULT_VALUE]`.

Later in the pipeline specification you can refer to these values using `:selectors` key and path to the specific part
of the configuration map, starting with `:config` key. For example, `[:config :postgres-jdbc-url]`

### Pipeline specification

Here's a complete example of a real world pipeline specification.
Don't worry if you don't understand everything at once, we will explain it step by step.

```clojure
{:name  :comments-sentiment-analysis
 ;; include postgres jdbc driver as a runtime dependency
 :deps  {:coordinates [[org.postgresql/postgresql "42.7.3"]
                       ;; also you can include a library with some prebuilt actions
                       [io.velio/collet-actions "0.2.7"]]
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

If you run the pipeline listed above it will do the following things:

- Validate the pipeline spec on correctness
- Fetch required dependencies from Maven or Clojars
- Query the Postgres database and return data from `comments` table
- Map over each comment and make an HTTP request to Google API
- Store the data from all requests into S3 bucket as a CSV file

More of it you can run multiple pipelines in parallel for different posts or even for different databases.
You just need to run it with different environment variables.

Let's dive in into more pipeline specification details.
The basic structure of pipeline spec can be represented as follows:

```clojure
{:name  :my-pipeline
 :deps  {...}
 :tasks [...]}
```

- `:name` (required): A keyword representing the pipeline name (something meaningful to distinct logs and results from
  other ones).
- `:tasks` (required): A vector of task (will cover it later).
- `:deps` (optional): A map with the coordinates of the pipeline dependencies (from maven or clojars).
- `:use-arrow` (optional): A boolean value that represents whether the pipeline should use the arrow format for data
  serialization. By default, it is set to true.
- `:max-parallelism` (optional): A number that represents the maximum number of parallel tasks that can be executed at
  the same time. By default, it is set to 10.

### Tasks

In a nutshell, a task is a logical unit of work that can be executed. Tasks can depend on other tasks, forming a
Directed Acyclic Graph. A task can be executed multiple times based on the `:iterator` property or can be executed in
parallel (see `:parallel` property). Every task iteration will contribute to the resulting pipeline state.

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
  value. In case of `:flatten` value, pipeline state will contain all task iterations values as a flattened sequence. If
  not specified, will be returned as is.
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
- `:return` (optional): specifies what part of the data should be treated as a task output.

The value of the `:return` key should be a "path vector" or "Select DSL" (think of it as a vector for `get-in` Clojure
function). It might look like this:

```clojure
;; Here we're drilling down in the task state
{:return [:state :action-name :nested-key :more-nested-key]}
```

This path vector supports some additional elements like "map syntax", `:$/cat`, `:$/cond` and `:$/op` functions.

If `:return` key is omitted, data returned from the last executed task will be treated as a task output.

The `:next` key in the `:iterator` map is responsible for the continuation of the iteration.
Setting it to true will mean an infinite loop, false - means no iteration at all.
You can provide a "conditional vector" to check if iteration must go on based on the current state.
Also, you can provide a "path vector" to point to the specific part of the state (in the same way as for `:return`, but
special syntax is not supported here). If value under this path is `nil` the iteration will stop.

```clojure
;; here we're using a path vector
{:iterator {:next [:state :action-name :nested-key]}}
```

For more complex use cases you can provide a `condition vector` (or Condition DSL), which looks like this:

```clojure
;; conditional vector
{:iterator {:next [:and
                   [:< [:state :users-count] batch-size]
                   [:not-nil? [:state :next-token]]]}}
```

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
List of predefined actions: `:counter`, `:intervals`, `:stats`, `:slicer`, `:mapper`, `:fold`, `:enrich`, `:switch`

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
         (+ 1 2))}
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
- `:params` (optional): Represents the parameters (function arguments) for the action. Keep in mind that if you want to
  provide some values either from config or current state as a part of the parameters structure, you have to select
  these values with `:selectors` key first and then refer to them in the `:params` key.
- `:selectors` (optional): You can bind any value in the pipeline state to the specific symbol and refer to it in the
  params definition. This is useful for passing values from the pipeline state to the action parameters.
- `:return` (optional): You can drill down to the specific part of the action output data and use it as a result

The `:selectors` key is a map of symbols (yes, only symbols should be used) and paths to the specific part of the state.
After that you can refer to these symbols in the `:params` key. They will be replaced with the actual values during the
action execution.
`:params` key could be a map (if only one argument is expected) or vector of positional arguments. If omitted, action
will be called without arguments.
`:return` is a "path vector" (with special syntax supported)

Also, Collet provides a separate package for more complex actions - `[io.velio/collet-actions "0.2.7"]`
This library contains such actions as `:collet.actions.http/request`, `:collet.actions.http/oauth2`,
`:collet.actions.odata/request`, `:collet.actions.jdbc/query`, `:collet.actions.s3/sink`, `:collet.actions.file/sink`,
`:collet.actions.queue/enqueue`, `:collet.actions.jslt/apply`, `:collet.actions.llm/openai`