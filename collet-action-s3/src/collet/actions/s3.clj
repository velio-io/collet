(ns collet.actions.s3
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [cognitect.aws.client.api :as aws]
   [cognitect.aws.credentials :as credentials]
   [collet.action :as action]
   [collet.actions.file :as actions.file]
   [collet.utils :as utils]
   [diehard.core :as dh])
  (:import
   [java.io ByteArrayInputStream FilterInputStream InputStream]
   [java.net URLEncoder]
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))


(defn make-client
  "Creates an AWS client for the S3 service."
  [api {:keys [aws-region aws-key aws-secret endpoint-override]}]
  (aws/client
   (utils/assoc-some {:api api}
    ;; R2 accepts this alias; the SDK needs a known AWS region even with an endpoint override.
    :region (if (and endpoint-override (= "auto" aws-region)) "us-east-1" aws-region)
    :credentials-provider (when (and (some? aws-key) (some? aws-secret))
                            (credentials/basic-credentials-provider
                             {:access-key-id     aws-key
                              :secret-access-key aws-secret}))
    :endpoint-override endpoint-override)))


(defn invoke!
  "Calls AWS invoke and captures the error message string and full response.
   - `client` (required) aws service client
   - `operation` (required) a keyword such as :GetObject
   - `params` (required) aws api request options"
  [client operation params]
  (let [params*  {:op operation :request params}
        response (aws/invoke client params*)
        error    (or (get-in response [:ErrorResponse :Error :Message])
                     (get-in response [:Error :Message])
                     (get-in response [:cognitect.anomalies/message]))]
    (if (some? (:cognitect.anomalies/category response))
      (throw (ex-info (str "AWS error response: " error)
                      (merge params* {:response response})))
      response)))


(def five-gb-in-bytes
  (* 5 1024 1024 1024))


(defn initiate-multipart-upload
  "Initiates the multipart upload process."
  [s3-client bucket key]
  (let [response (invoke! s3-client
                  :CreateMultipartUpload
                  {:Bucket bucket
                   :Key    key})]
    (:UploadId response)))


(defn upload-part
  "Uploads a part of a file to S3."
  [s3-client bucket key upload-id part-number part-content]
  (let [response (invoke! s3-client
                  :UploadPart
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
  (invoke! s3-client
   :CompleteMultipartUpload
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
                              bytes-read (.readNBytes is buffer 0 part-size)]
                          (if (pos? bytes-read)
                            (recur (inc part-number)
                                   (->> (new ByteArrayInputStream buffer 0 bytes-read)
                                        (upload-part s3-client bucket key upload-id part-number)
                                        (conj parts)))
                            parts)))]
        (complete-multipart-upload s3-client bucket key upload-id parts))
      (catch Exception e
        (invoke! s3-client
         :AbortMultipartUpload
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
   [:file-name :string]
   [:input :any]
   [:format {:optional true} :keyword]
   [:cat? {:optional true :default false} :boolean]
   [:csv-header? {:optional true :default false} :boolean]])


(defn upload-file
  "Uploads a file to S3.
   Options:
   :aws-creds   - the AWS credentials (region, key, secret)
   :bucket      - the S3 bucket name
   :format      - the format of the file (makes sense only if you're writing data into :json or :csv)
   :file-name   - the name of the file
   :input       - the data to write or the actual file (input stream or file path)
   :csv-header? - if true, the CSV file will have a header row"
  {:malli/schema [:=> [:cat s3-params-spec]
                  [:map
                   [:bucket :string]
                   [:key :string]
                   [:etag {:optional true} :string]]]}
  [{:keys [aws-creds bucket file-name] :as options}]
  (let [staging (Files/createTempFile "collet-upload-" ".tmp" (make-array FileAttribute 0))]
    (try
      (actions.file/write-into-file (-> options (dissoc :folder) (assoc :file-name (str staging))))
      (let [client (make-client :s3 aws-creds)]
        (try
          (let [response (dh/with-retry
                          {:retry-on Exception :delay-ms 1000 :max-duration-ms (* 1000 60 2)}
                          (with-open [input (io/input-stream (str staging))]
                            (if (> (Files/size staging) five-gb-in-bytes)
                              (multipart-upload client bucket file-name input)
                              (invoke! client :PutObject {:Bucket bucket :Key file-name :Body input}))))]
            (utils/assoc-some {:bucket bucket :key file-name} :etag (:ETag response)))
          (finally (aws/stop client))))
      (finally (Files/deleteIfExists staging)))))


(defmethod action/action-fn ::sink
  [_]
  upload-file)


(defn- with-client [credentials f]
  (let [client (make-client :s3 credentials)]
    (try (f client) (finally (aws/stop client)))))


(defn- not-found? [response]
  (or (= :cognitect.anomalies/not-found (:cognitect.anomalies/category response))
      (contains? #{"NoSuchKey" "NotFound" "404"} (get-in response [:Error :Code]))))


(defn- object-exists? [client bucket key]
  (try
    (invoke! client :HeadObject {:Bucket bucket :Key key})
    true
    (catch clojure.lang.ExceptionInfo error
      (if (not-found? (:response (ex-data error))) false (throw error)))))


(defn- modified-at [value]
  (str (if (instance? java.util.Date value) (.toInstant ^java.util.Date value) value)))


(defn- object-row [bucket {:keys [Key Size LastModified ETag]}]
  {:bucket      bucket
   :key         Key
   :path        (str "s3://" bucket "/" Key)
   :file-name   (last (str/split Key #"/"))
   :size-bytes  Size
   :modified-at (modified-at LastModified)
   :etag        ETag})


(def listing-columns [:file-name :path :bucket :key :size-bytes :modified-at :etag])


(defn list-objects [{:keys [aws-creds bucket prefix page-size limit]
                     :or   {page-size 1000}
                     :as   options}]
  (when-not (and (pos-int? page-size) (<= page-size 1000) (or (nil? limit) (nat-int? limit)))
    (throw (ex-info "Use :page-size 1..1000 and a non-negative :limit." {})))
  (if (= 0 limit)
    (actions.file/listing-dataset listing-columns [])
    (with-client aws-creds
                 (fn [client]
                   (loop [token nil
                          rows  []]
                     (let [response   (invoke! client
                                       :ListObjectsV2
                                       (utils/assoc-some {:Bucket bucket :MaxKeys page-size}
                                        :Prefix prefix
                                        :ContinuationToken token))
                           matches    (->> (:Contents response)
                                           (map #(object-row bucket %))
                                           (filter #(actions.file/matches-file? options %)))
                           rows       (into rows (take (if limit (- limit (count rows)) Long/MAX_VALUE) matches))
                           next-token (:NextContinuationToken response)]
                       (if (and (:IsTruncated response) (or (nil? limit) (< (count rows) limit)))
                         (do (when (or (nil? next-token) (= token next-token))
                               (throw (ex-info "S3 returned an invalid continuation token." {})))
                             (recur next-token rows))
                         (actions.file/listing-dataset listing-columns rows))))))))


(defn read-object [{:keys [aws-creds bucket key as] :as options}]
  (let [client       (make-client :s3 aws-creds)
        transferred? (volatile! false)]
    (try
      (let [input ^InputStream (:Body (invoke! client :GetObject {:Bucket bucket :Key key}))]
        (if (= as :stream)
          (let [closed? (atom false)
                stream  (proxy [FilterInputStream] [input]
                          (close []
                            (when (compare-and-set! closed? false true)
                              (try (.close input) (finally (aws/stop client))))))]
            (vreset! transferred? true)
            stream)
          (actions.file/read-content input options)))
      (finally (when-not @transferred? (aws/stop client))))))


(defn stat-object [{:keys [aws-creds bucket key]}]
  (with-client aws-creds
               (fn [client]
                 (let [response (invoke! client :HeadObject {:Bucket bucket :Key key})]
                   (merge (object-row bucket
                                      (assoc response
                                        :Key key
                                        :Size (:ContentLength response)))
                          (utils/assoc-some {}
                           :content-type (:ContentType response)
                           :metadata (:Metadata response)))))))


(defn exists-object? [{:keys [aws-creds bucket key]}]
  (with-client aws-creds #(object-exists? % bucket key)))


(defn- copy-object* [client {:keys [bucket key destination-bucket destination-key overwrite?]}]
  (let [destination-bucket (or destination-bucket bucket)]
    (when-not (and (string? destination-key) (not (str/blank? destination-key)))
      (throw (ex-info "Copy requires :destination-key." {})))
    (when (and (= bucket destination-bucket) (= key destination-key))
      (throw (ex-info "Copy destination must differ from source." {})))
    (let [source (invoke! client :HeadObject {:Bucket bucket :Key key})]
      (when (> (:ContentLength source) five-gb-in-bytes)
        (throw (ex-info "Server-side copy is limited to 5 GB." {:bucket bucket :key key})))
      (when (and (not overwrite?) (object-exists? client destination-bucket destination-key))
        (throw (ex-info "Destination object already exists." {:bucket destination-bucket :key destination-key})))
      (let [response (invoke! client
                      :CopyObject
                      {:Bucket            destination-bucket
                       :Key               destination-key
                       :CopySource        (str bucket "/" (str/replace (URLEncoder/encode key "UTF-8") "+" "%20"))
                       :CopySourceIfMatch (:ETag source)})]
        (utils/assoc-some {:bucket destination-bucket :key destination-key}
         :etag (get-in response [:CopyObjectResult :ETag]))))))


(defn copy-object [{:keys [aws-creds] :as options}]
  (with-client aws-creds #(copy-object* % options)))


(defn move-object [{:keys [aws-creds bucket key] :as options}]
  (with-client aws-creds
               (fn [client]
                 (let [copied (copy-object* client options)]
                   (try
                     (invoke! client :DeleteObject {:Bucket bucket :Key key})
                     copied
                     (catch Exception error
                       (throw (ex-info "Object copied, but source deletion failed."
                                       {:copied copied :source {:bucket bucket :key key} :source-retained true}
                                       error))))))))


(defn delete-object [{:keys [aws-creds bucket key]}]
  (with-client aws-creds
               (fn [client]
                 (invoke! client :DeleteObject {:Bucket bucket :Key key})
                 {:bucket bucket :key key})))


(defmethod action/action-fn ::list [_] list-objects)


(defmethod action/action-fn ::read [_] read-object)


(defmethod action/action-fn ::stat [_] stat-object)


(defmethod action/action-fn ::exists? [_] exists-object?)


(defmethod action/action-fn ::copy [_] copy-object)


(defmethod action/action-fn ::move [_] move-object)


(defmethod action/action-fn ::delete [_] delete-object)
