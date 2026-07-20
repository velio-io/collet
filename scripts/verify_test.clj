(ns verify-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [verify]))

(defn- per-artifact-tag-pattern []
  (var-get (ns-resolve 'verify 'per-artifact-tag-pattern)))

(deftest rejects-per-artifact-tags-with-placeholder-and-version-forms
  (let [pattern (per-artifact-tag-pattern)]
    (is (re-find pattern (str "<module>" "-v<version>")))
    (is (re-find pattern (str "collet-core-v" "VERSION")))
    (is (not (re-find pattern "v<version>")))
    (is (not (re-find pattern "v0.3.0")))))

(let [{:keys [fail error]} (run-tests 'verify-test)]
  (when (pos? (+ fail error))
    (System/exit 1)))
