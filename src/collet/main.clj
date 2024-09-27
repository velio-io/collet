(ns collet.main
  (:gen-class)
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.edn :as edn]
   [clojure.tools.cli :as tools.cli]
   [collet.core :as collet]
   [collet.utils :as utils])
  (:import
   [java.net URI URISyntaxException]))


(defmulti coerce
  "Coerce a value to the type referenced by a symbol."
  (fn [x type] type))

(defmethod coerce 'Int [x _]
  (Long/parseLong x))

(defmethod coerce 'Double [x _]
  (Double/parseDouble x))

(defmethod coerce 'Str [x _]
  (str x))

(defmethod coerce 'Keyword [x _]
  (keyword x))

(defmethod coerce 'Bool [x _]
  (case (some-> x string/lower-case)
    ("true" "t" "yes" "y") true
    ("false" "f" "no" "n" "" nil) false
    (throw (ex-info (str "Could not coerce '" (pr-str x) "' into a boolean."
                         "Must be one of: \"true\", \"t\", \"false\", \"f\","
                         "\"yes\", \"y\", \"no\", \"n\", \"\" or nil.")
                    {:value x, :coercion 'Bool}))))


(def ^:dynamic *env*
  (into {} (System/getenv)))


(defn get-env
  ([name]
   (if (vector? name)
     (apply get-env name)
     (*env* name)))
  ([name type & options]
   (if (keyword? type)
     ;; if type is not provided, assume it's a string
     (apply get-env name 'Str type options)
     (let [{default :or} options
           value (*env* name)]
       (if (nil? value)
         default
         (coerce value type))))))


(defn read-config-string [s]
  (edn/read-string {:eof nil :readers {'env get-env}} s))


(defn query->map
  "Turns a query string into a Clojure map with keyword keys"
  [query]
  (let [params (string/split query #"&")]
    (reduce (fn [acc param]
              (let [[k v] (string/split param #"=" 2)]
                (assoc acc (keyword k) v)))
            {}
            params)))


(defn s3-file-content
  "Reads the content of a file from S3"
  [^URI uri]
  (let [creds  (.getUserInfo uri)
        bucket (.getHost uri)
        path   (.getPath uri)
        {:keys [region]} (some-> (.getQuery uri)
                                 (query->map))
        [access-key secret-key] (if (some? creds)
                                  (string/split creds #":")
                                  [nil nil])
        client (utils/make-client :s3 (utils/assoc-some {}
                                        :aws-region region
                                        :aws-key access-key
                                        :aws-secret secret-key))]
    (-> (utils/invoke! client :GetObject {:Bucket bucket :Key path})
        :Body
        slurp)))


(defn file-or-map
  "Parses the provided string argument as a content of the file path or a raw Clojure map"
  [s]
  (if-some [uri (try (new URI s)
                     (catch URISyntaxException _ nil))]
    ;; read as a file
    (let [file-path (.getPath uri)]
      (case (.getScheme uri)
        "s3"
        (-> (s3-file-content uri)
            (read-config-string))
        ("http" "https")
        (-> (slurp uri)
            (read-config-string))
        ;; defaults to local file
        (let [file (io/as-file file-path)]
          (if (.exists file)
            (-> file slurp read-config-string)
            (throw (ex-info "File does not exist" {:file file-path}))))))
    ;; parse as map
    (read-config-string s)))


(def cli-options
  [["-s" "--pipeline-spec PIPELINE_SPEC" "(Required) Path to the pipeline spec file"
    :missing true
    :parse-fn file-or-map
    :validate [not-empty "Must provide a pipeline spec file"]]
   ["-c" "--pipeline-config PIPELINE_CONFIG" "(Optional) Dynamic configuration for the pipeline"
    :default {}
    :parse-fn file-or-map
    :validate [map? "Must provide a map for the pipeline config"]]])


(defn -main
  "Main entry point for the collet application.
   Receives the pipeline spec and config as arguments.
   Pipeline spec and config can be provided as a file path (local or S3) or a raw Clojure map."
  [& args]
  (let [{:keys [options errors summary]} (tools.cli/parse-opts args cli-options)]
    (if errors
      (do (println "Failed to parse provided options.")
          (println (or (first errors) "Check the usage:"))
          (println summary)
          (System/exit 1))
      ;; run the pipeline
      (let [{:keys [pipeline-spec pipeline-config]} options
            pipeline (collet/compile-pipeline pipeline-spec)]
        (try
          (println "Starting pipeline...")
          @(pipeline pipeline-config)
          (println "Pipeline completed.")
          (catch Exception ex
            (println "Pipeline failed with an exception:")
            (println (.getMessage ex)))
          (finally
            (System/exit 0)))))))