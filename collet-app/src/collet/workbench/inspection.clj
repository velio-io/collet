(ns collet.workbench.inspection
  "Focused reads of durable summaries and already observed samples."
  (:require [collet.store :as store]
            [collet.workbench.data :as data]
            [collet.workbench.diagnostics :as diagnostics]
            [collet.workbench.wire :as wire])
  (:import [java.util UUID]))


(defn run-state [state]
  (if (= state :done) "completed" (data/label state)))


(defn- uuid [id]
  (try (UUID/fromString (str id))
       (catch Exception _ (throw (ex-info "Invalid identifier." {:status 400 :code "invalid-id"})))))


(defn selected-sample [preview {:keys [samplePathEdn] :as options}]
  (let [sample (wire/sample preview options)]
    (if-not samplePathEdn
      sample
      (let [path (data/read-edn samplePathEdn)]
        (when-not (and (vector? path) (every? #(or (keyword? %) (string? %) (nat-int? %)) path))
          (throw (ex-info "A sample path contains only literal keys or indexes; it never evaluates selectors."
                          {:status 400 :code "invalid-sample-path"})))
        (assoc sample
          :value (reduce (fn [v k] (get v (if (and (map? v) (keyword? k)) (data/label k) k))) (:value sample) path)
          :samplePathEdn samplePathEdn)))))


(defn action-observation [app run-id execution-id task-name action-name]
  (last (filter #(and (= (keyword task-name) (:task %))
                      (= (keyword action-name) (:action %))
                      (if run-id
                        (= (str run-id) (str (:run-id %)))
                        (= (str execution-id) (str (:execution-id %)))))
                @(:inspect-values app))))


(defn read-run [app id {:keys [view task-name action-name] :as options}]
  (let [id      (uuid id)
        db      (:store (:ctx app))
        run     (or (store/get-run db id) (throw (ex-info "Run not found." {:status 404 :code "run-not-found"})))
        tasks   (store/get-task-runs db id)
        task    (some #(when (= (keyword task-name) (:task/name %)) %) tasks)
        failed  (some #(when (= :failed (:task/status %)) %) tasks)
        started (:run/started-at run)
        summary {:id         (str id)
                 :status     (run-state (:run/status run))
                 :pipeline   (data/label (get-in run [:run/pipeline :pipeline/name]))
                 :version    (get-in run [:run/pipeline :pipeline/version])
                 :url        (str @(:base-url app) "/#run=" id)
                 :owned      (contains? @(:handles app) id)
                 :startedAt  started
                 :finishedAt (:run/finished-at run)
                 :elapsedMs  (when started (- (or (:run/finished-at run) (System/currentTimeMillis)) started))
                 :sequence   (diagnostics/sequence-number app [id])
                 :taskCounts (update-keys (frequencies (map :task/status tasks)) data/label)}
        summary (cond-> summary
                  (or failed (:run/error run))
                  (assoc :error
                    (wire/run-error (or (:task/error failed) (:run/error run)) "run-failed")
                    :failedTask
                    (some-> (:task/name failed)
                            data/label)))]
    (case (or view "summary")
      "summary" summary
      "events" (merge summary (diagnostics/events app [id] options))
      "metrics" (let [selected (if task-name (filterv #(= (keyword task-name) (:task/name %)) tasks) tasks)]
                  (assoc summary
                    :metrics
                    (assoc (diagnostics/metrics app [id] options)
                      :durable true
                      :retryCount (reduce + 0 (keep :task/retry-count selected))
                      :tasks (mapv (fn [t]
                                     {:task       (data/label (:task/name t))
                                      :status     (data/label (:task/status t))
                                      :startedAt  (:task/started-at t)
                                      :finishedAt (:task/finished-at t)
                                      :durationMs (when (:task/started-at t)
                                                    (- (or (:task/finished-at t) (System/currentTimeMillis)) (:task/started-at t)))
                                      :retryCount (:task/retry-count t)
                                      :records    (:task/output-records t)
                                      :bytes      (:task/output-bytes t)})
                                   selected))))
      "result" (let [observation (when action-name (action-observation app id nil task-name action-name))
                     preview     (if action-name (:preview observation) (get @(:run-samples app) [id (keyword task-name)]))]
                 (when (and task-name (nil? task)) (throw (ex-info "Task not found in this run." {:status 404 :code "task-not-found"})))
                 (assoc summary
                   :task task-name
                   :action action-name
                   :available (boolean preview)
                   :sample (selected-sample preview options)
                   :representation (:representation preview)
                   :tasks (when-not task-name (mapv #(select-keys % [:task/name :task/status]) tasks))
                   :reason (when-not preview "Select a task/action. Only samples observed in this app instance are available; inspection does not reopen or consume live outputs."))))))
