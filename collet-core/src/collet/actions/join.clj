(ns collet.actions.join
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [collet.arrow :as arrow]
            [collet.artifact :as artifact]
            [collet.select :as select]
            [collet.stream :as stream]
            [collet.utils :as utils]
            [tech.v3.dataset :as ds]
            [tech.v3.dataset.join :as ds.join])
  (:import [clojure.lang RT]
           [java.io File RandomAccessFile]
           [java.lang AutoCloseable]
           [java.lang.ref Reference]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.sql DriverManager]
           [java.util Properties]
           [org.duckdb DuckDBConnection]))


(defn- connection [dir]
  (let [c (DriverManager/getConnection (str "jdbc:duckdb:" (io/file dir "join.db"))
                                       (doto (Properties.) (.setProperty "jdbc_stream_results" "true")))]
    (with-open [s (.createStatement c)]
      (.execute s "SET threads=1")
      (.execute s "SET memory_limit='64MB'"))
    c))


(defn- key-value [row path]
  (if (sequential? path) (select/select path row) (get row path)))


(defn- canonical [x]
  ;; Clojure map and set equality is independent of iteration order.
  (cond
    (map? x) [:map (sort-by pr-str (map (fn [[k v]] [(canonical k) (canonical v)]) x))]
    (set? x) [:set (sort-by pr-str (map canonical x))]
    (sequential? x) [:seq (mapv canonical x)]
    (integer? x) [:int (str x)]
    (decimal? x) [:decimal (.stripTrailingZeros ^java.math.BigDecimal x)]
    :else x))


(defn- stage! [^DuckDBConnection c dir table input key-path hash-path]
  (with-open [stmt (.createStatement c)]
    (.execute stmt (str "CREATE TABLE " table " (pos BIGINT, k VARCHAR, digest VARCHAR)")))
  (let [cols   (volatile! [])
        schema (:arrow-columns (meta input))]
    (with-open [out (RandomAccessFile. (io/file dir table) "rw")
                app (.createAppender c "main" table)]
      (doseq [batch (if (utils/ds-seq? input) input [input])]
        (vswap! cols #(into % (remove (set %) (ds/column-names batch))))
        (doseq [row (ds/rows batch)]
          (let [row    (cond-> row schema (arrow/prep-record schema))
                k      (key-value row key-path)
                digest (when hash-path (key-value row hash-path))
                pos    (.getFilePointer out)
                bytes  (.getBytes ^String (artifact/encode-row row) StandardCharsets/UTF_8)]
            (when (and hash-path (not (every? #(and (string? %) (not (str/blank? %))) [k digest])))
              (throw (ex-info "Join row is missing an identity or content hash." {})))
            (.writeInt out (alength bytes))
            (.write out bytes)
            (doto app (.beginRow) (.append (long pos)) (.append ^String (artifact/encode-row (canonical k))))
            (if hash-path (.append app ^String digest) (.appendNull app))
            (.endRow app)))))
    {:columns (if schema (mapv :key (:fields (arrow/normalize-schema schema))) @cols)
     :schema  (when schema (arrow/normalize-schema schema))}))


(defn- validate! [c]
  (with-open [s (.createStatement c)]
    (doseq [[sql message]
            [["SELECT k FROM lhs GROUP BY k HAVING count(*)>1 LIMIT 1" "Duplicate identity in join input."]
             ["SELECT k FROM rhs GROUP BY k HAVING count(*)>1 LIMIT 1" "Duplicate identity in join input."]
             ["SELECT 1 FROM lhs FULL JOIN rhs USING(k) WHERE lhs.pos IS NULL OR rhs.pos IS NULL LIMIT 1"
              "Join inputs have different identities."]
             ["SELECT 1 FROM lhs JOIN rhs USING(k) WHERE lhs.digest<>rhs.digest LIMIT 1"
              "Content hashes differ for a matched join identity."]]]
      (with-open [rs (.executeQuery s sql)]
        (when (.next rs) (throw (ex-info message {})))))))


(defn- read-row [^RandomAccessFile file pos]
  (.seek file pos)
  (let [buf (byte-array (.readInt file))]
    (.readFully file buf)
    (artifact/decode-row (String. buf StandardCharsets/UTF_8))))


(defn- joined-batches [dir owner names columns]
  (let [resources            (atom [])
        keep!                (fn [x] (swap! resources conj x) x)
        [reader-owner close] (stream/owned #(doseq [x (reverse @resources)] (.close ^AutoCloseable x)))]
    (try
      (let [c     (keep! (connection dir))
            s     (keep! (.createStatement c))
            rs    (keep! (.executeQuery s "SELECT l.pos,r.pos FROM lhs l LEFT JOIN rhs r USING(k) ORDER BY l.pos,r.pos"))
            left  (keep! (RandomAccessFile. (io/file dir "lhs") "r"))
            right (keep! (RandomAccessFile. (io/file dir "rhs") "r"))
            step  (fn step []
                    (lazy-seq
                     (try
                       (if (.next rs)
                         (let [row (read-row left (.getLong rs 1))
                               pos (.getLong rs 2)]
                           (cons (if (.wasNull rs) row (reduce-kv (fn [m k v] (assoc m (get names k k) v)) row (read-row right pos))) (step)))
                         (do (close) nil))
                       (catch Throwable t (close) (throw t))
                       (finally (Reference/reachabilityFence owner) (Reference/reachabilityFence reader-owner)))))]
        (map (fn [rows]
               (let [batch   (ds/->dataset rows)
                     missing (remove (set (ds/column-names batch)) columns)]
                 (ds/select-columns (reduce (fn [ds k] (assoc ds k (repeat (count rows) nil))) batch missing) columns)))
             (stream/chunks (RT/iter (step)) stream/row-limit)))
      (catch Throwable t (close) (throw t)))))


(defn join [left {:keys [with source target strict cat?]}]
  (when (and strict (not (every? #(some? (get strict %)) [:source-hash :target-hash])))
    (throw (ex-info "A strict join requires both content hash selectors." {:path [:strict]})))
  (let [dir             (.toFile (Files/createTempDirectory "collet-join-" (make-array FileAttribute 0)))
        readers         (atom {})
        closed?         (atom false)
        [owner dispose] (stream/owned #(locking readers
                                         (reset! closed? true)
                                         (try (doseq [close (vals @readers)] (close))
                                              (finally (doseq [^File f (reverse (file-seq dir))]
                                                         (Files/deleteIfExists (.toPath f)))))))]
    (try
      (let [[lhs rhs] (with-open [db (connection dir)]
                        (let [l (stage! db dir "lhs" left source (:source-hash strict))
                              r (stage! db dir "rhs" (utils/make-dataset with {:cat? cat?}) target (:target-hash strict))]
                          (when strict (validate! db))
                          [l r]))
            lcols     (:columns lhs)
            rcols     (:columns rhs)
            ;; Ask TMD for its column collision policy using only one schema row.
            sample    (fn [cols] (ds/->dataset [(zipmap (conj (vec cols) ::key) (repeat 1))]))
            names     (dissoc (:right-column-names (meta (ds.join/left-join ::key (sample lcols) (sample rcols)))) ::key)
            columns   (into lcols (map names rcols))
            schema    (when (and (:schema lhs) (:schema rhs))
                        {:version arrow/schema-version
                         :fields  (into (get-in lhs [:schema :fields])
                                        (map (fn [field]
                                               (let [k (names (:key field))]
                                                 (cond-> (assoc field :nullable? true)
                                                   (not= k (:key field)) (assoc :key
                                                                           k :name
                                                                           (name k))))))
                                        (get-in rhs [:schema :fields]))})
            open      (fn []
                        (locking readers
                          (when @closed? (throw (ex-info "Join handle is closed." {})))
                          (binding [stream/*resources* readers] (joined-batches dir owner names columns))))]
        (stream/view open (cond-> {:collet/close dispose} schema (assoc :arrow-columns schema))))
      (catch Throwable t (dispose) (throw t)))))
