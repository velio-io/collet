(ns release
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [verify :as verify]
   [versioning :as versioning]
   [workspace :as workspace])
  (:import
   (java.io FileNotFoundException)
   (java.net URL)
   (java.nio.channels FileChannel)
   (java.nio.file CopyOption Files OpenOption StandardCopyOption
                  StandardOpenOption)
   (java.nio.file.attribute FileAttribute)
   (java.security MessageDigest)))

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

(defn- release-state-path []
  (fs/path "target" ".collet" "release-state.edn"))

(defn- release-artifact-dir []
  (fs/path "target" ".collet" "release-artifacts"))

(defn- release-lock-path []
  (fs/path "target" ".collet" "release.lock"))

(defn- with-release-lock [operation]
  (let [path (release-lock-path)]
    (fs/create-dirs (fs/parent path))
    (with-open [channel
                (FileChannel/open
                 path
                 (into-array OpenOption [StandardOpenOption/CREATE
                                         StandardOpenOption/WRITE]))]
      (let [lock (try
                   (.tryLock channel)
                   (catch Exception error
                     (if (= "java.nio.channels.OverlappingFileLockException"
                            (.getName (class error)))
                       nil
                       (throw error))))]
        (ensure! lock "Another release command is already running"
                 {:lock (str path)})
        ;; Closing the channel releases every lock it owns, including on an
        ;; exception from the release operation.
        (operation)))))

(defn- sibling-temporary-file [path]
  (let [path (fs/path path)
        parent (fs/parent path)]
    (fs/create-dirs parent)
    (Files/createTempFile
     parent
     (str "." (fs/file-name path) ".")
     ".tmp"
     (make-array FileAttribute 0))))

(defn- move-replacing! [source destination]
  (try
    (Files/move (fs/path source) (fs/path destination)
                (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                        StandardCopyOption/REPLACE_EXISTING]))
    (catch Exception error
      (if (= "java.nio.file.AtomicMoveNotSupportedException"
             (.getName (class error)))
        (Files/move (fs/path source) (fs/path destination)
                    (into-array CopyOption
                                [StandardCopyOption/REPLACE_EXISTING]))
        (throw error)))))

(defn- write-release-state! [state]
  (let [path (release-state-path)
        temporary (sibling-temporary-file path)]
    (try
      (spit (str temporary) (pr-str state))
      (ensure! (= state (edn/read-string (slurp (str temporary))))
               "Release recovery state did not round-trip"
               {:path (str path)})
      (move-replacing! temporary path)
      state
      (finally
        (Files/deleteIfExists temporary)))))

(defn- read-release-state []
  (let [path (release-state-path)]
    (when (fs/regular-file? path)
      (let [state (edn/read-string (slurp (str path)))]
        (ensure! (= 1 (:schema state))
                 "Unsupported or corrupt release recovery state"
                 {:path (str path) :schema (:schema state)})
        state))))

(defn- clear-release-state! []
  (Files/deleteIfExists (release-state-path)))

(defn- clear-release-artifacts! []
  (when (fs/exists? (release-artifact-dir))
    (fs/delete-tree (release-artifact-dir))))

(defn- bytes->hex [bytes]
  (apply str (map #(format "%02x" (bit-and 0xff %)) bytes)))

(defn- stream-sha256 [input]
  (let [digest (MessageDigest/getInstance "SHA-256")
        buffer (byte-array 16384)]
    (loop []
      (let [read (.read input buffer)]
        (when (pos? read)
          (.update digest buffer 0 read)
          (recur))))
    (bytes->hex (.digest digest))))

(defn- file-sha256 [path]
  (with-open [input (Files/newInputStream (fs/path path)
                                          (make-array java.nio.file.OpenOption 0))]
    (stream-sha256 input)))

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

(defn- ensure-version-and-pins! [expected-version]
  (let [actual-version (:version (workspace/manifest))]
    (ensure! (= expected-version actual-version)
             "Workspace version does not match the captured release"
             {:expected expected-version :actual actual-version})
    (ensure! (empty? (versioning/plan-version-update "." expected-version))
             "Release graph or internal pins changed after quality gates"
             {:version expected-version})))

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
  (let [base-commit (command-output "git" "rev-parse" "HEAD")]
    (println "Release preflight passed for" current "as" tag)
    {:base-commit base-commit}))

(defn- stage-version! [version]
  (let [paths (mapv :path (versioning/set-version! version))]
    (when (seq paths)
      (apply shell! "git" "add" "--" paths))
    paths))

(defn- commit! [message paths]
  (ensure! (seq paths) "Release commit has no managed version paths"
           {:message message})
  (apply shell! "git" "commit" "-m" message "--" paths)
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

(defn- copy-replacing! [source destination]
  (fs/create-dirs (fs/parent destination))
  (Files/copy (fs/path source) (fs/path destination)
              (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING])))

(defn- freeze-artifacts! [module artifacts]
  (let [{:keys [lib version]} (workspace/module-config module)
        directory (fs/path (release-artifact-dir) (name module))
        jar (fs/path directory (str (name lib) "-" version ".jar"))
        pom (fs/path directory (str (name lib) "-" version ".pom"))]
    (copy-replacing! (:jar artifacts) jar)
    (copy-replacing! (:pom artifacts) pom)
    {:jar (str (fs/absolutize jar))
     :pom (str (fs/absolutize pom))
     :jar-sha256 (file-sha256 jar)
     :pom-sha256 (file-sha256 pom)
     :coordinate {:lib lib :version version}}))

(defn- stage-artifacts! [{:keys [modules]}]
  (let [staging-repo (fs/create-temp-dir {:prefix "collet-release-stage-"})]
    (try
      (clear-release-artifacts!)
      (reduce (fn [staged module]
                (workspace/install-module-to! module (str staging-repo))
                (verify/verify-module-artifact! module)
                (assoc staged module
                       (freeze-artifacts!
                        module
                        (ensure-artifacts! module (artifact-paths module)))))
              {}
              modules)
      (finally
        (fs/delete-tree staging-repo)))))

(defn- verify-release-artifact!
  [{:keys [release release-commit]} module artifacts]
  (let [{:keys [jar pom jar-sha256 pom-sha256 coordinate]}
        (ensure-artifacts! module artifacts)
        {:keys [lib version]} (workspace/module-config module)]
    (ensure! (= {:lib lib :version release} coordinate)
             "Staged release coordinate changed after capture"
             {:module module :expected {:lib lib :version release}
              :actual coordinate})
    (ensure! (= release version)
             "Workspace module coordinate changed after staging"
             {:module module :expected release :actual version})
    (verify/verify-pom-maven-coordinate! (slurp pom) lib release)
    (ensure! (= jar-sha256 (file-sha256 jar))
             "Release JAR changed after staging" {:module module})
    (ensure! (= pom-sha256 (file-sha256 pom))
             "Release POM changed after staging" {:module module})
    (verify/verify-artifact-maven-coordinate! jar lib release)
    (verify/verify-artifact-build-identity!
     jar release release-commit)
    artifacts))

(defn- production-predeploy! [context staged]
  (ensure-main-branch!)
  (ensure-clean!)
  (let [head (command-output "git" "rev-parse" "HEAD")]
    (ensure! (= (:release-commit context) head)
             "Release checkout moved after quality gates"
             {:expected (:release-commit context) :actual head}))
  (ensure-version-and-pins! (:release context))
  (ensure! (= (set (:modules context)) (set (keys staged)))
           "Staged release modules differ from the captured graph"
           {:expected (:modules context) :actual (vec (keys staged))})
  (doseq [module (:modules context)]
    (verify-release-artifact! context module (get staged module)))
  staged)

(defn- deployment-env []
  (let [environment (into {} (System/getenv))]
    (merge (workspace/nondeployment-env environment)
           (select-keys environment
                        workspace/publication-credential-variables))))

(defn- deploy-artifacts! [module artifacts]
  (let [{:keys [jar pom jar-sha256 pom-sha256]}
        (ensure-artifacts! module artifacts)
        {:keys [lib version]} (workspace/module-config module)]
    (ensure! (or (nil? jar-sha256) (= jar-sha256 (file-sha256 jar)))
             "Release JAR changed before deployment" {:module module})
    (ensure! (or (nil? pom-sha256) (= pom-sha256 (file-sha256 pom)))
             "Release POM changed before deployment" {:module module})
    ;; deps-deploy receives the exact files that passed staging verification.
    (process/shell {:env (deployment-env)} "clojure" "-X:release"
                   ":installer" ":remote"
                   ":sign-releases?" "false"
                   ":artifact" (pr-str jar)
                   ":pom-file" (pr-str pom))
    (println "Deployed" lib version)))

(defn- remote-artifact-url [lib version extension]
  (str "https://repo.clojars.org/"
       (str/replace (namespace lib) "." "/") "/"
       (name lib) "/" version "/"
       (name lib) "-" version "." extension))

(defn- remote-sha256 [url]
  (try
    (with-open [input (.openStream (URL. url))]
      (stream-sha256 input))
    (catch FileNotFoundException _
      nil)))

(defn- deployment-status! [_ {:keys [coordinate jar-sha256 pom-sha256]}]
  (let [{:keys [lib version]} coordinate]
    (try
      (let [remote-jar (remote-sha256 (remote-artifact-url lib version "jar"))
            remote-pom (remote-sha256 (remote-artifact-url lib version "pom"))]
        (cond
          (and (nil? remote-jar) (nil? remote-pom)) :absent
          (and (= jar-sha256 remote-jar)
               (= pom-sha256 remote-pom)) :matching
          (or (nil? remote-jar) (nil? remote-pom)) :partial
          :else :mismatch))
      (catch Throwable _
        :unknown))))

(defn- create-tag! [tag release-commit]
  (let [existing (command-output "git" "tag" "--list" tag)]
    (if (str/blank? existing)
      (shell! "git" "tag" tag release-commit)
      (let [actual (command-output "git" "rev-parse" (str "refs/tags/" tag "^{}"))]
        (ensure! (= release-commit actual)
                 "Existing release tag points to a different commit"
                 {:tag tag :expected release-commit :actual actual})))))

(defn- commit-paths [commit]
  (->> (str/split-lines
        (command-output "git" "diff-tree" "--no-commit-id"
                        "--name-only" "-r" commit))
       (remove str/blank?)
       set))

(defn- ensure-owned-commit!
  [commit expected-parent expected-message expected-paths]
  (let [parent (command-output "git" "rev-parse" (str commit "^"))
        message (command-output "git" "show" "-s" "--format=%s" commit)
        paths (commit-paths commit)]
    (ensure! (= expected-parent parent)
             "Release recovery found a commit with the wrong parent"
             {:commit commit :expected expected-parent :actual parent})
    (ensure! (= expected-message message)
             "Release recovery found an unexpected commit message"
             {:commit commit :expected expected-message :actual message})
    (ensure! (= (set expected-paths) paths)
             "Release recovery found unexpected committed paths"
             {:commit commit :expected (set expected-paths) :actual paths})
    commit))

(defn- verify-source-images!
  [{:keys [snapshot-changes]} allowed-images]
  (ensure! (seq snapshot-changes)
           "Snapshot recovery state lacks captured source images" {})
  (doseq [{:keys [path] :as change} snapshot-changes
          :let [actual (slurp path)]]
    (ensure! (some #(= actual (get change %)) allowed-images)
             "Snapshot recovery source differs from its captured images"
             {:path path :allowed allowed-images})))

(defn- prepare-snapshot!
  [{:keys [release-commit release next-snapshot managed-paths]}]
  (ensure-main-branch!)
  (ensure-clean!)
  (let [head (command-output "git" "rev-parse" "HEAD")]
    (ensure! (= release-commit head)
             "main moved before the next-snapshot update"
             {:expected release-commit :actual head}))
  (ensure-version-and-pins! release)
  (let [changes (versioning/plan-version-update "." next-snapshot)
        source-images (mapv #(select-keys % [:path :before :after]) changes)]
    (ensure! (= (set managed-paths) (set (map :path source-images)))
             "Next-snapshot paths differ from the release-owned paths"
             {:expected (set managed-paths)
              :actual (set (map :path source-images))})
    source-images))

(defn- snapshot-commit-status!
  [{:keys [release-commit next-snapshot managed-paths]
    :as state}]
  (ensure-main-branch!)
  (let [head (command-output "git" "rev-parse" "HEAD")]
    (if (= release-commit head)
      (do
        (verify-source-images! state #{:before :after})
        nil)
      (do
        (ensure-owned-commit!
         head release-commit (str "Begin " next-snapshot) managed-paths)
        (verify-source-images! state #{:after})
        (ensure-version-and-pins! next-snapshot)
        head))))

(defn- prepush!
  [{:keys [tag release-commit snapshot-commit next-snapshot managed-paths]}]
  (let [branch-commit (command-output "git" "rev-parse" "refs/heads/main")
        tag-commit (command-output "git" "rev-parse" (str "refs/tags/" tag "^{}"))]
    (ensure! (= release-commit tag-commit)
             "Release tag moved before push"
             {:tag tag :expected release-commit :actual tag-commit})
    (ensure! (= snapshot-commit branch-commit)
             "main moved before the atomic release push"
             {:expected snapshot-commit :actual branch-commit})
    (ensure-owned-commit!
     snapshot-commit release-commit (str "Begin " next-snapshot) managed-paths)
    (ensure-version-and-pins! next-snapshot)))

(defn- push-release! [{:keys [remote branch tag atomic?]}]
  (ensure! atomic? "Release push must be atomic" {:atomic? atomic?})
  (shell! "git" "push" "--atomic" remote branch
          (str "refs/tags/" tag)))

(defn- rollback-release! [{:keys [base-commit release-commit current
                                  managed-paths release]}]
  (ensure-main-branch!)
  (let [head (command-output "git" "rev-parse" "HEAD")
        active-release-commit
        (when-not (= base-commit head)
          (if release-commit
            (do
              (ensure! (= release-commit head)
                       "Automatic rollback refused because HEAD moved"
                       {:expected release-commit :actual head})
              release-commit)
            (ensure-owned-commit!
             head base-commit (str "Release " release) managed-paths)))]
    (let [paths (vec (distinct (concat managed-paths
                                       (map :path
                                            (versioning/set-version! current)))))]
      (when (seq paths)
        (apply shell! "git" "add" "--" paths))
      (when active-release-commit
        (shell! "git" "update-ref" "refs/heads/main"
                base-commit active-release-commit))
      (when (seq paths)
        (apply shell! "git" "restore" "--staged"
               (str "--source=" base-commit) "--" paths)))
    (ensure-version-and-pins! current)
    (println "Rolled back unpublished release state to" current)))

(def production-ops
  {:load-state! read-release-state
   :save-state! write-release-state!
   :clear-state! clear-release-state!
   :clear-artifacts! clear-release-artifacts!
   :preflight! production-preflight!
   :set-version! stage-version!
   :commit! commit!
   :test! workspace/test-all
   :verify! verify/verify
   :stage! stage-artifacts!
   :predeploy! production-predeploy!
   :deployment-status! deployment-status!
   :deploy! deploy-artifacts!
   :tag! create-tag!
   :prepare-snapshot! prepare-snapshot!
   :snapshot-commit-status! snapshot-commit-status!
   :verify-source-images! verify-source-images!
   :prepush! prepush!
   :push! push-release!
   :rollback! rollback-release!})

(defn- save-state! [ops state]
  ((:save-state! ops) state)
  state)

(defn- clear-state! [ops]
  ((:clear-artifacts! ops))
  ((:clear-state! ops)))

(defn- rollback-predeployment! [ops state failure]
  (try
    ((:rollback! ops) state)
    (clear-state! ops)
    (throw failure)
    (catch Throwable rollback-error
      (if (identical? rollback-error failure)
        (throw failure)
        (throw (ex-info
                "Release failed before publication and automatic rollback failed"
                {:release-error (ex-message failure)
                 :rollback-error (ex-message rollback-error)}
                rollback-error))))))

(defn- start-release! [context ops]
  (let [preflight (or ((:preflight! ops) context) {})
        state (atom (merge {:schema 1
                            :phase :predeploy
                            :completed []
                            :in-flight nil}
                           context
                           preflight))]
    (save-state! ops @state)
    (try
      (let [managed-paths (vec ((:set-version! ops) (:release context)))]
        (swap! state assoc :managed-paths managed-paths)
        (save-state! ops @state)
        (let [release-commit
              ((:commit! ops) (str "Release " (:release context)) managed-paths)]
          (swap! state assoc :release-commit release-commit)
          (save-state! ops @state)
          ((:test! ops))
          ((:verify! ops))
          (let [staged ((:stage! ops) @state)]
            ((:predeploy! ops) @state staged)
            (swap! state assoc
                   :phase :deploying
                   :artifacts staged
                   :completed []
                   :in-flight nil)
            (save-state! ops @state))))
      @state
      (catch Throwable failure
        (rollback-predeployment! ops @state failure)))))

(defn- validate-resume-input! [input state]
  (let [identity-keys [:current :release :next-snapshot :tag :level :modules]]
    (ensure! (= (select-keys input identity-keys)
                (select-keys state identity-keys))
             "Release arguments do not match the pending recovery state"
             {:requested (select-keys input identity-keys)
              :pending (select-keys state identity-keys)}))
  (let [completed (:completed state)
        expected-prefix (vec (take (count completed) (:modules state)))]
    (ensure! (= expected-prefix completed)
             "Release recovery state has a non-prefix deployment history"
             {:completed completed :expected-prefix expected-prefix}))
  state)

(defn- prepare-deployment-resume! [ops state]
  (if (= :deploying (:phase state))
    (try
      ((:predeploy! ops) state (:artifacts state))
      state
      (catch Throwable failure
        (if (and (empty? (:completed state))
                 (nil? (:in-flight state)))
          (rollback-predeployment! ops state failure)
          (throw failure))))
    state))

(defn- complete-deployments! [ops state]
  (letfn [(coordinate [current module]
            (or (get-in current [:artifacts module :coordinate]) module))
          (publication-data [current module status]
            {:status status
             :completed-coordinates
             (mapv #(coordinate current %) (:completed current))
             :in-flight-coordinate (coordinate current module)})]
    (reduce
     (fn [current module]
       (if (some #{module} (:completed current))
         current
         (let [artifacts (get (:artifacts current) module)
               status ((:deployment-status! ops) module artifacts)]
           (case status
             :matching
             (save-state! ops (-> current
                                  (update :completed conj module)
                                  (assoc :in-flight nil)))

             :absent
             (let [in-flight (save-state! ops (assoc current :in-flight module))]
               (try
                 ((:deploy! ops) module artifacts)
                 (catch Throwable failure
                   (throw (ex-info
                           (or (ex-message failure) "Maven deployment failed")
                           (merge (or (ex-data failure) {})
                                  (publication-data in-flight module :failed))
                           failure))))
               (save-state! ops (-> in-flight
                                    (update :completed conj module)
                                    (assoc :in-flight nil))))

             (fail! "Remote deployment requires manual reconciliation"
                    (publication-data current module status))))))
     state
     (:modules state))))

(defn- continue-release! [ops initial-state]
  (loop [state initial-state]
    (case (:phase state)
      :deploying
      (let [deployed (assoc (complete-deployments! ops state)
                            :phase :deployed :in-flight nil)
            deployed (save-state! ops deployed)]
        (recur deployed))

      :deployed
      (do
        ((:tag! ops) (:tag state) (:release-commit state))
        (recur (save-state! ops (assoc state :phase :tagged))))

      :tagged
      (let [source-images ((:prepare-snapshot! ops) state)]
        (recur (save-state! ops (assoc state
                                       :phase :snapshot-committing
                                       :snapshot-changes source-images))))

      :snapshot-committing
      (if-let [snapshot-commit ((:snapshot-commit-status! ops) state)]
        (recur (save-state! ops (assoc state
                                       :phase :snapshot-committed
                                       :snapshot-commit snapshot-commit)))
        (let [changed (vec ((:set-version! ops) (:next-snapshot state)))
              paths (vec (distinct (concat (:managed-paths state) changed)))
              _ ((:verify-source-images! ops) state #{:after})
              snapshot-commit
              ((:commit! ops) (str "Begin " (:next-snapshot state)) paths)]
          (recur (save-state! ops (assoc state
                                         :phase :snapshot-committed
                                         :managed-paths paths
                                         :snapshot-commit snapshot-commit)))))

      :snapshot-committed
      (recur (save-state! ops (assoc state :phase :pushing)))

      :pushing
      (do
        ((:prepush! ops) state)
        ((:push! ops) {:remote "origin"
                       :branch "main"
                       :tag (:tag state)
                       :atomic? true})
        (clear-state! ops)
        state)

      (fail! "Unsupported release recovery phase"
             {:phase (:phase state)}))))

(defn execute-release!
  [{:keys [current level modules]} ops]
  (let [release (versioning/release-version current)
        next-snapshot (versioning/next-snapshot-version release level)
        tag (str "v" release)
        context {:current current
                 :release release
                 :next-snapshot next-snapshot
                 :tag tag
                 :level level
                 :modules modules}]
    (if-let [pending ((:load-state! ops))]
      (do
        (validate-resume-input! context pending)
        (if (= :predeploy (:phase pending))
          (do
            ((:rollback! ops) pending)
            (clear-state! ops)
            (continue-release! ops (start-release! context ops)))
          (continue-release! ops (prepare-deployment-resume! ops pending))))
      (continue-release! ops (start-release! context ops)))))

(defn- ensure-tag-checkout! [tag]
  (let [[_ version] (re-matches #"^v(\d+\.\d+\.\d+)$" tag)]
    (ensure! version "Release tag must be vMAJOR.MINOR.PATCH" {:tag tag})
    (versioning/parse-version version)
    (ensure-clean!)
    (let [branch (command-output "git" "branch" "--show-current")
          head (command-output "git" "rev-parse" "HEAD")
          revision (command-output "git" "rev-parse" (str tag "^{}"))]
      (ensure! (str/blank? branch)
               "Release publication must run from a detached tag worktree"
               {:branch branch :tag tag})
      (ensure! (= revision head)
               "Publication checkout does not match the release tag"
               {:tag tag :expected revision :actual head})
      (ensure-version-and-pins! version)
      {:tag tag :version version :revision revision})))

(defn verify-cli-command [args]
  (ensure! (= 1 (count args))
           "Usage: bb release:verify-cli v<version>" {:args (vec args)})
  (let [{:keys [version revision] :as context}
        (ensure-tag-checkout! (first args))
        {:keys [lib]} (workspace/module-config :collet-cli)
        pod (fs/path "collet-cli/target/collet.pod.jar")
        archive (fs/path "collet-cli/target/collet-cli.tar.gz")]
    (doseq [path [pod archive]]
      (ensure! (fs/regular-file? path)
               "CLI release artifact is missing" {:path (str path)}))
    (verify/verify-artifact-build-identity! pod version revision)
    (verify/verify-artifact-maven-coordinate! pod lib version)
    (let [directory (fs/create-temp-dir {:prefix "collet-cli-release-"})]
      (try
        (process/shell (workspace/nondeployment-process-options {:dir directory})
                       "tar" "-xzf" (str (fs/absolutize archive)))
        (let [archived-pod (fs/path directory "collet-cli" "collet.pod.jar")]
          (ensure! (fs/regular-file? archived-pod)
                   "CLI archive lacks collet.pod.jar" {})
          (verify/verify-artifact-build-identity!
           archived-pod version revision)
          (verify/verify-artifact-maven-coordinate! archived-pod lib version))
        (finally
          (fs/delete-tree directory))))
    (println "Verified CLI artifacts for" (:tag context) revision)
    context))

(defn verify-image-command [args]
  (ensure! (= 2 (count args))
           "Usage: bb release:verify-image v<version> <local-image>"
           {:args (vec args)})
  (let [[tag image] args
        {:keys [version revision] :as context} (ensure-tag-checkout! tag)
        labels (command-output
                "docker" "image" "inspect"
                "--format"
                (str "{{ index .Config.Labels \"org.opencontainers.image.version\" }} "
                     "{{ index .Config.Labels \"org.opencontainers.image.revision\" }}")
                image)]
    (ensure! (= (str version " " revision) labels)
             "Docker image identity does not match the release tag"
             {:image image :tag tag})
    (let [directory (fs/create-temp-dir {:prefix "collet-image-release-"})
          jar (fs/path directory "collet.jar")
          container (command-output "docker" "create" image)]
      (try
        (shell! "docker" "cp" (str container ":/app/collet.jar") (str jar))
        (verify/verify-artifact-build-identity! jar version revision)
        (verify/verify-artifact-maven-coordinate!
         jar (:lib (workspace/module-config :collet-app)) version)
        (finally
          (try
            (shell! "docker" "rm" "-f" container)
            (finally
              (fs/delete-tree directory))))))
    (println "Verified Docker image for" tag revision)
    context))

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
  (let [level (release-level args)]
    (with-release-lock
      (fn []
        (let [pending ((:load-state! production-ops))
              input (if pending
                      {:current (:current pending)
                       :level level
                       :modules (:modules pending)}
                      (let [graph (workspace/manifest)]
                        {:current (:version graph)
                         :level level
                         :modules (->> (workspace/modules)
                                       (filter workspace/publish?)
                                       vec)}))]
          (execute-release! input production-ops))))))
