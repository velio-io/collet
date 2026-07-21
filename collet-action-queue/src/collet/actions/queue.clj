(ns collet.actions.queue
  (:require
   [tech.v3.dataset :as ds]
   [cues.queue :as q]
   [collet.action :as action]
   [collet.utils :as utils]))


(def queue-action-spec
  [:map
   [:input
    [:or map?
     utils/dataset?
     [:sequential map?]
     [:sequential utils/dataset?]]]
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
  (cond
    (ds/dataset? input)
    (doseq [message (ds/rows input)]
      (q/write appender message))

    (utils/ds-seq? input)
    (doseq [message (mapcat ds/rows input)]
      (q/write appender message))

    (sequential? input)
    (doseq [message input]
      (q/write appender message))

    :otherwise
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


(defmethod action/action-fn ::enqueue [_]
  write-into-queue)


(defmethod action/prep ::enqueue [action-spec]
  (attach-queue action-spec))
