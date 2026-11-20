# Collet actions

- [Types of actions](#types-of-actions)
- [Built-in actions](#built-in-actions)
- [Actions to work with external datasource's](#actions-to-work-with-external-datasources)

Optional actions are published as separate artifacts so consumers only load the
dependency families they use. Existing consumers may continue to use the
`io.velio/collet-actions` compatibility aggregate; it exposes every legacy namespace
through transitive dependencies. Namespace names, configuration keys, and action
types are unchanged. The complete artifact-to-namespace mapping is in the
[module migration guide](./module-migration.md#module-graph).
Obtain pinned `dependencyEdn` from `collet_describe_action` and merge it into
`:deps` before running integration examples. Prefer catalogue built-in actions,
then `:clj/*` functions; reserve inline forms for domain logic. Action artifacts are versioned independently and may have different
versions. A change to an action also gives the `io.velio/collet-actions` aggregate
a dependent patch release, so consumers of the aggregate only need its own latest
version. Package releases use tags such as
`io.velio/collet-action-http@0.2.8`; see the
[release guide](./releasing.md) for the conventional-commit policy and workflow.

In the source workspace, action packages use `:local/root` for their Collet
dependencies. Kmono emits ordinary Maven dependencies in each published POM and
pins those internal coordinates to their exact independently resolved versions.

For source MCP, run `bb workbench:prepare` before startup and regenerate/restart
after version changes. `:coordinates` supplies Maven libraries, `:requires` loads
namespaces, and `:imports` exposes classes. `classpath-present` describes the
current process only; Workbench success does not establish deployment portability.

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
                     :fn     (fn [xml-str]
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
 :params {:start 0 :step 10}}
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
             :type      :collet.actions.http/request
             :selectors {city [:$mapper/item]}
             :params    {:url "https://api.weather.com" :as :json :query-params {:city city}}
             :return    [:body]}]
 :iterator {:next [:true? [:$mapper/has-next-item]]}}
``` 

### Fold

Can be used to collect a set of discrete values into a single sequence. You must provide an `:item` key
with a value you want to collect. A simple example could look like this:

```clojure
{:actions  [{:name   :city
             :type   :mapper
             :params {:sequence ["London" "Paris" "Berlin"]}}
            {:name      :city-weather-request
             :type      :collet.actions.http/request
             :selectors {city [:$mapper/item]}
             :params    {:url "https://api.weather.com" :as :json :query-params {:city city}}
             :return    [:body]}
            {:name   :weather-by-city
             :type   :fold
             :selectors {weather [:state :city-weather-request] city [:$mapper/item]}
             :params {:item weather}}]
 :iterator {:next [:true? [:$mapper/has-next-item]]}}
```

The `weather` selector binds the decoded response from `:city-weather-request`
and passes it as the fold item.
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
             :type      :collet.actions.http/request
             :selectors {city [:$mapper/item]}
             :params    {:url "https://api.weather.com" :as :json :query-params {:city city}}
             :return    [:body]}
            {:name   :weather-by-city
             :type   :fold
             :selectors {weather [:state :city-weather-request] city [:$mapper/item]}
             :params {:item weather
                      :in   [:city-name]
                      :with city}}]
 :iterator {:next [:true? [:$mapper/has-next-item]]}}
```

### Enrich

Both previous actions leads us to the next one - `:enrich`. It basically works as a combination of `:mapper` and
`:fold` actions. It allows you to iterate over a sequence, perform some action on each item and then collect the
results into a single sequence. Previous example can be rewritten using `:enrich` action:

```clojure
{:actions  [{:name      :weather-by-city
             :type      :enrich
             :target    [:state :cities-list]
             :action    :collet.actions.http/request
             :selectors {city [:$enrich/item]}
             :params    {:url "https://api.weather.com" :as :json :query-params {:city city}}
             :return    [:body]
             :fold-in   [:weather]}]
 :iterator {:next [:true? [:$enrich/has-next-item]]}}
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
                     :apply    [[:map {:with (fn [{:keys [name second-name]}]
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
                     :apply    [[:map {:with   (fn [{:keys [name second-name]} prefix]
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
 :params {:case [{:condition [:nil? [:state :user-record]]
                  :actions [{:name :insert-user :type :clj/identity :params ["insert"]}]}
                 {:condition :default
                  :actions [{:name :update-user :type :clj/identity :params ["update"]}]}]}}
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
 :params {:sequence [{:a 1 :b 2} {:a 3 :b 4} {:a 5 :b 6}]
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

## Actions to work with external datasource's

Collet has a separate package with actions to work with external datasource's like third-party APIs, databases, etc.
Use the exact individual action dependency recipe returned by MCP. The
`io.velio/collet-actions` compatibility aggregate is also available.
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

Select the username and password from configuration and pass
`:basic-auth [username password]`. Collet encodes the pair as a UTF-8 Basic
Authorization header; callers do not need to build or encode it themselves.

```clojure
{:type   :collet.actions.http/request
 :name   :events-request
 :params {:url          "https://musicbrainz.org/ws/2/event"
          :as           :json ;; parse response as json 
          :accept       :json ;; request JSON (Accept: application/json)
          :rate         1 ;; repeat this query (in the next iteration) no more than once per second
          :query-params {:limit  10
                         :offset 0
                         :query  "type:Concert"}}
 :return [:body :events]}
```

Credentials belong in runtime configuration:

```clojure
{:name :private-resource :type :collet.actions.http/request
 :selectors {url [:config :url] username [:config :username] password [:config :password]}
 :params {:url url :as :json :basic-auth [username password]}
 :return [:body]}
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
 :params {:url "https://example.test/token"
          :client-id "example-client"
          :client-secret "example-secret"
          :grant-type "client_credentials"}
 :return [:body :access_token]}
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

`:collet.actions.jdbc/query` performs a JDBC query. The PostgreSQL driver is included
because `collet.actions.jdbc-pg` imports its classes. For other databases, make sure
the corresponding driver is available on the classpath.
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
 :deps  {:coordinates [[com.mysql/mysql-connector-j "9.7.0"]]
         :requires [[collet.actions.jdbc]]}
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

`:collet.actions.s3/sink` uploads records or an existing binary file. Merge the
pinned dependency recipe for `io.velio/collet-action-s3` from MCP into `:deps`.

- `:aws-creds`: `:aws-region`, `:aws-key`, `:aws-secret` and optional nested
  `:endpoint-override` with `:protocol` (`:http` or `:https`), `:hostname` (string)
  and `:port` (integer).
- `:bucket` and `:file-name`: destination bucket and object key.
- `:input`: records, a local file path, or an input stream.
- `:format`: `:json` or `:csv` for records; omit it for an existing binary file.
- `:csv-header?`: include column headers for CSV records.

The sink returns `{:bucket "…" :key "…" :etag "…"}`. `:etag` is included when
the service returns an ETag, from `PutObject` or the completed multipart upload.
Keep it unchanged, including surrounding quotes. An ETag is opaque and is not
necessarily an MD5 checksum; use a separate content hash for integrity checks.

Record upload:

```clojure
{:name :upload-records :type :collet.actions.s3/sink
 :selectors {creds [:config :aws]}
 :params {:aws-creds creds :bucket "reports" :file-name "totals.csv"
          :input [{:currency "EUR" :total 20}] :format :csv :csv-header? true}}
```

Binary upload:

```clojure
{:name :upload-file :type :collet.actions.s3/sink
 :selectors {creds [:config :aws] path [:config :file-path]}
 :params {:aws-creds creds :bucket "reports" :file-name "chart.png" :input path}}
```

For Cloudflare R2, supply this configuration (credentials stay outside source):

```clojure
{:aws {:aws-region "auto" :aws-key "EXAMPLE_KEY" :aws-secret "EXAMPLE_SECRET"
       :endpoint-override {:protocol :https
                           :hostname "ACCOUNT_ID.r2.cloudflarestorage.com"
                           :port 443}}
 :file-path "/tmp/chart.png"}
```

With an explicit endpoint override, `auto` is translated to `us-east-1` in SDK
client options for endpoint lookup and signing. The supplied configuration stays
unchanged. Other regions keep their existing behavior. Cloudflare accepts this
alias; see [R2 S3 compatibility](https://developers.cloudflare.com/r2/api/s3/api/).
The endpoint is explicit; Collet does not infer account IDs or endpoints.

### Local file sink

`:collet.actions.file/sink` Writes the input to a local file.
The input data should be a collection of maps or a collection of sequential items.
Options:

- `:input` - the data to write
- `:format` - the format of the file (`:json`, `:csv`, `:text`, `:bytes`, or `:edn`)
- `:file-name` - the name of the file
- `:csv-header?` - if true, the CSV file will have a header row

```clojure
{:name   :sink-action
 :type   :collet.actions.file/sink
 :params {:input       [{:a 1 :b 2} {:a 3 :b 4} {:a 5 :b 6}]
          :format      :csv
          :file-name   "./tmp/file-sink-test.csv"
          :csv-header? true}}
```

The sink also accepts an existing local path, an input stream (closed after the
copy), or an HTTP URL with optional `:request-options`. Explicit `:format :text`
writes a literal string; `:bytes` writes a byte array, and `:edn` writes an EDN
value. `:encoding` defaults to `"UTF-8"`. Existing destination files are replaced.
The receipt remains `{:file-name ... :path ...}`.

### File listing and reading

Both `:collet.actions.file/list` and `:collet.actions.s3/list` return a TMD dataset
of metadata, without reading file content. Local listing takes `:path` and an
optional `:recursive?` (default false); it excludes symbolic links. S3 listing
takes `:aws-creds`, `:bucket`, and optionally `:prefix`.

Both support `:prefix`, `:glob` (relative path or object key), `:extensions`
(case-insensitive, with or without a leading dot), inclusive `:min-size` and
`:max-size` in bytes, and exclusive `:modified-after` / `:modified-before` ISO
timestamps. `:limit` caps matching rows after filtering. S3 follows all pages,
using `:page-size` from 1 to 1000 (default 1000), and does not make per-object
metadata requests. Local listing accepts `:page-size` for compatibility; traversal
has no storage pages. Local rows are sorted by path; S3 retains storage order.
The complete matching metadata dataset is materialized in memory.

Local columns are `:file-name`, `:path`, `:relative-path`, `:size-bytes`, and
`:modified-at`. S3 columns are `:file-name`, `:path` (an S3 URI), `:bucket`, `:key`,
`:size-bytes`, `:modified-at`, and `:etag`. Empty results retain these columns.
ETags are opaque values, including quotes, rather than content checksums.

```clojure
{:name :files :type :collet.actions.file/list
 :params {:path "./inbox" :recursive? true :extensions ["csv"] :limit 100}}

{:name :objects :type :collet.actions.s3/list
 :selectors {creds [:config :aws]}
 :params {:aws-creds creds :bucket "reports" :prefix "daily/" :page-size 100}}
```

`:collet.actions.file/read` takes `:path`; `:collet.actions.s3/read` takes
`:aws-creds`, `:bucket`, and `:key`. Their content options are shared:

| `:as` | Result and options |
| --- | --- |
| `:memory` (default) | A byte array by default; `:format :text`, `:json`, or `:edn` returns decoded content. |
| `:dataset` | A TMD dataset. Defaults to CSV; `:format :json` or `:edn` requires a sequence of records. |
| `:file` | Streams to required `:destination`, returning `{:file-name ... :path ...}`. Existing files require `:overwrite? true`. |
| `:stream` | An open `InputStream` owned by the caller. Closing an S3 stream also releases its client. |

Text uses `:encoding "UTF-8"` by default. JSON and dataset keys are keywordized
unless `:keywordize false`; CSV defaults to `:csv-header? true`. `:max-bytes`
limits materialized reads and local copies as bytes arrive. Failed local copies
clean their staging file and leave an existing destination intact. Materialized
reads load the content into memory; use file or stream mode for large objects.

```clojure
{:name :rows :type :collet.actions.file/read
 :params {:path "./inbox/orders.csv" :as :dataset :max-bytes 10485760}}

{:name :download :type :collet.actions.s3/read
 :selectors {creds [:config :aws]}
 :params {:aws-creds creds :bucket "reports" :key "chart.png"
          :as :file :destination "./chart.png"}}
```

Consume raw streams within the same task and close them, including on errors.
An open stream is not a durable task output. A following file sink consumes and
closes it; when consuming it in custom code, use `with-open`. Inspection does not
consume a stream for you.

### Local file management and archives

Every action below uses the `collet.actions.file` namespace. Paths are local to
the Collet server. New copy, move, and archive destinations reject overwrites
unless `:overwrite? true` is provided.

| Action | Parameters and result |
| --- | --- |
| `stat` | `:path`; returns file receipt plus `:size-bytes`, ISO `:modified-at`, and `:type` (`:file`, `:directory`, `:symlink`). Missing paths fail. |
| `exists?` | `:path`; boolean, checking the path itself, including dangling symlinks. |
| `copy` | `:path`, `:destination`; directories require `:recursive? true`. Symlinks are rejected. Returns destination receipt. |
| `move` | `:path`, `:destination`; directories require `:recursive? true`. Returns destination receipt. |
| `delete` | `:path`; returns whether removed. Nonempty directories require `:recursive? true`; links are removed without following them. |
| `mkdir` | `:path`; creates parents and returns a directory receipt. Existing directories succeed. |
| `temp-file` | Optional `:folder`, `:prefix`, `:suffix`; creates a unique file and returns its receipt. |
| `temp-dir` | Optional `:folder`, `:prefix`; creates a unique directory and returns its receipt. |
| `checksum` | `:path`, optional Java digest `:algorithm` (default `"SHA-256"`); returns `:path`, `:algorithm`, and hexadecimal `:checksum`. |
| `zip` | `:path` (file or directory), `:destination`; uses relative entries, rejecting symlinks and destinations inside the source. |
| `unzip` | `:path`, `:destination`; rejects absolute paths, traversal, and symlink destinations. Defaults to `:max-entries 10000`, `:max-bytes 1073741824` total expanded bytes. |
| `gzip` | `:path`, `:destination`; compresses one file. |
| `gunzip` | `:path`, `:destination`; defaults to `:max-bytes 1073741824` expanded bytes. |

Archive actions return destination receipts. A failed ZIP extraction can leave
earlier completed entries; each individual output is staged before replacement.
Remove temporary files and directories explicitly when finished.

### S3 and R2 object management

These `collet.actions.s3` actions take `:aws-creds`, `:bucket`, and `:key`.
They support the same explicit R2 endpoint configuration as the existing sink.

| Action | Behavior |
| --- | --- |
| `stat` | `HeadObject` metadata, including size, modification time, opaque ETag, content type, and custom metadata when present. Missing objects fail. |
| `exists?` | Returns false only for not-found; permission and transport errors propagate. |
| `copy` | Required `:destination-key`, optional `:destination-bucket` (defaults to source), and `:overwrite?` (false). Server-side copy up to 5 GB, conditioned on the observed source ETag. |
| `move` | Copy followed by source deletion. On deletion failure, exception data contains `:copied`, `:source`, and `:source-retained true`. |
| `delete` | Deletes the exact key, never a prefix. Returns `{:bucket ... :key ...}`; missing keys are idempotent. |

Copy/move return `{:bucket ... :key ... :etag ...}` when an ETag is available.
Destination existence checks are best effort under concurrent writes, and moves
are not atomic. S3 sink serialization supports the same new text/bytes/EDN formats
as the local sink. Uploads use unique temporary staging paths and clean up on
success or failure, preserving source files even when their path equals the key.

For a remote image workflow, see the [Dash image analyzer](./dash-image-analysis.md).

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
{:name :queue-action :type :collet.actions.queue/enqueue
 :params {:input {:a 1 :b 2} :queue-name :pipeline-queue-test}}
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
 :params {:input    "{\"a\":1,\"b\":2}"
          :template "{
                      \"a\": .a,
                      \"b\": .b,
                      \"sum\": .a + .b
                    }"}}
```

### Generic LLM request

`:collet.actions.llm/request` selects a synchronous operation with `:operation`:
`:chat`, `:decide`, `:embed`, `:moderate`, `:rerank`, `:generate-image`,
`:transcribe`, or `:speak`. Providers include OpenAI, Anthropic, Gemini and Jev.
It returns `{:provider ... :model ... :operation ... :data ...}` and available
metadata. Use `:return [:data]` to extract the payload. Credentials and endpoint
overrides belong in `:connection`; retries require explicit configuration.

See [LLM and SPARQL](./llm-and-sparql.md) for operation inputs, media, structured
output, capability limits, the SDK publication gate and connection examples.

### SPARQL actions

The optional `io.velio/collet-action-sparql` package provides
`:collet.actions.sparql/format`, `:collet.actions.sparql/query` and
`:collet.actions.sparql/update`. Flint EDN is the primary input; complete raw
SPARQL strings are accepted without interpolation. Query results default to
lossless RDF terms. See [formats, terms and retry semantics](./llm-and-sparql.md#sparql).

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
 :params {:question        "What is the capital of {country}?"
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

### Partition rows with slicer

Collet evaluates list expressions inside `:params` with the action's selectors
bound in SCI. For example, `:selectors {files [:$mapper/item :items]}` makes
`:params {:body {:ids (mapv :id files)}}` send a vector of IDs for the current
partition. A local binding such as `file` in `(fn [file] ...)` is scoped to
that function. A saved fixture that replaces the entire HTTP action can check
the surrounding task. A transport mock can also check the actual request body.

For file-backed input, mapper keeps the current row and looks ahead one row.
It skips empty batches and reads each batch once. The mapper state includes
`:rows-count` only for counted input or when artifact metadata supplies it.

Batch input to slicer filter, map, select, partition, and join yields dataset
batches. A mapper consumes these directly. In Clojure, use
`(mapcat ds/rows batches)` for a row traversal. In-memory datasets keep
their single-dataset result. Batch joins stage rows and keys on local disk and
emit left rows in source order, including unmatched rows.

A strict join accepts `:strict {:source-hash :sha256 :target-hash :upload-sha256}`
alongside the normal join options. It requires a nonblank string identity and
content hash on every row, unique identities per side, identical identity sets,
and equal hashes for every pair. Validation finishes before the first joined
batch becomes available. This lets a downstream writer start safely even if a
mismatch occurs in the last input batch.

Use `[:partition {:size 100 :as :items}]` to prepare bulk calls. Each output
row has an `:items` vector of up to 100 original rows. Slicer preserves order
across input dataset batches and retains the last partial partition. Mapper
visits each partition row in turn. Nested Arrow input carries its declared
schema into the partition's List of Structs. For nested data that lacks a
schema, declare the input schema before partitioning.

### HTTP pagination, authentication, and polling

Use `:auth {:type :basic :username user :password password}`,
`{:type :bearer :value token}`, or
`{:type :api-key :in :header :name "X-API-Key" :value key}`. For query
authentication use `:in :query`. For OAuth2 pass `:type :oauth2`,
`:token-url`, `:client-id`, `:client-secret`, and either a `:refresh-token`
or `:grant-type :client_credentials`. Optional `:scope` accepts a string or
vector of strings. Collet refreshes before expiry and retries once on 401.
Replacement refresh tokens live only in memory. Managed requests reject
cross-origin pagination and do not forward credentials through redirects.

Use `:pagination` with one of these modes.

| Mode | Settings |
|---|---|
| `:page`, `:offset` | `:start` and `:step` default to 1 (set `:start 0` for a zero-based offset). `:cursor-param` overrides the query field. |
| `:body-cursor`, `:header-cursor` | `:next-path` or `:next-header` reads the cursor. `:cursor-param` writes it into the next request, e.g. `[:body :scrollId]`. |
| `:body-url`, `:link` | `:next-path` reads the URL from the response, or the action reads `rel=next` from the HTTP Link header. Relative URLs are resolved against the current request. |

`:items-path` defaults to `[:body]`. `:continuation` can override initial
request fields such as URL and body. `:until` evaluates a Collet condition
against each response. Without it, an empty numeric page or a missing cursor
or next URL ends pagination. Set `:max-pages` to bound the number of pages.
The last response's status and headers are retained, with `:body` replaced
by a file-backed sequence of datasets in page order. The request writes each
nonempty page to a temporary Arrow IPC file before fetching the next page.
Pages are decoded when consumed and can be traversed again. The request's
in-memory data is bounded by page size, while temporary storage grows with
the result. Direct callers can close the body when finished. Collet removes
partial files on request failure and collects files after the result and its
traversals become unreachable or when the JVM shuts down normally.

Opt into retries with `:retry {:max-retries 3 :statuses #{429 502 503}
:backoff-ms 250 :max-delay-ms 30000}`. Add `:transport? true` to retry I/O
errors. A `Retry-After` header takes precedence. Choose retry settings with
the endpoint's effects in mind. `:empty-body :error` rejects an empty
response. `:empty-body :nil` normalizes it. `:response-spec` validates the
decoded response body using Malli.

The `:collet.actions.http/poll` action uses the same request parameters and
`:poll {:until [:= [:body :status] "done"] :interval-ms 1000
:deadline-ms 120000}`. Add `:fail-when` for a terminal error response.
The deadline includes requests, retries, and waits.

### Inspect and encode original images

`:collet.actions.file/read` accepts `:path` or `:input` (path, byte array,
or stream). `:collet.actions.file/inspect-image` accepts the same inputs.
It validates PNG and JPEG content with limits on bytes and decoded pixels
and returns `:format`, `:mime-type`, `:width`, `:height`, `:size-bytes`,
and `:sha256`. Set `:base64? true` to return the original bytes as
`:base64`. Use `:collet.actions.file/decode-base64` with `:input` to
turn base64 back into bytes. File and S3 sinks accept these with
`:format :bytes`.
