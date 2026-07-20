(ns verify
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.edn :as edn]
   [clojure.set :as set]
   [clojure.string :as str]
   [workspace :as workspace])
  (:import
   (java.nio.file Files LinkOption)
   (java.nio.file.attribute PosixFilePermissions)
   (java.util.jar JarFile)
   (java.util.regex Pattern)))

(defn- fail! [message data]
  (throw (ex-info message data)))

(defn- ensure! [condition message data]
  (when-not condition
    (fail! message data)))

(defn- artifact-name [lib]
  (name lib))

(defn- library-jar [config]
  (fs/path (:dir config) "target"
           (str (artifact-name (:lib config)) "-" (:version config) ".jar")))

(defn- jar-entries [path]
  (with-open [jar (JarFile. (str path))]
    (->> (enumeration-seq (.entries jar))
         (map #(.getName %))
         set)))

(defn- jar-entry [path entry]
  (with-open [jar (JarFile. (str path))]
    (let [item (.getEntry jar entry)]
      (ensure! item "Missing JAR entry" {:jar (str path) :entry entry})
      (slurp (.getInputStream jar item)))))

(defn- namespace-entry [namespace]
  (str (-> (str namespace)
           (str/replace "." "/")
           (str/replace "-" "_"))
       ".clj"))

(defn- pom-entry [{:keys [lib]}]
  (str "META-INF/maven/" (namespace lib) "/" (name lib) "/pom.xml"))

(defn- properties-entry [{:keys [lib]}]
  (str "META-INF/maven/" (namespace lib) "/" (name lib) "/pom.properties"))

(defn- dependency-pattern [lib version]
  (re-pattern
   (str "(?s)<dependency>(?:(?!</dependency>).)*<groupId>"
        (Pattern/quote (namespace lib))
        "</groupId>(?:(?!</dependency>).)*<artifactId>"
        (Pattern/quote (name lib))
        "</artifactId>(?:(?!</dependency>).)*<version>"
        (Pattern/quote version)
        "</version>(?:(?!</dependency>).)*</dependency>")))

(defn- dependency-block [pom lib version]
  (re-find (dependency-pattern lib version) pom))

(defn- xml-value [xml tag]
  (second (re-find (re-pattern (str "<" tag ">([^<]+)</" tag ">")) xml)))

(defn- pom-dependency-set [pom]
  (->> (re-seq #"(?s)<dependency>.*?</dependency>" pom)
       (map (fn [block]
              [(symbol (str (xml-value block "groupId") "/"
                            (xml-value block "artifactId")))
               (xml-value block "version")]))
       set))

(defn- verify-library! [module]
  (let [{:keys [lib version namespaces source-dirs internal-deps] :as config}
        (workspace/module-config module)
        path (library-jar config)]
    (ensure! (fs/exists? path) "Library JAR was not built"
             {:module module :path (str path)})
    (let [entries (jar-entries path)
          pom (jar-entry path (pom-entry config))
          properties (jar-entry path (properties-entry config))]
    (ensure! (contains? entries "LICENSE") "Library JAR lacks LICENSE"
             {:module module})
    (ensure! (str/includes? properties (str "version=" version))
             "Maven properties contain the wrong version"
             {:module module :version version})
    (doseq [fragment ["https://github.com/velio-io/collet"
                      "Apache License, Version 2.0"
                      (str "<artifactId>" (name lib) "</artifactId>")
                      (str "<version>" version "</version>")]]
      (ensure! (str/includes? pom fragment) "POM metadata mismatch"
               {:module module :fragment fragment}))
    (doseq [dependency internal-deps
            :let [{dep-lib :lib dep-version :version}
                  (workspace/module-config dependency)]]
      (ensure! (dependency-block pom dep-lib dep-version)
               "POM lacks an exact internal dependency"
               {:module module :dependency dependency :version dep-version}))
    (let [base-deps (:deps (edn/read-string
                            (slurp (str (fs/path (:dir config) "deps.edn")))))
          expected-deps (->> base-deps
                             (keep (fn [[dep-lib dep-config]]
                                     (when-let [dep-version (:mvn/version dep-config)]
                                       [dep-lib dep-version])))
                             set)
          actual-deps (pom-dependency-set pom)]
      (ensure! (= expected-deps actual-deps)
               "POM direct dependencies differ from the module's base deps.edn"
               {:module module
                :missing (vec (sort-by pr-str (set/difference expected-deps actual-deps)))
                :unexpected (vec (sort-by pr-str (set/difference actual-deps expected-deps)))})
      (doseq [[dep-lib {:keys [mvn/version exclusions extension]}] base-deps
              :when version
              :let [block (dependency-block pom dep-lib version)]]
        (ensure! block "POM lacks an exact runtime dependency"
                 {:module module :dependency dep-lib :version version})
        (doseq [excluded exclusions]
          (ensure! (and (str/includes? block
                                      (str "<groupId>" (namespace excluded) "</groupId>"))
                        (str/includes? block
                                      (str "<artifactId>" (name excluded) "</artifactId>")))
                   "POM lost a runtime dependency exclusion"
                   {:module module :dependency dep-lib :exclusion excluded}))
        (when (and extension (not= "jar" extension))
          (ensure! (str/includes? block (str "<type>" extension "</type>"))
                   "POM lost a runtime dependency artifact type"
                   {:module module :dependency dep-lib :type extension}))))
    (when (seq source-dirs)
      (doseq [namespace namespaces]
        (ensure! (contains? entries (namespace-entry namespace))
                 "Library JAR lacks a preserved namespace source"
                 {:module module :namespace namespace})))
    (when (= module :collet-actions)
      (ensure! (not-any? #(str/starts-with? % "collet/actions/") entries)
               "Compatibility aggregate contains action implementation sources"
               {:module module}))
    (when (= module :collet-action-queue)
      (doseq [excluded ["com.taoensso" "encore"
                        "net.openhft" "chronicle-queue"
                        "chronicle-analytics"]]
        (ensure! (str/includes? pom excluded) "Queue POM lost an exclusion"
                 {:fragment excluded})))
    (when (= module :collet-action-vega)
      (doseq [entry ["vega.js" "vega-lite.js" "applied_science/darkstar.clj"
                     "META-INF/collet/darkstar.edn"
                     "META-INF/licenses/darkstar-MIT.txt"]]
        (ensure! (contains? entries entry) "Vega JAR lacks vendored Darkstar material"
                 {:entry entry}))
      (ensure! (str/includes? pom "<type>pom</type>")
               "Vega POM lost the Graal POM artifact type" {}))
      (println "verified library" lib version))))

(def optional-families
  {:postgres  ["org.postgresql/postgresql"]
   :mysql     ["com.mysql/mysql-connector-j"]
   :aws       ["com.cognitect.aws/"]
   :queue     ["io.zalky/cues" "net.openhft/chronicle-"]
   :llm       ["net.clojars.wkok/openai-clojure"]
   :graal     ["org.graalvm."]
   :lucene    ["org.apache.lucene/"]})

(def allowed-families
  {:collet-core          #{}
   :collet-action-http   #{}
   :collet-action-file   #{}
   :collet-action-odata  #{}
   :collet-action-jdbc   #{:postgres}
   :collet-action-s3     #{:aws}
   :collet-action-queue  #{:queue}
   :collet-action-jslt   #{}
   :collet-action-llm    #{:llm}
   :collet-action-vega   #{:graal}
   :collet-action-lucene #{:lucene}
   :collet-app           #{:aws}
   :collet-cli           #{:aws}})

(def action-modules
  (->> (workspace/modules)
       (filter #(str/starts-with? (name %) "collet-action-"))
       set))

(defn- internal-closure [module]
  (letfn [(visit [current seen]
            (reduce (fn [result dependency]
                      (if (contains? result dependency)
                        result
                        (visit dependency (conj result dependency))))
                    seen
                    (:internal-deps (workspace/module-config current))))]
    (visit module #{})))

(defn- capture! [dir & command]
  (let [{:keys [exit out err]}
        @(process/process command {:dir dir :out :string :err :string})]
    (when-not (zero? exit)
      (fail! "Command failed" {:command command :dir dir :exit exit :error err}))
    (str out err)))

(defn- verify-dependency-tree! [module]
  (when-let [allowed (get allowed-families module)]
    (let [{:keys [dir]} (workspace/module-config module)
          tree (capture! dir "clojure" "-Stree")]
      (doseq [[family tokens] optional-families
              :when (not (contains? allowed family))
              token tokens]
        (ensure! (not (str/includes? tree token))
                 "Module pulls a forbidden optional dependency family"
                 {:module module :family family :token token}))
      (let [allowed-actions (internal-closure module)]
        (doseq [action-module action-modules
                :when (not (contains? allowed-actions action-module))
                :let [coordinate (str "io.velio/" (name action-module))]]
          (ensure! (not (str/includes? tree coordinate))
                   "Module pulls an unrelated action artifact"
                   {:module module :dependency action-module})))
      (println "verified dependency tree" (name module)))))

(defn- consumer-form [namespaces]
  (str "(do "
       (str/join " " (map #(str "(require '" % ")") namespaces))
       " (println :consumer-ok))"))

(defn- verify-consumer! [repo-root module]
  (let [{:keys [lib version namespaces]} (workspace/module-config module)
        project (fs/create-temp-dir {:prefix (str (name module) "-consumer-")})]
    (try
      (spit (str (fs/path project "deps.edn"))
            (pr-str {:paths []
                     :mvn/local-repo (str repo-root)
                     :deps {lib {:mvn/version version}}}))
      (let [jvm-opts (map #(str "-J" %)
                          (get-in @workspace/manifest [:project :jvm-opts]))
            output (apply capture! (str project) "clojure" "-Srepro"
                          (concat jvm-opts ["-M" "-e" (consumer-form namespaces)]))]
        (ensure! (str/includes? output ":consumer-ok")
                 "Isolated Maven consumer failed to load namespaces"
                 {:module module :output output})
        (println "verified isolated consumer" lib))
      (finally
        (fs/delete-tree project)))))

(defn- verify-isolated-consumers! []
  (let [repo-root (fs/create-temp-dir {:prefix "collet-m2-"})]
    (try
      (workspace/install-to! (str repo-root))
      (doseq [module (workspace/modules)
              :when (workspace/publish? module)]
        (verify-consumer! repo-root module))
      (finally
        (fs/delete-tree repo-root)))))

(defn- main-class [path]
  (with-open [jar (JarFile. (str path))]
    (some-> jar .getManifest .getMainAttributes (.getValue "Main-Class"))))

(defn- file-mode [path]
  (PosixFilePermissions/toString
   (Files/getPosixFilePermissions (.toPath (fs/file path))
                                  (make-array LinkOption 0))))

(defn- verify-deployables! []
  (let [app-jar (fs/path "collet-app/target/collet.jar")
        pod-jar (fs/path "collet-cli/target/collet.pod.jar")
        archive (fs/path "collet-cli/target/collet-cli.tar.gz")]
    (doseq [path [app-jar pod-jar archive]]
      (ensure! (fs/regular-file? path) "Deployable artifact is missing"
               {:path (str path)}))
    (ensure! (= "collet.main" (main-class app-jar))
             "Application uberjar entrypoint changed" {})
    (ensure! (= "pod.collet.core" (main-class pod-jar))
             "CLI pod entrypoint changed" {})
    (let [target (fs/create-temp-dir {:prefix "collet-cli-dist-"})]
      (try
        (process/shell {:dir target} "tar" "-xzf" (str (fs/absolutize archive)))
        (let [root (fs/path target "collet-cli")
              expected {"bb.edn" "rw-r--r--"
                        "collet.bb" "rwxr-xr-x"
                        "collet.pod.jar" "rw-r--r--"
                        "gum" "rwxr-xr-x"}]
          (doseq [[file mode] expected]
            (let [path (fs/path root file)]
              (ensure! (fs/regular-file? path) "CLI archive entry is missing"
                       {:entry file})
              (ensure! (= mode (file-mode path)) "CLI archive mode changed"
                       {:entry file :expected mode :actual (file-mode path)}))))
        (finally
          (fs/delete-tree target))))
    (println "verified application and CLI distributions")))

(defn- verify-no-legacy-build! []
  (ensure! (empty? (fs/glob "." "**/project.clj"))
           "Legacy project.clj files remain" {})
  (ensure! (empty? (fs/glob "." "**/checkouts"))
           "Legacy checkout directories remain" {})
  (ensure! (empty? (fs/glob "." "**/darkstar.jar"))
           "Unpublished Darkstar binary remains" {})
  (let [targets (concat ["bb.edn" "deps.edn" "Dockerfile"]
                        (map str (fs/glob ".github" "**/*"))
                        (map str (fs/glob "build" "**/*"))
                        (map str (fs/glob "build-support" "**/*"))
                        (map str (fs/glob "scripts" "**/*"))
                        (map str (fs/glob "." "collet-*/bb.edn"))
                        (map str (fs/glob "." "collet-*/build.clj"))
                        (map str (fs/glob "." "collet-*/deps.edn"))
                        (map str (fs/glob "." "collet-*/Dockerfile")))
        legacy-tool (str "lei" "n")
        offenders (->> targets
                       (filter fs/regular-file?)
                       (filter #(str/includes? (str/lower-case (slurp %)) legacy-tool))
                       vec)]
    (ensure! (empty? offenders) "A build, CI, Docker, or release path invokes the legacy build tool"
             {:files offenders}))
  (println "verified legacy build paths are absent"))

(defn verify-module-artifact! [module]
  (verify-library! module)
  (verify-dependency-tree! module))

(defn verify []
  (println "\n==> installing and checking publishable library artifacts")
  (workspace/install [])
  (doseq [module (workspace/modules)
          :when (workspace/publish? module)]
    (verify-library! module))
  (println "\n==> checking dependency isolation")
  (doseq [module (workspace/modules)]
    (verify-dependency-tree! module))
  (println "\n==> checking isolated Maven consumers")
  (verify-isolated-consumers!)
  (println "\n==> building and checking deployable artifacts")
  (workspace/build [])
  (verify-deployables!)
  (verify-no-legacy-build!)
  (println "\nAll artifact and compatibility checks passed."))
