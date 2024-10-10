(ns collet.core
  (:require
   [clojure.walk :as walk]
   [malli.core :as m]
   [malli.dev.pretty :as pretty]
   [malli.error :as me]
   [malli.util :as mu]
   [weavejester.dependency :as dep]
   [diehard.core :as dh]
   [com.brunobonacci.mulog :as ml]
   [collet.actions.http :as collet.http]
   [collet.actions.odata :as collet.odata]
   [collet.actions.counter :as collet.counter]
   [collet.actions.slicer :as collet.slicer]
   [collet.actions.jdbc :as collet.jdbc]
   [collet.actions.file :as collet.file]
   [collet.actions.queue :as collet.queue]
   [collet.actions.fold :as collet.fold]
   [collet.actions.enrich :as collet.enrich]
   [collet.conditions :as collet.conds]
   [collet.select :as collet.select]
   [collet.deps :as collet.deps])
  (:import
   [clojure.lang IFn ILookup]
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
   [:params {:optional true}
    [:or map? [:vector :any]]]
   [:selectors {:optional true}
    [:map-of :symbol collet.select/select-path]]
   [:fn {:optional true} [:or fn? list?]]
   [:return {:optional true}
    collet.select/select-path]])


(defn compile-action-params
  "Prepare the action parameters by evaluating the config values.
   Takes the action spec and the context and returns the evaluated parameters.
   Clojure symbols used as parameter value placeholders. If the same symbol is found in the parameters map
   and as the selectors key it will be replaced with the corresponding value from the context."
  {:malli/schema [:=> [:cat (mu/select-keys action-spec [:params :selectors]) context-spec]
                  [:maybe (mu/get action-spec :params)]]}
  [{:keys [params selectors]} context]
  (when (some? params)
    (walk/postwalk
     (fn [x]
       (cond
         ;; replace value with the corresponding value from the context
         (and (symbol? x) (contains? selectors x))
         (let [selector-path (get selectors x)]
           (-> (collet.select/select selector-path context) :value))
         ;; x could a function call, try to evaluate the form
         (and (list? x) (symbol? (first x)))
         (try (eval x) (catch Exception _ x))
         ;; return as is
         :otherwise x))
     params)))


(def actions-map
  {:http    collet.http/request-action
   :oauth2  collet.http/oauth2-action
   :odata   collet.odata/odata-action
   :counter collet.counter/counter-action
   :slicer  collet.slicer/slicer-action
   :jdbc    collet.jdbc/action
   :file    collet.file/write-file-action
   :s3      collet.file/upload-file-action
   :queue   collet.queue/cues-action
   :fold    collet.fold/fold-action
   :enrich  collet.enrich/enrich-action})


(defn compile-action
  "Compiles an action spec into a function.
   Resulting function should be executed with a task context (configuration and current state).
   Action can be a producer or a consumer of data, depending on the action type."
  {:malli/schema [:=> [:cat action-spec]
                  [:=> [:cat context-spec] :any]]}
  [{action-type :type :as action-spec}]
  (let [predefined-action? (contains? actions-map action-type)

        {:keys [return] action-type :type action-name :name :as action-spec}
        (if-let [prep-fn (and predefined-action?
                              (get-in actions-map [action-type :prep]))]
          (prep-fn action-spec)
          action-spec)]
    (if (sequential? action-spec)
      ;; expand to multiple actions
      (mapv compile-action action-spec)
      ;; compile a single action
      (let [action-fn (cond
                        ;; Clojure core functions
                        (and (qualified-keyword? action-type)
                             (= (namespace action-type) "clj"))
                        (-> (name action-type) symbol resolve)
                        ;; Custom functions
                        (= action-type :custom)
                        (let [func (:fn action-spec)]
                          (if (list? func)
                            ;; if the function is a list, evaluate it
                            ;; TODO might be dangerous to eval arbitrary code
                            (eval func)
                            func))
                        ;; Predefined actions
                        predefined-action?
                        (get-in actions-map [action-type :action])
                        ;; Unknown action type
                        :otherwise
                        (throw (ex-info (str "Unknown action type: " action-type)
                                        {:spec action-spec})))]
        (fn [context]
          (try
            (ml/trace :collet/executing-action [:action action-name :type action-type]
              (let [params  (compile-action-params action-spec context)
                    result  (cond
                              ;; multiple parameters passed
                              (vector? params) (apply action-fn params)
                              ;; single map parameter
                              (map? params) (action-fn params)
                              ;; no parameters
                              (nil? params) (action-fn))
                    result' (if (some? return)
                              (-> (collet.select/select return result) :value)
                              result)]
                (assoc-in context [:state action-name] result')))
            (catch Exception e
              (throw (ex-info "Action failed" (merge (ex-data e) {:action action-name}) e)))))))))


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
   [:keep-latest {:optional true} :boolean]
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
      [:maybe [:or [:vector :any] :boolean]]]]]])


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
      (vector? data) (fn [context] (-> (collet.select/select data context) :value))
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


(defn compile-task
  "Compiles a task spec into a function.
   Resulting function can be executed with a configuration map,
   representing a single run of all actions attached to it.
   Actions should run in the order they are defined in the spec."
  {:malli/schema [:=> [:cat task-spec]
                  [:=> [:cat context-spec] [:sequential :any]]]}
  [{:keys [name setup actions iterator retry skip-on-error] :as task}]
  (let [setup-actions   (flatten (map compile-action setup))
        task-actions    (flatten (map compile-action actions))
        extract-data    (extract-data-fn task)
        next-iteration  (next-fn iterator)
        {:keys [max-retires backoff-ms]
         :or   {max-retires 3
                backoff-ms  [200 3000]}} retry
        execute-task-fn (fn execute-task [ctx]
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
    (fn [context]
      ;; run actions to set up the task
      (let [context' (cond->> context
                       (seq setup-actions) (execute-actions setup-actions))]
        (iteration execute-task-fn
                   :initk context'
                   :vf extract-data
                   :kf next-iteration)))))


(def pipeline-spec
  [:map
   [:name :keyword]
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


(deftype Pipeline [id name tasks state]
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
                            (let [{::keys [task-fn] :keys [inputs keep-state keep-latest]} (get tasks task-key)
                                  inputs-map (reduce (fn [is i]
                                                       (assoc is i (get-in @state [:results i])))
                                                     {} inputs)
                                  context    (-> (->context config)
                                                 (assoc :inputs inputs-map))]
                              (let [exec-status (ml/trace :collet/starting-task [:task task-key]
                                                  (try
                                                    (let [task-result-seq (->> (task-fn context)
                                                                               (seq))
                                                          ;; TODO infer keep-latest from the task spec
                                                          task-result     (if keep-latest
                                                                            (take-last 1 task-result-seq)
                                                                            (doall task-result-seq))
                                                          has-dependents? (seq (dep/immediate-dependents pipe-graph task-key))]
                                                      (when (or keep-state has-dependents?)
                                                        (swap! state assoc-in [:results task-key] task-result)))
                                                    ;; update tasks queue
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


(defn compile-pipeline
  "Compiles a pipeline spec into a function.
   Resulting function can be executed with a configuration map
   Flat list of tasks is compiled into a graph according to the dependencies.
   Tasks are then executed in the topological order."
  {:malli/schema [:=> [:cat pipeline-spec]
                  pipeline?]}
  [{:keys [name tasks deps] :as pipeline}]
  ;; validate pipeline spec first
  (when-not (m/validate pipeline-spec pipeline)
    (pretty/explain pipeline-spec pipeline)
    (->> (m/explain pipeline-spec pipeline)
         (me/humanize)
         (ex-info "Invalid pipeline spec.")
         (throw)))

  (when (some? deps)
    (collet.deps/add-dependencies deps))

  (let [pipeline-id (random-uuid)
        tasks-map   (->> tasks
                         (map (fn [task]
                                (assoc task ::task-fn (compile-task task))))
                         (index-by :name))
        state       (atom {:status      :pending
                           :tasks-queue (->tasks-queue tasks-map)})]
    (->Pipeline pipeline-id name tasks-map state)))
