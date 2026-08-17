(ns collet.artifact-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [collet.arrow :as arrow]
   [collet.artifact :as sut]
   [tech.v3.dataset :as ds])
  (:import
    [clojure.lang ExceptionInfo]
    [java.math BigDecimal BigInteger]
    [java.nio.charset StandardCharsets]
    [java.nio.file FileVisitOption Files LinkOption Path]
    [java.nio.file.attribute FileAttribute]
    [java.time Duration Instant LocalDate LocalDateTime LocalTime]
    [java.util UUID]
    [java.util.regex Pattern]))


(def ^:private no-link-options
  (make-array LinkOption 0))


(defrecord UnsupportedRecord [value])


(deftype UnsupportedType [])


(defn- temporary-directory
  ^Path
  [prefix]
  (Files/createTempDirectory prefix (make-array FileAttribute 0)))


(defn- delete-tree!
  [^Path path]
  (when (Files/exists path no-link-options)
    (with-open [paths (Files/walk path (make-array FileVisitOption 0))]
      (doseq [entry (sort-by str
                             #(compare %2 %1)
                             (iterator-seq (.iterator paths)))]
        (Files/deleteIfExists ^Path entry)))))


(defmacro with-temp-dir
  [[binding] & body]
  `(let [~binding (temporary-directory "collet-artifact-test-")]
     (try
       ~@body
       (finally
        (delete-tree! ~binding)))))


(defn- publish-scalar!
  [directory value]
  (sut/publish-output! directory
                       (random-uuid)
                       (random-uuid)
                       {:output/kind  :scalar
                        :output/value value}))


(defn- artifact-directory
  ^Path
  [^Path root artifact]
  (.resolve root
            (str "runs/"
                 (:artifact/run-id artifact)
                 "/tasks/"
                 (:artifact/task-id artifact)
                 "/"
                 (:artifact/id artifact))))


(defn- exception-data
  [f]
  (try
    (f)
    nil
    (catch ExceptionInfo error
      (ex-data error))))


(deftest data-layout-is-explicit-and-legacy-compatible
  (with-temp-dir [root]
                 (testing "a fresh data directory reserves db and artifacts children"
                   (let [layout (sut/data-layout root)]
                     (is (= (.resolve root "db") (:db-dir layout)))
                     (is (= (.resolve root "artifacts") (:artifact-dir layout)))
                     (is (false? (:legacy? layout)))))

                 (testing "a legacy Datalevin root keeps its database in place"
                   (Files/createFile (.resolve root "data.mdb")
                                     (make-array FileAttribute 0))
                   (let [layout (sut/data-layout root)]
                     (is (= root (:db-dir layout)))
                     (is (= (.resolve root "artifacts") (:artifact-dir layout)))
                     (is (true? (:legacy? layout)))))

                 (testing "a legacy root plus db child is rejected rather than guessed"
                   (Files/createDirectories (.resolve root "db")
                                            (make-array FileAttribute 0))
                   (is (= :collet.error/ambiguous-data-layout
                          (:collet.error/type
                           (exception-data #(sut/data-layout root))))))))


(deftest scalar-artifacts-are-strict-versioned-edn
  (with-temp-dir [directory]
                 (let [bytes    (byte-array [0 1 -2 127])
                       uuid     (random-uuid)
                       pattern  (Pattern/compile "ab+" Pattern/CASE_INSENSITIVE)
                       value    {:nil       nil
                                 :uuid      uuid
                                 :bytes     bytes
                                 :regex     pattern
                                 :instant   (Instant/parse "2026-01-02T03:04:05.123456789Z")
                                 :date      (LocalDate/of 2026 1 2)
                                 :time      (LocalTime/of 3 4 5 123456789)
                                 :date-time (LocalDateTime/of 2026 1 2 3 4 5 123456789)
                                 :duration  (Duration/ofSeconds 5 123456789)
                                 :nested    [{:keyword :value} '(one two) #{:set}]}
                       artifact (:artifact (publish-scalar! directory value))
                       actual   (sut/read-artifact directory artifact)]
                   (is (= :scalar (:artifact/kind artifact)))
                   (is (= :edn (:artifact/format artifact)))
                   (is (pos? (:artifact/bytes artifact)))
                   (is (= (dissoc value :bytes :regex)
                          (dissoc actual :bytes :regex)))
                   (is (= (seq bytes) (seq (:bytes actual))))
                   (is (= (.pattern pattern) (.pattern ^Pattern (:regex actual))))
                   (is (= (.flags pattern) (.flags ^Pattern (:regex actual)))))))


(deftest scalar-normalization-rejects-key-and-member-collisions
  (with-temp-dir [directory]
                 (let [first-bytes  (byte-array [1 2])
                       second-bytes (byte-array [1 2])]
                   (doseq [[value expected-path]
                           [[(array-map first-bytes :first second-bytes :second)
                             [:key]]
                            [(hash-set first-bytes second-bytes)
                             [:member]]]]
                     (is (= 2 (count value)))
                     (let [data (exception-data #(publish-scalar! directory value))]
                       (is (= :collet.error/non-durable-output
                              (:collet.error/type data)))
                       (is (= expected-path (:path data))))))))


(deftest scalar-artifacts-reject-unsupported-values-and-tags
  (with-temp-dir [directory]
                 (testing "unsupported values include their nested failing path"
                   (let [data (exception-data
                               #(publish-scalar!
                                 directory
                                 {:outer [{:bad (fn [] :not-durable)}]}))]
                     (is (= :collet.error/non-durable-output (:collet.error/type data)))
                     (is (= [:outer 0 :bad] (:path data)))))

                 (testing "records, deftypes, Vars, streams, and JVM objects stay out"
                   (doseq [value [(->UnsupportedRecord :record)
                                  (UnsupportedType.)
                                  #'clojure.core/identity
                                  (java.io.ByteArrayInputStream. (byte-array 0))
                                  (Object.)]]
                     (let [data (exception-data #(publish-scalar! directory value))]
                       (is (= :collet.error/non-durable-output
                              (:collet.error/type data)))
                       (is (= [] (:path data))))))

                 (testing "connections stay out"
                   (with-open [connection (java.sql.DriverManager/getConnection
                                           "jdbc:duckdb:")]
                     (let [data (exception-data
                                 #(publish-scalar! directory connection))]
                       (is (= :collet.error/non-durable-output
                              (:collet.error/type data)))
                       (is (= [] (:path data))))))

                 (testing "unknown EDN tags are rejected"
                   (let [path (.resolve directory "unknown.edn")
                         _ (Files/writeString path
                                              "#unknown/tag {:value 1}"
                                              StandardCharsets/UTF_8
                                              (make-array java.nio.file.OpenOption 0))
                         data (exception-data #(#'sut/read-edn-file path {}))]
                     (is (= :collet.error/invalid-scalar-artifact
                            (:collet.error/type data)))
                     (is (= 'unknown/tag (:tag data)))))

                 (testing "standard UUID and instant tags preserve their logical values"
                   (let [path (.resolve directory "builtin.edn")
                         _ (Files/writeString path
                                              (str "[#uuid \"7b4d1f1f-5a18-4c5a-9f4b-82e13d4e9a6a\" "
                                                   "#inst \"2026-01-02T03:04:05.123456789Z\"]")
                                              StandardCharsets/UTF_8
                                              (make-array java.nio.file.OpenOption 0))
                         data (#'sut/read-edn-file path @#'sut/scalar-readers)]
                     (is (= [(UUID/fromString "7b4d1f1f-5a18-4c5a-9f4b-82e13d4e9a6a")
                             (Instant/parse "2026-01-02T03:04:05.123456789Z")]
                            data))))))


(deftest scalar-artifacts-have-no-hidden-size-limit-and-detect-corruption
  (with-temp-dir [directory]
                 (let [value    (apply str (repeat (+ (* 1024 1024) 1) "x"))
                       artifact (:artifact (publish-scalar! directory value))
                       payload  (.resolve (artifact-directory directory artifact)
                                          "value.edn")]
                   (is (> (:artifact/bytes artifact) (* 1024 1024)))
                   (is (= value (sut/read-artifact directory artifact)))
                   (Files/writeString payload
                                      "{:collet.scalar/version 1}"
                                      StandardCharsets/UTF_8
                                      (make-array java.nio.file.OpenOption 0))
                   (is (= :collet.error/artifact-corrupt
                          (:collet.error/type
                           (exception-data #(sut/read-artifact directory artifact))))))))


(deftest publication-failure-before-rename-leaves-no-consumable-artifact
  (with-temp-dir
   [directory]
   (let [run-id  (random-uuid)
         task-id (random-uuid)
         failure (ex-info "rename failed" {})
         error   (try
                   (with-redefs-fn {#'sut/publish-directory!
                                    (fn [& _]
                                      (throw failure))}
                     #(sut/publish-output! directory
                                           run-id
                                           task-id
                                           {:output/kind  :scalar
                                            :output/value :value}))
                   nil
                   (catch ExceptionInfo error
                     error))]
     (is (identical? failure error))
     (is (not (Files/exists (.resolve (.resolve directory "runs")
                                      (str run-id))
                            no-link-options))))))


(def ^:private extended-schema
  (arrow/normalize-schema
   {:version 1
    :fields  [{:key    :address
               :name   "address"
               :type   :struct
               :fields [{:key :line :name "line" :type :string}
                        {:key :zip :name "zip" :type :int32}]}
              {:key :attributes :name "attributes" :type :map}
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
               :logical-type :big-integer}
              {:key :time :name "time" :type :time-nanoseconds}
              {:key :instant :name "instant" :type :epoch-nanoseconds :timezone "UTC"}
              {:key :duration :name "duration" :type :duration}]}))


(deftest dataset-artifacts-preserve-the-logical-arrow-contract
  (with-temp-dir
   [directory]
   (let [source         (.resolve directory "source.arrow")
         source-first   {:address    {:line "One" :zip 28001}
                         :attributes {"source" "api" "missing" nil}
                         :events     [{:kind "created" :score 7} nil]
                         :embedding  [(float 1) (float 2) (float 3)]
                         :amount     (BigDecimal. "12.30")
                         :big-id     (BigInteger. "18446744073709551616")
                         :time       (LocalTime/of 12 34 56 789012345)
                         :instant    (Instant/ofEpochSecond 123 456789012)
                         :duration   (Duration/ofSeconds 5 123456789)}
         source-second  {:address    nil
                         :attributes {}
                         :events     []
                         :embedding  nil
                         :amount     nil
                         :big-id     nil
                         :time       nil
                         :instant    nil
                         :duration   nil}
         expected-first (assoc source-first
                          :duration (Duration/ofSeconds 5 123456000))]
     (with-open [writer (arrow/make-writer source extended-schema)]
       (arrow/write writer [source-first source-second]))
     (let [{:keys [artifact output]}
           (sut/publish-output! directory
                                (random-uuid)
                                (random-uuid)
                                {:output/kind   :dataset
                                 :output/file   source
                                 :output/schema extended-schema})
           batches (mapv (fn [batch]
                           (let [rows  (vec (ds/rows batch))
                                 nulls (mapv #(nth (ds/column batch %) 1)
                                             [:address :embedding :amount :big-id
                                              :time :instant :duration])]
                             {:rows rows :nulls nulls}))
                         (sut/read-artifact directory artifact))
           rows    (reduce into [] (map :rows batches))]
       (is (= :dataset (:artifact/kind artifact)))
       (is (= :parquet (:artifact/format artifact)))
       (is (= 2 (:artifact/records artifact)))
       (is (= extended-schema (:artifact/schema artifact)))
       (is (= :dataset (:output/kind output)))
       (is (= [:artifact/id (:artifact/id artifact)] (:output/ref output)))
       (is (= expected-first (first rows)))
       (is (= {} (:attributes (second rows))))
       (is (= [] (:events (second rows))))
       (is (every? nil? (:nulls (first batches))))
       (is (= (Duration/ofSeconds 5 123456000)
              (:duration (first rows))))
       (is (= (:time source-first) (:time (first rows))))
       (is (= (:instant source-first) (:instant (first rows))))))))
