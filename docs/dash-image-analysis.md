# Dash image analyzer

The [tested pipeline](../collet-app/test/portability/image-analysis.edn) reads
original PNG and JPEG assets from the Dash storage API, classifies them with Jev,
uploads original bytes to R2, and records provenance and decisions in RDFox.
Dash is a remote digital asset service. Its folder ID is not a filesystem path.

## Configuration

Supply secrets as runtime configuration, outside pipeline source:

```clojure
{:dash {:folder-id "DASH_FOLDER_OPTION_ID"
        :client-id #env "DASH_CLIENT_ID"
        :client-secret #env "DASH_CLIENT_SECRET"
        :refresh-token #env "DASH_REFRESH_TOKEN"}
 :jev {:base-url #env "JEV_BASE_URL" :api-key #env "JEV_API_KEY" :model #env "JEV_MODEL"}
 :r2 {:aws-region "auto" :aws-key #env "R2_ACCESS_KEY" :aws-secret #env "R2_SECRET_KEY"
      :endpoint-override {:protocol :https :hostname "ACCOUNT_ID.r2.cloudflarestorage.com" :port 443}}
 :r2-bucket "original-images"
 :rdfox {:sparql-url #env "RDFOX_UPDATE_URL" :username #env "RDFOX_USERNAME" :password #env "RDFOX_PASSWORD"}
 :max-image-bytes 3000000
 :min-choice-confidence 0.7
 :capability-threshold 0.5
 :ad-categories {:retail "Retail products" :other_unclear "Unclear or unsupported category"}
 :capability-criteria {:contains_text "Visible text is present in the image"}}
```

## Migration to the generic actions

Jev uses `:collet.actions.llm/request` with `:operation :decide`. Configure the API
base URL without `/systemone`; the adapter adds the route, image assets and required
header. Existing configuration can supply `:jev :endpoint` ending in
`/systemone`. Jev and RDFox writes each make one attempt by default. The RDFox task
passes a Flint update to `:collet.actions.sparql/update` to replace only the asset
named graph. The action returns the HTTP status as a receipt.

The classification and upload branches still run in parallel. The RDFox task joins
them by source identity and checks content hashes before it writes. Use
`collet_describe_action` for the pinned dependencies and read `generic-actions` in
MCP for operation schemas, result formats, and retry rules.

Jev choices and thresholds retain the previous pipeline's meaning. The pipeline-level Malli `:config-spec` requires a positive
`:max-image-bytes` of at most 3,000,000. Classification remains creative triage rather than policy approval.

## Initial Dash authorization

Enable REST API access in Dash under Admin > Integrations. Add your callback URL
and obtain the account's client ID and secret. Authorize once in a browser using
`https://login.dash.app/authorize` with `response_type=code`, your client ID and
redirect URI, `audience=https://assetplatform.io`, and the scopes
`offline_access subdomain:YOUR_ACCOUNT_SUBDOMAIN`. Exchange the returned code at
`https://login.dash.app/oauth/token` and retain the refresh token in your external
secret configuration. Dash does not support client-credentials or password grants.
See the [official OAuth instructions](https://api-docs.dash.app/dash/openapi).

Dash requests opt into the HTTP action’s managed OAuth refresh grant.
It renews expiring access tokens and uses any returned replacement refresh token
for the current run. If refresh tokens rotate, update your external secret for
subsequent runs. OAuth credentials and tokens are excluded from retained task
outputs. Optional `:api-url` and `:token-url` overrides exist for test endpoints;
Malli supplies the defaults `https://api-v2.dash.app` and
`https://login.dash.app/oauth/token` when the keys are omitted. Override
`:api-url` with an HTTP(S) base URL without a trailing slash.

## Source and output behavior

The pipeline resolves the account folder field through `/folder-settings`, then
scrolls through live assets in the configured folder. Dash's hierarchical folder
matching includes descendant folders. It follows the latest scroll ID until the
search is exhausted and fails clearly when no live PNG/JPEG images are found.
See [Dash's search semantics](https://api-docs.dash.app/dash/openapi/assets).

The listing task produces file metadata. The next task partitions those rows
into batches of 100 and resolves download jobs. A third task reads and inspects
the files. Each download batch pins the asset file version discovered by the search. It
requests no transformations and no ZIP, polls once per second, and fails after
two minutes or on job/item errors. Transient HTTP responses receive at most
three retries, honoring `Retry-After` within the deadline. Signed download URLs
receive no Dash bearer header. The image-byte limit is enforced during download.
See the [download API](https://api-docs.dash.app/dash/openapi/assets/postassetdownloadbatchjob).

Retained provenance uses `:dash-account-id`, `:dash-asset-id`, and `:dash-file-id`.
The source identity hashes account ID plus asset ID; content SHA-256 continues to
name R2 originals. RDFox graphs therefore track stable Dash identities across
file replacements. Previous graphs based on local paths remain intact. Signed
URLs and OAuth values do not appear in downstream image records. The RDFox
task checks both branches for duplicate identities, missing matches, and
mismatched content hashes before it submits any update.

The automated fixture exercises the source with real HTTP endpoints and
LocalStack alongside Jev/RDFox mocks in a clean standalone process. A live run
requires valid Dash, Jev, R2, and RDFox configuration.

### Offline memory fixture

From the repository root, run each size in a fresh JVM. The fixture generates
distinct PNGs, substitutes the HTTP and upload transports, and checks that the
ordered identity and content hashes written to the RDFox mock match the downloads.

```sh
clojure -J-Xmx384m -J--add-opens=java.base/java.nio=ALL-UNNAMED -M collet-app/test/portability/streaming_fixture.clj full 768
```

Use `full 128` for the smaller comparison, `small 8192` for the multi-batch
graph, `slicer 10000` and `slicer 60000` for partitioning, and `join 5000`
and `join 40000` for the validated join. Each reports post-GC heap, the largest
observed Arrow reader allocation, and on-disk join staging. On macOS, prefix
the command with `/usr/bin/time -l` to also record peak process RSS.

The measurements below predate the generic LLM/SPARQL migration.
The 2026-11-18 offline run used the packaged build in a fresh JVM per row.
All measurements are in MiB. The graph rows show post-GC heap during image
preparation. The slicer and join rows show post-GC heap with the consumed
result handle still reachable. Join disk includes staged input and DuckDB files.

| Fixture | Live heap | Peak process RSS | Largest observed Arrow reader | Peak observed join disk |
| --- | ---: | ---: | ---: | ---: |
| 128 images, 433 KiB each | 77–79 | 751 | 8 | 1.1 |
| 768 images, 433 KiB each | 78–89 | 992 | 8 | 2.8 |
| 8,192 small images | 78–88 | 818 | 8 | 24.1 |
| Slicer, 10,000 rows | 69 | 487 | 0 | 0 |
| Slicer, 60,000 rows | 69 | 516 | 0 | 0 |
| Join, 5,000 rows per input | 69 | 464 | 0 | 11.5 |
| Join, 40,000 rows per input | 69 | 456 | 0 | 87.0 |

The isolated slicer and join fixtures generated dataset batches in memory.
Each join read each input once. Across all three graph runs, every download
had one matching classification, upload and mock RDFox write.
