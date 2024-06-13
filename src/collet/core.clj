(ns collet.core
  (:require [clojure.walk :as walk]))


(def params-spec
  [:maybe [:vector :any]])


(def context-spec
  [:map
   [:config :map]
   [:state :map]])


(def action-spec
  [:map
   [:name :keyword]
   [:type :keyword]
   [:params {:optional true} params-spec]
   [:fn {:optional true} fn?]])


(defn compile-action-params
  "Prepare the action parameters by evaluating the config values.
   Takes the action spec and the context and returns the evaluated parameters.
   Clojure symbols used as parameter value placeholders. If the same symbol is found in the parameters map
   and as the selectors key it will be replaced with the corresponding value from the context."
  {:malli/schema [:=> [:cat action-spec context-spec]
                  params-spec]}
  [{:keys [params selectors]} context]
  (walk/postwalk
   (fn [x]
     (if (and (symbol? x) (contains? selectors x))
       (let [selector-path (get selectors x)]
         (get-in context selector-path))
       x))
   params))


(defn compile-action
  "Compiles an action spec into a function.
   Resulting function should be executed with a workflow context (configuration and current state).
   Action can be a producer or a consumer of data, depending on the action type."
  {:malli/schema [:=> [:cat action-spec]
                  [:=> [:cat context-spec] :any]]}
  [{:keys [type] :as action-spec}]
  (let [action-fn (cond
                    (and (qualified-keyword? type) (= (namespace type) "clj"))
                    (-> (name type) symbol resolve)

                    (= type :custom)
                    (:fn action-spec)

                    :otherwise
                    (throw (ex-info (str "Unknown action type: " type) {:spec action-spec})))]
    (fn [context]
      (let [action-params (compile-action-params action-spec context)]
        (apply action-fn action-params)))))


(def workflow-spec
  [:map
   [:actions [:vector action-spec]]
   [:iterator [:map
               [:type :string]
               [:conditions [:vector :any]]]]])


(defn workflow
  "Compiles a workflow spec into a function.
   Resulting function can be executed with a configuration map,
   representing a single run of all actions attached to it.
   Actions should run in the order they are defined in the spec."
  {:malli/schema [:=> [:cat workflow-spec]
                  [:=> [:cat map?] [:sequential :any]]]}
  [spec]
  (fn [config]
    (println "Executing workflow")))
