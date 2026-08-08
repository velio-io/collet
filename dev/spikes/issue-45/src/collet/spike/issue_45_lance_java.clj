(ns collet.spike.issue-45-lance-java
  (:require
   [clojure.string :as str])
  (:import
    [java.nio ByteBuffer]
    [java.nio.channels FileChannel]
    [java.nio.charset StandardCharsets]
    [java.nio.file Files Path Paths StandardOpenOption]
    [java.nio.file.attribute FileAttribute]
    [java.sql Connection DriverManager ResultSet SQLException]
    [java.time Instant]
    [java.util Optional]
    [org.apache.arrow.memory RootAllocator]
    [org.apache.arrow.vector BigIntVector VectorSchemaRoot]
    [org.apache.arrow.vector.types.pojo ArrowType$Int Field Schema]
    [org.lance Dataset Fragment ReadOptions$Builder WriteParams$Builder]
    [org.lance.schema SqlExpressions$Builder]))


(def lance-java-version "9.0.1")


(def lance-arrow-version "18.3.0")


(def duckdb-jdbc-version "1.5.5.1")


(def duckdb-lance-extension-version "2f167ea")


(def orphan-fragment-exit-code 86)


(defn- now []
  (str (Instant/now)))


(defn- path
  [value]
  (Paths/get value (make-array String 0)))


(defn- file-attributes []
  (make-array FileAttribute 0))


(defn- ensure-parent!
  [^Path output]
  (when-let [parent (.getParent output)]
    (Files/createDirectories parent (file-attributes))))


(defn- utf8-bytes
  [value]
  (.getBytes (str value) StandardCharsets/UTF_8))


(defn- write-edn-file!
  [output value]
  (let [output (path output)
        data   (utf8-bytes (str (pr-str value) "\n"))]
    (ensure-parent! output)
    (with-open [channel (FileChannel/open
                         output
                         (into-array java.nio.file.OpenOption
                                     [StandardOpenOption/CREATE
                                      StandardOpenOption/TRUNCATE_EXISTING
                                      StandardOpenOption/WRITE]))]
      (.write channel (ByteBuffer/wrap data))
      (.force channel true))))


(defn- append-checkpoint!
  [output phase data]
  (let [output (path output)
        event  (merge {:timestamp (now) :phase phase} data)
        data   (utf8-bytes (str (pr-str event) "\n"))]
    (ensure-parent! output)
    (with-open [channel (FileChannel/open
                         output
                         (into-array java.nio.file.OpenOption
                                     [StandardOpenOption/CREATE
                                      StandardOpenOption/APPEND
                                      StandardOpenOption/WRITE]))]
      (.write channel (ByteBuffer/wrap data))
      (.force channel true))))


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


(defn- dependency-evidence
  []
  (let [arrow-version   (some-> Schema
                                .getPackage
                                .getImplementationVersion)
        lance-source    (class-source Dataset)
        duckdb-source   (class-source org.duckdb.DuckDBDriver)
        arrow-sources   {:schema       (class-source Schema)
                         :memory       (class-source RootAllocator)
                         :c-data       (class-source (Class/forName "org.apache.arrow.c.ArrowArrayStream"))
                         :dataset      (class-source (Class/forName "org.apache.arrow.dataset.jni.NativeDataset"))
                         :memory-netty (class-source (Class/forName "org.apache.arrow.memory.netty.NettyAllocationManager"))}
        arrows-aligned? (every? #(str/includes? % (str "/" lance-arrow-version "/"))
                                (vals arrow-sources))]
    {:lance-java {:expected-version lance-java-version
                  :code-source      lance-source
                  :native-jni       :bundled-in-lance-core}
     :arrow {:expected-version  lance-arrow-version
             :actual-version    arrow-version
             :component-sources arrow-sources}
     :duckdb-jdbc {:expected-version duckdb-jdbc-version
                   :code-source      duckdb-source}
     :runtime {:java-version (System/getProperty "java.version")
               :java-vm      (System/getProperty "java.vm.name")
               :os-name      (System/getProperty "os.name")
               :os-arch      (System/getProperty "os.arch")}
     :dependency-aligned?
     (and (= lance-arrow-version arrow-version)
          arrows-aligned?
          (str/includes? lance-source (str "lance-core-" lance-java-version ".jar"))
          (str/includes? duckdb-source (str "duckdb_jdbc-" duckdb-jdbc-version ".jar")))}))


(defn- child-options
  [args]
  (loop [options {}
         [arg value & remaining :as args] args]
    (cond
      (empty? args) options
      (not (#{"--path" "--version" "--result" "--checkpoints"
              "--embedding-width" "--multiplier"}
            arg))
      (throw (ex-info "Unknown Lance helper option" {:arg arg :args args}))
      (nil? value)
      (throw (ex-info "Lance helper option is missing a value" {:arg arg :args args}))
      :else
      (recur (assoc options (keyword (subs arg 2)) value) remaining))))


(defn- checkpoint-fn
  [output]
  (fn
    ([phase]
     (append-checkpoint! output phase {}))
    ([phase data]
     (append-checkpoint! output phase data))))


(defn- close-with-checkpoint!
  [checkpoint label ^java.lang.AutoCloseable resource]
  (checkpoint (keyword (str "before-" (name label) "-close")))
  (try
    (.close resource)
    (checkpoint (keyword (str "after-" (name label) "-close")))
    (catch Throwable throwable
      (checkpoint (keyword (str (name label) "-close-error"))
                  {:error (record-error throwable)})
      (throw throwable))))


(defn- open-options
  [version]
  (let [builder (ReadOptions$Builder.)]
    (when version
      (.setVersion builder (long version)))
    (.build builder)))


(defn- read-dataset!
  [dataset-path version checkpoint]
  (let [dependencies (dependency-evidence)
        allocator    (RootAllocator.)]
    (checkpoint :dependency-evidence dependencies)
    (checkpoint :allocator-opened)
    (try
      (let [dataset (Dataset/open allocator dataset-path (open-options version))]
        (checkpoint :dataset-opened {:requested-version version})
        (try
          (let [opened-version (.version dataset)
                _ (checkpoint :version-read {:version opened-version})
                latest-version (.latestVersion dataset)
                _ (checkpoint :latest-version-read {:latest-version latest-version})
                rows           (.countRows dataset)
                _ (checkpoint :row-count-read {:rows rows})
                schema         (str (.getSchema dataset))
                _ (checkpoint :schema-read {:schema schema})
                result         {:version              opened-version
                                :latest-version       latest-version
                                :rows                 rows
                                :schema               schema
                                :allocator-peak-bytes (.getPeakMemoryAllocation allocator)
                                :dependencies         dependencies
                                :dependency-aligned?  (:dependency-aligned? dependencies)}]
            ;; This forced checkpoint distinguishes a successful read from a
            ;; later native cleanup failure.
            (checkpoint :operation-complete {:result result})
            result)
          (finally
           (close-with-checkpoint! checkpoint :dataset dataset))))
      (finally
       (close-with-checkpoint! checkpoint :allocator allocator)))))


(defn- extension-info
  [^Connection connection]
  (with-open [statement (.createStatement connection)
              result    ^ResultSet
                        (.executeQuery statement
                                       (str "SELECT extension_name, installed, loaded, extension_version, "
                                            "install_path, install_mode FROM duckdb_extensions() "
                                            "WHERE extension_name = 'lance'"))]
    (when (.next result)
      {:extension-name    (.getString result "extension_name")
       :installed         (.getBoolean result "installed")
       :loaded            (.getBoolean result "loaded")
       :extension-version (.getString result "extension_version")
       :install-path      (.getString result "install_path")
       :install-mode      (.getString result "install_mode")})))


(defn- load-lance-extension!
  [^Connection connection checkpoint]
  (with-open [statement (.createStatement connection)]
    (checkpoint :before-lance-extension-install)
    (.execute statement "INSTALL lance")
    (checkpoint :after-lance-extension-install)
    (.execute statement "LOAD lance")
    (checkpoint :after-lance-extension-load))
  (let [extension (extension-info connection)]
    (checkpoint :lance-extension-inspected extension)
    (when-not (= duckdb-lance-extension-version (:extension-version extension))
      (throw (ex-info "DuckDB loaded an unexpected Lance extension version"
                      {:expected  duckdb-lance-extension-version
                       :actual    (:extension-version extension)
                       :extension extension})))
    extension))


(defn- coexistence-read!
  [dataset-path version checkpoint]
  (Class/forName "org.duckdb.DuckDBDriver")
  (checkpoint :before-duckdb-connection-open)
  (let [connection (DriverManager/getConnection "jdbc:duckdb:")]
    (checkpoint :duckdb-connection-opened)
    (try
      (let [extension (load-lance-extension! connection checkpoint)
            opened    (read-dataset! dataset-path version checkpoint)]
        (assoc opened
          :duckdb-lance-extension extension
          :coexistence-jvm? true))
      (finally
       (close-with-checkpoint! checkpoint :duckdb-connection connection)))))


(defn- orphan-fragment!
  [dataset-path checkpoint]
  (let [allocator (RootAllocator.)]
    (checkpoint :allocator-opened)
    (try
      (let [root (VectorSchemaRoot/create
                  (Schema. [(Field/nullable "orphan_id" (ArrowType$Int. 64 true))] nil)
                  allocator)]
        (checkpoint :vector-root-opened)
        (try
          (let [^BigIntVector vector (.getVector root "orphan_id")]
            (.allocateNew vector 1)
            (.setSafe vector 0 (long 999999))
            (.setValueCount vector 1)
            (.setRowCount root 1)
            (checkpoint :before-fragment-create)
            (let [fragments    (Fragment/create dataset-path
                                                allocator
                                                root
                                                (-> (WriteParams$Builder.)
                                                    (.withDataStorageVersion "2.2")
                                                    (.build)))
                  dependencies (dependency-evidence)
                  result       {:fragments-created    (count fragments)
                                :commit-attempted?    false
                                :termination          :runtime-halt
                                :exit-code            orphan-fragment-exit-code
                                :allocator-peak-bytes (.getPeakMemoryAllocation allocator)
                                :dependencies         dependencies
                                :dependency-aligned?  (:dependency-aligned? dependencies)}]
              (checkpoint :fragment-created result)
              ;; Deliberately bypass every finally block. The parent accepts
              ;; only this exact exit after the force-synced checkpoint.
              (.halt (Runtime/getRuntime) orphan-fragment-exit-code)))
          (finally
           (close-with-checkpoint! checkpoint :vector-root root))))
      (finally
       (close-with-checkpoint! checkpoint :allocator allocator)))))


(defn- derived-expression
  [embedding-width multiplier]
  (str "make_array("
       (str/join ", "
                 (map (fn [index]
                        (str "CAST(id + "
                             index
                             " AS FLOAT) * CAST("
                             multiplier
                             " AS FLOAT)"))
                      (range embedding-width)))
       ")"))


(defn- evolve-derived!
  [dataset-path embedding-width multiplier replace? checkpoint]
  (when-not (pos? embedding-width)
    (throw (ex-info "Embedding width must be positive" {:embedding-width embedding-width})))
  ;; Parsing and re-rendering prevents a command-line value from becoming SQL.
  (let [multiplier   (str (double (Double/parseDouble multiplier)))
        dependencies (dependency-evidence)
        allocator    (RootAllocator.)]
    (checkpoint :dependency-evidence dependencies)
    (checkpoint :allocator-opened)
    (try
      (let [dataset (Dataset/open allocator dataset-path (open-options nil))]
        (checkpoint :dataset-opened)
        (try
          (when replace?
            (checkpoint :before-derived-column-drop)
            (.dropColumns dataset ["derived_embedding"])
            (checkpoint :after-derived-column-drop {:version (.version dataset)}))
          (let [expression  (derived-expression embedding-width multiplier)
                expressions (-> (SqlExpressions$Builder.)
                                (.withExpression "derived_embedding" expression)
                                (.build))]
            (checkpoint :before-derived-column-add {:expression expression})
            (.addColumns dataset expressions (Optional/empty))
            (let [result {:version              (.version dataset)
                          :latest-version       (.latestVersion dataset)
                          :rows                 (.countRows dataset)
                          :schema               (str (.getSchema dataset))
                          :expression           expression
                          :replace?             replace?
                          :allocator-peak-bytes (.getPeakMemoryAllocation allocator)
                          :dependencies         dependencies
                          :dependency-aligned?  (:dependency-aligned? dependencies)}]
              (checkpoint :after-derived-column-add {:result result})
              (checkpoint :operation-complete {:result result})
              result))
          (finally
           (close-with-checkpoint! checkpoint :dataset dataset))))
      (finally
       (close-with-checkpoint! checkpoint :allocator allocator)))))


(defn -main
  [& args]
  (let [[command & options] args
        {:keys [path version result checkpoints embedding-width multiplier]}
        (child-options options)
        checkpoint (checkpoint-fn checkpoints)
        payload
        (try
          (checkpoint :process-start {:command command :arguments options})
          (assoc
            (case command
              "open" (read-dataset! path
                                    (some-> version
                                            parse-long)
                                    checkpoint)
              "coexist" (coexistence-read! path
                                           (some-> version
                                                   parse-long)
                                           checkpoint)
              "orphan-fragment" (orphan-fragment! path checkpoint)
              "add-derived" (evolve-derived! path (parse-long embedding-width) multiplier false checkpoint)
              "replace-derived" (evolve-derived! path (parse-long embedding-width) multiplier true checkpoint)
              (throw (ex-info "Unknown Lance helper command" {:command command})))
            :status :pass)
          (catch Throwable throwable
            (let [error (record-error throwable)]
              (checkpoint :operation-error {:error error})
              {:status :fail :error error})))]
    (write-edn-file! result payload)
    (checkpoint :result-written {:status (:status payload) :result-path result})
    (when (= "orphan-fragment" command)
      (checkpoint :process-terminating-before-commit
                  {:commit-attempted? false}))
    (when (not= :pass (:status payload))
      (System/exit 1))))
