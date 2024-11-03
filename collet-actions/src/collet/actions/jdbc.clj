(ns collet.actions.jdbc
  (:require
   [clojure.java.io :as io]
   [charred.api :as charred]
   [collet.action :as action]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [next.jdbc.connection :as connection]
   [next.jdbc.date-time :as date-time]
   [honey.sql :as sql]
   [collet.utils :as utils])
  (:import
   [java.io File Writer]
   [java.util Locale]
   [java.sql Array Clob ResultSet ResultSetMetaData SQLFeatureNotSupportedException Time Types]
   [java.time LocalDate LocalDateTime LocalTime]))


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
        row-json ^String (charred/write-json-str row-data)]
    (.write writer row-json)
    (.write writer "\n")))


(defn ->rows-seq
  "Convert a sequence of JSON strings to a lazy sequence of maps.
   The cleanup function is called when the sequence is exhausted."
  [xs mapper cleanup]
  (lazy-seq
   (let [row (first xs)]
     (if row
       (cons (-> row (charred/read-json :key-fn keyword) (mapper))
             (->rows-seq (rest xs) mapper cleanup))
       (do (cleanup) nil)))))


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
               :let [maybe-table-name (try (.getTableName md i)
                                           (catch SQLFeatureNotSupportedException _ nil))
                     table-name       (when (some? maybe-table-name)
                                        (.toLowerCase ^String maybe-table-name Locale/US))
                     column-name      (-> (.getColumnLabel md i) (.toLowerCase Locale/US))
                     full-name        (if (or (not prefix-table?) (nil? table-name))
                                        (keyword column-name)
                                        (keyword table-name column-name))
                     column-type      (.getColumnType md i)]]
           [full-name column-type])
         (into {}))))


(defn prep-connection
  "Prepare a connection for use in a query."
  [connection]
  (let [conn (cond (string? connection)
                   (jdbc/get-datasource {:jdbcUrl connection})

                   (map? connection)
                   (let [{:keys [user password auto-commit]
                          :or   {auto-commit false}} connection]
                     (-> {:jdbcUrl     (connection/jdbc-url connection)
                          :auto-commit auto-commit}
                         (utils/assoc-some :user user :password password)
                         (jdbc/get-datasource)))

                   :else connection)]
    (jdbc/get-connection conn)))


(def query-params-spec
  [:map
   [:connection
    [:or :string
     [:map
      [:dbtype {:optional true} :string]
      [:host {:optional true} :string]
      [:port {:optional true} :int]
      [:dbname {:optional true} :string]
      [:jdbc-url {:optional true} :string]
      [:user {:optional true} :string]
      [:password {:optional true} :string]
      [:auto-commit {:optional true :default false} :boolean]]]]
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
  ;; TODO figure out the response size. If it's small enough, don't use temp file pathway
  (let [result-file ^File (File/createTempFile "jdbc-query-data" ".json")
        rs-types    (atom {})]
    (.deleteOnExit result-file)
    (with-open [writer ^Writer (io/writer result-file :append true)
                conn   (prep-connection connection)]
      (let [append-row   (fn [row]
                           (append-row-to-file writer row)
                           (when (and preserve-types? (empty? @rs-types))
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


(defmethod action/action-fn ::query [_]
  make-query)
