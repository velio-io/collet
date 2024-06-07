(ns collet.core
  (:require
   [malli.dev :as dev]))


(dev/start!)


(def workflow-spec
  [:map
   [:actions [:vector map?]]
   [:iterator [:map
               [:type :string]
               [:conditions :sequential]]]])


(def action-spec
  map?)


(defn compile-action
  "Compiles an action spec into a function.
   Resulting function should be executed with a workflow configuration (context).
   Action can be a producer or a consumer of data, depending on the action type."
  {:malli/schema [:=> [:cat action-spec]
                  [:=> [:cat map?] :any]]}
  [action]
  (fn [config]
    (println "Executing action")))


(defn workflow
  "Compiles a workflow spec into a function.
   Resulting function should be executed with a specific configuration (context).
   Workflow function should run actions in the order they are defined in the spec,
   returning a lazy sequence of data maps produced by the actions"
  {:malli/schema [:=> [:cat workflow-spec]
                  [:=> [:cat map?] :sequential]]}
  [spec]
  (fn [config]
    (println "Executing workflow")))

