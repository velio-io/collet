(ns collet.actions.s3-operations-test
  (:require [clj-test-containers.core :as tc]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [cognitect.aws.client.api :as aws]
            [collet.actions.file :as file]
            [collet.actions.s3 :as s3]
            [collet.test-containers :as containers]
            [tech.v3.dataset :as ds])
  (:import [java.io ByteArrayInputStream InputStream]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]))


(def creds {:aws-region "us-east-1" :aws-key "test" :aws-secret "test"})


(def location {:aws-creds creds :bucket "source" :key "a.csv"})


(defn missing []
  (throw (ex-info "Missing" {:response {:cognitect.anomalies/category :cognitect.anomalies/not-found}})))


(deftest pagination-filters-and-empty-schema
  (let [calls   (atom [])
        stopped (atom 0)
        object  (fn [key size] {:Key key :Size size :LastModified (java.util.Date/from (Instant/parse "2026-01-01T00:00:00Z")) :ETag "\"opaque\""})]
    (with-redefs [s3/make-client (constantly ::client)
                  aws/stop       (fn [_] (swap! stopped inc))
                  s3/invoke!     (fn [_ op request]
                                   (swap! calls conj [op request])
                                   (is (= :ListObjectsV2 op))
                                   (case (:ContinuationToken request)
                                     nil {:Contents [(object "prefix/a.txt" 1)] :IsTruncated true :NextContinuationToken "next"}
                                     "next" {:Contents [(object "prefix/b.csv" 2) (object "prefix/c.csv" 3)] :IsTruncated false}))]
      (let [rows (vec (ds/rows (s3/list-objects {:aws-creds creds :bucket "source" :prefix "prefix/" :extensions ["csv"] :page-size 1 :limit 1})))]
        (is (= ["prefix/b.csv"] (mapv :key rows)))
        (is (= "\"opaque\"" (:etag (first rows))))
        (is (= 2 (count @calls)))
        (is (every? #(= "prefix/" (get-in % [1 :Prefix])) @calls))
        (is (= 1 @stopped)))
      (let [empty (s3/list-objects {:aws-creds creds :bucket "source" :extensions ["png"]})]
        (is (zero? (ds/row-count empty)))
        (is (= s3/listing-columns (vec (ds/column-names empty)))))
      (is (zero? (ds/row-count (s3/list-objects {:aws-creds creds :bucket "source" :limit 0})))))))


(deftest read-modes-close-body-and-client
  (doseq [mode [:memory :dataset :file :stream]]
    (let [closed  (atom 0)
          stopped (atom 0)
          path    (str (Files/createTempDirectory "collet-s3-read-" (make-array FileAttribute 0)))
          input   (proxy [ByteArrayInputStream] [(.getBytes "a,b\n1,2\n" "UTF-8")]
                    (close [] (swap! closed inc)))]
      (try
        (with-redefs [s3/make-client (constantly ::client)
                      aws/stop       (fn [_] (swap! stopped inc))
                      s3/invoke!     (fn [_ op request]
                                       (is (= :GetObject op))
                                       (is (= {:Bucket "source" :Key "a.csv"} request))
                                       {:Body input})]
          (let [result (s3/read-object (assoc location
                                         :as mode
                                         :destination (str path "/copy.csv")))]
            (case mode
              :memory (is (= "a,b\n1,2\n" (String. ^bytes result "UTF-8")))
              :dataset (is (= [{:a 1 :b 2}] (vec (ds/rows result))))
              :file (is (= "a,b\n1,2\n" (slurp (:path result))))
              :stream (do (is (zero? @stopped))
                          (is (zero? @closed))
                          (.close ^InputStream result)
                          (.close ^InputStream result))))
          (is (= 1 @closed))
          (is (= 1 @stopped)))
        (finally (file/delete-file {:path path :recursive? true}))))))


(deftest bounded-and-failed-reads-release-resources
  (let [closed  (atom false)
        stopped (atom 0)]
    (with-redefs [s3/make-client (constantly ::client)
                  aws/stop       (fn [_] (swap! stopped inc))
                  s3/invoke!     (fn [_ _ _]
                                   {:Body (proxy [ByteArrayInputStream] [(byte-array [1 2 3])]
                                            (close [] (reset! closed true)))})]
      (is (thrown? Exception (s3/read-object (assoc location :max-bytes 1))))
      (is @closed)
      (is (= 1 @stopped)))
    (with-redefs [s3/make-client (constantly ::client)
                  aws/stop       (fn [_] (swap! stopped inc))
                  s3/invoke!     (fn [_ _ _] (missing))]
      (is (thrown? Exception (s3/read-object location)))
      (is (= 2 @stopped)))))


(deftest copy-and-move-preserve-source-on-failure
  (let [calls        (atom [])
        fail-copy?   (atom false)
        fail-delete? (atom false)
        size         (atom 5)]
    (with-redefs [s3/make-client (constantly ::client)
                  aws/stop       (fn [_])
                  s3/invoke!     (fn [_ op request]
                                   (swap! calls conj [op request])
                                   (case op
                                     :HeadObject (if (= "a.csv" (:Key request)) {:ContentLength @size :ETag "\"source\""} (missing))
                                     :CopyObject (if @fail-copy? (throw (ex-info "copy failed" {})) {:CopyObjectResult {:ETag "\"copy\""}})
                                     :DeleteObject (if @fail-delete? (throw (ex-info "delete failed" {})) {})))]
      (is (= {:bucket "source" :key "b.csv" :etag "\"copy\""}
             (s3/copy-object (assoc location :destination-key "b.csv"))))
      (is (= "\"source\"" (get-in (last @calls) [1 :CopySourceIfMatch])))
      (reset! calls [])
      (reset! fail-copy? true)
      (is (thrown? Exception (s3/move-object (assoc location :destination-key "b.csv"))))
      (is (not-any? #(= :DeleteObject (first %)) @calls))
      (reset! fail-copy? false)
      (reset! fail-delete? true)
      (let [failure (try (s3/move-object (assoc location :destination-key "b.csv")) (catch Exception e (ex-data e)))]
        (is (:source-retained failure))
        (is (= "b.csv" (get-in failure [:copied :key]))))
      (reset! calls [])
      (reset! size (inc s3/five-gb-in-bytes))
      (is (thrown-with-msg? Exception #"5 GB" (s3/copy-object (assoc location :destination-key "b.csv"))))
      (is (= [:HeadObject] (mapv first @calls)))
      (is (false? (s3/exists-object? (assoc location :key "missing")))))))


(deftest permission-errors-are-not-missing-objects
  (with-redefs [s3/make-client (constantly ::client)
                aws/stop       (fn [_])
                s3/invoke!     (fn [_ _ _] (throw (ex-info "Denied" {:response {:cognitect.anomalies/category :cognitect.anomalies/forbidden}})))]
    (is (thrown-with-msg? Exception #"Denied" (s3/exists-object? location)))))


(deftest upload-staging-preserves-input-and-cleans-up
  (let [path     (str (Files/createTempDirectory "collet-s3-upload-" (make-array FileAttribute 0)))
        source   (str path "/same-as-key.txt")
        staged   (atom nil)
        original file/write-into-file]
    (try
      (spit source "keep original")
      (with-redefs [s3/make-client       (constantly ::client)
                    aws/stop             (fn [_])
                    file/write-into-file (fn [options] (reset! staged (:file-name options)) (original options))
                    s3/invoke!           (fn [_ _ request]
                                           (is (= source (:Key request)))
                                           (is (= "keep original" (slurp (:Body request))))
                                           {:ETag "opaque"})]
        (s3/upload-file {:aws-creds creds :bucket "source" :file-name source :input source})
        (is (= "keep original" (slurp source)))
        (is (not (file/exists-file? {:path @staged}))))
      (let [fail-writer (fn [options]
                          (reset! staged (:file-name options))
                          (throw (ex-info "serialize failed" {})))]
        (with-redefs [file/write-into-file fail-writer]
          (is (thrown? Exception (s3/upload-file {:aws-creds creds :bucket "source" :file-name "x" :input []})))
          (is (not (file/exists-file? {:path @staged})))))
      (finally (file/delete-file {:path path :recursive? true})))))


(deftest ^:integration localstack-object-toolkit
  (let [container   (containers/localstack)
        credentials (assoc creds :endpoint-override {:protocol :http :hostname "localhost" :port (get-in container [:mapped-ports 4566])})
        client      (s3/make-client :s3 credentials)
        base        {:aws-creds credentials :bucket "toolkit-test"}]
    (try
      (s3/invoke! client :CreateBucket {:Bucket "toolkit-test"})
      (doseq [key ["a.csv" "b.csv" "c.txt"]]
        (s3/upload-file (assoc base
                          :file-name key
                          :input "a,b\n1,2\n"
                          :format :text)))
      (is (= 2
             (ds/row-count (s3/list-objects (assoc base
                                              :extensions ["csv"]
                                              :page-size 1)))))
      (is (= [{:a 1 :b 2}]
             (vec (ds/rows (s3/read-object (assoc base
                                             :key "a.csv"
                                             :as :dataset))))))
      (is (= 8 (:size-bytes (s3/stat-object (assoc base :key "a.csv")))))
      (s3/copy-object (assoc base
                        :key "a.csv"
                        :destination-key "copy.csv"))
      (is (thrown? Exception
                   (s3/copy-object (assoc base
                                     :key "a.csv"
                                     :destination-key "copy.csv"))))
      (s3/move-object (assoc base
                        :key "copy.csv"
                        :destination-key "moved.csv"))
      (is (not (s3/exists-object? (assoc base :key "copy.csv"))))
      (is (s3/exists-object? (assoc base :key "moved.csv")))
      (s3/delete-object (assoc base :key "moved.csv"))
      (is (not (s3/exists-object? (assoc base :key "moved.csv"))))
      (finally (aws/stop client) (tc/stop! container)))))
