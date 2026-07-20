(ns workspace-test
  (:require [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is run-tests testing]]
            [workspace :as workspace]))

(def integration-modules
  ["collet-action-file"
   "collet-action-jdbc"
   "collet-action-s3"
   "collet-action-vega"
   "collet-app"
   "collet-cli"])

(deftest unit-tests-use-kmono-with-the-integration-selector-excluded
  (is (= ["clojure" "-M:kmono" "run" "--M" ":test"
          "--" "-e" ":integration"]
         (workspace/unit-test-command))))

(deftest integration-tests-select-the-empty-integration-marker-alias
  (is (= ["clojure" "-M:kmono" "run" "--M" ":test:integration"
          "--" "-i" ":integration"]
         (workspace/integration-test-command))))

(deftest module-tests-filter-kmono-to-the-requested-package-and-forward-runner-options
  (is (= ["clojure" "-M:kmono" "run" "-F" ":io.velio/collet-action-http"
          "--M" ":test" "--" "--focus" "collet.actions.http-test"]
         (workspace/module-test-command
          :collet-action-http
          ["--focus" "collet.actions.http-test"]))))

(deftest kmono-command-forwards-arguments-to-the-pinned-root-alias
  (let [commands (atom [])]
    (with-redefs [process/shell (fn [& args]
                                 (swap! commands conj (vec args))
                                 {:exit 0})]
      (workspace/kmono ["query" "--parallel"]))
    (is (= [[{:env (workspace/nondeployment-env)}
             "clojure" "-M:kmono" "query" "--parallel"]]
           @commands))))

(deftest only-integration-bearing-modules-declare-an-empty-integration-marker
  (let [module-aliases
        (into {}
              (for [module integration-modules]
                [module (get (edn/read-string
                              (slurp (io/file module "deps.edn")))
                             :aliases)]))]
    (is (= (set integration-modules)
           (->> (file-seq (io/file "."))
                (filter #(= "deps.edn" (.getName %)))
                (filter #(contains? (:aliases (edn/read-string (slurp %)))
                                    :integration))
                (map #(.getName (.getParentFile %)))
                set)))
    (doseq [[module aliases] module-aliases]
      (testing module
        (is (contains? aliases :test))
        (is (= {} (:integration aliases)))))))

(deftest release-ux-dispatches-to-root-build-with-package-filters
  (let [commands (atom [])]
    (with-redefs [process/shell (fn [& args] (swap! commands conj args))]
      (workspace/release-plan ["collet-core"])
      (workspace/release ["collet-core"])
      (workspace/release-all [])
      (workspace/release-verify-cli ["io.velio/collet-cli@0.2.8"])
      (workspace/release-verify-image
       ["io.velio/collet-app@0.2.8" "local-image"]))
    (is (= [["clojure" "-T:build" "release-plan"
             ":module" ":collet-core"]
            ["clojure" "-T:build" "release"
             ":module" ":collet-core"]
            ["clojure" "-T:build" "release-all"]
            ["clojure" "-T:build" "release-verify-cli"
             ":tag" "\"io.velio/collet-cli@0.2.8\""]
            ["clojure" "-T:build" "release-verify-image"
             ":tag" "\"io.velio/collet-app@0.2.8\""
             ":image" "\"local-image\""]]
           (mapv vec @commands)))))

(deftest root-build-and-install-dispatch-to-root-tools-build
  (let [commands (atom [])]
    (with-redefs [process/shell (fn [& args]
                                  (swap! commands conj (vec args))
                                  {:exit 0})]
      (workspace/build ["collet-app"])
      (workspace/install ["collet-core"]))
    (is (= [[{:env (workspace/nondeployment-env)}
             "clojure" "-T:build" "build" ":module" ":collet-app"]
            [{:env (workspace/nondeployment-env)}
             "clojure" "-T:build" "install" ":module" ":collet-core"]]
           @commands))))

(deftest verification-dispatches-to-the-root-build-namespace
  (let [commands (atom [])]
    (with-redefs [process/shell (fn [& args]
                                 (swap! commands conj (vec args))
                                 {:exit 0})]
      (workspace/verify []))
    (is (= [[{:env (workspace/nondeployment-env)}
             "clojure" "-T:build" "verify"]]
           @commands))))

(let [{:keys [fail error]} (run-tests 'workspace-test)]
  (when (pos? (+ fail error))
    (System/exit 1)))
