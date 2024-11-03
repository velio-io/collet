(defproject collet "0.1.0-SNAPSHOT"
  :description "Library for defining and executing workflows"
  :url "https://github.com/velio-io/collet"

  :plugins
  [[lein-sub "0.3.0"]]

  :sub
  ["collet-core"
   "collet-actions"
   "collet-app"]

  :dependencies
  [[org.clojure/clojure "1.12.0"]])