(ns user
  (:require
   [clj-reload.core :as reload]
   [clojure.java.io :as io]
   [clojure.string :as str]))


(def reload-dirs
  (->> (.listFiles (io/file "."))
       (filter #(.isDirectory %))
       (filter #(str/starts-with? (.getName %) "collet-"))
       (map #(io/file % "src"))
       (filter #(.isDirectory %))
       (map #(.getPath %))
       sort
       vec
       (#(conj % "test-fixtures/src"))))


(reload/init
 {:dirs      reload-dirs
  :no-reload '#{user}})


(defn reload
  []
  (reload/reload))
