(ns collet.utils
  (:require
   [clojure.walk :as walk]
   [malli.core :as m]
   [tech.v3.dataset :as ds])
  (:import
   [clojure.lang PersistentVector]
   [ham_fisted LinkedHashMap]))


(defn find-first
  "Finds the first item in a collection that matches a predicate. Returns a
  transducer when no collection is provided."
  ([pred]
   (fn [rf]
     (fn
       ([] (rf))
       ([result] (rf result))
       ([result x]
        (if (pred x)
          (ensure-reduced (rf result x))
          result)))))
  ([pred coll]
   (reduce
    (fn [_ x]
      (when (pred x)
        (reduced x)))
    nil
    coll)))


(defn assoc-some
  "Associates a key k, with a value v in a map m, if and only if v is not nil."
  ([m k v]
   (if (nil? v) m (assoc m k v)))

  ([m k v & kvs]
   (reduce (fn [m [k v]] (assoc-some m k v))
           (assoc-some m k v)
           (partition 2 kvs))))


(defn replace-vec-element
  "Replaces elements in a vector according to a replacement map"
  [replacement-map x]
  (if (vector? x)
    (reduce-kv
     (fn [^PersistentVector acc element replacement]
       (let [idx (.indexOf acc element)]
         (if (not= -1 idx)
           (-> (concat (subvec acc 0 idx)
                       replacement
                       (subvec acc (inc idx)))
               (vec))
           acc)))
     x replacement-map)
    x))


(defn replace-all
  "Traverse the data structure and replace elements according to a replacement map"
  [data replacement-map]
  (walk/postwalk
   (partial replace-vec-element replacement-map)
   data))


(def linked-hash-map?
  (m/-simple-schema
   {:type :linked-hash-map?
    :pred #(instance? LinkedHashMap %)
    :type-properties
    {:error/message "should be an instance of LinkedHashMap"}}))


(def dataset?
  (m/-simple-schema
   {:type :dataset?
    :pred #(ds/dataset? %)
    :type-properties
    {:error/message "should be an instance of oftech.v3.dataset (Dataset)"}}))


(defn make-dataset
  [data {:keys [cat? parse]}]
  (let [options (assoc-some {} :parser-fn parse)]
    (cond
      (ds/dataset? data) data
      cat? (ds/->dataset (sequence cat data) options)
      :otherwise (ds/->dataset data options))))