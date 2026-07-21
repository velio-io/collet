(ns collet.verify
  "Smoke checks for Collet's public build artifacts."
  (:require
   [clojure.data.xml :as xml]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [collet.build :as build])
  (:import
   (java.nio.file Files LinkOption)
   (java.nio.file.attribute FileAttribute PosixFilePermissions)
   (java.util.jar JarFile)))

(defn- fail! [message data]
  (throw (ex-info message data)))

(defn- ensure! [condition message data]
  (when-not condition
    (fail! message data)))

(defn- command! [dir & command]
  (let [{:keys [exit out err]} (apply shell/sh (concat command [:dir (str dir)]))]
    (when-not (zero? exit)
      (fail! "Command failed"
             {:command (vec command) :dir (str dir) :exit exit :error err}))
    (str out err)))

(defn- delete-tree! [path]
  (let [file (io/file path)]
    (when (.exists file)
      (doseq [entry (reverse (file-seq file))]
        (Files/deleteIfExists (.toPath entry))))))

(defn- temp-directory [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- file? [path]
  (.isFile (io/file path)))

(defn- jar-entries [path]
  (with-open [jar (JarFile. (str path))]
    (into #{} (map #(.getName %)) (enumeration-seq (.entries jar)))))

(defn- jar-entry [path entry]
  (with-open [jar (JarFile. (str path))]
    (let [item (.getEntry jar entry)]
      (ensure! item "Missing JAR entry" {:jar (str path) :entry entry})
      (slurp (.getInputStream jar item)))))

(defn- child-elements [element tag]
  (->> (:content element)
       (filter map?)
       (filter #(= tag (name (:tag %))))))

(defn- only-child [element tag]
  (let [matches (vec (child-elements element tag))]
    (ensure! (= 1 (count matches))
             "POM element must be declared exactly once"
             {:element tag :count (count matches)})
    (first matches)))

(defn- element-text [element]
  (let [content (:content element)]
    (ensure! (every? string? content)
             "POM value must contain only text"
             {:element (name (:tag element))})
    (str/trim (apply str content))))

(defn- child-text [element tag]
  (element-text (only-child element tag)))

(defn- parse-pom [pom]
  (let [project (xml/parse-str pom
                               :support-dtd false
                               :supporting-external-entities false)]
    (ensure! (= "project" (name (:tag project)))
             "Maven POM root element must be project"
             {:root (:tag project)})
    project))

(defn- pom-coordinate [project]
  {:group (child-text project "groupId")
   :artifact (child-text project "artifactId")
   :version (child-text project "version")})

(defn- expected-coordinate [{:keys [fqn version]}]
  {:group (namespace fqn) :artifact (name fqn) :version version})

(defn- pom-dependencies [project]
  (if-let [container (first (child-elements project "dependencies"))]
    (mapv (fn [dependency]
            [(symbol (str (child-text dependency "groupId") "/"
                          (child-text dependency "artifactId")))
             (child-text dependency "version")])
          (child-elements container "dependency"))
    []))

(defn- expected-internal-dependencies [{:keys [packages]} package]
  (->> (get-in package [:deps-edn :deps])
       (keep (fn [[fqn _]]
               (when-let [dependency (get packages fqn)]
                 [fqn (:version dependency)])))
       vec))

(defn verify-pom!
  "Verify public Maven coordinates and internal dependency versions."
  [context package pom]
  (let [project (parse-pom pom)
        expected (expected-coordinate package)
        actual (pom-coordinate project)
        internal? #(contains? (:packages context) (first %))]
    (ensure! (= expected actual)
             "POM Maven coordinates do not match"
             {:package (:fqn package) :expected expected :actual actual})
    (ensure! (not (or (str/includes? pom "SNAPSHOT")
                      (str/includes? pom "local/root")
                      (str/includes? pom "local-root")))
             "POM leaks a local-root or snapshot dependency"
             {:package (:fqn package)})
    (ensure! (= (frequencies (expected-internal-dependencies context package))
                (frequencies (filter internal? (pom-dependencies project))))
             "POM internal dependency versions differ"
             {:package (:fqn package)})
    true))

(defn- namespace-entry [namespace]
  (str (-> (str namespace)
           (str/replace "." "/")
           (str/replace "-" "_"))
       ".clj"))

(defn- pom-entry [{:keys [fqn]}]
  (str "META-INF/maven/" (namespace fqn) "/" (name fqn) "/pom.xml"))

(defn verify-library-artifact!
  "Verify one publishable library JAR's POM and required namespaces."
  [context package path]
  (ensure! (file? path) "Library JAR was not built"
           {:package (:fqn package) :path (str path)})
  (let [entries (jar-entries path)]
    (verify-pom! context package (jar-entry path (pom-entry package)))
    (when (seq (get-in package [:deps-edn :paths]))
      (doseq [namespace (get-in package [:artifact :public-namespaces])]
        (ensure! (contains? entries (namespace-entry namespace))
                 "Library JAR lacks a required namespace"
                 {:package (:fqn package) :namespace namespace}))))
  true)

(defn- main-class [path]
  (with-open [jar (JarFile. (str path))]
    (some-> jar .getManifest .getMainAttributes (.getValue "Main-Class"))))

(defn- file-mode [path]
  (PosixFilePermissions/toString
   (Files/getPosixFilePermissions (.toPath (io/file path))
                                  (make-array LinkOption 0))))

(defn- output-path [package output]
  (str (io/file (:absolute-path package) output)))

(defn- check-package! [package contract f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo error
      (throw (ex-info (ex-message error)
                      (assoc (ex-data error)
                             :package (:fqn package)
                             :contract contract)
                      error)))
    (catch Exception error
      (throw (ex-info "Artifact check failed"
                      {:package (:fqn package) :contract contract}
                      error)))))

(defn- verify-archive! [package]
  (let [{:keys [archive root files]} (get-in package [:artifact :outputs])
        path (output-path package archive)
        target (temp-directory "collet-distribution-")]
    (try
      (ensure! (file? path) "Distribution archive is missing"
               {:package (:fqn package) :path path})
      (command! target "tar" "-xzf" path)
      (let [root (io/file target root)
            expected (into {} (map (juxt :to :mode)) files)
            actual (->> (file-seq root)
                        (filter #(.isFile %))
                        (map #(.toString (.relativize (.toPath root) (.toPath %))))
                        set)]
        (ensure! (= (set (keys expected)) actual)
                 "Distribution archive layout changed"
                 {:package (:fqn package)})
        (doseq [[relative mode] expected]
          (ensure! (= mode (file-mode (io/file root relative)))
                   "Distribution archive mode changed"
                   {:package (:fqn package) :entry relative})))
      (finally
        (delete-tree! target)))))

(defn verify-deployable!
  "Verify executable filenames, entrypoints, and distribution layout."
  [context package]
  (let [{:keys [kind main outputs]} (:artifact package)
        uberjar (output-path package (:uberjar outputs))]
    (ensure! (file? uberjar) "Deployable uberjar is missing"
             {:package (:fqn package) :path uberjar})
    (ensure! (= (str main) (main-class uberjar))
             "Deployable entrypoint changed"
             {:package (:fqn package) :expected main :actual (main-class uberjar)})
    (verify-pom! context package (jar-entry uberjar (pom-entry package)))
    (when (= :distribution kind)
      (verify-archive! package)))
  true)

(defn- verify-vega-golden! [{:keys [packages]}]
  (let [package (get packages 'io.velio/collet-action-vega)]
    (command! (:absolute-path package)
              "clojure" "-M:test"
              "-v" "collet.actions.vega-test/write-vega-into-svg-test"
              "-e" ":integration"))
  true)

(defn verify
  "Build all artifacts and check their public contracts."
  [_opts]
  (let [{:keys [packages order] :as context} (build/resolve-context! nil)
        versions (into {} (map (juxt key (comp :version val))) packages)]
    (build/build {:versions versions})
    (doseq [fqn order
            :let [package (get packages fqn)]
            :when (get-in package [:artifact :publish?])]
      (check-package!
       package :library
       #(verify-library-artifact!
         context package
         (output-path package (str "target/" (name fqn) "-" (:version package) ".jar")))))
    (doseq [fqn order
            :let [package (get packages fqn)]
            :when (#{:uberjar :distribution} (get-in package [:artifact :kind]))]
      (check-package! package :executable #(verify-deployable! context package)))
    (let [package (get packages 'io.velio/collet-action-vega)]
      (check-package! package :vega #(verify-vega-golden! context)))
    (println "All artifact checks passed.")
    true))
