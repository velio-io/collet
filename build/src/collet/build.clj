(ns collet.build
  "Thin tools.build entry points for the Kmono workspace."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.tools.build.api :as b]
   [k16.kmono.build :as kmono.build]
   [k16.kmono.core.graph :as kmono.graph]
   [k16.kmono.version :as kmono.version]
   [k16.kmono.version.alg.conventional-commits :as conventional]
   [k16.kmono.workspace :as kmono.workspace])
  (:import
   (java.nio.file CopyOption Files StandardCopyOption)
   (java.nio.file.attribute PosixFilePermissions)
   (java.util.regex Pattern)))


(def ^:private bootstrap-version "0.2.8")


(defn- fail! [message data]
  (throw (ex-info message data)))


(defn- artifact [package]
  (get-in package [:deps-edn :collet/artifact]))


(defn load-packages
  "Resolve Kmono versions, optionally applying changes or explicit versions."
  [{:keys [dir versions changes?] :as opts}]
  (let [{:keys [root packages]} (kmono.workspace/resolve-workspace-context! dir)]
    (if (contains? opts :versions)
      (do
        (when-not (and (= (set (keys packages)) (set (keys versions)))
                       (every? #(and (string? %) (not (str/blank? %)))
                               (vals versions)))
          (fail! "Complete package version overrides are required"
                 {:expected (sort (keys packages)) :supplied (sort (keys versions))}))
        (update-vals packages #(assoc % :version (get versions (:fqn %)))))
      (let [resolved   (kmono.version/resolve-package-versions root packages)
            missing    (filterv #(nil? (:version %)) (vals resolved))
            initial    (set (map :fqn (filter #(get-in % [:deps-edn :collet/artifact :initial-release?]) missing)))
            initialize (fn [package]
                         (cond-> package
                           (initial (:fqn package)) (assoc :version
                                                      bootstrap-version :release?
                                                      true)))]
        (cond
          (= (count missing) (count resolved))
          (update-vals resolved
                       #(assoc %
                          :version bootstrap-version
                          :release? true))

          (some #(not (initial (:fqn %))) missing)
          (fail! "Package version tags are incomplete"
                 {:packages (sort (map :fqn missing))})

          changes?
          (let [next       (->> resolved
                                (kmono.version/resolve-package-changes root)
                                (kmono.version/inc-package-versions
                                 conventional/version-fn))
                unreleased (filter #(and (not (initial (:fqn %)))
                                         (seq (:commits %))
                                         (= (:version %)
                                            (get-in resolved [(:fqn %) :version])))
                                   (vals next))]
            (when (seq unreleased)
              (fail! "Package changes require a release-producing conventional commit"
                     {:packages (sort (map :fqn unreleased))
                      :guidance "Use fix:, feat:, !, or BREAKING CHANGE:"}))
            (update-vals next
                         #(initialize (assoc %
                                        :release?
                                        (not= (:version %) (get-in resolved [(:fqn %) :version]))))))

          :else (update-vals resolved initialize))))))


(defn release-packages [packages]
  (kmono.graph/filter-by :release? packages))


(defn- package-fqn [packages module]
  (when module
    (let [matches (filter #(= (name module) (name %)) (keys packages))]
      (when-not (= 1 (count matches))
        (fail! "Unknown or ambiguous workspace package"
               {:module module :matches (sort matches)}))
      (first matches))))


(defn- select-packages [packages module]
  (if-let [fqn (package-fqn packages module)]
    (let [selected (conj (kmono.graph/query-dependencies packages fqn) fqn)]
      (kmono.graph/filter-by #(contains? selected (:fqn %)) packages))
    packages))


(defn- project [root]
  (-> (io/file root "deps.edn") slurp edn/read-string :collet/project))


(defn- paths [package]
  (vec (get-in package [:deps-edn :paths])))


(defn- output [path]
  (str (b/resolve-path path)))


(defn- jar-file [package]
  (str "target/" (name (:fqn package)) "-" (:version package) ".jar"))


(defn- pom-file [package]
  (str "target/classes/META-INF/maven/"
       (namespace (:fqn package))
       "/"
       (name (:fqn package))
       "/pom.xml"))


(defn- pom-data [project package]
  (let [{:keys [url license scm]} project]
    [[:description (:description (artifact package))]
     [:url url]
     [:licenses
      [:license [:name (:name license)] [:url (:url license)]
       [:distribution "repo"]]]
     [:scm [:url (:url scm)] [:connection (:connection scm)]
      [:developerConnection (:developer-connection scm)]
      [:tag (kmono.version/create-package-version-tag package)]]]))


(defn- patch-pom-extensions! [package path]
  ;; tools.build currently omits Maven <type> for :extension coordinates.
  (spit path
        (reduce-kv
         (fn [xml lib {:keys [extension]}]
           (if (and extension (not= "jar" extension))
             (let [prefix (str "      <groupId>"
                               (namespace lib)
                               "</groupId>\n"
                               "      <artifactId>"
                               (name lib)
                               "</artifactId>\n")]
               (str/replace-first
                xml
                (re-pattern (str (Pattern/quote prefix) "      <version>[^<]+</version>"))
                #(str % "\n      <type>" extension "</type>")))
             xml))
         (slurp path)
         (get-in package [:deps-edn :deps]))))


(defn compatibility-manifest
  "Pin catalogue coordinates to the versions used for this distribution."
  [packages extra-versions app catalogues]
  (let [versions (merge extra-versions (into {} (map (juxt key (comp :version val))) packages))]
    {:build    {:version (:version app) :distribution (if (str/includes? (:version app) "local") "local" "release")}
     :packages (into {}
                     (map (fn [module]
                            (let [coordinate (symbol "io.velio" module)
                                  version    (get versions coordinate)]
                              (when-not (and (string? version) (seq version))
                                (fail! "Missing catalogue compatibility version" {:coordinate coordinate}))
                              [(str coordinate) {:version version}])))
                     catalogues)}))


(defn- prepare! [{:keys [root project packages compatibility-versions]} package]
  (b/delete {:path "target"})
  (when (seq (paths package))
    (b/copy-dir {:src-dirs (paths package) :target-dir "target/classes"}))
  (when-let [index (get-in (artifact package) [:knowledge :index])]
    (let [{:keys [docs catalogues]} (edn/read-string (slurp (output index)))]
      (doseq [{:keys [id path]} docs]
        (let [destination (io/file (output "target/classes/collet/knowledge/docs") (str id ".md"))]
          (io/make-parents destination)
          (io/copy (io/file root path) destination)))
      (doseq [module catalogues]
        (b/copy-dir {:src-dirs   [(str (io/file root module "resources/collet/catalogue"))]
                     :target-dir "target/classes/collet/catalogue"}))
      (let [path (io/file (output "target/classes") (get-in (artifact package) [:knowledge :compatibility]))]
        (io/make-parents path)
        (spit path (pr-str (compatibility-manifest packages compatibility-versions package catalogues))))))
  (when-let [{:keys [dir resource-root]} (:ui (artifact package))]
    (doseq [command [["npm" "ci" "--no-audit" "--no-fund"]
                     ["npm" "run" "build"]]]
      (let [{:keys [exit out err]} (apply shell/sh (concat command [:dir (output dir)]))]
        (when-not (zero? exit)
          (fail! "Workbench frontend build failed" {:command command :out out :err err}))))
    (b/copy-dir {:src-dirs   [(str dir "/dist/client")]
                 :target-dir (str "target/classes/" resource-root)}))
  (io/make-parents (io/file (output "target/classes/LICENSE")))
  (io/copy (io/file root "LICENSE") (io/file (output "target/classes/LICENSE")))
  (let [basis (kmono.build/create-basis packages package)]
    (b/write-pom {:class-dir "target/classes"
                  :src-pom   :none
                  :lib       (:fqn package)
                  :version   (:version package)
                  :basis     basis
                  :src-dirs  (paths package)
                  :pom-data  (pom-data project package)})
    (patch-pom-extensions! package (output (pom-file package)))))


(defn- build-jar! [context package]
  (prepare! context package)
  (b/jar {:class-dir "target/classes" :jar-file (jar-file package)})
  {:package  (:fqn package)
   :jar-file (output (jar-file package))
   :pom-file (output (pom-file package))})


(defn- build-uberjar! [context package]
  (let [{:keys [main outputs publish?]} (artifact package)]
    (prepare! context package)
    (when publish?
      (b/jar {:class-dir "target/classes" :jar-file (jar-file package)}))
    (let [basis (b/create-basis)]
      (b/compile-clj {:basis     basis
                      :class-dir "target/classes"
                      :src-dirs  (paths package)
                      :java-opts (get-in context [:project :jvm-opts])})
      (b/uber {:class-dir "target/classes"
               :uber-file (:uberjar outputs)
               :basis     basis
               :main      main}))
    {:package   (:fqn package)
     :jar-file  (when publish? (output (jar-file package)))
     :pom-file  (output (pom-file package))
     :uber-file (output (:uberjar outputs))}))


(defn- copy-distribution-file! [root {:keys [from to mode]}]
  (let [destination (io/file (output (str "target/" root "/" to)))]
    (io/make-parents destination)
    (Files/copy (.toPath (io/file (output from)))
                (.toPath destination)
                (into-array CopyOption
                            [StandardCopyOption/REPLACE_EXISTING
                             StandardCopyOption/COPY_ATTRIBUTES]))
    (when mode
      (Files/setPosixFilePermissions (.toPath destination)
                                     (PosixFilePermissions/fromString mode)))))


(defn- build-distribution! [context package]
  (let [built (build-uberjar! context package)
        {:keys [archive root files]} (:outputs (artifact package))]
    (b/delete {:path (str "target/" root)})
    (run! #(copy-distribution-file! root %) files)
    (let [{:keys [exit err]} (shell/sh "tar"
                                       "-czf"
                                       (output archive)
                                       "-C"
                                       (output "target")
                                       root)]
      (when-not (zero? exit)
        (fail! "Failed to create distribution archive"
               {:package (:fqn package) :error err})))
    (assoc built :archive (output archive))))


(defn- build-package! [context package _opts]
  (case (:kind (artifact package))
    :library (build-jar! context package)
    :uberjar (build-uberjar! context package)
    :distribution (build-distribution! context package)))


(defn- context [{:keys [dir packages] :as opts}]
  (let [{:keys [root]} (kmono.workspace/resolve-workspace-context! dir)]
    {:root                   root
     :project                (project root)
     :compatibility-versions (:compatibility-versions opts)
     :packages               (or packages (load-packages opts))}))


(defn prepare-workbench
  "Prepare source Workbench dependency metadata without building or installing packages."
  [opts]
  (let [{:keys [root packages compatibility-versions]} (context opts)
        app      (or (get packages 'io.velio/collet-app)
                     (fail! "Workbench package is missing" {}))
        index    (edn/read-string (slurp (io/file (:absolute-path app) (get-in (artifact app) [:knowledge :index]))))
        manifest (assoc-in (compatibility-manifest packages compatibility-versions app (:catalogues index)) [:build :distribution] "source")
        path     (io/file root "target/workbench-knowledge/collet/knowledge/compatibility.edn")]
    (io/make-parents path)
    (spit path (pr-str manifest))
    {:manifest (str path) :build (:build manifest) :packages (:packages manifest)}))


(defn build
  "Build all packages, one package and its dependencies, or a selected graph."
  [{:keys [module selected] :as opts}]
  (let [{:keys [packages] :as context} (context opts)
        selected (or selected (select-packages packages module))
        selected (update-vals selected #(assoc % :relative-path (:absolute-path %)))
        results  (atom {})]
    (kmono.build/for-each-package selected
                                  (fn [package]
                                    (swap! results assoc
                                      (:fqn package)
                                      (build-package! context package opts))))
    {:versions  (into {} (map (juxt key (comp :version val))) packages)
     :artifacts @results}))


(defn install
  "Build and install all publishable packages, or one dependency closure."
  [{:keys [module] :as opts}]
  (let [{:keys [packages]} (context opts)
        selected           (->> (select-packages packages module)
                                (kmono.graph/filter-by #(get-in % [:deps-edn :collet/artifact :publish?])))
        built              (:artifacts (build (assoc opts
                                                :packages packages
                                                :selected selected)))]
    (kmono.build/for-each-package
     (update-vals selected #(assoc % :relative-path (:absolute-path %)))
     (fn [package]
       (b/install {:class-dir "target/classes"
                   :lib       (:fqn package)
                   :version   (:version package)
                   :basis     (kmono.build/create-basis packages package)
                   :jar-file  (get-in built [(:fqn package) :jar-file])})))
    {:artifacts built}))


(defn verify [opts]
  ((requiring-resolve 'collet.verify/verify) opts))


(defn release-plan [opts]
  ((requiring-resolve 'collet.release/plan) opts))


(defn release [opts]
  ((requiring-resolve 'collet.release/release) opts))
