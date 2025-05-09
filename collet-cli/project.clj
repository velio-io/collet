(defproject io.velio/collet-cli "0.2.8-SNAPSHOT"
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
   [io.velio/collet-app "0.2.7"]
   [nrepl/bencode "1.1.0"]
   [djblue/portal "0.58.2"]]

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
   "--enable-native-access=ALL-UNNAMED"])