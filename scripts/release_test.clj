(ns release-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is run-tests testing]]
            [release :as release]
            [verify]
            [versioning]
            [workspace :as workspace])
  (:import (java.util.zip ZipEntry ZipOutputStream)))

(def release-input
  {:current "0.2.8-SNAPSHOT"
   :level :patch
   :modules [:collet-core :collet-action-http]})

(defn- fail-once! [failures key message data]
  (when (contains? @failures key)
    (swap! failures disj key)
    (throw (ex-info message data))))

(defn- recording-ops
  ([events] (recording-ops events (atom nil) (atom #{}) (atom {})))
  ([events fail-at]
   (recording-ops events (atom nil) (atom #{fail-at}) (atom {})))
  ([events state failures deployment-statuses]
   {:preflight! (fn [_]
                  (swap! events conj :preflight)
                  {:base-commit :base-commit})
    :load-state! (fn [] @state)
    :save-state! (fn [value]
                   (reset! state value))
    :clear-state! (fn []
                    (reset! state nil))
    :clear-artifacts! (fn [])
    :set-version! (fn [version]
                    (swap! events conj [:set-version version])
                    (fail-once! failures [:set-version version]
                                "set-version failed" {:version version})
                    [:managed-version-file])
    :commit! (fn [message & _]
               (swap! events conj [:commit message])
               (let [phase (if (.startsWith message "Release ")
                             :release-commit
                             :snapshot-commit)]
                 (fail-once! failures phase "commit failed" {:phase phase})
                 phase))
    :test! (fn []
             (swap! events conj :test)
             (fail-once! failures :test "test failed" {}))
    :verify! (fn []
               (swap! events conj :verify)
               (fail-once! failures :verify "verify failed" {}))
    :stage! (fn [_]
              (swap! events conj :stage)
              {:collet-core :core-artifacts
               :collet-action-http :http-artifacts})
    :predeploy! (fn [_ _]
                  (swap! events conj :predeploy)
                  (fail-once! failures :predeploy
                              "release source invariant failed" {}))
    :deployment-status! (fn [module _]
                          (get @deployment-statuses module :absent))
    :deploy! (fn [module _]
               (swap! events conj [:deploy module])
               (fail-once! failures module "deploy failed" {:module module}))
    :tag! (fn [tag commit]
            (swap! events conj [:tag tag commit])
            (fail-once! failures :tag "tag failed" {:tag tag}))
    :prepare-snapshot! (fn [_] [])
    :snapshot-commit-status! (fn [_] nil)
    :verify-source-images! (fn [_ _])
    :prepush! (fn [_])
    :push! (fn [{:keys [atomic?]}]
             (swap! events conj [:push (if atomic? :atomic :non-atomic)])
             (fail-once! failures :push "push failed" {}))
    :rollback! (fn [_]
                 (swap! events conj :rollback))}))

(defn- exception-message [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-message error))))

(defn- private-var [namespace symbol]
  (ns-resolve namespace symbol))

(defn- credential-free-options? [options]
  (and (map? (:env options))
       (not (contains? (:env options) "CLOJARS_USERNAME"))
       (not (contains? (:env options) "CLOJARS_PASSWORD"))))

(defn- write-jar! [path entries]
  (with-open [output (ZipOutputStream. (io/output-stream path))]
    (doseq [[entry content] entries]
      (.putNextEntry output (ZipEntry. entry))
      (.write output (.getBytes content "UTF-8"))
      (.closeEntry output))))

(deftest coordinates-a-release-in-graph-order
  (let [events (atom [])]
    (release/execute-release! release-input (recording-ops events))
    (is (= [:preflight
            [:set-version "0.2.8"]
            [:commit "Release 0.2.8"]
            :test
            :verify
            :stage
            :predeploy
            [:deploy :collet-core]
            [:deploy :collet-action-http]
            [:tag "v0.2.8" :release-commit]
            [:set-version "0.2.9-SNAPSHOT"]
            [:commit "Begin 0.2.9-SNAPSHOT"]
            [:push :atomic]]
           @events))))

(deftest quality-gate-failures-stop-before-deployment-and-release-mutations
  (doseq [[gate expected]
          [[:test [:preflight
                   [:set-version "0.2.8"]
                   [:commit "Release 0.2.8"]
                   :test
                   :rollback]]
           [:verify [:preflight
                     [:set-version "0.2.8"]
                     [:commit "Release 0.2.8"]
                     :test
                     :verify
                     :rollback]]]]
    (testing (name gate)
      (let [events (atom [])]
        (is (= (str (name gate) " failed")
               (exception-message
                #(release/execute-release! release-input
                                           (recording-ops events gate)))))
        (is (= expected @events))))))

(deftest deployment-failure-stops-before-tag-snapshot-and-push
  (let [events (atom [])
        error (try
                (release/execute-release!
                 release-input
                 (recording-ops events :collet-action-http))
                nil
                (catch clojure.lang.ExceptionInfo failure
                  failure))]
    (is (= "deploy failed" (ex-message error)))
    (is (= {:completed-coordinates [:collet-core]
            :in-flight-coordinate :collet-action-http}
           (select-keys (ex-data error)
                        [:completed-coordinates :in-flight-coordinate])))
    (is (= [:preflight
            [:set-version "0.2.8"]
            [:commit "Release 0.2.8"]
            :test
            :verify
            :stage
            :predeploy
            [:deploy :collet-core]
            [:deploy :collet-action-http]]
           @events))))

(deftest tags-the-release-commit
  (let [events (atom [])
        result (release/execute-release! release-input (recording-ops events))]
    (is (= :release-commit (:release-commit result)))
    (is (= [[:tag "v0.2.8" :release-commit]]
           (filterv #(and (vector? %) (= :tag (first %))) @events)))))

(deftest final-source-invariant-failures-roll-back-before-deployment
  (doseq [reason [:dirty-worktree :moved-head]]
    (testing (name reason)
      (let [events (atom [])
            state (atom nil)
            failures (atom #{:predeploy})]
        (is (= "release source invariant failed"
               (exception-message
                #(release/execute-release!
                  release-input
                  (recording-ops events state failures (atom {}))))))
        (is (= [:preflight
                [:set-version "0.2.8"]
                [:commit "Release 0.2.8"]
                :test
                :verify
                :stage
                :predeploy
                :rollback]
               @events))
        (is (nil? @state))))))

(deftest deployment-resume-skips-completed-coordinates
  (let [events (atom [])
        state (atom nil)
        failures (atom #{:collet-action-http})
        ops (recording-ops events state failures (atom {}))]
    (is (= "deploy failed"
           (exception-message #(release/execute-release! release-input ops))))
    (is (= :deploying (:phase @state)))
    (is (= [:collet-core] (:completed @state)))
    (reset! events [])
    (is (map? (release/execute-release! release-input ops)))
    (is (= [:predeploy
            [:deploy :collet-action-http]
            [:tag "v0.2.8" :release-commit]
            [:set-version "0.2.9-SNAPSHOT"]
            [:commit "Begin 0.2.9-SNAPSHOT"]
            [:push :atomic]]
           @events))
    (is (nil? @state))))

(deftest matching-in-flight-coordinate-is-not-redeployed-after-lost-acknowledgement
  (let [events (atom [])
        state (atom {:phase :deploying
                     :current "0.2.8-SNAPSHOT"
                     :release "0.2.8"
                     :next-snapshot "0.2.9-SNAPSHOT"
                     :tag "v0.2.8"
                     :level :patch
                     :modules [:collet-core :collet-action-http]
                     :release-commit :release-commit
                     :artifacts {:collet-core :core-artifacts
                                 :collet-action-http :http-artifacts}
                     :completed [:collet-core]
                     :in-flight :collet-action-http})
        statuses (atom {:collet-action-http :matching})
        ops (recording-ops events state (atom #{}) statuses)]
    (release/execute-release! release-input ops)
    (is (not-any? #(= [:deploy :collet-action-http] %) @events))
    (is (= [:predeploy
            [:tag "v0.2.8" :release-commit]
            [:set-version "0.2.9-SNAPSHOT"]
            [:commit "Begin 0.2.9-SNAPSHOT"]
            [:push :atomic]]
           @events))))

(deftest deployment-resume-rechecks-source-before-any-remote-action
  (doseq [[completed in-flight expected-events retained?]
          [[[] nil [:predeploy :rollback] false]
           [[:collet-core] :collet-action-http [:predeploy] true]]]
    (let [events (atom [])
          state (atom {:schema 1
                       :phase :deploying
                       :current "0.2.8-SNAPSHOT"
                       :release "0.2.8"
                       :next-snapshot "0.2.9-SNAPSHOT"
                       :tag "v0.2.8"
                       :level :patch
                       :modules (:modules release-input)
                       :release-commit :release-commit
                       :artifacts {:collet-core :core-artifacts
                                   :collet-action-http :http-artifacts}
                       :completed completed
                       :in-flight in-flight})
          ops (recording-ops events state (atom #{:predeploy}) (atom {}))]
      (is (= "release source invariant failed"
             (exception-message
              #(release/execute-release! release-input ops))))
      (is (= expected-events @events))
      (is (= retained? (some? @state))))))

(deftest tag-snapshot-and-push-failures-resume-only-incomplete-phases
  (doseq [[failure expected-phase resumed-events]
          [[:tag :deployed
            [[:tag "v0.2.8" :release-commit]
             [:set-version "0.2.9-SNAPSHOT"]
             [:commit "Begin 0.2.9-SNAPSHOT"]
             [:push :atomic]]]
           [:snapshot-commit :snapshot-committing
            [[:set-version "0.2.9-SNAPSHOT"]
             [:commit "Begin 0.2.9-SNAPSHOT"]
             [:push :atomic]]]
           [:push :pushing
            [[:push :atomic]]]]]
    (testing (name failure)
      (let [events (atom [])
            state (atom nil)
            failures (atom #{failure})
            ops (recording-ops events state failures (atom {}))]
        (is (some? (exception-message
                    #(release/execute-release! release-input ops))))
        (is (= expected-phase (:phase @state)))
        (reset! events [])
        (release/execute-release! release-input ops)
        (is (= resumed-events @events))
        (is (nil? @state))))))

(deftest snapshot-commit-lost-acknowledgement-does-not-create-a-second-commit
  (let [events (atom [])
        state (atom {:schema 1
                     :phase :snapshot-committing
                     :current "0.2.8-SNAPSHOT"
                     :release "0.2.8"
                     :next-snapshot "0.2.9-SNAPSHOT"
                     :tag "v0.2.8"
                     :level :patch
                     :modules (:modules release-input)
                     :release-commit :release-commit
                     :managed-paths [:managed-version-file]
                     :artifacts {}
                     :completed (:modules release-input)})
        ops (assoc (recording-ops events state (atom #{}) (atom {}))
                   :snapshot-commit-status! (fn [_] :snapshot-commit))]
    (release/execute-release! release-input ops)
    (is (= [[:push :atomic]] @events))
    (is (nil? @state))))

(deftest snapshot-recovery-refuses-unexpected-release-owned-edits
  (let [verify-images-var (private-var 'release 'verify-source-images!)
        root (fs/create-temp-dir {:prefix "snapshot-source-test-"})
        path (str (fs/path root "modules.edn"))
        state {:snapshot-changes [{:path path
                                   :before "{:version \"0.2.8\"}\n"
                                   :after "{:version \"0.2.9-SNAPSHOT\"}\n"}]}]
    (try
      (spit path "{:version \"0.2.8\" :user-edit true}\n")
      (is (= "Snapshot recovery source differs from its captured images"
             (exception-message
              #(@verify-images-var state #{:before :after}))))
      (is (= "{:version \"0.2.8\" :user-edit true}\n" (slurp path)))
      (finally
        (fs/delete-tree root)))))

(deftest resume-rejects-a-different-release-level
  (let [events (atom [])
        state (atom {:phase :deploying
                     :current "0.2.8-SNAPSHOT"
                     :release "0.2.8"
                     :next-snapshot "0.2.9-SNAPSHOT"
                     :tag "v0.2.8"
                     :level :minor
                     :modules (:modules release-input)
                     :release-commit :release-commit
                     :artifacts {}
                     :completed []})]
    (is (= "Release arguments do not match the pending recovery state"
           (exception-message
            #(release/execute-release!
              release-input
              (recording-ops events state (atom #{}) (atom {}))))))
    (is (empty? @events))))

(deftest production-predeploy-rejects-a-dirty-or-moved-checkout
  (let [predeploy-var (private-var 'release 'production-predeploy!)
        command-output-var (private-var 'release 'command-output)]
    (is (some? predeploy-var))
    (when predeploy-var
      (doseq [[status head expected]
              [[" M unrelated.txt" "release-commit"
                "Releases require a clean worktree"]
               ["" "moved-commit"
                "Release checkout moved after quality gates"]]]
        (with-redefs-fn
          {command-output-var
           (fn [& command]
             (case (vec command)
               ["git" "branch" "--show-current"] "main"
               ["git" "status" "--porcelain"] status
               ["git" "rev-parse" "HEAD"] head))}
          #(is (= expected
                  (exception-message
                   (fn []
                     (@predeploy-var
                      {:release "0.2.8"
                       :release-commit "release-commit"
                       :modules []}
                      {}))))))))))

(deftest durable-release-state-round-trips-and-cleans-up
  (let [path-var (private-var 'release 'release-state-path)
        write-var (private-var 'release 'write-release-state!)
        read-var (private-var 'release 'read-release-state)
        clear-var (private-var 'release 'clear-release-state!)
        root (fs/create-temp-dir {:prefix "release-state-test-"})
        path (fs/path root "target/.collet/release-state.edn")
        state {:schema 1 :phase :deploying :completed [:collet-core]}]
    (try
      (is (every? some? [path-var write-var read-var clear-var]))
      (when (every? some? [path-var write-var read-var clear-var])
        (with-redefs-fn
          {path-var (fn [] path)}
          (fn []
            (@write-var state)
            (is (= state (@read-var)))
            (is (fs/regular-file? path))
            (@clear-var)
            (is (nil? (@read-var)))
            (is (not (fs/exists? path))))))
      (finally
        (fs/delete-tree root)))))

(deftest release-command-loads-recovery-state-before-the-live-graph
  (let [execution (atom nil)
        pending {:phase :deploying
                 :current "0.2.8-SNAPSHOT"
                 :release "0.2.8"
                 :next-snapshot "0.2.9-SNAPSHOT"
                 :tag "v0.2.8"
                 :level :patch
                 :modules [:collet-core]
                 :release-commit :release-commit
                 :artifacts {:collet-core :core-artifacts}
                 :completed []}
        ops (assoc (recording-ops (atom []))
                   :load-state! (fn [] pending))]
    (with-redefs [release/production-ops ops
                  workspace/manifest
                  (fn []
                    (throw (ex-info "live graph must not be read" {})))
                  release/execute-release!
                  (fn [input _]
                    (reset! execution input)
                    :resumed)]
      (is (= :resumed (release/release-command [])))
      (is (= {:current "0.2.8-SNAPSHOT"
              :level :patch
              :modules [:collet-core]}
             @execution)))))

(deftest image-verification-rejects-labels-that-do-not-match-the-tag
  (let [verify-image-var (ns-resolve 'release 'verify-image-command)
        checkout-var (private-var 'release 'ensure-tag-checkout!)
        command-output-var (private-var 'release 'command-output)]
    (is (some? verify-image-var))
    (is (some? checkout-var))
    (when (and verify-image-var checkout-var)
      (with-redefs-fn
        {checkout-var (fn [_]
                        {:tag "v0.2.8"
                         :version "0.2.8"
                         :revision "release-commit"})
         command-output-var (fn [& _]
                              "0.2.9-SNAPSHOT other-commit")}
        #(is (= "Docker image identity does not match the release tag"
                (exception-message
                 (fn []
                   (@verify-image-var ["v0.2.8" "io.velio/collet:0.2.8"])))))))))

(deftest image-verification-checks-the-embedded-app-maven-coordinate
  (let [verify-image-var (ns-resolve 'release 'verify-image-command)
        checkout-var (private-var 'release 'ensure-tag-checkout!)
        command-output-var (private-var 'release 'command-output)
        shell-var (private-var 'release 'shell!)
        coordinate-var #'verify/verify-artifact-maven-coordinate!
        calls (atom [])]
    (with-redefs-fn
      {checkout-var (fn [_]
                      {:tag "v0.2.8"
                       :version "0.2.8"
                       :revision "release-commit"})
       command-output-var
       (fn [& command]
         (if (= ["docker" "create" "local-image"] (vec command))
           "container-id"
           "0.2.8 release-commit"))
       shell-var
       (fn [& command]
         (when (= ["docker" "cp"] (vec (take 2 command)))
           (spit (last command) "placeholder")))
       #'verify/verify-artifact-build-identity!
       (fn [jar version revision]
         (swap! calls conj [:identity version revision (fs/regular-file? jar)]))
       coordinate-var
       (fn [jar lib version]
         (swap! calls conj [:coordinate lib version (fs/regular-file? jar)]))
       #'workspace/module-config
       (fn [_] {:lib 'io.velio/collet-app :version "0.2.8"})}
      (fn []
        (@verify-image-var ["v0.2.8" "local-image"])))
    (is (= [[:identity "0.2.8" "release-commit" true]
            [:coordinate 'io.velio/collet-app "0.2.8" true]]
           @calls))))

(deftest publication-verification-refuses-a-next-snapshot-checkout
  (let [checkout-var (private-var 'release 'ensure-tag-checkout!)
        command-output-var (private-var 'release 'command-output)]
    (with-redefs-fn
      {command-output-var
       (fn [& command]
         (case (vec command)
           ["git" "status" "--porcelain"] ""
           ["git" "branch" "--show-current"] ""
           ["git" "rev-parse" "HEAD"] "release-commit"
           ["git" "rev-parse" "v0.2.8^{}"] "release-commit"))
       #'workspace/manifest (fn [] {:version "0.2.9-SNAPSHOT"})}
      #(is (= "Workspace version does not match the captured release"
              (exception-message (fn [] (@checkout-var "v0.2.8"))))))))

(deftest release-artifacts-require-top-level-pom-and-jar-source-identity
  (let [verify-var (private-var 'release 'verify-release-artifact!)
        sha-var (private-var 'release 'file-sha256)
        root (fs/create-temp-dir {:prefix "release-artifact-test-"})
        jar (str (fs/path root "example-0.2.8.jar"))
        pom (str (fs/path root "example-0.2.8.pom"))
        lib 'io.velio/example
        pom-text (fn [version]
                   (str "<project><modelVersion>4.0.0</modelVersion>"
                        "<groupId>io.velio</groupId>"
                        "<artifactId>example</artifactId>"
                        "<version>" version "</version>"
                        "<dependencies><dependency><groupId>x</groupId>"
                        "<artifactId>y</artifactId><version>0.2.8</version>"
                        "</dependency></dependencies></project>"))
        jar-entries (fn [pom-version properties-version]
                      {"META-INF/collet/build.edn"
                       (pr-str {:version "0.2.8"
                                :revision "release-commit"})
                       "META-INF/maven/io.velio/example/pom.xml"
                       (pom-text pom-version)
                       "META-INF/maven/io.velio/example/pom.properties"
                       (str "groupId=io.velio\nartifactId=example\nversion="
                            properties-version "\n")})]
    (try
      (write-jar! jar (jar-entries "0.2.8" "0.2.8"))
      (spit pom (pom-text "0.2.8"))
      (with-redefs [workspace/module-config
                    (fn [_] {:lib lib :version "0.2.8"})]
        (let [artifacts (fn []
                          {:jar jar
                           :pom pom
                           :jar-sha256 (@sha-var jar)
                           :pom-sha256 (@sha-var pom)
                           :coordinate {:lib lib :version "0.2.8"}})
              context {:release "0.2.8" :release-commit "release-commit"}]
          (is (= jar (:jar (@verify-var context :example (artifacts)))))
          (write-jar! jar (jar-entries "0.2.9-SNAPSHOT" "0.2.8"))
          (is (= "Artifact JAR Maven coordinates do not match"
                 (exception-message
                  #(@verify-var context :example (artifacts)))))
          (write-jar! jar (jar-entries "0.2.8" "0.2.8-SNAPSHOT"))
          (is (= "Artifact JAR Maven properties do not match"
                 (exception-message
                  #(@verify-var context :example (artifacts)))))
          (write-jar! jar (jar-entries "0.2.8" "0.2.8"))
          (spit pom (pom-text "0.2.9-SNAPSHOT"))
          (is (= "POM Maven coordinates do not match"
                 (exception-message
                  #(@verify-var context :example (artifacts)))))))
      (finally
        (fs/delete-tree root)))))

(deftest partial-remote-publication-stops-for-manual-reconciliation
  (let [events (atom [])
        state (atom {:phase :deploying
                     :current "0.2.8-SNAPSHOT"
                     :release "0.2.8"
                     :next-snapshot "0.2.9-SNAPSHOT"
                     :tag "v0.2.8"
                     :level :patch
                     :modules [:collet-core]
                     :release-commit :release-commit
                     :artifacts {:collet-core :core-artifacts}
                     :completed []
                     :in-flight :collet-core})
        statuses (atom {:collet-core :partial})]
    (is (= "Remote deployment requires manual reconciliation"
           (exception-message
            #(release/execute-release!
              (assoc release-input :modules [:collet-core])
              (recording-ops events state (atom #{}) statuses)))))
    (is (= :deploying (:phase @state)))
    (is (= [:predeploy] @events))))

(deftest automatic-rollback-targets-only-release-owned-paths
  (let [rollback-var (private-var 'release 'rollback-release!)
        command-output-var (private-var 'release 'command-output)
        exact-version-var (private-var 'release 'ensure-version-and-pins!)
        shell-var (private-var 'release 'shell!)
        commands (atom [])]
    (with-redefs-fn
      {command-output-var
       (fn [& command]
         (case (vec command)
           ["git" "branch" "--show-current"] "main"
           ["git" "rev-parse" "HEAD"] "release-commit"))
       exact-version-var (fn [_])
       #'versioning/set-version! (fn [_] [])
       shell-var (fn [& command]
                   (swap! commands conj (vec command)))}
      #(do
         (@rollback-var {:base-commit "base-commit"
                         :release-commit "release-commit"
                         :current "0.2.8-SNAPSHOT"
                         :managed-paths ["build/modules.edn"
                                         "collet-app/deps.edn"]})
         (is (= [["git" "add" "--"
                  "build/modules.edn" "collet-app/deps.edn"]
                 ["git" "update-ref" "refs/heads/main"
                  "base-commit" "release-commit"]
                 ["git" "restore" "--staged" "--source=base-commit" "--"
                  "build/modules.edn" "collet-app/deps.edn"]]
                @commands))
         (is (not-any? (fn [command]
                         (some #{"reset" "checkout" "clean"} command))
                       @commands))))))

(deftest automatic-rollback-finishes-after-the-branch-ref-was-restored
  (let [rollback-var (private-var 'release 'rollback-release!)
        command-output-var (private-var 'release 'command-output)
        exact-version-var (private-var 'release 'ensure-version-and-pins!)
        shell-var (private-var 'release 'shell!)
        commands (atom [])]
    (with-redefs-fn
      {command-output-var
       (fn [& command]
         (case (vec command)
           ["git" "branch" "--show-current"] "main"
           ["git" "rev-parse" "HEAD"] "base-commit"))
       exact-version-var (fn [_])
       #'versioning/set-version! (fn [_] [])
       shell-var (fn [& command]
                   (swap! commands conj (vec command)))}
      #(is (nil? (@rollback-var {:base-commit "base-commit"
                                 :release-commit "release-commit"
                                 :release "0.2.8"
                                 :current "0.2.8-SNAPSHOT"
                                 :managed-paths ["build/modules.edn"]}))))
    (is (= [["git" "add" "--" "build/modules.edn"]
            ["git" "restore" "--staged" "--source=base-commit" "--"
             "build/modules.edn"]]
           @commands))))

(deftest release-command-defaults-to-patch
  (let [execution (atom nil)
        graph {:version "0.2.8-SNAPSHOT"}]
    (with-redefs [workspace/manifest (fn [] graph)
                  workspace/modules (fn [] [:collet-core
                                             :collet-action-http
                                             :collet-cli])
                  workspace/publish? (fn [module]
                                       (not= :collet-cli module))
                  release/execute-release!
                  (fn [input ops]
                    (reset! execution [input ops])
                    :released)]
      (is (= :released (release/release-command [])))
      (is (= {:current "0.2.8-SNAPSHOT"
              :level :patch
              :modules [:collet-core :collet-action-http]}
             (first @execution))))))

(deftest release-command-runs-under-a-single-writer-lock
  (let [lock-var (private-var 'release 'with-release-lock)
        locked? (atom false)]
    (is (some? lock-var))
    (when lock-var
      (with-redefs-fn
        {lock-var (fn [operation]
                    (reset! locked? true)
                    (operation))
         #'release/production-ops (recording-ops (atom []))
         #'workspace/manifest (fn [] {:version "0.2.8-SNAPSHOT"})
         #'workspace/modules (fn [] [])
         #'release/execute-release! (fn [& _] :released)}
        #(is (= :released (release/release-command []))))
      (is @locked?))))

(deftest release-command-rejects-invalid-arguments-before-preflight
  (doseq [args [["patch"] [":patch" "unexpected"] [":other"]]]
    (let [executions (atom 0)]
      (with-redefs [release/execute-release!
                    (fn [& _]
                      (swap! executions inc))]
        (is (= "Usage: bb release [:patch|:minor|:major]"
               (exception-message #(release/release-command args))))
        (is (zero? @executions))))))

(deftest remote-sync-check-uses-the-freshly-fetched-head
  (let [command-output-var (ns-resolve 'release 'command-output)
        sync-var (ns-resolve 'release 'ensure-synced-with-origin!)]
    (with-redefs-fn
      {#'process/shell (fn [& _])
       command-output-var
       (fn [& command]
         (case (vec command)
           ["git" "rev-parse" "HEAD"] "release-commit"
           ["git" "rev-parse" "FETCH_HEAD"] "new-origin-commit"
           ["git" "rev-parse" "origin/main"] "release-commit"))}
      #(is (= "main must exactly match origin/main"
              (exception-message (fn [] (@sync-var))))))))

(deftest nondeployment-processes-receive-a-credential-free-environment
  (let [clojure-var (private-var 'workspace 'clojure!)
        commit-var (private-var 'release 'commit!)
        capture-var (private-var 'verify 'capture!)
        shell-options (atom [])
        process-options (atom [])
        manifest {:version "0.2.8-SNAPSHOT"
                  :module-order [:collet-core]
                  :modules {:collet-core {:dir "collet-core"
                                          :lib 'io.velio/collet-core}}}]
    (with-redefs [workspace/manifest (fn [] manifest)
                  process/shell (fn [& args]
                                  (swap! shell-options conj (first args))
                                  {:exit 0})
                  process/process (fn [_ options]
                                    (swap! process-options conj options)
                                    (delay {:exit 0 :out "release-commit\n" :err ""}))]
      (@clojure-var :collet-core "-M:test")
      (@commit-var "Release 0.2.8" ["build/modules.edn"])
      (@capture-var "." "clojure" "-Stree"))
    (is (= 2 (count @shell-options)))
    (is (every? credential-free-options? @shell-options))
    (is (= 2 (count @process-options)))
    (is (every? credential-free-options? @process-options))))

(deftest deployment-explicitly-restores-publication-credentials
  (let [deployment-env-var (private-var 'release 'deployment-env)
        deploy-var (private-var 'release 'deploy-artifacts!)
        root (fs/create-temp-dir {:prefix "release-env-test-"})
        jar (str (fs/path root "artifact.jar"))
        pom (str (fs/path root "pom.xml"))
        options (atom nil)]
    (try
      (spit jar "jar")
      (spit pom "pom")
      (is (some? deployment-env-var)
          "release defines an injectable deployment-only environment boundary")
      (when deployment-env-var
        (with-redefs-fn
          {deployment-env-var
           (fn [] {"PATH" "/bin"
                   "CLOJARS_USERNAME" "not-printed"
                   "CLOJARS_PASSWORD" "not-printed"})
           #'workspace/module-config
           (fn [_] {:lib 'io.velio/example :version "0.2.8"})
           #'process/shell
           (fn [& args]
             (reset! options (first args))
             {:exit 0})}
          (fn []
            (@deploy-var :example {:jar jar :pom pom})))
        (is (map? (:env @options)))
        (is (= #{"CLOJARS_USERNAME" "CLOJARS_PASSWORD"}
               (->> (keys (:env @options))
                    (filter #(re-find #"^CLOJARS_" %))
                    set))))
      (finally
        (fs/delete-tree root)))))

(deftest unit-orchestration-runs-all-script-suites-without-recursion
  (let [commands (atom [])]
    (with-redefs [workspace/manifest (fn [] {:version "0.2.8-SNAPSHOT"
                                             :module-order []
                                             :modules {}})
                  process/shell (fn [& args]
                                  (swap! commands conj (vec (rest args)))
                                  {:exit 0})]
      (workspace/test-unit))
    (is (= [["clojure" "-T:build-test"]
            ["bb" "-cp" "scripts" "scripts/workspace_test.clj"]
            ["bb" "-cp" "scripts" "scripts/versioning_test.clj"]
            ["bb" "-cp" "scripts" "scripts/release_test.clj"]
            ["bb" "-cp" "scripts" "scripts/verify_test.clj"]
            ["clojure" "-M:kmono" "run" "--M" ":test"
             "-e" ":integration"]]
           @commands))
    (is (not-any? #(= ["bb" "test"] %) @commands))))

(let [{:keys [fail error]} (run-tests 'release-test)]
  (when (pos? (+ fail error))
    (System/exit 1)))
