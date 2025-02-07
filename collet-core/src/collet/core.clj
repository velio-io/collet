(ns collet.core
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.walk :as walk]
   [clojure.edn :as edn]
   [malli.core :as m]
   [malli.dev.pretty :as pretty]
   [malli.error :as me]
   [malli.util :as mu]
   [tech.v3.dataset :as ds]
   [weavejester.dependency :as dep]
   [diehard.core :as dh]
   [com.brunobonacci.mulog :as ml]
   [collet.action :as collet.action]
   [collet.utils :as utils]
   ;; built-in actions
   [collet.actions.counter]
   [collet.actions.slicer]
   [collet.actions.fold]
   [collet.actions.enrich]
   [collet.actions.mapper]
   [collet.actions.switch]
   [collet.conditions :as collet.conds]
   [collet.select :as collet.select]
   [collet.deps :as collet.deps]
   [collet.arrow :as collet.arrow])
  (:import
   [clojure.lang IFn ILookup]
   [java.io File]
   [java.util.concurrent ExecutorService Executors Future Semaphore]
   [weavejester.dependency MapDependencyGraph]))


(def context-spec
  [:map
   [:config map?]
   [:state [:map-of :keyword :any]]
   [:inputs {:optional true}
    [:map-of :keyword :any]]])


(defn ->context
  "Creates a context map from the given configuration map.
   Context map is used to pass the configuration and the current state to the actions."
  [config]
  {:config config
   :state  {}})


;;------------------------------------------------------------------------------
;; Actions
;;------------------------------------------------------------------------------


(def action-spec
  [:map
   [:name :keyword]
   [:type :keyword]
   [:fn {:optional true} [:or fn? list?]]
   [:when {:optional true} collet.conds/condition?]
   [:keep-state {:optional true} :boolean]
   [:params {:optional true}
    [:or map? [:vector :any]]]
   [:selectors {:optional true}
    [:map-of :symbol collet.select/select-path]]
   [:return {:optional true}
    collet.select/select-path]])


(defn compile-action-params
  "Prepare the action parameters by evaluating the config values.
   Takes the action spec and the context and returns the evaluated parameters.
   Clojure symbols used as parameter value placeholders. If the same symbol is found in the parameters map
   and as the selectors key it will be replaced with the corresponding value from the context."
  {:malli/schema [:=> [:cat (mu/select-keys action-spec [:params :selectors]) context-spec utils/eval-context-spec]
                  [:maybe (mu/get action-spec :params)]]}
  [{:keys [params selectors]} context eval-context]
  (when (some? params)
    (let [selectors-values (update-vals selectors #(collet.select/select % context))]
      (->> params
           ;; x could be a function definition, try to evaluate the form
           (walk/prewalk
            (fn [x]
              (if (and (list? x) (symbol (first x)))
                (utils/eval-form eval-context x selectors-values)
                x)))
           ;; replace value with the corresponding value from the context
           (walk/postwalk
            (fn [x]
              (if (and (symbol? x) (contains? selectors x))
                (get selectors-values x)
                x)))))))


(defn compile-action
  "Compiles an action spec into a function.
   Resulting function should be executed with a task context (configuration and current state).
   Action can be a producer or a consumer of data, depending on the action type."
  {:malli/schema [:=> [:cat utils/eval-context-spec action-spec]
                  [:=> [:cat context-spec] :any]]}
  [eval-context action-spec]
  (let [{:keys        [return]
         action-type  :type
         action-name  :name
         execute-when :when
         :as          action-spec} (collet.action/prep action-spec)]
    (if (sequential? action-spec)
      ;; expand to multiple actions
      (mapv (partial compile-action eval-context) action-spec)
      ;; compile a single action
      (let [action-fn       (cond
                              ;; Clojure core functions
                              (and (qualified-keyword? action-type)
                                   (= (namespace action-type) "clj"))
                              (-> (name action-type) symbol resolve)

                              ;; Custom functions
                              (= action-type :custom)
                              (let [func (:fn action-spec)]
                                (if (list? func)
                                  ;; if the function is a list, evaluate it
                                  (utils/eval-form eval-context func)
                                  func))

                              (= action-type :switch)
                              (-> action-spec
                                  (update :case
                                          #(mapv (fn [{:keys [actions] :as switch-case}]
                                                   (->> (mapv (partial compile-action eval-context) actions)
                                                        (assoc switch-case :actions)))
                                                 %))
                                  (collet.action/action-fn))

                              ;; Predefined actions
                              :otherwise
                              (collet.action/action-fn action-spec))
            execute-when-fn (when (collet.conds/valid-condition? execute-when)
                              (collet.conds/compile-conditions execute-when))]
        (fn [context]
          (try
            (if (or (nil? execute-when-fn) (execute-when-fn context))
              (ml/trace :collet/executing-action [:action action-name :type action-type]
                (if (= action-type :switch)
                  (let [context' (action-fn context)]
                    (update context :state merge (:state context')))

                  (let [params  (compile-action-params action-spec context eval-context)
                        result  (cond
                                  ;; multiple parameters passed
                                  (vector? params) (apply action-fn params)
                                  ;; single map parameter
                                  (map? params) (action-fn params)
                                  ;; no parameters
                                  (nil? params) (action-fn))
                        result' (if (some? return)
                                  (collet.select/select return result)
                                  result)]
                    (tap> {:action action-name :type action-type :params params :result result'})
                    (assoc-in context [:state action-name] result'))))
              (do (ml/log :collet/action-skipped :action action-name :type action-type)
                  ;; need to reset action state to prevent discrepancies between iterations
                  (when-not (:keep-state action-spec)
                    (assoc-in context [:state action-name] nil))))
            (catch Exception e
              (throw (ex-info "Action failed"
                              (-> (merge (ex-data e)
                                         {:action action-name
                                          :params (compile-action-params action-spec context eval-context)})
                                  (utils/samplify))
                              e)))))))))


(defn find-action
  [spec action]
  (->> (:tasks spec)
       (mapcat :actions)
       (utils/find-first #(= (:name %) action))))


(defn list-actions
  [spec]
  (->> (:tasks spec)
       (mapcat :actions)
       (mapv :name)))


(defn execute-action
  ([action config]
   (execute-action action config {}))

  ([action config context]
   (let [afn (compile-action (utils/eval-ctx) action)]
     (afn (merge (->context config) context)))))


;;------------------------------------------------------------------------------
;; Tasks
;;------------------------------------------------------------------------------


(def task-spec
  [:and
   [:map
    [:name :keyword]
    [:inputs {:optional true}
     [:vector :keyword]]
    [:retry {:optional true}
     [:map
      [:max-retries {:optional true} :int]
      [:backoff-ms {:optional true} [:vector :int]]]]
    [:skip-on-error {:optional true} :boolean]
    [:keep-state {:optional true} :boolean]
    [:state-format {:optional true} [:enum :latest :flatten]]
    [:setup {:optional true}
     [:vector action-spec]]
    [:actions
     [:vector {:min 1} action-spec]]
    [:return {:optional    true
              :description "specifies how to get the data when all actions are executed"}
     [:or collet.select/select-path fn?]]
    [:iterator {:optional true}
     [:map
      [:next {:description "answers on the question should we iterate over task actions again"}
       [:or collet.conds/condition? :boolean]]]]
    [:parallel {:optional true}
     [:and [:map
            [:items {:optional true} collet.select/select-path]
            [:range {:optional true}
             [:map
              [:end :int]
              [:start {:optional true} :int]
              [:step {:optional true} :int]]]
            [:threads {:optional true} :int]]
      [:fn {:error/message "either :items or :range should be specified but not both"}
       (fn [{:keys [items range]}]
         (if (or items range)
           (not (and items range))
           true))]]]]
   [:fn {:error/message "either :iterator or :parallel should be specified but not both"}
    (fn [{:keys [iterator parallel]}]
      (if (or iterator parallel)
        (not (and iterator parallel))
        true))]])


(defn execute-actions
  "Executes a sequence of actions with the given context."
  [actions context]
  (reduce
   (fn [context action]
     (action context))
   context
   actions))


(defn extract-data-fn
  "Returns a function that extracts data from the context based on the iterator spec."
  {:malli/schema [:=> [:cat task-spec]
                  [:=> [:cat context-spec] :any]]}
  [{:keys [actions return]}]
  (let [last-action (-> actions last :name)]
    (cond
      (nil? return) (fn [context] (get-in context [:state last-action]))
      (vector? return) (fn [context] (collet.select/select return context))
      :otherwise return)))


(defn next-fn
  "Returns a function that decides whether to continue iterating based on the context."
  {:malli/schema [:=> [:cat [:maybe (mu/get-in task-spec [0 :iterator])]]
                  [:=> [:cat context-spec] :any]]}
  [{:keys [next]}]
  (cond
    (true? next)
    identity
    ;; if given vector is a condition DSL
    (and (vector? next) (collet.conds/valid-condition? next))
    (let [condition-fn (collet.conds/compile-conditions next)]
      (fn [context]
        (when (condition-fn context)
          context)))
    ;; decide whether to continue based on a specific value in the context
    (vector? next)
    (fn [context]
      (let [value (get-in context next)]
        (cond
          (and (seqable? value) (not-empty value)) context
          (some? value) context)))
    ;; don't iterate at all
    :otherwise
    (constantly nil)))


(defn read-regex
  "Parse regex strings from the EDN file"
  [rgx]
  (re-pattern rgx))


(defn read-action
  "Read the action from EDN file if it exists"
  [path-key]
  (let [sep       File/separator
        file-path (str (string/replace (namespace path-key) "." sep) sep (name path-key))
        file      (io/as-file file-path)]
    (if (.exists file)
      (->> file slurp (edn/read-string {:eof nil :readers {'rgx read-regex}}))
      (throw (ex-info "File does not exist" {:file file-path})))))


(defn replace-external-actions
  "Replace the actions which refers to external files"
  [actions]
  (mapv
   (fn [action]
     (if (= (:type action) :switch)
       (assoc action :case (map #(update % :actions replace-external-actions)
                                (:case action)))
       (if (string/ends-with? (name (:type action)) ".edn")
         (utils/deep-merge action (read-action (:type action)))
         action)))
   actions))


(defn expand-on-actions
  "Actions can expand (modify) task definition if provides expand hook"
  {:malli/schema [:=> [:cat task-spec]
                  task-spec]}
  [{:keys [actions] :as task}]
  (reduce
   (fn [t action]
     (if (= (:type action) :switch)
       (reduce (fn [task-acc switch-action]
                 (collet.action/expand task-acc switch-action))
               t (->> action :case (mapcat :actions)))
       (collet.action/expand t action)))
   task actions))


(defrecord Task
  [name                       ;; task name
   spec                       ;; full task spec (after processing)
   inputs                     ;; set of task-ids that must be completed first
   skip-on-error              ;; whether to skip the task if a dependency fails
   keep-state                 ;; whether to keep the result in the context
   state-format               ;; one of :latest, :flatten. default (if not set) is left as is
   task-fn                    ;; (fn []) actual work to do
   status                     ;; one of :waiting, :running, :completed, :failed, :skipped, :interrupted
   result                     ;; the result of (run-fn) here
   error])                    ;; exception if any


(def task?
  (m/-simple-schema
   {:type :task?
    :pred #(instance? Task %)
    :type-properties
    {:error/message "should be an instance of Task"}}))


(defn compile-task
  "Compiles a task spec into a function.
   Resulting function can be executed with a configuration map,
   representing a single run of all actions attached to it.
   Actions should run in the order they are defined in the spec."
  {:malli/schema [:=> [:cat utils/eval-context-spec task-spec]
                  task?]}
  [eval-context task]
  (let [{:keys [name setup actions iterator parallel retry
                skip-on-error inputs keep-state state-format]
         :as   task} (cond-> task
                       :always (update :actions replace-external-actions)
                       :always (expand-on-actions)
                       (some? (:parallel task))
                       (utils/replace-all {:$parallel/item [:state :$parallel/item]}))

        {:keys [max-retries backoff-ms]
         :or   {max-retries 2
                backoff-ms  [200 3000]}} retry

        compile-action-ctx (partial compile-action eval-context)
        setup-actions      (flatten (map compile-action-ctx setup))
        task-actions       (flatten (map compile-action-ctx actions))
        extract-data       (extract-data-fn task)

        task-exec-fn       (fn execute-task [ctx]
                             (try
                               (if (some? retry)
                                 (dh/with-retry {:retry-on    Exception
                                                 :max-retries max-retries
                                                 :backoff-ms  backoff-ms
                                                 :on-retry    (fn [_ ex]
                                                                (ml/log :collet/retrying-task
                                                                        :task name
                                                                        :reason (ex-data ex)
                                                                        :message (ex-message ex)))}
                                   (execute-actions task-actions ctx))
                                 ;; execute without retry
                                 (execute-actions task-actions ctx))
                               (catch Exception e
                                 (throw (ex-info "Task failed" (merge (ex-data e) {:task name}) e)))))

        task-fn            (cond
                             (some? parallel)
                             (fn [context]
                               ;; run actions to set up the task
                               (let [context'    (cond->> context
                                                   (seq setup-actions) (execute-actions setup-actions))
                                     items       (if (some? (:range parallel))
                                                   (let [{:keys [start end step]
                                                          :or   {start 0 step 1}} (:range parallel)]
                                                     (range start end step))
                                                   (collet.select/select (:items parallel) context'))
                                     executor    (Executors/newVirtualThreadPerTaskExecutor)
                                     semaphore   (Semaphore. (or (:threads parallel) 10))
                                     submit-task (fn [item]
                                                   (.submit executor
                                                            ^Callable
                                                            (fn []
                                                              ; Block if limit is reached
                                                              (.acquire semaphore)
                                                              (try
                                                                (-> context'
                                                                    (assoc-in [:state :$parallel/item] item)
                                                                    (task-exec-fn)
                                                                    (extract-data))
                                                                (finally
                                                                  ; Release permit
                                                                  (.release semaphore))))))
                                     items'      (cond
                                                   (ds/dataset? items) (ds/rows items)
                                                   (utils/ds-seq? items) (mapcat ds/rows items)
                                                   :otherwise items)
                                     futures     (doall (map submit-task items'))]
                                 (try
                                   ;; Collect results in original order by dereferencing futures
                                   (mapv (fn [^Future future]
                                           (.get future))
                                         futures)
                                   (finally
                                     (.shutdown executor)))))

                             (some? iterator)
                             (let [next-iteration (next-fn iterator)]
                               (fn [context]
                                 ;; run actions to set up the task
                                 (let [context' (cond->> context
                                                  (seq setup-actions) (execute-actions setup-actions))]
                                   (-> (iteration task-exec-fn
                                                  :initk context'
                                                  :vf extract-data
                                                  :kf next-iteration)
                                       (with-meta {:iteration true})))))

                             :otherwise
                             (fn [context]
                               (let [context' (cond->> context
                                                (seq setup-actions) (execute-actions setup-actions))]
                                 (extract-data (task-exec-fn context')))))]
    (map->Task
     {:name          name
      :spec          task
      :keep-state    keep-state
      :state-format  state-format
      :skip-on-error skip-on-error
      :status        :waiting
      :inputs        inputs
      :task-fn       task-fn})))


(defn find-task
  [spec task]
  (->> (:tasks spec)
       (utils/find-first #(= (:name %) task))))


(defn list-tasks
  [spec]
  (->> (:tasks spec)
       (mapv :name)))


(defn execute-task
  ([task config]
   (execute-task task {} config))

  ([task config context]
   (let [{:keys [task-fn]} (compile-task (utils/eval-ctx) task)
         result     (task-fn (merge (->context config) context))
         iteration? (-> result meta :iteration)]
     (if iteration?
       (doall (seq result))
       result))))


;;------------------------------------------------------------------------------
;; Pipeline
;;------------------------------------------------------------------------------


(def pipeline-spec
  [:map
   [:name :keyword]
   [:use-arrow {:optional true} :boolean]
   [:max-parallelism {:optional true} :int]
   [:deps {:optional true} collet.deps/deps-spec]
   [:tasks [:vector task-spec]]])


(defn index-by
  "Creates a map from the collection using the keyfn to extract the key."
  [keyfn coll]
  (persistent!
   (reduce (fn [m v]
             (let [k (keyfn v)]
               (assoc! m k v)))
           (transient {})
           coll)))


(def graph?
  (m/-simple-schema
   {:type :graph?
    :pred #(instance? MapDependencyGraph %)
    :type-properties
    {:error/message "should be an instance of MapDependencyGraph"}}))


(defn add-task-and-deps
  "Adds dependencies to the graph for the given task."
  {:malli/schema [:=> [:cat graph? :keyword [:maybe (mu/get-in task-spec [0 :inputs])]]
                  graph?]}
  [graph task-key inputs]
  (if (seq inputs)
    (reduce
     (fn [g input]
       (dep/depend g task-key input))
     graph inputs)
    ;; add node without dependencies
    (dep/depend graph task-key ::root)))


(defn ->pipeline-graph
  "Creates a dependency graph from the tasks map."
  {:malli/schema [:=> [:cat [:map-of :keyword task?]]
                  graph?]}
  [tasks]
  (reduce-kv
   (fn [graph task-key {:keys [inputs]}]
     (add-task-and-deps graph task-key inputs))
   (dep/graph)
   tasks))


(deftype ArrowTaskResult
  [task-name columns file])


(defn arrow-task-result?
  [x]
  (instance? ArrowTaskResult x))


(defn arrow->dataset
  [arrow-task-result]
  (collet.arrow/read-dataset
   (.-file ^ArrowTaskResult arrow-task-result)
   (.-columns ^ArrowTaskResult arrow-task-result)))


(defn handle-task-result
  [task-name data {:keys [use-arrow keep-result]}]
  (if (not (sequential? data))
    (when keep-result
      data)
    ;; process as a sequence
    (cond
      (and keep-result use-arrow)
      (let [seq-items?    (sequential? (first data))
            arrow-columns (if seq-items?
                            (collet.arrow/get-columns (first data))
                            (collet.arrow/get-columns data))]
        (if (and (some? arrow-columns)
                 (not-empty arrow-columns))
          ;; write to arrow file
          (let [file ^File (File/createTempFile (name task-name) ".arrow")]
            (.deleteOnExit file)
            (with-open [writer (collet.arrow/make-writer file arrow-columns)]
              (if seq-items?
                (loop [batch     (first data)
                       remaining (rest data)]
                  (when (some? batch)
                    (collet.arrow/write writer batch)
                    (recur (first remaining) (rest remaining))))
                ;; TODO add batching for flat sequences
                (collet.arrow/write writer data)))
            (ArrowTaskResult. task-name arrow-columns file))
          ;; return as is
          (doall data)))

      keep-result
      (doall data)

      :otherwise
      (do (doall data)
          nil))))


(defn dependencies-met?
  "Returns true if ALL dependencies are in :completed status (or :skipped if skip-on-error?=false)."
  [task tasks]
  (every?
   (fn [input]
     (let [input-task (get tasks input)]
       (when-not input-task
         (throw (ex-info (str "Missing dependency: " input) {:input input})))
       (= (:status input-task) :completed)))
   (:inputs task)))


(defn has-dependants?
  "Returns true if the task has any dependants."
  [task-name tasks-graph]
  (seq (dep/immediate-dependents tasks-graph task-name)))


(defn all-completed?
  [tasks]
  (->> (vals tasks)
       (some (fn [task]
               (or (= (:status task) :waiting)
                   (= (:status task) :running))))
       (not)))


(defn skip-downstream-tasks
  [task-name tasks-graph tasks]
  (let [dependants (dep/transitive-dependents tasks-graph task-name)]
    (swap! tasks (fn [ts]
                   (reduce
                    (fn [ts task-name]
                      (if (= (get-in ts [task-name :status]) :waiting)
                        (assoc-in ts [task-name :status] :skipped)
                        ts))
                    ts dependants)))))


(declare run-task-thread)


(defprotocol IPipelineLifeCycle
  "Defines the lifecycle methods for a pipeline."
  (start [this config])
  (stop [this])
  (pause [this])
  (resume [this config]))


(defprotocol IPipeline
  "Defines the pipeline interface.
   Pipeline is a collection of tasks that are executed in a specific order.
   Pipeline properties are id, name, status, and error.
   You shouldn't call the run-pipeline method directly, use the start method instead."
  (pipe-status [this])
  (pipe-error [this])
  (run-pipeline [this config]))


(deftype Pipeline
  [id                         ;; pipeline id
   name                       ;; pipeline name
   status                     ;; pipeline status; atom :pending, :running, :done, :stopped, :paused, :failed
   error                      ;; atom holding the error map; atom {:message, :task, :action, :exception}
   tasks                      ;; atom holding the tasks map; atom {task-id -> Task}
   tasks-graph                ;; graph of tasks based on their dependencies
   running-count              ;; atom (long) how many tasks are currently running
   max-parallelism            ;; max number of tasks that can run in parallel
   use-arrow                  ;; boolean; if true, use Arrow for data serialization
   executor                   ;; executor service to spawn virtual threads
   on-task-start              ;; (fn [task]) called just before a task starts
   on-task-complete           ;; (fn [task]) called on success
   on-task-error              ;; (fn [task]) called on error
   on-task-skipped]           ;; (fn [task]) called if skipping due to dep failure

  IPipeline
  (pipe-status [_] @status)
  (pipe-error [_] @error)

  (run-pipeline [this config]
    (future
     (ml/with-context {:app-name "collet" :pipeline-name name :pipeline-id id}
       (ml/trace :collet/pipeline-execution []
         (loop []
           (when (= @status :running)
             (let [all-tasks @tasks]
               (if (all-completed? all-tasks)
                 (do
                   ;; TODO clean uo all Arrow temp files
                   (ml/log :collet/pipeline-execution-finished)
                   (reset! status :done))

                 (do
                   (when (< @running-count max-parallelism)
                     (let [ready-tasks (->> (vals all-tasks)
                                            (filter #(and (= :waiting (:status %))
                                                          (dependencies-met? % all-tasks)))
                                            (take (- max-parallelism @running-count)))]
                       (doseq [task ready-tasks]
                         (run-task-thread this config task))))
                   (Thread/sleep 100)
                   (recur))))))))))

  ILookup
  (valAt [this k]
    (.valAt this k nil))

  (valAt [this k not-found]
    (get-in @tasks [k :result] not-found))

  IFn
  (invoke [this]
    (this {}))

  (invoke [this config]
    (start this config))

  IPipelineLifeCycle
  (start [this config]
    (when (= :pending @status)
      (ml/log :collet/starting-pipeline-execution)
      (reset! status :running)
      (run-pipeline this config)))

  (stop [this]
    (ml/log :collet/stopping-pipeline-execution)
    (reset! status :stopped)
    (.shutdown ^ExecutorService executor))

  (pause [this]
    (when (= :running @status)
      (ml/log :collet/pausing-pipeline-execution)
      (reset! status :paused)))

  (resume [this config]
    (when (contains? #{:paused :failed} @status)
      (ml/log :collet/resuming-pipeline-execution)
      (reset! status :running)
      (run-pipeline this config))))


(defn run-task-thread
  [^Pipeline pipeline config
   {:keys     [skip-on-error]
    task-name :name
    task-spec :spec
    :as       task}]
  (ml/trace :collet/starting-task [:task task-name]
    (let [log-ctx          (ml/local-context)
          executor         ^ExecutorService (.-executor pipeline)
          status           (.-status pipeline)
          error            (.-error pipeline)
          tasks            (.-tasks pipeline)
          running-count    (.-running-count pipeline)
          use-arrow        (.-use-arrow pipeline)
          tasks-graph      (.-tasks-graph pipeline)
          on-task-start    (.-on-task-start pipeline)
          on-task-complete (.-on-task-complete pipeline)
          on-task-error    (.-on-task-error pipeline)]
      (swap! tasks update task-name assoc :status :running)
      (swap! running-count inc)

      (when (fn? on-task-start)
        (on-task-start (get @tasks task-name)))
      ;; start the task
      (.submit executor
               ^Runnable
               (fn []
                 (ml/with-context log-ctx
                   (let [{:keys [task-fn inputs keep-state state-format]} task
                         inputs-map (reduce
                                     (fn [is i]
                                       (let [input-data (get-in @tasks [i :result])
                                             input-data (if (arrow-task-result? input-data)
                                                          (arrow->dataset input-data)
                                                          input-data)]
                                         (assoc is i input-data)))
                                     {} inputs)
                         context    (-> (->context config)
                                        (assoc :inputs inputs-map))]
                     (try
                       (let [task-result-raw       (task-fn context)
                             task-result-raw       (if (-> task-result-raw meta :iteration)
                                                     (seq task-result-raw)
                                                     task-result-raw)
                             result-sequential?    (sequential? task-result-raw)
                             formatted-task-result (cond
                                                     (and result-sequential?
                                                          (= state-format :latest))
                                                     (last task-result-raw)

                                                     (and result-sequential?
                                                          (= state-format :flatten))
                                                     (flatten task-result-raw)

                                                     :otherwise task-result-raw)
                             has-dependents?       (has-dependants? task-name tasks-graph)
                             keep-result           (or keep-state has-dependents?)
                             task-result           (handle-task-result
                                                    task-name
                                                    formatted-task-result
                                                    {:use-arrow   use-arrow
                                                     :keep-result keep-result})]
                         (tap> {:task      task-name
                                :task-spec task-spec
                                :context   context
                                :result    task-result})
                         (swap! tasks update task-name assoc
                                :status :completed
                                :result (when keep-result task-result)))

                       (when (fn? on-task-complete)
                         (on-task-complete (get @tasks task-name)))

                       (catch Throwable t
                         (let [root-cause (->> (iterate ex-cause t)
                                               (take-while identity)
                                               (last))]
                           (if (instance? InterruptedException root-cause)
                             ;; if the exception is an InterruptedException
                             ;; it means the pipeline was stopped externally
                             ;; no need to log it and propagate this error
                             (swap! tasks update task-name assoc :status :interrupted)

                             (let [{:keys [task action]} (ex-data t)
                                   original-error (ex-message root-cause)
                                   msg            (format "Pipeline error: %s Stopped on task: %s action: %s"
                                                          original-error task action)]
                               (tap> {:message   msg
                                      :task      task
                                      :action    action
                                      :context   (utils/samplify context)
                                      :exception t})
                               (swap! tasks update task-name assoc
                                      :status :failed
                                      :error-cause t)
                               (when (fn? on-task-error)
                                 (on-task-error (get @tasks task-name)))

                               (if skip-on-error
                                 (do
                                   (ml/log :collet/skipping-task-failure
                                           :task name
                                           :reason (ex-data t)
                                           :message (ex-message t))
                                   (skip-downstream-tasks task-name tasks-graph tasks))

                                 (do
                                   (ml/log :collet/pipeline-execution-failed
                                           :message msg :task task :action action :exception t)
                                   (reset! status :failed)
                                   (reset! error {:message msg :task task :action action :exception t})))))))

                       (finally
                         (swap! running-count dec))))))))))


(def pipeline?
  (m/-simple-schema
   {:type :pipeline?
    :pred #(instance? Pipeline %)
    :type-properties
    {:error/message "should be an instance of Pipeline"}}))


(defn extract-actions-types
  "Extracts the action types from the task"
  [{:keys [actions]}]
  (->> actions
       (map (fn [{:keys [type] :as action}]
              ;; enrich is a special case, actual action type specified under the :action key
              (cond (= type :enrich) (:action action)
                    (= type :switch) (->> (:case action) (map extract-actions-types))
                    :otherwise type)))
       (flatten)))


(def tasks->actions-namespaces-xf
  (comp (mapcat extract-actions-types)
        (filter (fn [action-type]
                  (let [action-ns (namespace action-type)]
                    (and (some? action-ns)
                         ;; clj namespace is reserved for clojure core functions
                         (not= action-ns "clj")
                         (not (string/ends-with? (name action-type) ".edn"))))))
        (map #(-> % namespace symbol))
        (distinct)
        (map vector)))


(defn get-actions-deps
  "Extracts the dependencies from the actions types from all tasks"
  [tasks]
  (transduce tasks->actions-namespaces-xf conj tasks))


(defn check-dependencies
  [deps tasks]
  (when (some? deps)
    (collet.deps/add-dependencies deps))

  (let [actions-deps (get-actions-deps tasks)]
    (when (seq actions-deps)
      (collet.deps/add-dependencies {:requires actions-deps}))))


(defn compile-pipeline
  "Compiles a pipeline spec into a function.
   Resulting function can be executed with a configuration map
   Flat list of tasks is compiled into a graph according to the dependencies.
   Tasks are then executed in the topological order."
  {:malli/schema [:=> [:cat pipeline-spec]
                  pipeline?]}
  ^Pipeline
  [{:keys [name tasks deps use-arrow max-parallelism
           on-task-start on-task-complete on-task-error on-task-skipped]
    :or   {use-arrow       true
           max-parallelism 10}
    :as   pipeline}]

  ;; validate pipeline spec first
  (when-not (m/validate pipeline-spec pipeline)
    (pretty/explain pipeline-spec pipeline)
    (->> (m/explain pipeline-spec pipeline)
         (me/humanize)
         (ex-info "Invalid pipeline spec.")
         (throw)))

  (check-dependencies deps tasks)

  (let [pipeline-id  (random-uuid)
        eval-context (utils/eval-ctx (:requires deps) (:imports deps))
        tasks-map    (->> tasks
                          (map #(compile-task eval-context %))
                          (index-by :name))]
    (->Pipeline
     pipeline-id
     name
     (atom :pending)
     (atom nil)
     (atom tasks-map)
     (->pipeline-graph tasks-map)
     (atom 0)
     max-parallelism
     use-arrow
     (Executors/newVirtualThreadPerTaskExecutor)
     on-task-start
     on-task-complete
     on-task-error
     on-task-skipped)))
