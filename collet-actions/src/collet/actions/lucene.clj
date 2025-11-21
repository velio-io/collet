(ns collet.actions.lucene
  (:require
   [clojure.java.io :as io])
  (:import
   [org.apache.lucene.analysis.standard StandardAnalyzer]
   [org.apache.lucene.document Document TextField KeywordField LongField KnnFloatVectorField]
   [org.apache.lucene.document Field$Store]
   [org.apache.lucene.index IndexWriter IndexWriterConfig IndexWriterConfig$OpenMode Term DirectoryReader]
   [org.apache.lucene.index VectorSimilarityFunction StoredFields]
   [org.apache.lucene.store FSDirectory]
   [org.apache.lucene.search IndexSearcher Query QueryVisitor BooleanQuery BooleanQuery$Builder BooleanClause$Occur]
   [org.apache.lucene.search TopDocs ScoreDoc KnnFloatVectorQuery]
   [org.apache.lucene.queryparser.classic QueryParser]
   [org.apache.lucene.demo.knn DemoEmbeddings KnnVectorDict]
   [java.io BufferedReader InputStreamReader IOException]
   [java.nio.charset StandardCharsets]
   [java.nio.file Files Paths FileVisitor FileVisitResult Path LinkOption]
   [java.nio.file.attribute BasicFileAttributes FileTime]
   [java.util Date ArrayList]))


(def knn-dict-name "knn-dict")

(defrecord IndexFilesContext [demo-embeddings vector-dict])


(defn create-context
  "Create an IndexFiles context with optional KNN vector dictionary"
  [vector-dict]
  (->IndexFilesContext
   (when vector-dict (DemoEmbeddings. vector-dict))
   vector-dict))


(defn index-doc
  "Index a single document with path, modified time, and contents"
  [^IndexWriter writer ^Path file-path last-modified {:keys [demo-embeddings]}]
  (with-open [stream (Files/newInputStream file-path (make-array java.nio.file.OpenOption 0))]
    (let [doc (Document.)]
      ;; Add path field (stored, keyword)
      (.add doc (KeywordField. "path" (.toString file-path) Field$Store/YES))

      ;; Add modified field (not stored, long)
      (.add doc (LongField. "modified" last-modified Field$Store/NO))

      ;; Add contents field (text, not stored)
      (.add doc (TextField. "contents"
                            (BufferedReader.
                             (InputStreamReader. stream StandardCharsets/UTF_8))))

      ;; Add KNN vector field if embeddings are available
      (when demo-embeddings
        (with-open [vec-stream (Files/newInputStream file-path (make-array java.nio.file.OpenOption 0))]
          (let [embedding (.computeEmbedding demo-embeddings
                                            (BufferedReader.
                                             (InputStreamReader. vec-stream StandardCharsets/UTF_8)))]
            (.add doc (KnnFloatVectorField. "contents-vector"
                                           embedding
                                           VectorSimilarityFunction/DOT_PRODUCT)))))

      ;; Add or update document based on mode
      (if (= (.getOpenMode (.getConfig writer)) IndexWriterConfig$OpenMode/CREATE)
        (do
          (println "adding" (.toString file-path))
          (.addDocument writer doc))
        (do
          (println "updating" (.toString file-path))
          (.updateDocument writer (Term. "path" (.toString file-path)) doc))))))


(defn create-file-visitor
  "Create a FileVisitor that indexes all files in a directory tree"
  [writer context]
  (reify FileVisitor
    (visitFile [_ file attrs]
      (try
        (index-doc writer file (.toMillis (.lastModifiedTime attrs)) context)
        (catch Exception e
          (println "Error indexing" (.toString file) ":" (.getMessage e))))
      FileVisitResult/CONTINUE)

    (visitFileFailed [_ file exc]
      (println "Failed to visit" (.toString file) ":" (.getMessage exc))
      FileVisitResult/CONTINUE)

    (preVisitDirectory [_ dir attrs]
      FileVisitResult/CONTINUE)

    (postVisitDirectory [_ dir exc]
      FileVisitResult/CONTINUE)))


(defn index-docs
  "Index documents starting from the given path (file or directory)"
  [^IndexWriter writer ^Path path context]
  (if (Files/isDirectory path (make-array LinkOption 0))
    (Files/walkFileTree path (create-file-visitor writer context))
    (index-doc writer path
               (.toMillis (Files/getLastModifiedTime path (make-array LinkOption 0)))
               context)))


(defn index-files!
  "Main indexing function
   Options:
   - :index-path    - Path to the index directory (required)
   - :docs-path     - Path to documents to index (required)
   - :knn-dict-path - Path to KNN vector dictionary (optional)
   - :create?       - Create new index (default true)
   - :update?       - Update existing index (default false)"
  [{:keys [index-path docs-path knn-dict-path create? update?]
    :or {create? true update? false}}]
  (let [docs-path-obj (Paths/get docs-path (make-array String 0))]

    ;; Validate docs path
    (when-not (Files/isReadable docs-path-obj)
      (throw (IOException. (str "Document directory '" (.toAbsolutePath docs-path-obj)
                                "' does not exist or is not readable"))))

    (println "Indexing to directory '" index-path "'...")
    (let [start-time (Date.)
          index-dir (FSDirectory/open (Paths/get index-path (make-array String 0)))
          analyzer (StandardAnalyzer.)
          config (doto (IndexWriterConfig. analyzer)
                   (.setOpenMode (if (and (not update?) create?)
                                   IndexWriterConfig$OpenMode/CREATE
                                   IndexWriterConfig$OpenMode/CREATE_OR_APPEND)))

          ;; Build KNN dictionary if path provided
          vector-dict (when knn-dict-path
                        (let [knn-path (Paths/get knn-dict-path (make-array String 0))]
                          (KnnVectorDict/build knn-path index-dir knn-dict-name)
                          (KnnVectorDict. index-dir knn-dict-name)))

          ram-bytes-used (if vector-dict (.ramBytesUsed vector-dict) 0)
          context (create-context vector-dict)]

      (try
        ;; Index documents
        (with-open [writer (IndexWriter. index-dir config)]
          (index-docs writer docs-path-obj context))

        ;; Report results
        (let [end-time (Date.)]
          (with-open [reader (DirectoryReader/open index-dir)]
            (println (str "Indexed " (.numDocs reader) " documents in "
                         (- (.getTime end-time) (.getTime start-time)) " milliseconds"))

            ;; Check for KNN vector dictionary abuse
            (when (and knn-dict-path
                       (> (.numDocs reader) 100)
                       (< ram-bytes-used 1000000)
                       (nil? (System/getProperty "smoketester")))
              (throw (RuntimeException.
                      "Are you (ab)using the toy vector dictionary? See the package javadocs to understand why you got this exception.")))))

        (catch IOException e
          (println (str (.getClass e) " caught with message: " (.getMessage e))))

        (finally
          (when vector-dict
            (try
              (.close vector-dict)
              (catch Exception _))))))))

;; ============================================================
;; Search Functions
;; ============================================================
#_
(defn extract-query-terms
  "Extract terms from a query for use in KNN search"
  [^Query query field]
  (let [terms (ArrayList.)]
    (.visit query
            (reify QueryVisitor
              (consumeTerms [_ q & [^Term field-obj ^Term term]]
                (when (= (.field field-obj) field)
                  (.add terms (.utf8ToString (.bytes term)))))
              (consumeTermsMatching [_ q field-obj bytes-supplier]
                nil)
              (getSubVisitor [_ occur field-obj]
                this)))
    terms))

#_
(defn add-semantic-query
  "Combine text query with KNN vector search for semantic results"
  [^Query query ^KnnVectorDict vector-dict knn-hits field]
  (let [terms (extract-query-terms query field)
        term-text (clojure.string/join " " terms)]
    (if (pos? (.length term-text))
      (let [embeddings (DemoEmbeddings. vector-dict)
            embedding (.computeEmbedding embeddings term-text)
            knn-query (KnnFloatVectorQuery. "contents-vector" embedding knn-hits)
            builder (BooleanQuery$Builder.)]
        (.add builder query BooleanClause$Occur/SHOULD)
        (.add builder knn-query BooleanClause$Occur/SHOULD)
        (.build builder))
      query)))

(defn display-results
  "Display search results in formatted or raw mode"
  [^StoredFields stored-fields score-docs start end raw?]
  (doseq [i (range start end)]
    (let [^ScoreDoc score-doc (aget score-docs i)]
      (if raw?
        (println (str "doc=" (.-doc score-doc) " score=" (.-score score-doc)))
        (let [doc (.document stored-fields (.-doc score-doc))
              path (.get doc "path")]
          (if path
            (do
              (println (str (inc i) ". " path))
              (when-let [title (.get doc "title")]
                (println (str "   Title: " title))))
            (println (str (inc i) ". " "No path for this document"))))))))

(defn do-paging-search
  "Perform paginated search with interactive navigation"
  [^BufferedReader in ^IndexSearcher searcher ^Query query hits-per-page raw? interactive?]
  (let [initial-results (.search searcher query (int (* 5 hits-per-page)))]
    (loop [score-docs (.-scoreDocs initial-results)
           start 0
           total-hits (Math/toIntExact (.value (.-totalHits initial-results)))]

      (when (zero? start)
        (println (str total-hits " total matching documents")))

      ;; Check if we need to collect more results
      (let [score-docs (if (and (> (+ start hits-per-page) (alength score-docs))
                                (< (alength score-docs) total-hits))
                         (do
                           (println (str "Only showing first " (alength score-docs) " hits out of " total-hits))
                           (println "Collect more (y/n) ?")
                           (let [response (.readLine in)]
                             (if (and response
                                      (pos? (.length response))
                                      (not= (.charAt response 0) \n))
                               (let [new-results (.search searcher query total-hits)]
                                 (.-scoreDocs new-results))
                               score-docs)))
                         score-docs)

            end (min (alength score-docs) (+ start hits-per-page))
            stored-fields (.storedFields searcher)]

        ;; Display current page of results
        (display-results stored-fields score-docs start end raw?)

        ;; Handle interactive navigation
        (if (and interactive? (pos? end))
          (do
            (print "Press ")
            (when (>= start hits-per-page)
              (print "(p)revious page, "))
            (when (< (+ start hits-per-page) total-hits)
              (print "(n)ext page, "))
            (println "(q)uit or enter number to jump to a page.")
            (flush)

            (if-let [line (.readLine in)]
              (let [action (cond
                             ;; Empty line or just whitespace
                             (zero? (.length line))
                             :stay

                             ;; Quit
                             (= (.charAt line 0) \q)
                             :quit

                             ;; Previous page
                             (= (.charAt line 0) \p)
                             :previous

                             ;; Next page
                             (and (= (.charAt line 0) \n)
                                  (< (+ start hits-per-page) total-hits))
                             :next

                             ;; Jump to page number
                             :else
                             (try
                               (let [page (Integer/parseInt line)]
                                 [:jump page])
                               (catch Exception _
                                 :stay)))]

                (case action
                  :quit
                  nil  ;; Exit loop by returning nil

                  :previous
                  (recur score-docs (max 0 (- start hits-per-page)) total-hits)

                  :next
                  (recur score-docs (+ start hits-per-page) total-hits)

                  :stay
                  (recur score-docs start total-hits)

                  ;; Handle [:jump page] pattern
                  (let [[_ page] action
                        new-start (* (dec page) hits-per-page)]
                    (if (< new-start total-hits)
                      (recur score-docs new-start total-hits)
                      (do
                        (println "No such page")
                        (recur score-docs start total-hits))))))

              ;; No input, stay at current position
              (recur score-docs start total-hits)))

          ;; Non-interactive mode, just return
          nil)))))


(defn search-index!
  "Search a Lucene index with optional KNN vector search
   Options:
   - :index-path     - Path to the index directory (default: 'index')
   - :field          - Field to search (default: 'contents')
   - :queries-file   - Path to file with queries (optional)
   - :query          - Single query string (optional)
   - :hits-per-page  - Results per page (default: 10)
   - :raw?           - Show raw results (doc ID + score) (default: false)
   - :knn-hits       - Number of KNN vector results to include (optional)
   - :repeat         - Repeat search N times for benchmarking (default: 0)"
  [{:keys [index-path field queries-file query hits-per-page raw? knn-hits repeat]
    :or {index-path "index" field "contents" hits-per-page 10 raw? false repeat 0}}]
  (let [index-dir (FSDirectory/open (Paths/get index-path (make-array String 0)))
        reader (DirectoryReader/open index-dir)
        searcher (IndexSearcher. reader)
        analyzer (StandardAnalyzer.)
        vector-dict (when (and knn-hits (pos? knn-hits))
                      (KnnVectorDict. (.directory reader) knn-dict-name))
        in (if queries-file
             (Files/newBufferedReader
              (Paths/get queries-file (make-array String 0))
              StandardCharsets/UTF_8)
             (BufferedReader. (InputStreamReader. System/in StandardCharsets/UTF_8)))
        parser (QueryParser. field analyzer)
        interactive? (and (nil? queries-file) (nil? query))]

    (try
      (when interactive?
        (println "Enter query: "))

      (loop [query-line (or query (.readLine in))]
        (when (and query-line (pos? (.length query-line)))
          (let [trimmed (.trim query-line)]
            (when (pos? (.length trimmed))
              (let [parsed-query (.parse parser trimmed)
                    final-query parsed-query]
                (println (str "Searching for: " (.toString final-query field)))

                ;; Benchmarking mode
                (when (pos? repeat)
                  (let [start (Date.)]
                    (dotimes [_ repeat]
                      (.search searcher final-query 100))
                    (let [end (Date.)]
                      (println (str "Time: " (- (.getTime end) (.getTime start)) "ms")))))

                ;; Actual search
                (do-paging-search in searcher final-query hits-per-page raw? interactive?)

                (when-not (or query interactive?)
                  (recur (.readLine in))))))

          (when (and interactive? (not query))
            (recur (.readLine in)))))

      (finally
        (when vector-dict
          (try
            (.close vector-dict)
            (catch Exception _)))
        (.close reader)))))

(def cli-options
  [["-i" "--index PATH" "Index directory path"
    :id :index-path
    :default "index"]
   ["-d" "--docs PATH" "Documents directory path"
    :id :docs-path
    :required "DOCS_PATH is required"]
   ["-k" "--knn-dict PATH" "KNN vector dictionary path"
    :id :knn-dict-path]
   ["-u" "--update" "Update existing index (default: create new)"
    :id :update?
    :default false]
   ["-c" "--create" "Create new index"
    :id :create?
    :default true]
   ["-h" "--help" "Show help message"]])

(comment
  ;; Indexing example
  (index-files!
   {:index-path "/Users/usuario/Projects/collet/collet-actions/resources/index"
    :docs-path "/Users/usuario/Projects/collet/collet-actions/src/collet/actions"})

  ;; Basic search example
  (search-index!
   {:index-path "/Users/usuario/Projects/collet/collet-actions/resources/index"
    :query "lucene"
    :hits-per-page 5})

  ;; Search with KNN vector support
  (search-index!
   {:index-path "/Users/usuario/Projects/collet/collet-actions/resources/index"
    :query "search functionality"
    :knn-hits 10
    :hits-per-page 5})

  ;; Interactive search (no query specified)
  (search-index!
   {:index-path "/Users/usuario/Projects/collet/collet-actions/resources/index"})

  ;; Benchmark search
  (search-index!
   {:index-path "/Users/usuario/Projects/collet/collet-actions/resources/index"
    :query "test"
    :repeat 100}))



[:title "foo bar"]

[:book [:title "foo bar"]]

[:book*title "foo bar"]

[:book* "foo bar"]

[:book.* "foo bar"]

[:book/* "foo bar"]

[:title {:proxi 4} "foo bar"]

[:title "foo*"]

[:and [:title "foo"]
      [:not [:title "bar"]]]

[:title [:and [:or "quick" "brown"] "fox"]]


