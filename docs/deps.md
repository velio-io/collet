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