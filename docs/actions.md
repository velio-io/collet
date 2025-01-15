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
available in classpath ([see deps](./deps.md) sections) and then use the fully qualified function name.

```clojure
{:name  :parsing-pipeline
 :deps  [[org.clojure/data.xml "0.0.8"]]
 :tasks [{:name    :xml-doc
          :actions [{:name   :parse-xml-string
                     :type   :clojure.data.xml/parse
                     :params ["<root><child>data</child></root>"]}]}]}
```

If you have a common action, used in different places multiple times you can create a separate file for that action.
Then you can use this action by providing the action type that match with a relative path to that file.

Let's say you have a such file `my-folder/with-actions/my-action.edn`

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

```clojure
{:actions [{:name   :users
            :type   :slicer
            :params {:sequence [{:id 1 :name "John" :street "Main St."}
                                {:id 2 :name "Jane" :street "NorthG St."}
                                {:id 3 :name "James" :street "Elm St."}
                                {:id 4 :name "Jacob" :street "Elm St."}
                                {:id 5 :name "Jason" :street "Main St."}]
                     :apply    [[:fold {:by     [:street]
                                        :rollup true}]]}}]}

;; will result in:
[{:street "Main St." :id [1 5] :name ["John" "Jason"]}
 {:street "NorthG St." :id 2 :name "Jane"}
 {:street "Elm St." :id [3 4] :name ["James" "Jacob"]}]
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

Of course, you can combine multiple transformations together.

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

## Actions to work with external datasource's

Collet has a separate package with actions to work with external datasource's like third-party APIs, databases, etc.
You'll have to include that package as a dependency `[io.velio/collet-actions "0.1.0"]`
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
          :as           :json ;; send as json
          :accept       :json ;; parse response as json
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
{:type          :collet.actions.http/oauth2
 :name          :user-token
 :client-id     "XXX"
 :client-secret "XXX"
 :grant-type    "client_credentials"
 :return        [:body :token]}
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
 :deps  {:coordinates [[io.velio/collet-actions "0.1.0"]
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
 :deps  {:coordinates [[io.velio/collet-actions "0.1.0"]]}
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
 :deps  {:coordinates [[io.velio/collet-actions "0.1.0"]]}
 :tasks [{:name    :write-messages
          :actions [{:name   :queue-action
                     :type   :collet.actions.queue/enqueue
                     :params {:input      {:a 1 :b 2}
                              :queue-name :pipeline-queue-test}}]}]}
```
