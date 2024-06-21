(defproject collet "0.1.0-SNAPSHOT"
  :description "Library for defining and executing workflows"
  :url ""

  :dependencies
  [[org.clojure/clojure "1.11.3"]
   [weavejester/dependency "0.2.1"]
   [metosin/malli "0.16.1"]
   [diehard "0.11.12"]]

  :profiles
  {:dev {:source-paths ["dev/src"]
         :repl-options {:init-ns dev}}})
