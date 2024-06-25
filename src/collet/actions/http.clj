(ns collet.actions.http
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [org.httpkit.client :as http])
  (:import
   [java.io InputStream]))


(defn read-json [^InputStream input keywordize]
  (when (some? input)
    (with-open [rdr (io/reader input)]
      (json/parse-stream rdr keywordize))))


(defn request
  [{:keys [method keywordize as content-type accept]
    :or   {method :get keywordize true}
    :as   req-map}]
  (let [request  (cond-> req-map
                   (= as :json) (assoc :as :stream)
                   (= content-type :json) (assoc-in [:headers "Content-Type"] "application/json")
                   (= accept :json) (assoc-in [:headers "Accept"] "application/json")
                   :always (assoc :method method))
        response @(http/request request)]
    (cond-> response
      (= as :json) (update :body read-json keywordize))))
