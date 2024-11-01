(ns collet.actions.file
  (:require
   [clojure.java.io :as io]
   [collet.utils :as utils]
   [diehard.core :as dh]
   [tech.v3.dataset :as ds])
  (:import
   [java.io ByteArrayInputStream File InputStream]
   [java.nio.file Files]))


(def file-params-spec
  [:map
   [:input
    [:sequential [:or map? [:sequential :any]]]]
   [:cat? {:optional true :default false}
    :boolean]
   [:format
    [:enum :json :csv]]
   [:file-name :string]
   [:csv-header? {:optional true :default false}
    :boolean]])


(defn write-into-file
  "Writes the input to a file.
   For JSON format, the resulting file will contain one JSON object per line.
   The input data should be a collection of maps or a collection of sequential items.
   Options:
   :input       - the data to write
   :format      - the format of the file (:json or :csv)
   :file-name    - the name of the file
   :override?   - if true, the file will be overwritten if it exists
   :csv-header? - if true, the CSV file will have a header row"
  {:malli/schema [:=> [:cat file-params-spec]
                  [:map
                   [:file-name :string]
                   [:path :string]]]}
  [{:keys [input format file-name csv-header? cat?]
    :or   {csv-header? false cat? false}}]
  (let [dataset (utils/make-dataset input {:cat? cat?})
        file    (io/file file-name)]
    (when-not (.exists file)
      (io/make-parents file))

    (case format
      :json (ds/write! dataset file-name)
      :csv (ds/write! dataset file-name {:headers? csv-header?}))

    {:file-name file-name
     :path      (.getAbsolutePath file)}))


(def write-file-action
  {:action write-into-file})


;; S3 upload action


(def five-gb-in-bytes
  (* 5 1024 1024 1024))


(defn initiate-multipart-upload
  "Initiates the multipart upload process."
  [s3-client bucket key]
  (let [response (utils/invoke! s3-client :CreateMultipartUpload
                                {:Bucket bucket
                                 :Key    key})]
    (:UploadId response)))


(defn upload-part
  "Uploads a part of a file to S3."
  [s3-client bucket key upload-id part-number part-content]
  (let [response (utils/invoke! s3-client :UploadPart
                                {:Bucket     bucket
                                 :Key        key
                                 :UploadId   upload-id
                                 :PartNumber part-number
                                 :Body       part-content})]
    {:ETag       (:ETag response)
     :PartNumber part-number}))


(defn complete-multipart-upload
  "Completes the multipart upload process."
  [s3-client bucket key upload-id parts]
  (utils/invoke! s3-client :CompleteMultipartUpload
                 {:Bucket          bucket
                  :Key             key
                  :UploadId        upload-id
                  :MultipartUpload {:Parts parts}}))


(defn multipart-upload
  "Uploads a file larger than 5GB to S3 using multipart upload.
   The file is split into parts of 50MB each.
   Options:
   :s3-client - the S3 client
   :bucket - the S3 bucket name
   :key    - the S3 key name
   :is     - the input stream of the file"
  [s3-client bucket key ^InputStream is]
  (let [upload-id (initiate-multipart-upload s3-client bucket key)]
    (try
      (let [part-size (* 50 1024 1024) ;; 50MB part size
            parts     (loop [part-number 1
                             parts       []]
                        (let [buffer     (byte-array part-size)
                              bytes-read (.read is buffer)]
                          (if (pos? bytes-read)
                            (recur (inc part-number)
                                   (->> (new ByteArrayInputStream buffer 0 bytes-read)
                                        (upload-part s3-client bucket key upload-id part-number)
                                        (conj parts)))
                            parts)))]
        (complete-multipart-upload s3-client bucket key upload-id parts))
      (catch Exception e
        (utils/invoke! s3-client :AbortMultipartUpload
                       {:Bucket   bucket
                        :Key      key
                        :UploadId upload-id})
        (throw e)))))


(def s3-params-spec
  [:map
   [:aws-creds
    [:map
     [:aws-region :string]
     [:aws-key :string]
     [:aws-secret :string]
     [:endpoint-override {:optional true}
      [:map
       [:protocol [:enum :http :https]]
       [:hostname :string]
       [:port :int]]]]]
   [:bucket :string]
   [:format [:enum :json :csv]]
   [:file-name :string]
   [:input
    [:sequential [:or map? [:sequential :any]]]]
   [:cat? {:optional true :default false}
    :boolean]
   [:csv-header? {:optional true :default false}
    :boolean]])


(defn upload-file
  "Uploads a file to S3.
   Options:
   :aws-creds   - the AWS credentials (region, key, secret)
   :bucket      - the S3 bucket name
   :format      - the format of the file (:json or :csv)
   :file-name   - the name of the file
   :input       - the data to write
   :csv-header? - if true, the CSV file will have a header row"
  {:malli/schema [:=> [:cat s3-params-spec]
                  [:map
                   [:bucket :string]
                   [:key :string]]]}
  [{:keys [aws-creds bucket format file-name input cat? csv-header?]
    :or   {cat? false}}]
  (let [dataset        (utils/make-dataset input {:cat? cat?})
        ext            (case format
                         :csv ".csv"
                         :json ".json")
        file           (File/createTempFile file-name ext)
        temp-file-path (.getAbsolutePath file)
        s3-client      (utils/make-client :s3 aws-creds)]
    (.deleteOnExit file)

    (case format
      :json (ds/write! dataset temp-file-path)
      :csv (ds/write! dataset temp-file-path {:headers? csv-header?}))

    (dh/with-retry
      {:retry-on        Exception
       :delay-ms        1000
       :max-duration-ms (* 1000 60 2)}
      (with-open [is (io/input-stream file)]
        ;; if the file is larger than 5GB, use multipart upload
        (if (> (Files/size (.toPath file)) five-gb-in-bytes)
          (multipart-upload s3-client bucket file-name is)
          ;; regular upload
          (utils/invoke! s3-client :PutObject
                         {:Bucket bucket
                          :Key    file-name
                          :Body   is}))))
    ;; cleanup
    (io/delete-file file)
    ;; return the S3 coordinates
    {:bucket bucket
     :key    file-name}))


(def upload-file-action
  {:action upload-file})