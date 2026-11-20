(ns collet.dash-pipeline-test
  (:require [charred.api :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [collet.actions.http :as http]
            [collet.actions.llm]
            [collet.actions.s3 :as s3]
            [collet.actions.sparql]
            [collet.core :as collet]
            [collet.dash-fixture :as dash]
            [collet.store :as store]
            [collet.utils :as utils]
            [collet.workbench-test :refer [with-workbench]]
            [collet.workbench.data :as data]
            [collet.workbench.fixtures :as fixtures]
            [llm.sdk.http :as sdk-http]
            [tech.v3.dataset :as ds])
  (:import [java.io ByteArrayInputStream InputStream]
           [java.net URI URLEncoder]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util Base64]
           [org.eclipse.rdf4j.repository.sail SailRepository]
           [org.eclipse.rdf4j.sail.memory MemoryStore]))


(defn configuration []
  {:dash                  {:api-url       "http://fixture/dash"
                           :token-url     "http://fixture/dash/oauth/token"
                           :folder-id     "parent-folder"
                           :client-id     "client-id"
                           :client-secret "client-secret"
                           :refresh-token "initial-refresh"}
   :jev                   {:base-url "http://unused" :api-key "unused" :model "unused"}
   :r2                    {:aws-region "auto" :aws-key "unused" :aws-secret "unused" :endpoint-override {:hostname "unused"}}
   :r2-bucket             "unused"
   :rdfox                 {:sparql-url "http://unused" :username "unused" :password "unused"}
   :max-image-bytes       1048576
   :min-choice-confidence 0.7
   :capability-threshold  0.5
   :ad-categories         {:retail "Retail" :other_unclear "Unclear"}
   :capability-criteria   {:contains_text "Visible text"}})


(defn- transport [handler]
  (fn [{:keys [url body form-params as headers]}]
    (let [uri      (URI. url)
          text     (if form-params
                     (str/join "&" (map (fn [[k v]] (str (name k) "=" (URLEncoder/encode (str v) "UTF-8"))) form-params))
                     (or body ""))
          response (handler {:uri     (.getPath uri)
                             :headers (merge {"host" (.getHost uri)} (update-keys headers str/lower-case))
                             :body    (ByteArrayInputStream. (.getBytes ^String text "UTF-8"))})
          input    (if (instance? InputStream (:body response))
                     (:body response)
                     (ByteArrayInputStream. (.getBytes ^String (:body response) "UTF-8")))]
      (assoc response
        :headers (update-keys (:headers response) str/lower-case)
        :body input))))


(defn run-source [options]
  (let [root     (str (Files/createTempDirectory "collet-dash-test-" (make-array FileAttribute 0)))
        observed (atom [])
        assets   (dash/images)
        handler  (dash/handler assets observed options)
        original (edn/read-string (slurp "test/portability/image-analysis.edn"))
        spec     (-> original
                     (assoc :name :dash-source-test)
                     (update :deps dissoc :coordinates)
                     (update :tasks #(assoc-in (subvec % 0 3) [2 :keep-state] true)))
        config   (cond-> (configuration) (:oversized? options) (assoc :max-image-bytes 1024))
        ctx      (collet/context {:data-dir (str root "/data")})]
    (try
      (with-redefs-fn {#'http/http-request (transport handler)
                       (ns-resolve 'collet.actions.http 'oauth-sessions) (atom {})}
        (fn []
          (let [run    @(collet/start ctx (collet/compile-pipeline spec) config)
                task   (some #(when (= :prepare-images (:task/name %)) %) (store/get-task-runs (:store ctx) (:run/id run)))
                output (when (= :done (:run/status run)) (collet/read-output ctx (:task/output task)))]
            {:run      run
             :observed @observed
             :rows     (when output (vec (if (ds/dataset? output) (ds/rows output) (mapcat ds/rows output))))})))
      (finally
       (collet/close ctx)
       (with-open [paths (Files/walk (.toPath (io/file root)) (make-array java.nio.file.FileVisitOption 0))]
         (doseq [path (reverse (vec (iterator-seq (.iterator paths))))] (Files/delete path)))))))


(deftest remote-source-follows-scrolls-refreshes-and-preserves-provenance
  (let [{:keys [run rows observed]} (run-source {:unauthorized-once? true :rate-limit-once? true})]
    (is (= :done (:run/status run)) (pr-str run))
    (is (= #{"asset-png" "asset-jpeg"} (set (map :dash-asset-id rows))))
    (is (every? #(= "account-1" (:dash-account-id %)) rows))
    (is (= [["asset-png" "asset-jpeg"]] (mapv :items (filter #(= :dash-download-job (:kind %)) observed))))
    (is (= ["scroll-1" "scroll-2"] (mapv :cursor (filter #(= :dash-scroll (:kind %)) observed))))
    (is (> (count (filter #(= :dash-token (:kind %)) observed)) 2))
    (is (every? #(false? (:authenticated? %)) (filter #(= :dash-file (:kind %)) observed)))
    (is (every? #(and (= 2 (:width %)) (= 3 (:height %)) (string? (:sha256 %))) rows))
    (is (not-any? #(or (contains? % :download-url) (contains? % :source-path) (contains? % :access-token)) rows))))


(deftest remote-source-fails-on-job-errors-and-oversized-content
  (doseq [options [{:fail-job? true} {:fail-item? true} {:oversized? true}]]
    (let [{:keys [run]} (run-source options)]
      (is (= :failed (:run/status run)) (pr-str {:options options :run run})))))


(deftest generic-actions-preserve-dash-classification-and-replace-only-the-asset-graph
  (let [root    (Files/createTempDirectory "collet-dash-graph-" (make-array FileAttribute 0))
        repo    (SailRepository. (MemoryStore.))
        ctx     (collet/context {:data-dir (str (.resolve root "data"))})
        assets  (dash/images)
        source  (transport (dash/handler assets (atom []) {:expired-tokens? false}))
        writes  (atom [])
        images  (atom [])
        uploads (atom [])
        spec    (-> (edn/read-string (slurp "test/portability/image-analysis.edn"))
                    (update :deps dissoc :coordinates))
        config  (-> (configuration)
                    (update :jev #(-> % (dissoc :base-url) (assoc :endpoint "http://fixture/jev/systemone/")))
                    (assoc-in [:rdfox :sparql-url] "http://fixture/rdfox"))
        answer  {:model   "fixture-model"
                 :answers {:ad_category       {:type "choice" :choice "retail" :confidence 0.9 :probabilities {:retail 0.9 :other_unclear 0.1}}
                           :ad_suitability    {:type "choice" :choice "suitable" :confidence 0.95 :probabilities {:suitable 0.95 :review 0.05}}
                           :creative_quality  {:type          "score"
                                               :score         4
                                               :confidence    1.0
                                               :legend        {:0 "Unusable" :1 "Poor" :2 "Acceptable" :3 "Good" :4 "Excellent"}
                                               :probabilities {:0 0.0 :1 0.0 :2 0.0 :3 0.0 :4 1.0}}
                           :cap_contains_text {:type "noul" :noul 0.8}}}]
    (.init repo)
    (try
      (with-open [connection (.getConnection repo)]
        (.execute (.prepareUpdate connection "INSERT DATA { GRAPH <urn:untouched> { <urn:s> <urn:p> 1 } }"))
        (with-redefs [http/http-request (fn [req]
                                          (if (= "http://fixture/rdfox" (:url req))
                                            (do (swap! writes conj (:body req))
                                                (.execute (.prepareUpdate connection (:body req)))
                                                {:status 204 :body "" :headers {}})
                                            (source req)))
                      sdk-http/request  (fn [req]
                                          (is (= "http://fixture/jev/systemone" (:url req)))
                                          (is (= "true" (get-in req [:headers "TypeSafe-Danger-Zone-Images"])))
                                          (swap! images conj (vec (.decode (Base64/getDecoder) ^String (get-in req [:body :assets "original" :data_base64]))))
                                          {:status 200 :headers {} :body answer})
                      s3/upload-file    (fn [{:keys [bucket file-name input]}]
                                          (swap! uploads conj (vec input))
                                          {:bucket bucket :key file-name :etag "fixture-etag"})]
          (let [run @(collet/start ctx (collet/compile-pipeline spec) config)]
            (is (= :done (:run/status run)) (pr-str (:run/error run)))
            (is (= 2 (count @writes)))
            (is (= (set (map (comp vec :bytes) assets)) (set @images) (set @uploads)))))
        (with-open
          [results
           (.evaluate
            (.prepareTupleQuery
             connection
             "SELECT ?g ?name ?category ?model ?cap WHERE { GRAPH ?g { ?s <urn:collet:dash:fileName> ?name ; <urn:collet:dash:adCategory> ?category ; <urn:collet:dash:model> ?model ; <urn:collet:dash:capability> ?cap } }"))]
          (let [rows (vec (iterator-seq results))]
            (is (= (set (map :file-name assets)) (set (map #(.stringValue (.getValue % "name")) rows))))
            (is (every? #(= ["retail" "fixture-model" "contains_text"]
                            (mapv (fn [field] (.stringValue (.getValue % field))) ["category" "model" "cap"]))
                        rows))))
        (let [before (.size connection (make-array org.eclipse.rdf4j.model.Resource 0))
              graph  (second (re-find #"GRAPH <([^>]+)>" (first @writes)))]
          (.execute (.prepareUpdate connection (str "INSERT DATA { GRAPH <" graph "> { <urn:stale> <urn:p> 1 } }")))
          (.execute (.prepareUpdate connection (first @writes)))
          (is (= before (.size connection (make-array org.eclipse.rdf4j.model.Resource 0))))
          (is (.evaluate (.prepareBooleanQuery connection "ASK { GRAPH <urn:untouched> { <urn:s> <urn:p> 1 } }")))))
      (finally
       (.shutDown repo)
       (collet/close ctx)
       (with-open [paths (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
         (doseq [path (reverse (sort (iterator-seq (.iterator paths))))] (Files/deleteIfExists path)))))))


(deftest join-rejects-invalid-branches-before-rdfox-writes
  (with-workbench [app]
                  (let [spec      (edn/read-string (slurp "test/portability/image-analysis.edn"))
                        decisions [{:source-id "a" :sha256 "hash-a"} {:source-id "b" :sha256 "hash-b"}]
                        receipts  [{:upload-source-id "a" :upload-sha256 "hash-a"}
                                   {:upload-source-id "b" :upload-sha256 "hash-b"}]]
                    (doseq [[left right error] [[(conj decisions (first decisions)) receipts #"Duplicate identity"]
                                                [decisions (conj receipts (first receipts)) #"Duplicate identity"]
                                                [decisions (conj receipts {:upload-source-id "c" :upload-sha256 "hash-c"}) #"different identities"]
                                                [decisions (pop receipts) #"different identities"]
                                                [decisions (assoc-in receipts [1 :upload-sha256] "bad-hash") #"Content hashes differ"]
                                                [decisions (assoc-in receipts [1 :upload-sha256] "") #"missing an identity or content hash"]]]
                      ;; Reaching the write with no mock responses would throw a different error.
                      (let [failure (try
                                      (fixtures/execute app
                                                        spec
                                                        {:edn (pr-str {:inputs {:classify-images left :upload-originals right}
                                                                       :allow  #{:build-rdfox-update}
                                                                       :mocks  {:save-metadata-to-rdfox {:responses []}}})}
                                                        {:scope "task" :task-name "write-rdfox"})
                                      (catch Exception e e))]
                        (is (instance? Exception failure))
                        (when (instance? Exception failure)
                          (is (some #(re-find error %) (:causes (data/error-data failure))))))))))


(deftest dash-credentials-are-excluded-from-write-errors
  (let [spec     (edn/read-string (slurp "test/portability/image-analysis.edn"))
        sentinel "synthetic-secret-marker"
        config   (-> (configuration)
                     (assoc-in [:rdfox :password] sentinel)
                     (assoc-in [:r2 :aws-secret] sentinel))
        context  {:config config
                  :state  {:build-rdfox-update {:update "synthetic update"}
                           :$mapper/item       {:object-key "fixture.png"}
                           :original-bytes     (byte-array [1])}}
        evaluate (fn [name]
                   (try ((collet/compile-action (utils/eval-ctx) (collet/find-action spec name)) context)
                        (catch Exception e (data/error-data e))))]
    (is (nil? (collet/find-action spec :dash-url)))
    (is (empty? (filter #(= :authorization (:name %)) (:setup (collet/find-task spec :classify-images)))))
    (with-redefs [http/http-request (http/wrap-unexceptional-status
                                     (constantly {:status 503 :body "{\"error\":{\"message\":\"fixture unavailable\"}}"}))]
      (let [error (evaluate :save-metadata-to-rdfox)]
        (is (some #{"SPARQL action failed."} (:causes error)))
        (is (not (str/includes? (pr-str error) sentinel)))))
    (let [action (assoc (collet/find-action spec :upload-original-to-r2)
                   :type :custom
                   :fn (fn [params] (throw (ex-info "Synthetic R2 failure" {:request params}))))
          error  (try ((collet/compile-action (utils/eval-ctx) action) context)
                      (catch Exception e (data/error-data e)))]
      (is (some #{"Synthetic R2 failure"} (:causes error)))
      (is (not (str/includes? (pr-str error) sentinel))))))
