(ns collet.arrow
  (:require
   [tech.v3.dataset :as ds]
   [tech.v3.dataset.utils :as ml-utils]
   [tech.v3.datatype :as dtype]
   [tech.v3.datatype.packing :as packing]
   [tech.v3.libs.arrow :as arrow]
   [collet.utils :as utils])
  (:import
   [java.nio.charset StandardCharsets]
   [org.apache.arrow.memory RootAllocator]
   [org.apache.arrow.vector.complex ListVector]
   [org.apache.arrow.vector.complex.impl UnionListWriter]
   [org.apache.arrow.vector.types TimeUnit DateUnit FloatingPointPrecision]
   [org.apache.arrow.vector Float4Vector IntVector VarCharVector VectorSchemaRoot]
   [org.apache.arrow.vector.types.pojo
    ArrowType$Bool ArrowType$Date ArrowType$Duration ArrowType$List ArrowType$Time ArrowType$Timestamp
    Field FieldType ArrowType ArrowType$FloatingPoint ArrowType$Int ArrowType$Utf8 Schema]
   [org.apache.arrow.vector.ipc ArrowFileWriter]
   [java.io Closeable FileOutputStream]))


(def zoned-types
  #{:instant :epoch-milliseconds :epoch-microseconds :epoch-nanoseconds})


(defn ds->columns
  [dataset]
  (->> dataset
       (mapv (fn [column]
               (let [{:keys [name datatype timezone]} (-> column val meta)
                     column-name (ml-utils/column-safe-name name)
                     column-type (cond
                                   ;; not supported types
                                   (or (= datatype :persistent-map) (= datatype :persistent-set))
                                   (throw (ex-info "Maps and sets currently not supported" {:column column}))
                                   ;; list of items
                                   (= datatype :persistent-vector)
                                   (let [list-type (transduce (map dtype/elemwise-datatype) conj #{} (get dataset name))
                                         list-type (if (> (count list-type) 1)
                                                     :string
                                                     (first list-type))]
                                     [:list list-type])
                                   ;; temporal types with timezone
                                   (contains? zoned-types datatype)
                                   [:zoned datatype timezone]
                                   ;; other types
                                   :otherwise
                                   datatype)]
                 [name column-name column-type])))))


(defn get-columns
  [data]
  (if (empty? data)
    nil
    (try
      (let [ds-sample (if (utils/dataset? data)
                        (ds/sample data (min 200 (ds/row-count data)))
                        (take 200 (shuffle data)))]
        (-> (utils/make-dataset ds-sample {})
            (ds->columns)))
      (catch Exception _
        nil))))


(defn create-zoned-field
  [column-name column-type ^String timezone]
  (let [datatype   (packing/unpack-datatype column-type)
        field-type ^ArrowType
                   (case datatype
                     :instant (ArrowType$Timestamp. TimeUnit/MICROSECOND timezone)
                     :epoch-milliseconds (ArrowType$Timestamp. TimeUnit/MILLISECOND timezone)
                     :epoch-microseconds (ArrowType$Timestamp. TimeUnit/MICROSECOND timezone)
                     :epoch-nanoseconds (ArrowType$Timestamp. TimeUnit/NANOSECOND timezone))]
    (Field. (name column-name) (FieldType/nullable field-type) nil)))


(defn create-field
  [column-name column-type]
  (let [datatype   (packing/unpack-datatype column-type)
        field-type ^ArrowType
                   (case datatype
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


(defn set-column-vector
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
                  ;; TODO: Handle other types
                  (.writeInt list-writer (int item)))
                (.setValueCount list-writer (count list))
                (.endList list-writer))))
        column))
      (.setValueCount list-writer batch-size))

    (= column-type :int)
    (let [vector ^IntVector (.getVector schema-root column-name)]
      (.allocateNew vector batch-size)
      (doall
       (map-indexed
        (fn [^long idx ^long value]
          (.set vector idx value))
        column)))

    (= column-type :float)
    (let [vector ^Float4Vector (.getVector schema-root column-name)]
      (.allocateNew vector batch-size)
      (doall
       (map-indexed
        (fn [^long idx ^double value]
          (.set vector idx value))
        column)))

    (= column-type :string)
    (let [vector ^VarCharVector (.getVector schema-root column-name)]
      (.allocateNew vector batch-size)
      (doall
       (map-indexed
        (fn [^long idx ^String value]
          (.set vector idx (.getBytes (str value) StandardCharsets/UTF_8)))
        column)))))


(defn set-vectors-data
  [^VectorSchemaRoot schema-root columns batch]
  (let [batch-size (count batch)
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


(defn make-writer
  [^String file-or-path columns]
  (let [allocator     (RootAllocator.)
        schema        (create-schema columns)
        schema-root   (VectorSchemaRoot/create schema allocator)
        output-stream (FileOutputStream. file-or-path)
        writer        (ArrowFileWriter. schema-root nil (.getChannel output-stream))]
    (.start writer)

    (reify
      PWriter
      (write [this batch]
        (try
          (set-vectors-data schema-root columns batch)
          (.setRowCount schema-root (count batch))
          (.writeBatch writer)
          (catch Exception e
            (throw (ex-info "Error writing Arrow file"
                            {:file    file-or-path
                             :columns columns
                             :batch   batch}
                            e)))))
      Closeable
      (close [this]
        (.end writer)
        (.close writer)
        (.close output-stream)
        (.close schema-root)
        (.close allocator)))))


(defn read-dataset
  [file-or-path]
  (let [[dataset & rest]
        (arrow/stream->dataset-seq file-or-path {:open-type :mmap})]
    (if (seq rest)
      (apply utils/parallel-concat dataset rest)
      dataset)))



(comment
 (let [columns (get-columns [{:id 1 :name "Alice" :score (float 95.5) :obj [1 2 3]}
                             {:id 2 :name "Bob" :score (float 85.0) :obj [3 4 5]}])]
   (with-open [writer (make-writer "tmp/maps-example.arrow" columns)]
     (write writer [{:id 1 :name "Alice" :score (float 95.5) :obj [1 2 3]}
                    {:id 2 :name "Bob" :score (float 85.0) :obj [3 4 5]}])

     (write writer [{:id 3 :name "Charlie" :score (float 77.3)}
                    {:id 4 :name "Diana" :score (float 89.9) :obj [6 7 8]}])))

 (read-dataset "tmp/maps-example.arrow")

 (def dts
   (ds/->dataset
    [{:id 1 :name "Alice" :score 95.5 :features [1 2 3]}
     {:id 2 :name "Bob" :score 85.0 :features [3 4 5]}]))

 nil)
