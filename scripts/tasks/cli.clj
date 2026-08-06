(ns tasks.cli
  (:require
   [babashka.cli :as cli]
   [clojure.string :as str])
  (:import
    [clojure.lang ExceptionInfo]))


(defn usage
  [{:keys [summary usage spec order examples]}]
  (str/join
   "\n"
   (cond-> [(str "Usage: " usage)
            ""
            summary]
     (seq spec)
     (conj "" "Options:" (cli/format-opts {:spec spec :order order}))
     (seq examples)
     (conj "" "Examples:" (str/join "\n" examples)))))


(defn exit! [status message]
  (binding [*out* (if (zero? status) *out* *err*)]
    (println message))
  (System/exit status))


(defn help! [config]
  (exit! 0 (usage config)))


(defn fail! [config message]
  (exit! 1 (str message "\n\n" (usage config))))


(defn parse-opts!
  [config args]
  (try
    (let [opts (cli/parse-opts args {:spec (:spec config) :restrict true})]
      (when (:help opts)
        (help! config))
      opts)
    (catch ExceptionInfo ex
      (fail! config (ex-message ex)))))


(defn parse-args!
  [config args]
  (try
    (let [{:keys [opts args]} (cli/parse-args args {:spec (:spec config) :restrict true})]
      (when (:help opts)
        (help! config))
      {:opts opts :args args})
    (catch ExceptionInfo ex
      (fail! config (ex-message ex)))))


(defn single-arg!
  [config args arg-name]
  (let [{:keys [args]} (parse-args! config args)]
    (when-not (= 1 (count args))
      (fail! config (str "Expected exactly one " arg-name ".")))
    (let [arg (first args)]
      (when (str/blank? arg)
        (fail! config (str "Expected non-empty " arg-name ".")))
      arg)))
