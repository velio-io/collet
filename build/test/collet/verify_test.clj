(ns collet.verify-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.test :refer [deftest is testing]]
   [collet.verify :as verify])
  (:import
   (java.nio.file Files LinkOption)
   (java.nio.file.attribute PosixFilePermissions)
   (java.util.zip ZipEntry ZipOutputStream)))

(def package
  {:fqn 'example/app
   :version "3.2.1"
   :artifact {:public-namespaces ['example.app]
              :publish? true
              :kind :library}
   :deps-edn {:paths ["src"]
              :deps {'example/core {:local/root "../core"}
                     'external/dep {:mvn/version "4.5.6"}}}})

(def context
  {:packages {'example/core {:fqn 'example/core :version "1.7.2"}
              'example/app package}})

(defn- exception-message [f]
  (try
    (f)
    nil
    (catch Throwable error
      (ex-message error))))

(defn- exception [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      error)))

(defn- library-error [context package path]
  (exception-message #(verify/verify-library-artifact! context package path)))

(defn- deployable-error [context package]
  (try
    ((ns-resolve 'collet.verify 'verify-deployable!) context package)
    nil
    (catch Throwable error
      (ex-message error))))

(defn- write-jar! [path entries]
  (with-open [output (ZipOutputStream. (io/output-stream path))]
    (doseq [[entry content] entries]
      (.putNextEntry output (ZipEntry. entry))
      (.write output (.getBytes content "UTF-8"))
      (.closeEntry output))))

(defn- pom-text
  ([] (pom-text {}))
  ([{:keys [app-version core-version suffix]
     :or {app-version "3.2.1" core-version "1.7.2" suffix ""}}]
   (str "<project><groupId>example</groupId><artifactId>app</artifactId>"
        "<version>" app-version "</version><dependencies>"
        "<dependency><groupId>example</groupId><artifactId>core</artifactId>"
        "<version>" core-version "</version></dependency>"
        "<dependency><groupId>external</groupId><artifactId>dep</artifactId>"
        "<version>4.5.6</version></dependency>"
        "</dependencies>" suffix "</project>")))

(defn- mode! [path mode]
  (Files/setPosixFilePermissions
   (fs/path path)
   (PosixFilePermissions/fromString mode)))

(defn- archive! [root archive]
  (let [{:keys [exit err]} (shell/sh "tar" "-czf" (str archive)
                                    "-C" (str (fs/parent root))
                                    (str (fs/file-name root)))]
    (when-not (zero? exit)
      (throw (ex-info "tar failed" {:error err})))))

(deftest pom-verification-keeps-public-maven-contracts
  (is (nil? (exception-message #(verify/verify-pom! context package (pom-text)))))
  (doseq [[label options expected]
          [["wrong coordinate" {:app-version "3.2.2"}
            "POM Maven coordinates do not match"]
           ["wrong internal version" {:core-version "3.2.1"}
            "POM internal dependency versions differ"]
           ["snapshot leakage" {:suffix "<!-- SNAPSHOT -->"}
            "POM leaks a local-root or snapshot dependency"]
           ["local-root leakage" {:suffix "<local-root>../core</local-root>"}
            "POM leaks a local-root or snapshot dependency"]]]
    (testing label
      (is (= expected
             (exception-message
              #(verify/verify-pom! context package (pom-text options))))))))

(deftest library-verification-requires-public-namespace-entries
  (let [root (fs/create-temp-dir {:prefix "verify-library-test-"})
        artifact (fs/path root "app.jar")
        entries {"example/app.clj" "(ns example.app)"
                 "META-INF/maven/example/app/pom.xml" (pom-text)}]
    (try
      (write-jar! (str artifact) entries)
      (is (nil? (library-error context package (str artifact))))
      (write-jar! (str artifact) (dissoc entries "example/app.clj"))
      (is (= "Library JAR lacks a required namespace"
             (library-error context package (str artifact))))
      (finally
        (fs/delete-tree root)))))

(deftest aggregate-library-does-not-require-dependency-namespaces-in-its-empty-jar
  (let [root (fs/create-temp-dir {:prefix "verify-aggregate-test-"})
        artifact (fs/path root "aggregate.jar")
        aggregate (assoc-in package [:deps-edn :paths] [])]
    (try
      (write-jar! (str artifact)
                  {"META-INF/maven/example/app/pom.xml" (pom-text)})
      (is (nil? (library-error context aggregate (str artifact))))
      (finally
        (fs/delete-tree root)))))

(deftest executable-verification-requires-declared-filenames-and-main-classes
  (let [root (fs/create-temp-dir {:prefix "verify-executable-test-"})
        app (-> package
                (assoc :absolute-path (str root))
                (assoc-in [:artifact :kind] :uberjar)
                (assoc-in [:artifact :main] 'example.app)
                (assoc-in [:artifact :outputs] {:uberjar "app.jar"}))
        app-context (assoc-in context [:packages 'example/app] app)
        jar (fs/path root "app.jar")
        entries {"META-INF/MANIFEST.MF"
                 "Manifest-Version: 1.0\nMain-Class: example.app\n\n"
                 "META-INF/maven/example/app/pom.xml" (pom-text)}]
    (try
      (is (= "Deployable uberjar is missing"
             (deployable-error app-context app)))
      (write-jar! (str jar) entries)
      (is (nil? (deployable-error app-context app)))
      (write-jar! (str jar) (dissoc entries "META-INF/maven/example/app/pom.xml"))
      (is (= "Missing JAR entry"
             (deployable-error app-context app)))
      (write-jar! (str jar)
                  (assoc entries "META-INF/MANIFEST.MF"
                         "Manifest-Version: 1.0\nMain-Class: example.other\n\n"))
      (is (= "Deployable entrypoint changed"
             (deployable-error app-context app)))
      (finally
        (fs/delete-tree root)))))

(deftest cli-verification-keeps-the-archive-root-and-file-modes
  (let [root (fs/create-temp-dir {:prefix "verify-cli-test-"})
        cli (-> package
                (assoc :absolute-path (str root))
                (assoc-in [:artifact :kind] :distribution)
                (assoc-in [:artifact :main] 'example.cli)
                (assoc-in [:artifact :outputs]
                                   {:uberjar "target/collet.pod.jar"
                           :archive "target/collet-cli.tar.gz"
                           :root "collet-cli"
                           :files [{:to "bb.edn" :mode "rw-r--r--"}
                                   {:to "collet.bb" :mode "rwxr-xr-x"}
                                   {:to "collet.pod.jar" :mode "rw-r--r--"}
                                   {:to "gum" :mode "rwxr-xr-x"}]}))
        cli-context (assoc-in context [:packages 'example/app] cli)
        jar (fs/path root "target" "collet.pod.jar")
        archive (fs/path root "target" "collet-cli.tar.gz")
        archive-root (fs/path root "collet-cli")]
    (try
      (fs/create-dirs (fs/parent jar))
      (fs/create-dirs archive-root)
      (write-jar! (str jar)
                  {"META-INF/MANIFEST.MF"
                   "Manifest-Version: 1.0\nMain-Class: example.cli\n\n"
                   "META-INF/maven/example/app/pom.xml" (pom-text)})
      (doseq [[filename mode] [["bb.edn" "rw-r--r--"]
                              ["collet.bb" "rwxr-xr-x"]
                              ["collet.pod.jar" "rw-r--r--"]
                              ["gum" "rwxr-xr-x"]]]
        (spit (str (fs/path archive-root filename)) filename)
        (mode! (fs/path archive-root filename) mode))
      (archive! archive-root archive)
      (is (nil? (deployable-error cli-context cli)))
      (mode! (fs/path archive-root "gum") "rw-r--r--")
      (archive! archive-root archive)
      (is (= "Distribution archive mode changed"
             (deployable-error cli-context cli)))
      (finally
        (fs/delete-tree root)))))

(deftest verification-reports-the-failing-package-and-contract
  (let [error (atom nil)
        context {:packages {'example/app package}
                 :order ['example/app]}]
    (with-redefs-fn
      {#'collet.build/resolve-context! (fn [_] context)
       #'collet.build/build (fn [_] nil)
       #'collet.verify/verify-library-artifact!
       (fn [& _] (throw (ex-info "Missing namespace" {:entry "example/app.clj"})))
       (ns-resolve 'collet.verify 'verify-vega-golden!) (fn [_] true)}
      (fn []
        (reset! error (exception (fn [] (verify/verify {}))))))
    (is (= {:entry "example/app.clj"
            :package 'example/app
            :contract :library}
           (ex-data @error)))))
