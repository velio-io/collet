(ns collet.actions.http
  (:require
   [clojure.java.io :as io]
   [org.httpkit.client :as http]
   [cheshire.core :as json]
   [diehard.core :as dh]
   [diehard.rate-limiter :as rl])
  (:import
   [java.io InputStream]))


(defn read-json [^InputStream input keywordize]
  (when (some? input)
    (with-open [rdr (io/reader input)]
      (json/parse-stream rdr keywordize))))


(defn wrap-rate-limiter [func]
  (fn [{::keys [rate-limiter] :as options}]
    (if (some? rate-limiter)
      (dh/with-rate-limiter {:ratelimiter rate-limiter}
        (func (dissoc options ::rate-limiter)))
      (func options))))


(def unexceptional-status?
  #{200 201 202 203 204 205 206 207 300 301 302 303 304 307 308})


(defn unexceptional-request-status?
  [req status]
  ((or (:unexceptional-status req) unexceptional-status?)
   status))


(def http-request
  (-> http/request
      (wrap-rate-limiter)))


(defn make-request
  [{:keys [url method keywordize as content-type accept]
    :or   {method :get keywordize true}
    :as   req-map}]
  (let [request  (cond-> req-map
                   (vector? url) (assoc :url (apply format url))
                   (= as :json) (assoc :as :stream)
                   (= content-type :json) (assoc-in [:headers "Content-Type"] "application/json")
                   (= accept :json) (assoc-in [:headers "Accept"] "application/json")
                   :always (assoc :method method))
        response @(http-request request)]
    (if (or (some? (:error response))
            (not (unexceptional-request-status? request (:status response))))
      (throw (ex-info "Request failed" {:status  (:status response)
                                        :error   (:error response)
                                        :request request}))
      ;; return response
      (cond-> response
        (= as :json) (update :body read-json keywordize)))))


(defn attach-rate-limiter [action-spec]
  (let [rate (get-in action-spec [:params :rate])]
    (cond-> action-spec
      (some? rate) (assoc-in [:params ::rate-limiter] (rl/rate-limiter {:rate rate})))))


(def request-action
  {:action make-request
   :prep   attach-rate-limiter})