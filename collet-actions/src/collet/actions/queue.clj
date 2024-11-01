(ns collet.actions.queue
  (:require
   [collet.action :as action]
   [cues.queue :as q]))


(def queue-action-spec
  [:map
   [:input [:or map? [:sequential map?]]]
   [:queue-name :keyword]
   [:queue-path {:optional true} :string]
   [:roll-cycle {:optional true}
    [:enum :five-minutely :ten-minutely :twenty-minutely
     :fast-hourly :half-hourly :two-hourly :four-hourly :six-hourly
     :fast-daily :weekly]]])


(defn write-into-queue
  "Writes the input to the queue.
   Input can be a single message or a sequence of messages.
   Message should a Clojure map."
  {:malli/schema [:=> [:cat queue-action-spec]
                  :any]}
  [{::keys [appender] :keys [input]}]
  (if (sequential? input)
    (doseq [message input]
      (q/write appender message))
    (q/write appender input)))


(defn attach-queue
  "Attaches a queue and an appender to the action spec.
   The `queue-name` is required, the `queue-path` and `roll-cycle` are optional.
   `roll-cycle` option can be :twenty-minutely, :six-hourly, :four-hourly, :fast-daily, :ten-minutely, :weekly, :five-minutely, :two-hourly, :half-hourly, :fast-hourly"
  [action-spec]
  (let [{:keys [queue-name queue-path roll-cycle]
         :or   {roll-cycle :fast-daily
                queue-path "tmp/queues"}} (:params action-spec)
        queue    (q/queue queue-name {:queue-path queue-path :roll-cycle roll-cycle})
        appender (q/appender queue)]
    (-> action-spec
        (assoc-in [:params ::queue] queue)
        (assoc-in [:params ::appender] appender))))


(defmethod action/action-fn :queue [_]
  write-into-queue)


(defmethod action/prep :queue [action-spec]
  (attach-queue action-spec))
