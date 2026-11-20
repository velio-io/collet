(ns collet.workbench.execution
  "Build process-local executable actions for the inspector and fixture runner."
  (:require [collet.action :as action]
            [collet.core :as collet]
            [collet.runtime :as runtime]
            [collet.utils :as utils]
            [com.brunobonacci.mulog :as ml]))


(defn- sampled-step [eval-context action sample!]
  (let [run (collet/compile-prepared-action eval-context action)]
    (fn [context]
      (let [[next-context value] (run context)]
        (when (and value sample!)
          (try (sample! (cond-> (merge (select-keys (ml/local-context) [:session-id :execution-id]) value)
                          (:collet/sensitive? action) (dissoc :params)))
               (catch Exception _ nil)))
        next-context))))


(defn steps [eval-context specs substitute sample!]
  (letfn [(compile-one [spec]
            (let [action (action/prep (substitute spec))]
              (if (sequential? action)
                (mapcat compile-one action)
                (let [prepared (cond-> action
                                 (= :switch (:type action))
                                 (update :case #(mapv (fn [branch] (update branch :actions compile-chain)) %)))]
                  [(sampled-step eval-context prepared sample!)]))))
          (compile-chain [actions] (vec (mapcat compile-one actions)))]
    (compile-chain specs)))


(defn task [eval-context prepared substitute sample!]
  (collet/executable-task eval-context
                          prepared
                          (steps eval-context (:setup prepared) substitute sample!)
                          (steps eval-context (:actions prepared) substitute sample!)))


(defn start
  "Run private executable tasks against the original saved plan."
  [ctx plan config
   {:keys [substitute sample! trace resolve-coordinates?]
    :or   {substitute identity resolve-coordinates? true}}]
  (let [config               (collet/prepare-config! plan config)
        {:keys [deps tasks]} (collet/rehydrate-value plan)]
    (collet/check-dependencies (cond-> deps (not resolve-coordinates?) (dissoc :coordinates)) tasks)
    (let [eval-context (utils/eval-ctx (:requires deps) (:imports deps))
          tasks-map    (collet/index-by :name (mapv #(task eval-context % substitute sample!) tasks))]
      (ml/with-context trace
                       (runtime/start ctx plan config {:tasks tasks-map :graph (collet/->pipeline-graph tasks-map)})))))
