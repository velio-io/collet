(ns collet.actions.http
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [collet.utils :as utils]
   [collet.action :as action]
   [hato.client :as hato]
   [charred.api :as charred]
   [diehard.core :as dh]
   [diehard.rate-limiter :as rl])
  (:import
   [java.io InputStream]))


(defn read-json
  "Reads a JSON object from an input stream and optionally keywordizes keys."
  [^InputStream input keywordize]
  (when (some? input)
    (with-open [rdr (io/reader input)]
      (if keywordize
        (charred/read-json rdr :key-fn keyword)
        (charred/read-json rdr)))))


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


(defn wrap-unexceptional-status
  "Wraps a function with a check for unexceptional statuses.
   If the status is not unexceptional, throws an exception."
  [func]
  (fn [req]
    (let [response (func req)]
      (if (or (some? (:error response))
              (not (unexceptional-request-status? req (:status response))))
        (let [error (or (:error response)
                        (some-> (:body response) (read-json true) :error :message))]
          (throw (ex-info (str "Request failed. " error)
                          {:status  (:status response)
                           :error   error
                           :request req})))
        response))))


(def http-request
  (-> hato/request
      (wrap-rate-limiter)
      (wrap-unexceptional-status)))


(def request-params-spec
  [:map
   [:url
    [:or :string
     [:catn [:template-string :string] [:substitution [:* :any]]]]]
   [:method {:optional true :default :get}
    [:enum :get :post :put :delete :head :options :trace]]
   [:body {:optional true} :any]
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
    number?]
   [:basic-auth {:optional true}
    [:tuple :string :string]]])


(defn make-request
  "Make an HTTP request and return the response.
   The request map can contain the following keys:
   :url - the URL to request
   :method - the HTTP method to use (default - :get)
   :body - the request body
   :keywordize - keywordize the keys in the response (default - true)
   :as - the response format
   :content-type - the content type of the request
   :accept - the accept header of the request
   :unexceptional-status - a set of unexceptional statuses
   :rate - the rate limit for the request. How many requests per second are allowed.
   :basic-auth - a vector of username and password for basic authentication."
  {:malli/schema [:=> [:cat request-params-spec]
                  :any]}
  [{:keys [url method body keywordize as content-type accept]
    :or   {method :get keywordize true}
    :as   req-map}]
  (let [request  (cond-> req-map
                   (vector? url) (assoc :url (apply format url))
                   (= as :json) (assoc :as :stream)
                   (= accept :json) (assoc-in [:headers "Accept"] "application/json")
                   (= content-type :json) (assoc-in [:headers "Content-Type"] "application/json")
                   ;; coerce body to a JSON string if it's not yet
                   (and (= content-type :json)
                        (some? body)
                        (not (string? body)))
                   (assoc :body (charred/write-json-str body))

                   :always (assoc :method method
                                  :throw-exceptions false
                                  :http-client {:redirect-policy :normal}))
        response (http-request request)]
    (cond-> response
      (= as :json) (update :body read-json keywordize))))


(defn attach-rate-limiter
  "Attaches a rate limiter to the action spec if a rate is provided."
  [action-spec]
  (let [rate (get-in action-spec [:params :rate])]
    (cond-> action-spec
      (some? rate) (assoc-in [:params ::rate-limiter] (rl/rate-limiter {:rate rate})))))


(defmethod action/action-fn ::request [_]
  make-request)


(defmethod action/prep ::request [action-spec]
  (attach-rate-limiter action-spec))


(defn ->scope [scope]
  (cond->> scope (sequential? scope) (string/join " ")))


(def oauth2-params
  [:map
   [:url [:or :string
          [:catn [:template-string :string] [:substitution [:* :any]]]]]
   [:method {:optional true :default :post} [:enum :get :post]]
   [:client-id {:optional true} :string]
   [:client-secret {:optional true} :string]
   [:scope {:optional true} [:or :string [:+ :string]]]
   [:grant-type {:optional true} :string]
   [:auth-data {:optional true} map?]
   [:as {:optional true}
    [:enum :json :auto :text :stream :byte-array]]
   [:keywordize {:optional true} :boolean]])


(defn get-oauth2-token
  "Get an OAuth2 token using the provided credentials."
  {:malli/schema [:=> [:cat oauth2-params]
                  :any]}
  [{:keys [url method client-id client-secret scope grant-type auth-data as keywordize headers basic-auth]
    :or   {as :json keywordize true method :post}}]
  (make-request
   (utils/assoc-some {:url         url
                      :method      method
                      :form-params (->> (utils/assoc-some {}
                                          :client_id client-id
                                          :client_secret client-secret
                                          :grant_type grant-type
                                          :scope (->scope scope))
                                        (merge auth-data))
                      :as          as
                      :keywordize  keywordize}
     :basic-auth basic-auth
     :headers headers)))


(defmethod action/action-fn ::oauth2 [_]
  get-oauth2-token)