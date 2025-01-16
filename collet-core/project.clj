(defproject io.velio/collet-core "0.1.3-SNAPSHOT"
  :description "Collet core library"
  :url "https://github.com/velio-io/collet"
  :license
  {:name    "Apache-2.0"
   :comment "Apache License 2.0"
   :url     "https://choosealicense.com/licenses/apache-2.0"
   :year    2024
   :key     "apache-2.0"}

  :scm {:dir ".."}

  :global-vars
  {*warn-on-reflection* true}

  :plugins
  [[lein-ancient "0.7.0"]]    ;; =>> lein ancient

  :dependencies
  [[org.clojure/clojure "1.12.0"]
   [weavejester/dependency "0.2.1"]
   [metosin/malli "0.16.4"]
   [diehard "0.11.12"]
   [clj-commons/pomegranate "1.2.24"]
   [org.babashka/sci "0.9.44"]
   [com.brunobonacci/mulog "0.9.0"]
   [org.slf4j/slf4j-nop "2.0.16"]
   [techascent/tech.ml.dataset "7.032"]
   [org.apache.arrow/arrow-vector "18.1.0"]
   [org.apache.arrow/arrow-memory-netty "18.1.0"]
   [com.cnuernber/jarrow "1.000"]
   [org.apache.commons/commons-compress "1.21"]
   [org.lz4/lz4-java "1.8.0"]
   [net.java.dev.jna/jna "5.10.0"]
   [com.github.luben/zstd-jni "1.5.4-1"]]

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
   "--add-opens=java.base/java.util=ALL-UNNAMED"
   "--add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED"
   "--enable-native-access=ALL-UNNAMED"]

  :profiles
  {:dev     {:source-paths ["dev/src"]
             :repl-options {:init-ns dev}
             :dependencies [[eftest "0.6.0"]
                            [vvvvalvalval/scope-capture "0.3.3"]
                            [clj-test-containers "0.7.4"]
                            [djblue/portal "0.58.2"]]}
   :uberjar {:aot :all}}

  :deploy-repositories
  [["clojars" {:sign-releases false
               :url           "https://clojars.org/repo"
               :username      :env/CLOJARS_USERNAME
               :password      :env/CLOJARS_PASSWORD}]]

  :release-tasks
  [["vcs" "assert-committed"]
   ["test"]
   ["change" "version" "leiningen.release/bump-version" "release"]
   ["vcs" "commit"]
   ["deploy" "clojars"]
   ["change" "version" "leiningen.release/bump-version"]
   ["vcs" "commit"]
   ["vcs" "push"]])
