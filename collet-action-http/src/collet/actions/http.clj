(ns collet.actions.http
  (:require
   [charred.api :as charred]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [collet.action :as action]
   [collet.arrow :as arrow]
   [collet.conditions :as conditions]
   [collet.select :as select]
   [collet.utils :as utils]
   [diehard.core :as dh]
   [diehard.rate-limiter :as rl]
   [hato.client :as hato]
   [malli.core :as m]
   [tech.v3.dataset :as ds]
   [tech.v3.resource :as resource])
  (:import
   [clojure.lang Counted SeqIterator Seqable Sequential]
   [java.io Closeable IOException InputStream Reader StringReader]
   [java.lang.ref Reference]
   [java.net URI]
   [java.nio.charset StandardCharsets]
   [java.nio.file Files Path]
   [java.nio.file.attribute FileAttribute]
   [java.time ZonedDateTime]
   [java.time.format DateTimeFormatter]
   [java.util.concurrent Callable ExecutionException Executors TimeUnit TimeoutException]))


(defn read-json
  "Reads a JSON object from an input stream and optionally keywordizes keys."
  [input keywordize]
  (when (some? input)
    (with-open [^Reader rdr (if (string? input)
                              (StringReader. input)
                              (io/reader input))]
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
                        (some-> (:body response)
                                (read-json true)
                                :error
                                :message))]
          (throw (ex-info (str "Request failed. " error)
                          {:status  (:status response)
                           :error   error
                           :request req})))
        response))))


(def http-request
  (-> hato/request
      (wrap-rate-limiter)
      (wrap-unexceptional-status)))


(def ^:private ^:dynamic *transport* nil)


(def auth-spec
  [:multi {:dispatch :type}
   [:basic [:map [:username :string] [:password :string]]]
   [:bearer [:map [:value :string]]]
   [:api-key [:map [:name :string] [:value :string] [:in {:optional true} [:enum :header :query]]]]
   [:oauth2
    [:map
     [:token-url :string] [:client-id :string] [:client-secret :string]
     [:refresh-token {:optional true} :string]
     [:grant-type {:optional true} [:enum :refresh_token :client_credentials]]
     [:scope {:optional true} [:or :string [:vector :string]]]]]])


(def pagination-spec
  [:map
   [:mode [:enum :page :offset :body-cursor :header-cursor :body-url :link]]
   [:items-path {:optional true} select/select-path]
   [:next-path {:optional true} select/select-path]
   [:next-header {:optional true} :string]
   [:cursor-param {:optional true} [:vector :any]]
   [:start {:optional true} :int]
   [:step {:optional true} pos-int?]
   [:max-pages {:optional true} pos-int?]
   [:until {:optional true} conditions/condition?]
   [:continuation {:optional true} map?]])


(def retry-spec
  [:map [:max-retries {:optional true} nat-int?]
   [:statuses {:optional true} [:set :int]]
   [:backoff-ms {:optional true} nat-int?]
   [:max-delay-ms {:optional true} nat-int?]
   [:transport? {:optional true} :boolean]])


(def request-params-spec
  [:map
   [:url
    [:or :string
     [:catn [:template-string :string] [:substitution [:* :any]]]]]
   [:method {:optional true :default :get}
    [:enum :get :post :put :delete :head :options :trace]]
   [:body {:optional true} :any]
   [:auth {:optional true} auth-spec]
   [:pagination {:optional true} pagination-spec]
   [:retry {:optional true} retry-spec]
   [:empty-body {:optional true} [:enum :nil :error]]
   [:response-spec {:optional true} :any]
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
  [{:keys [url method body keywordize as content-type accept basic-auth]
    :or   {method :get keywordize true}
    :as   req-map}]
  (let [request  (cond-> req-map
                   (vector? basic-auth) (assoc :basic-auth {:user (first basic-auth) :pass (second basic-auth)})
                   (vector? url) (assoc :url (apply format url))
                   (= as :json) (assoc :as :stream)
                   (= accept :json) (assoc-in [:headers "Accept"] "application/json")
                   (= content-type :json) (assoc-in [:headers "Content-Type"] "application/json")
                   ;; coerce body to a JSON string if it's not yet
                   (and (= content-type :json)
                        (some? body)
                        (not (string? body)))
                   (assoc :body (charred/write-json-str body))

                   :always (assoc :method
                                  method
                                  :throw-exceptions
                                  false
                                  :http-client
                                  (or (:http-client req-map) {:redirect-policy :normal})))
        response ((or *transport* http-request) request)]
    (cond-> response
      (= as :json) (update :body read-json keywordize))))


(defn attach-rate-limiter
  "Attaches a rate limiter to the action spec if a rate is provided."
  [action-spec]
  (let [rate (get-in action-spec [:params :rate])]
    (cond-> action-spec
      (some? rate) (assoc-in [:params ::rate-limiter] (rl/rate-limiter {:rate rate})))))


(defmethod action/prep ::request
  [action-spec]
  (cond-> (attach-rate-limiter action-spec)
    (some #(contains? (:params action-spec) %) [:auth :basic-auth :headers])
    (assoc :collet/sensitive? true)))


(defn ->scope [scope]
  (cond->> scope (sequential? scope) (string/join " ")))


(def oauth2-params
  [:map
   [:url
    [:or :string
     [:catn [:template-string :string] [:substitution [:* :any]]]]]
   [:method {:optional true :default :post} [:enum :get :post]]
   [:client-id {:optional true} :string]
   [:client-secret {:optional true} :string]
   [:scope {:optional true} [:or :string [:+ :string]]]
   [:grant-type {:optional true} :string]
   [:auth-data {:optional true} map?]
   [:basic-auth {:optional true} [:tuple :string :string]]
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


(defmethod action/action-fn ::oauth2
  [_]
  get-oauth2-token)


(def ^:private oauth-sessions (atom {}))


(defn- failure [code message]
  (ex-info message {:code code :collet/redact-params true}))


(defn- origin [url]
  (let [uri    (URI. url)
        scheme (some-> (.getScheme uri)
                       string/lower-case)
        port   (.getPort uri)]
    (when-not (and (#{"http" "https"} scheme) (.getHost uri) (nil? (.getUserInfo uri)))
      (throw (failure "invalid-http-url" "Expected an HTTP(S) URL without user info.")))
    [scheme (string/lower-case (.getHost uri))
     (if (neg? port) (if (= scheme "https") 443 80) port)]))


(defn- header [response k]
  (some (fn [[name value]]
          (when (= (string/lower-case (clojure.core/name name)) (string/lower-case (clojure.core/name k)))
            (if (sequential? value) (first value) value)))
        (:headers response)))


(defn- close-body! [response]
  (when (instance? Closeable (:body response))
    (.close ^Closeable (:body response))))


(defn- decode-response [options response]
  (let [{:keys [as keywordize empty-body response-spec] :or {keywordize true}} options
        decoded (if (= as :json)
                  (try
                    (let [body    (:body response)
                          content (cond (bytes? body) (String. ^bytes body StandardCharsets/UTF_8)
                                        (instance? InputStream body) (with-open [in body] (slurp in :encoding "UTF-8"))
                                        :else body)]
                      (when-not (string/blank? content)
                        (read-json content keywordize)))
                    (catch Exception _
                      (throw (failure "invalid-json" "HTTP response contains malformed JSON."))))
                  (:body response))
        empty?  (or (nil? decoded) (= "" decoded) (and (bytes? decoded) (zero? (alength ^bytes decoded))))
        body    (if empty?
                  (case empty-body
                    :error (throw (failure "empty-http-body" "Expected a nonempty HTTP response."))
                    :nil nil
                    decoded)
                  decoded)]
    (when response-spec
      (when-not (m/validate (m/schema response-spec {:disable-sci true}) body)
        (close-body! response)
        (throw (failure "invalid-http-response" "HTTP body does not match :response-spec."))))
    (assoc (select-keys response [:status :headers]) :body body)))


(def ^:private all-http-statuses (set (range 100 600)))


(defn- raw-request [{:keys [as] :as options}]
  (let [req (-> options
                (dissoc :auth :pagination :retry :empty-body :response-spec :poll)
                (assoc :http-client {:redirect-policy :never}
                       :unexceptional-status all-http-statuses)
                (cond-> (= :json as) (assoc :as :byte-array)))]
    (make-request req)))


(defn- oauth-token! [{:keys [token-url grant-type client-id client-secret refresh-token scope] :as auth} timeout rejected]
  (let [key     (select-keys auth [:token-url :grant-type :client-id :client-secret :scope :refresh-token])
        session (or (get @oauth-sessions key)
                    (get (swap! oauth-sessions #(if (contains? % key) % (assoc % key (atom nil)))) key))]
    (locking session
      (let [{:keys [token expires-at refresh] :as prior} @session
            now (System/currentTimeMillis)]
        (if (and token (< now expires-at) (not (identical? rejected prior)))
          prior
          (let [response (raw-request {:url         token-url
                                       :method      :post
                                       :as          :json
                                       :timeout     timeout
                                       :form-params (utils/assoc-some
                                                      {:grant_type (name (or grant-type :refresh_token))}
                                                      :client_id client-id
                                                      :client_secret client-secret
                                                      :refresh_token (or refresh refresh-token)
                                                      :scope (->scope scope))})
                _        (when-not (<= 200 (:status response) 299)
                           (close-body! response)
                           (throw (failure "oauth-token-failed" "OAuth token exchange failed.")))
                {:keys [access_token expires_in refresh_token]} (:body (decode-response {:as :json} response))]
            (when-not (and (string? access_token) (not (string/blank? access_token)) (pos-int? expires_in))
              (throw (failure "invalid-oauth-token" "OAuth response must contain a token and expiry.")))
            (reset! session {:token      access_token
                             :refresh    (or refresh_token refresh refresh-token)
                             :expires-at (+ now (* 1000 (max 0 (- expires_in 30))))})))))))


(defn- authorize [request token]
  (let [{:keys [type username password value name in]} (:auth request)]
    (case type
      nil request
      :basic (assoc request :basic-auth [username password])
      :api-key (assoc-in request [(if (= in :query) :query-params :headers) name] value)
      (:oauth2 :bearer) (-> request
                            (update :headers
                                    (fn [headers]
                                      (into {} (remove #(= "authorization" (string/lower-case (clojure.core/name (key %))))) headers)))
                            (assoc-in [:headers "Authorization"] (str "Bearer " (or (:token token) value))))
      (throw (failure "invalid-http-auth" "Unknown HTTP authentication type.")))))


(defn- retry-after-ms [response]
  (when-let [value (header response "retry-after")]
    (or (when-let [seconds (parse-long value)] (* 1000 (max 0 seconds)))
        (try (max 0
                  (- (.toEpochMilli (.toInstant (ZonedDateTime/parse value DateTimeFormatter/RFC_1123_DATE_TIME)))
                     (System/currentTimeMillis)))
             (catch Exception _ nil)))))


(defn- request-once [{:keys [retry auth timeout unexceptional-status] :or {timeout 30000} :as options}]
  (let [{:keys [max-retries statuses backoff-ms max-delay-ms transport?]
         :or   {max-retries 0 statuses #{429 500 502 503 504} backoff-ms 250 max-delay-ms 30000}} retry
        oauth? (= :oauth2 (:type auth))]
    (loop [retries  0
           rejected nil]
      (let [token    (when oauth? (oauth-token! auth timeout rejected))
            response (try (raw-request (authorize (assoc options :timeout timeout) token))
                          (catch IOException e e))]
        (cond
          (and oauth? (nil? rejected) (= 401 (:status response)))
          (do (close-body! response) (recur retries token))

          (and (< retries max-retries)
               (or (statuses (:status response)) (and transport? (instance? IOException response))))
          (let [wait (or (retry-after-ms response)
                         (long (min max-delay-ms (* backoff-ms (Math/pow 2 retries)))))]
            (close-body! response)
            (when (> wait max-delay-ms)
              (throw (failure "http-retry-delay" "Retry-After exceeds the configured delay limit.")))
            (Thread/sleep wait)
            (recur (inc retries) rejected))

          (instance? IOException response)
          (throw (failure "http-transport-failed" "HTTP transport failed."))

          ((or unexceptional-status unexceptional-status?) (:status response))
          (decode-response options response)

          :else
          (do (close-body! response)
              (throw (ex-info (str "HTTP request failed with status " (:status response) ".")
                              {:code "http-status" :status (:status response) :collet/redact-params true}))))))))


(defn- link-next [response]
  (let [links (->> (:headers response)
                   (filter #(= "link" (string/lower-case (name (key %)))))
                   (mapcat #(let [value (val %)] (if (sequential? value) value [value])))
                   (string/join ","))]
    (some (fn [[_ url params]]
            (when-let [[_ quoted unquoted] (re-find #";\s*rel\s*=\s*(?:\"([^\"]+)\"|([^;,\s]+))" params)]
              (when (some #{"next"} (string/split (or quoted unquoted) #"\s+"))
                url)))
          (re-seq #"<([^>]+)>([^<]*)" links))))


(defn- continuation-url [current next]
  (if (string/starts-with? next "?")
    (str (first (string/split current #"[?#]" 2)) next)
    (str (.resolve (URI. current) ^String next))))


(defn- url-string [url]
  (if (vector? url) (apply format url) url))


(def ^:private page-columns
  ;; JSON rows may change shape or have nested, null or mixed-type values.
  {:version arrow/schema-version
   :fields  [{:key :record-json :name "record-json" :type :string}
             {:key :keywordize :name "keywordize" :type :boolean}]})


(defn- page-path [^Path directory index]
  (.resolve directory (str index ".arrow")))


(defn- write-page! [directory index rows keywordize]
  (with-open [writer (arrow/make-writer (page-path directory index) page-columns)]
    (arrow/write writer
                 (mapv (fn [row]
                         {:record-json (charred/write-json-str row)
                          :keywordize  keywordize})
                       rows))))


(defn- read-page [directory index]
  (let [batches (arrow/read-arrow-record-batches (page-path directory index) page-columns)]
    (try
      (ds/->dataset (mapv #(read-json (:record-json %) (:keywordize %)) (first batches)))
      (finally ((:collet/close (meta batches)))))))


(defonce ^:private page-directories (atom #{}))


(defn- delete-pages! [^Path directory]
  (with-open [paths (Files/newDirectoryStream directory)]
    (run! #(Files/deleteIfExists ^Path %) paths))
  (Files/deleteIfExists directory)
  (swap! page-directories disj directory))


(defonce ^:private page-cleanup-on-exit
  (delay
   (.addShutdownHook (Runtime/getRuntime)
                     (Thread. (fn []
                                (doseq [directory @page-directories]
                                  (try (delete-pages! directory)
                                       (catch IOException _))))
                              "collet-http-page-cleanup"))))


(defn- file-backed-batches [directory size]
  (let [closed? (atom false)
        close!  (fn []
                  (locking closed?
                    (when-not @closed?
                      (delete-pages! directory)
                      (reset! closed? true))))
        owner   (resource/track (Object.) {:track-type :gc :dispose-fn close!})
        load!   (fn [index]
                  (try
                    (locking closed?
                      (when @closed?
                        (throw (failure "http-pagination-closed" "The pagination batches have been closed.")))
                      (read-page directory index))
                    ;; An in-flight sequence, even without the response envelope, owns the files.
                    (finally (Reference/reachabilityFence owner))))
        step    (fn step [index]
                  (lazy-seq
                   (when (< index size)
                     (cons (load! index) (step (inc index))))))]
    (with-meta
     (reify
      Sequential
      Seqable
      (seq [_] (seq (step 0)))

      Counted
      (count [_] size)

      Iterable
      (iterator [_] (SeqIterator. (step 0)))

      Closeable
      (close [_] (close!)))
     {:ds-seq       true
      :collet/close close!})))


(defn- paginated-request [{:keys [pagination] :as initial}]
  (let [{:keys [mode items-path next-path next-header cursor-param start step max-pages until continuation]
         :or   {items-path [:body] start 1 step 1 max-pages 100}} pagination
        url        (url-string (:url initial))
        base       (origin url)
        template   (merge initial continuation)
        numeric?   (#{:page :offset} mode)
        next-param (or cursor-param
                       [:query-params
                        (case mode
                          :offset "offset"
                          :page "page"
                          "cursor")])
        done?      (when until (conditions/compile-conditions until))]
    (when-not (and (pos-int? max-pages)
                   (pos-int? step)
                   (#{:page :offset :body-cursor :header-cursor :body-url :link} mode))
      (throw (failure "invalid-pagination" "Invalid HTTP pagination strategy or limits.")))
    @page-cleanup-on-exit
    (let [directory (Files/createTempDirectory "collet-http-pages-" (make-array FileAttribute 0))]
      (swap! page-directories conj directory)
      (try
        (loop [request (cond-> initial numeric? (assoc-in next-param start))
               page    1
               number  start
               batches 0]
          (when-not (= base (origin (url-string (:url request))))
            (throw (failure "http-origin-changed" "Pagination changed the request origin.")))
          (let [response (request-once request)
                [next finished? batch-count]
                (try
                  (let [rows (or (select/select items-path response) [])
                        _    (when-not (and (sequential? rows) (every? map? rows))
                               (throw (failure "invalid-http-page" "Pagination items must be a sequence of records.")))
                        next (case mode
                               (:page :offset) (+ number step)
                               (:body-cursor :body-url) (select/select next-path response)
                               :header-cursor (header response next-header)
                               :link (link-next response))]
                    [next
                     (if done? (boolean (done? response)) (if numeric? (empty? rows) (nil? next)))
                     (if (seq rows)
                       (do (write-page! directory batches rows (get request :keywordize true)) (inc batches))
                       batches)])
                  (finally (close-body! response)))]
            (cond
              finished? (assoc response
                          :body (if (zero? batch-count)
                                  (do (delete-pages! directory) (with-meta [] {:ds-seq true}))
                                  (file-backed-batches directory batch-count)))
              (>= page max-pages) (throw (failure "http-page-limit" "Pagination exceeded its page limit."))
              (nil? next) (throw (failure "missing-http-cursor" "Pagination stopped before its termination condition."))
              :else (recur (if (#{:body-url :link} mode)
                             (assoc template :url (continuation-url (url-string (:url request)) next))
                             (assoc-in template next-param next))
                           (inc page)
                           (if numeric? next number)
                           batch-count))))
        (catch Throwable error
          (try (delete-pages! directory)
               (catch Exception cleanup-error (.addSuppressed error cleanup-error)))
          (throw error))))))


(defn request [options]
  (if (some #(contains? options %) [:auth :pagination :retry :empty-body :response-spec])
    (try
      (when-not (m/validate request-params-spec options)
        (throw (failure "invalid-http-options" "Invalid HTTP request parameters.")))
      (origin (url-string (:url options)))
      (if (:pagination options) (paginated-request options) (request-once options))
      (catch InterruptedException e (throw e))
      (catch Exception e
        (throw (if (:collet/redact-params (ex-data e))
                 e
                 (failure "http-request-failed" "HTTP request failed.")))))
    (make-request options)))


(defn request-with-transport
  "Run the request action with an invocation-local substitute for the HTTP client."
  [options transport]
  (binding [*transport* transport]
    (request options)))


(defn poll [{:keys [poll] :as options}]
  (let [{:keys [until fail-when interval-ms deadline-ms] :or {interval-ms 1000 deadline-ms 120000}} poll
        _         (when-not (and (conditions/valid-condition? until)
                                 (or (nil? fail-when) (conditions/valid-condition? fail-when))
                                 (pos-int? interval-ms)
                                 (pos-int? deadline-ms))
                    (throw (failure "invalid-http-poll" "Polling requires a condition and positive interval and deadline.")))
        complete? (conditions/compile-conditions until)
        failed?   (when fail-when (conditions/compile-conditions fail-when))
        executor  (Executors/newVirtualThreadPerTaskExecutor)
        job       (.submit executor
                           ^Callable
                           (fn []
                             (loop []
                               (let [response (request (assoc options :retry (or (:retry options) {})))]
                                 (cond
                                   (and failed? (failed? response))
                                   (do (close-body! response)
                                       (throw (failure "http-poll-failed" "HTTP poll reached its failure condition.")))
                                   (complete? response) response
                                   :else (do (close-body! response) (Thread/sleep interval-ms) (recur)))))))]
    (try
      (.get job deadline-ms TimeUnit/MILLISECONDS)
      (catch TimeoutException _
        (throw (failure "http-poll-timeout" "HTTP poll exceeded its deadline.")))
      (catch ExecutionException e (throw (.getCause e)))
      (finally (.cancel job true) (.shutdownNow executor)))))


(defmethod action/action-fn ::request [_] request)


(defmethod action/action-fn ::poll [_] poll)


(defmethod action/prep ::poll
  [spec]
  (assoc (action/prep (assoc spec :type ::request)) :type ::poll))
