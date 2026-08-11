# Arrow record-batch boundary

Collet uses Arrow IPC for temporary task-result interchange. This document
defines the boundary owned by issue #46. It is separate from immutable Parquet
artifacts in issue #48 and optional DuckDB/DuckTape execution in issue #47.

## Explicit schema metadata

Attach the schema as :arrow-columns metadata to a sequential task result. The
schema is EDN-safe, versioned, and ordered:

~~~clojure
{:version 1
 :fields
 [{:key :id :name "id" :type :int64}
  {:key :address
   :name "address"
   :type :struct
   :fields [{:key :line :name "line" :type :string}
            {:key :zip :name "zip" :type :int32 :nullable? false}]}
  {:key :attributes :name "attributes" :type :map}
  {:key :events
   :name "events"
   :type :list
   :element {:type :struct
             :fields [{:key :kind :name "kind" :type :string}
                      {:key :score :name "score" :type :int32}]}}
  {:key :embedding
   :name "embedding"
   :type :fixed-size-list
   :size 768
   :element {:type :float32 :nullable? false}}
  {:key :amount
   :name "amount"
   :type :decimal
   :bit-width 128
   :precision 18
   :scale 2}
  {:key :external-id
   :name "external_id"
   :type :decimal
   :bit-width 256
   :precision 76
   :scale 0
   :logical-type :big-integer}]}
~~~

Every field has an application key, a physical Arrow name, a type, and an
optional nullable flag. Nullable defaults to true. Struct fields use the same
shape and are ordered. List elements are descriptors rather than named fields.

Legacy [key physical-name type] column triplets remain accepted and normalize
to this schema. New producers should emit the schema map.

## Type contract

| Shape | Arrow representation | Clojure value |
| --- | --- | --- |
| Scalar | Existing Arrow scalar types | Boolean, integer, floating point, String, UUID, Java Time, Duration |
| Struct | Fixed ordered fields | Map with declared keys only |
| Map | Non-null UTF-8 keys and nullable UTF-8 values | Map from String to String or nil |
| List | Nullable element descriptor | Sequential value, read as vector |
| Fixed embedding | FixedSizeList<Float32> | Vector of exactly the declared size |
| Decimal | Decimal128 or Decimal256 | BigDecimal, or BigInteger with logical type |

Struct is not Map: a Struct rejects extra keys and checks every non-nullable
child. Arrow Map is intentionally limited to dynamic UTF-8 keys and values.
Nil parent values, empty collections, nil list elements, and nil children are
distinct and round-trip separately.

## Inference and ambiguity

Without metadata, Collet infers only unambiguous scalars, Java Time values, and
homogeneous lists of scalars from the first 200 records in encounter order.
Struct-versus-Map values, fixed-size lists, all-null fields, empty/all-null
lists, mixed nested values, BigInteger, and BigDecimal require metadata.

At the core task-result boundary, a pipeline with :use-arrow true raises
:collet.error/arrow-schema-required instead of silently retaining ambiguous
data in memory. Setting :use-arrow false keeps the legacy in-memory behavior.
The JDBC action retains its best-effort JSON fallback when it has no explicit
schema.

## Numeric and temporal rules

All signed and unsigned integer widths are checked before writing. Decimal128
supports precision through 38 and Decimal256 through 76. BigDecimal must fit
the declared precision and scale exactly; required rounding and overflow fail.
Logical BigInteger uses decimal scale zero and Arrow field metadata.

Use Instant, LocalDate, LocalDateTime, or LocalTime. java.util.Date and all
of its subclasses are rejected rather than normalized implicitly.

## Batching and TMD views

Flat lazy results are written as private 4,096-row Arrow record batches.
Already batched output, including TMD dataset sequences, is written while
retaining its non-empty batch boundaries. No whole-result counting or random
sampling is used.

Scalar-only schemas retain the memory-mapped tech.ml.dataset reader. A schema
with nested or extended values is read one Arrow batch at a time into TMD
object columns: Struct and Map become Clojure maps, Lists become vectors, and
decimal values become BigDecimal or logical BigInteger. That view copies only
the requested bounded batch into heap; it does not make the complete IPC file
resident. The schema metadata stays attached so prep-record can restore
top-level nil keys that TMD omits from row maps.

Readers, channels, and allocators close at EOF or error, with resource tracking
as the abandonment fallback.

## Errors

Conversion failures are ExceptionInfo values with a short, path-aware payload:

- :collet.error/type identifies schema, value, legacy-date, or schema-required
  failure.
- :path identifies a row, field, list index, and nested child.
- :expected, :reason, and :actual-class explain the failed conversion.

The error payload deliberately excludes complete rows and batches.
