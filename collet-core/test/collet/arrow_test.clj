(ns collet.arrow-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer :all]
   [collet.arrow :as sut]
   [collet.test-fixtures :as tf]
   [tech.v3.dataset :as ds])
  (:import
    (clojure.lang ExceptionInfo)
    [java.io File]
    [java.math BigDecimal BigInteger]
    [java.time LocalDate LocalDateTime LocalTime Instant Duration]
    [java.util Date]
    [org.apache.arrow.vector.types.pojo ArrowType$Int ArrowType$Timestamp Schema]
    [org.apache.arrow.vector.types TimeUnit]
    [org.apache.arrow.vector VectorSchemaRoot]
    [org.apache.arrow.memory RootAllocator]
    [org.apache.arrow.vector.complex ListVector]))


(use-fixtures :once (tf/instrument! 'collet.arrow))


(declare with-arrow-file)


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
    (is (= {:version 1
            :fields  [{:key :name :name "name" :type :string :nullable? true}
                      {:key :age :name "age" :type :int64 :nullable? true}
                      {:key       :scores
                       :name      "scores"
                       :type      :list
                       :nullable? true
                       :element   {:type :int64 :nullable? true}}]}
           columns))))


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
      (sut/write writer
                 [{:id 1 :name "Alice" :score (float 95.5) :obj [1 2 3]}
                  {:id 2 :name "Bob" :score (float 85.0) :obj [3 4 5]}])
      (sut/write writer
                 [{:id 3 :name "Charlie" :score (float 77.3)}
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
                                  {:id 2 :name "Bob" :score (float 85.0) :obj ["3" "4" "5"]}])]
    (with-open [writer (sut/make-writer "tmp/test.arrow" columns)]
      (sut/write writer
                 [{:id 1 :name "Alice" :score (float 95.5) :obj ["item1" "item2" "item3"]}
                  {:id 2 :name "Bob" :score (float 85.0) :obj ["item3" "item4" "item5"]}])
      (sut/write writer
                 [{:id 3 :name "Charlie" :score (float 77.3)}
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
  (let [uuid (random-uuid)
        local-date (LocalDate/of 2025 1 2)
        local-time (LocalTime/of 12 34 56 789000000)
        instant (Instant/ofEpochSecond 123 456000000)
        data
        [{:instant            instant
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
          :epoch-days         local-date
          :local-date         local-date
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
        columns (sut/get-columns data)]
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
      (is (= (record :epoch-days) local-date))
      (is (= (record :local-date) local-date))
      (is (= (record :local-time) local-time))
      (is (= (record :time-nanoseconds) local-time))
      (is (= (record :time-microseconds) local-time))
      (is (= (record :time-milliseconds) local-time))
      (is (= (record :time-seconds) local-time))
      (is (= (record :duration) 123456000))
      (is (= (record :instant) instant))
      (is (= (record :epoch-milliseconds) instant))
      (is (= (record :epoch-microseconds) instant))
      (is (= (record :epoch-nanoseconds) instant))
      (with-arrow-file
       (fn [copy-file]
         (with-open [writer (sut/make-writer copy-file columns)]
           (sut/write writer dataset))
         (let [roundtrip (-> (sut/read-dataset copy-file columns)
                             first
                             ds/rows
                             first
                             (sut/prep-record columns))]
           (is (= (:duration (first data)) (:duration roundtrip)))
           (is (= (:local-date (first data)) (:local-date roundtrip)))
           (is (= (:local-time (first data)) (:local-time roundtrip)))
           (is (= (:local-date-time (first data)) (:local-date-time roundtrip)))
           (is (= (:instant (first data)) (:instant roundtrip))))))))
  (io/delete-file "tmp/test-all-types.arrow"))


(def nested-schema
  (sut/normalize-schema
   {:version 1
    :fields  [{:key :id :name "id" :type :int64}
              {:key    :address
               :name   "address"
               :type   :struct
               :fields [{:key :line :name "line" :type :string}
                        {:key :city :name "city" :type :string}
                        {:key :zip :name "zip" :type :int32 :nullable? false}]}
              {:key :attributes :name "attributes" :type :map}
              {:key     :tags
               :name    "tags"
               :type    :list
               :element {:type :string}}
              {:key     :events
               :name    "events"
               :type    :list
               :element {:type   :struct
                         :fields [{:key :kind :name "kind" :type :string}
                                  {:key :score :name "score" :type :int32}]}}
              {:key     :embedding
               :name    "embedding"
               :type    :fixed-size-list
               :size    3
               :element {:type :float32 :nullable? false}}
              {:key       :amount
               :name      "amount"
               :type      :decimal
               :bit-width 128
               :precision 10
               :scale     2}
              {:key          :big-id
               :name         "big_id"
               :type         :decimal
               :bit-width    256
               :precision    76
               :scale        0
               :logical-type :big-integer}]}))


(defn with-arrow-file
  [f]
  (let [file (File/createTempFile "collet-arrow-test" ".arrow")]
    (try
      (f file)
      (finally
       (.delete file)))))


(defn write-error
  [schema rows]
  (with-arrow-file
   (fn [file]
     (try
       (with-open [writer (sut/make-writer file schema)]
         (sut/write writer rows))
       nil
       (catch ExceptionInfo error
         error)))))


(defn normalization-error
  [normalizer]
  (try
    (normalizer)
    (catch ExceptionInfo error
      error)))


(deftest malli-normalization-defaults-test
  (doseq [{:keys [description input expected]}
          [{:description "keyword descriptor"
            :input       :string
            :expected    {:type :string :nullable? true}}
           {:description "explicit false nullability"
            :input       {:type :string :nullable? false}
            :expected    {:type :string :nullable? false}}
           {:description "explicit nil nullability"
            :input       {:type :string :nullable? nil}
            :expected    {:type :string :nullable? false}}
           {:description "truthy nullability"
            :input       {:type :string :nullable? "yes"}
            :expected    {:type :string :nullable? true}}
           {:description "recursive list of struct"
            :input       {:type    :list
                          :element {:type   :struct
                                    :fields [{:key :score :name "score" :type :int32}]}}
            :expected    {:type      :list
                          :nullable? true
                          :element   {:type      :struct
                                      :nullable? true
                                      :fields    [{:key :score :name "score" :type :int32 :nullable? true}]}}}]]
    (testing description
      (is (= expected (sut/normalize-descriptor input)))))
  (doseq [{:keys [description input expected]}
          [{:description "canonical schema"
            :input       {:fields [{:key :id :name "id" :type :string}]}
            :expected    {:version 1
                          :fields  [{:key :id :name "id" :type :string :nullable? true}]}}
           {:description "legacy column triplet"
            :input       [[:id "id" :string]]
            :expected    {:version 1
                          :fields  [{:key :id :name "id" :type :string :nullable? true}]}}]]
    (testing description
      (is (= expected (sut/normalize-schema input))))))


(deftest malli-normalization-test
  (let [descriptor
        (sut/normalize-descriptor
         {:type       :instant
          :nullable?  nil
          :timezone   false
          :custom-tag {:keep true}})
        map-descriptor (sut/normalize-descriptor {:type :map})
        schema
        (sut/normalize-schema
         {:version nil
          :ignored :drop
          :fields  [{:key       :events
                     :name      "events"
                     :type      :list
                     :custom    :keep
                     :nullable? "false"
                     :element   {:type   :struct
                                 :fields [{:key :score :name "score" :type :int32}]}}
                    {:key  :created-at
                     :name "created_at"
                     :type :instant}]})]
    (is (= {:type       :instant
            :nullable?  false
            :timezone   "UTC"
            :custom-tag {:keep true}}
           descriptor))
    (is (= {:type       :map
            :nullable?  true
            :key-type   {:type :string :nullable? false}
            :value-type {:type :string :nullable? true}}
           map-descriptor))
    (is (= {:version 1
            :fields  [{:key       :events
                       :name      "events"
                       :type      :list
                       :custom    :keep
                       :nullable? true
                       :element   {:type      :struct
                                   :fields    [{:key :score :name "score" :type :int32 :nullable? true}]
                                   :nullable? true}}
                      {:key       :created-at
                       :name      "created_at"
                       :type      :instant
                       :nullable? true
                       :timezone  "UTC"}]}
           schema))
    (is (= [:events :created-at] (mapv :key (:fields schema))))
    (is (not (contains? schema :ignored))))
  (is (= {:version 1
          :fields  [{:key       :created-at
                     :name      "created_at"
                     :type      :instant
                     :nullable? true
                     :timezone  "UTC"}
                    {:key       :scores
                     :name      "scores"
                     :type      :list
                     :nullable? true
                     :element   {:type :int32 :nullable? true}}]}
         (sut/normalize-schema
          [[:created-at "created_at" [:zoned :instant]]
           [:scores "scores" [:list :int32]]]))))


(deftest malli-schema-validation-errors-test
  (doseq [{:keys [reason message normalize]}
          [{:reason    :invalid-descriptor
            :message   "Arrow descriptor must be a keyword or map."
            :normalize #(sut/normalize-descriptor [])}
           {:reason    :unsupported-type
            :message   "Unsupported Arrow type :wat."
            :normalize #(sut/normalize-descriptor {:type :wat})}
           {:reason    :invalid-struct
            :message   "Arrow Struct requires an ordered :fields vector."
            :normalize #(sut/normalize-descriptor {:type :struct})}
           {:reason    :invalid-map-key
            :message   "Arrow Map keys must be non-null UTF-8 strings."
            :normalize #(sut/normalize-descriptor
                         {:type :map :key-type {:type :string}})}
           {:reason    :invalid-map-value
            :message   "Arrow Map values must be nullable UTF-8 strings."
            :normalize #(sut/normalize-descriptor
                         {:type :map :value-type {:type :string :nullable? false}})}
           {:reason    :missing-list-element
            :message   "Arrow List requires :element."
            :normalize #(sut/normalize-descriptor {:type :list})}
           {:reason    :invalid-fixed-size
            :message   "Arrow fixed-size list requires a positive :size."
            :normalize #(sut/normalize-descriptor
                         {:type :fixed-size-list :size 0 :element :float32})}
           {:reason    :invalid-fixed-size
            :message   "Arrow fixed-size list :size must not exceed 2147483647."
            :normalize #(sut/normalize-descriptor
                         {:type    :fixed-size-list
                          :size    2147483648
                          :element :float32})}
           {:reason    :invalid-fixed-size-element
            :message   "Arrow fixed-size lists currently require Float32 elements."
            :normalize #(sut/normalize-descriptor
                         {:type :fixed-size-list :size 1 :element :int32})}
           {:reason    :invalid-timezone
            :message   "Arrow timezone must be a string."
            :normalize #(sut/normalize-descriptor
                         {:type :instant :timezone 42})}
           {:reason    :invalid-decimal-width
            :message   "Arrow decimal :bit-width must be 128 or 256."
            :normalize #(sut/normalize-descriptor
                         {:type :decimal :bit-width 1 :precision 1 :scale 0})}
           {:reason    :invalid-decimal-precision
            :message   "Arrow decimal precision must be between 1 and 38."
            :normalize #(sut/normalize-descriptor
                         {:type :decimal :bit-width 128 :precision 39 :scale 0})}
           {:reason    :invalid-decimal-scale
            :message   "Arrow decimal scale must be between zero and its precision."
            :normalize #(sut/normalize-descriptor
                         {:type :decimal :bit-width 128 :precision 1 :scale 2})}
           {:reason    :invalid-decimal-logical-type
            :message   "Arrow decimal logical type must be :big-integer when present."
            :normalize #(sut/normalize-descriptor
                         {:type :decimal :bit-width 128 :precision 1 :scale 0 :logical-type :wat})}
           {:reason    :invalid-big-integer-scale
            :message   "Logical BigInteger decimals require scale zero."
            :normalize #(sut/normalize-descriptor
                         {:type :decimal :bit-width 128 :precision 1 :scale 1 :logical-type :big-integer})}
           {:reason    :invalid-field-key
            :message   "Arrow field :key must be a keyword or string."
            :normalize #(sut/normalize-field {:type :string :name "field"})}
           {:reason    :invalid-field-name
            :message   "Arrow field :name must be a string."
            :normalize #(sut/normalize-field {:type :string :key :field})}
           {:reason    :invalid-schema
            :message   "Arrow schema must be a map or legacy column vector."
            :normalize #(sut/normalize-schema nil)}
           {:reason    :unsupported-schema-version
            :message   "Unsupported Arrow schema version 2."
            :normalize #(sut/normalize-schema {:version 2 :fields []})}
           {:reason    :invalid-fields
            :message   "Arrow schema requires an ordered :fields vector."
            :normalize #(sut/normalize-schema {:version 1})}
           {:reason    :invalid-legacy-column
            :message   "Legacy Arrow columns must be [key physical-name type] triplets."
            :normalize #(sut/normalize-schema [[:id "id"]])}
           {:reason    :invalid-legacy-column
            :message   "Arrow columns must use a known legacy type or descriptor map."
            :normalize #(sut/normalize-schema [[:id "id" 42]])}
           {:reason    :duplicate-field-name
            :message   "Arrow field names must be unique."
            :normalize #(sut/normalize-schema
                         {:version 1
                          :fields  [{:key :first :name "name" :type :string}
                                    {:key :second :name "name" :type :string}]})}
           {:reason    :duplicate-field-key
            :message   "Arrow field keys must be unique."
            :normalize #(sut/normalize-schema
                         {:version 1
                          :fields  [{:key :name :name "first" :type :string}
                                    {:key :name :name "second" :type :string}]})}
           {:reason    :duplicate-field-key
            :message   "Arrow field keys must be unique."
            :normalize #(sut/normalize-schema
                         {:version 1
                          :fields  [{:key    :profile
                                     :name   "profile"
                                     :type   :struct
                                     :fields [{:key :name :name "name" :type :string}
                                              {:key :name :name "alias" :type :string}]}]})}]]
    (testing (str reason " preserves its error contract")
      (let [error (normalization-error normalize)
            data  (ex-data error)]
        (is (instance? ExceptionInfo error))
        (is (= message (ex-message error)))
        (is (= :collet.error/arrow-invalid-schema (:collet.error/type data)))
        (is (= reason (:reason data)))
        (is (not-any? #(contains? data %) [:value :schema :errors :row :batch])))))
  (let [error (normalization-error
               #(sut/normalize-descriptor
                 {:type         :decimal
                  :bit-width    1
                  :precision    0
                  :scale        -1
                  :logical-type :wat}))]
    (is (= :invalid-decimal-width (:reason (ex-data error)))))
  (doseq [{:keys [description normalize reason message]}
          [{:description "schema version precedes an invalid field"
            :normalize   #(sut/normalize-schema
                           {:version 2 :fields [{:type :string}]})
            :reason      :unsupported-schema-version
            :message     "Unsupported Arrow schema version 2."}
           {:description "an earlier legacy field precedes a later malformed triplet"
            :normalize   #(sut/normalize-schema [[:id "id" :wat] [:broken]])
            :reason      :unsupported-type
            :message     "Unsupported Arrow type :wat."}]]
    (testing description
      (let [error (normalization-error normalize)]
        (is (= reason (:reason (ex-data error))))
        (is (= message (ex-message error)))))))


(deftest nested-roundtrip-and-null-semantics-test
  (let [first-batch  [{:id         1
                       :address    {:line "One" :city nil :zip 28001}
                       :attributes {"source" "api" "missing" nil}
                       :tags       ["new" nil]
                       :events     [{:kind "created" :score 7} nil]
                       :embedding  [1.0 2.0 3.0]
                       :amount     (BigDecimal. "12.30")
                       :big-id     (BigInteger. "18446744073709551616")}
                      {:id         2
                       :address    nil
                       :attributes {}
                       :tags       []
                       :events     []
                       :embedding  nil
                       :amount     nil
                       :big-id     nil}]
        second-batch [{:id         3
                       :address    {:line nil :city "Madrid" :zip 28002}
                       :attributes nil
                       :tags       nil
                       :events     nil
                       :embedding  [3.0 2.0 1.0]
                       :amount     (BigDecimal. "0.00")
                       :big-id     BigInteger/ZERO}]
        expected     [{:id         1
                       :address    {:line "One" :city nil :zip 28001}
                       :attributes {"source" "api" "missing" nil}
                       :tags       ["new" nil]
                       :events     [{:kind "created" :score 7} nil]
                       :embedding  [1.0 2.0 3.0]
                       :amount     (BigDecimal. "12.30")
                       :big-id     (BigInteger. "18446744073709551616")}
                      {:id         2
                       :address    nil
                       :attributes {}
                       :tags       []
                       :events     []
                       :embedding  nil
                       :amount     nil
                       :big-id     nil}
                      {:id         3
                       :address    {:line nil :city "Madrid" :zip 28002}
                       :attributes nil
                       :tags       nil
                       :events     nil
                       :embedding  [3.0 2.0 1.0]
                       :amount     (BigDecimal. "0.00")
                       :big-id     BigInteger/ZERO}]]
    (with-arrow-file
     (fn [file]
       (with-open [writer (sut/make-writer file nested-schema)]
         (sut/write writer first-batch)
         (sut/write writer second-batch))
       (let [datasets (sut/read-dataset file nested-schema)
             rows     (mapv #(sut/prep-record % nested-schema)
                            (mapcat ds/rows datasets))]
         (is (= [2 1] (mapv ds/row-count datasets)))
         (is (= nested-schema (:arrow-columns (meta datasets))))
         (is (= expected rows)))))))


(deftest nested-uuid-roundtrip-test
  (let [id     (random-uuid)
        schema (sut/normalize-schema
                {:version 1
                 :fields  [{:key    :profile
                            :name   "profile"
                            :type   :struct
                            :fields [{:key :id :name "id" :type :uuid}]}
                           {:key     :ids
                            :name    "ids"
                            :type    :list
                            :element {:type :uuid}}]})]
    (with-arrow-file
     (fn [file]
       (with-open [writer (sut/make-writer file schema)]
         (sut/write writer [{:profile {:id id} :ids [id]}]))
       (is (= {:profile {:id id} :ids [id]}
              (-> (sut/read-dataset file schema)
                  first
                  ds/rows
                  first
                  (sut/prep-record schema))))))))


(deftest missing-dataset-field-validation-test
  (let [schema (sut/normalize-schema
                {:version 1
                 :fields  [{:key       :logical-id
                            :name      "physical_id"
                            :type      :int64
                            :nullable? false}]})
        error  (write-error schema (ds/->dataset [{:other 1}]))]
    (is (= :collet.error/arrow-invalid-value
           (:collet.error/type (ex-data error))))
    (is (= :non-nullable (:reason (ex-data error))))
    (is (= [0 :logical-id] (:path (ex-data error))))))


(deftest explicit-schema-validation-test
  (let [missing-child (write-error nested-schema [{:address {:line "One"}}])
        extra-child (write-error nested-schema [{:address {:line "One" :zip 1 :extra true}}])
        fixed-length (write-error nested-schema [{:embedding [1.0 2.0]}])
        duplicate-child
        (try
          (sut/normalize-schema
           {:version 1
            :fields  [{:key    :profile
                       :name   "profile"
                       :type   :struct
                       :fields [{:key :name :name "name" :type :string}
                                {:key :alias :name "name" :type :string}]}]})
          (catch ExceptionInfo error
            error))
        nested-score (write-error
                      (sut/normalize-schema
                       {:version 1
                        :fields  [{:key     :events
                                   :name    "events"
                                   :type    :list
                                   :element {:type   :struct
                                             :fields [{:key       :score
                                                       :name      "score"
                                                       :type      :int32
                                                       :nullable? false}]}}]})
                      [{:events [{:score nil}]}])]
    (is (= :non-nullable (:reason (ex-data missing-child))))
    (is (= [0 :address :zip] (:path (ex-data missing-child))))
    (is (= :unknown-struct-key (:reason (ex-data extra-child))))
    (is (= :wrong-fixed-size (:reason (ex-data fixed-length))))
    (is (= :duplicate-field-name (:reason (ex-data duplicate-child))))
    (is (= [0 :events 0 :score] (:path (ex-data nested-score))))
    (is (not (contains? (ex-data nested-score) :batch)))
    (is (not (contains? (ex-data nested-score) :row)))))


(deftest decimal-and-integer-boundary-test
  (let [max-128 (BigDecimal. (apply str (repeat 38 "9")))
        max-256 (BigDecimal. (apply str (repeat 76 "9")))
        max-big (BigInteger. (apply str (repeat 76 "9")))
        schema  (sut/normalize-schema
                 {:version 1
                  :fields  [{:key       :d128
                             :name      "d128"
                             :type      :decimal
                             :bit-width 128
                             :precision 38
                             :scale     0}
                            {:key       :d256
                             :name      "d256"
                             :type      :decimal
                             :bit-width 256
                             :precision 76
                             :scale     0}
                            {:key          :big
                             :name         "big"
                             :type         :decimal
                             :bit-width    256
                             :precision    76
                             :scale        0
                             :logical-type :big-integer}]})]
    (with-arrow-file
     (fn [file]
       (with-open [writer (sut/make-writer file schema)]
         (sut/write writer [{:d128 max-128 :d256 max-256 :big max-big}]))
       (is (= {:d128 max-128 :d256 max-256 :big max-big}
              (-> (sut/read-dataset file schema)
                  first
                  ds/rows
                  first
                  (sut/prep-record schema))))))
    (let [rounding (write-error nested-schema [{:amount (BigDecimal. "1.234")}])
          overflow (write-error
                    (sut/normalize-schema
                     {:version 1
                      :fields  [{:key :value :name "value" :type :int8}]})
                    [{:value 128}])]
      (is (= :rounding-required (:reason (ex-data rounding))))
      (is (= :integer-overflow (:reason (ex-data overflow)))))
    (is (= :invalid-decimal-precision
           (:reason (ex-data
                     (try
                       (sut/normalize-schema
                        {:version 1
                         :fields  [{:key       :bad
                                    :name      "bad"
                                    :type      :decimal
                                    :bit-width 128
                                    :precision 39
                                    :scale     0}]})
                       (catch ExceptionInfo error
                         error))))))))


(deftest inference-and-legacy-date-test
  (is (= :collet.error/arrow-schema-required
         (:collet.error/type
          (ex-data
           (try
             (sut/infer-schema! [{:value {:name "Ada"}}])
             (catch ExceptionInfo error
               error))))))
  (is (nil? (sut/get-columns [{:value {:name "Ada"}}])))
  (is (= :collet.error/arrow-schema-required
         (:collet.error/type
          (ex-data
           (try
             (sut/infer-schema! [{:values []}])
             (catch ExceptionInfo error
               error))))))
  (let [error (write-error
               (sut/normalize-schema
                {:version 1
                 :fields  [{:key :created-at :name "created_at" :type :instant}]})
               [{:created-at (Date.)}])]
    (is (= :collet.error/arrow-legacy-date (:collet.error/type (ex-data error)))))
  (is (= :instant
         (-> (sut/infer-schema! [{:created-at (Instant/parse "2026-01-01T00:00:00Z")}])
             :fields
             first
             :type))))
