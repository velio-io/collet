(ns collet.arrow
  (:require
   [tech.v3.libs.arrow :as arrow]
   [collet.utils :as utils])
  (:import
   [java.nio.charset StandardCharsets]
   [org.apache.arrow.memory RootAllocator]
   [org.apache.arrow.vector.types FloatingPointPrecision]
   [org.apache.arrow.vector Float4Vector IntVector FieldVector VarCharVector VectorSchemaRoot]
   [org.apache.arrow.vector.types.pojo Field FieldType ArrowType ArrowType$FloatingPoint ArrowType$Int ArrowType$Utf8 Schema]
   [org.apache.arrow.vector.ipc ArrowFileWriter]
   [java.io Closeable FileOutputStream]))


(defn create-schema [columns]
  "Create an Arrow schema based on the column definitions."
  (Schema. ^Iterable
           (->> columns
                (map (fn [[col-name col-type]]
                       (let [field-type ^ArrowType
                                        (case col-type
                                          :int (ArrowType$Int. 32 true)
                                          :float (ArrowType$FloatingPoint. FloatingPointPrecision/SINGLE)
                                          :string (ArrowType$Utf8.))]
                         (Field. (name col-name) (FieldType/nullable field-type) nil)))))))


(defn set-vectors-data
  [^VectorSchemaRoot root columns batch]
  (let [batch-size (count batch)]
    (doseq [[col-name col-type] columns]
      (let [vector (.getVector root (name col-name))]
        (.allocateNew vector batch-size)
        (doall (map-indexed
                (fn [idx data]
                  (let [value (if (= col-type :string)
                                (.getBytes (str (get data col-name)) StandardCharsets/UTF_8)
                                (get data col-name))]
                    (.set vector idx value)))
                batch))))))


(defprotocol PWriter
  (write [this batch]))


(defn make-writer
  [^String file-or-path columns]
  (let [allocator     (RootAllocator.)
        ;; TODO detect schema from columns
        schema        (create-schema columns)
        root          (VectorSchemaRoot/create schema allocator)
        output-stream (FileOutputStream. file-or-path)
        writer        (ArrowFileWriter. root nil (.getChannel output-stream))]
    (.start writer)

    (reify
      PWriter
      (write [this batch]
       ;; TODO batch can be a collection or dataset
        (try
          ;; TODO set data according to columns
          (set-vectors-data root columns batch)
          (.setRowCount root (count batch))
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
        (.close root)
        (.close allocator)))))


(defn read-dataset
  [file-or-path]
  (let [[dataset & rest]
        (arrow/stream->dataset-seq file-or-path {:open-type :mmap})]
    (if (seq rest) ;; TODO validate that concat doesn't mess heap
      (apply utils/parallel-concat dataset rest)
      dataset)))



(comment
 (let [columns [[:id :int]
                [:name :string]
                [:score :float]]]
   (with-open [writer (make-writer "tmp/maps-example.arrow" columns)]
     (write writer [{:id 1 :name "Alice" :score (float 95.5)}
                    {:id 2 :name "Bob" :score (float 85.0)}])

     (write writer [{:id 3 :name "Charlie" :score (float 77.3)}
                    {:id 4 :name "Diana" :score (float 89.9)}])))

 (read-dataset "tmp/maps-example.arrow"))
