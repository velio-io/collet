(ns collet.workbench.diagnostics
  "Bounded action events and transient aggregates; durable lifecycle summaries live in Store."
  (:require [clojure.string :as str]
            [collet.workbench.data :as data]
            [collet.workbench.wire :as wire]))


(def per-execution-events 1000)


(def per-execution-bytes (* 1024 1024))


(def total-bytes (* 16 1024 1024))


(def levels {"debug" 0 "info" 1 "warn" 2 "error" 3})


(defn state []
  (atom {:sequence 0 :bytes 0 :events (sorted-map) :groups {}}))


(defn- remove-first [state group]
  (let [n    (peek (get-in state [:groups group :sequences]))
        size (get-in state [:events n :bytes] 0)]
    (-> state
        (update :events dissoc n)
        (update :bytes - size)
        (update-in [:groups group :bytes] - size)
        (update-in [:groups group :sequences] pop)
        (update-in [:groups group :dropped] (fnil inc 0))
        (assoc-in [:groups group :lastDroppedSequence] n))))


(defn- trim-events [state group]
  (let [state (loop [state state]
                (let [g (get-in state [:groups group])]
                  (if (or (> (count (:sequences g)) per-execution-events) (> (:bytes g) per-execution-bytes))
                    (recur (remove-first state group))
                    state)))]
    (loop [state state]
      (if (> (:bytes state) total-bytes)
        (recur (remove-first state (:group (val (first (:events state))))))
        state))))


(defn- aggregate [metrics event]
  (let [path [:actions [(:task event) (:action event)]]
        type (:event event)]
    (cond-> (or metrics {:actions {} :retries 0})
      (= type "action-start") (update-in (conj path :invocations) (fnil inc 0))
      (= type "action-completed") (update-in (conj path :completed) (fnil inc 0))
      (= type "action-failed") (update-in (conj path :failed) (fnil inc 0))
      (:durationMs event) (update-in (conj path :durationMs) (fnil + 0) (:durationMs event))
      (= type "retry") (update :retries inc))))


(defn observe! [app observation]
  (when-not @(:closed? app)
    (let [group (some-> (or (:run-id observation) (:execution-id observation))
                        str)
          event (cond-> (select-keys observation [:event :level :durationMs :status :phase])
                  (:message observation) (assoc :message (wire/clip (:message observation) 1000)))
          event (reduce (fn [e [from to]]
                          (if-let [v (get observation from)]
                            (assoc e to (data/label v))
                            e))
                        event
                        [[:run-id :runId] [:execution-id :executionId] [:task :task]
                         [:action :action] [:type :actionType] [:invocation-id :invocationId]])]
      (when group
        (swap! (:diagnostics app)
          (fn [state]
            (let [n     (inc (:sequence state))
                  event (assoc event
                          :sequence n
                          :timestamp (or (:timestamp observation) (System/currentTimeMillis)))
                  size  (wire/bytes event)
                  state (if (get-in state [:groups group])
                          state
                          (assoc-in state
                           [:groups group]
                           {:sequences clojure.lang.PersistentQueue/EMPTY
                            :bytes     0
                            :dropped   0
                            :metrics   {:actions {} :retries 0}}))]
              (-> state
                  (assoc :sequence n)
                  (assoc-in [:events n] {:group group :bytes size :event event})
                  (update :bytes + size)
                  (update-in [:groups group :bytes] + size)
                  (update-in [:groups group :sequences] conj n)
                  (assoc-in [:groups group :sequence] n)
                  (update-in [:groups group :metrics] aggregate event)
                  (trim-events group)))))
        (let [[signal _] (swap-vals! (:diagnostic-signal app) (constantly (promise)))] (deliver signal true))))))


(defn forget! [app groups]
  (let [groups (set (map str groups))]
    ;; A batch can already be publishing when run deletion removes ownership.
    (locking (:diagnostics app)
      (swap! (:diagnostics app)
        (fn [state]
          (let [events (into (sorted-map) (remove (fn [[_ entry]] (contains? groups (:group entry)))) (:events state))]
            (assoc state
              :events events
              :bytes (reduce + 0 (map :bytes (vals events)))
              :groups (apply dissoc (:groups state) groups))))))))


(defn sequence-number [app groups]
  (reduce max 0 (keep #(get-in @(:diagnostics app) [:groups (str %) :sequence]) groups)))


(defn- cursor-number [app cursor]
  (if-not cursor
    0
    (let [[instance n] (str/split cursor #":" 2)]
      (when-not (= instance (:instance-id app))
        (throw (ex-info "Event cursor belongs to another application instance. Read durable run summaries after restart."
                        {:status 409 :code "stale-cursor"})))
      (let [value (some-> n
                          parse-long)]
        (or (when (and value (<= 0 value)) value)
            (throw (ex-info "Invalid event cursor." {:status 400 :code "invalid-cursor"})))))))


(defn events [app groups {:keys [cursor task-name action-name level limit maxBytes] :or {level "info" limit 20}}]
  (let [state   @(:diagnostics app)
        groups  (keep #(get-in state [:groups (str %)]) groups)
        after   (cursor-number app cursor)
        records (->> groups
                     (mapcat :sequences)
                     sort
                     (keep #(get-in state [:events % :event]))
                     (filter #(and (> (:sequence %) after)
                                   (or (nil? task-name) (= task-name (:task %)))
                                   (or (nil? action-name) (= action-name (:action %)))
                                   (>= (get levels (:level %) 1) (get levels level 1))))
                     (take (inc limit))
                     vec)
        shown   (reduce (fn [shown event]
                          ;; Always advance past one event; the wire projection can
                          ;; bound its display if it exceeds the page allowance.
                          (if (and (< (count shown) limit)
                                   (or (empty? shown)
                                       (< (* 2 (wire/bytes (conj shown event))) (- (or maxBytes 16384) 3000))))
                            (conj shown event)
                            (reduced shown)))
                        []
                        records)
        more?   (> (count records) (count shown))
        latest  (reduce max after (keep :sequence groups))]
    {:events     shown
     :available  (boolean (seq groups))
     :dropped    (reduce + 0 (keep :dropped groups))
     :gap        (boolean (some #(> (or (:lastDroppedSequence %) 0) after) groups))
     :hasMore    more?
     :nextCursor (str (:instance-id app) ":" (if more? (or (:sequence (peek shown)) after) latest))
     :retention  "Detailed events are bounded in memory and end on restart. Delivery is asynchronous and events can be lost under load."
     :reason     (when-not (seq groups) "No detailed events retained in this application instance.")}))


(defn metrics [app groups {:keys [task-name action-name]}]
  (let [metrics (keep #(get-in @(:diagnostics app) [:groups (str %) :metrics]) groups)]
    {:available    (boolean (seq metrics))
     :retryCount   (reduce + 0 (map :retries metrics))
     :actions      (->> metrics
                        (mapcat :actions)
                        (filter (fn [[[task action] _]]
                                  (and (or (nil? task-name) (= task task-name))
                                       (or (nil? action-name) (= action action-name)))))
                        (mapv (fn [[[task action] counts]]
                                (assoc counts
                                  :task task
                                  :action action))))
     :durationUnit "milliseconds"
     :note         "Action durations are cumulative; parallel invocations can overlap. Action counts may arrive late or be incomplete under load."}))
