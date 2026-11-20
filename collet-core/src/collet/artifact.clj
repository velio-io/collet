(ns collet.artifact
  (:require
   [clojure.edn :as edn]
   [clojure.string :as string]
   [collet.arrow :as arrow]
   [collet.durable :as durable]
   [collet.stream :as stream])
  (:import
   [java.io BufferedReader BufferedWriter InputStreamReader OutputStream OutputStreamWriter
            PushbackReader]
   [java.lang AutoCloseable]
   [java.nio.channels FileChannel]
   [java.nio.charset StandardCharsets]
   [java.nio.file FileVisitResult SimpleFileVisitor Files LinkOption OpenOption Path Paths StandardCopyOption
                  StandardOpenOption]
   [java.nio.file.attribute FileAttribute]
   [java.security DigestInputStream DigestOutputStream MessageDigest]
   [java.sql DriverManager]
   [java.time Duration Instant LocalDate LocalDateTime LocalTime]
   [java.util Base64 HexFormat UUID Properties]
   [java.util.regex Pattern]
   [org.apache.arrow.c ArrowArrayStream Data]
   [org.apache.arrow.memory RootAllocator]
   [org.apache.arrow.vector.ipc ArrowFileReader]
   [org.duckdb DuckDBConnection DuckDBResultSet]))


(def ^:private arrow-batch-size 4096)


(def ^:private no-link-options
  (make-array LinkOption 0))


(def ^:private no-file-attributes
  (make-array FileAttribute 0))


(def ^:private no-open-options
  (make-array OpenOption 0))


(defn- artifact-error!
  [type message data]
  (throw (ex-info message (assoc data :collet.error/type type))))


(defn- ->path
  ^Path
  [value]
  (cond
    (instance? Path value) value
    :else (Paths/get (str value) (make-array String 0))))


(defn- exists?
  [^Path path]
  (Files/exists path no-link-options))


(defn data-layout
  "Resolve Collet's local data layout without moving existing Datalevin data."
  [data-dir]
  (let [root    (->path data-dir)
        db-dir  (.resolve root "db")
        legacy? (some #(exists? (.resolve root ^String %))
                      ["VERSION" "data.mdb" "lock.mdb"])]
    (when (and legacy? (exists? db-dir))
      (artifact-error!
       :collet.error/ambiguous-data-layout
       "COLLET_DATA_DIR contains both a legacy Datalevin store and db/."
       {:data-dir (str root)}))
    {:data-dir     root
     :db-dir       (if legacy? root db-dir)
     :artifact-dir (.resolve root "artifacts")
     :legacy?      (boolean legacy?)}))


(defn ensure-artifact-dir!
  ^Path
  [artifact-dir]
  (let [path (->path artifact-dir)]
    (Files/createDirectories path no-file-attributes)
    path))


(defn- artifact-directory
  ^Path
  ([artifact-dir artifact]
   (artifact-directory artifact-dir
                       (:artifact/run-id artifact)
                       (:artifact/task-id artifact)
                       (:artifact/id artifact)))
  ([artifact-dir run-id task-id artifact-id]
   (.resolve (->path artifact-dir)
             (str "runs/" run-id "/tasks/" task-id "/" artifact-id))))


(defn- artifact-data-file
  [artifact]
  (case (:artifact/kind artifact)
    :dataset "data.parquet"
    :scalar "value.edn"
    (artifact-error!
     :collet.error/invalid-artifact
     "Artifact has no supported kind."
     {:artifact/id (:artifact/id artifact)
      :kind        (:artifact/kind artifact)})))


(defn- checksum
  [^bytes bytes]
  (str "sha256:" (.formatHex (HexFormat/of) bytes)))


(defn- force-file!
  [^Path path]
  (with-open [channel (->> (into-array OpenOption [StandardOpenOption/WRITE])
                           (FileChannel/open path))]
    (.force channel true))
  path)


(defn- delete-tree!
  [^Path path]
  (when (exists? path)
    (Files/walkFileTree path
                        (proxy [SimpleFileVisitor] []
                          (visitFile [file _]
                            (Files/deleteIfExists file)
                            FileVisitResult/CONTINUE)
                          (postVisitDirectory [directory error]
                            (when error (throw error))
                            (Files/deleteIfExists directory)
                            FileVisitResult/CONTINUE)))))


(defn- scalar-error!
  [path value]
  (artifact-error!
   :collet.error/non-durable-output
   (str "Task output contains a value that cannot be stored durably at "
        (pr-str path)
        ".")
   {:path       path
    :value-type (some-> value
                        class
                        .getName)}))


(defn- portable-scalar
  [path value]
  (durable/value
   path
   value
   {:extension?              #(or (instance? UUID %)
                                  (bytes? %)
                                  (instance? Pattern %)
                                  (instance? Instant %)
                                  (instance? LocalDate %)
                                  (instance? LocalTime %)
                                  (instance? LocalDateTime %)
                                  (instance? Duration %))
    :encode-extension        (fn [_ value]
                               (cond
                                 (instance? UUID value)
                                 (tagged-literal 'uuid (str value))

                                 (bytes? value)
                                 (tagged-literal
                                  'collet/bytes
                                  (.encodeToString (Base64/getEncoder) ^bytes value))

                                 (instance? Pattern value)
                                 (tagged-literal
                                  'collet/regex
                                  {:pattern (.pattern ^Pattern value)
                                   :flags   (.flags ^Pattern value)})

                                 (instance? Instant value)
                                 (tagged-literal 'inst (str value))

                                 (instance? LocalDate value)
                                 (tagged-literal 'collet/local-date (str value))

                                 (instance? LocalTime value)
                                 (tagged-literal 'collet/local-time (str value))

                                 (instance? LocalDateTime value)
                                 (tagged-literal 'collet/local-date-time (str value))

                                 :else
                                 (tagged-literal 'collet/duration (str value))))
    :unsupported!            scalar-error!
    :materialize-sequential? true}))


(defn- parse-tagged-string
  [tag parser value]
  (if (string? value)
    (try
      (parser ^String value)
      (catch Throwable error
        (artifact-error!
         :collet.error/invalid-scalar-artifact
         "Scalar artifact contains an invalid tagged value."
         {:tag tag :value value :cause (ex-message error)})))
    (artifact-error!
     :collet.error/invalid-scalar-artifact
     "Scalar artifact contains an invalid tagged value."
     {:tag tag :value value})))


(defn- reject-scalar-tag
  [tag value]
  (artifact-error!
   :collet.error/invalid-scalar-artifact
   "Scalar artifact contains an unsupported tagged value."
   {:tag tag :value value}))


(def ^:private scalar-readers
  {'inst                   #(parse-tagged-string 'inst Instant/parse %)
   'collet/bytes           #(parse-tagged-string
                             'collet/bytes
                             (fn [value]
                               (.decode (Base64/getDecoder) ^String value))
                             %)
   'collet/regex           (fn [value]
                             (if (and (map? value)
                                      (string? (:pattern value))
                                      (int? (:flags value)))
                               (Pattern/compile (:pattern value) (:flags value))
                               (artifact-error!
                                :collet.error/invalid-scalar-artifact
                                "Scalar artifact contains an invalid regex value."
                                {:tag 'collet/regex :value value})))
   'collet/local-date      #(parse-tagged-string 'collet/local-date ^[CharSequence] LocalDate/parse %)
   'collet/local-time      #(parse-tagged-string 'collet/local-time ^[CharSequence] LocalTime/parse %)
   'collet/local-date-time #(parse-tagged-string 'collet/local-date-time ^[CharSequence] LocalDateTime/parse %)
   'collet/duration        #(parse-tagged-string 'collet/duration Duration/parse %)})


(defn encode-row [row]
  (pr-str (portable-scalar [] row)))


(defn decode-row [text]
  (edn/read-string {:readers scalar-readers :default reject-scalar-tag} text))


(defn- read-edn-file
  [^Path path readers]
  (with-open [reader (PushbackReader.
                      (BufferedReader.
                       (InputStreamReader.
                        (Files/newInputStream path no-open-options)
                        StandardCharsets/UTF_8)))]
    (let [eof    ::eof
          opts   {:eof     eof
                  :readers readers
                  :default reject-scalar-tag}
          value  (edn/read opts reader)
          suffix (edn/read opts reader)]
      (when (= eof value)
        (artifact-error!
         :collet.error/invalid-scalar-artifact
         "Scalar artifact is empty."
         {:path (str path)}))
      (when-not (= eof suffix)
        (artifact-error!
         :collet.error/invalid-scalar-artifact
         "Scalar artifact contains more than one EDN value."
         {:path (str path)}))
      value)))


(defn- write-edn-file!
  [^Path path value]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [stream (DigestOutputStream.
                        (Files/newOutputStream
                         path
                         (into-array OpenOption
                                     [StandardOpenOption/CREATE_NEW
                                      StandardOpenOption/WRITE]))
                        digest)
                writer (BufferedWriter.
                        (OutputStreamWriter. stream StandardCharsets/UTF_8))]
      (binding [*out* writer]
        (prn value)))
    (force-file! path)
    {:checksum (checksum (.digest digest))
     :bytes    (Files/size path)}))


(defn- sql-literal
  [value]
  (str "'" (string/replace (str value) "'" "''") "'"))


(defn- physical-descriptor
  [descriptor]
  (case (:type descriptor)
    :struct
    (update descriptor :fields #(mapv physical-descriptor %))

    :map
    (-> descriptor
        (update :key-type physical-descriptor)
        (update :value-type physical-descriptor))

    (:list :fixed-size-list)
    (update descriptor :element physical-descriptor)

    :decimal
    (if (or (= 256 (:bit-width descriptor))
            (= :big-integer (:logical-type descriptor)))
      (select-keys (assoc descriptor :type :string)
                   [:key :name :type :nullable?])
      descriptor)

    (:time-nanoseconds :epoch-nanoseconds :duration)
    (select-keys (assoc descriptor :type :int64)
                 [:key :name :type :nullable?])

    descriptor))


(defn- physical-schema
  [schema]
  (assoc schema :fields (mapv physical-descriptor (:fields schema))))


(defn- physical-value
  [descriptor value]
  (when-some [value value]
    (case (:type descriptor)
      :struct
      (reduce (fn [result field]
                (assoc result
                  (:key field)
                  (physical-value field (get value (:key field)))))
              {}
              (:fields descriptor))

      :map
      (into {}
            (map (fn [[key item]]
                   [(physical-value (:key-type descriptor) key)
                    (physical-value (:value-type descriptor) item)]))
            value)

      (:list :fixed-size-list)
      (mapv #(physical-value (:element descriptor) %) value)

      :decimal
      (if (or (= 256 (:bit-width descriptor))
              (= :big-integer (:logical-type descriptor)))
        (str value)
        value)

      :time-nanoseconds
      (if (instance? LocalTime value)
        (.toNanoOfDay ^LocalTime value)
        value)

      :epoch-nanoseconds
      (if (instance? Instant value)
        (arrow/instant->nanos value)
        value)

      :duration
      (if (instance? Duration value)
        (arrow/duration->micros value)
        value)

      value)))


(defn- arrow->physical-ipc!
  [^Path source ^Path target schema]
  (let [physical-schema   (physical-schema schema)
        record-descriptor (assoc schema :type :struct)]
    (with-open [writer (arrow/make-writer target physical-schema)]
      (reduce (fn [[record-count max-width] batch]
                (let [rows (mapv #(physical-value record-descriptor %) batch)]
                  (when (seq rows)
                    (arrow/write writer rows))
                  [(+ record-count (count rows)) (reduce max max-width (map stream/estimated-bytes rows))]))
              [0 1]
              (arrow/read-arrow-record-batches source schema)))))


(defn- write-parquet!
  [^Path source ^Path target]
  (with-open [allocator  (RootAllocator.)
              channel    (->> (into-array OpenOption [StandardOpenOption/READ])
                              (FileChannel/open source))
              reader     (ArrowFileReader. channel allocator)
              stream     (ArrowArrayStream/allocateNew allocator)
              connection (DriverManager/getConnection "jdbc:duckdb:")]
    (Data/exportArrayStream allocator reader stream)
    (.registerArrowStream ^DuckDBConnection connection "input" stream)
    (with-open [statement (.createStatement connection)]
      (.execute statement "SET threads=1")
      (.execute statement "SET preserve_insertion_order=false")
      (.execute statement "SET memory_limit='128MB'")
      (.execute statement
                (str "COPY input TO "
                     (sql-literal target)
                     " (FORMAT PARQUET, COMPRESSION ZSTD, ROW_GROUP_SIZE_BYTES '8MB')"))))
  (force-file! target)
  target)


(defn- part-path [^Path directory index]
  (.resolve directory (str index ".parquet")))


(defn- payload-stats [files]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes  (reduce (fn [total path]
                         (with-open [in (DigestInputStream. (Files/newInputStream path no-open-options) digest)]
                           (+ total (.transferTo in (OutputStream/nullOutputStream)))))
                       0
                       files)]
    {:checksum (checksum (.digest digest)) :bytes bytes}))


(defn- payload-files [path artifact]
  (if-let [n (:artifact/parts artifact)]
    (map #(part-path path %) (range n))
    [path]))


(defn- write-parts! [ipc directory schema]
  (Files/createDirectory directory no-file-attributes)
  (let [source (arrow/read-arrow-record-batches ipc schema)
        close  (:collet/close (meta source))
        piece  (.resolveSibling ipc "part.arrow")]
    (try
      (reduce (fn [i rows]
                (with-open [writer (arrow/make-writer piece schema)]
                  (arrow/write writer rows))
                (write-parquet! piece (part-path directory i))
                (inc i))
              0
              source)
      (finally
        (close)
        (Files/deleteIfExists piece)))))


(defn- manifest
  [artifact]
  (cond-> {:collet.artifact/version 1
           :artifact/id             (str (:artifact/id artifact))
           :artifact/run-id         (str (:artifact/run-id artifact))
           :artifact/task-id        (str (:artifact/task-id artifact))
           :artifact/kind           (:artifact/kind artifact)
           :artifact/format         (:artifact/format artifact)
           :artifact/version        (:artifact/version artifact)
           :artifact/data-file      (artifact-data-file artifact)
           :artifact/checksum       (:artifact/checksum artifact)
           :artifact/bytes          (:artifact/bytes artifact)
           :artifact/records        (:artifact/records artifact)
           :artifact/schema         (:artifact/schema artifact)
           :artifact/created-at     (:artifact/created-at artifact)}
    (:artifact/max-row-bytes artifact) (assoc :artifact/max-row-bytes (:artifact/max-row-bytes artifact))
    (:artifact/parts artifact) (assoc :artifact/parts (:artifact/parts artifact))))


(defn- read-manifest
  [^Path directory artifact]
  (let [path (.resolve directory "manifest.edn")]
    (when-not (exists? path)
      (artifact-error!
       :collet.error/artifact-corrupt
       "Artifact manifest is missing."
       {:artifact/id (:artifact/id artifact) :path (str path)}))
    (read-edn-file path {})))


(defn- validate-artifact!
  "Validate the immutable local artifact manifest and payload checksum."
  [^Path directory artifact]
  (let [data-file         (artifact-data-file artifact)
        actual-manifest   (read-manifest directory artifact)
        expected-manifest (manifest artifact)
        data-path         (.resolve directory ^String data-file)]
    (when-not (= expected-manifest actual-manifest)
      (artifact-error!
       :collet.error/artifact-corrupt
       "Artifact manifest does not match its durable metadata."
       {:artifact/id (:artifact/id artifact)
        :expected    expected-manifest
        :actual      actual-manifest}))
    (when-not (exists? data-path)
      (artifact-error!
       :collet.error/artifact-corrupt
       "Artifact payload is missing."
       {:artifact/id (:artifact/id artifact) :path (str data-path)}))
    (let [{actual-checksum :checksum actual-bytes :bytes} (payload-stats (payload-files data-path artifact))]
      (when-not (= actual-checksum (:artifact/checksum artifact))
        (artifact-error!
         :collet.error/artifact-corrupt
         "Artifact payload checksum does not match its durable metadata."
         {:artifact/id (:artifact/id artifact)
          :expected    (:artifact/checksum artifact)
          :actual      actual-checksum}))
      (when-not (= actual-bytes (:artifact/bytes artifact))
        (artifact-error!
         :collet.error/artifact-corrupt
         "Artifact payload byte count does not match its durable metadata."
         {:artifact/id (:artifact/id artifact)
          :expected    (:artifact/bytes artifact)
          :actual      actual-bytes})))
    artifact))


(defn- publish-directory!
  [^Path staging ^Path final]
  (Files/createDirectories (.getParent final) no-file-attributes)
  (Files/move staging final (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE]))
  final)


(defn- stage-directory
  ^Path
  [^Path root run-id task-id artifact-id]
  (doto (.resolve root (str ".staging/runs/" run-id "/tasks/" task-id "/" artifact-id))
    (Files/createDirectories no-file-attributes)))


(defn- write-manifest!
  [^Path directory artifact]
  (write-edn-file! (.resolve directory "manifest.edn")
                   (manifest artifact)))


(defn- dataset-artifact
  [^Path artifact-dir run-id task-id source schema]
  (let [schema      (arrow/normalize-schema schema)
        artifact-id (random-uuid)
        created-at  (System/currentTimeMillis)
        staging     (stage-directory artifact-dir run-id task-id artifact-id)
        final       (artifact-directory artifact-dir run-id task-id artifact-id)
        ipc         (.resolve staging "physical.arrow")
        parquet     (.resolve staging "data.parquet")]
    (try
      (let [[records max-width] (arrow->physical-ipc! (->path source) ipc schema)
            ;; DuckDB can coalesce many Arrow batches into a single native vector.
            ;; For wide rows, each COPY and scan receives just one bounded IPC batch.
            parts (if (> (* (min records 2048) max-width) stream/byte-target)
                    (write-parts! ipc parquet (physical-schema schema))
                    (do (write-parquet! ipc parquet) nil))
            stats (payload-stats (payload-files parquet {:artifact/parts parts}))]
        (Files/deleteIfExists ipc)
        (let [artifact (cond-> {:artifact/id            artifact-id
                                :artifact/run-id        run-id
                                :artifact/task-id       task-id
                                :artifact/kind          :dataset
                                :artifact/format        :parquet
                                :artifact/version       (if parts 2 1)
                                :artifact/checksum      (:checksum stats)
                                :artifact/schema        schema
                                :artifact/records       records
                                :artifact/max-row-bytes max-width
                                :artifact/bytes         (:bytes stats)
                                :artifact/created-at    created-at}
                         parts (assoc :artifact/parts parts))]
          (write-manifest! staging artifact)
          (validate-artifact! staging artifact)
          (publish-directory! staging final)
          artifact))
      (catch Throwable error
        (delete-tree! staging)
        (throw error)))))


(defn- scalar-artifact
  [^Path artifact-dir run-id task-id value]
  (let [artifact-id (random-uuid)
        created-at  (System/currentTimeMillis)
        staging     (stage-directory artifact-dir run-id task-id artifact-id)
        final       (artifact-directory artifact-dir run-id task-id artifact-id)
        value-file  (.resolve staging "value.edn")]
    (try
      (let [{:keys [checksum bytes]}
            (write-edn-file!
             value-file
             {:collet.scalar/version 1
              :collet.scalar/value   (portable-scalar [] value)})
            artifact {:artifact/id         artifact-id
                      :artifact/run-id     run-id
                      :artifact/task-id    task-id
                      :artifact/kind       :scalar
                      :artifact/format     :edn
                      :artifact/version    1
                      :artifact/checksum   checksum
                      :artifact/bytes      bytes
                      :artifact/created-at created-at}]
        (write-manifest! staging artifact)
        (validate-artifact! staging artifact)
        (publish-directory! staging final)
        artifact)
      (catch Throwable error
        (delete-tree! staging)
        (throw error)))))


(defn publish-output!
  "Publish one run-owned output. Dataset output maps require :file and :schema."
  [artifact-dir run-id task-id output]
  (let [artifact-dir (ensure-artifact-dir! artifact-dir)
        output-kind  (:output/kind output)
        artifact     (case output-kind
                       :dataset
                       (dataset-artifact artifact-dir
                                         run-id
                                         task-id
                                         (:output/file output)
                                         (:output/schema output))

                       :scalar
                       (scalar-artifact artifact-dir
                                        run-id
                                        task-id
                                        (:output/value output))

                       (artifact-error!
                        :collet.error/invalid-output
                        "Durable task output has no supported artifact kind."
                        {:output output}))]
    {:artifact artifact
     :output   {:output/kind output-kind
                :output/ref  [:artifact/id (:artifact/id artifact)]}}))


(defn delete-artifacts!
  "Delete published payload directories after their Store metadata is released."
  [artifact-dir artifacts]
  (doseq [artifact artifacts]
    (delete-tree! (artifact-directory artifact-dir artifact))))


(defn delete-runs!
  "Delete internal run payloads after their Store metadata has been removed."
  [artifact-dir run-ids]
  (doseq [id run-ids]
    (when-not (uuid? id)
      (throw (ex-info "Expected a run UUID." {:run-id id})))
    (delete-tree! (.resolve (->path artifact-dir) (str "runs/" id)))))


(defn- read-scalar
  [artifact-dir artifact]
  (let [directory (artifact-directory artifact-dir artifact)]
    (validate-artifact! directory artifact)
    (let [envelope (read-edn-file (.resolve directory "value.edn")
                                  scalar-readers)]
      (case (:collet.scalar/version envelope)
        1
        (if (contains? envelope :collet.scalar/value)
          (:collet.scalar/value envelope)
          (artifact-error!
           :collet.error/invalid-scalar-artifact
           "Scalar artifact has no value."
           {:artifact/id (:artifact/id artifact)}))

        (artifact-error!
         :collet.error/invalid-scalar-artifact
         "Scalar artifact has an unsupported version."
         {:artifact/id (:artifact/id artifact)
          :version     (:collet.scalar/version envelope)})))))


(defn- open-part
  [data-path artifact]
  (let [connection (DriverManager/getConnection "jdbc:duckdb:" (doto (Properties.) (.setProperty "jdbc_stream_results" "true")))
        statement  (.createStatement connection)
        _          (.execute statement "SET threads=1")
        _          (.execute statement "SET memory_limit='128MB'")
        result-set ^DuckDBResultSet
                   (.executeQuery statement
                                  (str "SELECT * FROM read_parquet(" (sql-literal data-path) ")"))
        allocator  (RootAllocator.)
        reader     (.arrowExportStream result-set
                                       allocator
                                       (int (if-let [width (:artifact/max-row-bytes artifact)]
                                              (max 1 (min arrow-batch-size (quot stream/byte-target width)))
                                              1)))
        close!     (fn []
                     (doseq [closeable [reader result-set statement connection allocator]]
                       (try
                         (.close ^AutoCloseable closeable)
                         (catch Throwable _ nil))))]
    (try
      (arrow/reader->dataset-seq reader (:artifact/schema artifact) close!)
      (catch Throwable error
        (close!)
        (throw error)))))


(defn read-artifact
  [artifact-dir artifact]
  (case (:artifact/kind artifact)
    :dataset (do
               (validate-artifact! (artifact-directory artifact-dir artifact) artifact)
               (stream/managed-view #(let [scope stream/*resources*
                                           path  (.resolve (artifact-directory artifact-dir artifact) "data.parquet")]
                                       (letfn [(step [paths]
                                                 (lazy-seq
                                                  (when-let [s (seq paths)]
                                                    (concat (binding [stream/*resources* scope] (open-part (first s) artifact))
                                                            (step (rest s))))))]
                                         (step (payload-files path artifact))))
                                    {:arrow-columns (:artifact/schema artifact)
                                     :rows-count    (:artifact/records artifact)}))
    :scalar (read-scalar artifact-dir artifact)
    (artifact-error!
     :collet.error/invalid-artifact
     "Artifact has no supported kind."
     {:artifact/id (:artifact/id artifact)
      :kind        (:artifact/kind artifact)})))
