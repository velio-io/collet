(ns collet.actions.vega
  (:require
   [applied-science.darkstar :as darkstar]
   [charred.api :as charred]
   [clojure.java.io :as io]
   [collet.action :as action]
   [collet.actions.file :as file])
  (:import
   [java.io File]
   [org.graalvm.polyglot PolyglotException]))


(def vega-params-spec
  [:map
   [:vega-lite-spec
    ;; json-schema -> malli conversion does not exist yet.
    ;; that's why we can't fully validate provided vega-lite.
    ;; But at least let's validate that input could be a dataset
    :any]
   [:data-file-path {:optional true} :string]
   [:data-format {:optional true} :keyword]
   [:svg-file-path :string]])


(defn write-vega-lite-into-svg
  "Writes vega-lite plot as svg image.
   In vega-lite spec, in :data :values section,
   clients could specify a collection of maps or a collection of sequential items.
 
   The action also wraps file/sink action, since the data needs to be written
   into a file before rendering a plot.
 
   If the clients need more parameters for data file,
   they could call the file action first,
   and then specify the result file in vega-lite spec.
 
   Options:
   :vega-lite-spec - vega-lite schema
   :data-file-path - where to store data, If omitted, use vega-lite-spec as-is 
   :data-format    - the format for the data (:json or :csv). Also could be omitted
   :svg-file-path  - where to store the rendered svg plot"
  {:malli/schema [:=> [:cat vega-params-spec]
                  [:map
                   [:svg-file-path :string]
                   [:data-file-path :string]]]}
  [{:keys [vega-lite-spec data-file-path data-format svg-file-path]}]

  (let [data (when (and (some? data-file-path)
                        (some? data-format)
                        (-> vega-lite-spec :data :values))
               (:path (file/write-into-file
                       {:input (-> vega-lite-spec :data :values)
                        :format data-format
                        :file-name data-file-path})))

        vega-with-data (if-not data
                         vega-lite-spec
                         (-> vega-lite-spec
                             (update :data dissoc :values)
                             (assoc-in [:data :url] data)))

        vega-json (charred/write-json-str vega-with-data)

        svg (try
              (darkstar/vega-lite-spec->svg vega-json)
              (catch PolyglotException e
                (throw (Exception. (str "vega-lite spec is incorrect: " (.getMessage e))))))

        output-file (io/file svg-file-path)]
    (when-not (.exists output-file)
      (io/make-parents output-file))

    (io/copy svg (io/output-stream output-file))

    {:svg-file-path svg-file-path
     :data-file-path data-file-path}))


(defmethod action/action-fn ::sink [_]
  write-vega-lite-into-svg)
