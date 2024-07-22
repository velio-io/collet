(ns collet.core
  (:require
   [clojure.walk :as walk]
   [malli.core :as m]
   [malli.dev.pretty :as pretty]
   [malli.error :as me]
   [malli.util :as mu]
   [weavejester.dependency :as dep]
   [diehard.core :as dh]
   [collet.actions.http :as collet.http]
   [collet.actions.counter :as collet.counter]
   [collet.actions.slicer :as collet.slicer]
   [collet.actions.jdbc :as collet.jdbc]
   [collet.conditions :as collet.conds]
   [collet.select :as collet.select]
   [collet.deps :as collet.deps])
  (:import
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
   [:fn {:optional true} fn?]
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
           (collet.select/select selector-path context))
         ;; x could a function call, try to evaluate the form
         (and (list? x) (symbol? (first x)))
         (try (eval x) (catch Exception _ x))
         ;; return as is
         :otherwise x))
     params)))


(def actions-map
  {:http    collet.http/request-action
   :counter collet.counter/counter-action
   :slicer  collet.slicer/slicer-action
   :jdbc    collet.jdbc/action
   :odata   {:action (fn [])}
   :format  {:action (fn [])}})


(defn compile-action
  "Compiles an action spec into a function.
   Resulting function should be executed with a task context (configuration and current state).
   Action can be a producer or a consumer of data, depending on the action type."
  {:malli/schema [:=> [:cat action-spec]
                  [:=> [:cat context-spec] :any]]}
  [{:keys [return] action-type :type action-name :name :as action-spec}]
  (let [predefined-action? (contains? actions-map action-type)
        action-fn          (cond
                             ;; Clojure core functions
                             (and (qualified-keyword? action-type)
                                  (= (namespace action-type) "clj"))
                             (-> (name action-type) symbol resolve)
                             ;; Custom functions
                             (= action-type :custom)
                             (:fn action-spec)
                             ;; Predefined actions
                             predefined-action?
                             (get-in actions-map [action-type :action])
                             ;; Unknown action type
                             :otherwise
                             (throw (ex-info (str "Unknown action type: " action-type)
                                             {:spec action-spec})))
        action-spec        (if-let [prep-fn (and predefined-action?
                                                 (get-in actions-map [action-type :prep]))]
                             (prep-fn action-spec)
                             action-spec)]
    (fn [context]
      (try
        (let [params (compile-action-params action-spec context)
              result (cond
                       (vector? params) (apply action-fn params)
                       (map? params) (action-fn params)
                       (nil? params) (action-fn))]
          (cond->> result
            (some? return) (collet.select/select return)
            :always (assoc-in context [:state action-name])))
        (catch Exception e
          (throw (ex-info "Action failed" (merge (ex-data e) {:action action-name}) e)))))))


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


(defn compile-task
  "Compiles a task spec into a function.
   Resulting function can be executed with a configuration map,
   representing a single run of all actions attached to it.
   Actions should run in the order they are defined in the spec."
  {:malli/schema [:=> [:cat task-spec]
                  [:=> [:cat context-spec] [:sequential :any]]]}
  [{:keys [name setup actions iterator retry skip-on-error] :as task}]
  (let [setup-actions   (mapv compile-action setup)
        task-actions    (mapv compile-action actions)
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
                                              :backoff-ms  backoff-ms}
                                (execute-actions task-actions ctx))
                              ;; execute without retry
                              (execute-actions task-actions ctx))
                            (catch Exception e
                              (if (not skip-on-error)
                                (throw (ex-info "Task failed" (merge (ex-data e) {:task name}) e))
                                ;; returns the context from previous iteration
                                ;; maybe we should detect a throwing action and preserve values from other actions
                                ctx))))]
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


(defn compile-pipeline
  "Compiles a pipeline spec into a function.
   Resulting function can be executed with a configuration map
   Flat list of tasks is compiled into a graph according to the dependencies.
   Tasks are then executed in the topological order."
  {:malli/schema [:=> [:cat pipeline-spec]
                  [:=> [:cat map?] map?]]}
  [{:keys [tasks deps] :as pipeline}]
  ;; validate pipeline spec first
  (when-not (m/validate pipeline-spec pipeline)
    (pretty/explain pipeline-spec pipeline)
    (->> (m/explain pipeline-spec pipeline)
         (me/humanize)
         (ex-info "Invalid pipeline spec.")
         (throw)))

  (when (some? deps)
    (collet.deps/add-dependencies deps))

  (let [tasks-map   (->> tasks
                         (map (fn [task]
                                (assoc task ::task-fn (compile-task task))))
                         (index-by :name))
        pipe-graph  (reduce-kv
                     (fn [graph task-key {:keys [inputs]}]
                       (add-task-and-deps graph task-key inputs))
                     (dep/graph)
                     tasks-map)
        tasks-order (->> pipe-graph
                         (dep/topo-sort)
                         ;; first task should be a ::root node
                         (rest))
        leafs       (->> tasks-order
                         (filter (fn [node]
                                   (empty? (dep/immediate-dependents pipe-graph node))))
                         (vec))]
    (fn [config]
      (try
        (let [result (reduce
                      (fn [tasks-state task-key]
                        (let [{::keys [task-fn] :keys [inputs]} (get tasks-map task-key)
                              inputs-map (reduce (fn [is i] (assoc is i (get tasks-state i))) {} inputs)
                              context    (-> (->context config)
                                             (assoc :inputs inputs-map))]
                          (->> (task-fn context)
                               (seq)
                               (assoc tasks-state task-key))))
                      {} tasks-order)]
          (doseq [leaf leafs]
            ;; trigger the execution of the leaf task
            (dorun (get result leaf)))
          ;; return pipeline state, mostly for debugging purposes
          result)
        (catch Throwable e
          ;; TODO do we need to continue pipeline execution in case of an error?
          ;; let users to decide if pipeline should stop on error
          (let [{:keys [task]} (ex-data e)
                original-error (->> (iterate ex-cause e)
                                    (take-while identity)
                                    (last)
                                    (ex-message))]
            (println "Pipeline error:" original-error)
            (println "Stopped on task:" task)
            (throw e)))))))
