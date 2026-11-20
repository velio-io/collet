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

Use `collet_describe_action` to obtain the exact `dependencyEdn` for an action,
then merge its `:coordinates` and `:requires` into the pipeline's `:deps`.
Packages have independent versions; preserve each returned pin. The compatibility
aggregate `io.velio/collet-actions` remains available for legacy consumers.

Generic LLM operations belong to `io.velio/collet-action-llm`; SPARQL formatting,
query and update belong to `io.velio/collet-action-sparql`. Their catalogue entries
include the corresponding schemas and dependency recipes. The LLM implementation
currently has an [unpublished SDK dependency](./llm-and-sparql.md#sdk-development-dependency);
source development requires `bb dev:llm-sdk`, and distributable releases remain gated.

`:coordinates` supplies Maven libraries, `:requires` loads namespaces, and
`:imports` exposes Java classes. Namespace/class declarations do not install a
library. Include coordinates even if the action is `classpath-present`, which
reports availability in the current process only. A successful Workbench or saved
run does not prove deployment portability. Test outside the checkout with the
intended deployment artifacts.

For source Workbench, run `bb workbench:prepare` before startup. It uses the build
version resolver to write `target/workbench-knowledge/collet/knowledge/compatibility.edn`
without building UI/JARs or installing dependencies. Packaged manifests take
precedence. Regenerate and restart after changing package versions. Without a
manifest, MCP returns null `version` and `dependencyEdn` plus preparation guidance;
never invent a version. Exact development versions must also be staged or
installed in the deployment's Maven repository.

These are consumer dependencies. Inside this repository, package `deps.edn` files
use top-level `:local/root` entries for other Collet packages, so a checkout uses its
own source. Kmono converts them only for generated POMs, where every internal Maven
coordinate receives the exact version resolved from its package tag. Do not copy
those generated versions into source dependencies.
