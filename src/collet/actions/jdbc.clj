(ns collet.actions.jdbc
  (:require
   [clojure.java.io :as io]
   [cheshire.core :as json]
   [cheshire.generate :as json-gen]
   [malli.core :as m]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [next.jdbc.connection :as connection]
   [next.jdbc.date-time :as date-time]
   [honey.sql :as sql]
   [collet.utils :as utils])
  (:import
   [java.io File Writer]
   [java.util Locale]
   [java.sql Array Clob Connection ResultSet ResultSetMetaData SQLFeatureNotSupportedException Time Types]
   [java.time LocalDate LocalDateTime LocalTime]
   [javax.sql DataSource]))

;; next.jdbc.types namespace exports the following functions to convert Clojure types to SQL types:
;; as-bit as-tinyint as-smallint as-integer as-bigint as-float as-real as-double
;; as-numeric as-decimal as-char as-varchar as-longvarchar as-date as-time as-timestamp
;; as-binary as-varbinary as-longvarbinary as-null as-other as-java-object as-distinct
;; as-struct as-array as-blob as-clob as-ref as-datalink as-boolean as-rowid as-nchar
;; as-nvarchar as-longnvarchar as-nclob as-sqlxml as-ref-cursor as-time-with-timezone
;; as-timestamp-with-timezone

;; convert SQL dates and timestamps to local time automatically
(date-time/read-as-local)


(extend-protocol rs/ReadableColumn
  Time
  (read-column-by-label [^Time v _]
    (.toLocalTime v))
  (read-column-by-index [^Time v _2 _3]
    (.toLocalTime v)))


(json-gen/add-encoder LocalDate json-gen/encode-str)
(json-gen/add-encoder LocalTime json-gen/encode-str)
(json-gen/add-encoder LocalDateTime json-gen/encode-str)


(extend-protocol rs/ReadableColumn
  Array
  (read-column-by-label [^Array v _]
    (vec (.getArray v)))
  (read-column-by-index [^Array v _ _]
    (vec (.getArray v))))


(extend-protocol rs/ReadableColumn
  Clob
  (read-column-by-label [^Clob v _]
    (rs/clob->string v))
  (read-column-by-index [^Clob v _2 _3]
    (rs/clob->string v)))


(defn append-row-to-file
  "Append a row to a file as a JSON string."
  [^Writer writer ^ResultSet row]
  (let [row-keys (rs/column-names row)
        row-data (select-keys row row-keys)
        row-json (json/generate-string row-data)]
    (.write writer row-json)
    (.write writer "\n")))


(defn ->rows-seq
  "Convert a sequence of JSON strings to a lazy sequence of maps.
   The cleanup function is called when the sequence is exhausted."
  [xs mapper cleanup]
  (lazy-seq
   (let [row (first xs)]
     (if row
       (cons (-> row (json/parse-string true) (mapper))
             (->rows-seq (rest xs) mapper cleanup))
       (do (cleanup) nil)))))


(def connectable?
  (m/-simple-schema
   {:type :connectable?
    :pred #(or (instance? Connection %)
               (instance? DataSource %)
               (map? %))
    :type-properties
    {:error/message "should be an instance of java.sql.Connection or javax.sql.DataSource or db-spec map"}}))


(defn convert-values
  "Convert the values in a row to the appropriate types."
  [types row]
  (reduce-kv
   (fn [acc k v]
     (let [k-type (get types k)
           value  (condp = k-type
                    Types/DATE (LocalDate/parse v)
                    Types/TIME (LocalTime/parse v)
                    Types/TIME_WITH_TIMEZONE (LocalTime/parse v)
                    Types/TIMESTAMP (LocalDateTime/parse v)
                    Types/TIMESTAMP_WITH_TIMEZONE (LocalDateTime/parse v)
                    v)]
       (assoc acc k value)))
   {}
   row))


(defn get-columns-types
  "Returns a map of column names and their SQL types."
  [^ResultSet row prefix-table?]
  (let [^ResultSetMetaData md (rs/metadata row)]
    (->> (for [i (range 1 (inc (.getColumnCount md)))
               :let [table-name  (some-> (try (.getTableName md i)
                                              (catch SQLFeatureNotSupportedException _ nil))
                                         (.toLowerCase Locale/US))
                     column-name (-> (.getColumnLabel md i) (.toLowerCase Locale/US))
                     full-name   (if (or (not prefix-table?) (nil? table-name))
                                   (keyword column-name)
                                   (keyword table-name column-name))
                     column-type (.getColumnType md i)]]
           [full-name column-type])
         (into {}))))


(def query-params-spec
  [:map
   [:connection connectable?]
   [:query [:or map? [:cat :string [:* :any]]]]
   [:options {:optional true} map?]
   [:prefix-table? {:optional true} :boolean]
   [:preserve-types? {:optional true} :boolean]
   [:fetch-size {:optional true} :int]
   [:timeout {:optional true} :int]
   [:concurrency {:optional true} :keyword]
   [:result-type {:optional true} :keyword]
   [:cursors {:optional true} :keyword]])


(defn make-query
  "Execute a query and write the results to a temporary file.
   Return a lazy sequence of maps representing the rows in the result set.
   The query can be a HoneySQL query or a plain SQL string (wrapped in the vector)."
  {:malli/schema [:=> [:cat query-params-spec]
                  [:sequential :any]]}
  [{:keys [connection query options prefix-table? preserve-types?
           timeout concurrency result-type cursors fetch-size]
    :or   {options         {}
           prefix-table?   true
           preserve-types? false
           fetch-size      4000
           concurrency     :read-only
           cursors         :close
           result-type     :forward-only}}]
  (let [result-file (File/createTempFile "jdbc-query-data" ".json")
        rs-types    (atom {})]
    (.deleteOnExit result-file)
    (with-open [writer (io/writer result-file :append true)
                conn   (jdbc/get-connection connection)]
      (let [append-row   (fn [row]
                           (append-row-to-file writer row)
                           (when preserve-types?
                             (swap! rs-types merge (get-columns-types row prefix-table?))))
            query-string (if (map? query)
                           (sql/format query options)
                           query)
            options      (utils/assoc-some
                           {:concurrency concurrency
                            :result-type result-type
                            :cursors     cursors
                            :fetch-size  fetch-size
                            :builder-fn  (if (not prefix-table?)
                                           rs/as-unqualified-lower-maps
                                           rs/as-lower-maps)}
                           :timeout (when (some? timeout)
                                      timeout))]
        (->> (jdbc/plan conn query-string options)
             (run! append-row))))
    (let [reader         (io/reader result-file)
          lines          (line-seq reader)
          row-mapping-fn (if preserve-types?
                           (partial convert-values @rs-types)
                           identity)
          cleanup-fn     #(do (.close reader)
                              (.delete result-file))]
      (->rows-seq lines row-mapping-fn cleanup-fn))))


(defn prep-connection
  "Prepare the connection for the make-query action.
   If the connection is a string, create a new connection using the string as the JDBC URL.
   If the connection is a map, create a new connection using the map as the connection spec.
   Otherwise, return the connection as is."
  [action-spec]
  (update-in action-spec [:params :connection]
             (fn [{:keys [user password auto-commit]
                   :or   {auto-commit false}
                   :as   conn}]
               (cond (string? conn)
                     (jdbc/get-datasource {:jdbcUrl     conn
                                           :auto-commit auto-commit})

                     (map? conn)
                     (-> {:jdbcUrl     (connection/jdbc-url conn)
                          :auto-commit auto-commit}
                         (utils/assoc-some :user user :password password)
                         (jdbc/get-datasource))

                     :else conn))))


(def action
  {:action make-query
   :prep   prep-connection})