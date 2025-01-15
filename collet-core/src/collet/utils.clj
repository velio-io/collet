(ns collet.utils
  (:require
   [clojure.string :as string]
   [clojure.walk :as walk]
   [malli.core :as m]
   [sci.core :as sci]
   [tech.v3.dataset :as ds])
  (:import
   [clojure.lang PersistentVector]
   [ham_fisted LinkedHashMap]
   [sci.impl.opts Ctx]))


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


(defn ds-seq?
  [data]
  (or (-> data meta :ds-seq)
      (and (sequential? data)
           (ds/dataset? (first data)))))


(defn make-dataset
  [data {:keys [cat? parse]}]
  (let [options (assoc-some {} :parser-fn parse)]
    (cond
      (ds/dataset? data) data
      (ds-seq? data) data
      cat? (ds/->dataset (sequence cat data) options)
      :otherwise (ds/->dataset data options))))


(defn parallel-concat
  [& dss]
  (let [cnt    (max 10 (int (Math/sqrt (count dss))))
        subdss (pmap (partial apply ds/concat-inplace) (partition-all cnt dss))]
    (apply ds/concat-inplace subdss)))


(defn samplify
  "This function will walk through a given data structure and will reduce large sequences for performance reasons"
  [data]
  (walk/prewalk
   (fn [x]
     (cond
       (ds-seq? x) (list (ds/select-rows (first x) (range (min 100 (ds/row-count (first x))))))
       (ds/dataset? x) (ds/select-rows x (range (min 100 (ds/row-count x))))
       (and (sequential? x) (not (map-entry? x))) (take 100 x)
       :otherwise x))
   data))


(defn ->classes-map
  [classes]
  (reduce
   (fn [acc klass-sym]
     (let [klass            (resolve klass-sym)
           short-klass-name (-> (str klass) (string/split #"\.") last symbol)]
       (assoc acc klass-sym klass
                  short-klass-name klass)))
   {:allow :all}
   classes))


(defn ->namespaces-map
  [namespaces]
  (reduce
   (fn [acc namespace]
     (let [[full-ns-name ns-name]
           (if (> (count namespace) 1)
             [(first namespace) (last namespace)]
             [(first namespace) (first namespace)])

           fake-ns (sci/create-ns ns-name)
           publics (ns-publics full-ns-name)
           sci-ns  (update-vals publics #(sci/copy-var* % fake-ns))
           acc'    (assoc-in acc [:namespaces full-ns-name] sci-ns)]
       (if (not= full-ns-name ns-name)
         (assoc-in acc' [:ns-aliases ns-name] full-ns-name)
         acc')))
   {:namespaces {}}
   namespaces))


(def eval-context-spec
  (m/-simple-schema
   {:type :eval-context
    :pred #(instance? Ctx %)
    :type-properties
    {:error/message "should be an instance of sci.impl.opts.Ctx"}}))


(defn eval-ctx
  ([]
   (eval-ctx nil nil))

  ([namespaces classes]
   (let [opts (cond-> {}
                (seq namespaces) (merge (->namespaces-map namespaces))
                (seq classes) (assoc :classes (->classes-map classes)))]
     (sci/init opts))))


(defn eval-form
  [ctx form]
  (sci/eval-form ctx form))
