(defproject io.velio/collet-app "0.2.2-SNAPSHOT"
  :description "Standalone Collet application"
  :url "https://github.com/velio-io/collet"
  :license
  {:name    "Apache-2.0"
   :comment "Apache License 2.0"
   :url     "https://choosealicense.com/licenses/apache-2.0"
   :year    2024
   :key     "apache-2.0"}

  :scm {:dir ".."}

  :main ^:skip-aot collet.main

  :plugins
  [[lein-ancient "0.7.0"]]    ;; =>> lein ancient

  :dependencies
  [[org.clojure/clojure "1.12.0"]
   [org.clojure/java.jmx "1.1.0"]
   [org.clojure/tools.cli "1.1.230"]
   [io.velio/collet-core "0.2.1"]
   [com.brunobonacci/mulog "0.9.0"]
   [com.brunobonacci/mulog-zipkin "0.9.0"]
   [com.brunobonacci/mulog-elasticsearch "0.9.0"]
   [org.slf4j/slf4j-nop "2.0.16"]
   [com.cognitect.aws/api "0.8.692"]
   [com.cognitect.aws/endpoints "1.1.12.772"]
   [com.cognitect.aws/s3 "869.2.1687.0"]]

  ;;:jvm-opts     ["-javaagent:resources/jmx_prometheus_javaagent-0.20.0.jar=8080:resources/jmx.yaml"]

  :jvm-opts
  ["--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED"
   "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED"
   "--add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED"
   "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED"
   "--add-opens=jdk.compiler/com.sun.tools.javac=ALL-UNNAMED"
   "--add-opens=java.base/java.lang=ALL-UNNAMED"
   "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
   "--add-opens=java.base/java.io=ALL-UNNAMED"
   "--add-opens=java.base/java.util=ALL-UNNAMED"
   "--add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED"
   "--enable-native-access=ALL-UNNAMED"]

  :profiles
  {:dev     {:dependencies [[clj-test-containers "0.7.4"]]}

   :uberjar {:uberjar-name "collet.jar"
             :aot          :all}}

  :deploy-repositories
  [["clojars" {:sign-releases false
               :url           "https://clojars.org/repo"
               :username      :env/CLOJARS_USERNAME
               :password      :env/CLOJARS_PASSWORD}]]

  :release-tasks
  [["vcs" "assert-committed"]
   ["change" "version" "leiningen.release/bump-version" "release"]
   ["vcs" "commit"]
   ["deploy" "clojars"]
   ["change" "version" "leiningen.release/bump-version"]
   ["vcs" "commit"]
   ["vcs" "push"]])

