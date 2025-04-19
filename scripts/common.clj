(ns common
  (:require
   [clojure.string :as string]))


(def re-project-name-version
  #"(\(defproject\s+)(\S+)(\s+\")([\d|\.]+)([^\"]*)([\s\S]*)")


(defn get-project-version [project]
  (let [m       (->> (slurp (str project "/project.clj"))
                     (re-matches re-project-name-version))
        version (nth m 4)]
    version))


(defn bump-version [file lib next-version]
  (let [project-text     (slurp file)
        new-project-text (string/replace
                          project-text
                          (re-pattern (str "(\\[io.velio/" lib "\\s+\\\")([\\d|\\.]+)([^\\\"]*)"))
                          (str "$1" next-version))]
    (spit file new-project-text)))
