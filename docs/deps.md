### Adding custom dependencies

You can use any Clojure or Java library available on Maven or Clojars.
All you need to do is to provide the dependency map with the following keys:

- `:coordinates` - a vector of Maven artifact/version pairs
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

Collet's optional actions use normal Maven coordinates as well. Prefer the smallest
module that contains the namespaces a pipeline needs:

```clojure
{:deps {:coordinates [[io.velio/collet-action-http "VERSION"]]
        :requires [[collet.actions.http]]}}
```

Use `[io.velio/collet-actions "VERSION"]` when compatibility with all legacy action
namespaces is more important than classpath size. `VERSION` always means the
version published for that specific coordinate; Collet modules are independently
versioned and their versions do not need to match. The aggregate's generated POM
pins the exact compatible versions of all action modules transitively, so consumers
must not add or synchronize those internal versions themselves. See the
[module graph](./module-migration.md#module-graph) for every coordinate and its direct
internal dependencies.
