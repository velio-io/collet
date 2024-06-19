(ns collet.core
  (:require
   [clojure.walk :as walk]
   [malli.util :as mu]
   [weavejester.dependency :as dep]))


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
    [:vector :any]]
   [:selectors {:optional true}
    [:map-of :symbol [:vector :keyword]]]
   [:fn {:optional true} fn?]])


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
       (if (and (symbol? x) (contains? selectors x))
         (let [selector-path (get selectors x)]
           (get-in context selector-path))
         x))
     params)))


(def actions-map
  {:http  (fn [])
   :jdbc  (fn [])
   :odata (fn [])})


(defn compile-action
  "Compiles an action spec into a function.
   Resulting function should be executed with a task context (configuration and current state).
   Action can be a producer or a consumer of data, depending on the action type."
  {:malli/schema [:=> [:cat action-spec]
                  [:=> [:cat context-spec] :any]]}
  [{action-type :type action-name :name :as action-spec}]
  (let [action-fn (cond
                    ;; Clojure core functions
                    (and (qualified-keyword? action-type)
                         (= (namespace action-type) "clj"))
                    (-> (name action-type) symbol resolve)
                    ;; Custom functions
                    (= action-type :custom)
                    (:fn action-spec)
                    ;; Predefined actions
                    (contains? actions-map action-type)
                    (get actions-map action-type)
                    ;; Unknown action type
                    :otherwise
                    (throw (ex-info (str "Unknown action type: " action-type)
                                    {:spec action-spec})))]
    (fn [context]
      (->> (compile-action-params action-spec context)
           (apply action-fn)
           (assoc-in context [:state action-name])))))


(def task-spec
  [:map
   [:name :keyword]
   [:inputs {:optional true}
    [:vector :keyword]]
   [:setup {:optional true}
    [:vector action-spec]]
   [:actions
    [:vector {:min 1} action-spec]]
   [:iterator {:optional true}
    [:map
     [:cat? {:optional true} :boolean]
     [:data {:description "specifies how to get the data"}
      [:maybe [:or [:vector :keyword] fn?]]]
     [:next {:optional    true
             :description "answers on the question should we iterate over task actions again"}
      [:maybe [:vector :keyword]]]]]])


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
      (vector? data) (fn [context] (get-in context data))
      :otherwise data)))


(defn next-fn
  "Returns a function that decides whether to continue iterating based on the context."
  {:malli/schema [:=> [:cat [:maybe (mu/get task-spec :iterator)]]
                  [:=> [:cat context-spec] :any]]}
  [{:keys [data next]}]
  (cond
    ;; if next is not provided, but we want to extract some data
    ;; possibly useful for infinite iterators
    (and (nil? next) (some? data))
    identity
    ;; decide whether to continue based on a specific value in the context
    (vector? next)
    (fn [context]
      (when (some? (get-in context next))
        ;; TODO maybe include found key value in the context
        context))
    ;; don't iterate at all
    :otherwise
    (constantly nil)))


(defn lazy-concat
  "A concat version that is completely lazy and
   does not require to use apply."
  [colls]
  (lazy-seq
   (when-first [c colls]
     (lazy-cat c (lazy-concat (rest colls))))))


(defn compile-task
  "Compiles a task spec into a function.
   Resulting function can be executed with a configuration map,
   representing a single run of all actions attached to it.
   Actions should run in the order they are defined in the spec."
  {:malli/schema [:=> [:cat task-spec]
                  [:=> [:cat context-spec] [:sequential :any]]]}
  [{:keys [setup actions iterator] :as task}]
  (let [setup-actions  (map compile-action setup)
        task-actions   (map compile-action actions)
        extract-data   (extract-data-fn task)
        next-iteration (next-fn iterator)]
    (fn [context]
      ;; run actions to set up the task
      (let [context' (cond->> context
                       (seq setup-actions) (execute-actions setup-actions))
            result   (iteration (partial execute-actions task-actions)
                                :initk context'
                                :vf extract-data
                                :kf next-iteration)]
        (if (:cat? iterator)
          (lazy-concat result)
          result)))))


(def pipeline-spec
  [:map
   [:name :keyword]
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


(defn add-task-deps
  "Adds dependencies to the graph for the given task."
  [graph task-key inputs]
  (reduce
   (fn [g input]
     (dep/depend g task-key input))
   graph inputs))


(defn compile-pipeline
  "Compiles a pipeline spec into a function.
   Resulting function can be executed with a configuration map
   Flat list of tasks is compiled into a graph according to the dependencies.
   Tasks are then executed in the topological order."
  {:malli/schema [:=> [:cat pipeline-spec]
                  [:=> [:cat map?] map?]]}
  [{:keys [tasks]}]
  (let [tasks-map   (->> tasks
                         (map (fn [task]
                                (assoc task ::task-fn (compile-task task))))
                         (index-by :name))
        pipe-graph  (dep/graph)
        tasks-order (->> tasks-map
                         (reduce-kv
                          (fn [graph task-key {:keys [inputs]}]
                            (if (some? inputs)
                              (add-task-deps graph task-key inputs)
                              graph))
                          pipe-graph)
                         (dep/topo-sort))]
    (fn [config]
      (reduce
       (fn [tasks-state task-key]
         (let [{::keys [task-fn] :keys [inputs]} (get tasks-map task-key)
               context (reduce (fn [c i]
                                 (assoc-in c [:inputs i] (get tasks-state i)))
                               (->context config)
                               inputs)]
           (->> (task-fn context)
                (assoc tasks-state task-key))))
       {}
       tasks-order))))
