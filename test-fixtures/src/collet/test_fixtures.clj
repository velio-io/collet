(ns collet.test-fixtures
  (:require
   [clojure.java.io :as io]
   [malli.instrument :as mi])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

(defn resource-path [resource-name]
  (some-> resource-name io/resource io/file str))


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


(defn run-pipeline!
  "Runs a compiled pipeline against an isolated Datalevin Store and returns its
  completed Run handle."
  [pipeline config]
  (let [dir       (-> (Files/createTempDirectory
                       "collet-test-"
                       (make-array FileAttribute 0))
                      .toFile)
        store-fn  (requiring-resolve 'collet.store.datalevin/store)
        context   (requiring-resolve 'collet.core/context)
        start     (requiring-resolve 'collet.core/start)
        close     (requiring-resolve 'collet.core/close)
        ctx       (context {:store (store-fn {:dir (.getAbsolutePath dir)})})]
    (try
      (let [run (start ctx pipeline config)]
        @run
        run)
      (finally
        (close ctx)
        (delete-tree! dir)))))
