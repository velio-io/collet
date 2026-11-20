(ns collet.dash-fixture
  (:require [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.awt.image BufferedImage]
           [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.net URLDecoder]
           [javax.imageio ImageIO]))


(defn images []
  (mapv (fn [format]
          (let [output (ByteArrayOutputStream.)]
            (ImageIO/write (BufferedImage. 2 3 BufferedImage/TYPE_INT_RGB) format output)
            {:id        (str "asset-" format)
             :file-id   (str "file-" format)
             :file-name (str "quoted \"fixture\"." format)
             :format    format
             :bytes     (.toByteArray output)}))
        ["png" "jpeg"]))


(defn- form [body]
  (into {}
        (map (fn [pair]
               (let [[key value] (str/split pair #"=" 2)]
                 [(keyword (URLDecoder/decode key "UTF-8")) (URLDecoder/decode (or value "") "UTF-8")]))
             (str/split (slurp body) #"&"))))


(defn handler
  "A documented Dash v2 fixture, shared by unit and standalone HTTP integration tests."
  [assets observed
   {:keys [fail-job? fail-item? oversized? expired-tokens? unauthorized-once? rate-limit-once?]
    :or   {expired-tokens? true}}]
  (let [tokens   (atom 0)
        denied?  (atom false)
        polls    (atom {})
        jobs     (atom {})
        response (fn [status body] {:status status :headers {"Content-Type" "application/json"} :body (json/write-json-str body)})
        result   (fn [asset]
                   {:result           {:id               (:id asset)
                                       :accountId        "account-1"
                                       :lifecycleStatus  {:state "LIVE"}
                                       :metadata         {:values {"folder-field" ["nested-folder"]}}
                                       :currentAssetFile {:id        (:file-id asset)
                                                          :filename  (:file-name asset)
                                                          :size      (alength ^bytes (:bytes asset))
                                                          :dateAdded "2026-11-01T00:00:00Z"
                                                          :mediaType {:type "image" :subType (:format asset)}}}
                    :permittedActions []})]
    (fn [request]
      (let [path (:uri request)
            auth (get-in request [:headers "authorization"])]
        (when (and (not= path "/dash/oauth/token") (not (str/starts-with? path "/dash/files/")))
          (when-not (= auth (str "Bearer dash-token-" @tokens))
            (throw (ex-info "Fixture received incorrect Dash bearer token." {:path path}))))
        (cond
          (= path "/dash/oauth/token")
          (let [params   (form (:body request))
                expected (if (zero? @tokens) "initial-refresh" "rotated-refresh")]
            (when-not (= ["refresh_token" "client-id" "client-secret" expected]
                         (mapv params [:grant_type :client_id :client_secret :refresh_token]))
              (throw (ex-info "Incorrect Dash refresh grant." {})))
            (swap! observed conj {:kind :dash-token :rotated? (pos? @tokens)})
            (swap! tokens inc)
            (response 200
                      {:access_token  (str "dash-token-" @tokens)
                       :refresh_token "rotated-refresh"
                       :expires_in    (if expired-tokens? 1 86400)}))

          (= path "/dash/folder-settings")
          (if (and unauthorized-once? (compare-and-set! denied? false true))
            (response 401 {:message "expired"})
            (response 200 {:result {:accountId "account-1" :fieldId "folder-field"} :permittedActions []}))

          (= path "/dash/asset-scroll-searches")
          (let [body (json/read-json (io/reader (:body request)) :key-fn keyword)]
            (when-not (= {:type "FIELD_EQUALS" :value "parent-folder" :field {:type "FIELD" :fieldId "folder-field"}} (:criterion body))
              (throw (ex-info "Incorrect Dash folder search." {:body body})))
            (swap! observed conj {:kind :dash-search :folder "parent-folder"})
            (response 200 {:results [(result (first assets))] :totalResults (count assets) :scrollId "scroll-1"}))

          (= path "/dash/asset-scroll-search-scrolls")
          (let [body   (json/read-json (io/reader (:body request)) :key-fn keyword)
                cursor (:scrollId body)]
            (swap! observed conj {:kind :dash-scroll :cursor cursor})
            (case cursor
              "scroll-1" (response 200 {:results (mapv result (rest assets)) :totalResults (count assets) :scrollId "scroll-2"})
              "scroll-2" (response 200 {:results [] :totalResults (count assets) :scrollId "scroll-3"})
              (throw (ex-info "Scroll did not use the latest ID." {:cursor cursor}))))

          (= path "/dash/asset-download-batch-jobs")
          (let [body  (json/read-json (io/reader (:body request)) :key-fn keyword)
                items (:items body)]
            (when-not (and (false? (:zip body))
                           (= {:type "CUSTOM" :candidateTransformations [{:criteria [] :transformation []}]}
                              (:transformationDescription body))
                           (every? (fn [item] (some #(= {:batchItemId (:id %) :assetId (:id %) :assetFileId (:file-id %)} item) assets)) items))
              (throw (ex-info "Download must pin original file versions without transformations." {})))
            (swap! observed conj {:kind :dash-download-job :items (mapv :assetId items)})
            (let [id (str "job-" (:assetId (first items)))]
              (swap! jobs assoc id (mapv :assetId items))
              (response 200 {:id id :type "IN_PROGRESS"})))

          (str/starts-with? path "/dash/asset-download-batch-jobs/")
          (let [id        (last (str/split path #"/"))
                ids       (get @jobs id)
                attempt   (get (swap! polls update id (fnil inc 0)) id)
                url       (fn [asset-id] (str "http://" (get-in request [:headers "host"]) "/dash/files/" asset-id))
                successes (into {} (map (fn [asset-id] [asset-id {:result {:url (url asset-id)} :permittedActions []}])) ids)]
            (cond
              (and rate-limit-once? (= attempt 1)) (assoc-in (response 429 {:message "wait"}) [:headers "Retry-After"] "0")
              fail-job? (response 200 {:type "FAILED"})
              (= attempt (if rate-limit-once? 2 1)) (response 200 {:type "IN_PROGRESS"})
              fail-item? (response 200 {:type "COMPLETED" :result {:successes (dissoc successes (first ids)) :failures {(first ids) "ASSET_FILE_NOT_FOUND"}}})
              :else (response 200 {:type "COMPLETED" :result {:failures {} :successes successes}})))

          (str/starts-with? path "/dash/files/")
          (let [id    (last (str/split path #"/"))
                asset (some #(when (= id (:id %)) %) assets)]
            (when auth (throw (ex-info "Dash bearer token leaked to a signed download URL." {})))
            (swap! observed conj {:kind :dash-file :asset id :authenticated? (some? auth)})
            {:status  200
             :headers {"Content-Type" (str "image/" (:format asset))}
             :body    (ByteArrayInputStream. (if oversized? (byte-array 2048) (:bytes asset)))})

          :else (response 404 {:message "Unknown Dash fixture endpoint"}))))))
