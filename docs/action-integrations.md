# SQL, charting and search actions

Optional actions require their own package. Read each action descriptor for
its coordinate, authoring parameter schema, effects, availability and example.
Versions are independent per package; declare the version installed in your environment.

## SQL

`:collet.actions.jdbc/query` takes `:connection` and `:query`. `:collet.actions.jdbc/execute` takes
`:connection` and `:statement`. Both accept SQL vectors with bound values or
HoneySQL maps. A connection is a JDBC string or a connection map. Put credentials
in runtime configuration and reference them using selectors.

```clojure
{:name :write-total :type :collet.actions.jdbc/execute
 :selectors {db [:config :database]}
 :params {:connection db
          :statement ["INSERT INTO daily_totals(day, currency, total) VALUES (?, ?, ?)"
                      "2026-01-01" "EUR" 42]}}
```

SQL executes against the actual database. Query result samples do not limit SQL work.

## Charts

`:collet.actions.vega/sink` takes either `:vega-spec` or `:vega-lite-spec`, and
`:svg-file-path`. It writes an SVG file. Optional `:data` and
`:store-data-files?` control chart data files. Full chart schema checks happen
at runtime.

## Search

`:collet.actions.lucene/index` takes `:index-path` and `:input` (or `:docs-path`) and writes
an index. `:collet.actions.lucene/query` takes `:index`, `:query`, and optional `:limit`,
`:offset`, `:fields`, `:default`, `:analyzer`, `:searcher`, and `:include-score?`.
Its query can be a string or the Lucene query DSL described in the package.

```clojure
{:name :search :type :collet.actions.lucene/query
 :params {:index "data/index" :query "currency:EUR" :limit 20}}
```
