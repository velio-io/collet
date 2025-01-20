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


(def action-spec
  [:map
   [:name :keyword]
   [:type :keyword]
   [:fn {:optional true} [:or fn? list?]]
   [:when {:optional true} collet.conds/condition?]
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


(def task-spec
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
   [:iterator {:optional true}
    [:map
     [:data {:description "specifies how to get the data"}
      [:or collet.select/select-path fn?]]
     [:next {:optional    true
             :description "answers on the question should we iterate over task actions again"}
      [:maybe [:or collet.conds/condition? :boolean]]]]]])


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
  [{:keys [actions iterator]}]
  (let [{:keys [data]} iterator
        last-action (-> actions last :name)]
    (cond
      (nil? data) (fn [context] (get-in context [:state last-action]))
      (vector? data) (fn [context] (collet.select/select data context))
      :otherwise data)))


(defn next-fn
  "Returns a function that decides whether to continue iterating based on the context."
  {:malli/schema [:=> [:cat [:maybe (mu/get task-spec :iterator)]]
                  [:=> [:cat context-spec] :any]]}
  [{:keys [data next]}]
  (cond
    ;; if next is not provided, but we want to extract some data
    ;; possibly useful for infinite iterators
    (or (and (nil? next) (some? data))
        (true? next))
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


(defn deep-merge
  "Deeply merges maps"
  [& maps]
  (if (every? map? maps)
    (apply merge-with deep-merge maps)
    (last maps)))


(defn replace-external-actions
  "Replace the actions which refers to external files"
  [actions]
  (mapv
   (fn [action]
     (if (= (:type action) :switch)
       (assoc action :case (map #(update % :actions replace-external-actions)
                                (:case action)))
       (if (string/ends-with? (name (:type action)) ".edn")
         (deep-merge action (read-action (:type action)))
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


(defn compile-task
  "Compiles a task spec into a function.
   Resulting function can be executed with a configuration map,
   representing a single run of all actions attached to it.
   Actions should run in the order they are defined in the spec."
  {:malli/schema [:=> [:cat utils/eval-context-spec task-spec]
                  [:=> [:cat context-spec] [:sequential :any]]]}
  [eval-context task]
  (let [{:keys [name setup actions iterator retry skip-on-error]
         :as   task}
        (-> task
            (update :actions replace-external-actions)
            (expand-on-actions))

        compile-action-ctx (partial compile-action eval-context)
        setup-actions      (flatten (map compile-action-ctx setup))
        task-actions       (flatten (map compile-action-ctx actions))
        extract-data       (extract-data-fn task)
        next-iteration     (next-fn iterator)
        {:keys [max-retires backoff-ms]
         :or   {max-retires 3
                backoff-ms  [200 3000]}} retry
        execute-task-fn    (fn execute-task [ctx]
                             (try
                               (if (some? retry)
                                 (dh/with-retry {:retry-on    Exception
                                                 :max-retries max-retires
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
                                 (if (not skip-on-error)
                                   (throw (ex-info "Task failed" (merge (ex-data e) {:task name}) e))
                                   ;; returns the context from previous iteration
                                   ;; maybe we should detect a throwing action and preserve values from other actions
                                   (do
                                     (ml/log :collet/skipping-task-failure
                                             :task name
                                             :reason (ex-data e)
                                             :message (ex-message e))
                                     ctx)))))]
    (with-meta
     (fn [context]
       ;; run actions to set up the task
       (let [context' (cond->> context
                        (seq setup-actions) (execute-actions setup-actions))]
         (iteration execute-task-fn
                    :initk context'
                    :vf extract-data
                    :kf next-iteration)))
     {::task task})))


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
   (let [task-fn (compile-task (utils/eval-ctx) task)]
     (-> (task-fn (merge (->context config) context))
         (seq)
         (doall)))))


(def pipeline-spec
  [:map
   [:name :keyword]
   [:use-arrow {:optional true} :boolean]
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
  {:malli/schema [:=> [:cat graph? :keyword [:maybe (mu/get task-spec :inputs)]]
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
  {:malli/schema [:=> [:cat [:map-of :keyword task-spec]]
                  graph?]}
  [tasks]
  (reduce-kv
   (fn [graph task-key {:keys [inputs]}]
     (add-task-and-deps graph task-key inputs))
   (dep/graph)
   tasks))


(defn ->tasks-queue
  "Creates a queue (sequence) of tasks to be executed.
   Tasks are sorted topologically, so the first task in the queue is the one that has no dependencies."
  {:malli/schema [:=> [:cat [:map-of :keyword task-spec]]
                  [:sequential :keyword]]}
  [tasks]
  (->> (->pipeline-graph tasks)
       (dep/topo-sort)
       ;; first task should be a ::root node
       (rest)))


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
  [task-name data-seq {:keys [use-arrow keep-result]}]
  (cond
    (and keep-result use-arrow)
    (let [seq-items?    (sequential? (first data-seq))
          arrow-columns (if seq-items?
                          (collet.arrow/get-columns (first data-seq))
                          (collet.arrow/get-columns data-seq))]
      (if (some? arrow-columns)
        ;; write to arrow file
        (let [file ^File (File/createTempFile (name task-name) ".arrow")]
          (.deleteOnExit file)
          (with-open [writer (collet.arrow/make-writer file arrow-columns)]
            (if seq-items?
              (loop [batch     (first data-seq)
                     remaining (rest data-seq)]
                (when (some? batch)
                  (collet.arrow/write writer batch)
                  (recur (first remaining) (rest remaining))))
              ;; TODO add batching for flat sequences
              (collet.arrow/write writer data-seq)))
          (ArrowTaskResult. task-name arrow-columns file))
        ;; return as is
        (doall data-seq)))

    keep-result
    (doall data-seq)

    :otherwise
    (do (doall data-seq)
        nil)))


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
   You shouldn't call the run-worker method directly, use the start method instead."
  (pipe-id [this])
  (pipe-name [this])
  (pipe-status [this])
  (pipe-error [this])
  (run-worker [this config]))


(deftype Pipeline [id name tasks state options]
  IPipeline
  (pipe-id [_] id)
  (pipe-name [_] name)
  (pipe-status [_] (:status @state))
  (pipe-error [_] (:error @state))

  (run-worker [this config]
    (let [worker (future
                  (ml/with-context {:app-name "collet" :pipeline-name name :pipeline-id id}
                    (ml/trace :collet/pipeline-execution []
                      (let [pipe-graph (->pipeline-graph tasks)]
                        ;; executes tasks one by one
                        (loop [tq (:tasks-queue @state)]
                          (if-some [task-key (first tq)]
                            ;; prepare task context
                            (let [{::keys [task-fn]
                                   :keys  [inputs keep-state state-format]
                                   :as    task} (get tasks task-key)
                                  inputs-map (reduce
                                              (fn [is i]
                                                (let [input-data (get-in @state [:results i])
                                                      input-data (if (arrow-task-result? input-data)
                                                                   (arrow->dataset input-data)
                                                                   input-data)]
                                                  (assoc is i input-data)))
                                              {} inputs)
                                  context    (-> (->context config)
                                                 (assoc :inputs inputs-map))]
                              (let [exec-status (ml/trace :collet/starting-task [:task task-key]
                                                  (try
                                                    (let [task-result-seq       (->> (task-fn context)
                                                                                     (seq))
                                                          formatted-task-result (case state-format
                                                                                  :latest (last task-result-seq)
                                                                                  :flatten (flatten task-result-seq)
                                                                                  task-result-seq)
                                                          has-dependents?       (seq (dep/immediate-dependents pipe-graph task-key))
                                                          keep-result           (or keep-state has-dependents?)
                                                          task-result           (handle-task-result
                                                                                 task-key
                                                                                 formatted-task-result
                                                                                 {:use-arrow   (:use-arrow options)
                                                                                  :keep-result keep-result})]
                                                      (tap> {:task task-key :task-spec task :context context :result task-result})
                                                      (when keep-result
                                                        (swap! state assoc-in [:results task-key] task-result)))

                                                    ;; update tasks queue and return the status
                                                    (swap! state update :tasks-queue rest)
                                                    :continue

                                                    (catch Exception e
                                                      (let [root-cause (->> (iterate ex-cause e)
                                                                            (take-while identity)
                                                                            (last))]
                                                        (if (instance? InterruptedException root-cause)
                                                          ;; if the exception is an InterruptedException
                                                          ;; it means the pipeline was stopped externally
                                                          ;; no need to log it and propagate this error
                                                          :interrupted

                                                          ;; throw the exception for any other case
                                                          (let [{:keys [task action]} (ex-data e)
                                                                original-error (ex-message root-cause)
                                                                msg            (format "Pipeline error: %s Stopped on task: %s action: %s"
                                                                                       original-error task action)]
                                                            (tap> {:message msg :task task :action action :context (utils/samplify context) :exception e})
                                                            (ml/log :collet/pipeline-execution-failed
                                                                    :message msg :task task :action action :exception e)
                                                            (swap! state assoc
                                                                   :status :failed
                                                                   :error {:message msg :task task :action action :exception e})
                                                            (throw e)))))))]

                                (if (not= :continue exec-status)
                                  exec-status
                                  ;; continue with the next task
                                  (recur (rest tq)))))

                            ;; all tasks are done
                            (do
                              (ml/log :collet/pipeline-execution-finished)
                              (swap! state assoc :status :done)
                              ;; TODO clean uo all Arrow temp files
                              :done)))))))]
      (swap! state assoc :worker worker)
      worker))

  ILookup
  (valAt [this k]
    (.valAt this k nil))

  (valAt [this k not-found]
    (get-in @state [:results k] not-found))

  IFn
  (invoke [this]
    (this {}))

  (invoke [this config]
    (start this config))

  IPipelineLifeCycle
  (start [this config]
    (when (= :pending (:status @state))
      (ml/log :collet/starting-pipeline-execution :tasks (:tasks-queue @state))
      (swap! state assoc :status :running)
      (run-worker this config)))

  (stop [this]
    (ml/log :collet/stopping-pipeline-execution)
    (when-let [worker (:worker @state)]
      (future-cancel worker)
      (swap! state assoc :worker nil))
    (swap! state assoc :status :stopped))

  (pause [this]
    (when (= :running (:status @state))
      (ml/log :collet/pausing-pipeline-execution)
      (when-let [worker (:worker @state)]
        (future-cancel worker)
        (swap! state assoc :worker nil))
      (swap! state assoc :status :paused)))

  (resume [this config]
    (when (contains? #{:paused :failed} (:status @state))
      (ml/log :collet/resuming-pipeline-execution)
      (swap! state assoc :status :running)
      (run-worker this config))))


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
  [{:keys [name use-arrow tasks deps]
    :or   {use-arrow true}
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
                          (map (fn [task]
                                 (let [task-fn (compile-task eval-context task)]
                                   (-> task
                                       (merge (::task (meta task-fn)))
                                       (assoc ::task-fn task-fn)))))
                          (index-by :name))
        state        (atom {:status      :pending
                            :tasks-queue (->tasks-queue tasks-map)})]
    (->Pipeline pipeline-id name tasks-map state {:use-arrow use-arrow})))
