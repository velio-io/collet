(ns collet.actions.counter
  (:require
   [collet.actions.common :as common]))


(defn do-count
  [{:keys [start step end]
    :or   {start 0 step 1 end Integer/MAX_VALUE}}
   prev-state]
  (if (nil? prev-state)
    start
    (min (+ prev-state step) end)))


(def counter-action
  {:action do-count
   :prep   common/prep-state-ful-action})