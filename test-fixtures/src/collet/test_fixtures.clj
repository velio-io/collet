(ns collet.test-fixtures
  (:require
   [clojure.java.io :as io]
   [malli.instrument :as mi])
  (:import
    [java.nio.file Files]
    [java.nio.file.attribute FileAttribute]))


(defn resource-path [resource-name]
  (some-> resource-name
          io/resource
          io/file
          str))


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
  "Runs a compiled pipeline in an isolated Context and returns the terminal run
  metadata merged with task results captured before Artifact cleanup."
  [pipeline config]
  (let [dir       (-> (Files/createTempDirectory
                       "collet-test-"
                       (make-array FileAttribute 0))
                      .toFile)
        context   (requiring-resolve 'collet.core/context)
        start     (requiring-resolve 'collet.core/start)
        close     (requiring-resolve 'collet.core/close)
        results   (atom {})
        run-ready (promise)
        ctx       (context
                   {:data-dir         (.getAbsolutePath dir)
                    :on-task-complete (fn [task]
                                        (let [run    @run-ready
                                              name   (:task/name task)
                                              result (get run name)]
                                          (swap! results assoc
                                            name
                                            (if (seq? result)
                                              (doall result)
                                              result))))})]
    (try
      (let [run (start ctx pipeline config)]
        (deliver run-ready run)
        (merge @run @results))
      (finally
       (close ctx)
       (delete-tree! dir)))))
