(ns workspace
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(def publication-credential-variables
  ["CLOJARS_USERNAME" "CLOJARS_PASSWORD"])

(defn nondeployment-env
  ([] (nondeployment-env (System/getenv)))
  ([environment]
   (apply dissoc (into {} environment) publication-credential-variables)))

(defn nondeployment-process-options
  ([] (nondeployment-process-options {}))
  ([options]
   (assoc options :env (nondeployment-env))))

(defn manifest []
  (edn/read-string (slurp "build/modules.edn")))

(defn project-version []
  (:version (manifest)))

(defn module-key [value]
  (keyword value))

(defn module-config [module]
  (let [workspace (manifest)]
    (if-let [config (get-in workspace [:modules module])]
      (merge {:source-dirs ["src"]
              :resource-dirs ["resources"]
              :publish? true}
             config
             {:version (:version workspace)})
      (throw (ex-info (str "Unknown module: " (name module))
                      {:module module})))))

(defn migrated? [module]
  (let [{:keys [dir]} (module-config module)]
    (and (fs/exists? (fs/path dir "deps.edn"))
         (fs/exists? (fs/path dir "build.clj")))))

(defn publish? [module]
  (not= false (:publish? (module-config module))))

(defn modules []
  (->> (:module-order (manifest))
       (filter migrated?)
       vec))

(defn- selected-modules [args]
  (if-let [module (some-> (first args) module-key)]
    (do
      (module-config module)
      (when-not (migrated? module)
        (throw (ex-info (str "Module has not migrated yet: " (name module))
                        {:module module})))
      [module])
    (modules)))

(defn- clojure! [module & args]
  (let [{:keys [dir]} (module-config module)]
    (println (str "\n==> " (name module) " " (str/join " " args)))
    (apply process/shell (nondeployment-process-options {:dir dir})
           "clojure" args)))

(defn build-library! [module local-repo]
  (module-config module)
  (if local-repo
    (clojure! module "-T:build" "jar" ":mvn/local-repo" (pr-str local-repo))
    (clojure! module "-T:build" "jar")))

(defn install-module-to! [module local-repo]
  (when-not (publish? module)
    (throw (ex-info "Module is not a published Maven artifact" {:module module})))
  (clojure! module "-T:build" "install"
            ":mvn/local-repo" (pr-str local-repo)))

(defn- build-task [module]
  (name (or (:build-task (module-config module)) :jar)))

(defn- build-output-paths [module]
  (let [{:keys [dir lib version publish? uber-file distribution]}
        (module-config module)]
    (cond-> []
      publish?
      (conj (fs/path dir "target" (str (name lib) "-" version ".jar")))

      uber-file
      (conj (fs/path dir uber-file))

      (:archive distribution)
      (conj (fs/path dir (:archive distribution))))))

(defn- assert-build-outputs! [module]
  (doseq [path (build-output-paths module)]
    (when-not (fs/regular-file? path)
      (throw (ex-info "Build output is missing"
                      {:module module :path (str path)})))))

(defn- install-module! [module installed local-repo]
  (doseq [dependency (:internal-deps (module-config module))]
    (install-module! dependency installed local-repo))
  (when (and (publish? module)
             (not (contains? @installed module)))
    (if local-repo
      (clojure! module "-T:build" "install" ":mvn/local-repo" (pr-str local-repo))
      (clojure! module "-T:build" "install"))
    (swap! installed conj module)))

(defn- build-module! [module installed]
  (doseq [dependency (:internal-deps (module-config module))]
    (install-module! dependency installed nil))
  ;; Deployable modules also publish a library JAR. Install that JAR before
  ;; building the uberjar so downstream deployables can consume it without a
  ;; later install wiping the preserved uberjar filename from target/.
  (when (and (publish? module)
             (:build-task (module-config module))
             (not (contains? @installed module)))
    (install-module! module installed nil))
  (clojure! module "-T:build" (build-task module))
  (assert-build-outputs! module))

(defn- run-test! [module test-runner-options build-artifact?]
  (when build-artifact?
    (build-module! module (atom #{})))
  (apply clojure! module "-M:test" test-runner-options))

(defn test-module [args]
  (when-not (seq args)
    (throw (ex-info "Usage: bb test:module <module> [test-runner-options]"
                    {:args args})))
  (let [module (module-key (first args))]
    (module-config module)
    (when-not (migrated? module)
      (throw (ex-info (str "Module has not migrated yet: " (name module))
                      {:module module})))
    (run-test! module (rest args) (boolean (:build-task (module-config module))))))

(def script-test-files
  ["scripts/versioning_test.clj"
   "scripts/release_test.clj"
   "scripts/verify_test.clj"])

(defn test-scripts []
  (doseq [path script-test-files]
    (println (str "\n==> " path))
    (process/shell (nondeployment-process-options)
                   "bb" "-cp" "scripts" path)))

(defn test-unit []
  (test-scripts)
  (doseq [module (modules)]
    (run-test! module ["-e" ":integration"] false)))

(defn test-integration []
  (doseq [module (modules)
          :when (:integration-test? (module-config module))]
    (run-test! module ["-i" ":integration"]
               (boolean (:build-task (module-config module))))))

(defn test-all []
  (test-unit)
  (test-integration))

(defn build [args]
  (let [installed (atom #{})]
    (doseq [module (selected-modules args)]
      (build-module! module installed))))

(defn install [args]
  (let [installed (atom #{})]
    (doseq [module (selected-modules args)]
      (install-module! module installed nil))))

(defn install-to! [local-repo]
  (let [installed (atom #{})]
    (doseq [module (modules)]
      (install-module! module installed local-repo))))
