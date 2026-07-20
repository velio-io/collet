(ns collet.build
  "Shared tools.build implementation for every Collet artifact."
  (:refer-clojure :exclude [compile])
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.build.api :as b])
  (:import
   (java.nio.file CopyOption Files StandardCopyOption)
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

(defn- basis [opts]
  (cond-> (b/create-basis {:project "deps.edn"})
    (:mvn/local-repo opts) (assoc :mvn/local-repo (:mvn/local-repo opts))))

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
   (let [{:keys [source-dirs resource-dirs] :as config}
         (merge (module-config module) opts)
         target (class-dir config)
         output (jar-file config)
         dirs (->> (concat source-dirs resource-dirs)
                   (remove str/blank?)
                   (filter #(.exists (io/file %))))]
     (clean module opts)
     (when (seq dirs)
       (b/copy-dir {:src-dirs (vec dirs) :target-dir target}))
     (copy-license! target)
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
