(ns collet.build
  "Root-only tools.build entry points for the Collet Kmono workspace."
  (:refer-clojure :exclude [compile])
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.tools.build.api :as b]
   [k16.kmono.build :as kmono.build]
   [k16.kmono.core.graph :as kmono.graph]
   [k16.kmono.workspace :as kmono.workspace])
  (:import
   (java.nio.file CopyOption Files StandardCopyOption)
   (java.nio.file.attribute PosixFilePermissions)
   (java.util.regex Pattern)))

(def ^:private default-artifact
  {:public-namespaces []
   :publish? true
   :kind :library})

(defn- read-edn [path]
  (edn/read-string (slurp path)))

(defn- validate-artifact! [package]
  (let [{:keys [description kind main outputs] :as artifact}
        (:artifact package)]
    (when-not (and (string? description) (not (str/blank? description)))
      (throw (ex-info "Package artifact description is required"
                      {:package (:fqn package)})))
    (when-not (#{:library :uberjar :distribution} kind)
      (throw (ex-info "Unknown package artifact kind"
                      {:package (:fqn package) :kind kind})))
    (when (#{:uberjar :distribution} kind)
      (when-not (and main (get outputs :uberjar))
        (throw (ex-info "Executable package requires :main and :outputs/:uberjar"
                        {:package (:fqn package)}))))
    (when (= :distribution kind)
      (when-not (and (get outputs :archive)
                     (get outputs :root)
                     (seq (get outputs :files)))
        (throw (ex-info "Distribution package outputs are incomplete"
                        {:package (:fqn package)}))))
    artifact))

(defn resolve-workspace-context!
  "Resolve the Kmono graph and attach Collet's coordinated artifact metadata."
  ([] (resolve-workspace-context! nil))
  ([dir]
   (let [{:keys [root packages] :as context}
         (kmono.workspace/resolve-workspace-context! dir)
         project (-> (io/file root "deps.edn")
                     read-edn
                     :collet/project)
         version (:version project)]
     (when (str/blank? version)
       (throw (ex-info "The workspace :collet/project version is required"
                       {:root root})))
     (let [packages
           (into {}
                 (map (fn [[fqn package]]
                        (let [artifact (merge default-artifact
                                              (get-in package
                                                      [:deps-edn
                                                       :collet/artifact]))
                              package (assoc package
                                             :artifact artifact
                                             :version version)]
                          (validate-artifact! package)
                          [fqn package])))
                 packages)]
       (assoc context
              :project project
              :version version
              :packages packages)))))

(defn- basis-options [{:keys [mvn/local-repo]}]
  (cond-> {:root {:mvn/repos
                  {"central" {:url "https://repo1.maven.org/maven2/"}
                   "clojars" {:url "https://repo.clojars.org/"}}}
           :user nil
           :project "deps.edn"}
    local-repo (assoc :extra {:mvn/local-repo local-repo})))

(defn package-basis
  "Create the basis appropriate to a package build phase.

  Publishable POMs use Kmono's Maven-coordinate replacement. Uberjars keep the
  normal tools.deps local-root graph so they build the current checkout."
  [{:keys [packages]} package phase opts]
  (let [basis-opts (basis-options opts)]
    (if (and (= :pom phase) (get-in package [:artifact :publish?]))
      (kmono.build/create-basis packages package basis-opts)
      (b/create-basis basis-opts))))

(defn- artifact-name [package]
  (name (:fqn package)))

(defn- class-dir [_package]
  "target/classes")

(defn- jar-file [{:keys [version] :as package}]
  (format "target/%s-%s.jar" (artifact-name package) version))

(defn- resolved-file [path]
  (io/file (b/resolve-path path)))

(defn- pom-path [{:keys [fqn] :as package}]
  (str (class-dir package) "/META-INF/maven/"
       (namespace fqn) "/" (name fqn) "/pom.xml"))

(defn- pom-data [{:keys [project]} package]
  (let [{:keys [url license scm]} project]
    [[:description (get-in package [:artifact :description])]
     [:url url]
     [:licenses
      [:license
       [:name (:name license)]
       [:url (:url license)]
       [:distribution "repo"]]]
     [:scm
      [:url (:url scm)]
      [:connection (:connection scm)]
      [:developerConnection (:developer-connection scm)]
      [:tag (:tag scm)]]]))

(defn- copy-license! [context target]
  (let [source (.toPath (io/file (:root context) "LICENSE"))
        destination (.toPath (resolved-file (str target "/LICENSE")))]
    (Files/createDirectories (.getParent destination)
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/copy source destination
                (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))

(defn- package-paths [package]
  (or (seq (get-in package [:deps-edn :paths])) []))

(defn- copy-project! [context package]
  (let [target (class-dir package)
        paths (->> (package-paths package)
                   (filter #(-> % resolved-file .exists))
                   vec)]
    (when (seq paths)
      (b/copy-dir {:src-dirs paths :target-dir target}))
    (copy-license! context target)))

(defn- git-revision [root]
  (let [{:keys [exit out err]}
        (shell/sh "git" "-C" root "rev-parse" "HEAD")]
    (when-not (zero? exit)
      (throw (ex-info "Cannot determine build source revision"
                      {:exit exit :error (str/trim err)})))
    (str/trim out)))

(defn- write-build-identity!
  [{:keys [root version]} package {:keys [expected-version source-revision]}]
  (when (and expected-version (not= version expected-version))
    (throw (ex-info "Build version does not match the expected release"
                    {:expected expected-version :actual version})))
  (let [revision (or source-revision (git-revision root))
        path (resolved-file (str (class-dir package)
                                 "/META-INF/collet/build.edn"))]
    (when (str/blank? revision)
      (throw (ex-info "Build source revision must not be blank" {})))
    (io/make-parents path)
    (spit path (pr-str {:version version :revision revision}))
    {:version version :revision revision}))

(defn- patch-pom-extensions! [package pom-file]
  ;; tools.build 0.10.14 drops Maven <type> for :extension coordinates.
  (let [deps (get-in package [:deps-edn :deps])
        patched
        (reduce-kv
         (fn [xml lib {:keys [extension]}]
           (if (and extension (not= "jar" extension))
             (let [dependency-prefix
                   (str "      <groupId>" (namespace lib) "</groupId>\n"
                        "      <artifactId>" (name lib) "</artifactId>\n")
                   pattern (re-pattern
                            (str (Pattern/quote dependency-prefix)
                                 "      <version>[^<]+</version>"))]
               (str/replace-first
                xml pattern
                (fn [matched]
                  (str matched "\n      <type>" extension "</type>"))))
             xml))
         (slurp pom-file)
         deps)]
    (spit pom-file patched)))

(defn- clean-package! [package]
  (b/delete {:path "target"})
  {:package (:fqn package)})

(defn- write-pom! [context package opts]
  (let [target (class-dir package)
        basis (package-basis context package :pom opts)
        paths (vec (package-paths package))]
    (b/write-pom {:class-dir target
                  :src-pom :none
                  :lib (:fqn package)
                  :version (:version package)
                  :basis basis
                  :src-dirs paths
                  :pom-data (pom-data context package)})
    (let [path (resolved-file (pom-path package))]
      (patch-pom-extensions! package path)
      (str path))))

(defn- build-jar! [context package opts]
  (clean-package! package)
  (copy-project! context package)
  (let [pom-file (write-pom! context package opts)
        output (jar-file package)]
    (write-build-identity! context package opts)
    (b/jar {:class-dir (class-dir package) :jar-file output})
    {:package (:fqn package)
     :jar-file (str (resolved-file output))
     :pom-file pom-file}))

(defn- build-uberjar! [context package opts]
  (let [{:keys [main outputs publish?]} (:artifact package)
        target (class-dir package)
        output (:uberjar outputs)]
    (clean-package! package)
    (copy-project! context package)
    (let [pom-file (write-pom! context package opts)]
      (write-build-identity! context package opts)
      (when publish?
        (b/jar {:class-dir target :jar-file (jar-file package)}))
      (let [local-basis (package-basis context package :uberjar opts)]
        (b/compile-clj {:basis local-basis
                        :class-dir target
                        :src-dirs (vec (package-paths package))
                        :java-opts (:jvm-opts (:project context))})
        (b/uber {:class-dir target
                 :uber-file output
                 :basis local-basis
                 :main main}))
      {:package (:fqn package)
       :jar-file (when publish? (str (resolved-file (jar-file package))))
       :pom-file pom-file
       :uber-file (str (resolved-file output))})))

(defn- set-mode! [path mode]
  (Files/setPosixFilePermissions
   (.toPath (io/file path))
   (PosixFilePermissions/fromString mode)))

(defn- build-distribution! [context package opts]
  (let [built (build-uberjar! context package opts)
        {:keys [archive root files]} (get-in package [:artifact :outputs])
        dist-dir (str "target/" root)]
    (b/delete {:path dist-dir})
    (doseq [{:keys [from to mode]} files]
      (let [source (.toPath (resolved-file from))
            destination-file (resolved-file (str dist-dir "/" to))
            destination (.toPath destination-file)]
        (io/make-parents destination-file)
        (Files/copy source destination
                    (into-array CopyOption
                                [StandardCopyOption/REPLACE_EXISTING
                                 StandardCopyOption/COPY_ATTRIBUTES]))
        (when mode
          (set-mode! destination-file mode))))
    (let [archive-file (resolved-file archive)
          target-dir (resolved-file "target")
          {:keys [exit err]}
          (shell/sh "tar" "-czf" (str archive-file)
                    "-C" (str target-dir) root)]
      (when-not (zero? exit)
        (throw (ex-info "Failed to create distribution archive"
                        {:package (:fqn package) :exit exit :error err})))
      (assoc built
             :archive (str archive-file)
             :distribution-root root))))

(defn- build-package! [context package opts]
  (case (get-in package [:artifact :kind])
    :library (build-jar! context package opts)
    :uberjar (build-uberjar! context package opts)
    :distribution (build-distribution! context package opts)))

(defn- package-fqn [packages module]
  (when module
    (or (when (and (symbol? module) (namespace module) (contains? packages module))
          module)
        (let [requested (name module)
              matches (->> (keys packages)
                           (filter #(= requested (name %)))
                           vec)]
          (when-not (= 1 (count matches))
            (throw (ex-info "Unknown or ambiguous workspace package"
                            {:module module :matches matches})))
          (first matches)))))

(defn- select-packages [packages module]
  (if-let [fqn (package-fqn packages module)]
    (let [selected (conj (kmono.graph/query-dependencies packages fqn) fqn)]
      (kmono.graph/filter-by #(contains? selected (:fqn %)) packages))
    packages))

(defn clean
  "Clean every package target, or one package and its workspace dependencies."
  [{:keys [module]}]
  (let [{:keys [packages] :as context} (resolve-workspace-context!)
        selected (select-packages packages module)]
    (kmono.build/for-each-package selected {:concurrency 1}
      clean-package!)
    {:packages (vec (keys selected))
     :root (:root context)}))

(defn build
  "Build every workspace artifact, or one package and its dependencies."
  [{:keys [module] :as opts}]
  (let [{:keys [packages] :as context} (resolve-workspace-context!)
        selected (select-packages packages module)
        results (atom {})]
    (kmono.build/for-each-package selected
      (fn [package]
        (swap! results assoc (:fqn package)
               (build-package! context package opts))))
    {:version (:version context)
     :artifacts @results}))

(defn install
  "Build and install publishable workspace packages in dependency order."
  [{:keys [module] :as opts}]
  (let [{:keys [packages] :as context} (resolve-workspace-context!)
        requested (package-fqn packages module)
        selected (select-packages packages module)
        publishable (kmono.graph/filter-by
                     #(get-in % [:artifact :publish?])
                     selected)
        results (atom {})]
    (when (and requested
               (not (get-in packages [requested :artifact :publish?])))
      (throw (ex-info "Package is not a publishable Maven artifact"
                      {:module module})))
    (kmono.build/for-each-package publishable
      (fn [package]
        (let [built (build-jar! context package opts)
              basis (package-basis context package :pom opts)]
          (b/install {:class-dir (class-dir package)
                      :lib (:fqn package)
                      :version (:version package)
                      :basis basis
                      :jar-file (jar-file package)})
          (swap! results assoc (:fqn package) built))))
    {:version (:version context)
     :artifacts @results}))
