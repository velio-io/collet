(ns collet.spike.issue-45
  (:require
   [clojure.data.json :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [collet.arrow :as collet-arrow]
   [tech.v3.dataset :as ds])
  (:import
    [java.io FileOutputStream]
    [java.lang ProcessBuilder$Redirect ProcessHandle]
    [java.lang.management ManagementFactory]
    [java.net URI]
    [java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse$BodyHandlers]
    [java.nio.channels FileChannel]
    [java.nio.file Files Path Paths StandardCopyOption StandardOpenOption]
    [java.nio.file.attribute FileAttribute]
    [java.security MessageDigest]
    [java.math BigInteger]
    [java.sql Array Connection DriverManager ResultSet ResultSetMetaData SQLException Struct Timestamp]
    [java.time Instant LocalDateTime ZoneOffset]
    [java.util Map Properties UUID]
    [java.util.zip ZipInputStream]
    [org.apache.arrow.memory RootAllocator]
    [org.apache.arrow.vector VectorSchemaRoot]
    [org.apache.arrow.vector.ipc ArrowFileReader ArrowFileWriter]
    [org.apache.arrow.vector.util Text]
    [org.duckdb DuckDBResultSet]))


(def default-config
  ;; The benchmark defaults encode the requested one-GiB logical embedding
  ;; input. Gate 1 deliberately caps its generated rows at 512.
  {:rows                1048576
   :embedding-width     256
   :batch-rows          4096
   :duckdb-memory-limit "256MiB"
   :repetitions         3
   :output-dir          "target/spike-45"})


(def profile-ddl
  "The nested profile schema used for every format in this spike. The embedding
  width is supplied separately because DuckDB fixed-size arrays are width-aware."
  (str "CREATE TABLE profiles ("
       "id BIGINT, "
       "name VARCHAR, "
       "active BOOLEAN, "
       "score DOUBLE, "
       "created_at TIMESTAMP, "
       "address STRUCT(line VARCHAR, city VARCHAR, zip INTEGER), "
       "attributes MAP(VARCHAR, VARCHAR), "
       "tags VARCHAR[], "
       "events STRUCT(kind VARCHAR, happened_at TIMESTAMP, score INTEGER)[], "
       "embedding FLOAT[%d])"))


(def profile-type-matrix
  [{:column :id :duckdb-type "BIGINT" :arrow-type "Int(64, signed)" :collet-arrow :supported}
   {:column :name :duckdb-type "VARCHAR" :arrow-type "Utf8" :collet-arrow :supported}
   {:column :active :duckdb-type "BOOLEAN" :arrow-type "Bool" :collet-arrow :supported}
   {:column :score :duckdb-type "DOUBLE" :arrow-type "FloatingPoint(DOUBLE)" :collet-arrow :supported}
   {:column :created_at :duckdb-type "TIMESTAMP" :arrow-type "Timestamp(MICROSECOND)" :collet-arrow :supported}
   {:column :address :duckdb-type "STRUCT" :arrow-type "Struct" :collet-arrow :unsupported}
   {:column :attributes :duckdb-type "MAP(VARCHAR, VARCHAR)" :arrow-type "Map" :collet-arrow :unsupported}
   {:column :tags :duckdb-type "VARCHAR[] with null elements" :arrow-type "List<Utf8>" :collet-arrow :unsupported}
   {:column :events :duckdb-type "STRUCT[] with null elements" :arrow-type "List<Struct>" :collet-arrow :unsupported}
   {:column :embedding :duckdb-type "FLOAT[n]" :arrow-type "FixedSizeList<Float32>[n]" :collet-arrow :unsupported}])


(def duckdb-native-version "1.5.5")


(def lance-java-version "9.0.1")


(def lance-arrow-version "18.3.0")


(def collet-arrow-version "19.0.0")


(def orphan-fragment-exit-code 86)


(def duckdb-extension-versions
  ;; These are the exact core-extension builds observed for DuckDB 1.5.5.
  ;; INSTALL reuses a cache when present; checking this metadata prevents a
  ;; mutable repository result from being treated as spike evidence.
  {"lance"  "2f167ea"
   "httpfs" "827222f"})


(def duckdb-extension-sha256
  {"lance"  {"osx_arm64"   "b0753f592047d3016465ad661275ba45def627b566461b54eb842e27dd5fb85a"
             "linux_amd64" "a8b1463e8541a960859b05c39096a60a3c777d12fd63d9867ce62bb251ab2a1a"}
   "httpfs" {"osx_arm64"   "10514b4ef19f80bf4ec4bf90c124f5d34625f2606263b40ae8ec7979905bb779"
             "linux_amd64" "887c392b1e49128d11667c81e3698d8b00dfdeb456771acf66d05a0f74f7b7d8"}})


(def duckdb-native-sha256
  {"osx_universal" "b9027d18ef3e8d960568f77e946a83ca68009cb22d71cf7f94de842afdd094d8"
   "linux_amd64"   "fc23f12e376c47be520f75221288281906e7942e8fd6f6ce4849198ba60d0405"})


(defn- now []
  (str (Instant/now)))


(defn- file-attributes []
  (make-array FileAttribute 0))


(defn- output-directory
  [{:keys [output-dir]}]
  (doto (Paths/get output-dir (make-array String 0))
    (Files/createDirectories (file-attributes))))


(defn- output-path
  [config command]
  (.resolve (output-directory config) (str command ".edn")))


(defn- write-edn!
  [config command result]
  (let [path    (output-path config command)
        payload (assoc result
                  :timestamp (now)
                  :configuration config
                  :jvm {:java-version (System/getProperty "java.version")
                        :java-vm      (System/getProperty "java.vm.name")
                        :os-name      (System/getProperty "os.name")
                        :os-arch      (System/getProperty "os.arch")})]
    (spit (str path) (with-out-str (prn payload)))
    (println (str path))
    payload))


(defn- parse-long-option
  [option value]
  (or (parse-long value)
      (throw (ex-info "Expected an integer option value" {:option option :value value}))))


(defn- parse-args
  [args]
  (loop [config default-config
         [arg value & remaining :as args] args]
    (cond
      (empty? args) config
      (= arg "--rows") (recur (assoc config :rows (parse-long-option arg value)) remaining)
      (= arg "--embedding-width") (recur (assoc config :embedding-width (parse-long-option arg value)) remaining)
      (= arg "--batch-rows") (recur (assoc config :batch-rows (parse-long-option arg value)) remaining)
      (= arg "--memory-limit") (recur (assoc config :duckdb-memory-limit value) remaining)
      (= arg "--repetitions") (recur (assoc config :repetitions (parse-long-option arg value)) remaining)
      (= arg "--output-dir") (recur (assoc config :output-dir value) remaining)
      :else (throw (ex-info "Unknown spike option" {:arg arg :args args})))))


(defn- sql-literal
  [value]
  (str "'" (str/replace (str value) "'" "''") "'"))


(defn- sql!
  [^Connection connection sql]
  (with-open [statement (.createStatement connection)]
    (.execute statement sql)))


(declare canonical-value)


(defn- canonical-array
  [value]
  (let [length (java.lang.reflect.Array/getLength value)]
    (mapv #(canonical-value (java.lang.reflect.Array/get value %)) (range length))))


(defn- canonical-value
  "Convert JDBC and Arrow objects to EDN-shaped values for comparison. This is
  structural conversion only: unsupported values are recorded instead of being
  stringified or JSON-encoded."
  [value]
  (cond
    (nil? value) nil
    (instance? Text value) (str value)
    ;; DuckDB TIMESTAMP has no zone. Canonicalize it as a local timestamp so
    ;; JDBC's default-zone conversion cannot create a false Arrow mismatch.
    (instance? Timestamp value) (str (.toLocalDateTime ^Timestamp value))
    (instance? LocalDateTime value) (str value)
    (instance? Array value) (canonical-value (.getArray ^Array value))
    (instance? Struct value) (mapv canonical-value (.getAttributes ^Struct value))
    (instance? Map value) (into (sorted-map)
                                (map (fn [[k v]] [(str k) (canonical-value v)]) value))
    (instance? java.util.Collection value) (mapv canonical-value value)
    (.isArray (class value)) (canonical-array value)
    :else value))


(defn- result-set->rows
  [^ResultSet result-set]
  (let [^ResultSetMetaData metadata (.getMetaData result-set)
        column-count (.getColumnCount metadata)
        columns      (mapv (fn [index]
                             [(keyword (.getColumnLabel metadata index)) index])
                           (range 1 (inc column-count)))]
    (loop [rows []]
      (if (.next result-set)
        (recur (conj rows
                     (into {}
                           (map (fn [[column index]]
                                  [column (canonical-value (.getObject result-set index))]))
                           columns)))
        rows))))


(declare canonical-profile-row)


(defn- query-rows
  [^Connection connection sql]
  (with-open [statement  (.createStatement connection)
              result-set (.executeQuery statement sql)]
    (mapv canonical-profile-row (result-set->rows result-set))))


(defn- query-one
  [^Connection connection sql]
  (first (query-rows connection sql)))


(defn- consume-query!
  [^Connection connection sql]
  (with-open [statement  (.createStatement connection)
              result-set (.executeQuery statement sql)]
    (loop [rows 0]
      (if (.next result-set)
        (recur (inc rows))
        rows))))


(defn- consume-query-as-arrow!
  "Consume a DuckDB query through its JDBC Arrow stream without materializing
  the rows in Clojure. This is the full-sequential durable-format benchmark
  path as well as the cold-read path."
  [^Connection connection sql batch-size]
  (with-open [statement  (.createStatement connection)
              result-set ^DuckDBResultSet (.executeQuery statement sql)
              allocator  (RootAllocator.)
              reader     (.arrowExportStream result-set allocator (int batch-size))]
    (loop [rows    0
           batches 0]
      (if (.loadNextBatch reader)
        (recur (+ rows (.getRowCount (.getVectorSchemaRoot reader)))
               (inc batches))
        {:rows                 rows
         :batches              batches
         :allocator-peak-bytes (.getPeakMemoryAllocation allocator)}))))


(defn- bounded-string
  [value]
  (let [value (str value)
        limit 32768]
    (if (> (count value) limit)
      (str (subs value 0 limit) "\n… truncated")
      value)))


(declare record-error)


(defn- explain-plan
  [^Connection connection sql]
  (let [run (fn [prefix]
              (with-open [statement  (.createStatement connection)
                          result-set (.executeQuery statement (str prefix " " sql))]
                (->> (result-set->rows result-set)
                     (mapv #(update % :explain_value bounded-string)))))]
    (try
      {:mode :verbose :rows (run "EXPLAIN (ANALYZE, VERBOSE)")}
      (catch Throwable verbose-error
        ;; DuckDB 1.5 does not implement the PostgreSQL-style VERBOSE flag.
        ;; Its documented all-output mode supplies the logical, optimized, and
        ;; physical plans; profiling below supplies execution metrics.
        (try
          (sql! connection "SET explain_output = 'all'")
          {:mode          :all
           :verbose-error (record-error verbose-error)
           :rows          (run "EXPLAIN")}
          (finally
           (sql! connection "SET explain_output = 'physical_only'")))))))


(declare profile-json profile-metrics)


(defn- profiled-consumption!
  ([^Connection connection ^Path profile-path sql]
   (profiled-consumption! connection profile-path sql consume-query!))
  ([^Connection connection ^Path profile-path sql consume!]
   (sql! connection "PRAGMA enable_profiling = 'json'")
   (sql! connection (str "PRAGMA profiling_output = " (sql-literal (.toAbsolutePath profile-path))))
   (try
     (let [consumed (consume! connection sql)
           profile  (profile-json profile-path)]
       (cond-> {:rows               (if (map? consumed) (:rows consumed) consumed)
                :profile            (profile-metrics profile)
                :profile-available? (boolean profile)
                :profile-path       (.toString profile-path)}
         (map? consumed) (assoc :arrow-consumption consumed)))
     (finally
      (sql! connection "PRAGMA disable_profiling")))))


(defn- duckdb-version
  [^Connection connection]
  (:version (query-one connection "SELECT version() AS version")))


(defn- extension-info
  [^Connection connection extension]
  (query-one connection
             (str "SELECT extension_name, installed, loaded, extension_version, "
                  "install_path, install_mode FROM duckdb_extensions() "
                  "WHERE extension_name = " (sql-literal extension))))


(defn- sha256-file
  [^Path path]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [input (Files/newInputStream path (make-array java.nio.file.OpenOption 0))]
      (let [buffer (byte-array 8192)]
        (loop []
          (let [read (.read input buffer)]
            (when (pos? read)
              (.update digest buffer 0 read)
              (recur))))))
    (format "%064x" (BigInteger. 1 (.digest digest)))))


(defn- extension-evidence
  [info]
  (let [install-path (:install_path info)
        path         (when install-path
                       (Paths/get (str install-path) (make-array String 0)))]
    (cond-> info
      (and path (Files/isRegularFile path (make-array java.nio.file.LinkOption 0)))
      (assoc :sha256 (sha256-file path)))))


(defn- pinned-extension-evidence
  [extension info]
  (let [evidence         (extension-evidence info)
        expected-version (get duckdb-extension-versions extension)
        platform         (case [(System/getProperty "os.name")
                                (System/getProperty "os.arch")]
                           ["Mac OS X" "aarch64"] "osx_arm64"
                           ["Linux" "amd64"] "linux_amd64"
                           ["Linux" "x86_64"] "linux_amd64"
                           (throw (ex-info "No pinned DuckDB extension checksum for this platform"
                                           {:extension extension
                                            :os        (System/getProperty "os.name")
                                            :arch      (System/getProperty "os.arch")})))
        expected-sha256  (get-in duckdb-extension-sha256 [extension platform])]
    (when-not (= expected-version (:extension_version evidence))
      (throw (ex-info "DuckDB installed an extension outside the pinned spike version"
                      {:extension extension
                       :expected  expected-version
                       :actual    (:extension_version evidence)})))
    (when-not (= expected-sha256 (:sha256 evidence))
      (throw (ex-info "DuckDB installed an extension with an unexpected checksum"
                      {:extension extension
                       :platform  platform
                       :expected  expected-sha256
                       :actual    (:sha256 evidence)})))
    evidence))


(defn- enable-lance!
  [^Connection connection]
  (sql! connection "INSTALL lance")
  (sql! connection "LOAD lance")
  (pinned-extension-evidence "lance" (extension-info connection "lance")))


(defn- enable-httpfs!
  [^Connection connection]
  (sql! connection "INSTALL httpfs")
  (sql! connection "LOAD httpfs")
  (pinned-extension-evidence "httpfs" (extension-info connection "httpfs")))


(defn- s3-scope
  [bucket]
  (str "s3://" bucket "/"))


(defn- configure-localstack-s3!
  [^Connection connection bucket endpoint]
  ;; Keep HTTP and path-style addressing confined to the ephemeral LocalStack
  ;; session.  No credentials or endpoint settings leave the spike process.
  (let [scope (s3-scope bucket)]
    (sql! connection
          (str "CREATE SECRET spike_s3 ("
               "TYPE s3, PROVIDER config, "
               "KEY_ID 'test', SECRET 'test', REGION 'eu-west-1', "
               "ENDPOINT "
               (sql-literal endpoint)
               ", "
               "URL_STYLE 'path', USE_SSL false, SCOPE "
               (sql-literal scope)
               ")"))
    (sql! connection
          (str "CREATE SECRET spike_lance ("
               "TYPE lance, PROVIDER config, SCOPE " (sql-literal scope)
               ", "
               "ACCESS_KEY_ID 'test', SECRET_ACCESS_KEY 'test', REGION 'eu-west-1', "
               "ENDPOINT " (sql-literal (str "http://" endpoint))
               ", "
               "VIRTUAL_HOSTED_STYLE_REQUEST false, ALLOW_HTTP true)"))
    {:scope    scope
     :endpoint endpoint
     :httpfs   (extension-evidence (extension-info connection "httpfs"))
     :lance    (extension-evidence (extension-info connection "lance"))}))


(defn- check-row-count
  [config]
  (min 512 (:rows config)))


(defn- profile-select-sql
  [start end embedding-width]
  (format
   (str "SELECT id::BIGINT AS id, "
        "CASE WHEN id %% 11 = 0 THEN NULL ELSE 'profile-' || id::VARCHAR END AS name, "
        "id %% 2 = 0 AS active, "
        "(id %% 1000)::DOUBLE / 10 AS score, "
        "TIMESTAMP '2025-01-01 00:00:00' + id * INTERVAL 1 SECOND AS created_at, "
        "CASE WHEN id %% 5 = 0 THEN NULL ELSE "
        "struct_pack(line := CASE WHEN id %% 3 = 0 THEN NULL ELSE 'line-' || id::VARCHAR END, "
        "city := 'city-' || (id %% 7)::VARCHAR, zip := (10000 + id %% 89999)::INTEGER) END AS address, "
        "CASE WHEN id %% 6 = 0 THEN NULL ELSE "
        "map(['role', 'tier'], ['role-' || (id %% 3)::VARCHAR, "
        "CASE WHEN id %% 4 = 0 THEN NULL ELSE 'tier-' || (id %% 5)::VARCHAR END]) END AS attributes, "
        "CASE WHEN id %% 7 = 0 THEN NULL ELSE "
        "['tag-' || (id %% 5)::VARCHAR, NULL, 'tag-fixed'] END AS tags, "
        "CASE WHEN id %% 8 = 0 THEN NULL ELSE "
        "[struct_pack(kind := 'opened', happened_at := TIMESTAMP '2025-01-01 00:00:00' + id * INTERVAL 1 SECOND, "
        "score := (id %% 100)::INTEGER), "
        "CASE WHEN id %% 3 = 0 THEN NULL ELSE "
        "struct_pack(kind := 'closed', happened_at := TIMESTAMP '2025-01-01 00:00:00' + id * INTERVAL 2 SECOND, "
        "score := NULL::INTEGER) END] END AS events, "
        "(CASE WHEN id %% 9 = 0 THEN NULL::FLOAT[] ELSE "
        "list_transform(range(0, %d), x -> ((id + x) %% 101)::FLOAT / 100.0) END)::FLOAT[%d] AS embedding "
        "FROM range(%d, %d) AS generated(id)")
   embedding-width
   embedding-width
   start
   end))


(defn- profile-insert-sql
  [start end embedding-width]
  (str "INSERT INTO profiles " (profile-select-sql start end embedding-width)))


(defn- generate-profiles!
  [^Connection connection {:keys [rows batch-rows embedding-width]}]
  (sql! connection "DROP TABLE IF EXISTS profiles")
  (sql! connection (format profile-ddl embedding-width))
  (doseq [start (range 0 rows batch-rows)]
    (sql! connection (profile-insert-sql start (min rows (+ start batch-rows)) embedding-width)))
  {:rows       rows
   :batch-rows batch-rows
   :schema     (query-rows connection "DESCRIBE profiles")})


(defn- unique-artifact-directory
  [config command]
  (let [directory (.resolve (output-directory config)
                            (str command "-" (System/currentTimeMillis)))]
    (Files/createDirectories directory (file-attributes))
    directory))


(defn- parquet-path
  [^Path directory]
  (.resolve directory "profiles.parquet"))


(defn- lance-path
  [^Path directory]
  (.resolve directory "profiles.lance"))


(defn- write-parquet!
  [^Connection connection ^Path path]
  (sql! connection
        (str "COPY profiles TO " (sql-literal path) " (FORMAT PARQUET, COMPRESSION zstd)"))
  path)


(defn- parquet-scan-sql
  [path embedding-width]
  (str "(SELECT * EXCLUDE (embedding), embedding::FLOAT["
       embedding-width
       "] AS embedding FROM read_parquet("
       (sql-literal path)
       "))"))


(defn- write-lance!
  [^Connection connection ^Path path]
  (let [extension (enable-lance! connection)]
    (sql! connection
          (str "COPY profiles TO "
               (sql-literal path)
               " (FORMAT lance, MODE 'overwrite', data_storage_version '2.2')"))
    {:path path :extension extension}))


(defn- append-lance!
  [^Connection connection ^Path path]
  (sql! connection
        (str "COPY (SELECT * FROM profiles WHERE id = 1) TO "
             (sql-literal path)
             " (FORMAT lance, MODE 'append')"))
  path)


(defn- first-difference
  [expected actual]
  (first
   (keep-indexed (fn [index [expected-row actual-row]]
                   (when (not= expected-row actual-row)
                     {:row index :expected expected-row :actual actual-row}))
                 (map vector expected actual))))


(defn- fidelity
  [expected actual]
  (if (= expected actual)
    {:status :pass :rows (count actual)}
    {:status           :fail
     :expected-rows    (count expected)
     :actual-rows      (count actual)
     :first-difference (or (first-difference expected actual)
                           {:reason :row-count})}))


(defn- logical-schema
  [description]
  (mapv #(select-keys % [:column_name :column_type]) description))


(defn- format-fidelity
  [expected-rows actual-rows expected-schema actual-schema]
  (let [values (fidelity expected-rows actual-rows)
        schema (fidelity (logical-schema expected-schema)
                         (logical-schema actual-schema))]
    {:status          (if (and (= :pass (:status values))
                               (= :pass (:status schema)))
                        :pass
                        :fail)
     :rows            (count actual-rows)
     :value-fidelity  values
     :schema-fidelity schema
     :actual-schema   (logical-schema actual-schema)}))


(defn- struct-value
  [keys value]
  (cond
    (nil? value) nil
    (map? value) (merge (zipmap keys (repeat nil)) value)
    (vector? value) (zipmap keys value)
    :else value))


(defn- map-value
  [value]
  (if (and (vector? value) (every? map? value))
    (into (sorted-map) (map (juxt #(get % "key") #(get % "value"))) value)
    value))


(defn- canonical-profile-row
  [row]
  (cond-> row
    (contains? row :address) (update :address #(struct-value ["line" "city" "zip"] %))
    (contains? row :attributes) (update :attributes map-value)
    (contains? row :events) (update :events #(when % (mapv (partial struct-value ["kind" "happened_at" "score"]) %)))))


(defn- record-error
  [^Throwable throwable]
  (cond-> {:class      (.getName (class throwable))
           :message    (.getMessage throwable)
           :stacktrace (mapv str (.getStackTrace throwable))
           :suppressed (mapv record-error (.getSuppressed throwable))}
    (instance? SQLException throwable)
    (assoc :sql-state
      (.getSQLState ^SQLException throwable)
      :error-code
      (.getErrorCode ^SQLException throwable))

    (.getCause throwable)
    (assoc :cause (record-error (.getCause throwable)))))


(defn- class-source
  [^Class class]
  (some-> class
          .getProtectionDomain
          .getCodeSource
          .getLocation
          str))


(defn- class-present?
  [class-name]
  (try
    (Class/forName class-name false (.getContextClassLoader (Thread/currentThread)))
    true
    (catch ClassNotFoundException _
      false)))


(defn- main-dependency-evidence
  []
  (let [arrow-classes   {:vector        org.apache.arrow.vector.types.pojo.Schema
                         :memory-core   RootAllocator
                         :memory-unsafe (Class/forName "org.apache.arrow.memory.unsafe.UnsafeAllocationManager")
                         :c-data        (Class/forName "org.apache.arrow.c.ArrowArrayStream")}
        components      (into {}
                              (map (fn [[component class]]
                                     [component
                                      {:version (some-> class
                                                        .getPackage
                                                        .getImplementationVersion)
                                       :source  (class-source class)}]))
                              arrow-classes)
        arrows-aligned? (every? #(= collet-arrow-version (:version %))
                                (vals components))
        lance-present   (class-present? "org.lance.Dataset")]
    {:arrow               {:expected-version collet-arrow-version
                           :components       components}
     :lance-java          {:expected-presence    false
                           :present?             lance-present
                           :helper-version       lance-java-version
                           :helper-arrow-version lance-arrow-version}
     :dependency-aligned? (and arrows-aligned?
                               (not lance-present))}))


(defn- checked
  [name hard? f]
  (try
    (merge {:name name :hard? hard? :status :pass} (f))
    (catch Throwable throwable
      {:name name :hard? hard? :status :fail :error (record-error throwable)})))


(defn- hard-failure?
  [{:keys [hard? status]}]
  (and hard? (not= :pass status)))


(defn- child-options
  [args]
  (loop [options {}
         [arg value & remaining :as remaining-args] args]
    (cond
      (empty? remaining-args) options
      (not (#{"--path" "--version" "--result" "--bucket" "--endpoint"
              "--format" "--memory-limit" "--temp-dir" "--batch-rows"
              "--embedding-width"
              "--checkpoints"}
            arg))
      (throw (ex-info "Unknown child option" {:arg arg :args args}))
      (nil? value) (throw (ex-info "Child option is missing a value" {:arg arg :args args}))
      :else (recur (assoc options (keyword (subs arg 2)) value) remaining))))


(defn- write-child-result!
  [result-path result]
  (spit result-path (with-out-str (prn result))))


(defn- result-file
  [config label extension]
  (.resolve (output-directory config)
            (str (if (keyword? label) (name label) label)
                 "-"
                 (System/currentTimeMillis)
                 extension)))


(defn- bounded-file-text
  [^Path path]
  (when (Files/exists path (make-array java.nio.file.LinkOption 0))
    (let [text    (slurp (.toFile path))
          maximum 8192]
      (if (> (count text) maximum)
        (subs text (- (count text) maximum))
        text))))


(defn- bounded-file-head
  [^Path path]
  (when (Files/exists path (make-array java.nio.file.LinkOption 0))
    (let [text    (slurp (.toFile path))
          maximum 32768]
      (if (> (count text) maximum)
        (subs text 0 maximum)
        text))))


(defn- read-checkpoints
  [^Path path]
  (when (Files/exists path (make-array java.nio.file.LinkOption 0))
    (with-open [reader (io/reader (.toFile path))]
      (mapv (fn [line]
              (try
                (edn/read-string line)
                (catch Throwable throwable
                  {:phase :checkpoint-read-error
                   :raw   line
                   :error (record-error throwable)})))
            (line-seq reader)))))


(defn- read-child-result
  [^Path path]
  (when (Files/exists path (make-array java.nio.file.LinkOption 0))
    (try
      (edn/read-string (slurp (.toFile path)))
      (catch Throwable throwable
        {:status :unreadable
         :error  (record-error throwable)
         :raw    (bounded-file-text path)}))))


(declare file-inventory)


(defn- process-rss-by-pid
  [pid]
  (if (= "Linux" (System/getProperty "os.name"))
    (try
      (let [status (Files/readString
                    (Paths/get (str "/proc/" pid "/status") (make-array String 0)))
            kib    (some-> (re-find #"(?m)^VmRSS:\s+([0-9]+)\s+kB$" status)
                           second
                           parse-long)]
        (if kib
          {:bytes (* 1024 kib)}
          {:error {:message "VmRSS was absent from proc status"}}))
      (catch Throwable throwable
        {:error (record-error throwable)}))
    (try
      (let [process (doto (ProcessBuilder. (into-array String ["ps" "-o" "rss=" "-p" (str pid)]))
                      (.redirectErrorStream true))
            running (.start process)
            output  (slurp (.getInputStream running))
            exit    (.waitFor running)]
        (if (zero? exit)
          {:bytes (some-> output
                          str/trim
                          parse-long
                          (* 1024))}
          {:error {:exit exit :output (str/trim output)}}))
      (catch Throwable throwable
        {:error (record-error throwable)}))))


(defn- wait-for-child!
  [process]
  (loop [peak-rss       0
         samples        0
         sampling-error nil]
    (if (.isAlive process)
      (let [{:keys [bytes error]} (process-rss-by-pid (.pid process))]
        (Thread/sleep 25)
        (recur (max peak-rss (or bytes 0))
               (if bytes (inc samples) samples)
               (or sampling-error error)))
      {:exit               (.exitValue process)
       :peak-rss-bytes     (when (pos? samples) peak-rss)
       :rss-samples        samples
       :rss-sampling-error (when (zero? samples) sampling-error)})))


(defn- run-child!
  [config aliases command options]
  (let [run-directory   (doto (.resolve (output-directory config)
                                        (str "child-" (name command) "-" (UUID/randomUUID)))
                          (Files/createDirectories (file-attributes)))
        result-path     (.resolve run-directory "result.edn")
        checkpoint-path (.resolve run-directory "checkpoints.edn")
        log-path        (.resolve run-directory "output.log")
        error-pattern   (.resolve run-directory "hs_err_pid%p.log")
        args            (concat ["clojure"
                                 (str "-J-XX:ErrorFile=" error-pattern)
                                 "-J-Dclojure.main.report=stderr"
                                 aliases
                                 (name command)]
                                options
                                ["--result" (.toString result-path)
                                 "--checkpoints" (.toString checkpoint-path)])
        builder         (doto (ProcessBuilder. (into-array String args))
                          (.directory (io/file (System/getProperty "user.dir")))
                          (.redirectErrorStream true)
                          (.redirectOutput (ProcessBuilder$Redirect/to (.toFile log-path))))
        process         (.start builder)
        process-result  (wait-for-child! process)
        exit            (:exit process-result)
        result          (read-child-result result-path)
        artifacts       (file-inventory run-directory)]
    {:command            (vec args)
     :exit               exit
     :peak-rss-bytes     (:peak-rss-bytes process-result)
     :rss-samples        (:rss-samples process-result)
     :rss-sampling-error (:rss-sampling-error process-result)
     :result             result
     :checkpoints        (read-checkpoints checkpoint-path)
     :output             (bounded-file-text log-path)
     :run-directory      (.toString run-directory)
     :result-path        (.toString result-path)
     :checkpoint-path    (.toString checkpoint-path)
     :log-path           (.toString log-path)
     :artifacts          artifacts
     :error-reports      (into (sorted-map)
                               (keep (fn [[file entry]]
                                       (when (str/starts-with? file "hs_err_pid")
                                         [file
                                          (assoc entry
                                            :path (.toString (.resolve run-directory file))
                                            :head (bounded-file-head (.resolve run-directory file)))])))
                               artifacts)}))


(defn- run-main-child!
  [config command options]
  (run-child! config "-M:run:ducktape" command options))


(defn- run-lance-child!
  ([config command options]
   (run-lance-child! config "-M:lance-java-aligned" command options))
  ([config aliases command options]
   (run-child! config aliases command options)))


(defn- child-case
  [name hard? config aliases command options expected]
  (checked name
           hard?
           #(let [{:keys [exit result] :as child} (run-child! config aliases command options)]
              (cond
                (not= 0 exit) (assoc child :status :fail)
                (not= :pass (:status result)) (assoc child :status :fail)
                (not (every? (fn [[key value]] (= value (get result key))) expected))
                (assoc child
                  :status :fail
                  :expected expected)
                :else (assoc child :status :pass)))))


(defn- s3-project-filter-rows
  [^Connection connection parquet-uri lance-uri]
  {:parquet (query-rows connection
                        (str "SELECT id, name FROM read_parquet(" (sql-literal parquet-uri)
                             ") "
                             "WHERE id % 2 = 0 ORDER BY id"))
   :lance   (query-rows connection
                        (str "SELECT id, name FROM " (sql-literal lance-uri)
                             " "
                             "WHERE id % 2 = 0 ORDER BY id"))})


(defn- s3-open!
  [bucket endpoint]
  (Class/forName "org.duckdb.DuckDBDriver")
  (with-open [connection (DriverManager/getConnection "jdbc:duckdb:")]
    (let [httpfs (enable-httpfs! connection)
          lance  (enable-lance! connection)
          secret (configure-localstack-s3! connection bucket endpoint)
          uri    (s3-scope bucket)]
      {:httpfs httpfs
       :lance  lance
       :secret (select-keys secret [:scope :endpoint])
       :rows   (s3-project-filter-rows connection
                                       (str uri "profiles.parquet")
                                       (str uri "profiles.lance"))})))


(defn- s3-open-main!
  [args]
  (let [{:keys [bucket endpoint result]} (child-options args)
        payload (try
                  (assoc (s3-open! bucket endpoint) :status :pass)
                  (catch Throwable throwable
                    {:status :fail :error (record-error throwable)}))]
    (write-child-result! result payload)
    (when (not= :pass (:status payload))
      (System/exit 1))))


(defn- file-inventory
  [^Path root]
  (if-not (Files/exists root (make-array java.nio.file.LinkOption 0))
    {}
    (with-open [paths (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
      (into (sorted-map)
            (keep (fn [^Path path]
                    (when (Files/isRegularFile path (make-array java.nio.file.LinkOption 0))
                      [(.toString (.relativize root path))
                       {:bytes (Files/size path) :sha256 (sha256-file path)}])))
            (iterator-seq (.iterator paths))))))


(defn- inventory-bytes
  [inventory]
  (reduce + 0 (map (comp :bytes val) inventory)))


(defn- inventory-diff
  [before after]
  (let [unchanged (filter (fn [[path entry]]
                            (= (:sha256 entry) (get-in before [path :sha256])))
                          after)
        added     (filter (fn [[path _]] (not (contains? before path))) after)
        rewritten (filter (fn [[path entry]]
                            (and (contains? before path)
                                 (not= (:sha256 entry) (get-in before [path :sha256]))))
                          after)]
    {:unchanged-bytes (reduce + 0 (map (comp :bytes val) unchanged))
     :added-bytes     (reduce + 0 (map (comp :bytes val) added))
     :rewritten-bytes (reduce + 0 (map (comp :bytes val) rewritten))
     :unchanged-files (count unchanged)
     :added-files     (count added)
     :rewritten-files (count rewritten)}))


(defn- heap-snapshot
  []
  (let [usage (.getHeapMemoryUsage (ManagementFactory/getMemoryMXBean))]
    {:used      (.getUsed usage)
     :committed (.getCommitted usage)
     :max       (.getMax usage)}))


(defn- process-rss-snapshot
  []
  (try
    (let [pid     (.pid (ProcessHandle/current))
          process (doto (ProcessBuilder. (into-array String ["ps" "-o" "rss=" "-p" (str pid)]))
                    (.redirectErrorStream true))
          running (.start process)
          output  (slurp (.getInputStream running))
          exit    (.waitFor running)
          kib     (some-> output
                          str/trim
                          parse-long)]
      (if (and (zero? exit) kib)
        {:bytes (* 1024 kib)}
        {:error {:exit exit :output (str/trim output)}}))
    (catch Throwable throwable
      {:error (record-error throwable)})))


(defn- measure!
  [logical-bytes f]
  (let [before-heap (heap-snapshot)
        before-rss  (process-rss-snapshot)
        started     (System/nanoTime)
        result      (f)
        elapsed-ns  (- (System/nanoTime) started)
        elapsed-s   (/ elapsed-ns 1000000000.0)
        throughput  (when (pos? elapsed-s) (/ logical-bytes elapsed-s))]
    {:elapsed-ns             elapsed-ns
     :elapsed-ms             (/ elapsed-ns 1000000.0)
     :logical-bytes          logical-bytes
     :throughput-bytes-per-s throughput
     :throughput-mib-per-s   (when throughput (/ throughput 1048576.0))
     :jvm-heap               {:before before-heap :after (heap-snapshot)}
     :process-rss            {:before before-rss :after (process-rss-snapshot)}
     :result                 result}))


(defn- memory-limit-bytes
  [limit]
  (let [[_ value unit] (re-matches #"(?i)([0-9]+)(b|kib|mib|gib)" limit)
        multiplier     (case (str/lower-case unit)
                         "b" 1
                         "kib" 1024
                         "mib" (* 1024 1024)
                         "gib" (* 1024 1024 1024))]
    (* (parse-long value) multiplier)))


(defn- profile-json
  [^Path path]
  (when (Files/isRegularFile path (make-array java.nio.file.LinkOption 0))
    (json/read-str (slurp (.toFile path)) :key-fn keyword)))


(defn- profile-metrics
  [profile]
  (select-keys profile
               [:total_bytes_read
                :total_bytes_written
                :latency
                :cpu_time
                :rows_returned
                :system_peak_buffer_memory
                :system_peak_temp_dir_size]))


(defn- profiled-statement!
  [^Connection connection ^Path profile-path sql]
  (sql! connection "PRAGMA enable_profiling = 'json'")
  (sql! connection (str "PRAGMA profiling_output = " (sql-literal (.toAbsolutePath profile-path))))
  (try
    (sql! connection sql)
    (let [profile (profile-json profile-path)]
      {:profile            (profile-metrics profile)
       :profile-available? (boolean profile)
       :profile-path       (.toString profile-path)})
    (finally
     (sql! connection "PRAGMA disable_profiling"))))


(defn- logical-input-bytes
  [{:keys [rows embedding-width]}]
  (* rows embedding-width Float/BYTES))


(defn- ensure-directory!
  [^Path path]
  (Files/createDirectories path (file-attributes))
  path)


(defn- artifact-inventory
  [^Path path]
  (if (Files/isRegularFile path (make-array java.nio.file.LinkOption 0))
    {(.toString (.getFileName path))
     {:bytes  (Files/size path)
      :sha256 (sha256-file path)}}
    (file-inventory path)))


(defn- delete-tree!
  [^Path path]
  (when (Files/exists path (make-array java.nio.file.LinkOption 0))
    (with-open [paths (Files/walk path (make-array java.nio.file.FileVisitOption 0))]
      (doseq [^Path entry (sort-by (fn [^Path entry] (.getNameCount entry))
                                   >
                                   (iterator-seq (.iterator paths)))]
        (Files/delete entry)))))


(defn- cleanup-path!
  [label ^Path path]
  (try
    (let [inventory (file-inventory path)]
      (delete-tree! path)
      (if (Files/exists path (make-array java.nio.file.LinkOption 0))
        {:label     label
         :path      (.toString path)
         :status    :fail
         :inventory inventory
         :bytes     (inventory-bytes inventory)
         :error     {:message "Generated benchmark path still exists after cleanup"}}
        {:label     label
         :path      (.toString path)
         :status    :pass
         :inventory inventory
         :bytes     (inventory-bytes inventory)}))
    (catch Throwable throwable
      {:label  label
       :path   (.toString path)
       :status :fail
       :error  (record-error throwable)})))


(defn- cleanup-paths!
  [paths]
  (let [results (mapv (fn [[label path]] (cleanup-path! label path)) paths)]
    {:status (if (every? #(= :pass (:status %)) results) :pass :fail)
     :paths  results}))


(defn- benchmark-format-cleanup!
  [^Path artifact-directory format]
  (cleanup-paths!
   (conj (case format
           :parquet [[:artifact (.resolve artifact-directory "parquet")]
                     [:evolution (.resolve artifact-directory "parquet-evolution")]]
           :lance [[:artifact (.resolve artifact-directory "lance")]]
           :arrow-ipc [[:artifact (.resolve artifact-directory "arrow")]])
         [:spill (.resolve artifact-directory "duckdb-spill")])))


(defn- benchmark-sample-cleanup!
  [^Path artifact-directory]
  (cleanup-paths!
   [[:parquet (.resolve artifact-directory "parquet")]
    [:parquet-evolution (.resolve artifact-directory "parquet-evolution")]
    [:lance (.resolve artifact-directory "lance")]
    [:arrow-ipc (.resolve artifact-directory "arrow")]
    [:spill (.resolve artifact-directory "duckdb-spill")]]))


(defn- streaming-duckdb-connection
  []
  (let [properties (doto (Properties.)
                     (.setProperty "jdbc_stream_results" "true"))]
    (DriverManager/getConnection "jdbc:duckdb:" properties)))


(defn- benchmark-connection!
  [^Path artifact-directory config]
  (let [temp-directory (ensure-directory! (.resolve artifact-directory "duckdb-spill"))
        connection     (streaming-duckdb-connection)]
    (sql! connection (str "SET memory_limit = " (sql-literal (:duckdb-memory-limit config))))
    (sql! connection (str "SET temp_directory = " (sql-literal (.toAbsolutePath temp-directory))))
    (sql! connection "SET preserve_insertion_order = false")
    {:connection          connection
     :spill-directory     temp-directory
     :jdbc-stream-results true}))


(defn- create-benchmark-profiles!
  [^Connection connection {:keys [rows embedding-width]}]
  (sql! connection "DROP VIEW IF EXISTS profiles")
  (sql! connection
        (str "CREATE VIEW profiles AS " (profile-select-sql 0 rows embedding-width))))


(defn- format-write-sql
  [format path]
  (case format
    :parquet (str "COPY profiles TO " (sql-literal path) " (FORMAT PARQUET, COMPRESSION zstd)")
    :lance (str "COPY profiles TO "
                (sql-literal path)
                " (FORMAT lance, MODE 'overwrite', data_storage_version '2.2')")
    (throw (ex-info "No durable-format write SQL" {:format format}))))


(defn- format-scan-sql
  [format path embedding-width]
  (case format
    :parquet (parquet-scan-sql path embedding-width)
    :lance (sql-literal path)
    (throw (ex-info "No SQL scan exists for this format" {:format format}))))


(defn- format-operation-sql
  [format path embedding-width operation]
  (let [scan (format-scan-sql format path embedding-width)]
    (case operation
      :full-sequential (str "SELECT * FROM " scan)
      :projected-filtered (str "SELECT id, name FROM " scan " WHERE id % 17 = 0 ORDER BY id")
      ;; The sort key is the full embedding. It keeps the work substantially
      ;; above the 256 MiB default rather than merely sorting scalar IDs.
      :sort-join (str "SELECT p.id FROM "
                      scan
                      " p JOIN "
                      scan
                      " q ON p.id = q.id ORDER BY p.embedding")
      (throw (ex-info "Unknown benchmark operation" {:operation operation})))))


(defn- derived-embedding-sql
  [embedding-width multiplier]
  ;; Derive from the stable row ID so the operation has identical non-null
  ;; semantics in Lance Java and the Parquet replacement query. The original
  ;; embedding column remains responsible for the null-parent fidelity cases.
  (str "array_value("
       (str/join ", "
                 (map (fn [index]
                        (str "CAST(id + " index " AS FLOAT) * " multiplier "::FLOAT"))
                      (range embedding-width)))
       ")"))


(declare consume-arrow-ipc!)


(defn- cold-read!
  [format path embedding-width memory-limit temp-directory batch-size]
  (Class/forName "org.duckdb.DuckDBDriver")
  (with-open [connection (streaming-duckdb-connection)]
    (sql! connection (str "SET memory_limit = " (sql-literal memory-limit)))
    (sql! connection (str "SET temp_directory = " (sql-literal temp-directory)))
    (let [format (keyword format)
          result (case format
                   :arrow (measure! 0 #(consume-arrow-ipc! (Paths/get path (make-array String 0))))
                   :parquet (measure! 0
                                      #(consume-query-as-arrow!
                                        connection
                                        (format-operation-sql :parquet
                                                              path
                                                              embedding-width
                                                              :full-sequential)
                                        batch-size))
                   :lance (do
                            (enable-lance! connection)
                            (measure! 0
                                      #(consume-query-as-arrow!
                                        connection
                                        (format-operation-sql :lance
                                                              path
                                                              embedding-width
                                                              :full-sequential)
                                        batch-size)))
                   (throw (ex-info "Unknown cold-read format" {:format format})))]
      {:fresh-jvm               true
       :fresh-duckdb-connection true
       :jdbc-stream-results     true
       :os-page-cache           :not-flushed
       :format                  format
       :result                  result})))


(defn- cold-read-main!
  [args]
  (let [{:keys [format path embedding-width memory-limit temp-dir batch-rows result]} (child-options args)
        payload (try
                  (assoc (cold-read! format
                                     path
                                     (parse-long embedding-width)
                                     memory-limit
                                     temp-dir
                                     (parse-long batch-rows))
                    :status :pass)
                  (catch Throwable throwable
                    {:status :fail :error (record-error throwable)}))]
    (write-child-result! result payload)
    (when (not= :pass (:status payload))
      (System/exit 1))))


(defn- cold-read-case
  [config format path spill-directory]
  (let [{:keys [exit result] :as child}
        (run-main-child! config
                         :benchmark-cold-read
                         ["--format" (name format)
                          "--path" (.toString (.toAbsolutePath path))
                          "--embedding-width" (str (:embedding-width config))
                          "--memory-limit" (:duckdb-memory-limit config)
                          "--temp-dir" (.toString (.toAbsolutePath spill-directory))
                          "--batch-rows" (str (:batch-rows config))])]
    {:status (if (and (zero? exit) (= :pass (:status result))) :pass :fail)
     :child  child}))


(defn- native-package
  []
  (let [os   (System/getProperty "os.name")
        arch (System/getProperty "os.arch")]
    (case [os arch]
      ["Mac OS X" "aarch64"] {:platform "osx_universal"
                              :archive  "libduckdb-osx-universal.zip"
                              :library  "libduckdb.dylib"}
      ["Linux" "amd64"] {:platform "linux_amd64"
                         :archive  "libduckdb-linux-amd64.zip"
                         :library  "libduckdb.so"}
      ["Linux" "x86_64"] {:platform "linux_amd64"
                          :archive  "libduckdb-linux-amd64.zip"
                          :library  "libduckdb.so"}
      (throw (ex-info "DuckTape has no pinned native library for this platform"
                      {:os os :arch arch})))))


(defn- extract-library!
  [^Path archive ^Path target library-name]
  (with-open [zip (ZipInputStream. (Files/newInputStream archive (make-array java.nio.file.OpenOption 0)))]
    (loop []
      (let [entry (.getNextEntry zip)]
        (cond
          (nil? entry) (throw (ex-info "Pinned DuckDB archive did not contain its native library"
                                       {:archive archive :library library-name}))
          (= library-name (.getName entry))
          (Files/copy zip
                      target
                      (into-array java.nio.file.CopyOption [StandardCopyOption/REPLACE_EXISTING]))
          :else (recur))))))


(defn- ensure-ducktape-native!
  [_config]
  (let [{:keys [platform archive library]} (native-package)
        project-root (Paths/get (System/getProperty "user.dir") (make-array String 0))
        directory    (.resolve project-root
                               (str "target/native/duckdb-" duckdb-native-version "/" platform))
        library-path (.resolve directory library)
        archive-path (.resolve directory archive)]
    (Files/createDirectories directory (file-attributes))
    (when-not (Files/isRegularFile library-path (make-array java.nio.file.LinkOption 0))
      (let [url      (str "https://github.com/duckdb/duckdb/releases/download/v" duckdb-native-version "/" archive)
            request  (-> (HttpRequest/newBuilder (URI/create url))
                         (.GET)
                         (.build))
            client   (-> (HttpClient/newBuilder)
                         (.followRedirects HttpClient$Redirect/ALWAYS)
                         (.build))
            response (.send client request (HttpResponse$BodyHandlers/ofFile archive-path))]
        (when-not (= 200 (.statusCode response))
          (throw (ex-info "Unable to download pinned DuckDB native library"
                          {:url url :status (.statusCode response)})))
        (extract-library! archive-path library-path library)))
    (let [actual-sha256   (sha256-file library-path)
          expected-sha256 (get duckdb-native-sha256 platform)]
      (when-not (= expected-sha256 actual-sha256)
        (throw (ex-info "Pinned DuckDB native library checksum mismatch"
                        {:platform platform
                         :expected expected-sha256
                         :actual   actual-sha256
                         :library  (.toString library-path)})))
      {:version   duckdb-native-version
       :platform  platform
       :directory (.toString directory)
       :library   (.toString library-path)
       :sha256    actual-sha256})))


(defn- tmd-key
  [key]
  (if (keyword? key) (name key) (str key)))


(declare canonical-tmd-value)


(defn- canonical-tmd-value
  [value]
  (cond
    (nil? value) nil
    (instance? Instant value) (str (LocalDateTime/ofInstant value ZoneOffset/UTC))
    (instance? LocalDateTime value) (str value)
    (map? value) (into (sorted-map)
                       (map (fn [[key item]]
                              [(tmd-key key) (canonical-tmd-value item)])
                            value))
    (sequential? value) (mapv canonical-tmd-value value)
    (.isArray (class value)) (canonical-array value)
    :else (canonical-value value)))


(defn- ducktape-rows
  [config]
  (let [native       (ensure-ducktape-native! config)
        initialize!  (requiring-resolve 'ducktape.core/initialize!)
        open-db      (requiring-resolve 'ducktape.core/open-db)
        connect      (requiring-resolve 'ducktape.core/connect)
        run-query!   (requiring-resolve 'ducktape.core/run-query!)
        sql->dataset (requiring-resolve 'ducktape.core/sql->dataset)
        check-config (assoc config :rows (check-row-count config))]
    (initialize! {:duckdb-home (:directory native)})
    (with-open [db         (open-db)
                connection (connect db)]
      (run-query! connection (format profile-ddl (:embedding-width check-config)))
      (doseq [start (range 0 (:rows check-config) (:batch-rows check-config))]
        (run-query! connection
                    (profile-insert-sql start
                                        (min (:rows check-config)
                                             (+ start (:batch-rows check-config)))
                                        (:embedding-width check-config))))
      ;; DuckTape currently rejects DuckDB's DUCKDB_TYPE_ARRAY. Keep the
      ;; fixed-size embedding out of its optional view and report that fact;
      ;; the artifact fidelity paths still exercise it in full.
      (let [raw-rows (->> (sql->dataset connection
                                        "SELECT * EXCLUDE (embedding) FROM profiles ORDER BY id"
                                        {:key-fn keyword})
                          ds/rows
                          vec)
            object-columns
            {:address    (boolean (some #(map? (:address %)) raw-rows))
             :attributes (boolean (some #(map? (:attributes %)) raw-rows))
             :tags       (boolean (some #(and (vector? (:tags %))
                                              (some nil? (:tags %)))
                                        raw-rows))
             :events     (boolean (some #(and (vector? (:events %))
                                              (some nil? (:events %))
                                              (some map? (:events %)))
                                        raw-rows))}
            columns [:id :name :active :score :created_at :address :attributes :tags :events]
            nil-row (zipmap columns (repeat nil))
            rows (mapv #(canonical-profile-row
                         (merge nil-row
                                (into {}
                                      (map (fn [[key value]]
                                             [key (canonical-tmd-value value)])
                                           %))))
                       raw-rows)
            omitted-null-columns
            (->> columns
                 (filter (fn [column]
                           (some #(not (contains? % column)) raw-rows)))
                 vec)]
        {:native               native
         :object-columns       object-columns
         :unsupported-cells    [:embedding]
         ;; tech.ml.dataset rows omit a top-level key whose value is null. The
         ;; schema still retains that column; restore nil only in the
         ;; comparison shape and report the representation explicitly.
         :omitted-null-columns omitted-null-columns
         :rows                 rows}))))


(defn- ducktape-case
  [config expected]
  (let [case (checked :ducktape-nested-tmd-view
                      false
                      #(let [{:keys [native object-columns rows unsupported-cells omitted-null-columns]}
                             (ducktape-rows config)
                             expected        (mapv (fn [row]
                                                     (apply dissoc row unsupported-cells))
                                                   expected)
                             fidelity-result (fidelity expected rows)]
                         (assoc fidelity-result
                           :native native
                           :object-columns object-columns
                           :unsupported-cells unsupported-cells
                           :omitted-null-columns omitted-null-columns
                           :nested-clojure-values? (every? true? (vals object-columns))
                           :status (if (and (= :pass (:status fidelity-result))
                                            (every? true? (vals object-columns)))
                                     :pass
                                     :fail))))]
    ;; DuckTape is not an artifact candidate. Its unsupported values are useful
    ;; evidence but cannot reject Parquet or Lance on their own.
    (if (= :fail (:status case))
      (assoc case :status :unsupported)
      case)))


(defn- blocked-case
  [name hard? blocked-by]
  {:name       name
   :hard?      hard?
   :status     :fail
   :blocked-by blocked-by})


(defn- lance-recovery-cases!
  [^Connection connection config ^Path path row-count]
  (let [path-string (.toString (.toAbsolutePath path))
        append-case
        (checked :lance-second-version
                 true
                 #(do
                    (append-lance! connection path)
                    {:rows-after-append (:row_count
                                         (query-one connection
                                                    (str "SELECT count(*) AS row_count FROM "
                                                         (sql-literal path))))}))
        latest-case
        (if (= :pass (:status append-case))
          (child-case :lance-java-reopen-latest
                      true
                      config
                      "-M:lance-java-aligned"
                      :open
                      ["--path" path-string]
                      {:rows (inc row-count) :dependency-aligned? true})
          (blocked-case :lance-java-reopen-latest true :lance-second-version))
        latest-version (get-in latest-case [:result :version])
        pinned-version (when (and (integer? latest-version) (pos? latest-version))
                         (dec latest-version))
        pinned-case
        (if (and (= :pass (:status latest-case))
                 (integer? pinned-version)
                 (pos? pinned-version))
          (child-case :lance-java-reopen-pinned
                      true
                      config
                      "-M:lance-java-aligned"
                      :open
                      ["--path" path-string "--version" (str pinned-version)]
                      {:version pinned-version :rows row-count :dependency-aligned? true})
          (blocked-case :lance-java-reopen-pinned true :lance-java-reopen-latest))
        coexistence-case
        (if (= :pass (:status pinned-case))
          (child-case :lance-java-duckdb-coexistence
                      true
                      config
                      "-M:lance-java-aligned"
                      :coexist
                      ["--path" path-string]
                      {:version             latest-version
                       :rows                (inc row-count)
                       :dependency-aligned? true
                       :coexistence-jvm?    true})
          (blocked-case :lance-java-duckdb-coexistence true :lance-java-reopen-pinned))
        forced-arrow-case
        (checked :lance-java-forced-arrow19-diagnostic
                 false
                 #(let [child (run-lance-child! config
                                                "-M:lance-java-forced-arrow19"
                                                :open
                                                ["--path" path-string])]
                    {:status  :observed
                     :purpose :reproduce-invalid-mixed-classpath
                     :child   child}))
        precommit-case
        (if (= :pass (:status coexistence-case))
          (checked :lance-precommit-recovery
                   true
                   #(let [before          (file-inventory path)
                          created         (run-lance-child! config
                                                            :orphan-fragment
                                                            ["--path" path-string])
                          fragment-event  (some (fn [event]
                                                  (when (= :fragment-created (:phase event))
                                                    event))
                                                (:checkpoints created))
                          after           (file-inventory path)
                          reopened        (run-lance-child! config :open ["--path" path-string])
                          row-count-after (:row_count
                                           (query-one connection
                                                      (str "SELECT count(*) AS row_count FROM "
                                                           (sql-literal path))))
                          orphan-files    (into (sorted-map)
                                                (remove (fn [[file _]] (contains? before file)))
                                                after)
                          success?        (and (= orphan-fragment-exit-code (:exit created))
                                               (nil? (:result created))
                                               (= :runtime-halt (:termination fragment-event))
                                               (= orphan-fragment-exit-code (:exit-code fragment-event))
                                               (false? (:commit-attempted? fragment-event))
                                               (true? (:dependency-aligned? fragment-event))
                                               (pos? (:fragments-created fragment-event 0))
                                               (seq orphan-files)
                                               (= 0 (:exit reopened))
                                               (= :pass (get-in reopened [:result :status]))
                                               (true? (get-in reopened [:result :dependency-aligned?]))
                                               (= latest-version (get-in reopened [:result :version]))
                                               (= (inc row-count) (get-in reopened [:result :rows]))
                                               (= (inc row-count) row-count-after))]
                      {:status            (if success? :pass :fail)
                       :termination       {:method        :runtime-halt
                                           :expected-exit orphan-fragment-exit-code
                                           :actual-exit   (:exit created)
                                           :checkpoint    (:phase fragment-event)}
                       :created           created
                       :reopened          reopened
                       :rows-after-reopen row-count-after
                       :orphan-files      orphan-files}))
          (blocked-case :lance-precommit-recovery true :lance-java-duckdb-coexistence))]
    [append-case
     latest-case
     pinned-case
     coexistence-case
     forced-arrow-case
     precommit-case]))


(defn- jdbc-arrow->ipc!
  [^Connection connection ^Path path batch-size]
  (with-open [statement  (.createStatement connection)
              result-set ^DuckDBResultSet (.executeQuery statement "SELECT * FROM profiles ORDER BY id")
              allocator  (RootAllocator.)
              reader     (.arrowExportStream result-set allocator (int batch-size))
              output     (FileOutputStream. (.toFile path))]
    (let [^VectorSchemaRoot root (.getVectorSchemaRoot reader)
          writer (ArrowFileWriter. root nil (.getChannel output))]
      (let [result (try
                     (.start writer)
                     (loop [batches 0]
                       (if (.loadNextBatch reader)
                         (do
                           (.writeBatch writer)
                           (recur (inc batches)))
                         {:batches batches :schema (str (.getSchema root))}))
                     (finally
                      (.end writer)
                      (.close writer)))]
        (assoc result :allocator-peak-bytes (.getPeakMemoryAllocation allocator))))))


(defn- arrow-ipc-data
  [^Path path]
  (with-open [allocator (RootAllocator.)
              channel   (FileChannel/open path (into-array java.nio.file.OpenOption [StandardOpenOption/READ]))
              reader    (ArrowFileReader. channel allocator)]
    (let [^VectorSchemaRoot root (.getVectorSchemaRoot reader)
          schema (str (.getSchema root))]
      {:schema schema
       :rows   (loop [rows []]
                 (if (.loadNextBatch reader)
                   (let [vectors (.getFieldVectors root)
                         names   (mapv #(keyword (.getName (.getField %))) vectors)
                         batch   (mapv (fn [index]
                                         (into {}
                                               (map (fn [column vector]
                                                      [column (canonical-value (.getObject vector index))])
                                                    names
                                                    vectors)))
                                       (range (.getRowCount root)))]
                     (recur (into rows (map canonical-profile-row batch))))
                   rows))})))


(defn- consume-arrow-ipc!
  [^Path path]
  (with-open [allocator (RootAllocator.)
              channel   (FileChannel/open path (into-array java.nio.file.OpenOption [StandardOpenOption/READ]))
              reader    (ArrowFileReader. channel allocator)]
    (loop [rows 0]
      (if (.loadNextBatch reader)
        (recur (+ rows (.getRowCount (.getVectorSchemaRoot reader))))
        {:rows rows
         :allocator-peak-bytes (.getPeakMemoryAllocation allocator)}))))


(def collet-common-columns
  [[:id "id" :int64]
   [:name "name" :string]
   [:active "active" :boolean]
   [:score "score" :float64]
   [:created-at "created_at" [:zoned :instant "UTC"]]])


(defn- collet-common-record
  [{:keys [id name active score created_at]}]
  {:id         id
   :name       name
   :active     active
   :score      score
   :created-at (.toInstant (LocalDateTime/parse created_at) ZoneOffset/UTC)})


(defn- collet-common-row
  [{:keys [id name active score created_at]}]
  {:id         id
   :name       (some-> name
                       str)
   :active     active
   :score      score
   :created_at (str (LocalDateTime/ofInstant created_at ZoneOffset/UTC))})


(defn- collet-arrow-round-trip!
  [^Path path rows]
  (with-open [writer (collet-arrow/make-writer (.toString path) collet-common-columns)]
    (collet-arrow/write writer (mapv collet-common-record rows)))
  (->> (collet-arrow/read-dataset (.toString path) collet-common-columns)
       (mapcat ds/rows)
       (mapv collet-common-row)))


(defn- local-gate!
  [config]
  (Class/forName "org.duckdb.DuckDBDriver")
  (let [rows         (check-row-count config)
        check-config (assoc config :rows rows)
        artifacts    (unique-artifact-directory check-config "check")]
    (with-open [connection (DriverManager/getConnection "jdbc:duckdb:")]
      (sql! connection (str "SET memory_limit = " (sql-literal (:duckdb-memory-limit config))))
      (let [dependencies (main-dependency-evidence)
            dependency-case
            (checked :main-runtime-dependency-isolation
                     true
                     #(assoc dependencies
                        :status (if (:dependency-aligned? dependencies) :pass :fail)))
            profile-evidence (generate-profiles! connection check-config)
            baseline (query-rows connection "SELECT * FROM profiles ORDER BY id")
            baseline-schema (query-rows connection "DESCRIBE profiles")
            parquet-case
            (checked :duckdb-jdbc-parquet
                     true
                     #(let [path              (write-parquet! connection (parquet-path artifacts))
                            integrated-scan   (parquet-scan-sql path (:embedding-width check-config))
                            raw-schema        (query-rows connection
                                                          (str "DESCRIBE SELECT * FROM parquet_scan("
                                                               (sql-literal path)
                                                               ")"))
                            physical-fidelity (fidelity (logical-schema baseline-schema)
                                                        (logical-schema raw-schema))]
                        (assoc (format-fidelity
                                baseline
                                (query-rows connection
                                            (str "SELECT * FROM " integrated-scan " ORDER BY id"))
                                baseline-schema
                                (query-rows connection
                                            (str "DESCRIBE SELECT * FROM " integrated-scan)))
                          :artifact (.toString path)
                          :physical-schema (logical-schema raw-schema)
                          :physical-schema-fidelity physical-fidelity
                          :schema-restoration-sql integrated-scan
                          :unsupported-physical-cells [:embedding/fixed-size-width])))
            lance-case
            (checked :duckdb-jdbc-lance
                     true
                     #(let [{:keys [path extension]} (write-lance! connection (lance-path artifacts))]
                        (assoc (format-fidelity
                                baseline
                                (query-rows connection
                                            (str "SELECT * FROM " (sql-literal path) " ORDER BY id"))
                                baseline-schema
                                (query-rows connection
                                            (str "DESCRIBE SELECT * FROM " (sql-literal path))))
                          :artifact (.toString path)
                          :extension extension)))
            lance-recovery-cases
            (if (= :pass (:status lance-case))
              (lance-recovery-cases! connection
                                     check-config
                                     (Paths/get (:artifact lance-case) (make-array String 0))
                                     rows)
              [(blocked-case :lance-second-version true :duckdb-jdbc-lance)
               (blocked-case :lance-java-reopen-latest true :duckdb-jdbc-lance)
               (blocked-case :lance-java-reopen-pinned true :duckdb-jdbc-lance)
               (blocked-case :lance-java-duckdb-coexistence true :duckdb-jdbc-lance)
               {:name       :lance-java-forced-arrow19-diagnostic
                :hard?      false
                :status     :blocked
                :blocked-by :duckdb-jdbc-lance}
               (blocked-case :lance-precommit-recovery true :duckdb-jdbc-lance)])
            jdbc-arrow-case
            (checked :jdbc-arrow-ipc
                     true
                     #(let [path     (.resolve artifacts "profiles.arrow")
                            export   (jdbc-arrow->ipc! connection path (:batch-rows config))
                            readback (arrow-ipc-data path)
                            values   (fidelity baseline (:rows readback))
                            schema   {:status   (if (= (:schema export) (:schema readback)) :pass :fail)
                                      :expected (:schema export)
                                      :actual   (:schema readback)}]
                        {:status          (if (and (= :pass (:status values))
                                                   (= :pass (:status schema)))
                                            :pass
                                            :fail)
                         :rows            (count (:rows readback))
                         :value-fidelity  values
                         :schema-fidelity schema
                         :artifact        (.toString path)
                         :arrow           export}))
            collet-baseline (mapv #(select-keys % [:id :name :active :score :created_at]) baseline)
            collet-arrow-case
            (checked :collet-arrow-common-subset
                     true
                     #(let [path (.resolve artifacts "collet-common.arrow")]
                        (assoc (fidelity collet-baseline (collet-arrow-round-trip! path collet-baseline))
                          :artifact (.toString path)
                          :unsupported-cells [:address :attributes :tags/null-element :events :embedding])))
            ducktape-case (ducktape-case check-config baseline)]
        {:environment {:duckdb-version     (duckdb-version connection)
                       :dependencies       dependencies
                       :profile-schema     profile-evidence
                       :artifact-directory (.toString artifacts)}
         :cases       (into [dependency-case parquet-case lance-case]
                            (concat lance-recovery-cases
                                    [jdbc-arrow-case collet-arrow-case ducktape-case]))}))))


(defn- benchmark-profile-path
  [^Path artifact-directory format operation]
  (let [directory (ensure-directory! (.resolve artifact-directory "profiles"))]
    (.resolve directory (str (name format) "-" (name operation) ".json"))))


(defn- safe-explain-plan
  [^Connection connection sql]
  (try
    (explain-plan connection sql)
    (catch Throwable throwable
      {:mode :unavailable :error (record-error throwable)})))


(defn- durable-schema-evidence
  [^Connection connection config format path]
  (let [expected        (query-rows connection "DESCRIBE profiles")
        physical-scan   (case format
                          :parquet (str "read_parquet(" (sql-literal path) ")")
                          :lance (sql-literal path))
        integrated-scan (format-scan-sql format path (:embedding-width config))
        physical        (query-rows connection (str "DESCRIBE SELECT * FROM " physical-scan))
        integrated      (query-rows connection (str "DESCRIBE SELECT * FROM " integrated-scan))]
    {:physical-schema            (logical-schema physical)
     :physical-fidelity          (fidelity (logical-schema expected) (logical-schema physical))
     :integrated-schema          (logical-schema integrated)
     :integrated-fidelity        (fidelity (logical-schema expected) (logical-schema integrated))
     :unsupported-physical-cells (if (= format :parquet)
                                   [:embedding/fixed-size-width]
                                   [])}))


(defn- scan-operation!
  [^Connection connection config ^Path artifact-directory format path operation]
  (let [sql           (format-operation-sql format path (:embedding-width config) operation)
        profile-path  (benchmark-profile-path artifact-directory format operation)
        consume!      (if (= operation :full-sequential)
                        (fn [connection sql]
                          (consume-query-as-arrow! connection sql (:batch-rows config)))
                        consume-query!)
        measurement   (measure! (logical-input-bytes config)
                                #(profiled-consumption! connection profile-path sql consume!))
        profile       (get-in measurement [:result :profile])
        spill         (file-inventory (.resolve artifact-directory "duckdb-spill"))
        final-spill   (inventory-bytes spill)
        profile-spill (or (:system_peak_temp_dir_size profile) 0)]
    (assoc measurement
      :operation operation
      :sql sql
      :plan (safe-explain-plan connection sql)
      :scanned-byte-evidence (select-keys profile [:total_bytes_read :total_bytes_written])
      :duckdb-peak-buffer-bytes (:system_peak_buffer_memory profile)
      :peak-spill-directory-bytes (max final-spill profile-spill)
      :profiled-peak-spill-bytes profile-spill
      :final-spill-directory-bytes final-spill
      :spill-directory-files spill)))


(defn- bounded-or-spilled?
  [config operation]
  (let [limit (memory-limit-bytes (:duckdb-memory-limit config))
        peak  (:duckdb-peak-buffer-bytes operation)
        spill (:peak-spill-directory-bytes operation)]
    (or (pos? spill)
        (and (number? peak) (<= peak limit)))))


(defn- with-lance-table
  [^Connection connection ^Path dataset f]
  (let [namespace-root (.getParent (.toAbsolutePath dataset))
        alias          (str "spike_lance_"
                            (str/replace (str (UUID/randomUUID)) "-" ""))
        table          (str alias ".main.profiles")]
    (sql! connection
          (str "ATTACH " (sql-literal namespace-root) " AS " alias " (TYPE lance)"))
    (try
      (f table)
      (finally
       (sql! connection (str "DETACH " alias))))))


(defn- lance-cast-derived!
  [^Connection connection config ^Path dataset operation]
  (with-lance-table
   connection
   dataset
   (fn [table]
     (let [profile (benchmark-profile-path (.getParent dataset) :lance operation)
           sql     (str "ALTER TABLE "
                        table
                        " ALTER COLUMN derived_embedding TYPE FLOAT["
                        (:embedding-width config)
                        "]")]
       {:sql       sql
        :statement (profiled-statement! connection profile sql)
        :schema    (query-rows connection (str "DESCRIBE " table))}))))


(defn- lance-evolution-step!
  [^Connection connection config ^Path dataset command multiplier operation]
  (let [child (run-lance-child! config
                                command
                                ["--path" (.toString (.toAbsolutePath dataset))
                                 "--embedding-width" (str (:embedding-width config))
                                 "--multiplier" multiplier])]
    (when-not (and (zero? (:exit child))
                   (= :pass (get-in child [:result :status]))
                   (true? (get-in child [:result :dependency-aligned?])))
      (throw (ex-info "Aligned Lance Java column evolution failed"
                      {:command command :child child})))
    {:child     child
     :cast      (lance-cast-derived! connection config dataset operation)
     :inventory (file-inventory dataset)}))


(defn- lance-derived-verification
  [^Connection connection config ^Path dataset multiplier]
  (with-lance-table
   connection
   dataset
   (fn [table]
     (let [width      (:embedding-width config)
           row-count  (:row_count
                       (query-one connection
                                  (str "SELECT count(*) AS row_count FROM " table)))
           schema     (query-rows connection (str "DESCRIBE " table))
           column     (first (filter #(= "derived_embedding" (:column_name %)) schema))
           rows       (query-rows connection
                                  (str "SELECT id, derived_embedding FROM "
                                       table
                                       " WHERE id IN (0, 1) ORDER BY id"))
           expected   (mapv (fn [id]
                              {:id                id
                               :derived_embedding (mapv (fn [index]
                                                          (float (* (+ id index) multiplier)))
                                                        (range width))})
                            [0 1])
           values     (fidelity expected rows)
           fixed-type (str "FLOAT[" width "]")]
       {:status         (if (and (= fixed-type (:column_type column))
                                 (= (:rows config) row-count)
                                 (= :pass (:status values)))
                          :pass
                          :fail)
        :row-count      row-count
        :column         column
        :schema         schema
        :sample-rows    rows
        :expected       expected
        :value-fidelity values}))))


(defn- lance-evolution!
  [^Connection connection config ^Path dataset]
  (let [before (file-inventory dataset)]
    (try
      (let [add           (measure! (logical-input-bytes config)
                                    #(lance-evolution-step! connection
                                                            config
                                                            dataset
                                                            :add-derived
                                                            "0.5"
                                                            :add-derived-cast))
            after-add     (get-in add [:result :inventory])
            replace       (measure! (logical-input-bytes config)
                                    #(lance-evolution-step! connection
                                                            config
                                                            dataset
                                                            :replace-derived
                                                            "0.75"
                                                            :replace-derived-cast))
            after-replace (get-in replace [:result :inventory])
            verification  (lance-derived-verification connection config dataset 0.75)]
        {:status                        (if (= :pass (:status verification)) :pass :fail)
         :derived-null-parent-semantics :base-column-preserved-derived-from-id
         :before                        before
         :after-add                     after-add
         :after-replace                 after-replace
         :add                           (assoc add :amplification (inventory-diff before after-add))
         :replace                       (assoc replace :amplification (inventory-diff after-add after-replace))
         :verification                  verification})
      (catch Throwable throwable
        (let [after-failure (file-inventory dataset)]
          {:status                :fail
           :error                 (record-error throwable)
           :derived-null-parent-semantics :base-column-preserved-derived-from-id
           :before                before
           :after-failure         after-failure
           :failure-amplification (inventory-diff before after-failure)})))))


(defn- parquet-evolution!
  [^Connection connection config ^Path artifact-directory ^Path artifact]
  (let [directory          (ensure-directory! (.resolve artifact-directory "parquet-evolution"))
        add-artifact       (.resolve directory "profiles-with-derived.parquet")
        replace-artifact   (.resolve directory "profiles-with-derived-replaced.parquet")
        add-expression     (derived-embedding-sql (:embedding-width config) "0.5")
        replace-expression (derived-embedding-sql (:embedding-width config) "0.75")
        before             (artifact-inventory artifact)
        add                (measure! (logical-input-bytes config)
                                     #(let [sql (str "COPY (SELECT *, "
                                                     add-expression
                                                     " AS derived_embedding FROM "
                                                     (format-scan-sql :parquet
                                                                      artifact
                                                                      (:embedding-width config))
                                                     ") TO "
                                                     (sql-literal add-artifact)
                                                     " (FORMAT PARQUET, COMPRESSION zstd)")]
                                        {:statement (profiled-statement! connection
                                                                         (benchmark-profile-path artifact-directory :parquet :add-derived)
                                                                         sql)
                                         :inventory (artifact-inventory add-artifact)}))
        after-add          (:result add)
        replace            (measure! (logical-input-bytes config)
                                     #(let [sql (str "COPY (SELECT *, "
                                                     replace-expression
                                                     " AS derived_embedding FROM "
                                                     (format-scan-sql :parquet
                                                                      artifact
                                                                      (:embedding-width config))
                                                     ") TO "
                                                     (sql-literal replace-artifact)
                                                     " (FORMAT PARQUET, COMPRESSION zstd)")]
                                        {:statement (profiled-statement! connection
                                                                         (benchmark-profile-path artifact-directory :parquet :replace-derived)
                                                                         sql)
                                         :inventory (artifact-inventory replace-artifact)}))
        after-replace      (:result replace)]
    {:status        :pass
     :derived-null-parent-semantics :base-column-preserved-derived-from-id
     :before        before
     :after-add     (:inventory after-add)
     :after-replace (:inventory after-replace)
     :add           (assoc add
                      :replacement-artifact (.toString add-artifact)
                      :replacement-bytes (inventory-bytes (:inventory after-add))
                      :replacement-comparison
                      {:source-bytes      (inventory-bytes before)
                       :replacement-bytes (inventory-bytes (:inventory after-add))
                       :source-files      before
                       :replacement-files (:inventory after-add)})
     :replace       (assoc replace
                      :replacement-artifact (.toString replace-artifact)
                      :replacement-bytes (inventory-bytes (:inventory after-replace))
                      :replacement-comparison
                      {:source-bytes      (inventory-bytes before)
                       :replacement-bytes (inventory-bytes (:inventory after-replace))
                       :source-files      before
                       :replacement-files (:inventory after-replace)})}))


(defn- benchmark-durable-format!
  [^Connection connection config ^Path artifact-directory format]
  (let [directory   (ensure-directory! (.resolve artifact-directory (name format)))
        artifact    (.resolve directory (if (= format :lance) "profiles.lance" "profiles.parquet"))
        write-sql   (format-write-sql format artifact)
        write       (measure! (logical-input-bytes config)
                              #(let [statement (profiled-statement!
                                                connection
                                                (benchmark-profile-path artifact-directory format :write)
                                                write-sql)
                                     inventory (artifact-inventory artifact)]
                                 {:statement statement :inventory inventory}))
        inventory   (get-in write [:result :inventory])
        schema      (durable-schema-evidence connection config format artifact)
        full        (scan-operation! connection config artifact-directory format artifact :full-sequential)
        projected   (scan-operation! connection config artifact-directory format artifact :projected-filtered)
        sort-join   (scan-operation! connection config artifact-directory format artifact :sort-join)
        cold        (cold-read-case config format artifact (.resolve artifact-directory "duckdb-spill"))
        evolution   (case format
                      :lance (lance-evolution! connection config artifact)
                      :parquet (parquet-evolution! connection config artifact-directory artifact))
        plans       [(:plan full) (:plan projected) (:plan sort-join)]
        profile-ok? (every? true?
                            [(get-in write [:result :statement :profile-available?])
                             (get-in full [:result :profile-available?])
                             (get-in projected [:result :profile-available?])
                             (get-in sort-join [:result :profile-available?])])
        plan-ok?    (every? #(contains? #{:verbose :all} (:mode %)) plans)
        bounded?    (bounded-or-spilled? config sort-join)
        cold-ok?    (= :pass (:status cold))
        schema-ok?  (= :pass (get-in schema [:integrated-fidelity :status]))]
    {:status             (if (and profile-ok?
                                  plan-ok?
                                  bounded?
                                  cold-ok?
                                  schema-ok?
                                  (= :pass (:status evolution)))
                           :pass
                           :fail)
     :format             format
     :artifact           (.toString artifact)
     :artifact-files     inventory
     :artifact-bytes     (inventory-bytes inventory)
     :schema             schema
     :write              write
     :full-sequential    full
     :projected-filtered projected
     :sort-join          (assoc sort-join :bounded-or-spilled? bounded?)
     :process-cold       cold
     :derived-embedding  evolution
     :profile-ok?        profile-ok?
     :verbose-plans?     plan-ok?}))


(defn- benchmark-arrow!
  [^Connection connection config ^Path artifact-directory]
  (let [directory (ensure-directory! (.resolve artifact-directory "arrow"))
        artifact  (.resolve directory "profiles.arrow")
        write     (measure! (logical-input-bytes config)
                            #(let [export    (jdbc-arrow->ipc! connection artifact (:batch-rows config))
                                   inventory (artifact-inventory artifact)]
                               {:export export :inventory inventory}))
        full      (measure! (logical-input-bytes config) #(consume-arrow-ipc! artifact))
        cold      (cold-read-case config :arrow artifact (.resolve artifact-directory "duckdb-spill"))]
    {:status             (if (= :pass (:status cold)) :pass :fail)
     :format             :arrow-ipc
     :artifact           (.toString artifact)
     :artifact-files     (get-in write [:result :inventory])
     :artifact-bytes     (inventory-bytes (get-in write [:result :inventory]))
     :write              write
     :full-sequential    full
     :process-cold       cold
     ;; Arrow IPC is deliberately benchmarked as the core interchange only.
     ;; It has no durable table-level projection, join, or column-evolution
     ;; contract in this spike.
     :projected-filtered {:status :unsupported :reason :interchange-only}
     :sort-join          {:status :unsupported :reason :interchange-only}
     :derived-embedding  {:status :unsupported :reason :interchange-only}}))


(defn- benchmark-format-case!
  [config ^Path artifact-directory format]
  ;; A failed DDL/DML statement can abort DuckDB's current transaction. Keep
  ;; candidates in separate connections so one failure cannot contaminate the
  ;; other format's evidence. Generated data is removed only after that
  ;; connection closes, and therefore outside every timed operation.
  (let [outcome (try
                  {:result
                   (let [{:keys [connection]} (benchmark-connection! artifact-directory config)]
                     (with-open [connection connection]
                       (create-benchmark-profiles! connection config)
                       (when (= format :lance)
                         (enable-lance! connection))
                       (case format
                         :arrow-ipc (benchmark-arrow! connection config artifact-directory)
                         (benchmark-durable-format! connection config artifact-directory format))))}
                  (catch Throwable throwable
                    {:error (record-error throwable)}))
        cleanup (benchmark-format-cleanup! artifact-directory format)]
    (if-let [error (:error outcome)]
      {:status :fail :benchmark-error error :cleanup cleanup}
      (cond-> (assoc (:result outcome) :cleanup cleanup)
        (not= :pass (:status cleanup)) (assoc :status :fail)))))


(defn- benchmark-sample!
  [config phase iteration formats]
  (let [artifact-directory (unique-artifact-directory config
                                                      (str "benchmark-" (name phase) "-" iteration))
        {:keys [connection spill-directory jdbc-stream-results]}
        (benchmark-connection! artifact-directory config)
        environment        (with-open [connection connection]
                             (create-benchmark-profiles! connection config)
                             (cond-> {:duckdb-version      (duckdb-version connection)
                                      :logical-input-bytes (logical-input-bytes config)
                                      :jdbc-stream-results jdbc-stream-results
                                      :profile-schema      (query-rows connection "DESCRIBE profiles")
                                      :artifact-directory  (.toString artifact-directory)
                                      :spill-directory     (.toString spill-directory)}
                               (some #{:lance} formats)
                               (assoc :lance-extension (enable-lance! connection))))
        cases              (mapv (fn [format]
                                   (checked format
                                            true
                                            #(benchmark-format-case! config artifact-directory format)))
                                 formats)
        cleanup            (benchmark-sample-cleanup! artifact-directory)]
    {:status      (if (or (some hard-failure? cases)
                          (not= :pass (:status cleanup)))
                    :fail
                    :pass)
     :phase       phase
     :iteration   iteration
     :environment environment
     :cases       cases
     :cleanup     cleanup}))


(defn benchmark!
  [config]
  (let [formats      [:parquet :lance :arrow-ipc]
        warmup       (benchmark-sample! config :warmup 0 formats)
        survivors    (->> (:cases warmup)
                          (filter #(= :pass (:status %)))
                          (mapv :name))
        disqualified (->> (:cases warmup)
                          (remove #(= :pass (:status %)))
                          (mapv :name))
        measured     (mapv #(benchmark-sample! config :measured % survivors)
                           (range 1 (inc (:repetitions config))))
        samples      (into [warmup] measured)]
    {:status    (if (some #(not= :pass (:status %)) samples) :fail :pass)
     :benchmark {:warmups             1
                 :measured-executions (:repetitions config)
                 :cold-definition     "fresh JVM and DuckDB connection; OS page cache is not flushed"
                 :logical-input-bytes (logical-input-bytes config)
                 :surviving-formats   survivors
                 :disqualified        disqualified}
     :warmup    warmup
     :measured  measured}))


(defn check!
  [config]
  (let [{:keys [environment cases]} (local-gate! config)]
    {:status      (if (some hard-failure? cases) :fail :pass)
     :environment environment
     :type-matrix profile-type-matrix
     :cases       cases}))


(defn prepare-native!
  "Fetch and verify the spike-only native inputs. Docker uses this while its
  build still has networking; runtime checks then run with --network none."
  [config]
  (Class/forName "org.duckdb.DuckDBDriver")
  (let [case (checked :pinned-native-components
                      true
                      #(with-open [connection (DriverManager/getConnection "jdbc:duckdb:")]
                         {:duckdb-version (duckdb-version connection)
                          :ducktape       (ensure-ducktape-native! config)
                          :lance          (enable-lance! connection)
                          :httpfs         (enable-httpfs! connection)}))]
    {:status (if (hard-failure? case) :fail :pass)
     :cases  [case]}))


(defn- make-localstack-client
  [port]
  (let [make-client (requiring-resolve 'collet.actions.s3/make-client)]
    (make-client :s3
                 {:aws-region        "eu-west-1"
                  :aws-key           "test"
                  :aws-secret        "test"
                  :endpoint-override {:protocol :http
                                      :hostname "localhost"
                                      :port     port}})))


(defn- localstack-keys
  [client bucket]
  (let [invoke! (requiring-resolve 'collet.actions.s3/invoke!)]
    (mapv :Key (:Contents (invoke! client :ListObjectsV2 {:Bucket bucket})))))


(defn- s3-gate!
  [config]
  (let [localstack ((requiring-resolve 'collet.test-containers/localstack))
        stop!      (requiring-resolve 'clj-test-containers.core/stop!)]
    (try
      (let [port         (get-in localstack [:mapped-ports 4566])
            endpoint     (str "localhost:" port)
            bucket       (str "collet-spike-45-" (System/currentTimeMillis))
            client       (make-localstack-client port)
            invoke!      (requiring-resolve 'collet.actions.s3/invoke!)
            row-count    (check-row-count config)
            check-config (assoc config :rows row-count)]
        (invoke! client
         :CreateBucket
         {:Bucket bucket
          :CreateBucketConfiguration {:LocationConstraint "eu-west-1"}})
        (Class/forName "org.duckdb.DuckDBDriver")
        (with-open [connection (DriverManager/getConnection "jdbc:duckdb:")]
          (sql! connection (str "SET memory_limit = " (sql-literal (:duckdb-memory-limit config))))
          (generate-profiles! connection check-config)
          (let [expected    (query-rows connection
                                        "SELECT id, name FROM profiles WHERE id % 2 = 0 ORDER BY id")
                httpfs      (enable-httpfs! connection)
                lance       (enable-lance! connection)
                secret      (configure-localstack-s3! connection bucket endpoint)
                uri         (s3-scope bucket)
                parquet-uri (str uri "profiles.parquet")
                lance-uri   (str uri "profiles.lance")]
            (sql! connection
                  (str "COPY profiles TO "
                       (sql-literal parquet-uri)
                       " (FORMAT PARQUET, COMPRESSION zstd)"))
            (sql! connection
                  (str "COPY profiles TO "
                       (sql-literal lance-uri)
                       " (FORMAT lance, MODE 'overwrite', data_storage_version '2.2')"))
            ;; The child has a fresh JVM and a fresh DuckDB connection; it
            ;; must configure its own extensions and secrets before reopening.
            (let [{:keys [exit result] :as reopened}
                  (run-main-child! config
                                   :s3-reopen
                                   ["--bucket" bucket "--endpoint" endpoint])
                  rows       (:rows result)
                  parquet-ok (= expected (:parquet rows))
                  lance-ok   (= expected (:lance rows))
                  success?   (and (= 0 exit)
                                  (= :pass (:status result))
                                  parquet-ok
                                  lance-ok)]
              {:status      (if success? :pass :fail)
               :bucket      bucket
               :endpoint    endpoint
               :rows        row-count
               :expected    expected
               :reopened    reopened
               :parquet-ok? parquet-ok
               :lance-ok?   lance-ok
               :object-keys (sort (localstack-keys client bucket))
               :extensions  {:httpfs httpfs :lance lance}
               :secret      (select-keys secret [:scope :endpoint])}))))
      (finally
       (stop! localstack)))))


(defn s3-check!
  [config]
  (let [case (checked :localstack-s3-recovery true #(s3-gate! config))]
    {:status      (if (hard-failure? case) :fail :pass)
     :environment {:localstack-image "localstack/localstack:4.14.0"}
     :cases       [case]}))


(defn- repository-root
  []
  (-> (Paths/get (System/getProperty "user.dir") (make-array String 0))
      (.toAbsolutePath)
      (.getParent)
      (.getParent)
      (.getParent)))


(defn- run-command!
  [config label ^Path directory args]
  (let [label    (if (keyword? label) (name label) label)
        log-path (result-file config (str label "-output") ".log")
        builder  (doto (ProcessBuilder. (into-array String args))
                   (.directory (.toFile directory))
                   (.redirectErrorStream true)
                   (.redirectOutput (ProcessBuilder$Redirect/to (.toFile log-path))))
        process  (.start builder)
        exit     (.waitFor process)]
    {:command      (vec args)
     :exit         exit
     :output       (bounded-file-text log-path)
     :log-path     (.toString log-path)
     :log-artifact {:bytes  (Files/size log-path)
                    :sha256 (sha256-file log-path)}}))


(defn docker-check!
  [config]
  (let [case
        (checked :linux-amd64-image-no-network
                 true
                 #(let [root (repository-root)
                        image "collet-spike-45:issue-45"
                        host {:os-name (System/getProperty "os.name")
                              :os-arch (System/getProperty "os.arch")}
                        execution (cond
                                    (and (= "Linux" (:os-name host))
                                         (#{"amd64" "x86_64"} (:os-arch host)))
                                    :native-linux-amd64

                                    (and (= "Mac OS X" (:os-name host))
                                         (= "aarch64" (:os-arch host)))
                                    :translated-container

                                    :else :container-platform-emulation-unknown)
                        evidence-directory
                        (ensure-directory!
                         (.resolve (output-directory config)
                                   (str "docker-runtime-" (System/currentTimeMillis))))
                        check-path (.resolve evidence-directory "check/check.edn")
                        build (run-command! config
                                            :docker-build
                                            root
                                            ["docker" "build"
                                             "--platform" "linux/amd64"
                                             "--file" "dev/spikes/issue-45/Dockerfile"
                                             "--tag" image
                                             "."])
                        runtime (when (zero? (:exit build))
                                  (run-command! config
                                                :docker-runtime
                                                root
                                                ["docker" "run" "--rm"
                                                 "--platform" "linux/amd64"
                                                 "--network" "none"
                                                 "--volume"
                                                 (str (.toAbsolutePath evidence-directory) ":/evidence")
                                                 image
                                                 "check"
                                                 "--rows" "32"
                                                 "--embedding-width" "8"
                                                 "--batch-rows" "8"
                                                 "--output-dir" "/evidence/check"]))
                        runtime-check
                        (when (Files/exists check-path (make-array java.nio.file.LinkOption 0))
                          (edn/read-string (slurp (.toFile check-path))))]
                    {:status                 (if (and (zero? (:exit build))
                                                      runtime
                                                      (zero? (:exit runtime))
                                                      (= :pass (:status runtime-check)))
                                               :pass
                                               :fail)
                     :image                  image
                     :platform               "linux/amd64"
                     :host                   host
                     :execution              execution
                     :native-linux-evidence? (= :native-linux-amd64 execution)
                     :network                "none"
                     :build                  build
                     :runtime                runtime
                     :runtime-check          runtime-check
                     :runtime-evidence-path  (.toString check-path)}))]
    {:status (if (hard-failure? case) :fail :pass)
     :cases  [case]}))


(defn -main
  [& args]
  (let [[command & options] args]
    (case command
      "s3-reopen" (s3-open-main! options)
      "benchmark-cold-read" (cold-read-main! options)
      (let [config  (parse-args options)
            result  (case command
                      "check" (check! config)
                      "prepare-native" (prepare-native! config)
                      "s3-check" (s3-check! config)
                      "docker-check" (docker-check! config)
                      "benchmark" (benchmark! config)
                      (throw (ex-info "Usage: bb spike:45 check" {:args args})))
            payload (write-edn! config command result)]
        (when (not= :pass (:status payload))
          (System/exit 1))))))
