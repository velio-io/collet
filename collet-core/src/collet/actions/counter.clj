(ns collet.actions.counter
  (:require
   [collet.actions.common :as common]))


(defn do-count
  "Increment a counter by a step, up to a maximum value.
   Uses the previous state to determine the current state."
  [{:keys [start step end]
    :or   {start 0 step 1 end Integer/MAX_VALUE}}
   prev-state]
  (if (nil? prev-state)
    start
    (min (+ prev-state step) end)))


(def counter-action
  {:action do-count
   :prep   common/prep-stateful-action})