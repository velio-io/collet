(ns collet.build
  "Shared tools.build implementation for every Collet artifact."
  (:refer-clojure :exclude [compile])
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.tools.build.api :as b])
  (:import
   (java.nio.file CopyOption Files StandardCopyOption)
   (java.nio.file.attribute PosixFilePermissions)
   (java.util.regex Pattern)))

(defn- repo-file [path]
  (let [from-module (io/file ".." path)
        from-root   (io/file path)]
    (cond
      (.exists from-module) from-module
      (.exists from-root) from-root
      :else (throw (ex-info "Cannot locate repository file" {:path path})))))

(defn manifest []
  (edn/read-string (slurp (repo-file "build/modules.edn"))))

(defn module-config [module]
  (let [{:keys [project modules]} (manifest)
        config (get modules module)]
    (when-not config
      (throw (ex-info "Unknown module" {:module module})))
    (merge {:source-dirs ["src"]
            :resource-dirs ["resources"]
            :publish? true}
           project
           config)))

(defn- artifact-name [lib]
  (name lib))

(defn- class-dir [{:keys [class-dir]}]
  (or class-dir "target/classes"))

(defn- jar-file [{:keys [jar-file lib version]}]
  (or jar-file (format "target/%s-%s.jar" (artifact-name lib) version)))

(defn- pom-data [{:keys [description url license scm]}]
  [[:description description]
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
    [:tag (:tag scm)]]])

(defn- copy-license! [target]
  (let [source (.toPath (repo-file "LICENSE"))
        destination (.toPath (io/file target "LICENSE"))]
    (Files/createDirectories (.getParent destination)
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/copy source destination
                (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))

(defn- copy-project! [{:keys [source-dirs resource-dirs] :as config}]
  (let [target (class-dir config)
        dirs (->> (concat source-dirs resource-dirs)
                  (remove str/blank?)
                  (filter #(.exists (io/file %))))]
    (when (seq dirs)
      (b/copy-dir {:src-dirs (vec dirs) :target-dir target}))
    (copy-license! target)))

(defn- basis [opts]
  (b/create-basis
   (cond-> {:root {:mvn/repos
                   {"central" {:url "https://repo1.maven.org/maven2/"}
                    "clojars" {:url "https://repo.clojars.org/"}}}
            :user nil
            :project "deps.edn"}
     (:mvn/local-repo opts)
     (assoc :extra {:mvn/local-repo (:mvn/local-repo opts)}))))

(defn- pom-path [{:keys [lib] :as config}]
  (str (class-dir config) "/META-INF/maven/"
       (namespace lib) "/" (name lib) "/pom.xml"))

(defn- patch-pom-extensions! [pom-file]
  ;; tools.build 0.10.14 does not emit Maven <type> for :extension
  ;; coordinates. Graal's org.graalvm.polyglot/js artifact is POM-only, so
  ;; losing this field makes downstream Maven consumers request a nonexistent
  ;; JAR. Keep the project deps.edn authoritative and restore those types.
  (let [deps (:deps (edn/read-string (slurp "deps.edn")))
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

(defn clean
  ([module] (clean module {}))
  ([module _]
   (module-config module)
   (b/delete {:path "target"})
   {:module module}))

(defn pom
  ([module] (pom module {}))
  ([module opts]
   (let [{:keys [lib version source-dirs] :as config}
         (merge (module-config module) opts)
         target (class-dir config)]
     (b/write-pom {:class-dir target
                   :src-pom :none
                   :lib lib
                   :version version
                   :basis (basis opts)
                   :src-dirs source-dirs
                   :resource-dirs (:resource-dirs config)
                   :pom-data (pom-data config)})
     (patch-pom-extensions! (pom-path config))
     {:module module
      :pom-file (pom-path config)})))

(defn jar
  ([module] (jar module {}))
  ([module opts]
   (let [config (merge (module-config module) opts)
         target (class-dir config)
         output (jar-file config)]
     (clean module opts)
     (copy-project! config)
     (pom module opts)
     (b/jar {:class-dir target :jar-file output})
     {:module module :jar-file output})))

(defn install
  ([module] (install module {}))
  ([module opts]
   (let [{:keys [lib version] :as config} (merge (module-config module) opts)
         {:keys [jar-file]} (jar module opts)]
     (b/install {:class-dir (class-dir config)
                 :lib lib
                 :version version
                 :basis (basis opts)
                 :jar-file jar-file})
     {:module module :lib lib :version version :jar-file jar-file})))

(defn uberjar
  ([module] (uberjar module {}))
  ([module opts]
   (let [{:keys [main source-dirs jvm-opts] :as config}
         (merge (module-config module) opts)
         target (class-dir config)
         output (:uber-file config)
         project-basis (basis opts)]
     (when-not (and main output)
       (throw (ex-info "Module does not define :main and :uber-file"
                       {:module module})))
     (clean module opts)
     (copy-project! config)
     (pom module opts)
     (b/compile-clj {:basis project-basis
                     :class-dir target
                     :src-dirs source-dirs
                     :java-opts jvm-opts})
     (b/uber {:class-dir target
              :uber-file output
              :basis project-basis
              :main main})
     {:module module :uber-file output :main main})))

(defn- set-mode! [path mode]
  (Files/setPosixFilePermissions
   (.toPath (io/file path))
   (PosixFilePermissions/fromString mode)))

(defn distribution
  ([module] (distribution module {}))
  ([module opts]
   (let [{:keys [distribution]}
         (merge (module-config module) opts)
         {:keys [archive root files]} distribution
         dist-dir (str "target/" root)]
     (when-not distribution
       (throw (ex-info "Module does not define a distribution" {:module module})))
     (b/delete {:path dist-dir})
     (doseq [{:keys [from to mode]} files]
       (let [destination (str dist-dir "/" to)]
         (io/make-parents destination)
         (Files/copy (.toPath (io/file from))
                     (.toPath (io/file destination))
                     (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING
                                             StandardCopyOption/COPY_ATTRIBUTES]))
         (when mode
           (set-mode! destination mode))))
     (let [{:keys [exit err]}
           (shell/sh "tar" "-czf" archive "-C" "target" root)]
       (when-not (zero? exit)
         (throw (ex-info "Failed to create distribution archive"
                         {:module module :exit exit :error err}))))
     {:module module :archive archive :root root})))
