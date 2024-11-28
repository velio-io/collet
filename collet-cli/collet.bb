#!/usr/bin/env bb

(require
 '[babashka.pods :as pods]
 '[babashka.cli :as cli]
 '[bblgum.core :as b]
 '[clojure.string :as string]
 '[puget.printer :as puget])


;; run collet pod
;; (pods/load-pod ["java" "-jar" "./collet.pod.jar"])
(pods/load-pod ["lein" "run"])
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


(defn message [text]
  (->> (b/gum :style [text] :foreground 212 :gum-path "./gum")
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
               :gum-path "./gum")]
    (first result)))


(defn run-action [options]
  (let [{:keys [status result]}
        (b/gum :choose (collet/list-actions (:pipe-spec options))
               :header "Choose action"
               :gum-path "./gum")
        action-name   (-> result first (subs 1) keyword)
        action-result (-> (collet/run-action {:pipe-spec    (:pipe-spec options)
                                              :pipe-config  (:config options)
                                              :context-file (:context-file options)
                                              :action-name  action-name})
                          (get-in [:state action-name]))]
    (println "Action result:")
    (puget/cprint action-result)
    (println)))


(defn run-task [options]
  (let [{:keys [status result]}
        (b/gum :choose (collet/list-tasks (:pipe-spec options))
               :header "Choose task"
               :gum-path "./gum")
        task-name   (-> result first (subs 1) keyword)
        task-result (collet/run-task {:pipe-spec    (:pipe-spec options)
                                      :pipe-config  (:config options)
                                      :context-file (:context-file options)
                                      :task-name    task-name})]
    (println "Task result:")
    (puget/cprint task-result)
    (println)))


(defn run-pipeline [options]
  (let [result (collet/run-pipeline {:pipe-spec   (:pipe-spec options)
                                     :pipe-config (:config options)})]
    (println "Pipeline result:")
    (puget/cprint result)
    (println)))


(defn main [& args]
  (let [options  (cli/parse-opts args collet-cli-spec)
        *command (atom nil)]
    ;; show header
    (message "Welcome to Collet CLI")

    ;; choose command
    (loop [cmd (ask-command)]
      (when-not (= cmd "repeat action")
        (reset! *command cmd))

      (try (case cmd
             "run action" (run-action options)
             "run task" (run-task options)
             "run pipeline" (run-pipeline options)
             "show spec" (do (puget/cprint (collet/compile :spec (:pipe-spec options)))
                             (println))
             "open portal view" (collet/open-portal)
             "exit" (do (message "Bye!")
                        (System/exit 0))
             nil)
           (catch Exception ex
             (message (format "Error executing command %s" cmd))
             (message (ex-message ex))))

      ;; ask again
      (if (= cmd "repeat action")
        (recur @*command)
        (recur (ask-command))))))


(apply main *command-line-args*)
