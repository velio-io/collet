(ns release
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.string :as str]
   [verify :as verify]
   [versioning :as versioning]
   [workspace :as workspace]))

(defn- fail! [message data]
  (throw (ex-info message data)))

(defn- command-output [& command]
  (let [{:keys [exit out err]}
        @(process/process command
                          (workspace/nondeployment-process-options
                           {:out :string :err :string}))]
    (when-not (zero? exit)
      (fail! "Command failed"
             {:command command :exit exit :error (str/trim err)}))
    (str/trim out)))

(defn- shell! [& command]
  (apply process/shell (workspace/nondeployment-process-options) command))

(defn- ensure! [condition message data]
  (when-not condition
    (fail! message data)))

(defn- ensure-main-branch! []
  (let [branch (command-output "git" "branch" "--show-current")]
    (ensure! (= "main" branch)
             "Releases must run from the main branch"
             {:branch branch})))

(defn- ensure-clean! []
  (let [status (command-output "git" "status" "--porcelain")]
    (ensure! (str/blank? status)
             "Releases require a clean worktree"
             {:status status})))

(defn- ensure-synced-with-origin! []
  (shell! "git" "fetch" "origin" "main")
  (let [head (command-output "git" "rev-parse" "HEAD")
        fetched-head (command-output "git" "rev-parse" "FETCH_HEAD")
        remote-head (command-output "git" "rev-parse" "origin/main")]
    (ensure! (= head fetched-head remote-head)
             "main must exactly match origin/main"
             {:head head
              :fetched-main fetched-head
              :origin-main remote-head})))

(defn- ensure-snapshot-and-pins! [expected-version]
  (let [actual-version (:version (workspace/manifest))]
    (ensure! (= expected-version actual-version)
             "Workspace version changed during release preflight"
             {:expected expected-version :actual actual-version})
    (ensure! (str/ends-with? actual-version "-SNAPSHOT")
             "Release requires a snapshot workspace version"
             {:version actual-version})
    ;; Planning an idempotent update validates every literal internal Maven
    ;; pin against the graph version without writing any file.
    (versioning/plan-version-update "." actual-version)))

(defn- ensure-credentials! []
  (doseq [variable workspace/publication-credential-variables]
    (ensure! (not (str/blank? (System/getenv variable)))
             "Clojars credentials are required for release"
             {:missing variable})))

(defn- ensure-new-tag! [tag]
  (let [ref (str "refs/tags/" tag)
        local (command-output "git" "tag" "--list" tag)
        remote (command-output "git" "ls-remote" "--tags" "origin" ref)]
    (ensure! (str/blank? local)
             "Release tag already exists locally"
             {:tag tag})
    (ensure! (str/blank? remote)
             "Release tag already exists on origin"
             {:tag tag})))

(defn- production-preflight!
  [{:keys [current tag]}]
  ;; Keep this order deliberate: every guard completes before the first source
  ;; edit or release commit.
  (ensure-main-branch!)
  (ensure-clean!)
  (ensure-synced-with-origin!)
  (ensure-snapshot-and-pins! current)
  (ensure-credentials!)
  (ensure-new-tag! tag)
  (println "Release preflight passed for" current "as" tag))

(defn- stage-version! [version]
  (let [paths (mapv :path (versioning/set-version! version))]
    (when (seq paths)
      (apply shell! "git" "add" "--" paths))
    paths))

(defn- commit! [message]
  (shell! "git" "commit" "-m" message)
  (command-output "git" "rev-parse" "HEAD"))

(defn- artifact-paths [module]
  (let [{:keys [dir lib version]} (workspace/module-config module)]
    {:jar (str (fs/absolutize
                (fs/path dir "target" (str (name lib) "-" version ".jar"))))
     :pom (str (fs/absolutize
                (fs/path dir "target/classes/META-INF/maven"
                         (namespace lib) (name lib) "pom.xml")))}))

(defn- ensure-artifacts! [module {:keys [jar pom] :as artifacts}]
  (doseq [path [jar pom]]
    (ensure! (fs/regular-file? path)
             "Release artifact is missing"
             {:module module :path path}))
  artifacts)

(defn- stage-artifacts! [{:keys [modules]}]
  (let [staging-repo (fs/create-temp-dir {:prefix "collet-release-stage-"})]
    (try
      (reduce (fn [staged module]
                (workspace/install-module-to! module (str staging-repo))
                (verify/verify-module-artifact! module)
                (assoc staged module
                       (ensure-artifacts! module (artifact-paths module))))
              {}
              modules)
      (finally
        (fs/delete-tree staging-repo)))))

(defn- deployment-env []
  (let [environment (into {} (System/getenv))]
    (merge (workspace/nondeployment-env environment)
           (select-keys environment
                        workspace/publication-credential-variables))))

(defn- deploy-artifacts! [module artifacts]
  (let [{:keys [jar pom]} (ensure-artifacts! module artifacts)
        {:keys [lib version]} (workspace/module-config module)]
    ;; deps-deploy receives the exact files that passed staging verification.
    (process/shell {:env (deployment-env)} "clojure" "-X:release"
                   ":installer" ":remote"
                   ":sign-releases?" "false"
                   ":artifact" (pr-str jar)
                   ":pom-file" (pr-str pom))
    (println "Deployed" lib version)))

(defn- create-tag! [tag release-commit]
  (shell! "git" "tag" tag release-commit))

(defn- push-release! [{:keys [remote branch tag atomic?]}]
  (ensure! atomic? "Release push must be atomic" {:atomic? atomic?})
  (shell! "git" "push" "--atomic" remote branch
          (str "refs/tags/" tag)))

(def production-ops
  {:preflight! production-preflight!
   :set-version! stage-version!
   :commit! commit!
   :test! workspace/test-all
   :verify! verify/verify
   :stage! stage-artifacts!
   :deploy! deploy-artifacts!
   :tag! create-tag!
   :push! push-release!})

(defn execute-release!
  [{:keys [current level modules]} ops]
  (let [release (versioning/release-version current)
        next-snapshot (versioning/next-snapshot-version release level)
        tag (str "v" release)
        context {:current current
                 :release release
                 :next-snapshot next-snapshot
                 :tag tag
                 :modules modules}]
    ((:preflight! ops) context)
    ((:set-version! ops) release)
    (let [release-commit ((:commit! ops) (str "Release " release))]
      ((:test! ops))
      ((:verify! ops))
      (let [staged ((:stage! ops) context)]
        (doseq [module modules]
          ((:deploy! ops) module (get staged module))))
      ((:tag! ops) tag release-commit)
      ((:set-version! ops) next-snapshot)
      ((:commit! ops) (str "Begin " next-snapshot))
      ((:push! ops) {:remote "origin"
                     :branch "main"
                     :tag tag
                     :atomic? true})
      (assoc context :release-commit release-commit))))

(defn- release-level [args]
  (case (vec args)
    [] :patch
    [":patch"] :patch
    [":minor"] :minor
    [":major"] :major
    (throw (ex-info "Usage: bb release [:patch|:minor|:major]"
                    {:args (vec args)}))))

(defn release-command [args]
  ;; Parse before reading the graph or entering preflight so malformed input
  ;; cannot trigger Git or network operations.
  (let [level (release-level args)
        graph (workspace/manifest)
        modules (->> (workspace/modules)
                     (filter workspace/publish?)
                     vec)]
    (execute-release! {:current (:version graph)
                       :level level
                       :modules modules}
                      production-ops)))
