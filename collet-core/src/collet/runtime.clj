(ns collet.runtime
  (:require
   [collet.arrow :as arrow]
   [collet.artifact :as artifact]
   [collet.store :as store]
   [collet.store.datalevin :as datalevin]
   [collet.stream :as stream]
   [collet.utils :as utils]
   [com.brunobonacci.mulog :as ml]
   [tech.v3.dataset :as ds]
   [weavejester.dependency :as dep])
  (:import
   [clojure.lang IDeref ILookup]
   [java.io File]
   [java.util.concurrent ExecutorService Executors Future FutureTask TimeUnit]))


(deftype ArrowTaskResult
  [task-name columns file])


(defn arrow-task-result?
  [x]
  (instance? ArrowTaskResult x))


(defn arrow->dataset
  [^ArrowTaskResult arrow-task-result]
  (arrow/read-dataset
   (.-file arrow-task-result)
   (.-columns arrow-task-result)))


(defn- batch-candidate?
  [batch]
  (or (ds/dataset? batch)
      (sequential? batch)))


(defn- nonempty-batch?
  [batch]
  (and (batch-candidate? batch)
       (pos? (arrow/get-batch-size batch))))


(defn- record-batch?
  [batch]
  (or (ds/dataset? batch)
      (and (sequential? batch)
           (let [row (first batch)]
             (or (nil? row) (map? row))))))


(defn- result-layout
  [data]
  (let [sampled-item (first (drop-while nil? (take 200 data)))
        batches?     (record-batch? sampled-item)]
    ;; An empty batch establishes batched shape but cannot supply schema
    ;; metadata, so locate the first non-empty batch separately.
    {:batches?    batches?
     :first-batch (when batches?
                    (first (filter nonempty-batch? data)))}))


(defn- skip-arrow-conversion?
  [data]
  (true? (-> data meta :collet.arrow/skip-conversion?)))


(defn- result-schema
  [data batches? first-batch]
  (let [explicit-schema (or (-> data meta :arrow-columns)
                            (-> first-batch meta :arrow-columns))]
    (if explicit-schema
      (arrow/normalize-schema explicit-schema)
      (let [records (if batches?
                      (mapcat #(if (ds/dataset? %) (ds/rows %) %) data)
                      data)
            inference-candidate?
            (if-some [record (first (remove nil? (take 200 records)))]
              (map? record)
              true)]
        (when inference-candidate?
          (arrow/infer-schema! records))))))


(defn- logical-rows [batch schema]
  (if (ds/dataset? batch)
    (let [columns (or schema (:arrow-columns (meta batch)))]
      (cond->> (ds/rows batch) columns (map #(arrow/prep-record % columns))))
    batch))


(defn- write-arrow-result!
  [task-name data schema batches?]
  (let [file ^File (File/createTempFile (str "collet-" (name task-name)) ".arrow")]
    (.deleteOnExit file)
    (with-open [writer (arrow/make-writer file schema)]
      (if batches?
        (doseq [batch data
                :when (nonempty-batch? batch)]
          (doseq [rows (stream/chunks (clojure.lang.RT/iter (logical-rows batch schema)) stream/row-limit)]
            (arrow/write writer rows)))
        (doseq [batch (stream/chunks (clojure.lang.RT/iter data) stream/row-limit)
                :when (nonempty-batch? batch)]
          (arrow/write writer batch))))
    (ArrowTaskResult. task-name schema file)))


(defn handle-task-result
  [task-name data {:keys [use-arrow keep-result]}]
  (if (not (sequential? data))
    (when keep-result
      data)
    ;; process as a sequence
    (cond
      (and keep-result use-arrow)
      (if (skip-arrow-conversion? data)
        (doall data)
        (let [{:keys [batches? first-batch]} (result-layout data)
              schema (result-schema data batches? first-batch)]
          (if schema
            (write-arrow-result! task-name data schema batches?)
            (doall data))))

      keep-result
      (doall data)

      :otherwise
      (do (doall data)
          nil))))


(defn dependencies-met?
  "Returns true when all of a durable task run's direct inputs completed."
  [task tasks]
  (every?
   (fn [input]
     (let [input-task (get tasks input)]
       (when-not input-task
         (throw (ex-info (str "Missing dependency: " input) {:input input})))
       (= (:task/status input-task) :completed)))
   (:task/inputs task)))


(defn has-dependants?
  "Returns true if the task has any dependants."
  [task-name tasks-graph]
  (seq (dep/immediate-dependents tasks-graph task-name)))


(defn all-completed?
  [tasks]
  (not-any? #(contains? #{:waiting :running} (:task/status %)) tasks))


(def ^:private terminal-run-statuses
  #{:done :failed :stopped})


(def ^:private terminal-task-statuses
  #{:completed :failed :skipped :interrupted})


(defrecord Context
  [store
   artifact-dir
   executor
   runs
   closed?
   dev?
   on-task-start
   on-task-complete
   on-task-error
   on-task-skipped])


(declare deref-run read-output)


(deftype Run [ctx id]
  IDeref
  (deref [this]
    (deref-run this))

  ILookup
  (valAt [this key]
    (.valAt this key nil))

  (valAt [_ key not-found]
    (cond
      (= key :run/id)
      id

      :else
      (if-let [runtime (get @(:runs ctx) id)]
        (if (contains? @(:results runtime) key)
          (let [output (get @(:results runtime) key)]
            (cond
              (and (map? output) (contains? output :output/value))
              (:output/value output)

              (and (map? output) (:output/artifact output))
              (artifact/read-artifact (:artifact-dir ctx)
                                      (:output/artifact output))

              (and (map? output) (:output/ref output))
              (read-output ctx output)

              :else
              not-found))
          not-found)
        not-found))))


(defn- run-context
  [^Run run]
  (.-ctx run))


(defn- run-id
  [^Run run]
  (.-id run))


(defn- run-runtime
  [^Run run]
  (get @(:runs (run-context run)) (run-id run)))


(defn- completion-outcome
  [run]
  (when-let [runtime (run-runtime run)]
    (when (realized? (:completion runtime))
      @(:completion runtime))))


(defn deref-run
  [run]
  (let [runtime (or (run-runtime run)
                    (throw
                     (ex-info "Unknown pipeline run."
                              {:run-id (run-id run)})))
        outcome @(:completion runtime)]
    (if-let [error (:error outcome)]
      (throw error)
      (:run outcome))))


(defn context
  "Creates a process-lifetime runtime context."
  ([]
   (context {}))
  ([{:keys [store data-dir artifact-dir dev?
            on-task-start on-task-complete on-task-error on-task-skipped]
     :or   {dev? false}}]
   (let [explicit-store? (some? store)
         layout          (when data-dir
                           (artifact/data-layout data-dir))
         artifact-dir    (artifact/ensure-artifact-dir!
                          (or artifact-dir
                              (:artifact-dir layout)
                              (when explicit-store?
                                "./.collet/artifacts")
                              (:artifact-dir (artifact/data-layout "./.collet"))))
         store           (or store
                             (let [layout (or layout (artifact/data-layout "./.collet"))]
                               (datalevin/store {:dir (str (:db-dir layout))})))]
     (map->Context
      {:store            store
       :artifact-dir     artifact-dir
       :executor         (Executors/newCachedThreadPool)
       :runs             (atom {})
       :closed?          (atom false)
       :dev?             dev?
       :on-task-start    on-task-start
       :on-task-complete on-task-complete
       :on-task-error    on-task-error
       :on-task-skipped  on-task-skipped}))))


(defn- ensure-open-context!
  [ctx]
  (when @(:closed? ctx)
    (throw
     (ex-info "Context is closed."
              {:collet.error/type :collet.error/context-closed})))
  ctx)


(defn load-pipeline
  "Loads the latest or an exact persisted pipeline revision."
  ([ctx name]
   (ensure-open-context! ctx)
   (store/load-pipeline (:store ctx) name))
  ([ctx name version]
   (ensure-open-context! ctx)
   (store/load-pipeline (:store ctx) name version)))


(defn read-output
  "Resolve an active task output reference to its decoded value or dataset view.

  Task inputs and run lookup perform this resolution automatically. This function
  is the explicit API for output references obtained from the Store while their
  owning Context remains open."
  [ctx output]
  (ensure-open-context! ctx)
  (let [output-ref (or (:output/ref output) output)]
    (when output-ref
      (let [[attribute id] output-ref
            artifact       (if (= :artifact/id attribute)
                             (store/get-artifact (:store ctx) id)
                             (throw (ex-info "Unsupported task output reference."
                                             {:collet.error/type :collet.error/unknown-output
                                              :output-ref        output-ref})))]
        (when-not artifact
          (throw (ex-info "Output artifact metadata is missing."
                          {:collet.error/type :collet.error/unknown-output
                           :output-ref        output-ref})))
        (when-not (contains? @(:runs ctx) (:artifact/run-id artifact))
          (throw (ex-info "Output artifact is not active in this Context."
                          {:collet.error/type :collet.error/unknown-output
                           :output-ref        output-ref})))
        (artifact/read-artifact (:artifact-dir ctx) artifact)))))


(defn- root-cause
  [error]
  (->> (iterate ex-cause error)
       (take-while identity)
       last))


(defn- sanitized-error
  [error task-name]
  (let [root (root-cause error)
        data (ex-data error)]
    (cond-> {:message (or (ex-message root) (str root))
             :task    (or (:task data) task-name)
             :type    (some-> root
                              class
                              .getName)}
      (:action data) (assoc :action (:action data))
      (:code data) (assoc :code (:code data))
      (:collet.error/type data) (assoc :code (:collet.error/type data))
      (= :collet.error/arrow-invalid-value (:collet.error/type data))
      (merge (select-keys data [:path :expected :actual-class :reason :hint])))))


(defn- safe-callback
  [callback task]
  (when (fn? callback)
    (try
      (callback task)
      (catch Throwable error
        (ml/log :collet/task-callback-failed
                :task (:task/name task)
                :message (ex-message error))))))


(def ^:private completed-runtime-keys
  [:completion :results])


(defn- keep-state-task-names
  [runtime]
  (into #{}
        (keep (fn [[task-name task]]
                (when (:keep-state task)
                  task-name)))
        (:tasks runtime)))


(defn- delete-released-artifacts!
  [ctx run-id artifacts]
  (when (seq artifacts)
    (try
      (artifact/delete-artifacts! (:artifact-dir ctx) artifacts)
      (catch Throwable error
        (ml/log :collet/artifact-cleanup-failed
                :run-id run-id
                :message (ex-message error))))))


(defn- log-run-state! [ctx run]
  (ml/with-context (:trace (get @(:runs ctx) (:run/id run)))
                   (ml/log :collet/run-state :run-id (:run/id run) :status (:run/status run))))


(defn- deliver-run! [ctx run-id run]
  (when-let [runtime (get @(:runs ctx) run-id)]
    (log-run-state! ctx run)
    (swap! (:runs ctx) update run-id #(select-keys % completed-runtime-keys))
    (deliver (:completion runtime) {:run run})))


(defn- finalize-terminal-run!
  [ctx run-id run-changes]
  (let [runtime             (get @(:runs ctx) run-id)
        retained-task-names (keep-state-task-names runtime)
        retained-task-ids   (into #{}
                                  (keep (fn [task]
                                          (when (contains? retained-task-names
                                                           (:task/name task))
                                            (:task/id task))))
                                  (store/get-task-runs (:store ctx) run-id))
        {:keys [run released-artifacts]}
        (store/finalize-run! (:store ctx)
                             run-id
                             run-changes
                             retained-task-ids)]
    (swap! (:results runtime) select-keys retained-task-names)
    (delete-released-artifacts! ctx run-id released-artifacts)
    (deliver-run! ctx run-id run)))


(defn- release-context-artifacts!
  [ctx run-id runtime]
  (when (or (nil? (:task-futures runtime))
            (empty? @(:task-futures runtime)))
    (let [{:keys [released-artifacts]}
          (store/finalize-run! (:store ctx) run-id {} #{})]
      (when-let [results (:results runtime)]
        (reset! results {}))
      (delete-released-artifacts! ctx run-id released-artifacts))))


(defn- infrastructure-failure!
  [ctx run-id error]
  (when-let [runtime (get @(:runs ctx) run-id)]
    (when-some [halted-atom (:halted? runtime)]
      (reset! halted-atom true))
    (let [failure {:message "Pipeline persistence failed."
                   :type    (some-> error
                                    class
                                    .getName)
                   :cause   (sanitized-error error nil)}]
      (ml/log :collet/persistence-failed :run-id run-id :error failure)
      (try
        (let [run (store/update-run! (:store ctx)
                                     run-id
                                     {:run/status :failed
                                      :run/finished-at
                                      (System/currentTimeMillis)
                                      :run/error failure})]
          (deliver-run! ctx run-id run))
        (catch Throwable _
          (deliver (:completion runtime) {:error error}))))))


(defn- durable-tasks-by-name
  [ctx run-id]
  (into {}
        (map (juxt :task/name identity))
        (store/get-task-runs (:store ctx) run-id)))


(defn- skip-downstream-tasks!
  [ctx run-id task-name]
  (let [runtime    (get @(:runs ctx) run-id)
        dependants (dep/transitive-dependents (:graph runtime) task-name)
        tasks      (durable-tasks-by-name ctx run-id)
        now        (System/currentTimeMillis)]
    (doseq [dependent dependants
            :let      [task (get tasks dependent)]
            :when     (= :waiting (:task/status task))]
      (let [task' (store/update-task! (:store ctx)
                                      (:task/id task)
                                      {:task/status      :skipped
                                       :task/finished-at now})]
        (safe-callback (:on-task-skipped ctx) task')))))


(defn- input-output-refs
  [ctx run-id task]
  (let [tasks-by-name (durable-tasks-by-name ctx run-id)]
    (mapv
     (fn [input]
       (let [source-task (get tasks-by-name input)
             output-ref  (:task/output source-task)
             output      (when output-ref
                           {:output/ref output-ref})]
         (when-not source-task
           (throw (ex-info "Missing durable task input."
                           {:collet.error/type :collet.error/missing-task-input
                            :task              (:name task)
                            :input             input})))
         (when-not output
           (throw (ex-info "Completed task has no durable output."
                           {:collet.error/type :collet.error/missing-task-output
                            :task              (:name task)
                            :input             input})))
         output))
     (:inputs task))))


(defn- task-inputs
  [ctx task output-refs]
  (zipmap (:inputs task)
          (map #(read-output ctx %)
               output-refs)))


(defn- record-sequence?
  [data schema-override]
  (or schema-override
      (-> data meta :arrow-columns)
      (ds/dataset? data)
      (let [sampled-item (first (drop-while nil? (take 200 data)))]
        (or (ds/dataset? sampled-item)
            (map? sampled-item)))))


(defn- non-durable-output!
  [message data]
  (throw (ex-info message
                  (assoc data :collet.error/type :collet.error/non-durable-output))))


(defn- stream-output!
  "Reduce rows once. Infer from the first bounded buffer, then reuse the Arrow writer."
  [task-name schema-override consume]
  (let [file   (delay (File/createTempFile (str "collet-" (name task-name)) ".arrow"))
        schema (volatile! schema-override)
        writer (volatile! nil)
        mode   (volatile! (when schema-override :dataset))
        buffer (volatile! [])
        bytes  (volatile! 0)
        flush! (fn []
                 (when-not @writer
                   (when-not @schema (vreset! schema (arrow/infer-schema! @buffer)))
                   (vreset! writer (arrow/make-writer @file @schema)))
                 (when (seq @buffer) (arrow/write @writer @buffer))
                 (vreset! buffer [])
                 (vreset! bytes 0))
        accept (fn [_ row]
                 (when (and (nil? @mode) (some? row))
                   (vreset! mode (if (map? row) :dataset :scalar)))
                 (if (= :dataset @mode)
                   (let [width (stream/estimated-bytes row)]
                     (when (and (seq @buffer)
                                (or (> (+ @bytes width) stream/byte-target)
                                    (>= (count @buffer) (if @writer stream/row-limit 200))))
                       (flush!))
                     (vswap! bytes + width)
                     (vswap! buffer conj row))
                   (vswap! buffer conj row))
                 nil)]
    (try
      (try
        (consume accept)
        (if (= :dataset @mode)
          (do (flush!)
              {:output/kind :dataset :output/file @file :output/schema @schema})
          {:output/kind :scalar :output/value @buffer})
        (finally (when @writer (.close ^java.io.Closeable @writer))))
      (catch Throwable t (when (realized? file) (.delete ^File @file)) (throw t)))))


(defn- accept-flat [accept item path]
  (cond
    (ds/dataset? item)
    (throw (ex-info "A flattened task output contains a nested dataset batch."
                    {:collet.error/type :collet.error/arrow-invalid-value
                     :path              path
                     :expected          "row map"
                     :actual-class      "dataset batch"
                     :reason            :nested-dataset-batch
                     :hint              "Emit rows before flattening, for example with collet.stream/rows."}))
    (sequential? item)
    (reduce (fn [index child]
              (accept-flat accept child (conj path index))
              (inc index))
            0
            item)
    :else (accept nil item)))


(defn- retained-iterator-output [task result]
  (let [spec (:spec task)]
    (stream-output! (:name task)
                    (:arrow-columns spec)
                    (fn [accept]
                      (reduce (fn [index item]
                                (cond
                                  (ds/dataset? item) (reduce accept nil (logical-rows item (:arrow-columns spec)))
                                  (= :flatten (:state-format task)) (accept-flat accept item [index])
                                  :else (accept nil item))
                                (inc index))
                              0
                              result)))))


(defn- write-dataset-output! [task-name data schema-override]
  (let [batched? (or (ds/dataset? data) (utils/ds-seq? data) (record-batch? (first data)))
        batches  (if (ds/dataset? data) [data] data)
        schema   (or schema-override (:arrow-columns (meta data)))
        output   (stream-output! task-name
                                 schema
                                 (fn [accept]
                                   (if batched?
                                     (reduce (fn [_ batch]
                                               (reduce accept nil (logical-rows batch schema)))
                                             nil
                                             batches)
                                     (reduce accept nil data))))]
    (when-not (= :dataset (:output/kind output))
      (non-durable-output! "A retained record dataset needs an explicit :arrow-columns schema." {:task task-name}))
    output))


(defn- format-sequence-output
  [runtime task result]
  (let [result             (if (:iteration (meta result)) (iterator-seq (clojure.lang.RT/iter result)) result)
        sequential-result? (sequential? result)
        state-format       (:state-format task)
        formatted          (cond
                             (and sequential-result? (= state-format :latest))
                             (last result)

                             (and sequential-result? (= state-format :flatten))
                             (flatten result)

                             :else
                             result)
        keep-result        (or (:keep-state task)
                               (has-dependants? (:name task) (:graph runtime)))
        schema-override    (get-in task [:spec :arrow-columns])
        record-dataset?    (and (or (sequential? formatted)
                                    (ds/dataset? formatted))
                                (record-sequence? formatted schema-override))]
    (if keep-result
      (do
        (when (and record-dataset?
                   (skip-arrow-conversion? formatted))
          (non-durable-output!
           "A retained record dataset cannot skip Arrow conversion."
           {:task (:name task)}))
        (if (and (get-in runtime [:pipeline :use-arrow])
                 record-dataset?)
          (let [live-dataset (when (ds/dataset? formatted) formatted)
                output       (write-dataset-output! (:name task) formatted schema-override)]
            (cond-> output
              ;; Preserve the existing in-process surface for actions that
              ;; naturally return one TMD dataset. It is cached only after the
              ;; durable completion transaction below; task inputs always read
              ;; the artifact instead.
              live-dataset
              (assoc :output/live-value live-dataset)))
          {:output/kind  :scalar
           :output/value (handle-task-result (:name task)
                                             formatted
                                             {:use-arrow   false
                                              :keep-result true})}))
      {:output/kind  :transient
       :output/value (if (sequential? formatted)
                       (doall formatted)
                       formatted)})))


(defn- format-task-output [runtime task result]
  (try
    (cond
      (not (:iteration (meta result))) (format-sequence-output runtime task result)
      (= :latest (:state-format task))
      (format-sequence-output runtime (dissoc task :state-format) (reduce (fn [_ item] item) nil result))
      (and (get-in runtime [:pipeline :use-arrow])
           (or (:keep-state task) (has-dependants? (:name task) (:graph runtime))))
      (retained-iterator-output task result)
      :else (format-sequence-output runtime task result))
    (catch clojure.lang.ExceptionInfo e
      (if (= :collet.error/arrow-invalid-value (:collet.error/type (ex-data e)))
        (let [spec   (:spec task)
              path   (:return spec)
              action (or (when (and (vector? path) (= :state (first path))) (second path))
                         (:name (last (:actions spec))))]
          (throw (ex-info (ex-message e) (merge {:task (:name task) :action action} (ex-data e)) e)))
        (throw e)))))


(defn scratch-output!
  "Apply normal task handoff in an owned transient artifact area, without Store writes."
  [artifact-dir pipeline task result]
  (let [retained? (or (:keep-state task) (some #(some #{(:name task)} (:inputs %)) (:tasks pipeline)))
        output    (format-task-output {:pipeline (update pipeline :use-arrow #(if (nil? %) true %)) :graph (dep/graph)}
                                      (assoc task
                                        :spec (or (:spec task) task)
                                        :keep-state retained?)
                                      result)]
    (try
      (let [publication (when (#{:dataset :scalar} (:output/kind output))
                          (artifact/publish-output! artifact-dir (java.util.UUID/randomUUID) (java.util.UUID/randomUUID) output))]
        (cond-> {:artifact-dir   artifact-dir
                 :readers        (atom [])
                 :representation (if (= :dataset (:output/kind output)) "dataset-batches" "value")}
          publication (assoc :artifact (:artifact publication))
          (nil? publication) (assoc :value (:output/value output))))
      (finally (when-let [file (:output/file output)] (.delete ^File file))))))


(defn read-scratch-output [output]
  (let [value (if-let [artifact (:artifact output)]
                (artifact/read-artifact (:artifact-dir output) artifact)
                (:value output))]
    (when-let [close (:collet/close (meta value))] (swap! (:readers output) conj close))
    value))


(defn close-scratch-output! [output]
  (when output
    (doseq [close @(:readers output)] (close))
    (reset! (:readers output) [])
    (when (:artifact output) (artifact/delete-artifacts! (:artifact-dir output) [(:artifact output)]))))


(defn- handle-task-error!
  [ctx run-id task error]
  (let [task-name (:name task)
        root      (root-cause error)
        now       (System/currentTimeMillis)]
    (if (instance? InterruptedException root)
      (let [durable-task (some->> (store/get-task-runs (:store ctx) run-id)
                                  (utils/find-first #(= task-name
                                                        (:task/name %))))]
        (when-not (contains? terminal-task-statuses (:task/status durable-task))
          (store/update-task! (:store ctx)
                              (:task/id durable-task)
                              {:task/status      :interrupted
                               :task/finished-at now})))
      (let [failure (sanitized-error error task-name)
            task'   (store/update-task! (:store ctx)
                                        (:task/id task)
                                        {:task/status      :failed
                                         :task/finished-at now
                                         :task/error       failure})]
        (if (:skip-on-error task)
          (do
            (skip-downstream-tasks! ctx run-id task-name)
            (safe-callback (:on-task-error ctx) task'))
          (do
            (store/update-run! (:store ctx)
                               run-id
                               {:run/status      :failed
                                :run/finished-at now
                                :run/error       failure})
            (safe-callback (:on-task-error ctx) task')))))))


(defn- execute-run-task!
  [ctx run-id task]
  (let [runtime     (get @(:runs ctx) run-id)
        task-name   (:name task)
        config      (:config runtime)
        retry-count (atom 0)
        outcome     (try
                      (stream/with-resources
                       (let [input-refs (input-output-refs ctx run-id task)
                             trace      (assoc (:trace runtime)
                                          :run-id run-id
                                          :task-id (:task/id task)
                                          :task task-name
                                          :pipeline (get-in runtime [:pipeline :name])
                                          :version (get-in runtime [:pipeline :version]))
                             task-ctx   {:config          config
                                         :collet/on-retry (fn []
                                                            (let [n (swap! retry-count inc)]
                                                              (store/update-task! (:store ctx) (:task/id task) {:task/retry-count n})))
                                         :state           {}
                                         :inputs          (task-inputs ctx task input-refs)
                                         :store           (:store ctx)}]
                         (ml/with-context trace
                                          {:output (->> ((:task-fn task) task-ctx)
                                                        (format-task-output runtime task))})))
                      (catch Throwable error
                        {:error error}))]
    (try
      (if-let [error (:error outcome)]
        (do (store/update-task! (:store ctx) (:task/id task) {:task/retry-count @retry-count})
            (handle-task-error! ctx run-id task error))
        (let [output (:output outcome)]
          (if (= :stopped (:run/status (store/get-run (:store ctx) run-id)))
            (store/update-task! (:store ctx)
                                (:task/id task)
                                {:task/status      :interrupted
                                 :task/finished-at (System/currentTimeMillis)})
            (let [publication (when (#{:dataset :scalar} (:output/kind output))
                                (artifact/publish-output! (:artifact-dir ctx)
                                                          run-id
                                                          (:task/id task)
                                                          output))
                  task'       (store/complete-task!
                               (:store ctx)
                               (:task/id task)
                               (cond-> {:task/status      :completed
                                        :task/retry-count @retry-count
                                        :task/outcome     :computed
                                        :task/finished-at (System/currentTimeMillis)}
                                 publication
                                 (merge publication
                                        {:task/output-records (get-in publication [:artifact :artifact/records])
                                         :task/output-bytes   (get-in publication [:artifact :artifact/bytes])})))]
              ;; A dependant can only resolve this after the completion
              ;; transaction has made its output reference durable. Terminal
              ;; results without dependants remain process-local and create no
              ;; artifact.
              (swap! (:results runtime)
                assoc
                task-name
                (if publication
                  (if (= :scalar (:output/kind output))
                    (assoc (:output publication)
                      :output/value
                      (:output/value output))
                    (cond-> (assoc (:output publication)
                              :output/artifact
                              (:artifact publication))
                      (:output/live-value output)
                      (assoc :output/value (:output/live-value output))))
                  output))
              (safe-callback (:on-task-complete ctx) task')))))
      (catch Throwable error
        (infrastructure-failure! ctx run-id error))
      (finally
       (when-let [file (get-in outcome [:output :output/file])] (.delete ^File file))
       (swap! (:task-futures runtime) dissoc task-name)))))


(defn- start-ready-task!
  [ctx run-id task]
  (let [runtime (get @(:runs ctx) run-id)
        task'   (store/update-task! (:store ctx)
                                    (:task/id task)
                                    {:task/status :running
                                     :task/retry-count 0
                                     :task/started-at
                                     (System/currentTimeMillis)})]
    (safe-callback (:on-task-start ctx) task')
    (let [task-name (:task/name task)
          future    (FutureTask.
                     ^Runnable
                     #(execute-run-task! ctx
                                         run-id
                                         (assoc (get (:tasks runtime) task-name)
                                           :task/id (:task/id task)))
                     nil)]
      (swap! (:task-futures runtime) assoc task-name future)
      (try
        (.execute ^ExecutorService (:executor ctx) future)
        (catch Throwable error
          (swap! (:task-futures runtime) dissoc task-name)
          (throw error))))))


(defn- aborting-task-failure?
  [runtime tasks]
  (some (fn [task]
          (and (= :failed (:task/status task))
               (not (:skip-on-error
                     (get (:tasks runtime) (:task/name task))))))
        tasks))


(defn- schedule-ready-tasks!
  [ctx run-id]
  (let [runtime     (get @(:runs ctx) run-id)
        tasks       (store/get-task-runs (:store ctx) run-id)
        tasks-by-id (into {} (map (juxt :task/id identity)) tasks)
        running     (count (filter #(= :running (:task/status %)) tasks))
        available   (max 0
                         (- (get-in runtime [:pipeline :max-parallelism])
                            running))
        ready       (when-not (aborting-task-failure? runtime tasks)
                      (->> tasks
                           (filter #(and (= :waiting (:task/status %))
                                         (dependencies-met? % tasks-by-id)))
                           (take available)))]
    (run! #(start-ready-task! ctx run-id %) ready)
    tasks))


(defn- run-scheduler!
  [ctx run-id]
  (let [runtime (get @(:runs ctx) run-id)]
    (try
      (loop []
        (let [run (store/get-run (:store ctx) run-id)]
          (cond
            (contains? terminal-run-statuses (:run/status run))
            (if (seq @(:task-futures runtime))
              (do
                (when (#{:failed :stopped} (:run/status run))
                  (doseq [[_ ^Future future] @(:task-futures runtime)]
                    (.cancel future true)))
                (Thread/sleep 50)
                (recur))
              (finalize-terminal-run! ctx run-id {}))

            @(:halted? runtime)
            nil

            (= :paused (:run/status run))
            (do
              (Thread/sleep 50)
              (recur))

            :else
            (let [tasks (schedule-ready-tasks! ctx run-id)]
              (cond
                (aborting-task-failure? runtime tasks)
                (do
                  (Thread/sleep 50)
                  (recur))

                (and (all-completed? tasks)
                     (empty? @(:task-futures runtime)))
                (finalize-terminal-run!
                 ctx
                 run-id
                 {:run/status      :done
                  :run/finished-at (System/currentTimeMillis)})

                :else
                (do
                  (Thread/sleep 50)
                  (recur)))))))
      (catch InterruptedException _)
      (catch Throwable error
        (infrastructure-failure! ctx run-id error)))))


(defn start
  "Persists and starts a new run of an immutable compiled pipeline.
  `prepared-runtime` is a process-local execution state produced by the compiler;
  it contains rebuilt task functions and is never persisted."
  [ctx pipeline config prepared-runtime]
  (ensure-open-context! ctx)
  (let [store      (:store ctx)
        pipeline   (store/save-pipeline! store ctx pipeline)
        run-id     (random-uuid)
        now        (System/currentTimeMillis)
        task-ids   (into {}
                         (map (fn [{:keys [name]}]
                                [name (random-uuid)]))
                         (:tasks pipeline))
        run        {:run/id         run-id
                    :run/pipeline   {:pipeline/name    (:name pipeline)
                                     :pipeline/version (:version pipeline)}
                    :run/status     :running
                    :run/created-at now
                    :run/started-at now}
        task-runs  (mapv (fn [{:keys [name inputs]}]
                           {:task/id         (get task-ids name)
                            :task/run        run-id
                            :task/name       name
                            :task/status     :waiting
                            :task/inputs     (mapv task-ids (or inputs []))
                            :task/created-at now})
                         (:tasks pipeline))
        runtime    (merge prepared-runtime
                          {:pipeline     pipeline
                           :trace        (ml/local-context)
                           :config       config
                           :results      (atom {})
                           :task-futures (atom {})
                           :scheduler    (atom nil)
                           :completion   (promise)
                           :halted?      (atom false)})
        run-handle (Run. ctx run-id)]
    (store/create-run! store run task-runs)
    (swap! (:runs ctx) assoc run-id runtime)
    (log-run-state! ctx run)
    (try
      (reset! (:scheduler runtime)
        (.submit ^ExecutorService (:executor ctx)
                 ^Runnable
                 #(run-scheduler! ctx run-id)))
      (catch Throwable error
        (infrastructure-failure! ctx run-id error)))
    run-handle))


(defn pipe-status
  [run]
  (if-let [outcome (completion-outcome run)]
    (some-> outcome
            :run
            :run/status)
    (:run/status (store/get-run (:store (run-context run)) (run-id run)))))


(defn pipe-error
  [run]
  (if-let [outcome (completion-outcome run)]
    (some-> outcome
            :run
            :run/error)
    (:run/error (store/get-run (:store (run-context run)) (run-id run)))))


(defn pause
  [run]
  (let [ctx (run-context run)
        id  (run-id run)]
    (when (= :running (:run/status (store/get-run (:store ctx) id)))
      (log-run-state! ctx (store/update-run! (:store ctx) id {:run/status :paused})))
    run))


(defn resume
  [run]
  (let [ctx (run-context run)
        id  (run-id run)]
    (when (= :paused (:run/status (store/get-run (:store ctx) id)))
      (log-run-state! ctx (store/update-run! (:store ctx) id {:run/status :running})))
    run))


(defn stop
  [run]
  (let [ctx     (run-context run)
        id      (run-id run)
        runtime (run-runtime run)
        current (store/get-run (:store ctx) id)]
    (when (and runtime
               (not (contains? terminal-run-statuses (:run/status current))))
      (try
        (let [now  (System/currentTimeMillis)
              run' (store/update-run! (:store ctx)
                                      id
                                      {:run/status      :stopped
                                       :run/finished-at now})]
          (doseq [[_ ^Future future] @(:task-futures runtime)]
            (.cancel future true))
          (doseq [task (store/get-task-runs (:store ctx) id)]
            (case (:task/status task)
              :running
              (store/update-task! (:store ctx)
                                  (:task/id task)
                                  {:task/status      :interrupted
                                   :task/finished-at now})

              :waiting
              (let [task' (store/update-task! (:store ctx)
                                              (:task/id task)
                                              {:task/status      :skipped
                                               :task/finished-at now})]
                (safe-callback (:on-task-skipped ctx) task'))

              nil))
          run')
        (catch Throwable error
          (infrastructure-failure! ctx id error))))
    run))


(defn delete-pipeline!
  "Delete a version, or a whole pipeline with :latest-version, and its completed runs."
  [ctx name options]
  (ensure-open-context! ctx)
  (let [busy-run? (:busy-run? options)
        result    (store/delete-pipeline! (:store ctx)
                                          name
                                          (assoc options
                                            :busy-run?
                                            (fn [id]
                                              (or (and busy-run? (busy-run? id))
                                                  (when-let [runtime (get @(:runs ctx) id)]
                                                    (or (and (:completion runtime) (not (realized? (:completion runtime))))
                                                        (seq (some-> runtime
                                                                     :task-futures
                                                                     deref))))))))
        ids       (set (:run-ids result))]
    (swap! (:runs ctx) #(apply dissoc % ids))
    (try
      (artifact/delete-runs! (:artifact-dir ctx) ids)
      result
      (catch Exception error
        (ml/log :collet/artifact-cleanup-failed :run-ids ids :message (ex-message error))
        (assoc result
          :cleanup-warning
          "History was deleted, but some internal output files could not be removed. Check the server logs.")))))


(defn close
  "Stops active runs, closes the executor, and always closes the Store."
  [ctx]
  (when (compare-and-set! (:closed? ctx) false true)
    (try
      (try
        (doseq [[run-id _] @(:runs ctx)
                :let       [run (Run. ctx run-id)]
                :when      (not (contains? terminal-run-statuses (pipe-status run)))]
          (stop run))
        (finally
         (.shutdownNow ^ExecutorService (:executor ctx))
         (.awaitTermination ^ExecutorService (:executor ctx)
                            5
                            TimeUnit/SECONDS)
         (doseq [[run-id runtime] @(:runs ctx)
                 :let             [run (store/get-run (:store ctx) run-id)]]
           (when (and (contains? terminal-run-statuses (:run/status run))
                      (or (nil? (:task-futures runtime))
                          (empty? @(:task-futures runtime))))
             (try
               (release-context-artifacts! ctx run-id runtime)
               (catch Throwable error
                 (ml/log :collet/artifact-cleanup-failed
                         :run-id run-id
                         :message (ex-message error)))))

           (when-let [results (:results runtime)]
             (reset! results {}))

           (when (and (:completion runtime)
                      (not (realized? (:completion runtime))))
             (deliver-run! ctx run-id run)))))
      (finally
       (store/close! (:store ctx)))))
  nil)
