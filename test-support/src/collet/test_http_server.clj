(ns collet.test-http-server
  (:require
   [charred.api :as charred]
   [clojure.string :as string])
  (:import
   [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
   [java.net InetSocketAddress URLDecoder]
   [java.nio.charset StandardCharsets]))


(defonce ^:private base-url (atom nil))


(defn url
  [path]
  (str @base-url path))


(defn musicbrainz-url
  [path]
  (url (str "/ws/2" path)))


(defn odata-url
  []
  (url "/odata"))


(defn- decode-component
  [value]
  (URLDecoder/decode ^String value StandardCharsets/UTF_8))


(defn- query-params
  [^HttpExchange exchange]
  (let [query (some-> exchange .getRequestURI .getRawQuery)]
    (if (string/blank? query)
      {}
      (->> (string/split query #"&")
           (map #(string/split % #"=" 2))
           (map (fn [[key value]]
                  [(decode-component key)
                   (decode-component (or value ""))]))
           (into {})))))


(defn- int-param
  [params key default-value]
  (or (some-> (get params key) parse-long)
      default-value))


(defn- event
  [index]
  {:id        (str "event-" index)
   :name      (str "Concert " index)
   :relations [{:type   "main performer"
                :artist {:id        (str "artist-" index)
                         :name      (str "Artist " index)
                         :sort-name (str "Artist " index)}}]})


(def airports
  (mapv (fn [index]
          {"IcaoCode" (format "TEST%02d" index)
           "Name"     (str "Airport " index)})
        (range 10)))


(defn- json-response
  [body]
  {:status  200
   :headers {"Content-Type" "application/json"}
   :body    (charred/write-json-str body)})


(defn- musicbrainz-response
  [path params]
  (cond
    (= path "/ws/2/genre/all")
    (json-response {:genre-count 2
                    :genres      [{:id "rock" :name "Rock"}
                                  {:id "jazz" :name "Jazz"}]})

    (= path "/ws/2/artist")
    (let [limit  (int-param params "limit" 5)
          offset (int-param params "offset" 0)]
      (json-response {:offset  offset
                      :artists (mapv (fn [index]
                                       {:id   (str "artist-" index)
                                        :name (str "Artist " index)})
                                     (range offset (+ offset limit)))}))

    (string/starts-with? path "/ws/2/artist/")
    (let [artist-id (last (string/split path #"/"))]
      (json-response {:id     artist-id
                      :name   (str "Artist " artist-id)
                      :rating {:value 4.0}}))

    (= path "/ws/2/area")
    (json-response {:areas [{:id "area-london" :name "London"}]})

    (= path "/ws/2/event")
    (let [limit  (int-param params "limit" 10)
          offset (int-param params "offset" 0)]
      (json-response {:offset offset
                      :events (mapv event (range offset (+ offset limit)))
                      :event-count 50}))

    :else nil))


(defn- odata-response
  [path params]
  (cond
    (= path "/odata/People/$count")
    {:status 200 :headers {"Content-Type" "text/plain"} :body "3"}

    (= path "/odata/People")
    (json-response {"@odata.count" 3
                    "value" [{"FirstName" "Alice"
                               "LastName" "One"
                               "AddressInfo" [{"City" {"Name" "Berlin"}}]}
                              {"FirstName" "Bob"
                               "LastName" "Two"
                               "AddressInfo" [{"City" {"Name" "Boston"}}]}
                              {"FirstName" "Carol"
                               "LastName" "Three"
                               "AddressInfo" [{"City" {"Name" "Brussels"}}]}]})

    (= path "/odata/Airports/$count")
    {:status 200 :headers {"Content-Type" "text/plain"} :body (str (count airports))}

    (= path "/odata/Airports")
    (let [skip      (int-param params "$skip" 0)
          requested (some-> (get params "$top") parse-long)
          page-size (or requested 8)
          values    (->> airports (drop skip) (take page-size) vec)
          next-skip (+ skip (count values))]
      (json-response
       (cond-> {"value" values}
         (and (nil? requested) (< next-skip (count airports)))
         (assoc "@odata.nextLink"
                (str (odata-url) "/Airports?$skip=" next-skip)))))

    :else nil))


(defn- response-for
  [^HttpExchange exchange]
  (let [path   (some-> exchange .getRequestURI .getPath)
        params (query-params exchange)]
    (or (musicbrainz-response path params)
        (odata-response path params)
        (case path
          "/status/204" {:status 204 :headers {} :body ""}
          "/status/503" {:status 503
                         :headers {"Content-Type" "application/json"}
                         :body (charred/write-json-str {:error {:message "unavailable"}})}
          {:status 404 :headers {"Content-Type" "text/plain"} :body "not found"}))))


(defn- send-response!
  [^HttpExchange exchange {:keys [status headers body]}]
  (let [body-bytes (.getBytes ^String body StandardCharsets/UTF_8)]
    (doseq [[header value] headers]
      (.add (.getResponseHeaders exchange) header value))
    (.sendResponseHeaders exchange status (if (= status 204) -1 (alength body-bytes)))
    (when-not (= status 204)
      (with-open [stream (.getResponseBody exchange)]
        (.write stream body-bytes)))))


(defn with-server
  [test]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (send-response! exchange (response-for exchange)))))
    (.setExecutor server nil)
    (.start server)
    (reset! base-url
            (str "http://127.0.0.1:" (.getPort (.getAddress server))))
    (try
      (test)
      (finally
        (reset! base-url nil)
        (.stop server 0)))))
