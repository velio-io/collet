(ns collet.actions.file
  (:require
   [charred.api :as charred]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [collet.action :as action]
   [collet.actions.http :as http]
   [collet.utils :as utils]
   [ham-fisted.lazy-noncaching :as lznc]
   [tech.v3.dataset :as ds]
   [tech.v3.dataset.protocols :as ds-proto])
  (:import
   [java.io ByteArrayOutputStream InputStream OutputStream StringReader]
   [java.net URI URISyntaxException]
   [java.nio.file CopyOption FileVisitOption Files FileSystems LinkOption OpenOption Path StandardCopyOption]
   [java.nio.file.attribute BasicFileAttributes FileAttribute]
   [java.security DigestInputStream MessageDigest]
   [java.time Instant]
   [java.util Base64 HexFormat]
   [javax.imageio ImageIO]
   [java.util.zip GZIPInputStream GZIPOutputStream ZipEntry ZipFile ZipInputStream ZipOutputStream]
   [javax.imageio.stream MemoryCacheImageInputStream]))


(defn ->string
  "Converts the input to a string."
  ^String [x]
  (when-not (nil? x)
    (cond
      (string? x) x
      (keyword? x) (name x)
      (symbol? x) (name x)
      :else (.toString ^Object x))))


(defn write-dataset
  "Writes the dataset to a file."
  [dataset file format csv-header?]
  (case format
    :json (ds/write! dataset file {:file-type :json})
    :csv (ds/write! dataset
                    file
                    {:headers?  csv-header?
                     :file-type :csv})))


(defn dataset-seq->csv
  "Writes a sequence of datasets to a CSV file."
  [input file csv-header?]
  (transduce (mapcat (fn [dataset]
                       (let [headers (when csv-header?
                                       (map (comp ->string :name meta) (vals dataset)))
                             rows    (->> (ds-proto/rowvecs dataset nil)
                                          (lznc/map #(lznc/map ->string %)))]
                         (if (and csv-header? (= dataset (first input)))
                           (lznc/concat [headers] rows)
                           rows))))
             (charred/write-csv-rf file {:close-writer? true})
             input))


(defn is-uri?
  "Returns true if the input is a URI or a string that can be converted to a URI."
  [x]
  (or (uri? x)
      (try (new URI x)
           true
           (catch URISyntaxException _
             false))))


(def input-param-spec :any)


(def file-params-spec
  [:map
   [:input input-param-spec]
   [:file-name :string]
   [:folder {:optional true} :string]
   [:cat? {:optional true :default false} :boolean]
   [:format {:optional true} :keyword]
   [:csv-header? {:optional true :default false} :boolean]
   [:encoding {:optional true} :string]
   [:request-options {:optional true} map?]])


(defn write-into-file
  "Writes the input to a file.
   The input data should be a collection of maps or a collection of sequential items.
   Options:
   :input       - the data to write or the actual file (input stream or file path)
   :format      - the format of the file (makes sense only if you're writing data into :json or :csv)
   :file-name   - the name of the file
   :encoding    - text encoding (default UTF-8); existing files are overwritten
   :csv-header? - if true, the CSV file will have a header row"
  {:malli/schema [:=> [:cat file-params-spec]
                  [:map
                   [:file-name :string]
                   [:path :string]]]}
  [{:keys [input format file-name folder csv-header? cat? request-options encoding]
    :or   {csv-header? false cat? false request-options {}}}]
  (let [file (if (some? folder)
               (io/file folder file-name)
               (io/file file-name))]
    (when-not (.exists file)
      (io/make-parents file))

    (cond
      (= :text format)
      (do (when-not (string? input)
            (throw (ex-info "Text input must be a string." {})))
          (spit file input :encoding (or encoding "UTF-8")))

      (= :bytes format)
      (do (when-not (bytes? input)
            (throw (ex-info "Bytes input must be a byte array." {})))
          (with-open [out (io/output-stream file)] (.write out ^bytes input)))

      (= :edn format)
      (let [value (cond (ds/dataset? input) (vec (ds/rows input))
                        (utils/ds-seq? input) (vec (mapcat ds/rows input))
                        :else input)
            text  (pr-str value)]
        (edn/read-string text)
        (spit file text :encoding (or encoding "UTF-8")))

      (or (instance? InputStream input)
          (and (string? input)
               (.exists (io/file input))))
      (when-not (and (string? input)
                     (.exists file)
                     (Files/isSameFile (.toPath (io/file input)) (.toPath file)))
        (with-open [in  ^InputStream (io/input-stream input)
                    out ^OutputStream (io/output-stream file)]
          (io/copy in out)))

      (and (string? input) (is-uri? input))
      (with-open [in  ^InputStream
                      (-> (assoc request-options :url
                                 input :as
                                 :stream)
                          (http/make-request)
                          :body)
                  out ^OutputStream (io/output-stream file)]
        (io/copy in out))

      (utils/ds-seq? input)
      (case format
        :json (ds/write! (apply utils/parallel-concat input) file {:file-type :json})
        :csv (dataset-seq->csv input file csv-header?))

      (ds/dataset? input)
      (write-dataset input file format csv-header?)

      :otherwise
      (-> (utils/make-dataset input {:cat? cat?})
          (write-dataset file format csv-header?)))

    {:file-name file-name
     :path      (.getAbsolutePath file)}))


(defmethod action/action-fn ::sink
  [_]
  write-into-file)


(def ^:private no-links (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))


(def ^:private no-attributes (make-array FileAttribute 0))


(defn file-path ^Path [path]
  (.normalize (.toAbsolutePath (.toPath (io/file path)))))


(defn file-receipt [path]
  (let [path (file-path path)]
    {:file-name (str (.getFileName path)) :path (str path)}))


(defn copy-stream!
  "Copy without materializing; an optional byte limit is checked as bytes arrive."
  [^InputStream input ^OutputStream output max-bytes]
  (when (and (some? max-bytes) (not (nat-int? max-bytes)))
    (throw (ex-info ":max-bytes must be a non-negative integer." {})))
  (let [buffer (byte-array 16384)]
    (loop [total 0]
      (let [n (.read input buffer)]
        (if (neg? n)
          total
          (let [total (+ total n)]
            (when (and max-bytes (> total max-bytes))
              (throw (ex-info "File exceeds :max-bytes." {:max-bytes max-bytes})))
            (.write output buffer 0 n)
            (recur total)))))))


(defn- copy-options [overwrite?]
  (into-array CopyOption (if overwrite? [StandardCopyOption/REPLACE_EXISTING] [])))


(defn stream-to-file
  "Write through a unique sibling staging file; failed reads leave the destination intact."
  [^InputStream input {:keys [destination overwrite? max-bytes]}]
  (when-not (string? destination)
    (throw (ex-info "File reads require :destination." {})))
  (let [target (file-path destination)
        parent (.getParent target)]
    (Files/createDirectories parent no-attributes)
    (let [staging (Files/createTempFile parent ".collet-" ".part" no-attributes)]
      (try
        (with-open [output (Files/newOutputStream staging (make-array OpenOption 0))]
          (copy-stream! input output max-bytes))
        (Files/move staging target (copy-options overwrite?))
        (file-receipt (str target))
        (finally (Files/deleteIfExists staging))))))


(defn read-content
  "Shared decoder. Owns and closes input, except :stream which transfers ownership."
  [^InputStream input
   {:keys [as format encoding max-bytes keywordize csv-header?]
    :or   {as :memory encoding "UTF-8" keywordize true csv-header? true}
    :as   options}]
  (if (= as :stream)
    input
    (with-open [input input]
      (if (= as :file)
        (stream-to-file input options)
        (let [format (or format (if (= as :dataset) :csv :bytes))
              bytes  (with-open [output (ByteArrayOutputStream.)]
                       (copy-stream! input output max-bytes)
                       (.toByteArray output))
              text   #(String. ^bytes bytes ^String encoding)
              value  #(case format
                        :bytes bytes
                        :text (text)
                        :json (if keywordize
                                (charred/read-json (text) :key-fn keyword)
                                (charred/read-json (text)))
                        :edn (edn/read-string (text))
                        (throw (ex-info "Unsupported memory format." {:format format})))]
          (case as
            :memory (value)
            :dataset (case format
                       :csv (with-open [reader (StringReader. (text))]
                              (ds/->dataset reader
                                            {:file-type   :csv
                                             :header-row? csv-header?
                                             :key-fn      (if keywordize keyword identity)}))
                       (:json :edn) (let [rows (value)]
                                      (when-not (and (sequential? rows) (every? map? rows))
                                        (throw (ex-info "Dataset input must contain records." {:format format})))
                                      (ds/->dataset rows))
                       (throw (ex-info "Dataset format must be CSV, JSON, or EDN." {:format format})))
            (throw (ex-info "Unknown file read mode." {:as as}))))))))


(defn read-file [{:keys [path input] :as options}]
  (read-content (io/input-stream (if (contains? options :input) input path)) options))


(defn inspect-image
  [{:keys [input path max-bytes max-pixels base64?]
    :or   {max-bytes 10485760 max-pixels 25000000}}]
  (let [bytes (read-file {:input (or input path) :max-bytes max-bytes})]
    (when-not (pos-int? max-pixels)
      (throw (ex-info ":max-pixels must be positive." {})))
    (with-open [stream (MemoryCacheImageInputStream. (io/input-stream bytes))]
      (let [readers (ImageIO/getImageReaders stream)]
        (when-not (.hasNext readers)
          (throw (ex-info "Input is not a supported image." {})))
        (let [reader (.next readers)]
          (try
            (.setInput reader stream)
            (let [format (case (str/lower-case (.getFormatName reader))
                           "png" :png
                           ("jpg" "jpeg") :jpeg
                           (throw (ex-info "Expected a PNG or JPEG image." {})))
                  width  (.getWidth reader 0)
                  height (.getHeight reader 0)]
              (when (> (* (long width) height) max-pixels)
                (throw (ex-info "Image exceeds :max-pixels." {:max-pixels max-pixels})))
              (.read reader 0)
              (cond-> {:format     format
                       :mime-type  (str "image/" (name format))
                       :width      width
                       :height     height
                       :size-bytes (alength ^bytes bytes)
                       :sha256     (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") bytes))}
                base64? (assoc :base64 (.encodeToString (Base64/getEncoder) bytes))))
            (finally (.dispose reader))))))))


(defn decode-base64 [{:keys [input]}]
  (.decode (Base64/getDecoder) ^String input))


(defmethod action/action-fn ::inspect-image [_] inspect-image)


(defmethod action/action-fn ::decode-base64 [_] decode-base64)


(defn stat-file [{:keys [path]}]
  (let [p     (file-path path)
        attrs (Files/readAttributes p BasicFileAttributes no-links)]
    (merge (file-receipt path)
           {:size-bytes  (.size attrs)
            :modified-at (str (.toInstant (.lastModifiedTime attrs)))
            :type        (cond (.isSymbolicLink attrs) :symlink
                               (.isDirectory attrs) :directory
                               :else :file)})))


(defn exists-file? [{:keys [path]}]
  (Files/exists (file-path path) no-links))


(defn matches-file?
  "Shared listing filters; names are relative paths or object keys."
  [{:keys [prefix glob extensions min-size max-size modified-after modified-before]}
   {:keys [file-name size-bytes modified-at relative-path key]}]
  (let [name      (or relative-path key file-name)
        extension (some-> (re-find #"\.([^./]+)$" file-name)
                          second
                          str/lower-case)
        instant   #(if (instance? Instant %) % (Instant/parse %))]
    (and (or (nil? prefix) (str/starts-with? name prefix))
         (or (nil? glob)
             (.matches (.getPathMatcher (FileSystems/getDefault) (str "glob:" glob))
                       (.getPath (FileSystems/getDefault) name (make-array String 0))))
         (or (nil? extensions) (some #(= extension (str/lower-case (str/replace % #"^\." ""))) extensions))
         (or (nil? min-size) (<= min-size size-bytes))
         (or (nil? max-size) (<= size-bytes max-size))
         (or (nil? modified-after) (.isAfter (instant modified-at) (instant modified-after)))
         (or (nil? modified-before) (.isBefore (instant modified-at) (instant modified-before))))))


(defn listing-dataset [columns rows]
  (ds/->dataset (into (array-map) (map (fn [column] [column (mapv column rows)])) columns)
                {:parser-fn (into {} (map (fn [column] [column (if (= column :size-bytes) :int64 :string)])) columns)}))


(def listing-columns [:file-name :path :relative-path :size-bytes :modified-at])


(defn list-files [{:keys [path recursive? limit page-size] :as options}]
  (when (and limit (not (nat-int? limit)))
    (throw (ex-info ":limit must be a non-negative integer." {})))
  (when (and page-size (not (pos-int? page-size)))
    (throw (ex-info ":page-size must be positive." {})))
  (let [root (file-path path)]
    (when (or (Files/isSymbolicLink root) (not (Files/isDirectory root no-links)))
      (throw (ex-info "Listing requires a directory, not a symlink." {:path path})))
    (with-open [paths (Files/walk root (int (if recursive? Integer/MAX_VALUE 1)) (make-array FileVisitOption 0))]
      (->> (iterator-seq (.iterator paths))
           (filter #(Files/isRegularFile % no-links))
           (sort-by str)
           (map #(assoc (stat-file {:path (str %)}) :relative-path (str (.relativize root %))))
           (filter #(matches-file? options %))
           (take (or limit Long/MAX_VALUE))
           (listing-dataset listing-columns)))))


(defn mkdir [{:keys [path]}]
  (Files/createDirectories (file-path path) no-attributes)
  (file-receipt path))


(defn temp-file [{:keys [folder prefix suffix] :or {prefix "collet-" suffix ".tmp"}}]
  (file-receipt (str (if folder
                       (Files/createTempFile (file-path folder) prefix suffix no-attributes)
                       (Files/createTempFile prefix suffix no-attributes)))))


(defn temp-dir [{:keys [folder prefix] :or {prefix "collet-"}}]
  (file-receipt (str (if folder
                       (Files/createTempDirectory (file-path folder) prefix no-attributes)
                       (Files/createTempDirectory prefix no-attributes)))))


(defn- tree-paths [^Path root]
  (with-open [paths (Files/walk root (make-array FileVisitOption 0))]
    (vec (iterator-seq (.iterator paths)))))


(defn- transfer-paths [{:keys [path destination recursive? overwrite?]}]
  (let [source (file-path path)
        target (file-path destination)]
    (when (or (= source target) (.startsWith target source))
      (throw (ex-info "Destination must be outside the source." {})))
    (when (and (Files/exists target no-links) (not overwrite?))
      (throw (ex-info "Destination already exists." {:destination destination})))
    (when (and (Files/isDirectory source no-links) (not recursive?))
      (throw (ex-info "Directory transfer requires :recursive? true." {})))
    [source target]))


(defn copy-file [{:keys [overwrite?] :as options}]
  (let [[source target] (transfer-paths options)
        paths           (tree-paths source)]
    (when (some #(Files/isSymbolicLink %) paths)
      (throw (ex-info "Copy does not follow symbolic links." {})))
    (doseq [^Path path paths
            :let       [output (.resolve target (.relativize source path))]]
      (if (Files/isDirectory path no-links)
        (Files/createDirectories output no-attributes)
        (do (Files/createDirectories (.getParent output) no-attributes)
            (Files/copy path output (copy-options overwrite?)))))
    (file-receipt (str target))))


(defn move-file [{:keys [overwrite?] :as options}]
  (let [[source target] (transfer-paths options)]
    (Files/createDirectories (.getParent target) no-attributes)
    (Files/move source target (copy-options overwrite?))
    (file-receipt (str target))))


(defn delete-file [{:keys [path recursive?]}]
  (let [p (file-path path)]
    (when (nil? (.getParent p))
      (throw (ex-info "Cannot delete a filesystem root." {})))
    (if (and recursive? (Files/isDirectory p no-links))
      (do (doseq [^Path item (reverse (tree-paths p))] (Files/delete item)) true)
      (Files/deleteIfExists p))))


(defn checksum [{:keys [path algorithm] :or {algorithm "SHA-256"}}]
  (let [digest (MessageDigest/getInstance algorithm)]
    (with-open [input (DigestInputStream. (io/input-stream path) digest)]
      (.transferTo input (OutputStream/nullOutputStream)))
    {:algorithm algorithm :checksum (.formatHex (HexFormat/of) (.digest digest)) :path (str (file-path path))}))


(defn- check-archive-target [^Path root name]
  (let [entry  (.getPath (FileSystems/getDefault) name (make-array String 0))
        target (.normalize (.resolve root entry))]
    (when (or (.isAbsolute entry)
              (str/includes? name "\\")
              (re-find #"^[A-Za-z]:" name)
              (not (.startsWith target root)))
      (throw (ex-info "Unsafe archive entry path." {:entry name})))
    (loop [part target]
      (when (and part (.startsWith part root))
        (when (Files/isSymbolicLink part)
          (throw (ex-info "Archive destination contains a symbolic link." {:entry name})))
        (recur (.getParent part))))
    target))


(defn zip-file [{:keys [path destination overwrite?]}]
  (let [root       (file-path path)
        directory? (Files/isDirectory root no-links)
        base       (if directory? root (.getParent root))
        paths      (tree-paths root)
        target     (file-path destination)]
    (when (or (.startsWith target root) (some #(Files/isSymbolicLink %) paths))
      (throw (ex-info "ZIP source must not include symlinks or its destination." {})))
    (Files/createDirectories (.getParent target) no-attributes)
    (let [staging (Files/createTempFile (.getParent target) ".collet-" ".zip" no-attributes)]
      (try
        (with-open [out (ZipOutputStream. (io/output-stream (str staging)))]
          (doseq [^Path p paths
                  :when   (not (and directory? (= p root)))]
            (let [dir? (Files/isDirectory p no-links)
                  name (str (str/replace (str (.relativize base p)) "\\" "/") (when dir? "/"))]
              (.putNextEntry out (ZipEntry. name))
              (when-not dir? (with-open [in (io/input-stream (str p))] (io/copy in out)))
              (.closeEntry out))))
        (Files/move staging target (copy-options overwrite?))
        (file-receipt destination)
        (finally (Files/deleteIfExists staging))))))


(defn unzip-file [{:keys [path destination overwrite? max-entries max-bytes]
                   :or   {max-entries 10000 max-bytes 1073741824}}]
  (when-not (and (pos-int? max-entries) (nat-int? max-bytes))
    (throw (ex-info "Archive limits must be non-negative, with positive :max-entries." {})))
  (let [root (file-path destination)]
    (check-archive-target root "")
    (Files/createDirectories root no-attributes)
    (with-open [archive (ZipFile. (io/file path))
                input   (ZipInputStream. (io/input-stream path))]
      (loop [entries 0
             total   0]
        (when-let [entry (.getNextEntry input)]
          (when (>= entries max-entries)
            (throw (ex-info "Archive exceeds :max-entries." {:max-entries max-entries})))
          (let [target (check-archive-target root (.getName entry))
                bytes  (if (.isDirectory entry)
                         (do (Files/createDirectories target no-attributes) 0)
                         (do (Files/createDirectories (.getParent target) no-attributes)
                             (stream-to-file input {:destination (str target) :overwrite? overwrite? :max-bytes (- max-bytes total)})
                             (Files/size target)))]
            (.closeEntry input)
            (recur (inc entries) (+ total bytes))))))
    (file-receipt destination)))


(defn gzip-file [{:keys [path destination overwrite?]}]
  (let [target (file-path destination)]
    (Files/createDirectories (.getParent target) no-attributes)
    (let [staging (Files/createTempFile (.getParent target) ".collet-" ".gz" no-attributes)]
      (try
        (with-open [input  (io/input-stream path)
                    output (GZIPOutputStream. (io/output-stream (str staging)))]
          (io/copy input output))
        (Files/move staging target (copy-options overwrite?))
        (file-receipt destination)
        (finally (Files/deleteIfExists staging))))))


(defn gunzip-file [{:keys [path max-bytes] :or {max-bytes 1073741824} :as options}]
  (with-open [input (GZIPInputStream. (io/input-stream path))]
    (stream-to-file input (assoc options :max-bytes max-bytes))))


(defmethod action/action-fn ::read [_] read-file)


(defmethod action/action-fn ::list [_] list-files)


(defmethod action/action-fn ::stat [_] stat-file)


(defmethod action/action-fn ::exists? [_] exists-file?)


(defmethod action/action-fn ::mkdir [_] mkdir)


(defmethod action/action-fn ::temp-file [_] temp-file)


(defmethod action/action-fn ::temp-dir [_] temp-dir)


(defmethod action/action-fn ::copy [_] copy-file)


(defmethod action/action-fn ::move [_] move-file)


(defmethod action/action-fn ::delete [_] delete-file)


(defmethod action/action-fn ::checksum [_] checksum)


(defmethod action/action-fn ::zip [_] zip-file)


(defmethod action/action-fn ::unzip [_] unzip-file)


(defmethod action/action-fn ::gzip [_] gzip-file)


(defmethod action/action-fn ::gunzip [_] gunzip-file)
