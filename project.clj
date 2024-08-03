(defproject collet "0.1.0-SNAPSHOT"
  :description "Library for defining and executing workflows"
  :url ""

  :dependencies
  [[org.clojure/clojure "1.11.3"]
   [org.clojure/java.jmx "1.1.0"]
   [weavejester/dependency "0.2.1"]
   [metosin/malli "0.16.1"]
   [diehard "0.11.12"]
   [http-kit "2.3.0"]
   [clj-commons/pomegranate "1.2.24"]
   [com.github.oliyh/martian "0.1.26"]
   [com.github.seancorfield/next.jdbc "1.3.939"]
   [com.github.seancorfield/honeysql "2.6.1147"]
   [com.brunobonacci/mulog "0.9.0"]]

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
