(defproject io.velio/collet "0.2.7"
  :description "Library for defining and executing workflows"
  :url "https://github.com/velio-io/collet"
  :license {:name    "Apache-2.0"
            :comment "Apache License 2.0"
            :url     "https://choosealicense.com/licenses/apache-2.0"
            :year    2024
            :key     "apache-2.0"}

  :plugins
  [[lein-sub "0.3.0"]
   [lein-license "1.0.0"]]

  :sub
  ["collet-core"
   "collet-actions"
   "collet-app"]

  :dependencies
  [[org.clojure/clojure "1.12.0"]]

  :aliases
  {"install" ["sub" "install"]
   "test"    ["sub" "test"]})