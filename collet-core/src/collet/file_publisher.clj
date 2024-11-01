(ns collet.file-publisher
  (:require
   [clojure.walk :as w]
   [clojure.string :as str]
   [clojure.java.io :as io]
   [com.brunobonacci.mulog.publisher]
   [com.brunobonacci.mulog.buffer :as rb]
   [com.brunobonacci.mulog.common.json :as json]
   [com.brunobonacci.mulog.utils :as ut])
  (:import
   [com.brunobonacci.mulog.publisher PPublisher]
   [java.time LocalDate]
   [java.time.format DateTimeFormatter]
   [java.util Date UUID]))


(defn snake-case
  [n]
  (when n
    (-> (str n)
        (str/replace #"^:" "")
        (str/replace #"/" ".")
        (str/replace #"[^\w\d_.]" "_"))))


(defn snake-case-mangle
  [[k v]]
  [(if (= :mulog/timestamp k) k (snake-case k)) v])


(defn type-mangle
  "takes a clojure map and turns into a new map where attributes have a
  type-suffix to avoid type clash in ELS"
  [[k v :as e]]
  (cond
    (= :mulog/timestamp k) e
    (int? v) [(str k ".i") v]
    (string? v) [(str k ".s") v]
    (instance? UUID v) [(str k ".s") (str v)]
    (double? v) [(str k ".f") v]
    (float? v) [(str k ".f") v]
    (map? v) [(str k ".o") v]
    (sequential? v) [(str k ".a") v]
    (set? v) [(str k ".a") v]
    (boolean? v) [(str k ".b") v]
    (keyword? v) [(str k ".k") v]
    (instance? Date v) [(str k ".t") v]
    (instance? LocalDate v) [(str k ".t") v]
    (instance? Exception v) [(str k ".x") (ut/exception-stacktrace v)]
    :else e))


(def mangle
  (comp type-mangle snake-case-mangle))


(defn mangle-map [m]
  (w/postwalk
   (fn [i]
     (cond->> i
       (map? i) (into {} (map mangle))))
   m))


(defn date-string
  ([]
   (.format (LocalDate/now) (DateTimeFormatter/ofPattern "yyyy-MM-dd")))
  ([^LocalDate date]
   (.format date (DateTimeFormatter/ofPattern "yyyy-MM-dd"))))


(defn append-logs [buffer transform file-path]
  (doseq [item (transform (map second (rb/items buffer)))]
    (let [^String json-item (-> item mangle-map json/to-json (str \newline))]
      (spit file-path json-item :append true))))


(deftype FilePublisher [config buffer transform]
  PPublisher
  (agent-buffer [_]
    buffer)

  (publish-delay [_]
    500)

  (publish [_ buffer]
    (if-some [file-prefix (:file-prefix config)]
      ;; filename has pattern inside (replace * with current date)
      (let [current-date (date-string)
            file-path    (str file-prefix current-date ".log")]
        ;; when the file doesn't exist, we're starting new day
        ;; in order to save disk space we're dropping logs from before yesterday
        (when-not (.exists (io/file file-path))
          (let [before-yesterday (-> (.minusDays (LocalDate/now) 2)
                                     (date-string))
                file-to-drop     (str file-prefix before-yesterday ".log")]
            (when (.exists (io/file file-to-drop))
              (io/delete-file file-to-drop))))
        (append-logs buffer transform file-path))
      ;; single log file
      (append-logs buffer transform (:filename config)))
    (rb/clear buffer)))


(defn file-publisher [{:keys [filename transform]}]
  {:pre [filename]}
  (let [pattern? (str/includes? filename "*")
        config   (cond-> {:filename filename}
                   pattern? (assoc :file-prefix (re-find #"[^\*]+" filename)))
        log-file (io/file filename)]
    ;; create parent folders
    (when-not (.exists log-file)
      (io/make-parents log-file))

    (FilePublisher.
     config
     (rb/agent-buffer 10000)
     (or transform identity))))