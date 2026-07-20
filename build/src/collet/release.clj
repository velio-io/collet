(ns collet.release
  "Local, independently versioned package release planning and execution."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.data.xml :as xml]
   [collet.build :as build]
   [collet.workspace :as workspace]
   [k16.kmono.version :as kmono.version])
  (:import
   (java.io StringReader)
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
   (java.nio.file Files)
   (java.util Properties)
   (java.util.jar JarFile)))

(defn- fail! [message data]
  (throw (ex-info message data)))

(def publication-credential-variables
  ["CLOJARS_USERNAME" "CLOJARS_PASSWORD"])

(defn nondeployment-env
  "Return a child-process environment without publication credentials."
  ([] (nondeployment-env (System/getenv)))
  ([environment]
   (apply dissoc (into {} environment) publication-credential-variables)))

(defn validate-preflight!
  "Validate the local-only release source invariants."
  [{:keys [branch status head remote-head]}]
  (when-not (= "main" branch)
    (fail! "Releases require the main branch" {:branch branch}))
  (when-not (str/blank? status)
    (fail! "Releases require a clean worktree" {:status status}))
  (when-not (= head remote-head)
    (fail! "Local main must equal origin/main"
           {:head head :remote-head remote-head}))
  {:revision head})

(defn resolve-command-plan!
  "Resolve a fresh release plan or resumable package tags already at HEAD."
  [root opts]
  (let [fresh (workspace/resolve-release-plan! root opts)]
    (if (seq (:selected fresh))
      fresh
      (workspace/resolve-pending-release-plan! root opts))))

(defn- direct-elements [element]
  (->> (:content element)
       (filter associative?)
       (group-by #(name (:tag %)))))

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

(defn- direct-pom-coordinate [pom]
  (let [project (xml/parse-str pom
                               :support-dtd false
                               :supporting-external-entities false)]
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
  "Verify direct POM metadata and the JAR's embedded version/revision.

  Returns `:matching` or `:mismatch`; absence/partial state is determined by
  the remote transport before calling this function."
  [{:keys [fqn version tag]} revision pom-text jar-path]
  (try
    (let [group (namespace fqn)
          artifact (name fqn)
          expected {:group group
                    :artifact artifact
                    :version version
                    :scm-tag tag}
          pom-coordinate (direct-pom-coordinate pom-text)
          prefix (str "META-INF/maven/" group "/" artifact "/")
          embedded-pom (jar-entry jar-path (str prefix "pom.xml"))
          properties-text (jar-entry jar-path (str prefix "pom.properties"))
          identity-text (jar-entry jar-path "META-INF/collet/build.edn")
          properties (Properties.)]
      (when properties-text
        (.load properties (StringReader. properties-text)))
      (if (and (= expected pom-coordinate)
               (= expected (direct-pom-coordinate embedded-pom))
               (= version (.getProperty properties "version"))
               (= group (.getProperty properties "groupId"))
               (= artifact (.getProperty properties "artifactId"))
               (= {:version version :revision revision}
                  (some-> identity-text edn/read-string)))
        :matching
        :mismatch))
    (catch Exception _
      :mismatch)))

(defn- verify-jar [package revision jar-path]
  (let [group (namespace (:fqn package))
        artifact (name (:fqn package))
        prefix (str "META-INF/maven/" group "/" artifact "/")
        pom-text (jar-entry jar-path (str prefix "pom.xml"))]
    (verify-publication package revision pom-text jar-path)))

(defn- command-result [options command]
  (apply shell/sh (concat command
                          (mapcat identity options))))

(defn- command! [options & command]
  (let [{:keys [exit out err] :as result}
        (command-result options command)]
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
  (command! {} "git" "-C" root "fetch" "origin" "--tags")
  nil)

(defn- production-preflight! [root]
  (validate-preflight!
   {:branch (git-output root "branch" "--show-current")
    :status (git-output root "status" "--porcelain")
    :head (git-output root "rev-parse" "HEAD")
    :remote-head (git-output root "rev-parse" "origin/main")}))

(defn- require-credentials! []
  (let [environment (System/getenv)
        missing (filterv #(str/blank? (get environment %))
                         publication-credential-variables)]
    (when (seq missing)
      (fail! "Clojars credentials are required for publishable packages"
             {:variables missing}))))

(defn- quality-gate! [root task]
  (command! {:dir root :env (nondeployment-env)} "bb" task)
  nil)

(defn- build-release! [plan revision release-plan]
  (let [versions (into {}
                       (map (fn [[fqn package]] [fqn (:version package)]))
                       (:packages plan))]
    (reduce
     (fn [artifacts package]
       (let [result (build/build {:module (:fqn package)
                                  :versions versions
                                  :source-revision revision})]
         (assoc artifacts (:fqn package)
                (get-in result [:artifacts (:fqn package)]))))
     {}
     release-plan)))

(defn- remote-artifact-url [{:keys [fqn version]} extension]
  (str "https://repo.clojars.org/"
       (str/replace (namespace fqn) "." "/") "/"
       (name fqn) "/" version "/"
       (name fqn) "-" version "." extension))

(defn- http-artifact [client url]
  (let [request (-> (HttpRequest/newBuilder (URI/create url))
                    (.GET)
                    (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofByteArray))
        status (.statusCode response)]
    (case status
      200 (.body response)
      404 nil
      (fail! "Cannot inspect remote Maven publication"
             {:url url :status status}))))

(defn- publication-status! [package revision]
  (let [client (HttpClient/newHttpClient)
        pom-bytes (http-artifact client (remote-artifact-url package "pom"))
        jar-bytes (http-artifact client (remote-artifact-url package "jar"))]
    (cond
      (and (nil? pom-bytes) (nil? jar-bytes))
      :absent

      (or (nil? pom-bytes) (nil? jar-bytes))
      :partial

      :else
      (let [path (Files/createTempFile "collet-release-" ".jar"
                                       (make-array java.nio.file.attribute.FileAttribute 0))]
        (try
          (Files/write path jar-bytes (make-array java.nio.file.OpenOption 0))
          (verify-publication package revision
                              (String. pom-bytes "UTF-8")
                              (str path))
          (finally
            (Files/deleteIfExists path)))))))

(defn- deploy! [root package {:keys [jar-file pom-file]}]
  (when-not (and jar-file pom-file)
    (fail! "Publishable package build did not produce JAR and POM"
           {:package (:fqn package)}))
  (command! {:dir root :env (into {} (System/getenv))}
            "clojure" "-X:release"
            ":installer" ":remote"
            ":sign-releases?" "false"
            ":artifact" (pr-str jar-file)
            ":pom-file" (pr-str pom-file))
  nil)

(defn production-ops
  "Construct production operations for one local release plan."
  [plan]
  (let [root (:root plan)]
    {:fetch-tags! #(fetch-tags! root)
     :preflight! #(production-preflight! root)
     :require-credentials! require-credentials!
     :test! #(quality-gate! root "test")
     :verify! #(quality-gate! root "verify")
     :build! #(build-release! plan %2 %1)
     :tag-target! #(git-tag-target root %)
     :create-tags! (fn [tags target]
                     (let [commands (str "start\n"
                                         (str/join ""
                                                   (map #(str "create refs/tags/" %
                                                              " " target "\n")
                                                        tags))
                                         "prepare\ncommit\n")]
                       (command! {:in commands}
                                 "git" "-C" root "update-ref" "--stdin")))
     :publication-status! publication-status!
     :deploy! #(deploy! root %1 %2)
     :push-tags! (fn [tags]
                   (apply command! {} "git" "-C" root "push" "--atomic"
                          "origin" tags))}))

(defn release-packages
  "Return selected release packages in the plan's topological order."
  [{:keys [packages selected]}]
  (mapv packages selected))

(defn format-plan
  "Render a read-only independent package release plan."
  [plan]
  (let [rows (into [["PACKAGE" "CURRENT" "NEXT" "REASON" "TAG" "PUBLICATION"]]
                   (map (fn [{:keys [fqn current-version version reason tag publish?]}]
                          [(str fqn)
                           current-version
                           version
                           (name reason)
                           tag
                           (if publish? "Maven" "tag only")]))
                   (release-packages plan))
        widths (apply mapv (fn [& cells] (apply max (map count cells))) rows)
        render (fn [row]
                 (str/join "  "
                           (map (fn [width cell]
                                  (format (str "%-" width "s") cell))
                                widths row)))]
    (str/join "\n" (map render rows))))

(defn- normalize-status [status]
  (if (map? status) (:status status) status))

(defn execute-release!
  "Execute a local release transaction through injected side-effect operations.

  All package tags are created before the first Maven deployment. Existing tags
  at the release revision are resumable; Maven coordinates are either verified
  and skipped or deployed in package topological order."
  [plan ops]
  (let [release-plan (release-packages plan)
        publishable (filterv :publish? release-plan)
        tags (mapv :tag release-plan)]
    ((:fetch-tags! ops))
    (let [{:keys [revision]} ((:preflight! ops))
          targets (into {} (map (juxt identity (:tag-target! ops))) tags)
          existing-targets (into {} (filter (comp some? val)) targets)
          resume? (and (seq tags)
                       (every? #(= revision (get targets %)) tags))
          collisions (into {}
                           (filter (fn [[_ target]]
                                     (and target (not= revision target))))
                           targets)]
      (when (seq collisions)
        (fail! "Package release tag points to a different revision"
               {:revision revision :tags collisions}))
      (when (and (seq existing-targets) (not resume?))
        (fail! "Local package release tags are incomplete"
               {:revision revision :tags existing-targets :expected tags}))
      (when (seq publishable)
        ((:require-credentials! ops)))
      ((:test! ops))
      ((:verify! ops))
      (let [{current-revision :revision} ((:preflight! ops))]
        (when-not (= revision current-revision)
          (fail! "Release source changed during quality gates"
                 {:expected revision :actual current-revision}))
        (let [artifacts ((:build! ops) release-plan revision)
              {built-revision :revision} ((:preflight! ops))]
          (when-not (= revision built-revision)
            (fail! "Release source changed during artifact construction"
                   {:expected revision :actual built-revision}))
          (let [statuses (into {}
                               (map (fn [package]
                                      [(:fqn package)
                                       (normalize-status
                                        ((:publication-status! ops)
                                         package revision))]))
                               publishable)
                invalid (into {}
                              (filter (fn [[_ status]]
                                        (#{:partial :mismatch} status)))
                              statuses)]
            (when (seq invalid)
              (fail! "Remote Maven publication is partial or mismatched"
                     {:packages invalid}))
            (let [matching (into {}
                                 (filter (fn [[_ status]]
                                           (= :matching status)))
                                 statuses)]
              (when (and (seq matching) (not resume?))
                (fail! "Remote Maven coordinate already exists for a fresh release"
                       {:packages (keys matching)})))
            (let [missing-tags (filterv #(nil? (get targets %)) tags)]
              (when (seq missing-tags)
                ((:create-tags! ops) missing-tags revision)))
            (let [skipped (->> publishable
                               (filter #(= :matching (get statuses (:fqn %))))
                               (mapv :fqn))
                  missing (filterv #(= :absent (get statuses (:fqn %)))
                                   publishable)]
              (doseq [package missing]
                ((:deploy! ops) package (get artifacts (:fqn package))))
              ((:push-tags! ops) tags)
              {:revision revision
               :tags tags
               :deployed (mapv :fqn missing)
               :skipped skipped
               :tag-only (->> release-plan
                              (remove :publish?)
                              (mapv :fqn))})))))))

(defn- module-option [module]
  (when module
    (if (or (keyword? module) (symbol? module))
      module
      (keyword module))))

(defn plan
  "Print a read-only package release plan."
  [{:keys [root module] :or {root "."}}]
  (let [release-plan (workspace/resolve-release-plan!
                      root {:module (module-option module)})]
    (println (format-plan release-plan))
    release-plan))

(defn release
  "Run a guarded local package release, optionally filtered by module."
  [{:keys [root module] :or {root "."}}]
  ;; Planning must observe the fetched tag set. execute-release! still owns the
  ;; fetch phase for injected tests, so the production operation becomes a
  ;; no-op after this initial fetch.
  (fetch-tags! root)
  (let [release-plan (resolve-command-plan!
                      root {:module (module-option module)})]
    (if (empty? (:selected release-plan))
      (do
        (println "No packages require release.")
        {:tags [] :deployed [] :skipped [] :tag-only []})
      (execute-release! release-plan
                        (assoc (production-ops release-plan)
                               :fetch-tags! (constantly nil))))))

(defn release-all
  "Release the complete changed package graph."
  [opts]
  (release (dissoc opts :module)))

(defn- ensure-tag-checkout! [root tag expected-fqn]
  (let [version (kmono.version/match-package-version-tag tag expected-fqn)]
    (when-not version
      (fail! "Release tag must be <coordinate>@<version>"
             {:tag tag :package expected-fqn}))
    (let [status (git-output root "status" "--porcelain")
          branch (git-output root "branch" "--show-current")
          head (git-output root "rev-parse" "HEAD")
          revision (git-tag-target root tag)]
      (when-not (str/blank? status)
        (fail! "Release verification requires a clean worktree"
               {:status status}))
      (when-not (str/blank? branch)
        (fail! "Release verification must run from a detached tag worktree"
               {:branch branch :tag tag}))
      (when-not (= head revision)
        (fail! "Publication checkout does not match the package tag"
               {:tag tag :expected revision :actual head}))
      (let [plan (workspace/resolve-pending-release-plan! root)
            package (get-in plan [:packages expected-fqn])]
        (when-not (and (:release? package) (= version (:version package)))
          (fail! "Package tag does not match the checkout package version"
                 {:tag tag :package expected-fqn}))
        {:tag tag :version version :revision revision :package package}))))

(defn verify-cli
  "Verify CLI artifacts from a detached CLI package-tag checkout."
  [{:keys [root tag] :or {root "."}}]
  (let [fqn 'io.velio/collet-cli
        {:keys [revision package] :as context}
        (ensure-tag-checkout! root tag fqn)
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
        (command! {:dir (str directory) :env (nondeployment-env)}
                  "tar" "-xzf" (str archive))
        (let [archived-pod (fs/path directory "collet-cli" "collet.pod.jar")]
          (when-not (fs/regular-file? archived-pod)
            (fail! "CLI archive lacks collet.pod.jar" {}))
          (when-not (= :matching (verify-jar package revision archived-pod))
            (fail! "Archived CLI JAR identity does not match its package tag"
                   {:jar (str archived-pod)})))
        (finally
          (fs/delete-tree directory))))
    (println "Verified CLI artifacts for" tag revision)
    context))

(defn verify-image
  "Verify an application image against an app package tag."
  [{:keys [root tag image] :or {root "."}}]
  (let [fqn 'io.velio/collet-app
        {:keys [version revision package] :as context}
        (ensure-tag-checkout! root tag fqn)
        labels (command-output
                {}
                "docker" "image" "inspect" "--format"
                (str "{{ index .Config.Labels \"org.opencontainers.image.version\" }} "
                     "{{ index .Config.Labels \"org.opencontainers.image.revision\" }}")
                image)]
    (when-not (= (str version " " revision) labels)
      (fail! "Docker image identity does not match the app package tag"
             {:image image :tag tag :labels labels}))
    (let [directory (fs/create-temp-dir {:prefix "collet-image-release-"})
          jar (fs/path directory "collet.jar")
          container (command-output {} "docker" "create" image)]
      (try
        (command! {} "docker" "cp" (str container ":/app/collet.jar") (str jar))
        (when-not (= :matching (verify-jar package revision jar))
          (fail! "Image application JAR identity does not match its package tag"
                 {:jar (str jar)}))
        (finally
          (try
            (command! {} "docker" "rm" "-f" container)
            (finally
              (fs/delete-tree directory))))))
    (println "Verified Docker image for" tag revision)
    context))
