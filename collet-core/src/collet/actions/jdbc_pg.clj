(ns collet.actions.jdbc-pg
  (:require
   [next.jdbc.result-set :as rs]
   [next.jdbc.prepare :as p]
   [charred.api :as charred])
  (:import
   [clojure.lang IPersistentMap IPersistentVector]
   [java.sql PreparedStatement]
   [java.time Duration]
   [org.postgresql.util PGInterval PGobject]))


(defn ->pg-interval
  "Takes a Duration instance and converts it into a PGInterval
   instance where the interval is created as a number of seconds."
  [^Duration duration]
  (doto (PGInterval.)
    (.setSeconds (.getSeconds duration))))


(extend-protocol p/SettableParameter
  ;; Convert durations to PGIntervals before inserting into db
  Duration
  (set-parameter [^Duration v ^PreparedStatement s ^long i]
    (.setObject s i (->pg-interval v))))


(defn <-pg-interval
  "Takes a PGInterval instance and converts it into a Duration
   instance. Ignore sub-second units."
  [^PGInterval interval]
  (-> Duration/ZERO
      (.plusSeconds (.getSeconds interval))
      (.plusMinutes (.getMinutes interval))
      (.plusHours (.getHours interval))
      (.plusDays (.getDays interval))))


(extend-protocol rs/ReadableColumn
  ;; Convert PGIntervals back to durations
  PGInterval
  (read-column-by-label [^PGInterval v _]
    (<-pg-interval v))
  (read-column-by-index [^PGInterval v _2 _3]
    (<-pg-interval v)))



(def ->json
  charred/write-json-str)

(def <-json
  #(charred/read-json % :key-fn keyword))


(defn ->pgobject
  "Transforms Clojure data to a PGobject that contains the data as
  JSON. PGObject type defaults to `jsonb` but can be changed via
  metadata key `:pgtype`"
  [x]
  (let [pgtype (or (:pgtype (meta x)) "jsonb")]
    (doto (PGobject.)
      (.setType pgtype)
      (.setValue (->json x)))))


;; if a SQL parameter is a Clojure hash map or vector, it'll be transformed
;; to a PGobject for JSON/JSONB:
(extend-protocol p/SettableParameter
  IPersistentMap
  (set-parameter [m ^PreparedStatement s i]
    (.setObject s i (->pgobject m)))

  IPersistentVector
  (set-parameter [v ^PreparedStatement s i]
    (.setObject s i (->pgobject v))))


(defn <-pgobject
  "Transform PGobject containing `json` or `jsonb` value to Clojure
  data."
  [^PGobject v]
  (let [type  (.getType v)
        value (.getValue v)]
    (if (#{"jsonb" "json"} type)
      (when value
        (with-meta (<-json value) {:pgtype type}))
      value)))


;; if a row contains a PGobject then we'll convert them to Clojure data
;; while reading (if column is either "json" or "jsonb" type):
(extend-protocol rs/ReadableColumn
  PGobject
  (read-column-by-label [^PGobject v _]
    (<-pgobject v))
  (read-column-by-index [^PGobject v _2 _3]
    (<-pgobject v)))