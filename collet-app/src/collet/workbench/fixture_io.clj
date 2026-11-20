(ns collet.workbench.fixture-io
  (:require [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [collet.stream :as stream]
            [collet.utils :as utils]
            [tech.v3.dataset :as ds])
  (:import [java.io ByteArrayInputStream]
           [java.net URI]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption Path]))


(defn filename? [name]
  (and (string? name)
       (re-matches #"[A-Za-z0-9._-]+" name)
       (not (#{"." ".."} name))))


(defn- fail! [code action message]
  (throw (ex-info message {:code code :action action :collet/redact-params true})))


(defn- normalize-query [params]
  (into {} (map (fn [[k v]] [(name k) (if (sequential? v) (mapv str v) (str v))])) params))


(defn request-key [{:keys [method url query-params] :or {method :get}}]
  [(keyword (str/lower-case (name method)))
   (.normalize (URI. ^String url))
   (normalize-query query-params)])


(defn routes [http]
  (into {}
        (for [[action routes] http]
          [action (into {} (map (juxt (comp request-key :request) :responses)) routes)])))


(defn request! [routes counters action params]
  (let [request (requiring-resolve 'collet.actions.http/request-with-transport)]
    (request params
             (fn [{:keys [as] :as options}]
               (let [k          (request-key options)
                     replies    (get-in routes [action k])
                     _ (when-not replies
                         (fail! "fixture-unmatched-http" action "No fixture route matches this HTTP request. Check its method, URL and query parameters."))
                     [before _] (swap-vals! counters update-in [action k] (fnil inc 0))
                     index      (get-in before [action k] 0)
                     response   (or (get replies index)
                                    (fail! "fixture-http-exhausted" action "The matching HTTP fixture route has no responses left."))
                     body       (if (contains? response :json)
                                  (json/write-json-str (:json response))
                                  (or (:body response) ""))
                     bytes      (.getBytes ^String body StandardCharsets/UTF_8)]
                 (assoc (select-keys response [:status :headers])
                   :body (case as
                           :stream (ByteArrayInputStream. bytes)
                           :byte-array bytes
                           body)))))))


(defn unused-response [routes counters]
  (some (fn [[action requests]]
          (when (some (fn [[request responses]]
                        (< (get-in @counters [action request] 0) (count responses)))
                      requests)
            {:code    "fixture-http-unused"
             :action  (name action)
             :message "The fixture run did not consume all responses for this action."}))
        routes))


(def ^:private no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))


(defn write! [^Path directory files action {:keys [file-name input format] :as params}]
  (when-not (filename? file-name)
    (fail! "fixture-file-path" action "Use a single filename without directory components."))
  (when-not (case format
              :text (string? input)
              :csv (or (ds/dataset? input) (utils/ds-seq? input) (sequential? input))
              false)
    (fail! "fixture-file-format"
           action
           (str "Use CSV rows or a literal text body in a temporary file sink. Received "
                (if (#{:csv :text} format) (name format) "an unsupported format")
                " with "
                (some-> input
                        class
                        .getSimpleName)
                " input.")))
  (let [path (.resolve directory ^String file-name)]
    (when (Files/isSymbolicLink path)
      (fail! "fixture-file-path" action "Temporary file sinks cannot write through symbolic links."))
    (let [sink    (requiring-resolve 'collet.actions.file/write-into-file)
          receipt (sink (assoc (select-keys params [:file-name :input :format :csv-header? :cat?])
                          :folder (str directory)
                          :encoding "UTF-8"))]
      (swap! files assoc file-name {:name file-name :path (str path) :bytes (Files/size path)})
      receipt)))


(defn rows-match? [value expected]
  (let [rows (cond
               (ds/dataset? value) (ds/rows value)
               (utils/ds-seq? value) (stream/rows value)
               (sequential? value) value)]
    (and (or (some? rows) (nil? value))
         (= (frequencies expected)
            (frequencies (take (inc (count expected)) rows))))))


(defn csv-match? [^Path directory file {:keys [header rows]}]
  (when directory
    (let [path (.resolve directory ^String file)]
      (when (and (Files/isRegularFile path no-follow) (<= (Files/size path) 1048576))
        (try
          (with-open [reader (io/reader (.toFile path) :encoding "UTF-8")]
            (let [[actual-header & actual-rows] (take (+ 2 (count rows)) (json/read-csv reader))]
              (and (= header actual-header)
                   (= (frequencies rows) (frequencies actual-rows)))))
          (catch Exception _ false))))))
