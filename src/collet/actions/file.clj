(ns collet.actions.file
  (:require
   [cheshire.core :as json]
   [clojure.data.csv :as csv]
   [clojure.java.io :as io]))


(defn write-json
  "Writes the input data to a JSON file."
  [w input]
  (doseq [record input]
    (->> (json/generate-string record)
         (.write w))
    (.write w "\n")))


(defn write-csv
  "Writes the input data to a CSV file.
   Options:
   :input       - the data to write
   :csv-header? - if true, the CSV file will have a header row"
  [w input & {:keys [csv-header?]}]
  (let [item (first input)
        data (cond
               (map? item)
               (let [header    (keys item)
                     header-fn (mapv (fn [k] #(get % k)) header)
                     rows      (map (apply juxt header-fn) input)]
                 (if csv-header?
                   (cons (map name header) rows)
                   rows))

               (sequential? item)
               (if csv-header?
                 input
                 (rest input))

               :otherwise
               (throw (ex-info (str "Invalid input for file action.
                                     Input data should be either a collection of maps or collection of sequential items.
                                     Provided input type: " (type input))
                               {:input-type (type input)})))]
    (csv/write-csv w data)))


(def file-params-spec
  [:map
   [:input
    [:sequential [:or map? [:sequential :any]]]]
   [:format
    [:enum :json :csv]]
   [:filename :string]
   [:override? {:optional true :default false}
    :boolean]
   [:csv-header? {:optional true :default false}
    :boolean]])


(defn write-into-file
  "Writes the input to a file.
   For JSON format, the resulting file will contain one JSON object per line.
   The input data should be a collection of maps or a collection of sequential items.
   Options:
   :input       - the data to write
   :format      - the format of the file (:json or :csv)
   :filename    - the name of the file
   :override?   - if true, the file will be overwritten if it exists
   :csv-header? - if true, the CSV file will have a header row"
  {:malli/schema [:=> [:cat file-params-spec]
                  [:map
                   [:filename :string]
                   [:path :string]]]}
  [{:keys [input format filename override? csv-header?]
    :or   {override? false csv-header? false}}]
  (let [file    (io/file filename)
        exists? (.exists file)]
    (when (and exists? override?)
      (io/delete-file file))

    (when-not (.exists file)
      (io/make-parents file))

    (with-open [w (io/writer file :append (not override?))]
      (case format
        :json (write-json w input)
        :csv (write-csv w input :csv-header? csv-header?)))

    {:filename filename
     :path     (.getAbsolutePath file)}))


(def write-file-action
  {:action write-into-file})