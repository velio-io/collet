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

### Select DSL

It's a common practice to deal with highly nested or complex data structures when we're working with third-party APIs
and unstructured data. To make things easier, Collet offers a small DSL. You can describe what you want to select from
the data structure in a declarative way.

In the basic form you can think of that as a "path vector", similar to what you'll use in the Clojure `get-in` function.
Elements in the "path vector" could be a keywords, integers or strings.

```clojure
{:return [:state :user :name]}
```

In example above, we're selecting the `:name` key from the `:user` key in the `:state` map.
In addition, select DSL supports some additional functions to make it more powerful:

- You can use a "map syntax" to select multiple keys at the same time. If element in the "path vector" is a map, it will
  be treated as a map of keys to select. Map keys will be included in the resulting map and keys can be a "path vectors"
  itself.

```clojure
;; let's say your state looks like this:
{:state {:user {:name      "John"
                :last-name "Doe"
                :phone     "123-456-7890"
                :age       25
                :address   {:street "123 Main St."
                            :city   "Springfield"}}}}

;; you can specify in your task iterator key:
{:return [:state :user {:user-name :name
                        :street    [:address :street]}]}

;; and the result will be:
{:user-name "John"
 :street    "123 Main St."}
```

- Another special syntax for selecting values is a `:$/cat` function. You can use it to iterate over a collection.
  Functions in select DSL are defined as a vector with specific key in the first position. Syntax for `:$/cat` is
  `[:$/cat path-inside-each-element]`.

```clojure
;; let's say your state looks like this:
{:state {:users [{:first-name "John" :last-name "Doe" :age 25}
                 {:first-name "Jane" :last-name "Doe" :age 30}
                 {:first-name "Alice" :last-name "Smith" :age 35}
                 {:first-name "Bob" :last-name "Smith" :age 40}]}}

;; using the :$/cat function. 
;; notice that :first-name is a key in each user map, but you can use more complex paths as well
{:return [:state :users [:$/cat :first-name]]}

;; will return:
["John" "Jane" "Alice" "Bob"]
```

- Next function is `:$/cond`. It allows you to filter the results based on a condition. The syntax is
  `[:$/cond condition]`. The condition is a vector with the first element being a keyword representing the operator and
  the rest of the elements being the arguments for the operator.

```clojure
;; let's say your state looks like this:
{:state {:users [{:first-name "John" :last-name "Doe" :age 15}
                 {:first-name "Jane" :last-name "Doe" :age 30}
                 {:first-name "Alice" :last-name "Smith" :age 17}
                 {:first-name "Bob" :last-name "Smith" :age 40}]}}

;; notice how you can combine :$/cat and :$/cond functions
{:return [:state :users [:$/cat :first-name [:$/cond [:> :age 18]]]]}

;; will return:
["Jane" "Bob"]
```

- One more function available is `:$/op`. It allows you to perform a specific operation on the selected data. For only
  two operations are supported: `:first` and `:last`. This list will be extended in the future.

```clojure
;; let's say your state looks like this:
{:state {:users [{:first-name "John" :last-name "Doe" :age 15}
                 {:first-name "Jane" :last-name "Doe" :age 30}
                 {:first-name "Alice" :last-name "Smith" :age 17}
                 {:first-name "Bob" :last-name "Smith" :age 40}]}}

{:return [:state :users [:$/op :first] :first-name]}

;; will return:
"John"
```

Of course, you can combine all of these functions together to create more complex queries. For example, you can use
the following sample to get the last name of the last user who is older than 18.
The result will be `"Smith"`.

```clojure
;; let's say your state looks like this:
{:state {:users [{:first-name "John" :last-name "Doe" :age 15}
                 {:first-name "Jane" :last-name "Doe" :age 30}
                 {:first-name "Alice" :last-name "Smith" :age 17}
                 {:first-name "Bob" :last-name "Smith" :age 40}]}}

{:return [:state :users [:$/cat [:$/cond [:> :age 18]] :last-name] [:$/op :last]]}

;; will return:
;; "Smith"
```

### Adding custom dependencies

You can use any Clojure or Java library available on Maven or Clojars.
All you need to do is to provide the dependency map with the following keys:

- `:coordinates` - a vector of artifact coordinates, just like in Leiningen dependencies
- `:requires` - a vector of namespaces that should be required, just like in usual `:require` block in `ns` form
- `:imports` - a vector of fully qualified Java classes that should be imported

Here is an example of how to add a dependency on `org.clojure/data.csv`:

```clojure
{:name  :my-pipeline
 :deps  {:coordinates [[org.clojure/data.csv "0.2.0"]]
         :requires    [[clojure.data.csv :as csv]]
         :imports     [java.time.LocalDate java.io.File]}
 :tasks []}
```

After that you can use `csv` namespace or `LocalDate` class inside your pipeline actions.
These dependencies will be fetched at runtime just before the pipeline execution.

# Collet actions

- [Types of actions](#types-of-actions)
- [Built-in actions](#built-in-actions)
- [Actions to work with external datasource's](#actions-to-work-with-external-datasources)

## Types of actions

Actions are functions defined by the `:type` key.
Collet has three major types of actions: Clojure core functions, named external functions and inline (custom) functions.

You can use any Clojure core function as an action. Just add the `:clj` namespace before the function name.
Here's a basic example of greeting action:

```clojure
{:name   :greeting
 :type   :clj/format
 :params ["Hello, %s" "world"]}
```

`:params` key defines the arguments for the function. In this case, the `format` function will receive two arguments:
`"Hello, %s"` and `"world"`. The result will be `"Hello, world"`.

If you need a function from the different namespace (outside clojure/core) first make sure that namespace is
available in classpath ([see deps](./deps.md) sections), then you can use it in the custom actions.

```clojure
{:name  :parsing-pipeline
 :deps  {:coordinates [[org.clojure/data.xml "0.0.8"]]
         :requires    [[clojure.data.xml :as xml]]
         :imports     [java.io.StringReader]}
 :tasks [{:name    :xml-doc
          :actions [{:name   :parse-xml-string
                     :type   :custom
                     :params ["<root><child>data</child></root>"]
                     :fn     '(fn [xml-str]
                                (xml/parse (java.io.StringReader. xml-str)))}]}]}
```

If you have a common action, used in different places multiple times you can create a separate file for that action.
Then you can use this action by providing the action type that match with a relative path to that file.

Let's say you have a file `my-folder/with-actions/my-action.edn`

```clojure
{:name   :my-action
 :type   :clj/format
 :params ["Hello, %s" "world"]}
```

Then you can use this action in your pipeline like this:

```clojure
{:name  :pipeline
 :tasks [{:name    :task-1
          :actions [{:name   :external-file-action
                     :type   :my-folder.with-actions/my-action.edn
                     :params ["Hello, %s" "user"]}]}]}
```

The `:params` key in the pipeline file will take precedence over the `:params` key in the external file.

Finally, you can define your own functions and use them as actions.
You can use `:custom` key as a action type in this case.
When pipeline spec is read from EDN file custom functions will be evaluated and executed in the separate environment (
via [SCI](https://github.com/babashka/sci)) so they wouldn't have access to the global scope.

```clojure
{:name   :greeting
 :type   :custom
 :params ["world"]
 :fn     (fn [name]
           (str "Hello, " name))}
```

## Built-in actions

Collet has a set of prebuilt actions that you can use to solve common tasks.

### Counter

Increments the counter on every iteration. Accepts `:start`, `:end` and `:step` keys. Useful for
inferring parameters for pagination or limiting the number of iterations.

```clojure
{:name  :current-page
 :type  :counter
 :start 0
 :step  10}
```

Action above will increment the counter by 10 on every iteration starting from 0.

### Mapper

The most common task in the ETL world is iteration over sequences and transforming (or using its
data to fetch another peace of information) each element. The `:mapper` action requires a `:sequence` parameter which
should be some kind of sequence (list, vector, set, dataset, dataset-seq etc.). You can also provide a `:cat?` boolean
key to flatten items if your sequence has nested sequences. `:mapper` action will hold a pointer to currently mapped
item and a boolean value representing if there's more items left in the sequence. Those values can be accessed by
using `:$mapper/item` and `:$mapper/has-next-item` keywords in the surrounding actions (in the same task).

```clojure
{:actions  [{:name   :city
             :type   :mapper
             :params {:sequence ["London" "Paris" "Berlin"]}}
            {:name      :city-weather-request
             :type      :clj-http/get
             :selectors {city [:$mapper/item]}
             :params    ["https://api.weather.com" {:query-params {:city city}}]}]
 :iterator {:next [:true? :$mapper/has-next-item]}}
``` 

### Fold

Can be used to collect a set of discrete values into a single sequence. You must provide an `:item` key
with a value you want to collect. A simple example could look like this:

```clojure
{:actions  [{:name   :city
             :type   :mapper
             :params {:sequence ["London" "Paris" "Berlin"]}}
            {:name      :city-weather-request
             :type      :clj-http/get
             :selectors {city [:$mapper/item]}
             :params    ["https://api.weather.com" {:query-params {:city city}}]}
            {:name   :weather-by-city
             :type   :fold
             :params {:item [:state :city-weather-request :response :body]}}]
 :iterator {:next [:true? :$mapper/has-next-item]}}
```

In this case `:item` key refers to the value under the `:body` key in the response of the `:city-weather-request`
action.
Also, `:fold` action allows you to provide some additional keys: `:into`, `:op`, `:in` and `:with`. Using these
parameters you can modify the way the items are collected. For example, in the example below, you can merge the weather
response with mapped item under the `:city-name` key before collecting it.
With `:into` parameter you can provide an initial value for the collection.
If item is a sequence of values you can use `:op` parameter with value `:concat` to concatenate them.

```clojure
{:actions  [{:name   :city
             :type   :mapper
             :params {:sequence ["London" "Paris" "Berlin"]}}
            {:name      :city-weather-request
             :type      :clj-http/get
             :selectors {city [:$mapper/item]}
             :params    ["https://api.weather.com" {:query-params {:city city}}]}
            {:name   :weather-by-city
             :type   :fold
             :params {:item [:state :city-weather-request :response :body]
                      :in   [:city-name]
                      :with [:$mapper/item]}}]
 :iterator {:next [:true? :$mapper/has-next-item]}}
```

The `:in` parameter supports condition vectors for finding elements in collections:
`[:= :id 2]` - Match elements where `:id` equals 2
`[:contains :tags "premium"]` - Match elements where `:tags` contains "premium"

The condition syntax supports all condition functions from collet.conditions:
`:=`, `:not=`, `:<`, `:>`, :`<=`, :`>=`, `:contains`, `:regex`, `:nil?`, `:not-nil?`, `:empty?`, `:not-empty?`

```clojure
;; state
[{:id          1,
  :departments [{:dept-id 101, :name "HR",
                 :teams   [{:team-id 1001, :name "Recruiting"}
                           {:team-id 1002, :name "Training"}]}
                {:dept-id 102, :name "Engineering",
                 :teams   [{:team-id 2001, :name "Frontend"}
                           {:team-id 2002, :name "Backend"}]}]}
 {:id          2,
  :departments [{:dept-id 201, :name "Sales",
                 :teams   [{:team-id 3001, :name "Direct Sales"}
                           {:team-id 3002, :name "Channel Sales"}]}]}]

{:item [:state :departments]
 :in   [[:= :id 1]
        :departments [:= :name "Engineering"]
        :teams [:= :name "Backend"]
        :status]
 :with "Active"}

;;result
[{:id          1,
  :departments [{:dept-id 101, :name "HR",
                 :teams   [{:team-id 1001, :name "Recruiting"}
                           {:team-id 1002, :name "Training"}]}
                {:dept-id 102, :name "Engineering",
                 :teams   [{:team-id 2001, :name "Frontend"}
                           {:team-id 2002, :name "Backend", :status "Active"}]}]}
 {:id          2,
  :departments [{:dept-id 201, :name "Sales",
                 :teams   [{:team-id 3001, :name "Direct Sales"}
                           {:team-id 3002, :name "Channel Sales"}]}]}]
```

### Enrich

Both previous actions leads us to the next one - `:enrich`. It basically works as a combination of `:mapper` and
`:fold` actions. It allows you to iterate over a sequence, perform some action on each item and then collect the
results into a single sequence. Previous example can be rewritten using `:enrich` action:

```clojure
{:actions  [{:name      :weather-by-city
             :type      :enrich
             :target    [:state :cities-list]
             :action    :clj-http/get
             :selectors {city [:$enrich/item]}
             :params    ["https://api.weather.com" {:query-params {:city city}}]
             :return    [:response :body]
             :fold-in   [:weather]}]
 :iterator {:next [:true? :$enrich/has-next-item]}}
```

Notice that `:enrich` action has its own `:$enrich/item` and `:$enrich/has-next-item` keys.
`:target` key should point to some sequence available in the pipeline state to iterate on top of it.

### Slicer

This action designed to modify (reshape) a collections of data. It uses a tech.ml.dataset library under the
hood. You can think of it as a dataframes. You must provide a `:sequence` key which should point to the collection and
`:slicer` action will create a dataset from it as a result. Additionally, you can define a set of transformation on
the resulting dataset. Available transformations are: `:flatten` `:group` `:join` `:fold` `:filter` `:order` `:select`
`:map`. If you need to format some columns while creating a dataset you can provide a `:parse` key with a map of
column names and their types (e.g. `{:column-name-1 :instant :column-name-2 int32}`).

```clojure
;; let's say you have a dataset like this:
{:users [{:name      "John"
          :age       25
          :addresses [{:city   "London"
                       :street "Baker Street"}
                      {:city   "Paris"
                       :street "Champs Elysees"}]}
         {:name      "Alice"
          :age       30
          :addresses [{:city   "Berlin"
                       :street "Alexanderplatz"}]}]}

;; after applying the following slicer action:
{:actions [{:name      :users-by-city
            :type      :slicer
            :selectors {users [:state :users]}
            :params    {:sequence users
                        :apply    [[:flatten {:by {:city-name [:addresses [:$/cat :city]]}}]]}}]}

;; you will get the following dataset (sequence flattened by the city name):
[{:name      "John"
  :age       25
  :addresses [{:city   "London"
               :street "Baker Street"}
              {:city   "Paris"
               :street "Champs Elysees"}]
  :city-name "London"}
 {:name      "John"
  :age       25
  :addresses [{:city   "London"
               :street "Baker Street"}
              {:city   "Paris"
               :street "Champs Elysees"}]
  :city-name "Paris"}
 {:name      "Alice"
  :age       30
  :addresses [{:city   "Berlin"
               :street "Alexanderplatz"}]
  :city-name "Berlin"}]
```

Reverse operation is `:fold`.
You can provide a `:columns` map to include columns in the resulting dataset. Key should be a column name and value
should be a function keyword to apply on the column values during the folding process. Available functions are:
`:values` (get all values as a vector), `:distinct` (collect only distinct values), `:first-value` (take only the first
value), `:row-count` (count all values), `:count-distinct` (count only distinct values), `:mean` (calculate mean),
`:sum` (calculate sum)

With `:rollup` param set to true you can tell the slicer to take only a single value for `:distinct` operation if all
values are the same (so `[1 1 1]` will become just `1`).

```clojure
{:actions [{:name   :users
            :type   :slicer
            :params {:sequence [{:id 1 :name "John" :street "Main St."}
                                {:id 2 :name "Jane" :street "NorthG St."}
                                {:id 3 :name "James" :street "Elm St."}
                                {:id 4 :name "Jacob" :street "Elm St."}
                                {:id 5 :name "Jason" :street "Main St."}]
                     :apply    [[:fold {:by      :street
                                        :rollup  true
                                        :columns {:id   :distinct
                                                  :name :distinct}}]]}}]}

;; will result in:
[{:street "Main St." :id [1 5] :name ["John" "Jason"]}
 {:street "NorthG St." :id 2 :name "Jane"}
 {:street "Elm St." :id [3 4] :name ["James" "Jacob"]}]
```

Value in the `:columns` map can be a vector of function keyword and column name. In this case you can create a new
column with the result of the function applied to the values of the specified column.

```clojure
{:actions [{:name   :users
            :type   :slicer
            :params {:sequence [{:id 1 :name "John" :city "Springfield"}
                                {:id 2 :name "Jane" :city "Lakeside"}
                                {:id 3 :name "Jack" :city "Springfield"}
                                {:id 4 :name "Jill" :city "Lakeside"}
                                {:id 5 :name "Joe" :city "Lakeside"}]
                     :apply    [[:fold {:by      :city
                                        :columns {:city-rows-count [:row-count :id]}}]]}}]}

;; will result in:
[{:city "Springfield" :city-rows-count 2}
 {:city "Lakeside" :city-rows-count 3}]
```

You can group values with `:group` transformation. There's two options available: preserve a single dataset but add a
new column with grouped value.

```clojure
{:actions [{:name   :users
            :type   :slicer
            :params {:sequence [{:id 1 :name "John" :city "Springfield"}
                                {:id 3 :name "Jack" :city "Springfield"}
                                {:id 2 :name "Jane" :city "Lakeside"}
                                {:id 4 :name "Jill" :city "Lakeside"}
                                {:id 5 :name "Joe" :city "Lakeside"}
                                {:id 3 :name "Jack" :city "Springfield"}
                                {:id 5 :name "Joe" :city "Lakeside"}]
                     :apply    [[:group {:by :city}]]}}]}

;; will result in:
[{:id 1 :name "John" :city "Springfield" :_group_by_key "Springfield"}
 {:id 3 :name "Jack" :city "Springfield" :_group_by_key "Springfield"}
 {:id 3 :name "Jack" :city "Springfield" :_group_by_key "Springfield"}
 {:id 2 :name "Jane" :city "Lakeside" :_group_by_key "Lakeside"}
 {:id 4 :name "Jill" :city "Lakeside" :_group_by_key "Lakeside"}
 {:id 5 :name "Joe" :city "Lakeside" :_group_by_key "Lakeside"}
 {:id 5 :name "Joe" :city "Lakeside" :_group_by_key "Lakeside"}]
```

Another option is to split into multiple datasets

```clojure
{:actions [{:name   :users
            :type   :slicer
            :params {:sequence [{:id 1 :name "John" :city "Springfield"}
                                {:id 3 :name "Jack" :city "Springfield"}
                                {:id 2 :name "Jane" :city "Lakeside"}
                                {:id 4 :name "Jill" :city "Lakeside"}
                                {:id 5 :name "Joe" :city "Lakeside"}
                                {:id 3 :name "Jack" :city "Springfield"}
                                {:id 5 :name "Joe" :city "Lakeside"}]
                     :apply    [[:group {:by          :city
                                         :join-groups false}]]}}]}

;; will result in:
{"Springfield" [{:id 1 :name "John" :city "Springfield"}
                {:id 3 :name "Jack" :city "Springfield"}
                {:id 3 :name "Jack" :city "Springfield"}]
 "Lakeside"    [{:id 2 :name "Jane" :city "Lakeside"}
                {:id 4 :name "Jill" :city "Lakeside"}
                {:id 5 :name "Joe" :city "Lakeside"}
                {:id 5 :name "Joe" :city "Lakeside"}]}
```

Join multiple datasets together with `:join` transformation.

```clojure
{:actions [{:name   :users
            :type   :slicer
            :params {:sequence [{:id 1 :name "John"}
                                {:id 2 :name "Jane"}
                                {:id 3 :name "Jack"}]
                     :apply    [[:join {:with   [{:user {:id 1} :city "Springfield"}
                                                 {:user {:id 2} :city "Lakeside"}
                                                 {:user {:id 3} :city "Springfield"}]
                                        :source :id
                                        :target [:user :id]}]]}}]}

;; will result in:
[{:id 1, :name "John", :user {:id 1}, :city "Springfield"}
 {:id 2, :name "Jane", :user {:id 2}, :city "Lakeside"}
 {:id 3, :name "Jack", :user {:id 3}, :city "Springfield"}]
```

Use `:map` function to iterate on over every row in the dataset

```clojure
{:actions [{:name   :users
            :type   :slicer
            :params {:sequence [{:id 1 :name "John" :second-name "Doe"}
                                {:id 2 :name "Jane" :second-name "Lane"}
                                {:id 3 :name "Jack" :second-name "Black"}]
                     ;; this will add a new column to every row with a full name
                     :apply    [[:map {:fn (fn [{:keys [name second-name]}]
                                             {:full-name (str name " " second-name)})}]]}}]}
```

If need additional arguments for your mapping function you can provide them within `:args` key.

```clojure
{:actions [{:name   :users
            :type   :slicer
            :params {:sequence [{:id 1 :name "John" :second-name "Doe"}
                                {:id 2 :name "Jane" :second-name "Lane"}
                                {:id 3 :name "Jack" :second-name "Black"}]
                     ;; this will add a new column to every row with a full name
                     :apply    [[:map {:fn   (fn [{:keys [name second-name]} prefix]
                                               {:full-name (str prefix " " name " " second-name)})
                                       :args ["Mr."]}]]}}]}
```

Of course, you can combine multiple transformations together. Operations will be executed in the order they are defined.

```clojure
{:actions [{:name   :users
            :type   :slicer
            :params {:sequence [{:id 1 :name "John" :addresses [{:street "Main St." :city "Springfield"}
                                                                {:street "NorthG St." :city "Springfield"}]}
                                {:id 2 :name "Jane" :addresses [{:street "Elm St." :city "Springfield"}]}
                                {:id 3 :name "Joshua" :addresses [{:street "NorthG St." :city "Springfield"}]}]
                     :apply    [[:flatten {:by {:address [:addresses [:$/cat :street]]}}]
                                [:join {:with   [{:user {:id 1} :phone 1234567}
                                                 {:user {:id 2} :phone 7654321}
                                                 {:user {:id 3} :phone 4561237}]
                                        :source :id
                                        :target [:user :id]}]
                                [:group {:by :address}]]}}]}

;; will result in:
[{:id            1,
  :name          "John",
  :addresses     [{:street "Main St.", :city "Springfield"}
                  {:street "NorthG St.", :city "Springfield"}],
  :address       "Main St.",
  :user          {:id 1},
  :phone         1234567,
  :_group_by_key "Main St."}
 {:id            1,
  :name          "John",
  :addresses     [{:street "Main St.", :city "Springfield"}
                  {:street "NorthG St.", :city "Springfield"}],
  :address       "NorthG St.",
  :user          {:id 1},
  :phone         1234567,
  :_group_by_key "NorthG St."}
 {:id            3,
  :name          "Joshua",
  :addresses     [{:street "NorthG St.", :city "Springfield"}],
  :address       "NorthG St.",
  :user          {:id 3},
  :phone         4561237,
  :_group_by_key "NorthG St."}
 {:id            2,
  :name          "Jane",
  :addresses     [{:street "Elm St.", :city "Springfield"}],
  :address       "Elm St.",
  :user          {:id 2},
  :phone         7654321,
  :_group_by_key "Elm St."}]
```

### Switch

With `:switch` action you can create multiple branches which will be invoked if conditions met

```clojure
{:name :insert-or-update
 :type :switch
 :case [{:condition [:nil? [:state :user-record]]
         :actions   [{:name :insert-user}]}
        ;; default condition will be executed if none of the conditions above met
        {:condition :default
         :actions   [{:name :update-user}]}]}
```

### Stats

`stats` action calculates basic statistics for the input collection. You can provide a `:metrics` key with a map of
metrics you want to calculate. Available metrics are: `:sum` `:mean` `:median` `:min` `:max` `:quartiles`.

```clojure
;;data
[{:a 1 :b 2} {:a 3 :b 4} {:a 5 :b 6}]

;; action
{:name   :stats
 :type   :stats
 :params {:sequence data
          :metrics  {:sum-a       [:sum :a]
                     :min-b       [:min :b]
                     :max-a       [:max :a]
                     :mean-b      [:mean :b]
                     :median-a    [:median :a]
                     :quartiles-b [:quartiles :b]}}}

;; will result in:
{:sum-a       9.0,
 :min-b       2.0,
 :max-a       5.0,
 :mean-b      4.0,
 :median-a    3.0,
 :quartiles-b [2.0 2.0 4.0 6.0 6.0]}
```

### Intervals

The `:intervals` action generates time intervals or sequences of dates based on given parameters. It supports relative dates 
(like "one week ago" or "one week ahead"), specific date formats, and recurring patterns (like "every Monday" or "every 15th of the month").

Parameters:
- `:from` - Start date/time (can be a relative date like `[:week :ago]` or `[:month :ahead]`, a string date, or a java-time instant)
- `:to` - End date/time (can be a keyword like `:today`, `:yesterday`, `:now`, a string date, or a java-time instant)
- `:format` - Output format for date strings (`:iso`, `:iso-date`, `:timestamp`, `:rfc3339`, `:sql-timestamp`, `:epoch`, or a custom format string)
- `:interval` - Time interval unit to use when generating multiple intervals (`:days`, `:weeks`, `:months`, `:years`)
- `:count` - Number of intervals to generate, splitting the time between `:from` and `:to` into equal segments
- `:pattern` - Pattern for generating recurring dates
- `:return-as` - Format of returned values (`:strings`, `:objects`, `:instants`, `:dates`)

Simple date range examples:
```clojure
;; Past week
{:name   :past-week
 :type   :intervals
 :params {:from [:week :ago]
          :to   [:today]}}

;; Will result in:
{:from "2025-03-24", :to "2025-03-31"}

;; Upcoming week
{:name   :upcoming-week
 :type   :intervals
 :params {:from [:today]
          :to   [:week :ahead]}}

;; Will result in:
{:from "2025-03-31", :to "2025-04-07"}
```

Multiple intervals example:
```clojure
{:name   :weekly-intervals
 :type   :intervals
 :params {:from     "2025-01-01"
          :to       "2025-01-31"
          :interval :weeks
          :count    4}}

;; Will result in:
[{:from "2025-01-01", :to "2025-01-07"}
 {:from "2025-01-08", :to "2025-01-14"}
 {:from "2025-01-15", :to "2025-01-21"}
 {:from "2025-01-22", :to "2025-01-31"}]
```

Recurring pattern examples:
```clojure
;; Every Monday in January 2025
{:name   :mondays-in-january
 :type   :intervals
 :params {:from    "2025-01-01"
          :to      "2025-01-31"
          :pattern {:type  :recurring-day
                    :value :monday}}}

;; Will result in:
["2025-01-06" "2025-01-13" "2025-01-20" "2025-01-27"]

;; Every second Monday of each month in 2025
{:name   :second-mondays-of-2025
 :type   :intervals
 :params {:from      "2025-01-01"
          :to        "2025-12-31"
          :pattern   {:type  :recurring-week
                      :value [2 :monday]}
          :return-as :objects}}

;; Will result in a vector of date objects, one for each second Monday of each month
```

## Actions to work with external datasource's

Collet has a separate package with actions to work with external datasource's like third-party APIs, databases, etc.
You'll have to include that package as a dependency `[io.velio/collet-actions "0.2.7"]`
Available actions are:

### HTTP request

`:collet.actions.http/request` performs an arbitrary HTTP request.
The request map can contain the following keys:

- `:url` - the URL to request
- `:method` - the HTTP method to use (default - :get)
- `:body` - the request body
- `:keywordize` - keywordize the keys in the response (default - true)
- `:as` - the response format
- `:content-type` - the content type of the request
- `:accept` - the accept header of the request
- `:unexceptional-status` - a set of unexceptional statuses
- `:rate` - the rate limit for the request. How many requests per second are allowed.
- `:basic-auth` - a vector of username and password for basic authentication.

```clojure
{:type   :collet.actions.http/request
 :name   :events-request
 :params {:url          "https://musicbrainz.org/ws/2/event"
          :as           :json ;; parse response as json 
          :accept       :json ;; send as json (Content-Type: application/json)
          :rate         1 ;; repeat this query (in the next iteration) no more than once per second
          :query-params {:limit  10
                         :offset 0
                         :query  "type:Concert"}}
 :return [:body :events]}
```

### OAuth2 request

`:collet.actions.http/oauth2` performs an OAuth2 request, usually to get the auth token.
The request map can contain the following keys:

- `:url` - the URL to request
- `:method` - the HTTP method to use (default - :post)
- `:client-id` - the client ID
- `:client-secret` - the client secret
- `:scope` - the requested scope
- `:grant-type` - the grant type (e.g. "client_credentials")
- `:auth-data` - additional data to include in the request
- `:as` - the response format
- `:keywordize` - keywordize the keys in the response (default - true)
- `:headers` - additional headers to include in the request
- `:basic-auth` - a vector of username and password for basic authentication

```clojure
{:type   :collet.actions.http/oauth2
 :name   :user-token
 :params {:client-id     "XXX"
          :client-secret "XXX"
          :grant-type    "client_credentials"}
 :return [:body :token]}
```

### OData request

`:collet.actions.odata/request` Makes an OData request (HTTP request in OData format)
Accepts all HTTP options and the following OData specific options:

- `:service-url` - the URL of the OData service
- `:segment` - the OData segment (entity) to request
- `:filter` - filter expression
- `:select` - specify which fields to include in the response
- `:expand` - indicates the related entities and stream values that MUST be represented inline
- `:order` - specifies the order in which items are returned from the service
- `:top` - specifies a non-negative integer n that limits the number of items returned from a collection
- `:skip` - specifies a non-negative integer n that excludes the first n items of the queried collection from the result
- `:count ` - with a value of true specifies that the total count of items within a collection matching the request be
  returned along with the result
- `:follow-next-link` - if service supports a server side pagination you can set this parameter to true to automatically
  fetch all pages from the collection
- `:get-total-count` - return just a count of items instead the actuall collection
- `:as` - `:json` by default. Can be `:auto` `:text` `:stream` or `:byte-array`
- `:content-type` - `:json` by default.

```clojure
{:type   :collet.actions.odata/request
 :name   :people-request
 :params {:service-url "http://services.odata.org/V4/TripPinService/"
          :segment     [:People]
          :select      [:FirstName :LastName :AddressInfo]
          :expand      [[:Friends {:select [:UserName]}]]
          :order       [:FirstName]
          :top         10}
 :return [:body "value"]}
```

### JDBC query

`:collet.actions.jdbc/query` performs a JDBC query. Database drivers aren't included so you have to make sure that
driver for a specific database is available in the classpath.
The request map can contain the following keys:

- `:connection` - the JDBC connection properties map
- `:query` - the SQL query. Could be either a vector with string query as first element and dynamic parameters as a rest
- elements or a HoneySQL map format
- `:options` - HoneySQL format query options
- `:prefix-table?` - keys in the result set will be namespaced with the table name (default - true)
- `:preserve-types?` - should a resulting data contain values in the same format as in the database (default - false)
- `:fetch-size` - the number of rows to fetch from the database in a single batch (default - 4000)
- `:timeout` - the query timeout in seconds
- `:concurrency` - a keyword that specifies the concurrency level: `:read-only`, `:updatable`
- `:result-type` - a keyword that affects how the ResultSet can be traversed: `:forward-only`, `:scroll-insensitive`,
- `:scroll-sensitive`
- `:cursors` - a keyword that specifies whether cursors should be closed or held over a commit: `:close`, `:hold`

```clojure
{:name  :products-bought-by-users
 :deps  {:coordinates [[io.velio/collet-actions "0.2.7"]
                       [com.mysql/mysql-connector-j "9.0.0"]]}
 :tasks [{:name    :query
          :actions [{:name   :query-action
                     :type   :collet.actions.jdbc/query
                     :params {:connection {:dbtype   "mysql"
                                           :host     "localhost"
                                           :port     3306
                                           :dbname   "test"
                                           :user     "test-user"
                                           :password "test-pass"}
                              :query      {:select   [:u/username
                                                      :p/product_name
                                                      [[:sum :oi/quantity] :total-quantity]
                                                      [[:sum [:* :oi/price :oi/quantity]] :total-amount]]
                                           :from     [[:Users :u]]
                                           :join     [[:Orders :o] [:= :u.user_id :o.user_id]
                                                      [:OrderItems :oi] [:= :o.order_id :oi.order_id]
                                                      [:Products :p] [:= :oi.product_id :p.product_id]]
                                           :group-by [:u.username :p/product_name]
                                           :order-by [:u.username :p.product_name]}
                              :options    {:dialect :mysql
                                           :quoted  false}}}]}]}
```

### S3 file sink

`:collet.actions.s3/sink` Write data to an S3 bucket.
The request map can contain the following:

- `:aws-creds` - the AWS credentials (region, key, secret)
- `:bucket` - the S3 bucket name
- `:format` - the format of the file (:json or :csv)
- `:file-name` - the name of the file
- `:input` - the data to write
- `:csv-header?` - if true, the CSV file will have a header row

```clojure
{:name  :s3-sink-test
 :deps  {:coordinates [[io.velio/collet-actions "0.2.7"]]}
 :tasks [{:name    :s3-test-task
          :actions [{:name   :s3-action
                     :type   :collet.actions.s3/sink
                     :params {:aws-creds   {:aws-region "eu-west-1"
                                            :aws-key    "test"
                                            :aws-secret "test"}
                              :input       [{:a 1 :b 2} {:a 3 :b 4} {:a 5 :b 6}]
                              :format      :csv
                              :bucket      "pipe-test-bucket"
                              :file-name   "pipe-test-file.csv"
                              :csv-header? true}}]}]}
```

### Local file sink

`:collet.actions.file/sink` Writes the input to a local file.
The input data should be a collection of maps or a collection of sequential items.
Options:

- `:input` - the data to write
- `:format` - the format of the file (:json or :csv)
- `:file-name` - the name of the file
- `:override?` - if true, the file will be overwritten if it exists
- `:csv-header?` - if true, the CSV file will have a header row

```clojure
{:name   :sink-action
 :type   :collet.actions.file/sink
 :params {:input       [{:a 1 :b 2} {:a 3 :b 4} {:a 5 :b 6}]
          :format      :csv
          :file-name   "./tmp/file-sink-test.csv"
          :csv-header? true}}
```

### Chronicle queue sink

`:collet.actions.queue/enqueue` Writes the input (message) into
the [Chronicle queue](https://github.com/OpenHFT/Chronicle-Queue).
Input can be a single message or a sequence of messages. Message should be a Clojure map.
Options:

- `:input` - the message to write
- `:queue-name` - the name of the queue
- `:queue-path` - path on the file system where the queue is stored
- `:roll-cycle` - How frequently the queue data file on disk is rolled over. Default is `:fast-daily`. Can be:
- `:twenty-minutely`, `:six-hourly`, `:four-hourly`, `:fast-daily`, `:ten-minutely`, `:weekly`, `:five-minutely`,
- `:two-hourly`, `:half-hourly`, `:fast-hourly`

```clojure
{:name  :queue-sink-test
 :deps  {:coordinates [[io.velio/collet-actions "0.2.7"]]}
 :tasks [{:name    :write-messages
          :actions [{:name   :queue-action
                     :type   :collet.actions.queue/enqueue
                     :params {:input      {:a 1 :b 2}
                              :queue-name :pipeline-queue-test}}]}]}
```

### JSLT transformation

`:collet.actions.jslt/apply` Apply a JSLT transformation to the input data.
Options:

- `:input` - the data to transform
- `:template` - the JSLT transformation
- `:as` - the output format. One of :string or :clj (default).

```clojure
{:name   :jslt-action
 :type   :collet.actions.jslt/apply
 :params {:input    {:a 1 :b 2}
          :template "{
                      \"a\": .a,
                      \"b\": .b,
                      \"sum\": .a + .b
                    }"}}
```

### OpenAI request

`:collet.actions.llm/openai` Perform a request to the OpenAI API.

Options:

- `:question` - the prompt to use. Can include placeholders for the variables
- `:vars` - a map of variables to use in the prompt
- `:model` - the model to use
- `:images` - a map of images to use in the prompt. Image can be a path to local file or a URL
- `:api-key` - the OpenAI API key
- `:api-endpoint` - the OpenAI API endpoint
- `:organization` - the OpenAI organization
- `:max-tokens` - the maximum number of tokens to generate
- `:temperature` - the sampling temperature
- `:top-p` - the nucleus sampling parameter
- `:response-format` - the format of the response. A map of `:name` and `:schema` keys. Schema should be a malli spec.
- `:tools` - a vector of tools to use in the prompt. Each tool should have a map with the following keys: `:name`
  function name,  `:func` ref to the actual function, `:desc` description,  `:args` vector of arguments
- `:as` - if tools option is used, you can provide a `:as` key with a value `:values` to get the result of the tools
  usage as json objects

```clojure
{:name   :openai-action
 :type   :collet.actions.llm/openai
 :params {:question        "What is the capital of {{country}}?"
          :vars            {:country "France"}
          :model           "text-davinci-003"
          :api-key         "XXX"
          :max-tokens      100
          :temperature     0.5
          :top-p           0.9
          :response-format {:name   "city_info"
                            :schema [:map
                                     [:name :string]
                                     [:country :string]
                                     [:population :int]
                                     [:weather :string]]}
          :tools           [{:name "city_weather"
                             :func (fn [city]
                                     (str "The weather in " city " is sunny"))
                             :desc "Get the weather in the city"
                             :args [{:name     "city"
                                     :type     "string"
                                     :required true
                                     :desc     "Name of the city"}]}]}
 :return [:openai-response :choices]}
```

### Condition DSL

You can define conditions using vectors of shape: `[:function-name :value-path :arguments]`.

Where `:function-name` is one of the following: `:and`, `:or`, `:pos?`, `:neg?`, `:zero?`, `:>`,
`:>=`, `:<`, `:<=`, `:=`, `:always-true`, `:true?`, `:false?`, `:contains`, `:absent`, `:regex`, `:nil?`, `:not-nil?`,
`:not=`, `:empty?`, `:not-empty?`.
These names refer to their analogous functions in the `clojure.core` namespace.

`:value-path` it's a vector to some value inside the state data. Think of it as a Clojure `get-in` function path vector.

`:arguments` are optional and should be concrete values.

Here's some examples:

```clojure
;; Check if the user's age is more than 18 and less or equal 65
[:and
 [:> [:user :age] 18]
 [:<= [:user :age] 65]]

;; Check if the user's name is nil or empty string
[:or [:nil? [:user :name]]
 [:empty? [:user :name]]]

;; Check if the user's role is admin
[:contains [:user :roles] "admin"]

;; Check if the user's email is a gmail account
[:regex [:user :email] #"@gmail.com$"]
```