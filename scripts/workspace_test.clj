(ns workspace-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [workspace :as workspace]))

(deftest unit-test-command-vectors
  (is (= [["clojure" "-T:build-test"]
          ["bb" "-cp" "scripts" "scripts/workspace_test.clj"]
          ["clojure" "-M:kmono" "run" "--M" ":test"
           "--" "-e" ":integration"]]
         (workspace/unit-test-commands))))

(deftest integration-test-command-vectors
  (is (= [["clojure" "-T:build" "build" ":module" ":collet-app"]
          ["clojure" "-T:build" "build" ":module" ":collet-cli"]
          ["clojure" "-M:kmono" "run" "--M" ":test:integration"
           "--" "-i" ":integration"]]
         (workspace/integration-test-commands))))

(deftest module-test-command-vector
  (is (= [["clojure" "-M:kmono" "run" "-F" ":io.velio/collet-action-http"
           "--M" ":test" "--" "--focus" "collet.actions.http-test"]]
         (workspace/module-test-commands
          ["collet-action-http" "--focus" "collet.actions.http-test"]))))

(deftest executable-module-test-command-vectors-build-the-required-artifact
  (is (= [["clojure" "-T:build" "build" ":module" ":collet-cli"]
          ["clojure" "-M:kmono" "run" "-F" ":io.velio/collet-cli"
           "--M" ":test"]]
         (workspace/module-test-commands ["collet-cli"]))))

(deftest build-command-vector
  (is (= [["clojure" "-T:build" "build" ":module" ":collet-app"]]
         (workspace/build-commands ["collet-app"]))))

(deftest install-command-vector
  (is (= [["clojure" "-T:build" "install" ":module" ":collet-core"]]
         (workspace/install-commands ["collet-core"]))))

(deftest verify-command-vector
  (is (= [["clojure" "-T:build" "verify"]]
         (workspace/verify-commands []))))

(deftest release-plan-command-vector
  (is (= [["clojure" "-T:build" "release-plan" ":module" ":collet-core"]]
         (workspace/release-plan-commands ["collet-core"]))))

(let [{:keys [fail error]} (run-tests 'workspace-test)]
  (when (pos? (+ fail error))
    (System/exit 1)))
