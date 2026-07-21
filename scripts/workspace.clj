(ns workspace
  (:require [babashka.process :as process]))

(defn- run! [commands]
  (doseq [command commands]
    (apply process/shell command)))

(defn- module-args [usage args]
  (when (> (count args) 1)
    (throw (ex-info (str "Usage: bb " usage " [module]") {:args args})))
  (if-let [module (first args)]
    [":module" (str ":" module)]
    []))

(defn unit-test-commands []
  [["clojure" "-T:build-test"]
   ["bb" "-cp" "scripts" "scripts/workspace_test.clj"]
   ["clojure" "-M:kmono" "run" "--M" ":test" "--" "-e" ":integration"]])

(defn integration-test-commands []
  [["clojure" "-T:build" "build" ":module" ":collet-app"]
   ["clojure" "-T:build" "build" ":module" ":collet-cli"]
   ["clojure" "-M:kmono" "run" "--M" ":test:integration"
    "--" "-i" ":integration"]])

(def ^:private executable-test-modules #{"collet-app" "collet-cli"})

(defn module-test-commands [[module & runner-options :as args]]
  (when-not module
    (throw (ex-info "Usage: bb test:module <module> [test-runner-options]"
                    {:args args})))
  (cond-> []
    (executable-test-modules module)
    (conj ["clojure" "-T:build" "build" ":module" (str ":" module)])
    true
    (conj (into ["clojure" "-M:kmono" "run" "-F" (str ":io.velio/" module)
                "--M" ":test"]
               (concat (when (seq runner-options) ["--"]) runner-options)))))

(defn build-commands [args]
  [(into ["clojure" "-T:build" "build"] (module-args "build" args))])

(defn install-commands [args]
  [(into ["clojure" "-T:build" "install"] (module-args "install" args))])

(defn verify-commands [args]
  (when (seq args)
    (throw (ex-info "Usage: bb verify" {:args args})))
  [["clojure" "-T:build" "verify"]])

(defn release-plan-commands [args]
  [(into ["clojure" "-T:build" "release-plan"]
         (module-args "release:plan" args))])

(defn kmono [args]
  (run! [(into ["clojure" "-M:kmono"] args)]))

(defn test-scripts []
  (run! [["bb" "-cp" "scripts" "scripts/workspace_test.clj"]]))

(defn test-unit []
  (run! (unit-test-commands)))

(defn test-integration []
  (run! (integration-test-commands)))

(defn test-all []
  (run! (concat (unit-test-commands) (integration-test-commands))))

(defn test-module [args]
  (run! (module-test-commands args)))

(defn build [args]
  (run! (build-commands args)))

(defn install [args]
  (run! (install-commands args)))

(defn verify [args]
  (run! (verify-commands args)))

(defn release-plan [args]
  (run! (release-plan-commands args)))

(defn release [args]
  (run! [(into ["clojure" "-T:build" "release"]
               (module-args "release" args))]))

(defn release-all [args]
  (when (seq args)
    (throw (ex-info "Usage: bb release:all" {:args args})))
  (run! [["clojure" "-T:build" "release-all"]]))

(defn release-verify-cli [args]
  (when-not (= 1 (count args))
    (throw (ex-info "Usage: bb release:verify-cli <coordinate>@<version>"
                    {:args args})))
  (run! [["clojure" "-T:build" "release-verify-cli"
          ":tag" (pr-str (first args))]]))

(defn release-verify-image [args]
  (when-not (= 2 (count args))
    (throw (ex-info
            "Usage: bb release:verify-image <coordinate>@<version> <local-image>"
            {:args args})))
  (run! [["clojure" "-T:build" "release-verify-image"
          ":tag" (pr-str (first args))
          ":image" (pr-str (second args))]]))
