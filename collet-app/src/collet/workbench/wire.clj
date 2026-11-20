(ns collet.workbench.wire
  "Bounded transport projections. Source strings are read explicitly, never clipped."
  (:refer-clojure :exclude [bytes])
  (:require
   [charred.api :as json]
   [clojure.string :as str]
   [collet.workbench.data :as data])
  (:import
   [java.nio.charset StandardCharsets]))


(defn bytes [value]
  (alength (.getBytes (json/write-json-str value) StandardCharsets/UTF_8)))


(defn clip [value n]
  (let [s (str value)] (if (> (count s) n) (str (subs s 0 n) "…") s)))


(defn page
  "Page an already bounded storage query. Cursors belong to the last returned item."
  [key items {:keys [limit maxBytes] :or {limit 20 maxBytes 16384}}]
  (let [items (vec items)]
    (loop [n (min limit (count items))]
      (let [shown  (subvec items 0 n)
            more?  (< n (count items))
            result (cond-> {key (mapv #(dissoc % :cursor) shown)}
                     more? (assoc :nextCursor
                             (:cursor (peek shown)) :truncated
                             true))]
        ;; Account for the text-only rendering as well as structured content.
        (if (and (> n 1) (> (* 2 (bytes result)) (- maxBytes 512)))
          (recur (dec n))
          result)))))


(defn sample
  "Select only materialized preview data, not live pipeline values."
  [preview {:keys [limit] :or {limit 20}}]
  (when preview
    (let [value (:value preview)
          rows? (vector? value)
          value (if rows? (vec (take limit value)) value)]
      {:kind      (:type preview)
       :value     value
       :shown     (if rows? (count value) 1)
       :truncated (boolean (or (:truncated preview) (and rows? (> (count (:value preview)) limit))))
       :deferred  (boolean (:deferred preview))})))


(defn error [error]
  (let [details (data/error-details error)
        root    (last (take 8 (take-while some? (iterate ex-cause error))))
        code    (or (:code details)
                    (:collet.error/type details)
                    (case (:status details)
                      409 "conflict"
                      404 "not-found"
                      400 "invalid-request"
                      "execution-failed"))]
    {:error (cond-> {:code    (data/label code)
                     :message (clip (or (ex-message root) (ex-message error) "Execution failed.") 1500)}
              (:status details) (assoc :status (:status details))
              (:path details) (assoc :path (data/safe-value (:path details)))
              (:task details) (assoc :task (data/label (:task details)))
              (:action details) (assoc :action (data/label (:action details)))
              (= :collet.error/arrow-invalid-value (:collet.error/type details))
              (merge (cond-> (select-keys details [:expected])
                       (:actual-class details) (assoc :observed (:actual-class details))
                       (:reason details) (assoc :reason (data/label (:reason details)))))
              (:line details) (assoc :line (:line details))
              (:column details) (assoc :column (:column details))
              (:hint details) (assoc :hint (:hint details))
              (:errors details) (assoc :diagnostics (mapv #(select-keys % [:code :path :message :expected]) (take 8 (:errors details))))
              (:current details) (assoc :currentRevision
                                   (get-in details [:current :draft/revision])
                                   :hint
                                   "Read collet_get_draft, compare source, and resubmit with its current revision.")
              (:configurationRevision details) (assoc :configurationRevision (:configurationRevision details)))}))


(defn run-error [details fallback]
  (cond-> {:code    (data/label (or (:code details) fallback))
           :message (clip (:message details) 700)}
    (:task details) (assoc :task (data/label (:task details)))
    (:action details) (assoc :action (data/label (:action details)))
    (:path details) (assoc :path (data/safe-value (:path details)))
    (:expected details) (assoc :expected (:expected details))
    (:actual-class details) (assoc :observed (:actual-class details))
    (:reason details) (assoc :reason (data/label (:reason details)))
    (:hint details) (assoc :hint (:hint details))))


(defn text [value]
  (cond
    (:error value) (str (when (:id value) (str "id: " (:id value) "\nstatus: " (:status value) "\n"))
                        (when (:runId value) (str "runId: " (:runId value) "\n"))
                        (get-in value [:error :code])
                        ": "
                        (get-in value [:error :message])
                        (when-let [task (get-in value [:error :task])] (str "\nTask: " task))
                        (when-let [action (get-in value [:error :action])] (str "\nAction: " action))
                        (when-let [expected (get-in value [:error :expected])]
                          (str "\nExpected: " expected ", observed: " (get-in value [:error :observed])))
                        (when-let [hint (get-in value [:error :hint])] (str "\n" hint))
                        (when-let [ds (get-in value [:error :diagnostics])] (str "\n" (json/write-json-str ds))))
    (and (:fixtureId value) (:edn value)) (json/write-json-str value)
    (:edn value) (str (if (:version value)
                        (str "Pipeline " (:name value) " version " (:version value))
                        (str "Revision " (:revision value) " " (:url value)))
                      "\n"
                      (:edn value)
                      (when (:beforeEdn value) (str "\nPrevious source:\n" (:beforeEdn value))))
    (:text value) (str (:text value)
                       (when (:sections value) (str "\nSections: " (json/write-json-str (:sections value))))
                       (when (:nextCursor value) (str "\nMore available: read_documentation id=" (:id value) " section=" (:section value) " cursor=" (:nextCursor value))))
    ;; Detail and list reads have a compact complete fallback for text-only clients.
    (some #(contains? value %) [:sample :events :metrics :actions :documents :sections :matches :drafts :pipelines :runs :revisions :fields :errors :example])
    (json/write-json-str value)
    :else (str/join "\n" (for [[key value] value :when (some? value)] (str (name key) ": " (if (coll? value) (json/write-json-str value) value))))))


(defn result [value]
  {:content [{:type "text" :text (text value)}] :structuredContent value})


(defn- bounded-value [value nodes width]
  (let [remaining (volatile! nodes)]
    (letfn [(visit [x depth]
              (if (or (neg? (vswap! remaining dec)) (> depth 8))
                "<display limit>"
                (cond
                  (string? x) (clip x width)
                  (map? x) (into {} (map (fn [[k v]] [k (visit v (inc depth))])) (take (min 30 (max 1 (quot nodes 4))) x))
                  (vector? x) (mapv #(visit % (inc depth)) (take (min 100 (max 1 (quot nodes 4))) x))
                  :else x)))]
      (visit value 0))))


(defn fit
  "Enforce a wire budget on detail values, preserving identity and source metadata."
  [value budget]
  (loop [v     value
         nodes 200
         width 512]
    (if (<= (bytes (result v)) budget)
      v
      (let [keys [:sample :fields :events :tasks :error :errors :warnings :deferred :metrics :diagnostics
                  :configurationDiagnostics :assertions :files]
            next (reduce (fn [v k] (if (contains? v k) (update v k bounded-value nodes width) v)) v keys)
            next (cond-> next
                   (:sample next) (update :sample
                                          #(cond-> (assoc % :truncated true)
                                             (vector? (:value %)) (assoc :shown (count (:value %))))))
            next (assoc next
                   :truncated true
                   :notice "Display limit reached. Select a task/action or field, use a smaller page, or request maxBytes up to 65536.")]
        (if (or (= next v) (< nodes 4))
          (throw (ex-info "Selected response exceeds its display budget. Request a narrower selection."
                          {:code   "response-too-large"
                           :status 413
                           :hint   "Use a task/action, documentation section, or a smaller limit."}))
          (recur next (quot nodes 2) (max 64 (quot width 2))))))))
