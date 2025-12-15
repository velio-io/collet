(ns collet.actions.lucene
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [malli.core :as m]
   [tech.v3.dataset :as ds])
  (:import
   [clojure.lang ExceptionInfo]
   [java.util.regex Pattern]
   [org.apache.lucene.analysis Analyzer]
   [org.apache.lucene.analysis.standard StandardAnalyzer]
   [org.apache.lucene.analysis.core WhitespaceAnalyzer SimpleAnalyzer]
   [org.apache.lucene.document Document TextField KeywordField LongField StoredField StringField KnnFloatVectorField]
   [org.apache.lucene.document Field$Store]
   [org.apache.lucene.index IndexWriter IndexWriterConfig IndexWriterConfig$OpenMode DirectoryReader]
   [org.apache.lucene.index IndexableField VectorSimilarityFunction]
   [org.apache.lucene.search IndexSearcher TopDocs ScoreDoc Query TotalHits$Relation]
   [org.apache.lucene.queryparser.classic QueryParser ParseException]
   [org.apache.lucene.store FSDirectory]
   [java.io File IOException]))


(defn- make-analyzer
  "Creates a Lucene Analyzer from a keyword or returns existing Analyzer instance.
   Supported keywords: :standard, :whitespace, :simple"
  [analyzer-spec]
  (if (keyword? analyzer-spec)
    (case analyzer-spec
      :standard (StandardAnalyzer.)
      :whitespace (WhitespaceAnalyzer.)
      :simple (SimpleAnalyzer.)
      (throw (ex-info (str "Unknown analyzer type: " analyzer-spec)
                      {:analyzer  analyzer-spec
                       :supported [:standard :whitespace :simple]})))
    analyzer-spec))


(defn- ->open-mode
  "Converts keyword to IndexWriterConfig$OpenMode enum"
  [mode-kw]
  (case mode-kw
    :create IndexWriterConfig$OpenMode/CREATE
    :create-or-append IndexWriterConfig$OpenMode/CREATE_OR_APPEND
    :append IndexWriterConfig$OpenMode/APPEND
    (throw (ex-info (str "Unknown open mode: " mode-kw)
                    {:mode      mode-kw
                     :supported [:create :create-or-append :append]}))))


(def index-writer-params-spec
  [:or
   :string                    ;; Backward compatibility - simple path
   [:map
    [:index-path :string]
    [:ram-buffer-mb {:optional true} pos?]
    [:open-mode {:optional true} [:enum :create :create-or-append :append]]
    [:analyzer {:optional true}
     [:or [:enum :standard :whitespace :simple]
      [:fn #(instance? Analyzer %)]]]
    [:commit-on-close? {:optional true} :boolean]
    [:use-compound-file? {:optional true} :boolean]]])


(defn create-index
  "Creates or opens a Lucene IndexWriter with configurable options.

   Accepts either a string path (backward compatible) or an options map.

   Options:
   :index-path          - Path to index directory (required)
   :ram-buffer-mb       - RAM buffer size in MB (default: 256)
                          Larger = better performance, more memory
   :open-mode           - :create, :create-or-append, :append (default: :create-or-append)
   :analyzer            - :standard, :whitespace, :simple, or Analyzer instance (default: :standard)
   :commit-on-close?    - Commit on close (default: true)
   :use-compound-file?  - Use compound format (default: false for better performance)

   Returns IndexWriter (must be closed after use, prefer with-open)

   Examples:
   ;; Simple usage
   (create-index \"/path/to/index\")

   ;; Performance-tuned for large file + KNN indexing
   (create-index {:index-path \"/path/to/index\"
                  :ram-buffer-mb 512
                  :open-mode :create})

   ;; Incremental updates with code-optimized analyzer
   (create-index {:index-path \"/path/to/index\"
                  :open-mode :append
                  :analyzer :whitespace})"
  [opts-or-path]
  (let [;; Normalize input
        opts          (if (string? opts-or-path)
                        {:index-path opts-or-path}
                        opts-or-path)
        ;; Destructure with defaults
        {:keys [index-path ram-buffer-mb open-mode analyzer
                commit-on-close? use-compound-file?]
         :or   {ram-buffer-mb      256
                open-mode          :create-or-append
                analyzer           :standard
                commit-on-close?   true
                use-compound-file? false}} opts
        ;; Prepare resources
        index-file    (io/file index-path)
        _             (io/make-parents (io/file index-file "dummy"))
        directory     (FSDirectory/open (.toPath index-file))
        analyzer-inst (make-analyzer analyzer)
        config        (IndexWriterConfig. analyzer-inst)]
    ;; Configure IndexWriterConfig
    (doto config
      (.setRAMBufferSizeMB (double ram-buffer-mb))
      (.setOpenMode (->open-mode open-mode))
      (.setUseCompoundFile ^boolean use-compound-file?)
      (.setCommitOnClose commit-on-close?))
    ;; Create and return IndexWriter
    (IndexWriter. directory config)))


;; ========================================
;; index-files! helper functions
;; ========================================

(defn- detect-format
  "Detects file format from extension, handling gzip compression.
   Returns a map with :format and :compressed? keys.

   Examples:
   'data.csv'     -> {:format :csv, :compressed? false}
   'data.csv.gz'  -> {:format :csv, :compressed? true}
   'data.json.gz' -> {:format :jsonld, :compressed? true}"
  [file-path]
  (let [filename    (-> file-path io/file .getName string/lower-case)
        parts       (string/split filename #"\.")
        ;; Check if last extension is .gz
        is-gzipped? (= "gz" (last parts))
        ;; Get the actual format extension
        format-ext  (if is-gzipped?
                      (when (> (count parts) 1)
                        (nth parts (- (count parts) 2)))
                      (last parts))]
    (when format-ext
      (let [format (case format-ext
                     "csv" :csv
                     "json" :jsonld
                     "jsonld" :jsonld
                     "json-ld" :jsonld
                     "parquet" :parquet
                     "xlsx" :xlsx
                     "xls" :xlsx
                     nil)]
        (when format
          {:format      format
           :compressed? is-gzipped?})))))


(defn- normalize-column-name
  "Normalizes column names to snake_case keywords.
   Converts to lowercase, replaces spaces/hyphens with underscores,
   removes special characters, and returns as keyword.

   Examples:
   'Customer Id'  -> :customer_id
   'First_Name'   -> :first_name
   'Phone 1'      -> :phone_1
   'Email@Domain' -> :emaildomain"
  [col-name]
  (let [s          (cond
                     (keyword? col-name) (name col-name)
                     (string? col-name) col-name
                     :else (str col-name))

        ;; Normalize the string
        normalized (-> s
                       string/trim
                       string/lower-case
                       ;; Replace spaces and hyphens with underscores
                       (string/replace #"[\s\-]+" "_")
                       ;; Remove all non-alphanumeric except underscores
                       (string/replace #"[^a-z0-9_]+" "")
                       ;; Collapse multiple consecutive underscores
                       (string/replace #"_{2,}" "_")
                       ;; Remove leading/trailing underscores
                       (string/replace #"^_+|_+$" ""))]

    ;; Handle empty string edge case
    (keyword (if (empty? normalized) "column" normalized))))


(defn- load-dataset
  "Loads file as tech.ml.dataset, using :gzipped? option for compressed files.
   Accepts user-provided dataset-opts which are merged with internal options.

   By default, applies normalize-column-name to convert column names to snake_case.
   Users can override this by providing their own :key-fn in dataset-opts."
  [file-path {:keys [format compressed?]} dataset-opts]
  (let [;; Start with user options
        base-opts       (or dataset-opts {})

        ;; Apply default :key-fn if user hasn't provided one
        ;; This ensures column names are normalized to kebab-case
        opts-with-keyfn (if (contains? base-opts :key-fn)
                          base-opts ; User provided :key-fn, use as-is
                          (assoc base-opts :key-fn normalize-column-name))

        ;; Merge with internal options (these take precedence)
        opts            (cond-> opts-with-keyfn
                          compressed? (assoc :gzipped? true)
                          (= format :jsonld) (assoc :file-type :json))]
    (case format
      :csv (ds/->dataset file-path opts)
      :parquet (ds/->dataset file-path opts)
      :xlsx (ds/->dataset file-path opts)
      :jsonld (ds/->dataset file-path opts)
      (throw (ex-info "Unsupported format" {:format format :file file-path})))))


(defn- row->document
  "Converts a dataset row to a Lucene Document"
  [row row-idx file-metadata {:keys [knn-vector-fn]}]
  (let [doc (Document.)]
    ;; File metadata fields (stored)
    (.add doc (KeywordField. "source-path" ^String (:path file-metadata) Field$Store/YES))
    (.add doc (LongField. "source-modified" (:modified file-metadata) Field$Store/YES))
    (.add doc (KeywordField. "source-extension" (name (:extension file-metadata)) Field$Store/YES))
    (.add doc (KeywordField. "source-compressed"
                             (if (:compressed? file-metadata) "true" "false")
                             Field$Store/YES))
    (.add doc (LongField. "source-size" (:size file-metadata) Field$Store/YES))
    (.add doc (LongField. "row-number" row-idx Field$Store/YES))
    ;; Row data as fields
    (doseq [[col-name col-value] row]
      (let [field-name (name col-name)
            value-str  (str col-value)]
        ;; Store original value
        (.add doc (StoredField. (str field-name "-stored") value-str))
        ;; Index for search - use TextField for all values to make them searchable
        ;; with QueryParser (LongField/StringField don't support standard query syntax)
        (.add doc (TextField. field-name value-str Field$Store/NO))))
    ;; Full row contents as searchable text (concatenate all values)
    (let [contents (->> row
                        vals
                        (map str)
                        (string/join " "))]
      (.add doc (TextField. "contents" contents Field$Store/YES)))
    ;; Optional: KNN vector
    (when knn-vector-fn
      (try
        (when-let [vector (knn-vector-fn row)]
          (.add doc (KnnFloatVectorField. "vector" (float-array vector) VectorSimilarityFunction/DOT_PRODUCT)))
        (catch Exception e
          ;; Log error but continue
          (println "Warning: KNN vector generation failed for row" row-idx ":" (.getMessage e)))))
    ;; Return document
    doc))


(defn- index-file!
  "Indexes a single file (all rows) into the writer"
  [^IndexWriter writer file-path opts stats-atom]
  (try
    (let [format-info (detect-format file-path)]
      (when-not format-info
        (throw (ex-info "Unknown file format" {:file file-path})))
      (let [;; Load as dataset with user options
            dataset       (load-dataset file-path format-info (:dataset-opts opts))
            ;; File metadata
            file          (io/file file-path)
            file-metadata {:path        file-path
                           :modified    (.lastModified file)
                           :size        (.length file)
                           :extension   (:format format-info)
                           :compressed? (:compressed? format-info)}
            {:keys [commit-every-n progress-fn]} opts
            ;; Index each row
            rows          (ds/mapseq-reader dataset)]
        (doseq [[idx row] (map-indexed vector rows)]
          (let [doc (row->document row idx file-metadata opts)]
            (.addDocument writer doc)
            (swap! stats-atom update :indexed inc)
            ;; Periodic commit
            (when (and commit-every-n
                       (zero? (mod (:indexed @stats-atom) commit-every-n)))
              (.commit writer)
              (when progress-fn
                (progress-fn @stats-atom)))))))

    (catch Exception e
      (if (:fail-fast? opts)
        (throw e)
        ;; Log error and continue
        (-> stats-atom
            (swap! update :failed inc)
            (swap! update :errors conj
                   {:file  file-path
                    :error (.getMessage e)
                    :type  (.getName (.getClass e))}))))))


(defn- should-index-file?
  "Determines if file should be indexed based on extension"
  [file-path {:keys [extensions exclude-patterns]}]
  (let [format-info (detect-format file-path)
        filename    (.getName (io/file file-path))]
    (and
     ;; Has supported format
     (some? format-info)
     ;; Check extension whitelist
     (or (nil? extensions)
         (contains? extensions (:format format-info)))
     ;; Check exclude patterns
     (not-any? #(re-matches % filename) (or exclude-patterns #{})))))


(defn- walk-and-index
  "Walks directory tree and indexes matching files"
  [writer docs-path opts stats-atom]
  (let [path (io/file docs-path)]
    (if (.isFile path)
      ;; Single file
      (index-file! writer (.getAbsolutePath path) opts stats-atom)
      ;; Directory - walk files
      (let [files          (file-seq path)
            matching-files (filter (fn [^File f]
                                     (and (.isFile f)
                                          (should-index-file? (.getAbsolutePath f) opts)))
                                   files)]
        (doseq [file matching-files]
          (index-file! writer (.getAbsolutePath ^File file) opts stats-atom))))))


;; ========================================
;; Malli schemas
;; ========================================


(def regex?
  (m/-simple-schema
   {:type :regex?
    :pred #(instance? Pattern %)
    :type-properties
    {:error/message "must be a regex pattern"}}))


(def index-files-params-spec
  [:map
   ;; Required
   [:index-path :string]
   [:docs-path :string]
   ;; File selection
   [:extensions {:optional true}
    [:set [:enum :csv :jsonld :parquet :xlsx]]]
   [:exclude-patterns {:optional true}
    [:set regex?]]
   [:dataset-opts {:optional true} map?] ;; Options passed to ds/->dataset
   ;; Indexing behavior
   [:commit-every-n {:optional true :default 1000} pos-int?]
   [:fail-fast? {:optional true :default false} :boolean]
   [:progress-fn {:optional true} fn?]
   ;; KNN vectors
   [:knn-vector-fn {:optional true} fn?]
   ;; Index writer options (passed to create-index)
   [:ram-buffer-mb {:optional true :default 256} pos?]
   [:open-mode {:optional true :default :create-or-append}
    [:enum :create :create-or-append :append]]
   [:analyzer {:optional true :default :standard}
    [:or [:enum :standard :whitespace :simple]
     [:fn #(instance? Analyzer %)]]]])


(def error-map-spec
  [:map
   [:file :string]
   [:error :string]
   [:type {:optional true} :string]])


(def index-files-return-spec
  [:map
   [:indexed nat-int?]
   [:failed nat-int?]
   [:skipped nat-int?]
   [:duration-ms pos?]
   [:errors [:vector error-map-spec]]])


(def search-params-spec
  [:map
   ;; Required
   [:index-path :string]
   [:query [:or :string       ; QueryParser string
            [:fn #(instance? Query %)]]] ; Lucene Query object

   ;; Common options
   [:limit {:optional true :default 10} pos-int?]
   [:offset {:optional true :default 0} nat-int?]
   [:fields {:optional true} [:vector :keyword]]
   [:default-field {:optional true :default "contents"} :string]
   [:include-score? {:optional true :default true} :boolean]

   ;; Search behavior (Phase 2+)
   [:sort-by {:optional true} [:or :keyword [:vector :map]]]
   [:filter {:optional true} [:or :string ; QueryParser string
                              [:fn #(instance? Query %)]]] ; Query object
   [:min-score {:optional true} number?]

   ;; Advanced (Phase 2+)
   [:analyzer {:optional true} [:or [:enum :standard :whitespace :simple]
                                [:fn #(instance? Analyzer %)]]]
   [:include-highlights? {:optional true :default false} :boolean]
   [:include-explain? {:optional true :default false} :boolean]

   ;; KNN (Phase 3)
   [:knn-vector {:optional true} [:vector number?]]
   [:knn-k {:optional true :default 10} pos-int?]

   ;; Advanced searcher management
   [:searcher {:optional true} [:fn #(instance? IndexSearcher %)]]])


(def search-result-spec
  [:map
   [:results [:vector :map]]
   [:total-hits nat-int?]
   [:total-hits-relation [:enum :equal-to :greater-than-or-equal]]
   [:offset nat-int?]
   [:limit pos-int?]
   [:max-score {:optional true} number?]])


(defn index-files!
  "Indexes structured data files (CSV, JSON-LD, Parquet, XLSX) into Lucene index.

   Each row in the dataset becomes a separate Lucene document with:
   - All columns as searchable/stored fields
   - Full row contents as searchable text
   - File metadata (path, modified, size, extension, row number)
   - Optional KNN vector for semantic search

   Required Options:
   :index-path          - Path to Lucene index directory
   :docs-path           - File or directory to index

   Optional:
   :extensions          - Set of formats to include: #{:csv :jsonld :parquet :xlsx}
                          Default: all supported formats
   :exclude-patterns    - Set of regex patterns to exclude files
   :dataset-opts        - Map of options passed to tech.ml.dataset/->dataset
                          Default behavior: Column names are automatically normalized
                          to snake_case keywords (e.g., \"First Name\" -> :first_name)

                          Useful options:
                          :key-fn           - Function to transform column names
                                             (default: normalize-column-name for snake_case)
                                             Set to 'identity' to disable normalization
                                             Example: :key-fn keyword (keywords with original names)
                          :column-allowlist - Vector of column names/indices to include
                          :column-blocklist - Vector of column names/indices to exclude
                          :separator        - CSV separator character (default: comma)
                          :header-row?      - Whether CSV has headers (default: true)
                          :num-rows         - Maximum rows to load per file
                          :parser-fn        - Custom parser map {:column-name parser-fn}
                          :parser-scan-len  - Rows to scan for type detection
                          See tech.ml.dataset/->dataset for full list
   :commit-every-n      - Commit every N rows (default: 1000)
   :fail-fast?          - Stop on first error (default: false, continue)
   :progress-fn         - Callback fn(stats) for progress reporting
   :knn-vector-fn       - fn(row-map) -> float-array for vector search

   Index Writer Options (passed to create-index):
   :ram-buffer-mb       - RAM buffer size MB (default: 256)
   :open-mode           - :create, :create-or-append, :append
   :analyzer            - :standard, :whitespace, :simple

   Returns:
   {:indexed 1234        ;; Total rows indexed
    :failed 2            ;; Failed files
    :skipped 1           ;; Skipped files
    :duration-ms 5432    ;; Total time
    :errors [...]}       ;; Error details

   Examples:

   ;; Index all CSV files in directory
   (index-files!
    {:index-path \"/tmp/data-index\"
     :docs-path \"/path/to/csv-files\"})

   ;; Index with progress reporting
   (index-files!
    {:index-path \"/tmp/data-index\"
     :docs-path \"/path/to/data\"
     :extensions #{:csv :parquet}
     :progress-fn (fn [{:keys [indexed failed]}]
                    (println \"Progress:\" indexed \"rows indexed,\" failed \"files failed\"))})

   ;; With KNN vectors for semantic search
   (index-files!
    {:index-path \"/tmp/semantic-index\"
     :docs-path \"/path/to/data\"
     :knn-vector-fn (fn [row]
                      ;; Combine relevant columns and get embedding
                      (let [text (str (:title row) \" \" (:description row))]
                        (get-embedding text)))})

   ;; Performance tuned
   (index-files!
    {:index-path \"/tmp/large-index\"
     :docs-path \"/path/to/large-dataset\"
     :ram-buffer-mb 512
     :commit-every-n 5000})

   ;; Gzipped JSON files (automatically supported)
   (index-files!
    {:index-path \"/tmp/compressed-index\"
     :docs-path \"/path/to/data\"
     :extensions #{:jsonld}})  ;; Will index .json, .jsonld, .json.gz, .jsonld.gz

   ;; Mixed compressed CSV and JSON
   (index-files!
    {:index-path \"/tmp/mixed-index\"
     :docs-path \"/path/to/data\"
     :extensions #{:csv :jsonld}})  ;; Handles .csv, .csv.gz, .json, .json.gz, .jsonld, .jsonld.gz

   ;; Exclude compressed files if needed
   (index-files!
    {:index-path \"/tmp/uncompressed-only\"
     :docs-path \"/path/to/data\"
     :exclude-patterns #{#\".*\\.gz$\"}})  ;; Skip all .gz files"
  {:malli/schema [:=> [:cat index-files-params-spec] index-files-return-spec]}
  [{:keys [index-path docs-path] :as opts}]
  (let [start-time (System/currentTimeMillis)
        stats-atom (atom {:indexed 0 :failed 0 :skipped 0 :errors []})
        ;; Extract create-index options
        index-opts (select-keys opts [:ram-buffer-mb :open-mode :analyzer])]
    ;; Create index writer and process files
    (with-open [writer ^IndexWriter (create-index (assoc index-opts :index-path index-path))]
      (walk-and-index writer docs-path opts stats-atom)
      ;; Final commit happens via commit-on-close? true
      (assoc @stats-atom :duration-ms (- (System/currentTimeMillis) start-time)))))


;; ========================================
;; Search Implementation
;; ========================================

(defn- parse-query
  "Parse string query using QueryParser, or return Query object as-is.

   Parameters:
   - query: Either a string (QueryParser syntax) or Query object
   - analyzer: Lucene Analyzer instance
   - default-field: Field name to search when field not specified in query

   Returns: Lucene Query object"
  [query ^Analyzer analyzer ^String default-field]
  (cond
    (instance? Query query)
    query

    (string? query)
    (try
      (.parse (QueryParser. default-field analyzer) ^String query)
      (catch ParseException e
        (throw (ex-info "Failed to parse query string"
                        {:query query
                         :error (.getMessage e)}
                        e))))

    :else
    (throw (ex-info "Invalid query type - must be string or Query object"
                    {:query query
                     :type  (type query)}))))


(defn- doc->map
  "Convert Lucene Document to Clojure map using stored fields.

   For data columns, retrieves values from {column}-stored fields.
   For metadata fields, retrieves directly from stored KeywordField/LongField.

   Parameters:
   - doc: Lucene Document
   - field-names: Optional vector of field names to include (nil = all fields)

   Returns: Map with field values"
  [^Document doc field-names]
  (let [;; Get all field names if not specified
        all-fields      (if field-names
                          field-names
                          (->> (.getFields doc)
                               (map #(.name ^IndexableField %))
                               (map (fn [fname]
                                      ;; Strip -stored suffix to get the base field name
                                      (if (string/ends-with? fname "-stored")
                                        (subs fname 0 (- (count fname) 7))
                                        fname)))
                               distinct))

        ;; Helper to get field value, trying -stored suffix first
        get-field-value (fn [field-name]
                          (let [stored-name (str field-name "-stored")
                                value       (or (.get doc stored-name)
                                                (.get doc field-name))]
                            (when value
                              ;; Only coerce metadata fields to numbers
                              ;; Column data fields remain as strings since they're stored via TextField
                              (cond
                                (= field-name "source-modified") (Long/parseLong value)
                                (= field-name "source-size") (Long/parseLong value)
                                (= field-name "row-number") (Long/parseLong value)
                                :else value))))]
    ;; Build result map
    (reduce
     (fn [acc field-name]
       (if-let [value (get-field-value field-name)]
         (assoc acc (keyword field-name) value)
         acc))
     {}
     all-fields)))


(defn- execute-search
  "Execute search and return results as a tech.v3.dataset.

   Each row contains all document fields plus metadata:
   - :score - Relevance score (if include-score? true)
   - :rank - Position in results (1, 2, 3, ...)
   - :doc_id - Internal Lucene document ID

   Parameters:
   - searcher: IndexSearcher instance
   - query: Lucene Query object
   - opts: Search options map

   Returns: tech.v3.dataset with search results"
  [^IndexSearcher searcher ^Query query opts]
  (let [{:keys [limit offset fields include-score?]
         :or   {limit          10
                offset         0
                include-score? true}} opts

        ;; Execute search
        n-docs              ^int (+ limit offset)
        ^TopDocs top-docs   (.search searcher query n-docs)

        ;; Get score docs and apply offset/limit
        score-docs          (vec (.scoreDocs top-docs))
        relevant-docs       (cond->> score-docs
                              (pos? offset) (drop offset)
                              true (take limit))

        ;; Convert to flat row maps with metadata
        rows                (for [[idx ^ScoreDoc score-doc] (map-indexed vector relevant-docs)]
                              (let [doc-id    (.doc score-doc)
                                    score     (.score score-doc)
                                    ;; Lucene 10.x API: use storedFields().document()
                                    doc       (.document (.storedFields searcher) doc-id)
                                    field-map (doc->map doc fields)

                                    ;; Build flat row: merge fields + metadata
                                    row       (cond-> field-map
                                                include-score? (assoc :score score)
                                                true (assoc :rank (inc idx))  ; 1-based rank
                                                true (assoc :doc_id doc-id))]
                                row))]

    ;; Convert to dataset
    (ds/->dataset rows)))


(defn search
  "Search a Lucene index with QueryParser strings or Query objects.

   Required options:
   :index-path  - Path to Lucene index directory
   :query       - Query (string for QueryParser, or Query object)

   Common options:
   :limit               - Max results to return (default: 10)
   :offset              - Skip first N results (default: 0)
   :fields              - Vector of field names to return (default: all stored)
   :default-field       - Field for string queries (default: 'contents')
   :include-score?      - Include relevance score (default: true)

   Advanced options:
   :analyzer            - :standard, :whitespace, :simple, or Analyzer instance
                          (default: :standard)
   :searcher            - Reuse IndexSearcher instance (advanced, optional)

   Returns:
   tech.v3.dataset where each row contains:
   - All document fields (e.g., :customer_id, :first_name, etc.)
   - :score - Relevance score (if include-score? true, default)
   - :rank - Position in results (1, 2, 3, ...)
   - :doc_id - Internal Lucene document ID

   Example:
   ;; Simple search
   (search {:index-path \"/tmp/my-index\"
            :query \"laptop\"
            :limit 10})
   ;; Returns dataset:
   ;; | :title   | :price | :score | :rank | :doc_id |
   ;; |----------|--------|--------|-------|---------|
   ;; | Laptop A | 999    | 2.5    | 1     | 42      |
   ;; | Laptop B | 1200   | 2.1    | 2     | 57      |

   ;; Field-specific search
   (search {:index-path \"/tmp/my-index\"
            :query \"title:laptop AND price:[500 TO 1500]\"
            :limit 20})

   ;; With Query object
   (import '[org.apache.lucene.search TermQuery]
           '[org.apache.lucene.index Term])
   (search {:index-path \"/tmp/my-index\"
            :query (TermQuery. (Term. \"category\" \"electronics\"))})"
  [{:keys [index-path query default-field analyzer searcher]
    :or   {default-field "contents"
           analyzer      :standard}
    :as   opts}]
  (try
    ;; Validate required parameters
    (when-not index-path
      (throw (ex-info "Missing required parameter: :index-path"
                      {:opts opts})))
    (when-not query
      (throw (ex-info "Missing required parameter: :query"
                      {:opts opts})))

    ;; Create analyzer
    (let [analyzer-inst (make-analyzer analyzer)
          ;; Parse query
          lucene-query  (parse-query query analyzer-inst default-field)]

      (if searcher
        ;; Use provided searcher (advanced usage)
        (execute-search searcher lucene-query opts)

        ;; Create new searcher (normal usage)
        (let [index-dir (FSDirectory/open (.toPath (io/file index-path)))]
          ;; Check if index exists
          (when-not (DirectoryReader/indexExists index-dir)
            (throw (ex-info "Index does not exist"
                            {:index-path index-path})))

          ;; Execute search with auto-closing reader
          (with-open [reader (DirectoryReader/open index-dir)]
            (let [searcher (IndexSearcher. reader)]
              (execute-search searcher lucene-query opts))))))

    (catch ParseException e
      (throw (ex-info "Failed to parse query"
                      {:query query
                       :error (.getMessage e)}
                      e)))
    (catch IOException e
      (throw (ex-info "I/O error accessing index"
                      {:index-path index-path
                       :error      (.getMessage e)}
                      e)))
    (catch Exception e
      (if (instance? ExceptionInfo e)
        (throw e)           ; Re-throw ex-info as-is
        (throw (ex-info "Search error"
                        {:opts  opts
                         :error (.getMessage e)}
                        e))))))


(defn search-one
  "Convenience function to search for a single document.

   Same parameters as `search`, but returns a single result map or nil
   instead of a result collection.

   Automatically sets :limit to 1.

   Returns: Single result map {:score ... :fields {...}} or nil if no matches

   Example:
   (search-one {:index-path \"/tmp/my-index\"
                :query \"id:12345\"})"
  [opts]
  (let [result (search (assoc opts :limit 1))]
    (first (:results result))))






(comment
 ;; ========================================
 ;; create-index usage examples
 ;; ========================================

 ;; Simple usage (backward compatible)
 (def writer (create-index "/tmp/my-index"))
 (.close writer)

 ;; With options map
 (def writer (create-index {:index-path "/tmp/my-index"}))

 ;; Performance-tuned for large batch file indexing with KNN vectors
 (def writer (create-index
              {:index-path         "/tmp/large-index"
               :ram-buffer-mb      512
               :open-mode          :create
               :use-compound-file? false}))

 ;; Code/token search with whitespace analyzer
 (def writer (create-index
              {:index-path "/tmp/code-index"
               :analyzer   :whitespace}))

 ;; Incremental updates with smaller RAM buffer
 (def writer (create-index
              {:index-path    "/tmp/existing-index"
               :open-mode     :append
               :ram-buffer-mb 128}))

 ;; Custom analyzer instance
 (def custom-analyzer (StandardAnalyzer.))
 (def writer (create-index
              {:index-path "/tmp/my-index"
               :analyzer   custom-analyzer}))

 ;; Memory-constrained environment
 (def writer (create-index
              {:index-path       "/tmp/my-index"
               :ram-buffer-mb    64
               :commit-on-close? true}))

 ;; Benchmarking - max performance, manual commit control
 (def writer (create-index
              {:index-path         "/tmp/benchmark-index"
               :ram-buffer-mb      1024
               :open-mode          :create
               :use-compound-file? false
               :commit-on-close?   false}))


 ;; ========================================
 ;; index-files! usage examples
 ;; ========================================

 (load-dataset
  "resources/customers-10000.csv"
  {:format :csv}
  {})


 ;; Index CSV files
 (index-files!
  {:index-path "resources/index"
   :docs-path  "resources/customers-10000.csv"
   :extensions #{:csv}})

 ;; Index mixed formats with progress
 (index-files!
  {:index-path  "/tmp/data-index"
   :docs-path   "/path/to/data"
   :extensions  #{:csv :jsonld :parquet}
   :progress-fn (fn [{:keys [indexed failed]}]
                  (println (format "Indexed %d rows, %d files failed" indexed failed)))})

 ;; With KNN vectors for semantic search
 (defn row->vector [row]
   ;; Example: combine title and description columns
   (let [text (str (:title row) " " (:description row))]
     ;; Call your embedding service/model
     (get-embedding-vector text)))

 (index-files!
  {:index-path    "/tmp/semantic-index"
   :docs-path     "/path/to/products.csv"
   :knn-vector-fn row->vector})

 ;; Performance-tuned for large dataset
 (index-files!
  {:index-path     "/tmp/large-index"
   :docs-path      "/path/to/big-data"
   :ram-buffer-mb  512
   :commit-every-n 10000
   :analyzer       :whitespace})

 ;; Single file indexing
 (index-files!
  {:index-path "/tmp/my-index"
   :docs-path  "/path/to/data.csv"
   :open-mode  :append})

 ;; Load only specific columns (reduces memory usage)
 (index-files!
  {:index-path   "/tmp/filtered-index"
   :docs-path    "/path/to/data.csv"
   :dataset-opts {:column-allowlist ["id" "name" "price"]}})

 ;; Default behavior - column names normalized to snake_case
 (index-files!
  {:index-path "resources/test-index"
   :docs-path  "resources/customers-10000.csv"})
 ;; "First Name" column becomes :first_name field
 ;; Query with: "first_name:Alice"

 ;; Disable normalization - use original column names
 (index-files!
  {:index-path   "/tmp/original-names"
   :docs-path    "/path/to/data.csv"
   :dataset-opts {:key-fn identity}})
 ;; "First Name" stays as "First Name" field (not recommended)

 ;; Custom transformation - override default with keyword (preserves spaces/case)
 (index-files!
  {:index-path   "/tmp/keyword-columns"
   :docs-path    "/path/to/data"
   :dataset-opts {:key-fn keyword}})
 ;; "First Name" becomes :'First Name' keyword

 ;; Custom CSV separator and limit rows
 (index-files!
  {:index-path   "/tmp/tsv-index"
   :docs-path    "/path/to/data.tsv"
   :dataset-opts {:separator \tab
                  :num-rows  10000}})

 ;; Custom parser for specific columns
 (index-files!
  {:index-path   "/tmp/custom-parse"
   :docs-path    "/path/to/data.csv"
   :dataset-opts {:parser-fn {"date"   parse-date-fn
                              "amount" parse-currency-fn}}})

 ;; ========================================
 ;; search usage examples
 ;; ========================================

 ;; Simple full-text search
 (-> (search {:index-path "resources/index"
              :query      "city:East AND country:J*"})
     :email)

 ;; Field-specific search
 (search {:index-path "/tmp/my-index"
          :query      "title:laptop"
          :limit      20})

 ;; Complex boolean query
 (search {:index-path "/tmp/my-index"
          :query      "(category:electronics OR category:computers) AND price:[500 TO 1500]"})

 ;; Range query
 (search {:index-path "/tmp/my-index"
          :query      "price:[100 TO 500]"
          :limit      50})

 ;; Fuzzy search (typo-tolerant)
 (search {:index-path "/tmp/my-index"
          :query      "loptop~2"}) ; Edit distance 2

 ;; Wildcard search
 (search {:index-path "/tmp/my-index"
          :query      "lap*"})

 ;; Phrase search
 (search {:index-path "/tmp/my-index"
          :query      "\"high quality laptop\""})

 ;; Pagination
 (search {:index-path "/tmp/my-index"
          :query      "laptop"
          :offset     20
          :limit      10})    ; Results 21-30

 ;; Select specific fields only
 (search {:index-path "/tmp/my-index"
          :query      "laptop"
          :fields     [:title :price :category]})

 ;; Search without scores (faster)
 (search {:index-path     "/tmp/my-index"
          :query          "laptop"
          :include-score? false})

 ;; Custom analyzer
 (search {:index-path "/tmp/my-index"
          :query      "some_function_name"
          :analyzer   :whitespace})

 ;; Filter by file metadata
 (search {:index-path "/tmp/my-index"
          :query      "laptop AND source-path:\"/data/products.csv\""})

 ;; Search with Query object (advanced)
 (import '[org.apache.lucene.search TermQuery BooleanQuery BooleanClause]
         '[org.apache.lucene.index Term])

 (def my-query
   (doto (BooleanQuery$Builder.)
     (.add (TermQuery. (Term. "category" "electronics")) BooleanClause$Occur/MUST)
     (.add (TermQuery. (Term. "brand" "Apple")) BooleanClause$Occur/MUST)
     (.build)))

 (search {:index-path "/tmp/my-index"
          :query      my-query})

 ;; Search for single document
 (search-one {:index-path "/tmp/my-index"
              :query      "id:12345"})

 ;; Process results
 (let [result (search {:index-path "/tmp/my-index"
                       :query      "laptop"
                       :limit      100})]
   (println "Found" (:total-hits result) "total matches")
   (println "Took" (:took-ms result) "ms")
   (doseq [{:keys [score fields]} (:results result)]
     (println "Score:" score "Title:" (:title fields)))))



(comment
 ;; ========================================
 ;; Query DSL examples
 ;; ========================================

 [...]                        ;; is a placeholder for nested expression or terms

 ;; term
 "something"

 ;; modifiers
 "te*t"                       ;; wildcard
 "te?t"                       ;; single character wildcard

 ;; phrase
 "some phrase here"

 ;; regex
 #"[mb]oat"

 ;; field
 [:field_name [...]]          ;; search within specific field
 [:field_name "something"]
 [:field_name ["something" "some phrase here"]]


 ;; boolean operators
 [:and [...] [...]]
 [:or [...] [...]]
 [:not [...]]

 ;; required
 [:+ [...]]                   ;; required

 ;; excludes
 [:- [...]]                   ;; excludes

 [:fuzzy {:ed 0.7} [...]]     ;; fuzzy search :ed - edit distance or similarity
 [:prox {:nw 10} [...]]       ;; proximity search :nw - number of words
 [:boost {:bf 4} [...]]       ;; boosting :bf - boost factor

 ;; range search
 [:range [100 200]]           ;; inclusive range
 [:range {:exclusive true} [100 200]] ;; exclusive range


 ;; Examples

 ;; title:"leather jacket" AND color:gr?y AND size:M
 [:and
  [:title "leather jacket"]
  [:color "gr?y"]
  [:size "M"]]

 ;; (category:electronics OR category:gadgets) AND title:(phone OR tablet) AND price:[100 TO 500] AND -condition:refurbished
 [:and
  [:or
   [:category "electronics"]
   [:category "gadgets"]]
  [:title
   [:or "phone" "tablet"]]
  [:price
   [:range [100 500]]]
  [:- [:condition "refurbished"]]]

 ;; title:(+smartphone +Samsung~1)
 [:title
  [:+ "smartphone"]
  [:+ [:fuzzy {:ed 1} "Samsung"]]]

 ;; symptoms:(+fever +"sore throat") AND -diagnosis:"COVID-19"
 [:and
  [:symptoms
   [:+ "fever"]
   [:+ "sore throat"]]
  [:- [:diagnosis "COVID-19"]]]

 ;; text:bankrupt* AND text:fraud AND -text:discharge
 [:and
  [:text "bankrupt*"]
  [:text "fraud"]
  [:- [:text "discharge"]]]

 ;; headline:("climate change" OR "global warming") AND date:[20250101 TO 20251231]
 [:and
  [:headline
   [:or "climate change" "global warming"]]
  [:date
   [:range [20250101 20251231]]]]

 ;; content:("artificial intelligence"^3 OR AI)
 [:content
  [:or
   [:boost {:bf 3} "artificial intelligence"]
   "AI"]]

 ;; title:(+deep +learning) AND year:[2015 TO 2025]
 [:and
  [:title
   [:+ "deep"]
   [:+ "learning"]]
  [:year
   [:range [2015 2025]]]])