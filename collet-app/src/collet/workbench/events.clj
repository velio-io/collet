(ns collet.workbench.events
  (:require [com.brunobonacci.mulog.buffer :as rb]
            [com.brunobonacci.mulog.publisher :refer [PPublisher]]))


(defn observation [{:mulog/keys [event-name duration outcome timestamp] :as event}]
  (let [fields  (select-keys event [:run-id :execution-id :task :action :type :invocation-id])
        details (case event-name
                  :collet/action-start {:event "action-start" :level "debug"}
                  :collet/action-skipped {:event "action-skipped" :level "debug"}
                  :collet/executing-action (merge {:durationMs (/ duration 1e6) :timestamp (+ timestamp (quot duration 1000000))}
                                                  (if (= :error outcome)
                                                    {:event "action-failed" :level "error" :message "Action failed. Read execution diagnostics."}
                                                    {:event "action-completed" :level "info"}))
                  :collet/retrying-task {:event "retry" :level "warn" :message "Retrying the task action chain; external effects may repeat."}
                  :collet/run-state {:event "run-state" :level "info" :status (name (:status event))}
                  nil)]
    (when details (merge fields {:timestamp timestamp} details))))


(defn publisher [{:keys [on-events]}]
  (let [buffer (rb/agent-buffer 2048)]
    (reify
     PPublisher
     (agent-buffer [_] buffer)
     (publish-delay [_] 100)
     (publish [_ current]
       (on-events (map second (rb/items current)))
       (rb/clear current)))))
