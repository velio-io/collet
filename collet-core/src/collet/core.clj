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
   [collet.actions.stats]
   [collet.actions.fold]
   [collet.actions.enrich]
   [collet.actions.mapper]
   [collet.actions.switch]
   [collet.conditions :as collet.conds]
   [collet.select :as collet.select]
   [collet.deps :as collet.deps]
   [collet.arrow :as collet.arrow]
   [collet.artifact :as artifact]
   [collet.durable :as durable]
   [collet.store :as store]
   [collet.store.datalevin :as datalevin])
  (:import
    [clojure.lang IDeref ILookup]
    [java.io File]
    [java.util.regex Pattern]
    [java.util.concurrent Callable ExecutorService Executors Future FutureTask Semaphore
     TimeUnit]
    [weavejester.dependency MapDependencyGraph]))


(def context-spec
  [:map
   [:config map?]
   [:state [:map-of :keyword :any]]
   [:inputs {:optional true}
    [:map-of :keyword :any]]
   [:store {:optional true}
    [:fn #(satisfies? store/Store %)]]])


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
   [:fn {:optional true} [:or fn? list? symbol? [:fn var?]]]
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
  {:malli/schema [:=>
                  [:cat (mu/select-keys action-spec [:params :selectors])
                   context-spec utils/eval-context-spec]
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
                                (cond
                                  (list? func)
                                  (utils/eval-form eval-context func)

                                  (symbol? func)
                                  (or (requiring-resolve func)
                                      (throw
                                       (ex-info "Custom function cannot be resolved."
                                                {:function func})))

                                  (var? func)
                                  @func

                                  :else
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
              (ml/trace :collet/executing-action
                [:action action-name :type action-type]
                (if (= action-type :switch)
                  (let [context' (action-fn context)]
                    (update context :state merge (:state context')))

                  (let [params  (compile-action-params action-spec
                                                       context
                                                       eval-context)
                        result  (cond
                                  ;; multiple parameters passed
                                  (vector? params) (apply action-fn
                                                          params)
                                  ;; single map parameter
                                  (map? params) (action-fn params)
                                  ;; no parameters
                                  (nil? params) (action-fn))
                        result' (if (some? return)
                                  (collet.select/select return result)
                                  result)]
                    (tap> {:action action-name
                           :type   action-type
                           :params params
                           :result result'})
                    (assoc-in context [:state action-name] result'))))
              (do (ml/log :collet/action-skipped
                          :action action-name
                          :type action-type)
                  ;; need to reset action state to prevent discrepancies
                  ;; between iterations
                  (when-not (:keep-state action-spec)
                    (assoc-in context [:state action-name] nil))))
            (catch Exception e
              (throw (ex-info "Action failed"
                              (-> (merge (ex-data e)
                                         {:action action-name
                                          :params (compile-action-params
                                                   action-spec
                                                   context
                                                   eval-context)})
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
    [:arrow-columns {:optional true} [:or map? vector?]]
    [:state-format {:optional true} [:enum :latest :flatten]]
    [:setup {:optional true}
     [:vector action-spec]]
    [:actions
     [:vector {:min 1} action-spec]]
    [:return
     {:optional true
      :description
      "specifies how to get the data when all actions are executed"}
     [:or collet.select/select-path fn? list? symbol? [:fn var?]]]
    [:iterator {:optional true}
     [:map
      [:next
       {:description
        "answers on the question should we iterate over task actions again"}
       [:or collet.conds/condition? :boolean]]]]
    [:parallel {:optional true}
     [:and
      [:map
       [:items {:optional true} collet.select/select-path]
       [:range {:optional true}
        [:map
         [:end :int]
         [:start {:optional true} :int]
         [:step {:optional true} :int]]]
       [:threads {:optional true} :int]]
      [:fn
       {:error/message
        "either :items or :range should be specified but not both"}
       (fn [{:keys [items range]}]
         (if (or items range)
           (not (and items range))
           true))]]]]
   [:fn
    {:error/message
     "either :iterator or :parallel should be specified but not both"}
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
  {:malli/schema [:=> [:cat utils/eval-context-spec task-spec]
                  [:=> [:cat context-spec] :any]]}
  [eval-context {:keys [actions return]}]
  (let [last-action (-> actions
                        last
                        :name)]
    (cond
      (nil? return) (fn [context] (get-in context [:state last-action]))
      (vector? return) (fn [context] (collet.select/select return context))
      (list? return) (utils/eval-form eval-context return)
      (symbol? return) (or (requiring-resolve return)
                           (throw
                            (ex-info "Task return function cannot be resolved."
                                     {:function return})))
      (var? return) @return
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


(defn- rehydrate-value
  [value]
  (cond
    (and (map? value)
         (= :regex (:collet.runtime/type value))
         (string? (:pattern value))
         (int? (:flags value)))
    (Pattern/compile (:pattern value) (:flags value))

    (map? value)
    (reduce-kv (fn [result key item]
                 (assoc result key (rehydrate-value item)))
               {}
               value)

    (vector? value)
    (mapv rehydrate-value value)

    (list? value)
    (apply list (map rehydrate-value value))

    (set? value)
    (into #{} (map rehydrate-value) value)

    :else
    value))


(defn read-action
  "Read the action from EDN file if it exists"
  [path-key]
  (let [sep       File/separator
        file-path (str (string/replace (namespace path-key) "." sep)
                       sep
                       (name path-key))
        file      (io/as-file file-path)]
    (if (.exists file)
      (->> file
           slurp
           (edn/read-string {:eof nil :readers {'rgx read-regex}}))
      (throw (ex-info "File does not exist" {:file file-path})))))


(defn replace-external-actions
  "Replace the actions which refers to external files"
  [actions]
  (mapv
   (fn [action]
     (if (= (:type action) :switch)
       (assoc action
         :case (mapv #(update % :actions replace-external-actions)
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
               t
               (->> action
                    :case
                    (mapcat :actions)))
       (collet.action/expand t action)))
   task
   actions))


(defn- prepare-task-plan
  [task]
  (cond-> task
    :always (update :actions replace-external-actions)
    (some? (:setup task)) (update :setup replace-external-actions)
    :always (expand-on-actions)
    (some? (:parallel task))
    (utils/replace-all {:$parallel/item [:state :$parallel/item]})))


(defrecord Task
  [name          ;; task name
   spec          ;; full task spec (after processing)
   inputs        ;; set of task-ids that must be completed first
   skip-on-error ;; whether to skip the task if a dependency fails
   keep-state    ;; whether to keep the result in the context
   state-format  ;; one of :latest, :flatten. default (if not set) is left
   ;; as is
   task-fn       ;; (fn []) actual work to do
   status        ;; one of :waiting, :running, :completed, :failed,
   ;; :skipped, :interrupted
   result        ;; the result of (run-fn) here
   error])


;; exception if any


(def task?
  (m/-simple-schema
   {:type :task?
    :pred #(instance? Task %)
    :type-properties
    {:error/message "should be an instance of Task"}}))


(defn- compile-prepared-task
  [eval-context task]
  (let [{:keys [name setup actions iterator parallel retry
                skip-on-error inputs keep-state state-format]
         :as   task} task

        {:keys [max-retries backoff-ms]
         :or   {max-retries 2
                backoff-ms  [200 3000]}} retry

        compile-action-ctx (partial compile-action eval-context)
        setup-actions (flatten (map compile-action-ctx setup))
        task-actions (flatten (map compile-action-ctx actions))
        extract-data (extract-data-fn eval-context task)

        task-exec-fn
        (fn execute-task [ctx]
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
              (throw
               (ex-info "Task failed" (merge (ex-data e) {:task name}) e)))))

        task-fn
        (cond
          (some? parallel)
          (fn [context]
            ;; run actions to set up the task
            (let [context' (cond->> context
                             (seq setup-actions) (execute-actions
                                                  setup-actions))
                  items (if (some? (:range parallel))
                          (let [{:keys [start end step]
                                 :or   {start 0 step 1}} (:range parallel)]
                            (range start end step))
                          (collet.select/select (:items parallel) context'))
                  executor (Executors/newVirtualThreadPerTaskExecutor)
                  semaphore (Semaphore. (or (:threads parallel) 10))

                  submit-task
                  (fn [arrow-columns item]
                    (.submit executor
                             ^Callable
                             (fn []
                               ; Block if limit is reached
                               (.acquire semaphore)
                               (try
                                 (let [item (if (some? arrow-columns)
                                              (collet.arrow/prep-record
                                               item
                                               arrow-columns)
                                              item)]
                                   (-> context'
                                       (assoc-in [:state :$parallel/item]
                                                 item)
                                       (task-exec-fn)
                                       (extract-data)))
                                 (finally
                                  ; Release permit
                                  (.release semaphore))))))

                  [items' arrow-columns] (cond
                                           (ds/dataset? items) [(ds/rows items) nil]
                                           (utils/ds-seq? items) [(mapcat ds/rows items)
                                                                  (-> items
                                                                      meta
                                                                      :arrow-columns)]
                                           :otherwise [items nil])
                  futures (doall (map (partial submit-task arrow-columns)
                                      items'))]
              (try
                ;; Collect results in original order by dereferencing
                ;; futures
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
                               (seq setup-actions) (execute-actions
                                                    setup-actions))]
                (-> (iteration task-exec-fn
                               :initk context'
                               :vf extract-data
                               :kf next-iteration)
                    (with-meta {:iteration true})))))

          :otherwise
          (fn [context]
            (let [context' (cond->> context
                             (seq setup-actions) (execute-actions
                                                  setup-actions))]
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


(defn compile-task
  "Compiles a task spec into a function.
   Resulting function can be executed with a configuration map,
   representing a single run of all actions attached to it.
   Actions should run in the order they are defined in the spec."
  {:malli/schema [:=> [:cat utils/eval-context-spec task-spec]
                  task?]}
  [eval-context task]
  (compile-prepared-task eval-context (prepare-task-plan task)))


(defn find-task
  [spec task]
  (->> (:tasks spec)
       (utils/find-first #(= (:name %) task))))


(defn list-tasks
  [spec]
  (->> (:tasks spec)
       (mapv :name)))


(def first-from-seq
  (comp first seq))


(defn execute-task
  ([task config]
   (execute-task task config {} nil))

  ([task config context]
   (execute-task task config context nil))

  ([task config context deps]
   (let [{:keys [task-fn inputs state-format]}
         (compile-task (utils/eval-ctx (:requires deps) (:imports deps)) task)
         inputs       (reduce
                       (fn [is i]
                         (let [input-data (get-in context [:state i])]
                           (assoc is i input-data)))
                       {}
                       inputs)
         task-context (merge (->context config) context {:inputs inputs})
         result       (task-fn task-context)]
     (cond-> result
       (-> result meta :iteration) (first-from-seq)
       (= state-format :latest) (last)
       (= state-format :flatten) (flatten)))))


;;------------------------------------------------------------------------------
;; Pipeline
;;------------------------------------------------------------------------------


(def pipeline-spec
  [:map
   [:name :keyword]
   [:version {:optional true}
    [:and :int [:fn pos-int?]]]
   [:use-arrow {:optional true} :boolean]
   [:max-parallelism {:optional true}
    [:and :int [:fn pos-int?]]]
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
  {:malli/schema
   [:=> [:cat graph? :keyword [:maybe (mu/get-in task-spec [0 :inputs])]]
    graph?]}
  [graph task-key inputs]
  (if (seq inputs)
    (reduce
     (fn [g input]
       (dep/depend g task-key input))
     graph
     inputs)
    ;; add node without dependencies
    (dep/depend graph task-key ::root)))


(defn ->pipeline-graph
  "Creates a dependency graph from the tasks map."
  {:malli/schema [:=> [:cat [:map-of :keyword [:or task? task-spec]]]
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
  [^ArrowTaskResult arrow-task-result]
  (collet.arrow/read-dataset
   (.-file arrow-task-result)
   (.-columns arrow-task-result)))


(def ^:private arrow-batch-size 4096)


(defn- batch-candidate?
  [batch]
  (or (ds/dataset? batch)
      (sequential? batch)))


(defn- nonempty-batch?
  [batch]
  (and (batch-candidate? batch)
       (pos? (collet.arrow/get-batch-size batch))))


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
      (collet.arrow/normalize-schema explicit-schema)
      (let [records (if batches?
                      (mapcat #(if (ds/dataset? %) (ds/rows %) %) data)
                      data)
            inference-candidate?
            (if-some [record (first (remove nil? (take 200 records)))]
              (map? record)
              true)]
        (when inference-candidate?
          (collet.arrow/infer-schema! records))))))


(defn- write-arrow-result!
  [task-name data schema batches?]
  (let [file ^File (File/createTempFile (name task-name) ".arrow")]
    (.deleteOnExit file)
    (with-open [writer (collet.arrow/make-writer file schema)]
      (if batches?
        (doseq [batch data
                :when (nonempty-batch? batch)]
          (collet.arrow/write writer batch))
        (doseq [batch (partition-all arrow-batch-size data)
                :when (nonempty-batch? batch)]
          (collet.arrow/write writer batch))))
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
  ([{:keys [store data-dir artifact-dir
            on-task-start on-task-complete on-task-error on-task-skipped]}]
   (let [explicit-store? (some? store)
         layout          (when data-dir
                           (artifact/data-layout data-dir))
         artifact-dir    (artifact/ensure-artifact-dir!
                          (or artifact-dir
                              (:artifact-dir layout)
                              (when explicit-store?
                                "./.collet/artifacts")
                              (:artifact-dir (artifact/data-layout "./.collet"))))]
     (->Context (or store
                    (let [layout (or layout (artifact/data-layout "./.collet"))]
                      (datalevin/store {:dir (str (:db-dir layout))})))
                artifact-dir
                (Executors/newVirtualThreadPerTaskExecutor)
                (atom {})
                (atom false)
                on-task-start
                on-task-complete
                on-task-error
                on-task-skipped))))


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
      (:action data) (assoc :action (:action data)))))


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


(defn- deliver-run!
  [ctx run-id run]
  (when-let [runtime (get @(:runs ctx) run-id)]
    (swap! (:runs ctx)
      update
      run-id
      #(select-keys % completed-runtime-keys))
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
                                    .getName)}]
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


(defn- write-dataset-output!
  [task-name data schema-override]
  (let [source data
        data   (if (ds/dataset? data) [data] data)
        {:keys [batches? first-batch]} (result-layout data)
        schema (or (some-> schema-override
                           collet.arrow/normalize-schema)
                   (result-schema data batches? first-batch))]
    (when (skip-arrow-conversion? source)
      (non-durable-output!
       "A retained record dataset cannot skip Arrow conversion."
       {:task task-name}))
    (when-not schema
      (non-durable-output!
       "A retained record dataset needs an explicit :arrow-columns schema."
       {:task task-name}))
    (write-arrow-result! task-name data schema batches?)))


(defn- format-task-output
  [runtime task result]
  (let [result             (if (-> result
                                   meta
                                   :iteration)
                             (seq result)
                             result)
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
          (let [arrow-result (write-dataset-output! (:name task)
                                                    formatted
                                                    schema-override)]
            (cond-> {:output/kind   :dataset
                     :output/file   (.-file ^ArrowTaskResult arrow-result)
                     :output/schema (.-columns ^ArrowTaskResult arrow-result)}
              ;; Preserve the existing in-process surface for actions that
              ;; naturally return one TMD dataset. It is cached only after the
              ;; durable completion transaction below; task inputs always read
              ;; the artifact instead.
              (ds/dataset? formatted)
              (assoc :output/live-value formatted)))
          {:output/kind  :scalar
           :output/value (handle-task-result (:name task)
                                             formatted
                                             {:use-arrow   false
                                              :keep-result true})}))
      {:output/kind  :transient
       :output/value (if sequential-result?
                       (doall formatted)
                       formatted)})))


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
  (let [runtime   (get @(:runs ctx) run-id)
        task-name (:name task)
        config    (:config runtime)
        outcome   (try
                    (let [input-refs (input-output-refs ctx run-id task)
                          task-ctx   (-> (->context config)
                                         (assoc :inputs (task-inputs ctx
                                                                     task
                                                                     input-refs)
                                                :store (:store ctx)))]
                      {:output (->> ((:task-fn task) task-ctx)
                                    (format-task-output runtime task))})
                    (catch Throwable error
                      {:error error}))]
    (try
      (if-let [error (:error outcome)]
        (handle-task-error! ctx run-id task error)
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
                                        :task/outcome     :computed
                                        :task/finished-at (System/currentTimeMillis)}
                                 publication
                                 (merge publication)))]
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
       (swap! (:task-futures runtime) dissoc task-name)))))


(defn- start-ready-task!
  [ctx run-id task]
  (let [runtime (get @(:runs ctx) run-id)
        task'   (store/update-task! (:store ctx)
                                    (:task/id task)
                                    {:task/status :running
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


(declare check-dependencies)


(defn- runtime-tasks
  [pipeline]
  (let [pipeline             (rehydrate-value pipeline)
        {:keys [tasks deps]} pipeline]
    (check-dependencies deps tasks)
    (let [eval-context (utils/eval-ctx (:requires deps) (:imports deps))
          tasks-map    (->> tasks
                            (map #(compile-prepared-task eval-context %))
                            (index-by :name))]
      {:pipeline pipeline
       :tasks    tasks-map
       :graph    (->pipeline-graph tasks-map)})))


(defn start
  "Persists and starts a new run of an immutable compiled pipeline."
  [ctx pipeline config]
  (ensure-open-context! ctx)
  (store/save-pipeline! (:store ctx) pipeline)
  (let [run-id     (random-uuid)
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
        runtime    (merge (runtime-tasks pipeline)
                          {:config       config
                           :results      (atom {})
                           :task-futures (atom {})
                           :scheduler    (atom nil)
                           :completion   (promise)
                           :halted?      (atom false)})
        run-handle (Run. ctx run-id)]
    (store/create-run! (:store ctx) run task-runs)
    (swap! (:runs ctx) assoc run-id runtime)
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
      (store/update-run! (:store ctx) id {:run/status :paused}))
    run))


(defn resume
  [run]
  (let [ctx (run-context run)
        id  (run-id run)]
    (when (= :paused (:run/status (store/get-run (:store ctx) id)))
      (store/update-run! (:store ctx) id {:run/status :running}))
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


(defn extract-actions-types
  "Extracts the action types from the task"
  [{:keys [actions]}]
  (->> actions
       (map (fn [{:keys [type] :as action}]
              ;; enrich is a special case, actual action type specified
              ;; under the :action key
              (cond (= type :enrich) (:action action)
                    (= type :switch) (->> (:case action)
                                          (map extract-actions-types))
                    :otherwise type)))
       (flatten)))


(def tasks->actions-namespaces-xf
  (comp (mapcat extract-actions-types)
        (filter (fn [action-type]
                  (let [action-ns (namespace action-type)]
                    (and (some? action-ns)
                         ;; clj namespace is reserved for clojure core
                         ;; functions
                         (not= action-ns "clj")
                         (not (string/ends-with? (name action-type) ".edn"))))))
        (map #(-> %
                  namespace
                  symbol))
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


(defn- non-durable-value!
  [path value]
  (throw
   (ex-info
    "Pipeline contains a value that cannot be stored durably."
    {:collet.error/type :collet.error/non-durable-value
     :path              path
     :value-type        (some-> value
                                class
                                .getName)})))


(defn- durable-value
  ([value]
   (durable-value [] value))

  ([path value]
   (durable/value
    path
    value
    {:extension?       #(or (var? %)
                            (instance? Pattern %))
     :encode-extension (fn [_ value]
                         (if (var? value)
                           (let [{:keys [ns name]} (meta value)]
                             (symbol (str (ns-name ns)) (str name)))
                           {:collet.runtime/type :regex
                            :pattern             (.pattern ^Pattern value)
                            :flags               (.flags ^Pattern value)}))
     :unsupported!     non-durable-value!})))


(defn- validate-code-value!
  [path value]
  (when-not (or (list? value)
                (and (symbol? value) (qualified-symbol? value)))
    (throw
     (ex-info
      "Durable custom code must be a quoted function form, a fully-qualified symbol, or a Var."
      {:collet.error/type :collet.error/non-durable-value
       :path              path
       :value-type        (some-> value
                                  class
                                  .getName)}))))


(defn- validate-action-code!
  [path action]
  (when (contains? action :fn)
    (validate-code-value! (conj path :fn) (:fn action)))
  (when (= :switch (:type action))
    (doseq [[case-index switch-case]     (map-indexed vector (:case action))
            [action-index nested-action] (map-indexed vector (:actions switch-case))]
      (validate-action-code!
       (conj path :case case-index :actions action-index)
       nested-action))))


(defn- invalid-task-graph!
  [problem data]
  (throw
   (ex-info
    "Invalid pipeline task dependencies."
    (assoc data
      :collet.error/type :collet.error/invalid-task-graph
      :problem problem))))


(defn- validate-task-dependencies!
  [tasks]
  (let [task-names  (mapv :name tasks)
        known-names (set task-names)]
    (when-let [duplicate (->> task-names
                              frequencies
                              (some (fn [[task-name count]]
                                      (when (< 1 count)
                                        task-name))))]
      (invalid-task-graph! :duplicate-task-name {:task duplicate}))

    (doseq [{:keys [name inputs]} tasks
            input inputs]
      (when-not (contains? known-names input)
        (invalid-task-graph! :missing-input {:task name :input input})))

    (try
      (->pipeline-graph (index-by :name tasks))
      (catch Throwable error
        (invalid-task-graph! :cycle {:cause (ex-message error)})))))


(defn compile-pipeline
  "Validates and expands a pipeline into immutable data suitable for durable storage."
  {:malli/schema [:=> [:cat pipeline-spec] map?]}
  [{:keys [tasks deps] :as pipeline}]

  (when-not (m/validate pipeline-spec pipeline)
    (pretty/explain pipeline-spec pipeline)
    (->> (m/explain pipeline-spec pipeline)
         (me/humanize)
         (ex-info "Invalid pipeline spec.")
         (throw)))

  (check-dependencies deps tasks)
  (let [tasks    (mapv prepare-task-plan tasks)
        pipeline (-> pipeline
                     (assoc :version (or (:version pipeline) 1)
                            :use-arrow (get pipeline :use-arrow true)
                            :max-parallelism (get pipeline :max-parallelism 10)
                            :tasks tasks)
                     (durable-value))]
    (doseq [[task-index task]     (map-indexed vector (:tasks pipeline))
            section               [:setup :actions]
            [action-index action] (map-indexed vector (get task section))]
      (validate-action-code! [:tasks task-index section action-index] action))
    (doseq [[task-index task] (map-indexed vector (:tasks pipeline))]
      (when (and (symbol? (:return task))
                 (not (qualified-symbol? (:return task))))
        (validate-code-value! [:tasks task-index :return] (:return task))))
    (validate-task-dependencies! (:tasks pipeline))
    pipeline))
