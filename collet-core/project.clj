(defproject collet/collet-core "0.1.0-SNAPSHOT"
  :description "Collet core library"
  :url "https://github.com/velio-io/collet"

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
   [techascent/tech.ml.dataset "7.032"]
   [clj-commons/pomegranate "1.2.24"]
   [com.brunobonacci/mulog "0.9.0"]
   [org.slf4j/slf4j-nop "2.0.16"]]

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
  {:dev      {:source-paths ["dev/src"]
              :repl-options {:init-ns dev}
              :dependencies [[eftest "0.6.0"]
                             [clj-test-containers "0.7.4"]
                             [djblue/portal "0.58.2"]]}})