(ns collet.arrow
  (:require
   [clojure.core.protocols :as clj-proto]
   [malli.core :as m]
   [malli.transform :as mt]
   [tech.v3.dataset :as ds]
   [tech.v3.dataset.utils :as ds.utils]
   [tech.v3.datatype :as dtype]
   [tech.v3.datatype.casting :as casting]
   [tech.v3.datatype.packing :as packing]
   [tech.v3.libs.arrow :as arrow]
   [tech.v3.resource :as resource])
  (:import
    (clojure.lang BigInt ExceptionInfo)
    [java.io Closeable File FileOutputStream]
    (java.lang AutoCloseable)
    [java.math BigDecimal BigInteger RoundingMode]
    [java.nio.channels FileChannel SeekableByteChannel]
    [java.nio.file OpenOption Path Paths StandardOpenOption]
    [java.time Duration Instant LocalDate LocalDateTime LocalTime ZoneOffset]
    [java.util Date Map UUID]
    [org.apache.arrow.memory RootAllocator]
    [org.apache.arrow.vector
     BigIntVector BitVector DateDayVector Decimal256Vector DecimalVector DurationVector FieldVector Float4Vector
     Float8Vector IntVector SmallIntVector TimeMicroVector TimeMilliVector TimeNanoVector TimeSecVector
     TimeStampMicroVector TimeStampMilliVector TimeStampNanoVector TinyIntVector UInt1Vector UInt2Vector UInt4Vector
     UInt8Vector VarCharVector VectorSchemaRoot]
    [org.apache.arrow.vector.complex FixedSizeListVector ListVector MapVector StructVector]
    [org.apache.arrow.vector.complex.impl UnionListWriter]
    [org.apache.arrow.vector.holders NullableDurationHolder]
    [org.apache.arrow.vector.ipc ArrowFileReader ArrowFileWriter ArrowReader]
    [org.apache.arrow.vector.types DateUnit FloatingPointPrecision TimeUnit]
    [org.apache.arrow.vector.types.pojo
     ArrowType ArrowType$Bool ArrowType$Date ArrowType$Decimal ArrowType$Duration ArrowType$FixedSizeList ArrowType$FloatingPoint
     ArrowType$Int ArrowType$List ArrowType$Map ArrowType$Struct ArrowType$Time ArrowType$Timestamp ArrowType$Utf8 Field FieldType
     Schema]
    [org.apache.arrow.vector.util Text]))


(def schema-version 1)


(def zoned-types
  #{:instant :epoch-milliseconds :epoch-microseconds :epoch-nanoseconds})


(def scalar-types
  #{:boolean
    :uint8 :int8 :uint16 :int16 :uint32 :int32 :uint64 :int64
    :float32 :float64
    :epoch-days :local-date :local-time :local-date-time
    :time-nanoseconds :time-microseconds :time-milliseconds :time-seconds
    :duration :string :uuid :text :encoded-text
    :instant :epoch-milliseconds :epoch-microseconds :epoch-nanoseconds})


(def ^:private decimal-max-precision
  ;; Arrow Decimal128/256 physical encodings cap precision at 38/76 digits.
  {128 38
   256 76})


(def ^:private map-key-descriptor
  {:type :string :nullable? false})


(def ^:private map-value-descriptor
  {:type :string :nullable? true})


(def varchar-types
  #{:string :uuid :text :encoded-text})


(casting/alias-datatype! :duration :int64)


(extend-protocol clj-proto/Datafiable
 ArrowType$Duration
 (datafy [_]
   {:datatype :duration}))


(defn- class-name
  [value]
  (when (some? value)
    (.getName (class value))))


(defn- arrow-error!
  [type message data]
  (throw
   (ex-info message
            (assoc data :collet.error/type type))))


(defn- schema-error!
  [reason message]
  (arrow-error! :collet.error/arrow-invalid-schema
                message
                {:reason reason}))


(defn- value-error!
  [path descriptor reason value]
  (arrow-error!
   :collet.error/arrow-invalid-value
   (str "Invalid Arrow value at " (pr-str path) ".")
   {:path         path
    :expected     (case (:type descriptor)
                    :decimal (str "decimal"
                                  (:bit-width descriptor)
                                  "("
                                  (:precision descriptor)
                                  ","
                                  (:scale descriptor)
                                  ")")
                    :fixed-size-list (str "fixed-size-list[" (:size descriptor) "]")
                    (name (:type descriptor)))
    :reason       reason
    :actual-class (or (class-name value) "nil")}))


(defn- legacy-date-error!
  [path value]
  (arrow-error!
   :collet.error/arrow-legacy-date
   (str "Legacy java.util.Date is not supported at " (pr-str path) ".")
   {:path         path
    :reason       :legacy-java-util-date
    :actual-class (class-name value)
    :remediation  "Use Instant, LocalDate, LocalDateTime, or LocalTime."}))


(defn- schema-required!
  [path reason value]
  (arrow-error!
   :collet.error/arrow-schema-required
   (str "An explicit :arrow-columns schema is required at " (pr-str path) ".")
   {:path         path
    :reason       reason
    :actual-class (or (class-name value) "nil")}))


(def ^:private schema-error-messages
  {:invalid-descriptor           "Arrow descriptor must be a keyword or map."
   :duplicate-field-name         "Arrow field names must be unique."
   :duplicate-field-key          "Arrow field keys must be unique."
   :invalid-map-key              "Arrow Map keys must be non-null UTF-8 strings."
   :invalid-map-value            "Arrow Map values must be nullable UTF-8 strings."
   :invalid-struct               "Arrow Struct requires an ordered :fields vector."
   :missing-list-element         "Arrow List requires :element."
   :invalid-fixed-size           "Arrow fixed-size list requires a positive :size."
   :invalid-fixed-size-element   "Arrow fixed-size lists currently require Float32 elements."
   :invalid-decimal-width        "Arrow decimal :bit-width must be 128 or 256."
   :invalid-decimal-scale        "Arrow decimal scale must be between zero and its precision."
   :invalid-decimal-logical-type "Arrow decimal logical type must be :big-integer when present."
   :invalid-big-integer-scale    "Logical BigInteger decimals require scale zero."
   :invalid-timezone             "Arrow timezone must be a string."
   :invalid-field-key            "Arrow field :key must be a keyword or string."
   :invalid-field-name           "Arrow field :name must be a string."
   :invalid-schema               "Arrow schema must be a map or legacy column vector."
   :invalid-fields               "Arrow schema requires an ordered :fields vector."
   :invalid-legacy-column        "Arrow columns must use a known legacy type or descriptor map."})


(def ^:private legacy-column-shape-message
  "Legacy Arrow columns must be [key physical-name type] triplets.")


(defn- schema-error-message
  [reason value]
  (case reason
    :invalid-decimal-precision
    (str "Arrow decimal precision must be between 1 and "
         (get decimal-max-precision (:bit-width value))
         ".")

    :unsupported-type
    (str "Unsupported Arrow type " (pr-str (:type value)) ".")

    :unsupported-schema-version
    (str "Unsupported Arrow schema version " (pr-str (:version value)) ".")

    (get schema-error-messages reason)))


(defn- schema-rule
  ([reason predicate]
   (schema-rule reason nil nil predicate))
  ([reason path predicate]
   (schema-rule reason path nil predicate))
  ([reason path error-message predicate]
   (let [message    (or error-message
                        (get schema-error-messages reason)
                        #(schema-error-message reason %))
         properties (cond-> {::reason  reason
                             ::message message}
                      path (assoc :error/path path)
                      (string? message) (assoc :error/message message)
                      (fn? message) (assoc :error/fn
                                      (fn [{:keys [value]} _]
                                        (message value))))]
     [:fn properties predicate])))


(defn- field-values-unique?
  [field-key fields]
  (or (not (vector? fields))
      (= (count fields) (count (set (map field-key fields))))))


(defn- legacy-type?
  [column-type]
  (or (keyword? column-type)
      (and (vector? column-type) (= :list (first column-type)))
      (and (vector? column-type) (= :zoned (first column-type)))
      (map? column-type)))


(defn- legacy-column-shape?
  [column]
  (and (vector? column) (= 3 (count column))))


(defn- legacy-column?
  [column]
  (and (legacy-column-shape? column)
       (legacy-type? (nth column 2))))


(defn- legacy-columns-shape?
  [columns]
  (and (vector? columns)
       (every? legacy-column-shape? columns)))


(defn- legacy-columns?
  [columns]
  (and (legacy-columns-shape? columns)
       (every? legacy-column? columns)))


(declare legacy-column-error)


(defn- valid-legacy-columns?
  [columns]
  (or (not (vector? columns))
      (nil? (legacy-column-error columns))))


(def ^:private legacy-columns-schema-rule
  [:fn
   {::reason    #(some-> (legacy-column-error %)
                         :reason)
    ::message   #(some-> (legacy-column-error %)
                         :message)
    :error/path [:fields]}
   valid-legacy-columns?])


(defn- legacy-type->descriptor
  [column-type]
  (cond
    (keyword? column-type)
    {:type column-type}

    (and (vector? column-type) (= :list (first column-type)))
    {:type    :list
     :element {:type (second column-type)}}

    (and (vector? column-type) (= :zoned (first column-type)))
    {:type     (second column-type)
     :timezone (nth column-type 2 nil)}

    :else
    column-type))


(defn- legacy-column->field-input
  [[key name column-type]]
  (merge (legacy-type->descriptor column-type)
         {:key key :name name}))


(defn- decode-descriptor-input
  [descriptor]
  (if (keyword? descriptor)
    {:type descriptor}
    descriptor))


(defn- decode-schema-input
  [columns]
  (if (legacy-columns? columns)
    {:version schema-version
     :fields  (mapv legacy-column->field-input columns)}
    columns))


(defn- decode-zoned-descriptor
  [descriptor]
  (assoc descriptor :timezone (or (:timezone descriptor) "UTC")))


(defn- decode-map-descriptor
  [descriptor]
  (assoc descriptor
    :key-type (or (:key-type descriptor) map-key-descriptor)
    :value-type (or (:value-type descriptor) map-value-descriptor)))


(defn- decimal-bit-width?
  [{:keys [bit-width]}]
  (contains? decimal-max-precision bit-width))


(defn- decimal-precision?
  [{:keys [bit-width precision]}]
  (if-let [max-precision (get decimal-max-precision bit-width)]
    (and (integer? precision)
         (pos? precision)
         (<= precision max-precision))
    true))


(defn- decimal-scale?
  [{:keys [bit-width precision scale] :as descriptor}]
  (if (and (decimal-bit-width? descriptor)
           (decimal-precision? descriptor))
    (and (integer? scale)
         (<= 0 scale precision))
    true))


(defn- decimal-logical-type?
  [{:keys [logical-type] :as descriptor}]
  (if (and (decimal-bit-width? descriptor)
           (decimal-precision? descriptor)
           (decimal-scale? descriptor))
    (or (nil? logical-type)
        (= logical-type :big-integer))
    true))


(defn- decimal-big-integer-scale?
  [{:keys [logical-type scale] :as descriptor}]
  (if (and (decimal-bit-width? descriptor)
           (decimal-precision? descriptor)
           (decimal-scale? descriptor)
           (decimal-logical-type? descriptor))
    (or (not= logical-type :big-integer)
        (= scale 0))
    true))


(def ^:private nullable-entry
  [:nullable? {:optional true}
   [:boolean
    {:default      true
     :decode/arrow boolean}]])


(defn- descriptor-map
  ([type entries]
   (descriptor-map type nil entries))
  ([type properties entries]
   (into (cond-> [:map]
           properties (conj properties))
         (concat [[:type [:= type]]
                  nullable-entry]
                 entries))))


(defn- zoned-descriptor-schema
  [type]
  [:and
   (descriptor-map type
                   {:decode/arrow {:enter decode-zoned-descriptor}}
                   [[:timezone {:optional true} :any]])
   (schema-rule :invalid-timezone
                [:timezone]
                #(string? (:timezone %)))])


(def ^:private scalar-descriptor-schema
  [:and
   [:map
    [:type {:optional true} :any]
    nullable-entry]
   (schema-rule :unsupported-type
                [:type]
                #(contains? scalar-types (:type %)))])


(def ^:private struct-descriptor-schema
  [:and
   (descriptor-map :struct
                   [[:fields {:optional true}
                     [:vector [:ref ::field]]]])
   (schema-rule :invalid-struct
                [:fields]
                #(vector? (:fields %)))
   (schema-rule :duplicate-field-name
                [:fields]
                #(field-values-unique? :name (:fields %)))
   (schema-rule :duplicate-field-key
                [:fields]
                #(field-values-unique? :key (:fields %)))])


(def ^:private map-descriptor-schema
  [:and
   (descriptor-map :map
                   {:decode/arrow {:enter decode-map-descriptor}}
                   [[:key-type {:optional true} [:ref ::descriptor]]
                    [:value-type {:optional true} [:ref ::descriptor]]])
   (schema-rule :invalid-map-key
                [:key-type]
                #(= map-key-descriptor
                    (select-keys (:key-type %) [:type :nullable?])))
   (schema-rule :invalid-map-value
                [:value-type]
                #(= map-value-descriptor
                    (select-keys (:value-type %) [:type :nullable?])))])


(def ^:private list-descriptor-schema
  [:and
   (descriptor-map :list
                   [[:element {:optional true} [:ref ::descriptor]]])
   (schema-rule :missing-list-element
                [:element]
                #(contains? % :element))])


(def ^:private fixed-size-list-descriptor-schema
  [:and
   (descriptor-map :fixed-size-list
                   [[:size {:optional true} :any]
                    [:element {:optional true} [:ref ::descriptor]]])
   (schema-rule :invalid-fixed-size
                [:size]
                #(and (integer? (:size %))
                      (pos? (:size %))))
   (schema-rule :invalid-fixed-size
                [:size]
                (str "Arrow fixed-size list :size must not exceed "
                     Integer/MAX_VALUE
                     ".")
                #(or (not (and (integer? (:size %))
                               (pos? (:size %))))
                     (<= (:size %) Integer/MAX_VALUE)))
   (schema-rule :invalid-fixed-size-element
                [:element]
                #(= :float32 (get-in % [:element :type])))])


(def ^:private decimal-descriptor-schema
  [:and
   (descriptor-map :decimal
                   [[:bit-width {:optional true} :any]
                    [:precision {:optional true} :any]
                    [:scale {:optional true} :any]
                    [:logical-type {:optional true} :any]])
   (schema-rule :invalid-decimal-width [:bit-width] decimal-bit-width?)
   (schema-rule :invalid-decimal-precision [:precision] decimal-precision?)
   (schema-rule :invalid-decimal-scale [:scale] decimal-scale?)
   (schema-rule :invalid-decimal-logical-type [:logical-type] decimal-logical-type?)
   (schema-rule :invalid-big-integer-scale [:scale] decimal-big-integer-scale?)])


(def ^:private arrow-schema-registry
  {::descriptor-input
   [:and
    (schema-rule :invalid-descriptor #(or (keyword? %) (map? %)))
    [:or
     [:keyword {:decode/arrow decode-descriptor-input}]
     :map]]

   ::descriptor
   [:and
    [:ref ::descriptor-input]
    (into [:multi {:dispatch :type}]
          [[:struct struct-descriptor-schema]
           [:map map-descriptor-schema]
           [:list list-descriptor-schema]
           [:fixed-size-list fixed-size-list-descriptor-schema]
           [:decimal decimal-descriptor-schema]
           [:instant (zoned-descriptor-schema :instant)]
           [:epoch-milliseconds (zoned-descriptor-schema :epoch-milliseconds)]
           [:epoch-microseconds (zoned-descriptor-schema :epoch-microseconds)]
           [:epoch-nanoseconds (zoned-descriptor-schema :epoch-nanoseconds)]
           [::m/default scalar-descriptor-schema]])]

   ::field
   [:and
    [:ref ::descriptor]
    [:map
     [:key {:optional true} :any]
     [:name {:optional true} :any]]
    (schema-rule :invalid-field-key
                 [:key]
                 #(or (keyword? (:key %)) (string? (:key %))))
    (schema-rule :invalid-field-name
                 [:name]
                 #(string? (:name %)))]

   ::schema-input
   [:and
    (schema-rule :invalid-schema #(or (map? %) (vector? %)))
    legacy-columns-schema-rule
    [:or
     [:vector {:decode/arrow decode-schema-input} :any]
     :map]]

   ::schema
   [:and
    [:ref ::schema-input]
    (schema-rule :unsupported-schema-version
                 [:version]
                 #(= schema-version (:version %)))
    (schema-rule :invalid-fields
                 [:fields]
                 #(vector? (:fields %)))
    [:map
     [:version {:optional true}
      [:any
       {:default      schema-version
        :decode/arrow #(or % schema-version)}]]
     [:fields {:optional true}
      [:vector [:ref ::field]]]]
    (schema-rule :duplicate-field-name
                 [:fields]
                 #(field-values-unique? :name (:fields %)))
    (schema-rule :duplicate-field-key
                 [:fields]
                 #(field-values-unique? :key (:fields %)))]})


(defn- schema-reference
  [reference]
  [:schema {:registry arrow-schema-registry}
   [:ref reference]])


(def ^:private descriptor-schema
  (m/schema (schema-reference ::descriptor)))


(def ^:private field-schema
  (m/schema (schema-reference ::field)))


(def ^:private arrow-schema
  (m/schema (schema-reference ::schema)))


(def ^:private arrow-transformer
  (mt/transformer {:name :arrow}
                  (mt/default-value-transformer {::mt/add-optional-keys true})))


(def ^:private decode-descriptor
  (m/decoder descriptor-schema arrow-transformer))


(def ^:private decode-field
  (m/decoder field-schema arrow-transformer))


(def ^:private decode-schema
  (m/decoder arrow-schema arrow-transformer))


(def ^:private explain-descriptor
  (m/explainer descriptor-schema))


(def ^:private explain-field
  (m/explainer field-schema))


(def ^:private explain-schema
  (m/explainer arrow-schema))


(defn- resolve-validation-property
  [property value]
  (if (fn? property)
    (property value)
    property))


(defn- explanation-schema-error
  [explanation]
  (some (fn [error]
          (let [properties (m/properties (:schema error))
                reason     (resolve-validation-property (::reason properties) (:value error))]
            (when reason
              {:reason  reason
               :message (resolve-validation-property (::message properties) (:value error))})))
        (:errors explanation)))


(defn- legacy-column-error
  [columns]
  (some (fn [column]
          (cond
            (not (legacy-column-shape? column))
            {:reason  :invalid-legacy-column
             :message legacy-column-shape-message}

            (not (legacy-type? (nth column 2)))
            {:reason  :invalid-legacy-column
             :message (get schema-error-messages :invalid-legacy-column)}

            :else
            (explanation-schema-error
             (explain-field
              (decode-field (legacy-column->field-input column))))))
        columns))


(defn- schema-error-from-explanation!
  [explanation]
  (if-let [{:keys [reason message]} (explanation-schema-error explanation)]
    (schema-error! reason message)
    (schema-error! :invalid-schema
                   (schema-error-message :invalid-schema nil))))


(defn- normalize-with
  [decoder explainer value]
  (let [value (decoder value)]
    (if-let [explanation (explainer value)]
      (schema-error-from-explanation! explanation)
      value)))


(defn normalize-descriptor
  "Normalize a scalar or nested descriptor into the versioned Arrow schema form."
  [descriptor]
  (normalize-with decode-descriptor explain-descriptor descriptor))


(defn normalize-field
  "Normalize a named field descriptor. Struct fields use the same shape."
  [field]
  (normalize-with decode-field explain-field field))


(defn normalize-schema
  "Accept legacy column triplets or a versioned schema map and return canonical EDN."
  [columns]
  (select-keys (normalize-with decode-schema explain-schema columns)
               [:version :fields]))


(defn- legacy-column->field
  [column]
  (-> (normalize-schema [column])
      :fields
      first))


(defn- schema-fields
  [columns]
  (:fields (normalize-schema columns)))


(defn find-column
  [columns column-name]
  (let [column-name-string (str column-name)]
    (some
     (fn [field]
       (when (or (= (:key field) column-name)
                 (= (:name field) column-name-string)
                 (= (str (:key field)) column-name-string)
                 (= (keyword (:name field)) column-name))
         field))
     (schema-fields columns))))


(defn ds->columns
  "Infer legacy column triplets from a tech.ml.dataset sample."
  [dataset]
  (->> dataset
       (mapv
        (fn [[_ column]]
          (let [{:keys [name datatype timezone]} (-> column meta)
                datatype    (packing/unpack-datatype datatype)
                column-name (ds.utils/column-safe-name name)
                column-type (cond
                              (or (= datatype :persistent-map)
                                  (= datatype :persistent-set)
                                  (= datatype :object))
                              (throw (ex-info "Complex objects require an explicit Arrow schema."
                                              {:column name}))

                              (= datatype :persistent-vector)
                              (let [item-types (->> column
                                                    (mapcat #(map dtype/elemwise-datatype %))
                                                    set)
                                    item-type  (if (or (empty? item-types)
                                                       (> (count item-types) 1))
                                                 :string
                                                 (packing/unpack-datatype (first item-types)))]
                                (when (or (= item-type :persistent-map)
                                          (= item-type :persistent-set)
                                          (= item-type :object))
                                  (throw (ex-info "Complex objects require an explicit Arrow schema."
                                                  {:column name})))
                                [:list item-type])

                              (contains? zoned-types datatype)
                              [:zoned datatype timezone]

                              :otherwise
                              datatype)]
            [name column-name column-type])))))


(defn- sample-records
  [data]
  (cond
    (ds/dataset? data)
    (mapv #(ds/row-at data %)
          (range (min 200 (ds/row-count data))))

    :else
    (vec (take 200 (or (seq data) ())))))


(defn- scalar-inference
  [path value]
  (cond
    (instance? Date value)
    (legacy-date-error! path value)

    (instance? Boolean value)
    {:type :boolean}

    (instance? Byte value)
    {:type :int8}

    (instance? Short value)
    {:type :int16}

    (instance? Integer value)
    {:type :int32}

    (instance? Long value)
    {:type :int64}

    (instance? Float value)
    {:type :float32}

    (instance? Double value)
    {:type :float64}

    (or (instance? BigInteger value)
        (instance? BigInt value)
        (instance? BigDecimal value))
    (schema-required! path :decimal-or-big-integer value)

    (string? value)
    {:type :string}

    (instance? UUID value)
    {:type :uuid}

    (instance? LocalDate value)
    {:type :local-date}

    (instance? LocalTime value)
    {:type :local-time}

    (instance? LocalDateTime value)
    {:type :local-date-time}

    (instance? Instant value)
    {:type :instant :timezone "UTC"}

    (instance? Duration value)
    {:type :duration}

    (map? value)
    (schema-required! path :struct-or-map value)

    (and (sequential? value) (not (string? value)))
    (let [items       (take 200 value)
          descriptors (mapv #(scalar-inference (conj path :item) %) (remove nil? items))]
      (when (empty? descriptors)
        (schema-required! path :empty-or-all-null-list value))
      (when (or (not-every? #(contains? scalar-types (:type %)) descriptors)
                (> (count (distinct descriptors)) 1))
        (schema-required! path :mixed-or-nested-list value))
      {:type    :list
       :element (assoc (first descriptors) :nullable? true)})

    :else
    (schema-required! path :unsupported-value value)))


(defn- infer-field
  [records key]
  (let [values      (keep #(when (contains? % key) (get % key)) records)
        descriptors (mapv #(scalar-inference [key] %) (remove nil? values))]
    (when (empty? descriptors)
      (schema-required! [key] :all-null nil))
    (when (> (count (distinct descriptors)) 1)
      (schema-required! [key] :mixed-values (first (remove nil? values))))
    (merge (first descriptors)
           {:key       key
            :name      (ds.utils/column-safe-name key)
            :nullable? true})))


(defn- ordered-field-keys
  [records]
  (:keys
   (reduce
    (fn [state record]
      (reduce
       (fn [state key]
         (if (contains? (:seen state) key)
           state
           (-> state
               (update :keys conj key)
               (update :seen conj key))))
       state
       (clojure.core/keys record)))
    {:keys [] :seen #{}}
    records)))


(defn infer-schema!
  "Infer only deterministic scalar and homogeneous scalar-list task-result schemas."
  [data]
  (let [records (sample-records data)]
    (when (seq records)
      (when-not (every? map? records)
        (schema-required! [] :non-record-result (first records)))
      (normalize-schema
       {:version schema-version
        :fields  (mapv #(infer-field records %)
                       (ordered-field-keys records))}))))


(defn get-columns
  "Best-effort schema inference for compatibility callers such as JDBC."
  [data]
  (try
    (infer-schema! data)
    (catch Exception _
      nil)))


(defn- field-type
  [nullable? arrow-type metadata]
  (FieldType. (boolean nullable?) ^ArrowType arrow-type nil metadata))


(defn- scalar-arrow-type
  [{:keys [type timezone]}]
  (case type
    :boolean (ArrowType$Bool.)
    :uint8 (ArrowType$Int. 8 false)
    :int8 (ArrowType$Int. 8 true)
    :uint16 (ArrowType$Int. 16 false)
    :int16 (ArrowType$Int. 16 true)
    :uint32 (ArrowType$Int. 32 false)
    :int32 (ArrowType$Int. 32 true)
    :uint64 (ArrowType$Int. 64 false)
    :int64 (ArrowType$Int. 64 true)
    :float32 (ArrowType$FloatingPoint. FloatingPointPrecision/SINGLE)
    :float64 (ArrowType$FloatingPoint. FloatingPointPrecision/DOUBLE)
    :epoch-days (ArrowType$Date. DateUnit/DAY)
    :local-date (ArrowType$Date. DateUnit/DAY)
    :local-time (ArrowType$Time. TimeUnit/MICROSECOND (int 64))
    :local-date-time (ArrowType$Timestamp. TimeUnit/MICROSECOND nil)
    :time-nanoseconds (ArrowType$Time. TimeUnit/NANOSECOND (int 64))
    :time-microseconds (ArrowType$Time. TimeUnit/MICROSECOND (int 64))
    :time-milliseconds (ArrowType$Time. TimeUnit/MILLISECOND (int 32))
    :time-seconds (ArrowType$Time. TimeUnit/SECOND (int 32))
    :duration (ArrowType$Duration. TimeUnit/MICROSECOND)
    :string (ArrowType$Utf8.)
    :uuid (ArrowType$Utf8.)
    :text (ArrowType$Utf8.)
    :encoded-text (ArrowType$Utf8.)
    :instant (ArrowType$Timestamp. TimeUnit/MICROSECOND timezone)
    :epoch-milliseconds (ArrowType$Timestamp. TimeUnit/MILLISECOND timezone)
    :epoch-microseconds (ArrowType$Timestamp. TimeUnit/MICROSECOND timezone)
    :epoch-nanoseconds (ArrowType$Timestamp. TimeUnit/NANOSECOND timezone)))


(defn- decimal-metadata
  [descriptor]
  (when (= :big-integer (:logical-type descriptor))
    {"collet.logical-type" "big-integer"}))


(defn descriptor->field
  ([descriptor]
   (descriptor->field descriptor "item"))
  ([descriptor fallback-name]
   (let [{:keys [type nullable? name] :as descriptor} (normalize-descriptor descriptor)
         field-name (or name fallback-name)]
     (case type
       :struct
       (Field. field-name
               (field-type nullable? ArrowType$Struct/INSTANCE nil)
               (mapv descriptor->field (:fields descriptor)))

       :map
       (Field. field-name
               (field-type nullable? (ArrowType$Map. false) nil)
               [(Field. "entries"
                        (FieldType/notNullable ArrowType$Struct/INSTANCE)
                        [(Field. "key"
                                 (FieldType/notNullable (ArrowType$Utf8.))
                                 nil)
                         (Field. "value"
                                 (FieldType/nullable (ArrowType$Utf8.))
                                 nil)])])

       :list
       (Field. field-name
               (field-type nullable? ArrowType$List/INSTANCE nil)
               [(descriptor->field (:element descriptor) "item")])

       :fixed-size-list
       (Field. field-name
               (field-type nullable?
                           (ArrowType$FixedSizeList. (int (:size descriptor)))
                           nil)
               [(descriptor->field (:element descriptor) "item")])

       :decimal
       (Field. field-name
               (field-type nullable?
                           (ArrowType$Decimal. (int (:precision descriptor))
                                               (int (:scale descriptor))
                                               (int (:bit-width descriptor)))
                           (decimal-metadata descriptor))
               nil)

       (Field. field-name
               (field-type nullable? (scalar-arrow-type descriptor) nil)
               nil)))))


(defn create-zoned-field
  [column-name column-type timezone]
  (descriptor->field {:key      column-name
                      :name     (str column-name)
                      :type     column-type
                      :timezone timezone}))


(defn create-field
  [column-name column-type]
  (descriptor->field
   (legacy-column->field [column-name (str column-name) column-type])))


(defn create-schema
  "Create an authoritative Arrow Schema from canonical or legacy columns."
  [columns]
  (let [schema (normalize-schema columns)]
    (Schema. (mapv descriptor->field (:fields schema)))))


(defn local-time->millis
  ^Integer [^LocalTime time]
  (let [seconds (.toSecondOfDay time)
        nanos   (.getNano time)]
    (int (+ (* seconds 1000) (quot nanos 1000000)))))


(defn local-time->micros
  ^long [^LocalTime time]
  (let [seconds (.toSecondOfDay time)
        nanos   (.getNano time)]
    (+ (* seconds 1000000) (quot nanos 1000))))


(defn duration->micros
  ^long [^Duration duration]
  (+ (* (.getSeconds duration) 1000000)
     (quot (.getNano duration) 1000)))


(defn instant->micros
  ^long [^Instant instant]
  (+ (* (.getEpochSecond instant) 1000000)
     (quot (.getNano instant) 1000)))


(defn date-time->micros
  ^long [^LocalDateTime date-time]
  (+ (* (.toEpochSecond date-time ZoneOffset/UTC) 1000000)
     (quot (.getNano date-time) 1000)))


(defn instant->nanos
  ^long [^Instant instant]
  (+ (* (.getEpochSecond instant) 1000000000)
     (.getNano instant)))


(defn write-list-item
  "Compatibility writer used by existing callers of the legacy list helper."
  [^UnionListWriter list-writer column-type item]
  (case column-type
    :boolean (.writeBit list-writer (if item 1 0))
    :uint8 (.writeUInt1 list-writer (int item))
    :int8 (.writeTinyInt list-writer (int item))
    :uint16 (.writeUInt2 list-writer (int item))
    :int16 (.writeSmallInt list-writer (int item))
    :uint32 (.writeUInt4 list-writer (int item))
    :int32 (.writeInt list-writer (int item))
    :uint64 (.writeUInt8 list-writer (long item))
    :int64 (.writeBigInt list-writer (long item))
    :float32 (.writeFloat4 list-writer (float item))
    :float64 (.writeFloat8 list-writer (double item))
    :epoch-days (.writeDateDay list-writer (.toEpochDay ^LocalDate item))
    :local-date (.writeDateDay list-writer (.toEpochDay ^LocalDate item))
    :local-time (.writeTimeMicro list-writer (local-time->micros item))
    :local-date-time (.writeTimeStampMicro list-writer (date-time->micros item))
    :time-nanoseconds (.writeTimeNano list-writer (.toNanoOfDay ^LocalTime item))
    :time-microseconds (.writeTimeMicro list-writer (local-time->micros item))
    :time-milliseconds (.writeTimeMilli list-writer (local-time->millis item))
    :time-seconds (.writeTimeSec list-writer (.toSecondOfDay ^LocalTime item))
    :duration (.writeDuration list-writer (duration->micros item))
    (:string :uuid :text :encoded-text) (.writeVarChar list-writer (str item))
    :instant (.writeTimeStampMicro list-writer (instant->micros item))
    :epoch-milliseconds (.writeTimeStampMilli list-writer (.toEpochMilli ^Instant item))
    :epoch-microseconds (.writeTimeStampMicro list-writer (instant->micros item))
    :epoch-nanoseconds (.writeTimeStampNano list-writer (instant->nanos item))
    (schema-error! :unsupported-type
                   (str "Unsupported Arrow list item type " (pr-str column-type) "."))))


(defn- ->big-integer
  [value]
  (cond
    (instance? BigInteger value)
    value

    (instance? BigInt value)
    (.toBigInteger ^BigInt value)

    (or (instance? Byte value)
        (instance? Short value)
        (instance? Integer value)
        (instance? Long value))
    (BigInteger/valueOf (long value))

    :else
    nil))


(defn- integer-bounds
  [type]
  (let [[bits signed?] (case type
                         :uint8 [8 false]
                         :int8 [8 true]
                         :uint16 [16 false]
                         :int16 [16 true]
                         :uint32 [32 false]
                         :int32 [32 true]
                         :uint64 [64 false]
                         :int64 [64 true])]
    (if signed?
      [(.negate (.shiftLeft BigInteger/ONE (dec bits)))
       (.subtract (.shiftLeft BigInteger/ONE (dec bits)) BigInteger/ONE)]
      [BigInteger/ZERO
       (.subtract (.shiftLeft BigInteger/ONE bits) BigInteger/ONE)])))


(defn- checked-integer
  [value type path descriptor]
  (let [integer (->big-integer value)]
    (when-not integer
      (value-error! path descriptor :expected-integer value))
    (let [[minimum maximum] (integer-bounds type)]
      (when (or (neg? (.compareTo ^BigInteger integer minimum))
                (pos? (.compareTo ^BigInteger integer maximum)))
        (value-error! path descriptor :integer-overflow value)))
    integer))


(defn- mark!
  [cursors arrow-vector end]
  (swap! cursors update arrow-vector (fnil max 0) end))


(defn- write-decimal!
  [arrow-vector descriptor index value path]
  (let [logical-type (:logical-type descriptor)
        decimal      (cond
                       (= logical-type :big-integer)
                       (let [integer (or (when (instance? BigInteger value) value)
                                         (when (instance? BigInt value)
                                           (.toBigInteger ^BigInt value)))]
                         (when-not integer
                           (value-error! path descriptor :expected-big-integer value))
                         (BigDecimal. ^BigInteger integer))

                       (instance? BigDecimal value)
                       value

                       :else
                       (value-error! path descriptor :expected-big-decimal value))
        decimal      (try
                       (.setScale ^BigDecimal decimal
                                  (int (:scale descriptor))
                                  RoundingMode/UNNECESSARY)
                       (catch ArithmeticException _
                         (value-error! path descriptor :rounding-required value)))]
    (when (> (.precision ^BigDecimal decimal) (:precision descriptor))
      (value-error! path descriptor :decimal-precision-exceeded value))
    (if (= 128 (:bit-width descriptor))
      (.setSafe ^DecimalVector arrow-vector (int index) ^BigDecimal decimal)
      (.setSafe ^Decimal256Vector arrow-vector (int index) ^BigDecimal decimal))))


(defn- write-scalar!
  [arrow-vector {:keys [type] :as descriptor} index value path]
  (when (instance? Date value)
    (legacy-date-error! path value))
  (case type
    :boolean
    (do
      (when-not (instance? Boolean value)
        (value-error! path descriptor :expected-boolean value))
      (.setSafe ^BitVector arrow-vector (int index) (if value 1 0)))

    :uint8
    (let [value (checked-integer value type path descriptor)]
      (.setSafe ^UInt1Vector arrow-vector (int index) (.intValue ^BigInteger value)))

    :int8
    (let [value (checked-integer value type path descriptor)]
      (.setSafe ^TinyIntVector arrow-vector (int index) (.intValue ^BigInteger value)))

    :uint16
    (let [value (checked-integer value type path descriptor)]
      (.setSafe ^UInt2Vector arrow-vector (int index) (.intValue ^BigInteger value)))

    :int16
    (let [value (checked-integer value type path descriptor)]
      (.setSafe ^SmallIntVector arrow-vector (int index) (.intValue ^BigInteger value)))

    :uint32
    (let [value (checked-integer value type path descriptor)]
      (.setSafe ^UInt4Vector arrow-vector (int index) (.intValue ^BigInteger value)))

    :int32
    (let [value (checked-integer value type path descriptor)]
      (.setSafe ^IntVector arrow-vector (int index) (.intValue ^BigInteger value)))

    :uint64
    (let [value (checked-integer value type path descriptor)]
      (.setSafe ^UInt8Vector arrow-vector (int index) (.longValue ^BigInteger value)))

    :int64
    (let [value (checked-integer value type path descriptor)]
      (.setSafe ^BigIntVector arrow-vector (int index) (.longValue ^BigInteger value)))

    :float32
    (do
      (when-not (number? value)
        (value-error! path descriptor :expected-number value))
      (.setSafe ^Float4Vector arrow-vector (int index) (float value)))

    :float64
    (do
      (when-not (number? value)
        (value-error! path descriptor :expected-number value))
      (.setSafe ^Float8Vector arrow-vector (int index) (double value)))

    (:epoch-days :local-date)
    (do
      (when-not (instance? LocalDate value)
        (value-error! path descriptor :expected-local-date value))
      (.setSafe ^DateDayVector arrow-vector (int index) (.toEpochDay ^LocalDate value)))

    :local-time
    (do
      (when-not (instance? LocalTime value)
        (value-error! path descriptor :expected-local-time value))
      (.setSafe ^TimeMicroVector arrow-vector (int index) (local-time->micros value)))

    :local-date-time
    (do
      (when-not (instance? LocalDateTime value)
        (value-error! path descriptor :expected-local-date-time value))
      (.setSafe ^TimeStampMicroVector arrow-vector (int index) (date-time->micros value)))

    :time-nanoseconds
    (do
      (when-not (instance? LocalTime value)
        (value-error! path descriptor :expected-local-time value))
      (.setSafe ^TimeNanoVector arrow-vector (int index) (.toNanoOfDay ^LocalTime value)))

    :time-microseconds
    (do
      (when-not (instance? LocalTime value)
        (value-error! path descriptor :expected-local-time value))
      (.setSafe ^TimeMicroVector arrow-vector (int index) (local-time->micros value)))

    :time-milliseconds
    (do
      (when-not (instance? LocalTime value)
        (value-error! path descriptor :expected-local-time value))
      (.setSafe ^TimeMilliVector arrow-vector (int index) (local-time->millis value)))

    :time-seconds
    (do
      (when-not (instance? LocalTime value)
        (value-error! path descriptor :expected-local-time value))
      (.setSafe ^TimeSecVector arrow-vector (int index) (.toSecondOfDay ^LocalTime value)))

    :duration
    (do
      (when-not (instance? Duration value)
        (value-error! path descriptor :expected-duration value))
      (.setSafe ^DurationVector arrow-vector (int index) (duration->micros value)))

    (:string :uuid :text :encoded-text)
    (.setSafe ^VarCharVector arrow-vector (int index) (Text. (str value)))

    (:instant :epoch-microseconds)
    (do
      (when-not (instance? Instant value)
        (value-error! path descriptor :expected-instant value))
      (.setSafe ^TimeStampMicroVector arrow-vector (int index) (instant->micros value)))

    :epoch-milliseconds
    (do
      (when-not (instance? Instant value)
        (value-error! path descriptor :expected-instant value))
      (.setSafe ^TimeStampMilliVector arrow-vector (int index) (.toEpochMilli ^Instant value)))

    :epoch-nanoseconds
    (do
      (when-not (instance? Instant value)
        (value-error! path descriptor :expected-instant value))
      (.setSafe ^TimeStampNanoVector arrow-vector (int index) (instant->nanos value)))

    :decimal
    (write-decimal! arrow-vector descriptor index value path)))


(defn- map-contains?
  [value key]
  (or (and (map? value) (contains? value key))
      (and (instance? Map value)
           (.containsKey ^Map value key))))


(defn- field-value
  [value field]
  (let [keys [(:key field) (:name field) (keyword (:name field))]]
    (or (some (fn [key]
                (when (map-contains? value key)
                  [true (get value key)]))
              keys)
        [false nil])))


(defn- known-field-keys
  [fields]
  (set (mapcat (fn [{:keys [key name]}]
                 [key name (keyword name)])
        fields)))


(declare write-value!)


(defn- write-struct!
  [^StructVector arrow-vector descriptor index value path cursors]
  (when-not (map? value)
    (value-error! path descriptor :expected-map value))
  (let [fields  (:fields descriptor)
        unknown (seq (remove (known-field-keys fields) (keys value)))]
    (when unknown
      (value-error! path descriptor :unknown-struct-key (first unknown)))
    (.setIndexDefined arrow-vector (int index))
    (doseq [field fields]
      (let [[_ present-value] (field-value value field)
            child             (.getChild arrow-vector ^String (:name field))]
        (write-value! child field index present-value (conj path (:key field)) cursors)))))


(defn- write-map!
  [^MapVector arrow-vector descriptor index value path cursors]
  (when-not (map? value)
    (value-error! path descriptor :expected-map value))
  (let [entries-vector ^StructVector (.getDataVector arrow-vector)
        key-vector     (.getChild entries-vector "key")
        value-vector   (.getChild entries-vector "value")
        start          (.startNewValue arrow-vector (int index))
        key-desc       (:key-type descriptor)
        value-desc     (:value-type descriptor)
        size           (loop [entry-seq   (seq value)
                              entry-index start
                              size        0]
                         (if-let [[entry-key entry-value] (first entry-seq)]
                           (do
                             (when-not (string? entry-key)
                               (value-error! (conj path entry-key)
                                             key-desc
                                             :map-key-not-string
                                             entry-key))
                             (when (and (some? entry-value)
                                        (not (string? entry-value)))
                               (value-error! (conj path entry-key)
                                             value-desc
                                             :map-value-not-string
                                             entry-value))
                             (.setIndexDefined entries-vector (int entry-index))
                             (write-value! key-vector
                                           key-desc
                                           entry-index
                                           entry-key
                                           (conj path entry-key)
                                           cursors)
                             (write-value! value-vector
                                           value-desc
                                           entry-index
                                           entry-value
                                           (conj path entry-key)
                                           cursors)
                             (mark! cursors entries-vector (inc entry-index))
                             (recur (next entry-seq) (inc entry-index) (inc size)))
                           size))]
    (.endValue arrow-vector (int index) size)))


(defn- write-list!
  [^ListVector arrow-vector descriptor index value path cursors]
  (when-not (and (sequential? value) (not (string? value)))
    (value-error! path descriptor :expected-sequential value))
  (let [child (.getDataVector arrow-vector)
        start (.startNewValue arrow-vector (int index))
        size  (loop [items      (seq value)
                     item-index start
                     count      0]
                (if (seq items)
                  (let [item (first items)]
                    (write-value! child
                                  (:element descriptor)
                                  item-index
                                  item
                                  (conj path count)
                                  cursors)
                    (recur (next items) (inc item-index) (inc count)))
                  count))]
    (.endValue arrow-vector (int index) size)))


(defn- fixed-items
  [value size path descriptor]
  (when-not (and (sequential? value) (not (string? value)))
    (value-error! path descriptor :expected-sequential value))
  (let [items (vec (take (inc size) value))]
    (when-not (= size (count items))
      (value-error! path descriptor :wrong-fixed-size value))
    items))


(defn- write-fixed-size-list!
  [^FixedSizeListVector arrow-vector descriptor index value path cursors]
  (let [size  (:size descriptor)
        items (fixed-items value size path descriptor)
        child (.getDataVector arrow-vector)
        start (* index size)]
    (.setNotNull arrow-vector (int index))
    (doseq [[item-index item] (map-indexed clojure.core/vector items)]
      (write-value! child
                    (:element descriptor)
                    (+ start item-index)
                    item
                    (conj path item-index)
                    cursors))))


(defn- write-value!
  [^FieldVector arrow-vector descriptor index value path cursors]
  (if (nil? value)
    (if (:nullable? descriptor)
      (do
        (.setNull arrow-vector (int index))
        (mark! cursors arrow-vector (inc index)))
      (value-error! path descriptor :non-nullable nil))
    (do
      (case (:type descriptor)
        :struct (write-struct! ^StructVector arrow-vector descriptor index value path cursors)
        :map (write-map! ^MapVector arrow-vector descriptor index value path cursors)
        :list (write-list! ^ListVector arrow-vector descriptor index value path cursors)
        :fixed-size-list (write-fixed-size-list! ^FixedSizeListVector arrow-vector descriptor index value path cursors)
        (write-scalar! arrow-vector descriptor index value path))
      (mark! cursors arrow-vector (inc index)))))


(declare finish-vector!)


(defn- finish-map-vector!
  [^MapVector arrow-vector descriptor count cursors]
  (let [entries      ^StructVector (.getDataVector arrow-vector)
        key-vector   (.getChild entries "key")
        value-vector (.getChild entries "value")
        entry-count  (get @cursors entries 0)]
    (finish-vector! key-vector (:key-type descriptor) entry-count cursors)
    (finish-vector! value-vector (:value-type descriptor) entry-count cursors)
    (.setValueCount entries entry-count)
    (.setValueCount arrow-vector (int count))))


(defn- finish-vector!
  [^FieldVector arrow-vector descriptor count cursors]
  (case (:type descriptor)
    :struct
    (do
      (doseq [field (:fields descriptor)]
        (finish-vector! (.getChild ^StructVector arrow-vector ^String (:name field))
                        field
                        count
                        cursors))
      (.setValueCount arrow-vector (int count)))

    :map
    (finish-map-vector! ^MapVector arrow-vector descriptor count cursors)

    :list
    (do
      (finish-vector! (.getDataVector ^ListVector arrow-vector)
                      (:element descriptor)
                      (get @cursors (.getDataVector ^ListVector arrow-vector) 0)
                      cursors)
      (.setValueCount arrow-vector (int count)))

    :fixed-size-list
    (do
      (finish-vector! (.getDataVector ^FixedSizeListVector arrow-vector)
                      (:element descriptor)
                      (* count (:size descriptor))
                      cursors)
      (.setValueCount arrow-vector (int count)))

    (.setValueCount arrow-vector (int count))))


(defn- allocate-vector!
  [^FieldVector arrow-vector batch-size]
  (.setInitialCapacity arrow-vector (int batch-size))
  (.allocateNew arrow-vector))


(defn set-column-vector
  "Write one canonical or legacy column into its Arrow field vector."
  [{:keys [^VectorSchemaRoot schema-root column-name column-type column batch-size]}]
  (let [descriptor   (if (and (map? column-type)
                              (contains? column-type :key)
                              (contains? column-type :name))
                       (normalize-field column-type)
                       (legacy-column->field [(keyword column-name) column-name column-type]))
        arrow-vector (.getVector schema-root ^String column-name)
        cursors      (atom {})]
    (allocate-vector! arrow-vector batch-size)
    (doseq [[index value] (map-indexed clojure.core/vector column)]
      (write-value! arrow-vector descriptor index value [index (:key descriptor)] cursors))
    (finish-vector! arrow-vector descriptor batch-size cursors)))


(defn get-batch-size
  [batch]
  (if (ds/dataset? batch)
    (ds/row-count batch)
    (count batch)))


(defn- record-field-value
  [record field]
  (let [[present? value] (field-value record field)]
    (when present?
      value)))


(declare prep-value)


(defn set-vectors-data
  "Write all fields in a record batch using its authoritative schema."
  [^VectorSchemaRoot schema-root columns batch]
  (let [schema     (normalize-schema columns)
        batch-size (get-batch-size batch)
        dataset?   (ds/dataset? batch)]
    (doseq [field (:fields schema)]
      (let [column (if dataset?
                     (map #(prep-value % field)
                          (or (get batch (:key field))
                              (get batch (keyword (:name field)))
                              (repeat batch-size nil)))
                     (map #(record-field-value % field) batch))]
        (set-column-vector
         {:schema-root schema-root
          :column-name (:name field)
          :column-type field
          :column      column
          :batch-size  batch-size})))))


(defprotocol PWriter
  (write [this batch]))


(defn make-writer
  "Create an Arrow IPC writer for canonical or legacy columns."
  [file-or-path columns]
  (let [schema        (normalize-schema columns)
        allocator     (RootAllocator.)
        arrow-schema  (create-schema schema)
        schema-root   (VectorSchemaRoot/create arrow-schema allocator)
        output-stream (if (instance? File file-or-path)
                        (FileOutputStream. ^File file-or-path)
                        (FileOutputStream. (str file-or-path)))
        writer        (ArrowFileWriter. schema-root nil (.getChannel output-stream))]
    (.start writer)
    (reify
     PWriter
     (write [_ batch]
       (let [batch-size (get-batch-size batch)]
         (when (pos? batch-size)
           (try
             (set-vectors-data schema-root schema batch)
             (.setRowCount schema-root batch-size)
             (.writeBatch writer)
             (catch ExceptionInfo error
               (if (:collet.error/type (ex-data error))
                 (throw error)
                 (throw
                  (ex-info "Error writing Arrow file."
                           {:file   file-or-path
                            :schema schema}
                           error))))
             (catch Exception error
               (throw
                (ex-info "Error writing Arrow file."
                         {:file   file-or-path
                          :schema schema}
                         error)))))))

     Closeable
     (close [_]
       (.end writer)
       (.close writer)
       (.close output-stream)
       (.close schema-root)
       (.close allocator)))))


(defn- file-path
  [file-or-path]
  (cond
    (instance? File file-or-path)
    (.toPath ^File file-or-path)

    (instance? Path file-or-path)
    file-or-path

    :else
    (Paths/get (str file-or-path) (make-array String 0))))


(defn- read-channel
  [path]
  (FileChannel/open ^Path path
                    (into-array OpenOption [StandardOpenOption/READ])))


(defn- validate-file-schema!
  [path expected-schema]
  (with-open [allocator (RootAllocator.)
              channel   (read-channel path)
              reader    (ArrowFileReader. ^SeekableByteChannel channel allocator)]
    (let [actual-schema (.getSchema (.getVectorSchemaRoot reader))]
      (when-not (= expected-schema actual-schema)
        (arrow-error!
         :collet.error/arrow-schema-mismatch
         "Arrow IPC schema differs from the declared schema."
         {:expected (str expected-schema)
          :actual   (str actual-schema)})))))


(defn- object-column-schema?
  [descriptor]
  (case (:type descriptor)
    :struct true
    :map true
    :fixed-size-list true
    :decimal true
    :list true
    false))


(defn- nested-schema?
  [schema]
  (boolean (some object-column-schema? (:fields schema))))


(defn micros->duration
  [micros]
  (Duration/ofNanos (* (long micros) 1000)))


(defn- micros->instant
  [micros]
  (Instant/ofEpochSecond (quot (long micros) 1000000)
                         (* (rem (long micros) 1000000) 1000)))


(defn- raw-map-value
  [value field]
  (second (field-value value field)))


(defn- unsigned-value
  [type value]
  (let [value (long value)]
    (case type
      :uint8 (bit-and value 0xff)
      :uint16 (bit-and value 0xffff)
      :uint32 (Integer/toUnsignedLong (int value))
      :uint64 (let [integer (BigInteger/valueOf value)]
                (if (neg? value)
                  (.add integer (.shiftLeft BigInteger/ONE 64))
                  integer)))))


(declare logical-value)


(defn- logical-scalar
  [descriptor value]
  (let [type (:type descriptor)]
    (cond
      (nil? value)
      nil

      (= :uuid type)
      (if (instance? UUID value)
        value
        (parse-uuid (str value)))

      (contains? varchar-types type)
      (str value)

      (contains? #{:uint8 :uint16 :uint32 :uint64} type)
      (unsigned-value type value)

      (= :epoch-days type)
      (if (instance? LocalDate value)
        value
        (LocalDate/ofEpochDay (long value)))

      (= :local-date type)
      (if (instance? LocalDate value)
        value
        (LocalDate/ofEpochDay (long value)))

      (= :local-time type)
      (if (instance? LocalTime value)
        value
        (LocalTime/ofNanoOfDay (* (long value) 1000)))

      (= :time-nanoseconds type)
      (if (instance? LocalTime value)
        value
        (LocalTime/ofNanoOfDay (long value)))

      (= :time-microseconds type)
      (if (instance? LocalTime value)
        value
        (LocalTime/ofNanoOfDay (* (long value) 1000)))

      (= :time-milliseconds type)
      (if (instance? LocalTime value)
        value
        (LocalTime/ofNanoOfDay (* (long value) 1000000)))

      (= :time-seconds type)
      (if (instance? LocalTime value)
        value
        (LocalTime/ofSecondOfDay (long value)))

      (= :duration type)
      (if (instance? Duration value)
        value
        (micros->duration value))

      (= :local-date-time type)
      (cond
        (instance? LocalDateTime value)
        value

        (instance? Instant value)
        (LocalDateTime/ofInstant ^Instant value ZoneOffset/UTC)

        :else
        (LocalDateTime/ofInstant (micros->instant value) ZoneOffset/UTC))

      (= :instant type)
      (cond
        (instance? Instant value)
        value

        (instance? LocalDateTime value)
        (.toInstant ^LocalDateTime value ZoneOffset/UTC)

        :else
        (micros->instant value))

      (= :epoch-milliseconds type)
      (if (instance? Instant value)
        value
        (Instant/ofEpochMilli (long value)))

      (= :epoch-microseconds type)
      (if (instance? Instant value)
        value
        (micros->instant value))

      (= :epoch-nanoseconds type)
      (if (instance? Instant value)
        value
        (Instant/ofEpochSecond (quot (long value) 1000000000)
                               (rem (long value) 1000000000)))

      :else
      value)))


(defn logical-value
  "Convert an Arrow vector object into the declared logical Clojure value."
  [descriptor value]
  (let [descriptor (normalize-descriptor descriptor)]
    (if (nil? value)
      nil
      (case (:type descriptor)
        :struct
        (reduce
         (fn [result field]
           (assoc result
             (:key field)
             (logical-value field (raw-map-value value field))))
         {}
         (:fields descriptor))

        :map
        (into {}
              (map (fn [entry]
                     [(logical-value (:key-type descriptor)
                                     (raw-map-value entry {:key "key" :name "key"}))
                      (logical-value (:value-type descriptor)
                                     (raw-map-value entry {:key "value" :name "value"}))]))
              value)

        :list
        (mapv #(logical-value (:element descriptor) %) value)

        :fixed-size-list
        (mapv #(logical-value (:element descriptor) %) value)

        :decimal
        (let [decimal (if (instance? BigDecimal value)
                        value
                        (BigDecimal. (str value)))]
          (if (= :big-integer (:logical-type descriptor))
            (.toBigIntegerExact decimal)
            decimal))

        (logical-scalar descriptor value)))))


(defn- root->records
  [^VectorSchemaRoot root schema]
  (let [fields (:fields schema)
        rows   (mapv
                (fn [index]
                  (reduce
                   (fn [record field]
                     (let [arrow-vector (.getVector root ^String (:name field))]
                       (assoc record
                         (:key field)
                         (logical-value
                          field
                          (case (:type field)
                            :time-nanoseconds
                            (if (instance? TimeNanoVector arrow-vector)
                              (when-not (.isNull arrow-vector (int index))
                                (.get ^TimeNanoVector arrow-vector (int index)))
                              (.getObject arrow-vector index))

                            :epoch-nanoseconds
                            (if (instance? TimeStampNanoVector arrow-vector)
                              (when-not (.isNull arrow-vector (int index))
                                (.get ^TimeStampNanoVector arrow-vector (int index)))
                              (.getObject arrow-vector index))

                            :duration
                            (if (instance? DurationVector arrow-vector)
                              (let [holder (NullableDurationHolder.)]
                                (.get ^DurationVector arrow-vector (int index) holder)
                                (when (= 1 (.-isSet holder))
                                  (.-value holder)))
                              (.getObject arrow-vector index))

                            (.getObject arrow-vector index))))))
                   {}
                   fields))
                (range (.getRowCount root)))]
    rows))


(defn- root->dataset
  [^VectorSchemaRoot root schema]
  (let [precision-sensitive-columns
        (into {}
              (keep (fn [field]
                      ;; TMD's packed temporal columns have microsecond precision.
                      (when (contains? #{:time-nanoseconds :epoch-nanoseconds}
                                       (:type field))
                        [(:key field) :object])))
              (:fields schema))
        dataset
        (if (seq precision-sensitive-columns)
          (ds/->dataset (root->records root schema)
                        {:parser-fn precision-sensitive-columns})
          (ds/->dataset (root->records root schema)))]
    (with-meta dataset {:arrow-columns schema})))


(defn- close-quietly!
  [closeable]
  (try
    (.close ^AutoCloseable closeable)
    (catch Exception _
      nil)))


(defn- reader-batch-seq
  "Lazily load and detach one Arrow record batch per sequence element."
  [^ArrowReader reader close! root->batch]
  (let [closed?       (atom false)
        close-reader! (fn []
                        (when (compare-and-set! closed? false true)
                          (close!)))
        step          (fn step []
                        (lazy-seq
                         (try
                           (if (.loadNextBatch reader)
                             (cons (root->batch (.getVectorSchemaRoot reader))
                                   (step))
                             (do
                               (close-reader!)
                               nil))
                           (catch Throwable error
                             (close-reader!)
                             (throw error)))))]
    (resource/track
     (step)
     {:track-type :gc
      :dispose-fn close-reader!})))


(defn- nested-dataset-seq
  [path schema]
  (let [allocator (RootAllocator.)
        channel   (read-channel path)
        reader    (ArrowFileReader. ^SeekableByteChannel channel allocator)]
    (reader-batch-seq
     reader
     #(do
        (close-quietly! reader)
        (close-quietly! channel)
        (close-quietly! allocator))
     #(root->dataset % schema))))


(defn reader->dataset-seq
  "Read bounded Arrow batches from an open reader using a declared logical schema.

  The caller owns the reader resources and supplies their close function.
  This is used by format adapters whose physical Arrow schema differs from Collet's
  logical schema, such as Parquet fixed-size embeddings."
  [^ArrowReader reader columns close!]
  (let [schema (normalize-schema columns)]
    (with-meta
      (reader-batch-seq reader close! #(root->dataset % schema))
      {:ds-seq        true
       :arrow-columns schema})))


(defn reader->record-batches
  "Read bounded batches as logical Clojure records without dataset coercion.

  This preserves values such as nanosecond time and instant fields while a
  format adapter rewrites their physical representation."
  [^ArrowReader reader columns close!]
  (let [schema (normalize-schema columns)]
    (reader-batch-seq reader close! #(root->records % schema))))


(defn read-arrow-record-batches
  "Read bounded IPC record batches through the logical Arrow schema adapter."
  [file-or-path columns]
  (let [schema       (normalize-schema columns)
        arrow-schema (create-schema schema)
        path         (file-path file-or-path)]
    (validate-file-schema! path arrow-schema)
    (let [allocator (RootAllocator.)
          channel   (read-channel path)
          reader    (ArrowFileReader. ^SeekableByteChannel channel allocator)]
      (reader->record-batches
       reader
       schema
       #(do
          (close-quietly! reader)
          (close-quietly! channel)
          (close-quietly! allocator))))))


(defn read-dataset
  "Read an IPC file as TMD datasets with canonical Arrow schema metadata."
  [file-or-path columns]
  (let [schema       (normalize-schema columns)
        arrow-schema (create-schema schema)
        path         (file-path file-or-path)]
    (validate-file-schema! path arrow-schema)
    (with-meta
      (if (nested-schema? schema)
        (nested-dataset-seq path schema)
        (arrow/stream->dataset-seq path {:open-type :mmap :key-fn keyword}))
      {:ds-seq        true
       :arrow-columns schema})))


(defn- logical-nested-value?
  [field value]
  (case (:type field)
    :map
    (map? value)

    :struct
    (and (map? value)
         (some #(map-contains? value (:key %)) (:fields field)))

    :list
    (let [element (:element field)]
      (and (sequential? value)
           (some #(logical-nested-value? element %) (remove nil? value))))

    :fixed-size-list
    (and (sequential? value)
         (some? value))

    false))


(defn prep-value
  [value column]
  (let [field (if (and (vector? column) (= 3 (count column)))
                (legacy-column->field column)
                (normalize-descriptor column))
        value (if (logical-nested-value? field value)
                value
                (logical-value field value))]
    (if (and (= :uuid (:type field))
             (some? value)
             (not (instance? UUID value)))
      (parse-uuid (str value))
      value)))


(defn prep-record
  "Restore logical values and top-level nil keys from canonical Arrow metadata."
  [record columns]
  (reduce
   (fn [result field]
     (let [physical-key     (keyword (:name field))
           target-key       (:key field)
           [present? value] (if (contains? result target-key)
                              [true (get result target-key)]
                              (if (contains? result physical-key)
                                [true (get result physical-key)]
                                [false nil]))
           result           (assoc result
                              target-key
                              (if present?
                                (prep-value value field)
                                nil))]
       (if (= target-key physical-key)
         result
         (dissoc result physical-key))))
   record
   (schema-fields columns)))
