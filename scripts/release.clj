(ns release
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [verify :as verify]
   [workspace :as workspace]))

(defn- fail! [message data]
  (throw (ex-info message data)))

(defn- output [& command]
  (let [{:keys [exit out err]}
        @(process/process command {:out :string :err :string})]
    (when-not (zero? exit)
      (fail! "Command failed" {:command command :exit exit :error err}))
    (str/trim out)))

(defn- ensure-clean! []
  (let [status (output "git" "status" "--porcelain")]
    (when (seq status)
      (fail! "Releases require a clean worktree" {:status status}))))

(defn- snapshot? [version]
  (str/ends-with? version "-SNAPSHOT"))

(defn- dependency-closure [module]
  (letfn [(visit [current seen]
            (if (contains? seen current)
              seen
              (reduce #(visit %2 %1)
                      (conj seen current)
                      (:internal-deps (workspace/module-config current)))))]
    (visit module #{})))

(defn- ensure-release-versions! [module]
  (doseq [dependency (dependency-closure module)
          :let [{:keys [version]} (workspace/module-config dependency)]]
    (when (snapshot? version)
      (fail! "Release versions and all internal dependencies must be non-snapshot"
             {:module module :dependency dependency :version version}))))

(defn- ensure-deps-match-graph! [module]
  (let [{:keys [dir internal-deps]} (workspace/module-config module)
        deps (:deps (edn/read-string (slurp (str (fs/path dir "deps.edn")))))]
    (doseq [dependency internal-deps
            :let [{:keys [lib version]} (workspace/module-config dependency)
                  declared (get-in deps [lib :mvn/version])]]
      (when-not (= version declared)
        (fail! "Module deps.edn does not pin the graph's exact internal version"
               {:module module :dependency dependency
                :expected version :actual declared})))))

(defn- ensure-credentials! []
  (doseq [variable ["CLOJARS_USERNAME" "CLOJARS_PASSWORD"]]
    (when (str/blank? (System/getenv variable))
      (fail! "Clojars credentials are required for release"
             {:missing variable}))))

(defn- tag-name [module]
  (let [{:keys [lib version]} (workspace/module-config module)]
    (str (name lib) "-v" version)))

(defn- ensure-new-tag! [tag]
  (let [{:keys [exit]}
        @(process/process ["git" "rev-parse" "--verify" (str "refs/tags/" tag)]
                          {:out :string :err :string})]
    (when (zero? exit)
      (fail! "Release tag already exists" {:tag tag}))))

(defn- artifact-path [{:keys [dir lib version]}]
  (fs/absolutize
   (fs/path dir "target" (str (name lib) "-" version ".jar"))))

(defn- pom-path [{:keys [dir lib]}]
  (fs/absolutize
   (fs/path dir "target/classes/META-INF/maven"
            (namespace lib) (name lib) "pom.xml")))

(defn- deploy! [module]
  (let [config (workspace/module-config module)
        artifact (artifact-path config)
        pom (pom-path config)]
    (doseq [path [artifact pom]]
      (when-not (fs/regular-file? path)
        (fail! "Release artifact is missing" {:module module :path (str path)})))
    ;; deps-deploy reads Clojars credentials from the environment. Arguments
    ;; are explicit so publication never infers or rewrites a module version.
    (process/shell "clojure" "-X:release"
                   ":installer" ":remote"
                   ":sign-releases?" "false"
                   ":artifact" (pr-str (str artifact))
                   ":pom-file" (pr-str (str pom)))))

(defn- preflight! [module]
  (let [{:keys [lib version]} (workspace/module-config module)
        tag (tag-name module)]
    (when-not (workspace/publish? module)
      (fail! "Module is deployable but not a published Maven artifact"
             {:module module}))
    (ensure-release-versions! module)
    (doseq [dependency (dependency-closure module)]
      (ensure-deps-match-graph! dependency))
    (ensure-new-tag! tag)
    (println "Preflight passed for" lib version)))

(defn- prepare-single! [module remote-repo]
  (let [{:keys [lib version]} (workspace/module-config module)]
    (println "Testing" lib version)
    (workspace/test-module [(name module)])
    ;; A fresh Maven repository forces every internal coordinate to resolve
    ;; from the configured remotes. A locally built dependency with the same
    ;; version cannot make a single-module release appear valid.
    (workspace/build-library! module remote-repo)
    (verify/verify-module-artifact! module)))

(defn- prepare-staged! [module staging-repo]
  (let [{:keys [lib version]} (workspace/module-config module)]
    (println "Testing" lib version)
    (workspace/test-module [(name module)])
    ;; release:all prepares in graph order, so this repository contains only
    ;; artifacts that have already passed this same source checkout's gate.
    (workspace/install-module-to! module staging-repo)
    (verify/verify-module-artifact! module)))

(defn- publish! [module]
  (let [{:keys [lib version]} (workspace/module-config module)
        tag (tag-name module)]
    (deploy! module)
    (process/shell "git" "tag" tag)
    (println "Released" lib version "and created local tag" tag)))

(defn- publishable-modules []
  (->> (workspace/modules)
       (filter workspace/publish?)
       vec))

(defn release-module [args]
  (when-not (= 1 (count args))
    (fail! "Usage: bb release <module>" {:args args}))
  (let [module (workspace/module-key (first args))
        remote-repo (fs/create-temp-dir {:prefix "collet-release-m2-"})]
    (try
      (ensure-clean!)
      (preflight! module)
      (ensure-credentials!)
      (prepare-single! module (str remote-repo))
      (ensure-clean!)
      (publish! module)
      (finally
        (fs/delete-tree remote-repo)))))

(defn release-all []
  (let [modules (publishable-modules)
        staging-repo (fs/create-temp-dir {:prefix "collet-release-stage-"})]
    (try
      (ensure-clean!)
      ;; Complete every deterministic guard before the first irreversible
      ;; remote deployment. Network failure can still yield a partial remote
      ;; release, but a later version, tag, test, or artifact failure cannot.
      (doseq [module modules]
        (preflight! module))
      (ensure-credentials!)
      (doseq [module modules]
        (prepare-staged! module (str staging-repo)))
      (ensure-clean!)
      (doseq [module modules]
        (publish! module))
      (finally
        (fs/delete-tree staging-repo)))))
