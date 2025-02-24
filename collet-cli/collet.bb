#!/usr/bin/env bb

(ns collet
  (:require
   [babashka.pods :as pods]
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [bblgum.core :as b]
   [clojure.string :as string]
   [puget.printer :as puget]))


(def pod-jar-path
  (string/replace *file* "collet.bb" "collet.pod.jar"))

;; run collet pod
(pods/load-pod
 ["java"
  "--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED"
  "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED"
  "--add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED"
  "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED"
  "--add-opens=jdk.compiler/com.sun.tools.javac=ALL-UNNAMED"
  "--add-opens=java.base/java.lang=ALL-UNNAMED"
  "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
  "--add-opens=java.base/java.io=ALL-UNNAMED"
  "--add-opens=java.base/java.util=ALL-UNNAMED"
  "--add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED"
  "--enable-native-access=ALL-UNNAMED"
  "-jar" pod-jar-path])
;;(pods/load-pod ["lein" "run"])
(require '[pod.collet.core :as collet])


(defn print-error
  [{:keys [spec type cause msg option] :as data}]
  (when (= :org.babashka/cli type)
    (case cause
      :require (println
                (format "Missing required argument: %s\n" option))
      :validate (println
                 (format "%s!\n" msg)))))


(def collet-cli-spec
  {:spec     {:pipe-spec    {:desc  "Pipeline spec file"
                             :alias :s}
              :context-file {:desc  "File with the context data"
                             :alias :x}
              :config       {:desc    "Pipeline config file"
                             :alias   :c
                             :default "{}"}}
   :error-fn print-error})


(def gum-path
  (string/replace *file* "collet.bb" "gum"))


(defn message [text]
  (->> (b/gum :style [text] :foreground 212 :gum-path gum-path)
       :result
       first
       (println))
  (println))


(defn ask-command []
  (let [{:keys [status result]}
        (b/gum :choose ["repeat action"
                        "run action"
                        "run task"
                        "run pipeline"
                        "show spec"
                        "open portal view"
                        "exit"]
               :header "Choose an command"
               :gum-path gum-path)]
    (first result)))


(defn run-action [options]
  (let [{:keys [status result]}
        (b/gum :choose (collet/list-actions (:pipe-spec options))
               :header "Choose action"
               :gum-path gum-path)
        action-name   (-> result first (subs 1) keyword)
        action-result (-> (collet/run-action {:pipe-spec    (:pipe-spec options)
                                              :pipe-config  (:config options)
                                              :context-file (:context-file options)
                                              :action-name  action-name})
                          (get-in [:state action-name]))]
    (if (:portal-opened options)
      (println "Action finished")
      (do
        (println "Action result:")
        (puget/cprint action-result)
        (println)))))


(defn run-task [options]
  (let [{:keys [status result]}
        (b/gum :choose (collet/list-tasks (:pipe-spec options))
               :header "Choose task"
               :gum-path gum-path)
        task-name   (-> result first (subs 1) keyword)
        task-result (collet/run-task {:pipe-spec    (:pipe-spec options)
                                      :pipe-config  (:config options)
                                      :context-file (:context-file options)
                                      :task-name    task-name})]
    (if (:portal-opened options)
      (println "Task finished")
      (do
        (println "Task result:")
        (puget/cprint task-result)
        (println)))))


(defn run-pipeline [options]
  (let [result (collet/run-pipeline {:pipe-spec   (:pipe-spec options)
                                     :pipe-config (:config options)})]
    (if (:portal-opened options)
      (println "Pipeline finished")
      (do
        (println "Pipeline result:")
        (puget/cprint result)
        (println)))))


(defn main [& args]
  (when-not (fs/exists? ".collet")
    (fs/create-dir ".collet"))

  (let [options        (cli/parse-opts args collet-cli-spec)
        options        (if (nil? (:context-file options))
                         (let [temp-file (fs/create-temp-file {:dir    ".collet"
                                                               :suffix ".edn"})
                               path      (str temp-file)]
                           (fs/delete-on-exit temp-file)
                           (spit path "{}")
                           (assoc options :context-file path))
                         options)
        *command       (atom nil)
        *portal-opened (atom false)]
    ;; show header
    (message "Welcome to Collet CLI")

    ;; choose command
    (loop [cmd (ask-command)]
      (when-not (= cmd "repeat action")
        (reset! *command cmd))

      (let [options (assoc options :portal-opened @*portal-opened)]
        (try (case cmd
               "run action" (run-action options)
               "run task" (run-task options)
               "run pipeline" (run-pipeline options)
               "show spec" (do (puget/cprint (collet/compile (:pipe-spec options)))
                               (println))
               "open portal view" (do (collet/open-portal)
                                      (reset! *portal-opened true))
               "exit" (do (message "Bye!")
                          (System/exit 0))
               nil)
             (catch Exception ex
               (message (format "Error executing command %s" cmd))
               (message (ex-message ex))
               (message (ex-cause ex))
               (message ex))))

      ;; ask again
      (if (= cmd "repeat action")
        (recur @*command)
        (recur (ask-command))))))


(apply main *command-line-args*)
