(ns release-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.test :refer [deftest is run-tests testing]]
            [release :as release]
            [verify]
            [workspace :as workspace]))

(def release-input
  {:current "0.2.8-SNAPSHOT"
   :level :patch
   :modules [:collet-core :collet-action-http]})

(defn- recording-ops
  ([events] (recording-ops events nil))
  ([events fail-at]
   {:preflight! (fn [_]
                  (swap! events conj :preflight))
    :set-version! (fn [version]
                    (swap! events conj [:set-version version]))
    :commit! (fn [message]
               (swap! events conj [:commit message])
               (if (.startsWith message "Release ")
                 :release-commit
                 :snapshot-commit))
    :test! (fn []
             (swap! events conj :test)
             (when (= :test fail-at)
               (throw (ex-info "test failed" {}))))
    :verify! (fn []
               (swap! events conj :verify)
               (when (= :verify fail-at)
                 (throw (ex-info "verify failed" {}))))
    :stage! (fn [_]
              (swap! events conj :stage)
              {:collet-core :core-artifacts
               :collet-action-http :http-artifacts})
    :deploy! (fn [module _]
               (swap! events conj [:deploy module])
               (when (= module fail-at)
                 (throw (ex-info "deploy failed" {:module module}))))
    :tag! (fn [tag commit]
            (swap! events conj [:tag tag commit]))
    :push! (fn [{:keys [atomic?]}]
             (swap! events conj [:push (if atomic? :atomic :non-atomic)]))}))

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

(deftest coordinates-a-release-in-graph-order
  (let [events (atom [])]
    (release/execute-release! release-input (recording-ops events))
    (is (= [:preflight
            [:set-version "0.2.8"]
            [:commit "Release 0.2.8"]
            :test
            :verify
            :stage
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
                   :test]]
           [:verify [:preflight
                     [:set-version "0.2.8"]
                     [:commit "Release 0.2.8"]
                     :test
                     :verify]]]]
    (testing (name gate)
      (let [events (atom [])]
        (is (= (str (name gate) " failed")
               (exception-message
                #(release/execute-release! release-input
                                           (recording-ops events gate)))))
        (is (= expected @events))))))

(deftest deployment-failure-stops-before-tag-snapshot-and-push
  (let [events (atom [])]
    (is (= "deploy failed"
           (exception-message
            #(release/execute-release!
              release-input
              (recording-ops events :collet-action-http)))))
    (is (= [:preflight
            [:set-version "0.2.8"]
            [:commit "Release 0.2.8"]
            :test
            :verify
            :stage
            [:deploy :collet-core]
            [:deploy :collet-action-http]]
           @events))))

(deftest tags-the-release-commit
  (let [events (atom [])
        result (release/execute-release! release-input (recording-ops events))]
    (is (= :release-commit (:release-commit result)))
    (is (= [[:tag "v0.2.8" :release-commit]]
           (filterv #(and (vector? %) (= :tag (first %))) @events)))))

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
      (@commit-var "Release 0.2.8")
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
    (is (= [["bb" "-cp" "scripts" "scripts/versioning_test.clj"]
            ["bb" "-cp" "scripts" "scripts/release_test.clj"]
            ["bb" "-cp" "scripts" "scripts/verify_test.clj"]]
           @commands))
    (is (not-any? #(= ["bb" "test"] %) @commands))))

(let [{:keys [fail error]} (run-tests 'release-test)]
  (when (pos? (+ fail error))
    (System/exit 1)))
