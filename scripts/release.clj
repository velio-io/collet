(ns release
  (:require
   [babashka.process :as ps]
   [common :as common]))


(defn ensure-creds []
  (if (or
       (nil? (System/getenv "CLOJARS_USERNAME"))
       (nil? (System/getenv "CLOJARS_PASSWORD")))
    (throw (ex-info "CLOJARS_USERNAME and CLOJARS_PASSWORD must be set in the environment"
                    {:username (System/getenv "CLOJARS_USERNAME")
                     :password (System/getenv "CLOJARS_PASSWORD")}))))


(defn release-to-clojars [project]
  (ps/shell {:dir project} "lein clean")
  (ps/shell {:dir project} "lein release"))


(defn commit-changes []
  (ps/shell "lein vcs commit")
  (ps/shell "lein vcs push"))


(defn make-release []
  (ensure-creds)
  (let [next-version (common/get-project-version "collet-core")]
    (release-to-clojars "collet-core")
    (common/bump-version "collet-actions/project.clj" "collet-core" next-version)
    (common/bump-version "collet-app/project.clj" "collet-core" next-version)
    (commit-changes)
    (release-to-clojars "collet-actions")
    (common/bump-version "README.md" "collet-core" next-version)
    (common/bump-version "docs/actions.md" "collet-actions" next-version)
    (common/bump-version "collet-core/test/collet/actions/enrich_test.clj" "collet-actions" next-version)
    (commit-changes)
    (release-to-clojars "collet-app")
    (ps/shell {:dir "collet-app"} "docker" "buildx" "use" "collet-builder")
    (ps/shell {:dir "collet-app"} "docker" "buildx" "build"
              "--tag" (str "velioio/collet:" next-version)
              "--tag" "velioio/collet:latest"
              "--platform" "linux/arm64,linux/amd64"
              "--push" ".")
    (common/bump-version "collet-cli/project.clj" "collet-app" next-version)
    (ps/shell {:dir "collet-cli"} "bb build")
    (ps/shell {:dir "collet-cli"} "lein change version leiningen.release/bump-version patch")
    (commit-changes)
    (println "Create GH release and attach the collet-cli archive from collet-cli/target/collet-cli.tar.gz")))