(ns verify-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is run-tests testing]]
            [verify]
            [workspace :as workspace])
  (:import (java.util.zip ZipEntry ZipOutputStream)))

(defn- per-artifact-tag-pattern []
  (var-get (ns-resolve 'verify 'per-artifact-tag-pattern)))

(deftest rejects-per-artifact-tags-with-placeholder-and-version-forms
  (let [pattern (per-artifact-tag-pattern)]
    (is (re-find pattern (str "<module>" "-v<version>")))
    (is (re-find pattern (str "collet-core-v" "VERSION")))
    (is (not (re-find pattern "v<version>")))
    (is (not (re-find pattern "v0.3.0")))))

(defn- exception-message [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-message error))))

(defn- write-jar! [path entries]
  (with-open [output (ZipOutputStream. (io/output-stream path))]
    (doseq [[entry content] entries]
      (.putNextEntry output (ZipEntry. entry))
      (.write output (.getBytes content "UTF-8"))
      (.closeEntry output))))

(defn- pom-text [group version dependency-version]
  (str "<project>"
       "<groupId>" group "</groupId>"
       "<artifactId>example</artifactId>"
       "<version>" version "</version>"
       "<url>https://github.com/velio-io/collet</url>"
       "<licenses><license><name>Apache License, Version 2.0</name>"
       "</license></licenses>"
       "<dependencies><dependency>"
       "<groupId>external</groupId>"
       "<artifactId>dep</artifactId>"
       "<version>" dependency-version "</version>"
       "</dependency></dependencies>"
       "</project>"))

(defn- properties-text [group version]
  (str "groupId=" group "\n"
       "artifactId=example\n"
       "version=" version "\n"))

(defn- verify-library-coordinate-error
  [directory pom properties]
  (let [lib 'io.velio/example
        path (fs/path directory "target" "example-0.2.8.jar")
        verify-var (ns-resolve 'verify 'verify-library!)]
    (fs/create-dirs (fs/parent path))
    (spit (str (fs/path directory "deps.edn"))
          "{:deps {external/dep {:mvn/version \"0.2.8\"}}}\n")
    (write-jar! (str path)
                {"LICENSE" "license"
                 "META-INF/maven/io.velio/example/pom.xml" pom
                 "META-INF/maven/io.velio/example/pom.properties" properties})
    (with-redefs [workspace/module-config
                  (fn [_]
                    {:dir (str directory)
                     :lib lib
                     :version "0.2.8"
                     :namespaces []
                     :source-dirs []
                     :internal-deps []})]
      (exception-message #(@verify-var :example)))))

(deftest library-verification-rejects-inexact-maven-coordinates
  (let [root (fs/create-temp-dir {:prefix "maven-coordinate-test-"})]
    (try
      (testing "a dependency version cannot stand in for the project version"
        (is (= "Artifact JAR Maven coordinates do not match"
               (verify-library-coordinate-error
                root
                (pom-text "io.velio" "0.2.9" "0.2.8")
                (properties-text "io.velio" "0.2.8")))))
      (testing "a version prefix in Java properties is not an exact value"
        (is (= "Artifact JAR Maven properties do not match"
               (verify-library-coordinate-error
                root
                (pom-text "io.velio" "0.2.8" "0.2.8")
                (properties-text "io.velio" "0.2.8-extra")))))
      (testing "the project group ID is part of the exact coordinate"
        (is (= "Artifact JAR Maven coordinates do not match"
               (verify-library-coordinate-error
                root
                (pom-text "io.wrong" "0.2.8" "0.2.8")
                (properties-text "io.velio" "0.2.8")))))
      (testing "nested metadata cannot masquerade as the project coordinate"
        (is (= "Artifact JAR Maven coordinates do not match"
               (verify-library-coordinate-error
                root
                (str "<project><properties>"
                     "<groupId>io.velio</groupId>"
                     "<artifactId>example</artifactId>"
                     "<version>0.2.8</version>"
                     "</properties>"
                     (subs (pom-text "io.wrong" "9.9.9" "0.2.8")
                           (count "<project>")))
                (properties-text "io.velio" "0.2.8")))))
      (testing "the properties group ID is also exact"
        (is (= "Artifact JAR Maven properties do not match"
               (verify-library-coordinate-error
                root
                (pom-text "io.velio" "0.2.8" "0.2.8")
                (properties-text "io.wrong" "0.2.8")))))
      (finally
        (fs/delete-tree root)))))

(deftest publication-guides-use-tag-derived-artifacts-and-verify-before-push
  (let [app-guide (slurp "collet-app/deploy.md")
        cli-guide (slurp "collet-cli/README.md")]
    (testing "Docker publication is bound to and verified against the tag"
      (doseq [fragment ["git worktree add --detach"
                        "COLLET_VERSION"
                        "COLLET_REVISION"
                        "bb release:verify-image \"$tag\""]]
        (is (.contains app-guide fragment) fragment))
      (is (< (.indexOf app-guide "bb release:verify-image \"$tag\"")
             (.indexOf app-guide "--push"))
          "image verification must precede the registry push"))
    (testing "CLI publication is built and verified in the detached tag worktree"
      (doseq [fragment ["git worktree add --detach"
                        "bb build collet-cli"
                        "bb release:verify-cli \"$tag\""
                        "gh release create \"$tag\""]]
        (is (.contains cli-guide fragment) fragment))
      (is (< (.indexOf cli-guide "bb release:verify-cli \"$tag\"")
             (.indexOf cli-guide "gh release create \"$tag\""))
          "CLI verification must precede the GitHub upload"))))

(deftest verifies-embedded-build-version-and-source-revision
  (let [verify-var (ns-resolve 'verify 'verify-artifact-build-identity!)
        root (fs/create-temp-dir {:prefix "artifact-identity-test-"})
        artifact (str (fs/path root "artifact.jar"))]
    (try
      (is (some? verify-var) "artifact identity verification is available to release policy")
      (when verify-var
        (write-jar! artifact
                    {"META-INF/collet/build.edn"
                     (pr-str {:version "0.2.8" :revision "release-commit"})})
        (is (= {:version "0.2.8" :revision "release-commit"}
               (@verify-var artifact "0.2.8" "release-commit")))
        (is (= "Artifact build identity does not match the release source"
               (exception-message
                #(@verify-var artifact "0.2.9-SNAPSHOT" "release-commit")))))
      (finally
        (fs/delete-tree root)))))

(deftest app-build-requires-both-thin-and-uber-jars
  (let [root (fs/create-temp-dir {:prefix "app-build-output-test-"})
        module-dir (str (fs/path root "collet-app"))
        thin (fs/path module-dir "target" "collet-app-0.2.8-SNAPSHOT.jar")
        uber (fs/path module-dir "target" "collet.jar")
        manifest {:version "0.2.8-SNAPSHOT"
                  :module-order [:collet-app]
                  :modules {:collet-app {:dir module-dir
                                         :lib 'io.velio/collet-app
                                         :publish? true
                                         :build-task :uberjar
                                         :uber-file "target/collet.jar"}}}
        invoke-build
        (fn [outputs]
          (with-redefs [workspace/manifest (fn [] manifest)
                        process/shell
                        (fn [& args]
                          (when (= "uberjar" (last args))
                            (fs/create-dirs (fs/parent uber))
                            (doseq [path outputs]
                              (spit (str path) "artifact")))
                          {:exit 0})]
            (workspace/build ["collet-app"])))]
    (try
      (fs/create-dirs module-dir)
      (spit (str (fs/path module-dir "deps.edn")) "{:deps {}}\n")
      (spit (str (fs/path module-dir "build.clj")) "(ns build)\n")
      (is (= "Build output is missing"
             (exception-message #(invoke-build [uber]))))
      (is (nil? (exception-message #(invoke-build [thin uber]))))
      (finally
        (fs/delete-tree root)))))

(deftest docker-build-carries-release-version-and-source-identity
  (let [dockerfile (slurp "collet-app/Dockerfile")]
    (doseq [fragment ["ARG COLLET_VERSION"
                      "ARG COLLET_REVISION"
                      ":expected-version"
                      ":source-revision"
                      "org.opencontainers.image.version"
                      "org.opencontainers.image.revision"]]
      (is (.contains dockerfile fragment) fragment))
    (is (= 4 (count (re-seq #":source-revision" dockerfile)))
        "both the core install and app uberjar receive source identity")))

(deftest ci-pins-exact-clojure-and-babashka-versions
  (let [workflow (slurp ".github/workflows/ci.yml")]
    (is (not (re-find #"(?m)^\s+(?:cli|bb):\s+latest\s*$" workflow)))
    (is (= 3 (count (re-seq #"cli: \"1\.12\.5\.1654\"" workflow))))
    (is (= 3 (count (re-seq #"bb: \"1\.12\.218\"" workflow))))))

(let [{:keys [fail error]} (run-tests 'verify-test)]
  (when (pos? (+ fail error))
    (System/exit 1)))
