(ns collet.action)


;; prepare action for execution
(defmulti prep
  "Prepare or modify the action spec before the execution.
   Should return a valid action spec."
  :type)


(defmethod prep :default [action-spec]
  action-spec)


;; expand action spec on the task
(defmulti expand
  "You can modify the task spec based on the action spec.
   Should return a valid task spec."
  (fn [_task-spec action-spec]
    (:type action-spec)))


(defmethod expand :default [task-spec _action-spec]
  task-spec)


;; main action function
(defmulti action-fn
  "Returns a function that performs the action."
  :type)


(defmethod action-fn :default [action-spec]
  (throw (ex-info (str "Unknown action type: " (:type action-spec))
                  {:spec action-spec})))
