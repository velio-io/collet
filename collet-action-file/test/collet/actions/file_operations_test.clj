(ns collet.actions.file-operations-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [collet.actions.file :as file]
            [tech.v3.dataset :as ds])
  (:import [java.io ByteArrayInputStream InputStream]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]
           [java.util.zip ZipEntry ZipOutputStream]))


(defmacro with-directory
  [[root] & body]
  `(let [~root (str (Files/createTempDirectory "collet-file-test-" (make-array FileAttribute 0)))]
     (try ~@body (finally (file/delete-file {:path ~root :recursive? true})))))


(deftest content-round-trips-and-read-modes
  (with-directory [root]
                  (doseq [[format input] [[:text "literal text with ü and a nonexistent/path"]
                                          [:bytes (byte-array [0 -1 1 2])]
                                          [:edn {:nested [1 "hello"]}]
                                          [:json [{:a 1} {:a 2}]]]]
                    (let [path (str root "/" (name format))]
                      (file/write-into-file {:file-name path :input input :format format})
                      (let [actual (file/read-file {:path path :format format})]
                        (is (= (if (bytes? input) (seq input) input)
                               (if (bytes? actual) (seq actual) actual))))
                      (when (#{:json :edn} format)
                        (when (= :json format)
                          (is (= [{:a 1} {:a 2}] (vec (ds/rows (file/read-file {:path path :as :dataset :format format})))))))))
                  (let [path (str root "/rows.csv")
                        rows [{:a 1 :b "ü"} {:a 2 :b "q"}]]
                    (file/write-into-file {:file-name path :input rows :format :csv :csv-header? true})
                    (is (= rows (vec (ds/rows (file/read-file {:path path :as :dataset})))))
                    (with-open [input ^InputStream (file/read-file {:path path :as :stream})]
                      (is (pos? (alength (.readAllBytes input)))))
                    (let [destination (str root "/copied.csv")]
                      (is (= (file/file-receipt destination)
                             (file/read-file {:path path :as :file :destination destination})))
                      (is (= (slurp path) (slurp destination)))
                      (is (thrown? Exception (file/read-file {:path path :as :file :destination destination})))))))


(deftest bounded-reads-close-input-and-preserve-existing-output
  (with-directory [root]
                  (doseq [as [:memory :dataset :file]]
                    (let [closed?     (atom false)
                          input       (proxy [ByteArrayInputStream] [(.getBytes "123456" "UTF-8")]
                                        (close [] (reset! closed? true)))
                          destination (str root "/output")]
                      (spit destination "keep")
                      (is (thrown-with-msg? Exception
                                            #"max-bytes"
                                            (file/read-content input {:as as :max-bytes 2 :destination destination :overwrite? true})))
                      (is @closed?)
                      (is (= "keep" (slurp destination)))))
                  (let [closed? (atom false)
                        input   (proxy [ByteArrayInputStream] [(byte-array [1])]
                                  (close [] (reset! closed? true)))]
                    (is (identical? input (file/read-content input {:as :stream})))
                    (is (false? @closed?))
                    (.close input)
                    (is @closed?))))


(deftest csv-encoding-and-in-place-file-input
  (with-directory [root]
                  (let [path (str root "/utf16.csv")]
                    (file/write-into-file {:file-name path :input "name\nü\n" :format :text :encoding "UTF-16"})
                    (is (= [{:name "ü"}] (vec (ds/rows (file/read-file {:path path :as :dataset :encoding "UTF-16"})))))
                    (let [before (seq (file/read-file {:path path}))]
                      (file/write-into-file {:file-name path :input path})
                      (is (= before (seq (file/read-file {:path path}))))))
                  (let [path (str root "/records.edn")]
                    (file/write-into-file {:file-name path :input (ds/->dataset [{:n 1}]) :format :edn})
                    (is (= [{:n 1}] (vec (ds/rows (file/read-file {:path path :as :dataset :format :edn}))))))
                  (spit (str root "/invalid.zip") "not a ZIP")
                  (is (thrown? Exception (file/unzip-file {:path (str root "/invalid.zip") :destination (str root "/invalid-output")})))))


(deftest listings-and-file-management
  (with-directory [root]
                  (file/mkdir {:path (str root "/sub")})
                  (doseq [[path text] [["a.txt" "hello"] ["b.json" "{}"] ["sub/c.TXT" "longer"]]]
                    (file/write-into-file {:file-name (str root "/" path) :format :text :input text}))
                  (is (= 2 (ds/row-count (file/list-files {:path root}))))
                  (is (= ["a.txt" "c.TXT"] (mapv :file-name (ds/rows (file/list-files {:path root :recursive? true :extensions ["txt"]})))))
                  (is (= ["c.TXT"] (mapv :file-name (ds/rows (file/list-files {:path root :recursive? true :prefix "sub/" :min-size 6 :max-size 6})))))
                  (is (= ["a.txt"] (mapv :file-name (ds/rows (file/list-files {:path root :glob "*.txt" :limit 1})))))
                  (is (zero? (ds/row-count (file/list-files {:path root :modified-after "2099-01-01T00:00:00Z"}))))
                  (let [empty-listing (file/list-files {:path root :limit 0})]
                    (is (= file/listing-columns (vec (ds/column-names empty-listing))))
                    (is (= [:string :string :string :int64 :string] (mapv #(-> % meta :datatype) (vals empty-listing)))))
                  (let [source (str root "/a.txt")
                        target (str root "/copy.txt")
                        moved  (str root "/moved.txt")]
                    (is (= :file (:type (file/stat-file {:path source}))))
                    (is (= 5 (:size-bytes (file/stat-file {:path source}))))
                    (is (= "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
                           (:checksum (file/checksum {:path source}))))
                    (file/copy-file {:path source :destination target})
                    (is (thrown? Exception (file/copy-file {:path source :destination target})))
                    (file/move-file {:path target :destination moved})
                    (is (not (file/exists-file? {:path target})))
                    (is (file/delete-file {:path moved}))
                    (is (false? (file/delete-file {:path moved}))))
                  (is (thrown? Exception (file/copy-file {:path (str root "/sub") :destination (str root "/sub-copy")})))
                  (file/copy-file {:path (str root "/sub") :destination (str root "/sub-copy") :recursive? true})
                  (is (file/exists-file? {:path (str root "/sub-copy/c.TXT")}))
                  (is (thrown? Exception (file/delete-file {:path (str root "/sub-copy")})))
                  (is (file/delete-file {:path (str root "/sub-copy") :recursive? true}))
                  (is (file/exists-file? {:path (:path (file/temp-file {:folder root}))}))
                  (is (= :directory (:type (file/stat-file {:path (:path (file/temp-dir {:folder root}))}))))))


(deftest listings-never-follow-directory-links
  (with-directory [root]
                  (with-directory [outside]
                                  (spit (str outside "/secret.txt") "outside")
                                  (Files/createSymbolicLink (file/file-path (str root "/link")) (file/file-path outside) (make-array FileAttribute 0))
                                  (is (zero? (ds/row-count (file/list-files {:path root :recursive? true}))))
                                  (is (= :symlink (:type (file/stat-file {:path (str root "/link")})))))))


(defn make-zip [path entries]
  (with-open [output (ZipOutputStream. (io/output-stream path))]
    (doseq [[name text] entries]
      (.putNextEntry output (ZipEntry. name))
      (.write output (.getBytes ^String text "UTF-8"))
      (.closeEntry output))))


(deftest archives-round-trip-and-enforce-boundaries
  (with-directory [root]
                  (let [source      (str root "/source")
                        archive     (str root "/files.zip")
                        destination (str root "/unpacked")]
                    (file/mkdir {:path (str source "/nested")})
                    (spit (str source "/nested/hello.txt") "hello")
                    (file/zip-file {:path source :destination archive})
                    (file/unzip-file {:path archive :destination destination})
                    (is (= "hello" (slurp (str destination "/nested/hello.txt"))))
                    (is (thrown? Exception (file/unzip-file {:path archive :destination destination})))
                    (is (thrown-with-msg? Exception
                                          #"max-bytes"
                                          (file/unzip-file {:path archive :destination (str root "/limited") :max-bytes 2})))
                    (is (thrown-with-msg? Exception
                                          #"max-entries"
                                          (file/unzip-file {:path archive :destination (str root "/limited-entries") :max-entries 1})))
                    (file/gzip-file {:path (str source "/nested/hello.txt") :destination (str root "/hello.gz")})
                    (file/gunzip-file {:path (str root "/hello.gz") :destination (str root "/hello.txt")})
                    (is (= "hello" (slurp (str root "/hello.txt"))))
                    (is (thrown? Exception (file/gunzip-file {:path (str root "/hello.gz") :destination (str root "/too-large") :max-bytes 1}))))
                  (doseq [entry ["../escaped" "/absolute" "nested/../../escaped" "C:\\escaped"]]
                    (make-zip (str root "/bad.zip") [[entry "bad"]])
                    (is (thrown-with-msg? Exception
                                          #"Unsafe archive"
                                          (file/unzip-file {:path (str root "/bad.zip") :destination (str root "/extract")}))))
                  (with-directory [outside]
                                  (file/mkdir {:path (str root "/links")})
                                  (Files/createSymbolicLink (file/file-path (str root "/links/nested")) (file/file-path outside) (make-array FileAttribute 0))
                                  (make-zip (str root "/link.zip") [["nested/escaped" "bad"]])
                                  (is (thrown-with-msg? Exception
                                                        #"symbolic link"
                                                        (file/unzip-file {:path (str root "/link.zip") :destination (str root "/links")})))
                                  (is (not (file/exists-file? {:path (str outside "/escaped")}))))))


(deftest inspect-image-bounds-decode-and-preserves-original-bytes
  (doseq [format ["png" "jpeg"]]
    (let [buffer  (java.io.ByteArrayOutputStream.)
          _ (javax.imageio.ImageIO/write (java.awt.image.BufferedImage. 5 3 java.awt.image.BufferedImage/TYPE_INT_RGB)
                                         format
                                         buffer)
          bytes   (.toByteArray buffer)
          closed? (atom false)
          input   (proxy [ByteArrayInputStream] [bytes] (close [] (reset! closed? true)))
          image   (file/inspect-image {:input input :base64? true})]
      (is @closed?)
      (is (= [5 3 format] [(:width image) (:height image) (name (:format image))]))
      (is (java.util.Arrays/equals bytes (file/decode-base64 {:input (:base64 image)})))
      (is (thrown-with-msg? Exception #"max-pixels" (file/inspect-image {:input bytes :max-pixels 14})))
      (is (thrown-with-msg? Exception #"max-bytes" (file/inspect-image {:input bytes :max-bytes 2})))))
  (is (thrown? Exception (file/inspect-image {:input (byte-array [1 2 3])}))))
