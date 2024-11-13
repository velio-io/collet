(ns collet.actions.file
  (:require
   [clojure.java.io :as io]
   [tech.v3.dataset :as ds]
   [collet.utils :as utils]
   [collet.action :as action]))


(def file-params-spec
  [:map
   [:input
    [:or utils/dataset?
     [:sequential [:or map? [:sequential :any]]]]]
   [:cat? {:optional true :default false}
    :boolean]
   [:format
    [:enum :json :csv]]
   [:file-name :string]
   [:csv-header? {:optional true :default false}
    :boolean]])


(defn write-into-file
  "Writes the input to a file.
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


(defmethod action/action-fn ::sink [_]
  write-into-file)