(ns collet.workbench.http
  (:require [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [collet.workbench :as wb]
            [collet.workbench.api :as api]
            [collet.workbench.authoring :as authoring]
            [collet.workbench.contracts :as contracts]
            [collet.workbench.data :as data]
            [collet.workbench.fixtures :as fixtures]
            [collet.workbench.inspection :as inspection]
            [collet.workbench.knowledge :as knowledge]
            [collet.workbench.mcp :as mcp]
            [ring.adapter.jetty :as jetty]
            [ring.core.protocols :as ring])
  (:import [java.io ByteArrayInputStream]
           [java.net InetAddress URLDecoder URI]
           [java.util UUID]
           [org.eclipse.jetty.server HttpConnectionFactory]
           [org.eclipse.jetty.http UriCompliance]
           [java.util.concurrent ExecutorService]))


(defn- response [status content-type body]
  {:status status :headers {"Content-Type" content-type "Cache-Control" "no-store" "X-Content-Type-Options" "nosniff"} :body body})


(defn- json-response [value]
  (response 200 "application/json; charset=utf-8" (json/write-json-str value)))


(defn- error-response [error]
  (response (or (:status (data/error-details error)) 400)
            "application/json; charset=utf-8"
            (json/write-json-str {:error (data/error-data error)})))


(defn- bounded-body [request]
  (if-let [in (:body request)]
    (let [bytes (.readNBytes ^java.io.InputStream in 524289)]
      (when (> (count bytes) 524288) (wb/fail! "Request exceeds 512 KiB." 413))
      bytes)
    (byte-array 0)))


(defn- body [request]
  (when-not (= "1" (get-in request [:headers "x-collet-workbench"])) (wb/fail! "Workbench request header required." 403))
  (let [bytes (bounded-body request)
        value (if (zero? (count bytes)) {} (json/read-json (String. bytes java.nio.charset.StandardCharsets/UTF_8) :key-fn keyword))]
    (when-not (map? value) (wb/fail! "Request must be a JSON object." 400))
    value))


(defn- query-options [request]
  (into {}
        (map (fn [pair]
               (let [[k v]  (str/split pair #"=" 2)
                     k      (keyword (URLDecoder/decode k "UTF-8"))
                     v      (URLDecoder/decode (or v "") "UTF-8")
                     bounds ({:waitMs [0 10000] :afterSequence [0 Long/MAX_VALUE] :limit [1 100] :maxBytes [4096 65536]} k)]
                 (if bounds
                   (let [n (parse-long v)]
                     (when-not (and n (<= (first bounds) n (second bounds))) (wb/fail! "Invalid query limit." 400))
                     [k n])
                   [k v]))))
        (remove str/blank? (str/split (or (:query-string request) "") #"&"))))


(defn- route [app method parts value]
  (let [[group id operation arg index] parts]
    (case [method group]
      [:get "health"] {:status "ok" :instanceId (:instance-id app) :mcpUrl (str @(:base-url app) "/mcp")}
      [:get "catalog"] (assoc (wb/catalog app)
                         :drafts (:drafts (authoring/list-drafts app 100))
                         :instanceId (:instance-id app))
      [:get "drafts"] (api/drafts app value)
      [:get "fixtures"] (if operation (fixtures/get-case app id operation) (fixtures/list-cases app id))
      [:post "fixtures"] (fixtures/put-case! app id value)
      [:get "pipelines"] (api/pipelines app value)
      [:post "pipelines"] (if (= operation "delete")
                            (do (contracts/check! (if (:version value) "delete_pipeline_version" "delete_pipeline") (assoc value :name id))
                                (api/delete-pipeline! app
                                                      id
                                                      (if (:version value)
                                                        {:version (:version value)}
                                                        {:latest-version (:latestVersion value)})))
                            (wb/fail! "Endpoint not found." 404))
      [:get "actions"] (if id (knowledge/describe id) (knowledge/list-actions value))
      [:get "documentation"] (knowledge/documentation id)
      [:get "schemas"] (knowledge/schemas)
      [:post "validate"] (let [result (knowledge/check value)]
                           (if (:valid result)
                             result
                             (throw (ex-info "Validation failed." (assoc result :status 400)))))
      [:post "format"] {:edn (data/format-edn (:edn value))}
      [:post "inspect"] (authoring/inspect! app value)
      [:get "definitions"] (wb/load-definition app id operation)
      [:post "definitions"] (if (= arg "run")
                              (authoring/execute! app (:id value) (merge value {:scope "pipeline" :pipeline id :version (parse-long operation)}))
                              (wb/fail! "Endpoint not found." 404))
      [:get "sessions"] (case operation
                          ("source" "configuration") (authoring/browser-text app id operation)
                          "history" (if arg (authoring/revision app id (parse-long arg)) (authoring/history app id 100))
                          "inputs" (if-let [i (some-> index
                                                      parse-long)]
                                     (authoring/inspect-context app id {:task-name arg :index i})
                                     (wb/fail! "Action index must be an integer." 409))
                          (authoring/browser-view app id value))
      [:post "sessions"] (if-not id
                           (authoring/create! app value)
                           (case operation
                             "source" (authoring/update! app id value)
                             "configuration" (authoring/configuration! app id value)
                             "definition" (let [_ (when-not (= (:configurationRevision value) (:configurationRevision (authoring/view app id)))
                                                    (wb/fail! "Configuration changed or its expected revision is missing." 409))
                                                v (authoring/update! app id value)
                                                v (if (:configEdn value)
                                                    (authoring/configuration! app id (assoc value :configurationRevision (:configurationRevision value)))
                                                    v)]
                                            (authoring/prepare! app id {:revision (:revision v) :configurationRevision (:configurationRevision v)}))
                             "apply" (authoring/prepare! app id value)
                             "execute" (authoring/execute! app id (assoc value :scope (or (:scope value) (:mode value))))
                             "restore" (authoring/restore! app id value)
                             "delete" (authoring/delete! app id (:revision value))
                             "reset" (do (when (contains? @(:sessions app) (UUID/fromString id)) (wb/reset-session! app id)) (authoring/view app id))
                             "close" (authoring/close-scratch! app id)
                             "save" (authoring/save! app id value)
                             "run" (let [v (authoring/view app id)
                                         _ (when-not (= (:revision value) (:revision v)) (wb/fail! "Draft changed." 409))]
                                     (authoring/execute! app
                                                         id
                                                         (merge value
                                                                {:scope    "pipeline"
                                                                 :pipeline (get-in v [:document :name])
                                                                 :version  (get-in v [:document :version])})))
                             (wb/fail! "Endpoint not found." 404)))
      [:get "executions"] (authoring/get-execution app id value)
      [:post "executions"] (authoring/execute! app (:id value) value)
      [:get "runs"] (case operation
                      ("source" "configuration") (wb/run-text app id operation)
                      "outputs" (wb/output app id arg)
                      "diagnostics" (inspection/read-run app id value)
                      (wb/run-view app id value))
      [:post "runs"] (wb/control! app id operation)
      (wb/fail! "Endpoint not found." 404))))


(defn- event-body [app]
  (reify
   ring/StreamableResponseBody
   (write-body-to-stream [_ _ out]
     (.submit ^ExecutorService (:authoring-executor app)
              ^Runnable
              (fn []
                (try
                  (with-open [writer (io/writer out)]
                    (.write writer "event: snapshot\ndata: {}\n\n")
                    (.flush writer)
                    (loop [sequence (:sequence @(:events app))
                           ticks    0]
                      (when-not @(:closed? app)
                        (let [{current :sequence items :items} @(:events app)
                              events (filterv #(> (:sequence %) sequence) items)]
                          (when (seq events) (.write writer (str "id: " current "\nevent: updates\ndata: " (json/write-json-str events) "\n\n")))
                          (when (zero? (mod ticks 40)) (.write writer ": heartbeat\n\n"))
                          (.flush writer)
                          (Thread/sleep 250)
                          (recur current (inc ticks))))))
                  (catch java.io.IOException _ nil)
                  (catch InterruptedException _ (.interrupt (Thread/currentThread)))))))))


(defn- static [path]
  (let [path     (if (= path "/") "/index.html" path)
        resource (when-not (or (str/includes? path "..") (str/includes? path "\\")) (io/resource (str "collet/workbench" path)))]
    (if resource
      (response 200
                (cond (str/ends-with? path ".html") "text/html; charset=utf-8"
                      (str/ends-with? path ".js") "text/javascript; charset=utf-8"
                      (str/ends-with? path ".css") "text/css; charset=utf-8"
                      (str/ends-with? path ".svg") "image/svg+xml"
                      :else "application/octet-stream")
                (io/input-stream resource))
      (response 404 "text/plain" "Workbench assets not found. Build collet-app, or use the Vite development server."))))


(defn- loopback-uri? [value]
  (try (let [uri (URI/create value)]
         (and (contains? #{"http" "https"} (.getScheme uri))
              (contains? #{"127.0.0.1" "localhost" "[::1]" "::1"} (.getHost uri))))
       (catch Exception _ false)))


(defn- configure-jetty! [server]
  ;; Collet names are qualified keywords, encoded as one URL segment by clients.
  (doseq [connector (.getConnectors server)
          factory   (.getConnectionFactories connector)
          :when     (instance? HttpConnectionFactory factory)]
    (.setUriCompliance (.getHttpConfiguration ^HttpConnectionFactory factory)
                       (UriCompliance/from "DEFAULT,AMBIGUOUS_PATH_SEPARATOR"))))


(defn start! [{:keys [port host] :or {port 8081 host "127.0.0.1"} :as options}]
  (when-not (.isLoopbackAddress (InetAddress/getByName host)) (wb/fail! "Workbench only binds to loopback addresses." 400))
  (let [app     (wb/open! (dissoc options :port :host))
        mcp     (mcp/create app)
        handler (fn [request respond _raise]
                  (try
                    (when-not (loopback-uri? (str "http://" (get-in request [:headers "host"])))
                      (wb/fail! "Workbench requires a loopback Host header." 403))
                    (when-let [origin (get-in request [:headers "origin"])]
                      (when-not (loopback-uri? origin) (wb/fail! "Workbench requires a loopback Origin." 403)))
                    (let [path   (:uri request)
                          method (:request-method request)]
                      (cond
                        (= path "/mcp") ((:handler mcp)
                                         (if (= method :post) (assoc request :body (ByteArrayInputStream. (bounded-body request))) request)
                                         respond
                                         #(respond (error-response %)))
                        (= path "/api/events") (respond (response 200 "text/event-stream" (event-body app)))
                        (str/starts-with? path "/api/")
                        (respond (json-response (route app
                                                       method
                                                       (mapv #(URLDecoder/decode % "UTF-8") (str/split (subs path 5) #"/"))
                                                       (if (= method :post)
                                                         (body request)
                                                         (cond-> (query-options request)
                                                           (= "overview" (get-in request [:headers "x-collet-view"]))
                                                           (assoc :overview?
                                                             true
                                                             :task-name
                                                             (some-> (get-in request [:headers "x-collet-task"])
                                                                     (URLDecoder/decode "UTF-8"))))))))
                        (= method :get) (respond (static path))
                        :else (wb/fail! "Method not allowed." 405)))
                    (catch Throwable e (respond (error-response e)))))]
    (try
      (let [server   (jetty/run-jetty handler {:port port :host host :join? false :async? true :async-timeout 0 :configurator configure-jetty!})
            stopped? (atom false)]
        (reset! (:base-url app) (str "http://" host ":" (.getLocalPort (first (.getConnectors server)))))
        (assoc app
          :server server
          :port (.getLocalPort (first (.getConnectors server)))
          :stop (fn []
                  (when (compare-and-set! stopped? false true)
                    ((:close mcp))
                    (wb/close! app)
                    (.stop server)))))
      (catch Throwable e ((:close mcp)) (wb/close! app) (throw e)))))
