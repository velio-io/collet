(defproject collet "0.1.0-SNAPSHOT"
  :description "Library for defining and executing workflows"
  :url "https://github.com/velio-io/collet"

  :dependencies
  [[org.clojure/clojure "1.11.3"]
   [org.clojure/java.jmx "1.1.0"]
   [org.clojure/data.csv "1.1.0"]
   [weavejester/dependency "0.2.1"]
   [metosin/malli "0.16.1"]
   [diehard "0.11.12"]
   [http-kit "2.3.0"]
   [clj-commons/pomegranate "1.2.24"]
   [com.github.oliyh/martian "0.1.26"]
   [com.github.seancorfield/next.jdbc "1.3.939"]
   [com.github.seancorfield/honeysql "2.6.1147"]
   [com.brunobonacci/mulog "0.9.0"]
   [io.zalky/cues "0.2.1"
    :exclusions [com.taoensso/encore net.openhft/chronicle-queue]]
   [com.taoensso/encore "3.23.0"]
   [net.openhft/chronicle-queue "5.26ea5"
    :exclusions [net.openhft/chronicle-analytics]]
   [org.slf4j/slf4j-nop "2.0.6"]]

  ;; required by Chronicle Queue to work with Java 11, Java 17 or Java 21
  :jvm-opts ["--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED"
             "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED"
             "--add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED"
             "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED"
             "--add-opens=jdk.compiler/com.sun.tools.javac=ALL-UNNAMED"
             "--add-opens=java.base/java.lang=ALL-UNNAMED"
             "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
             "--add-opens=java.base/java.io=ALL-UNNAMED"
             "--add-opens=java.base/java.util=ALL-UNNAMED"]

  :profiles
  {:dev      {:source-paths ["dev/src"]
              :repl-options {:init-ns dev}
              :jvm-opts     ["-javaagent:resources/jmx_prometheus_javaagent-0.20.0.jar=8080:resources/jmx.yaml"]
              :dependencies [[eftest "0.6.0"]
                             [vvvvalvalval/scope-capture "0.3.3"]
                             [clj-test-containers "0.7.4"]
                             [com.brunobonacci/mulog-zipkin "0.9.0"]
                             [com.brunobonacci/mulog-elasticsearch "0.9.0"]]
              :injections   [(require 'sc.api)]}

   :provided {:dependencies [[org.postgresql/postgresql "42.7.3"]]}})
