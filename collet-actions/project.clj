(defproject collet/collet-actions "0.1.0-SNAPSHOT"
  :description "Actions library for Collet workflows"
  :url "https://github.com/velio-io/collet"

  :scm {:dir ".."}

  :global-vars
  {*warn-on-reflection* true}

  :plugins
  [[lein-ancient "0.7.0"]]    ;; =>> lein ancient

  :dependencies
  [[org.clojure/clojure "1.12.0"]
   [collet/collet-core "0.1.0-SNAPSHOT"]
   [metosin/malli "0.16.4"]
   [diehard "0.11.12"]
   [http-kit "2.8.0"]
   [com.cnuernber/charred "1.034"]
   [techascent/tech.ml.dataset "7.032"]
   [com.github.seancorfield/next.jdbc "1.3.955"]
   [com.github.seancorfield/honeysql "2.6.1196"]
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
  {:dev      {:source-paths ["dev/src"]
              :repl-options {:init-ns dev}
              :dependencies [[eftest "0.6.0"]
                             [clj-test-containers "0.7.4"]
                             [djblue/portal "0.58.2"]]}

   :provided {:dependencies [[org.postgresql/postgresql "42.7.4"]]}})