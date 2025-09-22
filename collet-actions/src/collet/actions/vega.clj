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


(def data-spec
  "We need to store data of the vega or vega-lite plot in a file."
  [:map
   [:file-path :string]
   [:format [:enum :csv :json]]
   [:csv-header? {:optional true :default false} :boolean]])


(defn make-temp-data
  "Creates a temp file and returns an info where to store data of the plot."
  [prefix suffix]
  {:file-path (.getAbsolutePath (File/createTempFile prefix suffix))
   :format :json})

(def vega-params-spec
  [:or
   ;; json-schema -> malli conversion does not exist yet.
   ;; that's why we can't fully validate provided vega and vega-lite schemas.
   ;; At least let's validate that inputs could be datasets.

   [:map
    [:vega-lite-spec
     [:map
      [:data [:or
              [:map [:values file/input-param-spec]]
              [:map [:url :string]]]]]]
    [:store-data-files? {:optional true :default false} :boolean]
    [:data {:optional true} data-spec]
    [:svg-file-path :string]]

   [:map
    [:vega-spec
     [:map
      [:data
       [:vector [:or
                 [:map
                  [:values file/input-param-spec]
                  [:name :string]]
                 [:map
                  [:url :string]
                  [:name :string]]]]]]]
    [:store-data-files? {:optional true :default false} :boolean]
    [:data {:optional true}
     ;; data name to data file
     [:map-of :string data-spec]]
    [:svg-file-path :string]]])


(defn update-data-spec
  "If :data field of vega/lite spec has values, extract them and change them to a file's url.
   However, a client can hardcode :url here by themself, at this case render the file and do nothing here.
   Also, the client can specify values but doesn't specify where to store them, at this case do nothing.
   
   The file with the data is then should be written to the file system."
  [data-spec {:keys [file-path] :as data}]
  (if (and (some? data) (:values data-spec))
    {:spec (-> data-spec
               (dissoc :values)
               (assoc :url (.getAbsolutePath (io/file file-path))))
     :values-to-write (:values data-spec)}

    {:spec data-spec
     :values-to-write nil}))


(defn write-data-spec!
  "Writes a file for single :data entry"
  [data-spec {:keys [file-path format csv-header?] :as data
              :or {csv-header? false}}]
  (let [{:keys [spec values-to-write]} (update-data-spec data-spec data)]
    (when values-to-write
      (file/write-into-file
       {:input values-to-write
        :format format
        :file-name file-path
        :csv-header? csv-header?}))
    spec))


(defn write-vega-data-spec!
  "Writes files for all data entries in vega :data spec"
  [data-spec name->data]
  (mapv #(write-data-spec! % (-> % :name ((or name->data {})))) data-spec))


(defn write-vega-lite-into-svg!
  [spec store-data-files? data]
  (let [data (or data (make-temp-data "collet-vega-" ".json"))

        svg (try
              (-> spec
                  (update :data write-data-spec! data)
                  charred/write-json-str
                  darkstar/vega-lite-spec->svg)
              (catch PolyglotException e
                (throw (Exception. (str "vega-lite spec is incorrect: " (.getMessage e))))))]

    (when-not store-data-files?
      (io/delete-file (io/file (:file-path data))))

    svg))


(defn write-vega-into-svg!
  [spec store-data-files? name->data]
  (let [name->data (or name->data
                       (->> spec :data
                            (map #(vector (:name %)
                                          (make-temp-data "collet-vega-" ".json")))
                            (into {})))

        svg (try
              (-> spec
                  (update :data write-vega-data-spec! name->data)
                  charred/write-json-str
                  darkstar/vega-spec->svg)
              (catch PolyglotException e
                (throw (Exception. (str "vega spec is incorrect: " (.getMessage e))))))]

    (when-not store-data-files?
      (doseq [[_ f] name->data]
        (io/delete-file (io/file (:file-path f)))))

    svg))


(defn render-vega
  "Writes vega or vega-lite plot as svg image.
   
   In a vega-lite spec, in :data :values section,
   clients can specify a collection of maps or a collection of sequential items.

   In vega spec, :data is a vector of datasets, each element has :name and may include :values section.
 
   The action also wraps file/sink action, since the data needs to be written
   into a file before rendering the plot.
   For vega, multiple files can be specified in :data argument.
 
   If clients need additional parameters for a data file,
   they could call the file action first,
   and then specify the result file in vega or vega-lite spec.
 
   Options:
   :vega-spec         - vega schema
   :vega-lite-spec    - vega-lite schema. Either vega or vega-lite spec must be specified.
   :store-data-files? - whether to store data files or delete them after rendering a plot.
   :data              - for vega-lite, a single element where the client can specify the file-path and format of the file.
                        for vega, a map of such files: keys are dataset names, values are file-paths and formats.
                        Creates temp files if nothing specified.
   :svg-file-path     - where to store the rendered svg plot"
  {:malli/schema [:=> [:cat vega-params-spec]
                  [:map
                   [:svg-file-path :string]]]}
  [{:keys [vega-lite-spec vega-spec store-data-files? data svg-file-path]
    :or {store-data-files? false}}]

  (let [svg (if (some? vega-lite-spec)
              (write-vega-lite-into-svg! vega-lite-spec store-data-files? data)
              (write-vega-into-svg! vega-spec store-data-files? data))

        output-file (io/file svg-file-path)]

    (when-not (.exists output-file)
      (io/make-parents output-file))
    (io/copy svg (io/output-stream output-file))

    {:svg-file-path svg-file-path}))


(defmethod action/action-fn ::sink [_]
  render-vega)
