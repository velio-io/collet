(ns workspace
  (:require
   [babashka.process :as process]
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

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

(defn module-key [value]
  (keyword value))

(defn- artifact-metadata [module]
  (let [path (io/file (name module) "deps.edn")]
    (when-not (.isFile path)
      (throw (ex-info (str "Unknown module: " (name module))
                      {:module module})))
    (:collet/artifact (edn/read-string (slurp path)))))

(defn unit-test-command []
  ["clojure" "-M:kmono" "run" "--M" ":test" "--" "-e" ":integration"])

(defn integration-test-command []
  ["clojure" "-M:kmono" "run" "--M" ":test:integration"
   "--" "-i" ":integration"])

(defn module-test-command [module runner-options]
  (into ["clojure" "-M:kmono" "run" "-F"
         (str ":io.velio/" (name module)) "--M" ":test"]
        (concat (when (seq runner-options) ["--"])
                runner-options)))

(defn- kmono! [command]
  (apply process/shell (nondeployment-process-options) command))

(defn kmono [args]
  (kmono! (into ["clojure" "-M:kmono"] args)))

(defn- build-test-tools! []
  (process/shell (nondeployment-process-options) "clojure" "-T:build-test"))

(declare build root-build-task! optional-module-args)

(defn test-module [args]
  (when-not (seq args)
    (throw (ex-info "Usage: bb test:module <module> [test-runner-options]"
                    {:args args})))
  (let [module (module-key (first args))
        artifact (artifact-metadata module)]
    (when (#{:uberjar :distribution} (:kind artifact))
      (build [module]))
    (kmono! (module-test-command module (rest args)))))

(def script-test-files
  ["scripts/workspace_test.clj"])

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
  (root-build-task! "build" (optional-module-args "build" args)))

(defn install [args]
  (root-build-task! "install" (optional-module-args "install" args)))

(defn verify [args]
  (when (seq args)
    (throw (ex-info "Usage: bb verify" {:args args})))
  (root-build-task! "verify" []))

(defn- root-release! [task args]
  (apply process/shell "clojure" "-T:build" task args))

(defn- root-build-task! [task args]
  (apply process/shell (nondeployment-process-options)
         "clojure" "-T:build" task args))

(defn- optional-module-args [task args]
  (when (> (count args) 1)
    (throw (ex-info (str "Usage: bb " task " [module]") {:args args})))
  (if-let [module (first args)]
    [":module" (str ":" (name (module-key module)))]
    []))

(defn release-plan [args]
  (root-release! "release-plan" (optional-module-args "release:plan" args)))

(defn release [args]
  (root-release! "release" (optional-module-args "release" args)))

(defn release-all [args]
  (when (seq args)
    (throw (ex-info "Usage: bb release:all" {:args args})))
  (root-release! "release-all" []))

(defn release-verify-cli [args]
  (when-not (= 1 (count args))
    (throw (ex-info "Usage: bb release:verify-cli <coordinate>@<version>"
                    {:args args})))
  (root-release! "release-verify-cli" [":tag" (pr-str (first args))]))

(defn release-verify-image [args]
  (when-not (= 2 (count args))
    (throw (ex-info
            "Usage: bb release:verify-image <coordinate>@<version> <local-image>"
            {:args args})))
  (root-release! "release-verify-image"
                 [":tag" (pr-str (first args))
                  ":image" (pr-str (second args))]))
