(ns dev
  (:require
   [babashka.fs :as fs]
   [babashka.process :as ps]
   [clojure.string :as string]
   [common :as common]))


(defn setup []
  (let [snapshot-version (str (common/get-project-version "collet-core") "-SNAPSHOT")]
    (ps/shell {:dir "collet-core"} "lein" "install")

    (fs/create-dir "collet-actions/checkouts")
    (fs/create-sym-link "collet-actions/checkouts/collet-core" "../../collet-core/")
    (common/bump-version "collet-actions/project.clj" "collet-core" snapshot-version)
    (ps/shell {:dir "collet-actions"} "lein" "install")

    (fs/create-dir "collet-app/checkouts")
    (fs/create-sym-link "collet-app/checkouts/collet-core" "../../collet-core/")
    (common/bump-version "collet-app/project.clj" "collet-core" snapshot-version)
    (ps/shell {:dir "collet-app"} "lein" "install")

    (fs/create-dir "collet-cli/checkouts")
    (fs/create-sym-link "collet-cli/checkouts/collet-app" "../../collet-app/")
    (common/bump-version "collet-cli/project.clj" "collet-app" snapshot-version)))


(defn cleanup []
  (let [[major minor patch] (-> (common/get-project-version "collet-core")
                                (string/split #"\."))
        prev-version (str major "." minor "." (dec (Integer/parseInt patch)))]
    (fs/delete-tree "collet-actions/checkouts")
    (common/bump-version "collet-actions/project.clj" "collet-core" prev-version)
    (fs/delete-tree "collet-app/checkouts")
    (common/bump-version "collet-app/project.clj" "collet-core" prev-version)
    (fs/delete-tree "collet-cli/checkouts")
    (common/bump-version "collet-cli/project.clj" "collet-app" prev-version)))