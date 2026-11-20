(ns collet.store)


(defprotocol DraftStore
  "Durable raw authoring source. Revisions are immutable; writes compare expected revisions."
  (create-draft! [store draft])
  (list-drafts [store limit])
  (get-draft [store id]
             [store id revision])
  (draft-history [store id limit])
  (update-draft!
   [store id expected-revision source author summary]
   [store id expected-revision source author summary metadata]
   "Return the draft fields asserted by the edit, or {} when the source matches.
    Metadata may include :expected-draft to compare the full prior snapshot.")
  (delete-draft! [store id expected-revision]))


(defprotocol PipelineDeletion
  "Delete selected saved definitions and their terminal run history."
  (delete-pipeline! [store name options]))


;; zprint 1.2.9 repeatedly grows whitespace around protocol method docstrings.
;!zprint {:format :off}
(defprotocol Store
  "Durable pipeline plans and namespaced run/task lifecycle entities."
  (close! [store])
  (save-pipeline! [store ctx pipeline])
  (load-pipeline [store name]
                 [store name version])
  (list-pipelines [store limit]
   "Lists saved revision summaries, newest first.")
  (list-runs [store limit]
   "Lists run summaries, newest first.")
  (create-run! [store run task-runs]
   "Create a run and its tasks, returning the committed run. The run ID must be unused.")
  (get-run [store run-id])
  (get-task-runs [store run-id])
  (update-run! [store run-id changes]
   "Update non-reference run fields and return the run as committed by this write.")
  (update-task! [store task-id changes]
   "Update non-reference task fields and return the task as committed by this write.")
  (complete-task! [store task-id completion])
  (finalize-run! [store run-id run-changes retained-task-ids]
   "Apply non-reference run field changes and release artifacts for tasks outside retained-task-ids.
    Return the updated run and the released artifacts' pre-retraction metadata.")
  (get-task-output [store task-id])
  (get-artifact [store artifact-id])
  (get-lineage [store task-id direction]))


;!zprint {:format :on}
