(ns collet.main
  (:gen-class)
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.edn :as edn]
   [clojure.tools.cli :as tools.cli]
   [com.brunobonacci.mulog :as ml]
   [collet.core :as collet]
   [collet.aws :as aws]
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


(declare read-config-file)


(defn deep-merge
  [& maps]
  (if (every? map? maps)
    (apply merge-with deep-merge maps)
    (last maps)))


(defn include-spec
  [path]
  (if (sequential? path)
    (let [[path overrides] path]
      (deep-merge
       (read-config-file :spec (new URI path))
       overrides))
    (read-config-file :spec (new URI path))))


(defn read-regex
  [rgx]
  (re-pattern rgx))


(defn read-config-string [target s]
  (if (= target :config)
    (edn/read-string {:eof nil :readers {'env get-env}} s)
    (edn/read-string {:eof nil :readers {'include include-spec 'rgx read-regex}} s)))


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
        client (aws/make-client :s3 (utils/assoc-some {}
                                      :aws-region region
                                      :aws-key access-key
                                      :aws-secret secret-key))]
    (-> (aws/invoke! client :GetObject {:Bucket bucket :Key path})
        :Body
        slurp)))


(defn read-config-file
  "Reads the content of the file from the provided URI"
  [target ^URI uri]
  (let [file-path (.getPath uri)]
    (case (.getScheme uri)
      "s3"
      (->> (s3-file-content uri)
           (read-config-string target))
      ("http" "https")
      (->> (slurp uri)
           (read-config-string target))
      ;; defaults to local file
      (let [file (io/as-file file-path)]
        (if (.exists file)
          (->> file slurp (read-config-string target))
          (throw (ex-info "File does not exist" {:file file-path})))))))


(defn file-or-map
  "Parses the provided string argument as a content of the file path or a raw Clojure map"
  [target s]
  (if-some [uri ^URI (try (new URI s)
                          (catch URISyntaxException _ nil))]
    ;; read as a file
    (read-config-file target uri)
    ;; parse as map
    (read-config-string target s)))


(def cli-options
  [["-s" "--pipeline-spec PIPELINE_SPEC" "(Required) Path to the pipeline spec file"
    :missing true
    :parse-fn (partial file-or-map :spec)
    :validate [not-empty "Must provide a pipeline spec file"]]
   ["-c" "--pipeline-config PIPELINE_CONFIG" "(Optional) Dynamic configuration for the pipeline"
    :default {}
    :parse-fn (partial file-or-map :config)
    :validate [map? "Must provide a map for the pipeline config"]]])


(defn start-publishers
  "Starts the publishers based on the provided configuration (environment variables)"
  []
  (let [console-publisher-pretty    (get-env "CONSOLE_PUBLISHER_PRETTY" 'Bool :or true)
        file-publisher-filename     (get-env "FILE_PUBLISHER_FILENAME" 'Str :or "tmp/collet-*.log")
        elasticsearch-publisher-url (get-env "ELASTICSEARCH_PUBLISHER_URL" 'Str :or "http://localhost:9200/")
        zipkin-publisher-url        (get-env "ZIPKIN_PUBLISHER_URL" 'Str :or "http://localhost:9411")
        publishers                  (cond-> []
                                      (get-env "CONSOLE_PUBLISHER" 'Bool)
                                      (conj {:type :console :pretty? console-publisher-pretty})
                                      (get-env "ELASTICSEARCH_PUBLISHER" 'Bool)
                                      (conj {:type :elasticsearch :url elasticsearch-publisher-url})
                                      (get-env "ZIPKIN_PUBLISHER" 'Bool)
                                      (conj {:type :zipkin :url zipkin-publisher-url})
                                      (get-env "FILE_PUBLISHER" 'Bool)
                                      (conj {:type         :custom
                                             :fqn-function "collet.file-publisher/file-publisher"
                                             :filename     file-publisher-filename}))]
    (when (not-empty publishers)
      (ml/start-publisher!
       {:type       :multi
        :publishers publishers}))))


(Thread/setDefaultUncaughtExceptionHandler
 (fn [thread ex]
   (ml/log :collet/uncaught-exception
           :exception ex :thread (.getName thread))
   (Thread/sleep 2000)
   (throw ex)))


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
            stop-publishers (start-publishers)
            pipeline        (collet/compile-pipeline pipeline-spec)
            stop-fn         (fn []
                              (ml/log :collet/stopping)
                              (when (not= (collet/pipe-status pipeline) :stopped)
                                (collet/stop pipeline))
                              (when (fn? stop-publishers)
                                ;; wait for publishers to finish
                                (Thread/sleep 3000)
                                (stop-publishers)))]

        (->> (Thread. ^Runnable stop-fn)
             (.addShutdownHook (Runtime/getRuntime)))

        (try
          (println "Starting pipeline...")
          (ml/log :collet/starting)
          @(pipeline pipeline-config)
          (println "Pipeline completed.")
          (catch Exception ex
            (println "Pipeline failed with an exception:")
            (println (ex-message ex))
            (println (ex-cause ex)))
          (finally
            (stop-fn)
            (System/exit 0)))))))