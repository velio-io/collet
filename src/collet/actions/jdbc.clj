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
   [java.io File]
   [java.sql Array Clob Connection]
   [java.time LocalDate LocalDateTime]
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

(json-gen/add-encoder LocalDate json-gen/encode-str)

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
  [writer row]
  (let [row-keys (rs/column-names row)
        row-data (select-keys row row-keys)
        row-json (json/generate-string row-data)]
    (.write writer row-json)
    (.write writer "\n")))


(defn ->rows-seq
  "Convert a sequence of JSON strings to a lazy sequence of maps.
   The cleanup function is called when the sequence is exhausted."
  [xs cleanup]
  (lazy-seq
   (let [row (first xs)]
     (if row
       (cons (json/parse-string row true)
             (->rows-seq (rest xs) cleanup))
       (do (cleanup) nil)))))


(def connectable?
  (m/-simple-schema
   {:type :connectable?
    :pred #(or (instance? Connection %)
               (instance? DataSource %)
               (map? %))
    :type-properties
    {:error/message "should be an instance of java.sql.Connection or javax.sql.DataSource or db-spec map"}}))


(def query-params-spec
  [:map
   [:connection connectable?]
   [:query [:or map? [:cat :string [:* :any]]]]
   [:prefix-table? {:optional true} boolean?]
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
  [{:keys [connection query prefix-table? timeout concurrency result-type cursors fetch-size]
    :or   {prefix-table? true
           fetch-size    4000
           concurrency   :read-only
           cursors       :close
           result-type   :forward-only}}]
  (let [result-file (File/createTempFile "jdbc-query-data" ".json")]
    (.deleteOnExit result-file)
    (with-open [writer (io/writer result-file :append true)
                conn   (jdbc/get-connection connection)]
      (let [append-row   (partial append-row-to-file writer)
            query-string (if (map? query)
                           (sql/format query)
                           query)
            options      (utils/assoc-some
                           {:concurrency concurrency
                            :result-type result-type
                            :cursors     cursors
                            :fetch-size  fetch-size}
                           :builder-fn (when (not prefix-table?)
                                         rs/as-unqualified-lower-maps)
                           :timeout (when (some? timeout)
                                      timeout))]
        (->> (jdbc/plan conn query-string options)
             (run! append-row))))
    (let [reader (io/reader result-file)
          lines  (line-seq reader)]
      (->rows-seq lines #(do (.close reader)
                             (.delete result-file))))))


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