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
    [(-> data-spec
         (dissoc :values)
         (assoc :url (.getAbsolutePath (io/file file-path))))
     (:values data-spec)]
    [data-spec nil]))


(defn do-write-data-spec
  [data-spec {:keys [file-path format csv-header?] :as data}]
  (let [[new-spec values] (update-data-spec data-spec data)]
    (when values
      (file/write-into-file
       (cond-> {:input values
                :format format
                :file-name file-path}
         (some? csv-header?) (assoc :csv-header? csv-header?))))
    new-spec))


(defn do-write-vega-data-spec
  [data-spec name->data]
  (doall
   (mapv #(do-write-data-spec % (get name->data (:name %))) data-spec)))


(defn write-vega-into-svg
  "Writes vega or vega-lite plot as svg image.
   
   In a vega-lite spec, in :data :values section,
   clients can specify a collection of maps or a collection of sequential items.

   In vega spec, :data is a vector of datasets, each element has :name and mai include :values section.
 
   The action also wraps file/sink action, since the data needs to be written
   into a file before rendering the plot.
   For vega, multiple files can be specified in :data argument.
 
   If clients need additional parameters for a data file,
   they could call the file action first,
   and then specify the result file in vega or vega-lite spec.
 
   Options:
   :vega-spec      - vega schema
   :vega-lite-spec - vega-lite schema. Either vega or vega-lite spec must be specified.
   :data           - for vega-lite, a single element where the client can specify the file-path and format of the file.
                     for vega, a map of such files: keys are dataset names, values are file-paths and formats.
   :svg-file-path  - where to store the rendered svg plot"
  {:malli/schema [:=> [:cat vega-params-spec]
                  [:map
                   [:svg-file-path :string]]]}
  [{:keys [vega-lite-spec vega-spec data svg-file-path]}]

  (let [{:keys [spec update-data-fn render-fn]}
        (if (nil? vega-lite-spec)
          {:spec vega-spec
           :update-data-fn do-write-vega-data-spec
           :render-fn darkstar/vega-spec->svg}

          {:spec vega-lite-spec
           :update-data-fn do-write-data-spec
           :render-fn darkstar/vega-lite-spec->svg})

        spec-with-data (update spec :data update-data-fn data)

        spec-json (charred/write-json-str spec-with-data)

        svg (try
              (render-fn spec-json)
              (catch PolyglotException e
                (throw (Exception. (str "vega/lite spec is incorrect: " (.getMessage e))))))

        output-file (io/file svg-file-path)]
    (when-not (.exists output-file)
      (io/make-parents output-file))

    (io/copy svg (io/output-stream output-file))

    {:svg-file-path svg-file-path}))


(defmethod action/action-fn ::sink [_]
  write-vega-into-svg)
