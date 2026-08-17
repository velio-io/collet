(ns collet.store)


(defprotocol Store
  "Durable pipeline plans and namespaced run/task lifecycle entities."
  (close! [store])
  (save-pipeline! [store pipeline])
  (load-pipeline [store name]
                 [store name version])
  (create-run! [store run task-runs])
  (get-run [store run-id])
  (get-task-runs [store run-id])
  (update-run! [store run-id changes])
  (update-task! [store task-id changes])
  (complete-task! [store task-id completion])
  (finalize-run! [store run-id run-changes retained-task-ids])
  (get-task-output [store task-id])
  (get-artifact [store artifact-id])
  (get-lineage [store task-id direction]))
