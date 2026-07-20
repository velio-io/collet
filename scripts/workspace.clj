(ns workspace
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(def manifest
  (delay (edn/read-string (slurp "build/modules.edn"))))

(defn- module-key [value]
  (keyword value))

(defn- module-config [module]
  (or (get-in @manifest [:modules module])
      (throw (ex-info (str "Unknown module: " (name module))
                      {:module module}))))

(defn- migrated? [module]
  (let [{:keys [dir]} (module-config module)]
    (and (fs/exists? (fs/path dir "deps.edn"))
         (fs/exists? (fs/path dir "build.clj")))))

(defn- selected-modules [args]
  (if-let [module (some-> (first args) module-key)]
    (do
      (module-config module)
      (when-not (migrated? module)
        (throw (ex-info (str "Module has not migrated yet: " (name module))
                        {:module module})))
      [module])
    (->> (:module-order @manifest)
         (filter migrated?))))

(defn- clojure! [module & args]
  (let [{:keys [dir]} (module-config module)]
    (println (str "\n==> " (name module) " " (str/join " " args)))
    (apply process/shell {:dir dir} "clojure" args)))

(defn- publish? [module]
  (not= false (:publish? (module-config module))))

(defn- build-task [module]
  (name (or (:build-task (module-config module)) :jar)))

(defn- install-dependencies! [module installed]
  (doseq [dependency (:internal-deps (module-config module))]
    (install-dependencies! dependency installed)
    (when (and (publish? dependency)
               (not (contains? @installed dependency)))
      (clojure! dependency "-T:build" "install")
      (swap! installed conj dependency))))

(defn- build-module! [module installed]
  (install-dependencies! module installed)
  (clojure! module "-T:build" (build-task module)))

(defn test-module [args]
  (when-not (seq args)
    (throw (ex-info "Usage: bb test:module <module> [test-runner-options]"
                    {:args args})))
  (let [module (module-key (first args))]
    (module-config module)
    (when-not (migrated? module)
      (throw (ex-info (str "Module has not migrated yet: " (name module))
                      {:module module})))
    (when (:build-task (module-config module))
      (build-module! module (atom #{})))
    (apply clojure! module "-M:test" (rest args))))

(defn build [args]
  (let [installed (atom #{})]
    (doseq [module (selected-modules args)]
      (build-module! module installed))))

(defn install [args]
  (doseq [module (selected-modules args)]
    (when (publish? module)
      (clojure! module "-T:build" "install"))))
