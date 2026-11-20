# Working recipes

## Paid orders and daily totals

This pipeline keeps the default Arrow task handoff. Slicer callbacks operate on
rows; an ordinary `filterv` on the upstream batch container does not.

```clojure
{:name :daily-sales
 :tasks
 [{:name :orders :keep-state true
   :actions [{:name :rows :type :clj/identity
              :params [[{:day "2026-11-01" :currency "EUR" :amount 12 :status "paid"}
                        {:day "2026-11-01" :currency "EUR" :amount 8 :status "paid"}
                        {:day "2026-11-01" :currency "USD" :amount 9 :status "paid"}
                        {:day "2026-11-01" :currency "EUR" :amount 99 :status "cancelled"}]]}]}
  {:name :totals :inputs [:orders] :keep-state true
   :actions [{:name :daily :type :slicer
              :selectors {rows [:inputs :orders]}
              :params {:sequence rows
                       :apply [[:filter {:by :status :predicate #{"paid"}}]
                               [:fold {:by [:day :currency]
                                       :columns {:total [:sum :amount]}}]]}}]}]}
```

Run `orders`, inspect `totals` inputs, then run `totals`. Save and run that exact
version with explicit empty configuration. Expected totals are EUR 20 and USD 9.
To fetch actual orders, discover the HTTP action and use its returned dependency
EDN. To write totals, discover the JDBC action and inspect its row-input contract.

## Row iteration and retry

```clojure
{:name :temperatures
 :tasks [{:name :convert :keep-state true
          :retry {:max-retries 2 :backoff-ms [10 100 2]}
          :actions [{:name :each :type :mapper
                     :selectors {rows [:config :readings]}
                     :params {:sequence rows}}
                    {:name :fahrenheit :type :custom
                     :selectors {row [:$mapper/item]}
                     :params [row]
                     :fn (fn [row] {:sensor (:sensor row)
                                    :fahrenheit (+ 32 (* 1.8 (:celsius row)))})}]
          :iterator {:next [:true? [:$mapper/has-next-item]]}}]}
```

Configuration: `{:readings [{:sensor "A" :celsius 0} {:sensor "B" :celsius 10}]}`.
Run the whole iterator task; individual-action execution is unavailable inside an
iterator or parallel task. Results are 32 and 50 Fahrenheit.

Backoff is `[initial-ms maximum-ms]` or `[initial-ms maximum-ms multiplier]`.
Delays are positive integers, initial is smaller than maximum, and a supplied
multiplier is finite and greater than one. Both `2` and `2.0` are accepted.
Retries repeat the action chain and may repeat external effects. An idempotency
key prevents duplicate execution starts, not duplicate effects within a retry.

## Native HTTP to S3 or R2

Discover and describe `collet.actions.http/request` and
`collet.actions.s3/sink`. Merge their returned `dependencyEdn` maps into `:deps`,
preserving each independent pinned version. If either recipe is null, prepare
source Workbench with `bb workbench:prepare` and restart; do not guess versions.
These action fragments use the native parameter and response contracts:

```clojure
{:name :orders :type :collet.actions.http/request
 :selectors {url [:config :orders-url]}
 :params {:url url :method :get :as :json}
 :return [:body]}
```

```clojure
{:name :upload :type :collet.actions.s3/sink
 :selectors {rows [:state :orders] creds [:config :aws] bucket [:config :bucket]}
 :params {:aws-creds creds :bucket bucket :file-name "orders.json" :input rows :format :json}}
```

Configuration for R2 uses an explicit HTTPS endpoint and credentials supplied by
the caller. Use a fixture endpoint and fixture credentials for local tests:

```clojure
{:orders-url "https://example.test/orders?limit=20"
 :bucket "reports"
 :aws {:aws-key "EXAMPLE_KEY" :aws-secret "EXAMPLE_SECRET" :aws-region "auto"
       :endpoint-override {:protocol :https
                           :hostname "ACCOUNT_ID.r2.cloudflarestorage.com"
                           :port 443}}}
```

HTTP returns a response map; `:return [:body]` passes decoded records to the sink.
To upload an existing binary file, select its local path as `:input` and omit
`:format`. The S3 descriptor and action reference include this form. Saved-run
success proves only that process has the dependencies; verify the same EDN in a
clean deployment process.

## Partition a listing for bulk requests

This recipe runs with no optional packages. Each result row represents one
bulk request with at most two items.

```clojure
{:name :batch-listing
 :tasks [{:name :files
          :actions [{:name :list :type :clj/identity
                     :params [[{:id "a"} {:id "b"} {:id "c"}]]}]}
         {:name :requests :inputs [:files] :keep-state true
          :actions [{:name :batches :type :slicer
                     :selectors {files [:inputs :files]}
                     :params {:sequence files :apply [[:partition {:size 2}]]}}]}]}
```

In a downstream task, select the partition rows as the mapper sequence and
bind the bulk request body to `[:$mapper/item :items]`.
