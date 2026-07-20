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
    (fs/exists? (fs/path dir "deps.edn"))))

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

(defn- root-build! [module task & args]
  (println (str "\n==> " (name module) " " task))
  (apply process/shell (nondeployment-process-options)
         "clojure" "-T:build" task ":module" (pr-str module) args))

(defn build-library! [module local-repo]
  (module-config module)
  (if local-repo
    (root-build! module "build" ":mvn/local-repo" (pr-str local-repo))
    (root-build! module "build")))

(defn install-module-to! [module local-repo]
  (when-not (publish? module)
    (throw (ex-info "Module is not a published Maven artifact" {:module module})))
  (root-build! module "install" ":mvn/local-repo" (pr-str local-repo)))

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
  (when (and (publish? module)
             (not (contains? @installed module)))
    (if local-repo
      (root-build! module "install" ":mvn/local-repo" (pr-str local-repo))
      (root-build! module "install"))
    ;; The root install includes the complete transitive workspace closure.
    (swap! installed into
           (conj (set (:internal-deps (module-config module))) module))))

(defn- build-module! [module _installed]
  (root-build! module "build")
  (assert-build-outputs! module))

(defn unit-test-command []
  ["clojure" "-M:kmono" "run" "--M" ":test" "-e" ":integration"])

(defn integration-test-command []
  ["clojure" "-M:kmono" "run" "--M" ":test:integration" "-i" ":integration"])

(defn module-test-command [module runner-options]
  (into ["clojure" "-M:kmono" "run" "-F"
         (str ":io.velio/" (name module)) "--M" ":test"]
        (concat (when (seq runner-options) ["--"])
                runner-options)))

(defn- kmono! [command]
  (apply process/shell (nondeployment-process-options) command))

(defn- build-test-tools! []
  (process/shell (nondeployment-process-options) "clojure" "-T:build-test"))

(declare build)

(defn test-module [args]
  (when-not (seq args)
    (throw (ex-info "Usage: bb test:module <module> [test-runner-options]"
                    {:args args})))
  (let [module (module-key (first args))
        config (module-config module)]
    (when-not (migrated? module)
      (throw (ex-info (str "Module has not migrated yet: " (name module))
                      {:module module})))
    (when (:build-task config)
      (build [module]))
    (kmono! (module-test-command module (rest args)))))

(def script-test-files
  ["scripts/workspace_test.clj"
   "scripts/versioning_test.clj"
   "scripts/release_test.clj"
   "scripts/verify_test.clj"])

(defn test-scripts []
  (doseq [path script-test-files]
    (println (str "\n==> " path))
    (process/shell (nondeployment-process-options)
                   "bb" "-cp" "scripts" path)))

(defn test-unit []
  (build-test-tools!)
  (test-scripts)
  (kmono! (unit-test-command)))

(defn test-integration []
  (build [:collet-app])
  (build [:collet-cli])
  (kmono! (integration-test-command)))

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
