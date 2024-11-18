(ns pod.collet.core
  (:gen-class)
  (:refer-clojure :exclude [read-string])
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.walk :as walk]
   [bencode.core :as bencode]
   [collet.main :as collet])
  (:import
   [java.io EOFException]))


(defn read-string [^"[B" v]
  (String. v))


(def debug? false)


(defn debug [& strs]
  (when debug?
    (binding [*out* (io/writer System/err)]
      (apply println strs))))


(def lookup
  {'pod.collet.core/compile collet/file-or-map})


(def describe-map
  (walk/postwalk
   (fn [v]
     (if (ident? v) (name v) v))
   `{:format     :edn
     :namespaces [{:name pod.collet.core
                   :vars [{:name compile}]}]
     :opts       {:shutdown {}}}))


(debug describe-map)


(defn -main [& _args]
  (loop []
    (let [message (try (bencode/read)
                       (catch EOFException _
                         ::EOF))]
      (when-not (identical? ::EOF message)
        (let [op (get message "op")
              op (read-string op)
              op (keyword op)
              id (some-> (get message "id")
                         read-string)
              id (or id "unknown")]
          (case op
            :describe (do (bencode/write describe-map)
                          (recur))

            :invoke (do (try
                          (let [var  (-> (get message "var")
                                         read-string
                                         symbol)
                                args (get message "args")
                                args (read-string args)
                                args (edn/read-string args)]
                            (if-let [f (lookup var)]
                              (let [value (binding [*print-meta* true]
                                            (let [result (apply f args)]
                                              (pr-str result)))
                                    reply {"value"  value
                                           "id"     id
                                           "status" ["done"]}]
                                (bencode/write reply))
                              (throw (ex-info (str "Var not found: " var) {}))))
                          (catch Throwable e
                            (debug e)
                            (let [reply {"ex-message" (ex-message e)
                                         "ex-data"    (pr-str
                                                       (assoc (ex-data e)
                                                         :type (class e)))
                                         "id"         id
                                         "status"     ["done" "error"]}]
                              (bencode/write reply))))
                        (recur))

            :shutdown (System/exit 0)

            (do
              (let [reply {"ex-message" "Unknown op"
                           "ex-data"    (pr-str {:op op})
                           "id"         id
                           "status"     ["done" "error"]}]
                (bencode/write reply))
              (recur))))))))
