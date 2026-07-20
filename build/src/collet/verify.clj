(ns collet.verify
  "Executable compatibility checks for built Collet artifacts."
  (:require
   [clojure.data.xml :as xml]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.set :as set]
   [clojure.string :as str]
   [collet.build :as build]
   [collet.workspace :as workspace])
  (:import
   (java.io StringReader)
   (java.nio.file Files LinkOption)
   (java.nio.file.attribute FileAttribute PosixFilePermissions)
   (java.util Properties)
   (java.util.jar JarFile)))

(def ^:private publication-credential-variables
  ["CLOJARS_USERNAME" "CLOJARS_PASSWORD"])

(defn- fail! [message data]
  (throw (ex-info message data)))

(defn- ensure! [condition message data]
  (when-not condition
    (fail! message data)))

(defn- nondeployment-env []
  (apply dissoc (into {} (System/getenv))
         publication-credential-variables))

(defn- command!
  [dir & command]
  (let [{:keys [exit out err]}
        (apply shell/sh
               (concat command
                       [:dir (str dir) :env (nondeployment-env)]))]
    (when-not (zero? exit)
      (fail! "Command failed"
             {:command (vec command) :dir (str dir) :exit exit :error err}))
    (str out err)))

(defn- delete-tree! [path]
  (let [file (io/file path)]
    (when (.exists file)
      (doseq [entry (reverse (file-seq file))]
        (Files/deleteIfExists (.toPath entry))))))

(defn- temp-directory [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- file? [path]
  (.isFile (io/file path)))

(defn- jar-entries [path]
  (with-open [jar (JarFile. (str path))]
    (into #{} (map #(.getName %)) (enumeration-seq (.entries jar)))))

(defn- jar-entry [path entry]
  (with-open [jar (JarFile. (str path))]
    (let [item (.getEntry jar entry)]
      (ensure! item "Missing JAR entry" {:jar (str path) :entry entry})
      (slurp (.getInputStream jar item)))))

(defn- child-elements [element tag]
  (->> (:content element)
       (filter map?)
       (filter #(= tag (name (:tag %))))))

(defn- only-child [element tag]
  (let [matches (vec (child-elements element tag))]
    (ensure! (= 1 (count matches))
             "POM element must be declared exactly once"
             {:element tag :count (count matches)})
    (first matches)))

(defn- element-text [element]
  (let [content (:content element)]
    (ensure! (every? string? content)
             "POM value must contain only text"
             {:element (name (:tag element))})
    (str/trim (apply str content))))

(defn- child-text [element tag]
  (element-text (only-child element tag)))

(defn- optional-child-text [element tag]
  (when-let [child (first (child-elements element tag))]
    (element-text child)))

(defn- parse-pom [pom]
  (let [project (xml/parse-str pom
                               :support-dtd false
                               :supporting-external-entities false)]
    (ensure! (= "project" (name (:tag project)))
             "Maven POM root element must be project"
             {:root (:tag project)})
    project))

(defn- pom-coordinate [project]
  {:group (child-text project "groupId")
   :artifact (child-text project "artifactId")
   :version (child-text project "version")})

(defn- expected-coordinate [{:keys [fqn version]}]
  {:group (namespace fqn) :artifact (name fqn) :version version})

(defn- dependency-element->data [dependency]
  (let [exclusions (when-let [container
                              (first (child-elements dependency "exclusions"))]
                     (into #{}
                           (map (fn [exclusion]
                                  (symbol (str (child-text exclusion "groupId")
                                               "/"
                                               (child-text exclusion "artifactId")))))
                           (child-elements container "exclusion")))]
    {:lib (symbol (str (child-text dependency "groupId")
                       "/"
                       (child-text dependency "artifactId")))
     :version (child-text dependency "version")
     :extension (or (optional-child-text dependency "type") "jar")
     :exclusions (or exclusions #{})}))

(defn- pom-dependencies [project]
  (if-let [container (first (child-elements project "dependencies"))]
    (mapv dependency-element->data (child-elements container "dependency"))
    []))

(defn- expected-dependencies [{:keys [packages]} package]
  (mapv
   (fn [[lib dependency]]
     (let [internal (get packages lib)
           version (or (:mvn/version dependency) (:version internal))]
       (ensure! version "Published dependency has no exact Maven version"
                {:package (:fqn package) :dependency lib})
       {:lib lib
        :version version
        :extension (or (:extension dependency) "jar")
        :exclusions (set (:exclusions dependency))}))
   (get-in package [:deps-edn :deps])))

(defn verify-pom!
  "Verify a generated POM against Kmono's package graph and exact versions."
  [context package pom]
  (let [project (parse-pom pom)
        expected (expected-coordinate package)
        actual (pom-coordinate project)
        expected-deps (set (expected-dependencies context package))
        actual-deps (set (pom-dependencies project))
        project-metadata (:project context)
        scm (only-child project "scm")
        license (-> project
                    (only-child "licenses")
                    (only-child "license"))]
    (ensure! (= expected actual)
             "POM Maven coordinates do not match"
             {:package (:fqn package) :expected expected :actual actual})
    (ensure! (not (or (str/includes? pom "SNAPSHOT")
                      (str/includes? pom "local/root")
                      (str/includes? pom "local-root")))
             "POM leaks a local-root or snapshot dependency"
             {:package (:fqn package)})
    (ensure! (= expected-deps actual-deps)
             "POM direct dependencies differ"
             {:package (:fqn package)
              :missing (set/difference expected-deps actual-deps)
              :unexpected (set/difference actual-deps expected-deps)})
    (ensure! (= (get-in project-metadata [:scm :url])
                (child-text scm "url"))
             "POM SCM URL does not match" {:package (:fqn package)})
    (ensure! (= (get-in project-metadata [:scm :connection])
                (child-text scm "connection"))
             "POM SCM connection does not match" {:package (:fqn package)})
    (ensure! (= (get-in project-metadata [:scm :developer-connection])
                (child-text scm "developerConnection"))
             "POM SCM developer connection does not match"
             {:package (:fqn package)})
    (ensure! (= (:tag package) (child-text scm "tag"))
             "POM SCM tag does not match the package tag"
             {:package (:fqn package)})
    (ensure! (= (get-in project-metadata [:license :name])
                (child-text license "name"))
             "POM license does not match" {:package (:fqn package)})
    (ensure! (= (get-in project-metadata [:license :url])
                (child-text license "url"))
             "POM license URL does not match" {:package (:fqn package)})
    (ensure! (= (:url project-metadata) (child-text project "url"))
             "POM project URL does not match" {:package (:fqn package)})
    (ensure! (= (get-in package [:artifact :description])
                (child-text project "description"))
             "POM description does not match" {:package (:fqn package)})
    true))

(defn- properties-coordinate [text]
  (let [properties (doto (Properties.)
                     (.load (StringReader. text)))]
    {:group (.getProperty properties "groupId")
     :artifact (.getProperty properties "artifactId")
     :version (.getProperty properties "version")}))

(defn verify-artifact-build-identity!
  [path expected-version expected-revision]
  (let [identity (edn/read-string
                  (jar-entry path "META-INF/collet/build.edn"))]
    (ensure! (= {:version expected-version :revision expected-revision}
                identity)
             "Artifact build identity does not match the release source"
             {:path (str path)
              :expected {:version expected-version
                         :revision expected-revision}
              :actual identity})
    identity))

(defn- namespace-entry [namespace]
  (str (-> (str namespace)
           (str/replace "." "/")
           (str/replace "-" "_"))
       ".clj"))

(defn- pom-entry [{:keys [fqn]}]
  (str "META-INF/maven/" (namespace fqn) "/" (name fqn) "/pom.xml"))

(defn- properties-entry [{:keys [fqn]}]
  (str "META-INF/maven/" (namespace fqn) "/" (name fqn)
       "/pom.properties"))

(defn- package-file-entries [{:keys [absolute-path deps-edn]}]
  (when absolute-path
    (->> (:paths deps-edn)
         (mapcat
          (fn [relative-root]
            (let [root (io/file absolute-path relative-root)]
              (when (.isDirectory root)
                (->> (file-seq root)
                     (filter #(.isFile %))
                     (map #(-> (.relativize (.toPath root) (.toPath %))
                               str
                               (str/replace "\\" "/"))))))))
         set)))

(defn verify-library-artifact!
  "Verify one publishable JAR and its embedded POM/Maven/build metadata."
  [context package path revision]
  (ensure! (file? path) "Library JAR was not built"
           {:package (:fqn package) :path (str path)})
  (let [entries (jar-entries path)
        pom (jar-entry path (pom-entry package))
        properties (jar-entry path (properties-entry package))
        expected (expected-coordinate package)]
    (ensure! (contains? entries "LICENSE") "Library JAR lacks LICENSE"
             {:package (:fqn package)})
    (verify-pom! context package pom)
    (ensure! (= expected (properties-coordinate properties))
             "Artifact JAR Maven properties do not match"
             {:package (:fqn package)
              :expected expected
              :actual (properties-coordinate properties)})
    (verify-artifact-build-identity! path (:version package) revision)
    (when (seq (get-in package [:deps-edn :paths]))
      (doseq [namespace (get-in package [:artifact :public-namespaces])]
        (ensure! (contains? entries (namespace-entry namespace))
                 "Library JAR lacks a preserved namespace source"
                 {:package (:fqn package) :namespace namespace}))
      (doseq [entry (package-file-entries package)]
        (ensure! (contains? entries entry)
                 "Library JAR lacks a source or runtime resource"
                 {:package (:fqn package) :entry entry})))
    (when (= 'io.velio/collet-actions (:fqn package))
      (ensure! (not-any? #(str/starts-with? % "collet/actions/") entries)
               "Compatibility aggregate contains action implementation sources"
               {:package (:fqn package)}))
    (when (= 'io.velio/collet-action-vega (:fqn package))
      (doseq [entry ["vega.js" "vega-lite.js"
                     "applied_science/darkstar.clj"
                     "META-INF/collet/darkstar.edn"
                     "META-INF/licenses/darkstar-MIT.txt"]]
        (ensure! (contains? entries entry)
                 "Vega JAR lacks vendored Darkstar material"
                 {:entry entry})))
    true))

(def ^:private optional-families
  {:postgres ["org.postgresql/postgresql"]
   :mysql ["com.mysql/mysql-connector-j"]
   :aws ["com.cognitect.aws/"]
   :queue ["io.zalky/cues" "net.openhft/chronicle-"]
   :llm ["net.clojars.wkok/openai-clojure"]
   :graal ["org.graalvm."]
   :lucene ["org.apache.lucene/"]})

(defn allowed-optional-families [fqn]
  (get {'io.velio/collet-action-jdbc #{:postgres}
        'io.velio/collet-action-s3 #{:aws}
        'io.velio/collet-action-queue #{:queue}
        'io.velio/collet-action-llm #{:llm}
        'io.velio/collet-action-vega #{:graal}
        'io.velio/collet-action-lucene #{:lucene}
        'io.velio/collet-actions #{:postgres :mysql :aws :queue
                                   :llm :graal :lucene}
        'io.velio/collet-app #{:aws}
        'io.velio/collet-cli #{:aws}}
       fqn #{}))

(defn- verify-dependency-tree! [{:keys [packages]} package]
  (let [fqn (:fqn package)
        tree (command! (:absolute-path package) "clojure" "-Stree")
        allowed (allowed-optional-families fqn)
        allowed-packages (workspace/dependency-closure packages fqn)
        action-packages (->> (keys packages)
                             (filter #(str/starts-with? (name %)
                                                       "collet-action-")))]
    (doseq [[family tokens] optional-families
            :when (not (contains? allowed family))
            token tokens]
      (ensure! (not (str/includes? tree token))
               "Package pulls a forbidden optional dependency family"
               {:package fqn :family family :token token}))
    (doseq [action action-packages
            :when (not (contains? allowed-packages action))]
      (ensure! (not (str/includes? tree (str action)))
               "Package pulls an unrelated action artifact"
               {:package fqn :dependency action}))
    true))

(defn- consumer-form [package]
  (let [requires (str/join " "
                           (map #(str "(require '" % ")")
                                (get-in package
                                        [:artifact :public-namespaces])))
        aggregate-check
        (when (= 'io.velio/collet-actions (:fqn package))
          (str "(require '[collet.core :as collet]) "
               "(let [p (collet/compile-pipeline "
               "{:name :aggregate-compatibility :tasks [{:name :transform "
               ":keep-state true :actions [{:name :apply-template "
               ":type :collet.actions.jslt/apply :params "
               "{:input \"{\\\"answer\\\":42}\" "
               ":template \"{\\\"value\\\":.answer}\"}}]}]})] "
               "@(p {}) (assert (= {:value 42} (:transform p)))) "))]
    (str "(do " requires " " aggregate-check
         "(println :consumer-ok))")))

(defn- verify-consumer! [context repository package]
  (let [directory (temp-directory (str (name (:fqn package)) "-consumer-"))]
    (try
      (spit (io/file directory "deps.edn")
            (pr-str {:paths []
                     :mvn/local-repo repository
                     :deps {(:fqn package)
                            {:mvn/version (:version package)}}}))
      (let [jvm-options (map #(str "-J" %)
                             (get-in context [:project :jvm-opts]))
            output (apply command! directory "clojure" "-Srepro"
                          (concat jvm-options
                                  ["-M" "-e" (consumer-form package)]))]
        (ensure! (str/includes? output ":consumer-ok")
                 "Isolated Maven consumer failed"
                 {:package (:fqn package) :output output}))
      (finally
        (delete-tree! directory)))))

(defn- main-class [path]
  (with-open [jar (JarFile. (str path))]
    (some-> jar .getManifest .getMainAttributes (.getValue "Main-Class"))))

(defn- file-mode [path]
  (PosixFilePermissions/toString
   (Files/getPosixFilePermissions (.toPath (io/file path))
                                  (make-array LinkOption 0))))

(defn- output-path [package output]
  (str (io/file (:absolute-path package) output)))

(defn- verify-deployable! [package revision]
  (let [{:keys [kind main outputs]} (:artifact package)
        uberjar (output-path package (:uberjar outputs))]
    (ensure! (file? uberjar) "Deployable uberjar is missing"
             {:package (:fqn package) :path uberjar})
    (ensure! (= (str main) (main-class uberjar))
             "Deployable entrypoint changed"
             {:package (:fqn package) :expected main
              :actual (main-class uberjar)})
    (verify-artifact-build-identity! uberjar (:version package) revision)
    (when (= :distribution kind)
      (let [archive (output-path package (:archive outputs))
            target (temp-directory "collet-distribution-")]
        (try
          (ensure! (file? archive) "Distribution archive is missing"
                   {:package (:fqn package) :path archive})
          (command! target "tar" "-xzf" archive)
          (let [root (io/file target (:root outputs))
                expected (into {} (map (juxt :to :mode)) (:files outputs))
                actual-files (->> (file-seq root)
                                  (filter #(.isFile %))
                                  (map #(.toString (.relativize (.toPath root)
                                                                (.toPath %))))
                                  set)]
            (ensure! (= (set (keys expected)) actual-files)
                     "Distribution archive layout changed"
                     {:expected (set (keys expected)) :actual actual-files})
            (doseq [[relative mode] expected]
              (let [path (io/file root relative)]
                (ensure! (= mode (file-mode path))
                         "Distribution archive mode changed"
                         {:entry relative :expected mode
                          :actual (file-mode path)}))))
          (finally
            (delete-tree! target)))))
    true))

(defn- verify-no-legacy-build! [root]
  (let [root-file (io/file root)
        files (->> (file-seq root-file)
                   (filter #(.isFile %))
                   (remove #(some #{"target" ".git" ".cpcache"}
                                  (map str (iterator-seq
                                            (.iterator (.toPath %)))))))
        legacy-projects (filter #(= "project.clj" (.getName %)) files)
        legacy-tool (str "lei" "n")
        operational? (fn [file]
                       (let [relative (str (.relativize (.toPath root-file)
                                                       (.toPath file)))]
                         (or (#{"bb.edn" "deps.edn" "Dockerfile"} relative)
                             (str/starts-with? relative ".github/")
                             (str/starts-with? relative "build/")
                             (str/starts-with? relative "scripts/")
                             (and (str/starts-with? relative "collet-")
                                  (or (str/ends-with? relative "/deps.edn")
                                      (str/ends-with? relative "/bb.edn")
                                      (str/ends-with? relative "/build.clj")
                                      (str/ends-with? relative "/Dockerfile"))))))
        offenders (->> files
                       (filter operational?)
                       (filter #(str/includes? (str/lower-case (slurp %))
                                               legacy-tool))
                       vec)]
    (ensure! (empty? legacy-projects) "Legacy project files remain"
             {:files (mapv str legacy-projects)})
    (ensure! (empty? offenders)
             "A supported build path invokes the legacy build tool"
             {:files (mapv str offenders)}))
  true)

(defn- verify-vega-golden! [{:keys [packages]}]
  (let [package (get packages 'io.velio/collet-action-vega)]
    (command! (:absolute-path package)
              "clojure" "-M:test"
              "-v" "collet.actions.vega-test/write-vega-into-svg-test"
              "-e" ":integration"))
  true)

(defn verify
  "Build, install, consume, and inspect every workspace artifact."
  [_opts]
  (let [{:keys [root packages] :as context}
        (workspace/resolve-release-plan! nil)
        order (workspace/package-order packages)
        versions (into {} (map (juxt key (comp :version val))) packages)
        revision (str/trim (command! root "git" "rev-parse" "HEAD"))
        repository (temp-directory "collet-m2-")
        build-options {:versions versions :source-revision revision}]
    (try
      (println "\n==> building and installing Kmono packages")
      (build/install (assoc build-options :mvn/local-repo repository))
      (build/build build-options)
      (println "\n==> checking publishable artifacts")
      (doseq [fqn order
              :let [package (get packages fqn)]
              :when (get-in package [:artifact :publish?])]
        (let [jar (output-path package
                               (str "target/" (name fqn) "-"
                                    (:version package) ".jar"))]
          (verify-library-artifact! context package jar revision)))
      (println "\n==> checking optional dependency isolation")
      (doseq [fqn order]
        (verify-dependency-tree! context (get packages fqn)))
      (println "\n==> checking isolated Maven consumers")
      (doseq [fqn order
              :let [package (get packages fqn)]
              :when (get-in package [:artifact :publish?])]
        (verify-consumer! context repository package))
      (println "\n==> checking application and CLI outputs")
      (doseq [fqn order
              :let [package (get packages fqn)]
              :when (#{:uberjar :distribution}
                      (get-in package [:artifact :kind]))]
        (verify-deployable! package revision))
      (println "\n==> checking Vega/Darkstar golden output")
      (verify-vega-golden! context)
      (verify-no-legacy-build! root)
      (println "\nAll artifact and compatibility checks passed.")
      {:versions versions :verified true}
      (finally
        (delete-tree! repository)))))
