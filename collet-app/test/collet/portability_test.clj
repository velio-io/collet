(ns collet.portability-test
  (:require [charred.api :as json]
            [clj-test-containers.core :as tc]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]
            [collet.actions.http]
            [collet.core :as collet]
            [collet.dash-fixture :as dash]
            [collet.mcp-contract-test :as docs]
            [collet.test-containers :as containers]
            [collet.workbench-test :refer [delete-tree! temp-dir]]
            [collet.workbench.knowledge :as knowledge]
            [ring.adapter.jetty :as jetty])
  (:import [java.awt.image BufferedImage]
           [java.nio.file Files StandardCopyOption]
           [java.security MessageDigest]
           [java.util Base64 HexFormat]
           [java.util.concurrent TimeUnit]
           [javax.imageio ImageIO]))


(defn- copy! [source target]
  (io/make-parents target)
  (Files/copy (.toPath (io/file source))
              (.toPath (io/file target))
              (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING])))


(defn- stage-repository! [root manifest]
  (let [repository   (io/file root "home/.m2/repository")
        cached       (io/file (System/getProperty "user.home") ".m2/repository")
        cache-prefix (str (.getCanonicalPath cached) java.io.File/separator)]
    ;; Seed only third-party POMs and the already resolved test JVM's JARs.
    ;; Collet artifacts always come from this build, never the user's Maven cache.
    (doseq [file  (file-seq cached)
            :when (and (.isFile file) (str/ends-with? (.getName file) ".pom"))
            :let  [relative (subs (.getCanonicalPath file) (count cache-prefix))]
            :when (not (str/starts-with? relative "io/velio/"))]
      (copy! file (io/file repository relative)))
    (doseq [path  (str/split (System/getProperty "java.class.path") (re-pattern java.io.File/pathSeparator))
            :when (and (str/starts-with? path cache-prefix) (str/ends-with? path ".jar"))
            :let  [relative (subs path (count cache-prefix))]
            :when (not (str/starts-with? relative "io/velio/"))]
      (copy! path (io/file repository relative)))
    (doseq [module ["collet-core" "collet-action-http" "collet-action-file" "collet-action-s3" "collet-action-llm" "collet-action-sparql"]
            :let   [version     (get-in manifest [:packages (str "io.velio/" module) :version])
                    destination (io/file repository "io/velio" module version)]]
      (copy! (io/file ".." module "target" (str module "-" version ".jar"))
             (io/file destination (str module "-" version ".jar")))
      (copy! (io/file ".." module "target/classes/META-INF/maven/io.velio" module "pom.xml")
             (io/file destination (str module "-" version ".pom"))))))


(defn- child! [root jar mode]
  (let [log     (io/file root (str mode ".log"))
        builder (ProcessBuilder.
                 ^java.util.List
                 ["java" "--add-opens=java.base/java.nio=ALL-UNNAMED"
                  "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED" "--enable-native-access=ALL-UNNAMED"
                  (str "-Duser.home=" (io/file root "home")) "-cp" jar "clojure.main"
                  "runner.clj" mode "cases.edn"])
        _ (.directory builder (io/file root))
        process (-> builder (.redirectErrorStream true) (.redirectOutput log) (.start))]
    (try
      (let [exited? (.waitFor process 120 TimeUnit/SECONDS)]
        (is exited? (str "Standalone process timed out: " mode "\n" (slurp log)))
        (when exited?
          (let [success?   (zero? (.exitValue process))
                completed? (str/includes? (slurp log) (str "PORTABILITY_OK " mode))]
            (is success? (slurp log))
            (is completed? (slurp log))
            (and success? completed?))))
      (finally
       (when (.isAlive process) (.destroyForcibly process) (.waitFor process))))))


(defn- recipe [section]
  (first (docs/documentation-examples "recipes" section)))


(defn- digest [algorithm bytes]
  (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance algorithm) bytes)))


(defn- etag [bytes]
  (str "\"" (digest "MD5" bytes) "\""))


(defn- basic-auth [username password]
  (str "Basic " (.encodeToString (Base64/getEncoder) (.getBytes (str username ":" password) "UTF-8"))))


(deftest ^:integration canonical-mcp-examples-survive-clean-processes-and-restart
  (let [path         (temp-dir)
        root         (.toFile path)
        jar          (.getCanonicalPath (io/file "target/collet.jar"))
        manifest     (edn/read-string (slurp (io/file "../target/workbench-knowledge/collet/knowledge/compatibility.edn")))
        localstack   (containers/localstack)
        creds        {:aws-region        "auto"
                      :aws-key           "test"
                      :aws-secret        "test"
                      :endpoint-override {:protocol :http :hostname "localhost" :port (get-in localstack [:mapped-ports 4566])}}
        s3           (aws/client {:api                  :s3
                                  :region               "us-east-1"
                                  :endpoint-override    (:endpoint-override creds)
                                  :credentials-provider (credentials/basic-credentials-provider {:access-key-id "test" :secret-access-key "test"})})
        observed     (atom [])
        dash-assets  (dash/images)
        dash-handler (dash/handler dash-assets observed {:rate-limit-once? true})
        source       (jetty/run-jetty
                      (fn [request]
                        (let [path          (:uri request)
                              auth          (get-in request [:headers "authorization"])
                              json-response (fn [status body]
                                              {:status  status
                                               :headers {"Content-Type" "application/json"}
                                               :body    (json/write-json-str body)})]
                          (case path
                            "/private" (if (= (basic-auth "usér" "p:a:ss word") auth)
                                         (json-response 200 {:authenticated true})
                                         (json-response 401 {:error {:message "Incorrect basic authentication"}}))
                            "/analyze/systemone"
                            (let [body     (json/read-json (io/reader (:body request)) :key-fn keyword)
                                  asset    (first (vals (:assets body)))
                                  bytes    (.decode (Base64/getDecoder) (:data_base64 asset))
                                  key      (str "dash/originals/" (digest "SHA-256" bytes) "." (:format asset))
                                  deadline (+ (System/nanoTime) 5000000000)
                                  overlap? (loop []
                                             (cond
                                               (not (:cognitect.anomalies/category (aws/invoke s3 {:op :HeadObject :request {:Bucket "images" :Key key}}))) true
                                               (> (System/nanoTime) deadline) false
                                               :else (do (Thread/sleep 20) (recur))))]
                              (swap! observed conj {:kind :analysis :overlap? overlap? :auth auth})
                              (json-response
                               200
                               {:model   (:model body)
                                :answers {:ad_category {:type "choice" :choice "retail" :confidence 0.9 :probabilities {:retail 0.9 :other_unclear 0.1}}
                                          :ad_suitability {:type "choice" :choice "suitable" :confidence 0.95 :probabilities {:suitable 0.95 :review 0.04 :unsuitable 0.01}}
                                          :creative_quality
                                          {:type "score" :score 4 :confidence 1.0 :legend {:0 "Unusable" :1 "Poor" :2 "Acceptable" :3 "Good" :4 "Excellent"} :probabilities {:0 0.0 :1 0.0 :2 0.0 :3 0.0 :4 1.0}}
                                          :cap_contains_text {:type "noul" :noul 0.8}}}))
                            "/rdfox" (let [update (slurp (:body request))]
                                       (swap! observed conj
                                         {:kind         :rdf
                                          :update       update
                                          :auth         auth
                                          :content-type (get-in request [:headers "content-type"])})
                                       (if (= (basic-auth "rdf-user" "p:a:ss word") auth)
                                         {:status 204 :body ""}
                                         (json-response 401 {:error {:message "Incorrect RDFox authentication"}})))
                            "/token" (json-response 200 {:access_token "fixture-token"})
                            (if (str/starts-with? path "/dash/")
                              (dash-handler request)
                              (json-response 200 [{:id 1 :amount 12} {:id 2 :amount 8}])))))
                      {:port 0 :host "127.0.0.1" :join? false})]
    (try
      (is (= "classpath-present" (:availability (knowledge/describe "collet.actions.http/request"))))
      (doseq [bucket ["portable" "reports" "images"]]
        (is (nil? (:cognitect.anomalies/category (aws/invoke s3 {:op :CreateBucket :request {:Bucket bucket}})))))
      (stage-repository! root manifest)
      (copy! "test/portability/runner.clj" (io/file root "runner.clj"))
      (let [;; Pin recipes from the packaged manifest, preserving independent versions.
            catalogue-var  (ns-resolve 'collet.workbench.knowledge 'catalogue)
            original       (deref (deref catalogue-var))
            deps           (with-redefs-fn {catalogue-var (delay (assoc original :compatibility manifest))}
                             (fn []
                               (let [recipes (map #(edn/read-string (:dependencyEdn (knowledge/describe %)))
                                                  ["collet.actions.http/request" "collet.actions.s3/sink" "collet.actions.file/read" "collet.actions.llm/request" "collet.actions.sparql/update"])]
                                 {:coordinates (vec (mapcat :coordinates recipes))
                                  :requires    (vec (mapcat :requires recipes))})))
            [fetch upload] (filter :type (docs/documentation-examples "recipes" "native-http-to-s3-or-r2"))
            binary         (some #(when (= :upload-file (:name %)) %) (docs/documentation-examples "actions" "s3-file-sink"))
            authenticated  (some #(when (= :private-resource (:name %)) %) (docs/documentation-examples "actions" "http-request"))
            native         {:name  :native-http-s3
                            :deps  deps
                            :tasks [{:name       :export
                                     :keep-state true
                                     :actions    [fetch upload {:name :config :type :clj/identity :selectors {'config [:config]} :params ['config]}]}]}
            config         {:orders-url (str "http://127.0.0.1:" (.getLocalPort (first (.getConnectors source))) "/orders")
                            :bucket     "portable"
                            :aws        creds}
            token-action   (assoc-in (first (docs/documentation-examples "actions" "oauth2-request"))
                            [:params :url]
                            (str/replace (:orders-url config) "/orders" "/token"))
            bytes          (byte-array [0 1 2 -1 -128 80 78 71])
            binary-path    (str (io/file root "fixture.bin"))
            _ (with-open [out (io/output-stream binary-path)] (.write out bytes))
            image-spec     (edn/read-string (slurp "test/portability/image-analysis.edn"))
            image-spec     (update-in image-spec
                                      [:deps :coordinates]
                                      (fn [coordinates]
                                        (mapv (fn [[coordinate _]]
                                                [coordinate (get-in manifest [:packages (str coordinate) :version])])
                                              coordinates)))
            image-config   {:dash                  {:api-url       (str/replace (:orders-url config) "/orders" "/dash")
                                                    :token-url     (str/replace (:orders-url config) "/orders" "/dash/oauth/token")
                                                    :folder-id     "parent-folder"
                                                    :client-id     "client-id"
                                                    :client-secret "client-secret"
                                                    :refresh-token "initial-refresh"}
                            :jev                   {:base-url (str/replace (:orders-url config) "/orders" "/analyze") :api-key "fixture-key" :model "fixture-model"}
                            :r2                    creds
                            :r2-bucket             "images"
                            :rdfox                 {:sparql-url (str/replace (:orders-url config) "/orders" "/rdfox") :username "rdf-user" :password "p:a:ss word"}
                            :max-image-bytes       1048576
                            :min-choice-confidence 0.7
                            :capability-threshold  0.5
                            :ad-categories         {:retail "Retail product" :other_unclear "Unclear"}
                            :capability-criteria   {:contains_text "Visible text"}}
            image-expected (set
                            (map (fn [asset]
                                   (let [bytes     (:bytes asset)
                                         sha       (digest "SHA-256" bytes)
                                         identity  (str "account-1/" (:id asset))
                                         extension (if (= "png" (:format asset)) ".png" ".jpg")]
                                     {:dash-account-id "account-1"
                                      :dash-asset-id   (:id asset)
                                      :dash-file-id    (:file-id asset)
                                      :sha256          sha
                                      :r2-etag         (etag bytes)
                                      :r2-uri          (str "s3://images/dash/originals/" sha extension)
                                      :rdfox-graph     (str "urn:collet:dash:image:" (digest "SHA-256" (.getBytes identity "UTF-8")))
                                      :ad-category     "retail"
                                      :capabilities    ["contains_text"]
                                      :ad-suitability  "suitable"
                                      :stored          true}))
                                 dash-assets))
            file-tools     {:name  :portable-file-tools
                            :deps  deps
                            :tasks [{:name    :write
                                     :actions [{:name      :write
                                                :type      :collet.actions.file/sink
                                                :selectors {'path [:config :path]}
                                                :params    {:file-name 'path :input [{:n 1}] :format :edn}}]}
                                    {:name       :read
                                     :inputs     [:write]
                                     :keep-state true
                                     :actions    [{:name      :read
                                                   :type      :collet.actions.file/read
                                                   :selectors {'path [:config :path]}
                                                   :params    {:path 'path :as :dataset :format :edn}}]}
                                    {:name       :list
                                     :inputs     [:read]
                                     :keep-state true
                                     :actions    [{:name      :list
                                                   :type      :collet.actions.file/list
                                                   :selectors {'folder [:config :folder]}
                                                   :params    {:path 'folder}}
                                                  {:name      :names
                                                   :type      :slicer
                                                   :selectors {'files [:state :list]}
                                                   :params    {:sequence 'files :apply [[:select {:columns [:file-name]}]]}}]}]}
            file-config    {:path (str (io/file root "file-tools/data.edn")) :folder (str (io/file root "file-tools"))}
            object-tools   {:name  :portable-object-tools
                            :deps  deps
                            :tasks [{:name    :write
                                     :actions [{:name      :write
                                                :type      :collet.actions.s3/sink
                                                :selectors {'creds [:config :aws]}
                                                :params    {:aws-creds 'creds :bucket "reports" :file-name "toolkit/data.edn" :input [{:n 1}] :format :edn}}]}
                                    {:name       :read
                                     :inputs     [:write]
                                     :keep-state true
                                     :actions    [{:name      :read
                                                   :type      :collet.actions.s3/read
                                                   :selectors {'creds [:config :aws]}
                                                   :params    {:aws-creds 'creds :bucket "reports" :key "toolkit/data.edn" :as :dataset :format :edn}}]}
                                    {:name       :list
                                     :inputs     [:read]
                                     :keep-state true
                                     :actions    [{:name      :list
                                                   :type      :collet.actions.s3/list
                                                   :selectors {'creds [:config :aws]}
                                                   :params    {:aws-creds 'creds :bucket "reports" :prefix "toolkit/" :page-size 1}}
                                                  {:name      :keys
                                                   :type      :slicer
                                                   :selectors {'files [:state :list]}
                                                   :params    {:sequence 'files :apply [[:select {:columns [:key]}]]}}]}
                                    {:name       :stream
                                     :inputs     [:read]
                                     :keep-state true
                                     :actions    [{:name      :stream
                                                   :type      :collet.actions.s3/read
                                                   :selectors {'creds [:config :aws]}
                                                   :params    {:aws-creds 'creds :bucket "reports" :key "toolkit/data.edn" :as :stream}}
                                                  {:name      :copy
                                                   :type      :collet.actions.file/sink
                                                   :selectors {'input [:state :stream] 'path [:config :copy-path]}
                                                   :params    {:input 'input :file-name 'path}}]}]}
            object-config  {:aws creds :copy-path (str (io/file root "streamed.edn"))}
            cases          [{:spec file-tools :config file-config :task :read :expected [{:n 1}]}
                            {:spec file-tools :config file-config :task :list :expected [{:file-name "data.edn"}]}
                            {:spec object-tools :config object-config :task :read :expected [{:n 1}]}
                            {:spec object-tools :config object-config :task :list :expected [{:key "toolkit/data.edn"}]}
                            {:spec     object-tools
                             :config   object-config
                             :task     :stream
                             :expected {:file-name (:copy-path object-config) :path (:copy-path object-config)}}
                            {:spec     (recipe "paid-orders-and-daily-totals")
                             :task     :totals
                             :expected #{{:day "2026-11-01" :currency "EUR" :total 20.0} {:day "2026-11-01" :currency "USD" :total 9.0}}}
                            {:spec     (first (docs/documentation-examples "pipeline" "pipeline-specification"))
                             :task     :totals
                             :expected #{{:day "2026-11-01" :currency "EUR" :total 20.0} {:day "2026-11-01" :currency "USD" :total 9.0}}}
                            {:spec     {:name  :inline-domain
                                        :tasks [{:name       :convert
                                                 :keep-state true
                                                 :actions    [(some #(when (= :convert-temperature (:name %)) %)
                                                                    (docs/documentation-examples "pipeline" "actions"))]}]}
                             :task     :convert
                             :expected {:sensor "A" :fahrenheit 32.0}}
                            {:spec     (recipe "row-iteration-and-retry")
                             :config   {:readings [{:sensor "A" :celsius 0} {:sensor "B" :celsius 10}]}
                             :task     :convert
                             :expected [{:sensor "A" :fahrenheit 32.0} {:sensor "B" :fahrenheit 50.0}]}
                            {:spec     (edn/read-string (slurp (io/resource "collet/workbench/examples/orders.edn")))
                             :config   {:failure-mode :once}
                             :task     :report
                             :retries  1
                             :repeats  2
                             :expected {:orders 24 :totals [{:currency "EUR" :orders 16 :total 2341.6} {:currency "GBP" :orders 8 :total 1033.0}] :status :ready}}
                            {:spec     {:name  :native-oauth2
                                        :deps  {:coordinates (filterv #(= 'io.velio/collet-action-http (first %)) (:coordinates deps))
                                                :requires    '[[collet.actions.http]]}
                                        :tasks [{:name :token :keep-state true :actions [token-action]}]}
                             :task     :token
                             :expected "fixture-token"}
                            {:spec native :config config :task :export :expected config}
                            {:spec     {:name  :native-basic-auth
                                        :deps  deps
                                        :tasks [{:name :fetch :keep-state true :actions [authenticated]}]}
                             :config   {:url (str/replace (:orders-url config) "/orders" "/private") :username "usér" :password "p:a:ss word"}
                             :task     :fetch
                             :expected {:authenticated true}}
                            {:spec     {:name :native-binary :deps deps :tasks [{:name :upload :keep-state true :actions [binary]}]}
                             :config   {:aws creds :file-path binary-path}
                             :task     :upload
                             :expected {:bucket "reports" :key "chart.png" :etag (etag bytes)}}
                            {:spec image-spec :config image-config :task :write-rdfox :expected image-expected}]]
        ;; This development process can run HTTP without coordinates; the clean JVM must reject it.
        (let [ctx (collet/context {:data-dir (str (io/file root "workbench-state"))})]
          (try
            (let [spec {:name  :classpath-only
                        :deps  {:requires '[[collet.actions.http]]}
                        :tasks [{:name :fetch :keep-state true :actions [fetch]}]}
                  run  @(collet/start ctx (collet/compile-pipeline spec) config)]
              (is (= :done (:run/status run))))
            (finally (collet/close ctx))))
        (spit (io/file root "cases.edn")
              (pr-str {:jar       jar
                       :data-dir  (str (io/file root "data"))
                       :manifest  manifest
                       :documents (into {}
                                        (map (fn [[id section :as key]] [key (docs/documentation-text id section)]))
                                        [["recipes" nil] ["actions" "s3-file-sink"] ["actions" "oauth2-request"] ["actions" "http-request"]
                                         ["pipeline" "pipeline-specification"] ["pipeline" "actions"]])
                       :cases     cases
                       :negative  {:spec native :config config}}))
        (when (and (child! root jar "missing")
                   (child! root jar "persist")
                   (child! root jar "run"))
          (let [records (aws/invoke s3 {:op :GetObject :request {:Bucket "portable" :Key "orders.json"}})
                image   (aws/invoke s3 {:op :GetObject :request {:Bucket "reports" :Key "chart.png"}})]
            (with-open [body (:Body records)]
              (is (= [{:id 1 :amount 12} {:id 2 :amount 8}] (json/read-json (io/reader body) :key-fn keyword))))
            (is (= [:analysis :analysis :rdf :rdf] (mapv :kind (filter #(#{:analysis :rdf} (:kind %)) @observed))))
            (is (= ["scroll-1" "scroll-2"] (mapv :cursor (filter #(= :dash-scroll (:kind %)) @observed))))
            (is (every? #(false? (:authenticated? %)) (filter #(= :dash-file (:kind %)) @observed)))
            (is (every? :overlap? (filter #(= :analysis (:kind %)) @observed)))
            (is (every? #(= "Bearer fixture-key" (:auth %)) (filter #(= :analysis (:kind %)) @observed)))
            (doseq [asset dash-assets
                    :let  [expected (some #(when (= (:id asset) (:dash-asset-id %)) %) image-expected)
                           key      (str/replace-first (:r2-uri expected) "s3://images/" "")
                           uploaded (aws/invoke s3 {:op :GetObject :request {:Bucket "images" :Key key}})
                           rdf      (some #(when (and (= :rdf (:kind %)) (str/includes? (:update %) (:rdfox-graph expected))) %) @observed)]]
              (with-open [body (:Body uploaded)]
                (is (= (seq (:bytes asset)) (seq (.readAllBytes body)))))
              (is (= (:r2-etag expected) (:ETag uploaded)))
              (is (= "application/sparql-update" (:content-type rdf)))
              (is (str/includes? (:update rdf) (str "<urn:collet:dash:r2ETag> " (json/write-json-str (:r2-etag expected))))))
            (with-open [body (:Body image)] (is (= (seq bytes) (seq (.readAllBytes body))))))))
      (finally (.stop source) (aws/stop s3) (tc/stop! localstack) (delete-tree! path)))))
