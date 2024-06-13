(ns collet.core)


(def params-spec
  [:maybe [:vector :any]])


(defn compile-action-params
  "Prepare the action parameters by evaluating the config values."
  {:malli/schema [:=> [:cat params-spec :map]
                  params-spec]}
  [params config]
  params)


(def action-spec
  [:map
   [:name :keyword]
   [:type :keyword]
   [:params {:optional true} params-spec]])


(defn compile-action
  "Compiles an action spec into a function.
   Resulting function should be executed with a workflow configuration (context).
   Action can be a producer or a consumer of data, depending on the action type."
  {:malli/schema [:=> [:cat action-spec]
                  [:=> [:cat map?] :any]]}
  [{:keys [type params] :as action-spec}]
  (let [action-fn (cond
                    (and (qualified-keyword? type) (= (namespace type) "clj"))
                    (-> (name type) symbol resolve)

                    (= type :custom)
                    (:fn action-spec)

                    :otherwise
                    (throw (ex-info (str "Unknown action type: " type) {:spec action-spec})))]
    (fn [config]
      (let [action-params (compile-action-params params config)]
        (apply action-fn action-params)))))


(def workflow-spec
  [:map
   [:actions [:vector action-spec]]
   [:iterator [:map
               [:type :string]
               [:conditions [:vector :any]]]]])


(defn workflow
  "Compiles a workflow spec into a function.
   Resulting function should be executed with a specific configuration (context).
   Workflow function should run actions in the order they are defined in the spec,
   returning a lazy sequence of data maps produced by the actions"
  {:malli/schema [:=> [:cat workflow-spec]
                  [:=> [:cat map?] [:sequential :any]]]}
  [spec]
  (fn [config]
    (println "Executing workflow")))
