(defproject io.velio/collet-app "0.1.0-SNAPSHOT"
  :description "Standalone Collet application"
  :url "https://github.com/velio-io/collet"

  :scm {:dir ".."}

  :main ^:skip-aot collet.main

  :plugins
  [[lein-ancient "0.7.0"]]    ;; =>> lein ancient

  :dependencies
  [[org.clojure/clojure "1.12.0"]
   [org.clojure/java.jmx "1.1.0"]
   [org.clojure/tools.cli "1.1.230"]
   [io.velio/collet-core "0.1.0-SNAPSHOT"]
   [com.brunobonacci/mulog "0.9.0"]
   [com.brunobonacci/mulog-zipkin "0.9.0"]
   [com.brunobonacci/mulog-elasticsearch "0.9.0"]
   [org.slf4j/slf4j-nop "2.0.16"]
   [com.cognitect.aws/api "0.8.692"]
   [com.cognitect.aws/endpoints "1.1.12.772"]
   [com.cognitect.aws/s3 "869.2.1687.0"]]

   ;;:jvm-opts     ["-javaagent:resources/jmx_prometheus_javaagent-0.20.0.jar=8080:resources/jmx.yaml"]

  :profiles
  {:dev     {:dependencies [[clj-test-containers "0.7.4"]]}

   :uberjar {:uberjar-name "collet.jar"
             :aot          :all}})

