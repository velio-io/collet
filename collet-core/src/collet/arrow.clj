(ns collet.arrow
  (:require
   [malli.core :as m]
   [tech.v3.dataset :as ds]
   [tech.v3.dataset.utils :as ml-utils]
   [tech.v3.libs.arrow :as arrow]
   [malli.provider :as mp]
   [collet.utils :as utils])
  (:import
   [java.nio.charset StandardCharsets]
   [org.apache.arrow.memory RootAllocator]
   [org.apache.arrow.vector.complex ListVector]
   [org.apache.arrow.vector.complex.impl UnionListWriter]
   [org.apache.arrow.vector.types FloatingPointPrecision]
   [org.apache.arrow.vector Float4Vector IntVector VarCharVector VectorSchemaRoot]
   [org.apache.arrow.vector.types.pojo ArrowType$List Field FieldType ArrowType ArrowType$FloatingPoint ArrowType$Int ArrowType$Utf8 Schema]
   [org.apache.arrow.vector.ipc ArrowFileWriter]
   [java.io Closeable FileOutputStream]))


(def schema-provider
  (mp/provider))


(def list-types
  #{:list :vector})           ;; TODO check other malli types


(defn data->columns
  [data]
  (let [sample-size (min 200 (count data))
        sample      (take sample-size (shuffle data))
        schema      (schema-provider sample)]
    (->> (m/children schema)
         (mapv (fn [[column-key _ t]]
                 (let [column-name (ml-utils/column-safe-name column-key)
                       ;; TODO need a mapping between malli and TMD types
                       column-type (cond
                                     (and (m/schema? t) (contains? list-types (m/type t)))
                                     [:list (-> t m/children first m/type)]
                                     (= t :some)
                                     :string
                                     :otherwise
                                     (m/type t))]
                   [column-key column-name column-type]))))))


(defn ds->columns
  [dataset]
  (let [ds-sample (ds/sample dataset (min 200 (ds/row-count dataset)))]
    (->> dataset
         (mapv (fn [column]
                 (let [{:keys [name datatype]} (-> column val meta)
                       column-name (ml-utils/column-safe-name name)
                       column-type (if (= datatype :persistent-vector)
                                     (let [list-type (schema-provider (get ds-sample name))]
                                       [:list (-> list-type m/children first m/type)])
                                     datatype)]
                   [name column-name column-type]))))))


(defn get-columns
  [data]
  ;; TODO add more constraints to the data
  ;; only simple values and simple lists should infer columns
  (cond
    (empty? data) nil
    (ds/dataset? data) (ds->columns data)
    :otherwise (data->columns data)))


(defn create-field
  [column-name column-type]
  (let [field-type ^ArrowType
                   (case column-type
                     ;; TODO extend supported types
                     :int (ArrowType$Int. 32 true)
                     :float (ArrowType$FloatingPoint. FloatingPointPrecision/SINGLE)
                     :string (ArrowType$Utf8.))]
    (Field. (name column-name) (FieldType/nullable field-type) nil)))


(defn create-schema
  [columns]
  "Create an Arrow schema based on the column definitions."
  (Schema. ^Iterable
           (->> columns
                (map (fn [[_column-key column-name column-type]]
                       (if (and (vector? column-type) (= :list (first column-type)))
                         (Field. column-name
                                 (FieldType/nullable ArrowType$List/INSTANCE)
                                 [(create-field (str column-name "_item") (second column-type))])
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
