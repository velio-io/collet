(ns verify
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.data.xml :as xml]
   [clojure.edn :as edn]
   [clojure.set :as set]
   [clojure.string :as str]
   [workspace :as workspace])
  (:import
   (java.io StringReader)
   (java.nio.file Files LinkOption)
   (java.nio.file.attribute PosixFilePermissions)
   (java.util Properties)
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

(defn verify-artifact-build-identity!
  [path expected-version expected-revision]
  (let [identity (edn/read-string
                  (jar-entry path "META-INF/collet/build.edn"))]
    (ensure! (= {:version expected-version :revision expected-revision}
                identity)
             "Artifact build identity does not match the release source"
             {:path (str path)
              :expected-version expected-version
              :expected-revision expected-revision
              :actual identity})
    identity))

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

(defn- expected-maven-coordinate [lib version]
  {:group (namespace lib)
   :artifact (name lib)
   :version version})

(def ^:private pom-coordinate-elements
  {"groupId" :group
   "artifactId" :artifact
   "version" :version})

(defn- coordinate-element-value [element element-name]
  (let [content (:content element)]
    (ensure! (every? string? content)
             "POM project coordinate element must contain only text"
             {:element element-name})
    (let [value (str/trim (apply str content))]
      (ensure! (not (str/blank? value))
               "POM project coordinate element must not be blank"
               {:element element-name})
      value)))

(defn- pom-project-coordinate [pom]
  (let [project (xml/parse-str pom
                               :support-dtd false
                               :supporting-external-entities false)]
    (ensure! (= "project" (name (:tag project)))
             "Maven POM root element must be project"
             {:root (:tag project)})
    (let [direct-elements
          (->> (:content project)
               (filter associative?)
               (group-by #(name (:tag %))))]
      (into {}
            (map (fn [[element-name coordinate-key]]
                   (let [matches (get direct-elements element-name)]
                     (ensure! (= 1 (count matches))
                              "POM project coordinate must be declared exactly once"
                              {:element element-name :count (count matches)})
                     [coordinate-key
                      (coordinate-element-value (first matches) element-name)])))
            pom-coordinate-elements))))

(defn- properties-coordinate [properties-text]
  (let [properties (doto (Properties.)
                     (.load (StringReader. properties-text)))]
    {:group (.getProperty properties "groupId")
     :artifact (.getProperty properties "artifactId")
     :version (.getProperty properties "version")}))

(defn- verify-coordinate! [actual lib version message data]
  (let [expected (expected-maven-coordinate lib version)]
    (ensure! (= expected actual)
             message
             (assoc data :expected expected :actual actual))
    expected))

(defn verify-pom-maven-coordinate! [pom lib version]
  (verify-coordinate!
   (pom-project-coordinate pom)
   lib version
   "POM Maven coordinates do not match"
   {:lib lib}))

(defn verify-artifact-maven-coordinate! [path lib version]
  (let [config {:lib lib}
        pom (jar-entry path (pom-entry config))
        properties (jar-entry path (properties-entry config))]
    (verify-coordinate!
     (pom-project-coordinate pom)
     lib version
     "Artifact JAR Maven coordinates do not match"
     {:path (str path)})
    (verify-coordinate!
     (properties-coordinate properties)
     lib version
     "Artifact JAR Maven properties do not match"
     {:path (str path)})))

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
          pom (jar-entry path (pom-entry config))]
    (ensure! (contains? entries "LICENSE") "Library JAR lacks LICENSE"
             {:module module})
    (verify-artifact-maven-coordinate! path lib version)
    (doseq [fragment ["https://github.com/velio-io/collet"
                      "Apache License, Version 2.0"]]
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
        @(process/process command
                          (workspace/nondeployment-process-options
                           {:dir dir :out :string :err :string}))]
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
                          (get-in (workspace/manifest) [:project :jvm-opts]))
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
  (let [version (workspace/project-version)
        revision (str/trim (capture! "." "git" "rev-parse" "HEAD"))
        app-thin (library-jar (workspace/module-config :collet-app))
        app-jar (fs/path "collet-app/target/collet.jar")
        pod-jar (fs/path "collet-cli/target/collet.pod.jar")
        archive (fs/path "collet-cli/target/collet-cli.tar.gz")]
    (doseq [path [app-thin app-jar pod-jar archive]]
      (ensure! (fs/regular-file? path) "Deployable artifact is missing"
               {:path (str path)}))
    (doseq [path [app-thin app-jar pod-jar]]
      (verify-artifact-build-identity! path version revision))
    (ensure! (= "collet.main" (main-class app-jar))
             "Application uberjar entrypoint changed" {})
    (ensure! (= "pod.collet.core" (main-class pod-jar))
             "CLI pod entrypoint changed" {})
    (let [target (fs/create-temp-dir {:prefix "collet-cli-dist-"})]
      (try
        (process/shell (workspace/nondeployment-process-options {:dir target})
                       "tar" "-xzf" (str (fs/absolutize archive)))
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

(def ^:private consistency-files
  ["README.md"
   "bb.edn"
   "deps.edn"
   "collet-app/deploy.md"
   "collet-cli/README.md"])

(def ^:private obsolete-support-names
  [(str "build" "-support")
   (str "test" "-support")])

(def ^:private obsolete-release-command-pattern
  (re-pattern
   (str "(?i)" "release" ":all|\\bbb\\s+release\\s+(?:<module>|collet-[a-z-]+)")))

(def ^:private per-artifact-tag-pattern
  (re-pattern
   (str "(?i)(?:<[a-z][a-z0-9-]*>|[a-z][a-z0-9-]*)-v"
        "(?:<version>|version|\\d+\\.\\d+\\.\\d+)")))

(def ^:private publication-guide-requirements
  {"collet-app/deploy.md"
   {:fragments ["git worktree add --detach"
                "COLLET_VERSION"
                "COLLET_REVISION"
                "bb release:verify-image \"$tag\""
                "--push"]
    :ordered ["bb release:verify-image \"$tag\"" "--push"]}
   "collet-cli/README.md"
   {:fragments ["git worktree add --detach"
                "bb build collet-cli"
                "bb release:verify-cli \"$tag\""
                "gh release create \"$tag\""]
    :ordered ["bb release:verify-cli \"$tag\""
              "gh release create \"$tag\""]}})

(defn- verify-publication-guides! []
  (doseq [[path {:keys [fragments ordered]}] publication-guide-requirements
          :let [guide (slurp path)
                missing (remove #(str/includes? guide %) fragments)
                [verification publication] ordered]]
    (ensure! (empty? missing)
             "Publication guide lacks release-source safeguards"
             {:path path :missing (vec missing)})
    (ensure! (< (str/index-of guide verification)
                (str/index-of guide publication))
             "Publication guide verifies after publishing"
             {:path path
              :verification verification
              :publication publication})))

(defn- tracked-files-under [directory]
  (->> (file-seq (fs/file directory))
       (filter fs/regular-file?)
       (map str)))

(defn- consistency-sources []
  ;; Keep this scope to operational configuration and user documentation. Test
  ;; fixtures and sample data may legitimately mention historical names.
  (concat consistency-files
          (tracked-files-under "build")
          (tracked-files-under "scripts")
          (tracked-files-under "docs")
          (map str (fs/glob "." "collet-*/deps.edn"))
          (map str (fs/glob "." "collet-*/bb.edn"))
          (map str (fs/glob "." "collet-*/build.clj"))
          (map str (fs/glob "." "collet-*/Dockerfile"))))

(defn- matching-files [pattern]
  (->> (consistency-sources)
       distinct
       (remove #{"scripts/verify.clj"})
       (filter #(re-find pattern (slurp %)))
       vec))

(defn- verify-repository-consistency! []
  (let [{:keys [version modules]} (workspace/manifest)
        internal-libs (set (map :lib (vals modules)))
        module-versions (->> modules
                             (keep (fn [[module config]]
                                     (when (contains? config :version)
                                       {:module module :version (:version config)})))
                             vec)
        stale-pins (->> modules
                        (mapcat (fn [[module {:keys [dir]}]]
                                  (let [deps (:deps (edn/read-string
                                                     (slurp (str (fs/path dir "deps.edn")))))]
                                    (keep (fn [[lib config]]
                                            (when (contains? internal-libs lib)
                                              (when-not (= version (:mvn/version config))
                                                {:module module
                                                 :dependency lib
                                                 :expected version
                                                 :actual (:mvn/version config)})))
                                          deps))))
                        vec)]
    (ensure! (empty? module-versions)
             "Modules must use the graph's coordinated version"
             {:modules module-versions :version version})
    (ensure! (empty? stale-pins)
             "Internal Maven pins must match the graph's coordinated version"
             {:pins stale-pins :version version})
    (let [obsolete-paths (filter fs/exists? obsolete-support-names)
          obsolete-names (matching-files
                          (re-pattern (str "(?i)" (str/join "|" obsolete-support-names))))
          obsolete-release-commands
          (matching-files obsolete-release-command-pattern)
          per-artifact-tags
          (matching-files per-artifact-tag-pattern)]
      (ensure! (empty? obsolete-paths)
               "Obsolete workspace support paths remain"
               {:paths (vec obsolete-paths)})
      (ensure! (empty? obsolete-names)
               "Obsolete workspace support names are referenced"
               {:files obsolete-names})
      (ensure! (empty? obsolete-release-commands)
               "Obsolete release command is documented or configured"
               {:files obsolete-release-commands})
      (ensure! (empty? per-artifact-tags)
               "Documentation must use one coordinated v<version> release tag"
               {:files per-artifact-tags})))
  (verify-publication-guides!)
  (println "verified repository versioning and release documentation consistency"))

(defn verify-module-artifact! [module]
  (verify-library! module)
  (verify-dependency-tree! module))

(defn verify []
  (println "\n==> checking repository versioning and release documentation consistency")
  (verify-repository-consistency!)
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
