(ns collet.verify-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [collet.verify :as verify])
  (:import
   (java.util.zip ZipEntry ZipOutputStream)))

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

(def package
  {:fqn 'example/app
   :version "3.2.1"
   :tag "example/app@3.2.1"
   :depends-on #{'example/core}
   :artifact {:description "Example"
              :public-namespaces ['example.app]
              :publish? true
              :kind :library}
   :deps-edn
   {:deps {'example/core {:local/root "../core"}
           'external/dep {:mvn/version "4.5.6"
                          :exclusions ['excluded/lib]}}}})

(def context
  {:project {:url "https://example.test/project"
             :license {:name "Apache License, Version 2.0"
                       :url "https://example.test/license"}
             :scm {:url "https://example.test/project"
                   :connection "scm:git:https://example.test/project.git"
                   :developer-connection
                   "scm:git:ssh://git@example.test/project.git"}}
   :packages {'example/core {:fqn 'example/core :version "1.7.2"}
              'example/app package}})

(defn- pom-text
  ([] (pom-text {}))
  ([{:keys [app-version core-version scm-tag scm-connection suffix]
     :or {app-version "3.2.1"
          core-version "1.7.2"
          scm-tag "example/app@3.2.1"
          scm-connection "scm:git:https://example.test/project.git"
          suffix ""}}]
   (str "<project>"
        "<groupId>example</groupId><artifactId>app</artifactId>"
        "<version>" app-version "</version>"
        "<url>https://example.test/project</url>"
        "<description>Example</description>"
        "<licenses><license><name>Apache License, Version 2.0</name>"
        "<url>https://example.test/license</url></license></licenses>"
        "<scm><url>https://example.test/project</url>"
        "<connection>" scm-connection "</connection>"
        "<developerConnection>scm:git:ssh://git@example.test/project.git"
        "</developerConnection><tag>" scm-tag
        "</tag></scm>"
        "<dependencies>"
        "<dependency><groupId>example</groupId><artifactId>core</artifactId>"
        "<version>" core-version "</version></dependency>"
        "<dependency><groupId>external</groupId><artifactId>dep</artifactId>"
        "<version>4.5.6</version><exclusions><exclusion>"
        "<groupId>excluded</groupId><artifactId>lib</artifactId>"
        "</exclusion></exclusions></dependency>"
        "</dependencies>" suffix "</project>")))

(deftest pom-verification-enforces-independent-versions-and-publishable-metadata
  (is (true? (verify/verify-pom! context package (pom-text))))
  (doseq [[label options expected]
          [["wrong internal version" {:core-version "3.2.1"}
            "POM direct dependencies differ"]
           ["snapshot" {:app-version "3.2.1-SNAPSHOT"}
            "POM Maven coordinates do not match"]
           ["local root leakage" {:suffix "<local-root>../core</local-root>"}
            "POM leaks a local-root or snapshot dependency"]
           ["wrong package tag" {:scm-tag "HEAD"}
            "POM SCM tag does not match the package tag"]
           ["wrong SCM connection" {:scm-connection "scm:git:wrong"}
            "POM SCM connection does not match"]]]
    (testing label
      (is (= expected
             (exception-message
              #(verify/verify-pom! context package (pom-text options))))))))

(deftest artifact-verification-rejects-inexact-maven-metadata-and-build-identity
  (let [root (fs/create-temp-dir {:prefix "verify-artifact-test-"})
        module (fs/path root "module")
        artifact (fs/path root "app.jar")
        package (assoc package
                       :absolute-path (str module)
                       :deps-edn (assoc (:deps-edn package)
                                        :paths ["src" "resources"]))
        context (assoc-in context [:packages 'example/app] package)
        entries {"LICENSE" "license"
                 "example/app.clj" "(ns example.app)"
                 "config.edn" "{:enabled true}"
                 "META-INF/maven/example/app/pom.xml" (pom-text)
                 "META-INF/maven/example/app/pom.properties"
                 "groupId=example\nartifactId=app\nversion=3.2.1\n"
                 "META-INF/collet/build.edn"
                 (pr-str {:version "3.2.1" :revision "abc123"})}]
    (try
      (fs/create-dirs (fs/path module "src" "example"))
      (spit (str (fs/path module "src" "example" "app.clj"))
            "(ns example.app)")
      (fs/create-dirs (fs/path module "resources"))
      (spit (str (fs/path module "resources" "config.edn"))
            "{:enabled true}")
      (write-jar! (str artifact) entries)
      (is (true? (verify/verify-library-artifact!
                  context package (str artifact) "abc123")))
      (write-jar! (str artifact)
                  (assoc entries
                         "META-INF/maven/example/app/pom.properties"
                         "groupId=example\nartifactId=app\nversion=3.2.1-extra\n"))
      (is (= "Artifact JAR Maven properties do not match"
             (exception-message
              #(verify/verify-library-artifact!
                context package (str artifact) "abc123"))))
      (write-jar! (str artifact) (dissoc entries "config.edn"))
      (is (= "Library JAR lacks a source or runtime resource"
             (exception-message
              #(verify/verify-library-artifact!
                context package (str artifact) "abc123"))))
      (finally
        (fs/delete-tree root)))))

(deftest optional-family-policy-is-coordinate-based
  (is (= #{} (verify/allowed-optional-families 'io.velio/collet-action-http)))
  (is (= #{:postgres}
         (verify/allowed-optional-families 'io.velio/collet-action-jdbc)))
  (is (= #{:aws}
         (verify/allowed-optional-families 'io.velio/collet-action-s3)))
  (is (= #{:aws}
         (verify/allowed-optional-families 'io.velio/collet-app)))
  (is (= #{:postgres :aws :queue :llm :graal :lucene}
         (verify/allowed-optional-families 'io.velio/collet-actions))))

(deftest ci-fetches-tags-without-any-release-or-deploy-step
  (let [workflow (slurp ".github/workflows/ci.yml")]
    (is (= 3 (count (re-seq #"fetch-depth: 0" workflow))))
    (is (= 3 (count (re-seq #"cli: \"1\.12\.5\.1654\"" workflow))))
    (is (= 3 (count (re-seq #"bb: \"1\.12\.218\"" workflow))))
    (is (not (re-find #"(?m)^\\s*- run: bb release" workflow)))
    (is (not (str/includes? workflow "deps-deploy")))))

(deftest docker-build-provides-a-complete-gitless-version-context
  (let [dockerfile (slurp "collet-app/Dockerfile")]
    (doseq [fragment ["ARG COLLET_CORE_VERSION"
                      "ARG COLLET_VERSION"
                      "ARG COLLET_REVISION"
                      ":versions"
                      "io.velio/collet-core"
                      "io.velio/collet-app"
                      ":source-revision"
                      "clojure -T:build build :module :collet-app"]]
      (is (str/includes? dockerfile fragment) fragment))
    (is (= 1 (count (re-seq #":versions" dockerfile))))
    (is (= 1 (count (re-seq #":source-revision" dockerfile))))
    (is (str/includes? (slurp ".dockerignore") ".git"))))
