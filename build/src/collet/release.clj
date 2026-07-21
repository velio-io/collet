(ns collet.release
  "Fail-fast local package release planning and execution."
  (:require
   [babashka.fs :as fs]
   [clojure.data.xml :as xml]
   [clojure.edn :as edn]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [collet.build :as build]
   [k16.kmono.version :as kmono.version])
  (:import
   (java.io StringReader)
   (java.util Properties)
   (java.util.jar JarFile)))

(def ^:private publication-credential-variables
  ["CLOJARS_USERNAME" "CLOJARS_PASSWORD"])

(defn- fail! [message data]
  (throw (ex-info message data)))

(defn nondeployment-env
  "Return a child-process environment without publication credentials."
  ([] (nondeployment-env (System/getenv)))
  ([environment]
   (apply dissoc (into {} environment) publication-credential-variables)))

(defn validate-preflight!
  "Validate the source revision that will be released."
  [{:keys [branch status head remote-head]}]
  (when-not (= "main" branch)
    (fail! "Releases require the main branch" {:branch branch}))
  (when-not (str/blank? status)
    (fail! "Releases require a clean worktree" {:status status}))
  (when-not (= head remote-head)
    (fail! "Local main must equal origin/main"
           {:head head :remote-head remote-head}))
  {:revision head})

(defn validate-credentials!
  "Require Clojars credentials only when the selected plan publishes Maven."
  [environment packages]
  (when (some :publish? packages)
    (let [missing (filterv #(str/blank? (get environment %))
                           publication-credential-variables)]
      (when (seq missing)
        (fail! "Clojars credentials are required for publishable packages"
               {:variables missing}))))
  nil)

(defn release-steps
  "Describe the fixed release pipeline for ordered candidate packages."
  [packages]
  (into [:quality-gate :build]
        (concat (map (fn [{:keys [fqn]}] [:deploy fqn])
                     (filter :publish? packages))
                [:tag :push])))

(defn format-plan
  "Render package/current/next/reason/tag/publication release candidates."
  [packages]
  (let [rows (into [["PACKAGE" "CURRENT" "NEXT" "REASON" "TAG" "PUBLICATION"]]
                   (map (fn [{:keys [fqn current-version version reason tag publish?]}]
                          [(str fqn)
                           current-version
                           version
                           (name reason)
                           tag
                           (if publish? "Maven" "tag only")]))
                   packages)
        widths (apply mapv (fn [& cells] (apply max (map count cells))) rows)]
    (str/join "\n"
              (map (fn [row]
                     (str/join "  "
                               (map (fn [width cell]
                                      (format (str "%-" width "s") cell))
                                    widths row)))
                   rows))))

(defn- module-option [module]
  (when module
    (if (or (keyword? module) (symbol? module)) module (keyword module))))

(defn- candidate-plan [root module]
  (let [context (build/resolve-context! root {:changes? true})
        fqn (module-option module)
        selected (build/release-packages context fqn)]
    {:context context
     :packages (mapv #(get (:packages context) %) selected)}))

(defn- command-result [options command]
  (apply shell/sh (concat command (mapcat identity options))))

(defn- command! [options & command]
  (let [{:keys [exit out err] :as result} (command-result options command)]
    (when-not (str/blank? out) (print out))
    (when-not (str/blank? err) (binding [*out* *err*] (print err)))
    (when-not (zero? exit)
      (fail! "Command failed"
             {:command (vec command) :exit exit :error (str/trim err)}))
    result))

(defn- command-output [options & command]
  (-> (apply command! options command) :out str/trim))

(defn- git-output [root & args]
  (apply command-output {} "git" "-C" root args))

(defn- git-tag-target [root tag]
  (let [{:keys [exit out]}
        (command-result {} ["git" "-C" root "rev-parse" "--verify" "--quiet"
                            (str tag "^{}")])]
    (when (zero? exit) (str/trim out))))

(defn- fetch-tags! [root]
  (command! {} "git" "-C" root "fetch" "origin" "--tags"))

(defn- production-preflight! [root]
  (validate-preflight!
   {:branch (git-output root "branch" "--show-current")
    :status (git-output root "status" "--porcelain")
    :head (git-output root "rev-parse" "HEAD")
    :remote-head (git-output root "rev-parse" "origin/main")}))

(defn- quality-gate! [root task]
  (command! {:dir root :env (nondeployment-env)} "bb" task))

(defn- build-release! [context packages revision]
  (build/build-packages context (mapv :fqn packages)
                        {:source-revision revision}))

(defn- deploy! [root package artifact]
  (let [{:keys [jar-file pom-file]} artifact]
    (when-not (and jar-file pom-file)
      (fail! "Publishable package build did not produce JAR and POM"
             {:package (:fqn package)}))
    (command! {:dir root :env (into {} (System/getenv))}
              "clojure" "-X:release"
              ":installer" ":remote"
              ":sign-releases?" "false"
              ":artifact" (pr-str jar-file)
              ":pom-file" (pr-str pom-file))))

(defn- create-tags! [root packages]
  (doseq [{:keys [tag]} packages]
    (command! {} "git" "-C" root "tag" tag)))

(defn- push-tags! [root packages]
  (apply command! {} "git" "-C" root "push" "--atomic" "origin"
         (map :tag packages)))

(defn plan
  "Print a read-only Kmono package release plan."
  [{:keys [root module] :or {root "."}}]
  (let [{:keys [packages] :as result} (candidate-plan root module)]
    (println (format-plan packages))
    result))

(defn release
  "Run the guarded fail-fast release pipeline, optionally for one module."
  [{:keys [root module] :or {root "."}}]
  (fetch-tags! root)
  (let [{:keys [context packages]} (candidate-plan root module)]
    (if (empty? packages)
      (do
        (println "No packages require release.")
        {:tags [] :deployed [] :tag-only []})
      (let [{:keys [revision]} (production-preflight! root)]
        (validate-credentials! (System/getenv) packages)
        (quality-gate! root "test")
        (quality-gate! root "verify")
        (let [artifacts (:artifacts (build-release! context packages revision))]
          (doseq [package (filter :publish? packages)]
            (deploy! root package (get artifacts (:fqn package))))
          (create-tags! root packages)
          (push-tags! root packages)
          {:revision revision
           :tags (mapv :tag packages)
           :deployed (mapv :fqn (filter :publish? packages))
           :tag-only (mapv :fqn (remove :publish? packages))})))))

(defn release-all
  "Release every changed package."
  [opts]
  (release (dissoc opts :module)))

;; Detached-tag artifact checks are intentionally kept here: they are release
;; workflow smoke checks, independent of the normal publication pipeline.
(defn- direct-elements [element]
  (->> (:content element) (filter associative?) (group-by #(name (:tag %)))))

(defn- element-value [element name]
  (let [content (:content element)]
    (when-not (every? string? content)
      (fail! "POM metadata element must contain only text" {:element name}))
    (let [value (str/trim (apply str content))]
      (when (str/blank? value)
        (fail! "POM metadata element must not be blank" {:element name}))
      value)))

(defn- exactly-one [elements name]
  (let [matches (get elements name)]
    (when-not (= 1 (count matches))
      (fail! "POM metadata must be declared exactly once"
             {:element name :count (count matches)}))
    (first matches)))

(defn- parse-pom [pom]
  (xml/parse-str pom :support-dtd false :supporting-external-entities false))

(defn- direct-pom-coordinate [pom]
  (let [project (parse-pom pom)]
    (when-not (= "project" (name (:tag project)))
      (fail! "Maven POM root element must be project" {:root (:tag project)}))
    (let [elements (direct-elements project)
          scm (exactly-one elements "scm")]
      {:group (element-value (exactly-one elements "groupId") "groupId")
       :artifact (element-value (exactly-one elements "artifactId") "artifactId")
       :version (element-value (exactly-one elements "version") "version")
       :scm-tag (element-value (exactly-one (direct-elements scm) "tag") "tag")})))

(defn- jar-entry [jar-path entry]
  (with-open [jar (JarFile. (str jar-path))]
    (when-let [item (.getEntry jar entry)]
      (slurp (.getInputStream jar item)))))

(defn verify-publication
  "Check a POM and JAR against a package tag and source revision."
  [{:keys [fqn version tag]} revision pom-text jar-path]
  (try
    (let [expected {:group (namespace fqn) :artifact (name fqn)
                    :version version :scm-tag tag}
          prefix (str "META-INF/maven/" (namespace fqn) "/" (name fqn) "/")
          embedded-pom (jar-entry jar-path (str prefix "pom.xml"))
          properties-text (jar-entry jar-path (str prefix "pom.properties"))
          identity-text (jar-entry jar-path "META-INF/collet/build.edn")
          properties (Properties.)]
      (when properties-text (.load properties (StringReader. properties-text)))
      (if (and (= expected (direct-pom-coordinate pom-text))
               (= expected (direct-pom-coordinate embedded-pom))
               (= version (.getProperty properties "version"))
               (= (namespace fqn) (.getProperty properties "groupId"))
               (= (name fqn) (.getProperty properties "artifactId"))
               (= {:version version :revision revision} (some-> identity-text edn/read-string)))
        :matching
        :mismatch))
    (catch Exception _ :mismatch)))

(defn verify-direct-dependency!
  "Require exactly one direct dependency at the expected Maven version."
  [pom expected-fqn expected-version]
  (try
    (let [project (parse-pom pom)
          dependencies (map (fn [dependency]
                              (let [elements (direct-elements dependency)]
                                {:group (element-value (exactly-one elements "groupId") "groupId")
                                 :artifact (element-value (exactly-one elements "artifactId") "artifactId")
                                 :version (element-value (exactly-one elements "version") "version")}))
                            (mapcat #(get (direct-elements %) "dependency")
                                    (get (direct-elements project) "dependencies")))
          matches (filter #(and (= (namespace expected-fqn) (:group %))
                                (= (name expected-fqn) (:artifact %)))
                          dependencies)]
      (when-not (= 1 (count matches))
        (fail! "POM dependency must be declared exactly once"
               {:dependency expected-fqn :count (count matches)}))
      (when-not (= expected-version (:version (first matches)))
        (fail! "POM dependency version does not match"
               {:dependency expected-fqn
                :expected expected-version
                :actual (:version (first matches))}))
      true)
    (catch clojure.lang.ExceptionInfo error (throw error))
    (catch Exception error (throw (ex-info "Malformed Maven POM" {} error)))))

(defn- verify-jar [package revision jar-path]
  (let [fqn (:fqn package)
        pom (jar-entry jar-path (str "META-INF/maven/" (namespace fqn) "/"
                                     (name fqn) "/pom.xml"))]
    (verify-publication package revision pom jar-path)))

(defn verify-image-jar!
  "Verify image application identity and its direct core dependency."
  [package revision core-version jar-path]
  (when-not (= :matching (verify-jar package revision jar-path))
    (fail! "Image application JAR identity does not match its package tag"
           {:jar (str jar-path)}))
  (try
    (let [fqn (:fqn package)
          pom (jar-entry jar-path (str "META-INF/maven/" (namespace fqn) "/"
                                       (name fqn) "/pom.xml"))]
      (verify-direct-dependency! pom 'io.velio/collet-core core-version))
    (catch Exception error
      (throw (ex-info "Application POM does not use the expected core version"
                      {:expected core-version :jar (str jar-path)} error))))
  true)

(defn- ensure-tag-checkout! [root tag expected-fqn]
  (let [version (kmono.version/match-package-version-tag tag expected-fqn)]
    (when-not version
      (fail! "Release tag must be <coordinate>@<version>" {:tag tag :package expected-fqn}))
    (let [status (git-output root "status" "--porcelain")
          branch (git-output root "branch" "--show-current")
          head (git-output root "rev-parse" "HEAD")
          revision (git-tag-target root tag)]
      (when-not (str/blank? status)
        (fail! "Release verification requires a clean worktree" {:status status}))
      (when-not (str/blank? branch)
        (fail! "Release verification must run from a detached tag worktree"
               {:branch branch :tag tag}))
      (when-not (= head revision)
        (fail! "Publication checkout does not match the package tag"
               {:tag tag :expected revision :actual head}))
      (let [{:keys [packages]} (build/resolve-context! root)
            package (get packages expected-fqn)]
        (when-not (= version (:version package))
          (fail! "Package tag does not match the checkout package version"
                 {:tag tag :package expected-fqn}))
        {:tag tag :version version :revision revision :package package :packages packages}))))

(defn verify-cli
  "Verify CLI artifacts from a detached CLI package-tag checkout."
  [{:keys [root tag] :or {root "."}}]
  (let [{:keys [revision package] :as context}
        (ensure-tag-checkout! root tag 'io.velio/collet-cli)
        package-root (:absolute-path package)
        pod (fs/path package-root "target" "collet.pod.jar")
        archive (fs/path package-root "target" "collet-cli.tar.gz")]
    (doseq [path [pod archive]]
      (when-not (fs/regular-file? path)
        (fail! "CLI release artifact is missing" {:path (str path)})))
    (when-not (= :matching (verify-jar package revision pod))
      (fail! "CLI JAR identity does not match its package tag" {:jar (str pod)}))
    (let [directory (fs/create-temp-dir {:prefix "collet-cli-release-"})]
      (try
        (command! {:dir (str directory) :env (nondeployment-env)} "tar" "-xzf" (str archive))
        (let [archived-pod (fs/path directory "collet-cli" "collet.pod.jar")]
          (when-not (fs/regular-file? archived-pod)
            (fail! "CLI archive lacks collet.pod.jar" {}))
          (when-not (= :matching (verify-jar package revision archived-pod))
            (fail! "Archived CLI JAR identity does not match its package tag"
                   {:jar (str archived-pod)})))
        (finally (fs/delete-tree directory))))
    (println "Verified CLI artifacts for" tag revision)
    context))

(defn verify-image
  "Verify an application image against an app package tag."
  [{:keys [root tag image] :or {root "."}}]
  (let [{:keys [version revision package packages] :as context}
        (ensure-tag-checkout! root tag 'io.velio/collet-app)
        core-version (get-in packages ['io.velio/collet-core :version])
        labels (command-output {}
                               "docker" "image" "inspect" "--format"
                               (str "{{ index .Config.Labels \"org.opencontainers.image.version\" }} "
                                    "{{ index .Config.Labels \"org.opencontainers.image.revision\" }}")
                               image)]
    (when (str/blank? core-version)
      (fail! "Cannot resolve the app checkout's core package version"
             {:tag tag :package 'io.velio/collet-core}))
    (when-not (= (str version " " revision) labels)
      (fail! "Docker image identity does not match the app package tag"
             {:image image :tag tag :labels labels}))
    (let [directory (fs/create-temp-dir {:prefix "collet-image-release-"})
          jar (fs/path directory "collet.jar")
          container (command-output {} "docker" "create" image)]
      (try
        (command! {} "docker" "cp" (str container ":/app/collet.jar") (str jar))
        (verify-image-jar! package revision core-version jar)
        (finally
          (try (command! {} "docker" "rm" "-f" container)
               (finally (fs/delete-tree directory))))))
    (println "Verified Docker image for" tag revision)
    context))
