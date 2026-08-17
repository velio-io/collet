(ns collet.runtime-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [collet.action :as action]
   [collet.artifact :as artifact]
   [collet.core :as collet]
   [collet.store :as store]
   [collet.store.datalevin :as datalevin]
   [datalevin.core :as d]
   [tech.v3.dataset :as ds])
  (:import
    [java.nio.file FileVisitOption Files Path]
    [java.nio.file.attribute FileAttribute]
    [java.util UUID]
    [java.util.concurrent AbstractExecutorService]))


(def task-gate (atom nil))


(def task-input-ready (atom nil))


(def active-tasks (atom 0))


(def max-active-tasks (atom 0))


(def task-executions (atom 0))


(def expansion-executions (atom 0))


(defn one []
  1)


(defn two []
  2)


(defn plus-two [value]
  (+ value 2))


(defn fail! []
  (throw (ex-info "boom" {:action :explode})))


(defn store? [value]
  (satisfies? store/Store value))


(defn rows []
  [{:id 1} {:id 2}])


(defn empty-rows []
  [])


(defn record-execution []
  (swap! task-executions inc))


(defn counted-rows []
  (swap! task-executions inc)
  [{:id 1} {:id 2}])


(defn skipped-records []
  (with-meta (map identity [{:json {:a 1}}])
             {:collet.arrow/skip-conversion? true}))


(defn record-expansion []
  (swap! expansion-executions inc))


(defmethod action/action-fn :count-expansion
  [_]
  (fn [] nil))


(defmethod action/expand :count-expansion
  [task _]
  (update task
          :actions
          conj
          {:name :expansion-marker
           :type :custom
           :fn   'collet.runtime-test/record-expansion}))


(defn- faulting-store
  [delegate fault]
  (reify
   store/Store
   (close! [_]
     (store/close! delegate))
   (save-pipeline! [_ pipeline]
     (store/save-pipeline! delegate pipeline))
   (load-pipeline [_ name]
     (store/load-pipeline delegate name))
   (load-pipeline [_ name version]
     (store/load-pipeline delegate name version))
   (create-run! [_ run task-runs]
     (store/create-run! delegate run task-runs))
   (get-run [_ run-id]
     (store/get-run delegate run-id))
   (get-task-runs [_ run-id]
     (store/get-task-runs delegate run-id))
   (update-run! [_ run-id changes]
     (when-let [error (fault :update-run changes)]
       (throw error))
     (store/update-run! delegate run-id changes))
   (update-task! [_ task-id changes]
     (when-let [error (fault :update-task changes)]
       (throw error))
     (store/update-task! delegate task-id changes))
   (complete-task! [_ task-id completion]
     (when-let [error (fault :complete-task completion)]
       (throw error))
     (store/complete-task! delegate task-id completion))
   (finalize-run! [_ run-id run-changes retained-task-ids]
     (when-let [error (fault :finalize-run
                             {:run/id            run-id
                              :run/changes       run-changes
                              :retained-task-ids retained-task-ids})]
       (throw error))
     (store/finalize-run! delegate run-id run-changes retained-task-ids))
   (get-task-output [_ task-id]
     (store/get-task-output delegate task-id))
   (get-artifact [_ artifact-id]
     (store/get-artifact delegate artifact-id))
   (get-lineage [_ task-id direction]
     (store/get-lineage delegate task-id direction))))


(defn gated-one
  []
  @(deref task-gate)
  1)


(defn gated-plus-two
  [value]
  @(deref task-gate)
  (+ value 2))


(defn gated-row-count
  [datasets]
  (let [row-count (reduce + (map ds/row-count datasets))]
    (deliver @task-input-ready row-count)
    @(deref task-gate)
    row-count))


(defn gated-fail!
  []
  @(deref task-gate)
  (fail!))


(defn counted-task
  []
  (let [active (swap! active-tasks inc)]
    (swap! max-active-tasks max active)
    (try
      (Thread/sleep 150)
      active
      (finally
       (swap! active-tasks dec)))))


(defn- temporary-store-path
  ^Path
  []
  (Files/createTempDirectory
   "collet-runtime-"
   (make-array FileAttribute 0)))


(defn- artifact-directory
  ^Path
  [ctx artifact]
  (.resolve ^Path (:artifact-dir ctx)
            (str "runs/"
                 (:artifact/run-id artifact)
                 "/tasks/"
                 (:artifact/task-id artifact)
                 "/"
                 (:artifact/id artifact))))


(defn- delete-store!
  [^Path path]
  (when (Files/exists path (make-array java.nio.file.LinkOption 0))
    (with-open [paths (Files/walk path (make-array FileVisitOption 0))]
      (doseq [entry (sort-by str
                             #(compare %2 %1)
                             (iterator-seq (.iterator paths)))]
        (Files/deleteIfExists ^Path entry)))))


(defn- wait-until
  [pred]
  (loop [remaining 200]
    (when (and (not (pred)) (pos? remaining))
      (Thread/sleep 10)
      (recur (dec remaining))))
  (pred))


(defn- task-status
  [ctx run task-name]
  (->> (store/get-task-runs (:store ctx) (:run/id run))
       (some (fn [task]
               (when (= task-name (:task/name task))
                 (:task/status task))))))


(defn basic-pipeline
  ([]
   (basic-pipeline 1))
  ([version]
   (collet/compile-pipeline
    {:name    :orders
     :version version
     :tasks   [{:name       :fetch
                :keep-state true
                :actions    [{:name :fetch
                              :type :custom
                              :fn   'collet.runtime-test/one}]}
               {:name       :load
                :inputs     [:fetch]
                :keep-state true
                :actions    [{:name      :load
                              :type      :custom
                              :selectors '{value [:inputs :fetch]}
                              :params    '[value]
                              :fn        'collet.runtime-test/plus-two}]}
               {:name       :has-store
                :keep-state true
                :actions    [{:name      :has-store
                              :type      :custom
                              :selectors '{value [:store]}
                              :params    '[value]
                              :fn        'collet.runtime-test/store?}]}]})))


(deftest context-construction-opens-the-store
  (let [parent (temporary-store-path)
        path   (.resolve parent "db")
        db     (datalevin/store {:dir (str path)})
        ctx    (collet/context
                {:store db})]
    (try
      (is (Files/exists path (make-array java.nio.file.LinkOption 0)))
      (is (map? ctx))
      (is (identical? db (:store ctx)))
      (is (= "./.collet/artifacts" (str (:artifact-dir ctx))))
      (finally
       (collet/close ctx)
       (delete-store! parent)))))


(deftest task-future-is-registered-before-execution
  (let [run-id       (random-uuid)
        task-id      (random-uuid)
        task-name    :fast
        task-futures (atom {})
        runtime      {:tasks        {task-name {:name task-name}}
                      :task-futures task-futures}
        executor     (proxy [AbstractExecutorService] []
                       (shutdown [])
                       (shutdownNow [] [])
                       (isShutdown [] false)
                       (isTerminated [] false)
                       (awaitTermination [_ _] true)
                       (execute [command]
                         (.run ^Runnable command)))
        ctx          {:store    ::store
                      :executor executor
                      :runs     (atom {run-id runtime})}
        task         {:task/id   task-id
                      :task/name task-name}]
    (with-redefs-fn
      {#'store/update-task!
       (fn [_ _ changes]
         (merge task changes))
       #'collet/execute-run-task!
       (fn [_ _ started-task]
         (swap! task-futures dissoc (:name started-task)))}
      #(do
         (#'collet/start-ready-task! ctx run-id task)
         (is (empty? @task-futures))))))


(deftest durable-run-executes-through-the-store
  (let [path      (temporary-store-path)
        callbacks (atom [])
        db        (datalevin/store {:dir (str path)})
        ctx       (collet/context
                   {:store            db
                    :on-task-start    #(swap! callbacks conj [:start (:task/name %)])
                    :on-task-complete #(swap! callbacks conj
                                         [:complete (:task/name %)])})]
    (try
      (let [run    (collet/start ctx (basic-pipeline) {:secret "not-persisted"})
            result @run
            tasks  (store/get-task-runs db (:run/id result))]
        (is (instance? UUID (:run/id result)))
        (is (= :done (:run/status result)))
        (is (= {:pipeline/name :orders :pipeline/version 1}
               (:run/pipeline result)))
        (is (= :done (collet/pipe-status run)))
        (is (nil? (collet/pipe-error run)))
        (is (= 1 (:fetch run)))
        (is (= 3 (:load run)))
        (is (true? (:has-store run)))
        (is (= #{:completion :results}
               (-> @(:runs ctx)
                   (get (:run/id result))
                   keys
                   set)))
        (is (every? #(= :completed (:task/status %)) tasks))
        (is (= #{[:start :fetch] [:complete :fetch]
                 [:start :load] [:complete :load]
                 [:start :has-store] [:complete :has-store]}
               (set @callbacks)))
        (is (not-any? #(contains? % :secret) (cons result tasks)))
        (is (not-any? #(contains? % :result) (cons result tasks))))
      (finally
       (collet/close ctx)
       (delete-store! path)))))


(deftest only-retained-results-publish-artifacts
  (let [path     (temporary-store-path)
        ctx      (collet/context {:data-dir path})
        pipeline (collet/compile-pipeline
                  {:name :retained-results
                   :tasks
                   [{:name    :unused
                     :actions [{:name :unused
                                :type :custom
                                :fn   'collet.runtime-test/record-execution}]}
                    {:name    :source
                     :actions [{:name :source
                                :type :custom
                                :fn   'collet.runtime-test/record-execution}]}
                    {:name    :dependent
                     :inputs  [:source]
                     :actions [{:name      :dependent
                                :type      :custom
                                :selectors '{value [:inputs :source]}
                                :params    '[value]
                                :fn        'collet.runtime-test/plus-two}]}
                    {:name       :kept
                     :keep-state true
                     :actions    [{:name :kept
                                   :type :custom
                                   :fn   'collet.runtime-test/record-execution}]}]})]
    (try
      (reset! task-executions 0)
      (let [run     (collet/start ctx pipeline {})
            result  @run
            by-name (into {}
                          (map (juxt :task/name identity))
                          (store/get-task-runs (:store ctx) (:run/id result)))]
        (is (= :done (:run/status result)))
        (is (nil? (:task/output (get by-name :unused))))
        (is (nil? (:task/output (get by-name :dependent))))
        (is (nil? (:task/output (get by-name :source))))
        (is (some? (:task/output (get by-name :kept))))
        (is (nil? (:source run)))
        (is (some? (:kept run)))
        (is (= :computed (:task/outcome (get by-name :source))))
        (is (= :computed (:task/outcome (get by-name :kept)))))
      (finally
       (collet/close ctx)
       (delete-store! path)))))


(deftest dataset-handoff-remains-until-running-readers-quiesce
  (let [path     (temporary-store-path)
        ctx      (collet/context {:data-dir path})
        pipeline (collet/compile-pipeline
                  {:name      :dataset-handoff-lifecycle
                   :use-arrow true
                   :tasks     [{:name    :source
                                :actions [{:name :source
                                           :type :custom
                                           :fn   'collet.runtime-test/rows}]}
                               {:name       :count-rows
                                :inputs     [:source]
                                :keep-state true
                                :actions    [{:name      :count-rows
                                              :type      :custom
                                              :selectors '{datasets [:inputs :source]}
                                              :params    '[datasets]
                                              :fn        'collet.runtime-test/gated-row-count}]}]})]
    (try
      (reset! task-gate (promise))
      (reset! task-input-ready (promise))
      (let [run (collet/start ctx pipeline {})]
        (is (= 2 (deref @task-input-ready 10000 ::timeout)))
        (let [tasks      (into {}
                               (map (juxt :task/name identity))
                               (store/get-task-runs (:store ctx) (:run/id run)))
              source     (get tasks :source)
              output-ref (:task/output source)
              artifact   (store/get-artifact (:store ctx) (second output-ref))
              directory  (artifact-directory ctx artifact)]
          (is (= :artifact/id (first output-ref)))
          (is (= :dataset (:artifact/kind artifact)))
          (is (Files/exists directory (make-array java.nio.file.LinkOption 0)))
          (is (= [{:id 1} {:id 2}]
                 (mapv #(into {} %)
                       (mapcat ds/rows (collet/read-output ctx output-ref)))))

          (deliver @task-gate true)
          (is (= :done (:run/status @run)))
          (is (= 2 (:count-rows run)))
          (is (nil? (store/get-task-output (:store ctx) (:task/id source))))
          (is (nil? (store/get-artifact (:store ctx) (second output-ref))))
          (is (not (Files/exists directory
                                 (make-array java.nio.file.LinkOption 0))))))
      (finally
       (deliver @task-gate true)
       (collet/close ctx)
       (delete-store! path)))))


(deftest store-release-failure-preserves-metadata-and-files
  (let [path          (temporary-store-path)
        db            (datalevin/store {:dir (str (.resolve path "db"))})
        artifact-dir  (.resolve path "artifacts")
        release-error (ex-info "release failed" {})
        ctx           (collet/context
                       {:store
                        (faulting-store
                         db
                         (fn [operation _]
                           (when (= :finalize-run operation)
                             release-error)))
                        :artifact-dir artifact-dir})
        pipeline      (collet/compile-pipeline
                       {:name  :store-release-failure
                        :tasks [{:name    :source
                                 :actions [{:name :source
                                            :type :custom
                                            :fn   'collet.runtime-test/one}]}
                                {:name    :dependent
                                 :inputs  [:source]
                                 :actions [{:name      :dependent
                                            :type      :custom
                                            :selectors '{value [:inputs :source]}
                                            :params    '[value]
                                            :fn        'collet.runtime-test/plus-two}]}]})]
    (try
      (let [run       (collet/start ctx pipeline {})
            result    @run
            tasks     (store/get-task-runs db (:run/id result))
            source    (some #(when (= :source (:task/name %)) %) tasks)
            output    (:task/output source)
            artifact  (store/get-artifact db (second output))
            directory (artifact-directory ctx artifact)]
        (is (= :failed (:run/status result)))
        (is (= "Pipeline persistence failed."
               (get-in result [:run/error :message])))
        (is (= :artifact/id (first output)))
        (is (= artifact (store/get-artifact db (second output))))
        (is (Files/exists directory (make-array java.nio.file.LinkOption 0)))
        (collet/close ctx)
        (let [reopened (collet/context {:data-dir path})]
          (try
            (is (= artifact
                   (store/get-artifact (:store reopened) (second output))))
            (let [read-error (try
                               (collet/read-output reopened output)
                               nil
                               (catch clojure.lang.ExceptionInfo error
                                 error))]
              (is (= :collet.error/unknown-output
                     (:collet.error/type (ex-data read-error)))))
            (finally
             (collet/close reopened)))))
      (finally
       (collet/close ctx)
       (delete-store! path)))))


(deftest filesystem-delete-failure-leaves-an-invisible-orphan
  (let [path     (temporary-store-path)
        ctx      (collet/context {:data-dir path})
        pipeline (collet/compile-pipeline
                  {:name  :filesystem-release-failure
                   :tasks [{:name    :source
                            :actions [{:name :source
                                       :type :custom
                                       :fn   'collet.runtime-test/one}]}
                           {:name       :dependent
                            :inputs     [:source]
                            :keep-state true
                            :actions    [{:name      :dependent
                                          :type      :custom
                                          :selectors '{value [:inputs :source]}
                                          :params    '[value]
                                          :fn        'collet.runtime-test/gated-plus-two}]}]})]
    (try
      (reset! task-gate (promise))
      (let [run (collet/start ctx pipeline {})]
        (is (wait-until #(= :running (task-status ctx run :dependent))))
        (let [source     (some #(when (= :source (:task/name %)) %)
                               (store/get-task-runs (:store ctx) (:run/id run)))
              output-ref (:task/output source)
              artifact   (store/get-artifact (:store ctx) (second output-ref))
              directory  (artifact-directory ctx artifact)]
          (is (Files/exists directory (make-array java.nio.file.LinkOption 0)))
          (with-redefs [artifact/delete-artifacts!
                        (fn [_ _]
                          (throw (ex-info "delete failed" {})))]
            (deliver @task-gate true)
            (is (= :done (:run/status @run))))
          (is (nil? (store/get-task-output (:store ctx) (:task/id source))))
          (is (nil? (store/get-artifact (:store ctx) (second output-ref))))
          (is (Files/exists directory (make-array java.nio.file.LinkOption 0)))))
      (finally
       (deliver @task-gate true)
       (collet/close ctx)
       (delete-store! path)))))


(deftest failed-run-releases-ordinary-handoff-artifacts
  (let [path     (temporary-store-path)
        ctx      (collet/context {:data-dir path})
        pipeline (collet/compile-pipeline
                  {:name  :failed-handoff-cleanup
                   :tasks [{:name    :source
                            :actions [{:name :source
                                       :type :custom
                                       :fn   'collet.runtime-test/one}]}
                           {:name    :explode
                            :inputs  [:source]
                            :actions [{:name :explode
                                       :type :custom
                                       :fn   'collet.runtime-test/gated-fail!}]}]})]
    (try
      (reset! task-gate (promise))
      (let [run (collet/start ctx pipeline {})]
        (is (wait-until #(= :running (task-status ctx run :explode))))
        (let [source     (some #(when (= :source (:task/name %)) %)
                               (store/get-task-runs (:store ctx) (:run/id run)))
              output-ref (:task/output source)
              artifact   (store/get-artifact (:store ctx) (second output-ref))
              directory  (artifact-directory ctx artifact)]
          (is (Files/exists directory (make-array java.nio.file.LinkOption 0)))
          (deliver @task-gate true)
          (is (= :failed (:run/status @run)))
          (is (nil? (store/get-task-output (:store ctx) (:task/id source))))
          (is (nil? (store/get-artifact (:store ctx) (second output-ref))))
          (is (not (Files/exists directory
                                 (make-array java.nio.file.LinkOption 0))))))
      (finally
       (deliver @task-gate true)
       (collet/close ctx)
       (delete-store! path)))))


(deftest stopped-run-releases-ordinary-handoff-artifacts
  (let [path     (temporary-store-path)
        ctx      (collet/context {:data-dir path})
        pipeline (collet/compile-pipeline
                  {:name  :stopped-handoff-cleanup
                   :tasks [{:name    :source
                            :actions [{:name :source
                                       :type :custom
                                       :fn   'collet.runtime-test/one}]}
                           {:name    :dependent
                            :inputs  [:source]
                            :actions [{:name :dependent
                                       :type :custom
                                       :fn   'collet.runtime-test/gated-one}]}]})]
    (try
      (reset! task-gate (promise))
      (let [run (collet/start ctx pipeline {})]
        (is (wait-until #(= :running (task-status ctx run :dependent))))
        (let [source     (some #(when (= :source (:task/name %)) %)
                               (store/get-task-runs (:store ctx) (:run/id run)))
              output-ref (:task/output source)
              artifact   (store/get-artifact (:store ctx) (second output-ref))
              directory  (artifact-directory ctx artifact)]
          (is (Files/exists directory (make-array java.nio.file.LinkOption 0)))
          (collet/stop run)
          (is (= :stopped (:run/status @run)))
          (is (nil? (store/get-task-output (:store ctx) (:task/id source))))
          (is (nil? (store/get-artifact (:store ctx) (second output-ref))))
          (is (not (Files/exists directory
                                 (make-array java.nio.file.LinkOption 0))))))
      (finally
       (deliver @task-gate true)
       (collet/close ctx)
       (delete-store! path)))))


(deftest identical-runs-publish-distinct-run-owned-dataset-artifacts
  (let [path     (temporary-store-path)
        ctx      (collet/context {:data-dir path})
        pipeline (collet/compile-pipeline
                  {:name  :run-owned-datasets
                   :tasks [{:name       :fetch
                            :keep-state true
                            :actions    [{:name :fetch
                                          :type :custom
                                          :fn   'collet.runtime-test/counted-rows}]}]})]
    (try
      (reset! task-executions 0)
      (let [run-1       (collet/start ctx pipeline {})
            result-1    @run-1
            task-1      (first (store/get-task-runs (:store ctx) (:run/id result-1)))
            output-1    (:task/output task-1)
            artifact-1  (store/get-artifact (:store ctx) (second output-1))
            run-2       (collet/start ctx pipeline {})
            result-2    @run-2
            task-2      (first (store/get-task-runs (:store ctx) (:run/id result-2)))
            output-2    (:task/output task-2)
            artifact-2  (store/get-artifact (:store ctx) (second output-2))
            directory-1 (artifact-directory ctx artifact-1)
            directory-2 (artifact-directory ctx artifact-2)]
        (is (= 2 @task-executions))
        (is (= :computed (:task/outcome task-1)))
        (is (= :computed (:task/outcome task-2)))
        (is (not= (:run/id result-1) (:run/id result-2)))
        (is (not= output-1 output-2))
        (is (= :artifact/id (first output-1) (first output-2)))
        (is (not= (:artifact/id artifact-1) (:artifact/id artifact-2)))
        (is (not (contains? artifact-1 :artifact/uri)))
        (is (not= directory-1 directory-2))
        (is (Files/exists directory-1 (make-array java.nio.file.LinkOption 0)))
        (is (Files/exists directory-2 (make-array java.nio.file.LinkOption 0))))
      (finally
       (collet/close ctx)
       (delete-store! path)))))


(deftest explicitly-schematized-empty-records-publish-a-dataset-artifact
  (let [path     (temporary-store-path)
        ctx      (collet/context {:data-dir path})
        pipeline (collet/compile-pipeline
                  {:name  :empty-records
                   :tasks [{:name          :extract
                            :keep-state    true
                            :arrow-columns {:version 1
                                            :fields  [{:key  :id
                                                       :name "id"
                                                       :type :int64}]}
                            :actions       [{:name :extract
                                             :type :custom
                                             :fn   'collet.runtime-test/empty-rows}]}]})]
    (try
      (let [run      (collet/start ctx pipeline {})
            result   @run
            task     (first (store/get-task-runs (:store ctx) (:run/id result)))
            artifact (store/get-artifact (:store ctx)
                                         (second (:task/output task)))]
        (is (= :done (:run/status result)))
        (is (= :dataset (:artifact/kind artifact)))
        (is (= 0 (:artifact/records artifact)))
        (is (empty? (mapcat ds/rows (:extract run)))))
      (finally
       (collet/close ctx)
       (delete-store! path)))))


(deftest retained-records-cannot-skip-arrow-conversion
  (let [path     (temporary-store-path)
        ctx      (collet/context {:data-dir path})
        pipeline (collet/compile-pipeline
                  {:name      :reject-skipped-records
                   :use-arrow false
                   :tasks     [{:name       :records
                                :keep-state true
                                :actions    [{:name :records
                                              :type :custom
                                              :fn   'collet.runtime-test/skipped-records}]}]})]
    (try
      (let [result @(collet/start ctx pipeline {})
            task   (first (store/get-task-runs (:store ctx) (:run/id result)))]
        (is (= :failed (:run/status result)))
        (is (= :failed (:task/status task)))
        (is (nil? (:task/output task))))
      (finally
       (collet/close ctx)
       (delete-store! path)))))


(deftest prepared-task-plans-are-not-expanded-again-at-runtime
  (let [path     (temporary-store-path)
        ctx      (collet/context
                  {:store (datalevin/store {:dir (str path)})})
        pipeline (collet/compile-pipeline
                  {:name  :single-expansion
                   :tasks [{:name    :only-task
                            :actions [{:name :expander
                                       :type :count-expansion}]}]})]
    (try
      (reset! expansion-executions 0)
      (is (= [:expander :expansion-marker]
             (mapv :name (get-in pipeline [:tasks 0 :actions]))))
      (is (= :done (:run/status @(collet/start ctx pipeline {}))))
      (is (= 1 @expansion-executions))
      (finally
       (collet/close ctx)
       (delete-store! path)))))


(deftest keep-state-output-is-released-on-context-close
  (let [path     (temporary-store-path)
        ctx      (collet/context {:data-dir path})
        pipeline (collet/compile-pipeline
                  {:name      :persistence-boundary
                   :use-arrow true
                   :tasks     [{:name       :extract
                                :keep-state true
                                :actions    [{:name :extract
                                              :type :custom
                                              :fn   'collet.runtime-test/rows}]}]})]
    (try
      (let [run       (collet/start ctx pipeline {:secret "not-persisted"})
            result    @run
            run-id    (:run/id result)
            task      (first (store/get-task-runs (:store ctx) run-id))
            output    (:task/output task)
            artifact  (store/get-artifact (:store ctx) (second output))
            directory (artifact-directory ctx artifact)]
        (is (= :artifact/id (first output)))
        (is (not (collet/arrow-task-result? (:extract run))))
        (is (= [{:id 1} {:id 2}]
               (mapv #(into {} %) (mapcat ds/rows (:extract run)))))
        (is (= [{:id 1} {:id 2}]
               (mapv #(into {} %)
                     (mapcat ds/rows (collet/read-output ctx output)))))
        (is (Files/exists directory (make-array java.nio.file.LinkOption 0)))
        (collet/close ctx)
        (is (not (Files/exists directory
                               (make-array java.nio.file.LinkOption 0))))
        (let [reopened (collet/context {:data-dir path})]
          (try
            (let [db (d/db (:conn (:store reopened)))
                  task-ids (d/q '[:find [?task-id ...]
                                  :in $ ?run-id
                                  :where
                                  [?run :run/id ?run-id]
                                  [?task :task/run ?run]
                                  [?task :task/id ?task-id]]
                                db
                                run-id)
                  raw-run (d/pull db '[*] [:run/id run-id])
                  raw-tasks (mapv #(d/pull db '[*] [:task/id %]) task-ids)
                  artifact-ids
                  (d/q '[:find [?artifact-id ...]
                         :in $ ?run-id
                         :where
                         [?run :run/id ?run-id]
                         [?artifact :artifact/run ?run]
                         [?artifact :artifact/id ?artifact-id]]
                       db
                       run-id)
                  read-error
                  (try
                    (collet/read-output reopened output)
                    nil
                    (catch clojure.lang.ExceptionInfo error
                      error))]
              (is (= :done (:run/status (store/get-run (:store reopened) run-id))))
              (is (nil? (store/get-task-output (:store reopened) (:task/id task))))
              (is (nil? (store/get-artifact (:store reopened) (second output))))
              (is (= :collet.error/unknown-output
                     (:collet.error/type (ex-data read-error))))
              (is (empty? artifact-ids))
              (is (every? #{:db/id
                            :run/id
                            :run/pipeline
                            :run/status
                            :run/created-at
                            :run/started-at
                            :run/finished-at}
                          (keys raw-run)))
              (is (every?
                   (fn [task]
                     (every? #{:db/id
                               :task/id
                               :task/run
                               :task/name
                               :task/key
                               :task/status
                               :task/outcome
                               :task/inputs
                               :task/created-at
                               :task/started-at
                               :task/finished-at}
                             (keys task)))
                   raw-tasks))
              (is (not-any? #(contains? % :task/output) raw-tasks))
              (is (not-any? #(= "not-persisted" %)
                            (tree-seq coll? seq (cons raw-run raw-tasks)))))
            (finally
             (collet/close reopened)))))
      (finally
       (collet/close ctx)
       (delete-store! path)))))


(deftest task-lineage-survives-after-context-artifacts-are-released
  (let [path     (temporary-store-path)
        ctx      (collet/context {:data-dir path})
        pipeline (basic-pipeline)]
    (try
      (let [run       (collet/start ctx pipeline {})
            result    @run
            run-id    (:run/id result)
            by-name   (into {}
                            (map (juxt :task/name identity))
                            (store/get-task-runs (:store ctx) run-id))
            source-id (get-in by-name [:fetch :task/id])
            target-id (get-in by-name [:load :task/id])
            source    (store/get-task-output (:store ctx)
                                             source-id)
            target    (store/get-task-output (:store ctx)
                                             target-id)]
        (is (= 3 (:load run)))
        (is (= :scalar (:output/kind source)))
        (is (= [source-id]
               (mapv :task/id
                     (store/get-lineage (:store ctx) target-id :upstream))))
        (is (= [target-id]
               (mapv :task/id
                     (store/get-lineage (:store ctx) source-id :downstream))))
        (collet/close ctx)
        (let [reopened (collet/context {:data-dir path})]
          (try
            (is (nil? (store/get-task-output (:store reopened) source-id)))
            (is (nil? (store/get-task-output (:store reopened) target-id)))
            (is (= [source-id]
                   (mapv :task/id
                         (store/get-lineage (:store reopened)
                                            target-id
                                            :upstream))))
            (is (= [target-id]
                   (mapv :task/id
                         (store/get-lineage (:store reopened)
                                            source-id
                                            :downstream))))
            (is (= :collet.error/unknown-output
                   (:collet.error/type
                    (ex-data
                     (try
                       (collet/read-output reopened source)
                       nil
                       (catch clojure.lang.ExceptionInfo error
                         error))))))
            (finally
             (collet/close reopened)))))
      (finally
       (collet/close ctx)
       (delete-store! path)))))


(deftest saved-pipelines-run-in-a-fresh-context
  (let [path (temporary-store-path)
        ctx1 (collet/context
              {:store (datalevin/store {:dir (str path)})})]
    (try
      @(collet/start ctx1 (basic-pipeline) {})
      (collet/close ctx1)
      (let [ctx2     (collet/context
                      {:store (datalevin/store {:dir (str path)})})
            pipeline (collet/load-pipeline ctx2 :orders)
            run      (collet/start ctx2 pipeline {})]
        (try
          (is (= 1 (:version pipeline)))
          (is (= :done (:run/status @run)))
          (is (= 3 (:load run)))
          (finally
           (collet/close ctx2))))
      (finally
       (collet/close ctx1)
       (delete-store! path)))))


(deftest persistence-failure-stops-scheduling-and-fails-the-run
  (let [path              (temporary-store-path)
        persistence-error (ex-info "task write failed" {})
        db                (datalevin/store {:dir (str path)})
        artifact-dir      (.resolve path "artifacts")
        ctx               (collet/context
                           {:store
                            (faulting-store
                             db
                             (fn [operation changes]
                               (when (and (= :complete-task operation)
                                          (= :completed (:task/status changes)))
                                 persistence-error)))
                            :artifact-dir artifact-dir})
        pipeline          (collet/compile-pipeline
                           {:name :durable-write-failure
                            :max-parallelism 1
                            :tasks
                            [{:name       :first
                              :keep-state true
                              :actions    [{:name :first
                                            :type :custom
                                            :fn   'collet.runtime-test/record-execution}]}
                             {:name :second
                              :inputs [:first]
                              :actions
                              [{:name :second
                                :type :custom
                                :fn   'collet.runtime-test/record-execution}]}]})]
    (try
      (reset! task-executions 0)
      (let [result @(collet/start ctx pipeline {})
            tasks  (store/get-task-runs db (:run/id result))]
        (is (= :failed (:run/status result)))
        (is (= "Pipeline persistence failed."
               (get-in result [:run/error :message])))
        (is (= result (store/get-run db (:run/id result))))
        (is (= 1 @task-executions))
        (is (= #{:running :waiting} (set (map :task/status tasks))))
        (is (nil? (store/get-task-output
                   db
                   (:task/id (some #(when (= :first (:task/name %)) %) tasks)))))
        (with-open [paths (Files/walk artifact-dir
                                      (make-array FileVisitOption 0))]
          (let [entries (vec (iterator-seq (.iterator paths)))]
            (is (= artifact-dir (:artifact-dir ctx)))
            (is (some #(= "manifest.edn" (str (.getFileName ^Path %)))
                      entries)))))
      (finally
       (collet/close ctx)
       (delete-store! path)))))


(deftest a-failed-task-cannot-race-the-run-to-done
  (let [path (temporary-store-path)
        release-run-failure (promise)
        callback-run-status (promise)
        db (datalevin/store {:dir (str path)})
        faulting-db
        (faulting-store
         db
         (fn [operation changes]
           (when (and (= :update-run operation)
                      (= :failed (:run/status changes)))
             @release-run-failure
             nil)))
        ctx (collet/context
             {:store faulting-db
              :on-task-error
              (fn [task]
                (deliver callback-run-status
                         (:run/status
                          (store/get-run faulting-db (:task/run task)))))})
        pipeline (collet/compile-pipeline
                  {:name  :failure-race
                   :tasks [{:name    :explode
                            :actions [{:name :explode
                                       :type :custom
                                       :fn   'collet.runtime-test/fail!}]}]})]
    (try
      (let [run (collet/start ctx pipeline {})]
        (is (wait-until #(= :failed (task-status ctx run :explode))))
        (Thread/sleep 150)
        (is (= :running (collet/pipe-status run)))
        (deliver release-run-failure true)
        (is (= :failed (:run/status @run)))
        (is (= :failed (deref callback-run-status 1000 ::timeout)))
        (is (= :failed
               (:run/status (store/get-run db (:run/id run))))))
      (finally
       (deliver release-run-failure true)
       (collet/close ctx)
       (delete-store! path)))))


(deftest an-unpersistable-run-failure-is-thrown-on-deref
  (let [path              (temporary-store-path)
        persistence-error (ex-info "task write failed" {})
        fallback-error    (ex-info "run write failed" {})
        db                (datalevin/store {:dir (str path)})
        ctx               (collet/context
                           {:store
                            (faulting-store
                             db
                             (fn [operation changes]
                               (cond
                                 (and (= :complete-task operation)
                                      (= :completed (:task/status changes)))
                                 persistence-error

                                 (and (= :update-run operation)
                                      (= :failed (:run/status changes)))
                                 fallback-error)))})
        pipeline          (collet/compile-pipeline
                           {:name  :unpersistable-run-failure
                            :tasks [{:name    :only-task
                                     :actions [{:name :only-task
                                                :type :custom
                                                :fn   'collet.runtime-test/one}]}]})]
    (try
      (let [run    (collet/start ctx pipeline {})
            thrown (try
                     @run
                     nil
                     (catch Throwable error
                       error))]
        (is (identical? persistence-error thrown))
        (is (= :running (:run/status (store/get-run db (:run/id run))))))
      (finally
       (collet/close ctx)
       (delete-store! path)))))


(deftest runs-and-pipeline-versions-are-independent
  (let [path (temporary-store-path)
        ctx  (collet/context
              {:store (datalevin/store {:dir (str path)})})]
    (try
      (let [run1    (collet/start ctx (basic-pipeline 1) {})
            run2    (collet/start ctx (basic-pipeline 1) {})
            result1 @run1
            result2 @run2
            v2      (assoc (basic-pipeline 2)
                      :tasks
                      [{:name       :fetch
                        :keep-state true
                        :actions    [{:name :fetch
                                      :type :custom
                                      :fn   'collet.runtime-test/two}]}])
            run-v2  (collet/start ctx v2 {})]
        (is (not= (:run/id result1) (:run/id result2)))
        (is (= 1 (:fetch run1)))
        (is (= 1 (:fetch run2)))
        (is (= :done (:run/status @run-v2)))
        (is (= 2 (:fetch run-v2)))
        (is (= 1 (:fetch run1))))
      (finally
       (collet/close ctx)
       (delete-store! path)))))


(deftest a-running-revision-is-isolated-from-a-newer-revision
  (let [path (temporary-store-path)
        ctx  (collet/context
              {:store (datalevin/store {:dir (str path)})})
        v1   (collet/compile-pipeline
              {:name  :revision-isolation
               :tasks [{:name       :value
                        :keep-state true
                        :actions    [{:name :value
                                      :type :custom
                                      :fn   'collet.runtime-test/gated-one}]}]})
        v2   (collet/compile-pipeline
              {:name    :revision-isolation
               :version 2
               :tasks   [{:name       :value
                          :keep-state true
                          :actions    [{:name :value
                                        :type :custom
                                        :fn   'collet.runtime-test/two}]}]})]
    (try
      (reset! task-gate (promise))
      (let [run-v1 (collet/start ctx v1 {})]
        (is (wait-until #(= :running (task-status ctx run-v1 :value))))
        (let [run-v2 (collet/start ctx v2 {})]
          (is (= :done (:run/status @run-v2)))
          (is (= 2 (:value run-v2))))
        (deliver @task-gate true)
        (is (= :done (:run/status @run-v1)))
        (is (= 1 (:value run-v1))))
      (finally
       (deliver @task-gate true)
       (collet/close ctx)
       (delete-store! path)))))


(deftest failures-and-downstream-skips-are-durable
  (let [path (temporary-store-path)
        skipped (atom [])
        ctx (collet/context
             {:store           (datalevin/store {:dir (str path)})
              :on-task-skipped #(swap! skipped conj (:task/name %))})
        pipeline
        (collet/compile-pipeline
         {:name  :failure
          :tasks [{:name          :explode
                   :skip-on-error true
                   :actions       [{:name :explode
                                    :type :custom
                                    :fn   'collet.runtime-test/fail!}]}
                  {:name    :downstream
                   :inputs  [:explode]
                   :actions [{:name :downstream
                              :type :custom
                              :fn   'collet.runtime-test/one}]}]})]
    (try
      (let [run    (collet/start ctx pipeline {})
            result @run
            tasks  (into {}
                         (map (juxt :task/name identity))
                         (store/get-task-runs (:store ctx) (:run/id result)))]
        (is (= :done (:run/status result)))
        (is (= :failed (get-in tasks [:explode :task/status])))
        (is (= "boom" (get-in tasks [:explode :task/error :message])))
        (is (= :skipped (get-in tasks [:downstream :task/status])))
        (is (= [:downstream] @skipped)))
      (finally
       (collet/close ctx)
       (delete-store! path)))))


(deftest a-non-skippable-failure-persists-the-run-error-before-callback
  (let [path      (temporary-store-path)
        callbacks (atom [])
        ctx       (collet/context
                   {:store         (datalevin/store {:dir (str path)})
                    :on-task-error #(swap! callbacks conj %)})
        pipeline  (collet/compile-pipeline
                   {:name  :run-failure
                    :tasks [{:name    :explode
                             :actions [{:name :explode
                                        :type :custom
                                        :fn   'collet.runtime-test/fail!}]}]})]
    (try
      (let [run    (collet/start ctx pipeline {})
            result @run
            task   (first @callbacks)]
        (is (= :failed (:run/status result)))
        (is (= "boom" (get-in result [:run/error :message])))
        (is (= :failed (:task/status task)))
        (is (= "boom" (get-in task [:task/error :message])))
        (is (= result (store/get-run (:store ctx) (:run/id result)))))
      (finally
       (collet/close ctx)
       (delete-store! path)))))


(deftest pause-resume-and-stop-control-one-run
  (let [path     (temporary-store-path)
        ctx      (collet/context
                  {:store (datalevin/store {:dir (str path)})})
        pipeline (collet/compile-pipeline
                  {:name  :controlled
                   :tasks [{:name       :first
                            :keep-state true
                            :actions    [{:name :first
                                          :type :custom
                                          :fn   'collet.runtime-test/gated-one}]}
                           {:name    :second
                            :inputs  [:first]
                            :actions [{:name :second
                                       :type :custom
                                       :fn   'collet.runtime-test/one}]}]})]
    (try
      (reset! task-gate (promise))
      (let [run (collet/start ctx pipeline {})]
        (is (wait-until #(= :running (task-status ctx run :first))))
        (collet/pause run)
        (deliver @task-gate true)
        (is (wait-until #(= :completed (task-status ctx run :first))))
        (is (= :paused (collet/pipe-status run)))
        (is (= :waiting (task-status ctx run :second)))
        (collet/resume run)
        (is (= :done (:run/status @run))))

      (reset! task-gate (promise))
      (let [run (collet/start ctx (assoc pipeline :version 2) {})]
        (is (wait-until #(= :running (task-status ctx run :first))))
        (collet/stop run)
        (is (= :stopped (:run/status @run)))
        (is (= #{:interrupted :skipped}
               (set (map :task/status
                         (store/get-task-runs
                          (:store ctx)
                          (:run/id run)))))))
      (finally
       (deliver @task-gate true)
       (collet/close ctx)
       (delete-store! path)))))


(deftest max-parallelism-is-enforced-from-durable-status
  (let [path     (temporary-store-path)
        ctx      (collet/context
                  {:store (datalevin/store {:dir (str path)})})
        pipeline (collet/compile-pipeline
                  {:name            :parallel
                   :max-parallelism 2
                   :tasks           (mapv
                                     (fn [index]
                                       {:name (keyword (str "task-" index))
                                        :actions
                                        [{:name :count
                                          :type :custom
                                          :fn   'collet.runtime-test/counted-task}]})
                                     (range 6))})]
    (try
      (reset! active-tasks 0)
      (reset! max-active-tasks 0)
      (is (= :done (:run/status @(collet/start ctx pipeline {}))))
      (is (= 2 @max-active-tasks))
      (finally
       (collet/close ctx)
       (delete-store! path)))))
