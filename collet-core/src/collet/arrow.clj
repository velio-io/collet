(ns collet.arrow
  (:require
   [clojure.core.protocols :as clj-proto]
   [tech.v3.dataset :as ds]
   [tech.v3.dataset.utils :as ds.utils]
   [tech.v3.datatype :as dtype]
   [tech.v3.datatype.packing :as packing]
   [tech.v3.datatype.casting :as casting]
   [tech.v3.libs.arrow :as arrow]
   [collet.utils :as utils])
  (:import
   [java.nio.charset StandardCharsets]
   [java.time Duration Instant LocalDate LocalDateTime LocalTime ZoneOffset]
   [org.apache.arrow.memory RootAllocator]
   [org.apache.arrow.vector.complex ListVector]
   [org.apache.arrow.vector.complex.impl UnionListWriter]
   [org.apache.arrow.vector.types TimeUnit DateUnit FloatingPointPrecision]
   [org.apache.arrow.vector
    BaseFixedWidthVector BaseVariableWidthVector BigIntVector BitVector DateDayVector DurationVector Float4Vector Float8Vector IntVector
    TimeMicroVector TimeMilliVector TimeNanoVector TimeSecVector TimeStampMicroVector TimeStampMilliVector
    TimeStampNanoVector VarCharVector VectorSchemaRoot]
   [org.apache.arrow.vector.types.pojo
    ArrowType$Bool ArrowType$Date ArrowType$Duration ArrowType$List ArrowType$Time ArrowType$Timestamp
    Field FieldType ArrowType ArrowType$FloatingPoint ArrowType$Int ArrowType$Utf8 Schema]
   [org.apache.arrow.vector.ipc ArrowFileWriter]
   [java.io Closeable File FileOutputStream]
   [org.apache.arrow.vector.util Text]))


(def zoned-types
  #{:instant :epoch-milliseconds :epoch-microseconds :epoch-nanoseconds})


(casting/alias-datatype! :duration :int64)


(extend-protocol clj-proto/Datafiable
  ArrowType$Duration
  (datafy [this]
    {:datatype :duration}))


(def column-name
  [:or :string :keyword])

(def column-safe-name
  :string)

(def column-type
  [:or :keyword
   [:tuple [:= :list] :keyword]
   [:tuple [:= :zoned] :keyword [:maybe :string]]])

(def columns-spec
  [:vector [:tuple column-name column-safe-name column-type]])


(defn ds->columns
  "Infer a list of columns (simple representation of Arrow fields) from a dataset sample."
  {:malli/schema [:=> [:cat utils/dataset?]
                  columns-spec]}
  [dataset]
  (->> dataset
       (mapv (fn [[_ column]]
               (let [{:keys [name datatype timezone]} (-> column meta)
                     datatype    (packing/unpack-datatype datatype)
                     column-name (ds.utils/column-safe-name name)
                     column-type (cond
                                   ;; not supported types
                                   (or (= datatype :persistent-map) (= datatype :persistent-set))
                                   (throw (ex-info "Complex objects aren't supported" {:column name}))
                                   ;; list of items
                                   (= datatype :persistent-vector)
                                   (let [list-type (->> column
                                                        (mapcat #(map dtype/elemwise-datatype %))
                                                        (set))
                                         list-type (if (> (count list-type) 1)
                                                     :string
                                                     (-> (first list-type)
                                                         (packing/unpack-datatype)))]
                                     [:list list-type])
                                   ;; temporal types with timezone
                                   (contains? zoned-types datatype)
                                   [:zoned datatype timezone]
                                   ;; other types
                                   :otherwise
                                   datatype)]
                 [name column-name column-type])))))

;; TODO empty sequences brake the schema inference
(defn get-columns
  "Get a list of columns from a dataset. If nil returned it means that dataset cannot be written as Arrow file."
  [data]
  (if (empty? data)
    nil
    (try
      (let [ds-sample (if (ds/dataset? data)
                        (ds/sample data (min 200 (ds/row-count data)))
                        (take 200 (shuffle data)))]
        (-> (utils/make-dataset ds-sample {})
            (ds->columns)))
      (catch Exception _ex
        nil))))


(defn find-column
  [columns column-name]
  (some
   (fn [[key name _ :as column]]
     (when (or (= key column-name) (= name column-name))
       column))
   columns))


(defn create-zoned-field
  [column-name column-type ^String timezone]
  (let [timezone   (or timezone "UTC")
        field-type ^ArrowType
                   (case column-type
                     :instant (ArrowType$Timestamp. TimeUnit/MICROSECOND timezone)
                     :epoch-milliseconds (ArrowType$Timestamp. TimeUnit/MILLISECOND timezone)
                     :epoch-microseconds (ArrowType$Timestamp. TimeUnit/MICROSECOND timezone)
                     :epoch-nanoseconds (ArrowType$Timestamp. TimeUnit/NANOSECOND timezone))]
    (Field. (name column-name) (FieldType/nullable field-type) nil)))


(defn create-field
  [column-name column-type]
  (let [field-type ^ArrowType
                   (case column-type
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
                     :encoded-text (ArrowType$Utf8.))]
    (Field. (name column-name) (FieldType/nullable field-type) nil)))


(defn create-schema
  [columns]
  "Create an Arrow schema based on the column definitions."
  (Schema. ^Iterable
           (->> columns
                (map (fn [[_column-key column-name column-type]]
                       (cond
                         ;; list of items
                         (and (vector? column-type) (= :list (first column-type)))
                         (Field. column-name
                                 (FieldType/nullable ArrowType$List/INSTANCE)
                                 [(create-field (str column-name "_item") (second column-type))])
                         ;; temporal types with timezone
                         (and (vector? column-type) (= :zoned (first column-type)))
                         (apply create-zoned-field column-name (rest column-type))
                         ;; other types
                         :otherwise
                         (create-field column-name column-type)))))))


(defn local-time->millis ^Integer
  [^LocalTime t]
  (let [seconds (.toSecondOfDay t)
        nanos   (.getNano t)
        millis  (+ (* seconds 1000) (quot nanos 1000000))]
    (int millis)))


(defn local-time->micros ^long
  [^LocalTime t]
  (let [seconds (.toSecondOfDay t)
        nanos   (.getNano t)
        micros  (+ (* seconds 1000000) (long (/ nanos 1000)))]
    micros))


(defn duration->micros ^long
  [^Duration d]
  (let [seconds (.getSeconds d)
        nanos   (.getNano d)]
    (+ (* seconds 1000000) (quot nanos 1000))))


(defn instant->micros ^long
  [^Instant inst]
  (let [seconds (.getEpochSecond inst)
        nanos   (.getNano inst)]
    (+ (* seconds 1000000) (quot nanos 1000))))


(defn date-time->micros ^long
  [^LocalDateTime date-time]
  (let [seconds (.toEpochSecond date-time ZoneOffset/UTC)
        nanos   (.getNano date-time)]
    (+ (* seconds 1000000) (quot nanos 1000))))


(defn instant->nanos ^long
  [^Instant inst]
  (let [seconds (.getEpochSecond inst)
        nanos   (.getNano inst)]
    (+ (* seconds 1000000000) nanos)))


(def varchar-types
  #{:string :uuid :text :encoded-text})


(defn write-list-item
  [^UnionListWriter list-writer column-type item]
  (cond
    (= column-type :boolean) (.writeBit list-writer (if item 1 0))
    (= column-type :uint8) (.writeUInt1 list-writer (byte item))
    (= column-type :int8) (.writeTinyInt list-writer (byte item))
    (= column-type :uint16) (.writeUInt2 list-writer (short item))
    (= column-type :int16) (.writeSmallInt list-writer (short item))
    (= column-type :uint32) (.writeUInt4 list-writer (int item))
    (= column-type :int32) (.writeInt list-writer (int item))
    (= column-type :uint64) (.writeUInt8 list-writer (long item))
    (= column-type :int64) (.writeBigInt list-writer (long item))
    (= column-type :float32) (.writeFloat4 list-writer (float item))
    (= column-type :float64) (.writeFloat8 list-writer (double item))
    (= column-type :epoch-days) (.writeDateDay list-writer (.toEpochDay ^LocalDate item))
    (= column-type :local-date) (.writeDateDay list-writer (.toEpochDay ^LocalDate item))
    (= column-type :local-time) (.writeTimeMicro list-writer (local-time->micros item))
    (= column-type :local-date-time) (.writeTimeMicro list-writer (date-time->micros item))
    (= column-type :time-nanoseconds) (.writeTimeNano list-writer (.toNanoOfDay ^LocalTime item))
    (= column-type :time-microseconds) (.writeTimeMicro list-writer (local-time->micros item))
    (= column-type :time-milliseconds) (.writeTimeMilli list-writer (local-time->millis item))
    (= column-type :time-seconds) (.writeTimeSec list-writer (.toSecondOfDay ^LocalTime item))
    (= column-type :duration) (.writeDuration list-writer (duration->micros item))
    (contains? varchar-types column-type) (.writeVarChar list-writer (str item))
    (= column-type :instant) (.writeTimeStampMicro list-writer (instant->micros item))
    (= column-type :epoch-milliseconds) (.writeTimeStampMilli list-writer (.toEpochMilli ^Instant item))
    (= column-type :epoch-microseconds) (.writeTimeStampMicro list-writer (instant->micros item))
    (= column-type :epoch-nanoseconds) (.writeTimeStampNano list-writer (instant->nanos item))
    :otherwise (throw (ex-info "Unsupported column type" {:column-type column-type}))))


(defn set-column-vector
  "Write data to a single column vector of specified type."
  [{:keys [^VectorSchemaRoot schema-root ^String column-name column-type column batch-size]}]
  (cond
    (and (vector? column-type) (= :list (first column-type)))
    (let [vector      ^ListVector (.getVector schema-root column-name)
          list-writer ^UnionListWriter (.getWriter vector)]
      (doall
       (map-indexed
        (fn [idx list]
          (.setPosition list-writer idx)
          (if (or (nil? list) (empty? list))
            (.writeNull list-writer)
            (do (.startList list-writer)
                (doseq [item list]
                  (write-list-item list-writer (second column-type) item))
                (.setValueCount list-writer (count list))
                (.endList list-writer))))
        column))
      (.setValueCount list-writer batch-size))

    (and (vector? column-type) (= :zoned (first column-type)))
    (let [vector (.getVector schema-root column-name)]
      (.allocateNew ^BaseFixedWidthVector vector batch-size)
      (doall
       (map-indexed
        (fn [^long idx value]
          (case (second column-type)
            (:instant :epoch-microseconds)
            (.set ^TimeStampMicroVector vector idx (instant->micros value))
            :epoch-milliseconds
            (.set ^TimeStampMilliVector vector idx (.toEpochMilli ^Instant value))
            :epoch-nanoseconds
            (.set ^TimeStampNanoVector vector idx (instant->nanos value))
            ;; default case if no match
            (throw (ex-info "Unsupported column type" {:column-type column-type}))))
        column)))

    :otherwise
    (let [vector (.getVector schema-root column-name)]
      (if (contains? varchar-types column-type)
        (.allocateNew ^BaseVariableWidthVector vector batch-size)
        (.allocateNew ^BaseFixedWidthVector vector batch-size))
      (doall
       (map-indexed
        (fn [^long idx value]
          (case column-type
            :boolean (.set ^BitVector vector idx (if value 1 0))
            (:uint8 :int8) (.setSafe ^IntVector vector idx (int value))
            (:uint16 :int16) (.setSafe ^IntVector vector idx (int value))
            (:uint32 :int32) (.setSafe ^IntVector vector idx (int value))
            (:uint64 :int64) (.set ^BigIntVector vector idx (long value))
            :float32 (.set ^Float4Vector vector idx (float value))
            :float64 (.set ^Float8Vector vector idx (double value))
            (:epoch-days :local-date) (.set ^DateDayVector vector idx (.toEpochDay ^LocalDate value))
            :local-time (.set ^TimeMicroVector vector idx (local-time->micros value))
            :local-date-time (.set ^TimeStampMicroVector vector idx (date-time->micros value))
            :time-nanoseconds (.set ^TimeNanoVector vector idx (.toNanoOfDay ^LocalTime value))
            :time-microseconds (.set ^TimeMicroVector vector idx (local-time->micros value))
            :time-milliseconds (.set ^TimeMilliVector vector idx (local-time->millis value))
            :time-seconds (.set ^TimeSecVector vector idx (.toSecondOfDay ^LocalTime value))
            :duration (.set ^DurationVector vector idx (duration->micros value))
            (:string :uuid :text :encoded-text)
            (let [bytes (.getBytes (str value) StandardCharsets/UTF_8)]
              (.setSafe ^VarCharVector vector idx (Text. (str value))))
            ;; default case if no match
            (throw (ex-info "Unsupported column type" {:column-type column-type}))))
        column)))))


(defn get-batch-size
  [batch]
  (if (ds/dataset? batch)
    (ds/row-count batch)
    (count batch)))


(defn set-vectors-data
  "Write data to all columns of the schema."
  [^VectorSchemaRoot schema-root columns batch]
  (let [batch-size (get-batch-size batch)
        ds?        (ds/dataset? batch)]
    (doseq [[column-key column-name column-type] columns]
      (set-column-vector
       {:schema-root schema-root
        :column-name column-name
        :column-type column-type
        :column      (if ds?
                       (get batch column-key)
                       (map #(get % column-key) batch))
        :batch-size  batch-size}))))


(defprotocol PWriter
  (write [this batch]))


(defn ^Closeable make-writer
  "Create an Arrow file writer. This function returns a writer object with `write` and `close` methods.
   Write methods accepts a batch of data to write to the Arrow file."
  [file-or-path columns]
  (let [allocator     (RootAllocator.)
        schema        (create-schema columns)
        schema-root   (VectorSchemaRoot/create schema allocator)
        output-stream (if (instance? File file-or-path)
                        (FileOutputStream. ^File file-or-path)
                        (FileOutputStream. ^String file-or-path))
        writer        (ArrowFileWriter. schema-root nil (.getChannel output-stream))]
    (.start writer)

    (reify
      PWriter
      (write [this batch]
        (let [batch-size (get-batch-size batch)]
          (when (> batch-size 0)
            (try
              (set-vectors-data schema-root columns batch)
              (.setRowCount schema-root batch-size)
              (.writeBatch writer)
              (catch Exception e
                (throw (ex-info "Error writing Arrow file"
                                {:file    file-or-path
                                 :columns columns
                                 :batch   batch}
                                e)))))))
      Closeable
      (close [this]
        (.end writer)
        (.close writer)
        (.close output-stream)
        (.close schema-root)
        (.close allocator)))))


(defn vec-or-nil [x]
  (when (some? x)
    (vec x)))


(defn instant->local-date-time
  [^Instant x]
  (LocalDateTime/ofInstant x ZoneOffset/UTC))


(defn read-dataset
  "Read an Arrow file and return a dataset.
   If the file contains multiple datasets, they will be concatenated."
  [file-or-path columns]
  (let [path (if (instance? File file-or-path)
               (.toPath ^File file-or-path)
               file-or-path)]
    (with-meta (arrow/stream->dataset-seq path {:open-type :mmap :key-fn keyword})
               {:ds-seq        true
                :arrow-columns columns})))


(defn prep-value
  [value [_column-key _column-name column-type]]
  (cond (= column-type :string)
        (str value)

        (= column-type :local-date-time)
        (instant->local-date-time value)

        (= column-type :uuid)
        (-> value str parse-uuid)

        (and (vector? column-type) (= :list (first column-type)))
        (vec-or-nil value)

        :otherwise value))


(defn prep-record
  [record columns]
  (reduce
   (fn [r [_column-key column-name _column-type :as column]]
     (let [column-key (keyword column-name)]
       (update r column-key prep-value column)))
   record
   columns))