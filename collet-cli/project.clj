(defproject io.velio/collet-cli "0.2.5-SNAPSHOT"
  :description "CLI interface for Collet app"
  :url "https://github.com/velio-io/collet"
  :license
  {:name    "Apache-2.0"
   :comment "Apache License 2.0"
   :url     "https://choosealicense.com/licenses/apache-2.0"
   :year    2024
   :key     "apache-2.0"}

  :scm {:dir ".."}

  :main ^:skip-aot pod.collet.core

  :profiles
  {:uberjar {:uberjar-name "collet.pod.jar"
             :aot          :all}}

  :dependencies
  [[org.clojure/clojure "1.12.0"]
   [io.velio/collet-app "0.2.4"]
   [nrepl/bencode "1.1.0"]
   [djblue/portal "0.58.2"]])