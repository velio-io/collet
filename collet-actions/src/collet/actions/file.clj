(ns collet.actions.file
  (:require
   [clojure.java.io :as io]
   [charred.api :as charred]
   [ham-fisted.lazy-noncaching :as lznc]
   [tech.v3.dataset :as ds]
   [tech.v3.dataset.protocols :as ds-proto]
   [collet.utils :as utils]
   [collet.action :as action])
  (:import
   [java.io InputStream OutputStream]
   [java.net URI URISyntaxException]))


(defn ->string
  "Converts the input to a string."
  ^String [x]
  (when-not (nil? x)
    (cond
      (string? x) x
      (keyword? x) (name x)
      (symbol? x) (name x)
      :else (.toString ^Object x))))


(defn write-dataset
  "Writes the dataset to a file."
  [dataset file-name format csv-header?]
  (case format
    :json (ds/write! dataset file-name)
    :csv (ds/write! dataset file-name {:headers? csv-header?})))


(defn dataset-seq->csv
  "Writes a sequence of datasets to a CSV file."
  [input file-name csv-header?]
  (transduce (mapcat (fn [dataset]
                       (let [headers (when csv-header?
                                       (map (comp ->string :name meta) (vals dataset)))
                             rows    (->> (ds-proto/rowvecs dataset nil)
                                          (lznc/map #(lznc/map ->string %)))]
                         (if (and csv-header? (= dataset (first input)))
                           (lznc/concat [headers] rows)
                           rows))))
             (charred/write-csv-rf file-name)
             input))


(defn is-uri?
  "Returns true if the input is a URI or a string that can be converted to a URI."
  [x]
  (or (uri? x)
      (try (new URI x)
           true
           (catch URISyntaxException _
             false))))


(def file-params-spec
  [:map
   [:input
    [:or :string
     utils/input-stream?
     utils/dataset?
     [:sequential utils/dataset?]
     [:sequential [:or map? [:sequential map?]]]]]
   [:file-name :string]
   [:cat? {:optional true :default false} :boolean]
   [:format {:optional true} :keyword]
   [:csv-header? {:optional true :default false} :boolean]])


(defn write-into-file
  "Writes the input to a file.
   The input data should be a collection of maps or a collection of sequential items.
   Options:
   :input       - the data to write or the actual file (input stream or file path)
   :format      - the format of the file (makes sense only if you're writing data into :json or :csv)
   :file-name   - the name of the file
   :override?   - if true, the file will be overwritten if it exists
   :csv-header? - if true, the CSV file will have a header row"
  {:malli/schema [:=> [:cat file-params-spec]
                  [:map
                   [:file-name :string]
                   [:path :string]]]}
  [{:keys [input format file-name csv-header? cat?]
    :or   {csv-header? false cat? false}}]
  (let [file (io/file file-name)]
    (when-not (.exists file)
      (io/make-parents file))

    (cond
      (or (instance? InputStream input)
          (and (string? input)
               (or (.exists (io/file input))
                   (is-uri? input))))
      (with-open [in  ^InputStream (io/input-stream input)
                  out ^OutputStream (io/output-stream file)]
        (io/copy in out))

      (utils/ds-seq? input)
      (case format
        :json (ds/write! (apply utils/parallel-concat input) file-name)
        :csv (dataset-seq->csv input file-name csv-header?))

      (ds/dataset? input)
      (write-dataset input file-name format csv-header?)

      :otherwise
      (-> (utils/make-dataset input {:cat? cat?})
          (write-dataset file-name format csv-header?)))

    {:file-name file-name
     :path      (.getAbsolutePath file)}))


(defmethod action/action-fn ::sink [_]
  write-into-file)