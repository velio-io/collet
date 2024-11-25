(defproject io.velio/collet-cli "0.1.0-SNAPSHOT"
  :description "CLI interface for Collet app"
  :url "https://github.com/velio-io/collet"

  :scm {:dir ".."}

  :main ^:skip-aot pod.collet.core

  :dependencies
  [[org.clojure/clojure "1.12.0"]
   [io.velio/collet-app "0.1.0-SNAPSHOT"]
   [nrepl/bencode "1.1.0"]])