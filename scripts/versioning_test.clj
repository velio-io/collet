(ns versioning-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [versioning :as versioning]
            [workspace :as workspace]))

(deftest calculates-lein-style-versions
  (is (= "0.2.8" (versioning/release-version "0.2.8-SNAPSHOT")))
  (is (= "0.2.9-SNAPSHOT"
         (versioning/next-snapshot-version "0.2.8" :patch)))
  (is (= "0.3.0-SNAPSHOT"
         (versioning/next-snapshot-version "0.2.8" :minor)))
  (is (= "1.0.0-SNAPSHOT"
         (versioning/next-snapshot-version "0.2.8" :major))))

(def graph-text
  "{:version \"0.2.8-SNAPSHOT\"\n :modules {:alpha {:lib example/alpha :dir \"alpha\"}\n           :beta {:lib example/beta :dir \"beta\"}}}\n")

(def beta-deps-text
  "{:deps {example/alpha {:mvn/version \"0.2.8-SNAPSHOT\"}\n        external/lib {:mvn/version \"9.9.9\" :exclusions [external/foo]}\n        nested/lib {:mvn/version \"8.8.8\" :metadata {:version \"leave\"}}}}\n")

(defn- fixture-repository [internal-version]
  (let [root (fs/create-temp-dir {:prefix "versioning-test-"})
        graph-path (fs/path root "build" "modules.edn")
        alpha-path (fs/path root "alpha" "deps.edn")
        beta-path (fs/path root "beta" "deps.edn")]
    (fs/create-dirs (fs/parent graph-path))
    (fs/create-dirs (fs/parent alpha-path))
    (fs/create-dirs (fs/parent beta-path))
    (spit (str graph-path) graph-text)
    (spit (str alpha-path) "{:deps {}}\n")
    (spit (str beta-path) (str beta-deps-text))
    (spit (str beta-path) (str/replace beta-deps-text "0.2.8-SNAPSHOT" internal-version))
    {:root (str root)
     :graph-path (str graph-path)
     :alpha-path (str alpha-path)
     :beta-path (str beta-path)}))

(defn- thrown-with-message? [message-pattern f]
  (try
    (f)
    false
    (catch clojure.lang.ExceptionInfo error
      (boolean (re-find message-pattern (ex-message error))))))

(deftest coordinates-version-updates-without-touching-external-dependencies
  (let [{:keys [root graph-path beta-path]}
        (fixture-repository "0.2.8-SNAPSHOT")]
    (try
      (is (= [graph-path beta-path]
             (mapv :path (versioning/set-version! root "0.2.9-SNAPSHOT"))))
      (is (= "0.2.9-SNAPSHOT" (:version (edn/read-string (slurp graph-path)))))
      (is (= "0.2.9-SNAPSHOT"
             (get-in (edn/read-string (slurp beta-path))
                     [:deps 'example/alpha :mvn/version])))
      (is (.contains (slurp beta-path)
                     "external/lib {:mvn/version \"9.9.9\" :exclusions [external/foo]}"))
      (is (.contains (slurp beta-path)
                     "nested/lib {:mvn/version \"8.8.8\" :metadata {:version \"leave\"}}"))
      (is (= [] (versioning/set-version! root "0.2.9-SNAPSHOT")))
      (finally
        (fs/delete-tree root)))))

(deftest rejects-stale-internal-pins-before-writing-any-file
  (let [{:keys [root graph-path alpha-path beta-path]}
        (fixture-repository "0.2.7-SNAPSHOT")
        before (mapv slurp [graph-path alpha-path beta-path])]
    (try
      (is (thrown-with-message? #"Internal dependency version is stale"
                                #(versioning/set-version! root "0.2.9-SNAPSHOT")))
      (is (= before (mapv slurp [graph-path alpha-path beta-path])))
      (finally
        (fs/delete-tree root)))))

(deftest updates-modules-omitted-from-module-order
  (let [{:keys [root graph-path beta-path]}
        (fixture-repository "0.2.8-SNAPSHOT")]
    (try
      (spit graph-path (str/replace graph-text
                                   ":modules"
                                   ":module-order [:alpha]\n :modules"))
      (versioning/set-version! root "0.2.9-SNAPSHOT")
      (is (= "0.2.9-SNAPSHOT"
             (get-in (edn/read-string (slurp beta-path))
                     [:deps 'example/alpha :mvn/version])))
      (finally
        (fs/delete-tree root)))))

(deftest version-command-requires-exactly-one-version
  (is (thrown-with-message? #"Usage: bb version <version>"
                            #(versioning/version-command [])))
  (is (thrown-with-message? #"Usage: bb version <version>"
                            #(versioning/version-command ["0.2.9-SNAPSHOT"
                                                         "unexpected"]))))

(deftest workspace-injects-the-graph-version-into-module-configs
  (is (= "0.2.8-SNAPSHOT" (workspace/project-version)))
  (is (= (workspace/project-version)
         (:version (workspace/module-config :collet-core)))))

(let [{:keys [fail error]} (run-tests 'versioning-test)]
  (when (pos? (+ fail error))
    (System/exit 1)))
