(ns pod.collet.core-test
  (:require
   [bencode.core :as bencode]
   [clojure.test :refer [deftest is]]
   [clojure.walk :as walk]
   [collet.core :as collet]
   [collet.main :as main]
   [pod.collet.core :as sut])
  (:import
    (java.io PushbackInputStream)
    [java.nio.file FileVisitOption Files LinkOption Path]
    [java.nio.file.attribute FileAttribute]
    (java.util.concurrent TimeUnit)))


(def jvm-options
  ["--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED"
   "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED"
   "--add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED"
   "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED"
   "--add-opens=jdk.compiler/com.sun.tools.javac=ALL-UNNAMED"
   "--add-opens=java.base/java.lang=ALL-UNNAMED"
   "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
   "--add-opens=java.base/java.io=ALL-UNNAMED"
   "--add-opens=java.base/java.util=ALL-UNNAMED"
   "--add-opens=java.base/java.nio=ALL-UNNAMED"
   "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
   "--enable-native-access=ALL-UNNAMED"])


(defn- bytes->strings [value]
  (walk/postwalk
   (fn [item]
     (if (instance? (Class/forName "[B") item)
       (String. ^bytes item)
       item))
   value))


(defn- delete-tree!
  [^Path path]
  (when (Files/exists path (make-array LinkOption 0))
    (with-open [paths (Files/walk path (make-array FileVisitOption 0))]
      (doseq [entry (sort-by str
                             #(compare %2 %1)
                             (iterator-seq (.iterator paths)))]
        (Files/deleteIfExists ^Path entry)))))


(deftest runtime-context-uses-the-configured-data-directory
  (let [data-dir (Files/createTempDirectory
                  "collet-pod-data-"
                  (make-array FileAttribute 0))]
    (try
      (binding [main/*env* {"COLLET_DATA_DIR" (str data-dir)}]
        (let [ctx (sut/create-runtime-context)]
          (try
            (is (= (str (.resolve data-dir "db")) (get-in ctx [:store :dir])))
            (is (= (str (.resolve data-dir "artifacts"))
                   (str (:artifact-dir ctx))))
            (finally
             (collet/close ctx)))))
      (finally
       (delete-tree! data-dir)))))


(deftest ^:integration pod-artifact-startup-test
  (let [command  (into ["java"]
                       (concat jvm-options ["-jar" "target/collet.pod.jar"]))
        data-dir (str (java.nio.file.Files/createTempDirectory
                       "collet-pod-test-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        builder  (ProcessBuilder. ^java.util.List command)
        _ (.put (.environment builder) "COLLET_DATA_DIR" data-dir)
        process  (.start builder)]
    (try
      (let [stdin  (.getOutputStream process)
            stdout (PushbackInputStream. (.getInputStream process))]
        (bencode/write-bencode stdin {"op" "describe"})
        (.flush stdin)
        (let [response (bytes->strings (bencode/read-bencode stdout))]
          (is (= "edn" (get response "format")))
          (is (some #(= "pod.collet.core" (get % "name"))
                    (get response "namespaces"))))

        (bencode/write-bencode stdin {"op" "shutdown"})
        (.flush stdin)
        (is (.waitFor process 10 TimeUnit/SECONDS))
        (is (zero? (.exitValue process))))
      (finally
       (when (.isAlive process)
         (.destroyForcibly process))))))
