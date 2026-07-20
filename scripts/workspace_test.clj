(ns workspace-test
  (:require [clojure.edn :as edn]
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
  (is (= ["clojure" "-M:kmono" "run" "--M" ":test" "-e" ":integration"]
         (workspace/unit-test-command))))

(deftest integration-tests-select-the-empty-integration-marker-alias
  (is (= ["clojure" "-M:kmono" "run" "--M" ":test:integration"
          "-i" ":integration"]
         (workspace/integration-test-command))))

(deftest module-tests-filter-kmono-to-the-requested-package-and-forward-runner-options
  (is (= ["clojure" "-M:kmono" "run" "-F" ":io.velio/collet-action-http"
          "--M" ":test" "--" "--focus" "collet.actions.http-test"]
         (workspace/module-test-command
          :collet-action-http
          ["--focus" "collet.actions.http-test"]))))

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

(let [{:keys [fail error]} (run-tests 'workspace-test)]
  (when (pos? (+ fail error))
    (System/exit 1)))
