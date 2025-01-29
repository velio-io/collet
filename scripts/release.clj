(ns release
  (:require
   [babashka.process :as ps]))


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


(def re-project-name-version
  #"(\(defproject\s+)(\S+)(\s+\")([\d|\.]+)([^\"]*)([\s\S]*)")


(defn get-project-version [project]
  (let [m       (->> (slurp (str project "/project.clj"))
                     (re-matches re-project-name-version))
        version (nth m 4)]
    version))


(defn bump-version [file lib next-version]
  (let [project-text     (slurp file)
        new-project-text (clojure.string/replace
                          project-text
                          (re-pattern (str "(\\[io.velio/" lib "\\s+\\\")([\\d|\\.]+)([^\\\"]*)"))
                          (str "$1" next-version "$3"))]
    (spit file new-project-text)))


(defn commit-changes []
  (ps/shell "lein vcs commit")
  (ps/shell "lein vcs push"))


(defn make-release []
  (ensure-creds)
  (let [next-version (get-project-version "collet-core")]
    (release-to-clojars "collet-core")
    (bump-version "collet-actions/project.clj" "collet-core" next-version)
    (bump-version "collet-app/project.clj" "collet-core" next-version)
    (commit-changes)
    (release-to-clojars "collet-actions")
    (bump-version "README.md" "collet-core" next-version)
    (bump-version "docs/actions.md" "collet-actions" next-version)
    (bump-version "collet-core/test/collet/actions/enrich_test.clj" "collet-actions" next-version)
    (commit-changes)
    (release-to-clojars "collet-app")
    (ps/shell {:dir "collet-app"} "docker" "buildx" "use" "collet-builder")
    (ps/shell {:dir "collet-app"} "docker" "buildx" "build"
              "--tag" (str "velioio/collet:" next-version)
              "--tag" "velioio/collet:latest"
              "--platform" "linux/arm64,linux/amd64"
              "--push" ".")
    (bump-version "collet-cli/project.clj" "collet-app" next-version)
    (ps/shell {:dir "collet-cli"} "bb build")
    (ps/shell {:dir "collet-cli"} "lein change version leiningen.release/bump-version patch")
    (commit-changes)
    (println "Create GH release and attach the collet-cli archive from collet-cli/target/collet-cli.tar.gz")))