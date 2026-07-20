(ns collet.test-fixtures
  (:require
   [clojure.java.io :as io]
   [malli.instrument :as mi]))


(defn- delete-tree!
  [^java.io.File root]
  (when (.exists root)
    (doseq [file (reverse (file-seq root))]
      (io/delete-file file true))))


(defn- with-temp-dir
  [test]
  (let [root (.getCanonicalFile (io/file "tmp"))]
    (delete-tree! root)
    (.mkdirs root)
    (try
      (test)
      (finally
        (delete-tree! root)))))


(defn instrument! [ns]
  (fn [test]
    (with-temp-dir
      (fn []
        (mi/collect! {:ns ns})
        (mi/instrument!)
        (test)))))
