(defproject collet "0.1.0-SNAPSHOT"
  :description "Library for defining and executing workflows"
  :url ""

  :dependencies
  [[org.clojure/clojure "1.11.3"]
   [weavejester/dependency "0.2.1"]
   [metosin/malli "0.16.1"]
   [diehard "0.11.12"]
   [http-kit "2.3.0"]
   [clj-commons/pomegranate "1.2.24"]
   [com.github.oliyh/martian "0.1.26"]
   [com.github.seancorfield/next.jdbc "1.3.939"]
   [com.github.seancorfield/honeysql "2.6.1147"]]

  :profiles
  {:dev      {:source-paths ["dev/src"]
              :repl-options {:init-ns dev}
              :dependencies [[eftest "0.6.0"]
                             [vvvvalvalval/scope-capture "0.3.3"]
                             [clj-test-containers "0.7.4"]]
              :injections   [(require 'sc.api)]}

   :provided {:dependencies [[org.postgresql/postgresql "42.7.3"]]}})
