(ns pod.collet.core
  (:gen-class)
  (:refer-clojure :exclude [read-string])
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.walk :as walk]
   [bencode.core :as bencode]
   [collet.main :as collet.main]
   [collet.core :as collet.core]
   [portal.api :as p])
  (:import
   [java.io EOFException PushbackInputStream StringWriter]))


(def stdin
  (PushbackInputStream. System/in))


(defn b-write [v]
  (bencode/write-bencode System/out v)
  (.flush System/out))


(defn read-string [^"[B" v]
  (String. v))


(defn b-read []
  (bencode/read-bencode stdin))


(def debug? false)


(defn debug [& strs]
  (when debug?
    (binding [*out* (io/writer System/err)]
      (apply println strs))))


(defn list-actions [pipe-spec]
  (let [spec (collet.main/file-or-map :spec pipe-spec)]
    (collet.core/list-actions spec)))


(defn list-tasks [pipe-spec]
  (let [spec (collet.main/file-or-map :spec pipe-spec)]
    (collet.core/list-tasks spec)))


(defn run-action [{:keys [pipe-spec pipe-config context-file action-name]}]
  (let [spec        (collet.main/file-or-map :spec pipe-spec)
        config      (collet.main/file-or-map :config pipe-config)
        context     (if (some? context-file)
                      (collet.main/file-or-map :config context-file)
                      {})
        action-spec (collet.core/find-action spec action-name)]
    (collet.core/check-dependencies (:deps spec) (:tasks spec))
    (collet.core/execute-action action-spec config context)))


(defn run-task [{:keys [pipe-spec pipe-config task-name context-file]}]
  (let [spec      (collet.main/file-or-map :spec pipe-spec)
        config    (collet.main/file-or-map :config pipe-config)
        context   (if (some? context-file)
                    (collet.main/file-or-map :config context-file)
                    {})
        task-spec (collet.core/find-task spec task-name)]
    (collet.core/check-dependencies (:deps spec) (:tasks spec))
    (collet.core/execute-task task-spec config context)))


(defn run-pipeline [{:keys [pipe-spec pipe-config]}]
  (let [spec     (collet.main/file-or-map :spec pipe-spec)
        config   (collet.main/file-or-map :config pipe-config)
        pipeline (collet.core/compile-pipeline spec)]
    @(pipeline config)
    (:results @(.-state pipeline))))


(defn open-portal []
  (p/open)
  (add-tap #'p/submit))


(def lookup
  {'pod.collet.core/compile      collet.main/file-or-map
   'pod.collet.core/list-actions list-actions
   'pod.collet.core/list-tasks   list-tasks
   'pod.collet.core/run-action   run-action
   'pod.collet.core/run-task     run-task
   'pod.collet.core/run-pipeline run-pipeline
   'pod.collet.core/open-portal  open-portal})


(def describe-map
  (walk/postwalk
   (fn [v]
     (if (ident? v) (name v) v))
   `{:format     :edn
     :namespaces [{:name pod.collet.core
                   :vars [{:name compile}
                          {:name list-actions}
                          {:name list-tasks}
                          {:name run-action}
                          {:name run-task}
                          {:name run-pipeline}
                          {:name open-portal}]}]
     :opts       {:shutdown {}}}))


(debug describe-map)


(defn -main [& _args]
  (loop []
    (let [message (try (b-read)
                       (catch EOFException _
                         ::EOF))]
      (when-not (identical? ::EOF message)
        (let [op (get message "op")
              op (read-string op)
              op (keyword op)
              id (some-> (get message "id")
                         read-string)
              id (or id "unknown")]
          (case op
            :describe (do (b-write describe-map)
                          (recur))

            :invoke (do (try
                          (let [var  (-> (get message "var")
                                         read-string
                                         symbol)
                                args (get message "args")
                                args (read-string args)
                                args (edn/read-string args)]
                            (if-let [f (lookup var)]
                              (let [value (binding [*print-meta* true
                                                    *out*        (new StringWriter)]
                                            (let [result (apply f args)]
                                              (pr-str result)))
                                    reply {"value"  value
                                           "id"     id
                                           "status" ["done"]}]
                                (b-write reply))
                              (throw (ex-info (str "Var not found: " var) {}))))
                          (catch Throwable e
                            (debug "error" e)
                            (let [reply {"ex-message" (ex-message e)
                                         "ex-data"    (pr-str
                                                       (assoc (ex-data e)
                                                         :type (class e)))
                                         "id"         id
                                         "status"     ["done" "error"]}]
                              (b-write reply))))
                        (recur))

            :shutdown (System/exit 0)

            (do
              (let [reply {"ex-message" "Unknown op"
                           "ex-data"    (pr-str {:op op})
                           "id"         id
                           "status"     ["done" "error"]}]
                (b-write reply))
              (recur))))))))
