(require '[collet.arrow :as arrow]
         '[clojure.java.io :as io]
         '[charred.api :as json]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[collet.actions.http :as http]
         '[collet.actions.s3 :as s3]
         '[llm.sdk.http :as sdk-http]
         '[collet.core :as collet]
         '[collet.workbench.execution :as execution]
         '[collet.store :as store]
         '[collet.utils :as utils]
         '[tech.v3.dataset :as ds])


(import '[java.awt.image BufferedImage]
        '[java.io ByteArrayInputStream ByteArrayOutputStream]
        '[java.lang.management ManagementFactory]
        '[java.net URI]
        '[java.nio.file Files Path]
        '[java.util Random]
        '[javax.imageio ImageIO])


(def spec (edn/read-string (slurp "collet-app/test/portability/image-analysis.edn")))


(def MiB (* 1024.0 1024))


(def run-dir (System/getProperty "java.io.tmpdir"))


(def state-dir (.toFile (Files/createTempDirectory "collet-dash-fixture-" (make-array java.nio.file.attribute.FileAttribute 0))))


(def mode (first *command-line-args*))


(def size-n (parse-long (second *command-line-args*)))


(def memory-bean (ManagementFactory/getMemoryMXBean))


(def print-lock (Object.))


(defn emit! [data]
  (locking print-lock (println (json/write-json-str data)) (flush)))


(def reader-peak (atom 0))


(def read-records @#'arrow/root->records)


(defn sample-reader [root schema]
  (when-let [v (first (.getFieldVectors root))]
    (swap! reader-peak max (.getAllocatedMemory (.getAllocator v))))
  (read-records root schema))


(def heap-checkpoints (atom {}))


(defn assert-memory-bound! []
  ;; Two 8 MiB buffers plus allocation/GC variation fit comfortably in 32 MiB.
  (doseq [[start end] [["before-list-slice" "list-slice-held"]
                       ["before-partition" "partition-output-held"]
                       ["before-join-validate" "joined-dataset-held"]
                       ["image-record" "image-record"]]
          :let        [before (get-in @heap-checkpoints [start :first])
                       peak   (get-in @heap-checkpoints [end :peak])]
          :when       (and before peak)]
    (assert (<= (- peak before) 32) (str end " retained " (- peak before) " MiB above its baseline"))))


(defn measurement [label extra]
  (System/gc)
  (let [heap  (Math/round (/ (.getUsed (.getHeapMemoryUsage memory-bean)) MiB))
        files (for [dir   (.listFiles (io/file run-dir))
                    :when (str/starts-with? (.getName dir) "collet-join-")
                    f     (file-seq dir)
                    :when (.isFile f)]
                f)]
    (swap! heap-checkpoints update
      label
      (fn [previous] {:first (or (:first previous) heap) :peak (max heap (get previous :peak 0))}))
    (emit! (merge {:at              label
                   :heapMiB         heap
                   :peakReaderBytes @reader-peak
                   :joinDiskBytes   (reduce + (map #(.length %) files))}
                  extra))))


(defn sha [data]
  (.formatHex (java.util.HexFormat/of) (.digest (java.security.MessageDigest/getInstance "SHA-256") data)))


(defn accumulate! [^java.security.MessageDigest digest value]
  (.update digest (.getBytes (str value "\n") "UTF-8")))


(defn png [seed]
  (let [side   (if (= "small" mode) 48 384)
        image  (BufferedImage. side side BufferedImage/TYPE_INT_RGB)
        pixels (int-array (* side side))
        random (Random. (long seed))
        out    (ByteArrayOutputStream.)]
    (dotimes [i (alength pixels)] (aset-int pixels i (.nextInt random)))
    (.setRGB image 0 0 side side pixels 0 side)
    (assert (ImageIO/write image "png" out))
    (.toByteArray out)))


(def endpoint "http://127.0.0.1:1")


(def config
  {:dash                  {:api-url       (str endpoint "/dash")
                           :token-url     (str endpoint "/token")
                           :folder-id     "parent-folder"
                           :client-id     "fixture"
                           :client-secret "fixture"
                           :refresh-token "fixture"}
   :jev                   {:base-url (str endpoint "/jev") :api-key "fixture" :model "fixture-model"}
   :r2                    {:aws-region        "auto"
                           :aws-key           "fixture"
                           :aws-secret        "fixture"
                           :endpoint-override {:hostname "127.0.0.1" :port 1}}
   :r2-bucket             "memory-fixture"
   :rdfox                 {:sparql-url (str endpoint "/rdfox") :username "fixture" :password "fixture"}
   :max-image-bytes       3000000
   :min-choice-confidence 0.7
   :capability-threshold  0.5
   :ad-categories         {:retail "Retail products" :other_unclear "Unclear"}
   :capability-criteria   {:contains_text "Contains text"}})


(defn reply [body]
  {:status 200 :headers {"content-type" "application/json"} :body (json/write-json-str body)})


(def jev-answer
  {:model   "fixture-model"
   :answers {:ad_category       {:type "choice" :choice "retail" :confidence 0.9 :probabilities {:retail 0.9 :other_unclear 0.1}}
             :ad_suitability    {:type "choice" :choice "suitable" :confidence 0.9 :probabilities {:suitable 0.9 :review 0.1}}
             :creative_quality  {:type "score" :score 4 :confidence 1.0 :legend {:0 "Unusable" :1 "Poor" :2 "Acceptable" :3 "Good" :4 "Excellent"} :probabilities {:0 0.0 :1 0.0 :2 0.0 :3 0.0 :4 1.0}}
             :cap_contains_text {:type "noul" :noul 0.8}}})


(defn body-json [body]
  (if (string? body) (json/read-json body :key-fn keyword) body))


(defn asset [i]
  {:result {:id               (str "asset-" i)
            :lifecycleStatus  {:state "LIVE"}
            :currentAssetFile {:id        (str "file-" i)
                               :filename  (str "fixture-" i ".png")
                               :dateAdded "2026-11-17T00:00:00Z"}}})


(defn full! []
  (let [expected     (java.security.MessageDigest/getInstance "SHA-256")
        actual       (java.security.MessageDigest/getInstance "SHA-256")
        bytes        (png 0)
        hits         (atom {})
        count-action (atom {})
        page         (fn [start]
                       (reply {:results  (mapv asset (range start (min size-n (+ start 100))))
                               :scrollId (str (+ start 100))}))
        transport    (fn [{:keys [url body]}]
                       (let [uri  (URI/create url)
                             path (.getPath uri)]
                         (assert (= "127.0.0.1" (.getHost uri)))
                         (cond
                           (= path "/token")
                           (reply {:access_token "fixture-access" :expires_in 86400})
                           (= path "/dash/folder-settings")
                           (reply {:result {:accountId "fixture-account" :fieldId "folder-field"}})
                           (= path "/dash/asset-scroll-searches")
                           (do (swap! hits update :pages (fnil inc 0)) (page 0))
                           (= path "/dash/asset-scroll-search-scrolls")
                           (do (swap! hits update :pages (fnil inc 0)) (page (parse-long (:scrollId (body-json body)))))
                           (= path "/dash/asset-download-batch-jobs")
                           (let [ids (mapv #(parse-long (subs (:assetId %) 6)) (:items (body-json body)))]
                             (assert (<= 1 (count ids) 100))
                             (swap! hits update :jobs (fnil inc 0))
                             (reply {:id (str (first ids) "-" (count ids))}))
                           (str/starts-with? path "/dash/asset-download-batch-jobs/")
                           (let [[start count] (mapv parse-long (str/split (last (str/split path #"/")) #"-"))]
                             (reply {:type   "COMPLETED"
                                     :result {:failures  {}
                                              :successes (into {}
                                                               (map #(vector (str "asset-" %) {:result {:url (str endpoint "/files/" %)}}))
                                                               (range start (+ start count)))}}))
                           (str/starts-with? path "/files/")
                           (let [i    (parse-long (last (str/split path #"/")))
                                 data (png i)]
                             (swap! hits update :downloads (fnil inc 0))
                             (accumulate! expected (str "asset-" i "/" (sha data)))
                             {:status 200 :headers {"content-type" "image/png"} :body (ByteArrayInputStream. data)})
                           (= path "/jev/systemone")
                           (do (swap! hits update :classify (fnil inc 0)) (reply jev-answer))
                           (= path "/rdfox")
                           (do
                             (let [asset (second (re-find #"dash:dashAssetId> \"([^\"]+)\"" body))
                                   hash  (second (re-find #"dash:sha256> \"([^\"]+)\"" body))]
                               (assert (and asset hash))
                               (accumulate! actual (str asset "/" hash)))
                             (swap! hits update :rdf-writes (fnil inc 0))
                             {:status 204 :headers {} :body ""})
                           :else (throw (ex-info "Unexpected local fixture route" {:path path})))))
        observe      (fn [{:keys [collet/event task action result]}]
                       (when (= event :action-result)
                         (let [key [task action]]
                           (cond
                             (#{:partitions :validate-join :join-results} action)
                             (measurement (name action) (cond-> {:task task} (ds/dataset? result) (assoc :rows (ds/row-count result))))
                             (or (= action :image-record)
                                 (and (= action :each-image) (#{:classify-images :upload-originals} task)))
                             (let [n (get (swap! count-action update key (fnil inc 0)) key)]
                               (when (or (and (= action :each-image) (or (= n 1) (= n size-n) (zero? (mod n (if (= mode "small") 2048 128)))))
                                         (and (= action :image-record)
                                              (if (= mode "small")
                                                (or (#{1 1024 2048 4096 4097 8192} n) (= n size-n))
                                                (or (= n 1) (zero? (mod n 128)) (= n size-n)))))
                                 (measurement (name action) {:task task :count n})))))))
        ctx          (collet/context {:data-dir         (str state-dir)
                                      :on-task-start    (fn [task] (measurement "task-start" {:task (:task/name task)}))
                                      :on-task-complete (fn [task]
                                                          (measurement "task-complete"
                                                                       {:task          (:task/name task)
                                                                        :rows          (:task/output-records task)
                                                                        :artifactBytes (:task/output-bytes task)}))})]
    (try
      (with-redefs [http/http-request transport
                    sdk-http/request  (fn [request]
                                        (let [response (transport request)]
                                          (update response :body body-json)))
                    s3/upload-file    (fn [{:keys [bucket file-name input]}]
                                        (assert (str/includes? file-name (sha input)))
                                        (swap! hits update :uploads (fnil inc 0))
                                        {:bucket bucket :key file-name :etag "fixture-etag"})]
        ;; This heap probe runs against the workspace classpath. Fresh-process
        ;; portability tests separately resolve the example's pinned artifacts.
        (let [plan (collet/compile-pipeline (update spec :deps dissoc :coordinates))]
          (measurement "before-run" {:assets size-n :pngBytes (alength bytes) :heapCapMiB (Math/round (/ (.getMax (.getHeapMemoryUsage memory-bean)) MiB))})
          (let [run   @(execution/start ctx plan config {:sample! observe})
                tasks (store/get-task-runs (:store ctx) (:run/id run))]
            (emit! {:status (:run/status run)
                    :error  (:run/error run)
                    :calls  @hits
                    :tasks  (mapv #(select-keys % [:task/name :task/status :task/output-records]) tasks)})
            (assert (= :done (:run/status run)) (pr-str (:run/error run)))
            (assert (java.util.Arrays/equals (.digest expected) (.digest actual)))
            (doseq [k [:downloads :classify :uploads :rdf-writes]] (assert (= size-n (get @hits k)) (str k)))
            nil)))
      (finally (collet/close ctx)))))


(defn generated-batches [row-fn page-size pass]
  (with-meta
    (reify
     clojure.lang.Sequential
     clojure.lang.Seqable
     (seq [_]
       (let [pass-number (swap! pass inc)]
         (letfn [(step [start]
                   (lazy-seq
                    (when (< start size-n)
                      (cons (ds/->dataset (mapv row-fn (range start (min size-n (+ start page-size)))))
                            (step (+ start page-size))))))]
           (seq (step 0))))))
    {:ds-seq true}))


(defn patterned [n i]
  ;; Allocate distinct values to avoid artificially sharing all row payloads.
  (str i "-" (str/join (repeat n (char (+ 65 (mod i 24)))))))


(defn run-probe-action [task action context]
  (let [eval-context (utils/eval-ctx (get-in spec [:deps :requires]) (get-in spec [:deps :imports]))
        task         (collet/find-task spec task)
        step         (some #(when (= (:name %) action) %) (concat (:setup task) (:actions task)))]
    ((collet/compile-action eval-context step)
     (merge {:state {} :config config} context))))


(defn listing-row [i]
  {:result {:id               (str "asset-" i)
            :lifecycleStatus  {:state "LIVE"}
            :currentAssetFile {:id        (str "file-" i)
                               :filename  (str (patterned 220 i) ".png")
                               :dateAdded "2026-11-17T00:00:00Z"}}})


(defn file-row [i]
  {:dash-account-id "fixture-account"
   :dash-asset-id   (str "asset-" i)
   :dash-file-id    (str "file-" i)
   :file-name       (str (patterned 220 i) ".png")
   :modified-at     "2026-11-17T00:00:00Z"})


(defn slicer-probe! []
  (let [pages (generated-batches listing-row 100 (atom 0))]
    (measurement "before-list-slice" {:assets size-n})
    (let [result (get-in (run-probe-action :list-images
                                           :image-files
                                           {:state {:dash-search pages :dash-folder {:accountId "fixture-account"}}})
                         [:state :image-files])
          total  (reduce (fn [before page]
                           (let [n (+ before (ds/row-count page))]
                             (when (= n (quot size-n 2)) (measurement "list-slice-half" {:rows n}))
                             n))
                         0
                         result)]
      (measurement "list-slice-end" {:rows total})
      (assert (= total size-n))))
  (let [pages  (generated-batches listing-row 100 (atom 0))
        result (get-in (run-probe-action :list-images
                                         :image-files
                                         {:state {:dash-search pages :dash-folder {:accountId "fixture-account"}}})
                       [:state :image-files])
        total  (reduce (fn [before page] (+ before (ds/row-count page))) 0 result)]
    (measurement "list-slice-held" {:rows total})
    (java.lang.ref.Reference/reachabilityFence result)
    (assert (= total size-n)))
  (let [files (generated-batches file-row 100 (atom 0))]
    (measurement "before-partition" {:assets size-n})
    (let [d    (get-in (run-probe-action :resolve-downloads :partitions {:inputs {:list-images files}}) [:state :partitions])
          rows (reduce + (map #(count (:items %)) (mapcat ds/rows d)))]
      (measurement "partition-output-held" {:batches (quot (+ size-n 99) 100) :rows rows})
      (java.lang.ref.Reference/reachabilityFence d)
      (assert (= size-n rows)))))


(defn left-row [i]
  {:source-id     (str "source-" i)
   :sha256        (str "hash-" i)
   :decision-json (str "{\"fixture\":\"" (patterned 2048 i) "\"}")})


(defn right-row [i]
  {:upload-source-id (str "source-" i)
   :upload-sha256    (str "hash-" i)
   :bucket           "fixture"
   :key              (str "image-" i)})


(defn join-probe! []
  (let [left-pass  (atom 0)
        right-pass (atom 0)
        left       (generated-batches left-row 1000 left-pass)
        right      (generated-batches right-row 1000 right-pass)
        inputs     {:classify-images left :upload-originals right}]
    (measurement "before-join-validate" {:rows-per-branch size-n :decisionPayloadBytes 2048})
    (let [joined (get-in (run-probe-action :write-rdfox :join-results {:inputs inputs}) [:state :join-results])]
      (try
        (let [n (reduce (fn [n row]
                          (assert (= (str "source-" n) (:source-id row) (:upload-source-id row)))
                          (inc n))
                        0
                        (mapcat ds/rows joined))]
          (measurement "joined-dataset-held" {:rows n :leftPasses @left-pass :rightPasses @right-pass})
          (assert (= 1 @left-pass @right-pass))
          (assert (= size-n n))
          (java.lang.ref.Reference/reachabilityFence joined))
        (finally ((:collet/close (meta joined))))))))


(try
  (with-redefs-fn {#'arrow/root->records sample-reader}
    (fn []
      (case mode
        ("full" "small") (full!)

        "slicer" (slicer-probe!)
        "join" (join-probe!))))
  (assert-memory-bound!)
  (catch Throwable t
    (emit! {:harnessError (.getName (class t))
            :message      (ex-message t)
            :cause        (some-> t
                                  ex-cause
                                  ex-message)
            :trace        (mapv str (take 10 (.getStackTrace t)))})
    (throw t))
  (finally (doseq [f (reverse (file-seq state-dir))] (Files/deleteIfExists (.toPath f))) (shutdown-agents)))
