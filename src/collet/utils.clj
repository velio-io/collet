(ns collet.utils
  (:require
   [cognitect.aws.client.api :as aws]
   [cognitect.aws.credentials :as credentials]))


(defn assoc-some
  "Associates a key k, with a value v in a map m, if and only if v is not nil."
  ([m k v]
   (if (nil? v) m (assoc m k v)))

  ([m k v & kvs]
   (reduce (fn [m [k v]] (assoc-some m k v))
           (assoc-some m k v)
           (partition 2 kvs))))


(defn make-client
  "Creates an AWS client for the S3 service."
  [api {:keys [aws-region aws-key aws-secret endpoint-override]}]
  (aws/client
   (assoc-some {:api api}
     :region aws-region
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