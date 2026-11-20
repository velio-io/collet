# Generic LLM and SPARQL actions

## SDK development dependency

The LLM integration uses [clojure-llm-sdk 0.6.4](https://github.com/DeadMeme5441/clojure-llm-sdk/tree/v0.6.4)
with the shared request-lifecycle fix in
[`patches/clojure-llm-sdk-0.6.4-lifecycle.patch`](../patches/clojure-llm-sdk-0.6.4-lifecycle.patch).
The patch preserves HTTP error metadata and shares bounded, opt-in retries across
ordinary synchronous chat and non-chat operations. Successful response parsing is
outside the retry loop. Authentication, invalid input, exhausted credits and
malformed successful responses are not blindly replayed.

Run `bb dev:llm-sdk` from the repository root before starting development. The
script clones upstream revision `2f3c4260e0bc2c2a9bb55407db3bdd4579f5336e`, applies
the patch, runs the SDK's offline suite and installs
`net.clojars.deadmeme5441/clojure-llm-sdk 0.6.4-collet.1-SNAPSHOT` locally. This
coordinate is a development build, **not a published release**. Existing upstream
0.6.4 artifacts are never overwritten. CI performs the same preparation.

The [upstream SDK pull request](https://github.com/DeadMeme5441/clojure-llm-sdk/pull/12)
requests a Clojars release. Once the fixed SDK is published, pin that exact Maven
version, remove the LLM artifact's `:release-blocker`, and rerun the acceptance checks.
`bb release:plan` reports
blockers and `bb release` rejects selected packages that depend on a blocked
artifact. No automatic publication occurs during preparation or testing.
`bb verify` also rejects the snapshot in the LLM package's POM; this artifact
check must remain failing until the published fixed SDK replaces it. Unit,
integration and independent SPARQL artifact checks can run against this checkout.

## LLM

Use `io.velio/collet-action-llm`, namespace `collet.actions.llm`, action
`:collet.actions.llm/request`. Obtain a pinned package recipe from the catalogue;
published consumers must wait for the SDK release gate above.

```clojure
{:name :classify
 :type :collet.actions.llm/request
 :selectors {key [:config :api-key] model [:config :model]}
 :params {:operation :chat :provider :openai :model model
          :connection {:api-key key :timeout-ms 60000}
          :messages [{:role :system :content "Classify the supplied description."}
                     {:role :user :content "A blue cotton shirt"}]
          :response-format {:type :malli :name "classification"
                            :schema [:map [:category :string]]}}
 :return [:data]}
```

Common keys are `:provider`, `:model`, `:connection`, `:retry`, `:provider-options`,
`:raw?` and `:max-media-bytes`. Connection values are per call: `:api-key` (or
`:auth-token`), `:base-url`, `:headers`, `:connect-timeout-ms`, `:timeout-ms`.
The base URL excludes the operation route. No provider registry is mutated.
`:gemini` maps to the SDK's native Gemini provider; `:jev` maps to TypeSafe System
One. Other SDK provider IDs are accepted when they implement the operation.

| Operation | Inputs | `:data` |
| --- | --- | --- |
| `:chat` | `:messages`; optional tools, sampling, response format | Text, or validated structured data |
| `:decide` | `:input` state, `:questions`, optional `:images` | `{:answers {"id" typed-answer}}` |
| `:embed` | `:input` string or vector of strings | Vector of embedding vectors |
| `:moderate` | `:input` string or vector of SDK moderation inputs | Moderation result maps |
| `:rerank` | `:input` query, `:documents`; optional `:top-n`, `:return-documents` | Ranked index/score/document maps |
| `:generate-image` | `:input` prompt; optional `:n`, `:size`, `:quality`, `:style`, `:response-format` | Image URL/base64 maps |
| `:transcribe` | `:file` path, File, bytes or stream; optional filename, language, format, timestamps | Text and available transcript metadata |
| `:speak` | `:input` text; optional voice, format, speed, instructions | `{:bytes byte-array :content-type string}` |

The envelope always includes `:provider`, `:model`, `:operation` and `:data`.
Available metadata includes `:id`, `:usage`, `:cost`, `:finish-reason`,
`:tool-calls`, `:provider-data` and canonical SDK `:parts` for lossless replay.
Unknown usage is omitted; unknown cost is marked `:unknown`, never zero.
`:raw? true` additionally retains the provider body. Raw bodies and replay data
can contain submitted content, so enable retention deliberately. Audio bytes
use Collet's existing durable byte-array artifact encoding.

Messages use `{:role :user :content "text"}` or a vector of parts:

```clojure
[{:type :text :text "Describe the image"}
 {:type :image :file "/path/to/image.png"}]
```

Images accept exactly one of `:file`, HTTP(S) `:url`, or `:base64` plus
`:mime-type`. Files are read with a byte limit (20 MiB by default). Inline images
support PNG, JPEG, WebP and GIF, subject to provider/model limits. URL images are
forwarded to chat providers. Jev URL images are downloaded without LLM credentials
and converted to bounded inline assets; Jev accepts PNG/JPEG here.

Structured chat output accepts `{:type :malli :schema ...}` or
`{:type :json-schema :schema {...}}`. Both are sent as JSON Schema and checked
locally; Malli constraints are checked again after JSON parsing. Object keys in
returned structured data are keywords. JSON Schema references must be local.
Refusals, truncation, invalid JSON, schema mismatches and unsupported capabilities
produce explicit error codes. Actual model support can still differ from a
provider's declared capabilities and may be rejected by the provider.

The LLM package uses NetworkNT JSON Schema Validator 3.0.9 with Jackson 3.3.0.
It checks the response as one JSON value and uses Draft 2020-12 when the schema
does not specify a dialect. Jackson 2 can coexist on the classpath for packages
that still use it.

Tools follow the SDK's declarative function schema:
`{:type :function :function {:name "lookup" :parameters {...}}}`. Returned
`:tool-calls` contain IDs, names, JSON argument strings and available provider
replay data. Explicit downstream Collet actions execute tools; this action never
executes a tool loop. The legacy `:collet.actions.llm/openai` action retains its
successful shapes and legacy tool execution behavior.

Jev questions retain `:noul`, `:choice` and `:score` types. Question/criterion IDs
are converted to JSON strings without dropping keyword namespaces. Answers retain
probabilities, confidence and score legends; thresholds stay in the pipeline.

```clojure
{:operation :decide :provider :jev :model "configured-model"
 :connection {:base-url "https://api.typesafe.ai/v1" :api-key "configured-key"}
 :input {:image "original"}
 :images {"original" {:file "/path/to/image.png"}}
 :questions {"contains_text" {:type :noul :instructions "Is visible text present?"}}}
```

Retries are disabled by default. Configure, for example,
`:retry {:max-attempts 3 :budget-ms 30000 :base-delay-ms 250 :max-delay-ms 5000
:jitter-ratio 0.2}`. Attempts include the first request. `Retry-After` seconds and
HTTP dates are honored when they fit the remaining deadline. Multipart retries
require replayable files/bytes; streams cannot be replayed. Interrupted or
cancelled requests do not retry. Provider-specific options remain an escape
hatch, not a portability promise. Streaming output, automatic tool loops and
provider batch jobs are outside this action's contract.
The streaming-only `:codex-backend` provider is explicitly rejected here.

## SPARQL

Use `io.velio/collet-action-sparql`, namespace `collet.actions.sparql`. Construction
uses [Flint 0.3.1](https://github.com/yetanalytics/flint); graph decoding and
JSON-LD serialization use RDF4J Rio 6.1.0 Turtle, N-Triples and JSON-LD modules.

```clojure
{:name :labels
 :type :collet.actions.sparql/query
 :selectors {endpoint [:config :sparql-url] label [:config :label]}
 :params {:url endpoint
          :query {:select [?s] :where [[?s "<urn:label>" {:rdf/type :literal :value label}]]}
          :as :rdf}}
```

`/format` accepts exactly one `:query` or `:update`, with optional `:pretty?`, and
performs no HTTP effect. `/query` accepts SELECT, ASK, CONSTRUCT and DESCRIBE.
`/update` accepts one update or an ordered vector of updates, submitted together
as one request. Strings are complete statements with no interpolation. A raw
query requires `:query-kind :select`, `:ask`, `:construct` or `:describe`.

| Query | `:as :rdf` (default) | `:as :clj` | `:as :jsonld` |
| --- | --- | --- | --- |
| SELECT | Rows of RDF term maps | Rows of Clojure values | Rejected before HTTP |
| ASK | Boolean | Boolean | Rejected before HTTP |
| CONSTRUCT / DESCRIBE | Triple vectors of RDF term maps | Triple vectors of converted values | Expanded JSON-LD |

RDF terms are `{:rdf/type :iri :value "urn:id"}`,
`{:rdf/type :bnode :value "id"}`, or
`{:rdf/type :literal :value "001" :datatype "http://www.w3.org/2001/XMLSchema#integer"}`.
Language literals also contain `:language`. SELECT variables become keyword keys;
unbound variables are absent. Clojure conversion returns IRI strings, blank-node
strings prefixed with `_:` and native boolean/numeric values. Other literals keep
their lexical strings. `:rdf` preserves the original lexical values and types.

These explicit forms also work as dynamic Flint inputs. Literal `:value` is an
unescaped string; use either `:language` or `:datatype`. Language terms returned
by queries may also retain their matching `rdf:langString` datatype. Ordinary Flint strings
and language maps are escaped at this boundary too. Static `<...>` strings retain
Flint's IRI meaning; use an explicit literal to store text shaped like an IRI.
Do not pre-escape values. Collet preserves SPARQL expression lists such as
`(> ?score threshold)` while substituting selectors. Other actions retain normal
Clojure expression evaluation.

Query/update connections use HTTP action conventions: `:url`, `:auth`, `:headers`
and `:timeout`. POST is the default; queries may opt into GET. `:auth` supports
the same basic, bearer, API-key and OAuth forms as the HTTP action. Queries
request SPARQL JSON for SELECT/ASK, or Turtle/N-Triples for graphs; JSON-LD is
produced locally, without requiring endpoint JSON-LD support. Responses are
bounded by `:max-response-bytes` (32 MiB by default).

An update returns `{:status 204}` (or the actual successful HTTP status), with no
invented affected-triple count. Updates make one attempt by default. Enabling
`:retry {:max-retries 2 ...}` additionally requires `:retry-safe? true`: a timeout
may mean the update already executed. A failed submitted update reports
`:ambiguous? true`. Decide whether the operation is safe to repeat before opting
in. HTTP error status is retained; credentials, request bodies and exception
causes are omitted from action failures.

The [Dash fixture](../collet-app/test/portability/image-analysis.edn) combines the
Jev action and a Flint DELETE/INSERT/WHERE update. In MCP, read `dash-images` for
the configuration and migration details. Its mock tests preserve
classification/provenance, check both branches' identity and hashes, and execute
updates in an in-memory RDF store to verify named-graph replacement.
