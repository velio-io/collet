(ns versioning-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [versioning :as versioning]
            [workspace :as workspace])
  (:import (java.nio.file Files LinkOption)
           (java.nio.file.attribute PosixFilePermissions)))

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
        root-deps-path (fs/path root "deps.edn")
        graph-path (fs/path root "build" "modules.edn")
        alpha-path (fs/path root "alpha" "deps.edn")
        beta-path (fs/path root "beta" "deps.edn")]
    (fs/create-dirs (fs/parent graph-path))
    (fs/create-dirs (fs/parent alpha-path))
    (fs/create-dirs (fs/parent beta-path))
    (spit (str root-deps-path)
          "{:collet/project {:version \"0.2.8-SNAPSHOT\"}}\n")
    (spit (str graph-path) graph-text)
    (spit (str alpha-path) "{:deps {}}\n")
    (spit (str beta-path) (str beta-deps-text))
    (spit (str beta-path) (str/replace beta-deps-text "0.2.8-SNAPSHOT" internal-version))
    {:root (str root)
     :root-deps-path (str root-deps-path)
     :graph-path (str graph-path)
     :alpha-path (str alpha-path)
     :beta-path (str beta-path)}))

(defn- thrown-with-message? [message-pattern f]
  (try
    (f)
    false
    (catch clojure.lang.ExceptionInfo error
      (boolean (re-find message-pattern (ex-message error))))))

(defn- transaction-state-path [root]
  (str (fs/path root "target" ".collet" "version-transaction.edn")))

(defn- private-var [symbol]
  (ns-resolve 'versioning symbol))

(deftest coordinates-version-updates-without-touching-external-dependencies
  (let [{:keys [root root-deps-path graph-path beta-path]}
        (fixture-repository "0.2.8-SNAPSHOT")]
    (try
      (is (= [root-deps-path graph-path beta-path]
             (mapv :path (versioning/set-version! root "0.2.9-SNAPSHOT"))))
      (is (= "0.2.9-SNAPSHOT"
             (get-in (edn/read-string (slurp root-deps-path))
                     [:collet/project :version])))
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

(deftest rejects-a-root-and-build-version-drift-before-writing-any-file
  (let [{:keys [root root-deps-path graph-path alpha-path beta-path]}
        (fixture-repository "0.2.8-SNAPSHOT")
        paths [root-deps-path graph-path alpha-path beta-path]
        before (mapv slurp paths)]
    (try
      (spit root-deps-path "{:collet/project {:version \"0.2.7-SNAPSHOT\"}}\n")
      (is (thrown-with-message? #"Root and build workspace versions differ"
                                #(versioning/set-version! root "0.2.9-SNAPSHOT")))
      (is (= (assoc before 0 (slurp root-deps-path))
             (mapv slurp paths)))
      (finally
        (fs/delete-tree root)))))

(deftest coordinated-version-update-preserves-posix-source-mode
  (let [{:keys [root graph-path]}
        (fixture-repository "0.2.8-SNAPSHOT")
        path (fs/path graph-path)
        posix? (try
                 (Files/getPosixFilePermissions path (make-array LinkOption 0))
                 true
                 (catch UnsupportedOperationException _
                   false))]
    (try
      (when posix?
        (let [expected (PosixFilePermissions/fromString "rwxr-x---")]
          (Files/setPosixFilePermissions path expected)
          (versioning/set-version! root "0.2.9-SNAPSHOT")
          (is (= expected
                 (Files/getPosixFilePermissions
                  path (make-array LinkOption 0)))
              "atomic source replacement must retain the tracked file mode")))
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

(deftest rolls-back-a-partial-replacement-and-retries-safely
  (let [{:keys [root root-deps-path graph-path alpha-path beta-path]}
        (fixture-repository "0.2.8-SNAPSHOT")
        paths [root-deps-path graph-path alpha-path beta-path]
        before (mapv slurp paths)
        replace-var (private-var 'replace-source-file!)]
    (try
      (is (some? replace-var) "version transactions expose an injectable replacement boundary")
      (when replace-var
        (let [replace! @replace-var
              replacements (atom 0)]
          (is (thrown-with-message?
               #"injected replacement failure"
               #(with-redefs-fn
                  {replace-var
                   (fn [temporary path]
                     (if (= 2 (swap! replacements inc))
                       (throw (ex-info "injected replacement failure" {:path path}))
                       (replace! temporary path)))}
                  (fn []
                    (versioning/set-version! root "0.2.9-SNAPSHOT")))))
          (is (= before (mapv slurp paths)))
          (is (fs/regular-file? (transaction-state-path root)))
          (is (= [root-deps-path graph-path beta-path]
                 (mapv :path
                       (versioning/set-version! root "0.2.9-SNAPSHOT"))))
          (is (= "0.2.9-SNAPSHOT"
                 (:version (edn/read-string (slurp graph-path)))))
          (is (= "0.2.9-SNAPSHOT"
                 (get-in (edn/read-string (slurp beta-path))
                         [:deps 'example/alpha :mvn/version])))
          (is (not (fs/exists? (transaction-state-path root))))))
      (finally
        (fs/delete-tree root)))))

(deftest recovers-a-durable-mixed-state-after-rollback-is-interrupted
  (let [{:keys [root graph-path beta-path]}
        (fixture-repository "0.2.8-SNAPSHOT")
        replace-var (private-var 'replace-source-file!)
        restore-var (private-var 'restore-source-file!)]
    (try
      (is (some? replace-var))
      (is (some? restore-var))
      (when (and replace-var restore-var)
        (let [replace! @replace-var
              replacements (atom 0)]
          (is (thrown-with-message?
               #"injected replacement failure"
               #(with-redefs-fn
                  {replace-var
                   (fn [temporary path]
                     (if (= 3 (swap! replacements inc))
                       (throw (ex-info "injected replacement failure" {:path path}))
                       (replace! temporary path)))
                   restore-var
                   (fn [_]
                     (throw (ex-info "injected rollback interruption" {})))}
                  (fn []
                    (versioning/set-version! root "0.2.9-SNAPSHOT")))))
          (is (= "0.2.9-SNAPSHOT"
                 (:version (edn/read-string (slurp graph-path)))))
          (is (= "0.2.8-SNAPSHOT"
                 (get-in (edn/read-string (slurp beta-path))
                         [:deps 'example/alpha :mvn/version])))
          (is (fs/regular-file? (transaction-state-path root)))
          (versioning/set-version! root "0.2.9-SNAPSHOT")
          (is (= "0.2.9-SNAPSHOT"
                 (:version (edn/read-string (slurp graph-path)))))
          (is (= "0.2.9-SNAPSHOT"
                 (get-in (edn/read-string (slurp beta-path))
                         [:deps 'example/alpha :mvn/version])))
          (is (not (fs/exists? (transaction-state-path root))))))
      (finally
        (fs/delete-tree root)))))

(deftest concurrent-edits-are-preserved-and-block-the-transaction
  (let [{:keys [root graph-path beta-path]}
        (fixture-repository "0.2.8-SNAPSHOT")
        graph-before (slurp graph-path)
        beta-before (slurp beta-path)
        beta-edited (str beta-before "\n;; unrelated concurrent edit\n")
        replace-var (private-var 'replace-source-file!)]
    (try
      (is (some? replace-var))
      (when replace-var
        (let [replace! @replace-var
              first-replacement? (atom true)]
          (is (thrown-with-message?
               #"Version source changed during transaction"
               #(with-redefs-fn
                  {replace-var
                   (fn [temporary path]
                     (when (compare-and-set! first-replacement? true false)
                       (spit beta-path beta-edited))
                     (replace! temporary path))}
                  (fn []
                    (versioning/set-version! root "0.2.9-SNAPSHOT")))))
          (is (= graph-before (slurp graph-path)))
          (is (= beta-edited (slurp beta-path)))
          (is (fs/regular-file? (transaction-state-path root)))))
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
