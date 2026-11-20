(ns collet.stream
  (:require [tech.v3.dataset :as ds]
            [tech.v3.resource :as resource]))


(def byte-target (* 8 1024 1024))


(def row-limit 4096)


(def ^:dynamic *resources* nil)


(defn owned
  "Create a cleanup function and a separate lifetime anchor. The cleanup never captures its owner."
  [dispose]
  (let [closed (atom false)
        scope  *resources*
        id     (Object.)
        close  (fn []
                 (when (compare-and-set! closed false true)
                   (when scope (swap! scope dissoc id))
                   (dispose)))]
    (when scope (swap! scope assoc id close))
    [(resource/track (Object.) {:track-type :gc :dispose-fn close}) close]))


(defmacro with-resources
  [& body]
  `(binding [*resources* (atom {})]
     (try ~@body (finally (doseq [close# (reverse (vals @*resources*))] (close#))))))


(defn view
  "A batch handle. Each traversal asks its source for a cursor, without caching a head."
  [open metadata]
  (with-meta
    (reify
     clojure.lang.Sequential
     clojure.lang.Seqable
     (seq [_] (seq (open)))

     clojure.lang.Counted
     (count [_] (reduce (fn [n _] (inc n)) 0 (open)))

     Iterable
     (iterator [_] (clojure.lang.SeqIterator. (lazy-seq (open)))))
    (assoc metadata :ds-seq true)))


(defn rows
  "Flatten datasets without prefetching other batches."
  [batches]
  (letfn [(step [tail items]
            (lazy-seq
             (loop [tail  tail
                    items items]
               (if-let [s (seq items)]
                 (cons (first s) (step tail (rest s)))
                 (when-let [b (seq tail)]
                   (recur (rest b) (ds/rows (first b))))))))]
    (step batches nil)))


(defn estimated-bytes [v]
  (cond
    (string? v) (loop [i 0
                       n 8]
                  (if (< i (.length ^String v))
                    (let [c (int (.charAt ^String v i))]
                      (recur (inc i)
                             (+ n
                                (cond (< c 128) 1
                                      (< c 2048) 2
                                      :else 3))))
                    n))
    (bytes? v) (+ 8 (alength ^bytes v))
    (map? v) (reduce-kv (fn [n _ x] (+ n 8 (estimated-bytes x))) 8 v)
    (coll? v) (reduce (fn [n x] (+ n (estimated-bytes x))) 8 v)
    :else 16))


(defn chunks
  "Pull bounded vectors from one cursor. A single wide row gets its own batch."
  [^java.util.Iterator cursor max-rows]
  (letfn [(step [pending]
            (lazy-seq
             (loop [items   (transient [])
                    n       0
                    bytes   0
                    pending pending]
               (if (or (= n max-rows) (and (nil? pending) (not (.hasNext cursor))))
                 (when (pos? n) (cons (persistent! items) (step pending)))
                 (let [[row width] (or pending (let [row (.next cursor)] [row (estimated-bytes row)]))]
                   (if (and (pos? n) (> (+ bytes width) byte-target))
                     (cons (persistent! items) (step [row width]))
                     (recur (conj! items row) (inc n) (+ bytes width) nil)))))))]
    (step nil)))


(defn managed-view
  "Reopen a file for each traversal. Closing the handle closes all outstanding readers."
  [open metadata]
  (let [readers (atom {})
        closed? (atom false)
        close   (fn []
                  (locking readers
                    (reset! closed? true)
                    (doseq [finish (vals @readers)] (finish))))]
    (when *resources* (swap! *resources* assoc (Object.) close))
    (view (fn []
            (locking readers
              (when @closed? (throw (ex-info "Dataset handle is closed." {})))
              (binding [*resources* readers] (open))))
          (assoc metadata :collet/close close))))
