(ns collet.actions.http
  (:require
   [clojure.java.io :as io]
   [org.httpkit.client :as http]
   [cheshire.core :as json]
   [diehard.core :as dh]
   [diehard.rate-limiter :as rl])
  (:import
   [java.io InputStream]))


(defn read-json
  "Reads a JSON object from an input stream and optionally keywordizes keys."
  [^InputStream input keywordize]
  (when (some? input)
    (with-open [rdr (io/reader input)]
      (json/parse-stream rdr keywordize))))


(defn wrap-rate-limiter
  "Wraps a function with a rate limiter if one is provided in the options."
  [func]
  (fn [{::keys [rate-limiter] :as options}]
    (if (some? rate-limiter)
      (dh/with-rate-limiter {:ratelimiter rate-limiter}
        (func (dissoc options ::rate-limiter)))
      (func options))))


(def unexceptional-status?
  #{200 201 202 203 204 205 206 207 300 301 302 303 304 307 308})


(defn unexceptional-request-status?
  "Returns true if the request status is not an exception.
   By default, the following statuses are considered unexceptional:
   200, 201, 202, 203, 204, 205, 206, 207, 300, 301, 302, 303, 304, 307, 308.
   List of unexceptional statuses can be overridden by setting the :unexceptional-status key in the request map."
  [req status]
  ((or (:unexceptional-status req) unexceptional-status?)
   status))


(def http-request
  (-> http/request
      (wrap-rate-limiter)))


(def request-params-spec
  [:map
   [:url
    [:or :string
     [:catn [:template-string :string] [:substitution [:* :any]]]]]
   [:method {:optional true :default :get}
    [:enum :get :post :put :delete :head :options :trace]]
   [:keywordize {:optional true :default true}
    :boolean]
   [:as {:optional true}
    [:enum :json :auto :text :stream :byte-array]]
   [:content-type {:optional true}
    [:enum :json]]
   [:accept {:optional true}
    [:enum :json]]
   [:unexceptional-status {:optional true}
    [:set :int]]
   [:rate {:optional true}
    :int]])


(defn make-request
  "Make an HTTP request and return the response.
   The request map can contain the following keys:
   :url - the URL to request
   :method - the HTTP method to use (default - :get)
   :keywordize - keywordize the keys in the response (default - true)
   :as - the response format
   :content-type - the content type of the request
   :accept - the accept header of the request
   :unexceptional-status - a set of unexceptional statuses
   :rate - the rate limit for the request. How many requests per second are allowed."
  {:malli/schema [:=> [:cat request-params-spec]
                  :any]}
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


(defn attach-rate-limiter
  "Attaches a rate limiter to the action spec if a rate is provided."
  [action-spec]
  (let [rate (get-in action-spec [:params :rate])]
    (cond-> action-spec
      (some? rate) (assoc-in [:params ::rate-limiter] (rl/rate-limiter {:rate rate})))))


(def request-action
  {:action make-request
   :prep   attach-rate-limiter})