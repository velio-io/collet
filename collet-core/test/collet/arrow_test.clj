(ns collet.arrow-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer :all]
   [collet.test-fixtures :as tf]
   [tech.v3.dataset :as ds]
   [collet.arrow :as sut])
  (:import
   [java.time LocalDate LocalDateTime LocalTime Instant Duration]
   [org.apache.arrow.vector.types.pojo ArrowType$Int ArrowType$Timestamp Schema]
   [org.apache.arrow.vector.types TimeUnit]
   [org.apache.arrow.vector VectorSchemaRoot]
   [org.apache.arrow.memory RootAllocator]
   [org.apache.arrow.vector.complex ListVector]))


(use-fixtures :once (tf/instrument! 'collet.arrow))


(deftest test-ds->columns
  (let [dataset (ds/->dataset [{:name "Alice" :age 30 :scores [95 85 75] :dob (LocalDateTime/of 2000 1 1 12 34 56)}
                               {:name "Bob" :age 25 :scores [88 78 68] :dob (LocalDateTime/of 2001 2 2 12 34 56)}])
        columns (sut/ds->columns dataset)]
    (is (= (count columns) 4))
    (is (= (first columns) [:name "name" :string]))
    (is (= (second columns) [:age "age" :int64]))
    (is (= (nth columns 2) [:scores "scores" [:list :int64]]))
    (is (= (nth columns 3) [:dob "dob" :local-date-time]))))


(deftest test-get-columns
  (let [data    [{:name "Alice" :age 30 :scores [95 85 75]}
                 {:name "Bob" :age 25 :scores [88 78 68]}]
        columns (sut/get-columns data)]
    (is (= (count columns) 3))
    (is (= (first columns) [:name "name" :string]))
    (is (= (second columns) [:age "age" :int64]))
    (is (= (nth columns 2) [:scores "scores" [:list :int64]]))))


(deftest test-create-field
  (let [field (sut/create-field "age" :int32)]
    (is (= (.getName field) "age"))
    (is (= (.getType (.getFieldType field)) (ArrowType$Int. 32 true)))))


(deftest test-create-zoned-field
  (let [field (sut/create-zoned-field "timestamp" :instant "UTC")]
    (is (= (.getName field) "timestamp"))
    (is (= (.getType (.getFieldType field)) (ArrowType$Timestamp. TimeUnit/MICROSECOND "UTC")))))


(deftest test-create-schema
  (let [columns [[:name "name" :string]
                 [:age "age" :int32]
                 [:scores "scores" [:list :int32]]]
        schema  (sut/create-schema columns)]
    (is (instance? Schema schema))
    (is (= (count (.getFields schema)) 3))))


(deftest test-local-time->millis
  (let [time (LocalTime/of 12 34 56 789000000)]
    (is (= (sut/local-time->millis time) 45296789))))


(deftest test-local-time->micros
  (let [time (LocalTime/of 12 34 56 789000000)]
    (is (= (sut/local-time->micros time) 45296789000))))


(deftest test-duration->micros
  (let [duration (Duration/ofSeconds 123 456000000)]
    (is (= (sut/duration->micros duration) 123456000))))


(deftest test-instant->micros
  (let [instant (Instant/ofEpochSecond 123 456000000)]
    (is (= (sut/instant->micros instant) 123456000))))


(deftest test-instant->nanos
  (let [instant (Instant/ofEpochSecond 123 456000000)]
    (is (= (sut/instant->nanos instant) 123456000000))))


(deftest test-write-list-item
  (let [allocator (RootAllocator.)
        vector    (ListVector/empty "list" allocator)
        writer    (.getWriter vector)]
    (.setPosition writer 0)
    (.startList writer)
    (sut/write-list-item writer :int32 42)
    (.setValueCount writer 1)
    (.endList writer)
    (.setValueCount vector 1)

    (is (= (.getObject vector 0) [42]))))


(deftest test-set-column-vector
  (let [allocator   (RootAllocator.)
        schema      (sut/create-schema [[:name "name" :string]
                                        [:age "age" :int32]])
        schema-root (VectorSchemaRoot/create schema allocator)
        batch       [{:name "Alice" :age 30}
                     {:name "Bob" :age 25}]]
    (sut/set-column-vector {:schema-root schema-root
                            :column-name "name"
                            :column-type :string
                            :column      (map :name batch)
                            :batch-size  (count batch)})
    (sut/set-column-vector {:schema-root schema-root
                            :column-name "age"
                            :column-type :int32
                            :column      (map :age batch)
                            :batch-size  (count batch)})
    (.setRowCount schema-root (count batch))

    (is (= (str (.getObject (.getVector schema-root "name") 0)) "Alice"))
    (is (= (str (.getObject (.getVector schema-root "name") 1)) "Bob"))
    (is (= (.getObject (.getVector schema-root "age") 0) 30))
    (is (= (.getObject (.getVector schema-root "age") 1) 25))))


(deftest test-set-vectors-data
  (let [allocator   (RootAllocator.)
        columns     [[:name "name" :string]
                     [:age "age" :int32]]
        schema      (sut/create-schema columns)
        schema-root (VectorSchemaRoot/create schema allocator)
        batch       [{:name "Alice" :age 30}
                     {:name "Bob" :age 25}]]
    (sut/set-vectors-data schema-root columns batch)
    (.setRowCount schema-root (count batch))

    (is (= (str (.getObject (.getVector schema-root "name") 0)) "Alice"))
    (is (= (str (.getObject (.getVector schema-root "name") 1)) "Bob"))
    (is (= (.getObject (.getVector schema-root "age") 0) 30))
    (is (= (.getObject (.getVector schema-root "age") 1) 25))))


(deftest test-make-writer
  (let [columns (sut/get-columns [{:id 1 :name "Alice" :score (float 95.5) :obj [1 2 3]}
                                  {:id 2 :name "Bob" :score (float 85.0) :obj [3 4 5]}])]
    (with-open [writer (sut/make-writer "tmp/test.arrow" columns)]
      (sut/write writer [{:id 1 :name "Alice" :score (float 95.5) :obj [1 2 3]}
                         {:id 2 :name "Bob" :score (float 85.0) :obj [3 4 5]}])
      (sut/write writer [{:id 3 :name "Charlie" :score (float 77.3)}
                         {:id 4 :name "Diana" :score (float 89.9) :obj [6 7 8]}]))
    (let [dataset-seq (sut/read-dataset "tmp/test.arrow" columns)]
      (is (= 2 (ds/row-count (first dataset-seq))))
      (is (= [:id :name :score :obj] (ds/column-names (first dataset-seq))))
      (is (= [{:id 1 :name "Alice" :score 95.5 :obj [1 2 3]}
              {:id 2 :name "Bob" :score 85.0 :obj [3 4 5]}]
             (mapv (fn [{:keys [id name score obj]}]
                     {:id id :name (str name) :score score :obj (vec obj)})
                   (ds/rows (first dataset-seq)))))
      (is (= [{:id 3 :name "Charlie" :score 77.30000305175781 :obj nil}
              {:id 4 :name "Diana" :score 89.9000015258789 :obj [6 7 8]}]
             (mapv (fn [{:keys [id name score obj]}]
                     {:id id :name (str name) :score score :obj (when obj (vec obj))})
                   (ds/rows (second dataset-seq)))))
      (io/delete-file "tmp/test.arrow"))))


(deftest test-read-dataset
  (let [columns (sut/get-columns [{:id 1 :name "Alice" :score (float 95.5) :obj ["1" "2" "3"]}
                                  {:id 2 :name "Bob" :score (float 85.0) :obj [3 4 5]}])]
    (with-open [writer (sut/make-writer "tmp/test.arrow" columns)]
      (sut/write writer [{:id 1 :name "Alice" :score (float 95.5) :obj ["item1" "item2" "item3"]}
                         {:id 2 :name "Bob" :score (float 85.0) :obj ["item3" "item4" "item5"]}])
      (sut/write writer [{:id 3 :name "Charlie" :score (float 77.3)}
                         {:id 4 :name "Diana" :score (float 89.9) :obj ["item6" "item7" "item8"]}]))
    (let [dataset-seq (sut/read-dataset "tmp/test.arrow" columns)]
      (is (= 2 (ds/row-count (first dataset-seq))))
      (is (= 2 (ds/row-count (second dataset-seq))))
      (is (= [:id :name :score :obj] (ds/column-names (first dataset-seq))))
      (is (= ["Alice" "Bob"]
             (map str (ds/column (first dataset-seq) :name))))
      (is (= [["item1" "item2" "item3"] ["item3" "item4" "item5"]]
             (map vec (ds/column (first dataset-seq) :obj)))))
    (io/delete-file "tmp/test.arrow")))


(deftest test-read-dataset-all-types
  (let [uuid       (random-uuid)
        local-time (LocalTime/now)
        instant    (Instant/now)
        data       [{:instant            instant
                     :epoch-milliseconds instant
                     :epoch-microseconds instant
                     :epoch-nanoseconds  instant
                     :boolean            true
                     :uint8              255
                     :int8               -128
                     :uint16             65535
                     :int16              -32768
                     :uint32             4294967295
                     :int32              -2147483648
                     :uint64             184467440737095516
                     :int64              -922337203685477580
                     :float32            3.14
                     :float64            3.141592653589793
                     :epoch-days         (LocalDate/now)
                     :local-date         (LocalDate/now)
                     :local-time         local-time
                     :time-nanoseconds   local-time
                     :time-microseconds  local-time
                     :time-milliseconds  local-time
                     :time-seconds       local-time
                     :duration           (Duration/ofSeconds 123 456000000)
                     :string             "test"
                     :uuid               uuid
                     :text               "text"
                     :encoded-text       "encoded"}]
        columns    (sut/get-columns data)]
    (with-open [writer (sut/make-writer "tmp/test-all-types.arrow" columns)]
      (sut/write writer data))
    (let [dataset-seq (sut/read-dataset "tmp/test-all-types.arrow" columns)
          dataset     (first dataset-seq)
          record      (first (ds/rows dataset))]
      (is (= (ds/row-count dataset) 1))
      (is (= #{:instant :epoch-milliseconds :epoch-microseconds :epoch-nanoseconds :boolean
               :uint8 :int8 :uint16 :int16 :uint32 :int32 :uint64 :int64 :float32 :float64
               :epoch-days :local-date :local-time :time-nanoseconds :time-microseconds :time-milliseconds
               :time-seconds :duration :string :uuid :text :encoded-text}
             (set (ds/column-names dataset))))
      (is (= (record :boolean) true))
      (is (= (record :uint8) 255))
      (is (= (record :int8) -128))
      (is (= (record :uint16) 65535))
      (is (= (record :int16) -32768))
      (is (= (record :uint32) 4294967295))
      (is (= (record :int32) -2147483648))
      (is (= (record :uint64) 184467440737095516))
      (is (= (record :int64) -922337203685477580))
      (is (= (record :float32) 3.14))
      (is (= (record :float64) 3.141592653589793))
      (is (= (record :string) "test"))
      (is (= (record :uuid) (str uuid)))
      (is (= (record :text) "text"))
      (is (= (record :encoded-text) "encoded"))
      (is (= (record :epoch-days) (LocalDate/now)))
      (is (= (record :local-date) (LocalDate/now)))
      (is (= (record :local-time) local-time))
      (is (= (record :time-nanoseconds) local-time))
      (is (= (record :time-microseconds) local-time))
      (is (= (record :time-milliseconds) local-time))
      (is (= (record :time-seconds) local-time))
      (is (= (record :duration) 123456000))
      (is (= (record :instant) instant))
      (is (= (record :epoch-milliseconds) instant))
      (is (= (record :epoch-microseconds) instant))
      (is (= (record :epoch-nanoseconds) instant))))
  (io/delete-file "tmp/test-all-types.arrow"))
