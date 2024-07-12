(ns collet.actions.jdbc
  (:require
   [clojure.java.io :as io]
   [cheshire.core :as json]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [next.jdbc.connection :as connection]
   [honey.sql :as sql]
   [collet.utils :as utils])
  (:import
   [java.io File]))


(defn append-row-to-file [writer row]
  (let [row-keys (rs/column-names row)
        row-data (select-keys row row-keys)
        row-json (json/generate-string row-data)]
    (.write writer row-json)
    (.write writer "\n")))


(defn ->rows-seq [xs cleanup]
  (lazy-seq
   (let [row (first xs)]
     (if row
       (cons (json/parse-string row true)
             (->rows-seq (rest xs) cleanup))
       (do (cleanup) nil)))))


(defn make-query [{:keys [connection query prefix-table?]
                   :or   {prefix-table? true}}]
  (let [result-file (File/createTempFile "jdbc-query-data" ".json")]
    (.deleteOnExit result-file)
    (with-open [writer (io/writer result-file :append true)]
      (let [append-row   (partial append-row-to-file writer)
            query-string (if (map? query)
                           (sql/format query)
                           query)
            options      (utils/assoc-some {}
                           :builder-fn (when (not prefix-table?)
                                         rs/as-unqualified-lower-maps))]
        (->> (jdbc/plan connection query-string options)
             (run! append-row))))
    (let [reader (io/reader result-file)
          lines  (line-seq reader)]
      (->rows-seq lines #(do (.close reader)
                             (.delete result-file))))))


(defn prep-connection [action-spec]
  (update-in action-spec [:params :connection]
             (fn [{:keys [user password] :as conn}]
               (cond (string? conn) (jdbc/get-connection {:jdbcUrl conn}))
               (cond (map? conn) (-> {:jdbcUrl (connection/jdbc-url conn)}
                                     (utils/assoc-some :user user :password password)
                                     (jdbc/get-connection))
                     :else conn))))


(def action
  {:action make-query
   :prep   prep-connection})