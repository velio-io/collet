(ns collet.actions.s3-test
  (:require
   [charred.api :as charred]
   [clj-test-containers.core :as tc]
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [cognitect.aws.client.api :as aws]
   [cognitect.aws.http :as http]
   [collet.actions.s3 :as sut]
   [collet.core :as collet]
   [collet.test-containers :as containers]
   [collet.test-fixtures :as tf]))


(use-fixtures :once (tf/instrument! 'collet.actions.s3))


(deftest sink-returns-the-final-object-etag
  (doseq [[multipart? etag] [[false "\"opaque-single\""] [true "\"opaque-multipart-2\""] [false nil]]]
    (let [requests (atom [])
          bytes    (byte-array [0 1 2 -1 -128])
          creds    {:aws-region "auto" :aws-key "test" :aws-secret "test"}
          invoke   (fn [_ operation request]
                     (swap! requests conj operation)
                     (case operation
                       :CreateMultipartUpload {:UploadId "upload"}
                       :UploadPart (do (is (= (seq bytes) (seq (.readAllBytes (:Body request)))))
                                       {:ETag "part-etag"})
                       :CompleteMultipartUpload {:ETag etag}
                       :PutObject (do (is (= (seq bytes) (seq (.readAllBytes (:Body request)))))
                                      (cond-> {} etag (assoc :ETag etag)))))
          expected (cond-> {:bucket "images" :key "photo.png"} etag (assoc :etag etag))]
      (with-redefs [sut/make-client      (fn [_ credentials] (is (= creds credentials)) :fixture-client)
                    aws/stop             (fn [_])
                    sut/invoke!          invoke
                    sut/five-gb-in-bytes (if multipart? 1 Long/MAX_VALUE)]
        (is (= expected
               (sut/upload-file {:aws-creds creds
                                 :bucket    "images"
                                 :file-name "photo.png"
                                 :input     (java.io.ByteArrayInputStream. bytes)})))
        (is (= (if multipart? [:CreateMultipartUpload :UploadPart :CompleteMultipartUpload] [:PutObject])
               @requests))))))


(deftest custom-endpoints-support-r2-auto-without-changing-aws-regions
  (doseq [[region override? expected] [["auto" true "us-east-1"]
                                       ["eu-west-1" true "eu-west-1"]
                                       ["us-east-1" false "us-east-1"]]]
    (let [requests      (atom [])
          transport     (reify
                         http/HttpClient
                         (-submit [_ request channel]
                           (swap! requests conj request)
                           (async/put! channel {:status 200 :headers {"etag" "uploaded"} :body nil})
                           channel)
                         (-stop [_] nil))
          create-client aws/client
          creds         (cond-> {:aws-region region :aws-key "test" :aws-secret "test"}
                          override? (assoc :endpoint-override {:protocol :https :hostname "account.r2.cloudflarestorage.com" :port 443}))]
      (with-redefs [aws/client (fn [options]
                                 (create-client (assoc options
                                                  :http-client transport
                                                  :retriable? (constantly false))))]
        (let [client (sut/make-client :s3 creds)]
          (try
            (is (= expected (:region client)))
            (is (= {:ETag "uploaded"} (sut/invoke! client :PutObject {:Bucket "images" :Key "photo.png" :Body (java.io.ByteArrayInputStream. (byte-array [1 2 3]))})))
            (is (= 1 (count @requests)))
            (when override?
              (is (= "account.r2.cloudflarestorage.com" (:server-name (first @requests))))
              (is (= :https (:scheme (first @requests))))
              (is (= 443 (:server-port (first @requests)))))
            (is (some #(and (= "authorization" (str/lower-case (name (key %))))
                            (str/includes? (val %) (str "/" expected "/s3/aws4_request")))
                      (:headers (first @requests))))
            (is (= region (:aws-region creds)))
            (finally (aws/stop client))))))))


(deftest ^:integration s3-upload-test
  (let [container      (containers/localstack)
        container-port (get-in container [:mapped-ports 4566])
        aws-creds      {:aws-region        "eu-west-1"
                        :aws-key           "test"
                        :aws-secret        "test"
                        :endpoint-override {:protocol :http
                                            :hostname "localhost"
                                            :port     container-port}}
        s3-client      (sut/make-client :s3 aws-creds)]
    (sut/invoke! s3-client
     :CreateBucket
     {:Bucket "test-bucket"
      :CreateBucketConfiguration {:LocationConstraint "eu-west-1"}})

    (is (string? (:etag (sut/upload-file
                         {:aws-creds   aws-creds
                          :bucket      "test-bucket"
                          :format      :csv
                          :file-name   "test/test-file.csv"
                          :input       [{:a 1 :b 2} {:a 3 :b 4} {:a 5 :b 6}]
                          :csv-header? true}))))

    (is (string? (:etag (sut/upload-file
                         {:aws-creds aws-creds
                          :bucket    "test-bucket"
                          :file-name "test/test-image.png"
                          :input     (tf/resource-path "Clojure_logo.png")}))))

    (let [file  (sut/invoke! s3-client
                 :GetObject
                 {:Bucket "test-bucket"
                  :Key    "test/test-file.csv"})
          image (sut/invoke! s3-client
                 :GetObject
                 {:Bucket "test-bucket"
                  :Key    "test/test-image.png"})]
      (is (= [["a" "b"] ["1" "2"] ["3" "4"] ["5" "6"]]
             (charred/read-csv (io/reader (:Body file)))))
      (with-open [original (io/input-stream (tf/resource-path "Clojure_logo.png"))
                  uploaded (:Body image)]
        (is (= (seq (.readAllBytes original)) (seq (.readAllBytes uploaded))))))

    (tc/stop! container)))


(defn generate-large-file
  [file-path size-in-mb]
  (let [chunk-size  (* 1024 1024) ;; 1MB in bytes
        repetitions (/ size-in-mb 1)] ;; Number of 1MB chunks needed
    (io/make-parents file-path)
    (with-open [writer (io/output-stream file-path)]
      (dotimes [_ repetitions]
        (.write writer (byte-array chunk-size))))))


(deftest ^:integration multipart-upload-test
  (let [container      (containers/localstack)
        container-port (get-in container [:mapped-ports 4566])
        aws-creds      {:aws-region        "eu-west-1"
                        :aws-key           "test"
                        :aws-secret        "test"
                        :endpoint-override {:protocol :http
                                            :hostname "localhost"
                                            :port     container-port}}
        s3-client      (sut/make-client :s3 aws-creds)]
    (sut/invoke! s3-client
     :CreateBucket
     {:Bucket "test-multipart-bucket"
      :CreateBucketConfiguration {:LocationConstraint "eu-west-1"}})

    (generate-large-file "./tmp/large-test-file.bin" 1024)

    (with-open [is (io/input-stream "./tmp/large-test-file.bin")]
      (sut/multipart-upload
       s3-client
       "test-multipart-bucket"
       "large-test-file.bin"
       is))

    (let [file            (sut/invoke! s3-client
                           :GetObject
                           {:Bucket "test-multipart-bucket"
                            :Key    "large-test-file.bin"})
          one-gb-in-bytes 1073741824]
      (is (= one-gb-in-bytes (:ContentLength file))))

    (io/delete-file "./tmp/large-test-file.bin")
    (tc/stop! container)))


(deftest ^:integration pipeline-s3-action
  (let [container      (containers/localstack)
        container-port (get-in container [:mapped-ports 4566])
        s3-client      (sut/make-client :s3
                                        {:aws-region        "eu-west-1"
                                         :aws-key           "test"
                                         :aws-secret        "test"
                                         :endpoint-override {:protocol :http
                                                             :hostname "localhost"
                                                             :port     container-port}})
        pipeline       (collet/compile-pipeline
                        {:name  :s3-sink-test
                         :tasks [{:name    :s3-test-task
                                  :actions [{:name      :s3-action
                                             :type      :collet.actions.s3/sink
                                             :selectors {'creds [:config :aws-creds]}
                                             :params    {:aws-creds   'creds
                                                         :input       [{:a 1 :b 2} {:a 3 :b 4} {:a 5 :b 6}]
                                                         :format      :csv
                                                         :bucket      "pipe-test-bucket"
                                                         :file-name   "pipe-test-file.csv"
                                                         :csv-header? true}}]}]})]
    (sut/invoke! s3-client
     :CreateBucket
     {:Bucket "pipe-test-bucket"
      :CreateBucketConfiguration {:LocationConstraint "eu-west-1"}})

    (tf/run-pipeline! pipeline
                      {:aws-creds {:aws-region        "eu-west-1"
                                   :aws-key           "test"
                                   :aws-secret        "test"
                                   :endpoint-override {:protocol :http
                                                       :hostname "localhost"
                                                       :port     container-port}}})

    (let [file (sut/invoke! s3-client
                :GetObject
                {:Bucket "pipe-test-bucket"
                 :Key    "pipe-test-file.csv"})]
      (is (= [["a" "b"] ["1" "2"] ["3" "4"] ["5" "6"]]
             (charred/read-csv (io/reader (:Body file))))))

    (tc/stop! container)))
