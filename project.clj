(defproject collet "0.1.0-SNAPSHOT"
  :description "Library for defining and executing workflows"
  :url "https://github.com/velio-io/collet"

  :global-vars
  {*warn-on-reflection* true}

  :plugins
  [[lein-ancient "0.7.0"]] ;; =>> lein ancient

  :dependencies
  [[org.clojure/clojure "1.12.0"]
   [org.clojure/java.jmx "1.1.0"]
   [org.clojure/data.csv "1.1.0"]
   [org.clojure/tools.cli "1.1.230"]
   [weavejester/dependency "0.2.1"]
   [metosin/malli "0.16.4"]
   [diehard "0.11.12"]
   [http-kit "2.8.0"]
   [techascent/tech.ml.dataset "7.032"]
   [clj-commons/pomegranate "1.2.24"]
   [com.github.oliyh/martian "0.1.26"]
   [com.github.seancorfield/next.jdbc "1.3.955"]
   [com.github.seancorfield/honeysql "2.6.1196"]
   [com.brunobonacci/mulog "0.9.0"]
   [com.brunobonacci/mulog-zipkin "0.9.0"]
   [com.brunobonacci/mulog-elasticsearch "0.9.0"]
   [io.zalky/cues "0.2.1"
    :exclusions [com.taoensso/encore net.openhft/chronicle-queue]]
   [com.taoensso/encore "3.122.0"]
   [net.openhft/chronicle-queue "5.26ea5"
    :exclusions [net.openhft/chronicle-analytics]]
   [org.slf4j/slf4j-nop "2.0.16"]
   [com.cognitect.aws/api "0.8.692"]
   [com.cognitect.aws/endpoints "1.1.12.772"]
   [com.cognitect.aws/s3 "869.2.1687.0"]]

  ;; required by Chronicle Queue to work with Java 11, Java 17 or Java 21
  :jvm-opts
  ["--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED"
   "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED"
   "--add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED"
   "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED"
   "--add-opens=jdk.compiler/com.sun.tools.javac=ALL-UNNAMED"
   "--add-opens=java.base/java.lang=ALL-UNNAMED"
   "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
   "--add-opens=java.base/java.io=ALL-UNNAMED"
   "--add-opens=java.base/java.util=ALL-UNNAMED"]

  :profiles
  {:dev        {:source-paths ["dev/src"]
                :repl-options {:init-ns dev}
                :jvm-opts     ["-javaagent:resources/jmx_prometheus_javaagent-0.20.0.jar=8080:resources/jmx.yaml"]
                :dependencies [[eftest "0.6.0"]
                               [clj-test-containers "0.7.4"]
                               [djblue/portal "0.58.2"]
                               [vvvvalvalval/scope-capture "0.3.3"]]
                :injections   [(require 'sc.api)]}

   :standalone {:main         ^:skip-aot collet.main
                :uberjar-name "collet.jar"
                :aot          :all}

   :provided   {:dependencies [[org.postgresql/postgresql "42.7.4"]]}})
